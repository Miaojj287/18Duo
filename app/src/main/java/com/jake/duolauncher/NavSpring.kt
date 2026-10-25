package com.jake.duolauncher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * A unit-mass damped spring parameterised like SwiftUI (`response`, `dampingFraction`): the
 * navigation overlay animates in a plain View, so it cannot use Compose's springs, yet must feel
 * identical to them. Velocity carries over when the target changes, which is what makes a card
 * released mid-flick keep its momentum.
 */
internal class NavSpring(
    var value: Float = 0f,
    response: Float = .45f,
    dampingFraction: Float = 1f,
) {
    var target: Float = value
    var velocity: Float = 0f
    private var stiffness = 0f
    private var damping = 0f
    /** Settle thresholds in the spring's own unit. */
    var valueThreshold = .01f
    var velocityThreshold = .05f

    init { configure(response, dampingFraction) }

    fun configure(response: Float, dampingFraction: Float): NavSpring {
        val r = max(response, .01f)
        stiffness = ((2.0 * PI / r) * (2.0 * PI / r)).toFloat()
        damping = (4.0 * PI * dampingFraction / r).toFloat()
        return this
    }

    fun thresholds(valueEpsilon: Float, velocityEpsilon: Float = valueEpsilon * 5f): NavSpring {
        valueThreshold = valueEpsilon; velocityThreshold = velocityEpsilon
        return this
    }

    val settled: Boolean get() = abs(value - target) <= valueThreshold && abs(velocity) <= velocityThreshold

    /** Jump without animation (finger tracking, reduce motion). */
    fun snap(to: Float) { value = to; target = to; velocity = 0f }

    fun animateTo(to: Float, initialVelocity: Float = velocity) { target = to; velocity = initialVelocity }

    /** Takes over [other]'s motion exactly (value, target, velocity, stiffness and damping). */
    fun copyFrom(other: NavSpring) {
        value = other.value; target = other.target; velocity = other.velocity
        stiffness = other.stiffness; damping = other.damping
    }

    /** Advances by [seconds]; returns true while still moving. Sub-stepped so long frames stay stable. */
    fun step(seconds: Float): Boolean {
        if (settled) { value = target; velocity = 0f; return false }
        val total = seconds.coerceIn(0f, .064f)
        val steps = max(1, ceil(total / MAX_STEP).toInt())
        val dt = total / steps
        repeat(steps) {
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * dt
            value += velocity * dt
        }
        if (settled) { value = target; velocity = 0f; return false }
        return true
    }

    private companion object { const val MAX_STEP = 1f / 480f }
}

/** Four springs moving a rectangle together (centre + size), all in the same unit. */
internal class RectSpring(start: ZoomBounds, response: Float, dampingFraction: Float, epsilon: Float = .5f) {
    val cx = NavSpring(start.centerX, response, dampingFraction).thresholds(epsilon)
    val cy = NavSpring(start.centerY, response, dampingFraction).thresholds(epsilon)
    val w = NavSpring(start.width, response, dampingFraction).thresholds(epsilon)
    val h = NavSpring(start.height, response, dampingFraction).thresholds(epsilon)
    // An array and indexed loops: stepped every frame, so no iterator is allocated.
    private val all = arrayOf(cx, cy, w, h)

    val bounds: ZoomBounds get() = ZoomBounds.centered(cx.value, cy.value, w.value, h.value)
    val settled: Boolean get() = cx.settled && cy.settled && w.settled && h.settled

    fun configure(response: Float, dampingFraction: Float) { for (i in all.indices) all[i].configure(response, dampingFraction) }

    fun snap(to: ZoomBounds) {
        cx.snap(to.centerX); cy.snap(to.centerY); w.snap(to.width); h.snap(to.height)
    }

    fun animateTo(to: ZoomBounds) {
        cx.animateTo(to.centerX); cy.animateTo(to.centerY); w.animateTo(to.width); h.animateTo(to.height)
    }

    fun step(seconds: Float): Boolean {
        var moving = false
        for (i in all.indices) if (all[i].step(seconds)) moving = true
        return moving
    }

    /** Moves the rectangle by ([dx], [dy]) keeping its target and momentum (the frame it lands in moved). */
    fun shift(dx: Float, dy: Float) { cx.value += dx; cy.value += dy }

    /** Continues [other]'s flight from where it is, with its momentum. */
    fun copyFrom(other: RectSpring) { for (i in all.indices) all[i].copyFrom(other.all[i]) }
}
