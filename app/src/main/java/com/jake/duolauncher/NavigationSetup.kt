package com.jake.duolauncher

internal enum class NavigationEnableResult { ENABLED, DEFAULT_HOME_REQUIRED, SYSTEM_MODE_REQUIRED }

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
