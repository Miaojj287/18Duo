package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import android.content.pm.ActivityInfo

class StandbyPolicyTest {
    @Test fun `night palette choice overrides ambient detection only when selected`() {
        assertEquals(StandbyNightMode.AUTO, StandbyNightMode.fromKey(null))
        assertEquals(StandbyNightMode.AUTO, StandbyNightMode.fromKey("invalid"))
        assertEquals(StandbyNightMode.OFF, StandbyNightMode.fromKey("OFF"))
        assertTrue(standbyNightEnabled(StandbyNightMode.AUTO, true))
        assertFalse(standbyNightEnabled(StandbyNightMode.AUTO, false))
        assertFalse(standbyNightEnabled(StandbyNightMode.OFF, true))
        assertTrue(standbyNightEnabled(StandbyNightMode.ON, false))
    }
    @Test fun `default locked scene cannot seize unlocked home while both and manual remain explicit`() {
        assertTrue(standbySceneAllows(StandbyScene.LOCKED_ONLY, true))
        assertFalse(standbySceneAllows(StandbyScene.LOCKED_ONLY, false))
        assertTrue(standbySceneAllows(StandbyScene.LOCKED_AND_HOME, false))
        assertTrue(standbySceneAllows(StandbyScene.LOCKED_AND_HOME, true))
        assertFalse(standbySceneAllows(StandbyScene.MANUAL_ONLY, true))
        assertFalse(standbySceneAllows(StandbyScene.MANUAL_ONLY, false))
    }
    @Test fun `lock gate settles exits on unlock and dismissal survives same pose`() {
        val gate = LockedStandbyGate()
        assertFalse(gate.update(false, true, true, true, 0))
        assertFalse(gate.update(true, true, true, true, 100))
        assertFalse(gate.update(true, true, true, true, 799))
        assertTrue(gate.update(true, true, true, true, 800))
        gate.dismiss()
        assertFalse(gate.update(true, true, true, true, 5000))
        assertFalse(gate.update(true, true, true, false, 6000))
        assertFalse(gate.update(true, true, true, true, 7000))
        assertFalse(gate.update(true, true, false, false, 8000))
        assertFalse(gate.update(true, true, true, true, 9000))
        assertTrue(gate.update(true, true, true, true, 9700))
        assertFalse(gate.update(false, true, true, true, 9800))
        assertFalse(gate.update(true, false, true, true, 11000))
    }
    @Test fun `angle configuration clamps invalid settings and rejects missing measurements`() {
        assertEquals(StandbyAngles(5, 10), StandbyAngles.normalized(-100, -50))
        assertEquals(StandbyAngles(165, 170), StandbyAngles.normalized(500, 500))
        assertEquals(StandbyAngles(50, 55), StandbyAngles.normalized(50, 20))
        val angles = StandbyAngles(25, 65)
        for (value in listOf(25f, 45f, 65f)) assertTrue(angles.contains(value))
        for (value in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 24.99f, 65.01f)) assertFalse(angles.contains(value))
    }
    @Test fun `inverted tent keeps its direction through the inner to cover handoff`() {
        assertEquals(StandbyDirection.RIGHT, standbyPostureDirection(FoldPanel.INNER, 0f, -8.69f, -4.32f, true))
        assertEquals(StandbyDirection.RIGHT, standbyPostureDirection(FoldPanel.COVER, 0f, -8.69f, -4.32f, true))
        assertNull(standbyPostureDirection(FoldPanel.UNKNOWN, 0f, -8.69f, -4.32f, true))
        assertNull(standbyPostureDirection(FoldPanel.INNER, 0f, -8.69f, -4.32f, false))
        for ((x, y, z) in listOf(Triple(0f, 8.69f, -4.32f), Triple(0f, -9.8f, 0f),
            Triple(0f, -8.69f, 4.32f), Triple(7f, -7f, -4f), Triple(Float.NaN, -9f, -4f)))
            assertNull(standbyPostureDirection(FoldPanel.INNER, x, y, z, true))
    }
    @Test fun `real hinge stays authoritative when vendor angle freezes or arrives late`() {
        val angles = HingeAngleSelection()
        assertEquals(86f, angles.update(86f, 100, false)?.degrees)
        assertEquals(51f, angles.update(51f, 110, true)?.degrees)
        assertNull(angles.update(86f, 120, false))
        assertEquals(51f, angles.sample?.degrees)
        assertNull(angles.update(70f, 109, true))
        assertNull(angles.update(Float.NaN, 130, true))
        assertEquals(180f, angles.update(180f, 140, true)?.degrees)
        // A direct first observation can supersede a newer vendor receipt.
        val queued = HingeAngleSelection()
        queued.update(86f, 200, false)
        assertEquals(51f, queued.update(51f, 190, true)?.degrees)
    }
    @Test fun `letterboxed inner uses maximum display bounds instead of cover configuration`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        for (areas in listOf(listOf(cover, inner), listOf(cover), emptyList())) {
            assertEquals(FoldPanel.INNER, launcherPanel(cover, areas, 498, 2364, 1672, 2.75f))
            assertEquals(FoldPanel.INNER, launcherPanel(1370L * 1672L, areas, 498, 1672, 2364, 2.75f))
        }
    }
    @Test fun `closing uses cover maximum bounds even with stale inner mode and configuration`() {
        val inner = 1672L * 2364L
        for (areas in listOf(listOf(1168L * 1712L, inner), listOf(inner), emptyList())) {
            assertEquals(FoldPanel.COVER, launcherPanel(inner, areas, 608, 1168, 1712, 2.75f))
            assertEquals(FoldPanel.COVER, launcherPanel(inner, areas, 608, 1712, 1168, 2.75f))
        }
    }
    @Test fun `unavailable full bounds retain physical fallback and invalid density is ignored`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(cover, inner), 498, 0, 0, 2.75f))
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover, inner), 608, 1370, 1672, Float.NaN))
    }
    @Test fun `cover standby waits five seconds of eligibility`() {
        val gate = StandbyEntryGate()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 0))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 4_999))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 5_000))
        assertFalse(gate.update(FoldPanel.COVER, false, true, 5_001))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 9_000))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 14_000))
    }
    @Test fun `folding upright never exposes standby even while cover config is still landscape`() {
        val gate = StandbyEntryGate(700L)
        assertFalse(gate.update(FoldPanel.INNER, true, false, 0))
        for (time in listOf(1L, 100L, 700L, 1500L))
            assertFalse(gate.update(FoldPanel.COVER, true, physicallyLandscape(0f, 9.8f), time))
        assertFalse(gate.update(FoldPanel.COVER, false, false, 2000))
    }
    @Test fun `genuine cover landscape must settle and portrait immediately exits`() {
        val gate = StandbyEntryGate(700L)
        assertFalse(gate.update(FoldPanel.COVER, true, true, 100))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 799))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 800))
        assertFalse(gate.update(FoldPanel.COVER, true, false, 801))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 802))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 1502))
    }
    @Test fun `panel handoff and lifecycle restart discard previous standby eligibility`() {
        val gate = StandbyEntryGate(700L)
        gate.update(FoldPanel.COVER, true, true, 0)
        assertTrue(gate.update(FoldPanel.COVER, true, true, 800))
        assertFalse(gate.update(FoldPanel.INNER, true, true, 900))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 901))
        gate.reset()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 5000))
    }
    @Test fun `both horizontal directions work but flat diagonal and invalid samples do not`() {
        assertTrue(physicallyLandscape(9.8f, 0f))
        assertTrue(physicallyLandscape(-9.8f, 0f))
        assertFalse(physicallyLandscape(0f, 9.8f))
        assertFalse(physicallyLandscape(0f, 0f))
        assertFalse(physicallyLandscape(6f, 6f))
        assertFalse(physicallyLandscape(Float.NaN, 0f))
    }
    @Test fun `cover landscape never gets classified as inner by window width`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover, inner), 800))
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(cover, inner), 425))
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover), 425))
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(inner), 608))
    }
    @Test fun `faces show only once requested and fitting, home only once settled`() {
        for (standby in listOf(true, false)) for (requested in listOf(true, false)) for (ready in listOf(true, false))
            for (settling in listOf(true, false)) {
                val surface = launcherSurface(standby, requested, ready, settling)
                assertEquals(standby && requested && ready, surface == LauncherSurface.STANDBY)
                assertEquals(!standby && !settling, surface == LauncherSurface.HOME)
            }
    }
    @Test fun `home supports all rotations on the inner panel, cover home is portrait, manual standby locks landscape`() {
        for (panel in FoldPanel.entries) {
            assertEquals(if (panel == FoldPanel.COVER) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_FULL_USER, launcherOrientation(panel))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, launcherOrientation(panel, true))
            for (direction in StandbyDirection.entries)
                assertEquals(direction.orientation, launcherOrientation(panel, true, direction))
        }
        assertFalse(isTentFoldStatus(0f))
        assertTrue(isTentFoldStatus(1f))
        assertFalse(isTentFoldStatus(2f))
    }
    @Test fun `entry requests the measured side directly without sensor landscape default`() {
        for ((x, expected) in listOf(9.8f to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            -9.8f to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            val gate = StandbyEntryGate(700L)
            val requests = listOf(0L, 100L, 699L, 700L, 800L).map { time ->
                launcherOrientation(FoldPanel.COVER,
                    gate.update(FoldPanel.COVER, true, physicallyLandscape(x, 0f), time), standbyDirection(x, 0f))
            }
            assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, expected, expected), requests)
        }
    }
    @Test fun `unknown upright diagonal and invalid samples never choose a default standby direction`() {
        for ((x, y) in listOf(0f to 0f, 0f to 9.8f, 6f to 6f, Float.NaN to 0f, Float.POSITIVE_INFINITY to 0f)) {
            assertNull(standbyDirection(x, y))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                launcherOrientation(FoldPanel.COVER, true, standbyDirection(x, y)))
        }
    }
    @Test fun `enter veils home before the request and stays black until the canvas fits`() {
        // Interaction review S1: no orientation request while Home is still visible.
        assertEquals(LauncherSurface.ENTERING, launcherSurface(standby = true, requested = false, ready = false, settling = false))
        assertEquals(LauncherSurface.ENTERING, launcherSurface(standby = true, requested = false, ready = false, settling = true))
        assertEquals(LauncherSurface.ROTATING, launcherSurface(standby = true, requested = true, ready = false, settling = false))
        assertEquals(LauncherSurface.STANDBY, launcherSurface(standby = true, requested = true, ready = true, settling = false))
    }
    @Test fun `exit keeps the face up until the window settles`() {
        assertEquals(LauncherSurface.EXITING, launcherSurface(standby = false, requested = false, ready = false, settling = true))
        assertEquals(LauncherSurface.HOME, launcherSurface(standby = false, requested = false, ready = false, settling = false))
    }
    @Test fun `the fold veil shows only where home would, standby surfaces keep priority`() {
        assertEquals(LauncherSurface.FOLDING, launcherSurface(standby = false, requested = false, ready = false, settling = false, foldVeil = true))
        for (standby in listOf(true, false)) for (requested in listOf(true, false)) for (ready in listOf(true, false))
            for (settling in listOf(true, false)) {
                val plain = launcherSurface(standby, requested, ready, settling)
                val veiled = launcherSurface(standby, requested, ready, settling, foldVeil = true)
                if (plain == LauncherSurface.HOME) assertEquals(LauncherSurface.FOLDING, veiled) else assertEquals(plain, veiled)
            }
    }
    @Test fun `standby canvas turns upright in any window`() {
        val r0 = android.view.Surface.ROTATION_0; val r90 = android.view.Surface.ROTATION_90
        val r180 = android.view.Surface.ROTATION_180; val r270 = android.view.Surface.ROTATION_270
        // Android rotated to StandBy's side: as is.
        assertEquals(0, standbyQuarterTurns(r90, r90, portraitWindow = false))
        assertEquals(0, standbyQuarterTurns(r270, r270, portraitWindow = false))
        // Request ignored, window still portrait: a quarter turn towards the stand's side (= RotatedStandbyScreen ±90°).
        assertEquals(1, standbyQuarterTurns(r90, r0, portraitWindow = true))
        assertEquals(3, standbyQuarterTurns(r270, r0, portraitWindow = true))
        assertEquals(3, standbyQuarterTurns(r90, r180, portraitWindow = true))
        // The display took the other landscape side.
        assertEquals(2, standbyQuarterTurns(r90, r270, portraitWindow = false))
        // Side unknown (sensor landscape): any landscape window as it is, ROTATION_90 in a portrait one.
        assertEquals(0, standbyQuarterTurns(null, r270, portraitWindow = false))
        assertEquals(1, standbyQuarterTurns(null, r0, portraitWindow = true))
        // Constants that don't fit the window (landscape-natural panel): never lay the canvas across the short side.
        for (target in listOf(null, r0, r90, r180, r270)) for (display in listOf(r0, r90, r180, r270))
            for (portrait in listOf(true, false))
                assertEquals(portrait, standbyQuarterTurns(target, display, portrait) % 2 == 1)
    }
    @Test fun `manual exit stays portrait until a real upright then sideways gesture`() {
        val gate = StandbyEntryGate(700L)
        gate.update(FoldPanel.COVER, true, true, 0)
        assertTrue(gate.update(FoldPanel.COVER, true, true, 700))
        gate.dismiss()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 2000))
        gate.pause()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 4000))
        assertFalse(gate.update(FoldPanel.COVER, true, false, 4100)) // flat/unknown does not re-arm
        assertFalse(gate.update(FoldPanel.COVER, true, true, 5000))
        gate.update(FoldPanel.COVER, true, false, 6000, upright = true)
        assertFalse(gate.update(FoldPanel.COVER, true, true, 7000))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 7700))
    }
    @Test fun `helper handoff keeps entry and a dismissal until the angle leaves range`() {
        val gate = StandbyEntryGate(700L)
        assertFalse(gate.update(FoldPanel.INNER, true, true, 0))
        assertTrue(gate.update(FoldPanel.INNER, true, true, 700))
        gate.retainPanel(FoldPanel.COVER)
        assertTrue(gate.update(FoldPanel.COVER, true, true, 701))
        gate.dismiss()
        gate.retainPanel(FoldPanel.INNER)
        assertFalse(gate.update(FoldPanel.INNER, true, true, 1_000))
        gate.leaveRange()
        assertFalse(gate.update(FoldPanel.INNER, true, true, 1_001))
        assertTrue(gate.update(FoldPanel.INNER, true, true, 1_701))
    }
    @Test fun `setup can prevent entry without unlocking cover home orientation`() {
        val gate = StandbyEntryGate(700L)
        assertFalse(gate.update(FoldPanel.COVER, false, true, 0))
        assertFalse(gate.update(FoldPanel.COVER, false, true, 1000))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 2000))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 2700))
    }
    @Test fun `month cells handle Sunday leap February and six week months`() {
        for (date in listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 2, 1))) {
            val cells = standbyMonthCells(date)
            assertEquals(42, cells.size)
            assertEquals((1..date.lengthOfMonth()).toList(), cells.filterNotNull())
            assertEquals(date.withDayOfMonth(1).dayOfWeek.value % 7, cells.indexOf(1))
        }
    }
    @Test fun `ten to eighty degree standing interval works on either active panel`() {
        for (angle in listOf(10f, 28f, 45f, 80f)) {
            assertTrue(standbyHingePosture(angle, true))
            assertFalse(standbyHingePosture(angle, false))
        }
        for (angle in listOf(null, Float.NaN, 0f, 9.9f, 80.1f, 180f))
            assertFalse(standbyHingePosture(angle, true))
        for (panel in listOf(FoldPanel.COVER, FoldPanel.INNER)) {
            val gate = StandbyEntryGate(700L)
            assertFalse(gate.update(panel, true, true, 0))
            assertTrue(gate.update(panel, true, true, 700))
        }
    }
    @Test fun `a phone set on a table is still while a hand-held tent is not`() {
        val table = StillnessDetector()
        assertFalse(table.isStill(0))
        for (t in 0L..700L step 100L) table.add(t, 9.31f + (t % 200) * .0002f, .41f, -2.6f + (t % 300) * .0001f)
        assertTrue(table.isStill(700))
        // One reading proves nothing; nor do samples older than the window.
        val single = StillnessDetector().apply { add(0, 9.3f, .4f, -2.6f) }
        assertFalse(single.isStill(0))
        assertFalse(table.isStill(5_000))
        // Hand tremor: ≈4° wobble on the gravity vector.
        val hand = StillnessDetector()
        for (t in 0L..700L step 100L) hand.add(t, 9.3f + if (t % 200 == 0L) .35f else -.35f, .4f, -2.6f)
        assertFalse(hand.isStill(700))
        // Setting it down: motion first, then stillness once the window has only quiet samples.
        val placed = StillnessDetector()
        for (t in 0L..300L step 100L) placed.add(t, 6f + t / 50f, 3f, -1f)
        for (t in 400L..1_100L step 100L) placed.add(t, 9.3f, .4f, -2.6f)
        assertFalse(placed.isStill(700))
        assertTrue(placed.isStill(1_100))
        placed.add(1_200, Float.NaN, 0f, 0f)
        assertFalse(placed.isStill(1_200))
        placed.reset()
        assertFalse(placed.isStill(1_200))
    }
    @Test fun `night mode needs a dark room for a moment and leaves with hysteresis`() {
        val night = StandbyNightDetector()
        assertFalse(night.update(200f, -100)) // StandBy opened in a lit room; the lights then go out
        assertFalse(night.update(1f, 0))
        assertFalse(night.update(1f, 2_000))
        assertFalse(night.update(200f, 2_500)) // a light switched back on resets the wait
        assertFalse(night.update(0f, 3_000))
        assertFalse(night.update(0f, 5_999))
        assertTrue(night.update(0f, 6_000))
        // Between the thresholds nothing changes (no flicker from a phone screen nearby).
        assertTrue(night.update(8f, 7_000))
        assertTrue(night.update(8f, 20_000))
        assertTrue(night.update(50f, 21_000))
        assertTrue(night.update(Float.NaN, 21_500))
        assertTrue(night.update(-1f, 21_600))
        assertFalse(night.update(50f, 22_500))
        assertTrue(standbyNightByClock(23) && standbyNightByClock(0) && standbyNightByClock(5))
        assertFalse(standbyNightByClock(6) || standbyNightByClock(12) || standbyNightByClock(21))
    }
    @Test fun `night mode starts from the first reading instead of three seconds of colour`() {
        // Dark bedroom: red from the very first frame.
        assertTrue(StandbyNightDetector().update(0f, 0))
        // Lit room, and a remembered night overridden by a bright first reading.
        assertFalse(StandbyNightDetector().update(300f, 0))
        assertFalse(StandbyNightDetector(initial = true).update(300f, 0))
        // Between the thresholds the remembered state stands (no flicker at dusk).
        assertTrue(StandbyNightDetector(initial = true).update(6f, 0))
        assertFalse(StandbyNightDetector(initial = false).update(6f, 0))
        // Invalid readings do not count as the first one; later readings keep the hysteresis.
        val detector = StandbyNightDetector()
        assertFalse(detector.update(Float.NaN, 0))
        assertTrue(detector.update(1f, 10))
        assertTrue(detector.update(50f, 20))
        assertFalse(detector.update(50f, 1_520))
        // Process memory: fresh for 15 minutes, only remembers night.
        StandbyNightMemory.remember(true, 1_000)
        assertTrue(StandbyNightMemory.recall(1_000 + 14 * 60_000L))
        assertFalse(StandbyNightMemory.recall(1_000 + 16 * 60_000L))
        StandbyNightMemory.remember(false, 2_000)
        assertFalse(StandbyNightMemory.recall(2_000))
    }
    @Test fun `screen stays lit only on external power and the locked clock never wakes a sleeping phone on battery`() {
        assertTrue(standbyKeepsScreenOn(true))
        assertFalse(standbyKeepsScreenOn(false))
        assertFalse(standbyExternalPower(0))
        // AC, USB, wireless, dock.
        for (plugged in listOf(1, 2, 4, 8)) assertTrue(standbyExternalPower(plugged))
        assertTrue(lockedClockMayShow(externalPower = true, interactive = false))
        assertTrue(lockedClockMayShow(externalPower = false, interactive = true))
        assertFalse(lockedClockMayShow(externalPower = false, interactive = false))
        // On battery the lock gate never opens for a dark screen.
        val gate = LockedStandbyGate()
        val enabled = lockedClockMayShow(externalPower = false, interactive = false)
        assertFalse(gate.update(true, enabled, true, true, 0))
        assertFalse(gate.update(true, enabled, true, true, 5_000))
    }
    @Test fun `automatic home standby waits for an idle home with nothing open`() {
        val idle = HomeIdleGate()
        assertTrue(idle.idle(0))
        idle.noteInput(1_000)
        assertFalse(idle.idle(1_000))
        assertFalse(idle.idle(1_000 + HOME_STANDBY_IDLE_MILLIS - 1))
        assertTrue(idle.idle(1_000 + HOME_STANDBY_IDLE_MILLIS))
        idle.busy = true
        assertFalse(idle.idle(60_000))
        idle.busy = false
        assertTrue(idle.idle(60_000))
    }
    @Test fun `locked clock monitor watches nothing while the phone is in use`() {
        // Unlocked and on: no hinge, no accelerometer, no tick, whatever the angle.
        for (inRange in listOf(true, false))
            assertEquals(StandbyMonitorPlan(false, false, false), standbyMonitorPlan(locked = false, interactive = true, angleInRange = inRange))
        // Locked or asleep: the hinge alone at a low rate until it enters the StandBy range.
        assertEquals(StandbyMonitorPlan(true, false, false), standbyMonitorPlan(locked = true, interactive = true, angleInRange = false))
        assertEquals(StandbyMonitorPlan(true, false, false), standbyMonitorPlan(locked = false, interactive = false, angleInRange = false))
        // In range: the accelerometer only once locked; the settle tick also while waiting for the keyguard.
        assertEquals(StandbyMonitorPlan(true, true, true), standbyMonitorPlan(locked = true, interactive = false, angleInRange = true))
        assertEquals(StandbyMonitorPlan(true, false, true), standbyMonitorPlan(locked = false, interactive = false, angleInRange = true))
    }
}
