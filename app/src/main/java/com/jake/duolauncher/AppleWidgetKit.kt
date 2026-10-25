package com.jake.duolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.min

// ---------------------------------------------------------------------------------------
// Layout kit for the Weather and Batteries widgets (r3-widgets-wb). Every measurement is in
// iOS points of Apple's iPhone widget canvases — small 158×158, medium 338×158, large 338×354 —
// and scaled to the cell the widget is given, so the gallery preview, the drag ghost and the
// placed widget are the same drawing. Text is positioned by its baseline (Apple's metrics were
// measured as baselines in the reference images; see docs/uiux-core17-widgets.md).
// ---------------------------------------------------------------------------------------

/** Apple's canvas for a Home Screen widget family, in points (158, 338×158, 338×354). */
internal fun appleWidgetCanvas(size: NativeWidgetSize): Pair<Float, Float> = size.widthPt to size.heightPt

internal class AppleWidgetScale(val unit: Float, private val density: Density) {
    fun pt(value: Float): Dp = (value * unit).dp
    fun sp(value: Float): TextUnit = with(density) { (value * unit).dp.toSp() }
}

/** How the card itself is painted. */
internal sealed interface AppleWidgetBackground {
    /** Weather's sky (Light and Dark appearances both keep it, as iOS does). */
    data class Sky(val top: Color, val bottom: Color) : AppleWidgetBackground
    /** iOS 26 Liquid Glass platter with a uniform veil (Batteries; every widget in the Clear style). */
    data class Glass(val veil: Color) : AppleWidgetBackground
}

/**
 * The widget card at Apple's proportions: continuous 22-pt corners, content laid out on the
 * Apple canvas ([AppleWidgetScale]) and centred in the cell. Gallery previews get the soft lift
 * shadow the other native widgets use.
 */
@Composable
internal fun AppleWidgetCard(
    modifier: Modifier,
    size: NativeWidgetSize,
    background: AppleWidgetBackground,
    preview: Boolean,
    clear: Boolean,
    content: @Composable BoxScope.(AppleWidgetScale) -> Unit,
) {
    val (canvasW, canvasH) = appleWidgetCanvas(size)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val unit = widgetPointScale(size, maxWidth.value, maxHeight.value)
        val scale = remember(unit, density) { AppleWidgetScale(unit, density) }
        // Same radius as every other 18Duo widget and the drag / context-menu copies (HIG hero: 11.7 % of a small).
        val shape = ContinuousRoundedShape(nativeWidgetCornerRadius(maxWidth.value, maxHeight.value).dp)
        val surface = when (background) {
            is AppleWidgetBackground.Sky -> Modifier.background(Brush.verticalGradient(listOf(background.top, background.bottom)), shape)
            is AppleWidgetBackground.Glass -> Modifier.duoGlass(DuoGlassRole.WidgetFrame, shape, frost = background.veil)
        }
        val lift = if (preview && background is AppleWidgetBackground.Sky) Modifier.shadow(6.dp, shape,
            ambientColor = Color.Black.copy(alpha = .12f), spotColor = Color.Black.copy(alpha = .18f)) else Modifier
        Box(Modifier.fillMaxSize().then(lift).clip(shape).then(surface)
            .then(if (preview && background is AppleWidgetBackground.Sky) Modifier.border(.5.dp, Color.Black.copy(alpha = .08f), shape) else Modifier)) {
            Box(Modifier.align(Alignment.Center).requiredSize((canvasW * unit).dp, (canvasH * unit).dp)) {
                androidx.compose.runtime.CompositionLocalProvider(LocalAppleWidgetClear provides clear) { content(scale) }
            }
        }
    }
}

/** Clear-style widgets add a faint shadow under their white content (legible on any wallpaper). */
internal val LocalAppleWidgetClear = androidx.compose.runtime.staticCompositionLocalOf { false }
private val AppleClearShadow = Shadow(Color.Black.copy(alpha = .35f), Offset(0f, 1f), 4f)

/** Apple's widget text: SF Text below 20 pt, Display above (Inter analogues), with matching tracking. */
@Composable
internal fun appleTextStyle(s: AppleWidgetScale, sizePt: Float, weight: FontWeight, color: Color): TextStyle =
    TextStyle(
        color = color,
        fontSize = s.sp(sizePt),
        lineHeight = s.sp(sizePt * 1.2f),
        fontWeight = weight,
        // Inter Display ships Medium and up only; Apple's big Regular numerals (79°, 5:13) need the Regular cut.
        fontFamily = if (weight.weight < FontWeight.Medium.weight) DuoFonts.text else DuoFonts.forSize(sizePt),
        letterSpacing = duoTrackingEm(sizePt).em,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        shadow = if (LocalAppleWidgetClear.current) AppleClearShadow else null,
    )

/**
 * One line of widget text whose baseline sits [baseline] pt below the top of the canvas.
 * [alignEnd] right-aligns it against [inset] pt from the trailing edge.
 */
@Composable
internal fun BoxScope.BaselineText(
    text: AnnotatedString, s: AppleWidgetScale, sizePt: Float, weight: FontWeight, color: Color,
    baseline: Float, inset: Float = 0f, alignEnd: Boolean = false, maxWidth: Float? = null, center: Boolean = false,
) {
    val alignment = when {
        center -> Alignment.TopCenter
        alignEnd -> Alignment.TopEnd
        else -> Alignment.TopStart
    }
    var m = Modifier.align(alignment)
    m = when {
        center -> m.then(Modifier.offsetPt(s, inset, 0f))
        alignEnd -> m.then(Modifier.offsetPt(s, -inset, 0f))
        else -> m.then(Modifier.offsetPt(s, inset, 0f))
    }
    if (maxWidth != null) m = m.then(Modifier.widthInPt(s, maxWidth))
    Text(text, m.paddingFromBaseline(top = s.pt(baseline)), style = appleTextStyle(s, sizePt, weight, color),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) TextAlign.End else if (center) TextAlign.Center else TextAlign.Start)
}

@Composable
internal fun BoxScope.BaselineText(
    text: String, s: AppleWidgetScale, sizePt: Float, weight: FontWeight, color: Color,
    baseline: Float, inset: Float = 0f, alignEnd: Boolean = false, maxWidth: Float? = null, center: Boolean = false,
) = BaselineText(AnnotatedString(text), s, sizePt, weight, color, baseline, inset, alignEnd, maxWidth, center)

internal fun Modifier.offsetPt(s: AppleWidgetScale, x: Float, y: Float): Modifier = offset(x = s.pt(x), y = s.pt(y))

private fun Modifier.widthInPt(s: AppleWidgetScale, max: Float): Modifier = widthIn(max = s.pt(max))

/** "9" + smaller "AM" (Apple sets the meridiem in small caps). */
internal fun meridiemText(main: String, suffix: String, suffixScale: Float = .72f): AnnotatedString = buildAnnotatedString {
    append(main)
    if (suffix.isNotEmpty()) withStyle(SpanStyle(fontSize = suffixScale.em)) { append(suffix) }
}
