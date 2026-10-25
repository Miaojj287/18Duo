package com.jake.duolauncher

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager

/** A display mode reduced to what the refresh choice needs (JVM-testable). */
internal data class RefreshMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/** The fastest mode with the current resolution: a refresh-rate-only switch is seamless. */
internal fun highestRefreshMode(modes: List<RefreshMode>, current: RefreshMode?): RefreshMode? =
    modes.filter { current == null || (it.width == current.width && it.height == current.height) }
        .filter { it.refreshRate.isFinite() && it.refreshRate > 0f }
        .maxByOrNull { it.refreshRate }

/**
 * Decides when Home needs the panel's top refresh rate. A touch boosts at once; frames drawn back
 * to back (≤ [animationGapNanos] apart) are an animation (page settle, fold, folder, menu, app
 * zoom, StandBy, Spotlight…) and keep the boost; [idleNanos] without either releases it so the
 * system can drop the panel back to its idle rate. A lone redraw (clock tick, widget update)
 * never boosts.
 */
internal class FrameRateGovernor(
    private val idleNanos: Long = 600_000_000L,
    private val animationGapNanos: Long = 50_000_000L,
) {
    var boosted = false
        private set
    private var lastFrameNanos = Long.MIN_VALUE
    private var lastActivityNanos = Long.MIN_VALUE

    /** Returns true when this input starts a boost. */
    fun onInput(nowNanos: Long): Boolean {
        lastActivityNanos = nowNanos
        return boost()
    }

    /** Returns true when this frame starts a boost. */
    fun onFrame(nowNanos: Long): Boolean {
        val previous = lastFrameNanos
        lastFrameNanos = nowNanos
        if (previous == Long.MIN_VALUE || nowNanos - previous > animationGapNanos) return false
        lastActivityNanos = nowNanos
        return boost()
    }

    /** Nanoseconds until the boost may end (0 = release now); null when not boosted. */
    fun remainingNanos(nowNanos: Long): Long? =
        if (!boosted) null else (lastActivityNanos + idleNanos - nowNanos).coerceAtLeast(0L)

    /** Returns true when the boost ended. */
    fun releaseIfIdle(nowNanos: Long): Boolean {
        if (!boosted || remainingNanos(nowNanos)!! > 0L) return false
        boosted = false
        return true
    }

    fun reset() { boosted = false; lastFrameNanos = Long.MIN_VALUE; lastActivityNanos = Long.MIN_VALUE }

    private fun boost(): Boolean {
        if (boosted) return false
        boosted = true
        return true
    }
}

/**
 * Runs Home at the panel's highest refresh rate (120 Hz on the Xiaomi 18 Fold) only while it
 * moves. Android's REQUESTED_FRAME_RATE_CATEGORY_HIGH is satisfied by 90 Hz, which is what the
 * inner panel picked, so a boost asks for the fastest mode explicitly: the window's
 * preferredDisplayModeId (every API level) plus an explicit per-view frame rate (API 35+). At rest
 * both are cleared and the system's adaptive policy takes over again. Platforms or vendor
 * policies that ignore either request simply keep their own rate. Used for Home's window and for
 * the navigation stage overlay (app open/close zoom, switcher), which animates above other apps.
 */
internal class DisplayRefreshBoost(
    private val rootView: () -> View?,
    private val display: () -> Display?,
    /** The thread that owns the window (the navigation stage has its own). */
    looper: Looper = Looper.getMainLooper(),
    private val setPreferredMode: (Int) -> Unit,
) {
    private val governor = FrameRateGovernor()
    private val handler = Handler(looper)
    private var observed: View? = null
    private var appliedModeId = 0
    /** The view that draws (API 35 needs the vote on the drawing view itself, not only its parent). */
    var contentView: View? = null

    private val drawListener = ViewTreeObserver.OnDrawListener {
        if (governor.onFrame(SystemClock.elapsedRealtimeNanos())) handler.post(applyBoost)
    }
    private val applyBoost = Runnable { apply(true) }
    private val releaseCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtimeNanos()
            if (governor.releaseIfIdle(now)) apply(false)
            else governor.remainingNanos(now)?.let {
                // Still moving: follow a cover/inner display handoff to the new panel's modes.
                setMode(fastestModeId())
                handler.postDelayed(this, it / 1_000_000L + 1L)
            }
        }
    }

    fun attach() {
        if (observed != null) return
        val view = rootView() ?: return
        runCatching { view.viewTreeObserver.addOnDrawListener(drawListener); observed = view }
    }

    fun detach() {
        val view = observed ?: return
        observed = null
        runCatching { view.viewTreeObserver.removeOnDrawListener(drawListener) }
        release()
    }

    /** Direct manipulation: boost before the first moved frame. */
    fun onInput() {
        if (governor.onInput(SystemClock.elapsedRealtimeNanos())) apply(true)
    }

    fun release() {
        handler.removeCallbacks(applyBoost)
        handler.removeCallbacks(releaseCheck)
        if (governor.boosted || appliedModeId != 0) { governor.reset(); apply(false) }
    }

    private fun apply(boost: Boolean) {
        if (boost) {
            handler.removeCallbacks(releaseCheck)
            handler.postDelayed(releaseCheck, 650L)
        }
        setMode(if (boost) fastestModeId() else 0)
        if (Build.VERSION.SDK_INT >= 35) runCatching { requestViewFrameRate(if (boost) fastestRate() else Float.NaN) }
    }

    private fun setMode(modeId: Int) {
        if (modeId == appliedModeId) return
        runCatching { setPreferredMode(modeId); appliedModeId = modeId }
    }

    private fun modes(): Pair<List<RefreshMode>, RefreshMode?> {
        val current = runCatching { display() }.getOrNull() ?: return emptyList<RefreshMode>() to null
        fun map(mode: Display.Mode) = RefreshMode(mode.modeId, mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
        return current.supportedModes.map(::map) to map(current.mode)
    }

    private fun fastestModeId(): Int {
        val (all, current) = modes()
        return highestRefreshMode(all, current)?.id ?: 0
    }

    private fun fastestRate(): Float {
        val (all, current) = modes()
        return highestRefreshMode(all, current)?.refreshRate ?: Float.NaN
    }

    /** NaN = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT (let the system decide). */
    @androidx.annotation.RequiresApi(35)
    private fun requestViewFrameRate(rate: Float) {
        val value = if (rate.isFinite() && rate > 0f) rate else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        val root = rootView() ?: return
        if (Build.VERSION.SDK_INT >= 36 && root is ViewGroup) {
            // Non-forced propagation keeps a widget's own request.
            root.propagateRequestedFrameRate(value, false)
        } else {
            root.requestedFrameRate = value
            contentView?.requestedFrameRate = value
        }
    }

    companion object {
        /** Home's activity window. */
        fun forActivity(activity: Activity) = DisplayRefreshBoost(
            rootView = { activity.window?.decorView },
            display = { activity.display },
        ) { modeId ->
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = modeId
            activity.window.attributes = attributes
        }

        /** An overlay window added through [WindowManager] (the navigation stage). */
        fun forWindow(view: View, windowManager: WindowManager, params: WindowManager.LayoutParams,
            looper: Looper = Looper.getMainLooper()) = DisplayRefreshBoost(
            rootView = { view },
            display = { view.display },
            looper = looper,
        ) { modeId ->
            params.preferredDisplayModeId = modeId
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
        }
    }
}
