package com.jake.duolauncher

import kotlin.math.abs

/**
 * iPhone Duo Home Screen geometry for every pose, derived from fractions measured on Apple's own
 * frames (BRIEF "Measured iPhone Duo Home Screen geometry": reference/hig/grid-outer.png and
 * grid-inner.png, apple-video/frames-status-bar). The Xiaomi panels have the Duo's physical size and
 * aspect, so fractions of the actual panel transfer 1:1 and also adapt to other panels.
 */
enum class DuoHomePose {
    COVER_PORTRAIT, COVER_LANDSCAPE, INNER_LANDSCAPE, INNER_PORTRAIT;

    /** Tech Talk 111466: the only pose that keeps horizontal bars is the inner display in portrait. */
    val sideColumn: Boolean get() = this != INNER_PORTRAIT
    val cover: Boolean get() = this == COVER_PORTRAIT || this == COVER_LANDSCAPE
    /** Unfolded landscape shows two pages (halves) at once and pages one half at a time. */
    val spread: Boolean get() = this == INNER_LANDSCAPE
}

fun duoHomePose(cover: Boolean, widthDp: Float, heightDp: Float): DuoHomePose = when {
    cover -> if (widthDp > heightDp) DuoHomePose.COVER_LANDSCAPE else DuoHomePose.COVER_PORTRAIT
    widthDp >= heightDp -> DuoHomePose.INNER_LANDSCAPE
    else -> DuoHomePose.INNER_PORTRAIT
}

/** Window-space rectangle in dp. */
data class DuoBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    fun intersects(other: DuoBox, margin: Float = 0f): Boolean =
        left < other.right + margin && other.left < right + margin &&
            top < other.bottom + margin && other.top < bottom + margin
    fun shifted(dx: Float = 0f, dy: Float = 0f) = DuoBox(left + dx, top + dy, right + dx, bottom + dy)
    fun union(other: DuoBox) = DuoBox(minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom))

    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float) =
            DuoBox(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }
}

/**
 * The page grid. Icons are centred in cells [columnPitch] wide and sit at the top of rows
 * [rowPitch] tall; labels hang below the artwork. Every page and both unfolded halves use the
 * same row system, so widgets and icons line up across the hinge.
 */
data class DuoGrid(
    val columns: Int,
    val rows: Int,
    val icon: Float,
    val columnPitch: Float,
    val rowPitch: Float,
    /** Left edge of the first visible page's cell grid, in window dp. */
    val cellLeft: Float,
    val firstRowTop: Float,
    /** Distance between consecutive pages: the full panel on the cover, grid + equal gap unfolded. */
    val pageStride: Float,
    /** Side of a small (2×2) widget. */
    val widgetSide: Float,
) {
    val cellGridWidth: Float get() = columns * columnPitch
    val iconLeft: Float get() = cellLeft + (columnPitch - icon) / 2f
    val iconGridWidth: Float get() = (columns - 1) * columnPitch + icon
    /** A widget covers span × pitch minus these gaps, so a 2×2 card is exactly [widgetSide]. */
    val widgetGapX: Float get() = (2f * columnPitch - widgetSide).coerceAtLeast(0f)
    val widgetGapY: Float get() = (2f * rowPitch - widgetSide).coerceAtLeast(0f)
    fun rowTop(row: Int): Float = firstRowTop + row * rowPitch
}

data class DuoDock(
    val box: DuoBox,
    val icon: Float,
    val pitch: Float,
    val padding: Float,
    val horizontal: Boolean,
    /** App slots including optional recent apps. */
    val slots: Int = 4,
    /** Extra space for the hairline before recent apps. */
    val extraGap: Float = 0f,
    /** Centre of the icon run along a horizontal bar; stays put if the glass is trimmed for a camera. */
    val contentCenterX: Float = box.centerX,
) {
    /** Extent of the icons along the bar (a horizontal Dock aligns its icons to the grid columns). */
    val iconSpan: Float get() = (slots - 1) * pitch + icon + extraGap
    /** Concentric corners (icon corner radius + inset), not a capsule: grid-outer.png Dock ≈ 0.31 × width. */
    val cornerRadius: Float get() = icon * IOS_ICON_CORNER_FRACTION + padding
}

data class DuoStatus(
    val timeCenterX: Float,
    val timeCenterY: Float,
    val ringCenterX: Float,
    val ringCenterY: Float,
    val ringDiameter: Float,
    /** Visual size of the time digits in dp (SF semibold ≈ 15 pt on the Duo). */
    val timeSize: Float,
    val horizontal: Boolean,
) {
    val timeBox: DuoBox get() = DuoBox.centered(timeCenterX, timeCenterY, timeSize * 3.2f, timeSize * 1.3f)
    val ringBox: DuoBox get() = DuoBox.centered(ringCenterX, ringCenterY, ringDiameter, ringDiameter)
    val bounds: DuoBox get() = timeBox.union(ringBox)
    fun shifted(dx: Float, dy: Float) = copy(timeCenterX = timeCenterX + dx, timeCenterY = timeCenterY + dy,
        ringCenterX = ringCenterX + dx, ringCenterY = ringCenterY + dy)
}

data class DuoPageDots(val centerX: Float, val centerY: Float, val dot: Float, val pitch: Float)

data class DuoHomeFrame(
    val pose: DuoHomePose,
    val width: Float,
    val height: Float,
    val grid: DuoGrid,
    val dock: DuoDock,
    val status: DuoStatus,
    val search: DuoBox,
    val dots: DuoPageDots,
    /** Label band under each icon: 5.5 dp gap plus one line of the label font. */
    val labelHeight: Float,
    val labelSp: Float,
    /** What the side-column Dock asks for before it is fitted between the status block and Search. */
    val sideDock: SideDockSpec? = null,
) {
    /** Right edge of the paging workspace; pages fade before they reach the Dock column. */
    val workspaceRight: Float get() = if (pose.sideColumn) minOf(dock.box.left, search.left) else width
    /** Bottom of the last icon label; ordinary pages need nothing below it. */
    val gridBottom: Float get() = grid.rowTop(grid.rows - 1) + grid.icon + labelHeight
    /**
     * Height a Home page pane leaves free at the bottom (page dots, bottom bars): the pane ends just
     * below the last row of [geometry] plus the renderer's 8 dp padding, so no row is ever clipped.
     */
    fun homeBottomSpace(geometry: HomeGeometry = toHomeGeometry()): Float {
        val artwork = geometry.shortcutArtworkSize ?: geometry.iconSize
        val lastRowBottom = geometry.contentTop + (grid.rows - 1) * geometry.rowHeight +
            maxOf(48f, artwork + geometry.labelHeight)
        return (height - lastRowBottom - 8f - 1f).coerceAtLeast(0f)
    }
}

/** Fractions measured on the Duo cover (portrait W×H, 425×622 dp on the Xiaomi). */
internal object DuoCover {
    const val ICON = .127f            // of W
    const val COLUMN_PITCH = .189f    // of W
    const val ICON_LEFT = .059f       // of W, first icon's left edge
    const val FIRST_ROW_TOP = .082f   // of H
    const val ROW_PITCH = .1455f      // of H
    const val WIDGET = .337f          // of W
    const val COLUMN_X = .896f        // of W, centre of time/ring/Dock/Search
    const val TIME_Y = .128f          // of H
    const val RING_Y = .179f          // of H
    const val RING = .086f            // of W
    const val TIME_SIZE = .0353f      // of W (15 dp)
    const val DOCK_LEFT = .821f       // of W
    const val DOCK_RIGHT = .974f      // of W
    const val DOCK_CENTER_Y = .550f   // of H
    const val DOCK_ICON = .104f       // of W (0.82 × home icon)
    const val DOCK_PITCH = .0946f     // of H
    const val SEARCH = .103f          // of W
    const val SEARCH_Y = .929f        // of H
    const val DOTS_Y = .966f          // of H
    const val DOT = .0148f            // of W (6.3 dp)
    const val DOT_PITCH = .0376f      // of W (16 dp)
}

/** Fractions measured on the unfolded Duo (landscape W×H, 860×608 dp on the Xiaomi). */
internal object DuoInner {
    const val ICON = .0624f           // of W
    const val GAP = .078f             // of W: Apple's equal edge→grid = grid→grid = grid→Dock (HIG frame)
    const val DOCK_GAP = .0336f       // of W: the cover's grid→Dock gap (29 dp), see [innerSpreadGaps]
    const val COLUMN_PITCH = .0922f   // of W ((41.7 − 7.8 − 6.24) / 3 %)
    const val FIRST_ROW_TOP = .081f   // of H
    const val ROW_PITCH = .146f       // of H
    const val WIDGET = .164f          // of W (141 dp, the cover's physical card)
    const val COLUMN_X = .949f        // of W
    const val TIME_Y = .066f          // of H
    const val RING_Y = .123f          // of H
    const val RING = .0426f           // of W
    const val TIME_SIZE = .0174f      // of W (15 dp)
    const val DOCK_LEFT = .913f
    const val DOCK_RIGHT = .985f
    const val DOCK_CENTER_Y = .551f   // of H
    const val DOCK_ICON = .0496f      // of W
    const val DOCK_PITCH = .0954f     // of H
    const val SEARCH = .0507f         // of W
    const val SEARCH_Y = .927f        // of H
    const val DOTS_Y = .946f          // of H
    const val DOT = .0073f            // of W
    const val DOT_PITCH = .0186f      // of W
}

/**
 * Round 3 (owner, on the real Xiaomi 18 Fold): with the side column the Dock glass is centred on the panel
 * height. Apple's frames put it at 55 %H ([DuoCover.DOCK_CENTER_Y], [DuoInner.DOCK_CENTER_Y]), which the
 * owner found "too low". With recent apps the whole group (four apps, hairline, recents) is centred.
 * Round 4: it is centred in every posture and never overlaps the status block or Search — a Dock that
 * does not fit is scaled and recent apps may overflow ([SideDockSpec.fit]).
 */
internal const val DOCK_CENTER_FRACTION = .5f

/**
 * Inner portrait: a compact floating Liquid Glass Dock that hugs its icons instead of a full-width bottom
 * strip. Its inset around an icon is the Duo's own Dock (grid-outer.png): 0.236 × icon
 * ((15.3 − 10.4) / 2 %W ÷ 10.4 %W); photo 6 (rectified) shows the same 12.5 dp around a 54 dp icon.
 */
internal const val FLOATING_DOCK_INSET = (DuoCover.DOCK_RIGHT - DuoCover.DOCK_LEFT - DuoCover.DOCK_ICON) / 2f / DuoCover.DOCK_ICON

private const val LABEL_SP = 11f
/** Icon → label gap: Apple's label cap starts ≈15 % of the icon below it (reviews/visual 94). */
private const val LABEL_GAP_DP = 5.5f
/** Space kept between side-column elements when the Dock has to be clamped. */
private const val COLUMN_SPACING_DP = 16f

/** Label band: the 5.5 dp gap plus one 11 sp line (scaled by the system font size). */
fun duoLabelHeight(fontScale: Float = 1f): Float =
    LABEL_GAP_DP + LABEL_SP * 1.3f * (fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f)

/**
 * Geometry of [pose] on a [widthDp]×[heightDp] window. [dockExtraSlots] are optional recent apps
 * appended after the four Dock apps (separated by a hairline of [dockExtraGap]).
 */
fun duoHomeFrame(
    pose: DuoHomePose,
    widthDp: Float,
    heightDp: Float,
    labelHeight: Float = duoLabelHeight(),
    dockExtraSlots: Int = 0,
    dockExtraGap: Float = 13f,
): DuoHomeFrame {
    val w = widthDp.takeIf { it.isFinite() && it > 0f } ?: SHARED_HOME_WIDTH_DP
    val h = heightDp.takeIf { it.isFinite() && it > 0f } ?: SHARED_SAFE_HEIGHT_DP
    val label = labelHeight.takeIf { it.isFinite() && it >= 0f } ?: duoLabelHeight()
    val extra = dockExtraSlots.coerceAtLeast(0)
    return when (pose) {
        DuoHomePose.COVER_PORTRAIT -> coverPortrait(w, h, label, extra, dockExtraGap)
        DuoHomePose.INNER_LANDSCAPE -> innerLandscape(w, h, label, extra, dockExtraGap)
        DuoHomePose.COVER_LANDSCAPE -> coverLandscape(w, h, label, extra, dockExtraGap)
        DuoHomePose.INNER_PORTRAIT -> innerPortrait(w, h, label, extra, dockExtraGap)
    }
}

/**
 * A side-column Dock before it is fitted: Apple's sizes (width, icon, pitch) for the four apps plus the
 * optional recent apps, the centre it wants, and the legacy width preference ([scale]; 1 = Apple's).
 * [centred] is false only when the legacy "align with app rows" preference is off and the user chose a
 * height; the Dock is then placed at [centerY] as near as it fits.
 */
data class SideDockSpec(
    val centerX: Float,
    val width: Float,
    val icon: Float,
    val pitch: Float,
    /** Recent apps asked for; [DuoDock.slots] − 4 of them are shown. */
    val recents: Int,
    val recentGap: Float,
    val centerY: Float,
    val scale: Float = 1f,
    val centred: Boolean = true,
)

/** The four Dock apps; recent apps are extra slots after a hairline. */
private const val DOCK_APPS = 4
/**
 * Round 4 (owner item 8 / review r3): the Dock is fitted, never overlapped. Like the iPad Dock, which
 * shrinks its icons as apps are added, a Dock that would not fit centred between the status block and
 * Search scales uniformly (icon, pitch, inset and width keep Apple's proportions). Recent apps only stay
 * while that costs at most 15 %; otherwise they overflow first, as iPhone Duo's vertical bars collapse
 * items "on the outer display in landscape … because there's less vertical space" (Tech Talk 111462).
 */
internal const val DOCK_MIN_FIT_SCALE = .85f
/** The four apps themselves never shrink below this; a window too short even for that clamps instead. */
private const val DOCK_MIN_APPS_SCALE = .6f

/** Fits this Dock centred on [SideDockSpec.centerY] between [minTop] and [maxBottom] (see [DOCK_MIN_FIT_SCALE]). */
internal fun SideDockSpec.fit(minTop: Float, maxBottom: Float, centerX: Float = this.centerX): DuoDock {
    val room = if (centred) 2f * minOf(centerY - minTop, maxBottom - centerY) else maxBottom - minTop
    // Apple's length of the four apps + n recents without the hairline (2 × inset = width − icon).
    fun length(n: Int) = (DOCK_APPS + n - 1) * pitch + icon + (width - icon).coerceAtLeast(0f)
    var shown = recents.coerceAtLeast(0)
    var s: Float
    while (true) {
        val gap = if (shown > 0) recentGap else 0f
        s = minOf(scale, (room - gap) / length(shown))
        if (shown == 0 || s >= DOCK_MIN_FIT_SCALE * scale) break
        shown--
    }
    s = s.coerceAtLeast(DOCK_MIN_APPS_SCALE * scale)
    val gap = if (shown > 0) recentGap else 0f
    val dockIcon = icon * s
    val dockPitch = pitch * s
    val dockWidth = width * s
    val padding = ((dockWidth - dockIcon) / 2f).coerceAtLeast(0f)
    val height = (DOCK_APPS + shown - 1) * dockPitch + dockIcon + 2f * padding + gap
    val top = clampInterval(centerY - height / 2f, height, minTop, maxBottom)
    return DuoDock(DuoBox(centerX - dockWidth / 2f, top, centerX + dockWidth / 2f, top + height), dockIcon, dockPitch,
        padding, false, DOCK_APPS + shown, gap)
}

/** The side-column Dock re-fitted to this frame's (possibly moved) status block and Search button. */
private fun DuoHomeFrame.refitSideDock(spec: SideDockSpec, centerX: Float = dock.box.centerX): DuoHomeFrame =
    copy(sideDock = spec, dock = spec.fit(status.bounds.bottom + COLUMN_SPACING_DP, search.top - COLUMN_SPACING_DP, centerX))

private fun sideColumnFrame(pose: DuoHomePose, w: Float, h: Float, grid: DuoGrid, status: DuoStatus, search: DuoBox,
    dots: DuoPageDots, label: Float, spec: SideDockSpec): DuoHomeFrame {
    val dock = spec.fit(status.bounds.bottom + COLUMN_SPACING_DP, search.top - COLUMN_SPACING_DP)
    return DuoHomeFrame(pose, w, h, grid, dock, status, search, dots, label, LABEL_SP, spec)
}

/** Keep [top, top + size] inside [min, max] when it fits, otherwise centre it in that interval. */
private fun clampInterval(top: Float, size: Float, min: Float, max: Float): Float =
    if (max - min <= size) min + (max - min - size) / 2f else top.coerceIn(min, max - size)

private fun coverPortrait(w: Float, h: Float, label: Float, extra: Int, extraGap: Float): DuoHomeFrame {
    val icon = DuoCover.ICON * w
    val pitch = DuoCover.COLUMN_PITCH * w
    val grid = DuoGrid(GRID_COLUMNS, GRID_ROWS, icon, pitch, DuoCover.ROW_PITCH * h,
        cellLeft = DuoCover.ICON_LEFT * w - (pitch - icon) / 2f, firstRowTop = DuoCover.FIRST_ROW_TOP * h,
        pageStride = w, widgetSide = DuoCover.WIDGET * w)
    val columnX = DuoCover.COLUMN_X * w
    val status = DuoStatus(columnX, DuoCover.TIME_Y * h, columnX, DuoCover.RING_Y * h, DuoCover.RING * w,
        DuoCover.TIME_SIZE * w, horizontal = false)
    val search = DuoBox.centered(columnX, DuoCover.SEARCH_Y * h, DuoCover.SEARCH * w, DuoCover.SEARCH * w)
    val dock = SideDockSpec(columnX, (DuoCover.DOCK_RIGHT - DuoCover.DOCK_LEFT) * w, DuoCover.DOCK_ICON * w,
        DuoCover.DOCK_PITCH * h, extra, extraGap, DOCK_CENTER_FRACTION * h)
    val dots = DuoPageDots(grid.iconLeft + grid.iconGridWidth / 2f, DuoCover.DOTS_Y * h,
        DuoCover.DOT * w, DuoCover.DOT_PITCH * w)
    return sideColumnFrame(DuoHomePose.COVER_PORTRAIT, w, h, grid, status, search, dots, label, dock)
}

/**
 * Unfolded edge→grid and grid→grid gaps (dp). Apple's frame has three equal 7.8 %W gaps, which puts the
 * page after the right-hand one exactly under the Dock column: a rigid strip then shows it beside the Dock
 * at rest, and every way of hiding it was wrong for the owner — a catch-up made the right page "chase" the
 * left one (rounds 3–4), a clip at the Dock edge made icons appear in mid-screen instead of from beyond it
 * (round 5). So the gap between the two pages is as wide as it must be for that next page's cell grid (and
 * its jiggle-badge margin) to rest just beyond the screen edge; the grid keeps the cover's own 29 dp to the
 * Dock, and the edge gap takes the rest. Every page then moves exactly with the finger at one constant
 * spacing and slides in from outside the screen; the crease falls in the gap between the pages.
 */
internal fun innerSpreadGaps(w: Float, icon: Float, pitch: Float): Pair<Float, Float> {
    val iconGrid = 3f * pitch + icon
    val cellInset = (pitch - icon) / 2f
    val dockGap = DuoInner.DOCK_GAP * w
    // cellLeft + 2 · (iconGrid + hinge) − margin ≥ w, with edge + 2 · iconGrid + hinge + dockGap = Dock left.
    val hinge = (w + PANE_DRAW_MARGIN_DP + cellInset + dockGap - DuoInner.DOCK_LEFT * w).coerceAtLeast(DuoInner.GAP * w)
    val edge = DuoInner.DOCK_LEFT * w - dockGap - 2f * iconGrid - hinge
    return edge to hinge
}

private fun innerLandscape(w: Float, h: Float, label: Float, extra: Int, extraGap: Float): DuoHomeFrame {
    val icon = DuoInner.ICON * w
    val pitch = DuoInner.COLUMN_PITCH * w
    val iconGrid = 3f * pitch + icon
    val (gap, hinge) = innerSpreadGaps(w, icon, pitch)
    val grid = DuoGrid(GRID_COLUMNS, GRID_ROWS, icon, pitch, DuoInner.ROW_PITCH * h,
        cellLeft = gap - (pitch - icon) / 2f, firstRowTop = DuoInner.FIRST_ROW_TOP * h,
        pageStride = iconGrid + hinge, widgetSide = DuoInner.WIDGET * w)
    val columnX = DuoInner.COLUMN_X * w
    val status = DuoStatus(columnX, DuoInner.TIME_Y * h, columnX, DuoInner.RING_Y * h, DuoInner.RING * w,
        DuoInner.TIME_SIZE * w, horizontal = false)
    val search = DuoBox.centered(columnX, DuoInner.SEARCH_Y * h, DuoInner.SEARCH * w, DuoInner.SEARCH * w)
    val dock = SideDockSpec(columnX, (DuoInner.DOCK_RIGHT - DuoInner.DOCK_LEFT) * w, DuoInner.DOCK_ICON * w,
        DuoInner.DOCK_PITCH * h, extra, extraGap, DOCK_CENTER_FRACTION * h)
    // Centred under BOTH grids (≈45.5 %W), not under the right page.
    val dots = DuoPageDots(gap + (grid.pageStride + iconGrid) / 2f, DuoInner.DOTS_Y * h,
        DuoInner.DOT * w, DuoInner.DOT_PITCH * w)
    return sideColumnFrame(DuoHomePose.INNER_LANDSCAPE, w, h, grid, status, search, dots, label, dock)
}

/**
 * Cover rotated: the vertical control column stays on the right (HIG); the page's 24 cells are
 * presented as six columns by four rows. Sizes are the cover's physical portrait sizes.
 * Round 4: the panel is only 425 dp tall, so Apple's 4-app Dock (242 dp) cannot sit centred below the
 * status block: it is fitted ([SideDockSpec.fit], ≈0.88×) and recent apps overflow (Tech Talk 111462).
 */
private fun coverLandscape(w: Float, h: Float, label: Float, extra: Int, extraGap: Float): DuoHomeFrame {
    val short = minOf(w, h)
    val long = maxOf(w, h)
    val icon = DuoCover.ICON * short
    val columnX = w - (1f - DuoCover.COLUMN_X) * short
    // Without a camera above it the time starts like the unfolded column (40 dp, level with the first
    // icon row); the ring keeps the cover's own time→ring rhythm (5.1 %H of the portrait cover, 31.7 dp).
    val timeY = DuoInner.TIME_Y * 608f / 425f * short
    val status = DuoStatus(columnX, timeY, columnX, timeY + (DuoCover.RING_Y - DuoCover.TIME_Y) * long,
        DuoCover.RING * short, DuoCover.TIME_SIZE * short, horizontal = false)
    val search = DuoBox.centered(columnX, h - (1f - DuoCover.SEARCH_Y) * long, DuoCover.SEARCH * short, DuoCover.SEARCH * short)
    val spec = SideDockSpec(columnX, (DuoCover.DOCK_RIGHT - DuoCover.DOCK_LEFT) * short, DuoCover.DOCK_ICON * short,
        DuoCover.DOCK_PITCH * long, extra, extraGap, DOCK_CENTER_FRACTION * h)
    val dock = spec.fit(status.bounds.bottom + COLUMN_SPACING_DP, search.top - COLUMN_SPACING_DP)
    val rows = LANDSCAPE_ROWS
    val columns = HOME_CELLS / rows
    val dotsY = h - (1f - DuoCover.DOTS_Y) * long
    val dot = DuoCover.DOT * short
    val firstRowTop = .075f * h
    // Rows keep the portrait pitch unless four rows plus labels would reach the page dots.
    val rowPitch = minOf(DuoCover.ROW_PITCH * long,
        ((dotsY - dot - 8f - firstRowTop - icon - label) / (rows - 1)).coerceAtLeast(icon))
    // The grid stops at Apple's Dock column, whatever size the fitted glass ends up.
    val available = columnX - spec.width / 2f
    val margin = .059f * short
    val pitch = minOf(DuoCover.COLUMN_PITCH * short,
        ((available - 2f * margin - icon) / (columns - 1)).coerceAtLeast(icon + 8f))
    val iconGrid = (columns - 1) * pitch + icon
    val iconLeft = ((available - iconGrid) / 2f).coerceAtLeast(0f)
    val grid = DuoGrid(columns, rows, icon, pitch, rowPitch, cellLeft = iconLeft - (pitch - icon) / 2f,
        firstRowTop = firstRowTop, pageStride = w,
        widgetSide = minOf(DuoCover.WIDGET * short, 2f * rowPitch - 8f, 2f * pitch - 8f))
    val dots = DuoPageDots(iconLeft + iconGrid / 2f, dotsY, dot, DuoCover.DOT_PITCH * short)
    return DuoHomeFrame(DuoHomePose.COVER_LANDSCAPE, w, h, grid, dock, status, search, dots, label, LABEL_SP, spec)
}

/**
 * Fractions measured on the unfolded Duo held in PORTRAIT (W×H = 608×860 dp on the Xiaomi). Apple publishes
 * no straight-on Home Screen of this pose (HIG, newsroom and the product page only show it in landscape or
 * with apps), so these come from the owner's photo reference/user-photos-r8/6.webp rectified by a homography
 * fitted to the four display edges (evidence/r8-layout/ref/p6_rect.png, 2 px per dp; icons come out square
 * to ±1 px, which checks the rectification). Icons, Dock icons, ring, Search and widgets keep the panel's
 * physical sizes ([DuoInner]); only the placement is the portrait's own.
 */
internal object DuoInnerPortrait {
    const val COLUMN_PITCH = .1587f   // of W: icon centres 159.5 / 256 / 352.5 / 449 dp → 96.5 dp
    const val FIRST_ROW_TOP = .0849f  // of H: 73 dp
    const val ROW_PITCH = .1241f      // of H: 106.75 dp (rows 1–5 centres 100.5 → 527.5 dp)
    const val COLUMN_X = .9285f       // of W: time/ring/Search centre, 43.5 dp from the edge (landscape: 44 dp)
    const val TIME_Y = .043f          // of H: 37 dp
    const val RING_Y = .0814f         // of H: 70 dp
    const val DOTS_Y = .8326f         // of H: 716 dp, centred on the panel (= centred on the grid)
    const val DOCK_PITCH = .1151f     // of W: 70 dp between Dock icon centres
    const val DOCK_BOTTOM_INSET = .0099f // of H: glass bottom 8.5 dp above the panel edge
}

/**
 * Unfolded in portrait the Duo returns to horizontal bars (Tech Talk 111462) — but its Home Screen is
 * strongly centred, not iPhone-wide (owner, round 8, photo 6): a centred 4-column grid with wide iPad-like
 * gaps, the floating glass Dock hugging four icons at the bottom centre, Search at the bottom right level
 * with the Dock, the page dots centred above the Dock, and the status block (time over ring) in the top-right
 * corner exactly as on the other panels — the status is not spread across a top bar.
 */
private fun innerPortrait(w: Float, h: Float, label: Float, extra: Int, extraGap: Float): DuoHomeFrame {
    val long = maxOf(w, h)
    val icon = DuoInner.ICON * long
    // A window narrower than the Xiaomi panel tightens the gaps before the grid ever touches the edges.
    val pitch = minOf(DuoInnerPortrait.COLUMN_PITCH * w, (w - 2f * 16f) / GRID_COLUMNS).coerceAtLeast(icon)
    val cellLeft = (w - GRID_COLUMNS * pitch) / 2f
    val firstRowTop = DuoInnerPortrait.FIRST_ROW_TOP * h
    val slots = 4 + extra
    val gap = if (extra > 0) extraGap else 0f
    // Home-size artwork and the Duo Dock's own inset (photo 6: 12.5 dp around a 54 dp icon = 0.23).
    val dockIcon = icon
    val padding = FLOATING_DOCK_INSET * dockIcon
    val edge = 16f
    val dockPitch = minOf(DuoInnerPortrait.DOCK_PITCH * w,
        ((w - 2f * edge - 2f * padding - dockIcon - gap) / (slots - 1).coerceAtLeast(1)).coerceAtLeast(dockIcon))
    val dockHeight = dockIcon + 2f * padding
    val dockBottom = h - DuoInnerPortrait.DOCK_BOTTOM_INSET * h
    val dockWidth = (slots - 1) * dockPitch + dockIcon + 2f * padding + gap
    val dockBox = DuoBox(w / 2f - dockWidth / 2f, dockBottom - dockHeight, w / 2f + dockWidth / 2f, dockBottom)
    val dock = DuoDock(dockBox, dockIcon, dockPitch, padding, horizontal = true, slots = slots, extraGap = gap)
    val columnX = DuoInnerPortrait.COLUMN_X * w
    val status = DuoStatus(columnX, DuoInnerPortrait.TIME_Y * h, columnX, DuoInnerPortrait.RING_Y * h,
        DuoInner.RING * long, DuoInner.TIME_SIZE * long, horizontal = false)
    val searchSize = DuoInner.SEARCH * long
    val search = DuoBox.centered(columnX, dockBox.centerY, searchSize, searchSize)
    val dots = DuoPageDots(w / 2f, DuoInnerPortrait.DOTS_Y * h, DuoInner.DOT * long, DuoInner.DOT_PITCH * long)
    // Six rows end above the page dots on any window (the Xiaomi leaves 33 dp).
    val lastLabelBottom = dots.centerY - dots.dot / 2f - 8f
    val rowPitch = minOf(DuoInnerPortrait.ROW_PITCH * h,
        ((lastLabelBottom - firstRowTop - icon - label) / (GRID_ROWS - 1)).coerceAtLeast(icon))
    // Widgets keep the inner panel's physical card (iPadOS keeps widget sizes across orientations).
    val widgetSide = minOf(DuoInner.WIDGET * long, 2f * pitch - 8f, 2f * rowPitch - 8f)
    val grid = DuoGrid(GRID_COLUMNS, GRID_ROWS, icon, pitch, rowPitch, cellLeft, firstRowTop, pageStride = w,
        widgetSide = widgetSide)
    return DuoHomeFrame(DuoHomePose.INNER_PORTRAIT, w, h, grid, dock, status, search, dots, label, LABEL_SP)
}

/**
 * Applies the legacy Dock preferences on top of Apple's Dock: the width slider scales the whole
 * Dock (68 = Apple's size) and "align with app rows" off places its centre at the chosen height.
 * Defaults return the frame unchanged. The horizontal inner-portrait bar is not affected.
 */
fun DuoHomeFrame.withDockPreference(preset: LayoutPreset): DuoHomeFrame {
    val p = preset.sanitized()
    val scale = p.dockWidth / LayoutPreset().dockWidth
    val spec = sideDock
    if (dock.horizontal || spec == null || (scale == 1f && p.dockAlignToGrid)) return this
    // A larger Dock is still fitted (never over the status block or Search); a chosen height is kept
    // as near as the column allows.
    return refitSideDock(spec.copy(scale = scale, centred = p.dockAlignToGrid,
        centerY = if (p.dockAlignToGrid) spec.centerY else p.dockPosition * height))
}

/** Cover landscape presents the 24 cells of a page as six by four. */
const val LANDSCAPE_ROWS = 4

/**
 * Where a page's portrait cells and widgets appear when the cover is rotated. Like iOS, a page
 * is treated as an ordered list flowed into the wider grid: each widget keeps its reading-order
 * position (portrait index n → landscape index n, or the next spot where it fits), then every
 * remaining cell — empty ones included, so intentional gaps survive — fills the free positions in
 * reading order. Stored addresses never change. Returns null when the widgets cannot be packed
 * (e.g. taller than four rows); the caller then keeps the portrait grid.
 */
data class LandscapeFlow(
    val columns: Int,
    val rows: Int,
    private val cells: Map<Int, Pair<Int, Int>>,
    private val widgets: Map<Int, Pair<Int, Int>>,
) {
    /** (column, row) of portrait cell [local], or null when a widget covers it. */
    fun cell(local: Int): Pair<Int, Int>? = cells[local]
    /** (column, row) of the widget in [slot]. */
    fun widget(slot: Int): Pair<Int, Int>? = widgets[slot]
}

fun landscapeFlow(pageWidgets: List<WidgetPlacement>, columns: Int = HOME_CELLS / LANDSCAPE_ROWS,
    rows: Int = LANDSCAPE_ROWS): LandscapeFlow? {
    if (columns <= 0 || rows <= 0 || columns * rows < HOME_CELLS) return null
    val visible = pageWidgets.filter { it.row < GRID_ROWS }.distinctBy { it.slot }
        .sortedWith(compareBy({ it.row }, { it.column }, { it.slot }))
    val covered = mutableSetOf<Int>()
    visible.forEach { w ->
        for (y in 0 until w.spanY) for (x in 0 until w.spanX) {
            if (w.row + y < GRID_ROWS && w.column + x < GRID_COLUMNS) covered += (w.row + y) * GRID_COLUMNS + w.column + x
        }
    }
    val occupied = BooleanArray(columns * rows)
    fun fits(position: Int, spanX: Int, spanY: Int): Boolean {
        val column = position % columns
        val row = position / columns
        if (column + spanX > columns || row + spanY > rows) return false
        for (y in 0 until spanY) for (x in 0 until spanX) if (occupied[(row + y) * columns + column + x]) return false
        return true
    }
    val placed = HashMap<Int, Pair<Int, Int>>()
    for (w in visible) {
        if (w.spanX > columns || w.spanY > rows) return null
        val preferred = (w.row * GRID_COLUMNS + w.column).coerceAtMost(columns * rows - 1)
        val position = ((preferred until columns * rows) + (0 until preferred)).firstOrNull { fits(it, w.spanX, w.spanY) }
            ?: return null
        val column = position % columns
        val row = position / columns
        for (y in 0 until w.spanY) for (x in 0 until w.spanX) occupied[(row + y) * columns + column + x] = true
        placed[w.slot] = column to row
    }
    val free = (0 until columns * rows).filterNot { occupied[it] }.iterator()
    val cells = HashMap<Int, Pair<Int, Int>>()
    for (local in 0 until HOME_CELLS) {
        if (local in covered) continue
        if (!free.hasNext()) return null
        val position = free.next()
        cells[local] = position % columns to position / columns
    }
    return LandscapeFlow(columns, rows, cells, placed)
}

// ---------------------------------------------------------------------------------------------
// Camera avoidance: move only what actually intersects a real cutout, by the minimum amount.
// ---------------------------------------------------------------------------------------------

/** Clearance kept between a camera cutout and text/controls moved away from it. */
internal const val CUTOUT_CLEARANCE_DP = 6f
/** Icons are only nudged when the camera truly overlaps their artwork. */
private const val GRID_CUTOUT_CLEARANCE_DP = 2f

fun DuoHomeFrame.avoidingCutouts(cutouts: List<DuoBox>): DuoHomeFrame {
    val real = cutouts.filter { it.width > 0f && it.height > 0f }
    if (real.isEmpty()) return this
    var frame = this
    frame = frame.withGridAvoiding(real)
    frame = if (pose.sideColumn) frame.withSideColumnAvoiding(real) else frame.withBottomBarsAvoiding(real)
    frame = frame.withDotsAvoiding(real)
    return frame
}

private fun DuoBox.attachedLeft(width: Float) = left <= 1f && right < width / 2f
private fun DuoBox.attachedRight(width: Float) = right >= width - 1f && left > width / 2f
private fun DuoBox.attachedTop() = top <= 1f

/** Icon artwork + label rectangles of every cell visible at rest (both halves when unfolded). */
private fun DuoHomeFrame.restingIconBoxes(): List<DuoBox> {
    val pages = if (pose.spread) 2 else 1
    return buildList {
        repeat(pages) { page ->
            repeat(grid.rows) { row -> repeat(grid.columns) { column ->
                val left = grid.iconLeft + page * grid.pageStride + column * grid.columnPitch
                val top = grid.rowTop(row)
                add(DuoBox(left, top, left + grid.icon, top + grid.icon + labelHeight))
            } }
        }
    }
}

private fun DuoHomeFrame.withGridAvoiding(cutouts: List<DuoBox>): DuoHomeFrame {
    val icons = restingIconBoxes()
    var dx = 0f
    var dy = 0f
    for (cutout in cutouts) {
        val hits = icons.filter { it.intersects(cutout, GRID_CUTOUT_CLEARANCE_DP) }
        if (hits.isEmpty()) continue
        when {
            cutout.attachedLeft(width) -> dx = maxOf(dx, cutout.right + GRID_CUTOUT_CLEARANCE_DP - hits.minOf { it.left })
            cutout.attachedRight(width) -> dx = minOf(dx, cutout.left - GRID_CUTOUT_CLEARANCE_DP - hits.maxOf { it.right })
            cutout.attachedTop() -> dy = maxOf(dy, cutout.bottom + GRID_CUTOUT_CLEARANCE_DP - hits.minOf { it.top })
        }
    }
    if (dx == 0f && dy == 0f) return this
    val rows = grid.rows
    // A top camera pushes the rows down; the pitch tightens just enough to keep the last label in place.
    val pitch = if (dy > 0f) (grid.rowPitch - dy / (rows - 1)).coerceAtLeast(grid.icon) else grid.rowPitch
    return copy(grid = grid.copy(cellLeft = grid.cellLeft + dx, firstRowTop = grid.firstRowTop + dy, rowPitch = pitch),
        dots = dots.copy(centerX = dots.centerX + dx))
}

/** Vertical shift (minimum magnitude) that moves [box] clear of [cutout] while staying in [min, max]. */
private fun verticalEscape(box: DuoBox, cutout: DuoBox, min: Float, max: Float, clearance: Float): Float? {
    val down = cutout.bottom + clearance - box.top
    val up = cutout.top - clearance - box.bottom
    return listOf(down, up).filter { box.top + it >= min - .01f && box.bottom + it <= max + .01f }
        .minByOrNull(::abs)
}

private fun DuoHomeFrame.withSideColumnAvoiding(cutouts: List<DuoBox>): DuoHomeFrame {
    var status = status
    var dock = dock
    var search = search
    var columnShift = 0f
    // 1. Status: a camera above or inside it pushes the time+ring block down as one unit.
    cutouts.filter { status.bounds.intersects(it, CUTOUT_CLEARANCE_DP) }.forEach { cutout ->
        val dy = if (cutout.centerY <= status.bounds.centerY) cutout.bottom + CUTOUT_CLEARANCE_DP - status.bounds.top
            else (cutout.top - CUTOUT_CLEARANCE_DP - status.bounds.bottom).coerceAtLeast(-status.bounds.top)
        status = status.shifted(0f, dy)
    }
    // 2. Search: vertical escape within the lower part of the column.
    cutouts.filter { search.intersects(it, CUTOUT_CLEARANCE_DP) }.forEach { cutout ->
        verticalEscape(search, cutout, dock.box.bottom + 8f, height, CUTOUT_CLEARANCE_DP)?.let { search = search.shifted(dy = it) }
    }
    // 3. Dock: re-fitted (centred, scaled if it must be) below the moved status block and above Search,
    //    then out of the way of the camera; if no vertical slot is free, the whole column moves inward
    //    instead (camera on the long edge of a rotated cover).
    val minTop = status.bounds.bottom + COLUMN_SPACING_DP
    val maxBottom = search.top - COLUMN_SPACING_DP
    dock = sideDock?.fit(minTop, maxBottom, dock.box.centerX) ?: run {
        val top = clampInterval(dock.box.top, dock.box.height, minTop, maxBottom)
        dock.copy(box = dock.box.shifted(dy = top - dock.box.top))
    }
    cutouts.filter { dock.box.intersects(it, CUTOUT_CLEARANCE_DP) }.forEach { cutout ->
        val dy = verticalEscape(dock.box, cutout, minTop, maxBottom, CUTOUT_CLEARANCE_DP)
        if (dy != null) dock = dock.copy(box = dock.box.shifted(dy = dy))
        else if (cutout.attachedRight(width) || cutout.centerX > dock.box.centerX)
            columnShift = minOf(columnShift, cutout.left - CUTOUT_CLEARANCE_DP - dock.box.right)
        else columnShift = maxOf(columnShift, cutout.right + CUTOUT_CLEARANCE_DP - dock.box.left)
    }
    var frame = copy(status = status, dock = dock, search = search)
    if (columnShift != 0f) {
        frame = frame.copy(status = status.shifted(columnShift, 0f),
            dock = dock.copy(box = dock.box.shifted(dx = columnShift)), search = search.shifted(dx = columnShift))
        // The cover landscape grid re-centres in the narrower space; the cover portrait grid already clears.
        if (pose == DuoHomePose.COVER_LANDSCAPE) {
            val g = frame.grid
            val available = frame.dock.box.centerX - (sideDock?.width ?: frame.dock.box.width) / 2f
            val pitch = minOf(g.columnPitch, ((available - 2f * 16f - g.icon) / (g.columns - 1)).coerceAtLeast(g.icon + 8f))
            val iconGrid = (g.columns - 1) * pitch + g.icon
            val iconLeft = ((available - iconGrid) / 2f).coerceAtLeast(0f)
            frame = frame.copy(grid = g.copy(columnPitch = pitch, cellLeft = iconLeft - (pitch - g.icon) / 2f,
                widgetSide = minOf(g.widgetSide, 2f * pitch - 8f)),
                dots = frame.dots.copy(centerX = iconLeft + iconGrid / 2f))
        }
    }
    return frame
}

/**
 * Inner portrait: the top-right status block moves down as one unit (as on the other panels), the bottom
 * bar's glass is trimmed or lifted, and Search takes the shortest way out (the Xiaomi's hole is in the
 * bottom-right corner when the open phone is turned clockwise: lifting Search 31 dp beats 44 dp sideways).
 */
private fun DuoHomeFrame.withBottomBarsAvoiding(cutouts: List<DuoBox>): DuoHomeFrame {
    var status = status
    fun escapeX(box: DuoBox, cutout: DuoBox): Float =
        if (cutout.centerX < box.centerX) cutout.right + CUTOUT_CLEARANCE_DP - box.left
        else cutout.left - CUTOUT_CLEARANCE_DP - box.right
    cutouts.forEach { cutout ->
        if (!status.horizontal) {
            if (status.bounds.intersects(cutout, CUTOUT_CLEARANCE_DP)) {
                val dy = if (cutout.centerY <= status.bounds.centerY) cutout.bottom + CUTOUT_CLEARANCE_DP - status.bounds.top
                    else (cutout.top - CUTOUT_CLEARANCE_DP - status.bounds.bottom).coerceAtLeast(-status.bounds.top)
                status = status.shifted(0f, dy)
            }
            return@forEach
        }
        if (status.timeBox.intersects(cutout, CUTOUT_CLEARANCE_DP)) {
            val dx = escapeX(status.timeBox, cutout)
            status = status.copy(timeCenterX = status.timeCenterX + dx)
        }
        if (status.ringBox.intersects(cutout, CUTOUT_CLEARANCE_DP)) {
            val dx = escapeX(status.ringBox, cutout)
            status = status.copy(ringCenterX = status.ringCenterX + dx)
        }
    }
    var dockBox = dock.box
    var search = search
    // The icons stay under their grid columns; only the glass edge is trimmed when the camera sits
    // beyond the outermost icon, otherwise the whole bar rises above it.
    val iconsLeft = dock.contentCenterX - dock.iconSpan / 2f
    val iconsRight = dock.contentCenterX + dock.iconSpan / 2f
    val edgeMargin = 4f
    cutouts.filter { dockBox.intersects(it, CUTOUT_CLEARANCE_DP) }.forEach { cutout ->
        dockBox = when {
            cutout.centerX > dockBox.centerX && cutout.left - CUTOUT_CLEARANCE_DP >= iconsRight + edgeMargin ->
                dockBox.copy(right = cutout.left - CUTOUT_CLEARANCE_DP)
            cutout.centerX <= dockBox.centerX && cutout.right + CUTOUT_CLEARANCE_DP <= iconsLeft - edgeMargin ->
                dockBox.copy(left = cutout.right + CUTOUT_CLEARANCE_DP)
            else -> dockBox.shifted(dy = cutout.top - CUTOUT_CLEARANCE_DP - dockBox.bottom)
        }
    }
    cutouts.filter { search.intersects(it, CUTOUT_CLEARANCE_DP) }.forEach { cutout ->
        val dx = escapeX(search, cutout)
        val dy = verticalEscape(search, cutout, dots.centerY + dots.dot, height, CUTOUT_CLEARANCE_DP)
        search = if (dy != null && abs(dy) < abs(dx)) search.shifted(dy = dy) else search.shifted(dx = dx)
    }
    return copy(status = status, dock = dock.copy(box = dockBox), search = search)
}

private fun DuoHomeFrame.withDotsAvoiding(cutouts: List<DuoBox>): DuoHomeFrame {
    val box = DuoBox.centered(dots.centerX, dots.centerY, dots.pitch * 4f, dots.dot * 2f)
    val hit = cutouts.firstOrNull { box.intersects(it, CUTOUT_CLEARANCE_DP) } ?: return this
    return copy(dots = dots.copy(centerY = hit.top - CUTOUT_CLEARANCE_DP - dots.dot))
}

// ---------------------------------------------------------------------------------------------
// Adapters for the existing Home renderer.
// ---------------------------------------------------------------------------------------------

/**
 * Builds the renderer's [HomeGeometry] from the frame. Rows are uniform, so the legacy
 * "two widget rows + app rows" fields collapse to one pitch. The icon-size preference scales
 * only the artwork (68 = Apple's size); the row-spacing preference nudges the pitch while the
 * six rows still end above the page dots.
 */
fun DuoHomeFrame.toHomeGeometry(preset: LayoutPreset = LayoutPreset(), labels: Boolean = true): HomeGeometry {
    val p = preset.sanitized()
    val g = grid
    val artwork = g.icon * (p.iconSize / LayoutPreset().iconSize)
    val text = if (labels) labelHeight else 0f
    // The last label must end above the page dots (Apple leaves ≈7 dp unfolded); larger text or a
    // wider row-spacing preference never pushes it further.
    val rowsLimit = (dots.centerY - dots.dot / 2f - 4f - g.firstRowTop - maxOf(48f, artwork + text)) / (g.rows - 1)
    val pitch = minOf(g.rowPitch + (p.rowGap - LayoutPreset().rowGap) * .5f, rowsLimit)
        .coerceAtLeast(maxOf(48f, minOf(g.rowPitch, artwork + text)))
    val metrics = HomeMetrics(panelWidth = width, scale = g.icon / (DuoCover.ICON * SHARED_HOME_WIDTH_DP),
        iconSize = g.icon, columnGap = g.columnPitch - g.icon, rowPitch = pitch, labelSp = labelSp,
        labelHeight = labelHeight, dockHeight = dock.box.height, dockInset = dock.padding, dockRadius = dock.cornerRadius,
        smallWidgetTrim = 2f * g.columnPitch - g.widgetSide, columns = g.columns)
    return HomeGeometry(
        expanded = pose.spread,
        homeWidth = g.cellGridWidth,
        gridWidth = g.cellGridWidth,
        iconSize = artwork,
        rowHeight = pitch,
        widgetHeight = 2f * pitch - 18f,
        contentTop = g.firstRowTop,
        dockTop = dock.box.top,
        dockHeight = dock.box.height,
        dockRowHeight = dock.pitch,
        adaptive = true,
        columns = g.columns,
        requestedIconSize = artwork,
        labelHeight = text,
        pageGutter = 0f,
        topRowHeight = pitch,
        referenceCardSize = g.widgetSide,
        shortcutArtworkSize = artwork,
        metrics = metrics,
    )
}
