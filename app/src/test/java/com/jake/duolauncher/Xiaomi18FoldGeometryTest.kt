package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class Xiaomi18FoldGeometryTest {
    @Test fun `connected lhasa panels keep identity after compatibility width changes`() {
        val areas = listOf(1168L * 1712L, 1672L * 2364L)
        assertEquals(FoldPanel.COVER, launcherPanel(1168L * 1712L, areas,
            425, 1712, 1168, 2.75f))
        assertEquals(FoldPanel.INNER, launcherPanel(1672L * 2364L, areas,
            498, 2364, 1672, 2.75f))
    }

    @Test fun `both orientations retain physical panel identity at simulated density`() {
        val areas = listOf(1140L * 1680L, 2360L * 1672L)
        for ((width, height, panel) in listOf(Triple(1140, 1680, FoldPanel.COVER),
            Triple(1680, 1140, FoldPanel.COVER), Triple(2360, 1672, FoldPanel.INNER),
            Triple(1672, 2360, FoldPanel.INNER))) {
            assertEquals(panel, launcherPanel(width.toLong() * height, areas, 434, width, height, 2.625f))
        }
    }

    @Test fun `every pose keeps grid and control column inside the panel without overlaps`() {
        val density = 2.75f
        for ((width, height, cover) in listOf(Triple(1168f, 1712f, true), Triple(1712f, 1168f, true),
            Triple(2364f, 1672f, false), Triple(1672f, 2364f, false))) {
            val w = width / density
            val h = height / density
            val frame = duoHomeFrame(duoHomePose(cover, w, h), w, h)
            val g = frame.grid
            assertTrue(g.iconLeft >= 0f && g.firstRowTop > 0f)
            assertTrue(frame.gridBottom < frame.dots.centerY - frame.dots.dot)
            val spreadRight = g.iconLeft + (if (frame.pose.spread) g.pageStride else 0f) + g.iconGridWidth
            if (frame.pose.sideColumn) {
                assertTrue(spreadRight < frame.dock.box.left)
                assertTrue(frame.status.bounds.bottom < frame.dock.box.top)
                assertTrue(frame.dock.box.bottom < frame.search.top)
            } else {
                // Open portrait (photo 6): dots between the grid and the Dock, Search beside the Dock.
                assertTrue(frame.gridBottom < frame.dots.centerY && frame.dots.centerY < frame.dock.box.top)
                assertTrue(frame.search.left > frame.dock.box.right && frame.gridBottom < frame.search.top)
            }
            for (box in listOf(frame.dock.box, frame.search, frame.status.bounds)) {
                assertTrue(box.left >= 0f && box.top >= 0f && box.right <= w + .01f && box.bottom <= h + .01f)
            }
        }
    }
}
