package com.jake.duolauncher

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** iOS-style Home editing: an anchored context menu, or the jiggling edit mode. */
enum class HomeEditMode { NONE, CONTEXT_MENU, JIGGLE }

sealed interface HomeEditEvent {
    /** Stationary long-press on an app, folder or widget. */
    data object LongPressItem : HomeEditEvent
    /** Stabilised hold on empty desktop space. */
    data object LongPressEmpty : HomeEditEvent
    /** A pressed item moved past touch slop. */
    data object DragStart : HomeEditEvent
    /** The "编辑主屏幕" menu row. */
    data object EditHome : HomeEditEvent
    /** Scrim tap, or any other menu row that completes an action. */
    data object DismissMenu : HomeEditEvent
    /** Single tap on empty space. iOS: "tap the Home Screen background" finishes editing. */
    data object TapEmpty : HomeEditEvent
    data object Done : HomeEditEvent
    data object Back : HomeEditEvent
    data object Home : HomeEditEvent
}

fun editModeTransition(current: HomeEditMode, event: HomeEditEvent): HomeEditMode = when (event) {
    HomeEditEvent.LongPressItem -> if (current == HomeEditMode.JIGGLE) HomeEditMode.JIGGLE else HomeEditMode.CONTEXT_MENU
    HomeEditEvent.LongPressEmpty -> HomeEditMode.JIGGLE
    HomeEditEvent.DragStart, HomeEditEvent.EditHome -> HomeEditMode.JIGGLE
    HomeEditEvent.DismissMenu -> if (current == HomeEditMode.CONTEXT_MENU) HomeEditMode.NONE else current
    // Apple support "Move apps and widgets on the iPhone Home Screen": when finished,
    // "tap the Home Screen background". The same tap also closes a context menu.
    HomeEditEvent.TapEmpty -> HomeEditMode.NONE
    HomeEditEvent.Done -> HomeEditMode.NONE
    HomeEditEvent.Back -> HomeEditMode.NONE
    HomeEditEvent.Home -> HomeEditMode.NONE
}

data class MenuBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    fun intersects(other: MenuBounds) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun inflate(by: Float) = MenuBounds(left - by, top - by, right + by, bottom + by)
    fun translate(dx: Float, dy: Float) = MenuBounds(left + dx, top + dy, right + dx, bottom + dy)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * [originX]/[originY] are the fractional transform origin the menu springs out of (the
 * point of the menu nearest the lifted item). [reversed] is true when the menu sits above
 * the item: like UIContextMenu's automatic element order, the first row stays nearest it.
 * When the item is too tall for the menu to fit above or below it, the lifted preview moves by
 * ([previewDx], [previewDy]) and scales by [previewScale] about its centre (UIContextMenu does
 * the same), or the menu opens [beside] it.
 */
data class ContextMenuPlacement(val x: Float, val y: Float, val below: Boolean,
    val originX: Float = .5f, val originY: Float = if (below) 0f else 1f,
    val previewDx: Float = 0f, val previewDy: Float = 0f, val previewScale: Float = 1f,
    val beside: Boolean = false) {
    val reversed get() = !below
}

/** Smallest scale UIContextMenu applies to a lifted preview before giving up on clearing it. */
const val CONTEXT_PREVIEW_MIN_SCALE = .8f

/** Smallest size a context menu takes to fit above or below its item (labels ≈ 13 sp). */
const val CONTEXT_MENU_MIN_SCALE = .76f

/** Screen height (dp) from which menus keep their full iOS size; an iPhone is 852 pt tall. */
const val CONTEXT_MENU_FULL_SIZE_HEIGHT_DP = 740f

/** The size a menu starts from on a screen [screenHeightDp] tall: shorter screens get a smaller menu. */
fun contextMenuMaxScale(screenHeightDp: Float): Float =
    (screenHeightDp / CONTEXT_MENU_FULL_SIZE_HEIGHT_DP).coerceIn(CONTEXT_MENU_MIN_SCALE, 1f)

/**
 * How large a context menu is drawn. iOS never moves a Home icon or widget to make room for its
 * menu: the menu opens straight above or below the item. A full-size menu (17 sp rows) does not fit
 * beside a middle-row icon on the 622 dp cover, so the whole menu (rows, labels, glyphs, width,
 * corners) takes the largest scale up to [maxScale] at which it fits on the roomier side of
 * [anchor]. The menu is [scalableHeight] * scale + [fixedHeight] tall (hairlines do not scale) and
 * [menuWidth] * scale wide. A camera cutout in the way ([obstacles]: the cover's sits where a menu
 * above the third row from the bottom would start) shrinks it a little further. Only when nothing
 * from [CONTEXT_MENU_MIN_SCALE] up fits does [contextMenuPlacement] fall back to its side and lift
 * placements (a large widget on a short screen).
 */
fun contextMenuScale(anchor: MenuBounds, screen: MenuBounds, scalableHeight: Float, fixedHeight: Float = 0f,
    gap: Float = 10f, margin: Float = 16f, maxScale: Float = 1f, menuWidth: Float = 0f,
    obstacles: List<MenuBounds> = emptyList()): Float {
    val top = maxScale.coerceIn(CONTEXT_MENU_MIN_SCALE, 1f)
    if (scalableHeight <= 0f) return top
    val room = maxOf(screen.bottom - margin - anchor.bottom - gap, anchor.top - gap - screen.top - margin) - fixedHeight
    val fit = (room / scalableHeight).coerceIn(CONTEXT_MENU_MIN_SCALE, top)
    if (obstacles.isEmpty()) return fit
    fun inPlace(scale: Float) = contextMenuPlacement(anchor, screen, menuWidth * scale, scalableHeight * scale + fixedHeight,
        gap, margin, obstacles).let { !it.beside && it.previewDx == 0f && it.previewDy == 0f && it.previewScale == 1f }
    return generateSequence(fit) { it - .01f }.takeWhile { it >= CONTEXT_MENU_MIN_SCALE - 1e-4f }
        .firstOrNull(::inPlace) ?: fit
}

/**
 * Anchor an iOS context menu to a lifted item. iOS aligns the menu with the item's
 * leading edge when the item is on the leading half of the screen and with its trailing
 * edge otherwise; it opens below when it fits, otherwise above. The rectangle never
 * leaves [screen] (already reduced by safe insets) and never covers an [obstacles]
 * rectangle (camera cutouts) when any clear position exists.
 *
 * The menu never covers the lifted item: callers first shrink it with [contextMenuScale]; when
 * neither side fits even then (a widget on the rotated cover), a landscape screen first tries the menu beside the item (top-aligned with it), then
 * the item is moved and, if needed, scaled down to [CONTEXT_PREVIEW_MIN_SCALE] so the item and
 * the menu below it both fit; a portrait screen tries those two in the opposite order.
 */
fun contextMenuPlacement(anchor: MenuBounds, screen: MenuBounds, menuWidth: Float, menuHeight: Float,
    gap: Float = 10f, margin: Float = 16f, obstacles: List<MenuBounds> = emptyList()): ContextMenuPlacement {
    val minX = screen.left + margin
    val maxX = (screen.right - margin - menuWidth).coerceAtLeast(minX)
    val minY = screen.top + margin
    val maxY = (screen.bottom - margin - menuHeight).coerceAtLeast(minY)
    val leading = anchor.centerX <= screen.centerX
    val preferredX = (if (leading) anchor.left else anchor.right - menuWidth).coerceIn(minX, maxX)
    val belowY = anchor.bottom + gap
    val aboveY = anchor.top - gap - menuHeight
    val sides = buildList {
        if (belowY + menuHeight <= screen.bottom - margin + .01f) add(belowY to true)
        if (aboveY >= minY - .01f) add(aboveY to false)
    }
    fun clear(x: Float, y: Float) = MenuBounds(x, y, x + menuWidth, y + menuHeight).let { rect ->
        obstacles.none { it.inflate(gap).intersects(rect) }
    }
    fun shifted(y: Float): Float? {
        // Slide sideways just past every obstacle the menu would cover.
        val blocking = obstacles.filter { it.inflate(gap).intersects(MenuBounds(preferredX, y, preferredX + menuWidth, y + menuHeight)) }
        if (blocking.isEmpty()) return preferredX
        val candidates = blocking.flatMap { listOf(it.right + gap, it.left - gap - menuWidth) }
            .filter { it in minX..maxX }.sortedBy { kotlin.math.abs(it - preferredX) }
        return candidates.firstOrNull { clear(it, y) }
    }
    fun originXFor(x: Float) = ((anchor.centerX.coerceIn(x, x + menuWidth) - x) / menuWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
    sides.firstNotNullOfOrNull { (y, below) -> shifted(y)?.let { Triple(it, y, below) } }?.let { (x, y, below) ->
        return ContextMenuPlacement(x, y, below, originXFor(x), if (below) 0f else 1f)
    }
    // Beside the item, its top edge level with the item's (clamped into the screen).
    fun beside(): ContextMenuPlacement? {
        val y = anchor.top.coerceIn(minY, maxY)
        val right = anchor.right + gap
        val left = anchor.left - gap - menuWidth
        val options = (if (leading) listOf(right, left) else listOf(left, right))
            .filter { it >= minX - .01f && it <= screen.right - margin - menuWidth + .01f }
        val x = options.firstOrNull { clear(it, y) } ?: return null
        val originY = ((anchor.top.coerceIn(y, y + menuHeight) - y) / menuHeight.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return ContextMenuPlacement(x, y, below = true, originX = if (x >= anchor.right) 0f else 1f,
            originY = originY, beside = true)
    }
    // The item moves (and shrinks if it must) so it and the menu below it share the screen.
    fun lifted(): ContextMenuPlacement? {
        val room = screen.bottom - margin - minY - gap - menuHeight
        if (room <= 0f || anchor.height <= 0f) return null
        val scale = (room / anchor.height).coerceAtMost(1f)
        if (scale < CONTEXT_PREVIEW_MIN_SCALE) return null
        val height = anchor.height * scale
        val top = (anchor.centerY - height / 2f).coerceIn(minY, screen.bottom - margin - menuHeight - gap - height)
        val y = top + height + gap
        val x = shifted(y) ?: return null
        return ContextMenuPlacement(x, y, below = true, originX = originXFor(x), originY = 0f,
            previewDy = top + height / 2f - anchor.centerY, previewScale = scale)
    }
    val landscape = screen.width > screen.height
    (if (landscape) beside() ?: lifted() else lifted() ?: beside())?.let { return it }
    val (x, y, below) = sides.firstOrNull()?.let { (y, below) -> Triple(preferredX, y, below) }
        ?: Triple(preferredX, belowY.coerceIn(minY, maxY), true)
    return ContextMenuPlacement(x, y, below, originXFor(x), if (below) 0f else 1f)
}

/**
 * SpringBoard's icon jitter: a rotation of about 0.03 rad (≈1.8°) with a period of roughly a
 * quarter second, plus a sub-point bob. Every item gets its own phase, period and amplitude
 * (±10 %) so neighbours never move in lock-step.
 */
const val JIGGLE_AMPLITUDE_DEG = 1.8f
const val JIGGLE_PERIOD_MS = 260L
/** Vertical bob in dp, slower than the rotation so the combined motion never repeats visibly. */
const val JIGGLE_BOB_DP = .55f
private const val JIGGLE_BOB_PERIOD_RATIO = 1.37f

/** Deterministic pseudo-random value in [0, 1) so an item keeps its motion across recompositions. */
fun jiggleUnit(key: Any, salt: Int = 0): Float {
    var hash = key.hashCode() + salt * -0x61c88647
    hash = hash xor (hash ushr 16)
    hash *= -0x7a143595
    hash = hash xor (hash ushr 13)
    hash *= -0x3d4d51cb
    hash = hash xor (hash ushr 16)
    return (hash.toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
}

/** Deterministic pseudo-random phase in [0, 2π). */
fun jigglePhase(key: Any): Float = (jiggleUnit(key) * 2f * PI).toFloat()

data class JiggleMotion(val amplitudeDeg: Float, val periodMs: Float, val phase: Float,
    val bobDp: Float, val bobPeriodMs: Float, val bobPhase: Float)

/**
 * [sizeRatio] = item size / Home icon size. A widget rotates proportionally less so its
 * corners travel about as far as an icon's corners (iOS widgets visibly jiggle, but gently).
 */
fun jiggleMotion(key: Any, sizeRatio: Float = 1f): JiggleMotion {
    val scale = sizeRatio.coerceAtLeast(1f)
    val amplitude = (JIGGLE_AMPLITUDE_DEG * (.9f + .2f * jiggleUnit(key, 1)) / scale).coerceAtLeast(.45f)
    val period = JIGGLE_PERIOD_MS * (.92f + .16f * jiggleUnit(key, 2))
    val phase = jigglePhase(key)
    return JiggleMotion(amplitude, period, phase, JIGGLE_BOB_DP * (.8f + .4f * jiggleUnit(key, 3)) / scale,
        period * JIGGLE_BOB_PERIOD_RATIO, phase + 1.9f)
}

fun jiggleRotation(timeMs: Long, motion: JiggleMotion, intensity: Float = 1f): Float {
    if (motion.periodMs <= 0f) return 0f
    val angle = 2.0 * PI * ((timeMs % motion.periodMs.toLong().coerceAtLeast(1L)) / motion.periodMs.toDouble()) + motion.phase
    return (motion.amplitudeDeg * intensity.coerceIn(0f, 1f) * sin(angle)).toFloat()
}

fun jiggleBob(timeMs: Long, motion: JiggleMotion, intensity: Float = 1f): Float {
    if (motion.bobPeriodMs <= 0f) return 0f
    val angle = 2.0 * PI * ((timeMs % motion.bobPeriodMs.toLong().coerceAtLeast(1L)) / motion.bobPeriodMs.toDouble()) + motion.bobPhase
    return (motion.bobDp * intensity.coerceIn(0f, 1f) * sin(angle)).toFloat()
}

/** Legacy single-parameter form kept for callers that only need a phase-shifted sine. */
fun jiggleRotation(timeMs: Long, phase: Float, amplitude: Float = JIGGLE_AMPLITUDE_DEG,
    periodMs: Long = JIGGLE_PERIOD_MS): Float {
    if (periodMs <= 0L) return 0f
    val angle = 2.0 * PI * (timeMs % periodMs) / periodMs + phase
    return (amplitude * sin(angle)).toFloat()
}

/**
 * The − badge of jiggle mode. iOS draws a ≈0.38-icon grey disc whose centre sits just inside
 * the icon's top-left corner, so it overhangs the icon by about a third of its diameter.
 * Offsets are the badge's top-left relative to the icon's top-left (dp, negative = outside).
 * A container that clips (the Dock glass) caps the overhang so the disc is never cut off.
 */
data class RemoveBadgeGeometry(val diameter: Float, val offsetX: Float, val offsetY: Float)

fun removeBadgeGeometry(iconSize: Float, maxOverhangX: Float = Float.POSITIVE_INFINITY,
    maxOverhangY: Float = Float.POSITIVE_INFINITY): RemoveBadgeGeometry {
    val diameter = (iconSize * .38f).coerceIn(17f, 25f)
    val inset = iconSize * .07f
    val overhang = (diameter / 2f - inset).coerceAtLeast(0f)
    return RemoveBadgeGeometry(diameter,
        -min(overhang, maxOverhangX.coerceAtLeast(0f)),
        -min(overhang, maxOverhangY.coerceAtLeast(0f)))
}

/**
 * Overhang a Dock badge may use without leaving the glass: the padding beside the icon,
 * reduced so the disc also clears a concentric corner (radius = icon radius + padding).
 */
fun dockBadgeOverhang(iconSize: Float, paddingX: Float, paddingY: Float, iconCornerFraction: Float = .225f,
    clearance: Float = 1.5f): Pair<Float, Float> {
    val badge = removeBadgeGeometry(iconSize)
    val radius = badge.diameter / 2f
    var ox = min(-badge.offsetX, paddingX - clearance).coerceAtLeast(0f)
    var oy = min(-badge.offsetY, paddingY - clearance).coerceAtLeast(0f)
    // Concentric corner centre, relative to the icon's top-left.
    val iconRadius = iconSize * iconCornerFraction
    val cornerRadius = iconRadius + min(paddingX, paddingY)
    val arcX = iconRadius - (paddingX - min(paddingX, paddingY))
    val arcY = iconRadius - (paddingY - min(paddingX, paddingY))
    repeat(24) {
        val cx = -ox + radius; val cy = -oy + radius
        val dx = (arcX - cx).coerceAtLeast(0f); val dy = (arcY - cy).coerceAtLeast(0f)
        if (kotlin.math.sqrt(dx * dx + dy * dy) + radius <= cornerRadius - clearance) return ox to oy
        ox = max(0f, ox - .5f); oy = max(0f, oy - .5f)
    }
    return ox to oy
}
