package com.jake.duolauncher

/**
 * iPhone Duo unlock → Home "arrival from the viewer".
 *
 * Round 8 measured it on the owner's photos (r8/1–2); round 11 re-tunes it to the owner's iOS 26
 * recording (`ios26-lock-swipe-unlock.mov`, 30 fps frames, Home appears at f107, icons first visible
 * at f112): the icons start ≈ 1.8–2× their size, pushed outward from the panel centre (Reddit, top
 * row: width 377 px at f113 vs 229 px at rest = 1.65× one frame in), already ≈ 80 % opaque, and settle
 * critically: the remaining deviation roughly halves every frame (0.65 → 0.35 → 0.20 → 0.12 → 0.06 →
 * 0.03 → 0.01, f113–f119), at rest by f120 (0.27 s). The Dock comes up from below the bottom edge
 * with a light bounce (its top edge 2683 → 2486 px at f118, then back to 2536 px by f130: ≈ 13 % of
 * its travel, settled 0.6 s after the start), the search pill with it (f111). The status bar is
 * there on the first Home frame (f107). The wallpaper is at rest on Home's frames.
 *
 * So: the Home content (pages, widgets, Today) is one layer that starts larger about the panel
 * centre and transparent and springs into its slots; the Dock and Search are their own layer on a
 * bouncier spring; the status rail fades in at once; the wallpaper settles from the lock screen's
 * hand-off zoom to rest (the recording snaps 1.075 → 1 between f105 and f106, the hand-off frame;
 * 18Duo settles it over the spring because HyperOS inserts its own unlock transition there).
 *
 * Pure numbers here (JVM-tested); [UnlockArrival] decides when to play, `UnlockArrivalLayers` draws.
 */
internal object UnlockArrivalMotion {
    /** Content scale about the panel centre on the first arrival frame (f112–f113: ≈ 1.8–2×, 1.65 one frame in). */
    const val CONTENT_START_SCALE = 1.9f
    /**
     * Wallpaper zoom on the first arrival frame = the zoom the 18Duo lock screen reaches as its slab
     * leaves (`LockSwipe.HANDOFF_WALLPAPER_SCALE`): the recording's wallpaper is 1.075× at f104–f105
     * (slab almost gone), 1 + 0.085·travel/H. Multiplied with Core.24's hinge zoom, never overriding it.
     */
    const val WALLPAPER_START_SCALE = 1f // round 12.2: the owner's iOS 26 unlock recording keeps the wallpaper still (f001–f140)
    /** Opacity reaches 1 at this fraction of the spring's progress (f113: ≈ 80 % opaque at scale 1.65). */
    const val ALPHA_LEAD = .35f
    /**
     * Round 13: content (and status rail) opacity on the first arrival frame. The 18Duo lock screen's window
     * vanishes in one frame, so a transparent first frame read as a bare-wallpaper gap; iOS shows the icons
     * large and opaque at once (core29-lock-unlock.mp4 f131 ≈ 2.4×). 0.5 keeps f113's ≈ 80 % at 1.65×.
     * Reduce Motion keeps its dissolve from 0.
     */
    const val ALPHA_START = .5f
    /** The status rail is there at once (f107): fully in within the first 15 % of the progress. */
    const val STATUS_LEAD = .15f
    /** The content never dips more than this below rest while the spring settles. */
    const val CONTENT_MIN_SCALE = .985f
    /** The Dock/Search layer may overshoot further (its bounce), but never collapses. */
    const val CHROME_MIN_SCALE = .9f
    /**
     * Icon settle: response 0.36 s, damping 0.82 (≈ 1.3 % overshoot, 95 % in ≈ 0.26 s). The recording
     * is at rest 0.27 s after the first icon frame with no visible bounce.
     */
    val spring = IosSpring(.36f, .82f)
    /** Dock + Search from below: response 0.4 s, damping 0.66 (≈ 6 % overshoot; f118 → f130 settle). */
    val chromeSpring = IosSpring(.4f, .66f)
    /** A touch during the arrival hands Home to the finger at once: critically damped, response 0.22 s. */
    val finishSpring = IosSpring(.22f, 1f)
    /** Reduce Motion: iOS replaces the zoom with a dissolve; the opacity curve stays, nothing scales. */
    val reducedSpring = IosSpring(.3f, 1f)

    fun frame(progress: Float, reduced: Boolean): UnlockArrivalFrame {
        if (progress >= 1f && progress.isFinite() && progress <= 1.0001f) return UnlockArrivalFrame.REST
        val p = if (progress.isFinite()) progress else 1f
        val remaining = 1f - p
        if (reduced) return UnlockArrivalFrame(1f, (p / ALPHA_LEAD).coerceIn(0f, 1f), 1f, (p / STATUS_LEAD).coerceIn(0f, 1f))
        val alpha = (ALPHA_START + (1f - ALPHA_START) * p / ALPHA_LEAD).coerceIn(0f, 1f)
        val status = (ALPHA_START + (1f - ALPHA_START) * p / STATUS_LEAD).coerceIn(0f, 1f)
        val content = (1f + (CONTENT_START_SCALE - 1f) * remaining).coerceIn(CONTENT_MIN_SCALE, CONTENT_START_SCALE)
        // The wallpaper never zooms out below rest (an overshoot would uncover the panel edges).
        val wallpaper = (1f + (WALLPAPER_START_SCALE - 1f) * remaining).coerceIn(1f, WALLPAPER_START_SCALE)
        return UnlockArrivalFrame(content, alpha, wallpaper, status)
    }

    /** The Dock and Search layer: same start, its own (bouncier) progress. */
    fun chromeFrame(progress: Float, reduced: Boolean): UnlockArrivalFrame {
        if (progress >= 1f && progress.isFinite() && progress <= 1.0001f) return UnlockArrivalFrame.REST
        val p = if (progress.isFinite()) progress else 1f
        val alpha = (p / ALPHA_LEAD).coerceIn(0f, 1f)
        if (reduced) return UnlockArrivalFrame(1f, alpha, 1f, 1f)
        val scale = (1f + (CONTENT_START_SCALE - 1f) * (1f - p)).coerceIn(CHROME_MIN_SCALE, CONTENT_START_SCALE)
        return UnlockArrivalFrame(scale, alpha, 1f, 1f)
    }
}

/** Scale the 18Duo lock screen's wallpaper should have reached when it hands over to Home. */
internal const val WALLPAPER_HANDOFF_SCALE = UnlockArrivalMotion.WALLPAPER_START_SCALE

internal data class UnlockArrivalFrame(val contentScale: Float, val contentAlpha: Float, val wallpaperScale: Float,
    val statusAlpha: Float = 1f) {
    val atRest: Boolean get() = contentScale == 1f && contentAlpha == 1f && wallpaperScale == 1f && statusAlpha == 1f

    /**
     * Translation that carries an element drawn in its own layer (the Dock, Search) exactly where the
     * scaled content layer carries the icons: its centre moves away from the panel centre by the scale.
     */
    fun displacement(elementCenter: Float, panelCenter: Float): Float = (elementCenter - panelCenter) * (contentScale - 1f)

    companion object { val REST = UnlockArrivalFrame(1f, 1f, 1f, 1f) }
}

/**
 * The single source of truth for Home's root wallpaper scale: the app-open/close zoom, Core.24's
 * hinge zoom (`1 + 0.08·gap`) and the unlock arrival all multiply, so unlocking half-folded or opening
 * an app during the arrival composes instead of one effect overriding another.
 */
internal fun homeWallpaperScale(appOpen: Float, fold: Float, arrival: Float): Float = appOpen * fold * arrival

/** What the arrival controller should do after an event (see [UnlockArrivalPolicy]). */
internal enum class ArrivalCommand { NONE, ARM, SCHEDULE_ARM, PLAY, SCHEDULE_FOCUS_FALLBACK, SCHEDULE_TIMEOUT, DISARM, FINISH_FAST }

internal enum class ArrivalPhase { REST, PENDING, ARMED, PLAYING }

/**
 * When the arrival plays. Round 11: the triggers no longer depend on the screen broadcasts (HyperOS
 * never delivered ACTION_SCREEN_OFF/ON/USER_PRESENT to the launcher — phone logcat 2026-10-21): Home
 * arms when its window is paused/stopped while the keyguard is locked or the display is off
 * (lifecycle + DisplayManager + isKeyguardLocked), plays on resume + window focus (or the fallback)
 * once the keyguard is unlocked (KeyguardManager's locked-state listener on API 33+), and disarms
 * when an app comes in front. The broadcasts remain as an extra source.
 *
 * Home is "armed" (start state: content large and transparent, wallpaper
 * zoomed) only while its window is out of sight after the screen went off with Home in front —
 * stopped behind the keyguard, or with the keyguard covering it at screen-on — so arming never shows.
 * It plays once Home is resumed again (only possible after the keyguard started to leave), at window
 * focus or [FOCUS_FALLBACK_MS] later, whichever is first; ACTION_USER_PRESENT and the 18Duo lock
 * screen's [UnlockArrival.keyguardDismissed] also count. An unlock that reveals an app disarms
 * silently; a wake without any keyguard (Home never hidden) does not play, because it would pop.
 */
internal class UnlockArrivalPolicy {
    var phase: ArrivalPhase = ArrivalPhase.REST
        private set

    /**
     * Round 13: the 18Duo lock screen is handing over. Its window disappears in one frame (no close
     * transition), so Home must arrive on its very first frame: play at the resume instead of waiting for
     * window focus or [FOCUS_FALLBACK_MS] (≈ 0.15–0.25 s of bare wallpaper between the lock screen and the icons).
     */
    internal var lockHandoff = false

    /** [UnlockArrival.keyguardDismissed]: the 18Duo lock screen's dismiss succeeded or it saw an unlock. */
    fun lockScreenHandoff(homeResumed: Boolean, homeFocused: Boolean): ArrivalCommand {
        if (phase == ArrivalPhase.ARMED) lockHandoff = true
        return userPresent(homeResumed, homeFocused || lockHandoff)
    }

    /**
     * [homeHidden]: Home's window is already out of sight — stopped behind the keyguard, or the panel
     * is no longer lit (no keyguard: the window stays resumed while the display sleeps).
     */
    fun screenOff(homeInFront: Boolean, homeHidden: Boolean): ArrivalCommand {
        if (!homeInFront) {
            // An app is in front: a half-finished arrival behind it has nothing left to show.
            if (phase == ArrivalPhase.PLAYING || phase == ArrivalPhase.PENDING) { phase = ArrivalPhase.REST; return ArrivalCommand.DISARM }
            return ArrivalCommand.NONE
        }
        if (phase == ArrivalPhase.ARMED) return ArrivalCommand.NONE
        return if (homeHidden) { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
        else { phase = ArrivalPhase.PENDING; ArrivalCommand.SCHEDULE_ARM }
    }

    /**
     * Home's window went away (onStop) after the screen went off with Home in front — it is behind the
     * keyguard now, so arming is invisible. (Broadcasts and lifecycle callbacks are often delivered
     * only once the device wakes again, so this does not look at whether the screen is lit.)
     */
    fun homeStopped(): ArrivalCommand =
        if (phase == ArrivalPhase.PENDING) { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
        else ArrivalCommand.NONE

    /**
     * Round 11 (HyperOS delivers no screen broadcasts to the launcher): Home's window stopped while
     * the keyguard is locked or the display is off — Home was in front and is now hidden behind the
     * lock, so arm right here. Stopped behind an app (display on, unlocked) while armed: the unlock
     * went into an app, rest invisibly.
     */
    fun homeStopped(homeInFront: Boolean, keyguardLocked: Boolean, displayOn: Boolean): ArrivalCommand = when (phase) {
        ArrivalPhase.PENDING -> { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
        ArrivalPhase.REST -> if (homeInFront && (keyguardLocked || !displayOn)) { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
            else ArrivalCommand.NONE
        ArrivalPhase.ARMED, ArrivalPhase.PLAYING -> if (!keyguardLocked && displayOn) { phase = ArrivalPhase.REST; ArrivalCommand.DISARM }
            else ArrivalCommand.NONE
    }

    /**
     * Home paused with the keyguard locked or the display going dark (the power key): the same as a
     * screen-off with Home in front — pending until the window is stopped or the arm deadline passes.
     */
    fun homePaused(homeInFront: Boolean, keyguardLocked: Boolean, displayOn: Boolean): ArrivalCommand =
        if (phase == ArrivalPhase.REST && homeInFront && (keyguardLocked || !displayOn)) { phase = ArrivalPhase.PENDING; ArrivalCommand.SCHEDULE_ARM }
        else ArrivalCommand.NONE

    /** DisplayManager reported the default display off/dozing: the same as ACTION_SCREEN_OFF. */
    fun displayOff(homeInFront: Boolean, homeHidden: Boolean): ArrivalCommand = screenOff(homeInFront, homeHidden)

    /** KeyguardManager's locked-state listener (API 33+) reported unlocked: the same as USER_PRESENT. */
    fun keyguardUnlocked(homeResumed: Boolean, homeFocused: Boolean): ArrivalCommand = userPresent(homeResumed, homeFocused)

    /** The screen-off animation is over by now; arm if the panel is still dark or locked. */
    fun armDeadline(interactive: Boolean, keyguardLocked: Boolean): ArrivalCommand = when {
        phase != ArrivalPhase.PENDING -> ArrivalCommand.NONE
        interactive && !keyguardLocked -> { phase = ArrivalPhase.REST; ArrivalCommand.NONE }
        else -> { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
    }

    /**
     * Home gained window focus: the keyguard window is gone and Home is what the user sees. This is
     * the trigger — Home resumes while the keyguard is still fading out over it (the emulator keeps
     * Home's window hidden until then), so starting at resume lost the first third of the arrival.
     */
    fun homeFocused(homeResumed: Boolean): ArrivalCommand =
        if (phase == ArrivalPhase.ARMED && homeResumed) { phase = ArrivalPhase.PLAYING; ArrivalCommand.PLAY }
        else ArrivalCommand.NONE

    /** [FOCUS_FALLBACK_MS] after the resume, or when focus never comes (a dialog or overlay holds it). */
    fun focusFallback(homeResumed: Boolean): ArrivalCommand = homeFocused(homeResumed)

    /** Home is resumed: only after the keyguard started going away (Home is not show-when-locked). */
    fun homeResumed(focused: Boolean, keyguardLocked: Boolean = false): ArrivalCommand = when (phase) {
        // The screen came straight back (no lock yet): Home never left, nothing arrives.
        ArrivalPhase.PENDING -> { phase = ArrivalPhase.REST; ArrivalCommand.NONE }
        // Resumed under a still-locked keyguard (some OEMs do this): stay armed, the keyguard
        // listener, USER_PRESENT or window focus plays it once the lock is really gone.
        ArrivalPhase.ARMED -> when {
            keyguardLocked -> ArrivalCommand.NONE
            focused || lockHandoff -> { phase = ArrivalPhase.PLAYING; lockHandoff = false; ArrivalCommand.PLAY }
            else -> ArrivalCommand.SCHEDULE_FOCUS_FALLBACK
        }
        else -> ArrivalCommand.NONE
    }

    /**
     * ACTION_SCREEN_ON. A keyguard now covers Home: arm under it. No keyguard and Home never hidden
     * (lock "None", where the system delivers the screen-off only on wake): Home is already lit at
     * rest, and arming now would visibly pop — it stays at rest.
     */
    fun screenOn(keyguardLocked: Boolean, homeResumed: Boolean): ArrivalCommand = when (phase) {
        ArrivalPhase.PENDING -> if (keyguardLocked) { phase = ArrivalPhase.ARMED; ArrivalCommand.ARM }
            else { phase = ArrivalPhase.REST; ArrivalCommand.NONE }
        ArrivalPhase.ARMED -> if (!keyguardLocked && homeResumed) { phase = ArrivalPhase.PLAYING; ArrivalCommand.PLAY } else ArrivalCommand.NONE
        else -> ArrivalCommand.NONE
    }

    /** ACTION_USER_PRESENT, or the 18Duo lock screen reporting that the keyguard was dismissed. */
    fun userPresent(homeResumed: Boolean, homeFocused: Boolean): ArrivalCommand = when (phase) {
        ArrivalPhase.PENDING -> { phase = ArrivalPhase.REST; ArrivalCommand.NONE }
        ArrivalPhase.ARMED -> when {
            homeResumed && homeFocused -> { phase = ArrivalPhase.PLAYING; ArrivalCommand.PLAY }
            homeResumed -> ArrivalCommand.NONE // focus (or its fallback) starts it
            else -> ArrivalCommand.SCHEDULE_TIMEOUT
        }
        else -> ArrivalCommand.NONE
    }

    /** Unlocked, but Home did not come to the front (notification, camera, an app): rest invisibly. */
    fun presentTimeout(homeResumed: Boolean): ArrivalCommand =
        if (phase == ArrivalPhase.ARMED && !homeResumed) { phase = ArrivalPhase.REST; ArrivalCommand.DISARM }
        else ArrivalCommand.NONE

    fun touched(): ArrivalCommand = if (phase == ArrivalPhase.PLAYING) ArrivalCommand.FINISH_FAST else ArrivalCommand.NONE

    fun finished() { if (phase == ArrivalPhase.PLAYING) phase = ArrivalPhase.REST; lockHandoff = false }
}

/**
 * Whether Home was the visible screen when the display went to sleep. onPause usually runs before
 * ACTION_SCREEN_OFF is delivered, so a pause that happened while the device was already
 * non-interactive (going to sleep) within [SLEEP_PAUSE_WINDOW_MS] still counts as "Home in front".
 */
internal fun homeWasInFront(
    resumed: Boolean,
    pausedForSleepAtMs: Long?,
    nowMs: Long,
    appOpening: Boolean,
    standby: Boolean,
): Boolean {
    if (appOpening || standby) return false
    if (resumed) return true
    return pausedForSleepAtMs != null && nowMs - pausedForSleepAtMs in 0..SLEEP_PAUSE_WINDOW_MS
}

internal const val SLEEP_PAUSE_WINDOW_MS = 3_000L
/** Arm by now if Home's window was never stopped (lock set to None): the screen-off animation is over. */
internal const val ARM_DEADLINE_MS = 900L
/** After an unlock, Home has this long to come to the front before the arrival is dropped. */
internal const val PRESENT_TIMEOUT_MS = 1_500L
/**
 * After a resume without window focus, start this much later (× the system animator duration scale).
 * Home resumes as the keyguard starts to leave; it is fully revealed ≈0.2–0.25 s later and gains focus
 * ≈0.15 s after that (emulator, measured at 10× animator scale: resume, reveal +2.0 s, focus +4.0 s).
 * Starting at the resume lost a third of the arrival behind the keyguard; waiting for focus showed a bare
 * wallpaper first. 150 ms puts the first visible frames near the start of the spring — icons large and
 * barely opaque — while the lock screen is still fading on keyguards that reveal Home during their exit.
 */
internal const val FOCUS_FALLBACK_MS = 150L
