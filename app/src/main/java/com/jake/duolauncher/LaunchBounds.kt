package com.jake.duolauncher

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun Rect.toAndroidBounds() = android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

/**
 * Order = preference of a card flying home (see [pickHomeIcon]); RECENT = the Dock's recent-apps copy;
 * WIDGET = a widget that opened the app ([WidgetLaunchTiles]), a target only of that very launch.
 */
internal enum class HomeIconKind { GRID, DOCK, RECENT, FOLDER, WIDGET }

/** Where one app's icon currently sits on Home, in screen pixels, and on which [layer] (Home page, folder, App Library …). */
internal data class HomeIconEntry(val appId: String, val bounds: ZoomBounds, val kind: HomeIconKind,
    val layer: IconLayer = IconLayer.HOME) {
    val packageName: String get() = parseProfileAppId(appId)?.component?.substringBefore('/') ?: appId.substringBefore('/')
}

/**
 * The icon a card flies back into: the exact app (launcher app id = component + profile) on the
 * visible page first, then another activity of the same package IN THE SAME PROFILE (a dual-app or
 * work copy never lands in its parent's icon, round 6), preferring the tile the app was launched from
 * ([launchedFrom], screen px), then a grid icon over the Dock over a folder tile (iOS shrinks an app
 * that lives in a folder into the folder). Icons mostly off-screen — the neighbouring pager pages —
 * never count.
 */
internal fun pickHomeIcon(
    entries: List<HomeIconEntry>,
    appId: String?,
    packageName: String?,
    screenWidth: Float,
    screenHeight: Float,
    launchedFrom: ZoomBounds? = null,
): HomeIconEntry? {
    fun visible(b: ZoomBounds): Boolean {
        if (b.width <= 1f || b.height <= 1f) return false
        val w = min(b.right, screenWidth) - max(b.left, 0f)
        val h = min(b.bottom, screenHeight) - max(b.top, 0f)
        return w > 0f && h > 0f && w * h >= b.width * b.height * .7f
    }
    // A widget is where its app returns only when the app was opened from it (iOS).
    val onScreen = entries.filter { visible(it.bounds) && (it.kind != HomeIconKind.WIDGET ||
        launchedFrom != null && it.bounds.contains(launchedFrom.centerX, launchedFrom.centerY)) }
    val exact = appId?.let { id -> onScreen.filter { it.appId == id } }.orEmpty()
    val profile = appId?.let(::appIdProfileSerial)
    val candidates = exact.ifEmpty {
        packageName?.let { p -> onScreen.filter { it.packageName == p && (appId == null || appIdProfileSerial(it.appId) == profile) } }.orEmpty()
    }
    if (launchedFrom != null && launchedFrom.width > 1f && launchedFrom.height > 1f)
        candidates.firstOrNull { it.bounds.contains(launchedFrom.centerX, launchedFrom.centerY) }?.let { return it }
    return candidates.minByOrNull { it.kind.ordinal }
}

/**
 * Round 13: [a] and [b] are the same Home slot, possibly moved: paging only translates a slot, so it
 * keeps its app, kind, layer and size.
 */
internal fun sameHomeSlot(a: HomeIconEntry, b: HomeIconEntry, sizeTolerance: Float): Boolean =
    a.appId == b.appId && a.kind == b.kind && a.layer == b.layer &&
        abs(a.bounds.width - b.bounds.width) <= sizeTolerance && abs(a.bounds.height - b.bounds.height) <= sizeTolerance

/**
 * Round 13 (owner: swiping Home's pages right after a swipe home left the landing icon behind, drawn
 * above Home near the top, instead of going with its page): the slot a card is landing in, found
 * again in a newer layout. Unlike [pickHomeIcon] there is no visibility filter — a slot carried off
 * screen by its page is still where the card lands — and among copies (the App Library) the one
 * nearest to where the slot was wins.
 */
internal fun trackHomeIcon(entries: List<HomeIconEntry>, aimed: HomeIconEntry, sizeTolerance: Float): HomeIconEntry? =
    entries.filter { sameHomeSlot(it, aimed, sizeTolerance) }.minByOrNull {
        val dx = it.bounds.centerX - aimed.bounds.centerX
        val dy = it.bounds.centerY - aimed.bounds.centerY
        dx * dx + dy * dy
    }

/**
 * Home's icon positions for one layout, in screen pixels, as a plain immutable value: the
 * navigation stage reads it on its own thread, with no round trip to the launcher's main thread
 * (which is busiest exactly while a card flies into Home). [width]/[height] = the size Home was
 * laid out at (≤ 0: unknown, trusted for any screen); [since] = uptime the positions were first seen.
 */
internal class HomeIconLayout(val width: Float, val height: Float, val entries: List<HomeIconEntry>, val since: Long,
    /** The layers in front when this was published ([frontIconLayers]); only their icons are flight targets. */
    val front: Set<IconLayer> = ALL_ICON_LAYERS) {
    /** Laid out for this screen, so a null [find] really means "not on the visible page". */
    fun matches(screenWidth: Float, screenHeight: Float): Boolean =
        width <= 0f || homeLayoutMatches(width, height, screenWidth, screenHeight)

    /** [launchedFrom] defaults to the tile Home last opened [appId] from (NavigationProgressBus.lastLaunch). */
    fun find(appId: String?, packageName: String?, screenWidth: Float, screenHeight: Float,
        launchedFrom: ZoomBounds? = NavigationProgressBus.lastLaunch?.takeIf { it.appId == appId }?.tile): HomeIconEntry? =
        pickHomeIcon(entries.filter { it.layer in front }, appId, packageName, screenWidth, screenHeight, launchedFrom)

    /**
     * Round 13: the slot [aimed] (picked by [find] earlier in the same flight) where Home has moved it
     * since — a page swiped under a landing card carries it sideways, off screen too. Null when it is
     * gone (relaid out at another size, its layer no longer in front): the flight then picks again.
     */
    fun track(aimed: HomeIconEntry, sizeTolerance: Float): HomeIconEntry? =
        trackHomeIcon(entries.filter { it.layer in front }, aimed, sizeTolerance)

    /** Height of a Home grid icon here (px), the size a card flying home ends at. */
    val iconHeight: Float? get() = (entries.firstOrNull { it.kind == HomeIconKind.GRID && it.layer == IconLayer.HOME && it.bounds.height > 1f }
        ?: entries.firstOrNull { it.kind == HomeIconKind.GRID && it.bounds.height > 1f })?.bounds?.height

    fun sameAs(other: HomeIconLayout?): Boolean =
        other != null && other.width == width && other.height == height && other.entries == entries && other.front == front
}

/**
 * Process-local map of Home icon positions, written by the launcher's layout, read by the overlay.
 * Positions are measured on the UI thread from the live layout and *without* Home's own zoom, and
 * [published] as an immutable [HomeIconLayout] once per changed layout pass — while Home is hidden
 * or a card is flying into it (the only times the overlay needs them), and whenever Home is left.
 * A launcher that relaid out while hidden (fold/unfold, rotation, App Library, page change) or is
 * mid-zoom therefore always has its resting positions published, without being asked.
 */
internal object LaunchIconRegistry {
    private val nodes = LinkedHashSet<HomeIconSource>()
    private var frame: HomeZoomFrameNode? = null
    /** Screen position of the launcher window's origin (edge-to-edge, normally 0,0). */
    @Volatile var windowOffsetX = 0f
    @Volatile var windowOffsetY = 0f
    /** The latest positions; replaced as a whole, so any thread may read it. */
    @Volatile var published: HomeIconLayout? = null
        private set
    /** The icon layers in front right now (LauncherScreen, [frontIconLayers]); any thread may read it. */
    @Volatile var front: Set<IconLayer> = ALL_ICON_LAYERS
        private set
    // Main thread only.
    private var dirty = true
    private var queued = false
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val publisher = Runnable { queued = false; if (dirty) publish() }

    internal interface HomeIconSource { fun entries(frame: LayoutCoordinates?): List<HomeIconEntry> }

    @Synchronized fun register(source: HomeIconSource) { nodes += source; layoutChanged() }
    @Synchronized fun remove(source: HomeIconSource) { nodes -= source; layoutChanged() }
    @Synchronized fun attachFrame(node: HomeZoomFrameNode) { frame = node; layoutChanged() }
    @Synchronized fun detachFrame(node: HomeZoomFrameNode) { if (frame === node) frame = null; layoutChanged() }

    /** UI thread: what is in front changed (a folder opened, the App Library or Today slid in …). */
    fun setFront(layers: Set<IconLayer>) { if (layers != front) { front = layers; layoutChanged() } }

    /**
     * An icon or Home's frame moved, resized, appeared or went away (UI thread, from the layout
     * pass). Republished after the pass when someone may need it: Home hidden (it relays out
     * there after a fold) or a card flying home. A visible, idle Home (paging, jiggling) only marks
     * the positions dirty; [publish] runs when it is left (see NavigationProgressBus.homePaused).
     */
    fun layoutChanged() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        dirty = true
        val needed = !NavigationProgressBus.launcherVisible ||
            AppOpenZoomController.state.value?.direction == AppOpenDirection.GESTURE
        if (needed && !queued) { queued = true; main.post(publisher) }
    }

    /** Measures every registered icon now (UI thread) and publishes the result if it changed. */
    @Synchronized fun publish(): HomeIconLayout? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return published
        dirty = false
        val f = frame?.coordinates()
        val size = f?.size
        val entries = nodes.flatMap { it.entries(f) }
        val next = HomeIconLayout(size?.width?.toFloat() ?: -1f, size?.height?.toFloat() ?: -1f, entries, SystemClock.uptimeMillis(), front)
        val previous = published
        // Unchanged positions keep their age: "settled" means nothing moved for a while.
        if (next.sameAs(previous)) return previous
        published = next
        return next
    }

    /**
     * Whether the window of [appId] flies into [source] (a recent-apps copy of an app that may also
     * sit on the page or in the Dock). iOS leaves only that one spot empty: before round 3 the recent
     * copy blinked out for the whole return (≈ 0.6 s) while the card landed on the Home icon.
     * A launch from a tile returns to that tile; a gesture return goes where [pickHomeIcon] says.
     */
    @Synchronized fun isFlightTarget(source: HomeIconSource, appId: String): Boolean {
        val f = frame?.coordinates()
        val mine = source.entries(f).firstOrNull { it.appId == appId } ?: return true
        val size = f?.size ?: return true
        val layers = front
        return isFlightTarget(mine, AppOpenZoomController.hiddenTile ?: AppOpenZoomController.state.value?.bounds,
            nodes.flatMap { it.entries(f) }.filter { it.appId == appId && it.layer in layers }, windowOffsetX + size.width,
            windowOffsetY + size.height, NavigationProgressBus.lastLaunch?.takeIf { it.appId == appId }?.tile)
    }

    /** Publishes pending changes (UI thread), e.g. as Home is left. */
    fun publishIfDirty() { if (dirty) publish() }
}

private fun ZoomBounds.contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

/**
 * Whether a card flying home lands on [mine] (one of [entries], all for the same app): a zoom that
 * started from a tile ([launchedFrom], screen px) returns to that tile; otherwise the card goes to
 * the icon [pickHomeIcon] prefers (page, then Dock, then the recent apps).
 */
internal fun isFlightTarget(mine: HomeIconEntry, launchedFrom: ZoomBounds?, entries: List<HomeIconEntry>,
    screenWidth: Float, screenHeight: Float, launchTile: ZoomBounds? = null): Boolean {
    if (launchedFrom != null && launchedFrom.width > 1f && launchedFrom.height > 1f)
        return mine.bounds.contains(launchedFrom.centerX, launchedFrom.centerY) ||
            launchedFrom.contains(mine.bounds.centerX, mine.bounds.centerY)
    val picked = pickHomeIcon(entries, mine.appId, null, screenWidth, screenHeight, launchTile) ?: return true
    return picked.bounds == mine.bounds
}

/** Home's laid-out size still matches the screen (within 3 %): its icon positions are current. */
internal fun homeLayoutMatches(layoutWidth: Float, layoutHeight: Float, screenWidth: Float, screenHeight: Float): Boolean =
    kotlin.math.abs(layoutWidth - screenWidth) <= screenWidth * .03f && kotlin.math.abs(layoutHeight - screenHeight) <= screenHeight * .03f

/**
 * Window bounds of [node] as they are with Home at rest. [frame] is the zoom layer's content (the
 * layer scales about its centre, which therefore stays put): positions measured inside it are
 * unaffected by the zoom, and its centre maps them back to the window.
 */
internal fun restingBoundsInWindow(node: LayoutCoordinates, frame: LayoutCoordinates?): androidx.compose.ui.geometry.Rect {
    if (frame == null || !frame.isAttached) return node.boundsInWindow()
    val local = frame.localBoundingBoxOf(node, clipBounds = false)
    val center = androidx.compose.ui.geometry.Offset(frame.size.width / 2f, frame.size.height / 2f)
    val origin = frame.localToWindow(center) - center
    return local.translate(origin)
}

/** Publishes this node's window bounds as the Home position of [appIds] (several for a folder tile). */
internal fun Modifier.homeIconTarget(appIds: List<String>, kind: HomeIconKind = HomeIconKind.GRID): Modifier =
    this then HomeIconTargetElement(appIds, kind)

internal fun Modifier.homeIconTarget(appId: String, kind: HomeIconKind = HomeIconKind.GRID): Modifier =
    homeIconTarget(listOf(appId), kind)

private data class HomeIconTargetElement(val appIds: List<String>, val kind: HomeIconKind) :
    ModifierNodeElement<HomeIconTargetNode>() {
    override fun create() = HomeIconTargetNode(appIds, kind)
    override fun update(node: HomeIconTargetNode) {
        node.appIds = appIds; node.kind = kind
        LaunchIconRegistry.layoutChanged()
    }
}

private class HomeIconTargetNode(var appIds: List<String>, var kind: HomeIconKind) :
    Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode, CompositionLocalConsumerModifierNode,
    LaunchIconRegistry.HomeIconSource {
    private var coordinates: LayoutCoordinates? = null
    /** The surface this icon is drawn on ([LocalHomeIconLayer]), read where composition locals are available. */
    @Volatile private var layer = IconLayer.HOME

    /** The app's window is flying in or out of this icon: leave its spot empty, as iOS does. */
    override fun ContentDrawScope.draw() {
        val hidden = AppOpenZoomController.hiddenIcon.value
        // Only the copy on the surface in front: the same app's Home icon under an open folder or the
        // App Library stays drawn.
        // Only the copy the window flies out of / into: with the same app also in the Dock's recent slots
        // (or twice on screen), its other copies stay drawn (the page icon used to blink out too).
        if (hidden != null && kind != HomeIconKind.FOLDER && hidden in appIds && layer in LaunchIconRegistry.front &&
            LaunchIconRegistry.isFlightTarget(this@HomeIconTargetNode, hidden) &&
            // Opened from a widget: the widget is the spot left empty, the app's icons stay.
            !WidgetLaunchTiles.targetsWidget(hidden)) return
        drawContent()
        AppOpenZoomController.iconDrawn(appIds)
    }

    override fun onAttach() { layer = currentValueOf(LocalHomeIconLayer); LaunchIconRegistry.register(this) }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        layer = currentValueOf(LocalHomeIconLayer)
        LaunchIconRegistry.layoutChanged()
    }

    override fun entries(frame: LayoutCoordinates?): List<HomeIconEntry> {
        val c = coordinates?.takeIf { isAttached && it.isAttached } ?: return emptyList()
        val r = runCatching { restingBoundsInWindow(c, frame) }.getOrNull() ?: return emptyList()
        val bounds = ZoomBounds(r.left, r.top, r.right, r.bottom)
            .offset(LaunchIconRegistry.windowOffsetX, LaunchIconRegistry.windowOffsetY)
        return appIds.map { HomeIconEntry(it, bounds, kind, layer) }
    }

    override fun onDetach() { LaunchIconRegistry.remove(this); coordinates = null }
}

/**
 * The card of one widget on Home or in Today View. When the widget opens its app, the app's window
 * zooms out of this card and a swipe home flies back into it (iOS); [WidgetLaunchTiles] names the
 * card of the latest such launch. [cornerRadiusPx] is the card's visible corner.
 */
@androidx.compose.runtime.Stable
internal class WidgetTile {
    internal var node: WidgetTileNode? = null
    @Volatile var cornerRadiusPx = 0f
    /** The card in root px (for a pixel copy) and in window px (the launch bounds); null if not laid out. */
    fun boundsInRoot(): Rect? = node?.coordinates()?.let { runCatching { it.boundsInRoot() }.getOrNull() }
    fun boundsInWindow(): Rect? = node?.coordinates()?.let { runCatching { it.boundsInWindow() }.getOrNull() }
    val attached: Boolean get() = node?.coordinates() != null
    val layer: IconLayer? get() = node?.currentLayer
}

/**
 * The widget an app was last opened from, kept until the next launch. [arm] is called just before
 * the launch with the widget's pixels; the launch [take]s it, so a later launch of the same app from
 * its icon (or from the switcher) no longer returns into the widget.
 */
internal object WidgetLaunchTiles {
    class Launch(val appId: String, val tile: WidgetTile, val art: Bitmap?, val cornerRadiusPx: Float, val at: Long)
    @Volatile private var pending: Launch? = null
    @Volatile var current: Launch? = null
        private set

    fun arm(appId: String, tile: WidgetTile, art: Bitmap?) {
        pending = Launch(appId, tile, art, tile.cornerRadiusPx, SystemClock.uptimeMillis())
    }

    /** The launch of [appId] begins: it came from the armed widget if that was armed just now. */
    fun take(appId: String, now: Long = SystemClock.uptimeMillis()): Launch? {
        val armed = pending?.takeIf { it.appId == appId && now - it.at <= ARM_WINDOW_MS }
        pending = null
        current = armed
        LaunchIconRegistry.layoutChanged()
        return armed
    }

    /** The launch never happened (it threw): the widget is an ordinary widget again. */
    fun drop(appId: String) { if (current?.appId == appId) { current = null; LaunchIconRegistry.layoutChanged() } }

    /** An app was opened from the switcher: a widget is no longer the way back. */
    fun forget() {
        if (current != null) { current = null; layoutChangedOnMain() }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private fun layoutChangedOnMain() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) LaunchIconRegistry.layoutChanged()
        else main.post { LaunchIconRegistry.layoutChanged() }
    }

    /** [appId] was opened from a widget that is on the surface in front: that widget is its target. */
    fun targetsWidget(appId: String): Boolean {
        val launch = current?.takeIf { it.appId == appId } ?: return false
        val layer = launch.tile.layer ?: return false
        return launch.tile.attached && layer in LaunchIconRegistry.front
    }

    /** The artwork a card flying into [appId]'s widget dissolves into. */
    fun artFor(appId: String?): Launch? = current?.takeIf { it.appId == appId }

    private const val ARM_WINDOW_MS = 1_000L
}

internal fun Modifier.widgetLaunchTile(tile: WidgetTile): Modifier = this then WidgetTileElement(tile)

private data class WidgetTileElement(val tile: WidgetTile) : ModifierNodeElement<WidgetTileNode>() {
    override fun create() = WidgetTileNode(tile)
    override fun update(node: WidgetTileNode) {
        if (node.tile !== tile) { if (node.tile.node === node) node.tile.node = null; node.tile = tile; tile.node = node }
    }
}

internal class WidgetTileNode(var tile: WidgetTile) : Modifier.Node(), GlobalPositionAwareModifierNode, DrawModifierNode,
    CompositionLocalConsumerModifierNode, LaunchIconRegistry.HomeIconSource {
    private var coordinates: LayoutCoordinates? = null
    @Volatile var currentLayer = IconLayer.HOME
        private set
    fun coordinates(): LayoutCoordinates? = coordinates?.takeIf { isAttached && it.isAttached }

    private fun launch(): WidgetLaunchTiles.Launch? = WidgetLaunchTiles.current?.takeIf { it.tile === tile }

    /** The app's window is flying out of or into this widget: its spot is empty (as for an icon). */
    override fun ContentDrawScope.draw() {
        val launch = launch()
        val hidden = AppOpenZoomController.hiddenIcon.value
        if (launch != null && hidden == launch.appId && currentLayer in LaunchIconRegistry.front) return
        drawContent()
        if (launch != null) AppOpenZoomController.iconDrawn(listOf(launch.appId))
    }

    // The layer is read when positioned (always before the node can be a flight target).
    override fun onAttach() {
        tile.node = this
        LaunchIconRegistry.register(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        currentLayer = currentValueOf(LocalHomeIconLayer)
        if (launch() != null) LaunchIconRegistry.layoutChanged()
    }

    override fun entries(frame: LayoutCoordinates?): List<HomeIconEntry> {
        val launch = launch() ?: return emptyList()
        val c = coordinates() ?: return emptyList()
        val r = runCatching { restingBoundsInWindow(c, frame) }.getOrNull() ?: return emptyList()
        val bounds = ZoomBounds(r.left, r.top, r.right, r.bottom)
            .offset(LaunchIconRegistry.windowOffsetX, LaunchIconRegistry.windowOffsetY)
        return listOf(HomeIconEntry(launch.appId, bounds, HomeIconKind.WIDGET, currentLayer))
    }

    override fun onDetach() {
        LaunchIconRegistry.remove(this)
        if (tile.node === this) tile.node = null
        coordinates = null
    }
}

/** Marks the content of Home's zoom layer (see [homeSwitcherLayer]) as the frame icons are measured in. */
internal fun Modifier.homeZoomFrame(): Modifier = this then HomeZoomFrameElement

private data object HomeZoomFrameElement : ModifierNodeElement<HomeZoomFrameNode>() {
    override fun create() = HomeZoomFrameNode()
    override fun update(node: HomeZoomFrameNode) = Unit
}

internal class HomeZoomFrameNode : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var coordinates: LayoutCoordinates? = null
    fun coordinates(): LayoutCoordinates? = coordinates?.takeIf { isAttached && it.isAttached }
    override fun onAttach() { LaunchIconRegistry.attachFrame(this) }
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        LaunchIconRegistry.layoutChanged()
    }
    override fun onDetach() { LaunchIconRegistry.detachFrame(this); coordinates = null }
}

/** A frozen frame of Home, drawn by the navigation overlay behind cards (blurred, zoomed). */
internal class HomeSnapshot(
    val bitmap: Bitmap,
    /** Full-resolution window size in px; the bitmap itself is downscaled by [HomeSnapshotStore.SCALE]. */
    val windowWidth: Int,
    val windowHeight: Int,
    val capturedAt: Long = SystemClock.uptimeMillis(),
    /** Where Home's icons were in this very frame (null: Home was laid out for another size). */
    val icons: HomeIconLayout? = null,
)

/** Home with [appId]'s icon hidden: the frame an icon launch zoomed out of. One value, so it never tears. */
internal class LaunchFrame(val snapshot: HomeSnapshot, val appId: String)

/**
 * The launcher copies its own window (PixelCopy — never another app's) when the user leaves Home
 * and when a gesture starts on Home. Our own UI only, so it is kept while the screen is locked.
 * [latest] is Home as the user last left it; [forSize] is the latest frame of each panel size
 * (cover and inner), so a card swiped home on the panel that was just unfolded flies over that
 * panel's Home rather than a grey stand-in; [launchFrame] is Home with one app's icon hidden (the
 * frame an icon launch zoomed out of), so a card flying back into that icon lands on an empty spot
 * exactly like the live Home will show it. At most three frames are held.
 */
internal object HomeSnapshotStore {
    const val SCALE = .6f
    @Volatile var latest: HomeSnapshot? = null
        private set
    @Volatile var launchFrame: LaunchFrame? = null
        private set
    /** Most recent first, one per window size, at most [MAX_SIZES]; replaced as a whole (any thread may read). */
    @Volatile private var frames: List<HomeSnapshot> = emptyList()
    private var activity: java.lang.ref.WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())
    /** Spotlight is over Home: a copy now would freeze the search UI as "Home". */
    @Volatile var searchVisible = false
    private const val MAX_SIZES = 2

    fun attach(host: Activity) { activity = java.lang.ref.WeakReference(host) }
    fun detach(host: Activity) { if (activity?.get() === host) activity = null }

    /**
     * Round 18: frames read back from disk in a restarted process (see [HomeSnapshotDisk]); a size that
     * already has a frame of this process keeps it. The newest restored frame becomes [latest] if none is.
     */
    @Synchronized fun restore(restored: List<HomeSnapshot>) {
        if (restored.isEmpty()) return
        val kept = frames
        val added = restored.filterNot { r -> kept.any { it.windowWidth == r.windowWidth && it.windowHeight == r.windowHeight } }
        if (added.isEmpty()) return
        frames = (kept + added).take(MAX_SIZES)
        if (latest == null) latest = added.first()
    }

    /** Home changed under us (Spotlight was open, a sheet): the stored frames no longer show it. */
    fun invalidate() { latest = null; launchFrame = null; frames = emptyList() }

    /** The latest frame of Home laid out at this size (screen px), from any thread. */
    fun forSize(screenWidth: Float, screenHeight: Float): HomeSnapshot? = frames.firstOrNull {
        homeLayoutMatches(it.windowWidth.toFloat(), it.windowHeight.toFloat(), screenWidth, screenHeight)
    }

    /**
     * Copies the launcher window if it is on screen; [onDone] runs on the main thread.
     * [hiddenAppId] non-null: the copy is the launch frame of that app (its icon hidden), kept
     * apart from [latest]. [onRequested] runs once the copy is queued (or has failed): what the
     * window shows from then on no longer matters to it, so a launch need not wait for the pixels.
     */
    fun capture(afterFrames: Int = 0, hiddenAppId: String? = null, onRequested: (() -> Unit)? = null,
        /** r12b-switcher: false = a transient copy for the caller only (Home blurred under an open switcher), never [latest]. */
        store: Boolean = true,
        onDone: (HomeSnapshot?) -> Unit = {}) {
        if (afterFrames > 0) {
            // Let a just-changed frame (e.g. the hidden icon) reach the screen before copying it.
            val decor = activity?.get()?.window?.peekDecorView() ?: run { onRequested?.invoke(); onDone(null); return }
            decor.postOnAnimation { capture(afterFrames - 1, hiddenAppId, onRequested, store, onDone) }
            return
        }
        val host = activity?.get()
        val window = host?.window
        val decor = window?.peekDecorView()
        if (host == null || decor == null || !decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) {
            onRequested?.invoke(); onDone(null); return
        }
        val w = decor.width
        val h = decor.height
        val bitmap = runCatching {
            Bitmap.createBitmap((w * SCALE).roundToInt().coerceAtLeast(1), (h * SCALE).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: run { onRequested?.invoke(); onDone(null); return }
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        LaunchIconRegistry.windowOffsetX = location[0].toFloat()
        LaunchIconRegistry.windowOffsetY = location[1].toFloat()
        // The icons of this very frame: a card flying home over it can aim at them at once.
        val icons = LaunchIconRegistry.publish()?.takeIf { it.matches(w.toFloat(), h.toFloat()) }
        try {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val snapshot = HomeSnapshot(bitmap, w, h, icons = icons)
                    if (!store) Unit
                    else if (hiddenAppId != null) launchFrame = LaunchFrame(snapshot, hiddenAppId)
                    else if (!searchVisible) {
                        latest = snapshot; launchFrame = null
                        frames = (listOf(snapshot) + frames.filterNot { it.windowWidth == w && it.windowHeight == h }).take(MAX_SIZES)
                        HomeSnapshotDisk.write(snapshot)
                    }
                    onDone(snapshot)
                } else onDone(null)
            }, main)
        } catch (_: IllegalArgumentException) {
            onDone(null)
        }
        onRequested?.invoke()
    }
}
