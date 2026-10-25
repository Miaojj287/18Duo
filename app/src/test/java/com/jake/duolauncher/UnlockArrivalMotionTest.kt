package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class UnlockArrivalMotionTest {
    private val eps = 1e-4f

    @Test fun firstFrameIsLargeTransparentAndZoomed() {
        val f = UnlockArrivalMotion.frame(0f, reduced = false)
        assertEquals(UnlockArrivalMotion.CONTENT_START_SCALE, f.contentScale, eps)
        // Round 13: half-opaque on the first frame (the lock screen's window vanishes in one frame).
        assertEquals(UnlockArrivalMotion.ALPHA_START, f.contentAlpha, eps)
        assertEquals(UnlockArrivalMotion.WALLPAPER_START_SCALE, f.wallpaperScale, eps)
        assertFalse(f.atRest)
    }

    @Test fun restIsIdentity() {
        assertTrue(UnlockArrivalMotion.frame(1f, reduced = false).atRest)
        assertTrue(UnlockArrivalMotion.frame(1f, reduced = true).atRest)
        assertTrue(UnlockArrivalMotion.frame(Float.NaN, reduced = false).atRest)
    }

    @Test fun matchesTheRecordingsFirstIconFrames() {
        // ios26-lock-swipe-unlock.mov f113: the Reddit icon is 1.65× its rest width and ≈ 80 % opaque,
        // one frame after it first appears (f112, ≈ 1.8–2×).
        val p = generateSequence(0f) { it + .001f }.first { UnlockArrivalMotion.frame(it, false).contentScale <= 1.65f }
        val f = UnlockArrivalMotion.frame(p, false)
        assertTrue("alpha ${f.contentAlpha}", f.contentAlpha in .7f..0.9f)
        assertTrue(UnlockArrivalMotion.CONTENT_START_SCALE in 1.8f..2f)
        // The status rail is there at once (f107): opaque long before the icons settle.
        assertEquals(1f, UnlockArrivalMotion.frame(UnlockArrivalMotion.STATUS_LEAD, false).statusAlpha, 0f)
        assertTrue(UnlockArrivalMotion.frame(.05f, false).statusAlpha > UnlockArrivalMotion.frame(.05f, false).contentAlpha)
    }

    @Test fun dockComesFromBelowOnItsOwnBouncierSpring() {
        // A bottom-centred Dock (inner portrait, centre 0.42 H below the panel centre) starts beyond the bottom edge.
        val c = UnlockArrivalMotion.chromeFrame(0f, false)
        val h = 860f
        val dockCenter = h / 2f + .42f * h
        assertTrue(dockCenter + c.displacement(dockCenter, h / 2f) > h + 40f)
        assertEquals(0f, c.contentAlpha, 0f)
        assertTrue(UnlockArrivalMotion.chromeFrame(1f, false).atRest)
        assertTrue(UnlockArrivalMotion.chromeSpring.dampingFraction < UnlockArrivalMotion.spring.dampingFraction)
        // Its bounce may pass rest, but never collapses the Dock.
        assertTrue(UnlockArrivalMotion.chromeFrame(1.1f, false).contentScale >= UnlockArrivalMotion.CHROME_MIN_SCALE)
        assertEquals(1f, UnlockArrivalMotion.chromeFrame(.5f, true).contentScale, 0f)
    }

    @Test fun monotonicTowardsRest() {
        var last = UnlockArrivalMotion.frame(0f, false)
        for (i in 1..100) {
            val f = UnlockArrivalMotion.frame(i / 100f, false)
            assertTrue(f.contentScale <= last.contentScale + eps)
            assertTrue(f.contentAlpha >= last.contentAlpha - eps)
            assertTrue(f.wallpaperScale <= last.wallpaperScale + eps)
            last = f
        }
        assertTrue(UnlockArrivalMotion.frame(UnlockArrivalMotion.ALPHA_LEAD, false).contentAlpha == 1f)
    }

    @Test fun springOvershootNeverUncoversWallpaperOrShrinksMuch() {
        for (p in listOf(1.005f, 1.02f, 1.05f, 1.2f)) {
            val f = UnlockArrivalMotion.frame(p, false)
            assertEquals(1f, f.wallpaperScale, 0f)
            assertEquals(1f, f.contentAlpha, 0f)
            assertTrue(f.contentScale >= UnlockArrivalMotion.CONTENT_MIN_SCALE)
        }
    }

    @Test fun reduceMotionOnlyDissolves() {
        for (i in 0..10) {
            val f = UnlockArrivalMotion.frame(i / 10f, reduced = true)
            assertEquals(1f, f.contentScale, 0f)
            assertEquals(1f, f.wallpaperScale, 0f)
        }
        assertEquals(0f, UnlockArrivalMotion.frame(0f, true).contentAlpha, 0f)
    }

    @Test fun chromeMovesWithTheContentLayer() {
        // A Dock centred at 89.6 %W of a 425 dp cover is carried 39.6 %W·(s−1) further right.
        val f = UnlockArrivalMotion.frame(0f, false)
        val w = 425f
        val moved = f.displacement(.896f * w, w / 2f)
        assertEquals((.896f * w - w / 2f) * (UnlockArrivalMotion.CONTENT_START_SCALE - 1f), moved, eps)
        // Its scaled left edge (dock x 82.1 %W) starts beyond the right edge's inner side: off-screen as in photo 2.
        val dockLeft = w / 2f + (.821f * w - w / 2f) * f.contentScale
        assertTrue(dockLeft > .95f * w)
        assertEquals(0f, UnlockArrivalFrame.REST.displacement(300f, 10f), 0f)
    }

    @Test fun wallpaperScaleIsOneProduct() {
        assertEquals(1.08f * 1.05f * 1.02f, homeWallpaperScale(1.02f, 1.05f, 1.08f), eps)
        assertEquals(1f, UnlockArrivalMotion.WALLPAPER_START_SCALE, 0f) // round 12.2: no wallpaper zoom (iOS 26 recording)
        // Unlocking half-folded: the hinge zoom keeps its value, the arrival settles on top of it.
        val fold = 1.04f
        assertEquals(fold * UnlockArrivalMotion.WALLPAPER_START_SCALE,
            homeWallpaperScale(1f, fold, UnlockArrivalMotion.frame(0f, false).wallpaperScale), eps)
        assertEquals(fold, homeWallpaperScale(1f, fold, UnlockArrivalMotion.frame(1f, false).wallpaperScale), eps)
        assertEquals(WALLPAPER_HANDOFF_SCALE, UnlockArrivalMotion.WALLPAPER_START_SCALE, 0f)
    }

    @Test fun springSettlesLikeIos() {
        // Damped harmonic oscillator from 0 → 1 with the arrival spring: the recording's icons are at rest
        // 0.27 s after their first frame (f112 → f120) with no visible bounce: ≈95 % in 0.2–0.45 s, overshoot < 2 %.
        val s = UnlockArrivalMotion.spring
        val w = s.angularFrequency.toDouble(); val z = s.dampingFraction.toDouble()
        val wd = w * sqrt(1 - z * z)
        fun x(t: Double) = 1 - exp(-z * w * t) * (Math.cos(wd * t) + z * w / wd * Math.sin(wd * t))
        val t95 = generateSequence(0.0) { it + .001 }.first { x(it) >= .95 }
        assertTrue("t95=$t95", t95 in .18..0.45)
        val peak = generateSequence(0.0) { it + .001 }.take(3000).maxOf { x(it) }
        assertTrue("peak=$peak", peak - 1 in 0.0..0.02)
        assertTrue(UnlockArrivalMotion.finishSpring.response < s.response)
    }

    // ------------------------------------------------------------------ policy

    @Test fun lockWithHomeInFrontArmsWhenHidden() {
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.SCHEDULE_ARM, p.screenOff(homeInFront = true, homeHidden = false))
        assertEquals(ArrivalPhase.PENDING, p.phase)
        assertEquals(ArrivalCommand.ARM, p.homeStopped())
        assertEquals(ArrivalPhase.ARMED, p.phase)
        // USER_PRESENT while Home is still stopped (an 18Duo lock screen or app on top) waits for Home.
        assertEquals(ArrivalCommand.SCHEDULE_TIMEOUT, p.userPresent(homeResumed = false, homeFocused = false))
        assertEquals(ArrivalCommand.PLAY, p.userPresent(homeResumed = true, homeFocused = true))
        assertEquals(ArrivalPhase.PLAYING, p.phase)
        p.finished()
        assertEquals(ArrivalPhase.REST, p.phase)
    }

    @Test fun alreadyStoppedHomeArmsImmediately() {
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.ARM, p.screenOff(homeInFront = true, homeHidden = true))
        // Home resumes first (keyguard gone), USER_PRESENT arrives later and changes nothing.
        assertEquals(ArrivalCommand.PLAY, p.homeResumed(focused = true))
        assertEquals(ArrivalCommand.NONE, p.userPresent(homeResumed = true, homeFocused = true))
    }

    @Test fun lockScreenActivityDismissalPlaysWhenHomeReturns() {
        // 18Duo lock screen (r8-lock): keyguardDismissed() while it is still on top, Home resumes after.
        val p = UnlockArrivalPolicy()
        p.screenOff(true, true)
        assertEquals(ArrivalCommand.SCHEDULE_TIMEOUT, p.userPresent(homeResumed = false, homeFocused = false))
        assertEquals(ArrivalCommand.PLAY, p.homeResumed(focused = true))
        assertEquals(ArrivalCommand.NONE, p.presentTimeout(homeResumed = true))
    }

    @Test fun lockScreenHandOffPlaysOnTheResumeWithoutWaitingForFocus() {
        // Round 13: the 18Duo lock screen vanishes in one frame, so Home plays at once when it resumes.
        val p = UnlockArrivalPolicy()
        p.screenOff(true, true)
        assertEquals(ArrivalCommand.SCHEDULE_TIMEOUT, p.lockScreenHandoff(homeResumed = false, homeFocused = false))
        assertTrue(p.lockHandoff)
        assertEquals(ArrivalCommand.PLAY, p.homeResumed(focused = false))
        p.finished()
        assertFalse(p.lockHandoff)
        // A plain keyguard unlock still waits for focus (the system keyguard fades out over Home).
        val q = UnlockArrivalPolicy()
        q.screenOff(true, true)
        q.userPresent(homeResumed = false, homeFocused = false)
        assertEquals(ArrivalCommand.SCHEDULE_FOCUS_FALLBACK, q.homeResumed(focused = false))
        // Not armed (an app was in front): the hand-off changes nothing.
        val r = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.NONE, r.lockScreenHandoff(homeResumed = false, homeFocused = false))
        assertFalse(r.lockHandoff)
    }

    @Test fun unlockIntoAnAppDisarms() {
        val p = UnlockArrivalPolicy()
        p.screenOff(true, true)
        assertEquals(ArrivalCommand.SCHEDULE_TIMEOUT, p.userPresent(homeResumed = false, homeFocused = false))
        assertEquals(ArrivalCommand.DISARM, p.presentTimeout(homeResumed = false))
        assertEquals(ArrivalPhase.REST, p.phase)
        // Returning to Home later shows it at rest.
        assertEquals(ArrivalCommand.NONE, p.homeResumed(focused = true))
    }

    @Test fun appInFrontNeverArms() {
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.NONE, p.screenOff(homeInFront = false, homeHidden = true))
        assertEquals(ArrivalCommand.NONE, p.userPresent(homeResumed = true, homeFocused = true))
        assertEquals(ArrivalCommand.NONE, p.homeResumed(focused = true))
    }

    @Test fun noKeyguardScreenOnPlaysAndQuickWakeDoesNot() {
        val p = UnlockArrivalPolicy()
        p.screenOff(true, false)
        assertEquals(ArrivalCommand.ARM, p.armDeadline(interactive = false, keyguardLocked = false))
        assertEquals(ArrivalCommand.PLAY, p.homeResumed(focused = true))
        p.finished()
        // Screen off and straight back on before Home was hidden: Home never left.
        val q = UnlockArrivalPolicy()
        q.screenOff(true, false)
        assertEquals(ArrivalCommand.NONE, q.homeResumed(focused = true))
        assertEquals(ArrivalPhase.REST, q.phase)
        assertEquals(ArrivalCommand.NONE, q.armDeadline(interactive = true, keyguardLocked = false))
    }

    @Test fun noKeyguardArmsAtScreenOffAndArrivesAtScreenOn() {
        // Lock "None": Home stays resumed while the dark panel sleeps; it is armed at once and arrives on wake.
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.ARM, p.screenOff(homeInFront = true, homeHidden = true))
        assertEquals(ArrivalCommand.NONE, p.screenOn(keyguardLocked = true, homeResumed = true))
        assertEquals(ArrivalCommand.PLAY, p.screenOn(keyguardLocked = false, homeResumed = true))
        val q = UnlockArrivalPolicy()
        q.screenOff(true, homeHidden = false)
        assertEquals(ArrivalCommand.NONE, q.screenOn(keyguardLocked = false, homeResumed = true))
        assertEquals(ArrivalPhase.REST, q.phase)
    }

    @Test fun lateBroadcastsAfterWakeStillArmUnderTheKeyguard() {
        // Emulator/doze: SCREEN_OFF, onStop and SCREEN_ON are all delivered only after the wake.
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.SCHEDULE_ARM, p.screenOff(homeInFront = true, homeHidden = false))
        assertEquals(ArrivalCommand.ARM, p.homeStopped())
        val q = UnlockArrivalPolicy()
        q.screenOff(true, homeHidden = false)
        assertEquals(ArrivalCommand.ARM, q.screenOn(keyguardLocked = true, homeResumed = false))
        // Resumed while the keyguard still fades over Home: the arrival waits for Home's window focus.
        assertEquals(ArrivalCommand.SCHEDULE_FOCUS_FALLBACK, q.homeResumed(focused = false))
        assertEquals(ArrivalCommand.NONE, q.userPresent(homeResumed = true, homeFocused = false))
        assertEquals(ArrivalCommand.PLAY, q.homeFocused(homeResumed = true))
        assertEquals(ArrivalCommand.NONE, q.focusFallback(homeResumed = true))
        assertEquals(ArrivalCommand.NONE, q.userPresent(homeResumed = true, homeFocused = true))
        val r = UnlockArrivalPolicy()
        r.screenOff(true, homeHidden = false)
        assertEquals(ArrivalCommand.ARM, r.armDeadline(interactive = true, keyguardLocked = true))
    }

    @Test fun touchFinishesFastAndScreenOffMidArrivalRearms() {
        val p = UnlockArrivalPolicy()
        p.screenOff(true, true); p.homeResumed(focused = true)
        assertEquals(ArrivalCommand.FINISH_FAST, p.touched())
        assertEquals(ArrivalCommand.ARM, p.screenOff(homeInFront = true, homeHidden = true))
        assertEquals(ArrivalCommand.NONE, UnlockArrivalPolicy().touched())
    }

    @Test fun homeInFrontAtScreenOff() {
        assertTrue(homeWasInFront(resumed = true, pausedForSleepAtMs = null, nowMs = 0, appOpening = false, standby = false))
        // onPause ran (non-interactive) just before ACTION_SCREEN_OFF.
        assertTrue(homeWasInFront(false, 1_000L, 1_200L, false, false))
        assertFalse(homeWasInFront(false, 1_000L, 1_000L + SLEEP_PAUSE_WINDOW_MS + 1, false, false))
        assertFalse(homeWasInFront(false, null, 5_000L, false, false))
        assertFalse(homeWasInFront(true, null, 0, appOpening = true, standby = false))
        assertFalse(homeWasInFront(true, null, 0, appOpening = false, standby = true))
        assertTrue(abs(ARM_DEADLINE_MS - 900L) < 1 && PRESENT_TIMEOUT_MS > ARM_DEADLINE_MS)
    }

    // ------------------------------------------------- round 11: no broadcasts (HyperOS)

    @Test fun armsFromLifecycleAndKeyguardWithoutAnyBroadcast() {
        val p = UnlockArrivalPolicy()
        // Power key: Home pauses with the keyguard already up, its window is stopped once the panel is dark.
        assertEquals(ArrivalCommand.SCHEDULE_ARM, p.homePaused(homeInFront = true, keyguardLocked = true, displayOn = true))
        assertEquals(ArrivalPhase.PENDING, p.phase)
        assertEquals(ArrivalCommand.ARM, p.homeStopped(homeInFront = true, keyguardLocked = true, displayOn = false))
        assertEquals(ArrivalPhase.ARMED, p.phase)
        // Resumed under a still-locked keyguard (OEM behaviour): stays armed, invisible.
        assertEquals(ArrivalCommand.NONE, p.homeResumed(focused = false, keyguardLocked = true))
        assertEquals(ArrivalPhase.ARMED, p.phase)
        // The keyguard listener reports unlocked before focus: focus (or its fallback) plays it.
        assertEquals(ArrivalCommand.NONE, p.keyguardUnlocked(homeResumed = true, homeFocused = false))
        assertEquals(ArrivalCommand.PLAY, p.homeFocused(homeResumed = true))
        assertEquals(ArrivalPhase.PLAYING, p.phase)
    }

    @Test fun stoppedWhileLockedArmsEvenWithoutAPause() {
        // onPause arrived while interactive and unlocked (nothing), onStop finds the keyguard up.
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.NONE, p.homePaused(homeInFront = true, keyguardLocked = false, displayOn = true))
        assertEquals(ArrivalCommand.ARM, p.homeStopped(homeInFront = true, keyguardLocked = true, displayOn = true))
        assertEquals(ArrivalCommand.PLAY, p.homeResumed(focused = true, keyguardLocked = false))
    }

    @Test fun displayOffEqualsScreenOffAndKeyguardUnlockedEqualsUserPresent() {
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.ARM, p.displayOff(homeInFront = true, homeHidden = true))
        assertEquals(ArrivalCommand.SCHEDULE_TIMEOUT, p.keyguardUnlocked(homeResumed = false, homeFocused = false))
        assertEquals(ArrivalCommand.PLAY, p.keyguardUnlocked(homeResumed = true, homeFocused = true))
    }

    @Test fun appInFrontDisarmsAndNeverArms() {
        // Home stopped behind an app (display on, unlocked): no arrival later.
        val p = UnlockArrivalPolicy()
        assertEquals(ArrivalCommand.NONE, p.homeStopped(homeInFront = false, keyguardLocked = false, displayOn = true))
        assertEquals(ArrivalCommand.NONE, p.homeStopped(homeInFront = true, keyguardLocked = false, displayOn = true))
        assertEquals(ArrivalPhase.REST, p.phase)
        // Armed, then an app comes in front while unlocked: rest invisibly.
        val q = UnlockArrivalPolicy()
        q.homeStopped(homeInFront = true, keyguardLocked = true, displayOn = false)
        assertEquals(ArrivalCommand.DISARM, q.homeStopped(homeInFront = false, keyguardLocked = false, displayOn = true))
        assertEquals(ArrivalPhase.REST, q.phase)
        // Stopped again while still locked (lock screen activity on top): stays armed.
        val r = UnlockArrivalPolicy()
        r.homeStopped(true, true, false)
        assertEquals(ArrivalCommand.NONE, r.homeStopped(false, true, false))
        assertEquals(ArrivalPhase.ARMED, r.phase)
    }
}
