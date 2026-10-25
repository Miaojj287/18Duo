package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** iOS context-menu material: 250dp wide, 26dp corners, 48dp rows, hairline separators, 8dp section gaps. */
internal val CONTEXT_MENU_WIDTH = 250.dp
internal val CONTEXT_MENU_RADIUS = 26.dp
internal val CONTEXT_MENU_ROW_HEIGHT = 48.dp
internal val CONTEXT_MENU_SECTION_GAP = 8.dp
internal val CONTEXT_MENU_HAIRLINE = .5.dp

/**
 * Size factor of the menu being drawn (see [contextMenuScale]): rows, labels, glyphs, gaps, width
 * and corners all follow it, so a menu on the short cover is a smaller copy of the iOS one.
 */
internal val LocalContextMenuScale = compositionLocalOf { 1f }
/** iOS systemRed, used for the destructive last row on both glass tints. */
internal val DestructiveRed = Color(0xFFFF3B30)

/**
 * Root-coordinate icon rectangles, written by tiles before their jiggle transform. A tile that
 * leaves the composition (its page scrolled away, a fold/unfold or rotation rebuilt the grid,
 * the app moved into a folder) takes its rectangle with it, so menus, folder zooms and drop
 * settles never aim at a position from an old layout. Each writer is a token: if two tiles
 * briefly share an id (an app moving between Home and the Dock), only the last writer's
 * removal clears it.
 */
@Stable
internal class HomeIconRegistry {
    private val bounds = HashMap<String, Pair<Rect, Any>>()
    operator fun get(id: String): Rect? = bounds[id]?.first
    operator fun set(id: String, rect: Rect) { bounds[id] = rect to Unit }
    fun put(id: String, rect: Rect, owner: Any) { bounds[id] = rect to owner }
    fun remove(id: String) { bounds.remove(id) }
    /** Clears [id] only if [owner] wrote it last. */
    fun release(id: String, owner: Any) { if (bounds[id]?.second === owner) bounds.remove(id) }
    val size: Int get() = bounds.size
}

/** Edit-mode callbacks; replaced wholesale each recomposition (read only when invoked). */
@Immutable
internal data class HomeEditActions(
    val onRemoveApp: (String) -> Unit = {},
    val onDissolveFolder: (String) -> Unit = {},
    val onRemoveWidget: (Int) -> Unit = {},
    val onResizeWidget: (Int) -> Unit = {},
    val onRemoveDock: (Int) -> Unit = {},
    /** A tap on empty Home space; in jiggle mode it finishes editing (iOS). */
    val onTapEmpty: () -> Unit = {},
)

/**
 * What the edit mode hands to every tile without threading parameters. One stable instance
 * backed by states: a tile recomposes only when a value it reads changes, not on every
 * pointer move of a drag (which recomposes the screen that owns this object).
 */
@Stable
internal class HomeEditChrome(
    private val editingState: State<Boolean> = mutableStateOf(false),
    /** True while jiggling or while the exit animation is still settling icons and badges. */
    private val decoratedState: State<Boolean> = mutableStateOf(false),
    val clock: State<Long> = mutableStateOf(0L),
    /** 0 → 1 presence of jiggle and badges, sprung on entry and exit. */
    val intensity: State<Float> = mutableStateOf(0f),
    /** Context-menu item drawn lifted above the blur; its resting copy is hidden. */
    private val liftedState: State<String?> = mutableStateOf(null),
    /** Items whose resting tile is hidden while a drag ghost settles onto it. */
    private val hiddenState: State<Set<String>> = mutableStateOf(emptySet()),
    /** App under a dragged app long enough to offer a new folder. */
    private val folderHoverState: State<String?> = mutableStateOf(null),
    val icons: HomeIconRegistry = HomeIconRegistry(),
    private val actions: State<HomeEditActions> = mutableStateOf(HomeEditActions()),
) {
    val editing: Boolean get() = editingState.value
    val decorated: Boolean get() = decoratedState.value
    val liftedId: String? get() = liftedState.value
    val hiddenIds: Set<String> get() = hiddenState.value
    val folderHoverId: String? get() = folderHoverState.value
    val onRemoveApp: (String) -> Unit get() = actions.value.onRemoveApp
    val onDissolveFolder: (String) -> Unit get() = actions.value.onDissolveFolder
    val onRemoveWidget: (Int) -> Unit get() = actions.value.onRemoveWidget
    val onResizeWidget: (Int) -> Unit get() = actions.value.onResizeWidget
    val onRemoveDock: (Int) -> Unit get() = actions.value.onRemoveDock
    val onTapEmpty: () -> Unit get() = actions.value.onTapEmpty
}

internal val LocalHomeEditChrome = compositionLocalOf { HomeEditChrome() }

/** In-app Reduce Motion, or the system animator scale set to off (observed, see [LauncherMotion]). */
@Composable
internal fun rememberReduceMotion(): Boolean = LauncherMotion.reduced

/** One frame clock shared by every wiggling item; stops ticking once edit mode has settled. */
@Composable
internal fun rememberJiggleClock(active: Boolean, reduced: Boolean = LauncherMotion.reduced): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active, reduced) {
        if (active && !reduced) while (true) {
            withInfiniteAnimationFrameNanos { clock.longValue = it / 1_000_000L }
        } else clock.longValue = 0L
    }
    return clock
}

/** Draw-phase Gaussian blur of Home behind menus and folders (no recomposition per frame). */
internal fun Modifier.homeBackdropBlur(radiusPx: GraphicsLayerScope.() -> Float): Modifier = graphicsLayer {
    val radius = radiusPx()
    renderEffect = if (radius > .5f) BlurEffect(radius, radius, TileMode.Clamp) else null
    clip = radius > .5f
}

/**
 * iOS 26 scroll-edge effect: scrolling content fades out over [edge] at a viewport edge it
 * continues past, instead of being cut flat (Today View and portrait-fallback Home pages, the
 * create-folder list). Off-screen compositing only while one of the edges actually fades.
 */
internal fun Modifier.scrollEdgeFade(canScrollBackward: () -> Boolean, canScrollForward: () -> Boolean,
    edge: Dp = 24.dp): Modifier = graphicsLayer {
    compositingStrategy = if (canScrollBackward() || canScrollForward()) CompositingStrategy.Offscreen
        else CompositingStrategy.Auto
}.drawWithContent {
    drawContent()
    val fade = edge.toPx().coerceAtMost(size.height / 3f)
    if (fade <= 0f) return@drawWithContent
    if (canScrollBackward()) drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black,
        startY = 0f, endY = fade), size = Size(size.width, fade), blendMode = BlendMode.DstIn)
    if (canScrollForward()) drawRect(Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent,
        startY = size.height - fade, endY = size.height), topLeft = Offset(0f, size.height - fade),
        size = Size(size.width, fade), blendMode = BlendMode.DstIn)
}

/** Registers the item's untransformed icon rectangle (root coordinates) while it is composed. */
internal fun Modifier.registerHomeIcon(registry: HomeIconRegistry, id: String, iconSizePx: Float? = null,
    topAligned: Boolean = true): Modifier = this then RegisterHomeIconElement(registry, id, iconSizePx, topAligned)

private data class RegisterHomeIconElement(val registry: HomeIconRegistry, val id: String, val iconSizePx: Float?,
    val topAligned: Boolean) : ModifierNodeElement<RegisterHomeIconNode>() {
    override fun create() = RegisterHomeIconNode(registry, id, iconSizePx, topAligned)
    override fun update(node: RegisterHomeIconNode) {
        if (node.registry !== registry || node.id != id) node.registry.release(node.id, node)
        node.registry = registry; node.id = id; node.iconSizePx = iconSizePx; node.topAligned = topAligned
    }
    override fun InspectorInfo.inspectableProperties() { name = "registerHomeIcon"; properties["id"] = id }
}

private class RegisterHomeIconNode(var registry: HomeIconRegistry, var id: String, var iconSizePx: Float?,
    var topAligned: Boolean) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val bounds = coordinates.boundsInRoot()
        val size = iconSizePx
        val rect = if (size == null) bounds else {
            val icon = iconRectInCell(MenuBounds(bounds.left, bounds.top, bounds.right, bounds.bottom), size, topAligned)
            Rect(icon.left, icon.top, icon.right, icon.bottom)
        }
        registry.put(id, rect, this)
    }
    override fun onDetach() { registry.release(id, this) }
}

/**
 * Reads the clock inside the layer block so only the layer, not the layout, updates per
 * frame. Reduce Motion keeps items still (badges remain, as on iOS).
 */
internal fun Modifier.jiggle(chrome: HomeEditChrome, motion: JiggleMotion): Modifier =
    if (!chrome.decorated) this else graphicsLayer {
        val intensity = chrome.intensity.value
        if (LauncherMotion.reduced || intensity <= 0f) {
            rotationZ = 0f; translationY = 0f
        } else {
            val time = chrome.clock.value
            rotationZ = jiggleRotation(time, motion, intensity)
            translationY = jiggleBob(time, motion, intensity) * density
        }
    }

@Composable
internal fun GlassMenu(modifier: Modifier = Modifier, width: Dp = CONTEXT_MENU_WIDTH, tag: String? = null,
    interactive: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(CONTEXT_MENU_RADIUS * LocalContextMenuScale.current)
    val dark = LocalDuoPalette.current.dark
    val touch = remember { IosMenuTouch() }
    val haptic = LocalHapticFeedback.current
    Column(modifier.width(width).duoGlass(DuoGlassRole.Floating, shape,
        frost = (if (dark) Color(0xFF2C2C2E) else Color.White).copy(alpha = .5f))
        // The whole platter (rows, hairlines, section gaps) owns its touches: nothing beneath it
        // ever sees a tap, long press or drag that starts on the menu (owner round 3, item 2).
        // A menu animating away is inert, so a second tap reaches Home at once (iOS).
        .overlayInputShield(interactive)
        .onPlaced { touch.container = it }
        .then(if (interactive) Modifier.pointerInput(touch) {
            trackIosMenuTouches(touch) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        } else Modifier)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        CompositionLocalProvider(LocalContentColor provides duoGlassContentColor(DuoGlassRole.Floating),
            LocalIosMenuTouch provides touch) {
            content()
        }
    }
}

/**
 * Touch model of a UIMenu: a finger that lands on the platter highlights the row under it and
 * keeps doing so as it slides (rows hand the highlight over with a selection tick); lifting on an
 * enabled row runs it however long it was held, lifting anywhere else does nothing. A long press
 * is therefore just a slow tap, never a gesture for whatever lies under the menu.
 */
@Stable
internal class IosMenuTouch {
    var highlighted by mutableStateOf<String?>(null)
    var container: LayoutCoordinates? = null
    private val rows = LinkedHashMap<String, Pair<LayoutCoordinates, IosMenuAction>>()

    fun register(action: IosMenuAction, coordinates: LayoutCoordinates) { rows[action.tag] = coordinates to action }
    fun unregister(tag: String) { rows.remove(tag); if (highlighted == tag) highlighted = null }

    /** The enabled row under [local] (the platter's own coordinates, scale animation included). */
    fun rowAt(local: Offset): IosMenuAction? {
        val menu = container?.takeIf { it.isAttached } ?: return null
        return rows.values.firstOrNull { (coordinates, action) ->
            action.enabled && coordinates.isAttached && menu.localBoundingBoxOf(coordinates, false).contains(local)
        }?.second
    }
}

internal val LocalIosMenuTouch = compositionLocalOf<IosMenuTouch?> { null }

private suspend fun PointerInputScope.trackIosMenuTouches(touch: IosMenuTouch, onSelectionChanged: () -> Unit) =
    awaitEachGesture {
        // A control inside the menu (a widget size button) that took its own down keeps its gesture.
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        touch.highlighted = touch.rowAt(down.position)?.tag
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (change == null) break
                if (!change.pressed) {
                    val action = touch.rowAt(change.position)
                    touch.highlighted = null
                    action?.onClick?.invoke()
                    break
                }
                val tag = touch.rowAt(change.position)?.tag
                if (tag != touch.highlighted) {
                    if (tag != null) onSelectionChanged()
                    touch.highlighted = tag
                }
            }
        } finally {
            touch.highlighted = null
        }
    }

/**
 * An iOS 26 menu row: leading glyph, 17sp label, a grey highlight instead of a ripple. Inside a
 * [GlassMenu] the platter tracks the finger (see [IosMenuTouch]); the row only draws the highlight
 * and exposes the click to accessibility. Outside one it is an ordinary button.
 */
@Composable
internal fun GlassMenuRow(icon: ImageVector?, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    tag: String? = null, destructive: Boolean = false, enabled: Boolean = true) {
    val tint = if (destructive) DestructiveRed else LocalContentColor.current
    val alpha = if (enabled) 1f else .35f
    val touch = LocalIosMenuTouch.current
    val scale = LocalContextMenuScale.current
    val key = tag ?: label
    val currentClick by rememberUpdatedState(onClick)
    if (touch != null) DisposableEffect(touch, key) { onDispose { touch.unregister(key) } }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlighted = if (touch != null) touch.highlighted == key else pressed
    Row(modifier.fillMaxWidth().height(CONTEXT_MENU_ROW_HEIGHT * scale)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .background(LocalContentColor.current.copy(alpha = if (highlighted) .10f else 0f))
        .then(if (touch != null) Modifier
            .onPlaced { touch.register(IosMenuAction(label, icon, key, destructive, enabled) { currentClick() }, it) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) onClick(label) { currentClick(); true } else disabled()
            }
        else Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
            onClick = onClick))
        .padding(horizontal = 16.dp * scale), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(21.dp * scale), tint = tint.copy(alpha = tint.alpha * alpha))
            Spacer(Modifier.width(13.dp * scale))
        }
        Text(label, Modifier.weight(1f), color = tint.copy(alpha = tint.alpha * alpha), fontSize = 17.sp * scale,
            lineHeight = 22.sp * scale, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GlassMenuDivider(thick: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(if (thick) CONTEXT_MENU_SECTION_GAP * LocalContextMenuScale.current else CONTEXT_MENU_HAIRLINE)
        .background(LocalContentColor.current.copy(alpha = if (thick) .07f else .15f)))
}

/** One menu row's model; sections are separated by an 8dp gap as on iOS. */
@Immutable
internal data class IosMenuAction(val label: String, val icon: ImageVector?, val tag: String,
    val destructive: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

internal fun contextMenuHeightPx(sections: List<List<IosMenuAction>>, density: Float, scale: Float = 1f): Float =
    contextMenuScalableHeightPx(sections, density) * scale + contextMenuHairlinesPx(sections, density)

/** The part of a menu's height that follows [LocalContextMenuScale]: rows and section gaps. */
internal fun contextMenuScalableHeightPx(sections: List<List<IosMenuAction>>, density: Float): Float {
    val rows = sections.sumOf { it.size }
    val gaps = (sections.count { it.isNotEmpty() } - 1).coerceAtLeast(0)
    return (rows * CONTEXT_MENU_ROW_HEIGHT.value + gaps * CONTEXT_MENU_SECTION_GAP.value) * density
}

private fun contextMenuHairlinesPx(sections: List<List<IosMenuAction>>, density: Float): Float =
    sections.sumOf { (it.size - 1).coerceAtLeast(0) } * CONTEXT_MENU_HAIRLINE.value * density

@Composable
internal fun IosMenuRows(sections: List<List<IosMenuAction>>, reversed: Boolean) {
    val ordered = sections.filter { it.isNotEmpty() }.let { list ->
        if (reversed) list.reversed().map { it.reversed() } else list
    }
    ordered.forEachIndexed { sectionIndex, section ->
        if (sectionIndex > 0) GlassMenuDivider(thick = true)
        section.forEachIndexed { index, action ->
            if (index > 0) GlassMenuDivider()
            GlassMenuRow(action.icon, action.label, action.onClick, tag = action.tag,
                destructive = action.destructive, enabled = action.enabled)
        }
    }
}

/** What a context menu shows: the lifted item and the rows beside it. */
@Immutable
internal data class IosMenuRequest(
    val key: Any,
    /** The lifted item's rectangle in root coordinates. */
    val anchor: Rect?,
    val sections: List<List<IosMenuAction>>,
    val overlayTag: String,
    val menuTag: String,
    /** Drawn at [anchor], above the blurred Home, scaled up slightly with a shadow. */
    val preview: (@Composable () -> Unit)? = null,
    val previewShape: Shape = RectangleShape,
    val width: Dp = CONTEXT_MENU_WIDTH,
    /** iOS blurs Home behind an item's menu; button menus (Edit) only float. */
    val blurBackground: Boolean = true,
    /** Optional custom first block (iOS widget menus start with a row of size buttons). */
    val header: (@Composable () -> Unit)? = null,
    val headerHeight: Dp = 0.dp,
)

private val menuInSpring get() = LauncherMotion.menuIn
private val menuOutSpring get() = LauncherMotion.menuOut

/**
 * Who draws a menu's lifted item. Once the finger drags it away the drag ghost owns it for the
 * rest of that menu: while the dismissed menu springs out, its retained request must not draw the
 * item again in its old slot. (A quick drag drops before the exit spring settles; the drag's own
 * flag is false again by then, and the preview used to reappear there beside the dropped icon.)
 * A live request that is not being dragged gives the item back to the menu.
 */
internal class MenuPreviewHandoff {
    private var handedOffKey: Any? = null

    /** True when the preview of [key] must not be drawn this frame. */
    fun hidden(key: Any, live: Boolean, dragging: Boolean): Boolean {
        if (dragging) handedOffKey = key
        else if (live) handedOffKey = null
        return dragging || handedOffKey == key
    }
}

/**
 * Hosts one context menu with iOS motion: it springs out of the lifted item, then springs
 * back into it when [request] becomes null. [onProgress] drives the Home blur (0 → 1).
 * [safeArea] is the root-coordinate rectangle the menu may occupy; [obstacles] are cutouts.
 */
@Composable
internal fun IosContextMenuHost(request: IosMenuRequest?, onDismissRequest: () -> Unit,
    onProgress: (Float) -> Unit = {}, safeArea: Rect? = null, obstacles: List<Rect> = emptyList(),
    interactive: Boolean = true, hidePreview: Boolean = false) {
    // The caller rebuilds the request (fresh lambdas) on every recomposition; only a new key
    // restarts the spring, and the last request is retained to animate out.
    val retained = remember { arrayOfNulls<IosMenuRequest>(1) }
    var visibleKey by remember { mutableStateOf<Any?>(null) }
    val progress = remember { Animatable(0f) }
    val currentProgress by rememberUpdatedState(onProgress)
    val reduced = rememberReduceMotion()
    val key = request?.key
    val blur = (request ?: retained[0])?.blurBackground == true
    LaunchedEffect(key) {
        if (key != null) {
            if (visibleKey != key) progress.snapTo(0f)
            visibleKey = key
            // Reduce Motion: the menu and blur cross-dissolve in place of the zoom (iOS).
            progress.animateTo(1f, if (reduced) LauncherMotion.dissolveIn else menuInSpring) {
                currentProgress(if (blur) value.coerceIn(0f, 1f) else 0f)
            }
        } else if (visibleKey != null) {
            progress.animateTo(0f, if (reduced) LauncherMotion.dissolveOut else menuOutSpring) {
                currentProgress(if (blur) value.coerceIn(0f, 1f) else 0f)
            }
            currentProgress(0f)
            visibleKey = null
        }
    }
    SideEffect { if (request != null) retained[0] = request }
    val active = request ?: retained[0]?.takeIf { visibleKey != null } ?: return
    val live = request != null
    val handoff = remember { MenuPreviewHandoff() }
    val previewHidden = handoff.hidden(active.key, live, hidePreview)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag(active.overlayTag)) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        // The scrim takes every touch outside the menu: a tap (or a long press, released) closes the
        // menu, and nothing under it — an icon, the page, Home's lift detector — ever sees it.
        if (live && interactive) Box(Modifier.fillMaxSize().overlayInputShield()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
            .semantics { contentDescription = uxText("关闭菜单", "Close menu") })
        if (active.blurBackground) Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = .12f)))
        val anchor = active.anchor
        val safe = safeArea ?: Rect(0f, 0f, screenW, screenH)
        val safeBounds = MenuBounds(safe.left, safe.top, safe.right, safe.bottom)
        val bounds = anchor?.let { MenuBounds(it.left, it.top, it.right, it.bottom) }
            ?: MenuBounds(screenW / 2f - 1f, screenH / 2f - 1f, screenW / 2f + 1f, screenH / 2f + 1f)
        val gap = with(density) { 12.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        // iOS opens the menu straight above or below the item and never moves the item: on a
        // short screen the menu shrinks to fit instead (owner round 14).
        val scalableH = contextMenuScalableHeightPx(active.sections, density.density) +
            if (active.header != null) with(density) { (active.headerHeight + CONTEXT_MENU_SECTION_GAP).toPx() } else 0f
        val hairlinesH = contextMenuHairlinesPx(active.sections, density.density)
        val fullW = with(density) { active.width.toPx() }
        val cutouts = obstacles.map { MenuBounds(it.left, it.top, it.right, it.bottom) }
        val menuScale = contextMenuScale(bounds, safeBounds, scalableH, hairlinesH, gap, margin,
            maxScale = contextMenuMaxScale(maxHeight.value), menuWidth = fullW, obstacles = cutouts)
        val menuW = fullW * menuScale
        val menuH = scalableH * menuScale + hairlinesH
        val placement = contextMenuPlacement(bounds, safeBounds, menuW, menuH, gap = gap, margin = margin,
            obstacles = cutouts)
        // The lifted item: same place as its resting copy, a touch larger, casting a shadow.
        // When the menu cannot fit above or below it, it glides (and shrinks) out of the menu's
        // way instead of being covered. Once the finger drags it away, the drag ghost owns the
        // item ([hidePreview]) until this menu has gone ([MenuPreviewHandoff]).
        if (anchor != null && active.preview != null && !previewHidden) {
            Box(Modifier.offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() })
                .graphicsLayer {
                    val p = progress.value
                    val lift = if (LauncherMotion.reduced) 1f else 1f + .045f * p
                    val fit = 1f + (placement.previewScale - 1f) * p.coerceIn(0f, 1f)
                    scaleX = lift * fit; scaleY = lift * fit
                    translationX = placement.previewDx * p
                    translationY = placement.previewDy * p
                    shadowElevation = 18.dp.toPx() * p.coerceIn(0f, 1f)
                    shape = active.previewShape
                    clip = false
                    ambientShadowColor = Color.Black.copy(alpha = .25f)
                    spotShadowColor = Color.Black.copy(alpha = .35f)
                }
                .testTag("${active.overlayTag}-lifted")) { active.preview.invoke() }
        }
        CompositionLocalProvider(LocalContextMenuScale provides menuScale) {
            GlassMenu(Modifier.offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
                .graphicsLayer {
                    val p = progress.value
                    val reducedMotion = LauncherMotion.reduced
                    alpha = if (reducedMotion) p.coerceIn(0f, 1f) else (p * 2.2f).coerceIn(0f, 1f)
                    val scale = if (reducedMotion) 1f else .25f + .75f * p
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin(placement.originX, placement.originY)
                }, width = active.width * menuScale, tag = active.menuTag, interactive = live && interactive) {
                val header = active.header
                // The header (a widget's size row) is part of the menu's first section, so it stays
                // nearest the item like the first row does when the menu opens above it.
                if (header != null && !placement.reversed) {
                    Box(Modifier.fillMaxWidth().height(active.headerHeight * menuScale)) { header() }
                    GlassMenuDivider(thick = true)
                }
                IosMenuRows(active.sections, placement.reversed)
                if (header != null && placement.reversed) {
                    GlassMenuDivider(thick = true)
                    Box(Modifier.fillMaxWidth().height(active.headerHeight * menuScale)) { header() }
                }
            }
        }
    }
}

/**
 * Legacy anchored menu with free-form content (kept for callers outside Home editing).
 * New code uses [IosContextMenuHost] with [IosMenuAction] rows.
 */
@Composable
internal fun AnchoredContextMenu(anchor: Rect?, onDismiss: () -> Unit, overlayTag: String, menuTag: String,
    width: Dp = CONTEXT_MENU_WIDTH, scrim: Boolean = true, interactive: Boolean = true,
    content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    var measured by remember { mutableStateOf(IntOffset(0, 0)) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, menuInSpring, label = "context menu arrival")
    BoxWithConstraints(Modifier.fillMaxSize().testTag(overlayTag)) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        if (interactive) Box(Modifier.fillMaxSize().overlayInputShield()
            .then(if (scrim) Modifier.background(Color.Black.copy(alpha = .14f * progress)) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = uxText("关闭菜单", "Close menu") })
        val menuW = with(density) { width.toPx() }
        val menuH = measured.y.toFloat().coerceAtLeast(1f)
        val fallback = MenuBounds(screenW / 2f - 1f, screenH / 2f - 1f, screenW / 2f + 1f, screenH / 2f + 1f)
        val bounds = anchor?.let { MenuBounds(it.left, it.top, it.right, it.bottom) } ?: fallback
        val placement = contextMenuPlacement(bounds, MenuBounds(0f, 0f, screenW, screenH), menuW, menuH,
            gap = with(density) { 10.dp.toPx() }, margin = with(density) { 16.dp.toPx() })
        GlassMenu(Modifier.offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
            .onGloballyPositioned { measured = IntOffset(it.size.width, it.size.height) }
            .graphicsLayer {
                alpha = (progress * 2f).coerceIn(0f, 1f)
                scaleX = .25f + .75f * progress; scaleY = scaleX
                transformOrigin = TransformOrigin(placement.originX, placement.originY)
            }, width = width, tag = menuTag, content = content)
    }
}

/**
 * iOS jiggle-mode delete badge: a grey translucent disc with a white minus, hanging off the
 * icon's top-left corner. It pops in and out with the edit-mode intensity.
 */
@Composable
internal fun RemoveBadge(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    diameter: Dp = 20.dp, intensity: State<Float>? = null) {
    // A 44dp-equivalent touch target centred on the visible disc.
    val touch = maxOf(diameter, 34.dp)
    val inset = (touch - diameter) / 2
    Box(modifier.offset(-inset, -inset).size(touch).testTag(tag)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(diameter)
            .graphicsLayer {
                val scale = intensity?.value?.coerceIn(0f, 1f) ?: 1f
                scaleX = scale; scaleY = scale; alpha = scale
            }
            .shadow(1.5.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = .2f), spotColor = Color.Black.copy(alpha = .25f))
            .background(Color(0xFF4A4A4E).copy(alpha = .86f), CircleShape)
            .border(.5.dp, Color.White.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.width(diameter * .44f).height((diameter.value * .1f).coerceAtLeast(1.8f).dp)
                .background(Color.White, RoundedCornerShape(50)))
        }
    }
}

/** Quarter-ring bracket at a widget's bottom-right corner; tapping starts the resize flow. */
@Composable
internal fun WidgetResizeHandle(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).testTag(tag)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.BottomEnd) {
        Canvas(Modifier.size(26.dp)) {
            val stroke = 3.dp.toPx()
            val arc = Size(size.width * 2f - stroke, size.height * 2f - stroke)
            drawArc(Color.White.copy(alpha = .35f), 0f, 90f, false,
                topLeft = Offset(-size.width + stroke / 2f, -size.height + stroke / 2f), size = arc,
                style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
            drawArc(Color.White, 0f, 90f, false,
                topLeft = Offset(-size.width + stroke / 2f, -size.height + stroke / 2f), size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

/** Glass capsule for the jiggle top bar ("编辑" on the left, "完成" on the right). */
@Composable
internal fun JigglePill(tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.defaultMinSize(minWidth = 44.dp).duoGlass(DuoGlassRole.Floating, CircleShape, interaction)
        .overlayInputShield()
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        CompositionLocalProvider(LocalContentColor provides duoGlassContentColor(DuoGlassRole.Floating)) { content() }
    }
}

@Composable
internal fun JigglePillLabel(text: String, bold: Boolean = true) {
    Text(text, fontSize = 15.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
}
