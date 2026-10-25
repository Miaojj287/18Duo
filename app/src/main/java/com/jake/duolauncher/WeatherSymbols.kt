package com.jake.duolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original vector drawings that follow the shapes and multicolour layers of the SF Symbols
 * Apple's Weather widgets use (sun.max.fill, moon.stars.fill, cloud.sun.fill, cloud.rain.fill …):
 * yellow sun and bolt, white cloud and moon, cyan rain, white snow. Overlapping layers are
 * separated by a knocked-out gap, like SF Symbols' multicolour rendering. Clear-style widgets
 * pass [monochrome] (white), as iOS does for its Clear widgets.
 */
internal data class WeatherSymbolPalette(val sun: Color, val cloud: Color, val rain: Color, val moon: Color, val accent: Color) {
    companion object {
        val Multicolor = WeatherSymbolPalette(sun = Color(0xFFFFD60A), cloud = Color.White, rain = Color(0xFF64D2FF),
            moon = Color.White, accent = Color.White.copy(alpha = .62f))
        fun mono(color: Color) = WeatherSymbolPalette(color, color, color, color, color.copy(alpha = color.alpha * .62f))
    }
}

@Composable
internal fun WeatherGlyph(symbol: WeatherSymbol, size: Dp, modifier: Modifier = Modifier, monochrome: Color? = null) {
    val palette = monochrome?.let(WeatherSymbolPalette::mono) ?: WeatherSymbolPalette.Multicolor
    // Offscreen so the knock-out gaps clear only the glyph, never the widget's sky behind it.
    Canvas(modifier.size(size).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        drawWeatherSymbol(symbol, Offset.Zero, this.size.minDimension, palette)
    }
}

/** Unit cloud outline (24-pt SF-like silhouette) mapped into [rect]. */
private fun cloudPath(rect: Rect): Path {
    fun x(v: Float) = rect.left + (v - 2.8f) / 18.4f * rect.width
    fun y(v: Float) = rect.top + (v - 6f) / 12.6f * rect.height
    return Path().apply {
        moveTo(x(7f), y(18.6f)); lineTo(x(17.2f), y(18.6f))
        cubicTo(x(19.6f), y(18.6f), x(21.2f), y(16.9f), x(21.2f), y(14.8f))
        cubicTo(x(21.2f), y(12.7f), x(19.6f), y(11.1f), x(17.5f), y(11.1f))
        cubicTo(x(17f), y(8.1f), x(14.7f), y(6f), x(11.8f), y(6f))
        cubicTo(x(8.9f), y(6f), x(6.6f), y(8.2f), x(6.4f), y(11.1f))
        cubicTo(x(4.4f), y(11.4f), x(2.8f), y(13f), x(2.8f), y(14.9f))
        cubicTo(x(2.8f), y(17f), x(4.5f), y(18.6f), x(7f), y(18.6f))
        close()
    }
}

private fun DrawScope.knockout(path: Path, gap: Float) {
    drawPath(path, Color.Black, style = Stroke(gap * 2f, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
    drawPath(path, Color.Black, blendMode = BlendMode.Clear)
}

private fun DrawScope.sun(center: Offset, disc: Float, rayIn: Float, rayOut: Float, width: Float, color: Color) {
    drawCircle(color, disc, center)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0 - 90.0)
        val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
        drawLine(color, center + Offset(dx * rayIn, dy * rayIn), center + Offset(dx * rayOut, dy * rayOut), width, StrokeCap.Round)
    }
}

private fun crescent(center: Offset, radius: Float): Path {
    val outer = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(center + Offset(radius * .62f, -radius * .5f), radius * .86f)) }
    return Path().apply { op(outer, bite, PathOperation.Difference) }
}

private fun sparkle(center: Offset, r: Float): Path = Path().apply {
    moveTo(center.x, center.y - r)
    quadraticTo(center.x + r * .18f, center.y - r * .18f, center.x + r, center.y)
    quadraticTo(center.x + r * .18f, center.y + r * .18f, center.x, center.y + r)
    quadraticTo(center.x - r * .18f, center.y + r * .18f, center.x - r, center.y)
    quadraticTo(center.x - r * .18f, center.y - r * .18f, center.x, center.y - r)
    close()
}

private fun bolt(left: Float, top: Float, w: Float, h: Float): Path = Path().apply {
    moveTo(left + w * .62f, top); lineTo(left, top + h * .58f); lineTo(left + w * .45f, top + h * .58f)
    lineTo(left + w * .3f, top + h); lineTo(left + w, top + h * .38f); lineTo(left + w * .55f, top + h * .38f); close()
}

/** Draws [symbol] in the square at [origin] with side [s]. */
internal fun DrawScope.drawWeatherSymbol(symbol: WeatherSymbol, origin: Offset, s: Float, palette: WeatherSymbolPalette) {
    fun p(x: Float, y: Float) = Offset(origin.x + x * s, origin.y + y * s)
    fun r(l: Float, t: Float, rr: Float, b: Float) = Rect(origin.x + l * s, origin.y + t * s, origin.x + rr * s, origin.y + b * s)
    val upperCloud = r(.04f, .06f, .96f, .64f)
    fun precipitationCloud() = drawPath(cloudPath(upperCloud), palette.cloud)
    fun streaks(xs: List<Float>, top: Float, length: Float, color: Color, width: Float = .075f) = xs.forEach { x ->
        drawLine(color, p(x, top), p(x - length * .32f, top + length), width * s, StrokeCap.Round)
    }
    fun flake(x: Float, y: Float, radius: Float, color: Color) {
        for (k in 0 until 3) {
            val a = Math.toRadians(k * 60.0 + 90.0)
            val d = Offset(cos(a).toFloat() * radius * s, sin(a).toFloat() * radius * s)
            drawLine(color, p(x, y) - d, p(x, y) + d, .05f * s, StrokeCap.Round)
        }
    }
    when (symbol) {
        WeatherSymbol.SUN -> sun(p(.5f, .5f), .21f * s, .32f * s, .45f * s, .072f * s, palette.sun)
        WeatherSymbol.MOON_STARS -> {
            drawPath(crescent(p(.42f, .56f), .36f * s), palette.moon)
            drawPath(sparkle(p(.8f, .2f), .13f * s), palette.moon)
            drawPath(sparkle(p(.62f, .06f), .06f * s), palette.moon)
        }
        WeatherSymbol.CLOUD_SUN, WeatherSymbol.CLOUD_MOON -> {
            if (symbol == WeatherSymbol.CLOUD_SUN) sun(p(.66f, .34f), .15f * s, .23f * s, .32f * s, .06f * s, palette.sun)
            else drawPath(crescent(p(.66f, .32f), .25f * s), palette.moon)
            val cloud = cloudPath(r(0f, .36f, .8f, .9f))
            knockout(cloud, .055f * s)
            drawPath(cloud, palette.cloud)
        }
        WeatherSymbol.CLOUD -> drawPath(cloudPath(r(.02f, .2f, .98f, .8f)), palette.cloud)
        WeatherSymbol.FOG -> {
            precipitationCloud()
            drawLine(palette.accent, p(.1f, .76f), p(.9f, .76f), .075f * s, StrokeCap.Round)
            drawLine(palette.accent, p(.22f, .92f), p(.78f, .92f), .075f * s, StrokeCap.Round)
        }
        WeatherSymbol.DRIZZLE -> {
            precipitationCloud()
            listOf(.3f to .76f, .5f to .86f, .7f to .76f, .4f to .96f, .6f to .96f).forEach { (x, y) ->
                drawCircle(palette.rain, .045f * s, p(x, y))
            }
        }
        WeatherSymbol.RAIN -> { precipitationCloud(); streaks(listOf(.32f, .52f, .72f), .72f, .22f, palette.rain) }
        WeatherSymbol.HEAVY_RAIN -> { precipitationCloud(); streaks(listOf(.26f, .42f, .58f, .74f), .71f, .27f, palette.rain) }
        WeatherSymbol.SLEET -> {
            precipitationCloud()
            streaks(listOf(.3f, .66f), .72f, .2f, palette.rain)
            flake(.48f, .84f, .085f, palette.cloud)
        }
        WeatherSymbol.SNOW -> {
            precipitationCloud()
            flake(.3f, .8f, .085f, palette.cloud); flake(.7f, .8f, .085f, palette.cloud); flake(.5f, .92f, .07f, palette.cloud)
        }
        WeatherSymbol.BOLT_RAIN -> {
            precipitationCloud()
            val boltPath = bolt(origin.x + .38f * s, origin.y + .54f * s, .26f * s, .44f * s)
            knockout(boltPath, .04f * s)
            drawPath(boltPath, palette.sun)
            streaks(listOf(.26f, .78f), .72f, .18f, palette.rain)
        }
        WeatherSymbol.WIND -> {
            val stroke = Stroke(.075f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(Path().apply {
                moveTo(origin.x + .06f * s, origin.y + .38f * s); lineTo(origin.x + .62f * s, origin.y + .38f * s)
                cubicTo(origin.x + .78f * s, origin.y + .38f * s, origin.x + .8f * s, origin.y + .14f * s, origin.x + .66f * s, origin.y + .14f * s)
                cubicTo(origin.x + .58f * s, origin.y + .14f * s, origin.x + .55f * s, origin.y + .2f * s, origin.x + .55f * s, origin.y + .24f * s)
            }, palette.cloud, style = stroke)
            drawPath(Path().apply {
                moveTo(origin.x + .06f * s, origin.y + .54f * s); lineTo(origin.x + .78f * s, origin.y + .54f * s)
                cubicTo(origin.x + .96f * s, origin.y + .54f * s, origin.x + .98f * s, origin.y + .82f * s, origin.x + .8f * s, origin.y + .82f * s)
                cubicTo(origin.x + .72f * s, origin.y + .82f * s, origin.x + .68f * s, origin.y + .76f * s, origin.x + .68f * s, origin.y + .72f * s)
            }, palette.cloud, style = stroke)
            drawLine(palette.cloud, p(.06f, .7f), p(.42f, .7f), .075f * s, StrokeCap.Round)
        }
        WeatherSymbol.SUNRISE, WeatherSymbol.SUNSET -> {
            val horizon = .7f
            val c = p(.5f, horizon)
            drawArc(palette.sun, 180f, 180f, true, Offset(c.x - .2f * s, c.y - .2f * s), Size(.4f * s, .4f * s))
            for (i in 0..4) {
                val a = Math.toRadians(180.0 + 22.5 + i * 33.75)
                val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                drawLine(palette.sun, c + Offset(dx * .28f * s, dy * .28f * s), c + Offset(dx * .4f * s, dy * .4f * s), .065f * s, StrokeCap.Round)
            }
            drawLine(palette.cloud, p(.04f, horizon + .06f), p(.96f, horizon + .06f), .065f * s, StrokeCap.Round)
            val up = symbol == WeatherSymbol.SUNRISE
            val tipY = if (up) .84f else 1f
            val baseY = if (up) 1f else .84f
            drawPath(Path().apply {
                moveTo(origin.x + .38f * s, origin.y + baseY * s); lineTo(origin.x + .5f * s, origin.y + tipY * s)
                lineTo(origin.x + .62f * s, origin.y + baseY * s)
            }, palette.cloud, style = Stroke(.06f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        WeatherSymbol.LOCATION -> drawPath(Path().apply {
            moveTo(origin.x + .92f * s, origin.y + .08f * s); lineTo(origin.x + .06f * s, origin.y + .46f * s)
            lineTo(origin.x + .47f * s, origin.y + .54f * s); lineTo(origin.x + .55f * s, origin.y + .94f * s); close()
        }, palette.cloud)
    }
}
