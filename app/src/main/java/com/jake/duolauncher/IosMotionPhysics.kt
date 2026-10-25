package com.jake.duolauncher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign

/**
 * SwiftUI/UIKit spring parameterisation: [response] is the period of the undamped oscillation in
 * seconds, [dampingFraction] is 1 for critical damping (SwiftUI `bounce = 1 − dampingFraction`).
 * Compose's `spring(stiffness, dampingRatio)` uses unit mass, so stiffness = (2π / response)².
 */
internal data class IosSpring(val response: Float, val dampingFraction: Float) {
    init {
        require(response.isFinite() && response > 0f)
        require(dampingFraction.isFinite() && dampingFraction > 0f)
    }

    val angularFrequency: Float get() = (2.0 * PI / response).toFloat()
    val stiffness: Float get() = angularFrequency * angularFrequency

    companion object {
        /** For springs already tuned as Compose stiffness: same motion, documented as a response. */
        fun fromStiffness(stiffness: Float, dampingFraction: Float): IosSpring =
            IosSpring((2.0 * PI / kotlin.math.sqrt(stiffness.toDouble())).toFloat(), dampingFraction)
    }
}

/**
 * UIScrollView physics used by Home paging (all distances in the same pixel unit, velocities in
 * pixels per second, positive = the content offset grows, i.e. the finger moves left).
 */
internal object IosScrollPhysics {
    /** `UIScrollView.DecelerationRate.normal`: velocity multiplier per millisecond. */
    const val DECELERATION_NORMAL = .998f
    /** `UIScrollView.DecelerationRate.fast`. */
    const val DECELERATION_FAST = .99f
    /** The constant in UIScrollView's rubber band, `(1 − 1 / (x·c/d + 1))·d`. */
    const val RUBBER_BAND_COEFFICIENT = .55f

    /**
     * Distance a released scroll view would coast before stopping (WWDC 2018 "Designing Fluid
     * Interfaces": `v/1000 · r / (1 − r)`), used to decide where a flick is heading.
     */
    fun projectedDistance(velocityPxPerSecond: Float, decelerationRate: Float = DECELERATION_NORMAL): Float {
        if (!velocityPxPerSecond.isFinite()) return 0f
        val rate = decelerationRate.coerceIn(.5f, .9999f)
        return velocityPxPerSecond / 1000f * rate / (1f - rate)
    }

    /** iOS rubber band for an overscroll of [offset] past the edge of a view [dimension] wide. Signed. */
    fun rubberBand(offset: Float, dimension: Float, coefficient: Float = RUBBER_BAND_COEFFICIENT): Float {
        if (offset == 0f || !offset.isFinite() || dimension <= 0f) return 0f
        val x = abs(offset)
        // (1 − 1/(q + 1))·d with q = x·c/d, written as x·c/(q + 1) to stay exact near the edge.
        return sign(offset) * x * coefficient / (x * coefficient / dimension + 1f)
    }

    /** The finger travel that produces a visible rubber-band offset [visible] (inverse of [rubberBand]). */
    fun rubberBandInverse(visible: Float, dimension: Float, coefficient: Float = RUBBER_BAND_COEFFICIENT): Float {
        if (visible == 0f || !visible.isFinite() || dimension <= 0f) return 0f
        val f = abs(visible).coerceAtMost(dimension * .999f)
        return sign(visible) * dimension * f / (coefficient * (dimension - f))
    }

    /** d(rubberBand)/d(offset): how much of the finger's speed the stretched content keeps. */
    fun rubberBandSlope(offset: Float, dimension: Float, coefficient: Float = RUBBER_BAND_COEFFICIENT): Float {
        if (dimension <= 0f || !offset.isFinite()) return coefficient
        val q = abs(offset) * coefficient / dimension + 1f
        return coefficient / (q * q)
    }

    /**
     * Largest speed toward the target that a critically damped [spring] can absorb without passing
     * it: x(t) = (x₀ + (v₀ + ωx₀)t)e^(−ωt) stays on one side while |v₀| ≤ ω|x₀|. At the cap the
     * motion is the pure exponential approach x₀e^(−ωt) — UIScrollView's own deceleration shape.
     */
    fun settleVelocity(velocity: Float, distanceToTarget: Float, spring: IosSpring): Float {
        if (!velocity.isFinite()) return 0f
        if (distanceToTarget == 0f || sign(velocity) != sign(distanceToTarget)) {
            // Moving away from (or resting at) the target: the spring turns it around without
            // crossing; still bound absurd sensor spikes.
            val cap = spring.angularFrequency * maxOf(abs(distanceToTarget), 1f) * 4f
            return velocity.coerceIn(-cap, cap)
        }
        val cap = spring.angularFrequency * abs(distanceToTarget)
        return velocity.coerceIn(-cap, cap)
    }
}

/**
 * The page a released paging scroll view settles on: project the release with UIScrollView's
 * normal deceleration and take the nearest page to where it would have coasted, never more than
 * one page from [anchor] (Home pages one at a time). [offsetOf] maps a page to its content offset.
 */
internal fun iosPagingTarget(
    offset: Float,
    velocityPxPerSecond: Float,
    anchor: Int,
    pageCount: Int,
    offsetOf: (Int) -> Float,
    decelerationRate: Float = IosScrollPhysics.DECELERATION_NORMAL,
): Int {
    if (pageCount <= 1) return 0
    val first = (anchor - 1).coerceIn(0, pageCount - 1)
    val last = (anchor + 1).coerceIn(0, pageCount - 1)
    val projected = offset + IosScrollPhysics.projectedDistance(velocityPxPerSecond, decelerationRate)
    var best = anchor.coerceIn(first, last)
    var bestDistance = Float.MAX_VALUE
    for (page in first..last) {
        val distance = abs(offsetOf(page) - projected)
        if (distance < bestDistance) { best = page; bestDistance = distance }
    }
    return best
}

/** Splits a requested content offset into the legal part and the visible rubber-band overscroll. */
internal data class RubberBandedOffset(val legal: Float, val overscroll: Float)

internal fun rubberBandedOffset(requested: Float, minimum: Float, maximum: Float, dimension: Float): RubberBandedOffset {
    val low = minOf(minimum, maximum); val high = maxOf(minimum, maximum)
    val legal = requested.coerceIn(low, high)
    return RubberBandedOffset(legal, IosScrollPhysics.rubberBand(requested - legal, dimension))
}
