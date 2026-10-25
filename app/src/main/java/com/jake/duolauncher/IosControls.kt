package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Pure geometry (unit-tested in IosControlsTest).
// ---------------------------------------------------------------------------------------

/** UISwitch metrics in points: 51×31 track, 27pt knob inset 2pt; the knob stretches to 33pt while touched. */
internal object IosSwitchMetrics {
    const val WIDTH = 51f
    const val HEIGHT = 31f
    const val KNOB = 27f
    const val INSET = 2f
    const val PRESSED_KNOB = 33f
}

/**
 * Knob frame (left, width) for [progress] 0 = off … 1 = on and [press] 0…1. The knob grows
 * toward the centre while pressed, so its outer edge stays pinned to the side it rests on.
 */
internal fun iosSwitchKnob(progress: Float, press: Float): Pair<Float, Float> {
    val p = progress.coerceIn(0f, 1f)
    val width = IosSwitchMetrics.KNOB + (IosSwitchMetrics.PRESSED_KNOB - IosSwitchMetrics.KNOB) * press.coerceIn(0f, 1f)
    val travel = IosSwitchMetrics.WIDTH - 2 * IosSwitchMetrics.INSET - width
    return (IosSwitchMetrics.INSET + travel * p) to width
}

/** Fraction of the track a value occupies; empty or inverted ranges read as 0. */
internal fun iosSliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (!span.isFinite() || span <= 0f || !value.isFinite()) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** Value for a track [fraction], snapped to [steps] intermediate stops (0 = continuous), like UISlider + rounding. */
internal fun iosSliderValue(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0): Float {
    val f = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val span = range.endInclusive - range.start
    if (steps <= 0) return range.start + span * f
    val segments = steps + 1
    return range.start + span * (f * segments).roundToInt() / segments
}

/** Which range-slider thumb a touch at [fraction] grabs: the nearer one (ties go to the end thumb once both overlap at the top). */
internal fun iosRangeThumbFor(fraction: Float, start: Float, end: Float): Int {
    val toStart = abs(fraction - start)
    val toEnd = abs(fraction - end)
    return when {
        toStart < toEnd -> 0
        toEnd < toStart -> 1
        fraction >= end -> 1
        else -> 0
    }
}

// ---------------------------------------------------------------------------------------
// Press feedback: iOS never ripples.
// ---------------------------------------------------------------------------------------

private const val IOS_DIM_ALPHA = .32f

/**
 * UIButton-style highlight for any clickable without its own feedback: the content dims while
 * touched and fades back in on release. Provided as [androidx.compose.foundation.LocalIndication]
 * by DuoTheme, so no Material ripple appears anywhere in 18Duo's own UI.
 */
internal object IosDimIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        IosPressNode(interactionSource, highlight = null)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x105D1
}

/** UITableViewCell selection: the row fills with the system highlight colour behind its content. */
internal data class IosRowHighlight(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        IosPressNode(interactionSource, highlight = color)
}

private class IosPressNode(private val source: InteractionSource, private val highlight: Color?) :
    androidx.compose.ui.Modifier.Node(), DrawModifierNode {
    private val amount = Animatable(0f)
    private val paint = Paint()

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses++
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                }
                launch {
                    // Highlight is instant on touch-down and dissolves out on lift, as on iOS (a spring,
                    // like every other fade; the system animator scale makes it instant).
                    if (presses > 0) { amount.snapTo(1f); invalidateDraw() }
                    else amount.animateTo(0f, LauncherMotion.dissolveOut) { invalidateDraw() }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val a = amount.value
        if (a <= .001f) { drawContent(); return }
        if (highlight != null) {
            drawRect(highlight.copy(alpha = highlight.alpha * a))
            drawContent()
            return
        }
        paint.alpha = 1f - (1f - IOS_DIM_ALPHA) * a
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        drawContext.canvas.restore()
    }
}

/** iOS system colours that depend on the sheet appearance. */
@Composable internal fun iosLabel(): Color = if (iosSheetDark()) Color.White else Color.Black
@Composable internal fun iosTertiaryText(): Color = if (iosSheetDark()) Color(0xFFEBEBF5).copy(alpha = .3f) else Color(0xFF3C3C43).copy(alpha = .3f)
@Composable internal fun iosRowHighlightColor(): Color = if (iosSheetDark()) Color(0xFF3A3A3C) else Color(0xFFD1D1D6)
@Composable internal fun iosFillColor(): Color = Color(0xFF787880).copy(alpha = if (iosSheetDark()) .36f else .2f)

// ---------------------------------------------------------------------------------------
// Switch.
// ---------------------------------------------------------------------------------------

/**
 * UISwitch: 51×31 pt green track, white knob with a soft shadow, spring travel and the knob
 * stretch while touched. With [onCheckedChange] null the parent row owns the toggle semantics
 * (iOS rows toggle anywhere you tap), and the switch only mirrors the pressed state of [interactionSource].
 */
@Composable
internal fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, interactionSource: MutableInteractionSource? = null) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = LauncherMotion.reduced
    val progress by animateFloatAsState(if (checked) 1f else 0f,
        if (reduced) tween(0) else LauncherMotion.switchKnob, label = "switch knob")
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        if (reduced) tween(0) else LauncherMotion.switchPress, label = "switch press")
    val off = if (iosSheetDark()) Color(0xFF39393D) else Color(0xFFE9E9EB)
    val toggle = if (onCheckedChange != null) Modifier.clickable(source, null, enabled, role = Role.Switch) {
        onCheckedChange(!checked)
    }.semantics { stateDescription = if (checked) uxText("开", "On") else uxText("关", "Off") } else Modifier
    Canvas(modifier.size(IosSwitchMetrics.WIDTH.dp, IosSwitchMetrics.HEIGHT.dp).then(toggle)
        .graphicsLayer { alpha = if (enabled) 1f else .5f }) {
        val unit = size.height / IosSwitchMetrics.HEIGHT
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(lerpColor(off, IosUi.green, progress), cornerRadius = radius)
        val (left, width) = iosSwitchKnob(progress, press)
        val knob = IosSwitchMetrics.KNOB * unit
        val top = IosSwitchMetrics.INSET * unit
        // Two-layer iOS knob shadow: a tight contact shadow plus a soft 8pt drop.
        drawRoundRect(Color.Black.copy(alpha = .06f), Offset(left * unit, top + 2.4f * unit), Size(width * unit, knob),
            CornerRadius(knob / 2f))
        drawRoundRect(Color.Black.copy(alpha = .10f), Offset(left * unit - .5f * unit, top + 1.2f * unit),
            Size(width * unit + unit, knob + unit), CornerRadius(knob / 2f + unit))
        drawRoundRect(Color.White, Offset(left * unit, top), Size(width * unit, knob), CornerRadius(knob / 2f))
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f,
        a.blue + (b.blue - a.blue) * f, a.alpha + (b.alpha - a.alpha) * f)
}

// ---------------------------------------------------------------------------------------
// Sliders.
// ---------------------------------------------------------------------------------------

private val SliderThumb = 28.dp
private val SliderTrack = 4.dp

@Composable
private fun SliderThumbDot(modifier: Modifier) {
    // UISlider knob: white disc, soft drop shadow plus a hairline so it reads on white cards too.
    Box(modifier.size(SliderThumb).shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = .3f),
        spotColor = Color.Black.copy(alpha = .35f)).background(Color.White, CircleShape)
        .border(.5.dp, Color.Black.copy(alpha = .06f), CircleShape))
}

/**
 * UISlider: 4pt rounded track (accent on the minimum side, grey fill on the maximum side) and a
 * 28pt white knob with a shadow. Drags move the value relative to the finger; taps on the track
 * do nothing (as on iOS), and accessibility can set the value directly.
 */
@Composable
internal fun IosSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true, tint: Color = iosAccent(), label: String? = null) {
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val fill = iosFillColor()
    var raw by remember { mutableFloatStateOf(iosSliderFraction(value, valueRange)) }
    BoxWithConstraints(modifier.fillMaxWidth().height(SliderThumb + 8.dp)
        .semantics {
            label?.let { contentDescription = it }
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
            if (enabled) setProgress { target ->
                val snapped = iosSliderValue(iosSliderFraction(target, valueRange), valueRange, steps)
                if (snapped != currentValue) currentChange(snapped)
                onValueChangeFinished?.invoke(); true
            }
        }
        .pointerInput(enabled, valueRange, steps) {
            if (!enabled) return@pointerInput
            val usable = (size.width - SliderThumb.toPx()).coerceAtLeast(1f)
            detectHorizontalDragGestures(
                onDragStart = { raw = iosSliderFraction(currentValue, valueRange) },
                onDragEnd = { onValueChangeFinished?.invoke() },
                onDragCancel = { onValueChangeFinished?.invoke() },
            ) { change, dx ->
                change.consume()
                raw = (raw + dx / usable).coerceIn(0f, 1f)
                val next = iosSliderValue(raw, valueRange, steps)
                if (next != currentValue) currentChange(next)
            }
        }, contentAlignment = Alignment.CenterStart) {
        val fraction = iosSliderFraction(value, valueRange)
        val usable = maxWidth - SliderThumb
        Box(Modifier.padding(horizontal = SliderThumb / 2).fillMaxWidth().height(SliderTrack).background(fill, CircleShape))
        Box(Modifier.padding(start = SliderThumb / 2).width(usable * fraction).height(SliderTrack)
            .background(if (enabled) tint else tint.copy(alpha = .4f), CircleShape))
        SliderThumbDot(Modifier.offset(x = usable * fraction))
    }
}

/** Two-knob variant (UISlider has none; this follows the same look) used for StandBy's hinge-angle window. */
@Composable
internal fun IosRangeSlider(value: ClosedFloatingPointRange<Float>, onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float>, steps: Int = 0, minimumSpan: Float = 0f,
    onValueChangeFinished: (() -> Unit)? = null, tint: Color = iosAccent()) {
    val current by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val fill = iosFillColor()
    var grabbed by remember { mutableIntStateOf(-1) }
    var raw by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(modifier.fillMaxWidth().height(SliderThumb + 8.dp)
        .pointerInput(valueRange, steps, minimumSpan) {
            val half = SliderThumb.toPx() / 2f
            val usable = (size.width - SliderThumb.toPx()).coerceAtLeast(1f)
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    val f = ((offset.x - half) / usable).coerceIn(0f, 1f)
                    val s = iosSliderFraction(current.start, valueRange)
                    val e = iosSliderFraction(current.endInclusive, valueRange)
                    grabbed = iosRangeThumbFor(f, s, e)
                    raw = if (grabbed == 0) s else e
                },
                onDragEnd = { grabbed = -1; onValueChangeFinished?.invoke() },
                onDragCancel = { grabbed = -1; onValueChangeFinished?.invoke() },
            ) { change, dx ->
                change.consume()
                raw = (raw + dx / usable).coerceIn(0f, 1f)
                val v = iosSliderValue(raw, valueRange, steps)
                val next = if (grabbed == 0) v.coerceAtMost(current.endInclusive - minimumSpan)..current.endInclusive
                    else current.start..v.coerceAtLeast(current.start + minimumSpan)
                if (next != current && next.start <= next.endInclusive) currentChange(next)
            }
        }, contentAlignment = Alignment.CenterStart) {
        val s = iosSliderFraction(value.start, valueRange)
        val e = iosSliderFraction(value.endInclusive, valueRange)
        val usable = maxWidth - SliderThumb
        Box(Modifier.padding(horizontal = SliderThumb / 2).fillMaxWidth().height(SliderTrack).background(fill, CircleShape))
        Box(Modifier.offset(x = SliderThumb / 2 + usable * s).width(usable * (e - s)).height(SliderTrack).background(tint, CircleShape))
        SliderThumbDot(Modifier.offset(x = usable * s).semantics {
            contentDescription = uxText("最小值", "Minimum")
            progressBarRangeInfo = ProgressBarRangeInfo(value.start, valueRange, steps)
            setProgress { t -> currentChange(iosSliderValue(iosSliderFraction(t, valueRange), valueRange, steps)
                .coerceAtMost(current.endInclusive - minimumSpan)..current.endInclusive); onValueChangeFinished?.invoke(); true }
        })
        SliderThumbDot(Modifier.offset(x = usable * e).semantics {
            contentDescription = uxText("最大值", "Maximum")
            progressBarRangeInfo = ProgressBarRangeInfo(value.endInclusive, valueRange, steps)
            setProgress { t -> currentChange(current.start..iosSliderValue(iosSliderFraction(t, valueRange), valueRange, steps)
                .coerceAtLeast(current.start + minimumSpan)); onValueChangeFinished?.invoke(); true }
        })
    }
}

// ---------------------------------------------------------------------------------------
// Buttons.
// ---------------------------------------------------------------------------------------

/**
 * iOS 26 toolbar button: a 44pt Liquid Glass circle with an SF-style glyph. [prominent] is the
 * confirmation style (blue glass, white checkmark) that iOS 26 uses for Done.
 */
@Composable
internal fun IosGlassCircleButton(symbol: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    tag: String? = null, prominent: Boolean = false, size: Dp = 44.dp, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.size(size).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .duoGlass(DuoGlassRole.Floating, CircleShape, interaction,
            frost = if (prominent) IosUi.blue.copy(alpha = .92f) else glassButtonLift())
        .clickable(interaction, null, enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(symbol, null, Modifier.size(size * .43f),
            tint = when {
                prominent -> Color.White
                !enabled -> iosLabel().copy(alpha = .3f)
                else -> iosLabel()
            })
    }
}

/** iOS 26 toolbar glass reads a step lighter than the bar behind it (grey on dark, white on light). */
@Composable
private fun glassButtonLift(): Color = if (iosSheetDark()) Color.White.copy(alpha = .13f) else Color.White.copy(alpha = .75f)

/** A Liquid Glass capsule with text (iOS 26 toolbar text button). */
@Composable
internal fun IosGlassCapsuleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String? = null,
    enabled: Boolean = true, prominent: Boolean = false, color: Color = Color.Unspecified,
    /** Round 12.4: the iOS 26 Lock Screen editor's compact pills (27 pt, 15 pt text). */
    height: Dp = 44.dp, fontSize: Float = 17f, horizontalPadding: Dp = 16.dp) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (color != Color.Unspecified) color else if (prominent) Color.White else iosLabel()
    Box(modifier.heightIn(min = height).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .duoGlass(DuoGlassRole.Floating, CircleShape, interaction,
            frost = if (prominent) IosUi.blue.copy(alpha = .92f) else glassButtonLift())
        .clickable(interaction, null, enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = horizontalPadding), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) ink else ink.copy(alpha = .35f), fontSize = fontSize.sp,
            fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Plain text button with the UIButton dim on touch (no background, no ripple). */
@Composable
internal fun IosPlainButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = iosAccent(),
    bold: Boolean = false, enabled: Boolean = true, tag: String? = null, fontSize: Float = 17f) {
    Box(modifier.heightIn(min = 44.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .clickable(remember { MutableInteractionSource() }, IosDimIndication, enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) color else color.copy(alpha = .35f), fontSize = fontSize.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------
// Search field.
// ---------------------------------------------------------------------------------------

/** UISearchBar field: grey rounded fill, magnifying glass, placeholder, ⓧ clear button. */
@Composable
internal fun IosSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    tag: String? = null, onSearch: (() -> Unit)? = null) {
    val ink = iosLabel()
    val secondary = iosSecondaryText()
    Row(modifier.fillMaxWidth().height(36.dp).background(Color(0xFF767680).copy(alpha = if (iosSheetDark()) .24f else .12f),
        androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(IosSymbols.magnifyingGlass, null, Modifier.size(17.dp), tint = secondary)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = secondary, fontSize = 17.sp, maxLines = 1)
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
                singleLine = true, cursorBrush = SolidColor(iosAccent()),
                textStyle = TextStyle(color = ink, fontSize = 17.sp, fontFamily = DuoFonts.text, letterSpacing = duoTracking(17f)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }))
        }
        if (query.isNotEmpty()) Box(Modifier.size(28.dp)
            .clickable(remember { MutableInteractionSource() }, IosDimIndication, role = Role.Button) { onQuery("") }
            .semantics { contentDescription = uxText("清除文本", "Clear text") }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(17.dp).background(secondary.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(IosSymbols.xmark, null, Modifier.size(10.dp), tint = if (iosSheetDark()) Color.Black else Color.White)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// HUD banner in place of Android Toasts.
// ---------------------------------------------------------------------------------------

/**
 * Short status messages that Android would show as a Toast. iOS has no toasts; it uses a
 * glass capsule that drops in from the top (like the AirPods / Focus banners) and leaves on its own.
 */
internal object IosBanner {
    internal data class Message(val id: Long, val text: String, val long: Boolean)
    var current by mutableStateOf<Message?>(null); private set
    /** Number of open panels: the top-most window renders the banner so it is never hidden behind a sheet. */
    var panels by mutableIntStateOf(0)
    private var next = 0L

    fun show(text: String, long: Boolean = false) {
        if (text.isBlank()) return
        current = Message(++next, text, long)
    }

    fun clear(id: Long) { if (current?.id == id) current = null }
}

/** Duration a banner stays: about 2 s plus reading time, capped (UIKit HUDs stay ≈ 2–4 s). */
internal fun iosBannerMillis(text: String, long: Boolean): Long =
    ((if (long) 3200L else 2000L) + text.length * 35L).coerceAtMost(6000L)

@Composable
internal fun IosBannerHost(insidePanel: Boolean, modifier: Modifier = Modifier) {
    val message = IosBanner.current
    // The newest window wins: the main window yields while any sheet is open.
    val visibleHere = message != null && (insidePanel || IosBanner.panels == 0)
    val retained = remember { arrayOfNulls<IosBanner.Message>(1) }
    if (message != null) retained[0] = message
    val shown = retained[0] ?: return
    val progress = remember { Animatable(0f) }
    val reduced = LauncherMotion.reduced
    LaunchedEffect(message?.id, visibleHere) {
        if (message != null && visibleHere) {
            if (reduced) progress.snapTo(1f) else progress.animateTo(1f, LauncherMotion.bannerIn)
            kotlinx.coroutines.delay(iosBannerMillis(message.text, message.long))
            IosBanner.clear(message.id)
        } else {
            if (reduced) progress.snapTo(0f) else progress.animateTo(0f, LauncherMotion.bannerOut)
        }
    }
    if (progress.value <= .001f && !visibleHere) return
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter) {
        Text(shown.text, Modifier
            .graphicsLayer {
                val p = progress.value
                translationY = (1f - p) * -with(density) { 64.dp.toPx() }
                alpha = p.coerceIn(0f, 1f)
                val s = .9f + .1f * p; scaleX = s; scaleY = s
            }
            .widthIn(max = 420.dp)
            .duoGlass(DuoGlassRole.Floating, CircleShape)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("ios-banner"),
            color = duoGlassContentColor(DuoGlassRole.Floating), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
internal fun IosDisclosureChevron(expanded: Boolean, color: Color = iosAccent(), modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f,
        if (LauncherMotion.reduced) tween(0) else LauncherMotion.disclosure, label = "disclosure")
    Icon(IosSymbols.chevronDown, null, modifier.size(14.dp).graphicsLayer { rotationZ = rotation }, tint = color)
}

/** xmark.circle.fill drawn as two layers (a tinted disc and a knocked-out cross), for clear buttons. */
@Composable
internal fun IosClearGlyph(tint: Color, size: Dp = 17.dp, contentDescription: String? = null) {
    Box(Modifier.size(size).background(tint, CircleShape)
        .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center) {
        Icon(IosSymbols.xmark, null, Modifier.size(size * .58f), tint = if (iosSheetDark()) Color.Black else Color.White)
    }
}
