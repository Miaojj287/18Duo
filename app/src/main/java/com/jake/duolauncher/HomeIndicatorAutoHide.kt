package com.jake.duolauncher

/**
 * When the home indicator over an app fades away and comes back (owner feedback, round 3 item 3).
 *
 * iOS hides the indicator once it has been on screen for a while with nothing happening around
 * it, and brings it back when the user reaches for it. The bar is [visible] for [hideAfterMs]
 * after the last [wake] — the app (or one of its windows) appearing, a return from the switcher or
 * a transition, a touch on the bottom gesture area — and then fades out. A gesture that is in
 * progress ([hold] … [release]) freezes it: a showing bar never disappears under a finger, a
 * hidden one does not pop up because of a Back swipe. Pure logic (JVM-tested); the stage draws it.
 *
 * 4 s: long enough to outlast the app's own appearance (≈ 0.5 s) and a first look at it, so the
 * user who opened something by mistake still sees where to swipe; short enough to read as "after a
 * while" (iOS's auto-hidden indicator goes after a few seconds without interaction). Unlike iOS it
 * does not return on any touch inside the app — we only see touches on our own edge windows — so
 * this errs to the long side of iOS-like 3–5 s.
 */
internal class HomeIndicatorAutoHide(private val hideAfterMs: Long = HIDE_AFTER_MS) {
    private var awake = false
    private var wokeAt = 0L
    private var holding = false
    private var visibleWhenHeld = false

    /** Something the user should see the bar for happened at [now]: show it and restart the countdown. */
    fun wake(now: Long) {
        awake = true
        wokeAt = now
        if (holding) visibleWhenHeld = true
    }

    /** A gesture began: the bar keeps whatever it shows now until [release]. */
    fun hold(now: Long) {
        if (holding) return
        visibleWhenHeld = visible(now)
        holding = true
    }

    /** The gesture ended: a bar that was showing gets a fresh countdown, a hidden one stays hidden. */
    fun release(now: Long) {
        if (!holding) return
        holding = false
        if (visibleWhenHeld) wake(now)
    }

    val held: Boolean get() = holding

    /** The bar should be shown at [now] (when an app is in front; Home never shows it). */
    fun visible(now: Long): Boolean = if (holding) visibleWhenHeld else awake && now - wokeAt < hideAfterMs

    /** Uptime at which [visible] turns false by itself; null when it will not without another event. */
    fun hideAt(): Long? = if (holding || !awake) null else wokeAt + hideAfterMs

    companion object {
        const val HIDE_AFTER_MS = 4_000L
    }
}
