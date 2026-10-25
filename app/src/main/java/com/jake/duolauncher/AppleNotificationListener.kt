package com.jake.duolauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/** In-memory only. 18Duo never stores notification titles or bodies on disk. */
class AppleNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = WeakReference(this)
        update()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = update()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = update()

    override fun onListenerDisconnected() {
        visible.set(emptyList())
        if (instance.get() === this) instance.clear()
        super.onListenerDisconnected()
    }

    private fun update() {
        if (!AppleShadeSettings.enabled(this)) {
            visible.set(emptyList())
            return
        }
        visible.set(runCatching { activeNotifications?.filter(::listed)?.sortedByDescending { it.postTime }?.take(30) ?: emptyList() }
            .getOrDefault(emptyList()))
    }

    /** Group summaries repeat their children, and an empty notification has nothing to show. */
    private fun listed(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = n.extras ?: return false
        return !extras.getCharSequence(android.app.Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(android.app.Notification.EXTRA_TEXT).isNullOrBlank()
    }

    companion object {
        private var instance = WeakReference<AppleNotificationListener>(null)
        private val visible = AtomicReference<List<StatusBarNotification>>(emptyList())
        internal fun snapshot(): List<StatusBarNotification> = visible.get()
        /** Swiped away in the Apple Notification Center: removed from the system shade too. */
        internal fun dismiss(key: String) {
            val listener = instance.get() ?: return
            runCatching { listener.cancelNotification(key) }
            listener.update()
        }
        /** "Clear" in the Notification Center: like the system, only clearable notifications go. */
        internal fun clearAll() {
            val listener = instance.get() ?: return
            runCatching { listener.cancelAllNotifications() }
            listener.update()
        }
        internal fun onShadeSettingChanged(enabled: Boolean) {
            if (enabled) instance.get()?.update() else visible.set(emptyList())
        }
    }
}
