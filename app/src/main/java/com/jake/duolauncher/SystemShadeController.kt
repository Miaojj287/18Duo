package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import java.lang.ref.WeakReference

internal enum class ShadePanel { NOTIFICATIONS, QUICK_SETTINGS }

internal enum class ShadeOpenResult { OPENED, SERVICE_DISABLED, SERVICE_STARTING, ACTION_REJECTED }

/**
 * The platform exposes shade expansion to third-party apps only as accessibility global actions.
 * This service cannot inspect windows or inject gestures. It observes one event type, window-state
 * changes, and reads only their package/class names (never text or content) to know which app is
 * in front. Its one screen-content capability is screenshots of the app in front — when the user
 * starts a bottom gesture, and once an app has settled — which the iOS-style app card and switcher
 * need (see [NavigationOverlay], [AutoCapturePolicy], PRIVACY.md).
 */
class SystemShadeAccessibilityService : AccessibilityService() {
    private var navigation: NavigationOverlay? = null
    private var wireless: WirelessAdbSetup? = null
    private var appleShade: AppleShadeOverlay? = null
    override fun onServiceConnected() {
        // The declaration lets Settings describe the service. Once bound, listen only to
        // window-state changes of all apps: which app's window is in front, nothing else.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = null
            // Lets a background snapshot check which windows lie over the app (captureWindows) — only
            // effective where Android grants window access to this service; harmless otherwise.
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // A service enabled before the screenshot capability was declared may need re-enabling.
        SnapshotCapability.update(this, serviceInfo.capabilities and
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0)
        instance = WeakReference(this)
        wireless?.close(); wireless = null
        navigation?.stop()
        navigation = NavigationOverlay(this).also { it.start() }
        updateAppleShade()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        appleShade?.hideIfLocked()
        appleShade?.onWindow(event.packageName?.toString(), event.className?.toString())
        navigation?.onWindowStateChanged(event.packageName?.toString(), event.className?.toString())
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        appleShade?.stop(); appleShade = null
        wireless?.close(); wireless = null
        // The service is being turned off: its app snapshots are deleted with it.
        navigation?.stop(forget = true); navigation = null
        if (instance.get() === this) instance.clear()
        super.onDestroy()
    }

    private fun updateAppleShade() {
        if (AppleShadeSettings.enabled(this)) {
            if (appleShade == null) appleShade = AppleShadeOverlay(this).also { it.start() }
        } else {
            appleShade?.stop()
            appleShade = null
        }
    }

    companion object {
        private var instance = WeakReference<SystemShadeAccessibilityService>(null)
        internal fun isConnected() = instance.get() != null
        internal fun refreshAppleShade() { instance.get()?.updateAppleShade() }
        internal fun showNavigationRecovery(): Boolean {
            val overlay = instance.get()?.navigation ?: return false
            overlay.showRecovery()
            return true
        }

        /** The system Recents, kept reachable for diagnostics; the iOS switcher replaces it in normal use. */
        internal fun openSystemRecents(): Boolean =
            instance.get()?.performGlobalAction(GLOBAL_ACTION_RECENTS) == true

        /** True when the overlay can draw the icon → app zoom now (stage attached and idle, motion on). */
        internal fun canAnimateLaunch(): Boolean = instance.get()?.navigation?.canAnimateLaunch() == true

        /**
         * Starts the overlay's icon → app zoom; false if the caller should use the in-launcher
         * fallback. Otherwise [onStarted] follows on the main thread (see NavigationOverlay.launchFromIcon).
         */
        internal fun animateLaunch(iconScreenPx: ZoomBounds, app: AppEntry, home: HomeSnapshot?,
            widget: WidgetLaunchTiles.Launch? = null, onStarted: (Boolean) -> Unit): Boolean {
            val service = instance.get() ?: return false
            val overlay = service.navigation ?: return false
            // Only the cached launch colour here: resolving it reads another package's theme (slow at tap time).
            val splash = if (app.isWork) null else SplashColors.forPackage(app.packageName)
            // The launch card should match the launch screen that follows it, so the real splash colour wins.
            val task = RecentTask(app.id, app.packageName, app.component, app.user, app.label, app.icon,
                splash ?: launchPlaceholderColor(null, app.icon))
            // From a widget the card starts as the widget's own pixels, at its corner radius.
            val art = widget?.art
            return overlay.launchFromIcon(iconScreenPx, task, art ?: app.icon, home, onStarted,
                widgetCornerPx = if (art != null) widget?.cornerRadiusPx else null)
        }

        /** The icon-less Home behind a running launch zoom (see [NavigationStage.setLaunchBackdrop]). */
        internal fun updateLaunchBackdrop(home: HomeSnapshot) { instance.get()?.navigation?.setLaunchBackdrop(home) }

        internal fun cancelLaunch() { instance.get()?.navigation?.cancelLaunch() }

        internal fun showWirelessSetup(): Boolean {
            val service = instance.get() ?: return false
            service.wireless?.close()
            service.wireless = WirelessAdbSetup(service) { service.navigation?.enableFromSetup() }.also { it.show() }
            return true
        }

        internal fun open(context: Context, panel: ShadePanel): ShadeOpenResult {
            val service = instance.get()
            // With the Apple panels on, Home's top-edge swipe opens them rather than Xiaomi's shade.
            if (service?.appleShade?.open(panel) == true) return ShadeOpenResult.OPENED
            if (service != null) {
                val action = when (panel) {
                    ShadePanel.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
                    ShadePanel.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
                }
                return if (service.performGlobalAction(action)) ShadeOpenResult.OPENED
                else ShadeOpenResult.ACTION_REJECTED
            }
            return if (isEnabled(context)) ShadeOpenResult.SERVICE_STARTING
            else ShadeOpenResult.SERVICE_DISABLED
        }

        internal fun enableNavigationFromSetup(): Boolean {
            val overlay = instance.get()?.navigation ?: return false
            overlay.enableFromSetup()
            return true
        }

        internal fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, SystemShadeAccessibilityService::class.java)
            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any {
                val service = it.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(service.packageName, service.name) == component
            }
        }
    }
}
