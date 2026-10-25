package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenCustomizationTest {
    @Test fun `config round-trips through its JSON string`() {
        val c = LockScreenConfig(LockClockFont.CONDENSED, 0xFF0A84FF.toInt(), glass = false, stretch = .35f, widgets =
            listOf(LockWidgetKind.ALARM, LockWidgetKind.BATTERY, LockWidgetKind.WORLD_CLOCK), position = LockWidgetPosition.UNDER_DATE, align = LockTextAlign.RIGHT)
        val json = c.toJson()
        assertTrue(json, json.startsWith("{") && json.contains("\"font\":\"condensed\"") && json.contains("\"widgets\":[\"alarm\",\"battery\",\"worldclock\"]"))
        assertTrue(json, json.contains("\"align\":\"right\""))
        assertEquals(c, LockScreenConfig.fromJson(json))
        assertEquals(LockScreenConfig.DEFAULT, LockScreenConfig.fromJson(LockScreenConfig.DEFAULT.toJson()))
    }

    @Test fun `reader tolerates missing, damaged and over-full values`() {
        assertEquals(LockScreenConfig.DEFAULT, LockScreenConfig.fromJson(null))
        assertEquals(LockScreenConfig.DEFAULT, LockScreenConfig.fromJson(""))
        val odd = LockScreenConfig.fromJson("""{"font":"comic","color":"zz","stretch":7,"position":"left","widgets":["weather","weather","battery","nope"]}""")
        assertEquals(LockClockFont.DEFAULT, odd.font)
        assertEquals(LockClockColors.WHITE, odd.color)
        assertEquals(1f, odd.stretch, 0f)
        assertEquals(LockWidgetPosition.BOTTOM, odd.position)
        assertEquals(LockTextAlign.CENTER, odd.align)
        // Round 12 `align`: a round-11 string without it reads as centred; each key round-trips.
        assertEquals(LockTextAlign.CENTER, LockScreenConfig.fromJson("""{"font":"bold","position":"bottom"}""").align)
        assertEquals(LockTextAlign.LEFT, LockScreenConfig.fromJson("""{"align":"left"}""").align)
        // Round 12.4 lunar date: on unless stored as false (older configs have no key).
        assertEquals(true, LockScreenConfig.fromJson("""{"align":"left"}""").lunar)
        assertEquals(false, LockScreenConfig.fromJson("""{"lunar":false}""").lunar)
        assertEquals(false, LockScreenConfig.fromJson(LockScreenConfig(lunar = false).toJson()).lunar)
        assertEquals(LockTextAlign.CENTER, LockScreenConfig.fromJson("""{"align":"middle"}""").align)
        LockTextAlign.entries.forEach { assertEquals(it, LockScreenConfig.fromJson(LockScreenConfig(align = it).toJson()).align) }
        assertEquals(LockTextAlign.entries.size, LockTextAlign.entries.map { it.key }.toSet().size)
        // Two rectangular widgets fill the four units: the battery no longer fits, the unknown one is dropped.
        assertEquals(listOf(LockWidgetKind.WEATHER, LockWidgetKind.WEATHER), odd.widgets)
        assertEquals(emptyList<LockWidgetKind>(), LockScreenConfig.fromJson("""{"widgets":[]}""").widgets)
        // A colour without alpha is made opaque.
        assertEquals(0xFF112233.toInt(), LockScreenConfig.fromJson("""{"color":"00112233"}""").color)
    }

    @Test fun `four-unit slot model - circular one, rectangular two`() {
        val row = LockWidgetSlots.DEFAULT
        assertEquals(4, LockWidgetSlots.used(row))
        assertFalse(LockWidgetSlots.canAdd(row, LockWidgetKind.WORLD_CLOCK))
        assertSame(row, LockWidgetSlots.add(row, LockWidgetKind.WORLD_CLOCK))
        val less = LockWidgetSlots.remove(row, 0)
        assertEquals(listOf(LockWidgetKind.CALENDAR_DATE, LockWidgetKind.BATTERY), less)
        assertTrue(LockWidgetSlots.canAdd(less, LockWidgetKind.ALARM))
        assertFalse(LockWidgetSlots.canAdd(less + LockWidgetKind.WORLD_CLOCK, LockWidgetKind.ALARM))
        assertEquals(listOf(0, 2, 3), LockWidgetSlots.startUnits(row))
        assertEquals(listOf(LockWidgetKind.CALENDAR_DATE, LockWidgetKind.WEATHER, LockWidgetKind.BATTERY), LockWidgetSlots.move(row, 1, 0))
        assertSame(row, LockWidgetSlots.move(row, 1, 5))
        assertSame(row, LockWidgetSlots.remove(row, 9))
        assertEquals(listOf(0, 1), LockWidgetSlots.startUnits(listOf(LockWidgetKind.BATTERY, LockWidgetKind.ALARM)))
    }

    @Test fun `size handle stretches height to 2_2 and width less, never past the panel`() {
        assertEquals(1f, LockClockSize.heightScale(0f), 0f)
        assertEquals(2.2f, LockClockSize.heightScale(1f), 1e-6f)
        assertEquals(1.35f, LockClockSize.widthScale(1f), 1e-6f)
        assertTrue(LockClockSize.widthScale(.5f) < LockClockSize.heightScale(.5f))
        // Dragging the handle down by 1.2 digit heights goes from the default to the tallest; up again returns.
        val h = 64f
        assertEquals(1f, LockClockSize.stretchAfterDrag(0f, h * 1.2f, h), 1e-5f)
        assertEquals(.5f, LockClockSize.stretchAfterDrag(0f, h * .6f, h), 1e-5f)
        assertEquals(0f, LockClockSize.stretchAfterDrag(.5f, -h, h), 0f)
        assertEquals(.3f, LockClockSize.stretchAfterDrag(.3f, 10f, 0f), 0f)
        // Width: the wanted scale while it fits, the fitting scale once it would leave the panel.
        assertEquals(1.35f, LockClockSize.fittedWidthScale(1f, 200f, 400f), 1e-6f)
        assertEquals(1.2f, LockClockSize.fittedWidthScale(1f, 300f, 360f), 1e-6f)
        assertEquals(.5f, LockClockSize.fittedWidthScale(1f, 1000f, 100f), 0f)
    }

    @Test fun `colour row starts white and the hue slider gives saturated opaque colours`() {
        assertEquals(LockClockColors.WHITE, LockClockColors.swatches.first())
        assertTrue(LockClockColors.swatches.all { (it ushr 24) == 0xFF })
        assertEquals(0xFFFF0000.toInt(), LockClockColors.fromHue(0f, 1f))
        assertEquals(0xFF00FF00.toInt(), LockClockColors.fromHue(120f, 1f))
        assertEquals(0xFF0000FF.toInt(), LockClockColors.fromHue(240f, 1f))
        assertEquals(LockClockColors.WHITE, LockClockColors.fromHue(200f, 0f))
        assertEquals(LockClockColors.fromHue(30f, .5f), LockClockColors.fromHue(390f, .5f))
        val (hue, vibrancy) = LockClockColors.hueAndVibrancy(LockClockColors.fromHue(210f, .7f))
        assertEquals(210f, hue, 1f)
        assertEquals(.7f, vibrancy, .01f)
        assertEquals(0f, LockClockColors.hueAndVibrancy(LockClockColors.WHITE).second, 0f)
        assertNotEquals(LockClockColors.fromHue(0f, 1f), LockClockColors.fromHue(180f, 1f))
    }

    @Test fun `fonts and widgets have unique keys`() {
        assertEquals(LockClockFont.entries.size, LockClockFont.entries.map { it.key }.toSet().size)
        assertEquals(LockWidgetKind.entries.size, LockWidgetKind.entries.map { it.key }.toSet().size)
        assertEquals(LockClockFont.DEFAULT, LockClockFont.fromKey("missing"))
        assertEquals(LockWidgetKind.WEATHER, LockWidgetKind.fromKey("weather"))
        assertTrue(LockWidgetKind.WEATHER.units == 2 && LockWidgetKind.BATTERY.circular)
        assertTrue(LockClockFont.CONDENSED.widthScale < 1f && LockClockFont.EXPANDED.widthScale > 1f && LockClockFont.DIGITS.duoDigits)
    }

    @Test fun `a stretched clock switches to the condensed digit face once squeezing Inter distorts it more`() {
        assertFalse(LockClockFace.preferCondensed(1f, 1.49f))   // default size: Inter as designed
        assertTrue(LockClockFace.preferCondensed(.66f, .98f))   // the owner's 0.77 stretch (Core.29.3 screenshot)
        assertFalse(LockClockFace.preferCondensed(.9f, 1.34f))  // a slight stretch keeps Inter
        assertFalse(LockClockFace.preferCondensed(.66f, 0f))    // no measurement: keep the chosen face
        // iOS 26 weights: the default is Bold, 粗体 one step heavier.
        assertEquals(700, LockClockFont.DEFAULT.weight)
        assertTrue(LockClockFont.BOLD.weight > LockClockFont.DEFAULT.weight)
    }
}
