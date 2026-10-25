package com.jake.duolauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** Round 21: the owner's iPhone Duo Today View — Calendar's Chinese date block and month, Notes, Reminders, Maps. */
class Round21WidgetsTest {
    private val previous = Locale.getDefault()
    @After fun restore() = Locale.setDefault(previous)

    @Test fun `new widgets take their reserved ids and readable keys`() {
        assertEquals(emptyList<String>(), iosWidgetRegistryProblems(iosWidgetSpecs))
        val expected = mapOf(-184 to "native:notes:small", -185 to "native:notes:medium",
            -187 to "native:reminders:small", -188 to "native:reminders:medium",
            -190 to "native:maps:small", -191 to "native:maps:medium")
        expected.forEach { (id, key) ->
            assertEquals(key, nativeWidgetKey(id))
            assertEquals(id, parseNativeWidgetKey(key))
            assertTrue(id in NATIVE_WIDGET_ID_RANGE)
        }
        assertEquals(NativeWidgetKind.NOTES, nativeWidgetKind(-184))
        assertEquals(NativeWidgetKind.REMINDERS, nativeWidgetKind(-188))
        assertEquals(NativeWidgetKind.MAPS, nativeWidgetKind(-191))
        // The Calendar widgets the owner's layout places keep their Core.17 identities.
        assertEquals("native:calendar:small", nativeWidgetKey(-108))
        assertEquals("native:calendar:medium", nativeWidgetKey(-109))
    }

    @Test fun `gallery rows join the matching installed apps`() {
        assertEquals(NativeWidgetKind.NOTES, nativeKindForWidgetApp("com.miui.notes", "笔记"))
        assertEquals(NativeWidgetKind.MAPS, nativeKindForWidgetApp("com.autonavi.minimap", "高德地图"))
        assertEquals(NativeWidgetKind.MAPS, nativeKindForWidgetApp("com.example.maps", "Maps"))
        assertEquals(NativeWidgetKind.REMINDERS, nativeKindForWidgetApp("com.example", "提醒事项"))
    }

    @Test fun `lunar day labels stack two characters and name a new month`() {
        // 25 Oct 2026 is 九月十六 (the owner's widget: 十 over 六).
        assertEquals("十六", LunarDate.dayLabel(9, false, 16))
        assertEquals("初五", LunarDate.dayLabel(9, false, 5))
        assertEquals("廿三", LunarDate.dayLabel(9, false, 23))
        assertEquals("十月", LunarDate.dayLabel(10, false, 1))
        assertEquals("冬月", LunarDate.dayLabel(11, false, 1))
        assertEquals("腊月", LunarDate.dayLabel(12, false, 1))
        assertEquals("初一", LunarDate.dayLabel(6, true, 1))
        assertTrue((1..30).all { LunarDate.dayLabel(3, false, it)!!.length == 2 })
        assertNull(LunarDate.dayLabel(13, false, 1))
        assertNull(LunarDate.dayLabel(1, false, 31))
    }

    @Test fun `short Chinese weekdays`() {
        assertEquals("周日", chineseShortWeekday(DayOfWeek.SUNDAY))
        assertEquals("周一", chineseShortWeekday(DayOfWeek.MONDAY))
        assertEquals("周六", chineseShortWeekday(DayOfWeek.SATURDAY))
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 25).dayOfWeek)
    }

    @Test fun `only mainland adjusted working days are underlined`() {
        val china = Locale.SIMPLIFIED_CHINESE
        // The owner's October 2026: only the 10th (a Saturday worked for the National Day week).
        val october = (1..31).map { LocalDate.of(2026, 10, it) }.filter { chinaAdjustedWorkday(it, china) }
        assertEquals(listOf(LocalDate.of(2026, 10, 10)), october)
        assertTrue(chinaAdjustedWorkday(LocalDate.of(2026, 9, 20), china))
        assertTrue(chinaAdjustedWorkday(LocalDate.of(2025, 10, 11), china))
        // Every listed day is a weekend day.
        (2025..2026).flatMap { y -> (1..365).map { LocalDate.ofYearDay(y, it) } }.filter { chinaAdjustedWorkday(it, china) }
            .forEach { assertTrue("$it", isWeekend(it.dayOfWeek)) }
        assertFalse(chinaAdjustedWorkday(LocalDate.of(2026, 10, 1), china))
        assertFalse(chinaAdjustedWorkday(LocalDate.of(2026, 10, 10), Locale.US))
        assertFalse(chinaAdjustedWorkday(LocalDate.of(2026, 10, 10), Locale.TRADITIONAL_CHINESE))
    }

    @Test fun `the medium month keeps Apple's row pitch and tightens six-week months`() {
        assertEquals(MiniMonthRows(58.45f, 18.45f), miniMonthRows(5))
        assertEquals(18.45f, miniMonthRows(4).pitch, 1e-4f)
        val six = miniMonthRows(6)
        assertTrue(six.pitch < 18.45f)
        assertEquals(140.5f, six.firstCenter + 5 * six.pitch, 1e-3f)
        // October 2026 from Sunday: 5 weeks, the 1st on Thursday, the 25th in the last row's first column.
        val weeks = monthGrid(LocalDate.of(2026, 10, 25), DayOfWeek.SUNDAY)
        assertEquals(5, weeks.size)
        assertEquals(LocalDate.of(2026, 10, 1), weeks[0][4])
        assertEquals(LocalDate.of(2026, 10, 25), weeks[4][0])
    }

    @Test fun `iOS starts Chinese months on Sunday`() {
        assertEquals(DayOfWeek.SUNDAY, iosFirstDayOfWeek(Locale.SIMPLIFIED_CHINESE))
        assertEquals(DayOfWeek.SUNDAY, iosFirstDayOfWeek(Locale.US))
        assertEquals(DayOfWeek.MONDAY, iosFirstDayOfWeek(Locale.GERMANY))
    }

    @Test fun `iOS's empty Calendar message in Chinese`() {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals("今天无日程", noEventsMessage(false))
    }

    @Test fun `note text and dates read like iOS Notes`() {
        assertEquals("新备忘录" to "手写备忘录", noteSummaryText("\n  新备忘录 \n\n手写备忘录\n第三行"))
        assertEquals("Only" to null, noteSummaryText("Only"))
        assertNull(noteSummaryText(" \n \n"))
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 10, 25, 11, 48, 0, 0, zone)
        val zh = Locale.SIMPLIFIED_CHINESE
        assertEquals("上午9:41", noteDateLabel(now.withHour(9).withMinute(41), now, false, zh))
        assertEquals("9:41", noteDateLabel(now.withHour(9).withMinute(41), now, true, zh))
        Locale.setDefault(zh)
        assertEquals("昨天", noteDateLabel(now.minusDays(1), now, false, zh))
        assertEquals("星期一", noteDateLabel(now.minusDays(6), now, false, zh))
        assertEquals("2026/10/18", noteDateLabel(now.minusDays(7), now, false, zh))
        Locale.setDefault(Locale.US)
        assertEquals("Monday", noteDateLabel(now.minusDays(6), now, false, Locale.US))
        assertEquals("10/18/26", noteDateLabel(now.minusDays(7), now, false, Locale.US))
        assertEquals("9:41 AM", noteDateLabel(now.withHour(9).withMinute(41), now, false, Locale.US))
    }

    @Test fun `maps prefers Amap and finds the app's own search shortcut`() {
        assertEquals("com.autonavi.minimap", preferredMapPackage { it in setOf("com.google.android.apps.maps", "com.autonavi.minimap") })
        assertEquals("com.tencent.map", preferredMapPackage { it == "com.tencent.map" })
        assertNull(preferredMapPackage { false })
        assertEquals(1, searchShortcutIndex(listOf("回家", "搜索", "去公司")))
        assertEquals(2, searchShortcutIndex(listOf("Home", "Work", "Search")))
        assertEquals(0, searchShortcutIndex(listOf("搜索附近", "导航")))
        assertEquals(1, searchShortcutIndex(listOf("搜索附近", "搜索")))
        assertNull(searchShortcutIndex(listOf("回家", "去公司")))
    }
}
