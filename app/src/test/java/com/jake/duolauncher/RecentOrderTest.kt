package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentOrderTest {
    @Test fun `usage order comes first, launcher history fills the rest`() {
        val usage = listOf("a/.A" to 100L, "b/.B" to 300L, "c/.C" to 200L)
        val history = listOf("work:w/.W", "a/.A")
        assertEquals(listOf("b/.B", "c/.C", "a/.A", "work:w/.W"), orderRecentIds(usage, history, { 0L }))
    }

    @Test fun `apps swiped away stay hidden until opened again`() {
        val usage = listOf("a/.A" to 100L, "b/.B" to 300L)
        val dismissed = mapOf("a/.A" to 150L, "b/.B" to 250L)
        assertEquals(listOf("b/.B"), orderRecentIds(usage, emptyList(), { dismissed[it] ?: 0L }))
    }

    @Test fun `the list is capped`() {
        val usage = (0 until 30).map { "p$it/.M" to it.toLong() }
        assertEquals(5, orderRecentIds(usage, emptyList(), { 0L }, limit = 5).size)
    }

    @Test fun `quick switching keeps its order until the user settles`() {
        val session = QuickSwitchSession(freezeMillis = 4_000L)
        // In A; previous apps B, C.
        val (first, index) = session.order(listOf("B", "C"), "A", 0L)
        assertEquals(listOf("A", "B", "C"), first)
        assertEquals(0, index)
        session.committed(first, 0L)
        // Now in B: usage already says B is the newest, but the frozen order keeps A to its right.
        val (held, heldIndex) = session.order(listOf("A", "C"), "B", 1_000L)
        assertEquals(listOf("A", "B", "C"), held)
        assertEquals(1, heldIndex)
        // After settling in B the live order wins.
        val (live, liveIndex) = session.order(listOf("A", "C"), "B", 6_000L)
        assertEquals(listOf("B", "A", "C"), live)
        assertEquals(0, liveIndex)
    }

    @Test fun `an unknown current app or a reset session uses the live order`() {
        val session = QuickSwitchSession()
        session.committed(listOf("A", "B"), 0L)
        assertEquals(listOf("A", "B") to 0, session.order(listOf("A", "B"), "Z", 100L).let { (l, i) -> l.drop(1) to i })
        session.reset()
        assertEquals(listOf("X", "Y") to -1, session.order(listOf("X", "Y"), null, 100L))
    }

    @Test fun `not knowing the current app yet keeps the frozen order (no A-B ping-pong)`() {
        // Core.17 review P1-8: the quick switch starts before the task list has loaded.
        val session = QuickSwitchSession(freezeMillis = 4_000L)
        session.committed(listOf("A", "B", "C"), 0L)
        assertEquals(listOf("B", "A", "C") to -1, session.order(listOf("B", "A", "C"), null, 100L))
        // The rebuild once tasks arrive (now in B) still uses the frozen order: next flick goes to C.
        assertEquals(listOf("A", "B", "C") to 1, session.order(listOf("A", "C"), "B", 150L))
    }

    @Test fun `placeholder cards wear the launch colour, else the icon's dominant colour`() {
        val indigo = 0xFF3F51B5.toInt()
        val white = 0xFFFAFAFA.toInt()
        val iconBlue = 0xFF1E88E5.toInt()
        assertEquals(indigo, chooseLaunchColor(indigo, iconBlue, white))
        // A white launch screen is not "a colour": the icon's own hue reads better than grey.
        assertEquals(iconBlue, chooseLaunchColor(white, iconBlue, white))
        assertEquals(white, chooseLaunchColor(white, null, 0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), chooseLaunchColor(null, null, 0xFF000000.toInt()))
    }

    @Test fun `dominant colour ignores grey and needs a real share of the icon`() {
        val green = 0xFF34C759.toInt()
        val white = 0xFFFFFFFF.toInt()
        val mostlyGreen = IntArray(100) { if (it < 40) green else white }
        val dominant = dominantColor(mostlyGreen)!!
        assertTrue(saturation(dominant) > .5f)
        assertEquals(green and 0xE0E0E0, dominant and 0xE0E0E0)
        assertNull(dominantColor(IntArray(100) { if (it < 5) green else white }))
        assertNull(dominantColor(IntArray(50) { 0xFF808080.toInt() }))
        assertNull(dominantColor(IntArray(0)))
    }
}
