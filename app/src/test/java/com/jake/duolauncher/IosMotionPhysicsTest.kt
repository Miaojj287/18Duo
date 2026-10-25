package com.jake.duolauncher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosMotionPhysicsTest {
    private val stride = 1000f
    private fun page(p: Int) = p * stride

    @Test fun `SwiftUI response maps to Compose stiffness`() {
        // SwiftUI .smooth (0.5 s) ≈ Compose stiffness 158; the paging spring is 0.40 s.
        assertEquals(157.91f, IosSpring(.5f, 1f).stiffness, .01f)
        assertEquals(246.74f, LauncherMotion.pageSpring.stiffness, .01f)
        assertEquals(2f * PI.toFloat() / .4f, LauncherMotion.pageSpring.angularFrequency, 1e-4f)
        // Compose's default spring (1500) is a 0.16 s response: far stiffer than any iOS page settle.
        assertEquals(.1622f, IosSpring.fromStiffness(1500f, 1f).response, 1e-3f)
        val tuned = IosSpring.fromStiffness(520f, .82f)
        assertEquals(520f, tuned.stiffness, .01f)
        assertTrue(LauncherMotion.pageSpring.response in .35f..IosSpring(.45f, 1f).response)
        assertTrue(LauncherMotion.reducedPageSpring.response < LauncherMotion.pageSpring.response)
    }

    @Test fun `projection uses UIScrollView deceleration rates`() {
        assertEquals(499f, IosScrollPhysics.projectedDistance(1000f), .01f)
        assertEquals(-499f, IosScrollPhysics.projectedDistance(-1000f), .01f)
        assertEquals(99f, IosScrollPhysics.projectedDistance(1000f, IosScrollPhysics.DECELERATION_FAST), .01f)
        assertEquals(0f, IosScrollPhysics.projectedDistance(0f), 0f)
        assertEquals(0f, IosScrollPhysics.projectedDistance(Float.NaN), 0f)
    }

    @Test fun `rubber band follows the iOS formula`() {
        val d = 860f
        val c = IosScrollPhysics.RUBBER_BAND_COEFFICIENT
        assertEquals(0f, IosScrollPhysics.rubberBand(0f, d), 0f)
        // (1 − 1/(x·c/d + 1))·d at x = d is (1 − 1/1.55)·d.
        assertEquals((1f - 1f / 1.55f) * d, IosScrollPhysics.rubberBand(d, d), .01f)
        // Slope c at the edge, then ever stiffer, never reaching d; odd symmetric.
        assertEquals(c, IosScrollPhysics.rubberBand(.01f, d) / .01f, 1e-3f)
        val samples = listOf(0f, 10f, 50f, 200f, 1000f, 10_000f).map { IosScrollPhysics.rubberBand(it, d) }
        samples.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertTrue(samples.last() < d)
        assertEquals(-IosScrollPhysics.rubberBand(123f, d), IosScrollPhysics.rubberBand(-123f, d), 1e-4f)
    }

    @Test fun `rubber band inverse and slope agree with the band`() {
        val d = 425f
        for (x in listOf(-900f, -40f, 3f, 120f, 600f)) {
            val visible = IosScrollPhysics.rubberBand(x, d)
            assertEquals(x, IosScrollPhysics.rubberBandInverse(visible, d), abs(x) * 1e-3f + 1e-3f)
            val h = .01f
            val numeric = (IosScrollPhysics.rubberBand(x + h, d) - IosScrollPhysics.rubberBand(x - h, d)) / (2 * h)
            assertEquals(numeric, IosScrollPhysics.rubberBandSlope(x, d), 2e-3f)
        }
    }

    @Test fun `rest and slow drags settle on the nearest page`() {
        assertEquals(2, iosPagingTarget(page(2), 0f, 2, 8, ::page))
        assertEquals(2, iosPagingTarget(2400f, 0f, 2, 8, ::page))
        assertEquals(3, iosPagingTarget(2600f, 0f, 2, 8, ::page))
        assertEquals(1, iosPagingTarget(1400f, 0f, 2, 8, ::page))
    }

    @Test fun `short flicks commit and reversals return`() {
        // 10 % of a page released at 0.6 px/ms coasts 299 px: still this page.
        assertEquals(2, iosPagingTarget(2100f, 600f, 2, 8, ::page))
        // The same travel at 1 px/ms coasts past the midpoint: next page.
        assertEquals(3, iosPagingTarget(2100f, 1000f, 2, 8, ::page))
        assertEquals(1, iosPagingTarget(1900f, -1000f, 2, 8, ::page))
        // 70 % dragged, then flicked back: returns.
        assertEquals(2, iosPagingTarget(2700f, -1000f, 2, 8, ::page))
    }

    @Test fun `one gesture moves at most one page and stops at the ends`() {
        assertEquals(3, iosPagingTarget(page(2), 50_000f, 2, 8, ::page))
        assertEquals(1, iosPagingTarget(page(2), -50_000f, 2, 8, ::page))
        assertEquals(0, iosPagingTarget(-200f, -3000f, 0, 8, ::page))
        assertEquals(7, iosPagingTarget(7300f, 3000f, 7, 8, ::page))
        assertEquals(0, iosPagingTarget(500f, 9000f, 0, 1, ::page))
    }

    @Test fun `unequal page offsets (unfolded spread) are compared in visual space`() {
        // Leading page one full window wide, Homes advance by a narrower pane.
        val motion = WorkspacePageMotion(firstHome = 1, homePages = 4, pageWidth = 2000f, homeStride = 800f)
        val offset = { p: Int -> motion.offset(p.toFloat()) }
        assertEquals(0, iosPagingTarget(motion.offset(.45f), 0f, 1, 6, offset))
        assertEquals(1, iosPagingTarget(motion.offset(.55f), 0f, 1, 6, offset))
        assertEquals(2, iosPagingTarget(motion.offset(1.3f), 800f, 1, 6, offset))
    }

    @Test fun `settle velocity never overshoots the target`() {
        val spring = LauncherMotion.pageSpring
        val w = spring.angularFrequency
        val distance = 300f
        // Toward the target: capped at ω·d.
        assertEquals(w * distance, IosScrollPhysics.settleVelocity(1e6f, distance, spring), .01f)
        assertEquals(500f, IosScrollPhysics.settleVelocity(500f, distance, spring), 0f)
        assertEquals(-w * distance, IosScrollPhysics.settleVelocity(-1e6f, -distance, spring), .01f)
        // Away from the target: kept (the spring turns it round) but bounded.
        assertEquals(-800f, IosScrollPhysics.settleVelocity(-800f, distance, spring), 0f)
        assertEquals(0f, IosScrollPhysics.settleVelocity(Float.NaN, distance, spring), 0f)
        // Critically damped motion x(t) = (x0 + (v0 + ωx0)t)e^(−ωt) with the capped velocity stays
        // on its side of the target for any release speed.
        for (release in listOf(100f, 2000f, 50_000f)) {
            val v0 = IosScrollPhysics.settleVelocity(release, distance, spring)
            val x0 = -distance
            var t = 0f
            while (t < 1f) {
                val x = (x0 + (v0 + w * x0) * t) * exp(-w * t)
                assertTrue("release $release overshot at $t", x <= 1e-3f)
                t += .004f
            }
        }
    }

    @Test fun `settle reaches the page in about the iOS paging time`() {
        val w = LauncherMotion.pageSpring.angularFrequency
        // From rest, critically damped: 95 % after ≈0.30 s, 99 % after ≈0.42 s.
        fun remaining(t: Float) = (1f + w * t) * exp(-w * t)
        assertTrue(remaining(.30f) in .04f..0.06f)
        assertTrue(remaining(.42f) < .011f)
    }

    @Test fun `offset splits into legal travel and visible overscroll`() {
        val inside = rubberBandedOffset(1500f, 0f, 3000f, 1000f)
        assertEquals(1500f, inside.legal, 0f); assertEquals(0f, inside.overscroll, 0f)
        val before = rubberBandedOffset(-200f, 0f, 3000f, 1000f)
        assertEquals(0f, before.legal, 0f)
        assertEquals(IosScrollPhysics.rubberBand(-200f, 1000f), before.overscroll, 1e-4f)
        val after = rubberBandedOffset(3400f, 3000f, 0f, 1000f)
        assertEquals(3000f, after.legal, 0f)
        assertTrue(after.overscroll in 0f..400f)
    }

    @Test fun `in-app reduce motion toggles the shared flag`() {
        val before = LauncherMotion.reduced
        try {
            LauncherMotion.reduced = true
            assertTrue(LauncherMotion.reduced)
            assertEquals(LauncherMotion.reducedPageSpring, LauncherMotion.page)
            LauncherMotion.reduced = false
            assertEquals(LauncherMotion.pageSpring, LauncherMotion.page)
        } finally { LauncherMotion.reduced = before }
    }
}
