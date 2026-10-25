package com.jake.duolauncher

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// =======================================================================================
// iPhone Duo's Today View while it scrolls (r21, owner's screenshot evidence/r21/ref/7-today-scrolled.webp and
// recording evidence/r21/rec/today-scroll.mp4, inner screen, 860 × 608 dp): the widgets slide through one clear
// glass sheet. Its edge runs 13.4 dp outside the widget column and is concentric with the cards; it starts 4.3 dp
// below where the first widget rests and ends 50.5 dp above the bottom. Widgets are cut at its rim, and within
// ≈ 20 dp of the rim they are magnified out toward it (a widget's edge passing under the top stretches up to meet
// it, text at the bottom smears with a faint rainbow); the sides, 13.4 dp in, stay straight. A 3 dp scroll bar runs
// 9 dp right of it. The sheet belongs to the scrolling, not to the offset: Today View at rest part-way down has
// none (the recording's first second), it comes in as soon as the page moves and leaves once it has stopped.
// What lies outside the sheet stays hidden at rest too (owner, r23: a widget below its bottom rim must not show);
// at rest the top edge sits at the first widget's resting top, so nothing of an unscrolled page is cut.
// =======================================================================================

internal const val TODAY_SHEET_SIDE_DP = 13.4f
internal const val TODAY_SHEET_TOP_DP = 4.3f
internal const val TODAY_SHEET_BOTTOM_DP = 50.5f
/** The rim's lens: band depth, how far in the rim samples, RGB split. */
private const val TODAY_SHEET_BEND_BAND_DP = 20f
private const val TODAY_SHEET_BEND_DP = 16f
private const val TODAY_SHEET_DISPERSION = .35f
/** The sheet comes in 0.15 s after the page starts moving and leaves 0.3 s, [TODAY_SHEET_LINGER_MS] after it stops. */
private const val TODAY_SHEET_IN_MS = 150
private const val TODAY_SHEET_OUT_MS = 300
internal const val TODAY_SHEET_LINGER_MS = 500L
private const val TODAY_SCROLL_BAR_GAP_DP = 9f
private const val TODAY_SCROLL_BAR_WIDTH_DP = 3f
private const val TODAY_SCROLL_BAR_MIN_DP = 36f

/** The sheet's frame (dp) in the Today pane's column: left / top / right / bottom and corner radius. */
internal data class TodaySheetFrame(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The sheet around a widget column that spans [columnLeft]…[columnLeft] + [columnWidth] (a four-wide widget's
 * frame), in a pane [paneHeight] tall whose first widget rests at [contentTop]; [cardRadius] is the cards' corner.
 */
internal fun todaySheetFrame(columnLeft: Float, columnWidth: Float, contentTop: Float, paneHeight: Float, cardRadius: Float) =
    TodaySheetFrame(columnLeft - TODAY_SHEET_SIDE_DP, contentTop + TODAY_SHEET_TOP_DP,
        columnLeft + columnWidth + TODAY_SHEET_SIDE_DP, (paneHeight - TODAY_SHEET_BOTTOM_DP).coerceAtLeast(contentTop + 2 * cardRadius),
        cardRadius + TODAY_SHEET_SIDE_DP)

/**
 * The sheet's presence, 0 → 1: in while [scroll] moves (finger or fling), out [TODAY_SHEET_LINGER_MS] after it stops.
 * Read it only in draw / layer blocks.
 */
@Composable
internal fun rememberTodaySheetProgress(scroll: ScrollState): () -> Float {
    var active by remember { mutableStateOf(false) }
    val moving = scroll.isScrollInProgress
    LaunchedEffect(moving) {
        if (moving) active = true
        else if (active) { kotlinx.coroutines.delay(TODAY_SHEET_LINGER_MS); active = false }
    }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(active) {
        progress.animateTo(if (active) 1f else 0f, tween(if (active) TODAY_SHEET_IN_MS else TODAY_SHEET_OUT_MS))
    }
    return remember(progress) { { progress.value } }
}

/** Scroll bar thumb (top, length) in px along a track [trackPx] long, or null when nothing scrolls. */
internal fun todayScrollThumb(scrollPx: Int, maxScrollPx: Int, viewportPx: Int, trackPx: Float, minPx: Float): Pair<Float, Float>? {
    if (maxScrollPx <= 0 || viewportPx <= 0 || trackPx <= 0f) return null
    val length = (trackPx * viewportPx / (viewportPx + maxScrollPx).toFloat()).coerceIn(minOf(minPx, trackPx), trackPx)
    val top = (trackPx - length) * (scrollPx.coerceIn(0, maxScrollPx) / maxScrollPx.toFloat())
    return top to length
}

/**
 * Top (dp) of what shows of the column: the sheet's rim while it is in ([progress] 1), the first widget's resting
 * top (less 1 dp for anti-aliasing) at rest, so an unscrolled page is whole and a scrolled one is cut at the top too.
 */
internal fun todaySheetMaskTop(frame: TodaySheetFrame, progress: Float) =
    frame.top - (TODAY_SHEET_TOP_DP + 1f) * (1f - progress.coerceIn(0f, 1f))

/** The sheet's outline at [top] (dp), in the column's coordinates; a plain round rect, so the clip stays in hardware. */
private data class TodaySheetMask(val frame: TodaySheetFrame, val top: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) = with(density) {
        Outline.Rounded(RoundRect(frame.left.dp.toPx(), top.dp.toPx(), frame.right.dp.toPx(), frame.bottom.dp.toPx(),
            CornerRadius(frame.radius.dp.toPx())))
    }
}

/**
 * Applied to the Today column's scroll viewport (before verticalScroll), whose top-left is [frame]'s origin: only
 * what lies inside the sheet shows, always; while it scrolls the widgets are also bent at the rim
 * ([TODAY_SHEET_SHADER], Android 13+; older systems keep the plain cut).
 */
internal fun Modifier.todaySheetContent(frame: TodaySheetFrame, progress: () -> Float): Modifier {
    val holder = arrayOfNulls<RuntimeShader>(1)
    return graphicsLayer {
        val p = progress()
        val top = todaySheetMaskTop(frame, p)
        shape = TodaySheetMask(frame, top)
        clip = true
        if (Build.VERSION.SDK_INT < 33 || p <= .001f) { renderEffect = null; return@graphicsLayer }
        val shader = holder[0] ?: runCatching { RuntimeShader(TODAY_SHEET_SHADER) }.getOrNull()?.also { holder[0] = it }
            ?: run { renderEffect = null; return@graphicsLayer }
        shader.setFloatUniform("rect", frame.left.dp.toPx(), top.dp.toPx(), frame.right.dp.toPx(), frame.bottom.dp.toPx())
        shader.setFloatUniform("radius", frame.radius.dp.toPx())
        shader.setFloatUniform("refractionHeight", TODAY_SHEET_BEND_BAND_DP.dp.toPx())
        shader.setFloatUniform("refractionAmount", TODAY_SHEET_BEND_DP.dp.toPx())
        shader.setFloatUniform("dispersion", TODAY_SHEET_DISPERSION)
        shader.setFloatUniform("progress", p)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * Over the column above and below the sheet, where its widgets are hidden: a drag there still scrolls the page,
 * and taps fall through to the pane (bare-space tap / Home options), never to a widget no one can see.
 */
@Composable
internal fun TodaySheetHiddenBands(frame: TodaySheetFrame, scroll: ScrollState, modifier: Modifier = Modifier) {
    val band = Modifier.scrollable(scroll, Orientation.Vertical, reverseDirection = true)
    Box(modifier.height(todaySheetMaskTop(frame, 0f).coerceAtLeast(0f).dp).then(band).testTag("today-hidden-top"))
    Box(modifier.fillMaxHeight().padding(top = frame.bottom.dp).then(band).testTag("today-hidden-bottom"))
}

/** The sheet's glass, behind the widgets: clear, lifted a little, lensing bevel and rim (the open-folder glass). */
@Composable
internal fun TodaySheetGlass(frame: TodaySheetFrame, progress: () -> Float, modifier: Modifier = Modifier) {
    val shown by remember(progress) { androidx.compose.runtime.derivedStateOf { progress() > .001f } }
    if (!shown) return
    Box(modifier.offset(frame.left.dp, frame.top.dp).size(frame.width.dp, frame.height.dp)
        .graphicsLayer { alpha = progress() }
        .duoGlass(DuoGlassRole.FolderPanel, RoundedCornerShape(frame.radius.dp))
        .testTag("today-sheet"))
}

/** iOS scroll bar right of the sheet: shown while the page moves, fading out 0.6 s after it stops. */
@Composable
internal fun TodayScrollBar(frame: TodaySheetFrame, scroll: ScrollState, modifier: Modifier = Modifier) {
    val moving = scroll.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(moving, scroll.value) {
        if (moving) visible = true
        else if (visible) { kotlinx.coroutines.delay(600); visible = false }
    }
    val alpha by animateFloatAsState(if (visible && scroll.value > 0) 1f else 0f, tween(if (visible) 120 else 300), label = "today scroll bar")
    if (alpha <= .001f) return
    Box(modifier.graphicsLayer {
        val thumb = todayScrollThumb(scroll.value, scroll.maxValue, scroll.viewportSize, frame.height.dp.toPx(),
            TODAY_SCROLL_BAR_MIN_DP.dp.toPx())
        translationX = (frame.right + TODAY_SCROLL_BAR_GAP_DP).dp.toPx()
        translationY = frame.top.dp.toPx() + (thumb?.first ?: 0f)
        this.alpha = if (thumb == null) 0f else alpha * .92f
    }.size(TODAY_SCROLL_BAR_WIDTH_DP.dp, todayScrollBarLength(frame, scroll))
        .background(Color.White, RoundedCornerShape(50)).testTag("today-scroll-bar"))
}

@Composable
private fun todayScrollBarLength(frame: TodaySheetFrame, scroll: ScrollState): androidx.compose.ui.unit.Dp {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return with(density) {
        val thumb = todayScrollThumb(scroll.value, scroll.maxValue, scroll.viewportSize, frame.height.dp.toPx(), TODAY_SCROLL_BAR_MIN_DP.dp.toPx())
        (thumb?.second ?: 0f).toDp()
    }
}
