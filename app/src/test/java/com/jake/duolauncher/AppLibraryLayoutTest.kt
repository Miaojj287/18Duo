package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLibraryLayoutTest {
    // The Xiaomi 18 Fold's inner panel, unfolded landscape.
    private val frame = wideLibraryFrame(860f, 608f)

    @Test fun `grid of four platters is centred and spans the iPad's 19 to 81 percent`() {
        assertEquals(4, frame.columns)
        assertEquals(430f, frame.gridLeft + frame.gridWidth / 2f, .01f)
        assertEquals(.195f, frame.gridLeft / 860f, .005f)
        assertEquals(.805f, (frame.gridLeft + frame.gridWidth) / 860f, .005f)
        assertEquals(106.6f, frame.folderSide, .1f)
        assertEquals(129f, frame.gridTop, 1f)
        // Platter + its name fit inside one row pitch.
        assertTrue(frame.folderSide + 23f < frame.rowPitch)
    }

    @Test fun `idle field is a centred capsule above the grid`() {
        assertEquals(430f, frame.fieldLeft + frame.fieldWidth / 2f, 1f)
        assertEquals(.276f * 860f, frame.fieldWidth, .01f)
        assertEquals(67f, frame.fieldTop + frame.fieldHeight / 2f, 1f)
        assertTrue(frame.fieldTop + frame.fieldHeight < frame.gridTop)
    }

    @Test fun `search field moves left of Cancel and the list is centred under it`() {
        assertEquals(frame.listLeft, frame.searchFieldLeft, 0f)
        assertEquals(430f, frame.listLeft + frame.listWidth / 2f, .5f)
        assertEquals(.458f, frame.listWidth / 860f, .002f)
        assertEquals(frame.searchFieldLeft + frame.searchFieldWidth, frame.cancelLeft, .01f)
        // Cancel's centre sits where the iPad's "取消" is (~71.5 %W) and never leaves the screen.
        assertEquals(.715f, (frame.cancelLeft + frame.cancelWidth / 2f) / 860f, .01f)
        assertTrue(frame.listTop > frame.fieldTop + frame.fieldHeight)
    }

    @Test fun `work profile band pushes the grid and the list down`() {
        val work = wideLibraryFrame(860f, 608f, extraTop = WIDE_LIBRARY_WORK_BAND)
        assertEquals(frame.gridTop + WIDE_LIBRARY_WORK_BAND, work.gridTop, 0f)
        assertEquals(frame.listTop + WIDE_LIBRARY_WORK_BAND, work.listTop, 0f)
        assertEquals(frame.fieldTop, work.fieldTop, 0f)
    }

    @Test fun `section index shortens with dots like iOS`() {
        val letters = ('A'..'Z').toList() + '#'
        assertEquals(letters, compactSectionIndex(letters, 40))
        val short = compactSectionIndex(letters, 11)
        assertEquals(11, short.size)
        assertEquals('A', short.first())
        assertEquals('#', short.last())
        assertEquals(null, short[1])
        assertFalse(short.filterNotNull().isEmpty())
        // Never two dots in a row, never more entries than slots.
        assertFalse(short.zipWithNext().any { (a, b) -> a == null && b == null })
        for (slots in 3..27) assertTrue(compactSectionIndex(letters, slots).size <= maxOf(slots, 3))
    }

    @Test fun `adjacent letters need no dot between them`() {
        assertEquals(listOf('A', 'B', 'C'), compactSectionIndex(listOf('A', 'B', 'C'), 3))
        assertEquals(listOf('A', null, 'D'), compactSectionIndex(listOf('A', 'B', 'C', 'D'), 3))
    }
}
