package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The Split View part of the navigation stage (owned by [NavigationStage], called on the stage
 * thread only): the two Liquid-Glass panes, their finger-driven reveal and grow springs, the card
 * shrinking over them, and the commit flight into one half while [SplitViewController] makes
 * Android split for real underneath.
 *
 * Springs, never tweens: the reveal follows the card's size and position through a quick critically
 * damped spring (finger smoothing only); each pane's growth is a lightly bouncy spring (SwiftUI
 * `.bouncy`) whose *target* is a function of the card's position, so carrying the card to a side
 * makes that pane swell outward with a small overshoot ("往外弹变大"), carrying it back shrinks it,
 * and crossing to the other side hands the bounce over. Reduce Motion: positions jump, only the
 * reveal still cross-fades.
 */
internal class SplitViewStage(private val view: View, private val density: Float, private val reduced: () -> Boolean) {
    private enum class Phase { OFF, HOVER, COMMIT, CANCEL }

    private var phase = Phase.OFF
    private var available = false
    private var engaged = false
    private var lastP: NavigationGestureProgress? = null
    private var offset = 0f
    /** The dragged card's scale (1 full screen) and the switcher deck's reveal (0…1), fed every frame. */
    private var cardScale = 1f
    private var deck = 0f
    private var w = 0f
    private var h = 0f
    private var packageName: String? = null
    private var userId = 0
    private var warmed = false
    private var lastSide: SplitSide? = null

    private val reveal = NavSpring(0f, REVEAL_RESPONSE, 1f).thresholds(.002f)
    private val leftGrow = NavSpring(0f, GROW_RESPONSE, GROW_DAMPING).thresholds(.002f)
    private val rightGrow = NavSpring(0f, GROW_RESPONSE, GROW_DAMPING).thresholds(.002f)
    private val leftRect = RectSpring(ZoomBounds(0f, 0f, 1f, 1f), COMMIT_RESPONSE, COMMIT_DAMPING)
    private val rightRect = RectSpring(ZoomBounds(0f, 0f, 1f, 1f), COMMIT_RESPONSE, COMMIT_DAMPING)
    /** During the commit the targeted pane dissolves under the arriving card. */
    private val targetAlpha = NavSpring(1f, .3f, 1f).thresholds(.004f)
    private var commitSide: SplitSide? = null
    private var outcome: SplitOutcome? = null
    private var outcomeAt = 0L
    private var commitAt = 0L

    private val glassLeft = SplitViewGlass("duo-split-left")
    private val glassRight = SplitViewGlass("duo-split-right")
    private val controller: SplitViewController? by lazy {
        (view.context as? AccessibilityService)?.let { SplitViewController(it) }
    }

    /** The card is carried to a side with the panes in: the switcher deck must give the hover up. */
    val ownsHover: Boolean get() = phase == Phase.HOVER && SplitViewMotion.ownsHover(offset, reveal.target)
    /** How far the card is carried to a side (0 centred … 1), for the card's own shrink into a pane. */
    val sideness: Float get() = if (phase == Phase.HOVER) SplitViewMotion.sideness(offset) else 0f
    val revealValue: Float get() = reveal.value.coerceIn(0f, 1f)
    val committing: Boolean get() = phase == Phase.COMMIT
    /** The card is (or was just) shaped like a half: crop its pixels from the leading edge. */
    val cropsCard: Boolean get() = phase == Phase.COMMIT || phase == Phase.CANCEL

    /** A new bottom gesture over [task] (null over Home): Split View only for an app on the inner landscape panel. */
    fun begin(width: Float, height: Float, task: RecentTask?, appInFront: Boolean) {
        reset()
        w = width; h = height
        available = appInFront && SplitViewMotion.available(width, height) && SplitViewSettings.enabled(view.context)
        setTask(task)
    }

    /** The app under the card becomes known a little after touch-down (recent tasks load on a worker). */
    fun setTask(task: RecentTask?) {
        // A card with no launchable component (a system screen) cannot be relaunched into a half.
        packageName = task?.takeIf { it.component != null }?.packageName
        userId = task?.user?.hashCode() ?: 0
    }

    /** Finger moved. Returns true when the panes own the hover (the deck must stay out). */
    fun drag(p: NavigationGestureProgress, cardCenterX: Float): Boolean {
        if (!available || phase == Phase.COMMIT || phase == Phase.CANCEL) return false
        lastP = p
        if (p.engaged) engaged = true
        offset = SplitViewMotion.offset(cardCenterX, w)
        update()
        return ownsHover
    }

    /**
     * Every frame of the drag: the card's current [scale] and the deck's reveal drive the panes'
     * reveal (they fade in as the card shrinks, recede while the deck is in).
     */
    fun follow(scale: Float, deckReveal: Float) {
        if (!available || phase == Phase.COMMIT || phase == Phase.CANCEL) return
        if (scale == cardScale && deckReveal == deck) return
        cardScale = scale; deck = deckReveal
        update()
    }

    /** The hover was recognised while the finger rested (no move event follows). */
    fun engage(cardCenterX: Float): Boolean {
        if (!available || phase == Phase.COMMIT) return false
        engaged = true
        offset = SplitViewMotion.offset(cardCenterX, w)
        update()
        return ownsHover
    }

    private fun update() {
        val p = lastP
        // A deliberate drag (or a hover) shows the panes; a flick home never does (like the deck).
        val calm = packageName != null && (engaged || p == null || p.velocity < SwitcherMotion.REVEAL_MAX_VELOCITY)
        val r = SplitViewMotion.reveal(offset, cardScale, deck, calm)
        phase = if (r > 0f || reveal.value > .003f) Phase.HOVER else Phase.OFF
        go(reveal, r)
        val g = if (r > 0f) SplitViewMotion.grow(offset) else 0f
        val side = SplitViewMotion.side(offset)
        go(leftGrow, if (side == SplitSide.LEFT) g else 0f)
        go(rightGrow, if (side == SplitSide.RIGHT) g else 0f)
        if (r >= .5f && side != lastSide) {
            // iOS taps once as a pane takes the card.
            lastSide = side
            view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                else HapticFeedbackConstants.CLOCK_TICK)
        }
        if (r < .2f) lastSide = null
        val pkg = packageName
        if (r > 0f && !warmed && pkg != null) { warmed = true; controller?.prewarm(pkg, userId) }
        view.invalidate()
    }

    /** The dragged card carried into a pane: it shrinks toward its hover size (recording f040–f046, photo 9). */
    fun adjustCard(drag: ZoomBounds): ZoomBounds =
        if (phase == Phase.HOVER) SplitViewMotion.hoverCard(drag, w, sideness * revealValue) else drag

    /** The release puts the app into a half, or null (today's behaviour). */
    fun commitSide(p: NavigationGestureProgress, cardCenterX: Float): SplitSide? {
        if (!available || phase != Phase.HOVER || packageName == null) return null
        val o = SplitViewMotion.offset(cardCenterX, w)
        return SplitViewMotion.commit(o, p.velocityX, p.velocity, w, revealValue, engaged)
    }

    /**
     * Starts the split: both panes morph into the two halves (the target one dissolving as the card
     * lands in it) while the controller works; [done] follows on the stage thread.
     */
    fun startCommit(side: SplitSide, screenRadius: Float, done: (SplitOutcome) -> Unit): Boolean {
        val pkg = packageName ?: return false
        val c = controller ?: return false
        phase = Phase.COMMIT
        commitSide = side; outcome = null; commitAt = SystemClock.uptimeMillis()
        val panes = panes(screenRadius)
        leftRect.snap(panes.left); rightRect.snap(panes.right)
        leftRect.configure(COMMIT_RESPONSE, COMMIT_DAMPING); rightRect.configure(COMMIT_RESPONSE, COMMIT_DAMPING)
        leftRect.animateTo(SplitViewMotion.half(SplitSide.LEFT, w, h))
        rightRect.animateTo(SplitViewMotion.half(SplitSide.RIGHT, w, h))
        if (reduced()) { leftRect.snap(SplitViewMotion.half(SplitSide.LEFT, w, h)); rightRect.snap(SplitViewMotion.half(SplitSide.RIGHT, w, h)) }
        targetAlpha.snap(1f); targetAlpha.animateTo(0f)
        go(reveal, 1f)
        c.enter(pkg, userId, side, view.width) { result ->
            view.post { if (phase == Phase.COMMIT) { outcome = result; outcomeAt = SystemClock.uptimeMillis(); done(result) } }
        }
        view.invalidate()
        return true
    }

    /** Commit flight done and Android has split (plus a beat for its own transition under us). */
    fun readyToReveal(cardSettled: Boolean): Boolean {
        val o = outcome ?: return false
        return o.ok && cardSettled && leftRect.settled && rightRect.settled &&
            SystemClock.uptimeMillis() - outcomeAt >= SYSTEM_SETTLE_MS
    }

    /** Split refused: the panes go back out while the card returns to full screen. */
    fun cancel() {
        phase = Phase.CANCEL
        go(reveal, 0f)
        view.invalidate()
    }

    /** The release was not a split (Home, the switcher, back to the app): the panes fade out under it. */
    fun dismiss() {
        if (phase != Phase.HOVER) return
        if (warmed) { warmed = false; controller?.cool() }
        cancel()
    }

    fun step(dt: Float): Boolean {
        var moving = false
        if (reveal.step(dt)) moving = true
        if (leftGrow.step(dt)) moving = true
        if (rightGrow.step(dt)) moving = true
        if (phase == Phase.COMMIT) {
            if (leftRect.step(dt)) moving = true
            if (rightRect.step(dt)) moving = true
            if (targetAlpha.step(dt)) moving = true
        }
        if (phase == Phase.CANCEL && reveal.value <= .003f) phase = Phase.OFF
        return moving
    }

    /** Draws the panes between the backdrop and the cards. [backdrop] draws the stage's backdrop in px. */
    fun draw(canvas: Canvas, screenRadius: Float, backdrop: (Canvas) -> Unit) {
        if (phase == Phase.OFF || !available) return
        val r = revealValue
        if (r <= .003f) return
        val dark = DuoAppearanceRuntime.dark
        val (left, right, radius) = if (phase == Phase.COMMIT)
            SplitPanes(leftRect.bounds, rightRect.bounds, SplitViewMotion.paneRadius(w, screenRadius)) else panes(screenRadius)
        val side = commitSide
        val la = r * (if (phase == Phase.COMMIT && side == SplitSide.LEFT) targetAlpha.value else 1f)
        val ra = r * (if (phase == Phase.COMMIT && side == SplitSide.RIGHT) targetAlpha.value else 1f)
        val d = density
        glassLeft.draw(canvas, left.left * d, left.top * d, left.right * d, left.bottom * d, radius * d, la, d, dark, backdrop)
        glassRight.draw(canvas, right.left * d, right.top * d, right.right * d, right.bottom * d, radius * d, ra, d, dark, backdrop)
    }

    private fun panes(screenRadius: Float): SplitPanes =
        SplitViewMotion.panes(w, h, revealValue, leftGrow.value, rightGrow.value, screenRadius)

    /** The half the card flies into. */
    fun half(side: SplitSide): ZoomBounds = SplitViewMotion.half(side, w, h)

    fun reset() {
        if (warmed && phase != Phase.COMMIT) controller?.cool()
        phase = Phase.OFF; engaged = false; lastP = null; offset = 0f; warmed = false; lastSide = null
        cardScale = 1f; deck = 0f
        commitSide = null; outcome = null
        reveal.snap(0f); leftGrow.snap(0f); rightGrow.snap(0f); targetAlpha.snap(1f)
        glassLeft.release(); glassRight.release()
    }

    fun release() { reset(); controller?.stop() }

    /** Longest the commit may wait for Android before the stage gives up (shell connect + split). */
    val timedOut: Boolean get() = phase == Phase.COMMIT && SystemClock.uptimeMillis() - commitAt > COMMIT_WATCHDOG

    private fun go(spring: NavSpring, target: Float) {
        if (reduced() && spring !== reveal) spring.snap(target) else spring.animateTo(target)
    }

    private companion object {
        const val REVEAL_RESPONSE = .22f
        /** SwiftUI `.bouncy` (bounce 0.3 → damping fraction 0.7), a touch quicker as it follows the finger. */
        const val GROW_RESPONSE = .42f
        const val GROW_DAMPING = .7f
        const val COMMIT_RESPONSE = .42f
        const val COMMIT_DAMPING = .86f
        /** Android's own split transition runs under the opaque stage; reveal once it has settled. */
        const val SYSTEM_SETTLE_MS = 380L
        const val COMMIT_WATCHDOG = 7_000L
    }
}

/** Card centre x (dp) for the drag, as SwitcherMotion.dragCard places it. */
internal fun splitCardCenterX(p: NavigationGestureProgress, width: Float): Float = width / 2f + p.dx * SwitcherMotion.DRAG_X_FOLLOW
