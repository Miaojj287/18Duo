package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * A vertical finger drag handed over by a gesture detector once it has recognised its axis.
 * [drag] receives the finger's signed y travel (px) since recognition, [release] the release
 * velocity (px/s, positive = down). [cancel] ends it without a fling (a second finger, a
 * cancelled stream).
 */
internal interface VerticalDragSession {
    fun drag(totalDy: Float)
    fun release(velocityY: Float)
    fun cancel()
}

/**
 * Pure geometry of the iOS Spotlight pull-down: progress follows the finger 1:1 over [travel],
 * past it the content rubber-bands, and a release is projected (WWDC 2018 "Designing Fluid
 * Interfaces") so a flick commits even from a short pull, as eagerly as iOS Spotlight does.
 */
internal object SpotlightPullMath {
    /** Finger travel for a full pull: about a quarter of the panel, as on iPhone. */
    fun travelPx(viewportHeightPx: Float, density: Float): Float =
        (viewportHeightPx * .26f).coerceIn(150f * density, 260f * density).coerceAtLeast(1f)

    /** Progress for a finger at [dragPx] (in the opening direction) starting from [startProgress]. */
    fun progress(startProgress: Float, dragPx: Float, travel: Float): Float =
        (startProgress + dragPx / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** Content travel beyond a full pull (the finger keeps going): an iOS rubber band. */
    fun overshootPx(startProgress: Float, dragPx: Float, travel: Float, viewport: Float): Float {
        val raw = startProgress * travel + dragPx - travel
        return if (raw <= 0f) 0f else IosScrollPhysics.rubberBand(raw, viewport.coerceAtLeast(1f))
    }

    /** Where a release at [progress] moving at [velocity] (progress/s) is heading: true = open. */
    fun releaseOpens(progress: Float, velocity: Float, travel: Float): Boolean {
        val projected = progress + IosScrollPhysics.projectedDistance(velocity * travel,
            SPOTLIGHT_DECELERATION) / travel.coerceAtLeast(1f)
        return projected >= .5f
    }

    /** Home's blur radius (dp) behind Spotlight; eases out like the Today veil so it builds early. */
    fun homeBlurDp(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return SPOTLIGHT_HOME_BLUR_DP * (1f - (1f - p) * (1f - p))
    }

    /** Home recedes slightly as Spotlight comes forward (iOS "zoom back"). */
    fun homeScale(progress: Float): Float = 1f - SPOTLIGHT_HOME_ZOOM * progress.coerceIn(0f, 1f)

    /** The field leads, the results follow: they only start to fade in a third of the way down. */
    fun resultsAlpha(progress: Float): Float = ((progress - .3f) / .7f).coerceIn(0f, 1f)

    /** A closing drag moves the content with the finger, damped (it is leaving, not being placed). */
    const val CLOSE_FOLLOW = .35f
}

internal const val SPOTLIGHT_HOME_BLUR_DP = 34f
/** Between UIScrollView's fast (.99) and normal (.998) rates: ≈0.2 s of coasting decides. */
internal const val SPOTLIGHT_DECELERATION = .995f
internal const val SPOTLIGHT_HOME_ZOOM = .05f

/**
 * Spotlight's single presence value (0 = Home, 1 = Search open), shared by the gesture that
 * drives it, the overlay that draws it and Home, which blurs, dims and recedes with it. Every
 * motion — a pull from Home, a drag that closes it, the Search button, Back — moves this one
 * value, so any of them can catch another in flight and the effect is always proportional.
 */
@Stable
internal class SpotlightPresence(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    /** A finger owns [progress]; the overlay's visibility animation must not fight it. */
    var dragging by mutableStateOf(false)
        private set
    /** The current motion came from a finger: the overlay slides with it instead of its button entrance. */
    var interactive by mutableStateOf(false)
    /** Content offset that follows a finger beyond (or against) the progress, px. */
    val follow = Animatable(0f)
    /** Launching from Search drops every Home effect at once (the launch freezes plain Home). */
    var hidden by mutableStateOf(false)
    /** Panel height for the rubber band past a full pull (set by the overlay's host). */
    var viewportHeightPx by mutableFloatStateOf(0f)

    /** Home's share of the effect, read in draw blocks. */
    fun homeEffect(): Float = if (hidden) 0f else progress.value.coerceIn(0f, 1f)

    private fun settle(target: Float, velocity: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.animateTo(target, SPOTLIGHT_SETTLE, initialVelocity = velocity.coerceIn(-MAX_VELOCITY, MAX_VELOCITY))
            if (target == 0f) interactive = false
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { follow.animateTo(0f, SPOTLIGHT_SETTLE) }
    }

    private fun snap(value: Float, offset: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { follow.snapTo(offset) }
    }

    /**
     * A downward drag on Home: Spotlight comes down with the finger from wherever it is (a
     * touch during a closing settle catches it). [onOpen] commits Search open (keyboard and all).
     */
    fun beginPull(travel: Float, onOpen: () -> Unit): VerticalDragSession {
        hidden = false; interactive = true; dragging = true
        val start = progress.value.coerceIn(0f, 1f)
        val viewport = viewportHeightPx.takeIf { it > 0f } ?: (travel * 4f)
        return object : VerticalDragSession {
            var ended = false
            var last = start
            override fun drag(totalDy: Float) {
                if (ended) return
                last = SpotlightPullMath.progress(start, totalDy, travel)
                snap(last, SpotlightPullMath.overshootPx(start, totalDy, travel, viewport))
            }
            override fun release(velocityY: Float) {
                if (ended) return
                ended = true; dragging = false
                val velocity = velocityY / travel
                val open = SpotlightPullMath.releaseOpens(last, velocity, travel)
                settle(if (open) 1f else 0f, velocity)
                if (open) onOpen()
            }
            override fun cancel() = release(0f)
        }
    }

    /**
     * A drag that closes open Search: [closingDy] in [drag] is the finger's travel in the closing
     * direction (positive = closing). [onClose] commits it closed; otherwise it springs back.
     */
    fun beginClose(travel: Float, direction: Float, onClose: () -> Unit): VerticalDragSession {
        interactive = true; dragging = true
        val start = progress.value.coerceIn(0f, 1f)
        return object : VerticalDragSession {
            var ended = false
            var last = start
            override fun drag(totalDy: Float) {
                if (ended) return
                val closing = totalDy * direction
                last = SpotlightPullMath.progress(start, -closing.coerceAtLeast(0f), travel)
                // Against the closing direction the content only gives a little (rubber band).
                val offset = if (closing >= 0f) totalDy * SpotlightPullMath.CLOSE_FOLLOW
                    else IosScrollPhysics.rubberBand(totalDy, travel * 4f) * SpotlightPullMath.CLOSE_FOLLOW
                snap(last, offset)
            }
            override fun release(velocityY: Float) {
                if (ended) return
                ended = true; dragging = false
                val velocity = -velocityY * direction / travel
                val open = SpotlightPullMath.releaseOpens(last, velocity, travel)
                settle(if (open) 1f else 0f, velocity)
                if (!open) onClose()
            }
            override fun cancel() = release(0f)
        }
    }

    private companion object {
        /** Spotlight's own spring (≈ SwiftUI `.snappy` without a visible bounce on the blur). */
        val SPOTLIGHT_SETTLE get() = LauncherMotion.spotlightSettle
        const val MAX_VELOCITY = 12f
    }
}

@Composable
internal fun rememberSpotlightPresence(): SpotlightPresence {
    val scope = rememberCoroutineScope()
    return remember(scope) { SpotlightPresence(scope) }
}

/**
 * Hands a results drag that the list cannot use to Search itself: pulling the results down past
 * their top (or a list too short to scroll) closes Search with the finger, as on iOS. A drag that
 * scrolled the list never turns into a close halfway (a new drag is needed), so flinging to the
 * top does not dismiss by accident.
 */
internal fun spotlightResultsPull(begin: (direction: Float) -> VerticalDragSession): NestedScrollConnection =
    object : NestedScrollConnection {
        var session: VerticalDragSession? = null
        var total = 0f
        var scrolledThisDrag = false

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val active = session ?: return Offset.Zero
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            total += available.y
            active.drag(total)
            return Offset(0f, available.y)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (consumed.y != 0f) { scrolledThisDrag = true; return Offset.Zero }
            if (session != null || scrolledThisDrag || available.y == 0f) return Offset.Zero
            val started = begin(if (available.y > 0f) 1f else -1f)
            session = started
            total = available.y
            started.drag(total)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            scrolledThisDrag = false
            val active = session ?: return Velocity.Zero
            session = null
            active.release(available.y)
            return available
        }
    }
