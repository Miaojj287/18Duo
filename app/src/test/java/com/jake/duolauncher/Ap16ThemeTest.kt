package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ap16ThemeTest {
    @Test fun statusGlyphNamesFollowTheThemeFiles() {
        assertEquals("status/signal_4", Ap16Status.signal(4, airplane = false, dark = false))
        assertEquals("status/signal_flightmode_dark", Ap16Status.signal(2, airplane = true, dark = true))
        assertEquals("status/signal_null", Ap16Status.signal(null, airplane = false, dark = false))
        assertEquals("status/wifi_0_dark", Ap16Status.wifi(-3, dark = true))
        assertEquals("battery_charge_digit", Ap16Status.battery(charging = true, powerSave = true, dark = false))
        assertEquals("battery_power_save_digit_dark", Ap16Status.battery(charging = false, powerSave = true, dark = true))
    }

    @Test fun controlCenterGridScalesToTheColumnWithoutOverflowingTheHeight() {
        // Cover: 1168 px wide, 2.75 px/dp; the theme's 870 units become ≈ 80 % of the column.
        val cover = Ap16ControlGrid.scale(1080, 1500, 2.75f, rows = 2)
        assertTrue(cover * Ap16ControlGrid.HEIGHT <= 1500f)
        assertTrue(cover * Ap16ControlGrid.WIDTH <= 1080f)
        // A short window: the height decides.
        val short = Ap16ControlGrid.scale(1080, 900, 2.75f, rows = 2)
        assertEquals(900f / Ap16ControlGrid.HEIGHT, short, 1e-4f)
    }

    @Test fun statusBandColorIgnoresATitleOnTheToolbar() {
        val bar = 0xFF2266AA.toInt()
        val samples = IntArray(24) { if (it in 9..12) 0xFFFFFFFF.toInt() else bar }
        assertEquals(bar, statusBandColor(samples))
    }

    @Test fun lockWidgetKeysSurviveReorder() {
        val row = listOf(LockWidgetKind.BATTERY, LockWidgetKind.CALENDAR_DATE, LockWidgetKind.BATTERY)
        assertEquals(listOf("battery#0", "calendar#0", "battery#1"), lockWidgetKeys(row))
        val moved = LockWidgetSlots.move(row, 2, 1)
        assertEquals(listOf("battery#0", "battery#1", "calendar#0"), lockWidgetKeys(moved))
    }

    @Test fun sizeHandleGrowsWhenDraggedOutwardInAnyDirection() {
        assertTrue(LockClockSize.handleTravel(30f, 0f) >= 30f)
        assertTrue(LockClockSize.handleTravel(0f, 30f) >= 30f)
        assertTrue(LockClockSize.handleTravel(20f, 20f) > 20f)
        assertTrue(LockClockSize.handleTravel(-25f, 5f) < 0f)
    }
}
