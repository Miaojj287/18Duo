package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRefreshBoostTest {
    private val ms = 1_000_000L

    @Test fun `picks the fastest mode of the current resolution`() {
        // Xiaomi 18 Fold inner panel style list, plus a lower resolution 144 Hz mode that must not win.
        val modes = listOf(
            RefreshMode(1, 2364, 1672, 60f), RefreshMode(2, 2364, 1672, 90f), RefreshMode(3, 2364, 1672, 120f),
            RefreshMode(4, 1182, 836, 144f), RefreshMode(5, 2364, 1672, Float.NaN),
        )
        assertEquals(3, highestRefreshMode(modes, modes[1])?.id)
        assertEquals(4, highestRefreshMode(modes, null)?.id)
        assertNull(highestRefreshMode(emptyList(), null))
    }

    @Test fun `touch boosts at once and idles out`() {
        val governor = FrameRateGovernor(idleNanos = 600 * ms)
        assertTrue(governor.onInput(0))
        assertFalse(governor.onInput(10 * ms))
        assertEquals(600 * ms, governor.remainingNanos(10 * ms))
        assertFalse(governor.releaseIfIdle(500 * ms))
        assertTrue(governor.releaseIfIdle(610 * ms))
        assertFalse(governor.boosted)
        assertNull(governor.remainingNanos(700 * ms))
    }

    @Test fun `back to back frames are an animation, lone redraws are not`() {
        val governor = FrameRateGovernor(idleNanos = 600 * ms, animationGapNanos = 50 * ms)
        // A clock tick once a minute or a widget update once a second never boosts.
        assertFalse(governor.onFrame(0))
        assertFalse(governor.onFrame(1_000 * ms))
        assertFalse(governor.onFrame(2_000 * ms))
        assertFalse(governor.boosted)
        // The second frame of a spring (one 60 Hz vsync later) boosts; following frames keep it.
        assertTrue(governor.onFrame(2_016 * ms))
        assertFalse(governor.onFrame(2_033 * ms))
        assertFalse(governor.releaseIfIdle(2_600 * ms))
        assertTrue(governor.releaseIfIdle(2_634 * ms))
        // The redraw caused by releasing (window attributes) is a lone frame: no re-boost.
        assertFalse(governor.onFrame(2_640 * ms))
        assertFalse(governor.boosted)
    }

    @Test fun `reset forgets frame history`() {
        val governor = FrameRateGovernor()
        governor.onFrame(0); governor.onFrame(16 * ms)
        assertTrue(governor.boosted)
        governor.reset()
        assertFalse(governor.boosted)
        assertFalse(governor.onFrame(20 * ms))
    }
}
