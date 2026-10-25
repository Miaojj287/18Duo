package com.jake.duolauncher

import android.app.Application
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.view.Display
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/** The lock screen's own settings (separate file: no format change for CorePreferences). */
internal class LockScreenPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("duo18_lock_screen", Context.MODE_PRIVATE)
    /** Default OFF: see docs/uiux-core17-r8-lock.md "默认关闭的原因". */
    val enabled: Boolean get() = prefs.getBoolean(ENABLED, false)
    val unseen: Int get() = prefs.getInt(UNSEEN, 0)
    val pending: Boolean get() = prefs.getBoolean(PENDING, false)
    /** When the pending launch was issued (round 12: a retry inside the grace does not count it unseen). */
    val pendingSince: Long get() = prefs.getLong(PENDING_SINCE, 0L)
    val autoDisabled: Boolean get() = prefs.getBoolean(AUTO_DISABLED, false)
    /** The customisation (round 11), one JSON string; the launcher's layout storage is untouched. */
    var config: LockScreenConfig
        get() = LockScreenConfig.fromJson(prefs.getString(CONFIG, null))
        set(value) { prefs.edit().putString(CONFIG, value.toJson()).apply() }
    /** Round 12 diagnostics ring: the last 8 launch decisions, oldest first. */
    var launchLog: List<LockLaunchEvent>
        get() = LockLaunchRing.decode(prefs.getString(LAUNCH_LOG, null))
        set(value) {
            prefs.edit().putString(LAUNCH_LOG, LockLaunchRing.encode(value)).apply()
            LockScreenController.lastEvent.value = value.lastOrNull()
        }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(ENABLED, value).putInt(UNSEEN, 0).putBoolean(PENDING, false)
            .putBoolean(AUTO_DISABLED, false).apply()
        LockScreenController.enabled.value = value
        LockScreenController.autoDisabled.value = false
        Log.i(TAG, "Lock screen ${if (value) "turned on" else "turned off"} in Settings")
    }
    /** Appends one decision to the ring (and to the log). */
    fun record(event: LockLaunchEvent) {
        Log.i(TAG, event.logLine())
        launchLog = LockLaunchRing.add(launchLog, event)
    }
    /**
     * Called right before a launch; returns false when the launch must not happen (feature turned
     * itself off). A launch still pending after [LockScreenHealth.PENDING_GRACE_MS] counts as unseen;
     * a retry inside the grace leaves the earlier launch's mark in place.
     */
    fun markLaunch(now: Long = System.currentTimeMillis()): Boolean {
        val wasPending = pending
        val age = if (wasPending) now - pendingSince else 0L
        val unseen = LockScreenHealth.afterLaunch(wasPending, unseen, age)
        if (unseen != this.unseen) launchLog = LockLaunchRing.markUnseen(launchLog)
        if (!LockScreenHealth.healthy(unseen)) {
            prefs.edit().putBoolean(ENABLED, false).putBoolean(AUTO_DISABLED, true).putInt(UNSEEN, 0)
                .putBoolean(PENDING, false).apply()
            LockScreenController.enabled.value = false
            LockScreenController.autoDisabled.value = true
            Log.w(TAG, "Lock screen was not seen $unseen times in a row; turned off")
            return false
        }
        val edit = prefs.edit().putInt(UNSEEN, unseen).putBoolean(PENDING, true)
        if (!wasPending || age >= LockScreenHealth.PENDING_GRACE_MS) edit.putLong(PENDING_SINCE, now)
        edit.apply()
        return true
    }
    /** The activity resumed over the keyguard, drew a frame or finished normally: the launch path works. */
    fun markSeen(how: String) {
        if (!pending && unseen == 0) return
        prefs.edit().putBoolean(PENDING, false).putInt(UNSEEN, 0).apply()
        launchLog = LockLaunchRing.markSeen(launchLog, System.currentTimeMillis(), how)
        Log.i(TAG, "Lock screen seen ($how)")
    }
    private companion object {
        const val ENABLED = "enabled"
        const val UNSEEN = "unseen_launches"
        const val PENDING = "launch_pending"
        const val PENDING_SINCE = "launch_pending_since"
        const val AUTO_DISABLED = "auto_disabled"
        const val CONFIG = "config"
        const val LAUNCH_LOG = "launch_log"
    }
}

/**
 * Puts the 18Duo lock screen up when the screen goes off and the keyguard is (or becomes) locked.
 * Lives in the launcher process (registered from [DuoApplication]); being the default Home lets it
 * start an activity from the background (Android's "home app" background-start exemption; HyperOS
 * additionally needs "Show on lock screen" and "Display pop-up windows while running in background").
 * It never dismisses or replaces the keyguard: the activity only shows *over* it (showWhenLocked).
 *
 * Round 12: the owner kept waking to the system keyguard. The screen-off checks alone missed every
 * launch that was late, swallowed, or pre-empted by a wake within the check delays, and the keyguard
 * that locks seconds after the screen goes dark. Now every display-ON and every keyguard-locked event
 * also *catches up*: if the keyguard is locked and our activity is not showing, launch at once and
 * again [CATCH_UP_RETRY_MS] later; each decision is logged and kept in [LockScreenPreferences.launchLog].
 */
internal object LockScreenController {
    val enabled = mutableStateOf(false)
    val autoDisabled = mutableStateOf(false)
    /** True while a LockScreenActivity exists; also tells Home that the lock screen still covers it. */
    val showing = mutableStateOf(false)
    /** Newest diagnostics entry (Settings' "上次：…" line). */
    val lastEvent = mutableStateOf<LockLaunchEvent?>(null)
    /** Last HyperOS app-ops read (Settings re-reads on every resume). */
    var permissionsSnapshot: LockPermissionState = LockPermissionState.NOT_APPLICABLE
    /** Second attempt of a catch-up when the first has not produced an activity yet. */
    const val CATCH_UP_RETRY_MS = 300L
    private val handler = Handler(Looper.getMainLooper())
    private var installed = false
    private var screenOffEpoch = 0

    fun install(app: Application) {
        if (installed) return
        installed = true
        val prefs = LockScreenPreferences(app)
        enabled.value = prefs.enabled
        autoDisabled.value = prefs.autoDisabled
        lastEvent.value = prefs.launchLog.lastOrNull()
        // Round 11: HyperOS never delivered ACTION_SCREEN_OFF/ON/USER_PRESENT to the launcher (phone
        // logcat 2026-10-21), so the display's own state and the keyguard's locked-state listener are
        // the primary triggers; the broadcasts stay as an extra source (exported: only the system can
        // send these protected actions).
        runCatching {
            val displays = app.getSystemService(DisplayManager::class.java)
            displayOn = displays?.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
            displays?.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId != Display.DEFAULT_DISPLAY) return
                    val state = displays.getDisplay(displayId)?.state ?: return
                    val on = state == Display.STATE_ON
                    if (on == displayOn) return
                    displayOn = on
                    if (!on) scheduleChecks(app, "display off")
                    else { screenOffEpoch++; catchUp(app, "display on") }
                }
            }, handler)
        }
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            app.getSystemService(KeyguardManager::class.java)?.addKeyguardLockedStateListener(ContextCompat.getMainExecutor(app)) { locked ->
                if (!locked) { screenOffEpoch++; return@addKeyguardLockedStateListener }
                // The keyguard locked after the "lock after timeout" grace while the panel is dark, or at
                // once on the power key: the checks fire now (delay 0) and keep re-checking. Locked while
                // the screen is on (manual lock, a wake that found it locked): catch up immediately.
                if (!app.getSystemService(PowerManager::class.java).isInteractive) scheduleChecks(app, "keyguard locked (dark)")
                else catchUp(app, "keyguard locked")
            }
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> scheduleChecks(app, "screen off broadcast")
                    Intent.ACTION_SCREEN_ON -> { screenOffEpoch++; catchUp(app, "screen on broadcast") }
                    Intent.ACTION_USER_PRESENT -> screenOffEpoch++
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        permissionsSnapshot = LockScreenPermissions.read(app)
        Log.i(TAG, "Controller installed enabled=${prefs.enabled} displayOn=$displayOn ${permissionsSnapshot.logNote()}")
    }

    private var displayOn = true

    /** The panel went dark: re-check at the screen-off delays until the keyguard locks or the panel wakes. */
    private fun scheduleChecks(context: Context, trigger: String) {
        val epoch = ++screenOffEpoch
        if (!LockScreenPreferences(context).enabled) return
        Log.i(TAG, "Screen-off checks scheduled ($trigger)")
        for (delay in LOCK_SCREEN_CHECK_DELAYS_MS) handler.postDelayed({
            if (epoch == screenOffEpoch && !context.getSystemService(PowerManager::class.java).isInteractive) {
                if (launchIfNeeded(context, "$trigger +${delay}ms")) screenOffEpoch++
            }
        }, delay)
    }

    /**
     * The panel is on (or the keyguard just locked over a lit panel): if the keyguard is locked and our
     * activity is not up, launch now, and once more after [CATCH_UP_RETRY_MS] if it still is not.
     */
    private fun catchUp(context: Context, trigger: String) {
        if (!LockScreenPreferences(context).enabled) return
        launchIfNeeded(context, trigger)
        handler.postDelayed({ if (!showing.value) launchIfNeeded(context, "$trigger +${CATCH_UP_RETRY_MS}ms") }, CATCH_UP_RETRY_MS)
    }

    /** Returns true when the lock screen is (now) up. Every decision is logged and kept in the ring. */
    private fun launchIfNeeded(context: Context, trigger: String): Boolean {
        val prefs = LockScreenPreferences(context)
        val keyguardLocked = runCatching { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
        val interactive = runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        val state = LockLaunchState(
            enabled = prefs.enabled,
            isDefaultHome = isDefaultHome(context),
            keyguardLocked = keyguardLocked,
            standbyClockVisible = StandbyMonitorService.clockVisible.value,
            callOrAlarm = callOrAlarm(context),
            alreadyShowing = showing.value,
            healthy = true,
        )
        fun event(result: LockLaunchResult, detail: String = "") =
            LockLaunchEvent(System.currentTimeMillis(), trigger, result, keyguardLocked, interactive, detail)
        val blocker = lockLaunchBlocker(state)
        if (blocker != null) {
            // "Already showing" and "off" are not worth a ring entry; everything else is.
            if (blocker == LockLaunchResult.ALREADY_SHOWING || blocker == LockLaunchResult.DISABLED) Log.i(TAG, event(blocker).logLine())
            else prefs.record(event(blocker))
            return blocker == LockLaunchResult.ALREADY_SHOWING
        }
        val ops = LockScreenPermissions.read(context)
        if (!prefs.markLaunch()) { prefs.record(event(LockLaunchResult.UNHEALTHY, ops.logNote())); return false }
        return try {
            context.startActivity(Intent(context, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            prefs.record(event(LockLaunchResult.LAUNCHED, ops.logNote()))
            true
        } catch (error: RuntimeException) {
            prefs.record(event(LockLaunchResult.REJECTED, listOf(error.toString().take(120), ops.logNote()).filter { it.isNotEmpty() }.joinToString("; ")))
            false
        }
    }

    internal fun isDefaultHome(context: Context): Boolean =
        runCatching { context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(false)

    private fun callOrAlarm(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        if (audio.mode == AudioManager.MODE_RINGTONE || audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION || audio.mode == AudioManager.MODE_CALL_SCREENING) return true
        return runCatching {
            audio.activePlaybackConfigurations.any {
                val usage = it.audioAttributes.usage
                usage == AudioAttributes.USAGE_ALARM || usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            }
        }.getOrDefault(false)
    }
}

/**
 * Reads HyperOS's two lock-screen app-ops for this package ([HyperOsLockOps]). The ids are MIUI's, not
 * in the public API, so the read goes through reflection on `AppOpsManager.checkOpNoThrow(int, int,
 * String)` first and `unsafeCheckOpNoThrow(opToPublicName(id), …)` second; anything that throws (hidden
 * API policy, a missing op) leaves that op [LockOpState.UNKNOWN] — Settings then shows ？ and asks the
 * owner to confirm in HyperOS. Off Xiaomi the state is [LockPermissionState.NOT_APPLICABLE].
 */
internal object LockScreenPermissions {
    fun read(context: Context): LockPermissionState {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return LockPermissionState.NOT_APPLICABLE
        val ops = runCatching { context.getSystemService(AppOpsManager::class.java) }.getOrNull()
            ?: return LockPermissionState.unreadable()
        return LockPermissionState(true,
            HyperOsLockOps.fromMode(mode(ops, HyperOsLockOps.OP_SHOW_WHEN_LOCKED, context.packageName)),
            HyperOsLockOps.fromMode(mode(ops, HyperOsLockOps.OP_BACKGROUND_START_ACTIVITY, context.packageName)))
    }

    private fun mode(ops: AppOpsManager, op: Int, packageName: String): Int? {
        val uid = Process.myUid()
        runCatching {
            val check = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, String::class.java)
            return check.invoke(ops, op, uid, packageName) as Int
        }.onFailure { Log.i(TAG, "checkOpNoThrow($op) unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val name = AppOpsManager::class.java.getMethod("opToPublicName", Int::class.javaPrimitiveType).invoke(null, op) as String
            return ops.unsafeCheckOpNoThrow(name, uid, packageName)
        }.onFailure { Log.i(TAG, "opToPublicName($op) unavailable: ${it.javaClass.simpleName}") }
        return null
    }
}

private const val TAG = "18DuoLockScreen"
