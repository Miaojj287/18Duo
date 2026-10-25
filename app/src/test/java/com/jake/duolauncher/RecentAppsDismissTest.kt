package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAppsDismissTest {
    @Test fun `an app swiped away stays hidden until a newer launch`() {
        assertFalse(recentStillDismissed(lastOpened = 1_000L, dismissedAt = 0L))
        assertTrue(recentStillDismissed(lastOpened = 1_000L, dismissedAt = 1_000L))
        assertTrue(recentStillDismissed(lastOpened = 900L, dismissedAt = 1_000L))
        assertFalse(recentStillDismissed(lastOpened = 1_001L, dismissedAt = 1_000L))
    }

    @Test fun `dock recents still skip pinned and unavailable apps`() {
        assertEquals(listOf("b", "d"), recentDockIds(listOf("a", "b", "c", "d"), setOf("b", "c", "d"), listOf("c", null)))
    }
}
