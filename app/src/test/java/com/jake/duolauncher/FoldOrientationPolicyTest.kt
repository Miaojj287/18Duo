package com.jake.duolauncher

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test

/** r12b-fold: the pure handoff policy (FoldOrientationPolicy.kt). */
class FoldOrientationPolicyTest {
    private val portrait = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private val reversePortrait = ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    private val landscape = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    private val reverseLandscape = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    private val sensorLandscape = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    private val free = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    private val held = ActivityInfo.SCREEN_ORIENTATION_LOCKED
    private val land = Configuration.ORIENTATION_LANDSCAPE
    private val port = Configuration.ORIENTATION_PORTRAIT

    /** Owner's fold, logcat 2026-10-20 18:53:29 (MiuiWallpaper-FoldManager), ~21 ms apart; the cover's
     * rotation was set 5 ms after the 6° sample. */
    private val ownerClose = listOf(176f, 172f, 166f, 159f, 150f, 140f, 128f, 115f, 103f, 94f, 88f, 85f, 82f, 78f, 72f,
        66f, 58f, 48f, 37f, 27f, 19f, 13f, 6f)

    private class Sim(var state: FoldHandoffState = FoldHandoffState()) {
        var now = 0L
        val reveals = mutableListOf<Long>()
        fun step(panel: FoldPanel, angle: Float?, gravity: FoldGravity = FoldGravity.UNKNOWN, orientation: Int = Configuration.ORIENTATION_UNDEFINED,
            rotation: Int = Surface.ROTATION_0, letterboxed: Boolean = false, frameDrawn: Boolean = false,
            standby: Boolean = false, ignored: Boolean = false, advance: Long = 0L, locked: Boolean = false,
            userRotation: Int? = null, honoured: Boolean = false): FoldHandoffStep {
            now += advance
            val step = foldHandoffStep(state, FoldHandoffInput(panel, angle, gravity, orientation, rotation, letterboxed,
                frameDrawn, standby, ignored, now, rotationLocked = locked, innerUserRotation = userRotation,
                fixedHonoured = honoured))
            state = step.state
            if (step.reveal) reveals += now
            return step
        }
    }

    @Test fun `gravity classes follow the device frame and the inner request follows the pose`() {
        assertEquals(FoldGravity.UPRIGHT, foldGravity(0f, 9.8f))
        assertEquals(FoldGravity.UPSIDE_DOWN, foldGravity(.5f, -9.6f))
        assertEquals(FoldGravity.TURNED_CCW, foldGravity(9.8f, 0f))
        assertEquals(FoldGravity.TURNED_CW, foldGravity(-9.8f, 1f))
        for ((x, y) in listOf(0f to 0f, 1f to 1f, 6f to 6f, 0f to 4f, 4f to 0f)) assertEquals(FoldGravity.FLAT, foldGravity(x, y))
        assertEquals(FoldGravity.UNKNOWN, foldGravity(Float.NaN, 9.8f))
        // dumpsys 2026-10-20: the inner's everyday book pose is ROTATION_270 where the cover shows ROTATION_0.
        assertEquals(Surface.ROTATION_270, innerRotationForCoverRotation(Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_0, innerRotationForCoverRotation(Surface.ROTATION_90))
        assertEquals(Surface.ROTATION_90, innerRotationForCoverRotation(Surface.ROTATION_180))
        assertEquals(Surface.ROTATION_180, innerRotationForCoverRotation(Surface.ROTATION_270))
        // Round 15: the rotation an unfold arrives at (none without a pose).
        assertEquals(Surface.ROTATION_270, innerUnfoldRotation(FoldGravity.UPRIGHT))
        assertEquals(Surface.ROTATION_0, innerUnfoldRotation(FoldGravity.TURNED_CCW))
        assertEquals(Surface.ROTATION_90, innerUnfoldRotation(FoldGravity.UPSIDE_DOWN))
        assertEquals(Surface.ROTATION_180, innerUnfoldRotation(FoldGravity.TURNED_CW))
        assertNull(innerUnfoldRotation(FoldGravity.FLAT)); assertNull(innerUnfoldRotation(FoldGravity.UNKNOWN))
        assertEquals(land, innerOrientationForRotation(Surface.ROTATION_270)); assertEquals(port, innerOrientationForRotation(Surface.ROTATION_180))
        // What each request must produce before the veil may lift.
        assertEquals(land, expectedOrientation(reverseLandscape)); assertEquals(land, expectedOrientation(sensorLandscape))
        assertEquals(port, expectedOrientation(reversePortrait)); assertNull(expectedOrientation(free))
        assertTrue(rotationMatches(reverseLandscape, Surface.ROTATION_270)); assertFalse(rotationMatches(reverseLandscape, Surface.ROTATION_90))
        assertTrue(rotationMatches(sensorLandscape, Surface.ROTATION_90)); assertFalse(rotationMatches(sensorLandscape, Surface.ROTATION_0))
        assertTrue(rotationMatches(free, Surface.ROTATION_180))
        assertEquals("LOCKED", orientationRequestName(held))
    }

    @Test fun `closing from a landscape inner veils before the swap and reveals portrait on the cover`() {
        val sim = Sim()
        val veiledAt = mutableListOf<Float>()
        for ((i, angle) in ownerClose.withIndex()) {
            val step = sim.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = if (i == 0) 0L else 21L)
            // Round 15: free while open, held from 165° down — before the lifted half can turn it.
            assertEquals("inner at $angle", if (angle < INNER_HOLD_BELOW_DEGREES) held else free, step.state.request)
            if (step.state.veil) veiledAt += angle
        }
        // Up at 19° (≤ 20°), i.e. two samples (~42 ms) before the 6° reading; 12° would have been 5 ms before the swap.
        assertEquals(listOf(19f, 13f, 6f), veiledAt)
        assertEquals(FoldHandoffPhase.CLOSING_VEILED, sim.state.phase)
        // The cover lights with the inner's rotation 5 ms later: portrait is requested at once, the veil stays.
        var step = sim.step(FoldPanel.COVER, 2f, orientation = land, rotation = Surface.ROTATION_270, advance = 5L)
        assertEquals(FoldHandoffPhase.COVER_SETTLING, step.state.phase)
        assertEquals(portrait, step.state.request); assertTrue(step.state.veil); assertTrue(step.changed)
        // Portrait configuration, but nothing drawn in it yet.
        step = sim.step(FoldPanel.COVER, 2f, orientation = port, rotation = Surface.ROTATION_0, advance = 120L)
        assertTrue(step.state.veil); assertFalse(step.reveal)
        step = sim.step(FoldPanel.COVER, 1f, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 16L)
        assertEquals(FoldHandoffPhase.IDLE, step.state.phase)
        assertTrue(step.reveal); assertEquals(portrait, step.state.request); assertEquals("portrait drawn", step.state.reason)
        assertEquals(1, sim.reveals.size)
        // Closed and still: nothing more happens.
        step = sim.step(FoldPanel.COVER, 1f, orientation = port, frameDrawn = true, advance = 500L)
        assertFalse(step.changed); assertFalse(step.state.veil)
    }

    @Test fun `closing from a portrait inner still waits for the cover's first portrait frame`() {
        val sim = Sim()
        for (angle in listOf(40f, 30f, 22f, 16f)) sim.step(FoldPanel.INNER, angle, orientation = port, rotation = Surface.ROTATION_0, advance = 21L)
        assertEquals(FoldHandoffPhase.CLOSING_VEILED, sim.state.phase)
        // The cover inherits rotation 0 = already portrait: only the drawn frame is awaited.
        var step = sim.step(FoldPanel.COVER, 3f, orientation = port, rotation = Surface.ROTATION_0, advance = 30L)
        assertEquals(FoldHandoffPhase.COVER_SETTLING, step.state.phase); assertEquals(portrait, step.state.request)
        step = sim.step(FoldPanel.COVER, 3f, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 16L)
        assertTrue(step.reveal); assertEquals(FoldHandoffPhase.IDLE, step.state.phase)
    }

    @Test fun `opening on a table lights the inner held in the rotation it lit with`() {
        val sim = Sim()
        val veiledAt = mutableListOf<Float>()
        for (angle in listOf(3f, 20f, 40f, 55f, 68f, 74f, 77f, 84f, 90f)) {
            val step = sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 40L)
            assertEquals("the cover never turns ($angle)", portrait, step.state.request)
            if (step.state.veil) veiledAt += angle
        }
        assertEquals(listOf(77f, 84f, 90f), veiledAt)
        // The inner lights with the cover's portrait (inner ROTATION_0) and a stale portrait config: not held yet.
        var step = sim.step(FoldPanel.INNER, 93f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_0, advance = 10L)
        assertEquals(FoldHandoffPhase.INNER_SETTLING, step.state.phase); assertEquals(free, step.state.request)
        assertEquals("inner lit, no pose", step.state.reason)
        // Its configuration catches up: held at once (before the swing's noise can turn it), lifted on the drawn frame.
        step = sim.step(FoldPanel.INNER, 98f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, advance = 16L)
        assertTrue(step.state.veil); assertEquals(held, step.state.request)
        step = sim.step(FoldPanel.INNER, 104f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 16L)
        assertTrue(step.reveal); assertEquals(held, step.state.request); assertEquals("settled", step.state.reason)
        // Lying open on the table: stays held, whatever the system's sensor proposed during the swing.
        for (angle in listOf(140f, 170f, 178f, 179f)) step = sim.step(FoldPanel.INNER, angle, gravity = FoldGravity.FLAT,
            orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 200L)
        step = sim.step(FoldPanel.INNER, 179f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0,
            frameDrawn = true, advance = 60_000L)
        assertTrue(step.state.hold); assertEquals(held, step.state.request); assertFalse(step.state.veil)

        // A panel that letterboxes the hold: released at once, remembered by the caller (free requests from then on).
        val boxed = Sim()
        for (angle in listOf(60f, 70f, 80f, 90f)) boxed.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 40L)
        boxed.step(FoldPanel.INNER, 93f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, advance = 10L)
        assertEquals(held, boxed.state.request)
        step = boxed.step(FoldPanel.INNER, 95f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, letterboxed = true, advance = 30L)
        assertEquals(free, step.state.request); assertTrue(step.state.letterboxed); assertEquals("letterboxed", step.state.reason)
        step = boxed.step(FoldPanel.INNER, 99f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 30L)
        assertTrue(step.reveal); assertFalse(step.state.hold); assertEquals(free, step.state.request)
        // Next unfold on that panel: no hold.
        val next = Sim()
        for (angle in listOf(60f, 70f, 80f, 90f)) next.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, ignored = true, advance = 40L)
        step = next.step(FoldPanel.INNER, 93f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, ignored = true, advance = 10L)
        assertEquals(free, step.state.request)
    }

    @Test fun `opening held upright waits for the sensor's book pose and holds it once reached`() {
        val sim = Sim()
        for (angle in listOf(60f, 72f, 80f, 88f)) sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        assertEquals(FoldHandoffPhase.OPENING_VEILED, sim.state.phase)
        // The cover stays portrait; a held-up pose is turned to by the system itself.
        assertEquals(portrait, sim.state.request)
        var step = sim.step(FoldPanel.INNER, 93f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, advance = 10L)
        assertEquals(free, step.state.request); assertEquals("inner lit", step.state.reason)
        assertEquals(Surface.ROTATION_270, step.state.target)
        // Landscape on the wrong side is not the pose: keep waiting, still free.
        step = sim.step(FoldPanel.INNER, 96f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_90, frameDrawn = true, advance = 60L)
        assertTrue(step.state.veil); assertFalse(step.reveal); assertEquals(free, step.state.request)
        // The pose: held at once (the rest of the swing must not turn it), lifted on its first frame.
        step = sim.step(FoldPanel.INNER, 99f, gravity = FoldGravity.TURNED_CW, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = false, advance = 60L)
        assertTrue(step.state.veil); assertEquals(held, step.state.request); assertEquals("inner lit, held", step.state.reason)
        step = sim.step(FoldPanel.INNER, 101f, gravity = FoldGravity.TURNED_CW, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 16L)
        assertTrue(step.reveal); assertEquals(held, step.state.request); assertEquals(FoldHandoffPhase.IDLE, step.state.phase)

        // Opened flat and held up for 0.6 s: handed back to the system (which follows the user's switch).
        for (angle in listOf(130f, 160f, 172f, 178f)) step = sim.step(FoldPanel.INNER, angle, gravity = FoldGravity.UNKNOWN,
            orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 40L)
        assertEquals(held, step.state.request)
        step = sim.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 30L)
        assertEquals(held, step.state.request)
        step = sim.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true,
            advance = INNER_RELEASE_POSE_MILLIS - 1)
        assertEquals(held, step.state.request)
        step = sim.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 1L)
        assertEquals(free, step.state.request); assertEquals("released", step.state.reason); assertTrue(step.changed)

        // A panel that letterboxed a hold before is never held: free, veil until the sensor reaches the pose.
        val known = Sim()
        for (angle in listOf(70f, 80f, 90f)) known.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, ignored = true, advance = 40L)
        step = known.step(FoldPanel.INNER, 93f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, ignored = true, advance = 10L)
        assertEquals(free, step.state.request); assertTrue(step.state.veil)
        step = known.step(FoldPanel.INNER, 95f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, ignored = true, advance = 30L)
        assertTrue(step.state.veil) // portrait is not the upright pose
        step = known.step(FoldPanel.INNER, 99f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, ignored = true, advance = 300L)
        assertTrue(step.reveal); assertEquals(free, step.state.request)
    }

    @Test fun `a reversal before the swap drops the veil on either panel`() {
        val inner = Sim()
        for (angle in listOf(30f, 24f, 18f)) inner.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertEquals(FoldHandoffPhase.CLOSING_VEILED, inner.state.phase)
        var step = inner.step(FoldPanel.INNER, 22f, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertTrue(step.state.veil) // within the hysteresis band
        step = inner.step(FoldPanel.INNER, 26f, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertFalse(step.state.veil); assertEquals("reversed", step.state.reason); assertEquals(held, step.state.request)
        // Closing again re-arms: the swap is still covered.
        for (angle in listOf(21f, 17f)) step = inner.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertTrue(step.state.veil)

        val cover = Sim()
        for (angle in listOf(70f, 78f)) cover.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        assertEquals(FoldHandoffPhase.OPENING_VEILED, cover.state.phase)
        step = cover.step(FoldPanel.COVER, 65f, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        assertTrue(step.state.veil)
        step = cover.step(FoldPanel.COVER, 58f, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        assertFalse(step.state.veil); assertEquals("reversed", step.state.reason); assertEquals(portrait, step.state.request)

    }

    @Test fun `timeouts lift the veil and continued motion toward the swap renews them`() {
        // No swap: 1.5 s after the last closing motion.
        val inner = Sim()
        for (angle in listOf(30f, 18f)) inner.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        var step = inner.step(FoldPanel.INNER, 18f, orientation = land, rotation = Surface.ROTATION_270, advance = 1499L)
        assertTrue(step.state.veil)
        step = inner.step(FoldPanel.INNER, 18f, orientation = land, rotation = Surface.ROTATION_270, advance = 1L)
        assertFalse(step.state.veil); assertEquals("timeout", step.state.reason); assertEquals(held, step.state.request)

        val slow = Sim()
        for (angle in listOf(30f, 18f)) slow.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        step = slow.step(FoldPanel.INNER, 15f, orientation = land, rotation = Surface.ROTATION_270, advance = 1000L)
        step = slow.step(FoldPanel.INNER, 12f, orientation = land, rotation = Surface.ROTATION_270, advance = 1000L)
        step = slow.step(FoldPanel.INNER, 12f, orientation = land, rotation = Surface.ROTATION_270, advance = 1400L)
        assertTrue(step.state.veil) // a slow fold keeps its veil (last motion 1.4 s ago)
        step = slow.step(FoldPanel.INNER, 12f, orientation = land, rotation = Surface.ROTATION_270, advance = 100L)
        assertFalse(step.state.veil)

        // Cover lit but never portrait (rotation locked by the system): reveal anyway.
        val cover = Sim()
        for (angle in listOf(30f, 18f, 9f)) cover.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        cover.step(FoldPanel.COVER, 2f, orientation = land, rotation = Surface.ROTATION_270, advance = 5L)
        step = cover.step(FoldPanel.COVER, 2f, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 1499L)
        assertTrue(step.state.veil); assertEquals(portrait, step.state.request)
        step = cover.step(FoldPanel.COVER, 2f, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 1L)
        assertTrue(step.reveal); assertEquals("timeout", step.state.reason); assertEquals(portrait, step.state.request)

        // Inner never reaches the pose: lit as it is after the short wait, and held there.
        val open = Sim()
        for (angle in listOf(70f, 80f, 90f)) open.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        open.step(FoldPanel.INNER, 93f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, advance = 10L)
        step = open.step(FoldPanel.INNER, 120f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true,
            advance = INNER_ROTATION_WAIT_MILLIS)
        assertTrue(step.reveal); assertEquals(held, step.state.request); assertEquals("timeout", step.state.reason)
        // Opening past the veil angle never raises it again while the device only opens further.
        step = open.step(FoldPanel.INNER, 150f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 200L)
        assertFalse(step.state.veil); assertFalse(step.changed)
    }

    @Test fun `standby entry takes priority over a fold veil and its request`() {
        val closing = Sim()
        for (angle in listOf(30f, 18f)) closing.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertTrue(closing.state.veil)
        var step = closing.step(FoldPanel.INNER, 16f, orientation = land, rotation = Surface.ROTATION_270, standby = true, advance = 21L)
        assertFalse(step.state.veil); assertEquals("standby", step.state.reason); assertEquals(free, step.state.request)
        // While StandBy owns the window no fold veil can rise, whatever the hinge does.
        for (angle in listOf(14f, 10f, 6f)) {
            step = closing.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, standby = true, advance = 21L)
            assertFalse(step.state.veil); assertFalse(step.changed)
        }
        step = closing.step(FoldPanel.COVER, 3f, orientation = land, rotation = Surface.ROTATION_270, standby = true, advance = 21L)
        assertFalse(step.state.veil); assertEquals(portrait, step.state.request)

        val settling = Sim()
        for (angle in listOf(70f, 80f, 90f)) settling.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 40L)
        settling.step(FoldPanel.INNER, 93f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, advance = 10L)
        assertEquals(held, settling.state.request)
        step = settling.step(FoldPanel.INNER, 95f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, standby = true, advance = 10L)
        assertEquals(free, step.state.request); assertFalse(step.state.veil); assertEquals(FoldHandoffPhase.IDLE, step.state.phase)
        assertFalse(step.state.hold)
        // StandBy's own surfaces stay in charge of the window.
        assertEquals(LauncherSurface.ENTERING, launcherSurface(standby = true, requested = false, ready = false, settling = false, foldVeil = true))
        assertEquals(LauncherSurface.EXITING, launcherSurface(standby = false, requested = false, ready = false, settling = true, foldVeil = true))
    }

    @Test fun `the log line carries every field the phone test needs`() {
        val state = FoldHandoffState(phase = FoldHandoffPhase.COVER_SETTLING, since = 0L, request = portrait,
            direction = FoldDirection.CLOSING, lastAngle = 2f, reason = "cover lit")
        val line = foldLogLine(state, FoldHandoffInput(FoldPanel.COVER, 2f, FoldGravity.UPRIGHT, land, Surface.ROTATION_270,
            letterboxed = false, frameDrawn = false, standbyActive = false, now = 5L))
        for (field in listOf("phase=COVER_SETTLING", "panel=COVER", "angle=2.0", "direction=CLOSING", "gravity=UPRIGHT",
            "request=PORTRAIT", "veil=true", "config=LANDSCAPE", "rotation=3", "letterboxed=false", "frameDrawn=false",
            "hold=false", "target=null", "locked=false", "reason=cover lit")) assertTrue(field, line.contains(field))
        assertEquals("REVERSE_LANDSCAPE", orientationRequestName(reverseLandscape))
        assertEquals("FULL_USER", orientationRequestName(free))
    }

    @Test fun `a step at the same angle after a timeout does not raise the veil again`() {
        // emulator-5564 2026-10-21 14:18: hinge parked at 3° after a closing sweep; a display event re-stepped the policy
        // and the sticky CLOSING direction re-veiled it 0.5 s after the timeout, in a 1.5 s loop.
        val sim = Sim()
        for (angle in listOf(28f, 19f, 10f, 3f)) sim.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 50L)
        assertTrue(sim.state.veil)
        var step = sim.step(FoldPanel.INNER, 3f, orientation = land, rotation = Surface.ROTATION_270, advance = 1500L)
        assertFalse(step.state.veil); assertEquals("timeout", step.state.reason)
        for (i in 0 until 5) {
            step = sim.step(FoldPanel.INNER, 3f, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = i % 2 == 0, advance = 300L)
            assertFalse(step.state.veil); assertFalse(step.changed)
        }
        // Only a sample that moved toward the swap again counts.
        assertTrue(sim.step(FoldPanel.INNER, 2f, orientation = land, rotation = Surface.ROTATION_270, advance = 50L).state.veil)
        val cover = Sim()
        for (angle in listOf(60f, 80f, 92f)) cover.step(FoldPanel.COVER, angle, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 50L)
        cover.step(FoldPanel.COVER, 92f, gravity = FoldGravity.UPRIGHT, orientation = port, advance = 1500L)
        assertFalse(cover.state.veil)
        assertFalse(cover.step(FoldPanel.COVER, 92f, gravity = FoldGravity.UPRIGHT, orientation = port, frameDrawn = true, advance = 200L).state.veil)
    }

    @Test fun `an inner that letterboxed a hold is only ever asked for a free request`() {
        for (gravity in FoldGravity.values()) {
            val sim = Sim()
            for (angle in listOf(70f, 80f, 90f)) sim.step(FoldPanel.COVER, angle, gravity = gravity, orientation = port, ignored = true, advance = 40L)
            assertEquals(portrait, sim.state.request)
            var step = sim.step(FoldPanel.INNER, 123f, gravity = gravity, orientation = port, rotation = Surface.ROTATION_0, ignored = true, advance = 10L)
            assertEquals(FoldHandoffPhase.INNER_SETTLING, step.state.phase); assertEquals(free, step.state.request)
            step = sim.step(FoldPanel.INNER, 176f, gravity = FoldGravity.TURNED_CW, orientation = port, rotation = Surface.ROTATION_270, ignored = true, advance = 300L)
            assertEquals(free, step.state.request)
            while (step.state.veil && sim.now < 5_000L) step = sim.step(FoldPanel.INNER, 177f, gravity = FoldGravity.UPRIGHT,
                orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, ignored = true, advance = 50L)
            assertFalse(step.state.veil); assertEquals(free, step.state.request)
            // Nor while closing.
            for (angle in listOf(150f, 90f, 40f)) assertEquals(free, sim.step(FoldPanel.INNER, angle, orientation = land,
                rotation = Surface.ROTATION_270, ignored = true, advance = 30L).state.request)
        }
    }

    @Test fun `invalid or missing angles never move the policy`() {
        val sim = Sim()
        for (angle in listOf(null, Float.NaN, -3f, 400f)) {
            val step = sim.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
            assertEquals(FoldHandoffPhase.IDLE, step.state.phase); assertEquals(free, step.state.request)
        }
        // The first valid sample has no direction yet: a lone 15° reading is not a closing fold.
        val step = sim.step(FoldPanel.INNER, 15f, orientation = land, rotation = Surface.ROTATION_270, advance = 21L)
        assertEquals(FoldHandoffPhase.IDLE, step.state.phase)
        assertTrue(sim.step(FoldPanel.INNER, 12f, orientation = land, rotation = Surface.ROTATION_270, advance = 21L).state.veil)
    }

    // ---- Round 13: replays of the phone's unfolds (log 2026-10-22 03:51–03:53, Core.31.1) -------------------

    /** Cover samples up to the veil, then the inner lights with the cover's portrait (inner ROTATION_0). */
    private fun Sim.openTo(before: FoldGravity, swap: FoldGravity, locked: Boolean = false): FoldHandoffStep {
        for (angle in listOf(40f, 60f, 70f)) step(FoldPanel.COVER, angle, gravity = before, orientation = port, advance = 30L)
        step(FoldPanel.COVER, 78f, gravity = before, orientation = port, frameDrawn = true, advance = 30L)
        step(FoldPanel.COVER, 95f, gravity = swap, orientation = port, frameDrawn = true, advance = 60L)
        return step(FoldPanel.INNER, 107f, gravity = swap, orientation = port, rotation = Surface.ROTATION_0,
            advance = 100L, locked = locked)
    }

    @Test fun `03-51-57 replay - a mid-swing TURNED_CW sample no longer lifts the veil in portrait`() {
        val sim = Sim()
        assertEquals(FoldHandoffPhase.INNER_SETTLING, sim.openTo(FoldGravity.UPRIGHT, FoldGravity.TURNED_CW).state.phase)
        assertEquals(FoldGravity.UPRIGHT, sim.state.openingGravity)
        // First frame on the inner, still portrait: the system has not turned it to the book pose yet.
        var step = sim.step(FoldPanel.INNER, 116f, gravity = FoldGravity.TURNED_CW, orientation = port, frameDrawn = true, advance = 80L)
        assertTrue("veil must wait for the system's turn", step.state.veil)
        // The system turns it (+300 ms): held there, lifts on the first landscape frame.
        sim.step(FoldPanel.INNER, 150f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, advance = 220L)
        step = sim.step(FoldPanel.INNER, 160f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270,
            frameDrawn = true, advance = 16L)
        assertFalse(step.state.veil); assertEquals("settled", step.state.reason); assertEquals(held, step.state.request)
    }

    @Test fun `the inner never stays dark longer than the short wait when the system does not turn`() {
        val sim = Sim()
        sim.openTo(FoldGravity.UPRIGHT, FoldGravity.UPRIGHT)
        val lit = sim.now
        sim.step(FoldPanel.INNER, 120f, orientation = port, frameDrawn = true, advance = 50L)
        var step: FoldHandoffStep
        do { step = sim.step(FoldPanel.INNER, 170f, orientation = port, frameDrawn = true, advance = 50L) }
        while (step.state.veil && sim.now - lit < 5_000L)
        assertFalse(step.state.veil)
        assertTrue("lit after ${sim.now - lit} ms", sim.now - lit <= INNER_ROTATION_WAIT_MILLIS + 50L)
        assertEquals("timeout", step.state.reason); assertEquals(held, step.state.request)
    }

    @Test fun `flat on a table, turned in the hand or rotation locked - the inner lights on its first frame`() {
        // openTo lights the inner with the cover's portrait (rotation 0); without a pose that is where it stays.
        for ((before, locked) in listOf(FoldGravity.FLAT to false, FoldGravity.UNKNOWN to false,
                FoldGravity.TURNED_CCW to false, FoldGravity.UPRIGHT to true)) {
            val sim = Sim()
            sim.openTo(before, FoldGravity.TURNED_CW, locked)
            val step = sim.step(FoldPanel.INNER, 120f, orientation = port, frameDrawn = true, advance = 16L, locked = locked)
            assertFalse("$before locked=$locked", step.state.veil)
            assertEquals("$before locked=$locked", if (locked) free else held, step.state.request)
        }
        // Held turned clockwise the pose is inner 180: the system's sensor turns it, under the veil.
        val cw = Sim()
        cw.openTo(FoldGravity.TURNED_CW, FoldGravity.TURNED_CW)
        assertTrue(cw.step(FoldPanel.INNER, 120f, orientation = port, frameDrawn = true, advance = 16L).state.veil)
        val step = cw.step(FoldPanel.INNER, 130f, orientation = port, rotation = Surface.ROTATION_180, frameDrawn = true, advance = 90L)
        assertFalse(step.state.veil); assertEquals(held, step.state.request)
    }

    @Test fun `every unfold ends held unless the inner's rotation is locked`() {
        for (locked in listOf(false, true)) for (before in FoldGravity.values()) for (swap in FoldGravity.values()) {
            val sim = Sim()
            sim.openTo(before, swap, locked)
            val step = sim.step(FoldPanel.INNER, 170f, orientation = land, rotation = Surface.ROTATION_270,
                frameDrawn = true, advance = 900L, locked = locked)
            assertFalse(step.state.veil)
            assertEquals("$before/$swap locked=$locked", if (locked) free else held, step.state.request)
        }
    }

    // ---- Round 15: the phone's rotations of 2026-10-22 10:29–10:30 (Core.31.4, inner auto-rotate on, cover locked) ----

    @Test fun `10-29-53 replay - an unfold flat on the table is never turned by the swing`() {
        // Log: opening=FLAT at 75°, inner lit 0 at 101°, veil lifted at 114°, then "inner turned after lift +566ms rotation=2".
        val sim = Sim()
        for (angle in listOf(40f, 60f, 70f, 75f)) sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, frameDrawn = true, advance = 30L)
        sim.step(FoldPanel.INNER, 101f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, advance = 215L)
        assertEquals(held, sim.state.request)
        var step = sim.step(FoldPanel.INNER, 114f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 209L)
        assertTrue(step.reveal)
        // +566 ms and on: still held, open flat with no pose — the system's stale proposal never gets a say.
        for (angle in listOf(150f, 175f, 178f)) {
            step = sim.step(FoldPanel.INNER, angle, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0,
                frameDrawn = true, advance = 300L)
            assertEquals(held, step.state.request); assertFalse(step.changed)
        }
    }

    @Test fun `10-30-05 replay - closing on a table holds the rotation before the lifted half turns it`() {
        // Log: open at rotation 270 (free), the system turned it to 180 at 10:30:05.147, the veil rose at 20° at .505.
        val sim = Sim()
        var step = sim.step(FoldPanel.INNER, 179f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true)
        assertEquals(free, step.state.request)
        val heldFrom = mutableListOf<Float>()
        for (angle in listOf(178f, 174f, 170f, 166f, 164f, 158f, 140f, 110f, 80f, 50f, 30f, 22f)) {
            step = sim.step(FoldPanel.INNER, angle, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 30L)
            if (step.state.request == held) heldFrom += angle
        }
        assertEquals(164f, heldFrom.first()); assertEquals(8, heldFrom.size)
        // 20°: the veil keeps the hold until the cover lights; the cover asks for portrait.
        step = sim.step(FoldPanel.INNER, 19f, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 30L)
        assertTrue(step.state.veil); assertEquals(held, step.state.request)
        step = sim.step(FoldPanel.COVER, 6f, orientation = land, rotation = Surface.ROTATION_270, advance = 30L)
        assertEquals(portrait, step.state.request); assertFalse(step.state.hold)
        // Half closed then opened again on the table: still held while it is lying flat.
        val back = Sim()
        for (angle in listOf(179f, 150f, 120f, 150f, 178f)) step = back.step(FoldPanel.INNER, angle, gravity = FoldGravity.FLAT,
            orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 200L)
        step = back.step(FoldPanel.INNER, 178f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 5_000L)
        assertEquals(held, step.state.request)
        // Picked up: handed back after 0.6 s; set down and folded a little: held again.
        back.step(FoldPanel.INNER, 178f, gravity = FoldGravity.TURNED_CCW, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 50L)
        step = back.step(FoldPanel.INNER, 178f, gravity = FoldGravity.TURNED_CCW, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true,
            advance = INNER_RELEASE_POSE_MILLIS)
        assertEquals(free, step.state.request)
        // A pose broken before 0.6 s restarts the count.
        val jitter = Sim()
        jitter.step(FoldPanel.INNER, 150f, orientation = land, rotation = Surface.ROTATION_270, advance = 10L)
        jitter.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, advance = 10L)
        jitter.step(FoldPanel.INNER, 178f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_270, advance = 400L)
        step = jitter.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, advance = 400L)
        assertEquals(held, step.state.request)
        step = jitter.step(FoldPanel.INNER, 178f, gravity = FoldGravity.UPRIGHT, orientation = land, rotation = Surface.ROTATION_270, advance = 600L)
        assertEquals(free, step.state.request)
    }

    @Test fun `a locked inner is never held and waits briefly for its locked rotation`() {
        val sim = Sim()
        for (angle in listOf(40f, 60f, 70f, 78f)) {
            val step = sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 30L, locked = true)
            assertEquals(portrait, step.state.request)
        }
        var step = sim.step(FoldPanel.INNER, 105f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0,
            advance = 120L, locked = true, userRotation = Surface.ROTATION_270)
        assertEquals(free, step.state.request); assertEquals("inner lit, rotation locked", step.state.reason)
        step = sim.step(FoldPanel.INNER, 110f, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 20L,
            locked = true, userRotation = Surface.ROTATION_270)
        assertTrue("waits for the locked rotation", step.state.veil)
        step = sim.step(FoldPanel.INNER, 120f, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 60L,
            locked = true, userRotation = Surface.ROTATION_270)
        assertTrue(step.reveal); assertEquals(free, step.state.request)
        for (angle in listOf(150f, 90f, 30f)) assertEquals(free, sim.step(FoldPanel.INNER, angle, orientation = land,
            rotation = Surface.ROTATION_270, advance = 40L, locked = true).state.request)
        // Locked rotation unknown: lights on its first frame.
        val unknown = Sim()
        for (angle in listOf(60f, 70f, 78f)) unknown.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 30L, locked = true)
        unknown.step(FoldPanel.INNER, 105f, orientation = port, rotation = Surface.ROTATION_0, advance = 120L, locked = true)
        assertTrue(unknown.step(FoldPanel.INNER, 110f, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 16L, locked = true).reveal)
        // Switched on while held: released at once.
        val toggled = Sim()
        toggled.step(FoldPanel.INNER, 150f, orientation = land, rotation = Surface.ROTATION_270)
        assertEquals(held, toggled.step(FoldPanel.INNER, 140f, orientation = land, rotation = Surface.ROTATION_270, advance = 20L).state.request)
        assertEquals(free, toggled.step(FoldPanel.INNER, 140f, orientation = land, rotation = Surface.ROTATION_270, advance = 20L, locked = true).state.request)
    }

    @Test fun `a hold that letterboxes Home is released at once and reported`() {
        val sim = Sim()
        sim.step(FoldPanel.INNER, 179f, orientation = land, rotation = Surface.ROTATION_270)
        assertEquals(held, sim.step(FoldPanel.INNER, 150f, orientation = land, rotation = Surface.ROTATION_270, advance = 30L).state.request)
        val step = sim.step(FoldPanel.INNER, 148f, orientation = port, rotation = Surface.ROTATION_270, letterboxed = true, advance = 30L)
        assertEquals(free, step.state.request); assertTrue(step.state.letterboxed); assertEquals("hold letterboxed", step.state.reason)
        assertTrue(step.changed)
        // From then on the caller passes ignored = true: never held again.
        for (angle in listOf(140f, 100f, 60f)) assertEquals(free, sim.step(FoldPanel.INNER, angle, orientation = land,
            rotation = Surface.ROTATION_270, ignored = true, advance = 30L).state.request)
    }

    @Test fun `no hinge reading means no hold`() {
        val sim = Sim()
        for (i in 0 until 5) assertEquals(free, sim.step(FoldPanel.INNER, null, gravity = FoldGravity.FLAT, orientation = land,
            rotation = Surface.ROTATION_270, advance = 100L).state.request)
    }

    // ---- Round 15b: owner video 11:57 — a flat unfold arrived in portrait. With the respect-orientation override the
    // inner turns to the book pose under the veil. ----

    @Test fun `11-57-14 replay - honoured, a flat unfold asks for the book pose under the veil and holds it`() {
        val sim = Sim()
        for (angle in listOf(40f, 60f, 70f, 79f)) sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, frameDrawn = true, advance = 30L)
        assertEquals(portrait, sim.state.request) // the cover itself never turns
        var step = sim.step(FoldPanel.INNER, 103f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_0,
            advance = 256L, honoured = true)
        assertEquals(reverseLandscape, step.state.request); assertTrue(step.state.veil)
        assertEquals("inner lit, no pose, book pose requested", step.state.reason); assertEquals(Surface.ROTATION_270, step.state.target)
        // Turned, configuration not there yet: keep asking, no hold on a stale configuration.
        step = sim.step(FoldPanel.INNER, 108f, gravity = FoldGravity.FLAT, orientation = port, rotation = Surface.ROTATION_270, advance = 60L, honoured = true)
        assertEquals(reverseLandscape, step.state.request); assertTrue(step.state.veil)
        step = sim.step(FoldPanel.INNER, 112f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_270, advance = 20L, honoured = true)
        assertEquals(held, step.state.request); assertTrue(step.state.veil)
        step = sim.step(FoldPanel.INNER, 116f, gravity = FoldGravity.FLAT, orientation = land, rotation = Surface.ROTATION_270, frameDrawn = true, advance = 16L, honoured = true)
        assertTrue(step.reveal); assertEquals(held, step.state.request); assertEquals("settled", step.state.reason)
        for (angle in listOf(150f, 178f)) assertEquals(held, sim.step(FoldPanel.INNER, angle, gravity = FoldGravity.FLAT, orientation = land,
            rotation = Surface.ROTATION_270, frameDrawn = true, advance = 400L, honoured = true).state.request)
    }

    @Test fun `honoured, a held-up unfold asks for its pose directly, a locked inner is never asked`() {
        val cw = Sim()
        for (angle in listOf(60f, 70f, 78f)) cw.step(FoldPanel.COVER, angle, gravity = FoldGravity.TURNED_CW, orientation = port, advance = 30L)
        var step = cw.step(FoldPanel.INNER, 105f, gravity = FoldGravity.UPRIGHT, orientation = port, rotation = Surface.ROTATION_0, advance = 100L, honoured = true)
        assertEquals(reversePortrait, step.state.request); assertEquals("inner lit, pose requested", step.state.reason)
        step = cw.step(FoldPanel.INNER, 110f, orientation = port, rotation = Surface.ROTATION_180, frameDrawn = true, advance = 80L, honoured = true)
        assertTrue(step.reveal); assertEquals(held, step.state.request)

        val locked = Sim()
        for (angle in listOf(60f, 70f, 78f)) locked.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 30L, locked = true)
        step = locked.step(FoldPanel.INNER, 105f, orientation = port, rotation = Surface.ROTATION_0, advance = 100L, locked = true,
            userRotation = Surface.ROTATION_270, honoured = true)
        assertEquals(free, step.state.request)
    }

    @Test fun `honoured but letterboxed after all - released at once, reported, and lit as it is`() {
        val sim = Sim()
        for (angle in listOf(60f, 70f, 78f)) sim.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 30L)
        sim.step(FoldPanel.INNER, 105f, orientation = port, rotation = Surface.ROTATION_0, advance = 100L, honoured = true)
        assertEquals(reverseLandscape, sim.state.request)
        var step = sim.step(FoldPanel.INNER, 110f, orientation = land, rotation = Surface.ROTATION_0, letterboxed = true, advance = 40L, honoured = true)
        assertEquals(free, step.state.request); assertTrue(step.state.letterboxed); assertEquals("letterboxed", step.state.reason)
        step = sim.step(FoldPanel.INNER, 120f, orientation = port, rotation = Surface.ROTATION_0, frameDrawn = true, advance = 30L, ignored = true, honoured = true)
        assertTrue(step.reveal); assertEquals(free, step.state.request)
        // Not honoured: Core.32 behaviour, nothing fixed is ever requested.
        val plain = Sim()
        for (angle in listOf(60f, 70f, 78f)) plain.step(FoldPanel.COVER, angle, gravity = FoldGravity.FLAT, orientation = port, advance = 30L)
        assertEquals(held, plain.step(FoldPanel.INNER, 105f, orientation = port, rotation = Surface.ROTATION_0, advance = 100L).state.request)
    }

    @Test fun `no request but LOCKED or free ever reaches the inner unless honoured`() {
        val allowed = setOf(held, free)
        for (before in FoldGravity.values()) for (swap in FoldGravity.values()) for (r in 0..3) {
            val sim = Sim()
            for (angle in listOf(60f, 70f, 78f)) sim.step(FoldPanel.COVER, angle, gravity = before, orientation = port, advance = 30L)
            for ((i, angle) in listOf(100f, 120f, 150f, 178f, 120f, 40f, 15f).withIndex()) {
                val step = sim.step(FoldPanel.INNER, angle, gravity = swap, orientation = innerOrientationForRotation(r), rotation = r,
                    frameDrawn = i > 0, advance = 80L)
                assertTrue("$before/$swap/$r at $angle: ${orientationRequestName(step.state.request)}", step.state.request in allowed)
            }
        }
    }
}
