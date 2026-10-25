package com.jake.duolauncher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process

/**
 * When the navigation service photographs the app in front without a gesture (pure, JVM-tested).
 *
 * Why (round 8): until Core.24 an app only got a snapshot when the user swiped up *from it*, and
 * every snapshot was dropped at each screen-off, so after a lock nearly every switcher card was a
 * logo. iOS shows every app as it was left. Android gives a launcher no "app went to background"
 * frame, so the app is photographed once it has settled in front ([SETTLE_MS] after its window
 * appeared: its own launch animation is over) and again at most every [RECAPTURE_MS] while it
 * stays there — never while anything of the system or of 18Duo is drawn over it.
 */
internal object AutoCapturePolicy {
    const val SETTLE_MS = 650L
    const val RECAPTURE_MS = 8_000L
    /** Battery Saver: only the one capture after the app settled. */
    const val POWER_SAVE_RECAPTURE_MS = Long.MAX_VALUE

    /**
     * @param windowAt when the app's window became the one in front (null: Home or unknown).
     * @param chromeAt when a system window (shade, dialog, heads-up) or one of 18Duo's own sheets last
     *   appeared; one that appeared after [windowAt] may still cover the app, so nothing is taken
     *   until the app's next window event.
     * @param lastShotAt the last automatic capture of this same window, if any.
     * @param clear the screen is on and unlocked, Home is not in front, the stage draws nothing over
     *   the app, and the service may take screenshots.
     */
    fun due(now: Long, windowAt: Long?, chromeAt: Long, lastShotAt: Long?, clear: Boolean, powerSave: Boolean): Boolean {
        if (!clear || windowAt == null) return false
        if (chromeAt >= windowAt) return false
        if (now - windowAt < SETTLE_MS) return false
        val every = if (powerSave) POWER_SAVE_RECAPTURE_MS else RECAPTURE_MS
        return lastShotAt == null || now - lastShotAt >= every
    }

    /** A system or own (non-Home) window: something that may lie over the app and must not end up in its snapshot. */
    fun coversApp(packageName: String?, className: String?, selfPackage: String, homeClass: String): Boolean =
        packageName != null && (isSystemChrome(packageName) || packageName == selfPackage && className != homeClass)

    /**
     * A snapshot is written to disk when it is the first of its window or the last write of it is
     * this old; later ones stay in memory until the app leaves the front or the screen goes off.
     */
    const val PERSIST_EVERY_MS = 60_000L

    fun persistNow(firstOfWindow: Boolean, now: Long, lastPersistAt: Long?): Boolean =
        firstOfWindow || lastPersistAt == null || now - lastPersistAt >= PERSIST_EVERY_MS
}

/**
 * The snapshot key of the app whose window is [window], when it is certain enough to file a
 * capture under it (the rules of [resolveForeground]); null otherwise. Blocking (usage events,
 * LauncherApps): worker thread only.
 */
internal fun backgroundSnapshotKey(context: Context, window: WindowSample, usageAccess: Boolean): String? {
    val pkg = window.packageName ?: return null
    val usage = if (!usageAccess) null else runCatching {
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - USAGE_LOOKBACK_MS, now + 1_000L)
        val event = UsageEvents.Event()
        val samples = ArrayList<UsageSample>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type == USAGE_RESUMED || type == USAGE_PAUSED || type == USAGE_STOPPED || type == USAGE_DESTROYED)
                samples += UsageSample(type, event.packageName, event.className, event.timeStamp)
        }
        usageForeground(samples, context.packageName)
        // Nothing of this user resumed yet (the event is not written yet, or the task was only
        // brought forward): no evidence either way, so decide as without usage access.
    }.getOrNull()?.takeIf { it.packageName != null }
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val me = Process.myUserHandle()
    val guess = resolveForeground(usage, window, context.packageName) { candidate ->
        runCatching { launcherApps.profiles.any { it != me && launcherApps.getActivityList(candidate, it).isNotEmpty() } }
            .getOrDefault(true)
    }
    return if (guess.fileable && guess.packageName == pkg) snapshotKey(pkg, me.hashCode()) else null
}

/** The app just came to front, so its RESUMED event is recent; a short look back keeps the query cheap. */
private const val USAGE_LOOKBACK_MS = 10L * 60L * 1000L

/** One live window as the accessibility window list reports it (pure copy of AccessibilityWindowInfo). */
internal data class WindowLine(
    val type: Int,
    val layer: Int,
    /** Package of the window's content (null when Android did not say). */
    val packageName: String?,
    val title: String?,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
) {
    fun intersects(o: WindowLine): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom

    companion object {
        // Mirrors android.view.accessibility.AccessibilityWindowInfo.TYPE_*.
        const val APPLICATION = 1
        const val INPUT_METHOD = 2
        const val SYSTEM = 3
        const val ACCESSIBILITY_OVERLAY = 4
        const val SPLIT_SCREEN_DIVIDER = 5
        const val MAGNIFICATION_OVERLAY = 6
    }
}

/** What the live window list says about photographing [CaptureWindows] the app in front right now. */
internal enum class CaptureWindows {
    /** The target app is the top application window and nothing foreign lies over it. */
    CLEAR,
    /** A dialog, toast, floating window, another app or a system panel intersects it. */
    COVERED,
    /** Its window is not the top application window (another app, a system dialog activity). */
    NOT_IN_FRONT,
    /** No window list (the service cannot read windows): decide with [AutoCapturePolicy] alone. */
    UNKNOWN,
}

/**
 * Capture-time check against the live window list (pure, JVM-tested): a background snapshot is only
 * taken when [target]'s window is the topmost application window and every window above it that
 * intersects it is the status bar, the navigation bar / taskbar, the keyboard or one of 18Duo's own
 * overlays. Permission dialogs, "built for an older version of Android" dialogs, HyperOS floating
 * windows, toasts and the notification shade all refuse the capture.
 */
internal fun captureWindows(windows: List<WindowLine>, target: String, selfPackage: String,
    screenWidth: Int, screenHeight: Int): CaptureWindows {
    if (windows.isEmpty()) return CaptureWindows.UNKNOWN
    val byLayer = windows.sortedByDescending { it.layer }
    val app = byLayer.firstOrNull { it.type == WindowLine.APPLICATION } ?: return CaptureWindows.NOT_IN_FRONT
    if (app.packageName != target) return CaptureWindows.NOT_IN_FRONT
    for (w in byLayer) {
        if (w === app) break
        if (!w.intersects(app) || harmlessOverApp(w, selfPackage, screenWidth, screenHeight)) continue
        return CaptureWindows.COVERED
    }
    return CaptureWindows.CLEAR
}

private fun harmlessOverApp(w: WindowLine, selfPackage: String, screenWidth: Int, screenHeight: Int): Boolean = when (w.type) {
    // The keyboard is part of what the user was doing in the app (iOS snapshots keep it too).
    WindowLine.INPUT_METHOD -> true
    WindowLine.ACCESSIBILITY_OVERLAY -> w.packageName == selfPackage || w.title?.startsWith("DuoNavigation") == true
    WindowLine.SYSTEM -> isSystemBar(w, screenWidth, screenHeight)
    else -> false
}

/**
 * The status bar, navigation bar or taskbar: by title, or by shape — a strip spanning the panel's
 * width (or height) and touching its edge, at most 9 % thick. A toast or a dialog is neither.
 */
internal fun isSystemBar(w: WindowLine, screenWidth: Int, screenHeight: Int): Boolean {
    val title = w.title.orEmpty()
    if (SYSTEM_BAR_TITLES.any { title.startsWith(it, ignoreCase = true) }) return true
    val width = w.right - w.left; val height = w.bottom - w.top
    val horizontal = width >= screenWidth * .98f && height <= screenHeight * .09f && (w.top <= 0 || w.bottom >= screenHeight)
    val vertical = height >= screenHeight * .98f && width <= screenWidth * .09f && (w.left <= 0 || w.right >= screenWidth)
    return horizontal || vertical
}

private val SYSTEM_BAR_TITLES = listOf("StatusBar", "NavigationBar", "Taskbar", "ScreenDecorOverlay", "RoundCorner")
