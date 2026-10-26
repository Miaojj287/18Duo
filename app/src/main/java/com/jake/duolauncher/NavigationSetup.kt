package com.jake.duolauncher

internal enum class NavigationEnableResult { ENABLED, DEFAULT_HOME_REQUIRED, SYSTEM_MODE_REQUIRED }

/**
 * HyperOS/MIUI refuses gesture navigation while a third-party Home is the default (its own
 * 系统导航方式 page offers to switch back to the Xiaomi launcher), so on those systems 18Duo turns
 * gestures on itself after the one-time wireless-debugging grant.
 */
internal fun systemGesturesNeedGrant(context: android.content.Context): Boolean =
    runCatching { context.packageManager.getApplicationInfo("com.miui.home", 0); true }.getOrDefault(false)

/** Existing system gesture mode needs no privileged write. Never enable after a failed mode change. */
internal fun enableNavigation(
    defaultHome: Boolean,
    gestureMode: () -> Boolean,
    requestGestureMode: () -> Boolean,
    enableOverlay: () -> Unit,
    hideGestureLine: () -> Unit,
): NavigationEnableResult {
    if (!defaultHome) return NavigationEnableResult.DEFAULT_HOME_REQUIRED
    if (!gestureMode()) {
        if (!runCatching { requestGestureMode() }.getOrDefault(false) || !gestureMode())
            return NavigationEnableResult.SYSTEM_MODE_REQUIRED
    }
    enableOverlay()
    // Cosmetic and optional: inability to hide the pill must not block working navigation.
    runCatching { hideGestureLine() }
    return NavigationEnableResult.ENABLED
}
