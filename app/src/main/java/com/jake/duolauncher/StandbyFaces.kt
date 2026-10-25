package com.jake.duolauncher

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow

/**
 * Everything a face needs to draw one frame; faces are pure functions of this plus their style. The time is read
 * through [clock] and only inside draw/semantics scopes, so a tick never recomposes (code review P2-8d).
 */
@Stable
internal class StandbyFrame(
    private val clock: StandbyClock,
    val locale: Locale,
    val use24h: Boolean,
    /** Display cutouts in the face's own coordinates. */
    val cutouts: List<Rect> = emptyList(),
    /** Keep-out margin around cutouts, px. */
    val cutoutMargin: Float = 0f,
    /** Burn-in orbit amplitude, px; the orbit position follows the minute. */
    private val shiftAmplitude: Float = 0f,
) {
    /** A still frame at [now] (tests, gallery). */
    constructor(now: ZonedDateTime, locale: Locale, use24h: Boolean, cutouts: List<Rect> = emptyList(), cutoutMargin: Float = 0f) :
        this(StandbyClock(now), locale, use24h, cutouts, cutoutMargin)
    /** Every tick: only the analog hands need it. */
    val now: ZonedDateTime get() = clock.now
    /** Minute precision for everything else, so those faces redraw once a minute. */
    val minute: ZonedDateTime get() = clock.minute
    /** Burn-in orbit applied to marks (never to the background), px. */
    val shift: Offset get() =
        if (shiftAmplitude == 0f) Offset.Zero else standbyPixelShift(clock.minute.toEpochSecond() / 60L, shiftAmplitude)
    /** Round 24: the owner's iPhone Duo (zh_CN) starts StandBy's weeks on Sunday (日 一 … 六), as iOS does in China. */
    val firstDay: DayOfWeek get() = if (zh) DayOfWeek.SUNDAY else WeekFields.of(locale).firstDayOfWeek
    val zh: Boolean get() = locale.language == "zh"
}

// Text ------------------------------------------------------------------------------------------------------

internal fun standbyClockParts(now: ZonedDateTime, use24h: Boolean): Pair<String, String> {
    val hour = if (use24h) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    return (if (use24h) "%02d".format(Locale.ROOT, hour) else hour.toString()) to "%02d".format(Locale.ROOT, now.minute)
}

/** Digital face date, e.g. "WED 1" (video 0.2 s) / "周三 1日". */
internal fun standbyDigitalDate(date: LocalDate, locale: Locale): String {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    return if (locale.language == "zh") "$weekday ${date.dayOfMonth}日" else "${weekday.uppercase(locale)} ${date.dayOfMonth}"
}

/** Analog face header: weekday (accent) + month (primary), e.g. "Wed" "Sep" (owner photo 4) / "周三" "9月". */
internal fun standbyAnalogHeader(date: LocalDate, locale: Locale): Pair<String, String> {
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
    val month = if (locale.language == "zh") "${date.monthValue}月" else date.month.getDisplayName(TextStyle.SHORT, locale)
    return weekday to month
}

/** Mini calendar label: "SEPTEMBER" / "9月". */
internal fun standbyMonthLabel(date: LocalDate, locale: Locale): String =
    if (locale.language == "zh") "${date.monthValue}月" else date.month.getDisplayName(TextStyle.FULL, locale).uppercase(locale)

/** Calendar face title: "April 2026" (video 5.8 s) / "2026年4月". */
internal fun standbyMonthTitle(date: LocalDate, locale: Locale): String =
    if (locale.language == "zh") "${date.year}年${date.monthValue}月"
    else "${date.month.getDisplayName(TextStyle.FULL, locale)} ${date.year}"

internal fun standbyWeekdayLetters(firstDay: DayOfWeek, locale: Locale): List<String> =
    List(7) { firstDay.plus(it.toLong()).getDisplayName(TextStyle.NARROW, locale) }

private fun standbyDescription(frame: StandbyFrame): String {
    val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
    val date = frame.minute.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(frame.locale))
    return "$hours:$minutes, $date"
}

// Drawing helpers -------------------------------------------------------------------------------------------

internal class StandbyPaint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val path = Path()
    val bounds = android.graphics.Rect()
    fun text(typeface: Typeface, cap: Float, color: Color, align: Paint.Align = Paint.Align.LEFT,
        sample: String = "0", spacing: Float = 0f): Paint = paint.apply {
        reset(); flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        this.typeface = typeface
        StandbyTextMetrics.sizeForCap(this, cap, sample)
        this.color = color.toArgb()
        textAlign = align
        letterSpacing = spacing
    }
}

internal fun DrawScope.drawText(paint: Paint, text: String, x: Float, baseline: Float) =
    drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)

/** Baseline that puts cap-height ink of [cap] centred on [centerY]. */
internal fun baselineFor(centerY: Float, cap: Float) = centerY + cap / 2f

// Digital ---------------------------------------------------------------------------------------------------

@Composable
internal fun StandbyDigitalFace(frame: StandbyFrame, style: StandbyDigitalStyle, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    Canvas(modifier.fillMaxSize().testTag("standby-digital").semantics { contentDescription = standbyDescription(frame) }) {
        drawRect(style.background)
        val w = size.width; val h = size.height; val g = StandbyGeometry
        translate(frame.shift.x, frame.shift.y) {
            val date = standbyDigitalDate(frame.minute.toLocalDate(), frame.locale)
            val datePaint = tools.text(StandbyFonts.sans(600), h * g.DIGITAL_DATE_CAP, style.date, sample = "E", spacing = .04f)
            drawText(datePaint, date, w * g.DIGITAL_DATE_LEFT, baselineFor(h * g.DIGITAL_DATE_Y, h * g.DIGITAL_DATE_CAP))
            val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
            drawDigitalTime(tools.paint, hours, minutes, style.digits, frame)
        }
    }
}

/** Condensed heavy digits with round colon dots, sized by cap height and widened to Apple's "9:41" ink aspect. */
private fun DrawScope.drawDigitalTime(paint: Paint, hours: String, minutes: String, color: Color, frame: StandbyFrame) {
    val w = size.width; val h = size.height; val g = StandbyGeometry
    paint.reset(); paint.flags = Paint.ANTI_ALIAS_FLAG
    paint.typeface = StandbyFonts.condensed(800)
    paint.color = color.toArgb()
    paint.textAlign = Paint.Align.LEFT
    var cap = h * g.DIGITAL_TIME_CAP
    StandbyTextMetrics.sizeForCap(paint, cap)
    // Ink layout: [hours ink] gap [dot] gap [minutes ink]; side bearings are excluded so centring is optical.
    val bounds = android.graphics.Rect()
    fun inkWidth(text: String): Float { paint.getTextBounds(text, 0, text.length, bounds); return bounds.width().toFloat() }
    fun colonWidth(c: Float) = c * (.17f + 2 * .065f)
    paint.textScaleX = 1f
    val referenceInk = inkWidth("9") + inkWidth("41")
    paint.textScaleX = ((g.DIGITAL_REFERENCE_ASPECT * cap - colonWidth(cap)) / referenceInk).coerceIn(.55f, 1.25f)
    // The synthetic-weight stroke grows the ink by half its width on each side.
    fun inkTotal(c: Float) = inkWidth(hours) + colonWidth(c) + inkWidth(minutes) + c * .022f
    var width = inkTotal(cap)
    // Keep clear of the short edges (Xiaomi's camera sits mid-height on one of them in the tent pose).
    var limit = w * g.DIGITAL_MAX_WIDTH
    for (cutout in frame.cutouts) {
        val keep = cutout.inflate(frame.cutoutMargin)
        val clearance = if (keep.center.x >= w / 2f) w - keep.left else keep.right
        limit = minOf(limit, w - 2f * clearance)
    }
    if (width > limit && width > 0f) {
        val factor = limit / width
        cap *= factor; paint.textSize *= factor; width = inkTotal(cap)
    }
    val baseline = h * g.DIGITAL_TIME_BASELINE - (h * g.DIGITAL_TIME_CAP - cap) / 2f
    var left = w * g.DIGITAL_TIME_X - width / 2f
    left += standbyCutoutShiftX(Rect(left, baseline - cap, left + width, baseline), frame.cutouts, frame.cutoutMargin, w)
    // Synthetic weight: Apple's strokes are ≈12 % of the cap height, Roboto Condensed Bold's ≈10 %.
    paint.style = Paint.Style.FILL_AND_STROKE
    paint.strokeWidth = cap * .022f
    paint.strokeJoin = Paint.Join.ROUND
    left += cap * .011f
    paint.getTextBounds(hours, 0, hours.length, bounds)
    drawText(paint, hours, left - bounds.left, baseline)
    var x = left + bounds.width() + cap * .065f
    val dot = cap * .17f
    drawCircle(color, dot / 2f, Offset(x + dot / 2f, baseline - cap * .755f))
    drawCircle(color, dot / 2f, Offset(x + dot / 2f, baseline - cap * .245f))
    x += dot + cap * .065f
    paint.getTextBounds(minutes, 0, minutes.length, bounds)
    drawText(paint, minutes, x - bounds.left, baseline)
}

// Analog ----------------------------------------------------------------------------------------------------

@Composable
internal fun StandbyAnalogFace(frame: StandbyFrame, style: StandbyAnalogStyle, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    // iPhone StandBy's second hand sweeps (standby.mp4 7.0–8.3 s: it advances on every 30 fps frame, never in
    // one-second steps). Only this hand's own layer redraws between the face's once-a-second frames. Liveness is
    // read once without observation so a clock tick never recomposes the face (code review P2-8d).
    val live = remember(frame) {
        Snapshot.withoutReadObservation { standbyNowIsLive(frame.now, System.currentTimeMillis()) }
    }
    val sweepStep = remember { mutableIntStateOf(0) }
    LaunchedEffect(live) {
        if (live) while (true) {
            delay(standbySweepDelayMillis(System.currentTimeMillis()))
            withInfiniteAnimationFrameMillis { sweepStep.intValue++ }
        }
    }
    Box(modifier.fillMaxSize().testTag("standby-analog").semantics { contentDescription = standbyDescription(frame) }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawRect(style.background)
            translate(frame.shift.x, frame.shift.y) { drawTentDial(tools, frame, style) }
        }
        Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            onDrawBehind {
                sweepStep.intValue // Redraw on each sweep step; the face's own tick redraws it too.
                val now = frame.now
                val second = if (live) standbySweepSecond(System.currentTimeMillis(), now.offset.totalSeconds)
                    else now.second.toFloat()
                translate(frame.shift.x, frame.shift.y) {
                    // Photo 4: the second hand is the dial's own white, not the accent.
                    drawSecondHandAndHub(center, size.height * TentDial.HAND_SIDE, second * 6f,
                        style.copy(accent = style.primary))
                }
            }
        })
    }
}

/**
 * Second-hand steps per second. The once-a-second face frame is step 0, so a redraw happens every
 * 1000/12 ≈ 83 ms: 0.5° steps, finer than a 10 Hz high-beat watch, which the eye reads as a sweep. Every
 * redraw stays > 50 ms from the next, so Home's [FrameRateGovernor] sees an idle clock and never holds
 * the panel at 120 Hz for StandBy (a vsync-rate sweep would, and StandBy stays on for hours).
 */
internal const val STANDBY_SWEEP_STEPS = 12

/** A frame time within a second of the wall clock is live; a fixed preview/test time is drawn as given. */
internal fun standbyNowIsLive(now: ZonedDateTime, wallMillis: Long): Boolean =
    kotlin.math.abs(wallMillis - now.toInstant().toEpochMilli()) < 1_500L

/** Seconds past the minute shown by the sweeping hand at [wallMillis], on the step grid. */
internal fun standbySweepSecond(wallMillis: Long, offsetSeconds: Int, steps: Int = STANDBY_SWEEP_STEPS): Float {
    val local = Math.floorMod(wallMillis + offsetSeconds * 1_000L, 60_000L)
    val step = (local % 1_000L) * steps / 1_000L
    return (local / 1_000L) + step / steps.toFloat()
}

/** Delay to the next sweep step, skipping step 0: the face's own once-a-second frame draws that one. */
internal fun standbySweepDelayMillis(wallMillis: Long, steps: Int = STANDBY_SWEEP_STEPS): Long {
    val withinSecond = Math.floorMod(wallMillis, 1_000L)
    var next = withinSecond * steps / 1_000L + 1
    if (next >= steps) next = steps + 1L
    // Ceiling so the wake-up never lands a millisecond before the step it is meant to show.
    return ((next * 1_000L + steps - 1) / steps - withinSecond).coerceAtLeast(1L)
}

private fun DrawScope.drawAnalogColumn(tools: StandbyPaint, layout: StandbyAnalogLayout, date: LocalDate,
    frame: StandbyFrame, style: StandbyAnalogStyle) {
    // Header "Wed Sep" and the big day numeral centred under it (photo 4).
    val (weekday, month) = standbyAnalogHeader(date, frame.locale)
    val headerPaint = tools.text(StandbyFonts.sans(600), layout.headerCap, style.accent, sample = "E")
    val headerBaseline = baselineFor(layout.headerY, layout.headerCap)
    drawText(headerPaint, weekday, layout.headerLeft, headerBaseline)
    val space = headerPaint.measureText(" ")
    val weekdayWidth = headerPaint.measureText(weekday)
    headerPaint.color = style.primary.copy(alpha = style.primary.alpha * .78f).toArgb()
    drawText(headerPaint, month, layout.headerLeft + weekdayWidth + space, headerBaseline)
    val headerCenter = layout.headerLeft + (weekdayWidth + space + headerPaint.measureText(month)) / 2f
    val numeralPaint = tools.text(StandbyFonts.sans(700), layout.numeralCap, style.primary)
    // Centred under the header (photo 4's "9"); a two-digit day wider than the header starts flush with it instead.
    val day = date.dayOfMonth.toString()
    numeralPaint.getTextBounds(day, 0, day.length, tools.bounds)
    val numeralX = maxOf(headerCenter, layout.headerLeft + tools.bounds.width() / 2f) - tools.bounds.exactCenterX()
    drawText(numeralPaint, day, numeralX, baselineFor(layout.numeralY, layout.numeralCap))

    // Mini month, bottom-anchored; weekends dimmed, today on an accent disc (photo 4 / video 7.4 s).
    val c = layout.calendar
    val label = tools.text(StandbyFonts.sans(700), c.cap, style.accent, sample = "E", spacing = .02f)
    drawText(label, standbyMonthLabel(date, frame.locale), c.labelLeft, baselineFor(c.labelY, c.cap))
    val letters = standbyWeekdayLetters(frame.firstDay, frame.locale)
    val letterPaint = tools.text(StandbyFonts.sans(600), c.cap * .95f, style.secondaryText, Paint.Align.CENTER, sample = "E")
    letters.forEachIndexed { index, letter -> drawText(letterPaint, letter, c.columnX(index), baselineFor(c.weekdayY, c.cap * .95f)) }
    val cells = standbyMonthCells(date, frame.firstDay)
    val dayPaint = tools.text(StandbyFonts.sans(600), c.cap, style.primary, Paint.Align.CENTER)
    cells.forEachIndexed { index, day ->
        if (day == null) return@forEachIndexed
        val column = index % 7
        val center = Offset(c.columnX(column), c.rowY(index / 7))
        val weekend = frame.firstDay.plus(column.toLong()).let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }
        val color = when {
            day == date.dayOfMonth -> { drawCircle(style.accent, c.todayDiameter / 2f, center); style.onAccent }
            weekend -> style.secondaryText
            else -> style.primary
        }
        dayPaint.color = color.toArgb()
        drawText(dayPaint, day.toString(), center.x, baselineFor(center.y, c.cap))
    }
}

/**
 * iPhone Duo's cover Analog face (round 24, owner photo 4): the whole landscape screen is the dial. Big wide figures
 * 12 2 3 4 6 8 9 10 on a rounded-rectangle ring with a short tick inside each side figure, long strokes for 1 5 7 11,
 * the digital time with seconds left of the hub and the weekday + day right of it. Fractions of W (width) / H (height).
 */
internal object TentDial {
    const val RING_X = .405f
    const val RING_Y = .355f
    const val RING_EXPONENT = 4f
    const val NUMERAL_CAP = .118f
    const val NUMERAL_SCALE_X = 1.14f
    const val LONG_TICK = .15f
    const val LONG_TICK_WIDTH = .011f
    const val SHORT_TICK_IN = .64f
    const val SHORT_TICK_OUT = .76f
    const val SHORT_TICK_WIDTH = .009f
    const val SIDE_TEXT_X = .155f
    const val SIDE_TEXT_CAP = .038f
    /** Hands use the classic dial's proportions on a dial this many H tall (hour .24 H, minute .39 H). */
    const val HAND_SIDE = .8f
}

/** Point on the tent dial's ring at a clock angle: |x/a|ⁿ + |y/b|ⁿ = 1 along the ray. */
internal fun tentDialRing(angleDegrees: Float, a: Float, b: Float, exponent: Float = TentDial.RING_EXPONENT): Offset {
    val v = standbyClockVector(angleDegrees)
    val sum = (abs(v.x) / a).toDouble().pow(exponent.toDouble()) + (abs(v.y) / b).toDouble().pow(exponent.toDouble())
    return v * (1.0 / sum.pow(1.0 / exponent)).toFloat()
}

private fun DrawScope.drawTentDial(tools: StandbyPaint, frame: StandbyFrame, style: StandbyAnalogStyle) {
    val w = size.width; val h = size.height; val d = TentDial
    val c = center
    val a = w * d.RING_X; val b = h * d.RING_Y
    val tick = style.secondaryText
    val numeral = tools.text(StandbyFonts.sans(700), h * d.NUMERAL_CAP, style.primary, Paint.Align.CENTER)
    numeral.textScaleX = d.NUMERAL_SCALE_X
    for (hour in 1..12) {
        val angle = hour * 30f
        val ring = tentDialRing(angle, a, b)
        if (hour % 6 == 1 || hour % 6 == 5) {
            val v = standbyClockVector(angle) * (h * d.LONG_TICK / 2f)
            drawLine(tick, c + ring - v, c + ring + v, h * d.LONG_TICK_WIDTH, StrokeCap.Round)
            continue
        }
        drawText(numeral, hour.toString(), c.x + ring.x, baselineFor(c.y + ring.y, h * d.NUMERAL_CAP))
        if (hour != 12 && hour != 6)
            drawLine(tick, c + ring * d.SHORT_TICK_IN, c + ring * d.SHORT_TICK_OUT, h * d.SHORT_TICK_WIDTH, StrokeCap.Round)
    }
    val now = frame.now
    val side = tools.text(StandbyFonts.sans(600), h * d.SIDE_TEXT_CAP, style.primary, Paint.Align.CENTER)
    val time = if (frame.use24h) "%02d:%02d:%02d".format(Locale.ROOT, now.hour, now.minute, now.second)
        else "%d:%02d:%02d".format(Locale.ROOT, (now.hour % 12).let { if (it == 0) 12 else it }, now.minute, now.second)
    val baseline = baselineFor(c.y, h * d.SIDE_TEXT_CAP)
    drawText(side, time, c.x - w * d.SIDE_TEXT_X, baseline)
    val date = now.toLocalDate()
    val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, frame.locale).let { if (frame.zh) it else it.uppercase(frame.locale) }
    drawText(side, "$weekday ${date.dayOfMonth}", c.x + w * d.SIDE_TEXT_X, baseline)
    val angles = standbyHandAngles(now.hour, now.minute, now.second)
    val g = StandbyGeometry
    val dial = h * d.HAND_SIDE
    fun hand(angle: Float, length: Float) {
        val v = standbyClockVector(angle)
        drawLine(style.primary, c, c + v * (dial * g.HAND_NECK), dial * g.HAND_NECK_WIDTH, StrokeCap.Round)
        drawLine(style.primary, c + v * (dial * g.HAND_NECK), c + v * (dial * length), dial * g.HAND_WIDTH, StrokeCap.Round)
    }
    hand(angles.hour, g.HAND_HOUR)
    hand(angles.minute, g.HAND_MINUTE)
}

/** Rounded-square dial (photo 4): radial ticks on a superellipse, 12/3/6/9, necked hands, ring hub. */
private fun DrawScope.drawAnalogDial(tools: StandbyPaint, center: Offset, side: Float, now: ZonedDateTime,
    style: StandbyAnalogStyle) {
    val g = StandbyGeometry
    val half = side / 2f
    for (tick in 0 until 60) {
        val angle = tick * 6f
        val major = tick % 5 == 0
        val v = standbyClockVector(angle)
        val r = standbyTickRadius(angle, half)
        val length = side * when {
            tick % 15 == 0 -> g.DIAL_CARDINAL_TICK
            major -> g.DIAL_HOUR_TICK
            else -> g.DIAL_MINUTE_TICK
        }
        drawLine(if (major) style.primary else style.minorTick, center + v * (r - length), center + v * r,
            strokeWidth = side * if (major) g.DIAL_HOUR_TICK_WIDTH else g.DIAL_MINUTE_TICK_WIDTH, cap = StrokeCap.Butt)
    }
    val numeral = tools.text(StandbyFonts.sans(700), side * g.DIAL_NUMERAL_CAP, style.primary, Paint.Align.CENTER)
    for ((label, angle) in listOf("12" to 0f, "3" to 90f, "6" to 180f, "9" to 270f)) {
        val radius = side * if (angle % 180f == 0f) g.DIAL_NUMERAL_RADIUS_VERTICAL else g.DIAL_NUMERAL_RADIUS_HORIZONTAL
        val p = center + standbyClockVector(angle) * radius
        drawText(numeral, label, p.x, baselineFor(p.y, side * g.DIAL_NUMERAL_CAP))
    }
    val angles = standbyHandAngles(now.hour, now.minute, now.second)
    fun hand(angle: Float, length: Float) {
        val v = standbyClockVector(angle)
        drawLine(style.primary, center, center + v * (side * g.HAND_NECK), side * g.HAND_NECK_WIDTH, StrokeCap.Round)
        drawLine(style.primary, center + v * (side * g.HAND_NECK), center + v * (side * length), side * g.HAND_WIDTH, StrokeCap.Round)
    }
    hand(angles.hour, g.HAND_HOUR)
    hand(angles.minute, g.HAND_MINUTE)
}

/** The sweeping second hand and the hub over it, on their own layer above the dial. */
private fun DrawScope.drawSecondHandAndHub(center: Offset, side: Float, angle: Float, style: StandbyAnalogStyle) {
    val g = StandbyGeometry
    val s = standbyClockVector(angle)
    drawLine(style.accent, center - s * (side * g.HAND_SECOND_TAIL), center + s * (side * g.HAND_SECOND),
        side * g.HAND_SECOND_WIDTH, StrokeCap.Round)
    drawCircle(style.background, side * g.HUB_RADIUS, center)
    drawCircle(style.accent, side * (g.HUB_RADIUS - g.HUB_STROKE / 2f), center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(side * g.HUB_STROKE))
}

// Book pose (inner screen) ---------------------------------------------------------------------------------

/**
 * Left page of the inner screen standing open like a book (round 24, owner photo 1, iPhone Duo): "周一 10月", the big
 * date with the lunar day stacked beside it, a month at the top right, the rounded-square dial filling the lower page.
 * Fractions of the page width W.
 */
internal object BookPage {
    const val MARGIN = .12f
    const val HEADER_Y = .19f
    const val HEADER_CAP = .045f
    const val NUMERAL_Y = .335f
    const val NUMERAL_CAP = .17f
    const val LUNAR_CAP = .037f
    const val LUNAR_PITCH = .05f
    const val MONTH_FIRST_COLUMN = .6f
    const val MONTH_PITCH = .0485f
    const val MONTH_LABEL_Y = .115f
    const val MONTH_WEEKDAY_Y = .185f
    const val MONTH_FIRST_ROW = .245f
    const val MONTH_ROW_PITCH = .055f
    const val MONTH_CAP = .026f
    const val MONTH_TODAY = .05f
    const val DIAL_TOP = .6f
    const val DIAL_SIDE = .8f
    const val DIAL_BOTTOM = .07f
}

/** The dial's centre and side on a [w]×[h] page: as wide as [BookPage.DIAL_SIDE] allows, centred in the space below the date. */
internal fun bookPageDial(w: Float, h: Float): Pair<Offset, Float> {
    val p = BookPage
    val room = h - w * (p.DIAL_TOP + p.DIAL_BOTTOM)
    val side = minOf(w * p.DIAL_SIDE, room).coerceAtLeast(w * .3f)
    return Offset(w / 2f, w * p.DIAL_TOP + maxOf(room, side) / 2f) to side
}

@Composable
internal fun StandbyBookClockPage(frame: StandbyFrame, style: StandbyAnalogStyle, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    val live = remember(frame) {
        Snapshot.withoutReadObservation { standbyNowIsLive(frame.now, System.currentTimeMillis()) }
    }
    val sweepStep = remember { mutableIntStateOf(0) }
    LaunchedEffect(live) {
        if (live) while (true) {
            delay(standbySweepDelayMillis(System.currentTimeMillis()))
            withInfiniteAnimationFrameMillis { sweepStep.intValue++ }
        }
    }
    Box(modifier.fillMaxSize().testTag("standby-book-clock").semantics { contentDescription = standbyDescription(frame) }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            drawRect(style.background)
            val (dialCenter, side) = bookPageDial(size.width, size.height)
            translate(frame.shift.x, frame.shift.y) {
                drawBookHeader(tools, frame, style)
                drawAnalogDial(tools, dialCenter, side, frame.now, style)
            }
        }
        Spacer(Modifier.fillMaxSize().graphicsLayer().drawWithCache {
            val (dialCenter, side) = bookPageDial(size.width, size.height)
            onDrawBehind {
                sweepStep.intValue
                val now = frame.now
                val second = if (live) standbySweepSecond(System.currentTimeMillis(), now.offset.totalSeconds)
                    else now.second.toFloat()
                translate(frame.shift.x, frame.shift.y) { drawSecondHandAndHub(dialCenter, side, second * 6f, style) }
            }
        })
    }
}

private fun DrawScope.drawBookHeader(tools: StandbyPaint, frame: StandbyFrame, style: StandbyAnalogStyle) {
    val w = size.width; val p = BookPage
    val date = frame.minute.toLocalDate()
    val (weekday, month) = standbyAnalogHeader(date, frame.locale)
    val left = w * p.MARGIN
    val header = tools.text(StandbyFonts.sans(700), w * p.HEADER_CAP, style.accent, sample = "E")
    val headerBaseline = baselineFor(w * p.HEADER_Y, w * p.HEADER_CAP)
    drawText(header, weekday, left, headerBaseline)
    val monthX = left + header.measureText(weekday) + header.measureText(" ")
    header.color = style.primary.toArgb()
    drawText(header, month, monthX, headerBaseline)
    val numeral = tools.text(StandbyFonts.sans(700), w * p.NUMERAL_CAP, style.primary)
    val day = date.dayOfMonth.toString()
    numeral.getTextBounds(day, 0, day.length, tools.bounds)
    drawText(numeral, day, left - tools.bounds.left, baselineFor(w * p.NUMERAL_Y, w * p.NUMERAL_CAP))
    // Lunar month + day stacked one character a line beside the date (photo 1: 九 月 十 七).
    standbyLunarMonthDay(date, frame.locale)?.let { lunar ->
        val x = left + tools.bounds.width() + w * .035f
        val lunarPaint = tools.text(StandbyFonts.sans(700), w * p.LUNAR_CAP, style.primary, Paint.Align.CENTER, sample = "月")
        val top = w * p.NUMERAL_Y - (lunar.length - 1) * w * p.LUNAR_PITCH / 2f
        lunar.forEachIndexed { index, ch ->
            drawText(lunarPaint, ch.toString(), x + w * p.LUNAR_CAP / 2f, baselineFor(top + index * w * p.LUNAR_PITCH, w * p.LUNAR_CAP))
        }
    }
    // Month at the top right: "10月 │ 九月十七", weekday letters, today on the accent disc.
    val cap = w * p.MONTH_CAP
    fun columnX(i: Int) = w * (p.MONTH_FIRST_COLUMN + i * p.MONTH_PITCH)
    val labelLeft = columnX(0) - w * p.MONTH_PITCH * .4f
    val label = tools.text(StandbyFonts.sans(700), cap, style.accent, sample = "E")
    val labelBaseline = baselineFor(w * p.MONTH_LABEL_Y, cap)
    val monthLabel = standbyMonthLabel(date, frame.locale)
    drawText(label, monthLabel, labelLeft, labelBaseline)
    standbyLunarMonthDay(date, frame.locale)?.let { lunar ->
        var x = labelLeft + label.measureText(monthLabel) + cap * .45f
        drawLine(style.secondaryText, Offset(x, labelBaseline - cap * 1.05f), Offset(x, labelBaseline + cap * .15f), cap * .08f)
        x += cap * .45f
        label.color = style.primary.toArgb()
        label.typeface = StandbyFonts.sans(600)
        drawText(label, lunar, x, labelBaseline)
    }
    val letters = standbyWeekdayLetters(frame.firstDay, frame.locale)
    val letterPaint = tools.text(StandbyFonts.sans(600), cap * .95f, style.primary, Paint.Align.CENTER, sample = "E")
    letters.forEachIndexed { i, letter -> drawText(letterPaint, letter, columnX(i), baselineFor(w * p.MONTH_WEEKDAY_Y, cap * .95f)) }
    val dayPaint = tools.text(StandbyFonts.sans(600), cap, style.primary, Paint.Align.CENTER)
    standbyMonthCells(date, frame.firstDay).forEachIndexed { index, cell ->
        if (cell == null) return@forEachIndexed
        val center = Offset(columnX(index % 7), w * (p.MONTH_FIRST_ROW + index / 7 * p.MONTH_ROW_PITCH))
        dayPaint.color = if (cell == date.dayOfMonth) {
            drawCircle(style.accent, w * p.MONTH_TODAY / 2f, center); style.onAccent.toArgb()
        } else style.primary.toArgb()
        drawText(dayPaint, cell.toString(), center.x, baselineFor(center.y, cap))
    }
}

/** "九月十七" (lunar month and day, no year) on a Chinese locale; null elsewhere. */
internal fun standbyLunarMonthDay(date: LocalDate, locale: Locale): String? =
    lunarDateOf(date, locale)?.substringAfter("年")

// Calendar --------------------------------------------------------------------------------------------------

@Composable
internal fun StandbyCalendarFace(frame: StandbyFrame, style: StandbyCalendarStyle, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    Canvas(modifier.fillMaxSize().testTag("standby-full-calendar").semantics { contentDescription = standbyDescription(frame) }) {
        drawRect(style.background)
        val w = size.width; val h = size.height; val g = StandbyGeometry
        val date = frame.minute.toLocalDate()
        val start = standbyGridStart(date, frame.firstDay)
        val today = java.time.temporal.ChronoUnit.DAYS.between(start, date).toInt()
        val gridShift = standbyCutoutShiftX(Rect(w * g.CALENDAR_FIRST_COLUMN - w * g.CALENDAR_COLUMN_PITCH / 2f,
            h * (g.CALENDAR_WEEKDAY_Y - g.CALENDAR_WEEKDAY_CAP), w * (g.CALENDAR_FIRST_COLUMN + 6.5f * g.CALENDAR_COLUMN_PITCH),
            h * (g.CALENDAR_FIRST_ROW + 5.5f * g.CALENDAR_ROW_PITCH)), frame.cutouts, frame.cutoutMargin, w)
        fun columnX(index: Int) = w * (g.CALENDAR_FIRST_COLUMN + index * g.CALENDAR_COLUMN_PITCH) + gridShift
        fun rowY(index: Int) = h * (g.CALENDAR_FIRST_ROW + index * g.CALENDAR_ROW_PITCH)
        translate(frame.shift.x, frame.shift.y) {
            val titleCap = h * g.CALENDAR_TITLE_CAP
            val title = tools.text(StandbyFonts.sans(600), titleCap, style.title, sample = "E")
            val monthTitle = standbyMonthTitle(date, frame.locale)
            drawText(title, monthTitle, w * g.CALENDAR_TITLE_LEFT, baselineFor(h * g.CALENDAR_TITLE_Y, titleCap))
            // Photo 2: the lunar month and day follow the title in the grid's blue.
            standbyLunarMonthDay(date, frame.locale)?.let { lunar ->
                val x = w * g.CALENDAR_TITLE_LEFT + title.measureText(monthTitle) + titleCap * .5f
                title.color = style.lunar.toArgb()
                drawText(title, lunar, x, baselineFor(h * g.CALENDAR_TITLE_Y, titleCap))
                title.color = style.title.toArgb()
            }
            val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
            title.textAlign = Paint.Align.RIGHT
            drawText(title, "$hours:$minutes", w * g.CALENDAR_TIME_RIGHT, baselineFor(h * g.CALENDAR_TITLE_Y, titleCap))

            // Giant serif day numeral behind today's column, top at the weekday row, foot above the last row.
            // Two digits are condensed, then shortened, to ≤ 2.5 pitches and stay inside the grid (never off-screen).
            val numeralText = date.dayOfMonth.toString()
            if (style.wideNumeral) {
                drawWideCalendarNumeral(tools, numeralText, style, columnX(0) - w * g.CALENDAR_COLUMN_PITCH / 2f,
                    columnX(6) + w * g.CALENDAR_COLUMN_PITCH / 2f)
            } else {
            val fullCap = h * (g.CALENDAR_NUMERAL_BOTTOM - g.CALENDAR_NUMERAL_TOP)
            val numeralPaint = tools.text(StandbyFonts.serif(900), fullCap, style.numeral, sample = numeralText)
            // Display setting: proportional figures, tracked tight, so "11" doesn't leave a column-wide gap.
            numeralPaint.fontFeatureSettings = "pnum"
            numeralPaint.letterSpacing = -.05f
            numeralPaint.getTextBounds(numeralText, 0, numeralText.length, tools.bounds)
            val pitch = w * g.CALENDAR_COLUMN_PITCH
            val fit = standbyCalendarNumeralFit(tools.bounds.width().toFloat(), fullCap, pitch * g.CALENDAR_NUMERAL_MAX_PITCHES)
            numeralPaint.textSize *= fit.cap / fullCap
            numeralPaint.textScaleX = fit.scaleX
            numeralPaint.getTextBounds(numeralText, 0, numeralText.length, tools.bounds)
            val numeralLeft = standbyNumeralLeft(columnX(today % 7), tools.bounds.width().toFloat(),
                columnX(0) - pitch / 2f, columnX(6) + pitch / 2f)
            val numeralX = numeralLeft - tools.bounds.left
            val numeralBaseline = h * g.CALENDAR_NUMERAL_BOTTOM - tools.bounds.bottom
            tools.path.reset()
            numeralPaint.getTextPath(numeralText, 0, numeralText.length, numeralX, numeralBaseline, tools.path)
            drawContext.canvas.nativeCanvas.drawPath(tools.path, numeralPaint)
            }

            val letters = standbyWeekdayLetters(frame.firstDay, frame.locale)
            val digitCap = h * g.CALENDAR_DIGIT_CAP
            val weekdayCap = h * g.CALENDAR_WEEKDAY_CAP
            fun grid(over: Boolean) {
                val letterPaint = tools.text(StandbyFonts.sans(600), weekdayCap, if (over) style.dayOnNumeral else style.weekday,
                    Paint.Align.CENTER, sample = "M")
                letters.forEachIndexed { index, letter -> drawText(letterPaint, letter, columnX(index), baselineFor(h * g.CALENDAR_WEEKDAY_Y, weekdayCap)) }
                val dayPaint = tools.text(StandbyFonts.sans(600), digitCap, style.day, Paint.Align.CENTER)
                for (index in 0 until 42) {
                    if (index == today) continue
                    val cell = start.plusDays(index.toLong())
                    dayPaint.color = when {
                        over && cell.month != date.month -> style.dayOnNumeral.copy(alpha = .4f)
                        over -> style.dayOnNumeral
                        cell.month != date.month -> style.outside
                        else -> style.day
                    }.toArgb()
                    drawText(dayPaint, cell.dayOfMonth.toString(), columnX(index % 7), baselineFor(rowY(index / 7), digitCap))
                }
            }
            grid(over = false)
            // Digits over the red numeral flip to ink, exactly within the glyph outline.
            if (!style.wideNumeral) {
                val canvas = drawContext.canvas.nativeCanvas
                canvas.save(); canvas.clipPath(tools.path); grid(over = true); canvas.restore()
            }
            val todayCenter = Offset(columnX(today % 7), rowY(today / 7))
            drawCircle(style.today, h * g.CALENDAR_TODAY / 2f, todayCenter)
            val todayPaint = tools.text(StandbyFonts.sans(600), digitCap, style.onToday, Paint.Align.CENTER)
            drawText(todayPaint, date.dayOfMonth.toString(), todayCenter.x, baselineFor(todayCenter.y, digitCap))
        }
    }
}

/**
 * Photo 2's date: rounded heavy sans, translucent so the month reads through it, its foot on the fifth week row and its
 * top under the weekday letters, centred on the grid and as wide as [CALENDAR_WIDE_NUMERAL_MAX] of it at most.
 */
private fun DrawScope.drawWideCalendarNumeral(tools: StandbyPaint, text: String, style: StandbyCalendarStyle,
    gridLeft: Float, gridRight: Float) {
    val h = size.height; val g = StandbyGeometry
    val top = h * (g.CALENDAR_WEEKDAY_Y + g.CALENDAR_WEEKDAY_CAP * .2f)
    val bottom = h * (g.CALENDAR_FIRST_ROW + 4.45f * g.CALENDAR_ROW_PITCH)
    val paint = tools.text(StandbyFonts.sans(700), bottom - top, style.numeral, sample = text)
    paint.letterSpacing = -.02f
    paint.getTextBounds(text, 0, text.length, tools.bounds)
    val maxWidth = (gridRight - gridLeft) * CALENDAR_WIDE_NUMERAL_MAX
    if (tools.bounds.width() > maxWidth) {
        paint.textScaleX = maxWidth / tools.bounds.width()
        paint.getTextBounds(text, 0, text.length, tools.bounds)
    }
    val x = (gridLeft + gridRight) / 2f - tools.bounds.exactCenterX()
    drawText(paint, text, x, bottom - tools.bounds.bottom)
}

internal const val CALENDAR_WIDE_NUMERAL_MAX = .8f

// Photos ----------------------------------------------------------------------------------------------------

@Composable
internal fun StandbyPhotoFace(frame: StandbyFrame, photo: ImageBitmap?, night: Boolean, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    val nightFilter = remember { ColorFilter.colorMatrix(ColorMatrix(standbyNightPhotoMatrix)) }
    Canvas(modifier.fillMaxSize().testTag("standby-photo").semantics { contentDescription = standbyDescription(frame) }) {
        drawRect(Color.Black)
        if (photo != null && photo.width > 0 && photo.height > 0) {
            val crop = wallpaperSourceCrop(photo.width, photo.height, size.width.toInt().coerceAtLeast(1),
                size.height.toInt().coerceAtLeast(1), rightAnchored = false)
            drawImage(photo, IntOffset(crop.x, crop.y), IntSize(crop.width, crop.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()), colorFilter = if (night) nightFilter else null)
        }
        val w = size.width; val h = size.height; val g = StandbyGeometry
        val ink = if (night) StandbyStyles.nightRed else Color.White
        translate(frame.shift.x, frame.shift.y) {
            val date = frame.minute.toLocalDate()
            val dateText = if (frame.zh) "${date.monthValue}月${date.dayOfMonth}日 ${date.dayOfWeek.getDisplayName(TextStyle.SHORT, frame.locale)}"
                else "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, frame.locale)} ${date.month.getDisplayName(TextStyle.SHORT, frame.locale)} ${date.dayOfMonth}"
            val datePaint = tools.text(StandbyFonts.sans(600), h * g.PHOTO_DATE_CAP, ink.copy(alpha = .9f), Paint.Align.RIGHT, sample = "E")
            if (!night) datePaint.setShadowLayer(h * .02f, 0f, h * .004f, android.graphics.Color.argb(90, 0, 0, 0))
            drawText(datePaint, dateText, w * g.PHOTO_RIGHT, baselineFor(h * g.PHOTO_DATE_Y, h * g.PHOTO_DATE_CAP))
            val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
            val timePaint = tools.text(StandbyFonts.sans(600), h * g.PHOTO_TIME_CAP, ink, Paint.Align.RIGHT)
            if (!night) timePaint.setShadowLayer(h * .03f, 0f, h * .006f, android.graphics.Color.argb(90, 0, 0, 0))
            drawText(timePaint, "$hours:$minutes", w * g.PHOTO_RIGHT, h * g.PHOTO_TIME_BASELINE)
        }
    }
}
