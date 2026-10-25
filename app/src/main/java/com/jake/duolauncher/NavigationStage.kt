package com.jake.duolauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What the stage needs from the accessibility service; implemented by [NavigationOverlay]. */
internal interface NavigationStageHost {
    fun setStageTouchable(touchable: Boolean)
    /**
     * Starts [task] under the stage, off the stage thread (a binder call must never hold a frame);
     * [onResult] follows on the stage thread, false if Android refused.
     */
    fun launchTask(task: RecentTask, onResult: (Boolean) -> Unit)
    /** Brings the launcher to the front under the stage for a gesture return. */
    fun startHome()
    /** The system Home action, for a flick on Home itself. */
    fun performSystemHome()
    /**
     * Round 12.1: whether an application window of [packageName] is still in the accessibility window
     * list. The app a gesture leaves keeps its window until the system's own close transition has
     * ended; the stage must keep covering Home until then.
     */
    fun appWindowPresent(packageName: String): Boolean
    fun dismissTask(task: RecentTask)
    /**
     * Calls [onShown] on the stage thread once [packageName]'s window is up; `true` when that was
     * confirmed (window event / resume), `false` after a fallback delay or timeout.
     */
    fun awaitForeground(packageName: String, sinceWallClock: Long, onShown: (confirmed: Boolean) -> Unit)
    /** Android Back, for a Back the user gave while the stage was still finishing a transition. */
    fun performBack()
    /**
     * r12b-switcher: a transient copy of the live Home as it looks right now (blurred and shrunk under
     * an open switcher); [onDone] on the stage thread, null when it could not be copied.
     */
    fun captureHome(onDone: (HomeSnapshot?) -> Unit)
    val reduceMotion: Boolean
    /** Physical display corner radius in dp (a sensible iPhone-like value when Android reports none). */
    val screenCornerRadius: Float
}

/**
 * Full-screen, draw-only accessibility overlay that renders all of iOS's navigation motion:
 * the home indicator, the live app shrinking into a card (a screenshot taken when the finger
 * lands), the card flying back into its Home icon, the app switcher deck over a blurred Home,
 * quick switching along the indicator, the icon-to-app zoom and a minimal back hint.
 *
 * The real app never moves: the stage covers it with pixels that look identical and only lets
 * Android change what is underneath once the gesture has committed, so pulling back, opening the
 * switcher or tapping the current card leave the app untouched (no pause, no PiP, no reload).
 * The window is touchable only while the switcher is open.
 *
 * Threading: the stage lives on its own Looper thread (see NavigationOverlay): it was added to the
 * WindowManager there, so its Choreographer, input and drawing run there and never wait for the
 * launcher's main thread — which is busiest exactly when Home resumes under a flying card. Every
 * public method must be called on that thread; [busy]/[interactive]/[sized] are safe to read anywhere.
 * What it needs from Home during a flight is published by Home as immutable values it reads
 * without asking ([LaunchIconRegistry.published], [HomeSnapshotStore]); binder calls (launching,
 * Home, Back) run on the overlay's action thread, never here.
 */
internal class NavigationStage(context: Context, private val host: NavigationStageHost) : View(context) {
    private enum class Mode { IDLE, PENDING, APP_DRAG, HOME_DRAG, QUICK, FLY_HOME, SWITCHER, OPEN_CARD, RETURN_APP, QUICK_SETTLE, LAUNCH, EXIT_HOME, FADE_OUT,
        /* r8-split */ SPLIT }

    /** One deck card. [current] is the app under the stage right now. */
    private class Entry(var task: RecentTask?, val current: Boolean, slot: Float) {
        val slot = NavSpring(slot, .36f, .9f).thresholds(.002f)
        val lift = NavSpring(0f, .32f, 1f).thresholds(.5f)
        /** Over Home: 0 = where the card waited off the left edge ([arriveFrom]), 1 = its deck place; overshoots (bounce). */
        val arrive = NavSpring(1f, SwitcherMotion.ARRIVE_RESPONSE, SwitcherMotion.ARRIVE_DAMPING).thresholds(.001f)
        var arriveFrom: ZoomBounds? = null
    }

    /** A card flying off after a dismissal; [index] keeps its place in the deck's z-order. */
    private class Ghost(val entry: Entry, val index: Int, val rect: ZoomBounds, val radius: Float, val lift: NavSpring)

    private data class Placed(val entry: Entry, val index: Int, val rect: ZoomBounds, val visible: Float,
        val iconAlpha: Float, val labelAlpha: Float, val alpha: Float = 1f)

    private val density = resources.displayMetrics.density
    private val w get() = width / density
    private val h get() = height / density
    private val fullScreen get() = ZoomBounds(0f, 0f, w, h)

    private var mode = Mode.IDLE
        set(value) { field = value; busyFlag = value != Mode.IDLE; interactiveFlag = value == Mode.SWITCHER }
    @Volatile private var busyFlag = false
    @Volatile private var interactiveFlag = false
    /** Laid out with a real size (readable from any thread). */
    @Volatile var sized = false
        private set
    private val entries = ArrayList<Entry>()
    private val ghosts = ArrayList<Ghost>()
    private var foregroundExpected = false
    private var capture: Bitmap? = null
    private var captureSecure = false
    /** The gesture's screenshot arrived or will not come (failed, not allowed, secure). */
    private var captureDone = false
    private var cardVisible = false
    private var downAt = 0L
    private var currentDismissed = false
    private var homeSnapshot: HomeSnapshot? = null
    private var pendingRelease: (() -> Unit)? = null
    private var switcherSwipe = false

    // Moving card / deck anchor.
    private val card = RectSpring(ZoomBounds(0f, 0f, 1f, 1f), FOLLOW_RESPONSE, 1f, .4f)
    private var anchorLive = true
    private var frozenAnchor = ZoomBounds(0f, 0f, 1f, 1f)
    private var cardEntry: Entry? = null
    private var flightFrom = ZoomBounds(0f, 0f, 1f, 1f)
    private var flightTo = ZoomBounds(0f, 0f, 1f, 1f)
    private var flightFromRadius = 0f
    private var flightToRadius = 0f
    private var flightFallback = false
    private var flightStart = 0L
    private var bdFrom = BackdropStyle(1f, 0f, 0f)
    private var handedOff = false
    private var appShown = false
    private var launchFromIcon: Bitmap? = null
    /** [launchFromIcon] is a widget's pixels: drawn filling the card, not as a centred icon. */
    private var launchFromWidget = false
    private var homeDragTravel = 0f
    /** [cardEntry] is drawn on its own at [card] (flying home, opening, returning, launching). */
    private var movingActive = false
    private var landedFrames = -1
    /** The quick-switch layout stays on screen through its fade-out. */
    private var quickActive = false
    /** Bumped whenever the stage ends, so a slow "app is up" callback never reaches the next transition. */
    private var showGen = 0
    /** App the current transition ends in (for the home indicator style once the stage is gone). */
    private var landingPackage: String? = null
    /** A Back given while an app was still opening under the stage; delivered when the stage ends. */
    private var deferredBack = false
    private var flightIconId: String? = null
    /** Reduce Motion return: the card dissolves in place over Home instead of flying. */
    private var flightDissolve = false
    // FLY_HOME aim, re-decided every frame from what Home has published (see [flightAim]).
    private var aim = FlightAim.HOLD
    private var aimIcon: HomeIconEntry? = null
    /** Live layout and task the cached [liveIcon] was picked for (re-picked only when either changes). */
    private var liveLayout: HomeIconLayout? = null
    private var liveTaskId: String? = null
    private var liveIcon: HomeIconEntry? = null
    private var frozenTaskId: String? = null
    private var frozenIcon: HomeIconEntry? = null
    private var frozenPicked = false
    /** Height (dp) the card ends at: the icon's; progress is measured on it so a re-aim stays continuous. */
    private var flightEndHeight = FALLBACK_ICON
    /** Flight progress this frame ([SwitcherMotion.shrinkProgress]) and its maximum so far (Home's un-blur). */
    private var flightP = 0f
    private var backdropP = 0f
    /** The gesture began over Home and the finger has paused: only now may the switcher deck appear. */
    private var homeEngaged = false
    /** The app in front is known for sure (its stored snapshot may stand in for a missing capture). */
    private var currentSure = false
    /** Home's wallpaper, the backdrop when no frozen Home of this panel exists yet (never a grey slab). */
    private var wallpaper: Bitmap? = null
    private var wallpaperRightAnchored = true
    /** Confirmed (not merely timed out) that the app the transition opens is drawn: hard cut, no fade. */
    private var appConfirmed = false
    /** Called once the icon-launch card has been drawn the first time (the real icon may then hide). */
    private var launchStarted: ((Boolean) -> Unit)? = null

    /**
     * A new swipe during a transition catches it where it is instead of cutting the stage off (round 9):
     * APP = the transition was ending in an app, its card is now the card under the finger;
     * HOME = a card flying home keeps landing ([carry]) while the new gesture runs over Home.
     */
    private enum class Catch { NONE, APP, HOME }
    private var caught = Catch.NONE
    @Volatile private var keepsBackdropFlag = false
    @Volatile private var overAppFlag = false
    /** Caught while its app was still opening: a return to it waits until the app is up. */
    private var waitForApp = false
    /** Right after a catch the backdrop follows the finger's style through its springs (no jump). */
    private var catchBlendUntil = 0L
    /** Caught in a quick switch: the finger continues from this offset of the quick layout. */
    private var quickBase = 0f
    /** The card that was flying home when a new swipe began; it keeps landing on its own spring. */
    private val carry = RectSpring(ZoomBounds(0f, 0f, 1f, 1f), FLY_RESPONSE, FLY_DAMPING, .4f)
    private var carrying = false
    private var carryEntry: Entry? = null
    private var carryArt: Bitmap? = null
    private var carryStart = 0L
    /** Set while drawing [carry], so the card is drawn as a flight home. */
    private var drawingCarry = false
    /** This gesture's task list has arrived (until then a quick switch cannot know its neighbours). */
    private var tasksLoaded = false
    /** A quick switch released before the task list: decided as soon as it arrives (or after a grace). */
    private var pendingQuick: NavigationGestureProgress? = null
    /** Damping of a quick drag past the last app, eased in when the list arrives mid-drag (no jump). */
    private var quickDamp = 1f

    private val scroll = NavSpring(0f, .45f, 1f).thresholds(.002f)
    /** Over Home: how far the finger has brought the newest card in from the left edge (0 … 1, capped). */
    private var homeP = 0f
    /** …and the same through a quick follow spring: what is drawn ([SwitcherMotion.homeEntryCard]). */
    private val homeEntry = NavSpring(0f, HOME_FOLLOW_RESPONSE, 1f).thresholds(.001f)
    /** Over Home, the switcher was not opened: the entering card slides back out to the left. */
    private var homeRetreat = false
    /**
     * Round 12: the Home page itself shrinks toward its centre under the finger ([SwitcherMotion.homeShrink]),
     * through a quick follow spring while dragging and the `.bouncy` spring when a flick lets it go.
     */
    private val homeScale = NavSpring(1f, HOME_FOLLOW_RESPONSE, 1f).thresholds(.001f)
    /** A Home-origin gesture zoom is running in the launcher ([AppOpenZoomController.beginGesture] with homeOrigin). */
    private var homeZoomActive = false
    /**
     * Round 12: the gesture began over the live Home and no frozen copy is wanted: Home shrinks and
     * blurs itself (the launcher's layer), the stage only draws the dim and the cards — nothing to
     * hand over when it ends. False for a gesture that caught a card still landing (its frozen Home
     * is still on screen) and for every gesture over an app.
     */
    private var liveHome = false
    /** Over the live Home with a card opening: the stage must cover the app window coming up under it. */
    private var coverLive = false
    /** The live Home copied as it looks under the open switcher (already blurred and shrunk): drawn as is. */
    private var liveFrozen: HomeSnapshot? = null
    private var liveCaptureRequested = false
    private var liveCaptureGen = 0
    /** An open switcher at rest over the live Home keeps its gesture alive for the launcher's watchdog. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (mode == Mode.SWITCHER && homeZoomActive) { AppOpenZoomController.touchGesture(); postDelayed(this, HEARTBEAT_MS) }
        }
    }
    /** The finger's last state while it drags the app card (re-applied every frame as the deck engages). */
    private var dragP: NavigationGestureProgress? = null
    /** Deck: where the touched card's left edge was when the finger landed (deck space). */
    private var touchLeft0 = 0f
    private val deckReveal = NavSpring(0f, .28f, 1f).thresholds(.004f)
    private val labelReveal = NavSpring(0f, .3f, 1f).thresholds(.004f)
    private val exitScale = NavSpring(1f, .4f, 1f).thresholds(.001f)
    private val bdZoom = NavSpring(1f, .45f, 1f).thresholds(.0005f)
    private val bdBlur = NavSpring(0f, .4f, 1f).thresholds(.004f)
    private val bdDim = NavSpring(0f, .4f, 1f).thresholds(.004f)
    private val bdAlpha = NavSpring(1f, .16f, 1f).thresholds(.004f)
    private val stageAlpha = NavSpring(1f, .14f, 1f).thresholds(.004f)
    private val cardAlpha = NavSpring(1f, .12f, 1f).thresholds(.004f)
    private val quickDx = NavSpring(0f, .36f, .92f).thresholds(.3f)
    private var quickLift = 0f
    private var quickDirection = 0
    private var quickOrder: List<Entry> = emptyList()
    private var quickIndex = 0
    private val quickSession = QuickSwitchSession()
    // r8-split: Split View panes and commit (SplitViewStage.kt).
    private val split = SplitViewStage(this, density) { reduced }

    // Home indicator and back hint.
    private val barAlpha = NavSpring(0f, BAR_RESPONSE, 1f).thresholds(.004f)
    /** Fades the indicator away over an app after a while, brings it back on interaction (round 3, item 3). */
    private val autoHide = HomeIndicatorAutoHide().also { it.wake(SystemClock.uptimeMillis()) }
    private val barCheck = Runnable { updateBar() }
    private val barDark = NavSpring(0f, .3f, 1f).thresholds(.004f)
    private val barHalo = NavSpring(1f, .3f, 1f).thresholds(.004f)
    private var homeVisible = false
    private var barStyle = HomeBarStyle.ADAPTIVE
    private var backEdge: NavigationEdge? = null
    private var backY = 0f
    private val backProgress = NavSpring(0f, .14f, 1f).thresholds(.004f)
    private var backArmed = false

    // Drawing.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        // Photo 5: the name is SF semibold 17 pt on a 393 pt screen = 4.3 % W → 18 sp on the 425 dp cover, white with a faint shadow.
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SwitcherMotion.LABEL_TEXT, resources.displayMetrics)
        typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        setShadowLayer(3f * density, 0f, 1f * density, Color.argb(70, 0, 0, 0))
    }
    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
    }
    /**
     * BitmapShader holds its bitmap strongly, so this cache must never outlive the transition:
     * bounded, and cleared whenever the stage ends (a WeakHashMap would pin every capture forever).
     */
    private val shaders = TransientDrawCache<Bitmap, BitmapShader>(MAX_SHADERS) {
        BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }
    private val iconRect = RectF()
    private val fitRect = RectF()
    private var placeholderVisible = Float.MAX_VALUE
    private val labelRect = RectF()
    private val ellipsized = HashMap<String, Pair<Float, CharSequence>>()
    private val emptyText: String by lazy { context.getString(R.string.switcher_empty) }
    private val matrix = Matrix()
    private val rectF = RectF()
    /*
     * Each node records a black fill under the frozen Home and is faded with setAlpha. A bare
     * RenderNode does not composite through a layer by default, so the alpha used to hit each op
     * separately: the black at α darkened the live Home under it, then the bitmap at α only partly
     * covered that — a·Home + (1−a)²·Home, a whole-screen dip of up to 25 % for the ~0.3 s the frozen
     * Home handed over to the live one (round 3, item 4, the "flash"). As one overlapping layer the
     * node fades as a picture: a·frozen + (1−a)·live, i.e. no change at all where they match.
     */
    private val sharpNode = RenderNode("duo-home").apply { setHasOverlappingRendering(true) }
    private val blurNode = RenderNode("duo-home-blur").apply { setHasOverlappingRendering(true) }
    private val backdropNodes = arrayOf(sharpNode, blurNode)
    private var nodeSource: Bitmap? = null
    private var nodeSize = 0L

    // Touch (switcher only).
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var velocity: VelocityTracker? = null
    private var touchMode = 0 // 0 undecided, 1 scroll, 2 dismiss, 3 ignore
    private var touchIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var scrollStart = 0f

    private var frameScheduled = false
    private var lastFrame = 0L
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val springs: Array<NavSpring> by lazy {
        arrayOf(scroll, homeEntry, homeScale, deckReveal, labelReveal, exitScale, bdZoom, bdBlur, bdDim, bdAlpha, stageAlpha, cardAlpha,
            quickDx, barAlpha, barDark, barHalo, backProgress)
    }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    val interactive: Boolean get() = interactiveFlag
    val busy: Boolean get() = busyFlag
    /** The gesture that just began is over an app (its card is under the finger), not over Home. */
    val gestureOverApp: Boolean get() = overAppFlag
    /** A caught transition keeps its frozen Home: a copy of Home taken now would be mid-transition. */
    val keepsBackdrop: Boolean get() = keepsBackdropFlag

    // ------------------------------------------------------------------ seams for other features

    /** Split View (r8-split) follows the dragged card through this; stage thread only. */
    var dragListener: SwitcherDragListener? = null
    private var dragPublished = false

    /** The app card the finger holds right now, or null when no app is being dragged up. Stage thread. */
    fun dragState(): SwitcherDragState? {
        val p = dragP ?: return null
        if (mode != Mode.APP_DRAG) return null
        return SwitcherDragState(card.bounds, p.x, p.travel, p.dx, p.velocity, p.velocityX, p.engaged,
            deckReveal.value.coerceIn(0f, 1f), w, h, entries.firstOrNull { it.current }?.task)
    }

    private fun publishDrag() {
        val listener = dragListener ?: return
        val state = dragState()
        if (state == null && !dragPublished) return
        dragPublished = state != null
        listener.onDrag(state)
    }

    /** Whether the stage draws anything over the app right now besides the home indicator (no background capture then). */
    val drawsOverApp: Boolean get() = busyFlag || backProgress.value > .01f || backProgress.target > 0f

    /**
     * The home indicator's rectangle in pixels while it is visible over the app, so a background
     * capture can paint it out ([SnapshotPolicy.patchRows]); null when it is hidden.
     */
    fun indicatorRectPx(): android.graphics.Rect? {
        if (barAlpha.value <= .003f && barAlpha.target <= 0f || width == 0) return null
        val halfW = SwitcherMotion.homeBarWidth(w, h) / 2f * density
        val halfH = SwitcherMotion.HOME_BAR_HEIGHT / 2f * density
        val cy = height - (SwitcherMotion.HOME_BAR_BOTTOM + SwitcherMotion.HOME_BAR_HEIGHT / 2f) * density
        val pad = 2f * density
        return android.graphics.Rect((width / 2f - halfW - pad).toInt(), (cy - halfH - pad).toInt(),
            (width / 2f + halfW + pad).toInt() + 1, (cy + halfH + pad).toInt() + 1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sized = w > 0 && h > 0
    }

    private val reduced: Boolean get() = host.reduceMotion || !ValueAnimator.areAnimatorsEnabled()

    // ------------------------------------------------------------------ home indicator

    fun setHomeVisible(visible: Boolean, launchedPackage: String?) {
        homeVisible = visible
        // Leaving Home for an app: the indicator shows for the new app, then fades after a while.
        if (!visible) autoHide.wake(SystemClock.uptimeMillis())
        if (!visible && mode == Mode.IDLE) setBarStyle(launchedPackage)
        updateBar()
    }

    /**
     * The app in front changed without us (notification, shortcut, another app): restyle the bar.
     * [newWindow]: a window of it just became active (window-state event), which also brings a
     * faded indicator back, as a new screen does; a usage poll naming the same app does not.
     */
    fun setForegroundPackage(packageName: String, newWindow: Boolean = false) {
        if (homeVisible) return
        val changed = packageName != barPackage
        if (newWindow || changed) { autoHide.wake(SystemClock.uptimeMillis()); updateBar() }
        if (mode != Mode.IDLE || !changed) return
        setBarStyle(packageName)
    }

    /** The screen came back (unlock): the app in front is seen anew, so is its indicator. */
    fun wakeIndicator() {
        autoHide.wake(SystemClock.uptimeMillis())
        updateBar()
    }

    private var barPackage: String? = null

    private fun setBarStyle(packageName: String?) {
        barPackage = packageName
        // Before a first snapshot exists, the app's splash colour is a good guess of what is behind the bar.
        val luma = AppSnapshotStore.luma(packageName)
            ?: SplashColors.forPackage(packageName)?.let(SnapshotPolicy::luma)
        barStyle = SnapshotPolicy.barStyle(luma)
        go(barDark, if (barStyle == HomeBarStyle.DARK) 1f else 0f)
        go(barHalo, if (barStyle == HomeBarStyle.ADAPTIVE) 1f else 0f)
        schedule()
    }

    private fun updateBar() {
        val now = SystemClock.uptimeMillis()
        // Over an app at rest — or under a finger resting on the bottom edge once its screenshot is
        // taken (iOS brings the indicator back where you reach for it; the gesture still runs on that touch).
        val resting = mode == Mode.IDLE ||
            mode == Mode.PENDING && foregroundExpected && captureDone && now - downAt >= HELD_TOUCH_MS
        val show = !homeVisible && resting && autoHide.visible(now)
        val target = if (show) 1f else 0f
        if (barAlpha.target != target) {
            // Fading away by itself is slow and gentle (iOS); appearing, or making way for a gesture, is quick.
            barAlpha.configure(if (!show && resting && !homeVisible) BAR_AUTO_HIDE_RESPONSE else BAR_RESPONSE, 1f)
            go(barAlpha, target)
        }
        removeCallbacks(barCheck)
        val hideAt = autoHide.hideAt()
        if (show && hideAt != null) postDelayed(barCheck, (hideAt - now).coerceAtLeast(0L) + 1L)
        schedule()
    }

    // ------------------------------------------------------------------ bottom gesture

    /** Finger down on the bottom edge. Returns true when an app is under the stage (capture needed). */
    fun bottomDown(launcherVisible: Boolean): Boolean {
        if (mode == Mode.SWITCHER) { switcherSwipe = true; return false }
        val catch = when {
            reduced || flightDissolve -> Catch.NONE
            mode == Mode.LAUNCH || mode == Mode.OPEN_CARD || mode == Mode.RETURN_APP ->
                if (cardEntry?.task != null) Catch.APP else Catch.NONE
            mode == Mode.QUICK_SETTLE -> if (quickOrder.getOrNull(quickIndex + quickDirection)?.task != null) Catch.APP else Catch.NONE
            mode == Mode.FLY_HOME -> Catch.HOME
            mode == Mode.EXIT_HOME && foregroundExpected -> Catch.HOME
            else -> Catch.NONE
        }
        if (catch == Catch.APP) return catchApp()
        val keepHome = catch == Catch.HOME && homeSnapshot?.let(::fits) == true
        if (catch == Catch.HOME) beginCarry()
        // A new gesture supersedes a Back still waiting for the previous app.
        else if (mode != Mode.IDLE) finishNow(deliverBack = false)
        caught = catch
        keepsBackdropFlag = keepHome
        mode = Mode.PENDING
        // Caught on its way home, Home is where this gesture starts, whatever the launcher reports yet.
        foregroundExpected = !launcherVisible && catch != Catch.HOME
        overAppFlag = foregroundExpected
        entries.clear(); ghosts.clear()
        if (foregroundExpected) entries += Entry(null, true, 0f)
        capture = null; captureSecure = false; captureDone = false; cardVisible = false
        currentDismissed = false; pendingRelease = null; handedOff = false; appShown = false
        launchFromIcon = null; launchFromWidget = false; cardEntry = null; homeDragTravel = 0f; quickDirection = 0
        appConfirmed = false; flightDissolve = false; homeEngaged = false; currentSure = false
        leavingPackage = null; homeOnScreenAt = 0L; leavingLogged = false
        movingActive = false; quickActive = false; waitForApp = false; quickBase = 0f; tasksLoaded = false
        // Over Home a fresh copy is on its way; an older one might show another page for a frame.
        // Over an app: the frozen Home of *this* panel (after a fold the last one is the other panel's).
        // Caught on its way home: the frozen Home it was landing on stays (Home may not be on screen yet).
        homeSnapshot = if (keepHome) homeSnapshot else if (launcherVisible || catch == Catch.HOME) null
            else frozenHomeForApp(barPackage)
        liveHome = false; coverLive = false; liveFrozen = null; liveCaptureRequested = false; liveCaptureGen++
        homeScale.snap(1f)
        wallpaper = currentWallpaper()
        downAt = SystemClock.uptimeMillis()
        // Hidden at once so the screenshot taken a frame later does not contain it (iOS hides it too).
        // The touch counts as reaching for it: it comes back if the finger rests or lifts without leaving.
        barAlpha.snap(0f)
        if (foregroundExpected) { val now = SystemClock.uptimeMillis(); autoHide.wake(now); autoHide.hold(now) }
        stageAlpha.snap(1f); bdAlpha.snap(1f); exitScale.snap(1f)
        if (!carrying) cardAlpha.snap(1f)
        deckReveal.configure(.28f, 1f)
        deckReveal.snap(0f); labelReveal.snap(0f); scroll.snap(0f)
        homeP = 0f; homeEntry.snap(0f); homeRetreat = false; dragP = null
        split.begin(w, h, null, foregroundExpected) // r8-split
        invalidate(); schedule()
        return foregroundExpected
    }

    /**
     * The frozen Home a gesture over [foregroundPackage] starts on. Round 12: when that app was just
     * opened from its icon, its launch frame (Home with that icon hidden, as the live Home will show
     * it when the card lands) is used from the first frame, not swapped in at the release — the icon
     * no longer blinks out of the blurred backdrop the moment the finger lets go.
     */
    private fun frozenHomeForApp(foregroundPackage: String?): HomeSnapshot? {
        val latest = HomeSnapshotStore.forSize(width.toFloat(), height.toFloat())
        val launch = HomeSnapshotStore.launchFrame ?: return latest
        val pkg = foregroundPackage ?: return latest
        val launchPackage = parseProfileAppId(launch.appId)?.component?.substringBefore('/') ?: return latest
        if (launchPackage != pkg || !fits(launch.snapshot)) return latest
        return if (launch.snapshot.capturedAt >= (latest?.capturedAt ?: 0L)) launch.snapshot else latest
    }

    /**
     * Catches a transition that was ending in an app (an icon launch, a card opening, a quick switch,
     * a return): its card stays exactly where it is, with its momentum, and becomes the card under
     * the finger. The app keeps opening underneath; no screenshot is taken (it would photograph the stage).
     */
    private fun catchApp(): Boolean {
        val now = SystemClock.uptimeMillis()
        val fromQuick = mode == Mode.QUICK_SETTLE
        val wasReturning = mode == Mode.RETURN_APP
        val oldEntry = if (fromQuick) quickOrder.getOrNull(quickIndex + quickDirection) else cardEntry
        val task = oldEntry?.task ?: return false
        val art = if (fromQuick || mode == Mode.OPEN_CARD || wasReturning) snapshotFor(oldEntry) else null
        // The launch card has been seen: the app starts, as it would have.
        launchStarted?.let { started -> launchStarted = null; post { started(true) } }
        val shown = appShown || wasReturning
        if (fromQuick) {
            // The quick layout continues from where it is: same cards, the one arriving is now current.
            val order = quickOrder.map { e -> Entry(e.task, e === oldEntry, 0f) }
            val target = quickIndex + quickDirection
            quickBase = quickDx.value - SwitcherMotion.quickTarget(quickDirection, w)
            entries.clear(); ghosts.clear(); entries += order
            quickOrder = order; quickIndex = target
            cardEntry = order[target]; movingActive = false
        } else {
            entries.clear(); ghosts.clear()
            val entry = Entry(task, true, 0f)
            entries += entry
            cardEntry = entry; movingActive = true
            quickActive = false; quickBase = 0f
        }
        mode = Mode.PENDING
        caught = Catch.APP
        keepsBackdropFlag = true; overAppFlag = true
        foregroundExpected = true
        capture = art; captureSecure = false; captureDone = true; cardVisible = true
        currentSure = art != null
        currentDismissed = false; pendingRelease = null; handedOff = false
        launchFromIcon = null; launchFromWidget = false; homeDragTravel = 0f; quickDirection = 0
        // Out of a quick switch the neighbours are already known (its order); otherwise the list is on its way.
        flightDissolve = false; homeEngaged = false; tasksLoaded = fromQuick; pendingQuick = null
        appShown = shown; waitForApp = !shown
        landingPackage = task.packageName
        if (homeSnapshot?.let(::fits) != true) homeSnapshot = HomeSnapshotStore.forSize(width.toFloat(), height.toFloat())
        if (wallpaper == null) wallpaper = currentWallpaper()
        downAt = now; flightStart = now; catchBlendUntil = now + CATCH_BLEND_MS
        barAlpha.snap(0f)
        run { autoHide.wake(now); autoHide.hold(now) }
        stageAlpha.snap(1f); bdAlpha.snap(1f); cardAlpha.snap(1f); exitScale.snap(1f)
        homeP = 0f; homeEntry.snap(0f); homeRetreat = false; dragP = null
        split.begin(w, h, null, true) // r8-split
        invalidate(); schedule()
        return false
    }

    /**
     * A swipe while a card flies home (or while the switcher closes to Home): the card keeps landing in
     * its icon on its own spring and the new gesture runs over Home, which keeps its frozen copy.
     */
    private fun beginCarry() {
        if (mode == Mode.FLY_HOME && cardEntry != null) {
            carry.copyFrom(card)
            carryEntry = cardEntry
            carryArt = snapshotFor(cardEntry)
            carryStart = flightStart
            carrying = true
            go(bdZoom, 1f)
            bdBlur.configure(.4f, 1f); go(bdBlur, 0f)
            bdDim.configure(.4f, 1f); go(bdDim, 0f)
        } else {
            // The switcher was closing to Home: nothing is flying, Home only needs its zoom back.
            AppOpenZoomController.endGesture()
        }
        // The new gesture takes the stage over without ending it: nothing it set up may go stale.
        showGen++
        launchStarted?.let { launchStarted = null; it(false) }
    }

    /** The carried card lands like FLY_HOME does: icon given back to Home, then the card goes. */
    private fun advanceCarry(now: Long) {
        if (!carrying) return
        if (landedFrames < 0) {
            updateFlightAim(now)
            flightP = SwitcherMotion.shrinkProgress(carry.h.value, flightFrom.height, flightEndHeight)
            AppOpenZoomController.gestureZoom(bdZoom.value)
            if (aim == FlightAim.FALLBACK) cardAlpha.snap(1f - SwitcherMotion.fallbackFade(flightP))
            val aimFinal = aim == FlightAim.ICON || aim == FlightAim.FALLBACK ||
                aim == FlightAim.PROVISIONAL && now - carryStart > HOLD_LIMIT_MS
            if (carry.settled && aimFinal) {
                landedFrames = 0
                if (flightFallback) go(cardAlpha, 0f) else AppOpenZoomController.endGesture()
            }
        } else if (flightFallback) {
            if (cardAlpha.settled && cardAlpha.value <= .01f) endCarry()
        } else if (++landedFrames >= 2 && AppOpenZoomController.restoredIconDrawn || landedFrames >= ICON_RESTORE_FRAMES) endCarry()
        if (carrying && now - carryStart > watchdog) endCarry()
    }

    private fun endCarry() {
        if (!carrying) return
        if (landedFrames < 0 || flightFallback) AppOpenZoomController.endGesture()
        carrying = false; carryEntry = null; carryArt = null
        cardAlpha.snap(1f)
        invalidate()
    }

    private fun drawCarry(canvas: Canvas) {
        val entry = carryEntry ?: return
        val b = carry.bounds
        val radius = flightFromRadius + (flightToRadius - flightFromRadius) * flightP
        val iconMix = if (flightFallback) 0f else SwitcherMotion.iconMix(flightP)
        // Over Home a deck coming in blurs Home: the landing card goes with it rather than float on top.
        val alpha = cardAlpha.value.coerceIn(0f, 1f) * (1f - bdBlur.value.coerceIn(0f, 1f))
        drawingCarry = true
        drawCard(canvas, entry, b, radius, alpha, iconMix, shadow = false, art = carryArt)
        drawingCarry = false
    }

    /**
     * The live screenshot of the app under the finger. Null: none (failed, rate-limited, not
     * allowed) — the card then shows the app's stored snapshot or its launch-screen card; only
     * [secure] (or [markCaptureSecure]) forbids showing any pixels of it.
     */
    fun setCapture(bitmap: Bitmap?, secure: Boolean) {
        if (mode == Mode.IDLE || !foregroundExpected) return
        capture = bitmap
        captureDone = true
        if (secure) captureSecure = true
        showCardIfReady()
        pendingRelease?.let { pendingRelease = null; it() }
        invalidate()
    }

    /** The capture turned out black: a FLAG_SECURE window. Show the icon card instead, never the pixels. */
    fun markCaptureSecure() {
        capture = null; captureSecure = true; captureDone = true
        invalidate()
    }

    /** Still transparent and waiting for the screenshot (a retry would not photograph the stage). */
    val awaitingCapture: Boolean get() = foregroundExpected && !captureDone && !cardVisible &&
        (mode == Mode.PENDING || mode == Mode.APP_DRAG || mode == Mode.QUICK)

    fun setHomeSnapshot(snapshot: HomeSnapshot?) {
        if (snapshot != null && fits(snapshot)) homeSnapshot = snapshot
        invalidate()
    }

    fun setTasks(list: RecentTaskList) {
        if (mode == Mode.IDLE) return
        tasksLoaded = true
        val current = entries.firstOrNull { it.current }
        val others = if (list.foreground) list.tasks.drop(1) else list.tasks
        if (current != null && list.foreground) current.task = list.tasks.first()
        split.setTask(current?.task) // r8-split
        currentSure = list.fileable
        val arrivedLate = mode == Mode.SWITCHER && !foregroundExpected && entries.isEmpty()
        entries.retainAll { it.current }
        others.forEach { task -> entries += Entry(task, false, entries.size.toFloat()) }
        // Released over Home before the list was in: the cards still spring out of the left edge.
        if (arrivedLate) startHomeArrival(SwitcherMotion.homeEntryCard(0f, geometry()))
        if (mode == Mode.QUICK) buildQuickOrder()
        // A quick flick released before the list: now its neighbours are known, decide it.
        pendingQuick?.let { p -> pendingQuick = null; if (mode == Mode.QUICK) quickRelease(p) }
        invalidate(); schedule()
    }

    fun bottomMove(p: NavigationGestureProgress) {
        if (switcherSwipe) return
        if (mode == Mode.PENDING) when (p.axis) {
            BottomAxis.VERTICAL -> if (foregroundExpected) enterAppDrag() else enterHomeDrag()
            BottomAxis.HORIZONTAL -> if (foregroundExpected) enterQuick()
            BottomAxis.UNDECIDED -> Unit
        }
        when (mode) {
            Mode.APP_DRAG -> {
                dragP = p
                val progress = SwitcherMotion.dragProgress(SwitcherMotion.dragScale(p.travel, h), w, h, targetScale())
                // Neighbours slide in under a slow, deliberate drag (iOS); a flick home never shows them.
                // Reversible: pulling the card back down toward full screen sends them away again.
                val near = p.travel >= h * SwitcherMotion.MIN_TRAVEL
                // r8-split: with the card carried to a side after a hover, the panes own it, not the deck.
                val panes = split.drag(p, splitCardCenterX(p, w))
                val reveal = !panes && near && (p.engaged || progress > .55f && p.velocity < SwitcherMotion.REVEAL_MAX_VELOCITY)
                go(deckReveal, if (reveal) 1f else 0f)
                go(labelReveal, if (reveal) 1f else 0f)
                updateDrag()
            }
            // Over Home the switcher comes in with the finger: Home blurs and dims and the cards slide in
            // from the right in proportion to the travel, and go back as the finger comes down (round 6:
            // "居然不能桌面唤起" — before, nothing at all showed until a pause had been recognised).
            Mode.HOME_DRAG -> { dragP = p; homeDragTravel = p.travel; updateHomeDrag() }
            Mode.QUICK -> {
                val hasPrevious = quickIndex + 1 < quickOrder.size
                val hasNext = quickIndex > 0
                val dx = p.dx
                // Past the last app the card resists, but only once the list says it is the last: before
                // that the finger is followed 1:1 (it used to be damped, then jump when the list came).
                val resist = tasksLoaded && (dx > 0f && !hasPrevious || dx < 0f && !hasNext)
                quickDamp += ((if (resist) .25f else 1f) - quickDamp) * .35f
                quickDx.snap(quickBase + dx * quickDamp)
                quickLift = min(p.travel, 80f) * .25f
            }
            else -> Unit
        }
        schedule()
    }

    /**
     * The dragged card and Home behind it, from the finger and the deck's reveal: run on every
     * move and every frame, so the card keeps easing toward its place in the deck while the
     * finger rests, and every effect stays proportional to where the finger is.
     */
    private fun updateDrag() {
        val p = dragP ?: return
        val reveal = deckReveal.value.coerceIn(0f, 1f)
        val focus = focusRect()
        val shift = reveal * (focus.centerX - w / 2f) * DRAG_SHIFT
        var target = SwitcherMotion.dragCard(p.dx, p.travel, w, h, shift)
        // Engaged, the card also eases toward its size in the switcher, its bottom edge still under the finger.
        val k = reveal * DRAG_SETTLE
        if (k > 0f && focus.width < target.width) {
            val nw = SwitcherMotion.lerp(target.width, focus.width, k)
            val nh = SwitcherMotion.lerp(target.height, focus.height, k)
            target = ZoomBounds(target.centerX - nw / 2f, target.bottom - nh, target.centerX + nw / 2f, target.bottom)
        }
        // r11: the panes fade in as the card shrinks and recede while the deck is in (SplitViewMotion.reveal).
        split.follow(target.width / max(w, 1f), reveal)
        target = split.adjustCard(target) // r8-split
        if (cardVisible) card.animateTo(target) else card.snap(target)
        val progress = SwitcherMotion.dragProgress(target.width / max(w, 1f), w, h, targetScale())
        val style = SwitcherMotion.dragBackdrop(progress, reveal)
        // r8-split: over the panes Home blurs and dims as fully as under the switcher.
        val sp = split.revealValue
        val blur = max(style.blur, sp); val dim = max(style.dim, SwitcherMotion.SWITCHER_DIM * sp)
        if (SystemClock.uptimeMillis() < catchBlendUntil) { go(bdZoom, style.zoom); go(bdBlur, blur); go(bdDim, dim) }
        else { bdZoom.snap(style.zoom); bdBlur.snap(blur); bdDim.snap(dim) }
        publishDrag()
    }

    /**
     * Over Home (photos 3–4): the newest card comes in from the left edge in proportion to the
     * finger, up to a cap beyond which it stays put; Home blurs and dims with it. Pulling down
     * takes it out again — the same function both ways, through one quick follow spring.
     */
    private fun updateHomeDrag() {
        homeP = SwitcherMotion.homeEntryProgress(homeDragTravel, h)
        // Round 12 (iOS): the page itself shrinks with the finger from the first pixel…
        homeScale.configure(HOME_FOLLOW_RESPONSE, 1f); go(homeScale, SwitcherMotion.homeShrink(homeDragTravel, h, targetScale()))
        // …while the cards, blur and dim come only with a pause or a slow, deliberate drag (the
        // APP_DRAG rule), and go away again if the finger speeds up: a flick never shows them.
        val p = dragP
        val revealed = p != null && SwitcherMotion.homeReveal(homeP, p.velocity, homeEngaged || p.engaged)
        deckReveal.configure(.28f, 1f); go(deckReveal, if (revealed) 1f else 0f)
        val r = deckReveal.value.coerceIn(0f, 1f)
        homeEntry.configure(HOME_FOLLOW_RESPONSE, 1f); go(homeEntry, homeP * r)
        val style = SwitcherMotion.homeBackdrop(homeP)
        bdBlur.configure(HOME_FOLLOW_RESPONSE, 1f); go(bdBlur, style.blur * r)
        bdDim.configure(HOME_FOLLOW_RESPONSE, 1f); go(bdDim, style.dim * r)
    }

    /** Round 12: what the live Home should look like this frame (scale and blur), handed to its layer. */
    private fun pushHomeZoom() {
        if (!homeZoomActive) return
        AppOpenZoomController.gestureHome(homeScale.value, if (liveHome) bdBlur.value.coerceIn(0f, 1f) else 0f)
    }

    /**
     * Round 12: a flick on Home goes to the first page. The launcher is in front, so its own Home
     * intent (no system transition) does it; the system Home action let the OS play a task
     * transition over the launcher (a frame of the overview on the emulator).
     */
    private fun homeFirstPage() {
        // Round 12.1: Home in front → served in-process; HyperOS animates both a Home intent and the
        // system Home action over the launcher (the last app's window flashed for two frames).
        if (NavigationProgressBus.requestHomeInProcess()) { Log.i("DuoNavigation", "Home flick: first page in-process"); return }
        Log.i("DuoNavigation", "Home flick: ${if (foregroundExpected) "system Home action" else "Home intent"} (launcherVisible=${NavigationProgressBus.launcherVisible})")
        if (foregroundExpected) host.performSystemHome() else host.startHome()
    }

    /** Scale of a card at rest in the open switcher on this panel. */
    private fun targetScale(): Float = SwitcherMotion.deckScale(w, h)

    fun bottomEngaged() {
        if (mode != Mode.APP_DRAG && mode != Mode.HOME_DRAG) return
        haptic()
        // r8-split: a hover with the card at a side brings the panes instead of the deck.
        if (mode == Mode.APP_DRAG && dragP?.let { split.engage(splitCardCenterX(it, w)) } == true) {
            go(deckReveal, 0f); go(labelReveal, 0f); schedule(); return
        }
        // Over Home the pause commits the release to the switcher and brings the cards, blur and dim in.
        if (mode == Mode.HOME_DRAG) { homeEngaged = true; updateHomeDrag(); schedule(); return }
        go(deckReveal, 1f)
        go(labelReveal, 1f)
        schedule()
    }

    fun bottomUp(action: NavigationAction?, p: NavigationGestureProgress) {
        if (switcherSwipe) {
            switcherSwipe = false
            if (mode == Mode.SWITCHER && (action == NavigationAction.HOME || action == NavigationAction.RECENTS)) exitToHome()
            return
        }
        when (mode) {
            Mode.PENDING -> releasePending(action)
            Mode.APP_DRAG -> {
                // r8-split: released over a pane → that half.
                val splitSide = split.commitSide(p, splitCardCenterX(p, w))
                val release = {
                    if (splitSide != null) enterSplit(splitSide, p) else when (action) {
                        NavigationAction.HOME -> flyHome(p)
                        NavigationAction.RECENTS -> openSwitcherFromDrag(p)
                        else -> returnToApp(p)
                    }
                }
                if (!cardVisible && (action != null || splitSide != null) && action != NavigationAction.RECOVERY) {
                    // A flick faster than the screenshot: wait a beat for the real pixels.
                    pendingRelease = release
                    postDelayed({ pendingRelease?.let { pendingRelease = null; showCard(); it() } }, CAPTURE_GRACE)
                } else { showCard(); release() }
            }
            Mode.HOME_DRAG -> when (SwitcherMotion.homeCommit(homeP, p.velocity, homeEngaged || p.engaged)) {
                SwitcherCommit.SWITCHER -> openSwitcherFromHome()
                // iOS: a flick up on Home goes to its first page; the page springs back with a bounce.
                SwitcherCommit.HOME -> { homeFirstPage(); retreatToHome() }
                SwitcherCommit.NONE -> retreatToHome()
            }
            Mode.QUICK -> if (tasksLoaded) quickRelease(p) else {
                // Released before the task list: wait a moment for it instead of springing back.
                pendingQuick = p
                postDelayed({ pendingQuick?.let { pending -> pendingQuick = null; if (mode == Mode.QUICK) quickRelease(pending); schedule() } },
                    QUICK_TASKS_GRACE)
            }
            else -> Unit
        }
        schedule()
    }

    /** A touch that never became a swipe. A caught transition just carries on to where it was going. */
    private fun releasePending(action: NavigationAction?) {
        when {
            caught == Catch.APP && quickActive -> {
                quickDirection = 0; appShown = appShown || !waitForApp
                mode = Mode.QUICK_SETTLE; flightStart = SystemClock.uptimeMillis(); go(quickDx, 0f)
            }
            caught == Catch.APP -> returnToApp(null)
            caught == Catch.HOME -> {
                if (action == NavigationAction.HOME) host.performSystemHome()
                settleOverHome()
            }
            else -> {
                val home = action == NavigationAction.HOME && !foregroundExpected
                finishNow()
                // Round 12: Home is in front — its own intent goes to the first page without a system transition.
                if (home) host.startHome()
            }
        }
    }

    /** A caught return home ends over Home: the stage leaves once the carried card has landed and Home is up. */
    private fun settleOverHome() {
        mode = Mode.EXIT_HOME
        host.setStageTouchable(false)
        bdBlur.configure(EXIT_RESPONSE, 1f); go(bdBlur, 0f)
        bdDim.configure(EXIT_RESPONSE, 1f); go(bdDim, 0f)
        flightStart = SystemClock.uptimeMillis()
        schedule()
    }

    fun bottomCancel() {
        if (switcherSwipe) { switcherSwipe = false; return }
        when (mode) {
            Mode.PENDING -> releasePending(null)
            Mode.APP_DRAG -> { showCard(); returnToApp(null) }
            Mode.HOME_DRAG -> retreatToHome()
            Mode.QUICK -> {
                quickDirection = 0; appShown = true; mode = Mode.QUICK_SETTLE; go(quickDx, 0f)
                landingPackage = cardEntry?.task?.packageName
            }
            else -> Unit
        }
        schedule()
    }

    // ------------------------------------------------------------------ side edges

    fun showBack(p: NavigationGestureProgress) {
        if (!p.active) return
        if (!autoHide.held) autoHide.hold(SystemClock.uptimeMillis())
        backEdge = p.edge; backY = p.y
        backProgress.snap(p.distance)
        if (p.distance >= 1f && !backArmed) { backArmed = true; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        if (p.distance < .8f) backArmed = false
        schedule()
    }

    fun releaseBack() {
        go(backProgress, 0f); backArmed = false
        if (autoHide.held && mode == Mode.IDLE) { autoHide.release(SystemClock.uptimeMillis()); updateBar() }
        schedule()
    }

    /** Back while the switcher is open: return to the app under it, or Home if there is none. */
    fun closeSwitcher() {
        if (mode != Mode.SWITCHER) return
        val index = entries.indexOfFirst { it.current }
        if (index >= 0 && !currentDismissed) openCard(index) else exitToHome()
    }

    // ------------------------------------------------------------------ app launch from a Home icon

    /** Plays iOS's icon → app zoom over a frozen Home. Returns false when it cannot (caller falls back). */
    fun launchFromIcon(iconScreenPx: ZoomBounds, task: RecentTask, icon: Bitmap, home: HomeSnapshot?,
        onStarted: (Boolean) -> Unit, widgetCornerPx: Float? = null): Boolean {
        if (mode != Mode.IDLE || reduced || width == 0 || home == null || !fits(home)) return false
        val from = iconScreenPx.scaled(1f / density)
        // Refused before arming the callback: a stale one fired at the end of a later flight and relaunched the app.
        if (from.width < 4f) return false
        launchStarted = onStarted
        mode = Mode.LAUNCH
        entries.clear(); ghosts.clear()
        val entry = Entry(task, false, 0f)
        entries += entry
        cardEntry = entry
        movingActive = true; quickActive = false
        homeSnapshot = home
        launchFromIcon = icon
        launchFromWidget = widgetCornerPx != null
        appShown = false
        stageAlpha.snap(1f); bdAlpha.snap(1f); cardAlpha.snap(1f)
        deckReveal.snap(0f); labelReveal.snap(0f)
        bdZoom.snap(1f); bdBlur.snap(0f); bdDim.snap(0f)
        flightFrom = from; flightTo = fullScreen
        flightFromRadius = widgetCornerPx?.let { it / density } ?: (from.width * IOS_ICON_CORNER_FRACTION)
        flightToRadius = host.screenCornerRadius
        flightStart = SystemClock.uptimeMillis()
        card.configure(LAUNCH_RESPONSE, LAUNCH_DAMPING)
        card.snap(from); card.animateTo(fullScreen)
        bdZoom.configure(LAUNCH_RESPONSE, 1f); bdZoom.animateTo(1f + SwitcherMotion.HOME_RETURN_ZOOM)
        bdBlur.configure(LAUNCH_RESPONSE, 1f); bdBlur.animateTo(.35f)
        bdDim.configure(LAUNCH_RESPONSE, 1f); bdDim.animateTo(.2f)
        barAlpha.snap(0f)
        landingPackage = task.packageName
        awaitShown(task.packageName)
        invalidate(); schedule()
        return true
    }

    /**
     * The launch zoom started over Home exactly as it was (icon included) so nothing blinks; this is
     * the same Home with the icon hidden, the backdrop the app really opens over.
     */
    fun setLaunchBackdrop(home: HomeSnapshot) {
        if (mode != Mode.LAUNCH || !fits(home)) return
        homeSnapshot = home
        invalidate()
    }

    private fun awaitShown(packageName: String) {
        val gen = showGen
        host.awaitForeground(packageName, System.currentTimeMillis()) { confirmed ->
            if (gen == showGen) { appShown = true; appConfirmed = confirmed; schedule() }
        }
    }

    /** The launch threw: shrink back into the icon and reveal Home again. */
    fun cancelLaunch() {
        if (mode != Mode.LAUNCH) return
        card.animateTo(flightFrom)
        bdZoom.animateTo(1f); bdBlur.animateTo(0f); bdDim.animateTo(0f)
        mode = Mode.FADE_OUT
        postDelayed({ if (mode == Mode.FADE_OUT) go(stageAlpha, 0f); schedule() }, 260L)
        schedule()
    }

    // ------------------------------------------------------------------ transitions

    private fun enterAppDrag() {
        mode = Mode.APP_DRAG
        barAlpha.snap(0f)
        anchorLive = true
        card.configure(FOLLOW_RESPONSE, 1f)
        if (caught == Catch.APP) {
            // Caught: the card goes on from where it is (out of a quick layout, from its place in it)
            // and the backdrop eases into the drag's style (updateDrag, catchBlendUntil).
            if (quickActive) { card.snap(SwitcherMotion.quickCard(0, quickDx.value, quickLift, w, h)); quickActive = false }
            movingActive = false
            cardEntry = entries.firstOrNull { it.current }
            return
        }
        card.snap(fullScreen)
        cardEntry = entries.firstOrNull { it.current }
        val style = SwitcherMotion.dragBackdrop(0f)
        bdZoom.snap(style.zoom); bdBlur.snap(style.blur); bdDim.snap(style.dim)
        showCardIfReady()
        if (!cardVisible) postDelayed({ if (mode == Mode.APP_DRAG) { showCard(); invalidate() } }, CAPTURE_GRACE)
    }

    private fun enterHomeDrag() {
        mode = Mode.HOME_DRAG
        anchorLive = false
        frozenAnchor = focusRect()
        card.snap(frozenAnchor)
        scroll.snap(0f); homeEntry.snap(0f); homeRetreat = false
        // Round 12: the cards come only with the reveal ([updateHomeDrag]); the names with the deck after the release.
        deckReveal.snap(0f); labelReveal.snap(0f)
        // Caught on its way home, Home is already settling there through its springs.
        if (caught != Catch.HOME) { bdZoom.snap(1f); bdBlur.snap(0f); bdDim.snap(0f) }
        // Round 12: over the live Home the page shrinks and blurs itself; the stage draws no copy of it.
        // (A gesture that caught a landing card keeps that card's frozen Home and the round-11 look.)
        liveHome = caught == Catch.NONE && !keepsBackdropFlag && !reduced
        if (liveHome) {
            homeScale.snap(1f); homeZoomActive = true
            AppOpenZoomController.beginGesture(1f, SystemClock.uptimeMillis(), null, homeOrigin = true)
        }
    }

    private fun enterQuick() {
        mode = Mode.QUICK
        barAlpha.snap(0f)
        quickActive = true
        // Caught in a quick switch, the cards continue from where they are.
        quickDx.configure(.36f, .92f); quickDx.snap(quickBase); if (quickBase == 0f) quickLift = 0f
        quickDamp = 1f
        cardEntry = entries.firstOrNull { it.current }; movingActive = false
        buildQuickOrder()
        showCardIfReady()
        if (!cardVisible) postDelayed({ if (mode == Mode.QUICK) { showCard(); invalidate() } }, CAPTURE_GRACE)
    }

    private fun buildQuickOrder() {
        val current = entries.firstOrNull { it.current } ?: return
        val currentId = current.task?.id
        if (currentId == null) {
            // Tasks still loading: show only the current card and leave the frozen order alone,
            // setTasks rebuilds with it (otherwise a flick back ping-pongs between two apps).
            quickOrder = listOf(current); quickIndex = 0
            return
        }
        val others = entries.filter { !it.current }
        val (order, _) = quickSession.order(others.mapNotNull { it.task?.id }, currentId, SystemClock.uptimeMillis())
        val byId = others.associateBy { it.task?.id }
        val list = order.mapNotNull { id -> if (id == currentId) current else byId[id] }
        quickOrder = if (current in list) list else listOf(current) + list
        quickIndex = quickOrder.indexOf(current)
    }

    private fun showCardIfReady() {
        if (cardVisible || mode == Mode.PENDING) return
        if (captureDone) showCard()
    }

    private fun showCard() {
        if (cardVisible) return
        cardVisible = true
        if (mode == Mode.APP_DRAG) {
            // Appear exactly over the app, then catch up with the finger through the follow spring.
            val target = ZoomBounds.centered(card.cx.target, card.cy.target, card.w.target, card.h.target)
            card.snap(fullScreen); card.animateTo(target)
        }
    }

    private fun flyHome(p: NavigationGestureProgress) {
        mode = Mode.FLY_HOME
        split.dismiss() // r11: panes fade out under the flight
        frozenAnchor = card.bounds; anchorLive = false
        val entry = entries.firstOrNull { it.current }
        cardEntry = entry
        leavingPackage = if (foregroundExpected) entry?.task?.packageName else null
        homeOnScreenAt = 0L; leavingLogged = false
        movingActive = true
        landingPackage = null
        flightFrom = card.bounds
        flightFromRadius = radiusFor(flightFrom)
        flightStart = SystemClock.uptimeMillis()
        bdFrom = BackdropStyle(bdZoom.value, bdBlur.value, bdDim.value)
        handedOff = false
        landedFrames = -1
        flightP = 0f; backdropP = 0f
        aim = FlightAim.HOLD; aimIcon = null; flightIconId = null; flightFallback = false
        liveLayout = null; liveTaskId = null; liveIcon = null
        frozenTaskId = null; frozenIcon = null; frozenPicked = false
        deckReveal.configure(.16f, 1f); go(deckReveal, 0f); go(labelReveal, 0f)
        quickSession.reset()
        flightDissolve = reduced
        if (flightDissolve) {
            // Reduce Motion (iOS): no flight; the app dissolves over Home and no icon is ever hidden.
            flightFallback = true; aim = FlightAim.FALLBACK
            flightTo = card.bounds; flightToRadius = flightFromRadius
            AppOpenZoomController.beginGesture(bdFrom.zoom, SystemClock.uptimeMillis(), null)
            card.snap(card.bounds)
            go(cardAlpha, 0f)
            host.startHome()
            return
        }
        card.configure(FLY_RESPONSE, FLY_DAMPING)
        card.cx.velocity = p.velocityX * SwitcherMotion.DRAG_X_FOLLOW
        card.cy.velocity = -p.velocity
        flightEndHeight = expectedIconHeight()
        flightToRadius = flightEndHeight * IOS_ICON_CORNER_FRACTION
        // Until Home names a target the card holds its line (no detour toward a guessed spot).
        flightTo = SwitcherMotion.holdCard(flightFrom, w, h)
        card.animateTo(flightTo)
        AppOpenZoomController.beginGesture(bdFrom.zoom, SystemClock.uptimeMillis(), null)
        // Usually decided right here: Home published where the icon is before the finger even lifted.
        updateFlightAim(flightStart)
        host.startHome()
    }

    /** The frozen Home of this app's own launch has its icon spot empty, as the live Home will. */
    private fun useLaunchFrameIfMatching() {
        val frame = HomeSnapshotStore.launchFrame ?: return
        if (aim != FlightAim.FALLBACK && frame.appId == flightIconId && fits(frame.snapshot)) homeSnapshot = frame.snapshot
    }

    private fun fallbackTarget() = ZoomBounds.centered(w / 2f, h * .42f, FALLBACK_ICON, FALLBACK_ICON)

    /** Height of a Home icon on this panel (dp), before the exact target is known. */
    private fun expectedIconHeight(): Float {
        val live = LaunchIconRegistry.published?.takeIf { it.matches(width.toFloat(), height.toFloat()) }
        val px = live?.iconHeight ?: homeSnapshot?.icons?.iconHeight
        return px?.let { it / density } ?: FALLBACK_ICON
    }

    /**
     * Re-decides the flight's target from what Home has published ([flightAim]); called every
     * frame of FLY_HOME, cheap unless the layout or the task changed. Nothing here waits for the
     * launcher's main thread: Home publishes its icon positions after each layout pass, so the
     * frame after Home relays out for this panel (fold/unfold while the app was in front) the
     * card turns to the right icon.
     */
    private fun updateFlightAim(now: Long) {
        if (flightDissolve) return
        val task = (if (carrying) carryEntry else cardEntry)?.task
        val sw = width.toFloat(); val sh = height.toFloat()
        // Round 18: a Home just recreated (process restarted) publishes an empty grid until its apps load;
        // that is not "the icon is not on this page", so the card holds instead of dissolving mid-screen.
        val contentLoaded = NavigationProgressBus.homeContentDrawnAt > 0L
        val layout = LaunchIconRegistry.published?.takeIf { contentLoaded }
        val fresh = layout != null && layout.matches(sw, sh)
        if (!fresh) { liveLayout = null; liveIcon = null }
        else if (layout !== liveLayout || task?.id != liveTaskId) {
            // Round 13: the slot this flight already aims at is followed wherever Home moves it (a page
            // the user swipes, off screen too), not picked again among the icons on screen — that dropped
            // it once 30 % had left the screen and the card stayed behind above Home (or turned to a Dock copy).
            val held = aimIcon?.takeIf { aim == FlightAim.ICON && task?.id == liveTaskId }
            liveLayout = layout; liveTaskId = task?.id
            liveIcon = held?.let { layout!!.track(it, SLOT_SIZE_TOLERANCE_DP * density) }
                ?: task?.let { layout!!.find(it.id, it.packageName, sw, sh) }
        }
        // Unchanged since before the gesture (Home sat still while hidden) or for a little while.
        val settled = fresh && (layout!!.since <= downAt || now - layout.since >= LAYOUT_SETTLE_MS)
        if (!frozenPicked || frozenTaskId != task?.id) {
            frozenPicked = true; frozenTaskId = task?.id
            frozenIcon = task?.let { t ->
                // The backdrop this flight will land on: this app's launch frame, else the panel's frozen Home.
                val frame = HomeSnapshotStore.launchFrame?.takeIf { it.appId == t.id && fits(it.snapshot) }?.snapshot ?: homeSnapshot
                frame?.icons?.takeIf { it.matches(sw, sh) }?.find(t.id, t.packageName, sw, sh)
            }
        }
        val waited = now - (if (carrying) carryStart else flightStart)
        val next = flightAim(aim, task != null, fresh, liveIcon != null, settled, frozenIcon != null,
            waited > (if (contentLoaded) HOLD_LIMIT_MS else HOME_CONTENT_FALLBACK_MS))
        val icon = when (next) {
            FlightAim.ICON -> liveIcon ?: aimIcon
            FlightAim.PROVISIONAL -> frozenIcon
            else -> null
        }
        val previous = aimIcon
        if (next == FlightAim.ICON && aim == FlightAim.ICON && icon != null && previous != null &&
            sameHomeSlot(icon, previous, SLOT_SIZE_TOLERANCE_DP * density)) {
            // The same slot, moved by Home (a page swipe): the card goes with it; nothing is re-aimed.
            if (icon.bounds != previous.bounds) followSlot(previous.bounds, icon.bounds)
            aimIcon = icon
            return
        }
        if (next == aim && icon?.appId == aimIcon?.appId && (icon == null || aimIcon == null ||
                SwitcherMotion.sameRect(icon.bounds, aimIcon!!.bounds, 2f * density))) return
        applyAim(next, icon)
    }

    /**
     * Round 13: the slot the card lands in moved under it (screen px): the card moves with it, the whole
     * way once icon-sized and in proportion while still large ([SwitcherMotion.slotFollowWeight]), and
     * keeps its own landing motion on top — it never stays behind above a page that is leaving.
     */
    private fun followSlot(from: ZoomBounds, to: ZoomBounds) {
        val spring = if (carrying) carry else card
        val weight = SwitcherMotion.slotFollowWeight(flightP)
        spring.shift((to.centerX - from.centerX) / density * weight, (to.centerY - from.centerY) / density * weight)
        flightTo = to.scaled(1f / density)
        spring.animateTo(flightTo)
        schedule()
    }

    private fun applyAim(next: FlightAim, icon: HomeIconEntry?) {
        aim = next; aimIcon = icon
        when (next) {
            FlightAim.ICON, FlightAim.PROVISIONAL -> {
                val target = icon!!.bounds.scaled(1f / density)
                flightTo = target; flightEndHeight = target.height
                flightIconId = icon.appId; flightFallback = false
                go(cardAlpha, 1f)
            }
            FlightAim.FALLBACK -> {
                flightTo = fallbackTarget(); flightEndHeight = FALLBACK_ICON
                flightIconId = null; flightFallback = true
            }
            FlightAim.HOLD -> return
        }
        // A widget the app was opened from keeps its own corner; an icon its continuous one.
        flightToRadius = widgetArt()?.let { it.cornerRadiusPx / density } ?: (flightTo.width * IOS_ICON_CORNER_FRACTION)
        (if (carrying) carry else card).animateTo(flightTo)
        // Only an icon that was found is hidden (by the id it is registered under, which may be
        // another activity or profile of the package); a fallback flight leaves every icon in place.
        AppOpenZoomController.gestureIcon(flightIconId)
        useLaunchFrameIfMatching()
        schedule()
    }

    private fun openSwitcherFromDrag(p: NavigationGestureProgress) {
        mode = Mode.SWITCHER
        split.dismiss() // r11: the deck owns the centre; the panes recede fully
        anchorLive = true
        card.configure(SWITCHER_RESPONSE, SWITCHER_DAMPING)
        card.cx.velocity = p.velocityX * SwitcherMotion.DRAG_X_FOLLOW
        card.cy.velocity = -p.velocity
        card.animateTo(focusRect())
        if (reduced) card.snap(focusRect())
        scroll.snap(0f)
        if (!p.engaged) haptic()
        enterSwitcherStyle()
    }

    /**
     * Release over Home (photo 5): up to three cards spring out of the left edge into the deck with
     * a light bounce — the newest from where the finger left it, the older ones from behind it,
     * each a little later — while Home finishes blurring. Every card stays touchable meanwhile.
     */
    private fun openSwitcherFromHome() {
        mode = Mode.SWITCHER
        anchorLive = true
        card.snap(focusRect())
        scroll.snap(0f)
        enterSwitcherStyle()
        startHomeArrival(SwitcherMotion.homeEntryCard(homeEntry.value, geometry()))
    }

    /** The newest cards spring from [entry] (the card the finger brought in) and from behind it into the deck. */
    private fun startHomeArrival(entry: ZoomBounds) {
        val g = geometry()
        entries.forEachIndexed { i, e ->
            if (i < SwitcherMotion.ARRIVE_CARDS && !reduced) {
                e.arriveFrom = SwitcherMotion.homeEntryStart(i, entry, g)
                e.arrive.configure(SwitcherMotion.arriveResponse(i), SwitcherMotion.ARRIVE_DAMPING)
                e.arrive.snap(0f); e.arrive.animateTo(1f)
            } else { e.arriveFrom = null; e.arrive.snap(1f) }
        }
        schedule()
    }

    /** Over Home, not opened: the card slides back out to the left while Home clears, then the stage goes. */
    private fun retreatToHome() {
        mode = Mode.EXIT_HOME
        homeRetreat = true
        frozenAnchor = anchorRect(); anchorLive = false
        host.setStageTouchable(false)
        homeEntry.configure(EXIT_RESPONSE, 1f); go(homeEntry, 0f)
        go(deckReveal, 0f); go(labelReveal, 0f)
        // Round 12: the page springs back to full size with a visible bounce (SwiftUI `.bouncy`).
        // Round 12.4: never with the finger's shrinking velocity — the follow spring was still heading
        // for a smaller target at release, so the page kept shrinking for a frame or two after the
        // finger had left (owner recording 15:02 f132 → f133: 0.86 → 0.835) before bouncing back.
        if (homeScale.velocity < 0f) homeScale.velocity = 0f
        homeScale.configure(SwitcherMotion.HOME_BOUNCE_RESPONSE, SwitcherMotion.HOME_BOUNCE_DAMPING); go(homeScale, 1f)
        bdBlur.configure(EXIT_RESPONSE, 1f); go(bdBlur, 0f)
        bdDim.configure(EXIT_RESPONSE, 1f); go(bdDim, 0f)
        handedOff = false
        flightStart = SystemClock.uptimeMillis()
        quickSession.reset()
        schedule()
    }

    private fun enterSwitcherStyle() {
        entries.forEachIndexed { i, e -> e.slot.snap(i.toFloat()); e.lift.snap(0f) }
        go(deckReveal, 1f); go(labelReveal, 1f)
        bdZoom.configure(SWITCHER_RESPONSE, 1f); go(bdZoom, 1f)
        bdBlur.configure(SWITCHER_RESPONSE, 1f); go(bdBlur, 1f)
        bdDim.configure(SWITCHER_RESPONSE, 1f); go(bdDim, SwitcherMotion.SWITCHER_DIM)
        host.setStageTouchable(true)
    }

    private fun returnToApp(p: NavigationGestureProgress?) {
        mode = Mode.RETURN_APP
        split.dismiss() // r11
        landingPackage = entries.firstOrNull { it.current }?.task?.packageName
        frozenAnchor = card.bounds; anchorLive = false
        cardEntry = entries.firstOrNull { it.current }
        movingActive = true
        flightFrom = card.bounds; flightTo = fullScreen
        flightFromRadius = radiusFor(flightFrom); flightToRadius = host.screenCornerRadius
        card.configure(RETURN_RESPONSE, 1f)
        if (p != null) { card.cx.velocity = p.velocityX * SwitcherMotion.DRAG_X_FOLLOW; card.cy.velocity = -p.velocity }
        card.animateTo(fullScreen)
        go(deckReveal, 0f); go(labelReveal, 0f)
        if (reduced) card.snap(fullScreen)
    }

    private fun openCard(index: Int) {
        val placed = layoutDeck().firstOrNull { it.index == index } ?: return
        endCarry()
        val entry = placed.entry
        mode = Mode.OPEN_CARD
        frozenAnchor = anchorRect(); anchorLive = false
        cardEntry = entry
        movingActive = true
        flightFrom = placed.rect; flightTo = fullScreen
        flightFromRadius = radiusFor(placed.rect); flightToRadius = host.screenCornerRadius
        card.configure(OPEN_RESPONSE, OPEN_DAMPING)
        card.snap(placed.rect); card.animateTo(fullScreen)
        if (reduced) card.snap(fullScreen)
        go(deckReveal, 0f); go(labelReveal, 0f)
        host.setStageTouchable(false)
        // Round 12: the app's window will come up under the stage: cover the live Home with its copy.
        if (liveHome) coverLive = true
        flightStart = SystemClock.uptimeMillis()
        appShown = false
        quickSession.reset()
        val task = entry.task
        landingPackage = task?.packageName
        if (entry.current && !currentDismissed) {
            appShown = true
        } else if (task?.component != null) {
            awaitShown(task.packageName)
            val gen = showGen
            host.launchTask(task) { ok -> if (!ok && gen == showGen) { appShown = true; appConfirmed = false; schedule() } }
        } else appShown = true
    }

    private fun exitToHome() {
        if (mode == Mode.EXIT_HOME || mode == Mode.FLY_HOME) return
        mode = Mode.EXIT_HOME
        frozenAnchor = anchorRect(); anchorLive = false
        host.setStageTouchable(false)
        go(deckReveal, 0f); go(labelReveal, 0f)
        exitScale.configure(.4f, 1f); go(exitScale, .9f)
        bdZoom.configure(EXIT_RESPONSE, 1f); go(bdZoom, 1f)
        bdBlur.configure(EXIT_RESPONSE, 1f); go(bdBlur, 0f)
        bdDim.configure(EXIT_RESPONSE, 1f); go(bdDim, 0f)
        // Round 12: the live Home grows back to full size as it un-blurs (no frozen copy, no hand-over).
        homeScale.configure(EXIT_RESPONSE, 1f); go(homeScale, 1f)
        coverLive = false; liveFrozen = null; liveCaptureGen++
        handedOff = false
        flightStart = SystemClock.uptimeMillis()
        quickSession.reset()
        if (foregroundExpected) {
            AppOpenZoomController.beginGesture(1f, SystemClock.uptimeMillis())
            host.startHome()
        }
    }

    private fun quickRelease(p: NavigationGestureProgress) {
        val hasPrevious = quickIndex + 1 < quickOrder.size
        val hasNext = quickIndex > 0
        val commit = SwitcherMotion.quickCommit(quickDx.value, p.velocityX, w, hasPrevious, hasNext)
        mode = Mode.QUICK_SETTLE
        quickDx.velocity = p.velocityX
        quickDirection = when (commit) {
            QuickSwitchCommit.PREVIOUS -> 1
            QuickSwitchCommit.NEXT -> -1
            QuickSwitchCommit.NONE -> 0
        }
        appShown = quickDirection == 0
        landingPackage = cardEntry?.task?.packageName
        go(quickDx, SwitcherMotion.quickTarget(quickDirection, w))
        if (quickDirection != 0) {
            val target = quickOrder.getOrNull(quickIndex + quickDirection)
            val task = target?.task
            showCard()
            if (task?.component != null) {
                val previous = landingPackage
                quickSession.committed(quickOrder.mapNotNull { it.task?.id }, SystemClock.uptimeMillis())
                landingPackage = task.packageName
                awaitShown(task.packageName)
                val gen = showGen
                host.launchTask(task) { ok ->
                    // Android refused: slide back to the app we came from.
                    if (ok || gen != showGen || mode != Mode.QUICK_SETTLE) return@launchTask
                    quickSession.reset()
                    quickDirection = 0; appShown = true; appConfirmed = false; landingPackage = previous
                    go(quickDx, 0f); schedule()
                }
            } else { quickDirection = 0; appShown = true; go(quickDx, 0f) }
        }
        flightStart = SystemClock.uptimeMillis()
    }

    // ---- r8-split: the card flies into its half while SplitViewController splits Android underneath.
    private fun enterSplit(side: SplitSide, p: NavigationGestureProgress) {
        if (!split.startCommit(side, host.screenCornerRadius) { if (!it.ok) splitFailed() }) { returnToApp(p); return }
        mode = Mode.SPLIT
        landingPackage = entries.firstOrNull { it.current }?.task?.packageName
        frozenAnchor = card.bounds; anchorLive = false
        cardEntry = entries.firstOrNull { it.current }; movingActive = true
        flightFrom = card.bounds; flightTo = split.half(side)
        flightFromRadius = radiusFor(flightFrom); flightToRadius = host.screenCornerRadius * .5f
        card.configure(OPEN_RESPONSE, SPLIT_DAMPING)
        card.cx.velocity = p.velocityX * SwitcherMotion.DRAG_X_FOLLOW; card.cy.velocity = -p.velocity
        card.animateTo(flightTo)
        if (reduced) card.snap(flightTo)
        go(deckReveal, 0f); go(labelReveal, 0f)
        haptic()
    }

    private fun splitFailed() {
        if (mode != Mode.SPLIT) return
        split.cancel()
        (context as? android.accessibilityservice.AccessibilityService)?.let { service ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                showNativeBanner(service, uxText("此 App 现在无法分屏", "This app can’t be split right now"))
            }
        }
        returnToApp(null)
    }

    /**
     * Ends whatever is on stage immediately (a new gesture, a display change, screen off).
     * [deliverBack]: give a Back the user made meanwhile to the app now in front.
     */
    fun finishNow(deliverBack: Boolean = true) {
        endCarry()
        if (mode == Mode.FLY_HOME || mode == Mode.EXIT_HOME && foregroundExpected) AppOpenZoomController.endGesture()
        if (homeZoomActive) { homeZoomActive = false; AppOpenZoomController.endGesture() }
        removeCallbacks(heartbeat)
        liveHome = false; coverLive = false; liveFrozen = null; liveCaptureRequested = false; liveCaptureGen++
        // Every transition that ends in an app records it (also those ending through FADE_OUT).
        val landedPackage = landingPackage
        val wasIdle = mode == Mode.IDLE
        mode = Mode.IDLE
        showGen++
        host.setStageTouchable(false)
        entries.clear(); ghosts.clear(); quickOrder = emptyList()
        capture = null; cardEntry = null; launchFromIcon = null; launchFromWidget = false; pendingRelease = null; homeSnapshot = null
        cardVisible = false; switcherSwipe = false; movingActive = false; quickActive = false
        landingPackage = null; flightDissolve = false; appConfirmed = false; homeEngaged = false; currentSure = false
        homeRetreat = false
        caught = Catch.NONE; keepsBackdropFlag = false; overAppFlag = false; waitForApp = false
        quickBase = 0f; pendingQuick = null; tasksLoaded = false; catchBlendUntil = 0L
        if (dragPublished) publishDrag()
        aimIcon = null; liveLayout = null; liveIcon = null; frozenIcon = null; wallpaper = null
        launchStarted?.let { launchStarted = null; it(false) }
        velocity?.recycle(); velocity = null
        split.reset() // r8-split
        releaseBitmaps()
        if (landedPackage != null) setBarStyle(landedPackage)
        // Back in an app after a transition, the switcher or a touch on the edge: the indicator shows
        // again and starts its countdown now (it never fades under a gesture still in progress).
        val now = SystemClock.uptimeMillis()
        autoHide.release(now); autoHide.wake(now)
        updateBar()
        invalidate()
        if (deferredBack) { deferredBack = false; if (deliverBack && !wasIdle) host.performBack() }
    }

    /** Nothing the stage drew may stay reachable once it is idle (screenshots are 10–20 MB each). */
    private fun releaseBitmaps() {
        shaders.clear()
        ellipsized.clear()
        bitmapPaint.shader = null
        nodeSource = null
        sharpNode.discardDisplayList()
        blurNode.discardDisplayList()
    }

    /**
     * Back while the stage is busy. The switcher closes; while an app is still opening under the
     * stage the Back is held and given to that app once it is up (never swallowed); a return to
     * Home ignores it.
     */
    fun back(): Boolean {
        when (mode) {
            Mode.IDLE -> return false
            Mode.SWITCHER -> closeSwitcher()
            Mode.FLY_HOME, Mode.EXIT_HOME, Mode.PENDING, Mode.APP_DRAG, Mode.HOME_DRAG, Mode.QUICK, Mode.SPLIT -> Unit
            Mode.FADE_OUT -> { finishNow(); return false }
            Mode.OPEN_CARD, Mode.RETURN_APP, Mode.QUICK_SETTLE, Mode.LAUNCH -> deferredBack = true
        }
        return true
    }

    // ------------------------------------------------------------------ frame loop

    private fun schedule() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun onFrame(nanos: Long) {
        frameScheduled = false
        var dt = if (lastFrame == 0L) 1f / 60f else ((nanos - lastFrame) / 1e9f).coerceIn(0f, .05f)
        lastFrame = nanos
        // Like Compose, follow the system animator scale for free motion (never for finger tracking).
        val fingerDriven = mode == Mode.PENDING || mode == Mode.APP_DRAG || mode == Mode.HOME_DRAG || mode == Mode.QUICK
        val scale = animatorDurationScale()
        if (!fingerDriven && scale > 0f) dt /= scale
        var moving = false
        if (card.step(dt)) moving = true
        if (carrying && carry.step(dt)) moving = true
        if (split.step(dt)) moving = true // r8-split
        for (spring in springs) if (spring.step(dt)) moving = true
        // Indexed loops: nothing is allocated per frame (Core.17 review P2-12h).
        for (i in entries.indices) {
            val e = entries[i]
            if (e.slot.step(dt)) moving = true
            if (e.lift.step(dt)) moving = true
            if (e.arrive.step(dt)) moving = true
        }
        var gi = ghosts.size - 1
        while (gi >= 0) {
            val g = ghosts[gi]
            if (g.lift.step(dt)) moving = true
            if (g.lift.settled) ghosts.removeAt(gi)
            gi--
        }
        advance()
        pushHomeZoom()
        if (dragPublished && mode != Mode.APP_DRAG) publishDrag()
        invalidate()
        val waiting = mode != Mode.IDLE && mode != Mode.SWITCHER
        if (moving || waiting || carrying || touchMode != 0 && mode == Mode.SWITCHER) schedule() else {
            lastFrame = 0L
            // Round 12: at rest over the live Home the switcher keeps its gesture alive (no frames needed).
            if (mode == Mode.SWITCHER && homeZoomActive) { removeCallbacks(heartbeat); postDelayed(heartbeat, HEARTBEAT_MS) }
        }
    }

    /** Mode-specific progress checks that run every frame. */
    private fun advance() {
        val now = SystemClock.uptimeMillis()
        advanceCarry(now)
        when (mode) {
            Mode.FLY_HOME -> {
                if (!flightDissolve && landedFrames < 0) updateFlightAim(now)
                flightP = if (flightDissolve) 1f - cardAlpha.value
                    else SwitcherMotion.shrinkProgress(card.h.value, flightFrom.height, flightEndHeight)
                // Home un-blurs, un-dims and settles its zoom continuously with the card, never backwards.
                backdropP = max(backdropP, flightP)
                val style = SwitcherMotion.homeReturnBackdrop(bdFrom, backdropP)
                bdZoom.snap(style.zoom); bdBlur.snap(style.blur); bdDim.snap(style.dim)
                AppOpenZoomController.gestureZoom(style.zoom)
                // The fallback (icon not on this page) stays opaque and only fades at the very end (iOS).
                if (aim == FlightAim.FALLBACK && !flightDissolve)
                    cardAlpha.snap(1f - SwitcherMotion.fallbackFade(flightP))
                val homeReady = homeReadyFor(now)
                // The live Home replaces the frozen copy only once that copy is as sharp as the live
                // one, in the zoom the launcher mirrors: an invisible swap (review N3).
                if (!handedOff && homeReady && (flightDissolve || SwitcherMotion.backdropHandoffReady(backdropP, bdBlur.value, bdDim.value))) {
                    handedOff = true; bdAlpha.configure(.16f, 1f); go(bdAlpha, 0f)
                }
                // A held card waits for its target; a provisional one for Home to confirm it (or long enough).
                val aimFinal = aim == FlightAim.ICON || aim == FlightAim.FALLBACK ||
                    aim == FlightAim.PROVISIONAL && now - flightStart > HOLD_LIMIT_MS
                val landed = handedOff && bdAlpha.settled && card.settled && aimFinal
                if (landed && flightFallback) {
                    if (cardAlpha.target != 0f) go(cardAlpha, 0f)
                    if (cardAlpha.settled && cardAlpha.value <= .01f) finishNow()
                } else if (landed) {
                    // The card now is the icon: give the icon back to Home and drop the card once
                    // Home has drawn it (the launcher's UI thread may lag behind our frames).
                    if (landedFrames < 0) { AppOpenZoomController.endGesture(); landedFrames = 0 }
                    else if (++landedFrames >= 2 && AppOpenZoomController.restoredIconDrawn || landedFrames >= ICON_RESTORE_FRAMES) finishNow()
                }
                if (mode == Mode.FLY_HOME && now - flightStart > watchdog) finishNow()
            }
            Mode.HOME_DRAG -> updateHomeDrag()
            Mode.SWITCHER -> requestLiveFrozen()
            Mode.EXIT_HOME -> {
                if (!foregroundExpected) {
                    // Caught on its way home: the stage stays until the carried card has landed and the
                    // live Home is really on screen under the frozen copy (else it would flash).
                    val homeUp = !keepsBackdropFlag || homeReadyFor(now)
                    // Round 12: the page's bounce back to full size is part of the motion (≈ 0.5 s).
                    val settled = bdBlur.settled && homeEntry.settled && homeScale.settled
                    if (!carrying && homeUp && (settled || now - flightStart > (if (homeZoomActive) 1_200L else 600L))) {
                        if (homeRetreat) Log.i("DuoNavigation", "Home retreat done after ${now - flightStart} ms (${if (settled) "settled" else "timeout"}), cards=${entries.size}")
                        mode = Mode.FADE_OUT; go(stageAlpha, 0f)
                    } else if (now - flightStart > watchdog) { endCarry(); mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
                } else {
                    AppOpenZoomController.gestureZoom(1f)
                    if (!handedOff && homeReadyFor(now) && bdBlur.value < .3f) { handedOff = true; go(stageAlpha, 0f) }
                    if (handedOff && stageAlpha.settled) finishNow()
                    else if (now - flightStart > watchdog) finishNow()
                }
            }
            Mode.APP_DRAG -> updateDrag()
            // A caught launch returns only once its app is up under the card.
            Mode.RETURN_APP -> if (card.settled && (!waitForApp || appShown)) { if (waitForApp) revealApp() else finishNow() }
                else if (now - flightStart > watchdog) { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
            Mode.OPEN_CARD -> {
                if (card.settled && appShown) {
                    if (cardEntry?.current == true && !currentDismissed) finishNow() else revealApp()
                } else if (now - flightStart > watchdog) { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
            }
            Mode.QUICK_SETTLE -> {
                if (quickDx.settled && appShown) {
                    if (quickDirection == 0 && !waitForApp) finishNow() else revealApp()
                } else if (now - flightStart > watchdog) { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
            }
            Mode.LAUNCH -> {
                if (card.settled && appShown) revealApp()
                else if (now - flightStart > watchdog) { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
            }
            Mode.FADE_OUT -> if (stageAlpha.settled && stageAlpha.value <= .01f) finishNow()
            // r8-split: the halves are in place and Android has split under the stage: let it show.
            Mode.SPLIT -> if (split.readyToReveal(card.settled)) { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
                else if (split.timedOut) splitFailed()
            Mode.PENDING -> if (now - downAt > 4_000L) finishNow() else if (barAlpha.target == 0f && !homeVisible) updateBar()
            else -> Unit
        }
    }

    /**
     * Round 12: once the deck has arrived over the live Home (blurred and shrunk by itself, at rest),
     * copy it exactly as it looks: the copy covers the app window that comes up under a card the user
     * opens, with pixels identical to the live Home it replaces (an invisible swap).
     */
    private fun requestLiveFrozen() {
        if (!liveHome || liveCaptureRequested || !bdBlur.settled || !bdDim.settled || !homeScale.settled) return
        for (i in entries.indices) if (!entries[i].arrive.settled) return
        liveCaptureRequested = true
        val gen = liveCaptureGen
        host.captureHome { home -> if (gen == liveCaptureGen && liveHome && home != null && fits(home)) { liveFrozen = home; invalidate() } }
    }

    /**
     * The app is up under a full-screen card in exactly the card's geometry: a confirmed window is
     * revealed with a cut (a cross-fade would show a stale snapshot and the live app at once); an
     * unconfirmed one (timeout) still fades, in case it is not quite there yet.
     */
    private fun revealApp() {
        if (appConfirmed) finishNow() else { mode = Mode.FADE_OUT; go(stageAlpha, 0f) }
    }

    /** Real-time limit for a stuck transition, stretched with the system animator scale. */
    private val watchdog: Long get() = (WATCHDOG * max(1f, animatorDurationScale())).toLong()

    /**
     * The live Home is on screen (not merely resumed): only then may the frozen copy over it go.
     * A fixed delay after the resume revealed the screen under the stage before Home's first frame
     * on slower resumes — a whole-screen dim of up to 24 % for ~200 ms (round 3, item 4).
     */
    private fun homeReadyFor(now: Long): Boolean {
        if (!NavigationProgressBus.homeOnScreen(now)) { homeOnScreenAt = 0L; return false }
        if (homeOnScreenAt == 0L) homeOnScreenAt = now
        // Round 12.1: Home may be on screen while HyperOS is still playing its own close transition of
        // the app being left (a black card with the app's icon shrinking over Home). Revealing the live
        // Home under that showed the transition for two frames (owner recording 2026-10-21 15:02,
        // f31–f32 and f63–f64: "上滑退出 App 的时候屏幕有时候会闪一下"). The leaving app keeps its window
        // until that transition ends, so the frozen copy stays until the window is gone (bounded).
        val pkg = leavingPackage ?: return true
        val waited = now - homeOnScreenAt
        // Round 12.4: the window list alone is not proof the transition is over (HyperOS animates a
        // snapshot of the app after its window may already be gone), so the copy also stays for the
        // transition's measured length (owner recording 2026-10-21 15:02: ≈ 0.35 s after Home's first frame).
        val present = waited < LEAVING_HOLD_MIN_MS || waited < LEAVING_WINDOW_LIMIT_MS && host.appWindowPresent(pkg)
        if (!present && !leavingLogged) {
            leavingLogged = true
            Log.i("DuoNavigation", "hand-off to live Home: $pkg window ${if (waited >= LEAVING_WINDOW_LIMIT_MS) "still up at the limit" else "gone"} after ${waited} ms")
        }
        return !present
    }

    /** Round 12.1: the app a FLY_HOME leaves, until its window has left the screen (see [homeReadyFor]). */
    private var leavingPackage: String? = null
    private var homeOnScreenAt = 0L
    private var leavingLogged = false

    private fun go(spring: NavSpring, target: Float) {
        // Reduce Motion (iOS): zooms and slides jump, but fades and blur still cross-dissolve.
        // With system animations off everything is instant.
        val dissolve = spring === stageAlpha || spring === bdAlpha || spring === cardAlpha || spring === barAlpha ||
            spring === bdBlur || spring === bdDim || spring === labelReveal
        if (reduced && (!dissolve || !ValueAnimator.areAnimatorsEnabled())) spring.snap(target) else spring.animateTo(target)
    }

    // ------------------------------------------------------------------ geometry

    /** Over an app the card just left rests on the right; over Home the newest card is centred (photo 5). */
    private fun geometry(): DeckGeometry = SwitcherMotion.deck(w, h, host.screenCornerRadius,
        if (foregroundExpected) DeckOrigin.APP else DeckOrigin.HOME)

    /** Where the card the finger brings up comes to rest: the deck's focus. */
    private fun focusRect(): ZoomBounds {
        val g = geometry()
        return ZoomBounds(g.focusLeft, g.centerY - g.cardHeight / 2f, g.focusLeft + g.cardWidth, g.centerY + g.cardHeight / 2f)
    }

    private fun anchorRect(): ZoomBounds = if (anchorLive) card.bounds else frozenAnchor

    private fun radiusFor(rect: ZoomBounds): Float = host.screenCornerRadius * (rect.width / max(w, 1f))

    /** Deck positions mapped from deck space onto the current anchor (the card the finger holds). */
    private fun layoutDeck(): List<Placed> {
        if (entries.isEmpty() || width == 0) return emptyList()
        val g = geometry()
        // Over Home, while the finger drags (or the card goes back out), only the newest card is
        // on stage, coming in from the left edge (photos 3–4).
        // Round 12.5 (owner recording 22:48, repro 22:58): [homeRetreat] stays true through the FADE_OUT
        // that ends a flick on Home — gated on EXIT_HOME alone, that fade laid the task list out as the
        // open switcher (the deck arrived with [setTasks] during the gesture) and drew it at full alpha
        // under the stage's 0.14 s fade: the switcher appeared for ≈ 0.1 s about 0.3 s after the finger left.
        if (mode == Mode.HOME_DRAG || homeRetreat) {
            val rect = SwitcherMotion.homeEntryCard(homeEntry.value, g)
            // A card that has gone back out of the left edge is not on stage at all (no shadow at x = 0).
            if (rect.right <= .5f) return emptyList()
            return listOf(Placed(entries[0], 0, rect, rect.width, 1f, 1f))
        }
        // Over an app the older cards slide in from the left as the deck engages (iOS), rather than fading in place.
        val slide = if (foregroundExpected) (1f - deckReveal.value.coerceIn(0f, 1f)) * g.cardWidth * REVEAL_SLIDE else 0f
        val cards = SwitcherMotion.deckCards(entries.size, scroll.value, g,
            slots = { entries[it].slot.value }, lift = { entries[it].lift.value })
        val a = anchorRect()
        val s = a.width / g.cardWidth
        val ex = exitScale.value
        return cards.map { c ->
            val b = c.bounds
            var rect = ZoomBounds(
                a.left + (b.left - g.focusLeft) * s,
                a.centerY + (b.top - g.centerY) * s,
                a.left + (b.right - g.focusLeft) * s,
                a.centerY + (b.bottom - g.centerY) * s,
            )
            if (slide > 0f && !entries[c.index].current) rect = rect.offset(-slide * s, 0f)
            // Springing out of the left edge (from Home): from where it waited to its deck place, overshoot included.
            val e = entries[c.index]
            val from = e.arriveFrom
            if (from != null && e.arrive.value != 1f) rect = SwitcherMotion.lerp(from, rect, e.arrive.value)
            if (ex != 1f) rect = ZoomBounds.centered(rect.centerX, rect.centerY, rect.width * ex, rect.height * ex)
            Placed(e, c.index, rect, c.visibleWidth * s, c.iconAlpha, c.labelAlpha, c.alpha)
        }
    }

    /** A frozen Home of exactly this panel's size (cover and inner portrait differ by < 4 % in aspect). */
    private fun fits(snapshot: HomeSnapshot): Boolean =
        width > 0 && homeLayoutMatches(snapshot.windowWidth.toFloat(), snapshot.windowHeight.toFloat(), width.toFloat(), height.toFloat())

    /** Home's own wallpaper as the launcher has it in memory (never decoded here, on the stage thread). */
    private fun currentWallpaper(): Bitmap? {
        val photo = LauncherBackgroundCache.bitmap?.takeUnless { it.isRecycled }
        val live = if (photo == null) DuneLive.restBitmap() else null
        // The live dunes are rendered centred at the window's size; only the bundled still is right-anchored.
        wallpaperRightAnchored = photo == null && live == null
        return photo ?: live ?: cachedBundledWallpaper()
    }

    /**
     * The pixels a card may show. The current card shows the live capture (it *is* the screen);
     * without one, its app's stored snapshot only when we are sure which app (and profile) is in
     * front — a guess could put one app's pixels on another app's card (review R4).
     */
    private fun snapshotFor(entry: Entry?): Bitmap? {
        if (entry == null) return null
        if (entry.current) return capture ?: if (captureSecure || !currentSure) null else stored(entry.task)
        return stored(entry.task)
    }

    private fun currentStored(): Bitmap? = entries.firstOrNull { it.current }?.takeIf { currentSure }?.let { stored(it.task) }

    /**
     * The app's (and profile's) last snapshot, if it was taken on a panel of this shape — checked
     * at draw time to have been filed under exactly this app and profile.
     */
    private fun stored(task: RecentTask?): Bitmap? {
        task ?: return null
        val snap = AppSnapshotStore.get(task, landscape = width >= height) ?: return null
        if (snap.owner != snapshotKey(task.packageName, task.user.hashCode())) return null
        // A frame of the other panel's shape is still the app's real page: drawCard shows it whole.
        return snap.bitmap.takeIf { !it.isRecycled }
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        if (mode != Mode.IDLE) drawStage(canvas)
        drawBar(canvas)
        drawBack(canvas)
        // The launch card is on screen from this frame: only now may Home hide the real icon.
        if (mode == Mode.LAUNCH) launchStarted?.let { started -> launchStarted = null; post { started(true) } }
    }

    private fun drawStage(canvas: Canvas) {
        // A caught transition stays drawn while the new touch decides what it is.
        if (mode == Mode.PENDING && caught != Catch.APP && !carrying && !keepsBackdropFlag) return
        if ((mode == Mode.APP_DRAG || mode == Mode.QUICK) && !cardVisible) return
        if (mode == Mode.HOME_DRAG && homeEntry.value <= .002f && bdBlur.value <= .002f && bdDim.value <= .002f && !carrying && !keepsBackdropFlag) return
        val alpha = stageAlpha.value.coerceIn(0f, 1f)
        if (alpha <= .003f) return
        val layer = if (alpha < .999f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt()) else -1
        if (quickActive) { if (cardVisible) drawQuick(canvas) } else {
            drawBackdrop(canvas)
            split.draw(canvas, host.screenCornerRadius) { drawBackdrop(it) } // r8-split
            drawDeck(canvas)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
    }

    private fun drawBackdrop(canvas: Canvas) {
        if (liveHome) {
            // Round 12: the live Home shrinks and blurs itself under the stage; only the dim is drawn
            // here — until a card opens, when the copy of that very look covers the window coming up.
            val a = bdAlpha.value.coerceIn(0f, 1f)
            val pre = liveFrozen
            if (coverLive && pre != null && canvas.isHardwareAccelerated) {
                recordBackdrop(pre.bitmap, pre)
                sharpNode.setScaleX(1f); sharpNode.setScaleY(1f)
                sharpNode.setAlpha(a); canvas.drawRenderNode(sharpNode)
            } else if (coverLive && pre == null) {
                // No copy came back: the stage's own blur of the sharp copy from the gesture's start.
                drawFrozenBackdrop(canvas); return
            }
            fill.color = Color.argb((255f * bdDim.value.coerceIn(0f, 1f) * a).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            return
        }
        drawFrozenBackdrop(canvas)
    }

    private fun drawFrozenBackdrop(canvas: Canvas) {
        // Home of this panel; else its wallpaper (Home before its icons); a blurred app only as a last resort.
        val wall = wallpaper?.takeUnless { it.isRecycled }
        val source = homeSnapshot?.bitmap ?: wall ?: capture?.takeIf { !captureSecure } ?: currentStored()?.takeIf { !captureSecure }
        val homeSource = homeSnapshot != null || source != null && source === wall
        // Over the live Home with no copy of it yet, the bare wallpaper would hide the icons: it comes in
        // only with the blur (the icons it hides are blurred away by then).
        val overLiveHome = !foregroundExpected && homeSnapshot == null
        val a = bdAlpha.value.coerceIn(0f, 1f) * (if (overLiveHome) bdBlur.value.coerceIn(0f, 1f) else 1f)
        if (a <= .003f) return

        if (source != null && canvas.isHardwareAccelerated) {
            recordBackdrop(source)
            val zoom = bdZoom.value
            sharpNode.setScaleX(zoom); sharpNode.setScaleY(zoom)
            blurNode.setScaleX(zoom); blurNode.setScaleY(zoom)
            sharpNode.setPivotX(width / 2f); sharpNode.setPivotY(height / 2f)
            blurNode.setPivotX(width / 2f); blurNode.setPivotY(height / 2f)
            val blur = if (homeSource) bdBlur.value.coerceIn(0f, 1f) else 1f
            if (blur < .995f) { sharpNode.setAlpha(a); canvas.drawRenderNode(sharpNode) }
            if (blur > .005f) { blurNode.setAlpha(a * (if (blur < .995f) blur else 1f)); canvas.drawRenderNode(blurNode) }
            val dim = if (homeSource) bdDim.value else max(bdDim.value, .45f)
            fill.color = Color.argb((255f * dim.coerceIn(0f, 1f) * a).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        } else {
            fill.color = Color.argb((255f * a).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    private fun recordBackdrop(source: Bitmap, snapshot: HomeSnapshot? = homeSnapshot) {
        val size = width.toLong() shl 32 or height.toLong()
        // Android drops the display list of a node nothing has drawn for a while: re-record then.
        if (nodeSource === source && nodeSize == size && sharpNode.hasDisplayList() && blurNode.hasDisplayList()) return
        nodeSource = source; nodeSize = size
        val dst = Rect(0, 0, width, height)
        var src: Rect? = null
        // Re-recorded only when the source changes (a few times per transition), not per frame.
        snapshot?.takeIf { it.bitmap === source }?.let {
            val ox = LaunchIconRegistry.windowOffsetX.toInt(); val oy = LaunchIconRegistry.windowOffsetY.toInt()
            dst.set(ox, oy, ox + it.windowWidth, oy + it.windowHeight)
        }
        if (source === wallpaper) {
            // The same crop Home's DuneWallpaper draws, so the live Home that follows lines up.
            val crop = wallpaperSourceCrop(source.width, source.height, width, height, wallpaperRightAnchored)
            src = Rect(crop.x, crop.y, crop.x + crop.width, crop.y + crop.height)
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (node in backdropNodes) {
            node.setPosition(0, 0, width, height)
            val c = node.beginRecording(width, height)
            c.drawColor(Color.BLACK)
            c.drawBitmap(source, src, dst, paint)
            node.endRecording()
        }
        val radius = BACKDROP_BLUR * density
        blurNode.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    }

    private fun drawDeck(canvas: Canvas) {
        val moving = if (movingActive) cardEntry else null
        val placed = if (launchFromIcon != null) emptyList() else layoutDeck()
        val reveal = deckReveal.value.coerceIn(0f, 1f)
        val labels = labelReveal.value.coerceIn(0f, 1f)
        val dragging = mode == Mode.APP_DRAG
        // Oldest first so each newer card lies on top of the one before it. A card being swiped
        // away keeps its place: above the older cards, still under the newer one (iOS).
        var ghostsDrawn = 0
        val ghostOrder = if (ghosts.size > 1) ghosts.sortedByDescending { it.index } else ghosts
        fun drawGhostsAbove(index: Int) {
            while (ghostsDrawn < ghostOrder.size && ghostOrder[ghostsDrawn].index > index) {
                val g = ghostOrder[ghostsDrawn++]
                drawCard(canvas, g.entry, g.rect.offset(0f, -g.lift.value), g.radius, 1f, 0f, shadow = true)
            }
        }
        // A deck fading in or out fades as one sheet: each card stays opaque inside the group, so
        // overlapping cards never show through one another (two apps blended into one card, R4).
        var group = if (reveal > .003f && reveal < .997f && placed.size > 1)
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (reveal * 255).toInt()) else -1
        for (p in placed.asReversed()) {
            drawGhostsAbove(p.index)
            if (p.entry === moving) continue
            val anchorCard = p.entry.current && anchorLive && (dragging || mode == Mode.SWITCHER)
            // The card under the finger (always the newest, drawn last) is not part of the fade.
            if (anchorCard && group >= 0) { canvas.restoreToCount(group); group = -1 }
            if (anchorCard && dragging) continue
            // Over Home the entering card slides, it never fades (photos 3–4); round 12 gates it by position.
            val a = (if (anchorCard || group >= 0 || mode == Mode.HOME_DRAG || homeRetreat) 1f else reveal) * p.alpha
            if (a <= .003f || p.rect.right < 0f || p.rect.left > w) continue
            drawCard(canvas, p.entry, p.rect, radiusFor(p.rect), a, 0f, shadow = true, visibleDp = p.visible)
            if (labels > .003f) drawLabel(canvas, p, labels * a)
        }
        if (group >= 0) canvas.restoreToCount(group)
        if (dragging) entries.firstOrNull { it.current }?.let {
            val b = card.bounds
            drawCard(canvas, it, b, radiusFor(b), 1f, 0f, shadow = true)
            // Engaged, the card in hand carries its icon and name like every other card.
            if (labels > .003f) drawLabel(canvas, Placed(it, 0, b, b.width, 1f, 1f), labels)
        }
        drawGhostsAbove(-1)
        if (moving != null) drawMovingCard(canvas, moving)
        if (carrying) drawCarry(canvas)
        if (mode == Mode.SWITCHER && entries.isEmpty() && labels > .01f) {
            emptyPaint.alpha = (200 * labels).toInt()
            canvas.drawText(emptyText, width / 2f, height / 2f, emptyPaint)
        }
    }

    private fun drawMovingCard(canvas: Canvas, entry: Entry) {
        val b = card.bounds
        val p = if (mode == Mode.FLY_HOME) flightP else SwitcherMotion.flightProgress(b, flightFrom, flightTo)
        val radius = flightFromRadius + (flightToRadius - flightFromRadius) * p
        val iconMix = when {
            launchFromIcon != null -> SwitcherMotion.launchIconMix(p)
            mode == Mode.FLY_HOME && !flightFallback -> SwitcherMotion.iconMix(p)
            else -> 0f
        }
        drawCard(canvas, entry, b, radius, cardAlpha.value.coerceIn(0f, 1f), iconMix, shadow = mode == Mode.OPEN_CARD)
    }

    private fun drawQuick(canvas: Canvas) {
        fill.color = Color.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val dx = quickDx.value
        for (offset in -1..1) {
            val entry = quickOrder.getOrNull(quickIndex + offset) ?: if (offset == 0) cardEntry else null
            entry ?: continue
            val rect = SwitcherMotion.quickCard(offset, dx, quickLift, w, h)
            if (rect.right < 0f || rect.left > w) continue
            drawCard(canvas, entry, rect, radiusFor(rect).coerceAtLeast(if (abs(dx) > 1f) 12f else 0f), 1f, 0f, shadow = false)
        }
    }

    /** [visibleDp]: width of the card not covered by the newer card on top (deck); its placeholder icon centres there. */
    private fun drawCard(canvas: Canvas, entry: Entry, b: ZoomBounds, radiusDp: Float, alpha: Float, iconMix: Float, shadow: Boolean,
        visibleDp: Float = b.width, art: Bitmap? = null) {
        placeholderVisible = visibleDp.coerceIn(0f, b.width) * density
        if (alpha <= .003f || b.width < 1f || b.height < 1f) return
        rectF.set(b.left * density, b.top * density, b.right * density, b.bottom * density)
        val r = radiusDp * density
        if (shadow) {
            // A soft shadow thrown mostly to the left, onto the older card underneath (photo 5).
            shadowPaint.setShadowLayer(20f * density, -4f * density, 3f * density, Color.argb((80 * alpha).toInt(), 0, 0, 0))
            shadowPaint.alpha = (255 * alpha).toInt()
            canvas.drawRoundRect(rectF, r, r, shadowPaint)
        }
        // The window is opaque under the dissolving icon (iOS): the icon is the card only while it
        // is (nearly) solid, so no Home ever shows through and no "platter" rims the icon.
        val content = alpha * SwitcherMotion.windowUnderIcon(iconMix)
        val task = entry.task
        if (content > .003f) {
            val snapshot = art ?: snapshotFor(entry)
            // r8-split: flying into a half, the app is cropped to it (it relays out to that shape).
            if (snapshot != null && !sameShape(snapshot, rectF) && !split.cropsCard) {
                // A window of the other panel's shape: shown whole on its launch colour as
                // the card zooms to this panel's shape, never cropped to a sliver.
                drawPlaceholder(canvas, task, r, content, withIcon = false)
                val sw = snapshot.width.toFloat(); val sh = snapshot.height.toFloat()
                val k = min(rectF.width() / sw, rectF.height() / sh)
                fitRect.set(rectF.centerX() - sw * k / 2f, rectF.bottom - sh * k, rectF.centerX() + sw * k / 2f, rectF.bottom)
                drawBitmap(canvas, snapshot, fitRect, r, content, crop = true)
            } else if (snapshot != null) drawBitmap(canvas, snapshot, rectF, r, content, crop = true,
                // r8-split: the app's leading edge stays put as its card narrows into a half (it relays out from there).
                leading = split.cropsCard)
            // An icon launch dissolves its icon into a plain launch screen (iOS); switcher cards keep theirs.
            else drawPlaceholder(canvas, task, r, content, withIcon = launchFromIcon == null)
        }
        if (iconMix > .003f) {
            val icon = launchFromIcon?.takeIf { mode == Mode.LAUNCH || mode == Mode.FADE_OUT } ?: task?.icon
            // Out of or into a widget: the card is the widget's own picture, edge to edge.
            val widget = if (launchFromWidget && (mode == Mode.LAUNCH || mode == Mode.FADE_OUT)) launchFromIcon
                else if (mode == Mode.FLY_HOME || drawingCarry) widgetArt()?.art else null
            if (widget != null) drawBitmap(canvas, widget, rectF, r, alpha * iconMix, crop = true)
            else if (icon != null) {
                val side = min(rectF.width(), rectF.height())
                iconRect.set(rectF.centerX() - side / 2f, rectF.centerY() - side / 2f, rectF.centerX() + side / 2f, rectF.centerY() + side / 2f)
                drawBitmap(canvas, icon, iconRect, max(r, side * IOS_ICON_CORNER_FRACTION).coerceAtMost(side / 2f), alpha * iconMix, crop = false)
            } else {
                fill.color = Color.argb((255 * alpha * iconMix).toInt(), 255, 255, 255)
                canvas.drawRoundRect(rectF, r, r, fill)
            }
        }
    }

    /** The widget this flight lands in (the app was opened from it), with its pixels and corner. */
    private fun widgetArt(): WidgetLaunchTiles.Launch? =
        aimIcon?.takeIf { it.kind == HomeIconKind.WIDGET }?.let { WidgetLaunchTiles.artFor(it.appId) }

    private fun sameShape(bitmap: Bitmap, rect: RectF): Boolean {
        val a = bitmap.width / max(bitmap.height, 1).toFloat()
        val b = rect.width() / max(rect.height(), 1f)
        return abs(a - b) / b <= .04f
    }

    /**
     * A card with no snapshot yet: like an iOS launch screen, the app's own launch colour (or its
     * icon's dominant colour) edge to edge with the icon in the middle — never a grey slab.
     */
    private fun drawPlaceholder(canvas: Canvas, task: RecentTask?, r: Float, alpha: Float, withIcon: Boolean) {
        // r11: while the task list is still loading the app in front is known by its package: its
        // launch colour, never a grey slab (its icon follows with the list a frame or two later).
        fill.color = task?.placeholderColor ?: SplashColors.forPackage(barPackage) ?: Color.rgb(242, 242, 247)
        fill.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(rectF, r, r, fill)
        val icon = task?.icon?.takeIf { withIcon } ?: return
        // iOS launch-screen look on a deck card (a modest icon, never a giant logo); it grows to
        // Android's splash-icon size only as the card fills the screen (the splash follows it).
        val fullness = rectF.width() / max(width, 1)
        val splash = AppOpenMotion.launchCardIconSide(rectF.width(), rectF.height(), min(width, height).toFloat(), density, fullness)
        val side = SwitcherMotion.placeholderIconSide(min(rectF.width(), rectF.height()), splash,
            SwitcherMotion.PLACEHOLDER_ICON_MAX * density, fullness)
        // A card mostly covered by the one in front shows its icon in the part you can see (the
        // centre of the card would be hidden), so a card without a snapshot still reads at once.
        val cx = if (placeholderVisible < rectF.width() - 1f)
            min(rectF.centerX(), rectF.left + max(placeholderVisible / 2f, side / 2f + 8f * density)) else rectF.centerX()
        iconRect.set(cx - side / 2f, rectF.centerY() - side / 2f, cx + side / 2f, rectF.centerY() + side / 2f)
        val ir = side * IOS_ICON_CORNER_FRACTION
        shadowPaint.setShadowLayer(side * .12f, 0f, side * .04f, Color.argb((46 * alpha).toInt(), 0, 0, 0))
        shadowPaint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(iconRect, ir, ir, shadowPaint)
        drawBitmap(canvas, icon, iconRect, ir, alpha, crop = false)
    }

    private fun drawBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF, radius: Float, alpha: Float, crop: Boolean,
        leading: Boolean = false) {
        if (bitmap.isRecycled) return
        val shader = shaders.get(bitmap)
        val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
        val scale = if (crop) max(rect.width() / bw, rect.height() / bh) else min(rect.width() / bw, rect.height() / bh)
        matrix.setScale(scale, scale)
        matrix.postTranslate(if (leading) rect.left else rect.centerX() - bw * scale / 2f, rect.centerY() - bh * scale / 2f)
        shader.setLocalMatrix(matrix)
        bitmapPaint.shader = shader
        bitmapPaint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawRoundRect(rect, radius, radius, bitmapPaint)
        bitmapPaint.shader = null
    }

    private fun drawLabel(canvas: Canvas, p: Placed, alpha: Float) {
        val task = p.entry.task ?: return
        val icon = task.icon
        val iconPx = SwitcherMotion.LABEL_ICON * density
        // Photo 5: the icon sits 0.06 of the card in from its left edge, 7 pt above it.
        val left = (p.rect.left + p.rect.width * SwitcherMotion.LABEL_INSET) * density
        val bottom = p.rect.top * density - SwitcherMotion.LABEL_GAP * density
        val iconAlpha = alpha * p.iconAlpha
        if (icon != null && iconAlpha > .003f) {
            labelRect.set(left, bottom - iconPx, left + iconPx, bottom)
            drawBitmap(canvas, icon, labelRect, iconPx * IOS_ICON_CORNER_FRACTION, iconAlpha, crop = false)
        }
        val textAlpha = alpha * p.labelAlpha
        if (textAlpha <= .003f) return
        val textLeft = left + iconPx + 8f * density
        val room = (p.visible - p.rect.width * SwitcherMotion.LABEL_INSET) * density - iconPx - 18f * density
        if (room <= 8f * density) return
        val text = ellipsized[task.label]?.takeIf { it.first == room }?.second
            ?: TextUtils.ellipsize(task.label, labelPaint, room, TextUtils.TruncateAt.END).also { ellipsized[task.label] = room to it }
        labelPaint.alpha = (255 * textAlpha).toInt()
        val baseline = bottom - iconPx / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(text, 0, text.length, textLeft, baseline, labelPaint)
    }

    /** iPhone home indicator: a 5 dp capsule, light or dark to contrast with the app behind it. */
    private fun drawBar(canvas: Canvas) {
        val a = barAlpha.value.coerceIn(0f, 1f)
        if (a <= .003f || width == 0) return
        val halfW = SwitcherMotion.homeBarWidth(w, h) / 2f * density
        val halfH = SwitcherMotion.HOME_BAR_HEIGHT / 2f * density
        val cx = width / 2f
        val cy = height - (SwitcherMotion.HOME_BAR_BOTTOM + SwitcherMotion.HOME_BAR_HEIGHT / 2f) * density
        val halo = barHalo.value.coerceIn(0f, 1f)
        val dark = barDark.value.coerceIn(0f, 1f)
        // Unknown background: a neutral grey reads on light and dark content alike.
        val level = (255f * (1f - dark) * (1f - .45f * halo)).toInt()
        fill.color = Color.argb((255f * a * (.9f - .05f * dark)).toInt(), level, level, level)
        rectF.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(rectF, halfH, halfH, fill)
    }

    /**
     * Android apps need Back, iOS has no visual for it: just a slim translucent sliver that follows
     * the finger at the edge and firms up when releasing would go back.
     */
    private fun drawBack(canvas: Canvas) {
        val edge = backEdge ?: return
        val p = backProgress.value.coerceIn(0f, 1f)
        if (p <= .01f) return
        val armed = p >= .999f
        // iOS shows no Back affordance at all; Android users still need a hint that Back will fire,
        // so only a hairline at ≤ 30 % in the home indicator's tint, firming slightly when armed.
        val thickness = (1.5f + 1f * p) * density
        val length = (36f + 28f * p) * density
        val inset = (2f + 4f * p) * density
        val x = if (edge == NavigationEdge.LEFT) inset else width - inset - thickness
        val y = (backY * density).coerceIn(length, height - length)
        rectF.set(x, y - length / 2f, x + thickness, y + length / 2f)
        val level = (255f * (1f - .88f * barDark.value.coerceIn(0f, 1f))).toInt()
        fill.color = Color.argb(((if (armed) .3f else .18f) * 255f * p).toInt(), level, level, level)
        canvas.drawRoundRect(rectF, thickness / 2f, thickness / 2f, fill)
    }

    // ------------------------------------------------------------------ switcher touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != Mode.SWITCHER) return false
        val x = event.x / density
        val y = event.y / density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity?.recycle(); velocity = VelocityTracker.obtain()
                velocity?.addMovement(event)
                downX = x; downY = y
                touchMode = 0
                // Newest first: in the deck a newer card lies over the older one.
                touchIndex = layoutDeck().firstOrNull { it.rect.left <= x && x <= it.rect.right && it.rect.top <= y && y <= it.rect.bottom }?.index ?: -1
                // A touch catches a coasting deck where it is (iOS).
                scrollStart = scroll.value
                scroll.velocity = 0f; scroll.target = scroll.value
                touchLeft0 = entries.getOrNull(touchIndex)?.let { SwitcherMotion.deckLeft(it.slot.value - scrollStart, geometry()) } ?: 0f
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = x - downX
                val dy = y - downY
                if (touchMode == 0 && (abs(dx) > slop / density || abs(dy) > slop / density)) {
                    touchMode = when {
                        abs(dy) > abs(dx) && dy < 0f -> if (touchIndex >= 0) 2 else 4
                        abs(dx) >= abs(dy) -> 1
                        else -> 3
                    }
                }
                when (touchMode) {
                    1 -> scrollTo(dx)
                    // The card follows the finger up; pulled down it only gives a little (rubber band).
                    2 -> entries.getOrNull(touchIndex)?.lift?.snap(if (dy < 0f) -dy else -dy * .15f)
                }
                schedule()
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vx = (velocity?.xVelocity ?: 0f) / density
                val vy = (velocity?.yVelocity ?: 0f) / density
                when (touchMode) {
                    0 -> if (touchIndex >= 0) openCard(touchIndex) else exitToHome()
                    1 -> flingScroll(vx)
                    // A swipe up on empty space goes Home, like a tap there (iOS).
                    4 -> if (y - downY < -SwitcherMotion.MIN_COMMIT_DISTANCE * 2f || -vy > SwitcherMotion.DISMISS_VELOCITY) exitToHome()
                    2 -> {
                        val entry = entries.getOrNull(touchIndex)
                        if (entry != null) {
                            if (SwitcherMotion.dismissCommit(entry.lift.value, -vy, h)) dismiss(touchIndex, -vy)
                            else { entry.lift.configure(.32f, .86f); entry.lift.animateTo(0f, -vy) }
                        }
                    }
                }
                touchMode = 0; touchIndex = -1
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                entries.forEach { it.lift.animateTo(0f) }
                flingScroll(0f)
                touchMode = 0; touchIndex = -1
                velocity?.recycle(); velocity = null
            }
        }
        schedule()
        return true
    }

    /** Finger-tracking scroll for a horizontal drag of [dx] dp since the touch landed. */
    private fun scrollTo(dx: Float) {
        val g = geometry()
        val entry = entries.getOrNull(touchIndex)
        // The card under the finger stays under it; the rest of the deck moves at its own pace.
        val raw = if (entry != null) SwitcherMotion.scrollForLeft(entry.slot.value, touchLeft0 + dx, g, entries.size.toFloat())
            else scrollStart + dx / (g.cardWidth * SwitcherMotion.SCROLL_UNIT)
        scroll.snap(SwitcherMotion.clampScroll(raw, entries.size))
    }

    /** Release: hand the finger's velocity to the spring, which coasts and settles on a card / column. */
    private fun flingScroll(vx: Float) {
        val g = geometry()
        val entry = entries.getOrNull(touchIndex)
        val slope = entry?.let { SwitcherMotion.deckSlope(it.slot.value - scroll.value, g) } ?: (g.cardWidth * SwitcherMotion.SCROLL_UNIT)
        val v = (vx / max(slope, g.cardWidth * .3f)).coerceIn(-SwitcherMotion.MAX_FLING_UNITS, SwitcherMotion.MAX_FLING_UNITS)
        scroll.configure(SCROLL_RESPONSE, 1f)
        scroll.animateTo(SwitcherMotion.snapScroll(scroll.value, v, entries.size), v)
    }

    private fun dismiss(index: Int, velocityUp: Float) {
        val placed = layoutDeck().firstOrNull { it.index == index } ?: return
        val entry = placed.entry
        val lift = NavSpring(0f, .3f, 1f).thresholds(1f)
        lift.animateTo(placed.rect.bottom + 40f, velocityUp.coerceAtLeast(600f))
        ghosts += Ghost(entry, index, placed.rect, radiusFor(placed.rect), lift)
        entries.removeAt(index)
        entries.forEachIndexed { i, e -> e.slot.configure(.36f, .9f); go(e.slot, i.toFloat()) }
        if (scroll.value > entries.size - 1) go(scroll, max(0, entries.size - 1).toFloat())
        if (entry.current) currentDismissed = true
        entry.task?.let { host.dismissTask(it) }
        if (entries.isEmpty()) postDelayed({ if (mode == Mode.SWITCHER && entries.isEmpty()) exitToHome() }, 220L)
    }

    private fun haptic() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            else HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        frameScheduled = false
        split.release() // r8-split
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FOLLOW_RESPONSE = .07f
        /** Over Home the deck and the blur follow the finger through this quick spring (smooths 60 Hz touch input). */
        const val HOME_FOLLOW_RESPONSE = .1f
        /** Home indicator: quick to appear or make way for a gesture… */
        const val BAR_RESPONSE = .2f
        /** …slow to fade away by itself (≈ 0.6 s, no bounce), like iOS's auto-hidden indicator. */
        const val BAR_AUTO_HIDE_RESPONSE = .6f
        /** A finger resting this long on the bottom edge brings a faded indicator back (a swipe never sees it). */
        const val HELD_TOUCH_MS = 150L
        const val FLY_RESPONSE = .44f
        const val FLY_DAMPING = .86f
        const val SWITCHER_RESPONSE = .42f
        const val SWITCHER_DAMPING = .88f
        const val RETURN_RESPONSE = .34f
        const val OPEN_RESPONSE = .4f
        const val OPEN_DAMPING = .92f
        /** r8-split: the card lands in its half with a hint of bounce. */
        const val SPLIT_DAMPING = .84f
        const val LAUNCH_RESPONSE = .46f
        const val LAUNCH_DAMPING = .96f
        const val EXIT_RESPONSE = .35f
        const val SCROLL_RESPONSE = .45f
        const val CAPTURE_GRACE = 140L
        /** Round 12.1: longest wait for the left app's window to go after Home is on screen (HyperOS's close transition ≈ 0.35 s). */
        const val LEAVING_WINDOW_LIMIT_MS = 900L
        /** Round 12.4: shortest the frozen Home stays after its live window is on screen when an app was left. */
        const val LEAVING_HOLD_MIN_MS = 360L
        const val WATCHDOG = 2_600L
        const val FALLBACK_ICON = 60f
        const val BACKDROP_BLUR = SwitcherMotion.HOME_BLUR_DP
        /** How far older cards start to the left of their place as the deck engages (card widths). */
        const val REVEAL_SLIDE = .55f
        /** Part of the way to its switcher spot the dragged card moves once the deck has engaged. */
        const val DRAG_SHIFT = .8f
        /** Part of the way to its switcher size the dragged card shrinks once the deck has engaged. */
        const val DRAG_SETTLE = .6f
        const val MAX_SHADERS = 24
        /** Longest the landed card waits for Home to redraw the icon (≈ 120 ms at 120 Hz). */
        const val ICON_RESTORE_FRAMES = 14
        /** Round 13: a slot that only moved (paging) keeps its size within this (dp). */
        const val SLOT_SIZE_TOLERANCE_DP = 2f
        /** A live layout unchanged this long is Home's answer (a page that is still settling is not). */
        const val LAYOUT_SETTLE_MS = 200L
        /** Longest a card holds before it flies on without Home's answer. */
        /** How long a caught card's backdrop eases (rather than jumps) into the drag's style. */
        const val CATCH_BLEND_MS = 250L
        /** How long a quick switch released before the task list waits for it. */
        const val QUICK_TASKS_GRACE = 300L
        const val HOLD_LIMIT_MS = 700L
        /** Round 12: how often an open switcher over the live Home tells the launcher its gesture is still held. */
        const val HEARTBEAT_MS = 1_000L
    }
}

/** The system animator scale; API 31–32 have no reader, so they count as normal speed. */
private fun animatorDurationScale(): Float =
    if (android.os.Build.VERSION.SDK_INT >= 33) ValueAnimator.getDurationScale() else 1f
