package com.jake.duolauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

class ClockCalendarWidgetsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val previous = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.US)
    @After fun restore() = Locale.setDefault(previous)

    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, shanghai)
    private fun event(title: String, day: Int, h1: Int, h2: Int, allDay: Boolean = false) = if (allDay)
        CalendarEvent(title, LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            LocalDate.of(2026, 10, day + 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, 0)
        else CalendarEvent(title, at(day, h1).toInstant().toEpochMilli(), at(day, h2).toInstant().toEpochMilli(), false, 0)

    @Test fun `the registry offers Apple's clock and calendar widgets at their iOS sizes`() {
        fun families(app: NativeWidgetKind, widget: String) =
            iosWidgetSpecs.filter { it.app == app && it.widget == widget }.map { it.family }
        assertEquals(listOf(NativeWidgetSize.SMALL), families(NativeWidgetKind.CLOCK, "city"))
        assertEquals(NativeWidgetSize.entries, families(NativeWidgetKind.CLOCK, "worldclock"))
        assertEquals(listOf(NativeWidgetSize.SMALL, NativeWidgetSize.MEDIUM), families(NativeWidgetKind.CLOCK, "alarm"))
        assertEquals(listOf(NativeWidgetSize.SMALL), families(NativeWidgetKind.CLOCK, "digital"))
        assertEquals(listOf(NativeWidgetSize.SMALL), families(NativeWidgetKind.CLOCK, "citydigital"))
        assertEquals(NativeWidgetSize.entries, families(NativeWidgetKind.CALENDAR, "upnext"))
        assertEquals(listOf(NativeWidgetSize.SMALL), families(NativeWidgetKind.CALENDAR, "month"))
        val mine = iosWidgetSpecs.count { it.app == NativeWidgetKind.CLOCK || it.app == NativeWidgetKind.CALENDAR }
        assertEquals(13, mine)
        // iOS 17.4 gallery order: Clock Digital is the Clock app's first page.
        assertEquals("digital", iosWidgetSpecs.first { it.app == NativeWidgetKind.CLOCK }.widget)
        // Clock ids live in -104/-105 and -120…-139, Calendar in -108/-109 and -140…-159.
        iosWidgetSpecs.filter { it.app == NativeWidgetKind.CLOCK }.forEach { assertTrue(it.id in -139..-120 || it.id in -105..-104) }
        iosWidgetSpecs.filter { it.app == NativeWidgetKind.CALENDAR }.forEach { assertTrue(it.id in -159..-140 || it.id in -109..-108) }
        // The World Clock and Up Next size rows switch between the three iOS sizes.
        val world = nativeWidgetSpanConstraints(nativeWidgetId(NativeWidgetKind.CLOCK, NativeWidgetSize.MEDIUM))!!
        assertEquals(WidgetSpan(2, 2), world.minimum); assertEquals(WidgetSpan(4, 4), world.maximum)
        assertNull(iosWidgetSpecs.firstOrNull { it.key == "native:city:medium" })
        assertEquals(-121, nativeWidgetIdForSpan(-105, 4, 4))
        assertEquals(-120, nativeWidgetIdForSpan(-121, 2, 2))
        // City has one size: no size row, no resize.
        val city = nativeWidgetSpanConstraints(-104)!!
        assertFalse(city.canResizeHorizontally || city.canResizeVertically)
    }

    @Test fun `city names and codes follow the UI language`() {
        assertEquals("Shanghai", cityName(shanghai, Locale.US))
        assertEquals("上海", cityName(shanghai, Locale.SIMPLIFIED_CHINESE))
        assertEquals("SHA", cityAbbreviation(shanghai, Locale.US))
        // Apple's codes (iOS 17.4 City Digital: LON SHA CUP NYC ND MOS): curated NYC, initials for two words.
        assertEquals("NYC", cityAbbreviation(ZoneId.of("America/New_York"), Locale.US))
        assertEquals("LA", cityAbbreviation(ZoneId.of("America/Los_Angeles"), Locale.US))
        assertEquals("HK", cityAbbreviation(ZoneId.of("Asia/Hong_Kong"), Locale.US))
        assertEquals("LON", cityAbbreviation(ZoneId.of("Europe/London"), Locale.US))
        assertEquals("MOS", cityAbbreviation(ZoneId.of("Europe/Moscow"), Locale.US))
        assertEquals("伦敦", cityAbbreviation(ZoneId.of("Europe/London"), Locale.SIMPLIFIED_CHINESE))
        assertEquals("洛杉矶", cityAbbreviation(ZoneId.of("America/Los_Angeles"), Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `world clock lists the local zone first and cities with other clocks`() {
        val zones = worldClockZones(shanghai, 5)
        assertEquals(5, zones.size)
        assertEquals(shanghai, zones.first())
        assertFalse(zones.drop(1).any { it.rules == shanghai.rules })
        assertEquals(zones.size, zones.map { it.rules }.distinct().size)
        val noon = at(19, 12)
        // iOS 17+ widgets print the offset bare: "-7", "+5:30", "-12:30", "+0".
        assertEquals("Today" to "-7", zoneOffsetParts(noon, ZoneId.of("Europe/London")))
        assertEquals("Today" to "-2:30", zoneOffsetParts(noon, ZoneId.of("Asia/Kolkata")))
        assertEquals("Today" to "+0", zoneOffsetParts(noon, shanghai))
        assertEquals("Yesterday" to "-12", zoneOffsetParts(at(19, 8), ZoneId.of("America/New_York")))
        assertEquals("Tomorrow" to "+1", zoneOffsetParts(at(19, 23, 30), ZoneId.of("Asia/Tokyo")))
        assertEquals("+5:30", compactZoneOffset(330)); assertEquals("-12:30", compactZoneOffset(-750))
        assertEquals("Today, -7", zoneOffsetLabel(noon, ZoneId.of("Europe/London")))
        // City Digital shows the first world-clock city whose time differs from here.
        assertEquals(ZoneId.of("Europe/London"), cityDigitalZone(shanghai))
        assertEquals(shanghai, cityDigitalZone(ZoneId.of("Europe/London")))
    }

    @Test fun `faces turn black at night and hands point where iOS points them`() {
        assertFalse(isClockNight(at(19, 6)))
        assertFalse(isClockNight(at(19, 17, 59)))
        assertTrue(isClockNight(at(19, 18)))
        assertTrue(isClockNight(at(19, 5, 59)))
        val angles = clockHandAngles(at(19, 3).withSecond(30))
        assertEquals(90.25f, angles.hour, .01f)
        assertEquals(3f, angles.minute, .01f)
        assertEquals(180f, angles.second, .01f)
        assertEquals(0f, clockHandAngles(at(19, 12)).hour, .01f)
    }

    @Test fun `times print like the Clock app in 12 and 24 hours`() {
        assertEquals("21:05" to null, clockTimeParts(at(19, 21, 5), true, Locale.US))
        assertEquals("9:05" to "PM", clockTimeParts(at(19, 21, 5), false, Locale.US))
        assertEquals("12:00" to "AM", clockTimeParts(at(19, 0), false, Locale.US))
        assertEquals("9:05" to "下午", clockTimeParts(at(19, 21, 5), false, Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `the next alarm reads as a day and a countdown`() {
        val now = at(20, 10, 40)
        val tomorrow = alarmSummary(at(21, 7, 30).toInstant().toEpochMilli(), now, Locale.US)
        assertEquals("Tomorrow", tomorrow.day)
        assertEquals("20 hr 50 min", tomorrow.countdown)
        assertEquals("Today", alarmSummary(at(20, 11, 0).toInstant().toEpochMilli(), now, Locale.US).day)
        assertEquals("20 min", alarmSummary(at(20, 11, 0).toInstant().toEpochMilli(), now, Locale.US).countdown)
        assertEquals("Friday", alarmSummary(at(23, 7, 0).toInstant().toEpochMilli(), now, Locale.US).day)
    }

    @Test fun `up next keeps pending events and says why the day is empty`() {
        val events = listOf(event("Standup", 20, 9, 10), event("Review", 20, 14, 15), event("Holiday", 20, 0, 0, allDay = true),
            event("Flight", 22, 8, 10))
        val morning = upNextDay(events, LocalDate.of(2026, 10, 20), at(20, 9, 30))
        assertEquals(listOf("Holiday", "Standup", "Review"), morning.events.map { it.title })
        val afternoon = upNextDay(events, LocalDate.of(2026, 10, 20), at(20, 16))
        assertEquals(listOf("Holiday"), afternoon.events.map { it.title })
        assertTrue(afternoon.hadEarlier)
        val empty = upNextDay(emptyList(), LocalDate.of(2026, 10, 20), at(20, 9))
        assertTrue(empty.events.isEmpty()); assertFalse(empty.hadEarlier)
        assertEquals("No Events Today", noEventsMessage(false))
        assertEquals("No more events today", noEventsMessage(true))
        // The medium/large right column jumps to the next day that has events.
        val next = nextEventDay(events, LocalDate.of(2026, 10, 20), shanghai)
        assertEquals(LocalDate.of(2026, 10, 22), next.date)
        assertEquals(LocalDate.of(2026, 10, 21), nextEventDay(emptyList(), LocalDate.of(2026, 10, 20), shanghai).date)
        // An all-day event stored at UTC midnight belongs to its own date in every zone.
        assertTrue(events[2].occursOn(LocalDate.of(2026, 10, 20), ZoneId.of("America/Los_Angeles")))
        assertFalse(events[2].occursOn(LocalDate.of(2026, 10, 21), shanghai))
    }

    @Test fun `list agenda groups the week and stops at its limit`() {
        val events = (0 until 10).map { event("E$it", 20 + it / 3, 9 + it % 3, 10 + it % 3) }
        val rows = listAgenda(events, at(20, 9, 30))
        assertEquals(6, rows.size)
        assertEquals("E0", rows.first().second.title)
        assertEquals(LocalDate.of(2026, 10, 21), rows[3].first)
    }

    @Test fun `headings and event times use iOS wording`() {
        val today = LocalDate.of(2026, 10, 20)
        assertEquals("TODAY", dayHeading(today, today, Locale.US))
        assertEquals("TOMORROW", dayHeading(today.plusDays(1), today, Locale.US))
        assertEquals("THURSDAY, OCT 22", dayHeading(today.plusDays(2), today, Locale.US))
        assertEquals("TUESDAY", weekdayHeading(today, Locale.US))
        assertEquals("星期二", weekdayHeading(today, Locale.SIMPLIFIED_CHINESE))
        val review = event("Review", 20, 10, 11)
        // Apple's compact range: "4:00–5:00PM" (marker once, no spaces), both markers when they differ.
        assertEquals("10:00–11:00", eventTimeLabel(review, shanghai, true, Locale.US))
        assertEquals("10:00–11:00AM", eventTimeLabel(review, shanghai, false, Locale.US))
        assertEquals("上午10:00–11:00", eventTimeLabel(review, shanghai, false, Locale.SIMPLIFIED_CHINESE))
        val lunch = event("Lunch", 20, 11, 13)
        assertEquals("11:00AM–1:00PM", eventTimeLabel(lunch, shanghai, false, Locale.US))
        assertEquals("上午11:00–下午1:00", eventTimeLabel(lunch, shanghai, false, Locale.SIMPLIFIED_CHINESE))
        assertEquals("09:00–10:00", eventTimeLabel(event("Early", 20, 9, 10), shanghai, true, Locale.US))
        assertEquals("All-day", eventTimeLabel(event("H", 20, 0, 0, allDay = true), shanghai, false, Locale.US))
        assertEquals("10:00 AM", eventStartLabel(review, shanghai, false, Locale.US))
        assertEquals("10:00AM", eventStartCompact(review, shanghai, false, Locale.US))
        assertEquals("上午10:00", eventStartCompact(review, shanghai, false, Locale.SIMPLIFIED_CHINESE))
        assertEquals("10:00", eventStartCompact(review, shanghai, true, Locale.US))
        // Small caps: only the Latin marker shrinks.
        val caps = smallCapsMeridiem("4:00–5:00PM")
        assertEquals("4:00–5:00PM", caps.text)
        assertEquals(listOf(9 to 11), caps.spanStyles.map { it.start to it.end })
        assertTrue(smallCapsMeridiem("下午4:00–5:00").spanStyles.isEmpty())
    }

    @Test fun `month grid starts on the region's first weekday and leaves other months blank`() {
        val sunday = monthGrid(LocalDate.of(2026, 10, 20), DayOfWeek.SUNDAY)
        assertEquals(5, sunday.size)
        assertEquals(listOf(null, null, null, null), sunday.first().take(4))
        assertEquals(LocalDate.of(2026, 10, 1), sunday.first()[4])
        assertEquals(LocalDate.of(2026, 10, 31), sunday.last()[6])
        val monday = monthGrid(LocalDate.of(2026, 10, 20), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 1), monday.first()[3])
        assertEquals(6, monthGrid(LocalDate.of(2026, 8, 1), DayOfWeek.SUNDAY).size)
        assertTrue(isWeekend(DayOfWeek.SATURDAY)); assertFalse(isWeekend(DayOfWeek.FRIDAY))
    }

    @Test fun `large timelines reproduce Apple's 1 to 5 and 7 to 2 windows and split overlapping events`() {
        val today = LocalDate.of(2026, 10, 20)
        // Apple's HIG large: now 1:10 PM, Yoga 4–5 PM → "1 … 5"; tomorrow coffee 8:30 → "7 … 2".
        val samples = sampleCalendarEvents(today, shanghai)
        assertEquals(TimelineWindow(13, 4), timelineWindow(samples, today, at(20, 13, 10), 4))
        assertEquals(TimelineWindow(7, 7), timelineWindow(samples, today.plusDays(1), at(20, 13, 10), 7))
        // The window stretches to reach the next event only; an empty day starts at 8; late evening never runs past midnight.
        val events = listOf(event("A", 20, 9, 11), event("B", 20, 10, 12), event("C", 20, 11, 12), event("D", 20, 15, 19))
        assertEquals(TimelineWindow(10, 4), timelineWindow(events, today, at(20, 10, 41), 4))
        assertEquals(TimelineWindow(10, 9), timelineWindow(events.drop(3), today, at(20, 10, 41), 4))
        assertEquals(TimelineWindow(8, 7), timelineWindow(emptyList(), today.plusDays(1), at(20, 10, 41), 7))
        assertEquals(TimelineWindow(20, 4), timelineWindow(events, today, at(20, 23, 10), 4))
        assertEquals(listOf(0, 1, 0), timelineLanes(events.take(3)))
        // Bare hour labels, as in Apple's image.
        assertEquals("2", timelineHourLabel(14, false)); assertEquals("12", timelineHourLabel(12, false))
        assertEquals("14", timelineHourLabel(14, true)); assertEquals("0", timelineHourLabel(24, true))
        // Hour lines stop at event boxes.
        assertEquals(listOf(10f to 20f, 40f to 50f), lineSegments(10f, 50f, listOf(20f..40f)))
        assertEquals(listOf(10f to 50f), lineSegments(10f, 50f, listOf(60f..70f)))
        assertEquals(emptyList<Pair<Float, Float>>(), lineSegments(10f, 50f, listOf(0f..60f)))
    }

    @Test fun `up next columns stack Apple's boxes and drop the location before an event`() {
        val today = LocalDate.of(2026, 10, 20)
        val samples = sampleCalendarEvents(today, shanghai)
        val day = upNextDay(samples, today, at(20, 13, 10))
        // Small (HIG): Yoga with location + time at 70–108.4 pt; Dinner without its location, ending ≈ 139.4 pt.
        val small = planUpNextColumn(UP_NEXT_EVENTS_TOP, UP_NEXT_BOTTOM, day.events).map { it as UpNextEventItem }
        assertEquals(2, small.size)
        assertEquals(3, small[0].box.lines); assertEquals(70f, small[0].top, .01f)
        assertFalse(small[1].box.location); assertTrue(small[1].box.time)
        assertEquals(139.4f, small[1].top + small[1].box.height, .1f)
        // Medium right column (HIG): today's next event at the top, TOMORROW on a 73.1 pt baseline, its event at 79.9 pt.
        val later = nextEventDay(samples, today, shanghai)
        val right = planUpNextColumn(UP_NEXT_RIGHT_TOP, UP_NEXT_BOTTOM, day.events.drop(1), later)
        assertEquals(3, right.size)
        assertEquals(55.1f, (right[0] as UpNextEventItem).let { it.top + it.box.height }, .1f)
        assertEquals(73.1f, right[1].top + UP_NEXT_FIRST_BASELINE, .1f)
        assertEquals(today.plusDays(1), (right[1] as UpNextHeaderItem).date)
        assertEquals(79.9f, right[2].top, .1f)
        // No more events today: the header moves to the top; an empty next day says so.
        val header = planUpNextColumn(UP_NEXT_RIGHT_TOP, UP_NEXT_BOTTOM, emptyList(), UpNextDay(today.plusDays(1), emptyList(), false))
        assertEquals(listOf(UpNextHeaderItem(UP_NEXT_RIGHT_TOP, today.plusDays(1)), UpNextNoteItem(UP_NEXT_RIGHT_TOP + UP_NEXT_HEADER_HEIGHT)), header)
        // A header never hangs at the bottom without an event under it.
        assertTrue(planUpNextColumn(130f, UP_NEXT_BOTTOM, emptyList(), later).isEmpty())
    }

    @Test fun `gallery samples are Apple's HIG agenda at 1-10 PM`() {
        val samples = sampleCalendarEvents(LocalDate.of(2026, 10, 20), shanghai)
        val now = at(20, SAMPLE_CALENDAR_NOW.hour, SAMPLE_CALENDAR_NOW.minute)
        assertEquals(2, upNextDay(samples, LocalDate.of(2026, 10, 20), now).events.size)
        assertEquals(LocalDate.of(2026, 10, 21), nextEventDay(samples, LocalDate.of(2026, 10, 20), shanghai).date)
        assertEquals("4:00–5:00PM", eventTimeLabel(samples[0], shanghai, false, Locale.US))
        assertEquals("8:30–9:00AM", eventTimeLabel(samples[2], shanghai, false, Locale.US))
    }

    @Test fun `clock digital prints Apple's figures and sweeps its ticks`() {
        assertEquals("09:46", digitalClockText(at(19, 9, 46), true))
        assertEquals("9:46", digitalClockText(at(19, 9, 46), false))
        assertEquals("12:05", digitalClockText(at(19, 0, 5), false))
        // Lit for 20 s behind the sweep, faintest just ahead of it.
        assertEquals(1f, digitalTickAlpha(30, 30), 0f)
        assertEquals(1f, digitalTickAlpha(10, 30), 0f)
        assertEquals(.22f, digitalTickAlpha(31, 30), .01f)
        assertTrue(digitalTickAlpha(45, 30) < digitalTickAlpha(5, 30))
        // Ticks ride a rounded square: 0.45 out on the edges, pulled in at the corners (Apple: (0.446, 0.401) at 312°).
        val (x0, y0) = roundedSquareRay(0f, .45f, .07f)
        assertEquals(0f, x0, 1e-4f); assertEquals(-.45f, y0, 1e-4f)
        val (x1, y1) = roundedSquareRay(90f, .45f, .07f)
        assertEquals(.45f, x1, 1e-4f); assertEquals(0f, y1, 1e-4f)
        val (x2, y2) = roundedSquareRay(312f, .45f, .07f)
        assertEquals(-.446f, x2, .006f); assertEquals(-.401f, y2, .006f)
    }
}
