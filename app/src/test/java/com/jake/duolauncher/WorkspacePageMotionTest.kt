package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePageMotionTest {
    private val motion = WorkspacePageMotion(
        firstHome = 1,
        homePages = 3,
        pageWidth = 1000f,
        homeStride = 500f,
    )

    @Test fun `leading page homes and library have the intended endpoints`() {
        assertEquals(-1000f, motion.offset(0f), 0f)
        assertEquals(0f, motion.offset(1f), 0f)
        assertEquals(500f, motion.offset(2f), 0f)
        assertEquals(1000f, motion.offset(3f), 0f)
        assertEquals(1500f, motion.offset(4f), 0f)

        assertEquals(0f, motion.position(-1000f), 0f)
        assertEquals(1f, motion.position(0f), 0f)
        assertEquals(2f, motion.position(500f), 0f)
        assertEquals(3f, motion.position(1000f), 0f)
        assertEquals(4f, motion.position(1500f), 0f)
    }

    @Test fun `mapping and inverse stay continuous through every boundary`() {
        for (step in -1000..4000) {
            val position = step / 1000f
            assertEquals(position, motion.position(motion.offset(position)), .00001f)
        }

        val epsilon = .0001f
        for (boundary in listOf(1f, 3f)) {
            assertTrue(motion.offset(boundary) - motion.offset(boundary - epsilon) < .11f)
            assertTrue(motion.offset(boundary + epsilon) - motion.offset(boundary) < .11f)
        }
    }

    @Test fun `one home page still has an adjacent single pane library`() {
        val single = WorkspacePageMotion(1, 1, 1000f, 500f)
        for (position in listOf(-.5f, 0f, .25f, 1f, 1.75f, 2f, 2.5f)) {
            val stride = if (position < 1f) 1000f else 500f
            assertEquals((position - 1f) * stride, single.offset(position), 0f)
            assertEquals(position, single.position(single.offset(position)), 0f)
        }
    }

    @Test fun `unfolded workspace starts at physical page zero`() {
        val unfolded = WorkspacePageMotion(0, 3, 1000f, 500f)
        assertEquals(0f, unfolded.offset(0f), 0f)
        assertEquals(500f, unfolded.offset(1f), 0f)
        assertEquals(1000f, unfolded.offset(2f), 0f)
        assertEquals(1500f, unfolded.offset(3f), 0f)
        assertEquals(3f, unfolded.position(1500f), 0f)
    }

    @Test fun `half width home panes preserve one to one visual input`() {
        val startingPositions = listOf(.25f, 1f, 1.5f, 2.75f, 3.25f)
        val deltas = listOf(-120f, -10f, 0f, 35f, 140f)
        for (start in startingPositions) for (delta in deltas) {
            val moved = motion.positionAfterVisualDelta(start, delta)
            // Two Float conversions can accumulate a tiny subpixel rounding error.
            assertEquals(delta, motion.offset(moved) - motion.offset(start), .001f)
        }
        assertEquals(1000f, motion.stride(0, 1), 0f)
        assertEquals(500f, motion.stride(1, 2), 0f)
        assertEquals(500f, motion.stride(2, 3), 0f)
        assertEquals(500f, motion.stride(3, 4), 0f)
    }

    @Test fun `library stop retains last home on left for any number of homes`() {
        for (homes in 1..12) {
            val expanded = WorkspacePageMotion(0, homes, 1000f, 500f)
            val rightOrigin = 500f
            val scroll = expanded.offset(homes.toFloat())
            assertEquals(0f, rightOrigin + (homes - 1) * 500f - scroll, 0f)
            assertEquals(500f, rightOrigin + homes * 500f - scroll, 0f)
            assertEquals(500f, expanded.stride(homes - 1, homes), 0f)
        }
    }
    // Panes are opaque and rigid and nothing clips them: the next page rests beyond the screen edge.
    private val innerFrame = duoHomeFrame(DuoHomePose.INNER_LANDSCAPE, 860f, 608f)
    private val inner = innerFrame.grid
    private val innerTrack = WorkspacePaneTrack(restLeft = inner.cellLeft, stride = inner.pageStride, restingPanes = 2,
        paneWidth = inner.cellGridWidth, screenWidth = 860f, margin = PANE_DRAW_MARGIN_DP)
    private val cover = duoHomeFrame(DuoHomePose.COVER_PORTRAIT, 425f, 622f).grid
    private val coverTrack = WorkspacePaneTrack(restLeft = cover.cellLeft, stride = cover.pageStride, restingPanes = 1,
        paneWidth = cover.cellGridWidth, screenWidth = 425f, margin = PANE_DRAW_MARGIN_DP)

    /** Round 6 (owner): "图标依然应该是从屏幕之外过来的感觉才对" — no mask, the next page waits off screen. */
    @Test fun `unfolded the neighbours of the resting spread rest beyond the screen edges`() {
        val t = innerTrack
        assertTrue(t.onScreen(t.restLeft) && t.onScreen(t.restLeft + t.stride))
        // The next page's cell grid (labels never overhang it) and its badge margin start past the right edge.
        assertTrue(t.restLeft + 2 * t.stride - t.margin >= 860f - .01f)
        assertFalse(t.onScreen(t.screenX(t.restLeft + 2 * t.stride)))
        assertFalse(t.onScreen(t.screenX(t.restLeft - t.stride)))
        // The right-hand page's last column (icon + label + badges) still clears the Dock glass.
        assertTrue(t.restLeft + t.stride + t.paneWidth + t.margin <= innerFrame.dock.box.left)
    }

    @Test fun `unfolded gaps keep the cover's Dock gap and put the crease between the pages`() {
        val g = inner
        val leftGridRight = g.iconLeft + g.iconGridWidth
        val rightGridLeft = g.iconLeft + g.pageStride
        assertEquals(DuoInner.DOCK_GAP * 860f, innerFrame.dock.box.left - (rightGridLeft + g.iconGridWidth), 1.5f)
        assertTrue("crease in the gap", 430f > leftGridRight && 430f < rightGridLeft)
        assertTrue("edge gap ${g.iconLeft}", g.iconLeft in 40f..67.1f)
    }

    @Test fun `cover pages are a plain rigid strip`() {
        for (step in -1200..1200) assertEquals(step.toFloat(), coverTrack.screenX(step.toFloat()), 0f)
        // At rest both neighbours are already off screen.
        assertFalse(coverTrack.onScreen(coverTrack.restLeft + coverTrack.stride))
        assertFalse(coverTrack.onScreen(coverTrack.restLeft - coverTrack.stride))
    }

    /** Round 5 (owner): the right page must not chase the left one coming out, nor run away going back. */
    @Test fun `every pane moves with the finger so the gaps between panes never change`() {
        val t = innerTrack
        var scroll = -t.stride
        while (scroll <= 2 * t.stride) {
            for (pane in -1..3) {
                val left = t.screenX(t.restLeft + pane * t.stride - scroll)
                val right = t.screenX(t.restLeft + (pane + 1) * t.stride - scroll)
                assertEquals(t.stride, right - left, .001f)
                // One to one with the finger.
                assertEquals(left - .5f, t.screenX(t.restLeft + pane * t.stride - scroll - .5f), .001f)
            }
            scroll += 1f
        }
    }

    @Test fun `trailing pane comes in from beyond the right screen edge and leaves past it`() {
        val t = innerTrack
        val incoming = t.restLeft + 2 * t.stride // the pane after the right-hand one, at scroll 0
        var wasSeen = false
        for (step in 0..4000) {
            val scroll = t.stride * step / 4000f // swipe right→left: [Today|P1] → [P1|P2]
            val x = t.screenX(incoming - scroll)
            val seen = t.onScreen(x)
            if (seen && !wasSeen) assertTrue("first drawn at $x", x - t.margin >= 860f - 1f)
            wasSeen = seen
        }
        assertTrue(wasSeen)
    }

    @Test fun `leading pane enters from the left edge`() {
        val t = innerTrack
        val leading = t.restLeft - t.stride // Today while [P1|P2] rests
        var wasSeen = false
        for (step in 0..4000) {
            val x = t.screenX(leading + t.stride * step / 4000f) // swipe left→right
            val seen = t.onScreen(x)
            if (seen && !wasSeen) assertTrue("right edge ${x + t.paneWidth} on first frame", x + t.paneWidth + t.margin <= 1.5f)
            wasSeen = seen
        }
        assertTrue(wasSeen)
    }

    @Test fun `every pane drawn during a gesture is already composed`() {
        // Unfolded with Today: physical p = [logical p−1 | p]; panes −1 (Today) … 5 (App Library).
        val homes = 5
        val unfolded = WorkspacePageMotion(0, homes, 2 * innerTrack.stride, innerTrack.stride)
        val origin = innerTrack.restLeft + innerTrack.stride
        val pageCount = homes + 1
        for (settled in 0 until pageCount) {
            val pages = composedPhysicalPages(settled, settled, settled, pageCount)
            val composed = innerTrack.panesSeenBetween(unfolded.offset(pages.first.toFloat()),
                unfolded.offset(pages.last.toFloat()), origin, -1, homes)
            // One gesture: ±1 page from the settled one, plus a long rubber band beyond.
            val low = unfolded.offset((settled - 1).coerceAtLeast(0).toFloat()) - .75f * innerTrack.stride
            val high = unfolded.offset((settled + 1).coerceAtMost(pageCount - 1).toFloat()) + .75f * innerTrack.stride
            var scroll = low
            while (scroll <= high) {
                for (pane in -1..homes) if (innerTrack.onScreen(innerTrack.screenX(origin + pane * innerTrack.stride - scroll)))
                    assertTrue("pane $pane drawn at scroll $scroll from page $settled", pane in composed)
                scroll += 2f
            }
            // …and nothing far away: at most the six panes of two pages either side.
            assertTrue("$composed from page $settled", composed.size <= 6)
        }
    }

    @Test fun `programmatic moves compose every page they pass`() {
        assertEquals(0..2, composedPhysicalPages(0, 0, 0, 8))
        assertEquals(1..5, composedPhysicalPages(3, 3, 3, 8))
        assertEquals(5..7, composedPhysicalPages(7, 7, 7, 8))
        // Home button from page 7 to page 0: everything in between, from the first frame.
        assertEquals(0..7, composedPhysicalPages(7, 0, 7, 8))
        assertEquals(0..0, composedPhysicalPages(0, 0, 0, 1))
    }

    @Test fun `swiping back and forth never rebuilds a composed pane`() {
        val homes = 5
        val unfolded = WorkspacePageMotion(0, homes, 2 * innerTrack.stride, innerTrack.stride)
        val origin = innerTrack.restLeft + innerTrack.stride
        fun panes(settled: Int, reach: Int): List<Int> {
            val pages = composedPhysicalPages(settled, settled, settled, homes + 1, reach)
            return innerTrack.panesSeenBetween(unfolded.offset(pages.first.toFloat()),
                unfolded.offset(pages.last.toFloat()), origin, -1, homes)
        }
        var composed = retainedPanes(panes(2, 2), emptyList(), panes(2, 3))
        val first = composed
        for (settled in listOf(3, 2, 3, 2, 1, 2)) {
            val next = retainedPanes(panes(settled, 2), composed, panes(settled, 3))
            assertTrue("$composed → $next", next.containsAll(composed.filter { it in panes(settled, 3) }))
            assertTrue(next.containsAll(panes(settled, 2)))
            composed = next
        }
        assertTrue(composed.containsAll(first))
        // Far away, old panes are released again.
        composed = retainedPanes(panes(5, 2), composed, panes(5, 3))
        assertFalse(-1 in composed)
        assertEquals(1..5, composedPhysicalPages(3, 3, 3, 8, reach = 2))
        assertEquals(0..6, composedPhysicalPages(3, 3, 3, 8, reach = 3))
    }

    @Test fun `the full-screen library step moves a whole spread and stays invertible`() {
        // Unfolded, Today at 0, Home stops 0..2 (three Home pages), library at 3 one spread (2 × 500) away.
        val spread = WorkspacePageMotion(0, 3, 1000f, 500f, libraryStride = 1000f)
        assertEquals(1000f, spread.offset(2f), 0f)
        assertEquals(1500f, spread.offset(2.5f), 0f)
        assertEquals(2000f, spread.offset(3f), 0f)
        assertEquals(1000f, spread.stride(2, 3), 0f)
        assertEquals(500f, spread.stride(1, 2), 0f)
        for (step in 0..3000) {
            val position = step / 1000f
            assertEquals(position, spread.position(spread.offset(position)), .00001f)
        }
        // 1:1 with the finger: a 250 px drag from the last Home stop is a quarter of the library step.
        assertEquals(2.25f, spread.positionAfterVisualDelta(2f, 250f), 1e-5f)
    }
}
