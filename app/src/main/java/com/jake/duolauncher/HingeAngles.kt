package com.jake.duolauncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper

internal data class HingeSample(val degrees: Float, val timestampNs: Long, val direct: Boolean)

/** The public on-change sensor remains authoritative after its first valid sample.
 * A vendor posture snapshot can stop updating long before the hinge stops moving. */
internal class HingeAngleSelection {
    var sample: HingeSample? = null
        private set
    fun update(degrees: Float, timestampNs: Long, direct: Boolean): HingeSample? {
        if (!degrees.isFinite() || degrees !in 0f..180f || timestampNs < 0L) return null
        val before = sample
        if (before?.direct == true && !direct) return null
        if (before != null && before.direct == direct && timestampNs <= before.timestampNs) return null
        return HingeSample(degrees, timestampNs, direct).also { sample = it }
    }
}

/** One sensor stream for gesture motion and StandBy, with no per-window angle cache. */
internal object HingeAngles : SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(HingeSample) -> Unit>()
    private var manager: SensorManager? = null
    private var direct: Sensor? = null
    private var fallback: Sensor? = null
    private var selection = HingeAngleSelection()

    fun acquire(context: Context, listener: (HingeSample) -> Unit) {
        if (!listeners.add(listener)) return
        if (listeners.size == 1) {
            selection = HingeAngleSelection()
            val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
            manager = sensors
            direct = hingeSensor(sensors)
            fallback = if (Build.DEVICE == "lhasa") sensors.getSensorList(Sensor.TYPE_ALL)
                .firstOrNull { it.stringType == "xiaomi.sensor.fold_status" && it.isWakeUpSensor } else null
            // On-change sensor: the period is only a ceiling, a still hinge reports nothing. lhasa
            // quantises to 1 degree and advertises 400 Hz; at the old 50 Hz request a brisk fold
            // (400-650 deg/s, logcat 2026-10-20) arrived in 8-13 degree jumps. FASTEST is clamped to
            // the sensor's maxRate (HIGH_SAMPLING_RATE_SENSORS lifts the 200 Hz app cap); no batching.
            direct?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, 0, handler) }
            fallback?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, handler) }
        }
        selection.sample?.let(listener)
    }

    /** getDefaultSensor(TYPE_HINGE_ANGLE) returns the WAKE-UP variant (Android lists hinge angle
     * among the wake-up defaults). Home only needs samples while the screen is on, like Xiaomi's own
     * fold wallpaper (com.miui.miwallpaper FoldManager), which holds the non-wake-up twin: same HAL
     * stream and timestamps, without wake-lock/ack overhead on every event. */
    internal fun hingeSensor(sensors: SensorManager): Sensor? =
        sensors.getSensorList(Sensor.TYPE_HINGE_ANGLE).firstOrNull { !it.isWakeUpSensor }
            ?: sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)

    fun release(listener: (HingeSample) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            manager?.unregisterListener(this)
            manager = null; direct = null; fallback = null
            selection = HingeAngleSelection()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val isDirect = event.sensor == direct
        val value = event.values.getOrNull(if (isDirect) 0 else 1) ?: return
        val sample = selection.update(value, event.timestamp, isDirect) ?: return
        listeners.toList().forEach { it(sample) }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
