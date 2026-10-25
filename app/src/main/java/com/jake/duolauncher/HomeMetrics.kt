package com.jake.duolauncher

import androidx.compose.foundation.shape.RoundedCornerShape

/** App icon corner radius as a fraction of the icon side (iOS 22.4 %). */
internal const val IOS_ICON_CORNER_FRACTION = .224f
/** Column gaps compress before artwork when a panel is narrower than the Duo's. */
internal const val MIN_COLUMN_GAP_DP = 16f
internal const val MIN_ICON_DP = 48f
/** Cover height ÷ width (Xiaomi 1712 / 1168 px; the Duo's is 1.455); rows are fractions of the height. */
private const val DUO_COVER_ASPECT = 1712f / 1168f

/**
 * Single source of truth for Home dimensions that only depend on a panel width: the iPhone Duo
 * cover measurements (icon 12.7 %W, column pitch 18.9 %W, row pitch 14.55 %H, small widget
 * 33.7 %W, label 11 sp), replacing Core.16's generic 393 pt iPhone table. Pose-specific
 * placement (rows anchored at the top, Dock column, status, search, page dots) lives in
 * [duoHomeFrame]; [panelWidth] is the logical panel the grid belongs to.
 */
data class HomeMetrics(
    val panelWidth: Float,
    val scale: Float,
    val iconSize: Float,
    val columnGap: Float,
    val rowPitch: Float,
    val labelSp: Float,
    val labelHeight: Float,
    val dockHeight: Float,
    val dockInset: Float,
    val dockRadius: Float,
    val smallWidgetTrim: Float,
    val columns: Int = GRID_COLUMNS,
) {
    val columnPitch: Float get() = iconSize + columnGap
    val gridWidth: Float get() = columns * columnPitch
    /** Rendered side of a 2×2 card: two pitches minus the trim (33.7 %W on the Duo cover). */
    val smallWidget: Float get() = 2f * columnPitch - smallWidgetTrim
    val iconCornerRadius: Float get() = iconSize * IOS_ICON_CORNER_FRACTION

    /** Keep this exact icon, trading column gap first, so stored cells never change. */
    fun fitted(availableGridWidth: Float): HomeMetrics {
        if (!availableGridWidth.isFinite() || availableGridWidth <= 0f || gridWidth <= availableGridWidth) return this
        val pitch = availableGridWidth / columns
        val gap = (pitch - iconSize).coerceAtLeast(0f)
        if (gap >= MIN_COLUMN_GAP_DP) return copy(columnGap = gap)
        return copy(iconSize = (pitch - MIN_COLUMN_GAP_DP).coerceAtLeast(minOf(iconSize, MIN_ICON_DP)),
            columnGap = minOf(MIN_COLUMN_GAP_DP, pitch - minOf(iconSize, MIN_ICON_DP)).coerceAtLeast(0f))
    }
}

fun homeMetrics(panelWidthDp: Float, availableGridWidthDp: Float = Float.POSITIVE_INFINITY): HomeMetrics {
    val width = panelWidthDp.takeIf { it.isFinite() && it > 0f } ?: SHARED_HOME_WIDTH_DP
    val height = width * DUO_COVER_ASPECT
    val icon = DuoCover.ICON * width
    val dockIcon = DuoCover.DOCK_ICON * width
    val dockWidth = (DuoCover.DOCK_RIGHT - DuoCover.DOCK_LEFT) * width
    val dockPadding = (dockWidth - dockIcon) / 2f
    return HomeMetrics(
        panelWidth = width,
        scale = width / SHARED_HOME_WIDTH_DP,
        iconSize = icon,
        columnGap = (DuoCover.COLUMN_PITCH - DuoCover.ICON) * width,
        rowPitch = DuoCover.ROW_PITCH * height,
        labelSp = 11f,
        labelHeight = duoLabelHeight(),
        dockHeight = 3f * DuoCover.DOCK_PITCH * height + dockIcon + 2f * dockPadding,
        dockInset = dockPadding,
        dockRadius = dockIcon * IOS_ICON_CORNER_FRACTION + dockPadding,
        smallWidgetTrim = (2f * DuoCover.COLUMN_PITCH - DuoCover.WIDGET) * width,
    ).fitted(availableGridWidthDp)
}

/** Panel width the metrics derive from: one half of the unfolded window, otherwise the
 * window's short side so a rotated cover keeps its portrait icon size. */
fun homeMetricsPanelWidth(windowWidthDp: Float, windowHeightDp: Float, expanded: Boolean): Float {
    val w = windowWidthDp.takeIf { it.isFinite() && it > 0f } ?: SHARED_HOME_WIDTH_DP
    val h = windowHeightDp.takeIf { it.isFinite() && it > 0f } ?: SHARED_SAFE_HEIGHT_DP
    return if (expanded) w / 2f else minOf(w, h)
}

/** iOS 22.4 % continuous-curvature corner (60 % smoothing), the same mask baked into icon bitmaps. */
fun homeIconShape(sizeDp: Float): androidx.compose.ui.graphics.Shape =
    ContinuousRoundedShape(androidx.compose.ui.unit.Dp((sizeDp * IOS_ICON_CORNER_FRACTION).coerceAtLeast(1f)))
