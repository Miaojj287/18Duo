package com.jake.duolauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 3 (r3-nav finding): the Dock's recent-apps copy of the app being left stayed hidden for the
 * whole return (≈ 0.6 s) even though the card landed on the app's Home icon. Only the icon the card
 * flies into is left empty.
 */
class RecentFlightTargetTest {
    private val w = 1168f; private val h = 1712f
    private fun at(x: Float, y: Float, size: Float = 120f) = ZoomBounds(x, y, x + size, y + size)
    private val grid = HomeIconEntry("com.x/.Main", at(100f, 400f, 150f), HomeIconKind.GRID)
    private val recent = HomeIconEntry("com.x/.Main", at(1000f, 1100f), HomeIconKind.RECENT)

    @Test fun `a gesture return lands on the Home icon, so the recent copy stays drawn`() {
        assertFalse(isFlightTarget(recent, null, listOf(grid, recent), w, h))
        assertTrue(isFlightTarget(grid, null, listOf(grid, recent), w, h))
    }

    @Test fun `without a Home icon on screen the recent copy is the target`() {
        val offPage = grid.copy(bounds = at(w + 200f, 400f, 150f))
        assertTrue(isFlightTarget(recent, null, listOf(offPage, recent), w, h))
        assertTrue(isFlightTarget(recent, null, listOf(recent), w, h))
    }

    @Test fun `a launch from the recent tile returns to it, a launch from the page does not hide it`() {
        assertTrue(isFlightTarget(recent, recent.bounds, listOf(grid, recent), w, h))
        assertFalse(isFlightTarget(recent, grid.bounds, listOf(grid, recent), w, h))
    }

    @Test fun `the page and the Dock are preferred over the recent apps`() {
        val dock = HomeIconEntry("com.x/.Main", at(1000f, 600f), HomeIconKind.DOCK)
        assertTrue(HomeIconKind.RECENT.ordinal > HomeIconKind.DOCK.ordinal)
        assertTrue(HomeIconKind.RECENT.ordinal < HomeIconKind.FOLDER.ordinal)
        assertFalse(isFlightTarget(recent, null, listOf(dock, recent), w, h))
    }
}
