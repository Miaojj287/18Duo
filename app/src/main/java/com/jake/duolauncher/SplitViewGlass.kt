package com.jake.duolauncher

import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.sin

/**
 * One Liquid-Glass slab drawn on a plain View canvas (the navigation stage is not Compose). It is
 * Home's glass material in its frosted pane role ([DuoGlassRole.Pane]: the milky, strongly blurred
 * slabs of the owner's iPhone Duo recording, bright rim) via [duoGlassSpec] → [LiquidGlassMaterial], and the same
 * AGSL lens and specular-rim shaders ([LIQUID_LENS_SHADER], [LIQUID_RIM_SHADER]); only the
 * backdrop source differs: the stage hands over a lambda that draws exactly what is under the pane
 * (its blurred Home / wallpaper), recorded into a RenderNode the size of the pane.
 *
 * Per frame: one RenderNode re-recording (two node draws, no bitmap copy) and one effect chain the
 * size of the pane. Shaders that fail to compile only drop the lens / rim, never the pane.
 */
internal class SplitViewGlass(name: String) {
    private val node = RenderNode(name).apply { setHasOverlappingRendering(true) }
    private val clip = Path()
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lens: RuntimeShader? = null
    private var rim: RuntimeShader? = null
    private var shadersFailed = false
    private val radii = FloatArray(4)

    /**
     * Draws the pane at [left]…[bottom] (px) with corner [radius] (px) and [alpha]. [backdrop]
     * draws the full-screen backdrop in screen px; [dark] picks the graphite recipe.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float, alpha: Float,
        density: Float, dark: Boolean, backdrop: (Canvas) -> Unit) {
        val w = right - left; val h = bottom - top
        if (alpha <= .003f || w < 2f || h < 2f) return
        val m = duoGlassSpec(DuoGlassRole.Pane, dark).material
        val r = radius.coerceAtMost(minOf(w, h) / 2f)
        rect.set(left, top, right, bottom)
        clip.reset(); clip.addRoundRect(rect, r, r, Path.Direction.CW)
        val a = (255 * alpha.coerceIn(0f, 1f)).toInt()

        // A soft contact shadow lifts the slab off the blurred Home (photo 9).
        // Only outside the slab: the glass is translucent while it fades, a fill under it would show.
        shadowPaint.color = android.graphics.Color.BLACK
        shadowPaint.setShadowLayer(22f * density, 0f, 6f * density, android.graphics.Color.argb((40 * alpha).toInt(), 0, 0, 0))
        canvas.save(); canvas.clipOutPath(clip); canvas.drawRoundRect(rect, r, r, shadowPaint); canvas.restore()

        val layer = canvas.saveLayerAlpha(left - 1f, top - 1f, right + 1f, bottom + 1f, a)
        canvas.clipPath(clip)
        var sampled = false
        if (canvas.isHardwareAccelerated) {
            node.setPosition(0, 0, w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
            val rec = node.beginRecording()
            rec.translate(-left, -top)
            backdrop(rec)
            node.endRecording()
            node.setRenderEffect(effect(m, w, h, r, density))
            canvas.save(); canvas.translate(left, top); canvas.drawRenderNode(node); canvas.restore()
            sampled = true
        }
        if (!sampled) { fill.color = m.fallback.toArgb(); canvas.drawRect(rect, fill) }
        fill.color = m.tint.toArgb(); canvas.drawRect(rect, fill)
        // The slab's edge in profile (Apple's thin grey ring), then the specular rim lit from the top-left.
        if (m.edgeShade.alpha > 0f) {
            shadePaint.strokeWidth = m.edgeShadeWidth.value * density * 2f
            shadePaint.color = m.edgeShade.toArgb()
            canvas.drawRoundRect(rect, r, r, shadePaint)
        }
        rimPaint.strokeWidth = m.rimWidth.value * density * 2f
        val rimShader = rimShader(m, w, h, r)
        if (rimShader != null && Build.VERSION.SDK_INT >= 33) {
            rimPaint.shader = rimShader; rimPaint.color = android.graphics.Color.WHITE
            canvas.save(); canvas.translate(left, top)
            rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, r, r, rimPaint)
            canvas.restore()
            rimPaint.shader = null
        } else {
            rimPaint.color = m.rim.toArgb(); canvas.drawRoundRect(rect, r, r, rimPaint)
        }
        canvas.restoreToCount(layer)
    }

    fun release() { node.discardDisplayList() }

    private fun effect(m: LiquidGlassMaterial, w: Float, h: Float, r: Float, density: Float): RenderEffect? {
        var effect: RenderEffect? = null
        val blur = m.blur.value * density
        if (blur >= .5f) effect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        liquidColorMatrix(m.saturation, m.brightness, m.contrast)?.let { matrix ->
            val filter = ColorMatrixColorFilter(matrix)
            effect = effect?.let { RenderEffect.createColorFilterEffect(filter, it) } ?: RenderEffect.createColorFilterEffect(filter)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val shader = lensShader()
            val height = minOf(m.refractionHeight.value * density * 1.6f, minOf(w, h) / 2f)
            val amount = m.refractionAmount.value * density * 1.4f
            if (shader != null && height >= 1f && amount > 0f) {
                radii.fill(r)
                shader.setFloatUniform("size", w, h)
                shader.setFloatUniform("cornerRadii", radii)
                shader.setFloatUniform("refractionHeight", height)
                shader.setFloatUniform("refractionAmount", -amount)
                shader.setFloatUniform("depthEffect", m.depth.coerceIn(0f, 1f))
                shader.setFloatUniform("dispersion", m.dispersion.coerceIn(0f, 1f))
                val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                effect = effect?.let { RenderEffect.createChainEffect(lensEffect, it) } ?: lensEffect
            }
        }
        return effect
    }

    private fun lensShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33 || shadersFailed) return null
        return lens ?: runCatching { RuntimeShader(LIQUID_LENS_SHADER) }.onFailure { shadersFailed = true }.getOrNull()?.also { lens = it }
    }

    private fun rimShader(m: LiquidGlassMaterial, w: Float, h: Float, r: Float): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33 || shadersFailed) return null
        val shader = rim ?: runCatching { RuntimeShader(LIQUID_RIM_SHADER) }.onFailure { shadersFailed = true }.getOrNull()?.also { rim = it }
            ?: return null
        val angle = Math.toRadians(-112.0)
        shader.setFloatUniform("size", w, h)
        radii.fill(r)
        shader.setFloatUniform("cornerRadii", radii)
        shader.setFloatUniform("lightDirection", cos(angle).toFloat(), sin(angle).toFloat())
        shader.setColorUniform("color", m.rim.toArgb())
        shader.setFloatUniform("ambient", m.rimAmbient.coerceIn(0f, 1f))
        shader.setFloatUniform("falloff", 2.2f)
        return shader
    }
}
