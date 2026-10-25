package com.jake.duolauncher

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for Home editing (jiggle chrome, folders, drag hover, page turning).
 * All values are in one consistent unit (dp in the tests, px at runtime).
 */

/** Where the jiggle-mode "编辑" and "完成" capsules go. */
data class JiggleChromeLayout(val edit: MenuBounds, val done: MenuBounds)

/**
 * How far a jiggling − badge can reach above its icon's top edge: the disc's static overhang
 * (≈6.5 dp on a 54 dp icon) plus its sway while the tile rotates (≈0.8 dp) and bobs (≈0.7 dp).
 */
const val JIGGLE_BADGE_REACH_DP = 8f

/**
 * The vertical band of the jiggle capsules ([top], [height]) and how far the page grid moves
 * down while jiggling ([gridShift]) so the band is tall enough.
 */
data class JiggleBand(val top: Float, val height: Float, val gridShift: Float = 0f)

/**
 * The capsules sit between the top margin (or below [ceilings], chrome that stays visible above
 * them, e.g. a horizontal status bar) and the first row's − badges. When that band is shorter
 * than [preferredHeight], the page grid moves down by at most [maxGridShift] (iOS shifts the
 * icons rather than letting a capsule cover a badge); only if that is not enough does the
 * capsule shrink, never below [minimumHeight].
 */
fun jiggleBand(windowTop: Float, gridTop: Float, gridLeft: Float, gridRight: Float,
    ceilings: List<MenuBounds> = emptyList(), maxGridShift: Float = 0f, topMargin: Float = 8f,
    badgeOverhang: Float = JIGGLE_BADGE_REACH_DP, preferredHeight: Float = 34f, minimumHeight: Float = 28f,
    gap: Float = 6f): JiggleBand {
    val clearTop = ceilings.filter { it.right > gridLeft && it.left < gridRight && it.top < gridTop }
        .maxOfOrNull { it.bottom + gap }
    val bandTop = max(windowTop + topMargin, clearTop ?: Float.NEGATIVE_INFINITY)
    val available = gridTop - badgeOverhang - bandTop
    val shift = (preferredHeight - available).coerceIn(0f, max(0f, maxGridShift))
    val room = available + shift
    val height = room.coerceIn(minimumHeight, preferredHeight)
    val top = if (room >= height) bandTop + (room - height) / 2f else bandTop
    return JiggleBand(top, height, shift)
}

/**
 * iPhone hides a horizontal status bar while jiggling; Edit/Done take its band. Every Duo Home pose keeps the
 * time+ring block beside the grid instead (inner portrait too since round 8, photo 6), so it stays visible.
 */
@Suppress("UNUSED_PARAMETER")
fun jiggleHidesStatus(pose: DuoHomePose, horizontalStatus: Boolean = false): Boolean = horizontalStatus

/**
 * The jiggle band of this Home frame (dp). The status time and ring are ceilings unless the pose
 * hides them while jiggling ([jiggleHidesStatus]); the grid may move down until its last label
 * meets the jiggle-mode page-dot pill ([dotPillPadding] around the dots) less [bottomMargin].
 */
fun DuoHomeFrame.jiggleBand(geometry: HomeGeometry = toHomeGeometry(), windowTop: Float = 0f,
    dotPillPadding: Float = 8f, bottomMargin: Float = 3f): JiggleBand {
    val g = grid
    val right = g.cellLeft + (if (pose.spread) g.pageStride else 0f) + g.cellGridWidth
    val ceilings = if (jiggleHidesStatus(pose, status.horizontal)) emptyList() else listOf(status.timeBox, status.ringBox)
        .map { MenuBounds(it.left, it.top, it.right, it.bottom) }
    val artwork = geometry.shortcutArtworkSize ?: geometry.iconSize
    val contentBottom = geometry.contentTop + (g.rows - 1) * geometry.rowHeight + max(48f, artwork + geometry.labelHeight)
    val dotPillTop = dots.centerY - dots.dot / 2f - dotPillPadding
    return jiggleBand(windowTop, geometry.contentTop, g.cellLeft, right, ceilings,
        maxGridShift = dotPillTop - bottomMargin - contentBottom)
}

/**
 * iPhone puts the Edit/Done capsules in the status-bar band above the first icon row. On
 * the Duo the time and status ring live in the right-hand column instead, so the capsules
 * stay above the icon grid and inside its horizontal extent: "编辑" starts at the grid's
 * leading edge, "完成" ends at the grid's trailing edge (never over the status column or
 * the Dock). They are vertically centred in [band] (by default between the top margin and the
 * first row's − badges), and slide sideways past camera cutouts only when one actually
 * intersects them.
 */
fun jiggleChromeLayout(window: MenuBounds, grid: MenuBounds, editWidth: Float, doneWidth: Float,
    obstacles: List<MenuBounds> = emptyList(), topMargin: Float = 8f, badgeOverhang: Float = 7f,
    preferredHeight: Float = 34f, minimumHeight: Float = 28f, gap: Float = 6f,
    band: JiggleBand? = null): JiggleChromeLayout {
    val resolved = band ?: jiggleBand(window.top, grid.top, grid.left, grid.right, topMargin = topMargin,
        badgeOverhang = badgeOverhang, preferredHeight = preferredHeight, minimumHeight = minimumHeight, gap = gap)
    val height = resolved.height
    val top = resolved.top
    val left = max(grid.left, window.left + topMargin)
    val right = min(grid.right, window.right - topMargin)
    fun place(x: Float, width: Float, towardsRight: Boolean): MenuBounds {
        var rect = MenuBounds(x, top, x + width, top + height)
        repeat(obstacles.size + 1) {
            val hit = obstacles.firstOrNull { it.inflate(gap).intersects(rect) } ?: return rect
            val nx = if (towardsRight) hit.right + gap else hit.left - gap - width
            rect = MenuBounds(nx, top, nx + width, top + height)
        }
        return rect
    }
    val edit = place(left, editWidth, towardsRight = true)
    var done = place(right - doneWidth, doneWidth, towardsRight = false)
    if (done.intersects(edit.inflate(gap))) {
        // A very narrow grid: keep both visible, Done after Edit.
        done = MenuBounds(edit.right + gap, top, edit.right + gap + doneWidth, top + height)
    }
    return JiggleChromeLayout(edit, done)
}

/** One mini icon inside a folder icon, relative to the folder icon's top-left. */
data class MiniIconSlot(val x: Float, val y: Float, val size: Float)

const val FOLDER_PREVIEW_COLUMNS = 3
const val FOLDER_PREVIEW_MARGIN = .15f
const val FOLDER_PREVIEW_GAP = .05f

/** iOS folder icon: the first nine apps as a 3×3 grid of mini icons on the folder platter. */
fun folderPreviewSlots(iconSize: Float, count: Int): List<MiniIconSlot> {
    val margin = iconSize * FOLDER_PREVIEW_MARGIN
    val gap = iconSize * FOLDER_PREVIEW_GAP
    val mini = (iconSize - 2f * margin - (FOLDER_PREVIEW_COLUMNS - 1) * gap) / FOLDER_PREVIEW_COLUMNS
    return (0 until count.coerceIn(0, FOLDER_PREVIEW_COLUMNS * FOLDER_PREVIEW_COLUMNS)).map { index ->
        val column = index % FOLDER_PREVIEW_COLUMNS
        val row = index / FOLDER_PREVIEW_COLUMNS
        MiniIconSlot(margin + column * (mini + gap), margin + row * (mini + gap), mini)
    }
}

/**
 * The open folder zooms out of its icon: at [progress] 0 the panel is scaled and moved onto
 * the icon, at 1 it rests at [panel]. Returns (scaleX, scaleY, translationX, translationY)
 * for a layer whose transform origin is the panel's centre.
 */
data class ZoomTransform(val scaleX: Float, val scaleY: Float, val translationX: Float, val translationY: Float)

fun folderZoomTransform(icon: MenuBounds, panel: MenuBounds, progress: Float): ZoomTransform {
    val p = progress
    val startScaleX = (icon.width / panel.width.coerceAtLeast(1f))
    val startScaleY = (icon.height / panel.height.coerceAtLeast(1f))
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return ZoomTransform(lerp(startScaleX, 1f), lerp(startScaleY, 1f),
        lerp(icon.centerX - panel.centerX, 0f), lerp(icon.centerY - panel.centerY, 0f))
}

/** What a dragged app does over the cell under the finger. */
enum class DragHoverKind { INSERT, FOLDER }

/**
 * iOS distinguishes "make room here" from "make a folder with this icon" by where the finger
 * rests: over the middle of another app's icon it offers a folder, anywhere else it reflows.
 */
fun dragHoverKind(x: Float, y: Float, occupantIcon: MenuBounds?, canMakeFolder: Boolean,
    zoneFraction: Float = .40f): DragHoverKind {
    if (!canMakeFolder || occupantIcon == null) return DragHoverKind.INSERT
    val radius = occupantIcon.width * zoneFraction
    return if (hypot(x - occupantIcon.centerX, y - occupantIcon.centerY) <= radius) DragHoverKind.FOLDER
        else DragHoverKind.INSERT
}

/** Share of an icon width within which the dragged icon's centre offers a folder. */
const val FOLDER_ZONE_FRACTION = .45f

/**
 * The app a dragged app would make a folder with (iOS): the icon whose centre the dragged icon's
 * centre covers. Judged on the dragged icon, not the finger, so it does not depend on where the
 * icon was grabbed: putting one icon onto another is what makes the folder.
 */
fun folderPartnerAt(x: Float, y: Float, icons: Map<String, MenuBounds>, zoneFraction: Float = FOLDER_ZONE_FRACTION): String? =
    icons.entries.filter { dragHoverKind(x, y, it.value, canMakeFolder = true, zoneFraction) == DragHoverKind.FOLDER }
        .minByOrNull { hypot(x - it.value.centerX, y - it.value.centerY) }?.key

/**
 * The grid reflows only once the dragged icon rests: it moved at most [slopPx] during the last
 * dwell. Crossing a gap on the way to another icon moves nothing (round 16: the target slid away
 * from under the finger before its folder could form).
 */
fun dragRested(fromX: Float, fromY: Float, toX: Float, toY: Float, slopPx: Float): Boolean =
    hypot(toX - fromX, toY - fromY) <= slopPx

/** How long the finger must rest before the grid reflows, or a folder platter appears. */
const val DRAG_REFLOW_DWELL_MS = 150L
const val DRAG_FOLDER_DWELL_MS = 380L

/** iOS turns the page after the finger rests at the edge for about half a second, then slower. */
const val EDGE_TURN_FIRST_MS = 520L
const val EDGE_TURN_REPEAT_MS = 900L
fun edgeTurnDelayMs(turnsSoFar: Int): Long = if (turnsSoFar <= 0) EDGE_TURN_FIRST_MS else EDGE_TURN_REPEAT_MS

/** Home cells draw their icon top-centred; Dock cells centre it. */
fun iconRectInCell(cell: MenuBounds, iconSize: Float, topAligned: Boolean): MenuBounds {
    val left = cell.centerX - iconSize / 2f
    val top = if (topAligned) cell.top else cell.centerY - iconSize / 2f
    return MenuBounds(left, top, left + iconSize, top + iconSize)
}

/**
 * Where a press grabs a Home or Dock icon: the artwork plus its label, with a little slop
 * around both. iOS only lifts an app when the finger is on it; a hold in the gutter between
 * icons (still inside the app's grid cell) is a hold on the bare Home Screen and enters jiggle
 * mode instead. [icon] is the icon's artwork rectangle; [labelHeight] reaches below it.
 */
fun iconGrabArea(icon: MenuBounds, labelHeight: Float, slop: Float): MenuBounds =
    MenuBounds(icon.left - slop, icon.top - slop, icon.right + slop, icon.bottom + labelHeight + slop)

/**
 * The lifted icon keeps the finger on the exact point that was grabbed (no jump when the
 * drag starts); it only grows around that point.
 */
fun dragGhostBounds(pointerX: Float, pointerY: Float, grabX: Float, grabY: Float, width: Float, height: Float) =
    MenuBounds(pointerX - grabX, pointerY - grabY, pointerX - grabX + width, pointerY - grabY + height)

/** Linear interpolation between two rectangles for drop and cancel settles. */
fun lerpBounds(from: MenuBounds, to: MenuBounds, progress: Float): MenuBounds {
    fun lerp(a: Float, b: Float) = a + (b - a) * progress
    return MenuBounds(lerp(from.left, to.left), lerp(from.top, to.top), lerp(from.right, to.right), lerp(from.bottom, to.bottom))
}
