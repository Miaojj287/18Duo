package com.jake.duolauncher

import kotlin.math.roundToInt

/**
 * Size of the status rail: the time above the connectivity ring. Only the visual size and the
 * time-to-ring rhythm matter; the rail's position comes from [DuoStatus] (measured fractions).
 */
internal data class StatusRailGeometry(
    val visualSize: Float,
    val timeFontSize: Float,
    val detailFontSize: Float,
    val clockHeight: Float,
    val detailHeight: Float,
    val infoHeight: Float,
    val speedHeight: Float,
    val speedFontSize: Float,
    val technologyFontSize: Float,
    val clockGap: Float,
    val speedGap: Float,
    val detailGap: Float,
) {
    val clockBlockHeight: Float get() = clockHeight + clockGap +
        if (speedHeight > 0f) speedHeight + speedGap else 0f
}

/** Fallback size for a rail [width] dp wide when no measured [DuoStatus] is supplied. */
internal fun statusRailGeometry(width: Float, requestedVisual: Float = 36.5f,
    compact: Boolean = false, fontScale: Float = 1f, density: Float = 1f): StatusRailGeometry {
    val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val visual = minOf(requestedVisual, maxOf(28f, width - 4f), if (compact) 40f else 52f)
    fun snap(value: Float) = snapStatusDimension(value, density)
    return StatusRailGeometry(snap(visual), .41f * visual / scale, 0f,
        snap(visual * .55f), 0f, snap(visual), 0f, 0f,
        .33f * visual / scale, snap(4f), 0f, 0f)
}

internal fun snapStatusDimension(value: Float, density: Float): Float =
    if (density.isFinite() && density > 0f) (density * value).roundToInt() / density else value

/**
 * Rail size for the measured status block: the ring is [DuoStatus.ringDiameter] and the time box is
 * centred on [DuoStatus.timeCenterY], so the ring centre lands exactly on [DuoStatus.ringCenterY].
 * Text sizes ignore the system font scale, like iOS status-bar text.
 */
internal fun DuoStatus.railGeometry(fontScale: Float = 1f, density: Float = 1f): StatusRailGeometry {
    val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val clock = timeBox.height
    val gap = if (horizontal) 0f else (ringCenterY - timeCenterY - clock / 2f - ringDiameter / 2f).coerceAtLeast(0f)
    return StatusRailGeometry(visualSize = ringDiameter, timeFontSize = timeSize / scale, detailFontSize = 0f,
        clockHeight = clock, detailHeight = 0f, infoHeight = ringDiameter, speedHeight = 0f, speedFontSize = 0f,
        technologyFontSize = .33f * ringDiameter / scale, clockGap = snapStatusDimension(gap, density),
        speedGap = 0f, detailGap = 0f)
}

/** Window-space top of the rail (the time box) for a vertical status block. */
internal val DuoStatus.railTop: Float get() = timeCenterY - timeBox.height / 2f
