package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Round 3: Dock recent slots change in place (no swap, no blink) when the recency list changes. */
class RecentDockSlotsTest {
    @Test fun `a re-ranking of the same apps changes nothing on screen`() {
        assertEquals(listOf("a", "b"), stableRecentSlots(listOf("a", "b"), listOf("b", "a")))
    }

    @Test fun `a newcomer takes the slot of the app that left and the other stays put`() {
        assertEquals(listOf("a", "c"), stableRecentSlots(listOf("a", "b"), listOf("c", "a")))
        assertEquals(listOf("c", "b"), stableRecentSlots(listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("c", "d"), stableRecentSlots(listOf("a", "b"), listOf("c", "d")))
    }

    @Test fun `slots appear and disappear with the list`() {
        assertEquals(listOf("a", "b"), stableRecentSlots(emptyList(), listOf("a", "b")))
        assertEquals(emptyList<String>(), stableRecentSlots(listOf("a", "b"), emptyList()))
        assertEquals(listOf("a", "c"), stableRecentSlots(listOf("a"), listOf("c", "a")))
        assertEquals(listOf("b"), stableRecentSlots(listOf("a", "b"), listOf("b")))
    }
}
