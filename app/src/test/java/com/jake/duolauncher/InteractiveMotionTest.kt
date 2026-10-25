package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 6 r6-motion: finger-driven Spotlight, sheets and the cover Today View veil. */
class InteractiveMotionTest {
    private val density = 2.75f
    private val travel = SpotlightPullMath.travelPx(1712f, density)

    @Test fun `the Spotlight pull spans about a quarter of the panel within bounds`() {
        assertEquals(1712f * .26f, travel, .01f)
        assertEquals(150f * density, SpotlightPullMath.travelPx(400f, density), .01f)
        assertEquals(260f * density, SpotlightPullMath.travelPx(4000f, density), .01f)
    }

    @Test fun `Spotlight progress follows the finger one to one and reverses with it`() {
        assertEquals(0f, SpotlightPullMath.progress(0f, 0f, travel), 0f)
        assertEquals(.5f, SpotlightPullMath.progress(0f, travel / 2f, travel), 1e-4f)
        assertEquals(.25f, SpotlightPullMath.progress(0f, travel / 4f, travel), 1e-4f)
        assertEquals(1f, SpotlightPullMath.progress(0f, travel * 3f, travel), 0f)
        assertEquals(0f, SpotlightPullMath.progress(0f, -travel, travel), 0f)
        // A finger that catches a settle continues from where it is.
        assertEquals(.8f, SpotlightPullMath.progress(.6f, travel * .2f, travel), 1e-4f)
    }

    @Test fun `past a full pull the content rubber-bands instead of stopping`() {
        assertEquals(0f, SpotlightPullMath.overshootPx(0f, travel, travel, 1712f), 0f)
        val small = SpotlightPullMath.overshootPx(0f, travel + 50f, travel, 1712f)
        val large = SpotlightPullMath.overshootPx(0f, travel + 400f, travel, 1712f)
        assertTrue(small in 1f..50f)
        assertTrue(large > small && large < 400f)
    }

    @Test fun `release opens by position or by a flick and closes on a flick back`() {
        assertTrue(SpotlightPullMath.releaseOpens(.6f, 0f, travel))
        assertFalse(SpotlightPullMath.releaseOpens(.4f, 0f, travel))
        // A quick short pull still commits (velocity hand-off).
        assertTrue(SpotlightPullMath.releaseOpens(.2f, 4000f / travel, travel))
        // Most of the way down but flicked back up: closes.
        assertFalse(SpotlightPullMath.releaseOpens(.8f, -5000f / travel, travel))
    }

    @Test fun `Home blur and zoom are proportional to the progress`() {
        assertEquals(0f, SpotlightPullMath.homeBlurDp(0f), 0f)
        assertEquals(SPOTLIGHT_HOME_BLUR_DP, SpotlightPullMath.homeBlurDp(1f), 0f)
        assertTrue(SpotlightPullMath.homeBlurDp(.25f) < SpotlightPullMath.homeBlurDp(.5f))
        assertEquals(1f, SpotlightPullMath.homeScale(0f), 0f)
        assertEquals(1f - SPOTLIGHT_HOME_ZOOM, SpotlightPullMath.homeScale(1f), 1e-6f)
        assertEquals(0f, SpotlightPullMath.resultsAlpha(.3f), 0f)
        assertEquals(1f, SpotlightPullMath.resultsAlpha(1f), 1e-6f)
    }

    @Test fun `a sheet follows the finger down and rubber-bands up`() {
        assertEquals(120f, SheetPullMath.visibleOffset(120f, 1500f), 0f)
        val up = SheetPullMath.visibleOffset(-200f, 1500f)
        assertTrue(up < 0f && up > -200f)
    }

    @Test fun `a sheet dismisses past a third or on a downward flick`() {
        val h = 1500f
        assertFalse(SheetPullMath.dismisses(100f, 0f, h, density))
        assertTrue(SheetPullMath.dismisses(500f, 0f, h, density))
        assertTrue(SheetPullMath.dismisses(80f, 1500f, h, density))
        assertFalse(SheetPullMath.dismisses(400f, -2000f, h, density))
        assertFalse(SheetPullMath.dismisses(0f, 0f, h, density))
    }

    @Test fun `the backdrop un-dims in proportion to the pull`() {
        assertEquals(1f, SheetPullMath.backdropFraction(1f, 0f, 1000f), 0f)
        assertEquals(.5f, SheetPullMath.backdropFraction(1f, 500f, 1000f), 1e-6f)
        assertEquals(0f, SheetPullMath.backdropFraction(1f, 1000f, 1000f), 0f)
        assertEquals(.25f, SheetPullMath.backdropFraction(.5f, 500f, 1000f), 1e-6f)
    }

    @Test fun `the cover Today veil tracks the swipe from Home 1`() {
        val cover = PagerPosture(spread = false, leading = true)
        assertEquals(0f, coverTodayProgress(1f, cover), 0f)
        assertEquals(.5f, coverTodayProgress(.5f, cover), 1e-6f)
        assertEquals(1f, coverTodayProgress(0f, cover), 0f)
        assertEquals(0f, coverTodayProgress(2.3f, cover), 0f)
        // Unfolded the iPad overlay owns the veil; no Today page, no veil.
        assertEquals(0f, coverTodayProgress(0f, PagerPosture(spread = true, leading = true)), 0f)
        assertEquals(0f, coverTodayProgress(0f, PagerPosture(spread = false, leading = false)), 0f)
        assertEquals(0f, coverTodayBlurDp(0f), 0f)
        assertTrue(coverTodayBlurDp(.5f) > 0f)
    }

    @Test fun `a lift after lost MOVEs keeps the finger's speed, a still finger stays still`() {
        assertEquals(900f, releaseVelocity(900f, 100L, 10f, 300L, 90f), 0f)
        assertEquals(500f, releaseVelocity(0f, 1000L, 1190f, 1120L, 1250f), 1f)
        assertEquals(0f, releaseVelocity(0f, 1000L, 1190f, 1120L, 1190f), 0f)
        assertEquals(0f, releaseVelocity(0f, 1000L, 1190f, 1030L, 1250f), 0f)
        assertEquals(0f, releaseVelocity(0f, Long.MIN_VALUE, 0f, 1030L, 1250f), 0f)
        // The tracker's damped reading after the gap is replaced by the stretch's own speed.
        assertEquals(500f, releaseVelocity(150f, 1000L, 1190f, 1120L, 1250f), 1f)
    }
}
