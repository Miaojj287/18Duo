package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 8: why most switcher cards showed a logo, and the rules that now keep every app's last frame. */
class SnapshotPersistenceTest {
    // ---------------------------------------------------------------- the cause: every screen-off wiped them

    private class Snap(val owner: String?, val size: Long = 10)

    private fun store() = RevocableSnapshots(SnapshotLru<String, Snap>(8, 1_000) { it.size }) { it.owner }

    @Test fun `a screen-off keeps the snapshots but refuses a capture still in flight`() {
        val s = store()
        assertTrue(s.put("cal#0", Snap("cal#0"), s.epoch))
        val inFlight = s.epoch
        s.revokeInFlight()
        // Kept across the lock (iOS shows every app as it was left)…
        assertEquals("cal#0", s.get("cal#0")?.owner)
        // …but a frame that was being prepared when the screen went off is not filed.
        assertFalse(s.put("clock#0", Snap("clock#0"), inFlight))
        assertNull(s.get("clock#0"))
        assertTrue(s.put("clock#0", Snap("clock#0"), s.epoch))
    }

    @Test fun `each app keeps one frame per panel shape`() {
        assertNotEquals(snapshotSlot("com.cal#0", true), snapshotSlot("com.cal#0", false))
        assertTrue(snapshotSlot("com.cal#0", false).startsWith("com.cal#0"))
    }

    // ---------------------------------------------------------------- files

    @Test fun `file names round-trip any key and shape and ignore foreign files`() {
        for (key in listOf("com.android.settings#0", "com.tencent.mm#999", "a.b_c-d#10")) for (landscape in listOf(false, true)) {
            val name = SnapshotFiles.baseName(key, landscape) + ".webp"
            assertTrue(name.none { it == '/' || it == '+' || it == '=' })
            assertEquals(key to landscape, SnapshotFiles.parse(name))
        }
        assertNull(SnapshotFiles.parse("random.webp"))
        assertNull(SnapshotFiles.parse("x_q.webp"))
        assertNotEquals(SnapshotFiles.baseName("a#0", false), SnapshotFiles.baseName("a#10", false))
    }

    @Test fun `metadata round-trips and damaged metadata is rejected`() {
        val text = SnapshotFiles.meta(1168, 1712, .42f, 1_760_000_000_000L)
        assertEquals(SnapshotFiles.Meta(1168, 1712, .42f, 1_760_000_000_000L), SnapshotFiles.parseMeta(text))
        assertNull(SnapshotFiles.parseMeta(""))
        assertNull(SnapshotFiles.parseMeta("v2\n1\n1\n0\n0"))
        assertNull(SnapshotFiles.parseMeta("v1\n0\n1712\n.4\n0"))
        assertNull(SnapshotFiles.parseMeta("v1\nx\n1712\n.4\n0"))
    }

    @Test fun `disk keeps the most recent files only`() {
        val files = (1..40).associate { "f$it.webp" to it.toLong() }
        val doomed = SnapshotFiles.prune(files, 32)
        assertEquals(8, doomed.size)
        assertEquals((1..8).map { "f$it.webp" }.toSet(), doomed.toSet())
        assertTrue(SnapshotFiles.prune(files.filterKeys { it != "f40.webp" }.mapValues { it.value }, 64).isEmpty())
    }

    // ---------------------------------------------------------------- our own home indicator is painted out

    @Test fun `the home indicator rows are replaced by their mirror above`() {
        val w = 4; val h = 6
        // Rows 0..5 coloured by row; the bar (value 99) covers rows 4..5, columns 1..2.
        val px = IntArray(w * h) { it / w }
        for (y in 4..5) for (x in 1..2) px[y * w + x] = 99
        SnapshotPolicy.patchRows(px, w, h, 1, 4, 3, 6)
        assertEquals(3, px[4 * w + 1]); assertEquals(2, px[5 * w + 2])
        // Outside the rect nothing changes.
        assertEquals(4, px[4 * w + 0]); assertEquals(5, px[5 * w + 3])
        assertTrue(px.none { it == 99 })
        // Degenerate rects are ignored, never throw.
        SnapshotPolicy.patchRows(px, w, h, 3, 0, 1, 2)
        SnapshotPolicy.patchRows(px, w, h, 0, 0, 4, 2)
        SnapshotPolicy.patchRows(px, w, h, -5, 2, 50, 50)
    }

    // ---------------------------------------------------------------- when an app is photographed without a gesture

    @Test fun `an app is photographed once settled and then at most every few seconds`() {
        val at = 10_000L
        val s = AutoCapturePolicy.SETTLE_MS
        assertFalse(AutoCapturePolicy.due(at + s - 1, at, 0L, null, clear = true, powerSave = false))
        assertTrue(AutoCapturePolicy.due(at + s, at, 0L, null, clear = true, powerSave = false))
        val shot = at + s
        assertFalse(AutoCapturePolicy.due(shot + 1_000, at, 0L, shot, clear = true, powerSave = false))
        assertTrue(AutoCapturePolicy.due(shot + AutoCapturePolicy.RECAPTURE_MS, at, 0L, shot, clear = true, powerSave = false))
        // Battery Saver: only the first.
        assertFalse(AutoCapturePolicy.due(shot + 600_000, at, 0L, shot, clear = true, powerSave = true))
        assertTrue(AutoCapturePolicy.due(at + s, at, 0L, null, clear = true, powerSave = true))
    }

    @Test fun `nothing is photographed over the shade, a dialog, the lock screen or Home`() {
        val at = 10_000L
        val later = at + 5_000L
        // A system window appeared after the app's: it may cover it.
        assertFalse(AutoCapturePolicy.due(later, at, at + 10, null, clear = true, powerSave = false))
        // The app showed a new window since: fine again.
        assertTrue(AutoCapturePolicy.due(later, at + 20, at + 10, null, clear = true, powerSave = false))
        assertFalse(AutoCapturePolicy.due(later, at, 0L, null, clear = false, powerSave = false))
        assertFalse(AutoCapturePolicy.due(later, null, 0L, null, clear = true, powerSave = false))
        val self = "com.jake.duolauncher.core"; val home = "com.jake.duolauncher.MainActivity"
        assertTrue(AutoCapturePolicy.coversApp("com.android.systemui", null, self, home))
        assertTrue(AutoCapturePolicy.coversApp(self, "com.jake.duolauncher.SettingsActivity", self, home))
        assertFalse(AutoCapturePolicy.coversApp(self, home, self, home))
        assertFalse(AutoCapturePolicy.coversApp("com.android.settings", ".Settings", self, home))
        assertFalse(AutoCapturePolicy.coversApp(null, null, self, home))
    }

    @Test fun `disk writes are throttled while an app stays in front`() {
        assertTrue(AutoCapturePolicy.persistNow(firstOfWindow = true, now = 5_000, lastPersistAt = 4_000))
        assertFalse(AutoCapturePolicy.persistNow(firstOfWindow = false, now = 5_000, lastPersistAt = 4_000))
        assertTrue(AutoCapturePolicy.persistNow(false, 4_000 + AutoCapturePolicy.PERSIST_EVERY_MS, 4_000))
        assertTrue(AutoCapturePolicy.persistNow(false, 5_000, null))
    }
}
