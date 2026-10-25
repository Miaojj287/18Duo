package com.jake.duolauncher

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

/** Foreground, user-controlled hinge monitoring. The helper may launch only the
 * dedicated clock while keyguard is locked; neither path dismisses keyguard. */
class StandbyMonitorService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var keyguard: KeyguardManager
    private lateinit var usb: UsbCoverArea
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var store: SharedPreferences
    private var preferences: CorePreferences? = null
    private val gate = LockedStandbyGate()
    private val stillness = StillnessDetector()
    private lateinit var power: PowerManager
    private var angle: Float? = null
    private var direction: StandbyDirection? = null
    private var gravityAt = -1L
    private var gravityRegistered = false
    private var hingeRegistered = false
    private var ticking = false
    private var requestedAt = -1L
    private var wasLocked = false
    private var notShown = false
    private var externalPower = false
    /** Own low-rate hinge registration (not the shared [HingeAngles] stream, which serves visible motion). */
    private var hingeSensor: Sensor? = null
    private var foldFallback: Sensor? = null
    private var hingeSelection = HingeAngleSelection()
    private val hingeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val direct = event.sensor == hingeSensor
            val value = event.values.getOrNull(if (direct) 0 else 1) ?: return
            angle = (hingeSelection.update(value, event.timestamp, direct) ?: return).degrees
            if (keyguard.isKeyguardLocked) wake.acquire(3000L)
            evaluate()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        preferences = CorePreferences(this)
        evaluate()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val plugged = standbyExternalPower(intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0))
                if (plugged == externalPower) return
                externalPower = plugged
            }
            if (intent.action == Intent.ACTION_SCREEN_OFF && clockVisible.value) dismissClock()
            evaluate()
        }
    }
    /** Runs only while the hinge is in the StandBy range on a locked or sleeping phone (see [standbyMonitorPlan]). */
    private val tick = object : Runnable {
        override fun run() { ticking = false; evaluate() }
    }
    override fun onCreate() {
        super.onCreate()
        keyguard = getSystemService(KeyguardManager::class.java)
        sensors = getSystemService(SensorManager::class.java)
        power = getSystemService(PowerManager::class.java)
        externalPower = standbyOnExternalPower(this)
        hingeSensor = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        foldFallback = if (android.os.Build.DEVICE == "lhasa") sensors.getSensorList(Sensor.TYPE_ALL)
            .firstOrNull { it.stringType == "xiaomi.sensor.fold_status" && it.isWakeUpSensor } else null
        wake = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "$packageName:standby-posture").apply { setReferenceCounted(false) }
        preferences = CorePreferences(this)
        store = getSharedPreferences("duo18_core_preferences", MODE_PRIVATE)
        store.registerOnSharedPreferenceChangeListener(preferenceListener)
        usb = UsbCoverArea(this) { evaluate() }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.standby_channel), NotificationManager.IMPORTANCE_LOW))
        val settings = PendingIntent.getActivity(this, 71, Intent(this, MainActivity::class.java)
            .putExtra("duo_destination", "settings"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 72, Intent(this, StandbyMonitorService::class.java)
            .setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(71, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_standby_notification)
            .setContentTitle(getString(R.string.standby_notification_title)).setContentText(getString(R.string.standby_notification_text))
            .setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.standby_notification_stop), stop).build()).build())
        running = true
        instance = this
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post { evaluate() }
        Log.i("18DuoLockClock", "Foreground posture monitor started")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { CorePreferences(this).setStandby(false); stopSelf() }
        return START_NOT_STICKY
    }
    private fun evaluate() {
        val prefs = preferences ?: return
        if (!prefs.standbyEnabled || prefs.standbyScene == StandbyScene.MANUAL_ONLY) {
            clockVisible.value = false; usb.stop(); stopSelf(); return
        }
        val locked = keyguard.isKeyguardLocked
        val interactive = power.isInteractive
        if (!locked) notShown = false
        val plan = standbyMonitorPlan(locked, interactive, prefs.standbyAngles.contains(angle))
        if (plan.hinge != hingeRegistered) {
            hingeRegistered = plan.hinge
            if (plan.hinge) {
                // An on-change sensor reports its current value on registration, so nothing is missed meanwhile.
                hingeSelection = HingeAngleSelection()
                hingeSensor?.let { sensors.registerListener(hingeListener, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler) }
                foldFallback?.let { sensors.registerListener(hingeListener, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler) }
            } else {
                sensors.unregisterListener(hingeListener)
                angle = null
            }
        }
        if (plan.gravity != gravityRegistered) {
            sensors.unregisterListener(this)
            gravityRegistered = plan.gravity
            direction = null; gravityAt = -1L; stillness.reset()
            if (plan.gravity) sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, 100_000, handler)
            }
        }
        if (plan.tick && !ticking) { ticking = true; handler.removeCallbacks(tick); handler.postDelayed(tick, 250L) }
        else if (!plan.tick && ticking) { ticking = false; handler.removeCallbacks(tick) }
        if (locked && !wasLocked) wake.acquire(3000L)
        wasLocked = locked
        val now = SystemClock.elapsedRealtime()
        // A hand-held tent never settles; only a set-down phone may summon the clock (iOS "stationary").
        // On battery the clock never wakes a sleeping screen by itself (Auto-Lock still applies, code review P1-3).
        val eligible = gate.update(locked, lockedClockMayShow(externalPower, interactive) || clockVisible.value,
            prefs.standbyAngles.contains(angle),
            direction != null && ((now - gravityAt in 0L..1500L && stillness.isStill(now)) || clockVisible.value ||
                (requestedAt >= 0L && now - requestedAt < 5000L)), now)
        launchAllowed = eligible
        if (eligible) {
            clockRotation.value = if (direction == StandbyDirection.RIGHT) -90f else 90f
            usb.configure(prefs.standbyAngles)
            usb.start()
            if (usb.available && !clockVisible.value && requestedAt < 0L) {
                requestedAt = now
                usb.requestLockedClock()
                Log.i("18DuoLockClock", "Locked clock requested hinge=$angle direction=$direction")
            }
            if (!clockVisible.value && requestedAt >= 0 && now - requestedAt > 8000L) {
                dismissClock()
                notShown = true
                status.intValue = R.string.standby_status_not_shown
                return
            }
            status.intValue = if (usb.available) R.string.standby_status_active
                else R.string.standby_status_waiting_usb
        } else {
            if (clockVisible.value) Log.i("18DuoLockClock", "Clock exited locked=$locked hinge=$angle")
            clockVisible.value = false
            usb.stop()
            requestedAt = -1L
            // A clock that failed to appear stays reported until unlock instead of reading "idle" 250 ms later.
            status.intValue = if (notShown) R.string.standby_status_not_shown else R.string.standby_status_idle
        }
    }
    private fun dismissClock() { launchAllowed = false; gate.dismiss(); clockVisible.value = false; usb.stop() }
    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return
        gravityAt = event.timestamp / 1_000_000L
        stillness.add(gravityAt, event.values[0], event.values[1], event.values[2])
        // Hardware axes stay stable even when the active logical display changes.
        direction = standbyPostureDirection(FoldPanel.INNER, event.values[0], event.values[1], event.values[2],
            android.os.Build.DEVICE == "lhasa")
        evaluate()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        instance = null; running = false; launchAllowed = false
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        store.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        sensors.unregisterListener(hingeListener)
        sensors.unregisterListener(this)
        usb.destroy()
        if (wake.isHeld) wake.release()
        clockVisible.value = false
        status.intValue = R.string.standby_status_off
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "standby-posture"
        private const val STOP = "com.jake.duolauncher.STOP_STANDBY"
        private var running = false
        private var instance: StandbyMonitorService? = null
        internal var launchAllowed = false
            private set
        /** Monitor state for Settings, as a string resource so it follows the app language. */
        internal val status = androidx.compose.runtime.mutableIntStateOf(R.string.standby_status_off)
        internal val clockVisible = mutableStateOf(false)
        internal val clockRotation = mutableStateOf(90f)
        internal fun dismiss() { instance?.dismissClock() }
        /**
         * Round 22: StandBy shows only over Home on the cover (PanelOrientationController); the locked clock and
         * its always-on monitor were removed, so this only stops a monitor an older build left running.
         */
        @Suppress("UNUSED_PARAMETER")
        internal fun sync(context: Context, setupComplete: Boolean = true) {
            if (running) context.stopService(Intent(context, StandbyMonitorService::class.java))
        }
    }
}
