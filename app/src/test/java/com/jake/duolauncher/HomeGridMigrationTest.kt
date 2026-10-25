package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class HomeGridMigrationTest {
    private val folder = FolderEntry("folder:00000000-0000-0000-0000-000000000001", "Tools", listOf("calc", "notes"))
    private val restore = WidgetRestore(7, "com.example/.ClockProvider", 0, "Clock", "Personal")

    /**
     * A realistic Core.16 (five rows × four columns) layout: page 0 = two small widgets above three
     * rows of apps, page 1 = a medium widget spanning rows 1–2 between app rows plus a folder and an
     * intentional gap, page 2 = a tall provider widget awaiting rebinding, plus a leading Today page
     * with a large widget and shortcuts, and a Dock with an empty slot.
     */
    private fun fiveRowFixture(): HomeLayout {
        val slots = MutableList<String?>(FIVE_ROW_HOME_CELLS * 3) { null }
        for (i in 8 until 20) slots[i] = "p0-$i"
        for (c in 0 until 4) slots[20 + c] = "p1-r0-$c"
        slots[20 + 12] = folder.id; slots[20 + 13] = "p1-a"; slots[20 + 15] = "p1-b"; slots[20 + 19] = "p1-last"
        slots[40] = "p2-first"; slots[40 + 19] = "p2-last"
        val widgets = listOf(
            WidgetPlacement(0, nativeWidgetId(NativeWidgetKind.WEATHER, NativeWidgetSize.SMALL), 0, 0, 0, 2, 2),
            WidgetPlacement(1, 2001, 0, 2, 0, 2, 2),
            WidgetPlacement(2, 2003, -1, 0, 0, 4, 4),
            WidgetPlacement(4, 2002, 1, 0, 1, 4, 2),
            WidgetPlacement(7, NEEDS_BINDING_WIDGET, 2, 0, 1, 2, 3),
        )
        val leading = List<String?>(FIVE_ROW_HOME_CELLS) { if (it == 16) "lead-a" else if (it == 19) "lead-b" else null }
        return HomeLayout(slots.dropLastWhile { it == null }, listOf("phone", "safari", null, "music"),
            widgets, listOf(folder), listOf(restore), leading)
    }

    @Test fun `six rows per page again`() {
        assertEquals(6, GRID_ROWS)
        assertEquals(24, HOME_CELLS)
        assertEquals(10, HOME_STATE_SCHEMA)
        assertEquals(4, LAYOUT_BACKUP_VERSION)
    }

    @Test fun `every persisted generation reports the rows it was written with`() {
        assertEquals(6, persistedGridRows(1))
        assertEquals(6, persistedGridRows(8))
        assertEquals(5, persistedGridRows(FIVE_ROW_STATE_SCHEMA))
        assertEquals(6, persistedGridRows(HOME_STATE_SCHEMA))
        assertEquals(6, backupGridRows(1))
        assertEquals(6, backupGridRows(2))
        assertEquals(5, backupGridRows(FIVE_ROW_BACKUP_VERSION))
        assertEquals(6, backupGridRows(LAYOUT_BACKUP_VERSION))
    }

    @Test fun `every app keeps its page row and column and each page gains an empty sixth row`() {
        val old = fiveRowFixture()
        val next = migrateFiveRowHome(old)
        old.slots.forEachIndexed { index, id ->
            if (id == null) return@forEachIndexed
            val page = index / FIVE_ROW_HOME_CELLS
            val local = index % FIVE_ROW_HOME_CELLS
            assertEquals("$id keeps page/row/column", id, next.slots[homeCellIndex(page, local)])
        }
        assertEquals(old.slots.filterNotNull(), next.slots.filterNotNull())
        assertEquals(old.pageCount, next.pageCount)
        for (page in 0 until next.pageCount) {
            assertTrue("page $page row six is empty", next.slotsForPage(page).subList(20, 24).all { it == null })
        }
        // The intentional gap on page 1 stays a gap.
        assertNull(next.slotAt(homeCellIndex(1, 14)))
    }

    @Test fun `widgets folders restores dock and the leading page are unchanged`() {
        val old = fiveRowFixture()
        val next = migrateFiveRowHome(old)
        assertEquals(old.widgetPlacements, next.widgetPlacements)
        assertEquals(old.folders, next.folders)
        assertEquals(old.widgetRestores, next.widgetRestores)
        assertEquals(old.dock, next.dock)
        assertEquals(HOME_CELLS, next.leadingSlots.size)
        assertEquals(old.leadingSlots, next.leadingSlots.take(FIVE_ROW_HOME_CELLS))
        assertTrue(next.leadingSlots.drop(FIVE_ROW_HOME_CELLS).all { it == null })
        assertEquals("lead-a", next.slotAt(homeCellIndex(-1, 16)))
        assertEquals(1, next.slots.count { it == folder.id })
    }

    @Test fun `migrated layout is valid in the six row grid`() {
        val next = migrateFiveRowHome(fiveRowFixture())
        val covered = next.widgetPlacements.flatMap { it.coveredIndices() }
        assertEquals("No two widgets overlap", covered.distinct().size, covered.size)
        next.widgetPlacements.forEach {
            assertTrue(validBackupPlacement(it))
            assertFalse(widgetOverlapsShortcuts(it, next.slots, next.leadingSlots))
            assertTrue(it.coveredIndices().none { index -> next.slotAt(index) != null })
        }
    }

    @Test fun `the new sixth row accepts apps and widgets`() {
        val next = migrateFiveRowHome(fiveRowFixture())
        val dropped = dropApp(next, "new-app", DropTarget.Home(homeCellIndex(0, 21)))
        assertEquals("new-app", dropped.slotAt(homeCellIndex(0, 21)))
        assertEquals(next.slots.filterNotNull().toSet() + "new-app", dropped.slots.filterNotNull().toSet())
        // A small widget can now sit across rows five and six of page 2.
        assertNotNull(widgetCandidate(next, 9, homeCellIndex(2, 16), 2, 2))
    }

    @Test fun `a full five row page does not spill onto the next page`() {
        val apps = List(40) { "app-$it" }
        val next = migrateFiveRowHome(HomeLayout(apps, List(4) { null }))
        assertEquals(2, next.pageCount)
        assertEquals(apps.take(20), next.slotsForPage(0).take(20))
        assertEquals(apps.drop(20), next.slotsForPage(1).take(20))
        assertEquals("app-20", next.slots[HOME_CELLS])
    }

    @Test fun `retained overflow card stays just below the grid`() {
        val overflow = WidgetPlacement(5, 2004, 1, 0, FIVE_ROW_GRID_ROWS, 4, 4)
        val next = migrateFiveRowHome(HomeLayout(listOf("a"), List(4) { null }, listOf(overflow)))
        assertEquals(overflow.copy(row = GRID_ROWS), next.placement(5))
        assertTrue(validBackupPlacement(next.placement(5)!!))
    }

    @Test fun `empty layouts stay empty`() {
        val empty = HomeLayout(emptyList(), List(4) { null }, leadingSlots = List(FIVE_ROW_HOME_CELLS) { null })
        val next = migrateFiveRowHome(empty)
        assertTrue(next.slots.isEmpty())
        assertEquals(List<String?>(HOME_CELLS) { null }, next.leadingSlots)
        assertEquals(1, next.pageCount)
    }
}
