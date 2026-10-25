package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotNoticeTest {
    @Test fun `the re-enable notice appears once, only when it can help`() {
        assertEquals(SnapshotNotice.SHOWN, snapshotNotice(serviceEnabled = true, checked = true, granted = false, dismissed = false))
        assertEquals(SnapshotNotice.HIDDEN, snapshotNotice(serviceEnabled = true, checked = true, granted = true, dismissed = false))
        assertEquals(SnapshotNotice.HIDDEN, snapshotNotice(serviceEnabled = true, checked = true, granted = false, dismissed = true))
        // Never checked yet (service not bound since the update) or service off: nothing to say.
        assertEquals(SnapshotNotice.HIDDEN, snapshotNotice(serviceEnabled = true, checked = false, granted = false, dismissed = false))
        assertEquals(SnapshotNotice.HIDDEN, snapshotNotice(serviceEnabled = false, checked = true, granted = false, dismissed = false))
    }
}
