package com.jake.duolauncher

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test

/** r5-fold: panel identity after a Home restart, and Home's orientation around the handoff. */
class FoldHandoffTest {
    private val portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val free = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    private val inner = 1672 to 2364
    private val cover = 1168 to 1712

    @Test fun `one lit lhasa display identifies its panel by its short side`() {
        assertEquals(FoldPanel.INNER, panelFromSingleDisplay(1672, 2364, 440))
        assertEquals(FoldPanel.INNER, panelFromSingleDisplay(2364, 1672, 440))
        assertEquals(FoldPanel.COVER, panelFromSingleDisplay(1168, 1712, 440))
        assertEquals(FoldPanel.UNKNOWN, panelFromSingleDisplay(0, 1712, 440))
        assertEquals(FoldPanel.UNKNOWN, panelFromSingleDisplay(1168, 1712, 0))
    }

    @Test fun `HyperOS lists only the lit panel yet the coordinator still knows it`() {
        // dumpsys display 2026-10-20: logical display 1 (cover) is disabled while the inner is lit,
        // so getDisplays() returns one display and two-area detection is always UNKNOWN.
        assertEquals(FoldPanel.UNKNOWN, panelFromPhysicalAreas(listOf(1672L * 2364), listOf(1672L * 2364)))
        assertEquals(FoldPanel.INNER, foldDisplayPanel(listOf(inner), listOf(inner), 440, FoldPanel.UNKNOWN))
        assertEquals(FoldPanel.COVER, foldDisplayPanel(listOf(cover), listOf(cover), 440, FoldPanel.UNKNOWN))
        // Both listed: the physical areas still decide, even against a stale window report.
        assertEquals(FoldPanel.COVER, foldDisplayPanel(listOf(cover), listOf(inner, cover), 440, FoldPanel.INNER))
        // One lit display: the window's own report wins (size-overridden emulator: phone mode,
        // inner window), the lone mode only fills in before any window has reported.
        assertEquals(FoldPanel.INNER, foldDisplayPanel(listOf(1080 to 2400), listOf(1080 to 2400), 440, FoldPanel.INNER))
        // Nothing lit (screen off mid-swap): the window's own panel is the only evidence.
        assertEquals(FoldPanel.INNER, foldDisplayPanel(emptyList(), listOf(cover), 440, FoldPanel.INNER))
        assertEquals(FoldPanel.UNKNOWN, foldDisplayPanel(emptyList(), listOf(cover), 440, FoldPanel.UNKNOWN))
    }

    @Test fun `an UNKNOWN panel is what disabled the inner fold animation`() {
        // logcat 18:53:29: "phase=RECOVERY panel=UNKNOWN angle=172" while folding from the inner panel.
        val unknown = FoldSignal(angleDegrees = 120f, activePanel = FoldPanel.UNKNOWN)
        assertEquals(1f, foldTrackingProgress(unknown, FoldPanel.INNER, 0L), 0f)
        val known = unknown.copy(activePanel = FoldPanel.INNER)
        assertEquals(hingeReveal(120f), foldTrackingProgress(known, FoldPanel.INNER, 0L), 0f)
    }

    @Test fun `cover home is portrait and inner home follows the sensor at every hinge angle`() {
        // Round 12: no hinge-driven request at all (the handoff frames are veiled instead, FoldOrientationPolicyTest).
        assertEquals(portrait, homeOrientation(FoldPanel.COVER))
        assertEquals(free, homeOrientation(FoldPanel.INNER))
        assertEquals(free, homeOrientation(FoldPanel.UNKNOWN))
        var cover = FoldHandoffState(); var inner = FoldHandoffState()
        for (angle in (0..180 step 3).map { it.toFloat() } + (180 downTo 0 step 3).map { it.toFloat() }) {
            // StandBy postures and ordinary tilting never change the request; only a veiled handoff does.
            cover = foldHandoffStep(cover, FoldHandoffInput(FoldPanel.COVER, angle, now = 0L, standbyActive = angle > 100f)).state
            inner = foldHandoffStep(inner, FoldHandoffInput(FoldPanel.INNER, angle, now = 0L, standbyActive = angle < 30f)).state
            assertEquals("cover at $angle", portrait, cover.request)
            assertEquals("inner at $angle", free, inner.request)
        }
    }

    @Test fun `standby still overrides home on both panels`() {
        for (panel in FoldPanel.entries) for (direction in StandbyDirection.entries)
            assertEquals(direction.orientation, launcherOrientation(panel, true, direction, home = portrait))
        assertEquals(portrait, launcherOrientation(FoldPanel.COVER, home = portrait))
        assertEquals(free, launcherOrientation(FoldPanel.COVER, home = free))
    }
}
