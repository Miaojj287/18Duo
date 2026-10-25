package com.jake.duolauncher

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Apple's continuous ("squircle") corners, built with the corner-smoothing construction Figma
 * published in "Desperately seeking squircles" (2018): each corner is a short circular arc that is
 * entered and left through cubic Béziers, so curvature ramps up from the straight edge instead of
 * jumping like a plain circular rounded rect. 60 % smoothing is Figma's "iOS" preset and visually
 * matches UIBezierPath continuous corners / the iOS icon mask at a 22.4 % radius.
 */
internal const val IOS_CORNER_SMOOTHING = .6f

/** Where the smoothed corner starts on each edge and the Bézier/arc lengths, in path units. */
internal data class SmoothCorner(
    /** Radius of the circular middle section (already clamped to the budget). */
    val radius: Float,
    /** Smoothing actually used; lowered when two corners would otherwise meet. */
    val smoothing: Float,
    /** Distance from the corner vertex along each edge where the curve begins. */
    val extent: Float,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    /** Angular size of the circular section in degrees: 90 · (1 − smoothing). */
    val arcDegrees: Float,
)

/**
 * Corner parameters for [radius] with [smoothing] inside a shape whose shorter side is
 * 2 · [budget]. When the smoothed corner would not fit, smoothing is reduced first (exactly as
 * Figma's non-"preserve smoothing" mode), so a radius equal to half the side degrades to a true
 * circle/capsule.
 */
internal fun smoothCorner(radius: Float, smoothing: Float, budget: Float): SmoothCorner {
    val safeBudget = budget.takeIf { it.isFinite() && it > 0f } ?: 0f
    val r = radius.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(safeBudget) ?: 0f
    if (r <= 0f) return SmoothCorner(0f, 0f, 0f, 0f, 0f, 0f, 0f, 90f)
    val wanted = smoothing.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val xi = min(wanted, (safeBudget / r - 1f).coerceAtLeast(0f))
    val p = min((1f + xi) * r, safeBudget)
    val arcDegrees = 90f * (1f - xi)
    val arcSectionLength = (sin(toRadians(arcDegrees / 2f)) * r * sqrt(2f)).toFloat()
    val beta = 45f * xi
    // Distance between the arc's start and the last Bézier control point (Figma, fig. 11.1).
    val p3ToP4 = r * tan(toRadians(beta / 2f)).toFloat()
    val c = p3ToP4 * cos(toRadians(beta)).toFloat()
    val d = c * tan(toRadians(beta)).toFloat()
    val b = ((p - arcSectionLength - c - d) / 3f).coerceAtLeast(0f)
    val a = 2f * b
    return SmoothCorner(r, xi, p, a, b, c, d, arcDegrees)
}

private fun toRadians(degrees: Float): Double = degrees * PI / 180.0

/** Minimal path vocabulary so the geometry can be unit-tested without Android's Path. */
internal interface CurveSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

/**
 * Emits a closed clockwise continuous-corner rectangle. [radii] are top-left, top-right,
 * bottom-right, bottom-left in path units. Degenerate sizes emit nothing.
 */
internal fun continuousRoundedRect(
    left: Float, top: Float, width: Float, height: Float,
    radii: FloatArray, smoothing: Float, sink: CurveSink,
) {
    if (!(width > 0f && height > 0f) || !width.isFinite() || !height.isFinite()) return
    val budget = min(width, height) / 2f
    val corners = Array(4) { smoothCorner(radii.getOrElse(it) { 0f }, smoothing, budget) }
    val right = left + width
    val bottom = top + height
    sink.moveTo(left + corners[0].extent, top)
    sink.lineTo(right - corners[1].extent, top)
    emitCorner(corners[1], right, top, 0, sink)
    sink.lineTo(right, bottom - corners[2].extent)
    emitCorner(corners[2], right, bottom, 1, sink)
    sink.lineTo(left + corners[3].extent, bottom)
    emitCorner(corners[3], left, bottom, 2, sink)
    sink.lineTo(left, top + corners[0].extent)
    emitCorner(corners[0], left, top, 3, sink)
    sink.close()
}

/**
 * Draws one corner. Geometry is written for the top-right corner with its vertex at the origin
 * (travelling right along the top edge, then down the right edge) and rotated clockwise by
 * [quarterTurns] × 90° for the other corners.
 */
private fun emitCorner(corner: SmoothCorner, vx: Float, vy: Float, quarterTurns: Int, sink: CurveSink) {
    if (corner.radius <= 0f) return
    val (r, _, p, a, b, c, d, arcDegrees) = corner
    fun px(x: Float, y: Float): Float = vx + when (quarterTurns) { 0 -> x; 1 -> -y; 2 -> -x; else -> y }
    fun py(x: Float, y: Float): Float = vy + when (quarterTurns) { 0 -> y; 1 -> x; 2 -> -y; else -> -x }
    // Entry Bézier: straight edge → start of the arc (tangent already at angle β).
    val p1x = -p + a; val p2x = -p + a + b
    val p3x = -p + a + b + c; val p3y = d
    sink.cubicTo(px(p1x, 0f), py(p1x, 0f), px(p2x, 0f), py(p2x, 0f), px(p3x, p3y), py(p3x, p3y))
    // Circular middle section around centre (−r, r), approximated by one cubic (≤ 90°).
    val arcRad = toRadians(arcDegrees)
    val p4x = -p3y; val p4y = -p3x
    if (arcDegrees > .01f) {
        val start = toRadians(-90f + 45f * corner.smoothing)
        val end = start + arcRad
        val k = (4.0 / 3.0 * tan(arcRad / 4.0) * r).toFloat()
        val c1x = p3x + k * (-sin(start)).toFloat(); val c1y = p3y + k * cos(start).toFloat()
        val c2x = p4x - k * (-sin(end)).toFloat(); val c2y = p4y - k * cos(end).toFloat()
        sink.cubicTo(px(c1x, c1y), py(c1x, c1y), px(c2x, c2y), py(c2x, c2y), px(p4x, p4y), py(p4x, p4y))
    }
    // Exit Bézier: mirror of the entry across the corner diagonal, (x, y) → (−y, −x).
    val q1y = p - a - b; val q2y = p - a
    sink.cubicTo(px(0f, q1y), py(0f, q1y), px(0f, q2y), py(0f, q2y), px(0f, p), py(0f, p))
}

private class ComposePathSink(val path: Path) : CurveSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = path.cubicTo(x1, y1, x2, y2, x3, y3)
    override fun close() = path.close()
}

private class AndroidPathSink(val path: android.graphics.Path) : CurveSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = path.cubicTo(x1, y1, x2, y2, x3, y3)
    override fun close() = path.close()
}

/** Android path for bitmap work (icon masks); same geometry as [ContinuousRoundedShape]. */
internal fun continuousRoundedPath(
    left: Float, top: Float, width: Float, height: Float, radius: Float, smoothing: Float = IOS_CORNER_SMOOTHING,
): android.graphics.Path = android.graphics.Path().also {
    continuousRoundedRect(left, top, width, height, floatArrayOf(radius, radius, radius, radius), smoothing, AndroidPathSink(it))
}

/**
 * A [CornerBasedShape] with Apple continuous corners. Corner sizes resolve exactly like
 * [RoundedCornerShape] (dp, px or percent; start/end follow layout direction), so it can replace
 * one 1:1. When no corner has room for smoothing the outline stays a platform round rect, which
 * keeps capsules/circles on Android's fast outline-clipping path.
 */
@Immutable
internal class ContinuousRoundedShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    val smoothing: Float = IOS_CORNER_SMOOTHING,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(all: CornerSize, smoothing: Float = IOS_CORNER_SMOOTHING) : this(all, all, all, all, smoothing)

    override fun copy(topStart: CornerSize, topEnd: CornerSize, bottomEnd: CornerSize, bottomStart: CornerSize) =
        ContinuousRoundedShape(topStart, topEnd, bottomEnd, bottomStart, smoothing)

    override fun createOutline(
        size: Size, topStart: Float, topEnd: Float, bottomEnd: Float, bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) return Outline.Rectangle(size.toRect())
        val ltr = layoutDirection == LayoutDirection.Ltr
        val radii = floatArrayOf(
            if (ltr) topStart else topEnd,
            if (ltr) topEnd else topStart,
            if (ltr) bottomEnd else bottomStart,
            if (ltr) bottomStart else bottomEnd,
        )
        val budget = size.minDimension / 2f
        if (radii.all { smoothCorner(it, smoothing, budget).smoothing <= 0f }) {
            return Outline.Rounded(RoundRect(size.toRect(),
                CornerRadius(radii[0]), CornerRadius(radii[1]), CornerRadius(radii[2]), CornerRadius(radii[3])))
        }
        val path = Path()
        continuousRoundedRect(0f, 0f, size.width, size.height, radii, smoothing, ComposePathSink(path))
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean = this === other || other is ContinuousRoundedShape &&
        topStart == other.topStart && topEnd == other.topEnd && bottomEnd == other.bottomEnd &&
        bottomStart == other.bottomStart && smoothing == other.smoothing

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return 31 * result + smoothing.hashCode()
    }

    override fun toString(): String = "ContinuousRoundedShape($topStart, $topEnd, $bottomEnd, $bottomStart, $smoothing)"
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

internal fun ContinuousRoundedShape(radius: Dp, smoothing: Float = IOS_CORNER_SMOOTHING) =
    ContinuousRoundedShape(CornerSize(radius), smoothing)

internal fun ContinuousRoundedShape(percent: Int, smoothing: Float = IOS_CORNER_SMOOTHING) =
    ContinuousRoundedShape(CornerSize(percent), smoothing)

/**
 * The continuous-corner twin of a Compose rounded shape. Glass surfaces call this so every
 * existing `RoundedCornerShape(…)` call site (Dock, menus, pills, folders, sheets) gets Apple
 * corners without touching layout code. Other shapes pass through unchanged.
 */
internal fun Shape.continuousCorners(): Shape = when (this) {
    is ContinuousRoundedShape -> this
    is RoundedCornerShape -> ContinuousRoundedShape(topStart, topEnd, bottomEnd, bottomStart)
    else -> this
}
