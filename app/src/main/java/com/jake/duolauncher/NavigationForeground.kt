package com.jake.duolauncher

/*
 * Which app is in front when a bottom gesture starts. The answer decides which card the live
 * screenshot belongs to and under which app it may be remembered, so a wrong guess files one
 * app's pixels under another app (or another profile's copy of it). Two independent sources:
 *  - accessibility window-state events (package + class name only, never text or content) say
 *    which app's window just became the one the user looks at, in every profile;
 *  - usage events of *this* user say which of our activities are resumed right now.
 * A capture is filed only when both agree (or the only source available is unambiguous);
 * everything else is shown live during the gesture and then forgotten.
 */

/** UsageEvents.Event types used here (DESTROYED is hidden in the SDK but delivered). */
internal const val USAGE_RESUMED = 1
internal const val USAGE_PAUSED = 2
internal const val USAGE_STOPPED = 23
internal const val USAGE_DESTROYED = 24

/** One activity lifecycle event from usage stats, in event order. */
internal data class UsageSample(val type: Int, val packageName: String, val className: String?, val time: Long)

/**
 * The newest resumed package of this user, resumed at wall-clock [time]; [ambiguous] when another
 * package is resumed too (floating window, split screen).
 */
internal data class UsageForeground(val packageName: String?, val ambiguous: Boolean, val time: Long = 0L)

/**
 * RESUMED adds an activity, PAUSED/STOPPED/DESTROYED removes it. Home resuming full screen ends
 * everything resumed before it, so an app that died without a PAUSED event cannot stay "in front".
 */
internal fun usageForeground(events: List<UsageSample>, homePackage: String): UsageForeground {
    val resumed = LinkedHashMap<String, UsageSample>()
    for (e in events) {
        val key = e.packageName + "/" + e.className.orEmpty()
        when (e.type) {
            USAGE_RESUMED -> {
                if (e.packageName == homePackage) resumed.clear()
                resumed.remove(key); resumed[key] = e
            }
            USAGE_PAUSED, USAGE_STOPPED, USAGE_DESTROYED -> resumed.remove(key)
        }
    }
    val newest = resumed.values.maxByOrNull { it.time } ?: return UsageForeground(null, false)
    return UsageForeground(newest.packageName, resumed.values.any { it.packageName != newest.packageName }, newest.time)
}

/** Who owns a window that just became active. */
internal enum class WindowOwner { HOME, APP, IGNORE }

/**
 * Packages whose windows never stand for "the app in front" (shade, system dialogs). HyperOS draws
 * its status bar, shade, Dynamic-Island-style notification capsule and keyguard dialogs from
 * `miui.systemui.plugin` (round 12.4: its window-state events had been filed as an app in front, so a
 * gesture leaving the real app aimed at "miui.systemui.plugin" — owner log 17:07:51 "hand-off to
 * live Home: miui.systemui.plugin window gone after 0 ms").
 */
internal val SYSTEM_CHROME_PACKAGES = setOf("com.android.systemui", "android", "com.miui.systemui", "miui.systemui.plugin")

/** [SYSTEM_CHROME_PACKAGES] and any other SystemUI plugin / overlay package of the OEM. */
internal fun isSystemChrome(packageName: String): Boolean =
    packageName in SYSTEM_CHROME_PACKAGES || packageName.startsWith("miui.systemui") || packageName.startsWith("com.miui.systemui")

internal fun classifyWindow(packageName: String?, className: String?, selfPackage: String, homeClass: String,
    inputMethods: Set<String>): WindowOwner = when {
    packageName.isNullOrEmpty() -> WindowOwner.IGNORE
    // Our own dialogs, sheets and StandBy are not Home; only the launcher activity is.
    packageName == selfPackage -> if (className == homeClass) WindowOwner.HOME else WindowOwner.IGNORE
    isSystemChrome(packageName) || packageName in inputMethods -> WindowOwner.IGNORE
    else -> WindowOwner.APP
}

/** The last window the user looked at, from window-state events at wall-clock [at]; [packageName] is null for Home. */
internal data class WindowSample(val packageName: String?, val home: Boolean, val at: Long)

/**
 * [packageName] = the app in front (null: Home or unknown). [fileable] = sure about both the app and
 * its profile, so its screenshot may be stored as that app's snapshot.
 */
internal data class ForegroundGuess(val packageName: String?, val home: Boolean, val fileable: Boolean) {
    companion object { val UNKNOWN = ForegroundGuess(null, home = false, fileable = false) }
}

/**
 * @param usage null without usage access.
 * @param window null before any window event arrived.
 * @param otherProfileHas whether [packageName] is also installed in another profile (work, Dual Apps):
 *   without usage events nothing tells the two copies apart, so such a capture is not filed.
 * @param launchedAfterWindow the package Home launched after [window] was sampled (Home is no longer
 *   in front, so that app is coming up even if its first window event has not arrived yet).
 */
internal fun resolveForeground(
    usage: UsageForeground?,
    window: WindowSample?,
    homePackage: String,
    launchedAfterWindow: String? = null,
    otherProfileHas: (String) -> Boolean,
): ForegroundGuess {
    // A usage resume clearly newer than the last window event means window events were missed.
    val windowStale = window != null && usage?.packageName != null && usage.time > window.at + STALE_WINDOW_MILLIS
    if (window != null && !windowStale) {
        // A swipe up right after a launch, before the app's window event: it is that app, not Home
        // (the card held 700 ms with no task and then fell to the middle of the screen).
        if (window.home) return if (launchedAfterWindow != null && launchedAfterWindow != homePackage)
            ForegroundGuess(launchedAfterWindow, home = false, fileable = false)
            else ForegroundGuess(null, home = true, fileable = false)
        val pkg = window.packageName ?: return ForegroundGuess.UNKNOWN
        return when {
            usage == null -> ForegroundGuess(pkg, home = false, fileable = !otherProfileHas(pkg))
            // Our user's copy is the resumed one: same app, same profile.
            usage.packageName == pkg -> ForegroundGuess(pkg, home = false, fileable = !usage.ambiguous)
            // The window is in front but our user's copy is not resumed: another profile, or a race.
            else -> ForegroundGuess(pkg, home = false, fileable = false)
        }
    }
    val pkg = usage?.packageName ?: return ForegroundGuess.UNKNOWN
    if (pkg == homePackage) return ForegroundGuess(null, home = true, fileable = false)
    return ForegroundGuess(pkg, home = false, fileable = !usage.ambiguous)
}

private const val STALE_WINDOW_MILLIS = 1_500L

/** Snapshot store key: one app in one profile ([userKey] = UserHandle hash, i.e. its user id). */
internal fun snapshotKey(packageName: String, userKey: Int): String = "$packageName#$userKey"
