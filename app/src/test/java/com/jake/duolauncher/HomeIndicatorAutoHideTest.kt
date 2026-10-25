package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core.17 round 3: the home indicator's auto-hide (item 3) and when the overlay may reveal Home (item 4). */
class HomeIndicatorAutoHideTest {
    private val delay = HomeIndicatorAutoHide.HIDE_AFTER_MS

    // ---------------------------------------------------------------- auto-hide (item 3)

    @Test fun `the delay is iOS-like, a few seconds`() {
        assertTrue(delay in 3_000L..5_000L)
    }

    @Test fun `hidden until something wakes it`() {
        val bar = HomeIndicatorAutoHide()
        assertFalse(bar.visible(0L))
        assertNull(bar.hideAt())
    }

    @Test fun `shows after an app appears and fades once the delay has passed`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(1_000L)
        assertTrue(bar.visible(1_000L))
        assertTrue(bar.visible(1_000L + delay - 1))
        assertFalse(bar.visible(1_000L + delay))
        assertEquals(1_000L + delay, bar.hideAt())
    }

    @Test fun `every wake restarts the countdown (window change, return from the switcher, touch)`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        bar.wake(3_000L)
        assertTrue(bar.visible(delay + 500L))
        assertFalse(bar.visible(3_000L + delay))
        assertEquals(3_000L + delay, bar.hideAt())
    }

    @Test fun `a faded bar comes back on the next wake`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        assertFalse(bar.visible(10_000L))
        bar.wake(10_000L)
        assertTrue(bar.visible(10_000L))
    }

    @Test fun `never fades under a gesture still in progress`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        bar.hold(delay - 100L)
        assertTrue(bar.held)
        // The countdown ran out mid-gesture: the bar stays.
        assertTrue(bar.visible(delay + 2_000L))
        assertNull(bar.hideAt())
        // Released: a fresh countdown from the end of the gesture.
        bar.release(delay + 2_000L)
        assertFalse(bar.held)
        assertTrue(bar.visible(delay + 2_000L + delay - 1))
        assertFalse(bar.visible(delay + 2_000L + delay))
    }

    @Test fun `a hidden bar does not pop up because of a Back swipe`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        bar.hold(delay + 1_000L)
        assertFalse(bar.visible(delay + 1_500L))
        bar.release(delay + 2_000L)
        assertFalse(bar.visible(delay + 2_000L))
    }

    @Test fun `a touch on the bottom edge wakes and holds it on that same touch`() {
        // The stage wakes then holds on finger-down, and wakes again when the gesture ends in the app.
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        val down = delay + 5_000L
        assertFalse(bar.visible(down))
        bar.wake(down); bar.hold(down)
        assertTrue(bar.visible(down + 10_000L))
        bar.release(down + 10_000L); bar.wake(down + 10_000L)
        assertTrue(bar.visible(down + 10_000L + delay - 1))
        assertFalse(bar.visible(down + 10_000L + delay))
    }

    @Test fun `a window that appears during a gesture keeps the bar for after it`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        bar.hold(delay + 100L)          // already faded when the gesture began
        bar.wake(delay + 200L)          // a new window came up meanwhile
        assertTrue(bar.visible(delay + 300L))
        bar.release(delay + 400L)
        assertTrue(bar.visible(delay + 400L + delay - 1))
    }

    @Test fun `hold and release are idempotent`() {
        val bar = HomeIndicatorAutoHide()
        bar.wake(0L)
        bar.hold(100L); bar.hold(delay + 100L)
        assertTrue(bar.visible(delay + 200L))
        bar.release(delay + 300L); bar.release(delay + 900L)
        assertEquals(delay + 300L + delay, bar.hideAt())
    }

    // ---------------------------------------------------------------- reveal the live Home (item 4)

    @Test fun `resumed is not enough - the frozen Home stays until the live one is on screen`() {
        // The old rule handed over 220 ms after the resume; Home's first frame came up to 600 ms later.
        assertFalse(homeOnScreen(now = 1_220L, launcherVisible = true, resumedAt = 1_000L, drawnAt = 0L, focusedAt = 0L))
        assertFalse(homeOnScreen(now = 1_400L, launcherVisible = true, resumedAt = 1_000L, drawnAt = 1_350L, focusedAt = 0L))
    }

    /** Round 18 (owner recording 12:20): a restarted Home was on screen but empty for ≈ 0.6 s. */
    @Test fun `a Home without its apps is not revealed until a frame with them is committed`() {
        // Recreated at 1 000, apps loaded and drawn at 1 650.
        assertFalse(homeContentShown(now = 1_200L, resumedAt = 1_000L, contentDrawnAt = 0L))
        assertFalse(homeContentShown(now = 1_660L, resumedAt = 1_000L, contentDrawnAt = 1_650L))
        assertTrue(homeContentShown(now = 1_650L + HOME_FOCUS_SETTLE_MS, resumedAt = 1_000L, contentDrawnAt = 1_650L))
        // Loaded long ago (the usual case): no extra wait.
        assertTrue(homeContentShown(now = 9_000L, resumedAt = 8_990L, contentDrawnAt = 100L))
        // Apps that never load still let Home through, late.
        assertTrue(homeContentShown(now = 1_000L + HOME_CONTENT_FALLBACK_MS, resumedAt = 1_000L, contentDrawnAt = 0L))
    }

    @Test fun `focus means Android shows the window - one compositor frame later it is revealed`() {
        assertFalse(homeOnScreen(1_610L, true, 1_000L, 1_550L, focusedAt = 1_600L))
        assertTrue(homeOnScreen(1_600L + HOME_FOCUS_SETTLE_MS, true, 1_000L, 1_550L, focusedAt = 1_600L))
    }

    /** Round 12: focus before the first committed frame (HyperOS) revealed the leaving app for a frame. */
    @Test fun `focus alone is not enough - a frame since the resume must have been committed too`() {
        assertFalse(homeOnScreen(1_600L + HOME_FOCUS_SETTLE_MS, true, 1_000L, drawnAt = 0L, focusedAt = 1_600L))
        assertFalse(homeOnScreen(1_600L + HOME_FOCUS_SETTLE_MS, true, 1_000L, drawnAt = 900L, focusedAt = 1_600L))
        // The frame comes a little after the focus: one compositor frame after the later of the two.
        assertFalse(homeOnScreen(1_650L + HOME_FOCUS_SETTLE_MS - 1, true, 1_000L, drawnAt = 1_650L, focusedAt = 1_600L))
        assertTrue(homeOnScreen(1_650L + HOME_FOCUS_SETTLE_MS, true, 1_000L, drawnAt = 1_650L, focusedAt = 1_600L))
        // Focused but never drawn: a bounded wait, then revealed anyway.
        assertFalse(homeOnScreen(1_600L + HOME_FOCUS_ONLY_FALLBACK_MS - 1, true, 1_000L, 0L, focusedAt = 1_600L))
        assertTrue(homeOnScreen(1_600L + HOME_FOCUS_ONLY_FALLBACK_MS, true, 1_000L, 0L, focusedAt = 1_600L))
    }

    @Test fun `without focus a drawn Home is revealed after a longer wait, an undrawn one only at the timeout`() {
        assertTrue(homeOnScreen(1_300L + HOME_DRAWN_FALLBACK_MS, true, 1_000L, drawnAt = 1_300L, focusedAt = 0L))
        assertFalse(homeOnScreen(1_000L + HOME_RESUME_FALLBACK_MS - 1, true, 1_000L, 0L, 0L))
        assertTrue(homeOnScreen(1_000L + HOME_RESUME_FALLBACK_MS, true, 1_000L, 0L, 0L))
    }

    @Test fun `signals from an earlier visit never count`() {
        // Focus and a frame from before this resume say nothing about the window now.
        assertFalse(homeOnScreen(5_100L, true, resumedAt = 5_000L, drawnAt = 900L, focusedAt = 950L))
    }

    @Test fun `a paused Home is never revealed`() {
        assertFalse(homeOnScreen(9_000L, launcherVisible = false, resumedAt = 1_000L, drawnAt = 1_100L, focusedAt = 1_200L))
    }
}
