package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Core.17 fix round 2 (fixnav2): the flight home, its backdrop hand-off, and the snapshot store. */
class NavigationFlightTest {
    private val innerW = 860f
    private val innerH = 608f

    private fun aim(
        current: FlightAim = FlightAim.HOLD,
        taskKnown: Boolean = true,
        liveFresh: Boolean = true,
        liveFound: Boolean = false,
        liveSettled: Boolean = true,
        frozenFound: Boolean = false,
        waitedTooLong: Boolean = false,
    ) = flightAim(current, taskKnown, liveFresh, liveFound, liveSettled, frozenFound, waitedTooLong)

    // ---------------------------------------------------------------- where the card flies (R3)

    @Test fun `an icon on the live Home of this panel is the target from the first frame`() {
        assertEquals(FlightAim.ICON, aim(liveFound = true))
        assertEquals(FlightAim.ICON, aim(current = FlightAim.PROVISIONAL, liveFound = true, liveSettled = false))
        assertEquals(FlightAim.ICON, aim(current = FlightAim.FALLBACK, liveFound = true))
    }

    @Test fun `a settled live Home without the icon sends the card up and away (another page)`() {
        assertEquals(FlightAim.FALLBACK, aim())
        assertEquals(FlightAim.FALLBACK, aim(current = FlightAim.PROVISIONAL, frozenFound = true))
    }

    @Test fun `after a fold the frozen Home of this panel gives the target until Home relays out`() {
        // Home is still laid out for the other panel: its live answer is stale, not "absent".
        assertEquals(FlightAim.PROVISIONAL, aim(liveFresh = false, frozenFound = true))
        // Nothing known for this panel: hold, never a detour to the middle of the screen.
        assertEquals(FlightAim.HOLD, aim(liveFresh = false))
        assertEquals(FlightAim.FALLBACK, aim(liveFresh = false, waitedTooLong = true))
    }

    @Test fun `a page still settling never pulls a card off the icon it is flying into`() {
        assertEquals(FlightAim.ICON, aim(current = FlightAim.ICON, liveSettled = false))
        assertEquals(FlightAim.PROVISIONAL, aim(current = FlightAim.PROVISIONAL, liveSettled = false, frozenFound = true))
        assertEquals(FlightAim.HOLD, aim(liveSettled = false))
    }

    @Test fun `an unknown app holds until its task list arrives`() {
        assertEquals(FlightAim.HOLD, aim(taskKnown = false))
        assertEquals(FlightAim.FALLBACK, aim(taskKnown = false, waitedTooLong = true))
    }

    @Test fun `progress is measured on the shrink, so a re-aim is continuous`() {
        val from = ZoomBounds(100f, 150f, 600f, 503f)
        val toIcon = 54f
        val mid = SwitcherMotion.shrinkProgress(300f, from.height, toIcon)
        assertEquals((from.height - 300f) / (from.height - toIcon), mid, 1e-4f)
        // Wherever the target moves, the same card height means the same progress.
        assertEquals(mid, SwitcherMotion.shrinkProgress(300f, from.height, toIcon), 0f)
        assertEquals(0f, SwitcherMotion.shrinkProgress(from.height + 20f, from.height, toIcon), 0f)
        assertEquals(1f, SwitcherMotion.shrinkProgress(40f, from.height, toIcon), 0f)
        assertEquals(1f, SwitcherMotion.shrinkProgress(54f, 54.5f, toIcon), 0f)
    }

    @Test fun `a held card stays near where it was released and on screen`() {
        val released = ZoomBounds.centered(430f, 420f, 520f, 368f)
        val hold = SwitcherMotion.holdCard(released, innerW, innerH)
        assertEquals(released.width * SwitcherMotion.HOLD_SHRINK, hold.width, 1e-3f)
        assertTrue(hold.centerY < released.centerY)
        assertTrue(hold.centerY in innerH * .3f..innerH * .62f)
        assertEquals(released.centerX, hold.centerX, 1e-3f)
        // Released far to one side: held toward the middle third, never off screen.
        val side = SwitcherMotion.holdCard(released.offset(-400f, 0f), innerW, innerH)
        assertEquals(innerW * .3f, side.centerX, 1e-3f)
        // While held, the card is far from icon size: no icon cross-fade, no fallback fade.
        val p = SwitcherMotion.shrinkProgress(hold.height, released.height, 54f)
        assertEquals(0f, SwitcherMotion.iconMix(p), 0f)
        assertEquals(0f, SwitcherMotion.fallbackFade(p), 0f)
    }

    // ---------------------------------------------------------------- Home under the card (N3)

    @Test fun `the frozen Home is handed to the live one only once it is sharp and undimmed`() {
        val from = SwitcherMotion.dragBackdrop(.4f)
        val early = SwitcherMotion.homeReturnBackdrop(from, .45f)
        assertFalse(SwitcherMotion.backdropHandoffReady(.45f, early.blur, early.dim))
        val ready = SwitcherMotion.homeReturnBackdrop(from, SwitcherMotion.HANDOFF_PROGRESS)
        assertEquals(0f, ready.blur, 1e-4f)
        assertEquals(0f, ready.dim, 1e-4f)
        assertTrue(SwitcherMotion.backdropHandoffReady(SwitcherMotion.HANDOFF_PROGRESS, ready.blur, ready.dim))
        // Blur and dim fall continuously with the card: no step anywhere along the flight.
        var last = SwitcherMotion.homeReturnBackdrop(from, 0f)
        for (i in 1..100) {
            val next = SwitcherMotion.homeReturnBackdrop(from, i / 100f)
            assertTrue(next.blur <= last.blur + 1e-6f && last.blur - next.blur < .05f)
            assertTrue(next.dim <= last.dim + 1e-6f && last.dim - next.dim < .05f)
            last = next
        }
    }

    // ---------------------------------------------------------------- snapshots (R4, screen-off race)

    private class Snap(val owner: String?, val size: Long = 10)

    private fun store() = RevocableSnapshots(SnapshotLru<String, Snap>(8, 1_000) { it.size }) { it.owner }

    @Test fun `a capture finishing after the screen went off never repopulates the cleared store`() {
        val s = store()
        val epoch = s.epoch
        assertTrue(s.put("cal#0", Snap("cal#0"), epoch))
        s.clear()
        assertNull(s.get("cal#0"))
        // Taken before the lock, filed after it: refused.
        assertFalse(s.put("cal#0", Snap("cal#0"), epoch))
        assertNull(s.get("cal#0"))
        assertTrue(s.put("cal#0", Snap("cal#0"), s.epoch))
    }

    @Test fun `a snapshot is only ever returned for the app and profile it was captured from`() {
        val s = store()
        assertFalse(s.put("clock#0", Snap("cal#0"), s.epoch))
        assertNull(s.get("clock#0"))
        assertTrue(s.put("cal#0", Snap("cal#0"), s.epoch))
        assertNull(s.get("cal#10"))
        assertEquals("cal#0", s.get("cal#0")?.owner)
        assertTrue(snapshotKey("com.cal", 0) != snapshotKey("com.cal", 10))
    }

    // ---------------------------------------------------------------- published icon positions (N1)

    @Test fun `published positions say whether they belong to this panel`() {
        val icon = HomeIconEntry("com.cal/.Main", ZoomBounds(200f, 300f, 348f, 448f), HomeIconKind.GRID)
        val cover = HomeIconLayout(1168f, 1712f, listOf(icon), 0L)
        assertTrue(cover.matches(1168f, 1712f))
        assertFalse(cover.matches(2364f, 1672f))
        assertTrue(HomeIconLayout(-1f, -1f, emptyList(), 0L).matches(2364f, 1672f))
        assertEquals(icon, cover.find("com.cal/.Main", "com.cal", 1168f, 1712f))
        assertEquals(148f, cover.iconHeight!!, 0f)
        assertTrue(cover.sameAs(HomeIconLayout(1168f, 1712f, listOf(icon.copy()), 99L)))
        assertFalse(cover.sameAs(HomeIconLayout(1168f, 1712f, listOf(icon.copy(bounds = icon.bounds.offset(4f, 0f))), 0L)))
    }
}
