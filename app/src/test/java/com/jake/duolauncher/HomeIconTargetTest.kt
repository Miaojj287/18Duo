package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIconTargetTest {
    private val w = 1168f
    private val h = 1712f
    private fun at(x: Float, y: Float, size: Float = 148f) = ZoomBounds(x, y, x + size, y + size)

    @Test fun `the exact app on the visible page wins, grid before dock before folder`() {
        val entries = listOf(
            HomeIconEntry("com.x/.Main", at(900f, 900f, 120f), HomeIconKind.DOCK),
            HomeIconEntry("com.x/.Main", at(100f, 400f), HomeIconKind.GRID),
            HomeIconEntry("com.x/.Main", at(300f, 600f), HomeIconKind.FOLDER),
        )
        assertEquals(HomeIconKind.GRID, pickHomeIcon(entries, "com.x/.Main", "com.x", w, h)?.kind)
        assertEquals(HomeIconKind.DOCK, pickHomeIcon(listOf(entries[0], entries[2]), "com.x/.Main", "com.x", w, h)?.kind)
    }

    @Test fun `icons on neighbouring pager pages do not count`() {
        val entries = listOf(HomeIconEntry("com.x/.Main", at(w + 100f, 400f), HomeIconKind.GRID))
        assertNull(pickHomeIcon(entries, "com.x/.Main", "com.x", w, h))
        // Mostly off-screen during a page scroll: also ignored.
        val half = listOf(HomeIconEntry("com.x/.Main", at(w - 40f, 400f), HomeIconKind.GRID))
        assertNull(pickHomeIcon(half, "com.x/.Main", "com.x", w, h))
    }

    @Test fun `falls back to the same package only within the same profile`() {
        val entries = listOf(HomeIconEntry("duo-profile:v1:10:com.x/.Other", at(100f, 400f), HomeIconKind.GRID))
        assertEquals("com.x", entries.single().packageName)
        // Round 6: a personal app never lands in a work/dual-app copy's icon (and vice versa) …
        assertNull(pickHomeIcon(entries, "com.x/.Main", "com.x", w, h))
        // … but another activity of the same package in the same profile still counts.
        assertEquals(entries.single(), pickHomeIcon(entries, "duo-profile:v1:10:com.x/.Main", "com.x", w, h))
        // With no app id (a task without a launcher entry) the package alone decides.
        assertEquals(entries.single(), pickHomeIcon(entries, null, "com.x", w, h))
        assertNull(pickHomeIcon(entries, null, "com.y", w, h))
        assertNull(pickHomeIcon(entries, null, null, w, h))
    }

    @Test fun `a widget is the target only of the launch that started in it (round 9)`() {
        val widget = HomeIconEntry("com.w/.Main", ZoomBounds(60f, 300f, 700f, 640f), HomeIconKind.WIDGET)
        val icon = HomeIconEntry("com.w/.Main", at(800f, 1200f), HomeIconKind.GRID)
        // Opened from the widget: back into the widget, even though the app's icon is on screen too.
        assertEquals(widget, pickHomeIcon(listOf(widget, icon), "com.w/.Main", "com.w", w, h, launchedFrom = widget.bounds))
        // Opened from its icon or the switcher: the widget never catches the card.
        assertEquals(icon, pickHomeIcon(listOf(widget, icon), "com.w/.Main", "com.w", w, h, launchedFrom = icon.bounds))
        assertEquals(icon, pickHomeIcon(listOf(widget, icon), "com.w/.Main", "com.w", w, h))
        assertNull(pickHomeIcon(listOf(widget), "com.w/.Main", "com.w", w, h))
    }

    @Test fun `degenerate bounds are ignored`() {
        val entries = listOf(HomeIconEntry("com.x/.Main", ZoomBounds(10f, 10f, 10f, 10f), HomeIconKind.GRID))
        assertNull(pickHomeIcon(entries, "com.x/.Main", "com.x", w, h))
    }

    @Test fun `positions laid out for the other panel are not trusted (P1-9)`() {
        // Opened on the cover (1168 x 1712), unfolded (2364 x 1672) before swiping home.
        assertFalse(homeLayoutMatches(1168f, 1712f, 2364f, 1672f))
        assertTrue(homeLayoutMatches(2364f, 1672f, 2364f, 1672f))
        assertTrue(homeLayoutMatches(2360f, 1668f, 2364f, 1672f))
    }

    @Test fun `a landing slot is tracked off screen while its page is swiped`() {
        val aimed = HomeIconEntry("com.x/.Main", at(100f, 200f), HomeIconKind.GRID)
        // The page has carried it beyond the left edge: no longer a pick, still the slot.
        val moved = HomeIconEntry("com.x/.Main", at(-400f, 200f), HomeIconKind.GRID)
        val dock = HomeIconEntry("com.x/.Main", at(900f, 1500f, 120f), HomeIconKind.DOCK)
        assertEquals(HomeIconKind.DOCK, pickHomeIcon(listOf(moved, dock), "com.x/.Main", "com.x", w, h)?.kind)
        assertEquals(moved, trackHomeIcon(listOf(moved, dock), aimed, 3f))
    }

    @Test fun `tracking keeps kind, layer and size and prefers the nearest copy`() {
        val aimed = HomeIconEntry("com.x/.Main", at(100f, 200f), HomeIconKind.GRID, IconLayer.LIBRARY)
        val near = HomeIconEntry("com.x/.Main", at(60f, 200f), HomeIconKind.GRID, IconLayer.LIBRARY)
        val far = HomeIconEntry("com.x/.Main", at(700f, 900f), HomeIconKind.GRID, IconLayer.LIBRARY)
        assertEquals(near, trackHomeIcon(listOf(far, near), aimed, 3f))
        // Another layer, another kind or another size (a relayout after a fold) is not the same slot.
        assertNull(trackHomeIcon(listOf(near.copy(layer = IconLayer.HOME)), aimed, 3f))
        assertNull(trackHomeIcon(listOf(near.copy(kind = HomeIconKind.FOLDER)), aimed, 3f))
        assertNull(trackHomeIcon(listOf(HomeIconEntry("com.x/.Main", at(60f, 200f, 120f), HomeIconKind.GRID, IconLayer.LIBRARY)), aimed, 3f))
        assertNull(trackHomeIcon(listOf(near.copy(appId = "com.y/.Main")), aimed, 3f))
    }

    @Test fun `a card takes on its slot's movement in proportion to how far it has shrunk`() {
        assertEquals(0f, SwitcherMotion.slotFollowWeight(-.2f), 0f)
        assertEquals(.5f, SwitcherMotion.slotFollowWeight(.5f), 0f)
        assertEquals(1f, SwitcherMotion.slotFollowWeight(1.3f), 0f)
    }
}
