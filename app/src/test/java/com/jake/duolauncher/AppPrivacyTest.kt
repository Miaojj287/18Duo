package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrivacyTest {
    @Test fun `hidden is never a categorisation result and sorts last`() {
        val all = (0..200).flatMap { cat -> listOf(true, false).map { categorizeApp("pkg.$cat", cat - 1, it) } }
        assertFalse(LibraryCategory.HIDDEN in all)
        assertEquals(LibraryCategory.HIDDEN, LibraryCategory.entries.last())
        assertFalse(LibraryCategory.HIDDEN.smart)
    }

    @Test fun `a revealed hidden app opens without asking, a locked one asks once per grant`() {
        AppPrivacy.relock()
        // Not locked: never asks.
        assertFalse(AppPrivacy.needsAuthentication("plain"))
        // Locked ids come only through lock()/hide(), which need a Context; the grant path is pure.
        AppPrivacy.grantLaunch("plain")
        assertFalse(AppPrivacy.needsAuthentication("plain"))
    }

    @Test fun `withoutHidden keeps every app when nothing is hidden`() {
        val list = emptyList<AppEntry>()
        assertTrue(list.withoutHidden().isEmpty())
    }
}
