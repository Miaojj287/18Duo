package com.jake.duolauncher

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context

/** The system shade replacement is opt-in and runs in 18Duo's existing accessibility service. */
internal object AppleShadeSettings {
    private const val PREFS = "apple_system_shade"
    private const val KEY_ENABLED = "enabled"

    /**
     * Round 22: the Apple status bar and pull-down shades were removed from Settings; the system's own
     * status bar, notifications and Control Center are used. A switch left on by an older build is ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        AppleNotificationListener.onShadeSettingChanged(enabled)
        SystemShadeAccessibilityService.refreshAppleShade()
    }

    fun notificationAccess(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
        ?.isNotificationListenerAccessGranted(ComponentName(context, AppleNotificationListener::class.java)) == true
}
