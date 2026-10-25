package com.jake.duolauncher

/** Persisted by [name]; [label] is a string resource so the list follows the app language. */
internal enum class StandbyScene(val label: Int) {
    LOCKED_ONLY(R.string.standby_scene_locked), LOCKED_AND_HOME(R.string.standby_scene_locked_home),
    MANUAL_ONLY(R.string.standby_scene_manual)
}

internal data class StandbyAngles(val minimum: Int = 10, val maximum: Int = 80) {
    fun contains(angle: Float?): Boolean = angle != null && angle.isFinite() &&
        angle >= minimum && angle <= maximum
    companion object {
        fun normalized(minimum: Int, maximum: Int): StandbyAngles {
            val low = minimum.coerceIn(5, 165)
            return StandbyAngles(low, maximum.coerceIn(low + 5, 170))
        }
    }
}

internal fun standbySceneAllows(scene: StandbyScene, locked: Boolean): Boolean = when (scene) {
    StandbyScene.LOCKED_ONLY -> locked
    StandbyScene.LOCKED_AND_HOME -> true
    StandbyScene.MANUAL_ONLY -> false
}

/** User choice for the black/red StandBy palette. Auto keeps the ambient-light behaviour. */
internal enum class StandbyNightMode {
    AUTO, OFF, ON;

    companion object {
        fun fromKey(key: String?): StandbyNightMode = entries.firstOrNull { it.name == key } ?: AUTO
    }
}

internal fun standbyNightEnabled(mode: StandbyNightMode, detected: Boolean): Boolean = when (mode) {
    StandbyNightMode.AUTO -> detected
    StandbyNightMode.OFF -> false
    StandbyNightMode.ON -> true
}

/** A close action suppresses re-entry until the angle leaves the interval or unlock.
 * Sensor jitter and screen-on broadcasts cannot undo an explicit user dismissal. */
internal class LockedStandbyGate {
    private var since: Long? = null
    private var dismissed = false
    fun dismiss() { dismissed = true; since = null }
    fun update(locked: Boolean, enabled: Boolean, angleInRange: Boolean,
        posture: Boolean, now: Long): Boolean {
        if (!locked || !angleInRange) dismissed = false
        if (!locked || !enabled || !angleInRange || !posture || dismissed) {
            since = null
            return false
        }
        val start = since ?: now.also { since = it }
        return now - start >= 700L
    }
}

/**
 * iOS starts StandBy only while the phone is set down and "stationary". A tent held in the hand passes the
 * hinge and gravity checks but never keeps every gravity axis within [tolerance] m/s² (≈3° of wobble) for
 * [windowMillis]; a table does. Only entry is gated — touching the screen once StandBy shows must not exit it.
 */
internal class StillnessDetector(private val windowMillis: Long = 600L, private val tolerance: Float = .5f) {
    private class Sample(val time: Long, val axes: FloatArray)
    private val samples = ArrayDeque<Sample>()
    fun add(time: Long, x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) { samples.clear(); return }
        samples.addLast(Sample(time, floatArrayOf(x, y, z)))
        while (samples.isNotEmpty() && samples.first().time < time - windowMillis * 2) samples.removeFirst()
    }
    fun reset() = samples.clear()
    /** Needs samples spanning at least half the window so a single quiet reading proves nothing. */
    fun isStill(now: Long): Boolean {
        val recent = samples.filter { it.time in (now - windowMillis)..now }
        if (recent.size < 2 || recent.last().time - recent.first().time < windowMillis / 2) return false
        for (axis in 0..2) {
            if (recent.maxOf { it.axes[axis] } - recent.minOf { it.axes[axis] } > tolerance) return false
        }
        return true
    }
}

/**
 * Night Mode ("adapts to low ambient light … displays items with a red tint"): ambient light with hysteresis so
 * a passing shadow or a phone screen nearby doesn't flicker the face between colour and red.
 */
internal class StandbyNightDetector(
    private val enterLux: Float = 3f,
    private val exitLux: Float = 12f,
    private val enterMillis: Long = 3_000L,
    private val exitMillis: Long = 1_500L,
    /** The last known state (a StandBy shown moments ago), used until the first reading arrives. */
    initial: Boolean = false,
) {
    var night = initial
        private set
    private var seeded = false
    private var pendingSince: Long? = null
    fun update(lux: Float, now: Long): Boolean {
        if (!lux.isFinite() || lux < 0f) return night
        if (!seeded) {
            // The first reading describes the room StandBy opens in: no 3 s of full colour in a dark bedroom
            // (code review P2-5). Between the thresholds the remembered state stands.
            seeded = true
            if (lux <= enterLux) night = true else if (lux >= exitLux) night = false
            return night
        }
        val target = when {
            night && lux >= exitLux -> false
            !night && lux <= enterLux -> true
            else -> night
        }
        if (target == night) { pendingSince = null; return night }
        val since = pendingSince ?: now.also { pendingSince = it }
        if (now - since >= if (target) enterMillis else exitMillis) { night = target; pendingSince = null }
        return night
    }
}

/** Process-wide memory of the last Night Mode decision, so a StandBy reopened soon after starts in the same mode. */
internal object StandbyNightMemory {
    private const val FRESH_MILLIS = 15 * 60_000L
    private var night = false
    private var at = Long.MIN_VALUE
    fun remember(value: Boolean, now: Long) { night = value; at = now }
    fun recall(now: Long): Boolean = night && at != Long.MIN_VALUE && now - at in 0L..FRESH_MILLIS
}

/** Only for panels without an ambient-light sensor: a fixed late-night window. */
internal fun standbyNightByClock(hour: Int): Boolean = hour >= 22 || hour < 6

/**
 * Power policy (code review P1-3). iPhone Duo StandBy also runs on battery, but only a charging iPhone keeps it lit
 * (Always-On style); otherwise the user's Auto-Lock still applies. So StandBy holds the screen on only while
 * external power is connected; on battery Android's own timeout dims, turns off and locks as usual, and the locked
 * clock never turns a sleeping screen back on by itself.
 */
internal fun standbyKeepsScreenOn(externalPower: Boolean): Boolean = externalPower

/** `BatteryManager.EXTRA_PLUGGED`: any source (AC, USB, wireless, dock) counts, even when charging is paused at full. */
internal fun standbyExternalPower(plugged: Int): Boolean = plugged > 0

/** The locked clock may wake the screen only on a charger; on battery it shows only on a screen the user woke. */
internal fun lockedClockMayShow(externalPower: Boolean, interactive: Boolean): Boolean = externalPower || interactive

/** Sticky battery broadcast, read without registering a receiver. */
internal fun standbyOnExternalPower(context: android.content.Context): Boolean =
    standbyExternalPower(context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0)

/**
 * Automatic Home StandBy may take over only an idle Home (code review P1-4): nothing open (Spotlight, sheets, folders,
 * menus, edit mode, a drag, a widget session — reported by Home as [busy]) and no touch for [idleMillis].
 */
internal class HomeIdleGate(private val idleMillis: Long = HOME_STANDBY_IDLE_MILLIS) {
    private var lastInput = Long.MIN_VALUE
    var busy = false
    fun noteInput(now: Long) { lastInput = now }
    fun idle(now: Long): Boolean = !busy && (lastInput == Long.MIN_VALUE || now - lastInput >= idleMillis)
}

internal const val HOME_STANDBY_IDLE_MILLIS = 3_000L

/** What the always-on locked-clock monitor needs running (code review P2-6). */
internal data class StandbyMonitorPlan(val hinge: Boolean, val gravity: Boolean, val tick: Boolean)

/**
 * The monitor only serves the lock screen. While the phone is unlocked and in use it watches nothing (Home StandBy
 * belongs to the launcher's own controller, which runs only while Home is visible); with the screen off or the
 * keyguard up it follows the hinge at a low rate, and only when the hinge is inside the StandBy range does it add
 * the 10 Hz accelerometer and the 250 ms settle tick.
 */
internal fun standbyMonitorPlan(locked: Boolean, interactive: Boolean, angleInRange: Boolean): StandbyMonitorPlan {
    val watching = locked || !interactive
    val posture = watching && angleInRange
    return StandbyMonitorPlan(hinge = watching, gravity = posture && locked, tick = posture)
}

/** Page dots and the close control appear on touch and fade after this long without interaction (iOS). */
internal const val STANDBY_CHROME_MILLIS = 3_000L

/** Round 24 (owner photo 1, iPhone Duo): the inner screen stood open like a book on a table. */
internal const val INNER_STANDBY_MIN_DEGREES = 50f
internal const val INNER_STANDBY_MAX_DEGREES = 150f

/**
 * `Surface.ROTATION_*` the inner shows a book standing on a table in, null when the device isn't one: the hinge part
 * open, gravity along the hinge and nearly none through the panels, so neither half lies on the table (a laptop pose)
 * or faces up. Xiaomi 18 Fold ([hingeAlongX], phone log 2026-10-26 02:24: book pose at 72°–94° read x = ±9.7, y ≈ 0,
 * window rotation 270 at x < 0) reports the hinge on the accelerometer's X axis; other book foldables on Y.
 */
internal fun innerStandbyRotation(angle: Float?, x: Float, y: Float, z: Float,
    hingeAlongX: Boolean = android.os.Build.DEVICE == "lhasa"): Int? {
    if (angle == null || !angle.isFinite() || angle !in INNER_STANDBY_MIN_DEGREES..INNER_STANDBY_MAX_DEGREES) return null
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || kotlin.math.abs(z) >= 4f) return null
    val along = if (hingeAlongX) -x else y
    if (if (hingeAlongX) !physicallyLandscape(x, y) else !physicallyUpright(x, y)) return null
    return if (along > 0f) INNER_BOOK_ROTATION else android.view.Surface.ROTATION_90
}
