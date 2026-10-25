package com.jake.duolauncher

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** The last few degrees settle early so a slightly imperfect flat hinge is crisp. */
internal fun hingeReveal(degrees: Float): Float {
    if (!degrees.isFinite()) return 1f
    return ((degrees - 1f) / 174f).coerceIn(0f, 1f)
}

internal data class FoldVisual(val opacity: Float, val scaleX: Float, val rotationY: Float, val blurDp: Float)
internal data class FoldEdgeDepths(val left: Float, val right: Float)
internal data class FoldProjectionGeometry(
    val horizontalInsetFraction: Float,
    val verticalInsetFraction: Float,
    val maxBlurFraction: Float,
)

internal enum class FoldPanel { UNKNOWN, COVER, INNER }
/** The shaded (free) edge of a projected pane. LEFT/RIGHT for a vertical hinge, TOP/BOTTOM (round 12)
 * for the cover under a rotation that puts the hinge on a horizontal edge. */
internal enum class FoldShadeSide { LEFT, RIGHT, TOP, BOTTOM }
internal enum class FoldDirection { UNKNOWN, OPENING, CLOSING }
internal enum class FoldPhase {
    RECOVERY,
    COVER_STABLE,
    OPENING_COVER,
    HANDOFF_TO_INNER,
    OPENING_INNER,
    INNER_STABLE,
    CLOSING_INNER,
    HANDOFF_TO_COVER,
    CLOSING_COVER,
}

internal data class FoldSignal(
    val angleDegrees: Float? = null,
    val sensorTimestampNs: Long = 0L,
    val receivedElapsedNs: Long = 0L,
    val velocityDegreesPerSecond: Float = 0f,
    val direction: FoldDirection = FoldDirection.UNKNOWN,
    val activePanel: FoldPanel = FoldPanel.UNKNOWN,
    val previousPanel: FoldPanel = FoldPanel.UNKNOWN,
    val panelEpoch: Long = 0L,
    val panelChangedElapsedNs: Long = 0L,
    val phase: FoldPhase = FoldPhase.RECOVERY,
    val coverProjectionBlocked: Boolean = false,
    val innerConfirmedFlat: Boolean = false,
    /** Without contact sensors the cover settles to crisp [COVER_FOLD_IDLE_NS] after the hinge
     * stops (an on-change sensor may never deliver the final closed reading). lhasa's Hall and
     * fold-status sensors report closure directly, so there the cover holds its angle at rest. */
    val coverSettlesWhenIdle: Boolean = true,
)

/** APK C0328j6.a / R7: lhasa's flat posture remains crisp during angle-only
 * tilt noise (a flat phone reports 172-179 degrees while it is tilted or touched).
 *
 * Release (r5): a real fold is a run of decreasing samples. Release as soon as one
 * run has fallen [CLOSING_RUN_DROP] degrees and below [CLOSING_RUN_MAX_ANGLE], with no
 * speed ceiling: owner's fold on 2026-10-20 went 176-172-166-159-150-140-128 at ~20 ms
 * spacing (400-650 deg/s) and the inner panel was dark 0.44 s after 172 degrees. The old
 * detector disabled itself above 240 deg/s or on a >12 degree first step, and the vendor
 * posture stays OPENED to ~159 degrees, so only its 500 ms fallback was left: the inner
 * fold animation never got to play. A single discontinuity (a jump larger than
 * [CLOSING_RUN_MAX_STEP]) still cannot release; the 500 ms fallback below 170 degrees in
 * a folded posture remains for very slow folds. */
internal class FoldInnerFlatTracker {
    private var confirmed = false
    private var belowFlatSinceNs: Long? = null
    private var lastUpdateNs = -1L
    private var flatAngle = Float.NaN
    private var runStartAngle = Float.NaN
    private var runLastAngle = Float.NaN
    private var runLastNs = -1L
    private var runSteps = 0

    private fun resetRun() {
        runStartAngle = Float.NaN
        runLastAngle = Float.NaN
        runLastNs = -1L
        runSteps = 0
    }

    private fun startRun(angle: Float, nowNs: Long) {
        runStartAngle = angle
        runLastAngle = angle
        runLastNs = nowNs
        runSteps = 0
    }

    /** Contact and fold-status events re-evaluate the same angle at 50 Hz; only a
     * changed reading extends or restarts the run. */
    private fun closingRun(angle: Float, nowNs: Long): Boolean {
        if (runLastNs < 0L) { startRun(angle, nowNs); return false }
        val step = runLastAngle - angle
        val gapNs = nowNs - runLastNs
        when {
            gapNs > CLOSING_RUN_MAX_GAP_NS -> startRun(angle, nowNs)
            abs(step) < CLOSING_RUN_MIN_STEP -> return false
            step > CLOSING_RUN_MAX_STEP || step < 0f -> startRun(angle, nowNs)
            else -> { runLastAngle = angle; runLastNs = nowNs; runSteps++ }
        }
        val ceiling = minOf(CLOSING_RUN_MAX_ANGLE,
            if (flatAngle.isFinite()) flatAngle - CLOSING_RUN_DROP else CLOSING_RUN_MAX_ANGLE)
        return runSteps >= CLOSING_RUN_MIN_STEPS && runStartAngle - angle >= CLOSING_RUN_DROP && angle <= ceiling
    }

    fun update(angle: Float, posture: Int, nowNs: Long): Boolean {
        if (nowNs <= lastUpdateNs) return confirmed
        if (lastUpdateNs >= 0L && nowNs - lastUpdateNs > 500_000_000L) {
            belowFlatSinceNs = null
            resetRun()
        }
        lastUpdateNs = nowNs
        if (!angle.isFinite() || angle !in 0f..180f) {
            belowFlatSinceNs = null
            resetRun()
            return confirmed
        }
        if (posture == 3 && angle >= 175f) {
            confirmed = true
            belowFlatSinceNs = null
            flatAngle = angle
            // The last flat reading is where a fold's run begins.
            startRun(angle, nowNs)
            return true
        }
        if (!confirmed || posture !in 0..3) {
            belowFlatSinceNs = null
            resetRun()
            return confirmed
        }
        val closing = closingRun(angle, nowNs)
        val timedOut = if (posture in 0..2 && angle < 170f) {
            val startNs = belowFlatSinceNs ?: nowNs.also { belowFlatSinceNs = it }
            nowNs - startNs >= 500_000_000L
        } else {
            belowFlatSinceNs = null
            false
        }
        if (closing || timedOut) {
            confirmed = false
            belowFlatSinceNs = null
            resetRun()
        }
        return confirmed
    }

    private companion object {
        const val CLOSING_RUN_MIN_STEP = .5f
        // 25 degrees per sample is ~1250 deg/s at 50 Hz; the fastest measured fold step was 13.
        const val CLOSING_RUN_MAX_STEP = 25f
        const val CLOSING_RUN_MAX_GAP_NS = 120_000_000L
        const val CLOSING_RUN_MIN_STEPS = 2
        const val CLOSING_RUN_DROP = 8f
        // Below the deepest flat-tilt reading seen on the device (172).
        const val CLOSING_RUN_MAX_ANGLE = 168f
    }
}

// Cover projection is a transition, not a resting desktop posture. TYPE_HINGE_ANGLE
// is an on-change sensor: a missing final closed reading must not strand Home in
// its last trapezoid. Keep tracking while moving, then settle the cover only.
internal const val COVER_FOLD_IDLE_NS = 1_200_000_000L

/** Round 20: a cover held shut in the hand reads 9–21° while lhasa's Hall contact flickers between
 * touching and separated (owner's video 2026-10-23, fold_status log 16:12:15–18). Each flip used to
 * switch the cover between sharp and its blur for the angle in one frame. The contact gate now
 * fades the projection in and out over this time instead. */
internal const val COVER_GATE_MS = 260

/** Android's Remove animations setting also applies to our custom VSYNC follower.
 * Compose already scales tween durations; only gate custom motion here, so the
 * system duration scale is never applied twice. */
internal fun foldMotionEnabled(animatorDurationScale: Float): Boolean =
    !animatorDurationScale.isFinite() || animatorDurationScale > 0f

/** A second handoff continues from the rendered value rather than replaying
 * from zero. A completed arrival can start a new physical panel transition. */
internal fun foldArrivalStart(current: Float): Float =
    if (current.isFinite() && current < 1f) current.coerceAtLeast(0f) else 0f

@Composable
internal fun rememberSystemFoldMotionEnabled(): State<Boolean> {
    val resolver = LocalContext.current.applicationContext.contentResolver
    fun readEnabled() = foldMotionEnabled(runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f))
    val enabled = remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled.value = readEnabled() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        enabled.value = readEnabled()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** [coverGated] false: the cover follows the hinge through its contact gate, which the caller
 * fades itself ([COVER_GATE_MS]); the gate then never steps the projection in one frame. */
internal fun foldTrackingProgress(signal: FoldSignal, panel: FoldPanel, nowNs: Long,
    coverGated: Boolean = true): Float {
    val rest = if (panel == FoldPanel.INNER) 1f else 0f
    val angle = signal.angleDegrees?.takeIf { it.isFinite() } ?: return rest
    if (signal.activePanel != panel) return rest
    if (panel == FoldPanel.INNER && signal.innerConfirmedFlat) return rest
    if (panel == FoldPanel.COVER && coverGated && signal.coverProjectionBlocked) return rest
    if (panel == FoldPanel.COVER && signal.receivedElapsedNs <= 0L) return rest
    if (panel == FoldPanel.COVER && signal.coverSettlesWhenIdle &&
            nowNs - signal.receivedElapsedNs !in 0 until COVER_FOLD_IDLE_NS) return rest
    return hingeReveal(angle)
}

internal fun resetFoldTracking(signal: FoldSignal): FoldSignal = signal.copy(
    angleDegrees = null,
    sensorTimestampNs = 0L,
    receivedElapsedNs = 0L,
    velocityDegreesPerSecond = 0f,
    direction = FoldDirection.UNKNOWN,
    activePanel = FoldPanel.UNKNOWN,
    previousPanel = FoldPanel.UNKNOWN,
    panelChangedElapsedNs = 0L,
    phase = FoldPhase.RECOVERY,
    coverProjectionBlocked = false,
    innerConfirmedFlat = false,
)

internal fun foldVisual(progress: Float): FoldVisual {
    val p = progress.coerceIn(0f, 1f)
    val remaining = 1f - p
    return FoldVisual(
        // Darkness comes from the three outside edges, not from fading the
        // complete page into its wallpaper.
        opacity = 1f,
        // Strong overscan compensates the foreshortened far edge. The content
        // therefore appears to continue beyond the panel instead of becoming a
        // smaller card floating inside it.
        scaleX = 1f + .24f * remaining,
        // With the hinge fixed on the right, positive Y rotation sends the
        // free left edge away from the camera, so the left side is shorter.
        rotationY = 30f * remaining,
        blurDp = 18f * remaining,
    )
}

/** The cover is the opposite face of the folding plane. It rotates away around
 * its left hinge while opening, and reverses the exact path when it takes Home
 * back during closing. */
internal fun foldCoverVisual(progress: Float): FoldVisual {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return FoldVisual(
        opacity = 1f,
        scaleX = 1f + .18f * p,
        // The cover keeps its left hinge edge tall and sends the right edge
        // away from the camera, making the right side of the trapezoid shorter.
        rotationY = 24f * p,
        blurDp = 15f * p,
    )
}

internal fun foldEdgeInsetFraction(amount: Float): Float =
    .072f * (if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f)

internal fun foldPerspectiveInsetFraction(amount: Float): Float =
    .055f * (if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f)

internal fun foldEdgeDepths(edge: Float, perspective: Float, side: FoldShadeSide): FoldEdgeDepths =
    when (side) {
        // For TOP/BOTTOM the wedges run along the left/right edges; "left" is then their top end.
        FoldShadeSide.LEFT, FoldShadeSide.TOP -> FoldEdgeDepths(left = edge + perspective, right = edge)
        FoldShadeSide.RIGHT, FoldShadeSide.BOTTOM -> FoldEdgeDepths(left = edge, right = edge + perspective)
    }

/** The cover's free (shaded) edge for the window's rotation: opposite [coverHingeEdge]. */
internal fun coverShadeSide(rotation: Int): FoldShadeSide = when (coverHingeEdge(rotation)) {
    HingeEdge.LEFT -> FoldShadeSide.RIGHT
    HingeEdge.BOTTOM -> FoldShadeSide.TOP
    HingeEdge.RIGHT -> FoldShadeSide.LEFT
    HingeEdge.TOP -> FoldShadeSide.BOTTOM
}

/**
 * Round 12: while the handoff veil ([LauncherSurface.FOLDING]) is up, the new panel's arrival motion
 * (cover "takes Home back", inner hinge flattening) waits at its start; it plays as the veil lifts,
 * so the reveal *is* the arrival rather than a frame of it hidden in black.
 */
internal object FoldArrivalGate { val held = mutableStateOf(false) }

/** Geometry shared by the AGSL renderer and unit tests. The free edge moves
 * inward while both sloping edges converge to zero exactly at the hinge. */
internal fun foldProjectionGeometry(amount: Float): FoldProjectionGeometry {
    val p = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
    val eased = p
    return FoldProjectionGeometry(
        horizontalInsetFraction = .13f * eased,
        verticalInsetFraction = .16f * eased,
        maxBlurFraction = .045f * eased,
    )
}

/** Inverse homography of a plane whose free edge is shorter than its hinge. */
internal fun foldSourceFraction(projectedFraction: Float, verticalInsetFraction: Float): Float {
    val q = projectedFraction.coerceIn(0f, 1f)
    val freeEdgeScale = (1f - 2f * verticalInsetFraction).coerceIn(.01f, 1f)
    return q / (freeEdgeScale + (1f - freeEdgeScale) * q)
}

/** Cover-screen motion consumes only the first part of the physical opening.
 * The exact panel handoff remains display-event driven rather than threshold driven. */
internal fun coverFoldProgress(hingeProgress: Float): Float {
    val normalized = (hingeProgress.coerceIn(0f, 1f) / .68f).coerceIn(0f, 1f)
    return normalized
}


/** The unfolded left pane meets the hinge on its right. The cover is mounted on
 * the opposite face, so its hinge edge is on the left and its free edge is right. */
internal fun foldShadeSide(expanded: Boolean): FoldShadeSide =
    if (expanded) FoldShadeSide.LEFT else FoldShadeSide.RIGHT

internal fun nextFoldDirection(previousDegrees: Float?, degrees: Float,
    previousDirection: FoldDirection): FoldDirection {
    if (!degrees.isFinite() || previousDegrees == null || !previousDegrees.isFinite()) return previousDirection
    val delta = degrees - previousDegrees
    return when {
        delta >= .9f -> FoldDirection.OPENING
        delta <= -.9f -> FoldDirection.CLOSING
        else -> previousDirection
    }
}

internal fun foldPhase(panel: FoldPanel, hingeProgress: Float,
    direction: FoldDirection, innerConfirmedFlat: Boolean = false): FoldPhase {
    val progress = hingeProgress.coerceIn(0f, 1f)
    return when {
        panel == FoldPanel.UNKNOWN -> FoldPhase.RECOVERY
        panel == FoldPanel.COVER && progress <= .015f -> FoldPhase.COVER_STABLE
        panel == FoldPanel.INNER && (innerConfirmedFlat || progress >= .985f) -> FoldPhase.INNER_STABLE
        panel == FoldPanel.COVER && direction == FoldDirection.OPENING -> FoldPhase.OPENING_COVER
        panel == FoldPanel.COVER -> FoldPhase.CLOSING_COVER
        direction == FoldDirection.CLOSING -> FoldPhase.CLOSING_INNER
        else -> FoldPhase.OPENING_INNER
    }
}

/** Physical mode area, not logical display id, distinguishes the two panels.
 * Logical id 0 is reused by HyperOS when it hands Home from one panel to the other. */
internal fun panelFromPhysicalAreas(activeAreas: List<Long>, allAreas: List<Long>): FoldPanel {
    val known = allAreas.filter { it > 0L }.distinct().sorted()
    val active = activeAreas.filter { it > 0L }.distinct()
    if (known.size < 2 || active.size != 1) return FoldPanel.UNKNOWN
    return when (active.single()) {
        known.first() -> FoldPanel.COVER
        known.last() -> FoldPanel.INNER
        else -> FoldPanel.UNKNOWN
    }
}

/** HyperOS keeps only the lit panel in DisplayManager.getDisplays() (the other logical display
 * is disabled), so [panelFromPhysicalAreas] never sees two areas on lhasa. One lit internal
 * display is still classifiable: the inner panel is 608 dp across its short side, the cover 425. */
internal fun panelFromSingleDisplay(widthPx: Int, heightPx: Int, densityDpi: Int): FoldPanel {
    if (widthPx <= 0 || heightPx <= 0 || densityDpi <= 0) return FoldPanel.UNKNOWN
    val shortSideDp = minOf(widthPx, heightPx) * 160f / densityDpi
    return if (shortSideDp >= 600f) FoldPanel.INNER else FoldPanel.COVER
}

internal fun foldDisplayPanel(
    activeModes: List<Pair<Int, Int>>,
    allModes: List<Pair<Int, Int>>,
    densityDpi: Int,
    windowFallback: FoldPanel,
): FoldPanel {
    fun area(mode: Pair<Int, Int>) = mode.first.toLong() * mode.second.toLong()
    val physical = panelFromPhysicalAreas(activeModes.map(::area), allModes.map(::area))
    if (physical != FoldPanel.UNKNOWN) return physical
    // The window's own panel outranks a lone display mode: a size-overridden QA emulator keeps
    // its phone mode, and mid-swap the lit mode can lead the window by a frame or two.
    if (windowFallback != FoldPanel.UNKNOWN) return windowFallback
    return activeModes.distinct().singleOrNull()
        ?.let { panelFromSingleDisplay(it.first, it.second, densityDpi) } ?: FoldPanel.UNKNOWN
}

internal class FoldTransitionState(
    val reveal: State<Float>,
    val coverFold: State<Float>,
    val arrival: State<Float>,
    val phase: State<FoldPhase>,
)

/** Process-local bridge across Xiaomi's cover/inner Home handoff. The listener remains
 * active only for a short grace window between visible Launcher instances. */
private object FoldSignalCoordinator : SensorEventListener, DisplayManager.DisplayListener {
    private const val TAG = "DuoFoldTransition"
    private const val HANDOFF_GRACE_MS = 3_500L
    private val handler = Handler(Looper.getMainLooper())
    val signal = mutableStateOf(FoldSignal())

    private var sensorManager: SensorManager? = null
    private var displayManager: DisplayManager? = null
    private val hingeListener: (HingeSample) -> Unit = { sample -> onHingeSample(sample) }
    private var physicalFoldSensor: Sensor? = null
    private var directContactSensor: Sensor? = null
    private var contactPose = GlassFoldPose(false)
    private var innerFlatTracker = FoldInnerFlatTracker()
    private val hasContactProfile = Build.DEVICE == "lhasa"
    private var sensorSessionStartedNs = 0L
    private val contactExpiry = Runnable {
        contactPose = contactPose.expireDirectContact(SystemClock.elapsedRealtimeNanos())
        publishContactGate()
    }
    private var clients = 0
    private var registered = false
    private var internalDisplayName: String? = null
    // Survives stop(): a Home that comes back after the 3.5 s grace (an app was open, the screen
    // was off) must not stay UNKNOWN, which disables every fold animation until the next handoff.
    private var lastWindowPanel = FoldPanel.UNKNOWN
    private var densityDpi = 0
    private var stopRunnable: Runnable? = null

    fun acquire(context: Context) {
        ensure(context.applicationContext)
        stopRunnable?.let(handler::removeCallbacks)
        stopRunnable = null
        clients++
        if (!registered) start(context.applicationContext)
    }

    fun release() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients != 0 || stopRunnable != null) return
        Runnable {
            stopRunnable = null
            if (clients == 0) stop()
        }.also { task -> stopRunnable = task; handler.postDelayed(task, HANDOFF_GRACE_MS) }
    }

    fun reportWindowPanel(panel: FoldPanel, display: Display?) {
        if (display != null) {
            internalDisplayName = display.name
        }
        if (panel != FoldPanel.UNKNOWN) lastWindowPanel = panel
        refreshDisplays("window", panel)
    }

    private fun ensure(context: Context) {
        if (sensorManager != null) return
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        densityDpi = context.resources.configuration.densityDpi
        if (hasContactProfile) {
            val available = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            physicalFoldSensor = available.firstOrNull {
                it.stringType == "xiaomi.sensor.fold_status" && it.name == "fold_status FOLD_STATUS Wakeup"
            }
            directContactSensor = available.firstOrNull {
                it.stringType == "xiaomi.sensor.dighall" && it.name == "ak0991x Digital Hall Sensor Non-wakeup"
            }
        }
    }

    private fun start(context: Context) {
        val sensors = sensorManager ?: return
        sensorSessionStartedNs = SystemClock.elapsedRealtimeNanos()
        // 18Duo owns the foreground desktop, unlike upstream's background overlay.
        // Read Hall locally: no shell helper, wireless debugging or extra permission.
        fun registerContact(sensor: Sensor?): Boolean = sensor?.let {
            runCatching { sensors.registerListener(this, it, 20_000, 0, handler) }.getOrDefault(false)
        } ?: false
        val foldRegistered = registerContact(physicalFoldSensor)
        val hallRegistered = registerContact(directContactSensor)
        contactPose = GlassFoldPose(foldRegistered, hasContactProfile, hallRegistered)
        innerFlatTracker = FoldInnerFlatTracker()
        // Closure comes from the contact sensors here, so the cover need not settle on a timer.
        signal.value = signal.value.copy(coverSettlesWhenIdle = !(hasContactProfile && (foldRegistered || hallRegistered)))
        publishContactGate()
        if (hasContactProfile) Log.i(TAG, "contactSensors fold=$foldRegistered hall=$hallRegistered")
        displayManager?.registerDisplayListener(this, handler)
        HingeAngles.acquire(context, hingeListener)
        registered = true
        refreshDisplays("start", lastWindowPanel)
    }

    private fun stop() {
        sensorManager?.unregisterListener(this)
        HingeAngles.release(hingeListener)
        displayManager?.unregisterDisplayListener(this)
        registered = false
        handler.removeCallbacks(contactExpiry)
        signal.value = resetFoldTracking(signal.value)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp < sensorSessionStartedNs) return
        if (hasContactProfile && event.timestamp >= sensorSessionStartedNs) {
            when (event.sensor) {
                directContactSensor -> {
                    val next = contactPose.withDirectContactEvent(event.values, event.timestamp)
                    if (next !== contactPose) {
                        contactPose = next.expireDirectContact(SystemClock.elapsedRealtimeNanos())
                        handler.removeCallbacks(contactExpiry)
                        val ageMs = contactPose.directContactAgeMs(SystemClock.elapsedRealtimeNanos())
                        if (ageMs <= 500L) handler.postDelayed(contactExpiry, 501L - ageMs)
                        publishContactGate()
                    }
                    return
                }
                physicalFoldSensor -> {
                    contactPose = contactPose.withFoldEvent(event.values, event.timestamp)
                    publishContactGate()
                    return
                }
            }
        }
    }

    private fun onHingeSample(sample: HingeSample) {
        // A shared on-change sensor's settled sample is still valid on acquisition;
        // it need not emit again when StandBy gives the desktop back.
        val degrees = sample.degrees
        if (hasContactProfile) {
            contactPose = contactPose.withAngle(degrees, sample.timestampNs)
            publishContactGate()
        }
        val before = signal.value
        if (sample.timestampNs < before.sensorTimestampNs) return
        if (before.angleDegrees != null && abs(degrees - before.angleDegrees) < .1f) return
        val direction = nextFoldDirection(before.angleDegrees, degrees, before.direction)
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val seconds = (sample.timestampNs - before.sensorTimestampNs) / 1_000_000_000f
        val velocity = if (before.angleDegrees != null && seconds in .002f..2f)
            (degrees - before.angleDegrees) / seconds else 0f
        val progress = hingeReveal(degrees)
        val next = before.copy(
            angleDegrees = degrees,
            sensorTimestampNs = sample.timestampNs,
            receivedElapsedNs = elapsed,
            velocityDegreesPerSecond = velocity,
            direction = direction,
            phase = foldPhase(before.activePanel, progress, direction, before.innerConfirmedFlat),
        )
        signal.value = next
        if (next.phase != before.phase || next.direction != before.direction) {
            Log.i(TAG, "phase=${next.phase} panel=${next.activePanel} angle=$degrees " +
                "velocity=$velocity direction=$direction epoch=${next.panelEpoch}")
        }
    }

    private fun publishContactGate() {
        val blocked = hasContactProfile && contactPose.blocksProjection()
        val innerFlat = hasContactProfile && innerFlatTracker.update(contactPose.rawAngle,
            contactPose.postureStatus, SystemClock.elapsedRealtimeNanos())
        if (signal.value.coverProjectionBlocked == blocked && signal.value.innerConfirmedFlat == innerFlat) return
        signal.value = signal.value.copy(coverProjectionBlocked = blocked, innerConfirmedFlat = innerFlat)
        Log.i(TAG, "coverBlocked=$blocked hall=${contactPose.directContactStatus} " +
            "rawAngle=${contactPose.rawAngle} innerFlat=$innerFlat posture=${contactPose.postureStatus}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDisplayAdded(displayId: Int) = refreshDisplays("added:$displayId")
    override fun onDisplayRemoved(displayId: Int) = refreshDisplays("removed:$displayId")
    override fun onDisplayChanged(displayId: Int) = refreshDisplays("changed:$displayId")

    private fun refreshDisplays(source: String, windowFallback: FoldPanel = lastWindowPanel) {
        val manager = displayManager
        val namedDisplays = manager?.displays?.filter { display ->
            display.isValid && internalDisplayName?.let(display.name::equals) != false
        }.orEmpty()
        val panel = foldDisplayPanel(namedDisplays.filter(::isDisplayLit).map(::modeSize),
            namedDisplays.map(::modeSize), densityDpi, windowFallback)
        if (panel != FoldPanel.UNKNOWN) updatePanel(panel, source, namedDisplays)
    }

    private fun updatePanel(panel: FoldPanel, source: String, displays: List<Display>) {
        val before = signal.value
        if (panel == before.activePanel) return
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val handoff = before.activePanel != FoldPanel.UNKNOWN
        val phase = when {
            !handoff -> foldPhase(panel, before.angleDegrees?.let(::hingeReveal)
                ?: if (panel == FoldPanel.INNER) 1f else 0f, before.direction)
            panel == FoldPanel.INNER -> FoldPhase.HANDOFF_TO_INNER
            else -> FoldPhase.HANDOFF_TO_COVER
        }
        val next = before.copy(
            activePanel = panel,
            previousPanel = before.activePanel,
            panelEpoch = before.panelEpoch + if (handoff) 1L else 0L,
            panelChangedElapsedNs = elapsed,
            phase = phase,
        )
        signal.value = next
        val displaySummary = displays.joinToString { display ->
            "${display.displayId}:${display.state}:${display.mode.physicalWidth}x${display.mode.physicalHeight}"
        }
        val ageMs = before.receivedElapsedNs.takeIf { it > 0L }
            ?.let { (elapsed - it) / 1_000_000L }
        Log.i(TAG, "handoff source=$source from=${before.activePanel} to=$panel " +
            "angle=${before.angleDegrees} angleAgeMs=$ageMs epoch=${next.panelEpoch} displays=[$displaySummary]")
    }

    private fun modeSize(display: Display): Pair<Int, Int> =
        display.mode.physicalWidth to display.mode.physicalHeight

    private fun isDisplayLit(display: Display): Boolean = when (display.state) {
        Display.STATE_ON, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND,
        Display.STATE_ON_SUSPEND -> true
        else -> false
    }
}

@Composable
private fun rememberFoldSignal(innerDisplay: Boolean): State<FoldSignal> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val display = LocalView.current.display
    val windowPanel = rememberUpdatedState(if (innerDisplay) FoldPanel.INNER else FoldPanel.COVER)
    val windowDisplay = rememberUpdatedState(display)
    DisposableEffect(context, lifecycle) {
        var acquired = false
        fun start() {
            if (!acquired) {
                // The keyed effect below runs only when the panel changes; a restart after the
                // grace window reset the coordinator. Tell it which panel this window is on first,
                // so its start() never has to guess from a display mode.
                FoldSignalCoordinator.reportWindowPanel(windowPanel.value, windowDisplay.value)
                FoldSignalCoordinator.acquire(context); acquired = true
            }
        }
        fun stop() {
            if (acquired) { FoldSignalCoordinator.release(); acquired = false }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(innerDisplay, display?.displayId, display?.mode?.physicalWidth, display?.mode?.physicalHeight) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) FoldSignalCoordinator.reportWindowPanel(
            if (innerDisplay) FoldPanel.INNER else FoldPanel.COVER, display)
    }
    return FoldSignalCoordinator.signal
}

@Composable
internal fun rememberFoldTransition(innerDisplay: Boolean, enabled: Boolean = true): FoldTransitionState {
    if (!enabled) return rememberDisabledFoldTransition(innerDisplay)
    if (!rememberSystemFoldMotionEnabled().value) return rememberDisabledFoldTransition(innerDisplay)
    val signalState = rememberFoldSignal(innerDisplay)
    return rememberFoldTransitionFromSignal(innerDisplay, signalState)
}

@Composable
internal fun rememberDisabledFoldTransition(innerDisplay: Boolean): FoldTransitionState = remember(innerDisplay) {
    // No sensor subscription, frame follower, debug replay or handoff animation.
    // Panel orientation/layout and StandBy remain controlled independently.
    FoldTransitionState(mutableStateOf(1f), mutableStateOf(0f), mutableStateOf(1f),
        mutableStateOf(if (innerDisplay) FoldPhase.INNER_STABLE else FoldPhase.COVER_STABLE))
}

@Composable
internal fun rememberFoldTransitionFromSignal(
    innerDisplay: Boolean,
    signalState: State<FoldSignal>,
    motionEnabled: Boolean = true,
): FoldTransitionState {
    if (!motionEnabled) return rememberDisabledFoldTransition(innerDisplay)
    // Hinge samples arrive at up to 400 Hz. Composition never reads the signal: the values
    // below are derived state consumed by effects and draw-phase readers, so a hinge sample
    // no longer recomposes Home (Core.16 recomposed the whole BoxWithConstraints per reading).
    val panel = if (innerDisplay) FoldPanel.INNER else FoldPanel.COVER
    val trackingNowNs = remember(panel) { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(panel, signalState) {
        snapshotFlow { signalState.value.receivedElapsedNs }.collectLatest { received ->
            trackingNowNs.longValue = SystemClock.elapsedRealtimeNanos()
            if (panel == FoldPanel.COVER && received > 0L) {
                val remaining = COVER_FOLD_IDLE_NS - (trackingNowNs.longValue - received)
                if (remaining > 0L) delay((remaining + 999_999L) / 1_000_000L)
                // Advance even under a Compose test clock; never resurrect the old angle.
                trackingNowNs.longValue = maxOf(SystemClock.elapsedRealtimeNanos(),
                    received + COVER_FOLD_IDLE_NS)
            }
        }
    }
    val targetProgress = remember(panel, signalState) { derivedStateOf {
        foldTrackingProgress(signalState.value, panel,
            maxOf(trackingNowNs.longValue, SystemClock.elapsedRealtimeNanos()), coverGated = false)
    } }
    // Cover contact gate: 1 while separated, 0 at confirmed contact, eased between the two.
    val coverGate = remember(panel) {
        Animatable(if (Snapshot.withoutReadObservation { signalState.value.coverProjectionBlocked }) 0f else 1f)
    }
    LaunchedEffect(panel, signalState) {
        if (innerDisplay) return@LaunchedEffect
        snapshotFlow { signalState.value.coverProjectionBlocked }.collectLatest { blocked ->
            coverGate.animateTo(if (blocked) 0f else 1f, tween(COVER_GATE_MS, easing = FastOutSlowInEasing))
        }
    }
    val sensorProgress = remember(panel) {
        mutableFloatStateOf(Snapshot.withoutReadObservation { targetProgress.value })
    }
    LaunchedEffect(panel, targetProgress) {
        val follower = GlassAngleFollow()
        // Wait without scheduling frames at rest. During motion consume the latest target
        // every VSYNC rather than restarting a spring for every integer sensor reading.
        while (true) {
            snapshotFlow { targetProgress.value }.first { abs(it - sensorProgress.floatValue) > .0001f }
            var now = withFrameNanos { it / 1_000_000L }
            follower.reset(now, sensorProgress.floatValue * 174f + 1f)
            do {
                now = withFrameNanos { it / 1_000_000L }
                val degrees = follower.update(now, sensorProgress.floatValue * 174f + 1f,
                    targetProgress.value * 174f + 1f, innerDisplay)
                sensorProgress.floatValue = ((degrees - 1f) / 174f).coerceIn(0f, 1f)
            } while (abs(targetProgress.value - sensorProgress.floatValue) > .0001f)
            sensorProgress.floatValue = targetProgress.value
        }
    }

    val initialSignal = remember(panel) { Snapshot.withoutReadObservation { signalState.value } }
    val recentHandoff = remember(panel) {
        initialSignal.panelEpoch > 0L && initialSignal.activePanel == panel &&
            SystemClock.elapsedRealtimeNanos() - initialSignal.panelChangedElapsedNs < 3_500_000_000L
    }
    val arrival = remember(panel) { Animatable(if (recentHandoff) 0f else 1f) }
    val consumedEpoch = remember(panel) {
        mutableLongStateOf(if (recentHandoff) initialSignal.panelEpoch - 1L else initialSignal.panelEpoch)
    }
    LaunchedEffect(panel, signalState) {
        snapshotFlow { signalState.value.let { it.panelEpoch to it.activePanel } }
            .collectLatest { (epoch, activePanel) ->
                if (activePanel != panel) {
                    // This instance is no longer on the physical panel. Clear its
                    // residual without snapping the newly active instance mid-flight.
                    arrival.snapTo(1f)
                    return@collectLatest
                }
                if (epoch > consumedEpoch.longValue) {
                    consumedEpoch.longValue = epoch
                    arrival.snapTo(foldArrivalStart(arrival.value))
                    // Under the handoff veil the panel shows its fully tilted leaf; the arrival starts as it lifts.
                    if (FoldArrivalGate.held.value) snapshotFlow { FoldArrivalGate.held.value }.first { !it }
                    arrival.animateTo(1f, tween(if (innerDisplay) 460 else 280, easing = FastOutSlowInEasing))
                }
            }
    }

    val reveal = remember(panel, signalState) { derivedStateOf {
        // A confirmed physical endpoint bypasses even the follower/arrival
        // residual, so flat tilt cannot briefly leave the inner desktop blurred.
        if (!innerDisplay || signalState.value.innerConfirmedFlat) 1f
        else minOf(sensorProgress.floatValue, arrival.value)
    } }
    val coverFold = remember(panel, signalState) { derivedStateOf {
        // Confirmed contact clears the follower and arrival residual too, through the eased gate.
        if (innerDisplay) 0f else coverGate.value * maxOf(
            coverFoldProgress(sensorProgress.floatValue),
            1f - arrival.value,
        )
    } }
    val phase = remember(signalState) { derivedStateOf { signalState.value.phase } }
    return remember(reveal, coverFold, arrival, phase) {
        FoldTransitionState(reveal, coverFold, arrival.asState(), phase)
    }
}

/** Hinge angle behind the inner window's reveal (1 + 174·reveal, [hingeReveal]) as a leaf tilt. */
internal fun innerTiltFromReveal(reveal: Float): Float =
    if (!reveal.isFinite()) 0f else GlassProjectionMath.innerTilt(1f + 174f * reveal.coerceIn(0f, 1f))

/** Hinge angle behind the cover's fold progress ([coverFoldProgress] saturates at 119°) as a tilt. */
internal fun coverTiltFromFold(coverFold: Float): Float =
    if (!coverFold.isFinite()) 0f else GlassProjectionMath.coverTilt(1f + 174f * .68f * coverFold.coerceIn(0f, 1f))

internal fun foldLeafTilt(state: FoldTransitionState, innerDisplay: Boolean): Float =
    if (innerDisplay) innerTiltFromReveal(state.reveal.value) else coverTiltFromFold(state.coverFold.value)

/** Core.35: Apple's Hinge.position (opening angle / 180) behind the inner window's reveal. */
internal fun innerHingeFromReveal(reveal: Float): Float =
    if (!reveal.isFinite()) 1f else DuneFoldMath.hingePosition(1f + 174f * reveal.coerceIn(0f, 1f))

/** Core.35: Apple's Hinge.position behind the cover's fold progress (saturates at 119°, h ≈ .69). */
internal fun coverHingeFromFold(coverFold: Float): Float =
    if (!coverFold.isFinite()) 0f else DuneFoldMath.hingePosition(1f + 174f * .68f * coverFold.coerceIn(0f, 1f))

internal fun foldHinge(state: FoldTransitionState, innerDisplay: Boolean): Float =
    if (innerDisplay) innerHingeFromReveal(state.reveal.value) else coverHingeFromFold(state.coverFold.value)

/** Round 7: one projection for the whole Home window (wallpaper, pages, Today column, folders,
 * menus, Spotlight), applied at the launcher root. Everything on the glass is one optical image,
 * so nothing is projected twice and no translucent layer can turn black. Core.35: the projection
 * is Apple's screen Wipe at the window's hinge position. API 31–32 keep the per-pane graphicsLayer
 * fallbacks ([foldLeftPane], [foldCoverPane]). */
@Composable
internal fun Modifier.foldProjection(state: FoldTransitionState, innerDisplay: Boolean, enabled: Boolean = true): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    // Round 12: the launcher root's display rotation puts the hinge on the physical hinge edge even in the
    // frames that inherit the other panel's rotation (draw-phase read, like the hinge).
    val view = LocalView.current
    return glassProjection(hinge = { if (enabled) foldHinge(state, innerDisplay) else if (innerDisplay) 1f else 0f },
        inner = innerDisplay, rotation = { view.display?.rotation ?: android.view.Surface.ROTATION_0 })
}

/** A photo wallpaper zooms toward the viewer as the lit leaf tilts (Apple Tech Talk 111464). The
 * default dunes do not zoom: they move with Apple's own wallpaper camera ([DuneLive]). Draw-phase read. */
internal fun foldWallpaperZoom(state: FoldTransitionState, innerDisplay: Boolean): Float =
    GlassProjectionMath.wallpaperZoom(foldLeafTilt(state, innerDisplay))

/** The live sensor signal for readers outside a launcher window's fold state (the dunes). */
internal fun foldSignalState(): State<FoldSignal> = FoldSignalCoordinator.signal

/** Apple's Hinge.position for the live dunes, straight from the sensor (the dunes have their own
 * spring, [DuneFoldMotion]); the physical endpoints and the active panel stand in when the angle is
 * unknown, so a cover Home settles on the folded dunes and an inner one on the open dunes. NaN while
 * the coordinator is stopped (no panel, no angle): the next known pose is snapped to, not replayed. */
internal fun dunesHinge(signal: FoldSignal): Float = when {
    signal.innerConfirmedFlat -> 1f
    signal.coverProjectionBlocked -> 0f
    signal.angleDegrees?.isFinite() == true -> DuneFoldMath.hingePosition(signal.angleDegrees)
    signal.activePanel == FoldPanel.INNER -> 1f
    signal.activePanel == FoldPanel.COVER -> 0f
    else -> Float.NaN
}

/** Android 12 fallback and isolated leaf tests; production API 33+ projects at the launcher root. */
@Composable
internal fun Modifier.foldLeftPane(
    progress: State<Float>,
    isLeft: () -> Boolean = { true },
    blur: Boolean = true,
): Modifier {
    if (Build.VERSION.SDK_INT >= 33) return this
    return graphicsLayer {
        val p = if (isLeft()) progress.value else 1f
        val amount = 1f - p.coerceIn(0f, 1f)
        clip = amount > .001f

        // Android 12/12L fallback. The tested Xiaomi path uses AGSL on API 37;
        // this preserves a functional transition on the project's minSdk 31.
        val visual = foldVisual(p)
        transformOrigin = TransformOrigin(1f, .5f)
        // Android recommends keeping the camera just beyond a large layer's
        // width. Tying it to this pane preserves the same perspective on the
        // compact screen and the half-width inner pane.
        cameraDistance = maxOf(size.width * 1.05f, 1f)
        scaleX = visual.scaleX
        rotationY = visual.rotationY
        alpha = visual.opacity
        renderEffect = if (blur && visual.blurDp > .1f) android.graphics.RenderEffect.createBlurEffect(
            visual.blurDp * density, visual.blurDp * density, android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect() else null
    }
}

/** Applied to the complete compact cover workspace on API 31–32. Unlike the inner motion,
 * this pivots around the hinge edge ([coverHingeEdge] for the window's rotation, round 12) and
 * grows stronger as the device opens. */
@Composable
internal fun Modifier.foldCoverPane(
    progress: State<Float>,
    enabled: () -> Boolean,
    blur: Boolean = true,
): Modifier {
    if (Build.VERSION.SDK_INT >= 33) return this
    val view = LocalView.current
    return graphicsLayer {
        val amount = if (enabled()) progress.value.coerceIn(0f, 1f) else 0f
        clip = amount > .001f

        val visual = foldCoverVisual(amount)
        val leaf = FoldLeafGeometry.forWindow(size.width, size.height, inner = false,
            rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0)
        cameraDistance = maxOf(leaf.lengthPx * 1.05f, 1f)
        if (leaf.dirY == 0f) {
            // Hinge on a vertical edge: positive rotationY sends the right edge away, so the sign follows the
            // direction from the hinge to the free edge.
            transformOrigin = TransformOrigin(leaf.hingeX / size.width, .5f)
            scaleX = visual.scaleX; scaleY = 1f
            rotationY = visual.rotationY * leaf.dirX; rotationX = 0f
        } else {
            // Hinge on a horizontal edge: positive rotationX sends the top edge away.
            transformOrigin = TransformOrigin(.5f, leaf.hingeY / size.height)
            scaleY = visual.scaleX; scaleX = 1f
            rotationX = -visual.rotationY * leaf.dirY; rotationY = 0f
        }
        alpha = visual.opacity
        renderEffect = if (blur && visual.blurDp > .1f) android.graphics.RenderEffect.createBlurEffect(
            visual.blurDp * density, visual.blurDp * density, android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect() else null
    }
}

/** A solid-black aperture sits above the projected content plane. The unequal
 * top/bottom insets expose a real trapezoid, while the third side stays opaque
 * black instead of being blurred, dimmed, or pushed off-screen by overscan. */
@Composable
internal fun FoldProjectionEdges(
    progress: State<Float>,
    side: FoldShadeSide,
    cover: Boolean = false,
    enabled: () -> Boolean = { true },
) {
    // AGSL produces the trapezoid and its connected black outside region in a
    // single pass. The Canvas aperture remains only for Android 12/12L.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    val view = LocalView.current
    Canvas(Modifier.fillMaxSize()) {
        val raw = if (enabled()) progress.value else if (cover) 0f else 1f
        val amount = if (cover) raw.coerceIn(0f, 1f) else 1f - raw.coerceIn(0f, 1f)
        val edge = minOf(size.width, size.height) * foldEdgeInsetFraction(amount)
        val perspective = minOf(size.width, size.height) * foldPerspectiveInsetFraction(amount)
        if (edge <= .5f) return@Canvas
        // Round 12: the cover's free edge follows the physical hinge under the window's rotation.
        val shade = if (cover) coverShadeSide(view.display?.rotation ?: android.view.Surface.ROTATION_0) else side
        val depths = foldEdgeDepths(edge, perspective, shade)
        val horizontalHinge = shade == FoldShadeSide.TOP || shade == FoldShadeSide.BOTTOM
        // Wedges along the two edges parallel to the fold direction; depth "left" is their first end.
        fun wedge(start: Offset, along: Offset, inward: Offset, depthStart: Float, depthEnd: Float) = drawPath(Path().apply {
            moveTo(start.x, start.y)
            lineTo(start.x + along.x, start.y + along.y)
            lineTo(start.x + along.x + inward.x * depthEnd, start.y + along.y + inward.y * depthEnd)
            lineTo(start.x + inward.x * depthStart, start.y + inward.y * depthStart)
            close()
        }, Color.Black)
        if (!horizontalHinge) {
            wedge(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, 1f), depths.left, depths.right)
            wedge(Offset(0f, size.height), Offset(size.width, 0f), Offset(0f, -1f), depths.left, depths.right)
        } else {
            wedge(Offset(0f, 0f), Offset(0f, size.height), Offset(1f, 0f), depths.left, depths.right)
            wedge(Offset(size.width, 0f), Offset(0f, size.height), Offset(-1f, 0f), depths.left, depths.right)
        }
        when (shade) {
            FoldShadeSide.LEFT -> drawRect(Color.Black, size = Size(edge, size.height))
            FoldShadeSide.RIGHT -> drawRect(Color.Black, topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height))
            FoldShadeSide.TOP -> drawRect(Color.Black, size = Size(size.width, edge))
            FoldShadeSide.BOTTOM -> drawRect(Color.Black, topLeft = Offset(0f, size.height - edge),
                size = Size(size.width, edge))
        }
    }
}
