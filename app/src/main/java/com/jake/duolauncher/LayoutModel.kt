package com.jake.duolauncher

/** Dock artwork has its own visual scale; changing Home artwork must not shrink it. */
fun dockIconSize() = 48f

const val SHARED_HOME_WIDTH_DP = 425f
const val SHARED_SAFE_HEIGHT_DP = 608f

/** Only ordinary Home pages reserve the bottom controls strip. The leading feed scrolls
 * all the way to the workspace edge, just as it can scroll past its initial top inset. */
fun homePaneViewportHeight(page: Int, contentHeight: Float, bottomControlsSpace: Float): Float =
    (contentHeight - if (page == -1) 0f else bottomControlsSpace).coerceAtLeast(0f)

/**
 * The cover is slightly taller than the shared 608dp desktop canvas. Only the
 * leading widgets page is allowed to use those centred top/bottom margins; the
 * ordinary Home surfaces keep the shared geometry used by the unfolded screen.
 */
fun coverLeadingEdgeInset(coverDisplay: Boolean, safeHeight: Float, windowHeight: Float): Float =
    if (!coverDisplay) 0f else ((windowHeight - safeHeight) / 2f).coerceAtLeast(0f)

data class LayoutPreset(
    val iconSize: Float = 68f,
    val rowGap: Float = 8f,
    val dockWidth: Float = 68f,
    val dockPosition: Float = 0.56f,
    val dockAlignToGrid: Boolean = true,
) {
    fun sanitized() = copy(
        iconSize = (if (iconSize.isFinite()) iconSize else 68f).coerceIn(48f, 68f),
        rowGap = rowGap.coerceIn(0f, 28f),
        dockWidth = dockWidth.coerceIn(56f, 84f),
        dockPosition = dockPosition.coerceIn(0.25f, 0.75f),
    )
}

data class HomeGeometry(
    val expanded: Boolean,
    val homeWidth: Float,
    val gridWidth: Float,
    val iconSize: Float,
    val rowHeight: Float,
    val widgetHeight: Float,
    val contentTop: Float,
    val dockTop: Float,
    val dockHeight: Float,
    val dockRowHeight: Float,
    val adaptive: Boolean = false,
    val columns: Int = GRID_COLUMNS,
    val requestedIconSize: Float = iconSize,
    val labelHeight: Float = 20f,
    val pageGutter: Float = 16f,
    val topRowHeight: Float = (widgetHeight + 18f) / 2f,
    val referenceCardSize: Float? = null,
    val shortcutArtworkSize: Float? = null,
    val shortcutLabelLines: Int = 1,
    val appGridHeight: Float? = null,
    /** iOS metric table the grid was derived from; null for legacy call sites. */
    val metrics: HomeMetrics? = null,
)

/** Advance beta14/beta15 defaults once; later explicit/imported values stay untouched. */
fun upgradeArtworkDefault(preset: LayoutPreset, defaultsRevision: Int): LayoutPreset {
    if (defaultsRevision >= 4) return preset
    if (defaultsRevision == 3) return if (preset.iconSize == 56f) preset.copy(iconSize = 68f) else preset
    val oldArtwork = if ((defaultsRevision == 0 && preset.iconSize == 44f) ||
        (defaultsRevision == 1 && preset.iconSize == 46f)) 48f else preset.iconSize
    val upgraded = preset.copy(iconSize = (if (oldArtwork.isFinite()) oldArtwork else 48f) + 8f).sanitized()
    return if (upgraded.iconSize == 56f) upgraded.copy(iconSize = 68f) else upgraded
}

/** User-selected icon size is the visible artwork size; spacing belongs to the cell. */
fun homeIconArtworkSize(footprint: Float, available: Float = Float.POSITIVE_INFINITY): Float {
    val nominal = (if (footprint.isFinite()) footprint else 56f).coerceIn(MIN_ICON_DP, 80f)
    val artwork = nominal.coerceAtLeast(1f)
    return minOf(artwork, if (available.isNaN() || available == Float.POSITIVE_INFINITY) artwork
        else available.coerceAtLeast(0f))
}

/** Advance old defaults without changing individually tuned values. */
fun upgradePreset(preset: LayoutPreset, schema: Int, expanded: Boolean): LayoutPreset = when {
    schema < 2 -> preset.copy(
        iconSize = if (preset.iconSize == if (expanded) 58f else 54f) 54f else preset.iconSize,
        rowGap = if (preset.rowGap == 12f) 8f else preset.rowGap,
        dockWidth = if (preset.dockWidth == 64f) 68f else preset.dockWidth,
    )
    schema == 2 && preset.iconSize == 60f -> preset.copy(iconSize = 54f)
    else -> preset
}

/**
 * Home grid geometry of a [width]×[height] window, derived from the measured iPhone Duo
 * fractions ([duoHomeFrame]). [expanded] selects the unfolded two-page spread; without a side
 * column ([sideDock] false) the window is the unfolded panel in portrait.
 */
fun homeGeometry(width: Float, height: Float, preset: LayoutPreset, labels: Boolean,
    labelHeight: Float = duoLabelHeight(), expanded: Boolean = width >= 650f,
    sideDock: Boolean = true): HomeGeometry {
    val w = width.takeIf { it.isFinite() && it > 0f } ?: SHARED_HOME_WIDTH_DP
    val h = height.takeIf { it.isFinite() && it > 0f } ?: SHARED_SAFE_HEIGHT_DP
    val pose = when {
        expanded -> DuoHomePose.INNER_LANDSCAPE
        !sideDock || minOf(w, h) >= 600f -> DuoHomePose.INNER_PORTRAIT
        w > h -> DuoHomePose.COVER_LANDSCAPE
        else -> DuoHomePose.COVER_PORTRAIT
    }
    return duoHomeFrame(pose, w, h, labelHeight).toHomeGeometry(preset, labels)
}

/** Keep stored order stable across installs, removals and configuration changes. */
fun reconcileOrder(saved: List<String>, installed: List<String>): List<String> {
    val present = installed.toSet()
    return (saved.filter { it in present } + installed).distinct()
}

/** Installing an app must never create a home-screen pin. */
fun reconcilePins(saved: List<String>, installed: List<String>): List<String> {
    val available = installed.toSet()
    return saved.filter { it in available }.distinct()
}

fun migrateHomePins(legacy: List<String>, installed: List<String>, suggested: List<String>): List<String> {
    val surviving = reconcilePins(legacy, installed)
    val oldSet = surviving.toSet()
    val wasReordered = surviving.isNotEmpty() && surviving != installed.filter { it in oldSet }
    return if (wasReordered) surviving.take(16) else reconcilePins(suggested, installed).take(16)
}

fun homePageCount(cellCount: Int) = maxOf(1, (cellCount + HOME_CELLS - 1) / HOME_CELLS)

fun moveApp(order: List<String>, id: String, offset: Int): List<String> {
    val from = order.indexOf(id)
    if (from < 0) return order
    val to = (from + offset).coerceIn(0, order.lastIndex)
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/** HIG: the vertical control column (status, Dock, Search) stays on the side in every pose
 * except the unfolded display in portrait, which returns to horizontal bars. */
internal fun desktopSideDock(cover: Boolean, landscape: Boolean): Boolean = cover || landscape
