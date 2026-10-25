// HazeArea/HazeSourceNode are read directly so the glass reuses Haze's single backdrop capture.
@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.jake.duolauncher

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.HazeArea
import dev.chrisbanes.haze.HazeSourceNode
import dev.chrisbanes.haze.HazeState
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * iOS 26 Liquid Glass recipe for one surface. Distances are dp so a role reads the same on the
 * cover (2.75 px/dp) and inner panel.
 */
@Immutable
internal data class LiquidGlassMaterial(
    /** Gaussian blur of the sampled backdrop. Liquid Glass is mostly clear; menus frost more. */
    val blur: Dp,
    /** Width of the refracting band along the rim. */
    val refractionHeight: Dp,
    /** Largest displacement at the very rim (sampled inward, so content bends toward the edge). */
    val refractionAmount: Dp,
    /** 0..1 – adds a radial component so the whole slab magnifies slightly, like a thick lens. */
    val depth: Float,
    /** 0..1 – RGB split at the rim; bounded to 3 samples in the band. */
    val dispersion: Float,
    /** Vibrancy: Apple boosts saturation of what shows through glass. */
    val saturation: Float,
    val brightness: Float,
    val contrast: Float,
    /** Painted over the refracted backdrop (light glass = white, dark glass = graphite). */
    val tint: Color,
    /** Used when there is no backdrop or no RenderEffect (API < 31, software canvas, captures). */
    val fallback: Color,
    /** Peak colour of the specular rim; alpha is its strength. */
    val rim: Color,
    /** Fraction of the rim that stays lit on edges facing away from the light. */
    val rimAmbient: Float,
    val rimWidth: Dp,
    /** Thin darker band just inside the rim: the slab's edge seen in profile (Apple's grey ring). */
    val edgeShade: Color = Color.Transparent,
    val edgeShadeWidth: Dp = Dp(1.5f),
    /** Added light inside the bevel, fading inward over [edgeGlowWidth] (lit like the rim); alpha is its peak. */
    val edgeGlow: Color = Color.Transparent,
    val edgeGlowWidth: Dp = Dp(0f),
    /** Gain on what shows through the refraction band at the very rim (0 = none, 0.5 = +50 %), easing inward. */
    val edgeLight: Float = 0f,
)

/** Press feedback shared by the glass node and the scale layer. */
@Stable
internal class LiquidGlassPress {
    var progress by mutableFloatStateOf(0f)
    var position by mutableStateOf(Offset.Unspecified)
}

/** Haze keeps its traversal key enum internal; a detached source node hands it over publicly. */
private val hazeSourceTraverseKey: Any by lazy { HazeSourceNode(HazeState(), 0f, null).traverseKey }

/** Light comes from the top-left, as on iOS. */
private const val LIGHT_ANGLE_DEGREES = -112f
/** The press glow at full strength; its fade is applied as draw alpha. */
private val GLOW_COLORS = listOf(Color.White.copy(alpha = .10f), Color.Transparent)

/**
 * Draws Liquid Glass behind this node's content: the [backdrop] areas recorded by Haze's
 * `hazeSource` are re-drawn (no second capture) through one RenderEffect chain
 * blur → colour matrix → AGSL lens on API 33+, then tint, press glow and specular rim.
 *
 * Cost per glass surface and frame: one offscreen of the surface's own size, a downsampled
 * Skia blur, one fused colour-matrix pass and one lens pass (1 texture sample per pixel, ≤ 3 in
 * the rim band); the rim is a stroked path. All bounded by the glass area, independent of how much
 * moves underneath. The caller must clip (duoGlass does this in its graphics layer).
 *
 * [sampleBackdrop] false keeps tint/rim/press only (API 31–32 use Haze's blur underneath).
 */
internal fun Modifier.liquidGlass(
    backdrop: HazeState?,
    shape: Shape,
    material: LiquidGlassMaterial,
    press: LiquidGlassPress? = null,
    sampleBackdrop: Boolean = true,
    fillWhenEmpty: Boolean = true,
): Modifier = this then LiquidGlassElement(backdrop, shape, material, press, sampleBackdrop, fillWhenEmpty)

private data class LiquidGlassElement(
    val backdrop: HazeState?,
    val shape: Shape,
    val material: LiquidGlassMaterial,
    val press: LiquidGlassPress?,
    val sampleBackdrop: Boolean,
    val fillWhenEmpty: Boolean,
) : ModifierNodeElement<LiquidGlassNode>() {
    override fun create() = LiquidGlassNode(backdrop, shape, material, press, sampleBackdrop, fillWhenEmpty)
    override fun update(node: LiquidGlassNode) {
        node.backdrop = backdrop
        node.shape = shape
        node.material = material
        node.press = press
        node.sampleBackdrop = sampleBackdrop
        node.fillWhenEmpty = fillWhenEmpty
        node.invalidateDraw()
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "liquidGlass"
        properties["material"] = material
    }
}

private class LiquidGlassNode(
    var backdrop: HazeState?,
    var shape: Shape,
    var material: LiquidGlassMaterial,
    var press: LiquidGlassPress?,
    var sampleBackdrop: Boolean,
    var fillWhenEmpty: Boolean,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var positionOnScreen = Offset.Unspecified
    /** This glass's scale on screen (an ancestor's zoom: app open/return, unlock arrival, Home shrink). */
    private var screenScaleX = 1f
    private var screenScaleY = 1f
    private var backdropLayer: GraphicsLayer? = null
    private var lensShader: Any? = null
    private var rimShader: Any? = null
    private var edgeGlowShader: Any? = null
    private var edgeGlowSize = Size.Unspecified
    private var edgeGlowMaterial: LiquidGlassMaterial? = null
    private val edgeGlowRadii = FloatArray(4)
    private var edgeGlowBrush: Brush? = null
    /** A shader that fails to compile disables only that layer of the effect, never the launcher. */
    private var shadersUnavailable = false
    // Glass inside a moving page redraws every frame while Home pages: keep the outline (a Path
    // for continuous corners), its radii and the effect keys instead of rebuilding them per frame.
    private var cachedOutline: Outline? = null
    private var cachedRadii: FloatArray = FloatArray(4)
    private var outlineShape: Shape? = null
    private var outlineSize = Size.Unspecified
    private var outlineDirection: LayoutDirection? = null
    private var outlineDensity = 0f
    private var effectSize = Size.Unspecified
    private var effectMaterial: LiquidGlassMaterial? = null
    private var effectDensity = 0f
    private val effectRadii = FloatArray(4)
    private var rimSize = Size.Unspecified
    private var rimMaterial: LiquidGlassMaterial? = null
    private val rimRadii = FloatArray(4)
    private var rimBrushCache: Brush? = null
    // Strokes, the press glow and the pre-33 rim gradient, rebuilt only when their inputs change.
    private var edgeStroke: Stroke? = null
    private var rimStroke: Stroke? = null
    private var glowBrush: Brush? = null
    private var glowCenter = Offset.Unspecified
    private var glowRadius = 0f
    private var fallbackRimBrush: Brush? = null
    private var fallbackRimMaterial: LiquidGlassMaterial? = null
    private var fallbackRimSize = Size.Unspecified

    override fun onAttach() {
        backdropLayer = requireGraphicsContext().createGraphicsLayer()
    }

    override fun onDetach() {
        backdropLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        backdropLayer = null
        effectMaterial = null
        rimMaterial = null
        rimBrushCache = null
        edgeGlowBrush = null
        edgeGlowMaterial = null
        glowBrush = null
        fallbackRimBrush = null
        fallbackRimMaterial = null
        cachedOutline = null
        positionOnScreen = Offset.Unspecified
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val position = coordinates.positionOnScreen()
        val (sx, sy) = screenScaleOf(coordinates)
        if (position != positionOnScreen || sx != screenScaleX || sy != screenScaleY) {
            positionOnScreen = position
            screenScaleX = sx; screenScaleY = sy
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        if (size.width < 1f || size.height < 1f) return drawContent()
        val outline = outlineFor()
        val radii = cachedRadii
        val sampled = sampleBackdrop && drawBackdrop(radii)
        if (!sampled && fillWhenEmpty) drawOutline(outline, material.fallback)
        if (material.tint.alpha > 0f) drawOutline(outline, material.tint)
        drawEdgeGlow(outline, radii)
        drawPressGlow(outline)
        drawEdgeShade(outline)
        drawRim(outline, radii)
        drawContent()
    }

    private fun DrawScope.drawBackdrop(radii: FloatArray): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val state = backdrop ?: return false
        val layer = backdropLayer ?: return false
        val origin = positionOnScreen.takeIf { it.isSpecified } ?: return false
        if (!drawContext.canvas.nativeCanvas.isHardwareAccelerated) return false
        val areas = visibleAreas(state)
        if (areas.isEmpty()) return false
        var drewAny = false
        layer.record(this, layoutDirection, IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
            // Round 13 (owner photo r13/5: the Dock showed the icons up and to its left while Home zoomed): every
            // backdrop pixel must come from what is physically under this pixel on screen. Haze records a source
            // unscaled at its screen position, and this node draws in its own (possibly zoomed) space, so both
            // zooms are applied: screen = sourcePos + sourceScale·p, local = (screen − origin) / glassScale.
            val gx = screenScaleX.takeIf { it > .01f } ?: 1f
            val gy = screenScaleY.takeIf { it > .01f } ?: 1f
            for (area in areas) {
                val content = area.contentLayer?.takeUnless { it.isReleased || it.size.width <= 0 } ?: continue
                val position = area.positionOnScreen.takeIf { it.isSpecified } ?: continue
                val source = area.key as? BackdropSourceScale
                val ax = source?.scaleX ?: 1f
                val ay = source?.scaleY ?: 1f
                if (gx == 1f && gy == 1f && ax == 1f && ay == 1f) {
                    translate(position.x - origin.x, position.y - origin.y) { drawLayer(content) }
                } else withTransform({
                    scale(1f / gx, 1f / gy, Offset.Zero)
                    translate(position.x - origin.x, position.y - origin.y)
                    scale(ax, ay, Offset.Zero)
                }) { drawLayer(content) }
                drewAny = true
            }
        }
        if (!drewAny) return false
        updateEffect(layer, radii)
        drawLayer(layer)
        return true
    }

    private fun ContentDrawScope.outlineFor(): Outline {
        val cached = cachedOutline
        if (cached != null && outlineShape === shape && outlineSize == size && outlineDirection == layoutDirection &&
            outlineDensity == density) return cached
        val outline = shape.createOutline(size, layoutDirection, this)
        cachedRadii = glassCornerRadii(shape, outline, size, layoutDirection, this)
        cachedOutline = outline
        outlineShape = shape; outlineSize = size; outlineDirection = layoutDirection; outlineDensity = density
        return outline
    }

    /** Same filtering as Haze: never sample a source that encloses this glass (no feedback loop). */
    private fun visibleAreas(state: HazeState): List<HazeArea> {
        val ancestor = (findNearestAncestor(hazeSourceTraverseKey) as? HazeSourceNode)
            ?.takeIf { it.state == state }
        val areas = state.areas
        return if (ancestor == null && areas.size <= 1) areas
            else areas.filter { ancestor == null || it.zIndex < ancestor.zIndex }.sortedBy { it.zIndex }
    }

    @RequiresApi(33)
    private fun DrawScope.updateEffect(layer: GraphicsLayer, radii: FloatArray) {
        val m = material
        if (effectMaterial == m && effectSize == size && effectDensity == density && effectRadii.contentEquals(radii)) return
        effectMaterial = m; effectSize = size; effectDensity = density; radii.copyInto(effectRadii)
        var effect: RenderEffect? = null
        val blur = m.blur.toPx()
        if (blur >= .5f) effect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
        liquidColorMatrix(m.saturation, m.brightness, m.contrast)?.let { matrix ->
            val filter = ColorMatrixColorFilter(matrix)
            effect = effect?.let { RenderEffect.createColorFilterEffect(filter, it) } ?: RenderEffect.createColorFilterEffect(filter)
        }
        // Physically a slab only bends what lies under its own bevel: the band stays within a quarter of the
        // slab's thickness and the bend within 1.5 bands, so a narrow Dock or a small button never smears
        // content from far across itself (owner r13/5: an icon "stretched" through the Dock).
        val height = minOf(m.refractionHeight.toPx(), size.minDimension * .25f)
        val amount = minOf(m.refractionAmount.toPx(), height * 1.5f)
        val shader = if (height >= 1f && amount > 0f) lensShaderOrNull() else null
        if (shader != null) {
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("cornerRadii", radii)
            shader.setFloatUniform("refractionHeight", height)
            // Negative: sample toward the inside, so content near the rim is pulled outward.
            shader.setFloatUniform("refractionAmount", -amount)
            shader.setFloatUniform("depthEffect", m.depth.coerceIn(0f, 1f))
            shader.setFloatUniform("dispersion", m.dispersion.coerceIn(0f, 1f))
            shader.setFloatUniform("edgeLight", m.edgeLight.coerceIn(0f, 2f))
            val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
            effect = effect?.let { RenderEffect.createChainEffect(lens, it) } ?: lens
        }
        layer.renderEffect = effect?.asComposeRenderEffect()
    }

    private fun DrawScope.drawPressGlow(outline: Outline) {
        val press = press ?: return
        val progress = press.progress
        if (progress <= .001f) return
        // iOS lights the glass where the finger is, softly; the whole slab only lifts a touch.
        drawOutline(outline, Color.White, alpha = .03f * progress, blendMode = BlendMode.Plus)
        val center = press.position.takeIf { it.isSpecified } ?: Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension
        // The press animates [progress] every frame: fade one cached gradient instead of building a new one.
        val glow = glowBrush?.takeIf { glowCenter == center && glowRadius == radius }
            ?: Brush.radialGradient(GLOW_COLORS, center = center, radius = radius).also {
                glowBrush = it; glowCenter = center; glowRadius = radius
            }
        drawOutline(outline, glow, alpha = progress, blendMode = BlendMode.Plus)
    }

    private fun stroke(cached: Stroke?, width: Float): Stroke =
        if (cached != null && cached.width == width) cached else Stroke(width)

    private fun DrawScope.drawEdgeShade(outline: Outline) {
        val m = material
        val width = m.edgeShadeWidth.toPx()
        if (m.edgeShade.alpha <= 0f || width <= 0f) return
        val stroke = stroke(edgeStroke, width * 2f).also { edgeStroke = it }
        drawOutline(outline, m.edgeShade, style = stroke)
    }

    private fun DrawScope.drawRim(outline: Outline, radii: FloatArray) {
        val m = material
        val width = m.rimWidth.toPx()
        if (m.rim.alpha <= 0f || width <= 0f) return
        // Stroke centred on the outline; the caller's clip keeps the inner half, i.e. [width].
        val stroke = stroke(rimStroke, width * 2f).also { rimStroke = it }
        val shaded = if (Build.VERSION.SDK_INT >= 33) rimBrush(radii) else null
        // Added light, as on iOS: the rim brightens what is behind it and never paints a grey line.
        if (shaded != null) {
            drawOutline(outline, shaded, style = stroke, blendMode = BlendMode.Plus)
        } else {
            val brush = fallbackRimBrush?.takeIf { fallbackRimMaterial == m && fallbackRimSize == size } ?: run {
                val peak = m.rim
                val soft = peak.copy(alpha = peak.alpha * m.rimAmbient)
                Brush.linearGradient(listOf(peak, soft, soft, peak.copy(alpha = peak.alpha * .6f)),
                    start = Offset.Zero, end = Offset(size.width, size.height))
                    .also { fallbackRimBrush = it; fallbackRimMaterial = m; fallbackRimSize = size }
            }
            drawOutline(outline, brush, style = stroke, blendMode = BlendMode.Plus)
        }
    }

    private fun DrawScope.drawEdgeGlow(outline: Outline, radii: FloatArray) {
        val m = material
        val width = m.edgeGlowWidth.toPx().coerceAtMost(size.minDimension * .3f)
        if (m.edgeGlow.alpha <= 0f || width < 1f || Build.VERSION.SDK_INT < 33) return
        val brush = edgeGlowBrush?.takeIf { edgeGlowMaterial == m && edgeGlowSize == size && edgeGlowRadii.contentEquals(radii) }
            ?: run {
                val shader = edgeGlowShaderOrNull() ?: return
                edgeGlowMaterial = m; edgeGlowSize = size; radii.copyInto(edgeGlowRadii)
                val angle = Math.toRadians(LIGHT_ANGLE_DEGREES.toDouble())
                shader.setFloatUniform("size", size.width, size.height)
                shader.setFloatUniform("cornerRadii", radii)
                shader.setFloatUniform("lightDirection", cos(angle).toFloat(), sin(angle).toFloat())
                shader.setColorUniform("color", m.edgeGlow.toArgb())
                shader.setFloatUniform("ambient", m.rimAmbient.coerceIn(0f, 1f))
                shader.setFloatUniform("width", width)
                ShaderBrush(shader).also { edgeGlowBrush = it }
            }
        drawOutline(outline, brush, blendMode = BlendMode.Plus)
    }

    @RequiresApi(33)
    private fun edgeGlowShaderOrNull(): RuntimeShader? = (edgeGlowShader as? RuntimeShader)
        ?: if (shadersUnavailable) null else runCatching { RuntimeShader(LIQUID_EDGE_GLOW_SHADER) }
            .onFailure { shadersUnavailable = true }.getOrNull()?.also { edgeGlowShader = it }

    @RequiresApi(33)
    private fun lensShaderOrNull(): RuntimeShader? = (lensShader as? RuntimeShader)
        ?: if (shadersUnavailable) null else runCatching { RuntimeShader(LIQUID_LENS_SHADER) }
            .onFailure { shadersUnavailable = true }.getOrNull()?.also { lensShader = it }

    @RequiresApi(33)
    private fun rimShaderOrNull(): RuntimeShader? = (rimShader as? RuntimeShader)
        ?: if (shadersUnavailable) null else runCatching { RuntimeShader(LIQUID_RIM_SHADER) }
            .onFailure { shadersUnavailable = true }.getOrNull()?.also { rimShader = it }

    @RequiresApi(33)
    private fun DrawScope.rimBrush(radii: FloatArray): Brush? {
        val shader = rimShaderOrNull() ?: return null
        val m = material
        rimBrushCache?.let { brush ->
            if (rimMaterial == m && rimSize == size && rimRadii.contentEquals(radii)) return brush
        }
        run {
            rimMaterial = m; rimSize = size; radii.copyInto(rimRadii)
            val angle = Math.toRadians(LIGHT_ANGLE_DEGREES.toDouble())
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("cornerRadii", radii)
            shader.setFloatUniform("lightDirection", cos(angle).toFloat(), sin(angle).toFloat())
            shader.setColorUniform("color", m.rim.toArgb())
            shader.setFloatUniform("ambient", m.rimAmbient.coerceIn(0f, 1f))
            shader.setFloatUniform("falloff", 1.5f)
        }
        return ShaderBrush(shader).also { rimBrushCache = it }
    }
}

/**
 * Corner radii (top-left, top-right, bottom-right, bottom-left, px) for the lens/rim SDF. A
 * continuous corner reaches further along each edge than its nominal radius, so the SDF uses a
 * slightly larger circular radius to stay inside the squircle.
 */
internal fun glassCornerRadii(shape: Shape, outline: Outline, size: Size, layoutDirection: LayoutDirection, density: Density): FloatArray {
    val max = size.minDimension / 2f
    val corner = shape as? CornerBasedShape
    if (corner != null) {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val ts = corner.topStart.toPx(size, density); val te = corner.topEnd.toPx(size, density)
        val be = corner.bottomEnd.toPx(size, density); val bs = corner.bottomStart.toPx(size, density)
        val boost = if (shape is ContinuousRoundedShape) 1f + .28f * shape.smoothing else 1f
        return floatArrayOf(if (ltr) ts else te, if (ltr) te else ts, if (ltr) be else bs, if (ltr) bs else be)
            .map { (it * boost).coerceIn(0f, max) }.toFloatArray()
    }
    return when (outline) {
        is Outline.Rounded -> outline.roundRect.let {
            floatArrayOf(it.topLeftCornerRadius.x, it.topRightCornerRadius.x, it.bottomRightCornerRadius.x, it.bottomLeftCornerRadius.x)
        }
        is Outline.Rectangle -> FloatArray(4)
        is Outline.Generic -> FloatArray(4) { max }
    }
}

/**
 * Brightness/contrast/saturation colour matrix (4×5, Android order) with Rec. 709 luma weights;
 * null when it would be the identity. Brightness is an offset in 0..1 units of full scale.
 */
internal fun liquidColorMatrix(saturation: Float, brightness: Float, contrast: Float): FloatArray? {
    if (saturation == 1f && brightness == 0f && contrast == 1f) return null
    val s = saturation
    val inv = 1f - s
    val r = .2126f * inv; val g = .7152f * inv; val b = .0722f * inv
    val c = contrast
    val t = (.5f - .5f * c + brightness) * 255f
    return floatArrayOf(
        c * (r + s), c * g, c * b, 0f, t,
        c * r, c * (g + s), c * b, 0f, t,
        c * r, c * g, c * (b + s), 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** Screen scale of a laid-out node (the product of its ancestors' zooms), 1 when degenerate. */
internal fun screenScaleOf(coordinates: LayoutCoordinates): Pair<Float, Float> {
    val w = coordinates.size.width.toFloat(); val h = coordinates.size.height.toFloat()
    if (w < 1f || h < 1f) return 1f to 1f
    val o = coordinates.localToScreen(Offset.Zero)
    val sx = (coordinates.localToScreen(Offset(w, 0f)).x - o.x) / w
    val sy = (coordinates.localToScreen(Offset(0f, h)).y - o.y) / h
    return (if (sx > .01f) sx else 1f) to (if (sy > .01f) sy else 1f)
}

/** A Haze source's live screen scale, handed to the glass through the area's key ([measuredHazeSource]). */
internal class BackdropSourceScale {
    @Volatile var scaleX = 1f
    @Volatile var scaleY = 1f
}

/**
 * [hazeSource] that also publishes its screen scale, so Liquid Glass sampling it while either side is
 * zoomed (app open/return, unlock arrival) still refracts exactly what lies beneath it.
 */
@Composable
internal fun Modifier.measuredHazeSource(state: HazeState, zIndex: Float = 0f): Modifier {
    val key = remember { BackdropSourceScale() }
    return onGloballyPositioned {
        if (it.isAttached) { val (sx, sy) = screenScaleOf(it); key.scaleX = sx; key.scaleY = sy }
    }.hazeSource(state, zIndex, key)
}
