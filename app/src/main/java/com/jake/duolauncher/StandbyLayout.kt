package com.jake.duolauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * StandBy faces in iPhone Duo order. Round 24 (owner photos of a real iPhone Duo cover, 7-dot pager): Widgets
 * (clock + month + weather) first, Analog third, Weather sixth; Digital, Calendar and Photos keep the other slots.
 */
internal enum class StandbyFace(val key: String) {
    WIDGETS("widgets"), DIGITAL("digital"), ANALOG("analog"), CALENDAR("calendar"), PHOTO("photo"), WEATHER("weather");
    companion object {
        /** Owner photo 4 (the tent-pose target) is the Analog face, so a first StandBy opens there. */
        val DEFAULT = ANALOG
        fun of(key: String?): StandbyFace = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Apple's StandBy geometry as fractions of the landscape panel (W = width, H = height). Measured from
 * reference/user-photos/4.png (black Analog face) and standby.mp4 at 0.2 s (Digital), 5.8 s (Calendar) and
 * 7.4 s (yellow Analog); perspective was removed using the Duo cover's 1.455 aspect ratio.
 */
internal object StandbyGeometry {
    // Analog face (photo 4 / video 7.4 s): rounded-square dial on the right, date column on the left.
    const val ANALOG_CLOCK_X = .66f
    const val ANALOG_CLOCK_Y = .495f
    const val ANALOG_CLOCK_SIDE_H = .79f
    const val ANALOG_CLOCK_SIDE_MAX_W = .56f
    const val ANALOG_COLUMN_LEFT = .058f
    const val ANALOG_HEADER_Y = .16f
    const val ANALOG_HEADER_CAP = .057f
    const val ANALOG_NUMERAL_Y = .33f
    const val ANALOG_NUMERAL_CAP = .222f
    const val MINI_FIRST_COLUMN = .07f
    const val MINI_COLUMN_PITCH = .0376f
    const val MINI_LAST_ROW = .895f
    const val MINI_ROW_PITCH = .057f
    const val MINI_WEEKDAY_ABOVE_ROW = .056f
    const val MINI_LABEL_ABOVE_WEEKDAY = .055f
    const val MINI_LABEL_LEFT = .062f
    const val MINI_CAP = .022f
    const val MINI_TODAY = .052f

    // Dial internals, as fractions of the dial side.
    const val DIAL_EXPONENT = 5f
    /** Hour ticks are long except at 12/3/6/9, where the numerals sit (both references). */
    const val DIAL_HOUR_TICK = .16f
    const val DIAL_CARDINAL_TICK = .095f
    const val DIAL_MINUTE_TICK = .045f
    const val DIAL_HOUR_TICK_WIDTH = .016f
    const val DIAL_MINUTE_TICK_WIDTH = .008f
    const val DIAL_NUMERAL_CAP = .11f
    const val DIAL_NUMERAL_RADIUS_VERTICAL = .285f
    const val DIAL_NUMERAL_RADIUS_HORIZONTAL = .31f
    const val HAND_HOUR = .30f
    const val HAND_MINUTE = .49f
    const val HAND_SECOND = .49f
    const val HAND_SECOND_TAIL = .085f
    const val HAND_WIDTH = .029f
    const val HAND_NECK = .06f
    const val HAND_NECK_WIDTH = .011f
    const val HAND_SECOND_WIDTH = .007f
    const val HUB_RADIUS = .027f
    const val HUB_STROKE = .009f

    // Digital face (video 0.2 s): "WED 1" top-left, condensed heavy time filling 60 % of the height.
    const val DIGITAL_DATE_LEFT = .091f
    const val DIGITAL_DATE_Y = .174f
    const val DIGITAL_DATE_CAP = .04f
    const val DIGITAL_TIME_CAP = .60f
    const val DIGITAL_TIME_BASELINE = .866f
    const val DIGITAL_TIME_X = .495f
    /** "9:41" ink width ÷ cap height in Apple's frame once the tilted screen is restored to its 1.466 aspect. */
    const val DIGITAL_REFERENCE_ASPECT = 1.58f
    const val DIGITAL_MAX_WIDTH = .86f

    // Calendar face (video 5.8 s): white month sheet with a giant red date numeral behind today's column.
    const val CALENDAR_TITLE_LEFT = .09f
    const val CALENDAR_TITLE_Y = .102f
    const val CALENDAR_TITLE_CAP = .052f
    const val CALENDAR_TIME_RIGHT = .893f
    const val CALENDAR_WEEKDAY_Y = .217f
    const val CALENDAR_WEEKDAY_CAP = .05f
    const val CALENDAR_FIRST_COLUMN = .129f
    const val CALENDAR_COLUMN_PITCH = .121f
    const val CALENDAR_FIRST_ROW = .333f
    const val CALENDAR_ROW_PITCH = .1165f
    const val CALENDAR_DIGIT_CAP = .058f
    const val CALENDAR_TODAY = .127f
    const val CALENDAR_NUMERAL_TOP = .232f
    const val CALENDAR_NUMERAL_BOTTOM = .885f
    /**
     * New York's figures are narrower than the Noto Serif stand-in: at the same 65 %H height Apple's "1" is 20.8 %W
     * (1.7 pitches, video 5.8 s with perspective removed), Noto Serif Bold's 25.9 %W.
     */
    const val CALENDAR_NUMERAL_FONT_SCALE_X = .8f
    /** A date wider than this (two digits) is fitted so it stays behind today's column and its neighbours. */
    const val CALENDAR_NUMERAL_MAX_PITCHES = 2.6f
    /** Two digits condense first (iOS 26's tall Lock Screen numerals), never narrower than this… */
    const val CALENDAR_NUMERAL_MIN_SCALE_X = .62f
    /** …then shorten, standing on the same foot line, never below this fraction of Apple's height. */
    const val CALENDAR_NUMERAL_MIN_HEIGHT = .6f

    // Photos face: date over time in the top-trailing corner.
    const val PHOTO_RIGHT = .94f
    const val PHOTO_DATE_Y = .115f
    const val PHOTO_DATE_CAP = .038f
    const val PHOTO_TIME_BASELINE = .29f
    const val PHOTO_TIME_CAP = .115f

    // Page indicator (video 3.4 s / 5.8 s): a glass capsule centred at 94.6 % H.
    const val DOTS_Y = .946f
}

internal data class StandbyMiniCalendarLayout(
    val labelLeft: Float, val labelY: Float, val cap: Float,
    val firstColumnX: Float, val columnPitch: Float,
    val weekdayY: Float, val firstRowY: Float, val rowPitch: Float,
    val todayDiameter: Float, val rows: Int,
) {
    fun columnX(index: Int) = firstColumnX + index * columnPitch
    fun rowY(index: Int) = firstRowY + index * rowPitch
    /** Ink extent: label cap top to the bottom of today's disc on the last row. */
    val bounds: Rect get() = Rect(minOf(labelLeft, firstColumnX - columnPitch / 2f), labelY - cap / 2f,
        columnX(6) + columnPitch / 2f, rowY(rows - 1) + todayDiameter / 2f)
    fun shifted(dx: Float) = copy(labelLeft = labelLeft + dx, firstColumnX = firstColumnX + dx)
}

internal data class StandbyAnalogLayout(
    val clockCenter: Offset,
    val clockSide: Float,
    val headerLeft: Float,
    val headerY: Float,
    val headerCap: Float,
    val numeralY: Float,
    val numeralCap: Float,
    val calendar: StandbyMiniCalendarLayout,
) {
    val clockBounds: Rect get() = Rect(clockCenter, clockSide / 2f)
}

/** Analog face layout; only blocks that intersect a real cutout move, and only horizontally by the minimum. */
internal fun standbyAnalogLayout(width: Float, height: Float, calendarRows: Int,
    cutouts: List<Rect> = emptyList(), margin: Float = 0f): StandbyAnalogLayout {
    val g = StandbyGeometry
    val side = minOf(height * g.ANALOG_CLOCK_SIDE_H, width * g.ANALOG_CLOCK_SIDE_MAX_W)
    var center = Offset(width * g.ANALOG_CLOCK_X, height * g.ANALOG_CLOCK_Y)
    center += Offset(standbyCutoutShiftX(Rect(center, side / 2f), cutouts, margin, width), 0f)
    val rows = calendarRows.coerceIn(4, 6)
    val lastRow = height * g.MINI_LAST_ROW
    val rowPitch = height * g.MINI_ROW_PITCH
    val firstRow = lastRow - (rows - 1) * rowPitch
    val weekday = firstRow - height * g.MINI_WEEKDAY_ABOVE_ROW
    var calendar = StandbyMiniCalendarLayout(
        labelLeft = width * g.MINI_LABEL_LEFT, labelY = weekday - height * g.MINI_LABEL_ABOVE_WEEKDAY,
        cap = height * g.MINI_CAP, firstColumnX = width * g.MINI_FIRST_COLUMN, columnPitch = width * g.MINI_COLUMN_PITCH,
        weekdayY = weekday, firstRowY = firstRow, rowPitch = rowPitch,
        todayDiameter = height * g.MINI_TODAY, rows = rows,
    )
    calendar = calendar.shifted(standbyCutoutShiftX(calendar.bounds, cutouts, margin, width))
    var headerLeft = width * g.ANALOG_COLUMN_LEFT
    val header = Rect(headerLeft, height * (g.ANALOG_HEADER_Y - g.ANALOG_HEADER_CAP / 2f),
        calendar.columnX(6), height * (g.ANALOG_NUMERAL_Y + g.ANALOG_NUMERAL_CAP / 2f))
    headerLeft += standbyCutoutShiftX(header, cutouts, margin, width)
    return StandbyAnalogLayout(center, side, headerLeft, height * g.ANALOG_HEADER_Y, height * g.ANALOG_HEADER_CAP,
        height * g.ANALOG_NUMERAL_Y, height * g.ANALOG_NUMERAL_CAP, calendar)
}

/**
 * Smallest horizontal move that takes [element] clear of every cutout (inflated by [margin]), always away from
 * the edge the cutout sits on. Xiaomi's cover camera is top-centre in portrait, so in the tent pose it lands
 * mid-height on whichever short edge is up — the HIG rule is to move only what actually collides.
 */
internal fun standbyCutoutShiftX(element: Rect, cutouts: List<Rect>, margin: Float, screenWidth: Float): Float {
    var dx = 0f
    repeat(cutouts.size.coerceAtMost(4)) {
        var moved = false
        for (cutout in cutouts) {
            if (cutout.isEmpty) continue
            val keepOut = cutout.inflate(margin)
            val current = element.translate(dx, 0f)
            if (!current.overlaps(keepOut)) continue
            dx += if (keepOut.center.x >= screenWidth / 2f) keepOut.left - current.right else keepOut.right - current.left
            moved = true
        }
        if (!moved) return dx
    }
    return dx
}

/** Calendar numeral size: cap (ink) height, horizontal scale, and the resulting ink width, px. */
internal data class StandbyNumeralFit(val cap: Float, val scaleX: Float, val width: Float)

/**
 * The Calendar face's giant date (video 5.8 s): Apple's single "1" stands under the weekday letters on the line
 * above the last row, ≈1.7 column pitches wide. The stand-in serif is drawn at New York's width ([fontScaleX]); a
 * two-digit date at that size still spans 4–5 columns and ran off the screen (visual review P1-8), so a date wider
 * than [maxWidth] is condensed further (down to [StandbyGeometry.CALENDAR_NUMERAL_MIN_SCALE_X], iOS 26's tall Lock
 * Screen figures) and only then shortened (down to [StandbyGeometry.CALENDAR_NUMERAL_MIN_HEIGHT] of [fullCap]).
 * [inkWidth] is the unscaled ink width at [fullCap].
 */
internal fun standbyCalendarNumeralFit(inkWidth: Float, fullCap: Float, maxWidth: Float,
    fontScaleX: Float = StandbyGeometry.CALENDAR_NUMERAL_FONT_SCALE_X): StandbyNumeralFit {
    val g = StandbyGeometry
    if (inkWidth <= 0f || fullCap <= 0f) return StandbyNumeralFit(fullCap, fontScaleX, 0f)
    if (inkWidth * fontScaleX <= maxWidth) return StandbyNumeralFit(fullCap, fontScaleX, inkWidth * fontScaleX)
    val scaleX = (maxWidth / inkWidth).coerceIn(g.CALENDAR_NUMERAL_MIN_SCALE_X, fontScaleX)
    val cap = fullCap * (maxWidth / (inkWidth * scaleX)).coerceIn(g.CALENDAR_NUMERAL_MIN_HEIGHT, 1f)
    return StandbyNumeralFit(cap, scaleX, inkWidth * scaleX * cap / fullCap)
}

/**
 * Left edge for numeral ink of [width] centred on today's column ([centerX]), kept inside the calendar grid
 * ([gridLeft]…[gridRight]) so it is never clipped by the screen edge; a numeral wider than the grid is centred on it.
 */
internal fun standbyNumeralLeft(centerX: Float, width: Float, gridLeft: Float, gridRight: Float): Float {
    if (width >= gridRight - gridLeft) return (gridLeft + gridRight - width) / 2f
    return (centerX - width / 2f).coerceIn(gridLeft, gridRight - width)
}

/** Radius from the dial centre to the rounded-square tick ring (superellipse |x|ⁿ+|y|ⁿ=aⁿ) at a clock angle. */
internal fun standbyTickRadius(angleDegrees: Float, halfSide: Float, exponent: Float = StandbyGeometry.DIAL_EXPONENT): Float {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val sum = abs(sin(radians)).pow(exponent.toDouble()) + abs(cos(radians)).pow(exponent.toDouble())
    return (halfSide / sum.pow(1.0 / exponent)).toFloat()
}

internal data class StandbyHandAngles(val hour: Float, val minute: Float, val second: Float)

/** Clockwise degrees from 12 o'clock. Hour and minute hands creep; the second hand ticks once a second. */
internal fun standbyHandAngles(hour: Int, minute: Int, second: Int): StandbyHandAngles {
    val s = second.coerceIn(0, 59)
    val m = minute.coerceIn(0, 59) + s / 60f
    return StandbyHandAngles(((hour % 12 + 12) % 12 + m / 60f) * 30f, m * 6f, s * 6f)
}

/** Unit vector for a clock angle in screen coordinates (y down). */
internal fun standbyClockVector(angleDegrees: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset(sin(radians).toFloat(), -cos(radians).toFloat())
}

/** Locale-aware week start (iOS follows the region: Sunday in the US, Monday in mainland China). */
internal fun standbyGridStart(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.SUNDAY): LocalDate {
    val first = date.withDayOfMonth(1)
    return first.minusDays(((first.dayOfWeek.value - firstDay.value + 7) % 7).toLong())
}

/** 42 cells (6 weeks) of the current month; null for days of neighbouring months. */
internal fun standbyMonthCells(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.SUNDAY): List<Int?> {
    val offset = ((date.withDayOfMonth(1).dayOfWeek.value - firstDay.value + 7) % 7)
    return List(42) { index -> (index - offset + 1).takeIf { it in 1..date.lengthOfMonth() } }
}

/** Week rows the month actually occupies (4–6); the mini calendar is bottom-anchored on that count. */
internal fun standbyMonthRows(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.SUNDAY): Int {
    val offset = ((date.withDayOfMonth(1).dayOfWeek.value - firstDay.value + 7) % 7)
    return (offset + date.lengthOfMonth() + 6) / 7
}

/**
 * Burn-in care for an always-on face: one step per minute around a 3×3 orbit of ±[amplitude], visiting every
 * position once per 9 minutes and starting at the centre. Small enough to be invisible, enough to rest pixels.
 */
internal fun standbyPixelShift(minute: Long, amplitude: Float): Offset {
    val step = Math.floorMod(minute, 9L).toInt()
    val (x, y) = PIXEL_ORBIT[step]
    return Offset(x * amplitude, y * amplitude)
}

private val PIXEL_ORBIT = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

/** Which short edge a cutout sits on, so edge chrome (variant dots) can take the other side. */
internal fun standbyCutoutOnRight(cutouts: List<Rect>, screenWidth: Float): Boolean? =
    cutouts.filterNot { it.isEmpty }.maxByOrNull { it.width * it.height }?.let { it.center.x >= screenWidth / 2f }

/**
 * Clockwise quarter turns that stand StandBy's landscape canvas upright in its window (interaction review S1):
 * 0 once Android has rotated the display to StandBy's side; 1 or 3 in a window that stays portrait (the platform
 * ignored the orientation request, the lock-screen window, the frames on either side of a rotation); 2 when the
 * display took the other landscape side. [target] and [display] are `Surface.ROTATION_*`; a null target is
 * "whichever landscape side", i.e. any landscape window as it is and ROTATION_90 in a portrait one.
 */
internal fun standbyQuarterTurns(target: Int?, display: Int, portraitWindow: Boolean): Int {
    if (target == null && !portraitWindow) return 0
    val turns = Math.floorMod((target ?: android.view.Surface.ROTATION_90) - display, 4)
    if ((turns % 2 == 1) == portraitWindow) return turns
    // Rotation constants that don't describe this panel (a landscape-natural inner screen): go by the window's shape.
    return if (!portraitWindow) 0 else if (target == android.view.Surface.ROTATION_270) 3 else 1
}

/** WindowInsets corners are in window coordinates; the face can turn inside a portrait window. */
internal data class StandbyCorners(val topLeft: Int, val topRight: Int, val bottomRight: Int, val bottomLeft: Int) {
    fun forCanvas(turns: Int): StandbyCorners = when (Math.floorMod(turns, 4)) {
        1 -> StandbyCorners(topRight, bottomRight, bottomLeft, topLeft)
        2 -> StandbyCorners(bottomRight, bottomLeft, topLeft, topRight)
        3 -> StandbyCorners(bottomLeft, topLeft, topRight, bottomRight)
        else -> this
    }
}
