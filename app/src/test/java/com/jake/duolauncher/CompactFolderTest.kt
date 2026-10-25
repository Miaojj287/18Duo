package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class CompactFolderTest {
    private val id = "folder:00000000-0000-0000-0000-000000000001"

    @Test fun dissolvingRetainsEveryChildAndOtherPlacements() {
        val layout = HomeLayout(listOf(id, "other"), listOf("dock", null, null, null),
            folders = listOf(FolderEntry(id, "Folder", listOf("a", "b", "c"))))
        val next = dissolveFolder(layout, id)
        assertTrue(next.folders.isEmpty())
        assertEquals("a", next.slotAt(0))
        assertEquals("other", next.slotAt(1))
        assertTrue(next.slots.containsAll(listOf("a", "b", "c")))
        assertEquals(layout.dock, next.dock)
        assertEquals(next, dissolveFolder(next, id))
    }

    @Test fun leadingFolderOverflowSkipsWidgetsAndPreservesChildren() {
        val layout = HomeLayout(List(HOME_CELLS) { "home-$it" }, List(4) { null },
            widgetPlacements = listOf(WidgetPlacement(0, CLOCK_WIDGET, -1, 0, 0, 4, 2)),
            folders = listOf(FolderEntry(id, "Folder", (0..29).map { "child-$it" })),
            leadingSlots = List(HOME_CELLS) { if (it == HOME_CELLS - 1) id else if (it >= 8) "leading-$it" else null })
        val next = dissolveFolder(layout, id)
        assertEquals("child-0", next.slotAt(-1))
        assertEquals(layout.widgetPlacements, next.widgetPlacements)
        assertEquals(layout.slots, next.slots.take(HOME_CELLS))
        (0..29).forEach { assertNotNull(next.indexOfShortcut("child-$it")) }
        (0..7).forEach { assertNull(next.leadingSlots[it]) }
    }

}
