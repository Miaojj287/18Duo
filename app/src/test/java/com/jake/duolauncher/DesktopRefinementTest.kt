package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class DesktopRefinementTest {
    @Test fun `removing a blank middle page shifts whole pages and widgets together`() {
        val slots = MutableList<String?>(HOME_CELLS * 3) { null }
        slots[3] = "first"; slots[HOME_CELLS * 2 + 7] = "last"
        val widget = WidgetPlacement(0, 12, 2, 0, 0, 2, 1)
        val before = HomeLayout(slots, List(4) { null }, listOf(widget))
        val after = compactEmptyHomePages(before)
        assertEquals(2, after.pageCount)
        assertEquals("first", after.slots[3])
        assertEquals("last", after.slots[HOME_CELLS + 7])
        assertEquals(1, after.widgetPlacements.single().page)
    }
    @Test fun `page removal preserves the leading page and removes only that page bindings`() {
        val slots = MutableList<String?>(HOME_CELLS + 2) { null }
        slots[0] = "folder:11111111-1111-1111-1111-111111111111"; slots[HOME_CELLS + 1] = "kept"
        val before = HomeLayout(slots, List(4) { null }, listOf(
            WidgetPlacement(1, 12, 0, 2, 0, 1, 1), WidgetPlacement(2, 13, -1, 0, 0, 2, 2)),
            folders = listOf(FolderEntry("folder:11111111-1111-1111-1111-111111111111", "Test", listOf("member"))))
        val after = removeHomePage(before, 0)
        assertEquals("kept", after.slots[1])
        assertTrue(after.folders.isEmpty())
        assertEquals(-1, after.widgetPlacements.single().page)
        assertEquals(before.leadingSlots, after.leadingSlots)
    }
    @Test fun `last home page cannot be removed`() {
        val one = HomeLayout(listOf("a"), List(4) { null })
        assertEquals(one, removeHomePage(one, 0))
        assertEquals(one, removeHomePage(one, -1))
    }
    @Test fun `recent dock uses installed distinct apps and does not duplicate pinned apps`() {
        assertEquals(listOf("b", "c"), recentDockIds(listOf("gone", "a", "b", "b", "c", "d"),
            setOf("a", "b", "c", "d"), listOf("a", null)))
    }
    @Test fun `a long slow upward release opens recents while a short fast flick goes home`() {
        val slow = NavigationGesture(NavigationEdge.BOTTOM)
        slow.down(100f, 400f, 0); slow.move(100f, 200f, 100); slow.move(100f, 190f, 220)
        assertEquals(NavigationAction.RECENTS, slow.up(230))
        val fast = NavigationGesture(NavigationEdge.BOTTOM)
        fast.down(100f, 400f, 0); fast.move(100f, 300f, 80)
        assertEquals(NavigationAction.HOME, fast.up(90))
        // Core.17: like iOS, a long fast flick goes Home too; only a pause (or a stop high up) opens the switcher.
        val longFlick = NavigationGesture(NavigationEdge.BOTTOM)
        longFlick.down(100f, 400f, 0); longFlick.move(100f, 180f, 80)
        assertEquals(NavigationAction.HOME, longFlick.up(90))
    }
    @Test fun `reversing a bottom swipe cancels navigation`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 400f, 0); gesture.move(100f, 200f, 100); gesture.move(100f, 380f, 180)
        assertNull(gesture.up(190))
    }
}
