package com.jake.duolauncher

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSignalMappingTest {
    @Test fun wifiUsesTheThreeElementSfGlyph() {
        val lit = (0..4).map { level ->
            (wifiSignalVisual(connected = true, level = level) as WifiSignalVisual.Connected).elements
                .also { assertEquals(WIFI_GLYPH_ELEMENTS, it.size) }
                .count { it == SignalElementEmphasis.LIT }
        }
        // A connected link always shows the wedge; full strength lights both arcs.
        assertEquals(listOf(1, 1, 2, 3, 3), lit)
    }

    @Test fun connectedUnknownWifiUsesNeutralElements() {
        val visual = wifiSignalVisual(connected = true, level = null) as WifiSignalVisual.Connected

        assertEquals(List(WIFI_GLYPH_ELEMENTS) { SignalElementEmphasis.NEUTRAL }, visual.elements)
    }

    @Test fun disconnectedWifiHasNoSignalLevel() {
        assertEquals(WifiSignalVisual.Disconnected, wifiSignalVisual(connected = false, level = 4))
    }

    @Test fun cellularBarsBecomeFourDots() {
        assertEquals(4, CELLULAR_DOTS)
        assertEquals(listOf(0, 1, 2, 3, 4, 4), listOf(0, 1, 2, 3, 4, 7).map { level ->
            (cellularSignalVisual(level, airplane = false) as CellularSignalVisual.Available).activeDots
        })
    }

    @Test fun accessibilityTextNeverMixesLanguages() {
        val status = DeviceStatus(battery = 80, charging = true, wifiConnected = true, wifiLevel = 3,
            cellularLevel = 2, cellularTechnology = CellularTechnology.FIVE_G)
        val now = java.time.LocalDateTime.of(2026, 10, 20, 9, 41)
        val zh = statusAccessibilityText(status, now, "9:41", locationInUse = true, chinese = true)
        val en = statusAccessibilityText(status, now, "9:41", locationInUse = true, chinese = false)
        assertTrue(zh, zh.contains("10月20日") && zh.contains("电池电量 80%") && zh.contains("正在充电"))
        assertTrue(zh, listOf("Battery", "Wi-Fi", "signal", "percent", "Tuesday", "October").none { zh.contains(it) })
        assertTrue(en, en.contains("Tuesday, October 20") && en.contains("Battery 80 percent, charging"))
        assertTrue(en, en.none { it in '\u4e00'..'\u9fff' })
        val airplane = statusAccessibilityText(DeviceStatus(airplane = true), now, "9:41", false, chinese = true)
        assertTrue(airplane.contains("飞行模式") && airplane.contains("电池电量不可用"))
    }

    @Test fun cellularUnavailableAndAirplaneRemainDistinctWithoutLitDots() {
        assertEquals(CellularSignalVisual.Unavailable, cellularSignalVisual(level = null, airplane = false))
        assertEquals(CellularSignalVisual.Airplane, cellularSignalVisual(level = 4, airplane = true))
        assertTrue(cellularSignalVisual(level = null, airplane = true) is CellularSignalVisual.Airplane)
    }

    @Test fun cellularTechnologyUsesCarrierDisplayOverrideBeforeCampedNetwork() {
        assertEquals(CellularTechnology.FIVE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
        assertEquals(CellularTechnology.FIVE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_NR,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.FOUR_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.THREE_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.TWO_G, cellularTechnology(TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        assertEquals(CellularTechnology.UNKNOWN, cellularTechnology(TelephonyManager.NETWORK_TYPE_UNKNOWN,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }
}
