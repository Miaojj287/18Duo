package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LunarDateTest {
    @Test fun `the owner's iPhone line for 2026-10-21`() {
        // 2026 is 丙午 (cycle year 43: 1984 甲子 = 1); the ninth month, the twelfth day.
        assertEquals("丙午年九月十二", LunarDate.text(43, 9, false, 12))
    }

    @Test fun `month and day names follow ICU's zh data`() {
        assertEquals("甲子年正月初一", LunarDate.text(1, 1, false, 1))
        assertEquals("癸亥年腊月三十", LunarDate.text(60, 12, false, 30))
        assertEquals("乙巳年冬月二十", LunarDate.text(42, 11, false, 20))
        assertEquals("乙巳年闰六月廿九", LunarDate.text(42, 6, true, 29))
    }

    @Test fun `out-of-range fields give no line`() {
        assertNull(LunarDate.text(0, 1, false, 1))
        assertNull(LunarDate.text(43, 13, false, 1))
        assertNull(LunarDate.text(43, 9, false, 31))
    }
}
