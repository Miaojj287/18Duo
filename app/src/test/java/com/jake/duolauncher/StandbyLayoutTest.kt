package com.jake.duolauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class StandbyLayoutTest {
    @Test fun `cover corners keep the small hinge side through canvas rotation`() {
        val portrait = StandbyCorners(28, 154, 154, 28)
        assertEquals(portrait, portrait.forCanvas(0))
        assertEquals(StandbyCorners(154, 154, 28, 28), portrait.forCanvas(1))
        assertEquals(StandbyCorners(154, 28, 28, 154), portrait.forCanvas(2))
        assertEquals(StandbyCorners(28, 28, 154, 154), portrait.forCanvas(3))
        assertEquals(portrait, portrait.forCanvas(4))
    }
    // Xiaomi cover in the tent pose: 1712 × 1168 px landscape; camera cutout 54 × 126 px mid-height on a short edge.
    private val w = 1712f
    private val h = 1168f
    private val rightCamera = Rect(w - 126f, 557f, w, 611f)
    private val leftCamera = Rect(0f, 557f, 126f, 611f)

    @Test fun `analog face uses photo 4 proportions on the cover panel`() {
        val layout = standbyAnalogLayout(w, h, 5)
        assertEquals(.66f * w, layout.clockCenter.x, .5f)
        assertEquals(.495f * h, layout.clockCenter.y, .5f)
        assertEquals(.79f * h, layout.clockSide, .5f)
        // Dial margins top/bottom/right are all ≈ 10 % H, as in the photo.
        val bounds = layout.clockBounds
        assertEquals(bounds.top, h - bounds.bottom, 12f)
        assertEquals(bounds.top, w - bounds.right, .03f * h)
        // Mini month bottom-anchored: a 5-row month's last row at 89.5 % H, label 55.6 % H (video 7.4 s).
        assertEquals(.895f * h, layout.calendar.rowY(4), .5f)
        assertEquals(.556f * h, layout.calendar.labelY, .01f * h)
        // The left column never reaches the dial.
        assertTrue(layout.calendar.bounds.right < bounds.left)
    }

    @Test fun `six week months lift the mini calendar by one row instead of overflowing`() {
        val five = standbyAnalogLayout(w, h, 5).calendar
        val six = standbyAnalogLayout(w, h, 6).calendar
        assertEquals(five.rowY(4), six.rowY(5), .01f)
        assertEquals(five.labelY - five.rowPitch, six.labelY, .01f)
        assertTrue(six.bounds.bottom < h)
    }

    @Test fun `only what intersects the camera moves and only by the minimum`() {
        val margin = 16.5f
        val plain = standbyAnalogLayout(w, h, 5)
        val right = standbyAnalogLayout(w, h, 5, listOf(rightCamera), margin)
        val overlap = plain.clockBounds.right - (rightCamera.left - margin)
        assertTrue("reference dial does touch the right camera keep-out", overlap > 0f)
        assertEquals(-overlap, right.clockCenter.x - plain.clockCenter.x, .01f)
        assertEquals(plain.clockCenter.y, right.clockCenter.y, 0f)
        assertEquals(plain.calendar, right.calendar)
        assertEquals(plain.headerLeft, right.headerLeft, 0f)
        // Camera on the left: a 5-row column passes between numeral and month, so nothing moves.
        val left = standbyAnalogLayout(w, h, 5, listOf(leftCamera), margin)
        assertEquals(plain, left)
        // A 6-row month lifts the label into the camera band: that block alone slides right, just clear.
        val six = standbyAnalogLayout(w, h, 6)
        val sixLeft = standbyAnalogLayout(w, h, 6, listOf(leftCamera), margin)
        assertTrue(sixLeft.calendar.firstColumnX > six.calendar.firstColumnX)
        assertEquals(leftCamera.right + margin, sixLeft.calendar.bounds.left, .01f)
        assertEquals(six.clockCenter, sixLeft.clockCenter)
        assertEquals(six.headerLeft, sixLeft.headerLeft, 0f)
    }

    @Test fun `cutout shift is zero without overlap and points away from the cutout edge`() {
        val element = Rect(500f, 500f, 700f, 700f)
        assertEquals(0f, standbyCutoutShiftX(element, emptyList(), 10f, w), 0f)
        assertEquals(0f, standbyCutoutShiftX(element, listOf(rightCamera, leftCamera), 10f, w), 0f)
        assertEquals(0f, standbyCutoutShiftX(element, listOf(Rect.Zero), 10f, w), 0f)
        val nearRight = Rect(1500f, 500f, 1650f, 650f)
        assertEquals(rightCamera.left - 10f - 1650f, standbyCutoutShiftX(nearRight, listOf(rightCamera), 10f, w), .01f)
        val nearLeft = Rect(50f, 500f, 200f, 650f)
        assertEquals(126f + 10f - 50f, standbyCutoutShiftX(nearLeft, listOf(leftCamera), 10f, w), .01f)
        assertEquals(true, standbyCutoutOnRight(listOf(rightCamera), w))
        assertEquals(false, standbyCutoutOnRight(listOf(leftCamera), w))
        assertNull(standbyCutoutOnRight(emptyList(), w))
    }

    @Test fun `rounded square tick ring is a superellipse`() {
        val half = 100f
        for (angle in listOf(0f, 90f, 180f, 270f)) assertEquals(half, standbyTickRadius(angle, half), .001f)
        val corner = half * sqrt(2f) / 2f.pow(1f / StandbyGeometry.DIAL_EXPONENT)
        assertEquals(corner, standbyTickRadius(45f, half), .01f)
        // Measured corner reach in standby.mp4 (7.4 s) ≈ 1.24 × half side.
        assertEquals(1.24f, standbyTickRadius(45f, 1f), .02f)
        var previous = 0f
        for (angle in 0..45) {
            val r = standbyTickRadius(angle.toFloat(), half)
            assertTrue(r >= previous - .0001f); previous = r
        }
        assertEquals(standbyTickRadius(30f, half), standbyTickRadius(60f, half), .001f)
    }

    @Test fun `hands creep like a real movement`() {
        val apple = standbyHandAngles(9, 41, 2)
        assertEquals(6f * (41 + 2 / 60f), apple.minute, .001f)
        assertEquals(30f * (9 + (41 + 2 / 60f) / 60f), apple.hour, .001f)
        assertEquals(12f, apple.second, 0f)
        assertEquals(StandbyHandAngles(0f, 0f, 0f), standbyHandAngles(0, 0, 0))
        assertEquals(StandbyHandAngles(0f, 0f, 0f), standbyHandAngles(12, 0, 0))
        assertEquals(105f, standbyHandAngles(15, 30, 0).hour, .001f)
        val up = standbyClockVector(0f); val right = standbyClockVector(90f)
        assertEquals(0f, up.x, 1e-6f); assertEquals(-1f, up.y, 1e-6f)
        assertEquals(1f, right.x, 1e-6f); assertEquals(0f, right.y, 1e-6f)
    }

    @Test fun `month grid follows the region's first weekday`() {
        val april = LocalDate.of(2026, 4, 1) // Wednesday
        assertEquals(LocalDate.of(2026, 3, 29), standbyGridStart(april))
        assertEquals(LocalDate.of(2026, 3, 30), standbyGridStart(april, DayOfWeek.MONDAY))
        assertEquals(3, standbyMonthCells(april).indexOf(1))
        assertEquals(2, standbyMonthCells(april, DayOfWeek.MONDAY).indexOf(1))
        assertEquals(5, standbyMonthRows(april))
        assertEquals(4, standbyMonthRows(LocalDate.of(2026, 2, 1)))
        assertEquals(6, standbyMonthRows(LocalDate.of(2026, 8, 31)))
        for (first in DayOfWeek.entries) for (date in listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 8, 1))) {
            val cells = standbyMonthCells(date, first)
            assertEquals((1..date.lengthOfMonth()).toList(), cells.filterNotNull())
            assertEquals(standbyGridStart(date, first).plusDays(cells.indexOf(1).toLong()), date.withDayOfMonth(1))
            assertEquals(first, standbyGridStart(date, first).dayOfWeek)
        }
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), standbyWeekdayLetters(DayOfWeek.SUNDAY, Locale.US))
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), standbyWeekdayLetters(DayOfWeek.MONDAY, Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `burn in orbit visits nine positions and stays within two dp`() {
        assertEquals(Offset.Zero, standbyPixelShift(0, 5.5f))
        val cycle = (0L until 9L).map { standbyPixelShift(it, 5.5f) }
        assertEquals(9, cycle.toSet().size)
        for (offset in cycle) assertTrue(offset.getDistance() <= 5.5f * sqrt(2f) + .001f)
        assertEquals(standbyPixelShift(3, 5.5f), standbyPixelShift(12, 5.5f))
        assertEquals(standbyPixelShift(8, 5.5f), standbyPixelShift(-1, 5.5f))
        // Consecutive minutes move at most one step on each axis: no visible jump.
        for (minute in 0L until 18L) {
            val a = standbyPixelShift(minute, 1f); val b = standbyPixelShift(minute + 1, 1f)
            assertTrue(kotlin.math.abs(a.x - b.x) <= 2f && kotlin.math.abs(a.y - b.y) <= 2f)
        }
    }

    @Test fun `clock and date strings match Apple's faces`() {
        fun at(time: String) = ZonedDateTime.parse("2026-04-01T$time+08:00[Asia/Shanghai]")
        assertEquals("9" to "41", standbyClockParts(at("09:41:00"), false))
        assertEquals("09" to "41", standbyClockParts(at("09:41:00"), true))
        assertEquals("12" to "05", standbyClockParts(at("00:05:00"), false))
        assertEquals("00" to "05", standbyClockParts(at("00:05:00"), true))
        assertEquals("1" to "05", standbyClockParts(at("13:05:00"), false))
        assertEquals("12" to "30", standbyClockParts(at("12:30:00"), false))
        val april = LocalDate.of(2026, 4, 1)
        assertEquals("WED 1", standbyDigitalDate(april, Locale.US))
        assertEquals("周三 1日", standbyDigitalDate(april, Locale.SIMPLIFIED_CHINESE))
        assertEquals("April 2026", standbyMonthTitle(april, Locale.US))
        assertEquals("2026年4月", standbyMonthTitle(april, Locale.SIMPLIFIED_CHINESE))
        val september = LocalDate.of(2026, 9, 9)
        assertEquals("Wed" to "Sep", standbyAnalogHeader(september, Locale.US))
        assertEquals("周三" to "9月", standbyAnalogHeader(september, Locale.SIMPLIFIED_CHINESE))
        assertEquals("SEPTEMBER", standbyMonthLabel(september, Locale.US))
        assertEquals("9月", standbyMonthLabel(september, Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun `face order follows the Duo cover photos and opens on the owner's analog face`() {
        assertEquals(listOf(StandbyFace.WIDGETS, StandbyFace.DIGITAL, StandbyFace.ANALOG, StandbyFace.CALENDAR, StandbyFace.PHOTO,
            StandbyFace.WEATHER), StandbyFace.entries.toList())
        assertEquals(StandbyFace.ANALOG, StandbyFace.of(null))
        assertEquals(StandbyFace.ANALOG, StandbyFace.of("nope"))
        assertEquals(StandbyFace.WEATHER, StandbyFace.of("weather"))
        assertEquals(StandbyFace.CALENDAR, StandbyFace.of("calendar"))
    }

    @Test fun `first options are Apple's and night styles emit only red`() {
        val analog = StandbyStyles.analog(0, false)
        assertEquals(androidx.compose.ui.graphics.Color.Black, analog.background)
        assertTrue(analog.accent.red > .9f && analog.accent.green > .7f && analog.accent.blue < .3f) // photo 4 yellow
        val digital = StandbyStyles.digital(0, false)
        assertTrue(digital.background.green > digital.background.red && digital.background.green > digital.background.blue)
        // Round 24 photo 2: navy sheet, wide translucent date.
        StandbyStyles.calendar(0, false).let { assertTrue(it.background.blue > .4f && it.background.red < .1f && it.wideNumeral) }
        assertTrue(StandbyStyles.calendar(1, false).background.red > .9f)
        for (style in listOf(StandbyStyles.nightAnalog.let { listOf(it.background, it.primary, it.accent, it.minorTick, it.secondaryText) },
                StandbyStyles.nightDigital.let { listOf(it.background, it.digits, it.date) },
                StandbyStyles.nightCalendar.let { listOf(it.background, it.title, it.weekday, it.day, it.outside, it.numeral, it.dayOnNumeral, it.today, it.onToday) }))
            for (color in style) assertTrue("$color", color.green < .12f && color.blue < .12f)
        for (face in StandbyFace.entries) assertEquals(androidx.compose.ui.graphics.Color.Black, StandbyStyles.background(face, 0, true))
        assertEquals(3, StandbyStyles.variants(StandbyFace.ANALOG, 0))
        assertEquals(1, StandbyStyles.variants(StandbyFace.PHOTO, 0))
        assertEquals(2, StandbyStyles.variants(StandbyFace.PHOTO, 2))
        // Variant indexes wrap rather than crash if a saved option disappears.
        assertEquals(StandbyStyles.analog(0, false), StandbyStyles.analog(3, false))
    }

    @Test fun `the veil rises over home on every StandBy surface and lifts only on home`() {
        for (surface in LauncherSurface.entries)
            assertEquals(if (surface == LauncherSurface.HOME) 0f else 1f, standbyVeilTarget(surface), 0f)
    }

    @Test fun `home stays composed but parked under every other surface`() {
        assertFalse(standbyParksHome(LauncherSurface.HOME))
        // Still drawn while the veil rises over it.
        assertFalse(standbyParksHome(LauncherSurface.ENTERING))
        assertTrue(standbyParksHome(LauncherSurface.ROTATING))
        assertTrue(standbyParksHome(LauncherSurface.STANDBY))
        assertTrue(standbyParksHome(LauncherSurface.EXITING))
        // Round 12: under the fold veil Home keeps laying out so the new panel's configuration is drawn before it lifts.
        assertFalse(standbyParksHome(LauncherSurface.FOLDING))
    }

    @Test fun `clock ticks reach minute readers only when the minute changes`() {
        // Code review P2-8d: faces without seconds observe StandbyClock.minute, so a tick must not invalidate them.
        val clock = StandbyClock(ZonedDateTime.parse("2026-04-01T09:41:58+08:00[Asia/Shanghai]"))
        var invalidations = 0
        val observer = androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
        observer.start()
        try {
            val read: (Unit) -> Unit = { invalidations++ }
            fun observe() = observer.observeReads(Unit, read) { clock.minute }
            observe()
            fun tick(time: String) {
                clock.now = ZonedDateTime.parse(time)
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            tick("2026-04-01T09:41:59.500+08:00[Asia/Shanghai]")
            assertEquals(0, invalidations)
            tick("2026-04-01T09:42:00.010+08:00[Asia/Shanghai]")
            assertEquals(1, invalidations)
            assertEquals(ZonedDateTime.parse("2026-04-01T09:42:00+08:00[Asia/Shanghai]"), clock.minute)
            observe()
            tick("2026-04-01T09:42:01+08:00[Asia/Shanghai]")
            assertEquals(1, invalidations)
        } finally { observer.stop() }
    }

    @Test fun `calendar numeral keeps Apple's single digit and fits two digits behind today's column`() {
        val g = StandbyGeometry
        val pitch = w * g.CALENDAR_COLUMN_PITCH
        val cap = h * (g.CALENDAR_NUMERAL_BOTTOM - g.CALENDAR_NUMERAL_TOP)
        val max = pitch * g.CALENDAR_NUMERAL_MAX_PITCHES
        // Noto Serif Bold's "1" is 25.9 %W at Apple's height; drawn at New York's width it is Apple's 1.7 pitches.
        val one = standbyCalendarNumeralFit(.259f * w, cap, max)
        assertEquals(cap, one.cap, 1e-3f)
        assertEquals(g.CALENDAR_NUMERAL_FONT_SCALE_X, one.scaleX, 1e-4f)
        assertEquals(1.7f, one.width / pitch, .05f)
        // Noto Serif "20" at Apple's height is ≈5 pitches (visual review 61): condensed, then shortened, to fit.
        val twenty = standbyCalendarNumeralFit(5f * pitch, cap, max)
        assertEquals(g.CALENDAR_NUMERAL_MIN_SCALE_X, twenty.scaleX, 1e-4f)
        assertEquals(max, twenty.width, .5f)
        assertTrue(twenty.cap < cap && twenty.cap >= cap * g.CALENDAR_NUMERAL_MIN_HEIGHT)
        // A slightly wide date only condenses and keeps Apple's full height.
        val narrow = standbyCalendarNumeralFit(1.1f * max / g.CALENDAR_NUMERAL_FONT_SCALE_X, cap, max)
        assertEquals(cap, narrow.cap, 1e-3f)
        assertEquals(max, narrow.width, .5f)
        assertTrue(narrow.scaleX < g.CALENDAR_NUMERAL_FONT_SCALE_X)
        // Absurd widths stop at the floors instead of collapsing.
        val huge = standbyCalendarNumeralFit(20f * pitch, cap, max)
        assertEquals(cap * g.CALENDAR_NUMERAL_MIN_HEIGHT, huge.cap, 1e-3f)
        assertEquals(0f, standbyCalendarNumeralFit(-1f, cap, max).width, 0f)
    }

    @Test fun `calendar numeral is centred on today's column but never leaves the grid`() {
        val g = StandbyGeometry
        val pitch = w * g.CALENDAR_COLUMN_PITCH
        val gridLeft = w * g.CALENDAR_FIRST_COLUMN - pitch / 2f
        val gridRight = w * (g.CALENDAR_FIRST_COLUMN + 6 * g.CALENDAR_COLUMN_PITCH) + pitch / 2f
        val width = 2.5f * pitch
        for (column in 0..6) {
            val center = w * (g.CALENDAR_FIRST_COLUMN + column * g.CALENDAR_COLUMN_PITCH)
            val left = standbyNumeralLeft(center, width, gridLeft, gridRight)
            assertTrue("column $column off the left", left >= gridLeft - .01f && left > 0f)
            assertTrue("column $column off the right", left + width <= gridRight + .01f && left + width < w)
            // Today's column always stays under the numeral.
            assertTrue(center in left..left + width)
            if (column in 1..5) assertEquals(center - width / 2f, left, .01f)
        }
        assertEquals((gridLeft + gridRight - 2000f) / 2f, standbyNumeralLeft(0f, 2000f, gridLeft, gridRight), .01f)
    }
}
