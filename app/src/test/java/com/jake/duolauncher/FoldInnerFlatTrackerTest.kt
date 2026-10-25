package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class FoldInnerFlatTrackerTest {
    @Test fun `flat requires both a physical opened posture and the angle endpoint`() {
        val tracker = FoldInnerFlatTracker()
        assertFalse(tracker.update(180f, 2, 1_000_000_000L))
        assertFalse(tracker.update(174f, 3, 1_020_000_000L))
        assertTrue(tracker.update(175f, 3, 1_040_000_000L))
        assertTrue(tracker.update(158f, 3, 1_060_000_000L))
        assertTrue(tracker.update(Float.NaN, 2, 1_080_000_000L))
    }

    @Test fun `continuous plausible closing releases before the fallback timeout`() {
        val tracker = FoldInnerFlatTracker()
        val start = 1_000_000_000L
        assertTrue(tracker.update(180f, 3, start))
        assertTrue(tracker.update(176f, 3, start + 20_000_000L))
        assertTrue(tracker.update(174f, 2, start + 40_000_000L))
        assertTrue(tracker.update(172f, 2, start + 70_000_000L))
        assertTrue(tracker.update(170f, 2, start + 100_000_000L))
        // r5: one run has fallen 8 degrees from the last flat reading and left the tilt band.
        assertFalse(tracker.update(168f, 2, start + 130_000_000L))
    }

    /** MiuiWallpaper FoldManager log of the owner's fold, 2026-10-20 18:53:28.721-29.744
     * (the same HAL stream Home receives). Vendor posture: OPENED until DeviceState
     * HALF_OPENED at 159 degrees, TENT at 88, folded at the end; inner dark at +1.036 s. */
    private val ownerFold = listOf(
        0 to 178f, 22 to 176f, 43 to 175f, 82 to 173f, 160 to 175f, 222 to 176f, 472 to 174f,
        482 to 173f, 500 to 174f, 522 to 176f, 600 to 172f, 622 to 166f, 641 to 159f, 661 to 150f,
        679 to 140f, 701 to 128f, 722 to 115f, 740 to 103f, 761 to 94f, 780 to 88f, 801 to 85f,
        823 to 82f, 840 to 78f, 861 to 72f, 879 to 66f, 901 to 58f, 923 to 48f, 940 to 37f,
        962 to 27f, 980 to 19f, 1001 to 13f, 1023 to 6f)

    private fun ownerPosture(angle: Float, ms: Int): Int = when {
        ms >= 1023 -> 0
        angle <= 88f && ms >= 780 -> 1
        angle <= 159f && ms >= 641 -> 2
        else -> 3
    }

    @Test fun `owner's real fold releases flat within one sample of leaving the tilt band`() {
        val tracker = FoldInnerFlatTracker()
        val start = 5_000_000_000L
        var releasedAt: Pair<Int, Float>? = null
        for ((ms, angle) in ownerFold) {
            val flat = tracker.update(angle, ownerPosture(angle, ms), start + ms * 1_000_000L)
            if (!flat && releasedAt == null) releasedAt = ms to angle
            if (ms < 600) assertTrue("tilt noise at $ms ms ($angle) must stay crisp", flat)
        }
        // Released at 166 degrees, 22 ms after the fold's first 172: the inner panel then has
        // ~400 ms of hinge-tracked projection before it goes dark (the old tracker: none).
        assertEquals(622 to 166f, releasedAt)
    }

    @Test fun `contact events repeating the same angle at 50 Hz do not break a fold's run`() {
        val tracker = FoldInnerFlatTracker()
        val start = 5_000_000_000L
        var released = false
        for ((ms, angle) in ownerFold) {
            val now = start + ms * 1_000_000L
            released = released || !tracker.update(angle, ownerPosture(angle, ms), now)
            // A Hall/fold_status event 5 ms later re-evaluates the unchanged hinge angle.
            released = released || !tracker.update(angle, ownerPosture(angle, ms), now + 5_000_000L)
            if (ms == 622) assertTrue(released)
        }
    }

    @Test fun `sparse or delayed delivery of a fold still releases flat`() {
        // Every other sample dropped (40 ms spacing), then the same fold delivered in bursts
        // after a long frame: all readings of a 40 ms window arrive within 1 ms.
        val sparse = FoldInnerFlatTracker()
        val start = 5_000_000_000L
        var sparseRelease: Float? = null
        for ((index, sample) in ownerFold.withIndex()) {
            if (index % 2 == 1) continue
            val (ms, angle) = sample
            if (!sparse.update(angle, ownerPosture(angle, ms), start + ms * 1_000_000L) && sparseRelease == null)
                sparseRelease = angle
        }
        assertNotNull(sparseRelease)
        assertTrue("released at $sparseRelease", sparseRelease!! >= 140f)

        val burst = FoldInnerFlatTracker()
        var burstRelease: Float? = null
        for ((ms, angle) in ownerFold) {
            val delivered = start + (ms / 40) * 40_000_000L + (ms % 40) * 20_000L
            if (!burst.update(angle, ownerPosture(angle, ms), delivered) && burstRelease == null) burstRelease = angle
        }
        assertTrue("released at $burstRelease", (burstRelease ?: 0f) >= 150f)
    }

    @Test fun `a 400 Hz fold at 650 degrees per second releases within 20 ms`() {
        val tracker = FoldInnerFlatTracker()
        val start = 5_000_000_000L
        assertTrue(tracker.update(177f, 3, start))
        var releasedMs = -1.0
        var angle = 177f
        var t = 0.0
        while (angle > 5f) {
            t += 2.5
            angle -= 1.625f
            val rounded = kotlin.math.round(angle)
            if (!tracker.update(rounded, if (rounded > 159f) 3 else 2, start + (t * 1_000_000).toLong()) &&
                releasedMs < 0) releasedMs = t
        }
        assertTrue("released after $releasedMs ms", releasedMs in 0.0..20.0)
    }

    @Test fun `flat tilt noise at 400 Hz never releases`() {
        // On-change readings walking the measured 172-179 band one degree at a time, with
        // monotonic stretches of up to six degrees, for ten seconds.
        val tracker = FoldInnerFlatTracker()
        val random = java.util.Random(7)
        var angle = 178
        var now = 5_000_000_000L
        assertTrue(tracker.update(178f, 3, now))
        repeat(4_000) {
            now += 2_500_000L
            angle = (angle + if (random.nextInt(3) == 0) 1 else -1).coerceIn(172, 179)
            assertTrue("released at $angle", tracker.update(angle.toFloat(), 3, now))
        }
    }

    @Test fun `a single discontinuity and a short half-open report cannot release flat`() {
        val tracker = FoldInnerFlatTracker()
        val start = 1_000_000_000L
        assertTrue(tracker.update(180f, 3, start))
        assertTrue(tracker.update(130f, 2, start + 20_000_000L))
        for (step in 1..4) {
            assertTrue(tracker.update(130f, 2, start + 20_000_000L + step * 100_000_000L))
        }
        assertFalse(tracker.update(130f, 2, start + 520_000_000L))
    }

    @Test fun `long gaps cannot reuse an old closing timeout or movement streak`() {
        val tracker = FoldInnerFlatTracker()
        val start = 1_000_000_000L
        assertTrue(tracker.update(180f, 3, start))
        assertTrue(tracker.update(150f, 2, start + 20_000_000L))
        assertTrue(tracker.update(150f, 2, start + 1_000_000_000L))
        for (step in 1..4) assertTrue(tracker.update(150f, 2, start + 1_000_000_000L + step * 100_000_000L))
        assertFalse(tracker.update(150f, 2, start + 1_500_000_000L))
    }

    @Test fun `invalid posture and duplicate clock observations preserve confirmation`() {
        val tracker = FoldInnerFlatTracker()
        assertTrue(tracker.update(180f, 3, 1_000_000_000L))
        assertTrue(tracker.update(90f, -1, 1_100_000_000L))
        assertTrue(tracker.update(90f, 2, 1_100_000_000L))
        assertTrue(tracker.update(90f, 2, 1_000_000_000L))
        assertTrue(tracker.update(180f, 3, 1_200_000_000L))
    }

    @Test fun `confirmed flat changes only the inner tracking target and reset clears it`() {
        val signal = FoldSignal(angleDegrees = 130f, activePanel = FoldPanel.INNER,
            innerConfirmedFlat = true, receivedElapsedNs = 1_000_000_000L)
        assertEquals(1f, foldTrackingProgress(signal, FoldPanel.INNER, 1_100_000_000L), 0f)
        assertEquals(0f, foldTrackingProgress(signal, FoldPanel.COVER, 1_100_000_000L), 0f)
        assertEquals(FoldPhase.INNER_STABLE,
            foldPhase(FoldPanel.INNER, .5f, FoldDirection.CLOSING, true))
        assertFalse(resetFoldTracking(signal).innerConfirmedFlat)
    }
}
