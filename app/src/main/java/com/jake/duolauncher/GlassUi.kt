package com.jake.duolauncher

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal enum class DuoGlassRole {
    Control,
    SettingsRow,
    Dock,
    Card,
    Elevated,
    Screen,
    Floating,
    WidgetFrame,
    /** Split View panes (r11-switcher): frosted light slabs, iPhone Duo recording f036–f050. */
    Pane,
    /** Home folder icons and the folder-forming platter (round 16, iOS 26 folder reference). */
    Folder,
    /** The open folder's platter: clear glass over the blurred Home (round 16 rename reference). */
    FolderPanel,
}

@Immutable
internal data class DuoGlassSpec(
    val backgroundColor: Color,
    val tintColor: Color,
    val fallbackColor: Color,
    val contentColor: Color,
    val highlightColor: Color,
    val borderLightColor: Color,
    val blurRadius: Dp,
    val noiseFactor: Float,
    val elevation: Dp,
    /** Largest press growth for capsule/circle controls (iOS 26 glass buttons swell); 1 = none. */
    val pressedScale: Float,
    val material: LiquidGlassMaterial,
)

internal val LocalDuoGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/**
 * Liquid Glass is part of the visual material, not an idle-only effect.
 * Keep the sampled backdrop active while pages, widgets, and fold planes move.
 */
internal fun duoGlassBackdropEnabled(): Boolean = true

internal val DuoGlassRoleKey = SemanticsPropertyKey<String>("DuoGlassRole")
internal var SemanticsPropertyReceiver.duoGlassRole by DuoGlassRoleKey
internal val DuoGlassPressedKey = SemanticsPropertyKey<Boolean>("DuoGlassPressed")
internal var SemanticsPropertyReceiver.duoGlassPressed by DuoGlassPressedKey

/** Per-role recipe: one row per role keeps the material scale reviewable in one place. */
private class GlassRecipe(
    val blur: Float, val refraction: Float, val bend: Float, val depth: Float, val dispersion: Float,
    val saturation: Float, val lightTint: Float, val darkTint: Float, val fallback: Float,
    val elevation: Float, val rim: Float, val rimAmbient: Float, val grows: Boolean,
    /** Home Screen chrome (Dock, Search) stays clear glass: it carries icons, not text. */
    val clear: Boolean = false,
    /** Light caught inside the bevel: peak white alpha and its depth (dp). */
    val edgeGlow: Float = 0f, val edgeGlowWidth: Float = 0f,
    /** Gain on the backdrop seen through the bevel (see [LiquidGlassMaterial.edgeLight]). */
    val edgeLight: Float = 0f,
)

// Round 13 (owner: "液态玻璃效果不够好", iPhone Duo photo of the Dock): the wallpaper must visibly bend at
// the rim. Values follow Kyant's Backdrop catalog, the closest open-source match to iOS 26: lens bend ≈ 1.5–2×
// the band (buttons 12/24 dp, Control Center 24/48 dp), vibrancy ≤ 1.5 (1.25 on the clear Home chrome: sand
// wallpapers turned the Dock amber on the phone), a nearly clear body, and a thin
// added-light rim lit at two opposite corners instead of a white outline.
private fun recipe(role: DuoGlassRole): GlassRecipe = when (role) {
    // Search / small buttons: nearly clear, lensing strong for their size, swell on press.
    DuoGlassRole.Control -> GlassRecipe(2f, 10f, 18f, .30f, .30f, 1.25f, .10f, .08f, .30f, 2f, .60f, .10f, true, clear = true)
    // Nested rows already sit on a sheet: no second backdrop, a flat light fill and hairline.
    DuoGlassRole.SettingsRow -> GlassRecipe(0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, .12f, 0f, .12f, 1f, false)
    // Home Screen Dock: clear glass, the wallpaper bends at the rim (HIG hero, outer screen).
    DuoGlassRole.Dock -> GlassRecipe(3f, 14f, 24f, .15f, .30f, 1.25f, .10f, .06f, .60f, 3f, .60f, .10f, false, clear = true)
    DuoGlassRole.Card -> GlassRecipe(14f, 14f, 22f, .25f, .15f, 1.4f, .16f, .22f, .40f, 4f, .45f, .10f, false)
    // Folder panel / App Library: frosted enough for 3×3 artwork to read.
    DuoGlassRole.Elevated -> GlassRecipe(24f, 18f, 28f, .25f, .15f, 1.4f, .18f, .22f, .52f, 10f, .45f, .10f, false)
    // Sheets add their own body tint on top; the glass gives the frosted edge and rim.
    DuoGlassRole.Screen -> GlassRecipe(30f, 16f, 24f, .15f, 0f, 1.3f, .06f, .10f, .66f, 0f, .40f, .15f, false)
    // Context menus, edit pills, page-dot pill: iOS "regular" glass, frosted for legible text.
    DuoGlassRole.Floating -> GlassRecipe(20f, 14f, 24f, .25f, .15f, 1.4f, .18f, .24f, .56f, 9f, .50f, .10f, true)
    // Clear widgets and App Library platters: frosted, the caller lays one uniform tone on top.
    DuoGlassRole.WidgetFrame -> GlassRecipe(26f, 14f, 22f, .20f, .12f, 1.3f, .12f, .18f, .25f, 2f, .55f, .12f, false)
    // Split View panes: milky frosted glass (blur ≈ 36 dp; white tint 0.24, the light-glass ceiling once
    // packed to 8 bits — the recording reads ≈ 0.26) with a bright rim, as in the Duo recording.
    DuoGlassRole.Pane -> GlassRecipe(36f, 16f, 26f, .22f, .10f, 1.3f, .24f, .22f, .62f, 10f, .55f, .15f, false)
    // Home folder icon (round 16, owner's iOS 26 reference): a thick small slab. The wallpaper shows through
    // softly blurred and magnified, bends hard at the bevel, which catches light (edge glow) under a bright
    // two-corner rim; a light frost keeps the 3×3 icons readable on busy wallpapers.
    DuoGlassRole.Folder -> GlassRecipe(5f, 13f, 20f, .45f, .25f, 1.35f, .12f, .16f, .38f, 3f, .48f, .10f, false,
        edgeGlow = .03f, edgeGlowWidth = 6f, edgeLight = .45f)
    // Open folder platter (iOS 26 rename screenshot): Home behind is already blurred, so the slab is nearly clear,
    // lifted a touch, with a lensing bevel and a bright two-corner rim; no graphite, white labels keep their shadow.
    DuoGlassRole.FolderPanel -> GlassRecipe(4f, 20f, 30f, .20f, .12f, 1.12f, .04f, .12f, .55f, 6f, .45f, .10f, false,
        clear = true, edgeLight = .12f)
}

internal fun duoGlassSpec(
    role: DuoGlassRole,
    dark: Boolean,
    pressed: Boolean = false,
): DuoGlassSpec {
    val r = recipe(role)
    val base = if (dark) Color(0xFF1C1C1E) else Color(0xFFF9F9FB)
    val graphite = Color(0xFF1C1C1E)
    val content = if (dark) Color(0xFFF5F5F7) else Color(0xFF202124)
    val pressBoost = if (pressed) .025f else 0f
    val settingsRow = role == DuoGlassRole.SettingsRow
    // Light glass brightens what it shows; dark glass (auto-picked from wallpaper luminance) sinks it.
    val tint = when {
        settingsRow -> Color.White.copy(alpha = (if (dark) .055f else .22f) + pressBoost)
        // Clear glass always lifts its backdrop a little, as the Dock does in both appearances.
        r.clear -> Color.White.copy(alpha = (if (dark) r.lightTint * .8f else r.lightTint) + pressBoost)
        dark -> graphite.copy(alpha = r.darkTint)
        else -> Color.White.copy(alpha = r.lightTint + pressBoost)
    }
    val fallback = if (settingsRow) tint
        else base.copy(alpha = (r.fallback + (if (dark) .12f else 0f) + pressBoost).coerceAtMost(.96f))
    val rimAlpha = (if (dark) r.rim * .8f else r.rim) + if (pressed) .08f else 0f
    val rim = Color.White.copy(alpha = rimAlpha.coerceAtMost(.9f))
    val material = LiquidGlassMaterial(
        blur = r.blur.dp,
        refractionHeight = r.refraction.dp,
        refractionAmount = r.bend.dp,
        depth = r.depth,
        dispersion = r.dispersion,
        saturation = r.saturation,
        brightness = when {
            settingsRow -> 0f
            dark -> if (r.clear) .03f else -.03f
            else -> .04f
        },
        contrast = if (settingsRow || dark) 1f else .96f,
        tint = if (settingsRow) Color.Transparent else tint,
        fallback = fallback,
        rim = rim,
        rimAmbient = r.rimAmbient,
        rimWidth = if (settingsRow) .5.dp else if (role == DuoGlassRole.Folder) 1.dp else .7.dp,
        edgeShade = if (settingsRow) Color.Transparent else Color.Black.copy(alpha = if (dark) .06f else .03f),
        edgeGlow = Color.White.copy(alpha = if (dark) r.edgeGlow * .7f else r.edgeGlow),
        edgeGlowWidth = r.edgeGlowWidth.dp,
        edgeLight = if (dark) r.edgeLight * .7f else r.edgeLight,
    )
    return DuoGlassSpec(
        backgroundColor = Color.Transparent,
        tintColor = tint,
        fallbackColor = fallback,
        contentColor = content,
        highlightColor = Color.White.copy(alpha = if (settingsRow) 0f else if (pressed) .13f else .07f),
        borderLightColor = rim,
        blurRadius = r.blur.dp,
        noiseFactor = 0f,
        elevation = r.elevation.dp,
        pressedScale = if (r.grows) 1.08f else 1f,
        material = material,
    )
}

@Composable
internal fun duoGlassContentColor(role: DuoGlassRole): Color =
    duoGlassSpec(role, LocalDuoPalette.current.dark).contentColor

/** Snappy, slightly bouncy press like iOS 26 glass buttons; instant under Reduce Motion. */
private fun pressSpec(): AnimationSpec<Float> =
    if (LauncherMotion.reduced) snap() else LauncherMotion.glassPress

/** iOS glass controls swell by a few points; capped so large pills barely move. */
internal fun glassPressScale(progress: Float, minDimensionPx: Float, growPx: Float, maxScale: Float): Float {
    if (progress <= 0f || minDimensionPx <= 0f || maxScale <= 1f) return 1f
    return 1f + progress.coerceIn(0f, 1.2f) * minOf(growPx / minDimensionPx, maxScale - 1f)
}

/**
 * Measurement-neutral Liquid Glass material. The caller owns size, padding and input; this
 * modifier only changes drawing and exposes testable material semantics. Rounded shapes are
 * drawn with Apple continuous corners. API 33+ refracts the shared Haze backdrop with AGSL;
 * API 31–32 keep Haze's blur with the same tint, rim and press response.
 */
@Composable
internal fun Modifier.duoGlass(
    role: DuoGlassRole,
    shape: Shape,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Boolean = true,
    backdropState: HazeState? = LocalDuoGlassBackdrop.current,
    /**
     * Light or dark material; follows the system appearance. Surfaces that always carry white
     * Home Screen labels (the open folder platter) pass true so the labels stay legible in Light.
     */
    dark: Boolean = LocalDuoPalette.current.dark,
    /**
     * A body tone the caller needs for legibility (menus, Spotlight, widget platters). It is laid
     * into the glass under the rim and press glow; a `.background()` after the glass would paint
     * over the rim and turn the slab into a flat milky pill.
     */
    frost: Color = Color.Transparent,
): Modifier {
    val noPress: State<Boolean> = remember { mutableStateOf(false) }
    val pressed = (interactionSource?.collectIsPressedAsState() ?: noPress).value
    val spec = duoGlassSpec(role, dark, pressed)
    val press = remember { LiquidGlassPress() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        progress.animateTo(if (pressed) 1f else 0f, pressSpec()) { press.progress = value }
        press.progress = progress.value
    }
    if (interactionSource != null) LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Press) press.position = it.pressPosition }
    }
    val glassShape = remember(shape) { shape.continuousCorners() }
    val capsule = remember(glassShape) { CapsuleCheck(glassShape) }
    val material = if (frost.alpha > 0f) spec.material.copy(tint = frost.compositeOver(spec.material.tint)) else spec.material
    val sample = backdrop && backdropState != null && material.blur > 0.dp
    val liquid = Build.VERSION.SDK_INT >= 33
    val hazeStyle = remember(spec) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tint = HazeTint(Color.Transparent),
            blurRadius = spec.blurRadius,
            noiseFactor = 0f,
            fallbackTint = HazeTint(spec.fallbackColor),
        )
    }
    val surface = if (sample && !liquid) {
        Modifier.hazeEffect(backdropState!!, hazeStyle)
            .liquidGlass(null, glassShape, material, press, sampleBackdrop = false, fillWhenEmpty = false)
    } else {
        Modifier.liquidGlass(if (sample) backdropState else null, glassShape, material, press, sampleBackdrop = sample)
    }
    val grows = spec.pressedScale > 1f
    return this
        .graphicsLayer {
            this.shape = glassShape
            clip = true
            shadowElevation = spec.elevation.toPx()
            ambientShadowColor = Color.Black.copy(alpha = .10f)
            spotShadowColor = Color.Black.copy(alpha = .16f)
            // Reduce Motion keeps the press highlight but not the swell (no shape morph).
            val scale = if (grows && !LauncherMotion.reduced && capsule.test(size, this))
                glassPressScale(press.progress, size.minDimension, 3.dp.toPx(), spec.pressedScale) else 1f
            scaleX = scale
            scaleY = scale
        }
        .then(surface)
        .semantics {
            duoGlassRole = role.name
            duoGlassPressed = pressed
        }
}

private fun isCapsule(outline: Outline, minDimension: Float): Boolean =
    outline is Outline.Rounded && outline.roundRect.topLeftCornerRadius.x >= minDimension / 2f - .5f

/**
 * Whether [shape] is a capsule at a given size, remembered per size: the press swell reads it in
 * a layer block that reruns on every press frame, where building an Outline (a Path for
 * continuous corners) each time was a per-frame allocation (code review P2-8c).
 */
private class CapsuleCheck(private val shape: Shape) {
    private var size = Size.Unspecified
    private var density = 0f
    private var result = false
    fun test(size: Size, density: Density): Boolean {
        if (size != this.size || density.density != this.density) {
            result = isCapsule(shape.createOutline(size, LayoutDirection.Ltr, density), size.minDimension)
            this.size = size; this.density = density.density
        }
        return result
    }
}
