package com.jake.duolauncher

import android.os.Build
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * The lock screen's glass slab (round 11): the iOS 26 Lock Screen's bottom block is one piece of
 * light "clear" Liquid Glass that lifts with the finger. Measured on the owner's recording
 * (`lock-slab-edge-crop-f058.png`, f058/f080): the wallpaper shows through blurred (≈ 20–25 pt),
 * lifted by a white tint (≈ 0.2), with a lens band along the rim where the mountain texture bends
 * (≈ 14–18 pt) and a faint RGB fringe, under a thin bright rim; the lower corners are the display's
 * own corners (the bottom of the screen torn off), the upper corners the same large radius.
 *
 * A [LiquidGlassMaterial] of its own rather than a new `DuoGlassRole` (GlassUi.kt belongs to the
 * motion workstream this round); it is drawn by the same [liquidGlass] node the Dock uses, sampling
 * the wallpaper's Haze backdrop.
 */
internal object LockSlabGlass {
    val blur: Dp = 22.dp
    val lensBand: Dp = 16.dp
    val lensBend: Dp = 26.dp
    const val TINT = .17f
    const val DISPERSION = .14f
    const val RIM = .60f

    val material = LiquidGlassMaterial(
        blur = blur,
        refractionHeight = lensBand,
        refractionAmount = lensBend,
        depth = .18f,
        dispersion = DISPERSION,
        saturation = 1.4f,
        brightness = .05f,
        contrast = .97f,
        tint = Color.White.copy(alpha = TINT),
        fallback = Color(0xFFF9F9FB).copy(alpha = .34f),
        rim = Color.White.copy(alpha = RIM),
        rimAmbient = .10f,
        rimWidth = .7.dp,
        edgeShade = Color.Black.copy(alpha = .03f),
    )

    private val hazeStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tint = HazeTint(Color.Transparent),
        blurRadius = blur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(material.fallback),
    )

    /** Shape with the panel's real corners at the bottom and the large radius on top. */
    fun shape(corners: LockSlabCorners): Shape = ContinuousRoundedShape(
        topStart = CornerSize(corners.top), topEnd = CornerSize(corners.top),
        bottomEnd = CornerSize(corners.bottomRight), bottomStart = CornerSize(corners.bottomLeft))

    /** API 33+: AGSL lens over the shared backdrop; API 31–32: Haze's blur with the same tint and rim. */
    fun modifier(shape: Shape, backdrop: HazeState?): Modifier {
        val liquid = Build.VERSION.SDK_INT >= 33
        val surface = if (backdrop != null && !liquid) {
            Modifier.hazeEffect(backdrop, hazeStyle)
                .liquidGlass(null, shape, material, null, sampleBackdrop = false, fillWhenEmpty = false)
        } else {
            Modifier.liquidGlass(backdrop, shape, material, null, sampleBackdrop = backdrop != null)
        }
        return Modifier.graphicsLayer {
            this.shape = shape
            clip = true
            shadowElevation = 0f
        }.then(surface)
    }
}

/**
 * Round 13: the Lock Screen's flashlight and camera (owner's iPhone, r13/3.webp): clear Liquid Glass — the
 * backdrop only lifted ≈ 5 % (+12/255 over the dark sea), a thin added-light rim, a lensing edge, no shadow.
 * Core.30 used `duoGlass(Control, dark = true)` (white tint 0.16, rim 0.70, 2 dp shadow): milky discs.
 */
internal object LockControlGlass {
    val material = LiquidGlassMaterial(
        blur = 4.dp,
        refractionHeight = 14.dp,
        refractionAmount = 22.dp,
        depth = .35f,
        dispersion = .2f,
        saturation = 1.4f,
        brightness = .02f,
        contrast = 1f,
        tint = Color.White.copy(alpha = .06f),
        fallback = Color(0xFF1C1C1E).copy(alpha = .30f),
        rim = Color.White.copy(alpha = .55f),
        rimAmbient = .10f,
        rimWidth = .7.dp,
        edgeShade = Color.Black.copy(alpha = .03f),
    )

    private val hazeStyle = HazeStyle(
        backgroundColor = Color.Transparent,
        tint = HazeTint(Color.Transparent),
        blurRadius = material.blur,
        noiseFactor = 0f,
        fallbackTint = HazeTint(material.fallback),
    )

    /** API 33+: AGSL lens over the wallpaper backdrop; API 31–32: Haze's blur with the same tint and rim. */
    fun modifier(backdrop: HazeState?): Modifier {
        val shape = CircleShape
        val surface = if (backdrop != null && Build.VERSION.SDK_INT < 33) {
            Modifier.hazeEffect(backdrop, hazeStyle)
                .liquidGlass(null, shape, material, null, sampleBackdrop = false, fillWhenEmpty = false)
        } else {
            Modifier.liquidGlass(backdrop, shape, material, null, sampleBackdrop = backdrop != null)
        }
        return Modifier.graphicsLayer {
            this.shape = shape
            clip = true
            shadowElevation = 0f
        }.then(surface)
    }
}

/** Corner radii of the slab in dp: the bottom ones are the display's physical corners. */
internal data class LockSlabCorners(val top: Dp, val bottomLeft: Dp, val bottomRight: Dp)

/**
 * The display's rounded corners for this window, per corner (the D-shaped cover has 10 dp on the
 * left and 56 dp on the right), with the recording's radius as the fallback where the display
 * reports none (emulator). The top corners take the larger of the two bottom radii.
 */
@Composable
internal fun rememberLockSlabCorners(fallbackDp: Float): LockSlabCorners {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, fallbackDp, density) {
        val insets = runCatching {
            context.getSystemService(android.view.WindowManager::class.java)?.currentWindowMetrics?.windowInsets
        }.getOrNull()
        fun radius(position: Int): Float = with(density) {
            (insets?.getRoundedCorner(position)?.radius ?: 0).toDp().value
        }
        val bl = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)
        val br = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
        val left = if (bl >= 4f) bl else fallbackDp
        val right = if (br >= 4f) br else fallbackDp
        LockSlabCorners(top = maxOf(left, right, fallbackDp * .8f).dp, bottomLeft = left.dp, bottomRight = right.dp)
    }
}
