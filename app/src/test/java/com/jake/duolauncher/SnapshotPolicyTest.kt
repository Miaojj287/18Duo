package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPolicyTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `snapshots are downscaled to a bounded long side`() {
        assertEquals(1f, SnapshotPolicy.scaleFor(800, 1200), 0f)
        // Cover 1168 × 1712 and inner 2364 × 1672 both come out ≤ 1280 px on the long side.
        assertEquals(1280f, 1712 * SnapshotPolicy.scaleFor(1168, 1712), .5f)
        assertEquals(1280f, 2364 * SnapshotPolicy.scaleFor(2364, 1672), .5f)
    }

    @Test fun `secure windows come back black and are recognised`() {
        assertTrue(SnapshotPolicy.isBlank(IntArray(32) { argb(255, 0, 0, 0) }))
        assertTrue(SnapshotPolicy.isBlank(IntArray(32) { 0 }))
        val mostlyBlack = IntArray(32) { argb(255, 0, 0, 0) }.also { it[7] = argb(255, 200, 200, 200) }
        assertFalse(SnapshotPolicy.isBlank(mostlyBlack))
        assertFalse(SnapshotPolicy.isBlank(IntArray(0)))
    }

    @Test fun `luma follows perceived brightness`() {
        assertEquals(1f, SnapshotPolicy.luma(argb(255, 255, 255, 255)), .001f)
        assertEquals(0f, SnapshotPolicy.luma(argb(255, 0, 0, 0)), .001f)
        assertTrue(SnapshotPolicy.luma(argb(255, 0, 255, 0)) > SnapshotPolicy.luma(argb(255, 0, 0, 255)))
        assertEquals(.5f, SnapshotPolicy.averageLuma(intArrayOf(argb(255, 255, 255, 255), argb(255, 0, 0, 0))), .001f)
    }

    @Test fun `snapshots from the other panel are not shown`() {
        assertTrue(SnapshotPolicy.fitsPanel(876, 1284, 1168f, 1712f))
        assertFalse(SnapshotPolicy.fitsPanel(1280, 905, 1168f, 1712f))
        assertTrue(SnapshotPolicy.fitsPanel(1280, 905, 2364f, 1672f))
        assertFalse(SnapshotPolicy.fitsPanel(0, 905, 2364f, 1672f))
    }

    @Test fun `home indicator contrasts with the app behind it`() {
        assertEquals(HomeBarStyle.ADAPTIVE, SnapshotPolicy.barStyle(null))
        assertEquals(HomeBarStyle.DARK, SnapshotPolicy.barStyle(.9f))
        assertEquals(HomeBarStyle.LIGHT, SnapshotPolicy.barStyle(.1f))
        assertEquals(HomeBarStyle.ADAPTIVE, SnapshotPolicy.barStyle(.5f))
    }

    @Test fun `cache evicts least recently used entries by count and bytes`() {
        val lru = SnapshotLru<String, Int>(3, 100) { it.toLong() }
        lru.put("a", 10); lru.put("b", 10); lru.put("c", 10)
        lru.get("a")
        lru.put("d", 10)
        assertNull(lru.get("b"))
        assertEquals(listOf("c", "a", "d"), lru.keys())
        lru.put("e", 85)
        assertEquals(listOf("d", "e"), lru.keys())
        assertEquals(95L, lru.bytes)
        // Replacing a key frees its old size; an entry larger than the budget is refused.
        lru.put("e", 5)
        assertEquals(15L, lru.bytes)
        lru.put("huge", 500)
        assertNull(lru.get("huge"))
        lru.remove("d")
        assertEquals(5L, lru.bytes)
        lru.clear()
        assertEquals(0, lru.size)
        assertEquals(0L, lru.bytes)
    }
}
