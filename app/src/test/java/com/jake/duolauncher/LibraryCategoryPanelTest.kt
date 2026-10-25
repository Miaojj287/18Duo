package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCategoryPanelTest {
    @Test fun `wide category is a full screen centred grid at iPad Home pitch with its title on the first icon`() {
        val f = libraryCategoryFrame(860f, 608f, wide = true)
        assertEquals(6, f.columns)
        assertEquals((860f - f.gridWidth) / 2f, f.gridLeft, .01f)
        assertTrue(f.icon in 56f..64f)
        // The title starts exactly where the first icon does.
        assertEquals(f.gridLeft + (f.pitch - f.icon) / 2f, f.titleLeft, .01f)
        assertTrue(f.titleTop > 56f && f.titleTop < 608f * .25f)
    }

    @Test fun `narrow category spans the four Home columns of the cover library`() {
        val f = libraryCategoryFrame(320f, 640f, wide = false)
        assertEquals(4, f.columns)
        assertEquals(0f, f.gridLeft, .01f)
        assertEquals(320f, f.gridWidth, .01f)
        assertEquals(54f, f.icon, .01f)
        assertTrue(f.titleTop in 56f..96f)
        assertEquals(30f, f.titleSize, .01f)
    }

    @Test fun `the category springs follow the iOS response model`() {
        // Opening overshoots a hair (damping < 1); closing never does.
        assertTrue(LIBRARY_CATEGORY_OPEN.dampingFraction < 1f)
        assertEquals(1f, LIBRARY_CATEGORY_CLOSE.dampingFraction, 0f)
        assertTrue(LIBRARY_CATEGORY_CLOSE.response < LIBRARY_CATEGORY_OPEN.response)
    }

    @Test fun `the zoom grows into the category as it rests, clipped by the screen`() {
        val f = libraryCategoryFrame(860f, 608f, wide = true)
        val (top, bottom) = libraryCategoryViewRect(f, 5, 608f)
        assertEquals(f.titleTop, top, .01f)
        // One row of five apps: the title, its gap and one icon row.
        assertEquals(f.titleTop + LIBRARY_CATEGORY_TITLE_HEIGHT + f.titleGap + f.icon + LIBRARY_CATEGORY_LABEL_BLOCK, bottom, .01f)
        assertEquals(608f, libraryCategoryViewRect(f, 200, 608f).second, .01f)
    }

    @Test fun `rows round up`() {
        assertEquals(0, libraryCategoryRows(0, 4))
        assertEquals(1, libraryCategoryRows(4, 4))
        assertEquals(2, libraryCategoryRows(5, 4))
    }

    @Test fun `the session hoists the open category and cancel clears it with the search`() {
        val session = LibrarySearchSession()
        assertFalse(session.active)
        session.openCategory = LibraryCategory.SOCIAL.name
        assertTrue(session.active)
        assertFalse(session.searching)
        session.query = "we"; session.work = true
        session.cancel()
        assertNull(session.openCategory)
        assertEquals("", session.query)
        assertFalse(session.open)
        assertFalse(session.active)
        // The Personal / Work tab is a choice, not part of the search: it survives.
        assertTrue(session.work)
    }
}
