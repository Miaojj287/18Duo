package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class FoldMotionPolicyTest {
    @Test fun `remove animations disables custom projection while positive scales stay enabled`() {
        assertFalse(foldMotionEnabled(0f))
        assertFalse(foldMotionEnabled(-1f))
        for (scale in listOf(.5f, 1f, 2f, 10f)) assertTrue(foldMotionEnabled(scale))
        assertTrue(foldMotionEnabled(Float.NaN))
    }

    @Test fun `handoff interruption preserves presentation progress and completed arrival may restart`() {
        for (current in listOf(0f, .1f, .5f, .999f)) {
            assertEquals(current, foldArrivalStart(current), 0f)
        }
        assertEquals(0f, foldArrivalStart(1f), 0f)
        assertEquals(0f, foldArrivalStart(Float.NaN), 0f)
    }
}
