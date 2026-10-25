package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

// =======================================================================================
// Calendar app widgets (iOS 26 Calendar ▸ Up Next small/medium/large, Calendar month, List).
// The date block was measured on Apple's iPhone Duo Home Screen (hig/designing-for-iphone-hero-
// inside.png, "THURSDAY 3 No Events Today", 176 px = 158 pt); the event boxes and the large
// timelines on Apple's HIG Calendar widget images (reference/widgets/review: small, medium light /
// dark, large), measured as fractions of the card and converted to its iPhone points.
// Events come from CalendarContract with READ_CALENDAR, which is requested only when the user
// taps the widget. Sample events appear ONLY in gallery previews (Apple's HIG agenda at 1:10 PM).
// Ids: -108 / -109 are the Core.17 calendar (kept), new ones come from -140 … -159.
//
// Round 21 (the owner's iPhone Duo in Chinese, evidence/r21/ref/1.webp and 4.webp): in a Chinese
// locale Up Next small / medium draw iOS's Chinese date block — "25 │ 十六 │ 周日", lunar day and
// short weekday stacked beside the date — and, when nothing is coming up, the medium shows the
// month on its right half (red month title, Sunday-first header with grey weekends, today in a red
// disc, adjusted working days underlined; see [chinaAdjustedWorkday]).
// =======================================================================================

/**
 * Measured on Apple's card (158 pt): glyphs start 17.5–18 pt in (16 pt HIG margin plus the
 * Duo's slightly larger inset; the Weather card beside it agrees), weekday caps 7.9 pt tall on a
 * 27.5 pt baseline, date figures 24.5 pt tall on a 60 pt baseline.
 */
private const val CAL_MARGIN = 17f
private const val CAL_WEEKDAY_BASELINE = 27.5f
private const val CAL_DATE_BASELINE = 60f

private const val UP_NEXT_ZH = "查看今天接下来的日程，以及之后的安排。"
private const val UP_NEXT_EN = "See your upcoming events for today and what’s next."

internal fun calendarWidgetSpecs(): List<IosWidgetSpec> = listOf(
    IosWidgetSpec(-108, NativeWidgetKind.CALENDAR, "upnext", NativeWidgetSize.SMALL, "即将开始", "Up Next",
        UP_NEXT_ZH, UP_NEXT_EN, featured = 20, key = "native:calendar:small") { UpNextWidget(this) },
    IosWidgetSpec(-109, NativeWidgetKind.CALENDAR, "upnext", NativeWidgetSize.MEDIUM, "即将开始", "Up Next",
        UP_NEXT_ZH, UP_NEXT_EN, featured = 66, key = "native:calendar:medium") { UpNextWidget(this) },
    IosWidgetSpec(-140, NativeWidgetKind.CALENDAR, "upnext", NativeWidgetSize.LARGE, "即将开始", "Up Next",
        UP_NEXT_ZH, UP_NEXT_EN) { UpNextWidget(this) },
    IosWidgetSpec(-141, NativeWidgetKind.CALENDAR, "month", NativeWidgetSize.SMALL, "日历", "Calendar",
        "查看本月日历。", "View your calendar for the month.", featured = 62,
        opens = { calendarAppIntent() }) { MonthWidget(this) },
    IosWidgetSpec(-142, NativeWidgetKind.CALENDAR, "list", NativeWidgetSize.MEDIUM, "列表", "List",
        "按时间顺序查看接下来的日程。", "See a list of your upcoming events.") { ListWidget(this) },
)

internal fun calendarAppIntent(): Intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)

// Model + pure helpers (unit-tested) -----------------------------------------------------

internal data class CalendarEvent(
    val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int, val location: String? = null,
)

/** All-day instances are stored at UTC midnight; timed ones at their instant. */
internal fun CalendarEvent.startDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

internal fun CalendarEvent.endDate(zone: ZoneId): LocalDate {
    // End is exclusive: an all-day event ending at the next UTC midnight covers only its start day.
    val end = Instant.ofEpochMilli((end - 1).coerceAtLeast(begin)).atZone(if (allDay) ZoneOffset.UTC else zone)
    return end.toLocalDate()
}

internal fun CalendarEvent.occursOn(date: LocalDate, zone: ZoneId): Boolean =
    !date.isBefore(startDate(zone)) && !date.isAfter(endDate(zone))

/** What "Up Next" shows for one day: events not yet over (all-day ones stay all day). */
internal data class UpNextDay(val date: LocalDate, val events: List<CalendarEvent>, val hadEarlier: Boolean)

internal fun upNextDay(events: List<CalendarEvent>, date: LocalDate, now: ZonedDateTime): UpNextDay {
    val on = events.filter { it.occursOn(date, now.zone) }
        .sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { it.begin }))
    val nowMillis = now.toInstant().toEpochMilli()
    val pending = on.filter { it.allDay || it.end > nowMillis }
    return UpNextDay(date, pending, hadEarlier = pending.size < on.size)
}

/** The first day after [today] (within [horizonDays]) that has events; tomorrow when none does. */
internal fun nextEventDay(events: List<CalendarEvent>, today: LocalDate, zone: ZoneId, horizonDays: Int = 7): UpNextDay {
    for (offset in 1..horizonDays) {
        val date = today.plusDays(offset.toLong())
        val on = events.filter { it.occursOn(date, zone) }.sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { it.begin }))
        if (on.isNotEmpty()) return UpNextDay(date, on, hadEarlier = false)
    }
    return UpNextDay(today.plusDays(1), emptyList(), hadEarlier = false)
}

/**
 * List widget rows: today's pending events, then each later day's events on the day they start
 * (a multi-day event is listed once), within [days], at most [limit] rows.
 */
internal fun listAgenda(events: List<CalendarEvent>, now: ZonedDateTime, days: Int = 7, limit: Int = 6): List<Pair<LocalDate, CalendarEvent>> {
    val today = now.toLocalDate()
    val todays = upNextDay(events, today, now).events.map { today to it }
    val later = (1..days).flatMap { offset ->
        val date = today.plusDays(offset.toLong())
        events.filter { it.startDate(now.zone) == date }.sortedWith(compareBy<CalendarEvent>({ !it.allDay }, { it.begin })).map { date to it }
    }
    return (todays + later).distinctBy { it.second }.take(limit)
}

/** iOS: "No Events Today" when the day is empty, "No more events today" once they are all over. */
internal fun noEventsMessage(hadEarlier: Boolean): String =
    if (hadEarlier) uxText("今天没有其他日程", "No more events today") else uxText("今天无日程", "No Events Today")

/** Section title for a day after today: 明天 / TOMORROW, else "10月23日 星期五" / "FRIDAY, OCT 23". */
internal fun dayHeading(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String = when {
    date == today -> uxText("今天", "TODAY")
    date == today.plusDays(1) -> uxText("明天", "TOMORROW")
    locale.language == "zh" -> date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", locale))
    else -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d", locale)).uppercase(locale)
}

/** Weekday line of the date block: 星期二 / TUESDAY (iOS prints it uppercase in red). */
internal fun weekdayHeading(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).let { if (locale.language == "zh") it else it.uppercase(locale) }

/**
 * Apple's compact event time: "4:00–5:00PM", "11:30AM–1:00PM" (the marker drawn in small caps by
 * [smallCapsMeridiem]), 24-hour "16:00–17:00", Chinese "下午4:00–5:00" / "上午11:30–下午1:00", or 全天 / All-day.
 */
internal fun eventTimeLabel(event: CalendarEvent, zone: ZoneId, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
    if (event.allDay) return uxText("全天", "All-day")
    val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
    val end = Instant.ofEpochMilli(event.end).atZone(zone)
    if (use24h) return "${begin.format(DateTimeFormatter.ofPattern("HH:mm"))}–${end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    val zh = locale.language == "zh"
    fun marker(t: ZonedDateTime) = if (zh) (if (t.hour < 12) "上午" else "下午") else (if (t.hour < 12) "AM" else "PM")
    val h = DateTimeFormatter.ofPattern("h:mm")
    val same = marker(begin) == marker(end)
    return if (zh) "${marker(begin)}${begin.format(h)}–${if (same) "" else marker(end)}${end.format(h)}"
    else "${begin.format(h)}${if (same) "" else marker(begin)}–${end.format(h)}${marker(end)}"
}

/** The start time inside a large-widget timeline box: "4:00PM", "下午4:00", "16:00", or 全天 / All-day. */
internal fun eventStartCompact(event: CalendarEvent, zone: ZoneId, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
    if (event.allDay) return uxText("全天", "All-day")
    val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
    return clockTimeParts(begin, use24h, locale).let { (t, m) ->
        when { m == null -> begin.format(DateTimeFormatter.ofPattern("HH:mm")); locale.language == "zh" -> "$m$t"; else -> "$t$m" }
    }
}

/** Start time only (List widget's time column): "10:00" / "10:00 AM" / 全天. */
internal fun eventStartLabel(event: CalendarEvent, zone: ZoneId, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
    if (event.allDay) return uxText("全天", "All-day")
    val begin = Instant.ofEpochMilli(event.begin).atZone(zone)
    return clockTimeParts(begin, use24h, locale).let { (t, m) -> if (m == null) t else if (locale.language == "zh") "$m$t" else "$t $m" }
}

/** Month grid: [weeks] × 7 cells starting on [firstDay]; null outside the month (iOS leaves them blank). */
internal fun monthGrid(date: LocalDate, firstDay: DayOfWeek): List<List<LocalDate?>> {
    val first = date.withDayOfMonth(1)
    val lead = ((first.dayOfWeek.value - firstDay.value) + 7) % 7
    val cells = lead + date.lengthOfMonth()
    val weeks = (cells + 6) / 7
    return List(weeks) { week ->
        List(7) { col ->
            val day = week * 7 + col - lead + 1
            if (day in 1..date.lengthOfMonth()) first.withDayOfMonth(day) else null
        }
    }
}

internal fun isWeekend(day: DayOfWeek): Boolean = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY

/** "周日": the short Chinese weekday iOS stacks (周 over 日) in the widgets' Chinese date block. */
internal fun chineseShortWeekday(day: DayOfWeek): String = "周" + "一二三四五六日"[day.value - 1]

/**
 * Mainland China's adjusted working days (调休上班): weekend days that are working days because a
 * holiday was moved, from the State Council General Office's annual holiday notices (国务院办公厅关于
 * 2025年 / 2026年部分节假日安排的通知). The owner's iPhone underlines one in red in the Calendar widget's
 * month (10 Oct 2026, the Saturday worked for the National Day week) and marks none of the holidays
 * themselves. Only published years are listed; later years show no marks rather than guesses.
 */
private val CHINA_ADJUSTED_WORKDAYS: Set<LocalDate> = listOf(
    // 2025: Spring Festival, Labour Day, National Day + Mid-Autumn.
    "2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11",
    // 2026: New Year, Spring Festival, Labour Day, National Day.
    "2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10",
).mapTo(HashSet(), LocalDate::parse)

/** True for an adjusted working day (see [CHINA_ADJUSTED_WORKDAYS]) when the user's region is mainland China. */
internal fun chinaAdjustedWorkday(date: LocalDate, locale: Locale = Locale.getDefault()): Boolean =
    locale.language == "zh" && (locale.country == "CN" || locale.country.isEmpty()) && date in CHINA_ADJUSTED_WORKDAYS

/**
 * Rows of the medium widget's month (pt, measured on 1.webp: five weeks centred 58.45 … 132.2 pt,
 * 18.45 pt apart); a six-week month tightens the pitch so its last row still ends above 140.5 pt.
 */
internal data class MiniMonthRows(val firstCenter: Float, val pitch: Float)

internal fun miniMonthRows(weeks: Int): MiniMonthRows {
    val first = 58.45f
    val pitch = if (weeks <= 1) 18.45f else minOf(18.45f, (140.5f - first) / (weeks - 1))
    return MiniMonthRows(first, pitch)
}

/**
 * Gallery-only sample agenda: Apple's own HIG Calendar widget agenda (Yoga Class 4–5 PM at Golden
 * Gate Park, Dinner with Ravi 6–7:30 PM, tomorrow Pick up coffee 8:30–9 AM; purple calendar), shown
 * at Apple's 1:10 PM so previews compare 1:1 with the HIG images. Never used for a placed widget.
 */
internal val SAMPLE_CALENDAR_NOW: java.time.LocalTime = java.time.LocalTime.of(13, 10)

internal fun sampleCalendarEvents(today: LocalDate, zone: ZoneId): List<CalendarEvent> {
    fun at(day: Long, h: Int, m: Int) = today.plusDays(day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    val purple = 0xFFCC73E1.toInt() // iOS Calendar's "Purple" calendar colour
    return listOf(
        CalendarEvent(uxText("瑜伽课", "Yoga Class"), at(0, 16, 0), at(0, 17, 0), false, purple, uxText("金门公园", "Golden Gate Park")),
        CalendarEvent(uxText("和 Ravi 吃晚饭", "Dinner with Ravi"), at(0, 18, 0), at(0, 19, 30), false, purple, uxText("餐厅", "Restaurant Name")),
        CalendarEvent(uxText("买咖啡", "Pick up coffee"), at(1, 8, 30), at(1, 9, 0), false, purple, uxText("咖啡店", "Coffee Shop")),
    )
}

// Data ------------------------------------------------------------------------------------

private fun queryEvents(context: Context, from: ZonedDateTime, days: Int): List<CalendarEvent>? {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val start = from.toLocalDate().atStartOfDay(from.zone).toInstant().toEpochMilli()
    val end = from.toLocalDate().plusDays(days.toLong()).atStartOfDay(from.zone).toInstant().toEpochMilli()
    return runCatching {
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION)
        CalendarContract.Instances.query(context.contentResolver, projection, start, end)?.use { cursor ->
            val events = mutableListOf<CalendarEvent>()
            while (cursor.moveToNext() && events.size < 80) {
                events += CalendarEvent(cursor.getString(0).orEmpty().ifBlank { uxText("（无标题）", "(No title)") },
                    cursor.getLong(1), cursor.getLong(2), cursor.getInt(3) == 1, cursor.getInt(4),
                    cursor.getString(5)?.takeIf { it.isNotBlank() })
            }
            events.sortedBy { it.begin }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

/** Events from the start of today for a week; null = no calendar access. Re-read every minute. */
@Composable
private fun rememberWeekEvents(now: ZonedDateTime, revision: Int): List<CalendarEvent>? {
    val context = LocalContext.current
    val events = produceState<List<CalendarEvent>?>(null, context, now.toLocalDate(), now.hour, now.minute, revision) {
        value = withContext(Dispatchers.IO) { queryEvents(context, now, 8) }
    }
    return events.value
}

/**
 * Tap handler that asks for READ_CALENDAR (the widget's only data source). After the user has
 * refused for good, Android no longer shows the prompt, so the tap opens 18Duo's app settings.
 */
@Composable
internal fun rememberCalendarPermissionRequest(enabled: Boolean, onGranted: () -> Unit): (() -> Unit)? {
    if (!enabled || androidx.activity.compose.LocalActivityResultRegistryOwner.current == null) return null
    val activity = androidx.activity.compose.LocalActivity.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val currentGranted by rememberUpdatedState(onGranted)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        asked = true
        if (granted) currentGranted()
    }
    return {
        val permission = android.Manifest.permission.READ_CALENDAR
        if (asked && activity != null && !activity.shouldShowRequestPermissionRationale(permission)) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", activity.packageName, null)))
            }
        } else launcher.launch(permission)
    }
}

/** What an event widget draws: the agenda (null = no access yet) and the "now" it is computed at. */
private class CalendarContent(val events: List<CalendarEvent>?, val now: ZonedDateTime, val use24h: Boolean, val tap: Modifier)

/** Live events, the access request on tap, and gallery samples; shared by Up Next and List. */
@Composable
private fun rememberCalendarContent(scope: IosWidgetContext): CalendarContent {
    val minute = rememberMinuteTime()
    var revision by remember { mutableIntStateOf(0) }
    val live = rememberWeekEvents(minute, revision)
    val context = LocalContext.current
    val use24h = remember(context, minute.hour) { android.text.format.DateFormat.is24HourFormat(context) }
    val requestAccess = rememberCalendarPermissionRequest(scope.interactive && live == null) { revision++ }
    val openApp = rememberWidgetAppOpener()
    val tap = scope.bareModifier.widgetTap(scope.interactive) {
        if (live == null) requestAccess?.invoke() else openApp(calendarAppIntent())
    }
    if (scope.preview && live.isNullOrEmpty()) {
        val pinned = minute.toLocalDate().atTime(SAMPLE_CALENDAR_NOW).atZone(minute.zone)
        return CalendarContent(sampleCalendarEvents(minute.toLocalDate(), minute.zone), pinned, use24h, tap)
    }
    return CalendarContent(live, minute, use24h, tap)
}

// Pieces ----------------------------------------------------------------------------------

private fun eventColor(event: CalendarEvent, colors: WidgetColors): Color =
    if (colors.clear) Color.White else Color(event.color or 0xFF000000.toInt())

/** Weekday (red) over the big date, at Apple's measured baselines. */
@Composable
private fun DateBlock(date: LocalDate, s: WidgetScale) {
    val colors = LocalWidgetColors.current
    Box {
        WidgetText(weekdayHeading(date), s, 11f, Modifier.paddingFromBaseline(top = s.pt(CAL_WEEKDAY_BASELINE)), weight = FontWeight.SemiBold,
            color = colors.red, tracking = .01f)
        WidgetText("${date.dayOfMonth}", s, 33f, Modifier.paddingFromBaseline(top = s.pt(CAL_DATE_BASELINE)), lineHeight = 38f, tracking = -.01f)
    }
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue

/** Vertical list that drops whole rows that do not fit (iOS never clips an event in half). */
@Composable
private fun FittingColumn(spacing: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = constraints.copy(minHeight = 0, minWidth = 0)
        val placeables = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var used = 0
        for (m in measurables) {
            val p = m.measure(loose)
            val next = used + (if (placeables.isEmpty()) 0 else gap) + p.height
            if (next > constraints.maxHeight) break
            placeables += p; used = next
        }
        layout(constraints.maxWidth, if (constraints.hasBoundedHeight) constraints.maxHeight else used) {
            var y = 0
            placeables.forEach { it.placeRelative(0, y); y += it.height + gap }
        }
    }
}

@Composable
private fun NoAccessText(s: WidgetScale, size: Float = 13f) {
    WidgetText(uxText("轻点以显示日程", "Tap to show events"), s, size, color = LocalWidgetColors.current.secondary,
        maxLines = 3, lineHeight = size * 1.25f)
}

// Up Next layout (pure, unit-tested) -------------------------------------------------------

/**
 * Apple's Up Next event box, measured on the HIG Calendar widgets (in the card's iPhone points):
 * 11.8 pt lines with the first baseline 10.8 pt in, 3 pt of padding in all, 4.4 pt between boxes
 * (small: 70.1–108.3 and 112.7–139.3 pt; medium right column 16.7–55.1, TOMORROW on 73.1, 79.9–118.3).
 */
internal const val UP_NEXT_LINE = 11.8f
internal const val UP_NEXT_FIRST_BASELINE = 10.8f
internal const val UP_NEXT_BOX_PADDING = 3f
internal const val UP_NEXT_BOX_GAP = 4.4f
/** A day header ("TOMORROW"): baseline 10.8 pt below its top, the next box 6.8 pt below that; 7.2 pt after a box. */
internal const val UP_NEXT_HEADER_HEIGHT = 17.6f
internal const val UP_NEXT_HEADER_GAP = 7.2f
/** Top of the first event under the date block, and the lowest point any box may reach. */
internal const val UP_NEXT_EVENTS_TOP = 70f
internal const val UP_NEXT_BOTTOM = 144f
/** The medium widget's right column starts at the top of the card. */
internal const val UP_NEXT_RIGHT_TOP = 16.7f

internal fun upNextBoxHeight(lines: Int) = lines * UP_NEXT_LINE + UP_NEXT_BOX_PADDING

/** One event box: the title always, then the location and the time while they fit (iOS drops the location first). */
internal data class UpNextBox(val event: CalendarEvent, val location: Boolean, val time: Boolean) {
    val lines: Int get() = 1 + (if (location) 1 else 0) + (if (time) 1 else 0)
    val height: Float get() = upNextBoxHeight(lines)
}

internal sealed interface UpNextItem { val top: Float }
internal data class UpNextEventItem(override val top: Float, val box: UpNextBox) : UpNextItem
internal data class UpNextHeaderItem(override val top: Float, val date: LocalDate) : UpNextItem
/** "No events" under a day header with nothing on it. */
internal data class UpNextNoteItem(override val top: Float) : UpNextItem

private fun boxOptions(event: CalendarEvent): List<UpNextBox> = listOfNotNull(
    UpNextBox(event, location = true, time = true).takeIf { !event.location.isNullOrBlank() },
    UpNextBox(event, location = false, time = true), UpNextBox(event, location = false, time = false))

/**
 * Stacks one Up Next column between [top] and [bottom] (pt): [today]'s events, then — when [later]
 * is given — that day's header and events. Each box keeps as many lines as fit; the first event
 * that cannot fit even as a title ends the column, and a header never appears without a line under it.
 */
internal fun planUpNextColumn(top: Float, bottom: Float, today: List<CalendarEvent>, later: UpNextDay? = null): List<UpNextItem> {
    val items = mutableListOf<UpNextItem>()
    var y = top
    fun place(event: CalendarEvent, gap: Float): Boolean {
        val box = boxOptions(event).firstOrNull { y + gap + it.height <= bottom + .01f } ?: return false
        items += UpNextEventItem(y + gap, box)
        y += gap + box.height
        return true
    }
    for (event in today) if (!place(event, if (items.isEmpty()) 0f else UP_NEXT_BOX_GAP)) return items
    if (later == null) return items
    val headerTop = if (items.isEmpty()) y else y + UP_NEXT_HEADER_GAP
    val firstTop = headerTop + UP_NEXT_HEADER_HEIGHT
    if (firstTop + (if (later.events.isEmpty()) UP_NEXT_LINE else upNextBoxHeight(1)) > bottom + .01f) return items
    items += UpNextHeaderItem(headerTop, later.date)
    y = firstTop
    if (later.events.isEmpty()) { items += UpNextNoteItem(firstTop); return items }
    later.events.forEachIndexed { i, event -> if (!place(event, if (i == 0) 0f else UP_NEXT_BOX_GAP)) return items }
    return items
}

/** The hours one large-widget timeline shows: [startHour] … [startHour] + [hours] (each gets a label). */
internal data class TimelineWindow(val startHour: Int, val hours: Int)

/**
 * Apple's large Up Next (HIG): today's timeline starts at the current hour, the other day an hour
 * before its first event (8 AM when it has none); each runs [minHours] (4 / 7: "1 … 5" and
 * "7 … 2" in Apple's image), stretched only as far as needed to reach the end of the NEXT event
 * — Apple's 1–5 PM window shows Yoga, not the 6 PM dinner. Never more than [maxHours], never past midnight.
 */
internal fun timelineWindow(events: List<CalendarEvent>, date: LocalDate, now: ZonedDateTime, minHours: Int, maxHours: Int = 12): TimelineWindow {
    val zone = now.zone
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val today = date == now.toLocalDate()
    val nowMillis = now.toInstant().toEpochMilli()
    val timed = events.filter { !it.allDay && it.occursOn(date, zone) && (!today || it.end > nowMillis) }
    fun hourOf(millis: Long) = ((millis - dayStart) / 3_600_000.0).coerceIn(0.0, 24.0)
    val start = if (today) now.hour
        else timed.minOfOrNull { kotlin.math.floor(hourOf(it.begin)).toInt() }?.let { it - 1 } ?: 8
    val nextEnd = timed.minByOrNull { it.begin }?.let { kotlin.math.ceil(hourOf(it.end)).toInt() } ?: 0
    val hours = maxOf(minHours, nextEnd - start.coerceAtLeast(0)).coerceIn(1, maxHours)
    return TimelineWindow(start.coerceIn(0, 24 - hours), hours)
}

/** Hour labels of the large widget: bare numbers, as Apple prints them ("1 2 3", "7 8 9 … 12 1 2"; 24-hour "13"). */
internal fun timelineHourLabel(hour: Int, use24h: Boolean): String {
    val h = Math.floorMod(hour, 24)
    return if (use24h) "$h" else "${if (h % 12 == 0) 12 else h % 12}"
}

/** Side-by-side lane of each event (sorted by start): overlapping events share the width. */
internal fun timelineLanes(events: List<CalendarEvent>): List<Int> {
    val laneEnds = mutableListOf<Long>()
    return events.map { event ->
        val free = laneEnds.indexOfFirst { it <= event.begin }
        if (free >= 0) { laneEnds[free] = event.end; free } else { laneEnds += event.end; laneEnds.lastIndex }
    }
}

/** Parts of the line [from]…[to] left after cutting out [cuts] (event boxes: Apple's hour lines never cross them). */
internal fun lineSegments(from: Float, to: Float, cuts: List<ClosedFloatingPointRange<Float>>): List<Pair<Float, Float>> {
    var segments = listOf(from to to)
    for (cut in cuts.sortedBy { it.start }) segments = segments.flatMap { (a, b) ->
        if (cut.endInclusive <= a || cut.start >= b) listOf(a to b)
        else listOfNotNull((a to cut.start).takeIf { cut.start > a }, (cut.endInclusive to b).takeIf { cut.endInclusive < b })
    }
    return segments
}

// Event boxes ------------------------------------------------------------------------------

/** Colours of one event: tinted box, bar, darkened title and detail lines (sampled on the HIG light / dark images). */
private data class EventPalette(val fill: Color, val bar: Color, val title: Color, val detail: Color)

private fun eventPalette(event: CalendarEvent, colors: WidgetColors): EventPalette {
    if (colors.clear) return EventPalette(Color.White.copy(alpha = .16f), Color.White, Color.White, Color.White.copy(alpha = .8f))
    val tint = eventColor(event, colors)
    // HIG light (purple #CC73E1 on #F8F8F8): fill (241,219,242), title (73,29,78), detail (106,49,114).
    // HIG dark: fill (72,21,78), title ≈ (190,90,200), detail ≈ (150,60,165).
    return if (colors.background.luminanceValue() > .5f) EventPalette(
        // Opaque (pre-blended with the card), so a large widget's hour lines stay hidden beneath.
        fill = lerp(colors.background, tint, .22f), bar = tint,
        title = deepened(tint, .36f), detail = deepened(tint, .52f))
    else lerp(Color.Black, tint, .36f).let { fill ->
        // Android calendars come in saturated, dark colours (pure blue, deep purple) that iOS never
        // uses; their text is lifted toward white until it reads like Apple's bright dark-mode hues.
        val readable = lerp(tint, Color.White, (.55f - tint.luminanceValue()).coerceIn(0f, .5f))
        EventPalette(fill = fill, bar = tint, title = lerp(fill, readable, .92f), detail = lerp(fill, readable, .66f))
    }
}

/**
 * Apple darkens an event's text by deepening its calendar colour, not by greying it: saturation
 * pushed 1.3× away from grey, then the brightness scaled by [brightness] (#CC73E1 → title
 * (74, 36, 83) / detail (108, 53, 121) against the HIG's (73, 29, 78) / (106, 49, 114)).
 */
private fun deepened(color: Color, brightness: Float): Color {
    val grey = (color.red + color.green + color.blue) / 3f
    fun channel(c: Float) = ((grey + 1.3f * (c - grey)).coerceIn(0f, 1f) * brightness)
    return Color(channel(color.red), channel(color.green), channel(color.blue))
}

/** Apple's event box: tinted rounded box, the calendar bar inside, title / location / time in the calendar's colour. */
@Composable
private fun EventBox(event: CalendarEvent, lines: List<androidx.compose.ui.text.AnnotatedString>, s: WidgetScale, width: Float,
    height: Float, modifier: Modifier = Modifier) {
    val palette = eventPalette(event, LocalWidgetColors.current)
    Box(modifier.size(s.pt(width), s.pt(height)).background(palette.fill, RoundedCornerShape(s.pt(4f)))) {
        Box(Modifier.offset(s.pt(2.6f), s.pt(2.6f)).size(s.pt(2.3f), s.pt((height - 5.2f).coerceAtLeast(2f)))
            .background(palette.bar, RoundedCornerShape(50)))
        lines.forEachIndexed { i, line ->
            WidgetText(line, s, 10.6f, Modifier.padding(start = s.pt(8.2f), end = s.pt(4f))
                .paddingFromBaseline(top = s.pt(UP_NEXT_FIRST_BASELINE + i * UP_NEXT_LINE)),
                weight = if (i == 0) FontWeight.SemiBold else FontWeight.Medium,
                color = if (i == 0) palette.title else palette.detail, lineHeight = UP_NEXT_LINE, tracking = 0f)
        }
    }
}

private fun upNextLines(box: UpNextBox, content: CalendarContent): List<androidx.compose.ui.text.AnnotatedString> = buildList {
    add(androidx.compose.ui.text.AnnotatedString(box.event.title))
    if (box.location) add(androidx.compose.ui.text.AnnotatedString(box.event.location.orEmpty()))
    if (box.time) add(smallCapsMeridiem(eventTimeLabel(box.event, content.now.zone, content.use24h)))
}

/** One planned Up Next column at [left] (pt), [width] wide. */
@Composable
private fun BoxScope.UpNextColumn(items: List<UpNextItem>, content: CalendarContent, s: WidgetScale, left: Float, width: Float) {
    val colors = LocalWidgetColors.current
    val today = content.now.toLocalDate()
    items.forEach { item ->
        when (item) {
            is UpNextEventItem -> EventBox(item.box.event, upNextLines(item.box, content), s, width, item.box.height,
                Modifier.align(Alignment.TopStart).offset(s.pt(left), s.pt(item.top)))
            is UpNextHeaderItem -> WidgetText(dayHeading(item.date, today), s, 11f, Modifier.align(Alignment.TopStart)
                .offset(s.pt(left)).paddingFromBaseline(top = s.pt(item.top + UP_NEXT_FIRST_BASELINE)).width(s.pt(width)),
                weight = FontWeight.SemiBold, color = colors.secondary, tracking = .01f)
            is UpNextNoteItem -> WidgetText(uxText("没有日程", "No Events"), s, 11f, Modifier.align(Alignment.TopStart)
                .offset(s.pt(left)).paddingFromBaseline(top = s.pt(item.top + UP_NEXT_FIRST_BASELINE)), color = colors.secondary)
        }
    }
}

/**
 * Below the date block when there is nothing to list: Apple's "No Events Today", or the access
 * prompt, centred on [center] pt (HIG English 105; the owner's Chinese iPhone 97.5, under the shorter date block).
 */
@Composable
private fun BoxScope.TodayEmpty(day: UpNextDay?, s: WidgetScale, left: Float, width: Float, center: Float = 105f) {
    val half = 158f - 16f - center
    Box(Modifier.align(Alignment.TopStart).offset(s.pt(left), s.pt(center - half)).size(s.pt(width), s.pt(2 * half))) {
        if (day == null) Box(Modifier.align(Alignment.CenterStart)) { NoAccessText(s, 15f) }
        else WidgetText(noEventsMessage(day.hadEarlier), s, 15f, Modifier.align(Alignment.CenterStart),
            color = LocalWidgetColors.current.secondary, maxLines = 2, lineHeight = 18f)
    }
}

// Up Next (small, medium, large) -----------------------------------------------------------

/** Column geometry (pt) measured on the HIG medium image: 17.5 pt margins, two 143 pt columns, 17 pt apart. */
private const val UP_NEXT_LEFT = 17.5f
private const val UP_NEXT_COLUMN = 143f
private const val UP_NEXT_RIGHT = UP_NEXT_LEFT + UP_NEXT_COLUMN + 17f

@Composable
private fun UpNextWidget(scope: IosWidgetContext) {
    val content = rememberCalendarContent(scope)
    val today = content.now.toLocalDate()
    val day = content.events?.let { upNextDay(it, today, content.now) }
    val later = content.events?.let { nextEventDay(it, today, content.now.zone) }
    val chinese = Locale.getDefault().language == "zh"
    val colors = LocalWidgetColors.current
    // Round 21: the empty message sits higher under the shorter Chinese date block; events keep the HIG's 70 pt top.
    val emptyCenter = if (chinese) ZH_EMPTY_CENTER else 105f
    IosWidgetCard(content.tap, scope.family, background = darkWidgetPlatter(colors)) { s ->
        when (scope.family) {
            // Small (HIG): the date, then as many of today's next events as fit — two in Apple's image,
            // the second without its location.
            NativeWidgetSize.SMALL -> {
                CalendarDateBlock(today, s, chinese)
                if (day == null || day.events.isEmpty()) TodayEmpty(day, s, CAL_MARGIN, 158f - 2 * CAL_MARGIN, emptyCenter)
                else UpNextColumn(planUpNextColumn(UP_NEXT_EVENTS_TOP, UP_NEXT_BOTTOM, day.events), content, s,
                    16.5f, 158f - 33f)
            }
            // Medium (HIG): date + the next event on the left; the right column continues with today's
            // later events, then the next day's header and events. With nothing coming up this week (or
            // no calendar access yet) the right half is the month, as on the owner's iPhone.
            NativeWidgetSize.MEDIUM -> {
                CalendarDateBlock(today, s, chinese)
                if (day == null || day.events.isEmpty()) TodayEmpty(day, s, CAL_MARGIN, UP_NEXT_COLUMN, emptyCenter)
                else UpNextColumn(planUpNextColumn(UP_NEXT_EVENTS_TOP, UP_NEXT_BOTTOM, day.events.take(1)), content, s,
                    UP_NEXT_LEFT, UP_NEXT_COLUMN)
                if (day == null || (day.events.isEmpty() && later?.events.isNullOrEmpty())) MiniMonth(today, s)
                else UpNextColumn(planUpNextColumn(UP_NEXT_RIGHT_TOP, UP_NEXT_BOTTOM, day.events.drop(1), later),
                    content, s, UP_NEXT_RIGHT, UP_NEXT_COLUMN)
            }
            NativeWidgetSize.LARGE -> UpNextLarge(content, today, later, s)
        }
    }
}

// Chinese date block and month (round 21) ---------------------------------------------------

/*
 * Measured on the owner's iPhone Duo (evidence/r21/ref/1.webp, medium card 983 px = 338 pt; the
 * small cards of 4.webp / 5.webp agree within 0.6 pt), in card points: the date's figures 18.9–55.4
 * across, cap top 18.2, baseline 43; hairlines at 60.9 and 82.9 from 18.6 to 42.6; the lunar day
 * (grey) and the short weekday (red) stacked with their characters centred on 23.4 and 37.3 pt and
 * 72.4 / 94.0 pt across; "今天无日程" 15 pt, centred on 97.5 pt.
 */
private const val ZH_DATE_SIZE = 34f
private const val ZH_DATE_BASELINE = 43f
private const val ZH_RULE_TOP = 18.6f
private const val ZH_RULE_BOTTOM = 42.6f
private const val ZH_STACK_SIZE = 10.5f
private const val ZH_STACK_FIRST = 23.4f
private const val ZH_STACK_PITCH = 13.9f
private const val ZH_EMPTY_CENTER = 97.5f
/** Gaps (pt) between the date's advance box, the rules and the stacked columns (ink measured, bearings calibrated). */
private const val ZH_GAP_AFTER_DATE = 4f
private const val ZH_GAP_AROUND_STACK = 5.8f

/** iOS's date block: the HIG's weekday-over-date in English, the Chinese "25 │ 十六 │ 周日" otherwise. */
@Composable
private fun CalendarDateBlock(date: LocalDate, s: WidgetScale, chinese: Boolean) {
    if (!chinese) { Box(Modifier.padding(start = s.pt(CAL_MARGIN))) { DateBlock(date, s) }; return }
    val colors = LocalWidgetColors.current
    val locale = Locale.getDefault()
    val lunar = remember(date, locale) { lunarDayLabelOf(date, locale) }
    val rule = if (colors.clear) Color.White.copy(alpha = .3f) else colors.primary.copy(alpha = .14f)
    val hairline = with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }
    @Composable fun Rule() {
        Spacer(Modifier.padding(top = s.pt(ZH_RULE_TOP)).size(maxOf(hairline, s.pt(.4f)), s.pt(ZH_RULE_BOTTOM - ZH_RULE_TOP))
            .background(rule))
    }
    @Composable fun Stack(text: String, color: Color) {
        Column(Modifier.padding(top = s.pt(ZH_STACK_FIRST - ZH_STACK_PITCH / 2f))) {
            text.forEach { WidgetText(it.toString(), s, ZH_STACK_SIZE, color = color, lineHeight = ZH_STACK_PITCH, tracking = 0f) }
        }
    }
    Row(Modifier.padding(start = s.pt(CAL_DATE_LEFT_ZH))) {
        WidgetText("${date.dayOfMonth}", s, ZH_DATE_SIZE, Modifier.paddingFromBaseline(top = s.pt(ZH_DATE_BASELINE)),
            lineHeight = 40f, tracking = -.01f)
        Spacer(Modifier.width(s.pt(ZH_GAP_AFTER_DATE)))
        if (lunar != null) {
            Rule()
            Spacer(Modifier.width(s.pt(ZH_GAP_AROUND_STACK)))
            Stack(lunar, colors.secondary)
            Spacer(Modifier.width(s.pt(ZH_GAP_AROUND_STACK)))
        }
        Rule()
        Spacer(Modifier.width(s.pt(ZH_GAP_AROUND_STACK)))
        Stack(chineseShortWeekday(date.dayOfWeek), colors.red)
    }
}

/** Left edge (pt) of the Chinese date's text box: its figures' ink starts 18.9 pt in. */
private const val CAL_DATE_LEFT_ZH = 17.4f

/** The lunar day label of [date] (see [LunarDate.dayLabel]) from ICU's Chinese calendar; null off a Chinese locale. */
internal fun lunarDayLabelOf(date: LocalDate, locale: Locale): String? {
    if (locale.language != "zh") return null
    return runCatching {
        val cal = android.icu.util.ChineseCalendar()
        cal.time = java.util.Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant())
        LunarDate.dayLabel(cal.get(android.icu.util.Calendar.MONTH) + 1,
            cal.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 1, cal.get(android.icu.util.Calendar.DAY_OF_MONTH))
    }.getOrNull()
}

/*
 * The medium widget's month (1.webp, pt): red title ink from 182.9 pt, centred on 21.9; weekday
 * header centred on 39.0 with columns centred 187.4 + 21.28·i; date rows per [miniMonthRows];
 * 10 pt semibold figures, weekends grey; today in a 17.7 pt red disc; an adjusted working day
 * underlined 13.8 × 0.8 pt, 7.4 pt below its row's centre.
 */
private const val MINI_FIRST_COLUMN = 187.4f
private const val MINI_COLUMN_PITCH = 21.28f
private const val MINI_TITLE_CENTER = 21.9f
private const val MINI_HEADER_CENTER = 39f
private const val MINI_TODAY_DISC = 17.7f

@Composable
private fun BoxScope.MiniMonth(today: LocalDate, s: WidgetScale) {
    val colors = LocalWidgetColors.current
    val locale = Locale.getDefault()
    val firstDay = remember(locale) { firstDayOfWeek(locale) }
    val weeks = remember(today, firstDay) { monthGrid(today, firstDay) }
    val rows = miniMonthRows(weeks.size)
    val month = today.month.getDisplayName(TextStyle.FULL, locale).let { if (locale.language == "zh") it else it.uppercase(locale) }
    @Composable fun Cell(cx: Float, cy: Float, content: @Composable BoxScope.() -> Unit) {
        Box(Modifier.align(Alignment.TopStart).offset(s.pt(cx - 10f), s.pt(cy - 10f)).size(s.pt(20f)), contentAlignment = Alignment.Center,
            content = content)
    }
    WidgetText(month, s, 11f, Modifier.align(Alignment.TopStart).offset(s.pt(MINI_FIRST_COLUMN - 5f), s.pt(MINI_TITLE_CENTER - 7f)),
        weight = FontWeight.SemiBold, color = colors.red, lineHeight = 14f, tracking = .01f)
    repeat(7) { col ->
        val dow = firstDay.plus(col.toLong())
        Cell(MINI_FIRST_COLUMN + col * MINI_COLUMN_PITCH, MINI_HEADER_CENTER) {
            WidgetText(dow.getDisplayName(TextStyle.NARROW, locale), s, 10f, weight = FontWeight.Medium,
                color = if (isWeekend(dow)) colors.secondary else colors.primary, align = TextAlign.Center, lineHeight = 13f, tracking = 0f)
        }
    }
    val red = if (colors.clear) Color.White else colors.red
    weeks.forEachIndexed { week, days ->
        val cy = rows.firstCenter + week * rows.pitch
        days.forEachIndexed { col, date ->
            if (date == null) return@forEachIndexed
            val cx = MINI_FIRST_COLUMN + col * MINI_COLUMN_PITCH
            val isToday = date == today
            Cell(cx, cy) {
                if (isToday) Box(Modifier.size(s.pt(minOf(MINI_TODAY_DISC, rows.pitch))).background(red, CircleShape))
                WidgetText("${date.dayOfMonth}", s, 10f, weight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                    color = when {
                        isToday -> if (colors.clear) Color.Black.copy(alpha = .8f) else Color.White
                        isWeekend(date.dayOfWeek) -> colors.secondary
                        else -> colors.primary
                    }, align = TextAlign.Center, lineHeight = 13f, tracking = 0f)
            }
            if (!isToday && chinaAdjustedWorkday(date, locale)) Box(Modifier.align(Alignment.TopStart)
                .offset(s.pt(cx - 6.9f), s.pt(cy + 7f)).size(s.pt(13.8f), s.pt(.8f)).background(red))
        }
    }
}

/**
 * Large (HIG image, in pt of the 338-pt-wide card): date block top-left; today's timeline below it
 * (first hour centred at 85 pt), the next day's header level with the weekday at the top right and
 * its timeline from 48.5 pt; both end 24 pt above the bottom. Bare hour labels (12 pt semibold,
 * secondary): left-aligned at 17 pt on the left, right-aligned at 184.5 pt on the right; hairlines
 * run from the labels to the column edge (165 / 321 pt) and never through an event.
 */
@Composable
private fun BoxScope.UpNextLarge(content: CalendarContent, today: LocalDate, later: UpNextDay?, s: WidgetScale) {
    val colors = LocalWidgetColors.current
    Box(Modifier.padding(start = s.pt(CAL_MARGIN))) { DateBlock(today, s) }
    val next = later?.date ?: today.plusDays(1)
    WidgetText(dayHeading(next, today), s, 11f, Modifier.align(Alignment.TopStart).offset(s.pt(188.5f))
        .paddingFromBaseline(top = s.pt(CAL_WEEKDAY_BASELINE)), weight = FontWeight.SemiBold, color = colors.secondary, tracking = .01f)
    val events = content.events
    if (events == null) {
        Box(Modifier.align(Alignment.TopStart).offset(s.pt(CAL_MARGIN), s.pt(78f)).width(s.pt(140f))) { NoAccessText(s, 15f) }
        return
    }
    val bottom = 354f - 24f
    DayTimeline(events, today, content, s, labelLeft = 17f, labelRightAlign = false, right = 165f, firstCenter = 85.3f,
        lastCenter = bottom, minHours = 4, showNow = true)
    DayTimeline(events, next, content, s, labelLeft = 170f, labelRightAlign = true, right = 321f, firstCenter = 48.5f,
        lastCenter = bottom, minHours = 7, showNow = false)
}

/** Width (pt) of a bare 12 pt semibold hour label ("1" … "12"). */
private fun hourLabelWidth(label: String) = label.length * 7.2f

@Composable
private fun BoxScope.DayTimeline(events: List<CalendarEvent>, date: LocalDate, content: CalendarContent, s: WidgetScale,
    labelLeft: Float, labelRightAlign: Boolean, right: Float, firstCenter: Float, lastCenter: Float, minHours: Int, showNow: Boolean) {
    val colors = LocalWidgetColors.current
    val zone = content.now.zone
    val window = timelineWindow(events, date, content.now, minHours)
    val labels = (0..window.hours).map { timelineHourLabel(window.startHour + it, content.use24h) }
    // Right column: labels end at 184.5 pt and lines start 4 pt later; left: lines start 3.5 pt after the widest label.
    val lineStart = if (labelRightAlign) 188.5f else labelLeft + labels.maxOf(::hourLabelWidth) + 3.5f
    // All-day events sit above the hours as one-line boxes.
    val allDay = events.filter { it.allDay && it.occursOn(date, zone) }.take(2)
    allDay.forEachIndexed { i, event ->
        EventBox(event, listOf(androidx.compose.ui.text.AnnotatedString(event.title)), s, right - lineStart, upNextBoxHeight(1),
            Modifier.align(Alignment.TopStart).offset(s.pt(lineStart), s.pt(firstCenter - 6f + i * (upNextBoxHeight(1) + 3f))))
    }
    val top = firstCenter + allDay.size * (upNextBoxHeight(1) + 3f)
    val pitch = (lastCenter - top) / window.hours
    val windowStart = date.atTime(window.startHour, 0).atZone(zone).toInstant().toEpochMilli()
    val windowEnd = windowStart + window.hours * 3_600_000L
    fun yOf(millis: Long) = top + (millis.coerceIn(windowStart, windowEnd) - windowStart) / 3_600_000f * pitch
    val timed = events.filter { !it.allDay && it.end > windowStart && it.begin < windowEnd && it.occursOn(date, zone) }.sortedBy { it.begin }
    val lanes = timelineLanes(timed)
    val laneCount = (lanes.maxOrNull() ?: 0) + 1
    val area = right - lineStart
    val boxes = timed.mapIndexed { i, event ->
        val y0 = yOf(event.begin)
        val y1 = maxOf(yOf(event.end), y0 + upNextBoxHeight(1))
        val x0 = lineStart + lanes[i] * area / laneCount
        androidx.compose.ui.geometry.Rect(x0, y0, x0 + area / laneCount - if (laneCount > 1) 2f else 0f, y1)
    }
    val line = if (colors.clear) Color.White.copy(alpha = .3f)
        else if (colors.background.luminanceValue() > .5f) Color(0xFF3C3C43).copy(alpha = .18f) else Color.White.copy(alpha = .15f)
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val px = s.px(1f)
        for (i in 0..window.hours) {
            val y = top + i * pitch
            val cuts = boxes.filter { y >= it.top - .5f && y <= it.bottom + .5f }.map { it.left..it.right }
            lineSegments(lineStart, right, cuts).forEach { (a, b) ->
                drawLine(line, androidx.compose.ui.geometry.Offset(a * px, y * px), androidx.compose.ui.geometry.Offset(b * px, y * px),
                    strokeWidth = .75f * px)
            }
        }
    }
    labels.forEachIndexed { i, label ->
        val baseline = top + i * pitch + 4.4f
        if (labelRightAlign) WidgetText(label, s, 12f, Modifier.align(Alignment.TopStart).offset(s.pt(lineStart - 4f - 24f))
            .width(s.pt(24f)).paddingFromBaseline(top = s.pt(baseline)), weight = FontWeight.SemiBold, color = colors.secondary,
            align = TextAlign.End, tracking = 0f)
        else WidgetText(label, s, 12f, Modifier.align(Alignment.TopStart).offset(s.pt(labelLeft))
            .paddingFromBaseline(top = s.pt(baseline)), weight = FontWeight.SemiBold, color = colors.secondary, tracking = 0f)
    }
    timed.forEachIndexed { i, event ->
        val r = boxes[i]
        val lines = ((r.height - UP_NEXT_BOX_PADDING) / UP_NEXT_LINE).toInt().coerceIn(1, 3)
        val text = buildList {
            add(androidx.compose.ui.text.AnnotatedString(event.title))
            add(smallCapsMeridiem(eventStartCompact(event, zone, content.use24h)))
            event.location?.takeIf { it.isNotBlank() }?.let { add(androidx.compose.ui.text.AnnotatedString(it)) }
        }.take(lines)
        EventBox(event, text, s, r.width, r.height, Modifier.align(Alignment.TopStart).offset(s.pt(r.left), s.pt(r.top)))
    }
    if (showNow && date == content.now.toLocalDate()) {
        val nowMillis = content.now.toInstant().toEpochMilli()
        if (nowMillis in windowStart until windowEnd) {
            val y = yOf(nowMillis)
            val red = if (colors.clear) Color.White else colors.red
            Box(Modifier.align(Alignment.TopStart).offset(s.pt(labelLeft), s.pt(y - 2f)).size(s.pt(4f)).background(red, CircleShape))
            Box(Modifier.align(Alignment.TopStart).offset(s.pt(labelLeft + 2f), s.pt(y - .6f)).size(s.pt(right - labelLeft - 2f), s.pt(1.2f))
                .background(red))
        }
    }
}

// List (medium) ----------------------------------------------------------------------------

@Composable
private fun ListWidget(scope: IosWidgetContext) {
    val content = rememberCalendarContent(scope)
    val colors = LocalWidgetColors.current
    val today = content.now.toLocalDate()
    // Next events across the coming week, grouped under their day like the iOS List widget.
    val upcoming = content.events?.let { listAgenda(it, content.now) }
    IosWidgetCard(content.tap, scope.family) { s ->
        Column(Modifier.fillMaxSize().padding(horizontal = s.pt(CAL_MARGIN))) {
            Row(Modifier.paddingFromBaseline(top = s.pt(CAL_WEEKDAY_BASELINE)), verticalAlignment = Alignment.Bottom) {
                WidgetText(weekdayHeading(today), s, 11f, weight = FontWeight.SemiBold, color = colors.red, tracking = .01f)
                Spacer(Modifier.width(s.pt(5f)))
                WidgetText(today.format(DateTimeFormatter.ofPattern(uxText("M月d日", "MMM d"), Locale.getDefault())), s, 12f,
                    weight = FontWeight.SemiBold, color = colors.secondary)
            }
            Spacer(Modifier.height(s.pt(8f)))
            when {
                upcoming == null -> NoAccessText(s)
                upcoming.isEmpty() -> WidgetText(uxText("接下来 7 天没有日程", "No events in the next 7 days"), s, 15f, color = colors.secondary)
                else -> FittingColumn(s.pt(6f), Modifier.fillMaxWidth().weight(1f).padding(bottom = s.pt(12f))) {
                    upcoming.groupBy { it.first }.forEach { (date, rows) ->
                        rows.forEachIndexed { index, (_, event) ->
                            // A day header travels with its first event, so a dropped row never strands it.
                            Column {
                                if (index == 0 && date != today) WidgetText(dayHeading(date, today), s, 11f, Modifier.padding(bottom = s.pt(4f)),
                                    weight = FontWeight.SemiBold, color = colors.secondary)
                                ListRow(event, content, s)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListRow(event: CalendarEvent, content: CalendarContent, s: WidgetScale) {
    val colors = LocalWidgetColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        WidgetText(eventStartLabel(event, content.now.zone, content.use24h), s, 12f, Modifier.width(s.pt(62f)), color = colors.secondary)
        Box(Modifier.width(s.pt(3.5f)).fillMaxHeight().background(eventColor(event, colors), RoundedCornerShape(50)))
        Spacer(Modifier.width(s.pt(6f)))
        WidgetText(listOfNotNull(event.title, event.location).joinToString(" · "), s, 13f, weight = FontWeight.SemiBold, lineHeight = 17f)
    }
}

// Month (small) ----------------------------------------------------------------------------

/**
 * The week's first day as iOS Calendar draws it: the region's (Sunday in the US, Monday in most of
 * Europe), except that iOS starts mainland-Chinese months on Sunday (日 一 二 … 六 on the owner's
 * iPhone, round 21) where Android's CLDR data says Monday.
 */
internal fun iosFirstDayOfWeek(locale: Locale): DayOfWeek {
    if (locale.language == "zh" && (locale.country == "CN" || locale.country.isEmpty())) return DayOfWeek.SUNDAY
    return runCatching { DayOfWeek.of(((java.util.Calendar.getInstance(locale).firstDayOfWeek + 5) % 7) + 1) }.getOrDefault(DayOfWeek.SUNDAY)
}

private fun firstDayOfWeek(locale: Locale): DayOfWeek = iosFirstDayOfWeek(locale)

@Composable
private fun MonthWidget(scope: IosWidgetContext) {
    val now = rememberMinuteTime()
    val today = now.toLocalDate()
    val locale = Locale.getDefault()
    val colors = LocalWidgetColors.current
    val firstDay = remember(locale) { firstDayOfWeek(locale) }
    val weeks = remember(today, firstDay) { monthGrid(today, firstDay) }
    val month = today.month.getDisplayName(TextStyle.FULL, locale).let { if (locale.language == "zh") it else it.uppercase(locale) }
    IosWidgetCard(scope.modifier, scope.family) { s ->
        Column(Modifier.fillMaxSize().padding(start = s.pt(12f), end = s.pt(12f), bottom = s.pt(10f))) {
            WidgetText(month, s, 11f, Modifier.padding(start = s.pt(5f)).paddingFromBaseline(top = s.pt(CAL_WEEKDAY_BASELINE)),
                weight = FontWeight.SemiBold, color = colors.red, tracking = .01f)
            Spacer(Modifier.height(s.pt(5f)))
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val dow = firstDay.plus(col.toLong())
                    WidgetText(dow.getDisplayName(TextStyle.NARROW, locale), s, 9f, Modifier.weight(1f), weight = FontWeight.SemiBold,
                        color = if (isWeekend(dow)) colors.tertiary else colors.secondary, align = TextAlign.Center, lineHeight = 13f)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                weeks.forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEachIndexed { col, date ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (date != null) {
                                    val isToday = date == today
                                    if (isToday) Box(Modifier.size(s.pt(17f)).background(if (colors.clear) Color.White else colors.red, CircleShape))
                                    WidgetText("${date.dayOfMonth}", s, 10f, weight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                                        color = when {
                                            isToday -> if (colors.clear) Color.Black.copy(alpha = .8f) else Color.White
                                            isWeekend(firstDay.plus(col.toLong())) -> colors.secondary
                                            else -> colors.primary
                                        }, align = TextAlign.Center, lineHeight = 13f, tracking = 0f)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
