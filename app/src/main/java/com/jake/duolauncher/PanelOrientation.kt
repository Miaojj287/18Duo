package com.jake.duolauncher

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/** No logical-display-id constants: HyperOS may reuse id 0 for either panel. */
internal fun launcherPanel(
    currentArea: Long,
    deviceAreas: List<Long>,
    smallestWidthDp: Int,
    maximumWidthPx: Int = 0,
    maximumHeightPx: Int = 0,
    density: Float = 0f,
): FoldPanel {
    // Activity mode/configuration can be compatibility-adjusted after a panel handoff.
    // Use the full display bounds first, otherwise a portrait letterbox on the inner
    // screen is mistaken for the cover and keeps requesting that same letterbox.
    if (maximumWidthPx > 0 && maximumHeightPx > 0) {
        val maximumPanel = panelFromPhysicalAreas(
            listOf(maximumWidthPx.toLong() * maximumHeightPx), deviceAreas,
        )
        if (maximumPanel != FoldPanel.UNKNOWN) return maximumPanel
        if (density.isFinite() && density > 0f) {
            val fullSmallestWidthDp = minOf(maximumWidthPx, maximumHeightPx) / density
            return if (fullSmallestWidthDp >= 600f) FoldPanel.INNER else FoldPanel.COVER
        }
    }
    val physical = panelFromPhysicalAreas(listOf(currentArea), deviceAreas)
    return if (physical != FoldPanel.UNKNOWN) physical
        else if (smallestWidthDp >= 600) FoldPanel.INNER else FoldPanel.COVER
}

internal enum class StandbyDirection(val orientation: Int, val rotation: Int) {
    LEFT(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90),
    RIGHT(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, Surface.ROTATION_270)
}

internal fun standbyDirection(x: Float, y: Float): StandbyDirection? =
    if (!physicallyLandscape(x, y)) null else if (x > 0f) StandbyDirection.LEFT else StandbyDirection.RIGHT

/** lhasa's inverted tent reports gravity on -Y rather than +/-X, also after switching to the cover.
 * Require a rear-facing slope as well, so ordinary upright folding cannot enter. */
internal fun standbyPostureDirection(panel: FoldPanel, x: Float, y: Float, z: Float,
    lhasa: Boolean): StandbyDirection? = standbyDirection(x, y) ?: if (lhasa && panel != FoldPanel.UNKNOWN &&
        x.isFinite() && y.isFinite() && z.isFinite() && y <= -4.5f &&
        -y > kotlin.math.abs(x) * 1.25f && z < -1f) StandbyDirection.RIGHT else null

internal fun launcherOrientation(panel: FoldPanel, standby: Boolean = false, direction: StandbyDirection? = null,
    home: Int = homeOrientation(panel)): Int =
    if (standby) direction?.orientation ?: ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    else home

/** Below this hinge angle on the inner panel the window's rotation animation is a cut (the cover
 * is about to inherit the inner's rotation; lhasa swaps at ~5°). Round 12 removed every
 * hinge-driven orientation *request*; see [FoldOrientationPolicy.kt]. */
internal const val FOLD_JUMPCUT_DEGREES = 45f

/**
 * Home's own request outside StandBy — steady state, no hinge angle involved (round 12):
 * - Cover: portrait at every hinge angle, like iPhone Duo's cover and the original APK (C0132bg
 *   requested 1 on COVER) and Xiaomi's own launcher (mScreenOrientationOuter=PORTRAIT). Round 5's
 *   FULL_SENSOR above 45° let the cover rotate "at some angle" while opening (round 12 feedback).
 * - Inner: FULL_SENSOR. A fixed request there is letterboxed (ignoreOrientationRequest display +
 *   PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY). Round 5's ≤20° portrait pre-emption turned
 *   the inner 90° in the user's hands and, back open, FULL_SENSOR left it there when the device was
 *   flat (Android keeps the current rotation while the sensor is ambiguous).
 * The handoff frames that both pre-emptions tried to fix are hidden behind a veil instead
 * ([foldHandoffStep]).
 */
internal fun homeOrientation(panel: FoldPanel): Int =
    // Round 13: FULL_USER, not FULL_SENSOR — the inner follows the system rotation lock (owner: unfolding
    // rotated Home although rotation was locked; FULL_SENSOR overrides the user's lock).
    if (panel == FoldPanel.COVER) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_FULL_USER

/** Device axes, not the window rotation inherited from the inner activity. Flat/diagonal
 * and upright devices are not evidence of an intentional landscape StandBy posture. */
internal fun physicallyLandscape(x: Float, y: Float): Boolean =
    x.isFinite() && y.isFinite() && kotlin.math.abs(x) >= 4.5f && kotlin.math.abs(x) > kotlin.math.abs(y) * 1.25f

internal fun physicallyUpright(x: Float, y: Float): Boolean = physicallyLandscape(y, x)

internal enum class LauncherSurface { HOME, ENTERING, ROTATING, STANDBY, EXITING, FOLDING }

/**
 * What the launcher window shows around StandBy (interaction review S1). iOS enters and leaves StandBy with a plain
 * fade, so Android's rotation must never show Home or a face sideways:
 * - ENTERING: a black veil rises over Home; StandBy's orientation is requested only once it is on screen, so the
 *   platform's rotation snapshot is black.
 * - ROTATING: black while Android rotates (or until StandBy has decided to turn its own canvas).
 * - STANDBY: the faces, once the landscape canvas fits the window ([ready]).
 * - EXITING: the face stays up as the veil while the request is released, until the window has settled.
 * - FOLDING (round 12): the same black veil over Home around a panel handoff ([foldHandoffStep]); Home stays laid
 *   out under it so the new panel's configuration is already drawn when it lifts. StandBy's surfaces win.
 * - HOME: Home, revealed from under the face/veil.
 */
internal fun launcherSurface(standby: Boolean, requested: Boolean, ready: Boolean, settling: Boolean,
    foldVeil: Boolean = false): LauncherSurface = when {
    standby && !requested -> LauncherSurface.ENTERING
    standby && ready -> LauncherSurface.STANDBY
    standby -> LauncherSurface.ROTATING
    settling -> LauncherSurface.EXITING
    foldVeil -> LauncherSurface.FOLDING
    else -> LauncherSurface.HOME
}

internal fun isTentFoldStatus(value: Float): Boolean = value.isFinite() && value > 0f && value <= 1f

internal fun standbyHingePosture(angle: Float?, landscape: Boolean): Boolean =
    angle != null && angle.isFinite() && angle in 10f..80f && landscape

/** Round 22 (owner): StandBy appears after Home has stood still in a tent on the cover for five seconds. */
internal const val COVER_STANDBY_HOLD_MILLIS = 5_000L

internal class StandbyEntryGate(private val holdMillis: Long = COVER_STANDBY_HOLD_MILLIS) {
    private var lastPanel = FoldPanel.UNKNOWN
    private var eligibleSince: Long? = null
    private var dismissed = false
    fun reset() { lastPanel = FoldPanel.UNKNOWN; eligibleSince = null; dismissed = false }
    fun pause() { eligibleSince = null }
    fun retainPanel(panel: FoldPanel) { lastPanel = panel }
    fun leaveRange() { dismissed = false; eligibleSince = null }
    fun dismiss() { dismissed = true; eligibleSince = null }
    fun update(panel: FoldPanel, allowed: Boolean, physicalLandscape: Boolean, now: Long, upright: Boolean = false): Boolean {
        if (panel != lastPanel) { eligibleSince = null; lastPanel = panel; dismissed = false }
        if (upright) dismissed = false
        if (panel == FoldPanel.UNKNOWN || !allowed || !physicalLandscape || dismissed) {
            eligibleSince = null
            return false
        }
        val since = eligibleSince ?: now.also { eligibleSince = it }
        return now - since >= holdMillis
    }
}

internal class PanelOrientationController(private val activity: Activity) : DisplayManager.DisplayListener, SensorEventListener {
    val panel = mutableStateOf(FoldPanel.UNKNOWN)
    val standbyReady = mutableStateOf(false)
    val standbyDirectionReady = mutableStateOf(false)
    /** What the window shows; see [launcherSurface]. */
    val surface = mutableStateOf(LauncherSurface.HOME)
    /** Round 24: the inner screen's book-pose StandBy (clock page + photo page) is up. */
    val innerStandby = mutableStateOf(false)
    /** `Surface.ROTATION_*` the book stands in (kept while it fades out). */
    val innerStandbyTarget = mutableStateOf(INNER_BOOK_ROTATION)
    private val innerGate = StandbyEntryGate()
    private val gravity = floatArrayOf(Float.NaN, Float.NaN, Float.NaN)
    private val innerStandbyTask = Runnable { updateInnerStandby() }
    /** `Surface.ROTATION_*` StandBy faces (null = not known yet); kept while exiting so the face doesn't turn. */
    val standbyTarget = mutableStateOf<Int?>(null)
    private val manager = activity.applicationContext.getSystemService(DisplayManager::class.java)
    private val homeUsb = if (android.os.Build.DEVICE == "lhasa")
        UsbCoverArea(activity.applicationContext) { updateStandby() } else null
    private var coverRequestPending = false
    private var coverRequestFailed = false
    private var coverRequestedAt = -1L
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val orientationSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val foldStatusSensor = sensors.getSensorList(Sensor.TYPE_ALL).firstOrNull {
        it.stringType == "xiaomi.sensor.fold_status"
    }
    private var hingeAngle: Float? = null
    // The hinge now reports at up to 400 Hz; StandBy's gates (700 ms entry) need ~20 Hz at most.
    private val hingeStandbyUpdate = Runnable { updateStandby() }
    private val hingeListener: (HingeSample) -> Unit = {
        hingeAngle = it.degrees
        if (!handler.hasCallbacks(hingeStandbyUpdate)) handler.postDelayed(hingeStandbyUpdate, HINGE_STANDBY_COALESCE_MILLIS)
    }
    // The hinge is read whenever Home is started (not only with StandBy on): it times the handoff veil.
    private var homeAngle: Float? = null
    private var homeRequest = homeOrientation(FoldPanel.UNKNOWN)
    private val homeHingeListener: (HingeSample) -> Unit = { sample ->
        homeAngle = sample.degrees
        if (!standbyAngles.contains(sample.degrees)) entryGate.leaveRange()
        if (panel.value == FoldPanel.INNER && sample.degrees <= FOLD_JUMPCUT_DEGREES) foldHandoffRotationAnimation()
        updateFold()
    }
    // ---- Round 12: handoff veil + orientation policy (FoldOrientationPolicy.kt). All state is the pure
    // FoldHandoffState; this class only feeds it the activity's configuration and acts on its outputs.
    private var fold = FoldHandoffState()
    private var foldGravity = FoldGravity.UNKNOWN
    private var foldConfigKey = Long.MIN_VALUE
    private var foldFrameDrawn = false
    private var foldFrameSerial = 0
    private var foldFrameCommitPending = false
    private val foldTick = Runnable { updateFold() }
    /** Registered sampling period (µs), null while off. */
    private var foldAccelerometerRate: Int? = null
    /** The inner's own auto-rotate switch as last read on the inner (HyperOS mirrors it into the public key there). */
    private var lastInnerRotationLocked = false
    /** The user flips auto-rotate (shade tile, Settings): a hold must end, or may start, at once. */
    private val rotationSettingsObserver by lazy {
        object : android.database.ContentObserver(handler) { override fun onChange(selfChange: Boolean) = updateFold() }
    }
    private val foldAccelerometer = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 2) return
            val gravity = foldGravity(event.values[0], event.values[1])
            if (gravity != foldGravity) {
                foldGravity = gravity
                if (fold.phase != FoldHandoffPhase.IDLE || fold.hold) updateFold()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private var tent = false
    private var manualStandby = false
    val tentSupported get() = foldStatusSensor != null
    private val entryGate = StandbyEntryGate()
    private val stillness = StillnessDetector()
    private val homeIdle = HomeIdleGate()
    private var direction: StandbyDirection? = null
    val automaticStandby get() = standbyReady.value && !manualStandby
    val standbyRotation get() = if (direction == StandbyDirection.RIGHT) -90f else 90f
    private var upright = false
    private var standbyAllowed = false
    private var standbySensors = false
    private var standbyAngles = StandbyAngles()
    private var sampleAt = -1L
    private var samplesAfter = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var lastOrientationRequest: Int? = null
    private var lastSampleLog = 0L
    private val refreshTask = Runnable { refresh() }
    private val standbySettleTask = Runnable { updateStandby() }
    // StandBy's orientation request is in effect (sent after Home was veiled).
    private var standbyRequested = false
    // Entering: waiting for the veil over Home before requesting the orientation.
    private var veilPending = false
    // The platform didn't rotate for StandBy (ignoreOrientationRequest letterbox, a window that stays portrait):
    // keep Home's request and let StandBy turn its own canvas.
    private var selfRotating = false
    // Exiting: the request is released; the face stays up until the window leaves StandBy's orientation.
    private var settling = false
    private var settleOrientation = android.content.res.Configuration.ORIENTATION_UNDEFINED
    private val veilTimeout = Runnable { standbyVeiled() }
    private val rotationCheck = Runnable {
        if (standbyRequested && !selfRotating && standbyReady.value && !standbyDirectionReady.value) {
            startSelfRotating("no rotation"); updateStandby()
        }
    }
    private val settleTimeout = Runnable { finishSettling() }
    private val restoreRotationAnimation = Runnable { if (!standbyReady.value && !settling) standbyRotationAnimation(false) }
    // ---- r13 invariant: Home is never letterboxed on the inner. After every request/configuration change while Home
    // owns the request, the window is compared with the panel; one that stays smaller is logged and the platform is
    // nudged with a different request (a free one, then the display's own orientation, which cannot letterbox).
    private var innerLitAt = -1L
    private var innerLitKey = -1L
    private var letterboxSince = -1L
    private var letterboxKicks = 0
    private var letterboxGaveUp = false
    private var kickRequest: Int? = null
    private var kickAt = -1L
    private var lastRequestAt = -1L
    private var letterboxWatchKey: List<Int>? = null
    private val letterboxCheck = Runnable { checkLetterbox() }

    fun start() {
        if (!started) {
            started = true
            resetStandby(clearDismissal = false)
            manager.registerDisplayListener(this, handler)
            syncStandbySensors()
            HingeAngles.acquire(activity, homeHingeListener)
            respectsOrientationRequests = readRespectsOrientationRequests()
            Log.i("18DuoPanel", "respect-orientation override=$respectsOrientationRequests")
            for (key in listOf(android.provider.Settings.System.ACCELEROMETER_ROTATION, android.provider.Settings.System.USER_ROTATION,
                    INNER_ACCELEROMETER_ROTATION, INNER_USER_ROTATION)) runCatching {
                activity.contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(key), false,
                    rotationSettingsObserver)
            }
        }
        refresh()
    }
    /**
     * Posture sensors only while StandBy can actually show (automatic Home StandBy allowed, or a manual one open):
     * with StandBy off, Home holds no accelerometer, fold-status or hinge registration of its own.
     */
    private fun syncStandbySensors() {
        val wanted = started && (standbyAllowed || manualStandby)
        if (started && standbyAllowed) homeUsb?.start() else {
            homeUsb?.stop(); coverRequestPending = false; coverRequestFailed = false
        }
        if (wanted == standbySensors) return
        standbySensors = wanted
        if (wanted) {
            foldStatusSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            // 10 Hz (vs ~5 Hz NORMAL) so the stillness window sees enough samples to tell a table from a hand.
            orientationSensor?.let { sensors.registerListener(this, it, 100_000, handler) }
            HingeAngles.acquire(activity, hingeListener)
        } else {
            sensors.unregisterListener(this)
            HingeAngles.release(hingeListener)
            hingeAngle = null; tent = false; direction = null; sampleAt = -1L; stillness.reset()
        }
    }
    fun stop() {
        if (started) {
            manager.unregisterDisplayListener(this)
            HingeAngles.release(homeHingeListener)
            activity.contentResolver.unregisterContentObserver(rotationSettingsObserver)
        }
        homeAngle = null
        started = false
        homeUsb?.stop(); coverRequestPending = false; coverRequestFailed = false
        syncStandbySensors()
        resetFold()
        tent = false; hingeAngle = null; manualStandby = false
        resetStandby(clearDismissal = false)
        handler.removeCallbacks(refreshTask)
        handler.removeCallbacks(standbySettleTask)
        handler.removeCallbacks(hingeStandbyUpdate)
        endLetterboxWatch(); letterboxKicks = 0; letterboxGaveUp = false
        // Nothing is drawn while stopped: drop a pending veil, finish an exit (start() re-evaluates the request).
        if (veilPending) { veilPending = false; handler.removeCallbacks(veilTimeout); standbyRotationAnimation(false) }
        finishSettling(update = false)
    }
    fun destroy() { homeUsb?.destroy() }
    fun refresh() {
        val display = activity.display ?: return
        val area = display.mode.physicalWidth.toLong() * display.mode.physicalHeight
        val areas = manager.displays.filter { it.isValid && it.name == display.name }
            .map { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        val maximum = activity.windowManager.maximumWindowMetrics
        val bounds = maximum.bounds
        val density = if (android.os.Build.VERSION.SDK_INT >= 34) maximum.density
            else activity.resources.displayMetrics.density
        val current = launcherPanel(area, areas, activity.resources.configuration.smallestScreenWidthDp,
            bounds.width(), bounds.height(), density)
        if (current != panel.value) {
            Log.i("18DuoPanel", "panel=$current max=${bounds.width()}x${bounds.height()} density=$density compatSw=${activity.resources.configuration.smallestScreenWidthDp} modeArea=$area")
            // Fallback when no near-closed hinge sample preceded the swap: whatever rotation the
            // portrait request still causes on the cover is a cut, never a quarter-turn.
            if (panel.value == FoldPanel.INNER && current == FoldPanel.COVER) foldHandoffRotationAnimation()
            // A helper-initiated inner → cover switch keeps the clock visible across the handoff.
            // A user's dismissal survives either panel switch until the angle leaves its range.
            val seamless = coverRequestPending && panel.value == FoldPanel.INNER && current == FoldPanel.COVER
            entryGate.retainPanel(current)
            if (!seamless) resetStandby(clearDismissal = false)
            endLetterboxWatch(); letterboxKicks = 0; letterboxGaveUp = false
        }
        panel.value = current
        // Exit settles when the window leaves StandBy's orientation (the timeout covers a window that never rotates).
        if (settling && activity.resources.configuration.orientation != settleOrientation) finishSettling(update = false)
        updateFold()
        updateStandby()
    }

    /**
     * Steps the handoff policy with the activity's current configuration; called on every hinge sample, on every
     * display/configuration change, on a gravity class change, on frame commits while settling and on the timeout.
     * One `18DuoFold` line per decision change.
     */
    private fun updateFold() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()
        val configuration = activity.resources.configuration
        val rotation = activity.display?.rotation ?: Surface.ROTATION_0
        // Round 15: on the inner in every phase (a hold must never stand letterboxed); never in multi-window.
        val letterboxed = (fold.phase == FoldHandoffPhase.INNER_SETTLING || panel.value == FoldPanel.INNER && fold.hold) &&
            !activity.isInMultiWindowMode && standbyLetterboxed()
        // A frame counts only if it was committed after the configuration it is checked against.
        val key = (panel.value.ordinal.toLong() shl 8) or (configuration.orientation.toLong() shl 4) or rotation.toLong()
        if (key != foldConfigKey) { foldConfigKey = key; foldFrameDrawn = false; foldFrameSerial++ }
        val input = FoldHandoffInput(panel.value, homeAngle, foldGravity, configuration.orientation, rotation, letterboxed,
            foldFrameDrawn, standbyActive = standbyReady.value || standbyRequested || veilPending || settling,
            // r13: never a fixed rotation on the inner. Its display ignores orientation requests (dumpsys:
            // ignoreOrientationRequest=true), so a fixed request cannot rotate it, only letterbox Home (core30.1 log
            // 23:38:16, photo user-photos-r13/4.jpg). Round 15: LOCKED is honoured there and only holds the current
            // rotation; a panel that letterboxes even that gets free requests only.
            fixedRequestsIgnored = panel.value in foldFixedRequestIgnoredPanels, now = now,
            rotationLocked = innerRotationLocked(), innerUserRotation = innerUserRotation(),
            fixedHonoured = panel.value == FoldPanel.INNER && respectsOrientationRequests == true)
        val before = fold
        val step = foldHandoffStep(before, input)
        fold = step.state
        if (fold.letterboxed && !before.letterboxed) foldFixedRequestIgnoredPanels += panel.value
        if (step.changed) Log.i("18DuoFold", foldLogLine(fold, input))
        // Round 13 diagnostics: does the system still turn the inner after the veil lifted (the owner's "展开会自动旋转")?
        if (before.phase == FoldHandoffPhase.INNER_SETTLING && !fold.veil) { innerLitAt = now; innerLitKey = key }
        else if (innerLitAt >= 0L && panel.value == FoldPanel.INNER && key != innerLitKey && now - innerLitAt <= 4_000L) {
            Log.w("18DuoFold", "inner turned after lift +${now - innerLitAt}ms config=${configuration.orientation} rotation=$rotation " +
                "opening=${before.openingGravity} gravity=$foldGravity locked=${input.rotationLocked}")
            innerLitKey = key
        }
        handler.removeCallbacks(foldTick)
        if (fold.veil) {
            val limit = foldHandoffLimit(fold)
            handler.postDelayed(foldTick, (limit - (now - fold.since)).coerceIn(1L, limit))
            if (!foldFrameDrawn && (fold.phase == FoldHandoffPhase.COVER_SETTLING || fold.phase == FoldHandoffPhase.INNER_SETTLING))
                armFoldFrameCommit()
        } else if (fold.hold && fold.poseSince >= 0L) {
            // The release comes from time alone once the pose is steady (the accelerometer only reports class changes).
            handler.postDelayed(foldTick, (fold.poseSince + INNER_RELEASE_POSE_MILLIS - now).coerceAtLeast(1L))
        }
        syncFoldAccelerometer(when {
            panel.value == FoldPanel.COVER && (homeAngle ?: 0f) >= FOLD_GRAVITY_ARM_DEGREES ||
                fold.phase == FoldHandoffPhase.OPENING_VEILED || fold.phase == FoldHandoffPhase.INNER_SETTLING ->
                SensorManager.SENSOR_DELAY_GAME
            // A held inner, open: is it still lying on the table, or held up (the release, [INNER_RELEASE_POSE_MILLIS])?
            panel.value == FoldPanel.INNER && fold.hold && (homeAngle ?: 0f) >= INNER_RELEASE_ABOVE_DEGREES ->
                SensorManager.SENSOR_DELAY_UI
            else -> null
        })
        // The panel's arrival motion ("takes Home back" / hinge flattening) waits under the veil and plays as it lifts.
        FoldArrivalGate.held.value = fold.veil
        if (before.veil != fold.veil || before.request != fold.request || before.hold != fold.hold) updateStandby()
    }

    private fun armFoldFrameCommit() {
        if (foldFrameCommitPending) return
        val view = activity.window?.decorView ?: return
        foldFrameCommitPending = true
        val serial = foldFrameSerial
        view.viewTreeObserver.registerFrameCommitCallback {
            foldFrameCommitPending = false
            if (serial == foldFrameSerial) foldFrameDrawn = true
            if (fold.veil) updateFold()
        }
        view.invalidate()
    }

    /** Gravity for the unfold's pose (cover past [FOLD_GRAVITY_ARM_DEGREES] until settled) and a held inner's release. */
    private fun syncFoldAccelerometer(rate: Int?) {
        if (rate == foldAccelerometerRate) return
        if (foldAccelerometerRate != null) sensors.unregisterListener(foldAccelerometer)
        foldAccelerometerRate = rate
        if (rate != null) orientationSensor?.let { sensors.registerListener(foldAccelerometer, it, rate, handler) }
        // A new registration reports again; the class from the old one is kept only while still sampling.
        if (rate == null) foldGravity = FoldGravity.UNKNOWN
    }

    private fun resetFold() {
        handler.removeCallbacks(foldTick)
        syncFoldAccelerometer(null)
        if (fold.veil) Log.i("18DuoFold", "phase=IDLE panel=${panel.value} reason=stopped")
        fold = FoldHandoffState()
        foldConfigKey = Long.MIN_VALUE; foldFrameDrawn = false
        FoldArrivalGate.held.value = false
    }
    private fun resetStandby(clearDismissal: Boolean = true) {
        if (clearDismissal) entryGate.reset() else entryGate.pause()
        handler.removeCallbacks(standbySettleTask)
        standbyReady.value = false
        standbyDirectionReady.value = false
        innerStandby.value = false; innerGate.pause(); handler.removeCallbacks(innerStandbyTask)
        gravity.fill(Float.NaN)
        direction = null
        upright = false; sampleAt = -1L; samplesAfter = SystemClock.elapsedRealtime()
        stillness.reset()
    }
    fun setStandbyAllowed(allowed: Boolean) {
        standbyAllowed = allowed
        syncStandbySensors()
        updateStandby()
    }
    fun setStandbyAngles(angles: StandbyAngles) {
        standbyAngles = angles
        updateStandby()
    }
    fun enterStandby() {
        manualStandby = true
        syncStandbySensors()
        updateStandby()
    }
    /** A touch on Home (or a new intent): automatic StandBy waits until Home has been idle again. */
    fun noteUserActivity() {
        homeIdle.noteInput(SystemClock.elapsedRealtime())
        if (!standbyReady.value) entryGate.pause()
        if (!innerStandby.value) innerGate.pause()
    }
    /** Home reports open Spotlight / sheets / folders / menus / edit mode / drags; StandBy never takes those over. */
    fun setHomeBusy(busy: Boolean) {
        if (homeIdle.busy == busy) return
        homeIdle.busy = busy
        if (busy && !standbyReady.value) entryGate.pause()
        updateStandby()
    }
    fun dismissStandby() {
        manualStandby = false
        entryGate.dismiss()
        homeUsb?.release()
        coverRequestPending = false
        syncStandbySensors()
        updateStandby()
    }
    private fun updateStandby() {
        val now = SystemClock.elapsedRealtime()
        val fresh = started && sampleAt >= samplesAfter && now - sampleAt in 0L..1000L
        val previousReady = standbyReady.value
        // Stillness and an idle, focused Home gate entry only: once StandBy shows, touching it (and nudging the
        // phone) must not exit. A dialog, the shade or another window on top takes our window focus.
        // Round 22 (owner): only on the cover, Home in front.
        val eligible = started && standbyAllowed && panel.value == FoldPanel.COVER &&
            standbyAngles.contains(hingeAngle) && fresh && direction != null &&
            (previousReady || (stillness.isStill(now) && homeIdle.idle(now) && activity.hasWindowFocus()))
        standbyReady.value = manualStandby || entryGate.update(panel.value, eligible, eligible, now, fresh && upright)
        if (!standbyAngles.contains(hingeAngle)) coverRequestFailed = false
        if (homeUsb?.message != null) coverRequestFailed = true
        val handoffGrace = now - coverRequestedAt in 0L..2_000L &&
            standbyAngles.contains(hingeAngle) && direction != null && homeUsb?.available == true
        if (coverRequestPending && (!started || !standbyAllowed || manualStandby || homeUsb?.available != true ||
                (!standbyReady.value && !handoffGrace))) {
            homeUsb?.release()
            coverRequestPending = false
        } else if (!coverRequestPending && !coverRequestFailed && standbyReady.value && !manualStandby &&
            panel.value == FoldPanel.INNER && homeUsb?.available == true) {
            homeUsb.configure(standbyAngles)
            homeUsb.requestCoverStandby()
            coverRequestPending = true
            coverRequestedAt = now
        }
        handler.removeCallbacks(standbySettleTask)
        if (eligible && !standbyReady.value) handler.postDelayed(standbySettleTask, 100L)
        val currentDirection = if (fresh) direction else null
        val want = standbyReady.value
        if (previousReady != want)
            Log.i("18DuoStandby", "ready=$want panel=${panel.value} angle=$hingeAngle direction=$currentDirection")
        when {
            // Enter: the request waits for the veil over Home (standbyVeiled), so Android's rotation snapshot is a
            // black frame, never Home; the crossfade attribute reaches WM with the veil's frames, before the request.
            want && !standbyRequested && !veilPending -> {
                veilPending = true
                standbyTarget.value = currentDirection?.rotation
                handler.removeCallbacks(restoreRotationAnimation)
                standbyRotationAnimation(true)
                handler.postDelayed(veilTimeout, VEIL_TIMEOUT_MILLIS)
            }
            !want && veilPending -> {
                veilPending = false
                handler.removeCallbacks(veilTimeout)
                standbyRotationAnimation(false)
            }
            // Exit: StandBy's own face is the veil. Release now; the face stays until the window has settled.
            !want && standbyRequested -> {
                standbyRequested = false
                handler.removeCallbacks(rotationCheck)
                settling = true
                settleOrientation = activity.resources.configuration.orientation
                handler.removeCallbacks(settleTimeout)
                handler.postDelayed(settleTimeout, if (selfRotating) 0L else SETTLE_TIMEOUT_MILLIS)
            }
        }
        if (want && currentDirection != null) standbyTarget.value = currentDirection.rotation
        // Round 12: the handoff policy owns Home's request (portrait cover, free inner); round 15: also while it holds
        // the inner's rotation (LOCKED) through a fold or lying flat.
        homeRequest = if (fold.veil || fold.hold) fold.request else homeOrientation(panel.value)
        // A watchdog nudge only stands while Home owns the request (never over StandBy or a fold veil).
        if (kickRequest != null && !homeOwnsRequest()) kickRequest = null
        val requested = if (standbyRequested && !selfRotating) launcherOrientation(panel.value, true, currentDirection)
            else kickRequest ?: launcherOrientation(panel.value, home = homeRequest)
        if (lastOrientationRequest != requested) {
            lastOrientationRequest = requested
            lastRequestAt = now
            activity.requestedOrientation = requested
        }
        watchLetterbox()
        // A letterboxed landscape window inside a portrait panel means the platform won't rotate for StandBy.
        if (standbyRequested && !selfRotating && standbyLetterboxed()) { startSelfRotating("letterboxed"); updateStandby(); return }
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // A stale landscape configuration can still belong to the opposite side.
        val ready = want && standbyRequested && (selfRotating || (landscape && !standbyLetterboxed() &&
            (currentDirection == null || activity.display?.rotation == currentDirection.rotation)))
        // The side Android actually chose (sensor landscape): an exiting face keeps facing it.
        if (ready && !selfRotating) activity.display?.rotation?.let { standbyTarget.value = it }
        // Also after a side flip mid-session: a rotation that never comes falls back to turning the canvas.
        if (want && standbyRequested && !selfRotating && !ready && !handler.hasCallbacks(rotationCheck))
            handler.postDelayed(rotationCheck, ROTATION_TIMEOUT_MILLIS)
        standbyDirectionReady.value = ready
        surface.value = launcherSurface(want, standbyRequested, ready, settling, foldVeil = fold.veil)
        updateInnerStandby()
    }

    /**
     * Round 24 (owner): Home on the inner, the device standing open like a book on a table, untouched for the same five
     * seconds as the cover's tent → the clock page and a photo page (owner photo 1). Leaves when the pose does or on a tap.
     */
    private fun updateInnerStandby() {
        handler.removeCallbacks(innerStandbyTask)
        val now = SystemClock.elapsedRealtime()
        val fresh = started && sampleAt >= samplesAfter && now - sampleAt in 0L..1000L
        val rotation = if (fresh) innerStandbyRotation(homeAngle, gravity[0], gravity[1], gravity[2]) else null
        val shown = innerStandby.value
        val eligible = started && standbyAllowed && panel.value == FoldPanel.INNER && rotation != null &&
            !activity.isInMultiWindowMode && surface.value == LauncherSurface.HOME &&
            (shown || (stillness.isStill(now) && homeIdle.idle(now) && activity.hasWindowFocus()))
        val next = innerGate.update(panel.value, eligible, eligible, now, upright = fresh && rotation == null)
        if (rotation != null && (next && !shown || !next)) innerStandbyTarget.value = rotation
        if (next != shown) {
            Log.i("18DuoStandby", "inner=$next angle=$homeAngle gravity=${gravity.toList()} rotation=$rotation")
            innerStandby.value = next
        }
        if (eligible && !next) handler.postDelayed(innerStandbyTask, 200L)
    }
    fun dismissInnerStandby() {
        innerGate.dismiss()
        if (innerStandby.value) Log.i("18DuoStandby", "inner=false dismissed")
        innerStandby.value = false
    }

    /** Home is veiled (black on screen): send StandBy's orientation request now (or after [VEIL_TIMEOUT_MILLIS]). */
    fun standbyVeiled() {
        if (surface.value == LauncherSurface.FOLDING) {
            // The fold veil needs no request of its own; the swap triggers it. Record when it was on screen.
            Log.i("18DuoFold", "veil committed panel=${panel.value} angle=$homeAngle phase=${fold.phase}")
            return
        }
        handler.removeCallbacks(veilTimeout)
        if (!veilPending) return
        veilPending = false
        standbyRequested = true
        // A panel that ignored StandBy's request before is not asked again this run: no rotation, the canvas turns.
        selfRotating = panel.value in selfRotatingPanels
        updateStandby()
    }

    private fun startSelfRotating(reason: String) {
        selfRotating = true
        selfRotatingPanels += panel.value
        handler.removeCallbacks(rotationCheck)
        Log.i("18DuoStandby", "orientation request not honoured ($reason): StandBy turns its own canvas")
    }

    private fun finishSettling(update: Boolean = true) {
        handler.removeCallbacks(settleTimeout)
        if (!settling) return
        settling = false
        selfRotating = false
        // WM picks the rotation animation when the transition becomes ready, i.e. after our first frame in the new
        // orientation: keep the crossfade until that rotation is long over.
        handler.removeCallbacks(restoreRotationAnimation)
        handler.postDelayed(restoreRotationAnimation, ROTATION_ANIMATION_RESTORE_MILLIS)
        if (update) updateStandby()
    }

    /** iOS fades into and out of StandBy; ordinary Home rotations keep the platform rotation. */
    private fun standbyRotationAnimation(crossfade: Boolean) {
        setRotationAnimation(if (crossfade) android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_CROSSFADE
            else android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE)
    }

    /** Around a fold onto the cover: if a rotation still happens it must be a cut, not the platform's
     * quarter-turn of a sideways Home. StandBy's crossfade keeps priority. */
    private fun foldHandoffRotationAnimation() {
        if (veilPending || standbyRequested || settling || standbyReady.value) return
        setRotationAnimation(android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_JUMPCUT)
        handler.removeCallbacks(restoreRotationAnimation)
        handler.postDelayed(restoreRotationAnimation, ROTATION_ANIMATION_RESTORE_MILLIS)
    }

    private fun setRotationAnimation(animation: Int) {
        val window = activity.window ?: return
        val attributes = window.attributes
        if (attributes.rotationAnimation != animation) {
            attributes.rotationAnimation = animation
            window.attributes = attributes
        }
    }

    /** Settings → auto-rotate off. Read per step: the user may toggle it from the shade at any time. */
    private fun systemRotationLocked(): Boolean = runCatching {
        android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) == 0
    }.getOrDefault(false)

    /**
     * Round 15: the INNER panel's auto-rotate switch. HyperOS keeps one per panel (phone 2026-10-22: `accelerometer_rotation
     * _outer=0`, `_inner=1`) and mirrors the panel in use into the public key — read on the cover, that key is the cover's
     * lock, which made "the system's lock" and the inner's behaviour disagree. Elsewhere: the public key while on the inner.
     */
    private fun innerRotationLocked(): Boolean {
        systemInt(INNER_ACCELEROMETER_ROTATION)?.let { return it == 0 }
        if (panel.value == FoldPanel.INNER) lastInnerRotationLocked = systemRotationLocked()
        return lastInnerRotationLocked
    }

    /** The inner's locked rotation (what the system shows there with auto-rotate off), when it can be read. */
    private fun innerUserRotation(): Int? = systemInt(INNER_USER_ROTATION)
        ?: if (panel.value == FoldPanel.INNER) systemInt(android.provider.Settings.System.USER_ROTATION) else null

    /**
     * Round 15b: OVERRIDE_RESPECT_REQUESTED_ORIENTATION (236283604) for 18Duo, which makes the inner's
     * ignoreOrientationRequest display honour Home's fixed requests (HyperOS `AppCompatOrientationOverrides
     * .isOverrideRespectRequestedOrientationEnabled`). Set per app with `adb shell am compat enable 236283604 <package>`.
     * Read once per start by reflection; null when it cannot be read — then nothing fixed is ever requested.
     */
    private var respectsOrientationRequests: Boolean? = null
    private fun readRespectsOrientationRequests(): Boolean? = runCatching {
        Class.forName("android.app.compat.CompatChanges").getMethod("isChangeEnabled", Long::class.javaPrimitiveType)
            .invoke(null, OVERRIDE_RESPECT_REQUESTED_ORIENTATION) as Boolean
    }.recoverCatching {
        Class.forName("android.compat.Compatibility").getMethod("isChangeEnabled", Long::class.javaPrimitiveType)
            .invoke(null, OVERRIDE_RESPECT_REQUESTED_ORIENTATION) as Boolean
    }.onFailure { Log.w("18DuoPanel", "respect-orientation override unreadable: $it") }.getOrNull()

    private fun systemInt(key: String): Int? = runCatching {
        android.provider.Settings.System.getInt(activity.contentResolver, key)
    }.getOrNull()

    private fun homeOwnsRequest() = started && !fold.veil && !standbyRequested && !veilPending && !settling &&
        !standbyReady.value && !activity.isInMultiWindowMode

    /** Cheap: re-checks only when the panel, the window's size/orientation, the rotation or our request changed. */
    private fun watchLetterbox() {
        if (!homeOwnsRequest()) { if (letterboxSince >= 0L || letterboxWatchKey != null) endLetterboxWatch(); return }
        val configuration = activity.resources.configuration
        val key = listOf(panel.value.ordinal, configuration.screenWidthDp, configuration.screenHeightDp,
            configuration.orientation, activity.display?.rotation ?: -1, lastOrientationRequest ?: Int.MIN_VALUE)
        if (key == letterboxWatchKey) return
        letterboxWatchKey = key
        handler.removeCallbacks(letterboxCheck)
        handler.post(letterboxCheck)
    }

    private fun endLetterboxWatch() {
        handler.removeCallbacks(letterboxCheck)
        letterboxSince = -1L; letterboxWatchKey = null; kickRequest = null
    }

    private fun checkLetterbox() {
        if (!homeOwnsRequest()) return
        val now = SystemClock.elapsedRealtime()
        val letterboxed = standbyLetterboxed()
        kickRequest?.let { kick ->
            // Hold a nudge ≥ 1 s so the hand-back is never counted as an orientation-request loop.
            val held = now - kickAt
            if (held < LETTERBOX_KICK_HOLD_MILLIS) { handler.postDelayed(letterboxCheck, LETTERBOX_KICK_HOLD_MILLIS - held); return }
            kickRequest = null
            Log.i("18DuoPanel", "letterbox kick released ${orientationRequestName(kick)} -> " +
                "${orientationRequestName(homeRequest)} letterboxed=$letterboxed ${windowState()}")
            updateStandby()
            return
        }
        if (!letterboxed) {
            if (letterboxSince >= 0L) Log.i("18DuoPanel", "letterbox cleared panel=${panel.value} " +
                "after=${now - letterboxSince}ms kicks=$letterboxKicks ${windowState()}")
            letterboxSince = -1L
            return
        }
        if (letterboxSince < 0L) {
            letterboxSince = now
            Log.w("18DuoPanel", "letterboxed panel=${panel.value} ${windowState()}")
        }
        // The platform's own reaction first, and ≥ 1 s after our last request.
        val wait = maxOf(LETTERBOX_GRACE_MILLIS - (now - letterboxSince), LETTERBOX_GRACE_MILLIS - (now - lastRequestAt))
        if (wait > 0L) { handler.postDelayed(letterboxCheck, wait); return }
        if (panel.value != FoldPanel.INNER) return // the cover's portrait is the policy: logged only
        if (lastOrientationRequest == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            // Round 15: this panel letterboxes even a hold. Free requests only from now on (the policy releases it).
            Log.w("18DuoPanel", "hold letterboxed: no more holds on ${panel.value} ${windowState()}")
            foldFixedRequestIgnoredPanels += panel.value
            updateFold()
            return
        }
        if (letterboxKicks >= LETTERBOX_MAX_KICKS) {
            if (!letterboxGaveUp) Log.e("18DuoPanel", "letterbox persists after $letterboxKicks kicks ${windowState()} " +
                "(adb shell dumpsys activity activities | grep -iE 'letterbox|orientation')")
            letterboxGaveUp = true
            return
        }
        letterboxKicks++
        // 1: another free request (UNSPECIFIED, which also follows the rotation lock) makes WM re-resolve with no fixed
        //    orientation; 2+: the display's own current orientation (requested == parent: never letterboxed), then free.
        val kick = if (letterboxKicks == 1) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else fixedOrientationForRotation(activity.display?.rotation ?: Surface.ROTATION_0)
        Log.w("18DuoPanel", "letterbox kick #$letterboxKicks ${orientationRequestName(kick)} ${windowState()}")
        kickRequest = kick; kickAt = now
        updateStandby()
        handler.removeCallbacks(letterboxCheck)
        handler.postDelayed(letterboxCheck, LETTERBOX_KICK_HOLD_MILLIS)
    }

    /** What the phone log needs to tell "the platform dropped our request" (server != sent) from "it ignores it". */
    private fun windowState(): String {
        val current = activity.windowManager.currentWindowMetrics.bounds
        val maximum = activity.windowManager.maximumWindowMetrics.bounds
        val server = runCatching { activity.requestedOrientation }.getOrDefault(Int.MIN_VALUE)
        return "window=${current.width()}x${current.height()} max=${maximum.width()}x${maximum.height()} " +
            "rotation=${activity.display?.rotation} config=${activity.resources.configuration.orientation} " +
            "sent=${lastOrientationRequest?.let(::orientationRequestName)} server=${orientationRequestName(server)} " +
            "multiWindow=${activity.isInMultiWindowMode}"
    }

    /** The window is smaller than the panel area it could fill: a fixed-orientation letterbox. */
    private fun standbyLetterboxed(): Boolean {
        val current = activity.windowManager.currentWindowMetrics.bounds
        val maximum = activity.windowManager.maximumWindowMetrics.bounds
        return current.width() < maximum.width() || current.height() < maximum.height()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.isEmpty()) return
        if (event.sensor == foldStatusSensor) {
            // lhasa's first field is a binary closure signal; posture is field 4.
            // Other devices with a single fold-status field follow their advertised value.
            val posture = if (android.os.Build.DEVICE == "lhasa") event.values.getOrNull(4) else event.values[0]
            val nextTent = posture?.let(::isTentFoldStatus) == true
            if (nextTent != tent) Log.i("18DuoStandby", "posture=$posture tent=$nextTent")
            tent = nextTent
            updateStandby()
            return
        }
        if (event.values.size < 2) return
        val timestamp = event.timestamp / 1_000_000L
        if (timestamp < samplesAfter) return // queued samples from before the panel handoff are not evidence
        sampleAt = timestamp
        stillness.add(timestamp, event.values[0], event.values[1], event.values.getOrElse(2) { Float.NaN })
        gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values.getOrElse(2) { Float.NaN }
        direction = standbyPostureDirection(panel.value, event.values[0], event.values[1],
            event.values.getOrElse(2) { Float.NaN }, android.os.Build.DEVICE == "lhasa")
        upright = direction == null && physicallyUpright(event.values[0], event.values[1])
        if (standbyAllowed && timestamp - lastSampleLog >= 2000L) {
            lastSampleLog = timestamp
            Log.d("18DuoStandby", "sample panel=${panel.value} angle=$hingeAngle gravity=${event.values.take(3)} direction=$direction still=${stillness.isStill(timestamp)} " +
                "idle=${homeIdle.idle(SystemClock.elapsedRealtime())} focus=${activity.hasWindowFocus()}")
        }
        updateStandby()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun schedule() { if (started) { handler.removeCallbacks(refreshTask); handler.post(refreshTask) } }
    override fun onDisplayAdded(displayId: Int) = schedule()
    override fun onDisplayRemoved(displayId: Int) = schedule()
    override fun onDisplayChanged(displayId: Int) = schedule()

    private companion object {
        /** Backstop if the veil's frame never commits (window hidden). */
        const val VEIL_TIMEOUT_MILLIS = 900L
        /** No landscape StandBy window by then: the platform ignored the request. */
        const val ROTATION_TIMEOUT_MILLIS = 900L
        /** No configuration change by then: the window keeps its orientation (still in a stand, or rotation locked). */
        const val SETTLE_TIMEOUT_MILLIS = 500L
        const val ROTATION_ANIMATION_RESTORE_MILLIS = 2_000L
        const val HINGE_STANDBY_COALESCE_MILLIS = 50L
        /** A letterbox the platform keeps this long (and this long after our last request) gets a nudge. */
        const val LETTERBOX_GRACE_MILLIS = 1_100L
        /** A nudge stands this long before Home's own request is restored (WM's loop counter resets after 1 s). */
        const val LETTERBOX_KICK_HOLD_MILLIS = 1_100L
        /** Per panel arrival: a policy that letterboxes every free request is logged, not fought forever. */
        const val LETTERBOX_MAX_KICKS = 3
        val selfRotatingPanels = mutableSetOf<FoldPanel>()
        /** Panels that letterboxed the handoff's fixed request (ignoreOrientationRequest): FULL_SENSOR straight away next time. */
        val foldFixedRequestIgnoredPanels = mutableSetOf<FoldPanel>()
        /** HyperOS's per-panel copies of `accelerometer_rotation` / `user_rotation`. */
        const val INNER_ACCELEROMETER_ROTATION = "accelerometer_rotation_inner"
        const val INNER_USER_ROTATION = "user_rotation_inner"
        const val OVERRIDE_RESPECT_REQUESTED_ORIENTATION = 236283604L
    }
}
