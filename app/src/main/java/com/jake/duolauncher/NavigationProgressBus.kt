package com.jake.duolauncher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/**
 * Process-local hand-off between the launcher activity and the navigation overlay (both live in
 * our process). The overlay owns every gesture visual — card, switcher, home indicator — and only
 * needs to know whether Home is in front, whether its window is really on screen yet, and which
 * app Home just opened.
 */
internal object NavigationProgressBus {
    private val _homeVisible = MutableStateFlow(false)
    /** True between MainActivity.onResume and onPause. */
    val homeVisible: StateFlow<Boolean> get() = _homeVisible

    /** Same as [homeVisible]; read synchronously from touch handlers. */
    val launcherVisible: Boolean get() = _homeVisible.value

    /** Monotonic time Home last resumed, for the overlay's hand-back to the live launcher. */
    @Volatile var homeResumedAt = 0L
        private set
    /** Monotonic time Home committed its first frame after [homeResumedAt] (0: not yet). */
    @Volatile var homeDrawnAt = 0L
        private set
    /** Monotonic time Home's window gained focus after [homeResumedAt] — Android only focuses a window it shows (0: not yet). */
    @Volatile var homeFocusedAt = 0L
        private set

    /**
     * Round 18: Home's apps are loaded (LauncherModel left `loading`) and a frame showing them has been
     * committed (0: not yet in this process). A process HyperOS restarted (an update, memory reclaim)
     * resumes Home with an empty grid and Dock for ≈ 0.6 s; the overlay must not reveal or measure that.
     */
    @Volatile var homeContentDrawnAt = 0L
        private set
    @Volatile var homeContentLoaded = false
        private set

    fun homeContentReset() { homeContentLoaded = false; homeContentDrawnAt = 0L }

    /** UI thread: Home's apps are in its state from now on; [drawn] runs at its next committed frame. */
    fun homeContentLoaded(view: android.view.View) {
        if (homeContentLoaded) return
        homeContentLoaded = true
        LaunchIconRegistry.layoutChanged()
        view.viewTreeObserver.registerFrameCommitCallback {
            if (homeContentDrawnAt == 0L) homeContentDrawnAt = android.os.SystemClock.uptimeMillis()
            LaunchIconRegistry.layoutChanged()
        }
        view.invalidate()
    }

    /** Package of the app Home just launched (consumed by the overlay to style the home indicator). */
    @Volatile var launchedPackage: String? = null

    /**
     * The app Home or the switcher opened last, by launcher app id (component + profile) and with the
     * tile it grew out of: the gesture return flies back into that very tile, and a dual-app copy is
     * never taken for its parent (they share [launchedPackage]). Kept until the next launch.
     */
    @Volatile var lastLaunch: LaunchedApp? = null
        private set

    fun noteLaunched(appId: String, tile: ZoomBounds? = null, now: Long = android.os.SystemClock.uptimeMillis()) {
        LaunchedApp.of(appId, tile, now)?.let { lastLaunch = it }
    }

    /**
     * Round 12.1: a "first page" request served inside the launcher process while Home is in front.
     * Neither a Home intent nor the system Home action may be used for it: on HyperOS both run the
     * system's own app ↔ launcher transition over the launcher, which showed the last app's window for
     * two frames after a quick flick on Home (owner recording 2026-10-21 15:02, f136–f137).
     * Set by MainActivity while resumed; null otherwise.
     */
    @Volatile var inProcessHome: (() -> Unit)? = null

    /** True when Home is in front and took the request; false means the caller must fall back. */
    fun requestHomeInProcess(): Boolean {
        val handler = inProcessHome ?: return false
        if (!launcherVisible) return false
        android.os.Handler(android.os.Looper.getMainLooper()).post(handler)
        return true
    }

    fun homeResumed(now: Long) {
        // Cleared before the new resume time is published, so no reader pairs it with a stale signal.
        homeDrawnAt = 0L; homeFocusedAt = 0L
        homeResumedAt = now
        _homeVisible.value = true
    }

    /** Home's window drew (committed) a frame since it resumed. */
    fun homeDrawn(now: Long) { if (_homeVisible.value && homeDrawnAt == 0L) homeDrawnAt = now }

    /** Home's window gained input focus since it resumed: it is on screen. */
    fun homeFocused(now: Long) { if (_homeVisible.value && homeFocusedAt == 0L) homeFocusedAt = now }

    fun homePaused() {
        // The positions Home is left with are the ones a card swiped home later flies into.
        LaunchIconRegistry.publishIfDirty()
        homeDrawnAt = 0L; homeFocusedAt = 0L
        _homeVisible.value = false
    }

    /** The live Home is really on screen now, so the overlay may stop covering it (see [homeOnScreen]). */
    fun homeOnScreen(now: Long): Boolean = homeOnScreen(now, launcherVisible, homeResumedAt, homeDrawnAt, homeFocusedAt) &&
        homeContentShown(now, homeResumedAt, homeContentDrawnAt)
}

/**
 * Round 18 (owner recording 2026-10-22 12:20, after a reinstall): the live Home may be on screen but
 * still empty — its apps load ≈ 0.6 s after a cold start. It counts as shown once a frame with its apps
 * has been committed (and a compositor frame passed), or after a long fallback.
 */
internal fun homeContentShown(now: Long, resumedAt: Long, contentDrawnAt: Long): Boolean =
    contentDrawnAt > 0L && now - contentDrawnAt >= HOME_FOCUS_SETTLE_MS || now - resumedAt >= HOME_CONTENT_FALLBACK_MS

/**
 * Whether the overlay may reveal the live Home under its frozen copy (owner feedback round 3,
 * item 4: Home "flashed" after a swipe up). Resuming is not enough: Home's first frame after a
 * resume came 200–600 ms later, and until Android shows that window the screen under the overlay
 * is the app that is leaving, black, or a half-finished system transition. Revealing it then
 * dimmed the whole screen by up to 24 % for ~200 ms. Android gives a window focus only once it is
 * visible, so focus (plus a frame for the compositor) is the signal; a frame Home committed is a
 * slower fallback (a system dialog may hold focus), and a long timeout the last resort.
 */
internal fun homeOnScreen(now: Long, launcherVisible: Boolean, resumedAt: Long, drawnAt: Long, focusedAt: Long): Boolean {
    if (!launcherVisible) return false
    val focused = focusedAt >= resumedAt && focusedAt > 0L
    val drawn = drawnAt >= resumedAt && drawnAt > 0L
    return when {
        // Round 12: focus alone was not enough either — a window can be focused before its first
        // frame since the resume has reached the compositor (HyperOS gives focus early), and the
        // reveal then showed the leaving app or black for a frame ("屏幕有时候会闪一下"). Both signals,
        // then one compositor frame.
        focused && drawn -> now - max(focusedAt, drawnAt) >= HOME_FOCUS_SETTLE_MS
        // Focused but no committed frame yet: a frame is normally a few ms away; a bounded wait.
        focused -> now - focusedAt >= HOME_FOCUS_ONLY_FALLBACK_MS
        drawn -> now - drawnAt >= HOME_DRAWN_FALLBACK_MS
        else -> now - resumedAt >= HOME_RESUME_FALLBACK_MS
    }
}

/** One frame for the compositor to show what the focused window drew. */
internal const val HOME_FOCUS_SETTLE_MS = 24L
/** Focused but no frame committed since the resume: how long to wait for that frame before revealing anyway. */
internal const val HOME_FOCUS_ONLY_FALLBACK_MS = 200L
/** Drawn but never focused (another window holds focus): long enough for a system transition to end. */
internal const val HOME_DRAWN_FALLBACK_MS = 450L
internal const val HOME_RESUME_FALLBACK_MS = 1_500L
/** A Home whose apps never load (an error) is still revealed after this long. */
internal const val HOME_CONTENT_FALLBACK_MS = 2_000L
