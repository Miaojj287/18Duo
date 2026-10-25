package com.jake.duolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Proportions of the iPhone Duo status ring as fractions of its outer diameter D. Round 3 re-measured
 * them on the full-resolution status-bar video (status-bar.mp4 @ 3.17 s, 1920×1080, D = 353.7 px;
 * fitted model IoU 0.973) and checked them on the HIG toolbar figure (D = 43 px): the battery pill
 * becomes the open ring, the four cellular bars become four dots on a 20° pitch below its gap, and
 * Wi-Fi keeps the SF "wifi" glyph ([SfWifiGlyph]). evidence/r3-chrome/wifi has the comparisons.
 */
internal object DuoStatusRing {
    /** Ring stroke: 25.35 / 353.7 px (HIG figure 0.0755 at 3 px, i.e. within a pixel). */
    const val STROKE = .0717f
    /** Stroke-centre ends at 149.3° and 30.7° (0° = 3 o'clock, clockwise); round caps add ≈ 4.4° each side. */
    const val ARC_START = 149.3f
    const val ARC_SWEEP = 241.4f
    /** Dots: Ø 0.0997 D, centres 0.476 D from the ring centre (just outside its stroke centre-line, 0.464 D). */
    const val DOT_DIAMETER = .0997f
    const val DOT_RADIUS = .476f
    /** Dot angles (0° = 3 o'clock, clockwise), lit from the left: 90° ± 10° and ± 30° (video 60.0/80.4/100.3/120.7). */
    val DOT_ANGLES = floatArrayOf(120f, 100f, 80f, 60f)
}

/**
 * SF Symbols "wifi" exactly as the Duo ring draws it, fitted on the same video frame (glyph IoU 0.976):
 * every element is concentric about one point [CENTER_Y] below the ring centre. Two arcs of one stroke
 * weight with ROUND caps, and a filled wedge — a sector with softly rounded corners, tip at the bottom.
 * Before round 3 the wedge was 1.5× too large and the arcs sat 10 % too far out (owner: "ugly").
 */
internal object SfWifiGlyph {
    const val CENTER_Y = .158f
    /** Arc stroke (22.2 / 353.7 px): 0.88 × the ring's stroke. */
    const val STROKE = .0628f
    /** Stroke centre-line radii of the inner and outer arc; gaps between elements ≈ 0.054 D. */
    const val INNER_ARC = .1665f
    const val OUTER_ARC = .2825f
    /** Half opening of the arcs' centre-lines from vertical; the round caps carry them to ≈ ±49°. */
    const val ARC_HALF_ANGLE = 42.5f
    /** Wedge = a sector of [WEDGE_CORE] radius and ±[WEDGE_HALF_ANGLE], grown by [WEDGE_CORNER] all round. */
    const val WEDGE_CORE = .0585f
    const val WEDGE_HALF_ANGLE = 46.1f
    const val WEDGE_CORNER = .0209f
}

/** Glyph extents (fractions of D, relative to the ring centre, y down) for tests and evidence. */
internal data class GlyphExtent(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Bounding box of one SF "wifi" element: 0 = wedge, 1 = inner arc, 2 = outer arc. */
internal fun sfWifiElementExtent(element: Int): GlyphExtent {
    val c = SfWifiGlyph.CENTER_Y
    if (element == 0) {
        val half = Math.toRadians(SfWifiGlyph.WEDGE_HALF_ANGLE.toDouble())
        val x = (SfWifiGlyph.WEDGE_CORE * sin(half)).toFloat() + SfWifiGlyph.WEDGE_CORNER
        return GlyphExtent(-x, c - SfWifiGlyph.WEDGE_CORE - SfWifiGlyph.WEDGE_CORNER, x, c + SfWifiGlyph.WEDGE_CORNER)
    }
    val r = if (element == 1) SfWifiGlyph.INNER_ARC else SfWifiGlyph.OUTER_ARC
    val half = Math.toRadians(SfWifiGlyph.ARC_HALF_ANGLE.toDouble())
    val cap = SfWifiGlyph.STROKE / 2f
    val x = (r * sin(half)).toFloat() + cap
    return GlyphExtent(-x, c - r - cap, x, c - (r * cos(half)).toFloat() + cap)
}

private val IosGreen = Color(0xFF34C759)
private val IosRed = Color(0xFFFF3B30)
/**
 * Dim (unlit) status elements: one solid translucent white fill, like iOS's empty battery and
 * unlit bars. No dark underlay: under a translucent fill it shows as a hollow outline (review).
 */
private val StatusDimFill = Color.White.copy(alpha = .36f)
/** Soft shadow under lit status elements. */
private val StatusLitUnder = Color.Black.copy(alpha = .14f)

@Composable
internal fun StatusRail(
    status: DeviceStatus,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    iconSize: Dp = 36.dp,
    locationInUse: Boolean = false,
    geometry: StatusRailGeometry? = null,
    /** Inner portrait: the time and ring sit at the two ends of a horizontal bar. */
    horizontal: Boolean = false,
) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_050L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val format = if (rememberIs24HourFormat()) "HH:mm" else "h:mm"
    val timeFormatter = remember(format) { DateTimeFormatter.ofPattern(format) }
    val timeText = now.format(timeFormatter)
    val description = statusAccessibilityText(status, now, timeText, locationInUse,
        chinese = java.util.Locale.getDefault().language == "zh")
    val density = LocalDensity.current
    val labelStyle = TextStyle(fontFamily = DuoFonts.text, platformStyle = PlatformTextStyle(includeFontPadding = false),
        shadow = Shadow(Color.Black.copy(alpha = .3f), Offset(0f, 1f), 3f))
    BoxWithConstraints(modifier.testTag("status-rail").semantics(mergeDescendants = true) { contentDescription = description }) {
        val rail = geometry ?: statusRailGeometry(maxWidth.value, iconSize.value, compact,
            density.fontScale, density.density)
        val timeSize = rail.timeFontSize.sp
        val time: @Composable (Modifier) -> Unit = { box ->
            Box(box.height(rail.clockHeight.dp).testTag("status-time"), contentAlignment = Alignment.Center) {
                // Inter sets digits wider than SF: −2 % tracking keeps a 24-hour "03:33" within the ring's width.
                Text(timeText,
                    color = Color.White, fontSize = timeSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    lineHeight = timeSize * 1.2f, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                    style = labelStyle.copy(letterSpacing = (-0.02f).em))
            }
        }
        if (horizontal) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            time(Modifier.wrapContentWidth())
            Spacer(Modifier.weight(1f))
            ConnectivityRing(status, rail, locationInUse, labelStyle)
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            time(Modifier.fillMaxWidth())
            Spacer(Modifier.height(rail.clockGap.dp))
            ConnectivityRing(status, rail, locationInUse, labelStyle)
        }
    }
}

@Composable
private fun ConnectivityRing(status: DeviceStatus, rail: StatusRailGeometry, locationInUse: Boolean, labelStyle: TextStyle) {
    val wifiVisual = wifiSignalVisual(status.wifiConnected, status.wifiLevel)
    val cellularVisual = cellularSignalVisual(status.cellularLevel, status.airplane)
    Box(Modifier.size(rail.visualSize.dp).testTag("status-connectivity-ring"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val center = Offset(w / 2f, w / 2f)
            val stroke = w * DuoStatusRing.STROKE
            val radius = w / 2f - stroke / 2f
            val arcSize = Size(radius * 2f, radius * 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            // iOS draws the empty part of the battery and unlit cellular dots as a dim, SOLID
            // track (no outline). Only lit parts get a soft dark underlay (like the time's
            // shadow); under a translucent dim element it would read as a hollow outline.
            val sweep = status.battery?.let { DuoStatusRing.ARC_SWEEP * it.coerceIn(0, 100) / 100f } ?: 0f
            if (sweep > 0f) drawArc(StatusLitUnder, DuoStatusRing.ARC_START, sweep, false,
                topLeft, arcSize, style = Stroke(width = stroke * 1.3f, cap = StrokeCap.Round))
            drawArc(StatusDimFill, DuoStatusRing.ARC_START, DuoStatusRing.ARC_SWEEP, false,
                topLeft, arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
            status.battery?.let { level ->
                // iOS battery colours: green while charging, red at 20 % or less.
                val fill = when {
                    status.charging -> IosGreen
                    level <= 20 -> IosRed
                    else -> Color.White
                }
                if (sweep > 0f) drawArc(fill, DuoStatusRing.ARC_START, sweep, false,
                    topLeft, arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            val activeDots = (cellularVisual as? CellularSignalVisual.Available)?.activeDots ?: 0
            DuoStatusRing.DOT_ANGLES.forEachIndexed { index, degrees ->
                val angle = Math.toRadians(degrees.toDouble())
                val dotRadius = w * DuoStatusRing.DOT_DIAMETER / 2f
                val dotCenter = Offset(center.x + w * DuoStatusRing.DOT_RADIUS * cos(angle).toFloat(),
                    center.y + w * DuoStatusRing.DOT_RADIUS * sin(angle).toFloat())
                if (index < activeDots) {
                    drawCircle(StatusLitUnder, dotRadius * 1.25f, dotCenter)
                    drawCircle(Color.White, dotRadius, dotCenter)
                } else {
                    drawCircle(StatusDimFill, dotRadius, dotCenter)
                }
            }
        }
        if (wifiVisual is WifiSignalVisual.Connected) {
            Canvas(Modifier.matchParentSize()) { drawWifiGlyph(wifiVisual.elements) }
        } else if (status.airplane) {
            Icon(IosSymbols.airplane, contentDescription = null, tint = Color.White,
                modifier = Modifier.size((rail.visualSize * .45f).dp))
        } else if (status.cellularTechnology.label != null) {
            Text(status.cellularTechnology.label, color = Color.White, fontSize = rail.technologyFontSize.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { scaleX = .88f }, style = labelStyle)
        } else if (status.cellularLevel != null) {
            Icon(IosSymbols.cellularBars, contentDescription = null,
                tint = Color.White, modifier = Modifier.size((rail.visualSize * .42f).dp))
        } else {
            Text("—", color = Color.White.copy(alpha = .72f), fontSize = 12.sp,
                textAlign = TextAlign.Center, style = labelStyle)
        }
        if (locationInUse) Icon(IosSymbols.location, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).size(12.dp))
    }
}

/**
 * SF "wifi" ([SfWifiGlyph]): a corner-rounded wedge and two round-capped arcs, all concentric. Unlit
 * elements are a solid dim fill of exactly their own shape (iOS); lit ones sit on a hairline shadow.
 */
private fun DrawScope.drawWifiGlyph(elements: List<SignalElementEmphasis>) {
    val d = size.width
    val center = Offset(d / 2f, d / 2f + d * SfWifiGlyph.CENTER_Y)
    val stroke = d * SfWifiGlyph.STROKE
    fun emphasis(index: Int) = elements.getOrElse(index) { SignalElementEmphasis.DIM }
    fun color(index: Int) = Color.White.copy(alpha = signalAlpha(emphasis(index)))
    fun lit(index: Int) = emphasis(index) != SignalElementEmphasis.DIM
    val shadow = Color.Black.copy(alpha = .16f)
    val halo = stroke * .16f
    // Wedge: the core sector, filled and outlined with a round-joined stroke of twice the corner
    // radius, is exactly the sector grown by that radius (rounded tip and top corners).
    val core = d * SfWifiGlyph.WEDGE_CORE
    val corner = d * SfWifiGlyph.WEDGE_CORNER
    val half = SfWifiGlyph.WEDGE_HALF_ANGLE
    val wedge = Path().apply {
        moveTo(center.x, center.y)
        arcTo(Rect(center, core), 270f - half, 2f * half, false)
        close()
    }
    if (lit(0)) drawPath(wedge, shadow, style = Stroke(2f * (corner + halo), join = StrokeJoin.Round))
    val wedgeAlpha = signalAlpha(emphasis(0))
    fun wedge(color: Color) {
        drawPath(wedge, color)
        drawPath(wedge, color, style = Stroke(2f * corner, join = StrokeJoin.Round))
    }
    if (wedgeAlpha >= 1f) wedge(Color.White) else {
        // Fill and rounding stroke overlap: a translucent wedge is composited once, as one shape.
        val reach = core + 2f * corner
        drawContext.canvas.saveLayer(Rect(center.x - reach, center.y - reach, center.x + reach, center.y + reach),
            Paint().apply { alpha = wedgeAlpha })
        wedge(Color.White)
        drawContext.canvas.restore()
    }
    val arcHalf = SfWifiGlyph.ARC_HALF_ANGLE
    listOf(SfWifiGlyph.INNER_ARC, SfWifiGlyph.OUTER_ARC).forEachIndexed { index, fraction ->
        val radius = d * fraction
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arc = Size(radius * 2f, radius * 2f)
        if (lit(index + 1)) drawArc(shadow, 270f - arcHalf, 2f * arcHalf, false, topLeft, arc,
            style = Stroke(stroke + 2f * halo, cap = StrokeCap.Round))
        drawArc(color(index + 1), 270f - arcHalf, 2f * arcHalf, false, topLeft, arc,
            style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private fun signalAlpha(emphasis: SignalElementEmphasis): Float = when (emphasis) {
    SignalElementEmphasis.DIM -> .36f
    SignalElementEmphasis.NEUTRAL -> .62f
    SignalElementEmphasis.LIT -> 1f
}
