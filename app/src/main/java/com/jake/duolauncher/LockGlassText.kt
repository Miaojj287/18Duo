package com.jake.duolauncher

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeState
import org.intellij.lang.annotations.Language
import kotlin.math.ceil
import kotlin.math.max

/**
 * iOS 26's "Glass" Lock Screen clock (owner, r13: the time must be Liquid Glass, iPhone reference
 * user-photos-r13/3.webp): each glyph is a slab of clear glass. The wallpaper under a glyph is seen through
 * it, displaced along the glyph's own slope (a height field = the glyph's blurred coverage), so it bends at
 * every stroke edge exactly where the eye expects; the body is lifted toward white and the edges facing the
 * light (top-left) catch a specular highlight, the opposite ones a softer one.
 *
 * Two alpha masks per string (sharp coverage + blurred height) are rebuilt only when the text, its paint or
 * its size change (once a minute); per frame the cost is one offscreen of the time's bounding box and one
 * shader pass over it.
 */
@Language("AGSL")
private const val GLASS_TEXT_SHADER = """
uniform shader content;
uniform shader coverage;
uniform shader height;
uniform float bend;
uniform float2 light;
uniform float lift;
uniform float spec;

float h(float2 p) { return height.eval(p).a; }

half4 main(float2 c) {
    float a = coverage.eval(c).a;
    if (a <= 0.002) return half4(0.0);
    // Slope of the height field (rises into the glyph): its normal at the stroke's edge points outward.
    float2 g = float2(h(c + float2(1.5, 0.0)) - h(c - float2(1.5, 0.0)), h(c + float2(0.0, 1.5)) - h(c - float2(0.0, 1.5)));
    float rim = clamp(length(g) * 2.2, 0.0, 1.0);
    float2 n = rim > 0.0 ? -normalize(g + float2(1e-5)) : float2(0.0);
    // A thick lens: content near an edge is pulled in from beyond it (looking through the slab's bevel).
    half4 bg = content.eval(c - n * bend * rim);
    half3 body = mix(bg.rgb, half3(1.0), lift);
    float facing = dot(n, light);
    float shine = spec * rim * (pow(max(facing, 0.0), 1.6) + 0.55 * pow(max(-facing, 0.0), 1.6));
    half3 col = min(body + shine, half3(1.0));
    return half4(col * a, a);
}
"""

internal class LockGlassText {
    private var shader: RuntimeShader? = null
    private var failed = false
    private var coverage: Bitmap? = null
    private var heightMap: Bitmap? = null
    private var key: Any? = null
    var layer: GraphicsLayer? = null

    /**
     * Draws [text] as glass with its baseline at ([x], [baseline]) (the paint's alignment applies) inside a
     * full-screen canvas whose top-left is at [origin] on screen. Returns false (nothing drawn) when glass is
     * unavailable — the caller then draws plain text.
     */
    fun draw(scope: DrawScope, text: String, paint: Paint, x: Float, baseline: Float, origin: Offset, backdrop: HazeState?): Boolean {
        if (Build.VERSION.SDK_INT < 33 || failed || backdrop == null || !origin.isSpecified) return false
        val layer = layer ?: return false
        if (!scope.drawContext.canvas.nativeCanvas.isHardwareAccelerated) return false
        return drawGlass(scope, text, paint, x, baseline, origin, backdrop, layer)
    }

    @RequiresApi(33)
    private fun drawGlass(scope: DrawScope, text: String, paint: Paint, x: Float, baseline: Float, origin: Offset,
        backdrop: HazeState, layer: GraphicsLayer): Boolean = with(scope) {
        val program = shader ?: runCatching { RuntimeShader(GLASS_TEXT_SHADER) }.onFailure { failed = true }.getOrNull()
            ?.also { shader = it } ?: return false
        val fm = paint.fontMetrics
        val width = paint.measureText(text)
        val pad = ceil(blurRadius(paint) * 1.6f + 2f)
        val left = when (paint.textAlign) {
            Paint.Align.CENTER -> x - width / 2f
            Paint.Align.RIGHT -> x - width
            else -> x
        } - pad
        val top = baseline + fm.ascent - pad
        val w = ceil(width + 2 * pad).toInt().coerceAtLeast(1)
        val h = ceil(fm.descent - fm.ascent + 2 * pad).toInt().coerceAtLeast(1)
        val maskKey = listOf(text, paint.typeface, paint.textSize, paint.textScaleX, paint.letterSpacing, paint.isFakeBoldText, w, h)
        if (maskKey != key || coverage == null) {
            rebuildMasks(text, paint, w, h, pad, baseline - top)
            key = maskKey
            program.setInputShader("coverage", BitmapShader(coverage!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            program.setInputShader("height", BitmapShader(heightMap!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            program.setFloatUniform("bend", paint.textSize * .085f)
            program.setFloatUniform("light", -.6f, -.8f)
            program.setFloatUniform("lift", .18f)
            program.setFloatUniform("spec", .95f)
            layer.renderEffect = RenderEffect.createRuntimeShaderEffect(program, "content").asComposeRenderEffect()
        }
        val areas = backdrop.areas
        var drew = false
        layer.record(this, layoutDirection, IntSize(w, h)) {
            for (area in areas) {
                val content = area.contentLayer?.takeUnless { it.isReleased || it.size.width <= 0 } ?: continue
                val position = area.positionOnScreen.takeIf { it.isSpecified } ?: continue
                translate(position.x - origin.x - left, position.y - origin.y - top) { drawLayer(content) }
                drew = true
            }
        }
        if (!drew) return false
        translate(left, top) { drawLayer(layer) }
        true
    }

    private fun blurRadius(paint: Paint) = max(2f, paint.textSize * .045f)

    private fun rebuildMasks(text: String, paint: Paint, w: Int, h: Int, pad: Float, baselineInMask: Float) {
        coverage?.recycle(); heightMap?.recycle()
        val sharp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val soft = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val mask = Paint(paint).apply {
            textAlign = Paint.Align.LEFT; clearShadowLayer(); color = android.graphics.Color.WHITE
        }
        AndroidCanvas(sharp).drawText(text, pad, baselineInMask, mask)
        mask.maskFilter = BlurMaskFilter(blurRadius(paint), BlurMaskFilter.Blur.NORMAL)
        AndroidCanvas(soft).drawText(text, pad, baselineInMask, mask)
        coverage = sharp; heightMap = soft
    }

    fun release() {
        coverage?.recycle(); heightMap?.recycle(); coverage = null; heightMap = null; key = null
    }
}
