package com.jake.duolauncher

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface

/**
 * Round 12 (r12b-fold): the pure orientation policy around lhasa's two panel handoffs.
 *
 * Steady state is trivial — the cover is PORTRAIT at every hinge angle (iPhone Duo's cover never
 * rotates; Xiaomi's launcher: `mScreenOrientationOuter=PORTRAIT`), the inner is FULL_SENSOR — and
 * nothing is ever requested from the hinge angle (round 5's ≤20° portrait pre-emption turned the
 * inner 90° in the user's hands and left it there, round 12 feedback).
 *
 * The handoff frames are hidden instead. HyperOS swaps inner → cover at ~5° while closing and
 * cover → inner at ~92° while opening (logcat 2026-10-20); the activity survives the swap and its
 * first frames on the new panel inherit the other panel's rotation. A black veil ([LauncherSurface.FOLDING])
 * rises shortly before the swap, the right orientation is requested the moment the new panel is
 * lit, and the veil lifts once the configuration matches and a frame has drawn, through the panel's
 * arrival motion ([FoldArrivalGate]). Everything here is a pure function of [FoldHandoffInput].
 *
 * Device frame (dumpsys display 2026-10-20, `evidence/r5-fold`): the accelerometer's X runs across
 * the cover (perpendicular to the hinge), Y along the cover's long side (parallel to the hinge) —
 * the frame StandBy's `physicallyLandscape` tent detection was tuned in. The inner panel's native
 * mode is 1672 × 2364 (natural portrait, hinge horizontal); the owner's everyday "book" pose is
 * inner ROTATION_270 (`mDisplayRotation=ROTATION_270`, 2364 × 1672 `land`), the pose in which the
 * cover rotates to 0. Hence inner rotation = cover rotation + 270° for the same pose.
 */

/** Gravity class in the device frame; thresholds are [physicallyUpright]/[physicallyLandscape]'s. */
internal enum class FoldGravity(
    /** The rotation a cover window has in this pose (`Surface.ROTATION_*`), null when ambiguous. */
    val coverRotation: Int?,
) {
    UNKNOWN(null), FLAT(null),
    /** Natural top up: +Y. */
    UPRIGHT(Surface.ROTATION_0),
    /** Turned counter-clockwise, natural right side up: +X. */
    TURNED_CCW(Surface.ROTATION_90),
    UPSIDE_DOWN(Surface.ROTATION_180),
    /** Turned clockwise, natural left side up: -X. */
    TURNED_CW(Surface.ROTATION_270),
}

internal fun foldGravity(x: Float, y: Float): FoldGravity = when {
    !x.isFinite() || !y.isFinite() -> FoldGravity.UNKNOWN
    physicallyUpright(x, y) -> if (y > 0f) FoldGravity.UPRIGHT else FoldGravity.UPSIDE_DOWN
    physicallyLandscape(x, y) -> if (x > 0f) FoldGravity.TURNED_CCW else FoldGravity.TURNED_CW
    else -> FoldGravity.FLAT
}

/** The inner panel's rotation for the pose a cover window would show at [coverRotation]:
 * cover 0 (upright) ↔ inner 270 (owner's dumpsys), and the hinge turns with the device. */
internal fun innerRotationForCoverRotation(coverRotation: Int): Int = (coverRotation + 3) % 4

/** `ActivityInfo.SCREEN_ORIENTATION_*` that pins a natural-portrait panel to [rotation]. */
internal fun fixedOrientationForRotation(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}

/** The inner's configuration orientation at [rotation]: the panel is natively portrait (1672 × 2364). */
internal fun innerOrientationForRotation(rotation: Int): Int =
    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) Configuration.ORIENTATION_LANDSCAPE
    else Configuration.ORIENTATION_PORTRAIT

/**
 * The book pose: the cover read upright, unfolded (owner's dumpsys 2026-10-20; HyperOS's own keyguard unfolds to it,
 * `DisplayRotationStubImpl.getRotationForNosensorOnUnfold` = 3). Round 15b: an unfold without a pose arrives here when
 * the inner honours fixed requests ([FoldHandoffInput.fixedHonoured]).
 */
internal const val INNER_BOOK_ROTATION = Surface.ROTATION_270

/** The inner rotation an unfold held in [openingGravity] arrives at; null without a pose (flat on a table, unread). */
internal fun innerUnfoldRotation(openingGravity: FoldGravity): Int? =
    openingGravity.coverRotation?.let(::innerRotationForCoverRotation)

/** `Configuration.ORIENTATION_*` a request must produce, null when either is acceptable. */
internal fun expectedOrientation(request: Int): Int? = when (request) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ->
        Configuration.ORIENTATION_PORTRAIT
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> Configuration.ORIENTATION_LANDSCAPE
    else -> null
}

internal fun rotationMatches(request: Int, rotation: Int): Boolean = when (request) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> rotation == Surface.ROTATION_0
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> rotation == Surface.ROTATION_90
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> rotation == Surface.ROTATION_180
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> rotation == Surface.ROTATION_270
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    else -> true
}

internal fun orientationRequestName(request: Int): String = when (request) {
    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> "PORTRAIT"
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> "REVERSE_PORTRAIT"
    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "LANDSCAPE"
    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "REVERSE_LANDSCAPE"
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> "SENSOR_LANDSCAPE"
    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR -> "FULL_SENSOR"
    ActivityInfo.SCREEN_ORIENTATION_FULL_USER -> "FULL_USER"
    ActivityInfo.SCREEN_ORIENTATION_LOCKED -> "LOCKED"
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> "UNSPECIFIED"
    else -> request.toString()
}

/** Inner closing: raise the veil at or below this angle. The owner's fold on 2026-10-20 read
 * 19° → 13° → 6° at ~21 ms spacing and HyperOS set the cover's rotation 5 ms after the 6° sample,
 * so the brief's 12° would have left no frame for the veil; 20° leaves ~48 ms (≥ 4 frames at 120 Hz).
 * The inner leaf is already at its 90° tilt (hinge column only, 20 % light) below 82°. */
internal const val FOLD_VEIL_CLOSE_DEGREES = 20f
/** Closing reversed: the veil drops once the hinge opens past this again. */
internal const val FOLD_VEIL_CLOSE_REVERSAL_DEGREES = 25f
/** Cover opening: raise the veil from this angle (the swap comes at ~92°). The cover projection
 * is 95 % saturated here (`coverTilt` 72°: gap .95, 26 % light, σ ≈ 93 px), so the veil is a
 * small darkening of an already blurred frame; 85° would leave ~11 ms at a 650 °/s opening. */
internal const val FOLD_VEIL_OPEN_DEGREES = 75f
internal const val FOLD_VEIL_OPEN_REVERSAL_DEGREES = 60f
/** No swap / no matching configuration by then: lift the veil anyway and log it. */
internal const val FOLD_HANDOFF_TIMEOUT_MILLIS = 1_500L
/** Cover opening past this: the accelerometer is read so a class is known at the swap. */
internal const val FOLD_GRAVITY_ARM_DEGREES = 45f

/**
 * Round 15: below this hinge angle the inner holds its rotation ([ActivityInfo.SCREEN_ORIENTATION_LOCKED]).
 * The system rotates the inner from a sensor in one half (`device_orient`, OrientationSensorJudge): while that half
 * swings — lifted off a table to close, or the other half resting on it while opening — gravity sweeps across it and
 * the system turned Home in view (phone log 2026-10-22 10:30:05: 270 → 180 0.36 s before the 20° veil; 10:29:54: an
 * unfold flat on the table turned to 180 0.57 s after the veil lifted). LOCKED is the one request the inner's
 * ignoreOrientationRequest display still honours (HyperOS services.jar `DisplayArea.shouldIgnoreOrientationRequest`:
 * LOCKED, NOSENSOR, SENSOR and FULL_SENSOR pass) and keeps the current rotation (`rotationForOrientation`: lastRotation).
 * LOCKED means "the activity's current orientation" to the letterbox logic, so a hold only starts while the window's
 * configuration agrees with the panel's rotation ([FoldHandoffInput.windowAgrees]) — never on a stale configuration.
 */
internal const val INNER_HOLD_BELOW_DEGREES = 165f
/** A held inner is released only open past this (hysteresis against [INNER_HOLD_BELOW_DEGREES]) … */
internal const val INNER_RELEASE_ABOVE_DEGREES = 170f
/** … and after a clear pose (held up, not lying flat) for this long: the system's own sensor needs a fresh reading
 * before it decides (a release while flat would apply the swing's stale proposal). */
internal const val INNER_RELEASE_POSE_MILLIS = 600L

internal enum class FoldHandoffPhase {
    IDLE,
    /** Inner, ≤ [FOLD_VEIL_CLOSE_DEGREES] and closing: veiled, waiting for the cover to light. */
    CLOSING_VEILED,
    /** Cover lit with the inner's rotation: PORTRAIT requested, veiled until portrait has drawn. */
    COVER_SETTLING,
    /** Cover, ≥ [FOLD_VEIL_OPEN_DEGREES] and opening: veiled, waiting for the inner to light. */
    OPENING_VEILED,
    /** Inner lit: veiled until it shows the unfold's rotation ([innerUnfoldRotation]) and has drawn there. */
    INNER_SETTLING,
}

internal data class FoldHandoffInput(
    val panel: FoldPanel,
    val hingeDegrees: Float?,
    val gravity: FoldGravity = FoldGravity.UNKNOWN,
    /** `Configuration.ORIENTATION_*` of the activity's current configuration. */
    val orientation: Int = Configuration.ORIENTATION_UNDEFINED,
    /** `Surface.ROTATION_*` of the activity's display. */
    val rotation: Int = Surface.ROTATION_0,
    /** The window is smaller than the panel: a request was letterboxed (`ignoreOrientationRequest`). */
    val letterboxed: Boolean = false,
    /** A frame has been committed since the configuration last changed. */
    val frameDrawn: Boolean = false,
    /** StandBy is entering, showing, or settling out: its own veil and request have priority. */
    val standbyActive: Boolean = false,
    /** This panel letterboxed one of our requests earlier in this process: only free requests from now on. */
    val fixedRequestsIgnored: Boolean = false,
    val now: Long,
    /** The INNER panel's auto-rotate is off (HyperOS keeps one switch per panel): the system shows [innerUserRotation]
     * there and 18Duo never holds anything. */
    val rotationLocked: Boolean = false,
    /** The inner's locked rotation, when known. */
    val innerUserRotation: Int? = null,
    /**
     * Round 15b: the inner display honours this app's fixed requests despite ignoreOrientationRequest (compat change
     * OVERRIDE_RESPECT_REQUESTED_ORIENTATION enabled for 18Duo, read by reflection). Only then is a rotation *requested*.
     */
    val fixedHonoured: Boolean = false,
) {
    /** The window's configuration matches the inner's rotation and fills the panel: a hold cannot letterbox it. */
    val windowAgrees: Boolean get() = !letterboxed && orientation == innerOrientationForRotation(rotation)
}

internal data class FoldHandoffState(
    val phase: FoldHandoffPhase = FoldHandoffPhase.IDLE,
    /** When the phase began (the timeout's origin); continued motion toward the swap renews it. */
    val since: Long = 0L,
    /** Home's orientation request while StandBy is not requesting. */
    val request: Int = ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
    val direction: FoldDirection = FoldDirection.UNKNOWN,
    val lastAngle: Float? = null,
    /** A request of this handoff came back letterboxed (free requests since). */
    val letterboxed: Boolean = false,
    /**
     * Round 13: the pose as the opening began (≈ 75°, before the swing). The accelerometer sits in the swinging half,
     * so the sample at the swap (~105°) is noise (UPRIGHT at 78° read TURNED_CW at 107°, phone log 03:51:57).
     */
    val openingGravity: FoldGravity = FoldGravity.UNKNOWN,
    /** Round 15: the inner holds its rotation ([INNER_HOLD_BELOW_DEGREES]); [request] is LOCKED. */
    val hold: Boolean = false,
    /** Since when the device has shown a clear pose while held open (−1: none) — [INNER_RELEASE_POSE_MILLIS]. */
    val poseSince: Long = -1L,
    /** INNER_SETTLING: the rotation the veil waits for (null: whatever the inner shows now). */
    val target: Int? = null,
    val reason: String = "",
) {
    val veil: Boolean get() = phase != FoldHandoffPhase.IDLE
}

internal data class FoldHandoffStep(
    val state: FoldHandoffState,
    /** The veil just lifted: reveal Home through the panel's arrival motion. */
    val reveal: Boolean,
    /** Phase, request, veil or letterbox changed: worth one `18DuoFold` line. */
    val changed: Boolean,
)

private fun innerRequest(hold: Boolean): Int =
    if (hold) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_USER

/**
 * How long a phase may hold the veil. On the inner the system's own sensor turns the panel (ignoreOrientationRequest);
 * the veil waits for that turn only briefly — past it the panel lights as it is (owner, r13: "内屏延迟亮起" with the
 * 1.5 s cap).
 */
internal fun foldHandoffLimit(state: FoldHandoffState): Long =
    if (state.phase == FoldHandoffPhase.INNER_SETTLING) INNER_ROTATION_WAIT_MILLIS else FOLD_HANDOFF_TIMEOUT_MILLIS

/** Longest the inner stays veiled waiting for the system to turn it to the pose (free request). */
internal const val INNER_ROTATION_WAIT_MILLIS = 700L

/**
 * Round 15, the inner outside a handoff: hold the rotation from [INNER_HOLD_BELOW_DEGREES] down, release it to the
 * system ([ActivityInfo.SCREEN_ORIENTATION_FULL_USER], which follows the user's switch) once open past
 * [INNER_RELEASE_ABOVE_DEGREES] with a clear pose for [INNER_RELEASE_POSE_MILLIS]. Never with the inner's rotation
 * locked, without a hinge reading, or on a panel that letterboxed the hold.
 */
private fun innerHold(previous: FoldHandoffState, input: FoldHandoffInput, angle: Float?, poseSince: Long): Boolean = when {
    input.rotationLocked || input.fixedRequestsIgnored || angle == null -> false
    !previous.hold -> angle < INNER_HOLD_BELOW_DEGREES && input.windowAgrees
    else -> angle < INNER_RELEASE_ABOVE_DEGREES || poseSince < 0L || input.now - poseSince < INNER_RELEASE_POSE_MILLIS
}

internal fun foldHandoffStep(previous: FoldHandoffState, input: FoldHandoffInput): FoldHandoffStep {
    val angle = input.hingeDegrees?.takeIf { it.isFinite() && it in 0f..180f }
    val direction = if (angle != null) nextFoldDirection(previous.lastAngle, angle, previous.direction) else previous.direction
    val poseSince = when {
        input.gravity.coverRotation == null -> -1L
        previous.poseSince >= 0L -> previous.poseSince
        else -> input.now
    }
    val base = previous.copy(direction = direction, lastAngle = angle ?: previous.lastAngle, poseSince = poseSince)
    val elapsed = input.now - previous.since
    fun finish(next: FoldHandoffState) = FoldHandoffStep(next, reveal = previous.veil && !next.veil,
        changed = next.phase != previous.phase || next.request != previous.request || next.letterboxed != previous.letterboxed)
    fun idle(reason: String): FoldHandoffStep {
        if (previous.hold && input.panel == FoldPanel.INNER && input.letterboxed)
            // A hold that letterboxes Home: release it; the caller stops holding on this panel.
            return finish(base.copy(phase = FoldHandoffPhase.IDLE, request = ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
                hold = false, target = null, letterboxed = true, reason = "hold letterboxed"))
        val hold = input.panel == FoldPanel.INNER && innerHold(previous, input, angle ?: previous.lastAngle, poseSince)
        val request = if (input.panel == FoldPanel.INNER) innerRequest(hold) else homeOrientation(input.panel)
        val why = when {
            hold && !previous.hold -> "hold"
            !hold && previous.hold && previous.phase == FoldHandoffPhase.IDLE -> "released"
            else -> reason
        }
        return finish(base.copy(phase = FoldHandoffPhase.IDLE, request = request, hold = hold, target = null,
            letterboxed = false, reason = why))
    }
    if (input.standbyActive) return finish(base.copy(phase = FoldHandoffPhase.IDLE, request = homeOrientation(input.panel),
        hold = false, target = null, letterboxed = false, reason = "standby"))
    // Only a sample that actually moved toward the swap can raise the veil: the direction is sticky, and a
    // step triggered by a display event or frame at the same angle (e.g. right after a timeout) must not.
    val closingNow = angle != null && previous.lastAngle != null && angle < previous.lastAngle && direction == FoldDirection.CLOSING
    val openingNow = angle != null && previous.lastAngle != null && angle > previous.lastAngle && direction == FoldDirection.OPENING
    return when (previous.phase) {
        FoldHandoffPhase.IDLE -> when {
            input.panel == FoldPanel.INNER && closingNow && angle!! <= FOLD_VEIL_CLOSE_DEGREES -> {
                val hold = innerHold(previous, input, angle, poseSince)
                finish(base.copy(phase = FoldHandoffPhase.CLOSING_VEILED, since = input.now, request = innerRequest(hold),
                    hold = hold, letterboxed = false, reason = "closing"))
            }
            input.panel == FoldPanel.COVER && openingNow && angle!! >= FOLD_VEIL_OPEN_DEGREES ->
                finish(base.copy(phase = FoldHandoffPhase.OPENING_VEILED, since = input.now,
                    request = homeOrientation(FoldPanel.COVER), hold = false, letterboxed = false,
                    openingGravity = input.gravity, reason = "opening"))
            else -> idle(previous.reason)
        }
        FoldHandoffPhase.CLOSING_VEILED -> when {
            // The cover is lit with the inner's rotation: ask for portrait now, keep the veil until it has drawn.
            input.panel == FoldPanel.COVER -> finish(base.copy(phase = FoldHandoffPhase.COVER_SETTLING, since = input.now,
                request = homeOrientation(FoldPanel.COVER), hold = false, reason = "cover lit"))
            input.panel == FoldPanel.INNER && angle != null && angle > FOLD_VEIL_CLOSE_REVERSAL_DEGREES -> idle("reversed")
            elapsed >= FOLD_HANDOFF_TIMEOUT_MILLIS -> idle("timeout")
            // Still closing: the swap is still ahead, renew the timeout.
            closingNow -> finish(base.copy(since = input.now))
            else -> finish(base)
        }
        FoldHandoffPhase.COVER_SETTLING -> when {
            input.panel != FoldPanel.COVER -> idle("panel changed")
            input.orientation == Configuration.ORIENTATION_PORTRAIT && input.rotation == Surface.ROTATION_0 &&
                input.frameDrawn -> idle("portrait drawn")
            elapsed >= FOLD_HANDOFF_TIMEOUT_MILLIS -> idle("timeout")
            else -> finish(base)
        }
        FoldHandoffPhase.OPENING_VEILED -> when {
            input.panel == FoldPanel.INNER -> {
                // Held up, the system's own sensor turns the inner to the pose (it reads the half that stopped moving
                // first); flat or unread, nothing better than the rotation it lit with will come — the system would only
                // turn it by the swing's noise. Locked: the system shows the user's rotation.
                // Round 15b: if the inner honours fixed requests, the pose (or, flat, the book pose) is asked for directly.
                val turnable = input.fixedHonoured && !input.rotationLocked && !input.fixedRequestsIgnored
                val target = if (input.rotationLocked) input.innerUserRotation
                    else innerUnfoldRotation(previous.openingGravity) ?: if (turnable) INNER_BOOK_ROTATION else null
                finish(innerSettle(base.copy(phase = FoldHandoffPhase.INNER_SETTLING, since = input.now, hold = false,
                    target = target, letterboxed = false, reason = when {
                        input.rotationLocked -> "inner lit, rotation locked"
                        input.fixedRequestsIgnored -> "inner lit, holds ignored"
                        turnable && previous.openingGravity.coverRotation == null -> "inner lit, no pose, book pose requested"
                        turnable -> "inner lit, pose requested"
                        target == null -> "inner lit, no pose"
                        else -> "inner lit"
                    }), input))
            }
            input.panel == FoldPanel.COVER && angle != null && angle < FOLD_VEIL_OPEN_REVERSAL_DEGREES -> idle("reversed")
            elapsed >= FOLD_HANDOFF_TIMEOUT_MILLIS -> idle("timeout")
            openingNow -> finish(base.copy(since = input.now))
            else -> finish(base)
        }
        FoldHandoffPhase.INNER_SETTLING -> {
            if (input.panel != FoldPanel.INNER) return idle("panel changed")
            if (input.letterboxed && previous.request != ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
                // A hold that came back letterboxed: release it; the caller stops holding on this panel.
                return finish(base.copy(request = ActivityInfo.SCREEN_ORIENTATION_FULL_USER, hold = false, target = null,
                    letterboxed = true, reason = "letterboxed"))
            }
            val next = innerSettle(base, input)
            val settled = (next.target == null || input.rotation == next.target) && input.windowAgrees && input.frameDrawn
            when {
                settled -> finish(next.copy(phase = FoldHandoffPhase.IDLE, target = null, reason = "settled"))
                // Not there in time: light as it is, held from here if the window agrees (no turn in view by the swing).
                elapsed >= foldHandoffLimit(previous) -> {
                    val hold = next.hold || canHold(previous, input) && input.windowAgrees
                    finish(next.copy(phase = FoldHandoffPhase.IDLE, request = innerRequest(hold), hold = hold, target = null,
                        reason = "timeout"))
                }
                else -> finish(next)
            }
        }
    }
}

private fun canHold(state: FoldHandoffState, input: FoldHandoffInput) =
    !input.rotationLocked && !input.fixedRequestsIgnored && !state.letterboxed

/** INNER_SETTLING: hold as soon as the inner shows the unfold's rotation (any, without a pose) and the window agrees. */
private fun innerSettle(state: FoldHandoffState, input: FoldHandoffInput): FoldHandoffState {
    val there = state.target == null || input.rotation == state.target
    val hold = state.hold || canHold(state, input) && there && input.windowAgrees
    // Not held yet and the inner honours fixed requests: keep asking for the target rotation (under the veil) until the
    // window has caught up with it.
    val request = if (!hold && state.target != null && input.fixedHonoured && canHold(state, input))
        fixedOrientationForRotation(state.target!!) else innerRequest(hold)
    return state.copy(hold = hold, request = request,
        reason = if (hold && !state.hold && state.phase == FoldHandoffPhase.INNER_SETTLING && state.reason.startsWith("inner lit"))
            "${state.reason}, held" else state.reason)
}

private fun orientationName(orientation: Int): String = when (orientation) {
    Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
    Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
    else -> "UNDEFINED"
}

/** One line per decision for `adb logcat | grep 18DuoFold`. */
internal fun foldLogLine(state: FoldHandoffState, input: FoldHandoffInput): String =
    "phase=${state.phase} panel=${input.panel} angle=${input.hingeDegrees} direction=${state.direction} " +
        "gravity=${input.gravity} request=${orientationRequestName(state.request)} veil=${state.veil} " +
        "config=${orientationName(input.orientation)} rotation=${input.rotation} letterboxed=${input.letterboxed} " +
        "frameDrawn=${input.frameDrawn} standby=${input.standbyActive} opening=${state.openingGravity} " +
        "hold=${state.hold} target=${state.target} locked=${input.rotationLocked} userRotation=${input.innerUserRotation} " +
        "honoured=${input.fixedHonoured} " +
        "reason=${state.reason}"
