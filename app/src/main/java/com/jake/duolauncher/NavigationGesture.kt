package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.hypot

internal enum class NavigationEdge { LEFT, RIGHT, BOTTOM }
internal enum class NavigationAction { BACK, HOME, RECENTS, RECOVERY, QUICK_PREVIOUS, QUICK_NEXT }

/** The bottom gesture locks to one axis once it leaves the touch slop, like iOS. */
internal enum class BottomAxis { UNDECIDED, VERTICAL, HORIZONTAL }

internal data class NavigationGestureProgress(
    val edge: NavigationEdge,
    val x: Float,
    val y: Float,
    val distance: Float,
    val hold: Float,
    val recovery: Float,
    val active: Boolean,
    /** Raw upward travel in dp for the bottom edge. */
    val travel: Float = 0f,
    /** Upward finger velocity in dp/s over the last ~100 ms. */
    val velocity: Float = 0f,
    /** Horizontal offset from the touch-down point in dp (rightward positive). */
    val dx: Float = 0f,
    /** Rightward finger velocity in dp/s. */
    val velocityX: Float = 0f,
    val axis: BottomAxis = BottomAxis.UNDECIDED,
    /** The finger paused high enough for the app switcher to engage (iOS haptic moment). */
    val engaged: Boolean = false,
)

/**
 * Coordinates are dp; cancellation and multi-touch must never trigger navigation.
 * [screenWidth]/[screenHeight] (dp) scale the bottom thresholds to the panel; the defaults are the
 * Xiaomi cover panel.
 */
internal class NavigationGesture(private val edge: NavigationEdge) {
    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var started = 0L
    private var stableSince = 0L
    private var active = false
    private var moved = false
    private var axis = BottomAxis.UNDECIDED
    private var engaged = false
    private var screenWidth = 425f
    private var screenHeight = 622f
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()

    fun down(x: Float, y: Float, time: Long, screenWidth: Float = 425f, screenHeight: Float = 622f) {
        startX = x; startY = y; this.x = x; this.y = y
        anchorX = x; anchorY = y; started = time; stableSince = time
        active = true; moved = false; axis = BottomAxis.UNDECIDED; engaged = false
        this.screenWidth = screenWidth.coerceAtLeast(1f); this.screenHeight = screenHeight.coerceAtLeast(1f)
        samples.clear(); samples.add(Triple(time, x, y))
    }

    fun move(x: Float, y: Float, time: Long) {
        if (!active) return
        this.x = x; this.y = y
        sample(time)
        if (hypot(x - startX, y - startY) > 10f) moved = true
        if (hypot(x - anchorX, y - anchorY) > 7f) {
            anchorX = x; anchorY = y; stableSince = time
        }
        if (edge == NavigationEdge.BOTTOM && axis == BottomAxis.UNDECIDED && hypot(x - startX, y - startY) >= AXIS_SLOP) {
            val dx = abs(x - startX)
            val up = startY - y
            axis = when {
                dx > abs(up) * 1.15f -> BottomAxis.HORIZONTAL
                up > 0f -> BottomAxis.VERTICAL
                else -> BottomAxis.UNDECIDED
            }
        }
    }

    /** Only the recovery hold ends a gesture from the timer; engagement is reported, not acted on. */
    fun tick(time: Long): NavigationAction? {
        if (!active || edge != NavigationEdge.BOTTOM) return null
        if (!moved && time - started >= RECOVERY_MILLIS) return finish(NavigationAction.RECOVERY)
        if (!engaged && axis == BottomAxis.VERTICAL && travel() >= screenHeight * SwitcherMotion.ENGAGE_TRAVEL &&
            time - stableSince >= SwitcherMotion.HOLD_MILLIS) engaged = true
        return null
    }

    fun progress(time: Long): NavigationGestureProgress {
        if (!active) return NavigationGestureProgress(edge, x, y, 0f, 0f, 0f, false)
        val distance = when (edge) {
            NavigationEdge.BOTTOM -> ((startY - y) / 48f).coerceIn(0f, 1f)
            NavigationEdge.LEFT -> ((x - startX) / BACK_DISTANCE).coerceIn(0f, 1f)
            NavigationEdge.RIGHT -> ((startX - x) / BACK_DISTANCE).coerceIn(0f, 1f)
        }
        val hold = if (edge == NavigationEdge.BOTTOM && axis == BottomAxis.VERTICAL && !engaged)
            ((time - stableSince) / SwitcherMotion.HOLD_MILLIS.toFloat()).coerceIn(0f, 1f) else if (engaged) 1f else 0f
        val recovery = if (edge == NavigationEdge.BOTTOM && !moved)
            ((time - started) / RECOVERY_MILLIS.toFloat()).coerceIn(0f, 1f) else 0f
        val bottom = edge == NavigationEdge.BOTTOM
        return NavigationGestureProgress(edge, x, y, distance, hold, recovery, true,
            travel = if (bottom) travel() else 0f,
            velocity = velocityY(),
            dx = if (bottom) x - startX else 0f,
            velocityX = velocityX(),
            axis = axis,
            engaged = engaged)
    }

    fun up(time: Long): NavigationAction? {
        if (!active) return null
        tick(time)?.let { return it }
        // A finger that rested before lifting has no velocity, whatever its last move was.
        if (time > (samples.lastOrNull()?.first ?: time)) sample(time)
        val dx = x - startX
        val dy = y - startY
        val action = when (edge) {
            NavigationEdge.BOTTOM -> when (axis) {
                BottomAxis.VERTICAL -> when (SwitcherMotion.commit(travel(), velocityY(), screenHeight, engaged)) {
                    SwitcherCommit.HOME -> NavigationAction.HOME
                    SwitcherCommit.SWITCHER -> NavigationAction.RECENTS
                    SwitcherCommit.NONE -> null
                }
                // Availability of a neighbour is the overlay's call; report the raw direction.
                BottomAxis.HORIZONTAL -> when (SwitcherMotion.quickCommit(dx, velocityX(), screenWidth, true, true)) {
                    QuickSwitchCommit.PREVIOUS -> NavigationAction.QUICK_PREVIOUS
                    QuickSwitchCommit.NEXT -> NavigationAction.QUICK_NEXT
                    QuickSwitchCommit.NONE -> null
                }
                BottomAxis.UNDECIDED -> null
            }
            NavigationEdge.LEFT -> if (dx >= BACK_DISTANCE && dx > abs(dy) * 1.2f) NavigationAction.BACK else null
            NavigationEdge.RIGHT -> if (-dx >= BACK_DISTANCE && -dx > abs(dy) * 1.2f) NavigationAction.BACK else null
        }
        active = false
        return action
    }

    fun cancel() { active = false }

    private fun travel() = (startY - y).coerceAtLeast(0f)

    private fun sample(time: Long) {
        samples.add(Triple(time, x, y))
        while (samples.size > 2 && time - samples.first().first > VELOCITY_WINDOW) samples.removeFirst()
    }

    private fun velocityY(): Float {
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        return if (first != null && last != null && last.first > first.first)
            (first.third - last.third) * 1000f / (last.first - first.first) else 0f
    }

    private fun velocityX(): Float {
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        return if (first != null && last != null && last.first > first.first)
            (last.second - first.second) * 1000f / (last.first - first.first) else 0f
    }

    private fun finish(action: NavigationAction): NavigationAction { active = false; return action }

    companion object {
        const val AXIS_SLOP = 10f
        const val BACK_DISTANCE = 28f
        const val RECOVERY_MILLIS = 2000L
        const val VELOCITY_WINDOW = 100L
    }
}
