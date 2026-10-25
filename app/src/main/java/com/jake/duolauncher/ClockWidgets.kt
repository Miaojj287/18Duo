package com.jake.duolauncher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.AlarmClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// =======================================================================================
// Clock app widgets (iOS 26 Clock ▸ Clock Digital / City / World Clock / City Digital, plus
// 18Duo's Alarm extra). Analog faces follow the Clock app: a WHITE dial while it is day in that
// city (06:00–17:59) and a BLACK dial at night, whatever the system appearance; numerals 1–12,
// minute ticks, necked hands, the orange second hand. World Clock sits on the graphite card in
// every appearance; Clock Digital / City Digital fill the card with condensed figures inside 60
// sweeping minute ticks (iOS 17.2 / 17.4). Clear turns everything into white strokes on glass.
// Ids: -104 / -105 are the Core.17 clock (kept), new ones -120 … -139.
// All data is real: the system time zone, well-known cities' clocks, AlarmManager's next alarm.
// =======================================================================================

private const val CLOCK_DESCRIPTION_CITY_ZH = "查看一个城市的时间。"
private const val CLOCK_DESCRIPTION_CITY_EN = "View the time in a city."
private const val CLOCK_DESCRIPTION_WORLD_ZH = "查看多个城市的时间。"
private const val CLOCK_DESCRIPTION_WORLD_EN = "View the time in multiple cities."

internal fun clockWidgetSpecs(): List<IosWidgetSpec> = listOf(
    // Gallery order follows iOS 17.4+: Clock Digital is page 1, City Digital comes after the World Clock pair.
    IosWidgetSpec(-122, NativeWidgetKind.CLOCK, "digital", NativeWidgetSize.SMALL, "数字时钟", "Clock Digital",
        "显示当前时间。", "Display the current time.", opens = ::clockAppIntent) { DigitalClockWidget(this, city = false) },
    IosWidgetSpec(-104, NativeWidgetKind.CLOCK, "city", NativeWidgetSize.SMALL, "城市", "City",
        CLOCK_DESCRIPTION_CITY_ZH, CLOCK_DESCRIPTION_CITY_EN, featured = 10, key = "native:clock:small",
        opens = ::clockAppIntent) { CityClockWidget(this) },
    IosWidgetSpec(-120, NativeWidgetKind.CLOCK, "worldclock", NativeWidgetSize.SMALL, "世界时钟", "World Clock",
        CLOCK_DESCRIPTION_WORLD_ZH, CLOCK_DESCRIPTION_WORLD_EN, opens = ::clockAppIntent) { WorldClockSmall(this) },
    IosWidgetSpec(-105, NativeWidgetKind.CLOCK, "worldclock", NativeWidgetSize.MEDIUM, "世界时钟", "World Clock",
        CLOCK_DESCRIPTION_WORLD_ZH, CLOCK_DESCRIPTION_WORLD_EN, featured = 60, key = "native:clock:medium",
        opens = ::clockAppIntent) { WorldClockMedium(this) },
    // No Apple large World Clock exists (round-3 review): an 18Duo extension, and labelled as one.
    IosWidgetSpec(-121, NativeWidgetKind.CLOCK, "worldclock", NativeWidgetSize.LARGE, "世界时钟", "World Clock",
        "查看多个城市的时间。大号为 18Duo 扩展版式。", "View the time in multiple cities. The large size is an 18Duo extra.",
        opens = ::clockAppIntent) { WorldClockLarge(this) },
    IosWidgetSpec(-125, NativeWidgetKind.CLOCK, "citydigital", NativeWidgetSize.SMALL, "城市数字时钟", "City Digital",
        CLOCK_DESCRIPTION_CITY_ZH, CLOCK_DESCRIPTION_CITY_EN, opens = ::clockAppIntent) { DigitalClockWidget(this, city = true) },
    // iOS has only a Lock Screen "Next Alarm" accessory; this Home Screen pair is 18Duo's own.
    IosWidgetSpec(-123, NativeWidgetKind.CLOCK, "alarm", NativeWidgetSize.SMALL, "闹钟", "Alarm",
        "查看下一个闹钟（18Duo 扩展小组件）。", "See your next alarm (an 18Duo extra).", featured = 64) { AlarmWidget(this) },
    IosWidgetSpec(-124, NativeWidgetKind.CLOCK, "alarm", NativeWidgetSize.MEDIUM, "闹钟", "Alarm",
        "查看下一个闹钟（18Duo 扩展小组件）。", "See your next alarm (an 18Duo extra).") { AlarmWidget(this) },
)

private val CLOCK_PACKAGES = listOf("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
    "com.huawei.deskclock", "com.coloros.alarmclock", "com.oneplus.deskclock")

/**
 * The Clock app's launcher entry (iOS: a Clock widget opens Clock). Starting AlarmClock.SHOW_ALARMS
 * directly needs SET_ALARM on AOSP's DeskClock ("Permission Denial" on the QA emulator), so the
 * app that handles it is looked up and opened like a Home Screen icon instead.
 */
internal fun clockAppIntent(context: Context): Intent? {
    val pm = context.packageManager
    val handler = runCatching { pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName }.getOrNull()
        ?.takeIf { it != "android" }
    return (listOfNotNull(handler) + CLOCK_PACKAGES).firstNotNullOfOrNull { pkg ->
        runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    }
}

// Pure helpers (unit-tested) -------------------------------------------------------------

private val CITY_NAMES = mapOf(
    "Asia/Shanghai" to "上海", "Asia/Chongqing" to "重庆", "Asia/Harbin" to "哈尔滨", "Asia/Urumqi" to "乌鲁木齐",
    "Asia/Hong_Kong" to "香港", "Asia/Macau" to "澳门", "Asia/Taipei" to "台北",
    "Asia/Tokyo" to "东京", "Asia/Seoul" to "首尔", "Asia/Singapore" to "新加坡", "Asia/Bangkok" to "曼谷",
    "Europe/London" to "伦敦", "Europe/Paris" to "巴黎", "Europe/Berlin" to "柏林", "Europe/Moscow" to "莫斯科",
    "America/New_York" to "纽约", "America/Los_Angeles" to "洛杉矶", "America/Chicago" to "芝加哥",
    "America/Denver" to "丹佛", "America/Toronto" to "多伦多", "America/Vancouver" to "温哥华",
    "Australia/Sydney" to "悉尼", "Australia/Melbourne" to "墨尔本", "Asia/Dubai" to "迪拜", "Asia/Kolkata" to "加尔各答",
    "Pacific/Auckland" to "奥克兰", "Etc/UTC" to "UTC", "UTC" to "UTC", "GMT" to "GMT",
)

/** The city a time zone is named after, in Chinese when the UI is Chinese (iOS: 上海, 伦敦 …). */
internal fun cityName(zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val id = zone.id
    if (locale.language == "zh") CITY_NAMES[id]?.let { return it }
    return id.substringAfterLast('/').replace('_', ' ')
}

/** Apple's curated codes where the first three letters would mislead (iOS 17.4 City Digital: NYC, ND, LA, SF). */
private val CITY_CODES = mapOf("New York" to "NYC", "Rio de Janeiro" to "RIO", "Mexico City" to "MEX")

/**
 * The city code iOS prints on small clocks: a single-word city's first three letters (LON, SHA,
 * CUP, MOS), a multi-word city's initials (LA, SF, ND, HK) and the curated NYC. Chinese keeps the
 * city's Chinese name, as the Chinese Clock app does.
 */
internal fun cityAbbreviation(zone: ZoneId, locale: Locale = Locale.getDefault()): String {
    val name = cityName(zone, locale)
    if (locale.language == "zh" && CITY_NAMES.containsKey(zone.id)) return name
    val english = cityName(zone, Locale.US)
    CITY_CODES[english]?.let { return it }
    val words = english.split(' ', '-').filter { it.isNotBlank() }
    if (words.size > 1) return words.joinToString("") { it.take(1) }.uppercase(Locale.ROOT).take(3)
    return english.filter(Char::isLetter).take(3).uppercase(Locale.ROOT).ifEmpty { name.take(3) }
}

private val WORLD_CLOCK_CITIES = listOf("Asia/Shanghai", "Europe/London", "America/New_York", "Asia/Tokyo",
    "Europe/Paris", "America/Los_Angeles", "Australia/Sydney")

/** Local zone first, then [count] − 1 well-known cities whose clocks differ from it. */
internal fun worldClockZones(local: ZoneId, count: Int = 4): List<ZoneId> {
    val others = WORLD_CLOCK_CITIES.map(ZoneId::of).filter { it.rules != local.rules }
        .distinctBy { it.rules }
    return listOf(local) + others.take((count - 1).coerceAtLeast(0))
}

/** The city a City Digital widget shows: the first world-clock city whose time differs from here. */
internal fun cityDigitalZone(local: ZoneId): ZoneId = worldClockZones(local, 2).last()

/** The World Clock large list's sub-line: "Today, -7" / "今天，-7". */
internal fun zoneOffsetLabel(now: ZonedDateTime, zone: ZoneId): String {
    val (day, delta) = zoneOffsetParts(now, zone)
    return "$day${uxText("，", ", ")}$delta"
}

/**
 * Day word and hour offset as the iOS 17+ Clock widgets print them: no unit, half hours as
 * minutes ("Today" + "-7", "+5:30", "-12:30", "+0"). The Chinese widgets use the same digits.
 */
internal fun zoneOffsetParts(now: ZonedDateTime, zone: ZoneId): Pair<String, String> {
    val there = now.withZoneSameInstant(zone)
    val day = when {
        there.toLocalDate().isAfter(now.toLocalDate()) -> uxText("明天", "Tomorrow")
        there.toLocalDate().isBefore(now.toLocalDate()) -> uxText("昨天", "Yesterday")
        else -> uxText("今天", "Today")
    }
    return day to compactZoneOffset((there.offset.totalSeconds - now.offset.totalSeconds) / 60)
}

/** "+7", "-1", "+5:30", "-12:30", "+0" for an offset of [minutes]. */
internal fun compactZoneOffset(minutes: Int): String {
    val sign = if (minutes < 0) "-" else "+"
    val abs = kotlin.math.abs(minutes)
    return if (abs % 60 == 0) "$sign${abs / 60}" else "$sign${abs / 60}:${"%02d".format(abs % 60)}"
}

/** The digits of iOS 17 Clock Digital / City Digital: "09:46" in 24-hour time, "9:46" in 12-hour (no AM/PM). */
internal fun digitalClockText(time: ZonedDateTime, use24h: Boolean): String =
    time.format(DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm"))

/**
 * Brightness of the Clock Digital minute tick at [tick] (0 = 12 o'clock) when the seconds sweep is
 * at [second]: the ticks the sweep passed in the last 20 s are lit, older ones fade to 22 % just
 * ahead of it (measured on Apple's iOS 17.4 City Digital widgets, where the six cards share one second).
 */
internal fun digitalTickAlpha(tick: Int, second: Int): Float {
    val age = Math.floorMod(second - tick, 60)
    return if (age <= 20) 1f else 1f - .78f * (age - 20) / 39f
}

/**
 * Where a ray from the card centre at [degrees] (clockwise from 12) meets the rounded square that
 * carries the Clock Digital ticks: half-side [half], corner radius [radius] (Apple: 0.45 and 0.07 of
 * the card, ticks every 6°). Returns the offset from the centre.
 */
internal fun roundedSquareRay(degrees: Float, half: Float, radius: Float): Pair<Float, Float> {
    val radians = Math.toRadians(degrees.toDouble())
    val dx = kotlin.math.sin(radians); val dy = -kotlin.math.cos(radians)
    val ax = kotlin.math.abs(dx); val ay = kotlin.math.abs(dy)
    var t = half / maxOf(ax, ay)
    val corner = (half - radius).toDouble()
    if (t * ax > corner && t * ay > corner) {
        val dot = corner * (ax + ay)
        t = dot + kotlin.math.sqrt((dot * dot - 2 * corner * corner + radius.toDouble() * radius).coerceAtLeast(0.0))
    }
    return (t * dx).toFloat() to (t * dy).toFloat()
}

/** The Clock app's day face runs 06:00–17:59 local time in that city; the rest is night. */
internal fun isClockNight(time: ZonedDateTime): Boolean = time.hour < 6 || time.hour >= 18

/** Hour, minute and second hand angles in degrees clockwise from 12 (smooth hour/minute, stepped second). */
internal data class HandAngles(val hour: Float, val minute: Float, val second: Float)

internal fun clockHandAngles(time: ZonedDateTime): HandAngles {
    val minutes = time.minute + time.second / 60f
    return HandAngles(((time.hour % 12) + minutes / 60f) * 30f, minutes * 6f, time.second * 6f)
}

/** "9:41" + "AM" (12-hour) or "21:41" + null (24-hour), as the Clock app prints a time. */
internal fun clockTimeParts(time: ZonedDateTime, use24h: Boolean, locale: Locale = Locale.getDefault()): Pair<String, String?> {
    if (use24h) return time.format(DateTimeFormatter.ofPattern("H:mm", locale)) to null
    val marker = if (locale.language == "zh") (if (time.hour < 12) "上午" else "下午") else (if (time.hour < 12) "AM" else "PM")
    return time.format(DateTimeFormatter.ofPattern("h:mm", locale)) to marker
}

/** When the next alarm rings, relative to [now]: day word (今天/明天/星期三) and "7小时12分钟" countdown. */
internal data class AlarmSummary(val time: ZonedDateTime, val day: String, val countdown: String)

internal fun alarmSummary(triggerMillis: Long, now: ZonedDateTime, locale: Locale = Locale.getDefault()): AlarmSummary {
    val time = Instant.ofEpochMilli(triggerMillis).atZone(now.zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), time.toLocalDate())
    val day = when (days) {
        0L -> uxText("今天", "Today")
        1L -> uxText("明天", "Tomorrow")
        else -> time.dayOfWeek.getDisplayName(JavaTextStyle.FULL, locale)
    }
    val total = Duration.between(now, time).plusSeconds(59).toMinutes().coerceAtLeast(0)
    val h = total / 60; val m = total % 60
    val countdown = when {
        h >= 24 -> uxText("${h / 24}天${h % 24}小时", "${h / 24} d ${h % 24} hr")
        h > 0 && m > 0 -> uxText("${h}小时${m}分钟", "$h hr $m min")
        h > 0 -> uxText("${h}小时", "$h hr")
        else -> uxText("${m}分钟", "$m min")
    }
    return AlarmSummary(time, day, countdown)
}

// Live data ------------------------------------------------------------------------------

/** Wall-clock state for draw-phase readers: the second hand redraws without recomposing the widget. */
@Composable
private fun rememberClockState(): State<ZonedDateTime> = produceState(ZonedDateTime.now()) {
    while (true) {
        val now = ZonedDateTime.now()
        value = now
        delay(1000L - now.nano / 1_000_000L)
    }
}

@Composable
private fun rememberUse24Hour(): Boolean {
    val context = LocalContext.current
    val minute = rememberMinuteTime()
    return remember(context, minute.hour) { android.text.format.DateFormat.is24HourFormat(context) }
}

private data class NextAlarm(val triggerMillis: Long, val show: android.app.PendingIntent?)

@Composable
private fun rememberNextAlarm(): NextAlarm? {
    val context = LocalContext.current
    val read = { runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock }.getOrNull()
        ?.let { NextAlarm(it.triggerTime, it.showIntent) } }
    val alarm = produceState(read(), context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { value = read() }
        }
        runCatching { context.registerReceiver(receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) }
        // A fired or dismissed alarm also moves "next"; the broadcast covers changes, a slow poll covers the rest.
        val poll = launch { while (true) { delay(60_000L); value = read() } }
        awaitDispose { poll.cancel(); runCatching { context.unregisterReceiver(receiver) } }
    }
    return alarm.value
}

// Dial ------------------------------------------------------------------------------------

private val ClockOrange = Color(0xFFFF9500)
private val ClockOrangeDark = Color(0xFFFF9F0A)

private data class DialPalette(
    val face: Color, val numeral: Color, val majorTick: Color, val minorTick: Color,
    val hand: Color, val second: Color, val rim: Color, val label: Color,
)

/** Night dials on the World Clock's graphite card are a lighter grey (#3A3A3C, sampled on Apple's widget). */
private val WorldClockNightFace = Color(0xFF3A3A3C)

private fun dialPalette(night: Boolean, colors: WidgetColors, graphite: Boolean = false): DialPalette = when {
    colors.clear -> DialPalette(Color.White.copy(alpha = .10f), Color.White, Color.White, Color.White.copy(alpha = .45f),
        Color.White, Color.White, Color.White.copy(alpha = .22f), Color.White.copy(alpha = .78f))
    night -> DialPalette(if (graphite) WorldClockNightFace else Color.Black, Color.White, Color.White, Color.White.copy(alpha = .38f), Color.White, ClockOrangeDark,
        Color.White.copy(alpha = .16f), Color(0xFFEBEBF5).copy(alpha = .6f))
    else -> DialPalette(Color.White, Color.Black, Color.Black, Color.Black.copy(alpha = .30f), Color.Black, ClockOrange,
        Color.Black.copy(alpha = .10f), Color(0xFF3C3C43).copy(alpha = .6f))
}

/** How much a dial carries: the City face has everything; tiny World Clock dials keep ticks only. */
private enum class DialDetail { FULL, NUMERALS, TICKS }

/**
 * One iOS Clock-app dial. Geometry is in fractions of the radius R (measured on the iOS City
 * widget and Clock icon): 60 minute ticks 0.075 R long at the rim (hour ticks bolder), numerals
 * on 0.74 R at 0.24 R, hour hand 0.52 R, minute 0.82 R (both necked: a hairline stem for the
 * first 0.14 R, then a 0.075 R bar), second hand 0.90 R with a 0.20 R tail, ring hub.
 */
@Composable
private fun AnalogDial(time: State<ZonedDateTime>, zone: ZoneId, night: Boolean, detail: DialDetail, label: String?,
    modifier: Modifier, fullBleed: Boolean = false, graphite: Boolean = false) {
    val colors = LocalWidgetColors.current
    val palette = dialPalette(night, colors, graphite)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(modifier
        .drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val numeralStyle = TextStyle(color = palette.numeral, fontFamily = DuoFonts.text, fontWeight = FontWeight.Medium,
                fontSize = with(density) { (r * .19f).toSp() }, letterSpacing = (-.03).em)
            val numerals = if (detail == DialDetail.TICKS) emptyList() else (1..12).map { n ->
                val layout = measurer.measure(n.toString(), numeralStyle)
                val angle = n * 30f
                val p = c + clockVector(angle) * (r * .72f)
                layout to Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
            }
            val labelLayout = label?.let {
                measurer.measure(it, TextStyle(color = palette.label, fontFamily = DuoFonts.text, fontWeight = FontWeight.SemiBold,
                    fontSize = with(density) { (r * .15f).toSp() }, letterSpacing = .02.em))
            }
            onDrawBehind {
                if (!fullBleed) {
                    drawCircle(palette.face, r, c)
                    if (!colors.clear) drawCircle(palette.rim, r - .5f, c, style = Stroke(1f))
                }
                for (tick in 0 until 60) {
                    val major = tick % 5 == 0
                    if (!major && detail == DialDetail.TICKS) continue
                    val v = clockVector(tick * 6f)
                    val outer = r * .955f
                    val length = r * if (major) .085f else .06f
                    drawLine(if (major) palette.majorTick else palette.minorTick, c + v * (outer - length), c + v * outer,
                        strokeWidth = r * if (major) .022f else .011f, cap = StrokeCap.Butt)
                }
                numerals.forEach { (layout, at) -> drawText(layout, topLeft = at) }
                // The city code sits between the 12 and the hub, like the iOS City widget.
                labelLayout?.let { drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - r * .36f - it.size.height / 2f)) }
            }
        }
        .drawBehind { drawHands(clockHandAngles(time.value.withZoneSameInstant(zone)), palette, detail) })
}

private fun clockVector(degrees: Float): Offset {
    val radians = (degrees - 90f) * PI.toFloat() / 180f
    return Offset(cos(radians), sin(radians))
}

private fun DrawScope.drawHands(angles: HandAngles, palette: DialPalette, detail: DialDetail) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val body = r * if (detail == DialDetail.TICKS) .085f else .058f
    fun hand(angle: Float, length: Float) {
        val v = clockVector(angle)
        drawLine(palette.hand, c, c + v * (r * .14f), strokeWidth = body * .42f, cap = StrokeCap.Round)
        drawLine(palette.hand, c + v * (r * .14f), c + v * (r * length), strokeWidth = body, cap = StrokeCap.Round)
    }
    hand(angles.hour, .50f)
    hand(angles.minute, .80f)
    drawCircle(palette.hand, body * .95f, c)
    val s = clockVector(angles.second)
    drawLine(palette.second, c - s * (r * .20f), c + s * (r * .90f), strokeWidth = (r * .022f).coerceAtLeast(1f), cap = StrokeCap.Round)
    drawCircle(palette.second, body * .62f, c)
    drawCircle(palette.face.copy(alpha = 1f).takeIf { palette.face.alpha > .5f } ?: Color.Black.copy(alpha = .4f), body * .24f, c)
}

// City (small) -----------------------------------------------------------------------------

@Composable
private fun CityClockWidget(scope: IosWidgetContext) {
    val time = rememberClockState()
    val minute = rememberMinuteTime()
    val zone = remember { ZoneId.systemDefault() }
    val night = isClockNight(minute.withZoneSameInstant(zone))
    val colors = LocalWidgetColors.current
    val palette = dialPalette(night, colors)
    // The whole card is the dial (iOS City widget): white by day, black by night; Clear stays glass.
    val face: (@Composable BoxScope.(WidgetScale) -> Unit)? =
        if (colors.clear) null else { _ -> Box(Modifier.fillMaxSize().background(palette.face)) }
    IosWidgetCard(scope.modifier, scope.family, background = face) { s ->
        AnalogDial(time, zone, night, DialDetail.FULL, cityAbbreviation(zone),
            Modifier.align(Alignment.Center).size(s.pt(150f)), fullBleed = true)
    }
}

// World Clock ------------------------------------------------------------------------------

/**
 * Apple draws World Clock on the graphite card (#1C1C1E) in Light as well as Dark (idownloadblog
 * gallery + Home captures); only Clear turns it into glass. Day dials stay white, night dials grey.
 */
@Composable
private fun WorldClockCard(scope: IosWidgetContext, content: @Composable BoxScope.(WidgetScale) -> Unit) {
    val colors = LocalWidgetColors.current
    if (colors.clear) IosWidgetCard(scope.modifier, scope.family, content = content)
    else CompositionLocalProvider(LocalWidgetColors provides widgetColors(WidgetTone.DARK)) {
        IosWidgetCard(scope.modifier, scope.family, content = content)
    }
}

@Composable
private fun WorldClockSmall(scope: IosWidgetContext) {
    val time = rememberClockState()
    val minute = rememberMinuteTime()
    val zones = remember { worldClockZones(ZoneId.systemDefault(), 4) }
    WorldClockCard(scope) { s ->
        // 2 × 2: 50 pt dials on a 66 pt pitch, each with its city code underneath.
        zones.forEachIndexed { index, zone ->
            val cx = 46f + (index % 2) * 66f
            val top = 14f + (index / 2) * 70f
            AnalogDial(time, zone, isClockNight(minute.withZoneSameInstant(zone)), DialDetail.TICKS, null,
                Modifier.offset(s.pt(cx - 25f), s.pt(top)).size(s.pt(50f)), graphite = true)
            WidgetText(cityAbbreviation(zone), s, 11f, Modifier.offset(s.pt(cx - 30f), s.pt(top + 51f)).width(s.pt(60f)),
                weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 13f)
        }
    }
}

/**
 * Measured on Apple's World Clock medium (idownloadblog Home capture, 338 × 158 pt): four 62 pt
 * dials (numerals) at x ≈ 47 + 81 i, top 21 pt; city 13 pt semibold on a 99 pt baseline, then
 * "Today" and the offset in 12 pt secondary at 13.5 pt steps.
 */
@Composable
private fun WorldClockMedium(scope: IosWidgetContext) {
    val time = rememberClockState()
    val minute = rememberMinuteTime()
    val zones = remember { worldClockZones(ZoneId.systemDefault(), 4) }
    WorldClockCard(scope) { s ->
        val colors = LocalWidgetColors.current
        zones.forEachIndexed { index, zone ->
            val (day, delta) = zoneOffsetParts(minute, zone)
            val cx = 47f + 81.3f * index
            AnalogDial(time, zone, isClockNight(minute.withZoneSameInstant(zone)), DialDetail.NUMERALS, null,
                Modifier.offset(s.pt(cx - 31f), s.pt(21f)).size(s.pt(62f)), graphite = true)
            Column(Modifier.offset(s.pt(cx - 40f)).width(s.pt(80f)), horizontalAlignment = Alignment.CenterHorizontally) {
                WidgetText(cityName(zone), s, 13f, Modifier.paddingFromBaseline(top = s.pt(99f)), weight = FontWeight.SemiBold)
                WidgetText(day, s, 12f, Modifier.paddingFromBaseline(top = s.pt(13.5f)), color = colors.secondary, lineHeight = 13.5f)
                WidgetText(delta, s, 12f, Modifier.paddingFromBaseline(top = s.pt(13.5f)), color = colors.secondary, lineHeight = 13.5f)
            }
        }
    }
}

@Composable
private fun WorldClockLarge(scope: IosWidgetContext) {
    val time = rememberClockState()
    val minute = rememberMinuteTime()
    val zones = remember { worldClockZones(ZoneId.systemDefault(), 5) }
    val use24h = rememberUse24Hour()
    WorldClockCard(scope) { s ->
        val colors = LocalWidgetColors.current
        Column(Modifier.fillMaxSize().padding(horizontal = s.pt(16f), vertical = s.pt(10f))) {
            zones.forEachIndexed { index, zone ->
                if (index > 0) Box(Modifier.fillMaxWidth().padding(start = s.pt(58f)).height(.5.dp).background(colors.tertiary.copy(alpha = .5f)))
                val there = minute.withZoneSameInstant(zone)
                val (clock, marker) = clockTimeParts(there, use24h)
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    AnalogDial(time, zone, isClockNight(there), DialDetail.TICKS, null, Modifier.size(s.pt(46f)), graphite = true)
                    Spacer(Modifier.width(s.pt(12f)))
                    Column(Modifier.weight(1f)) {
                        WidgetText(zoneOffsetLabel(minute, zone), s, 12f, color = colors.secondary, lineHeight = 15f)
                        WidgetText(cityName(zone), s, 17f, weight = FontWeight.SemiBold, lineHeight = 21f)
                    }
                    TimeWithMarker(clock, marker, s, 32f, 15f)
                }
            }
        }
    }
}

/** "7:30 AM" with a smaller marker on the baseline; Chinese puts it first ("上午7:30"), as iOS does. */
@Composable
private fun TimeWithMarker(clock: String, marker: String?, s: WidgetScale, size: Float, markerSize: Float) {
    val zh = Locale.getDefault().language == "zh"
    Row {
        if (marker != null && zh) WidgetText(marker, s, markerSize, Modifier.alignByBaseline().padding(end = s.pt(1f)),
            lineHeight = markerSize * 1.2f)
        WidgetText(clock, s, size, Modifier.alignByBaseline(), lineHeight = size * 1.1f, tracking = -.02f)
        if (marker != null && !zh) WidgetText(marker, s, markerSize, Modifier.alignByBaseline().padding(start = s.pt(2f)),
            lineHeight = markerSize * 1.2f)
    }
}

// Clock Digital / City Digital (small) -------------------------------------------------------

/**
 * Condensed clock figures: an instance of Roboto Flex (SIL OFL 1.1; wdth 48, wght 540, XTRA 500,
 * opsz 144) subset to digits, colon and signs (res/font/duo_clock_digits.ttf). Matched to Apple's
 * iOS 17.4 City Digital figures: "12:46" spans 0.77 of the card, 0.366 high, stems ≈ 0.13 of the height.
 */
private val ClockDigits = FontFamily(Font(R.font.duo_clock_digits, FontWeight.SemiBold))

/** Apple's text greys on the white day face and the black night face. */
private val DigitalLabelDay = Color(0xFF3C3C43).copy(alpha = .6f)
private val DigitalLabelNight = Color(0xFFEBEBF5).copy(alpha = .6f)

/**
 * iOS 17.2 "Clock Digital" ([city] false: this phone's time) and iOS 17.4 "City Digital" ([city]
 * true: another city, its code on top and the hour offset below). The whole card is the clock:
 * white by day and black by night in that city whatever the appearance (Clear: glass), 60 minute
 * ticks on a rounded square 0.45 of the card out from the centre, lit by the seconds sweep, and
 * condensed figures centred 0.49 down. Measured on idownloadblog's six-widget Home capture and
 * iPhoneLife's gallery page (reference/widgets/review).
 */
@Composable
private fun DigitalClockWidget(scope: IosWidgetContext, city: Boolean) {
    val time = rememberClockState()
    val minute = rememberMinuteTime()
    val use24h = rememberUse24Hour()
    val local = remember { ZoneId.systemDefault() }
    val zone = remember(local, city) { if (city) cityDigitalZone(local) else local }
    val there = minute.withZoneSameInstant(zone)
    val night = isClockNight(there)
    val clear = LocalWidgetColors.current.clear
    val ink = if (clear || night) Color.White else Color.Black
    val label = when {
        clear -> Color.White.copy(alpha = .72f)
        night -> DigitalLabelNight
        else -> DigitalLabelDay
    }
    val face: (@Composable BoxScope.(WidgetScale) -> Unit)? =
        if (clear) null else { _ -> Box(Modifier.fillMaxSize().background(if (night) Color.Black else Color.White)) }
    IosWidgetCard(scope.modifier, scope.family, background = face) { s ->
        Canvas(Modifier.fillMaxSize()) { drawDigitalTicks(time.value.second, ink) }
        if (city) WidgetText(cityAbbreviation(zone), s, 15f, Modifier.align(Alignment.TopCenter).paddingFromBaseline(top = s.pt(32.2f)),
            weight = FontWeight.SemiBold, color = label, tracking = .02f)
        Text(digitalClockText(there, use24h), Modifier.align(Alignment.TopCenter).paddingFromBaseline(top = s.pt(106.3f)),
            color = ink, fontFamily = ClockDigits, fontWeight = FontWeight.SemiBold, fontSize = s.sp(79.7f),
            lineHeight = s.sp(84f), letterSpacing = 0.em, maxLines = 1, softWrap = false)
        if (city) WidgetText(zoneOffsetParts(minute, zone).second, s, 15.5f,
            Modifier.align(Alignment.TopCenter).paddingFromBaseline(top = s.pt(135.4f)), weight = FontWeight.SemiBold, color = label)
    }
}

/** 60 round-capped ticks, radial, on Apple's rounded square; brightness follows [digitalTickAlpha]. */
private fun DrawScope.drawDigitalTicks(second: Int, ink: Color) {
    val side = size.minDimension
    val length = side * .036f
    val width = side * .0095f
    val reach = (length - width) / 2f
    for (tick in 0 until 60) {
        val angle = tick * 6f
        val (x, y) = roundedSquareRay(angle, side * .45f, side * .07f)
        val p = center + Offset(x, y)
        val d = clockVector(angle)
        drawLine(ink.copy(alpha = ink.alpha * digitalTickAlpha(tick, second)), p - d * reach, p + d * reach,
            strokeWidth = width, cap = StrokeCap.Round)
    }
}

// Alarm (small, medium) --------------------------------------------------------------------

/** SF "alarm.fill": a round bell body with two hammers and feet. */
@Composable
private fun AlarmGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w * .5f, w * .54f)
        val r = w * .36f
        drawCircle(tint, r, c)
        val bell = Path().apply {
            // Left and right hammers: quarter discs above the body.
            addOval(androidx.compose.ui.geometry.Rect(Offset(w * .02f, w * .05f), Size(w * .30f, w * .30f)))
            addOval(androidx.compose.ui.geometry.Rect(Offset(w * .68f, w * .05f), Size(w * .30f, w * .30f)))
        }
        drawPath(bell, tint)
        drawLine(tint, Offset(w * .22f, w * .86f), Offset(w * .12f, w * .97f), strokeWidth = w * .09f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * .78f, w * .86f), Offset(w * .88f, w * .97f), strokeWidth = w * .09f, cap = StrokeCap.Round)
        // Clock hands cut out of the body.
        val hand = if (tint.luminance() > .5f) Color.Black else Color.White
        drawLine(hand, c, c + Offset(0f, -r * .55f), strokeWidth = w * .07f, cap = StrokeCap.Round)
        drawLine(hand, c, c + Offset(r * .42f, r * .22f), strokeWidth = w * .07f, cap = StrokeCap.Round)
    }
}

private fun Color.luminance(): Float = .2126f * red + .7152f * green + .0722f * blue

@Composable
private fun AlarmWidget(scope: IosWidgetContext) {
    val alarm = rememberNextAlarm()
    val now = rememberMinuteTime()
    val use24h = rememberUse24Hour()
    val context = LocalContext.current
    val colors = LocalWidgetColors.current
    val medium = scope.family != NativeWidgetSize.SMALL
    // Gallery only: iOS previews the widget with a sample alarm when none is set.
    val summary = alarm?.let { alarmSummary(it.triggerMillis, now) }
        ?: if (scope.preview) alarmSummary(now.toLocalDate().plusDays(1).atTime(7, 0).atZone(now.zone).toInstant().toEpochMilli(), now) else null
    val openApp = rememberWidgetAppOpener()
    val tap = scope.bareModifier.widgetTap(scope.interactive) {
        val show = alarm?.show
        if (show != null) runCatching { show.send() }.onFailure { openApp(clockAppIntent(context)) }
        else openApp(clockAppIntent(context))
    }
    val accent = if (colors.clear) Color.White else colors.orange
    IosWidgetCard(tap, scope.family) { s ->
        Row(Modifier.fillMaxSize().padding(s.pt(16f))) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlarmGlyph(s.pt(18f), if (summary == null) colors.secondary else accent)
                    Spacer(Modifier.width(s.pt(5f)))
                    WidgetText(uxText("闹钟", "Alarm"), s, 13f, weight = FontWeight.SemiBold, color = if (summary == null) colors.secondary else accent)
                }
                Spacer(Modifier.weight(1f))
                if (summary == null) {
                    WidgetText(uxText("无闹钟", "No Alarms"), s, 17f, weight = FontWeight.SemiBold, color = colors.secondary)
                    Spacer(Modifier.height(s.pt(2f)))
                    WidgetText(uxText("轻点以设置闹钟", "Tap to set an alarm"), s, 13f, color = colors.tertiary, maxLines = 2)
                } else {
                    val (clock, marker) = clockTimeParts(summary.time, use24h)
                    TimeWithMarker(clock, marker, s, 38f, 17f)
                    WidgetText(summary.day, s, 13f, weight = FontWeight.SemiBold, color = colors.secondary)
                    if (!medium) WidgetText(uxText("${summary.countdown}后", "In ${summary.countdown}"), s, 13f, color = colors.secondary)
                }
            }
            if (medium) {
                Spacer(Modifier.width(s.pt(16f)))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    if (summary != null) {
                        WidgetText(uxText("距离响铃", "Rings In"), s, 13f, weight = FontWeight.SemiBold, color = colors.secondary)
                        WidgetText(summary.countdown, s, 22f, weight = FontWeight.SemiBold, lineHeight = 26f, maxLines = 2)
                    }
                }
            }
        }
    }
}
