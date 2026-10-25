package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Launcher-side half of the iOS app motion. Everything the finger drives — the app card, the app
 * switcher, quick switching — is drawn by the navigation overlay (NavigationStage), which stays
 * above every app. Here the launcher only zooms Home itself: away from the viewer when an app opens
 * from an icon, back from ≈1.1 when the overlay flies a card home, and the in-launcher icon zoom
 * used when the overlay is not running.
 */

/**
 * Zooms and fades Home with the app-open transform (also the overlay's gesture return). Icon
 * positions for the overlay are measured inside this layer ([homeZoomFrame]), so they stay right
 * while Home is zoomed.
 */
internal fun Modifier.homeSwitcherLayer(zoom: () -> AppOpenTransform?): Modifier =
    graphicsLayer {
        val open = zoom()
        val scale = open?.contentScale ?: 1f
        scaleX = scale
        scaleY = scale
        alpha = open?.contentAlpha ?: 1f
        // Round 22: while Home zooms, draw it once into a layer and scale that layer. Scaled directly, every
        // continuous-corner clip (widgets, glass, the Dock) was re-rasterised on the CPU for each frame of the
        // launch and the return, the largest single cost behind the dropped frames around app switches.
        compositingStrategy = if (open != null && scale != 1f) androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            else androidx.compose.ui.graphics.CompositingStrategy.Auto
    }.homeZoomFrame()

/** Progress-driven geometry of the running icon zoom, or null when no app is opening/returning. */
@Composable
internal fun rememberAppOpenTransform(): State<AppOpenTransform?> {
    val zoom by AppOpenZoomController.state.collectAsState()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(zoom) {
        val current = zoom ?: return@LaunchedEffect
        when (current.direction) {
            AppOpenDirection.OPENING -> {
                progress.snapTo(0f)
                if (current.handover) {
                    progress.animateTo(1f, if (current.dissolve) LauncherMotion.dissolveIn else appZoomSpec(HANDOVER_STIFFNESS)) {
                        if (value >= AppOpenMotion.HANDOVER_PROGRESS) AppOpenZoomController.handover()
                    }
                    AppOpenZoomController.handover()
                } else progress.animateTo(1f, appZoomSpec(OPEN_STIFFNESS))
                // Android never covered us (silent launch failure): settle Home back in place.
                delay(AppOpenMotion.ABANDON_MILLIS)
                AppOpenZoomController.abandon(current, android.os.SystemClock.uptimeMillis())
            }
            AppOpenDirection.RETURNING -> {
                progress.snapTo(1f)
                progress.animateTo(0f, if (current.dissolve) LauncherMotion.dissolveOut else appZoomSpec(RETURN_STIFFNESS))
                AppOpenZoomController.finished(current)
            }
            // The overlay writes the zoom every frame; a lost overlay must not leave Home zoomed.
            // Round 12: a switcher open over the live Home holds its gesture for as long as it is
            // open, so the watchdog only fires once the overlay has stopped touching it.
            AppOpenDirection.GESTURE -> {
                while (true) {
                    delay(GESTURE_WATCHDOG)
                    if (!AppOpenZoomController.gestureAlive(GESTURE_WATCHDOG)) break
                }
                AppOpenZoomController.endGesture()
            }
        }
    }
    val width = with(density) { configuration.screenWidthDp.dp.toPx() }
    val height = with(density) { configuration.screenHeightDp.dp.toPx() }
    return remember(zoom, width, height) {
        derivedStateOf {
            zoom?.let {
                if (it.direction == AppOpenDirection.GESTURE && it.homeOrigin)
                    AppOpenMotion.homeGestureTransform(AppOpenZoomController.gestureProgress.floatValue,
                        AppOpenZoomController.gestureBlur.floatValue)
                else if (it.direction == AppOpenDirection.GESTURE)
                    AppOpenMotion.gestureTransform(AppOpenZoomController.gestureProgress.floatValue)
                else if (it.dissolve) AppOpenMotion.dissolveTransform(progress.value, width, height)
                else AppOpenMotion.transform(progress.value, it.bounds, width, height)
            }
        }
    }
}


private const val GESTURE_WATCHDOG = 3_000L

/**
 * iOS zooms windows with critically damped springs, not timed curves: SwiftUI response 0.44 s
 * (open) / 0.40 s (close) → stiffness (2π / response)², the same numbers the overlay uses.
 */
private const val OPEN_STIFFNESS = 204f
private const val RETURN_STIFFNESS = 247f
/** Response 0.36 s: the app starts ≈ 0.27 s after the tap, its card then ≈ 94 % of the way to full screen. */
private const val HANDOVER_STIFFNESS = 305f

private fun appZoomSpec(stiffness: Float): AnimationSpec<Float> =
    if (LauncherMotion.reduced) snap() else spring(dampingRatio = 1f, stiffness = stiffness, visibilityThreshold = .001f)

/**
 * In-launcher fallback for opening (when the overlay is off) and the return after system Back:
 * the app's "launch screen" — its own background colour with the icon in the middle — grows out
 * of / shrinks into the icon, dissolving into the real icon at the end like iOS.
 */
@Composable
internal fun AppOpenZoomOverlay(transform: () -> AppOpenTransform?) {
    val zoom by AppOpenZoomController.state.collectAsState()
    val icon = zoom?.icon ?: return
    val image = remember(icon) { icon.asImageBitmap() }
    // Out of / into a widget the card's end state is the widget itself, edge to edge.
    val art = zoom?.art
    val endImage = remember(art, image) { art?.asImageBitmap() ?: image }
    val appId = zoom?.appId
    val handover = zoom?.handover == true
    val dissolve = zoom?.dissolve == true
    val fill = remember(icon, appId) {
        val pkg = appId?.let { parseProfileAppId(it)?.component?.substringBefore('/') }
        Color(SplashColors.forPackage(pkg) ?: iconBackgroundColor(icon))
    }
    Box(Modifier.fillMaxSize().testTag("app-open-zoom").drawWithContent {
        val t = transform() ?: return@drawWithContent
        val rect = Rect(Offset(t.iconLeft, t.iconTop), Size(t.iconWidth, t.iconHeight))
        val iconMix = if (dissolve) 0f else AppOpenMotion.fallbackIconMix(t.progress)
        // Opaque under the dissolving icon (never Home showing through a half-faded card); a
        // Reduce Motion card is the cross-fade itself.
        val cardAlpha = if (dissolve) t.progress
            else AppOpenMotion.fallbackCardAlpha(t.progress, handover) * SwitcherMotion.windowUnderIcon(iconMix)
        val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(t.iconCornerRadius))) }
        clipPath(path) {
            if (cardAlpha > .002f) {
                drawRect(fill, rect.topLeft, rect.size, alpha = cardAlpha)
                val short = min(size.width, size.height)
                val side = AppOpenMotion.launchCardIconSide(rect.width, rect.height, short, density,
                    fullness = if (handover) min(rect.width, rect.height) / short else 0f).let { if (dissolve) min(short * .34f, 160.dp.toPx()) else it }
                drawImage(image, dstOffset = IntOffset((rect.center.x - side / 2f).roundToInt(), (rect.center.y - side / 2f).roundToInt()),
                    dstSize = IntSize(side.roundToInt().coerceAtLeast(1), side.roundToInt().coerceAtLeast(1)),
                    alpha = cardAlpha)
            }
            if (iconMix > .002f) {
                drawImage(endImage, dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                    dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                    alpha = iconMix)
            }
        }
    })
}

