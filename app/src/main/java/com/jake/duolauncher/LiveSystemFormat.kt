package com.jake.duolauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Broadcasts after which the 12/24-hour choice (or the locale default it falls back to) may differ. */
internal val TIME_FORMAT_ACTIONS = listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_LOCALE_CHANGED)

/**
 * The system 12/24-hour setting, kept live. Toggling "Use 24-hour format" does not recreate the
 * activity, and the status time used to re-read it only when the minute ticked (or not at all
 * until a restart after a language change), so iOS-style "3:49" vs "15:49" lagged the system.
 */
@Composable
internal fun rememberIs24HourFormat(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val is24 = remember(context) { mutableStateOf(DateFormat.is24HourFormat(context)) }
    // Keyed on the configuration too: a locale change can flip the locale-default 12/24 h choice.
    DisposableEffect(context, configuration) {
        val update = { is24.value = DateFormat.is24HourFormat(context) }
        update()
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { update() } }
        val filter = IntentFilter().apply { TIME_FORMAT_ACTIONS.forEach(::addAction) }
        // System broadcasts still arrive with NOT_EXPORTED; nothing else may send us these.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { update() } }
        runCatching { context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer) }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
        }
    }
    return is24.value
}

/**
 * Decides whether an activity still shows the previous language. [expected] is the process-wide
 * configuration (Application resources, updated in place on every change, per-app locale
 * included); [actual] the activity's own. A mismatch is acted on once per [expected] value, so a
 * device that legitimately keeps them apart can never loop.
 */
internal class StaleLocaleGuard<T : Any> {
    private var attemptedFor: T? = null
    fun shouldRecreate(expected: T, actual: T): Boolean {
        if (expected == actual || expected == attemptedFor) return false
        attemptedFor = expected
        return true
    }
}

/**
 * Safety net for a system language change: the review saw Settings half in the new language
 * (uxText() reads Locale.getDefault()) and half in the old one (string resources, plus the
 * locale-default 24-hour clock) until 18Duo was force-stopped. On resume and on
 * ACTION_LOCALE_CHANGED, an activity whose resources lag the process configuration is recreated.
 */
internal object LiveLocale {
    private val guard = StaleLocaleGuard<LocaleList>()

    fun attach(activity: Activity, owner: LifecycleOwner) {
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { check(activity) } }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val filter = IntentFilter(Intent.ACTION_LOCALE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else activity.registerReceiver(receiver, filter)
            }
            override fun onResume(owner: LifecycleOwner) = check(activity)
            override fun onStop(owner: LifecycleOwner) { runCatching { activity.unregisterReceiver(receiver) } }
        })
    }

    private fun check(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || activity.isChangingConfigurations) return
        val expected = activity.applicationContext.resources.configuration.locales
        val actual = activity.resources.configuration.locales
        if (guard.shouldRecreate(expected, actual)) {
            android.util.Log.i("18Duo", "Recreating Home: resources still use $actual, process uses $expected")
            activity.recreate()
        }
    }
}
