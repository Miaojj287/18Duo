package com.jake.duolauncher

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 26 sheet vocabulary shared by every settings / customization surface: an opaque grouped
 * background (large sheets are not translucent on iOS), Liquid Glass circle buttons in the
 * toolbar (xmark / chevron / blue checkmark), a 34pt large title that collapses into the inline
 * title, inset-grouped lists (12pt continuous corners, 44pt rows, 29pt tinted icon tiles, hairline
 * separators inset to the text), UISwitch, UISlider and capsule buttons. Light or dark is decided
 * once per wallpaper by [AppearanceStore]; nothing in here reads a user preference.
 */
internal object IosUi {
    val blue = Color(0xFF0A84FF)
    val green = Color(0xFF34C759)
    val orange = Color(0xFFFF9F0A)
    val red = Color(0xFFFF453A)
    val grey = Color(0xFF8E8E93)
    val purple = Color(0xFFBF5AF2)
    val indigo = Color(0xFF5E5CE6)
    val teal = Color(0xFF64D2FF)
    val yellow = Color(0xFFFFD60A)
    val pink = Color(0xFFFF375F)
    val sheetRadius = 34.dp
    val groupRadius = 12.dp
    val rowHeight = 44.dp
    val iconBox = 29.dp
    /** Text starts here in a row with an icon tile; separators are inset to it. */
    val textInset = 16.dp + 29.dp + 14.dp
}

@Composable internal fun iosSheetDark(): Boolean = LocalDuoPalette.current.dark

@Composable internal fun iosSecondaryText(): Color = if (iosSheetDark()) Color(0xFFEBEBF5).copy(alpha = .6f) else Color(0xFF3C3C43).copy(alpha = .6f)
/** secondarySystemGroupedBackground (elevated in dark). */
@Composable internal fun iosGroupFill(): Color = if (iosSheetDark()) Color(0xFF2C2C2E) else Color.White
/** systemGroupedBackground (elevated in dark). */
@Composable internal fun iosGroupedBackground(): Color = if (iosSheetDark()) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
@Composable internal fun iosSeparator(): Color = if (iosSheetDark()) Color(0xFF545458).copy(alpha = .65f) else Color(0xFF3C3C43).copy(alpha = .29f)
@Composable internal fun iosAccent(): Color = IosUi.blue

private val groupShape: Shape = ContinuousRoundedShape(IosUi.groupRadius)

/**
 * Body of a sheet: iOS 26 large sheets are opaque grouped backgrounds. The glass underneath only
 * contributes the rim and corner shading; the old 60–70 % tint let Home show through and read as
 * muddy, especially behind the setup wizard.
 */
@Composable
internal fun Modifier.iosSheetSurface(shape: Shape): Modifier {
    return duoGlass(DuoGlassRole.Screen, shape).background(iosGroupedBackground(), shape)
}

/**
 * iOS 26 scroll-edge effect: scrolled content fades out over [top] / [bottom] instead of being cut
 * flat by the viewport (sheet toolbar, setup wizard, App Library, Spotlight above its field). The
 * edges are read while drawing, so scrolling never recomposes; an edge with nothing beyond it
 * stays crisp. Works over any background because it masks the content's own alpha.
 */
internal fun Modifier.iosScrollEdgeFade(
    top: Dp = 18.dp, bottom: Dp = 18.dp,
    fadeTop: () -> Boolean = { true }, fadeBottom: () -> Boolean = { true },
): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val t = if (fadeTop()) (top.toPx() / h).coerceIn(0f, .5f) else 0f
        val b = if (fadeBottom()) (bottom.toPx() / h).coerceIn(0f, .5f) else 0f
        if (t == 0f && b == 0f) return@drawWithContent
        drawRect(Brush.verticalGradient(0f to (if (t > 0f) Color.Transparent else Color.Black), t to Color.Black,
            1f - b to Color.Black, 1f to (if (b > 0f) Color.Transparent else Color.Black)), blendMode = BlendMode.DstIn)
    }

/** Drag hooks for a sheet that can be pulled down to dismiss (provided by [LauncherPanel]). */
internal class IosSheetDrag(val onDrag: (Float) -> Unit, val onRelease: (Float) -> Unit)
internal val LocalIosSheetDrag = staticCompositionLocalOf<IosSheetDrag?> { null }

/** Pull-down handle area (grabber + toolbar), as on iOS. */
@Composable
private fun Modifier.sheetDragHandle(): Modifier {
    val drag = LocalIosSheetDrag.current ?: return this
    val state = rememberDraggableState { drag.onDrag(it) }
    return draggable(state, Orientation.Vertical, onDragStopped = { velocity -> drag.onRelease(velocity) })
}

@Composable
internal fun IosGrabber(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 5.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 5.dp).background(
            if (iosSheetDark()) Color.White.copy(alpha = .3f) else Color.Black.copy(alpha = .2f), CircleShape))
    }
}

/**
 * Grabber + toolbar + content. The toolbar has a glass xmark (cancel/close) or a glass chevron
 * ([leading]) on the left and a blue glass checkmark (Done) on the right, as iOS 26 renders
 * cancellation / confirmation actions. With [largeTitleScroll] the inline title stays hidden
 * until the content's [IosLargeTitle] has scrolled under the toolbar.
 */
@Composable
internal fun IosSheetScaffold(
    title: String,
    modifier: Modifier = Modifier,
    cancelLabel: String? = null, onCancel: (() -> Unit)? = null, cancelTag: String? = null,
    doneLabel: String? = uxText("完成", "Done"), onDone: (() -> Unit)? = null, doneTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    grabber: Boolean = true,
    largeTitleScroll: ScrollState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().sheetDragHandle()) {
            // Only a sheet that can be pulled down shows a grabber (the centred inner-screen card cannot).
            if (grabber && LocalIosSheetDrag.current != null) IosGrabber()
            Box(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 14.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) {
                    when {
                        leading != null -> leading()
                        onCancel != null -> IosGlassCircleButton(IosSymbols.xmark, cancelLabel ?: uxText("取消", "Cancel"),
                            onCancel, tag = cancelTag)
                    }
                }
                val titleAlpha = largeTitleScroll?.let { scroll ->
                    val start = with(density) { 22.dp.toPx() }
                    val span = with(density) { 18.dp.toPx() }
                    ((scroll.value - start) / span).coerceIn(0f, 1f)
                } ?: 1f
                Text(title, Modifier.align(Alignment.Center).padding(horizontal = 64.dp)
                    .graphicsLayer { alpha = titleAlpha }.semantics { heading() },
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, color = iosLabel())
                if (onDone != null && doneLabel != null) Box(Modifier.align(Alignment.CenterEnd)) {
                    IosGlassCircleButton(IosSymbols.checkmark, doneLabel, onDone, tag = doneTag, prominent = true)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // iOS 26 soft scroll edge: scrolled content fades under the toolbar and above the sheet's
            // bottom instead of being cut (a slider thumb used to poke out half-clipped).
            Column(Modifier.fillMaxSize().then(if (largeTitleScroll == null) Modifier else
                Modifier.iosScrollEdgeFade(top = 22.dp, bottom = 22.dp,
                    fadeTop = { largeTitleScroll.value > 0 }, fadeBottom = { largeTitleScroll.canScrollForward })),
                content = content)
        }
    }
}

/** 34pt bold large title at the top of a scrolling sheet page (Inter Display ≈ SF Pro Display). */
@Composable
internal fun IosLargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 8.dp).semantics { heading() },
        style = TextStyle(fontFamily = DuoFonts.display, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp,
            letterSpacing = duoTracking(34f)), color = iosLabel(), maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun IosTextButton(label: String, onClick: () -> Unit, tag: String? = null, bold: Boolean = false,
    color: Color = iosAccent(), enabled: Boolean = true) {
    IosPlainButton(label, onClick, color = color, bold = bold, enabled = enabled, tag = tag)
}

/** iOS 26 back button: a glass circle with a chevron; [label] (the previous page) is its accessibility name. */
@Composable
internal fun IosBackButton(label: String, onClick: () -> Unit, tag: String? = null) {
    IosGlassCircleButton(IosSymbols.chevronLeft, uxText("返回：$label", "Back: $label"), onClick, tag = tag)
}

@Composable
internal fun IosSectionHeader(text: String, modifier: Modifier = Modifier) {
    // iOS 26 grouped headers are sentence-case, 13pt secondary (no more ALL CAPS).
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 7.dp).semantics { heading() },
        fontSize = 13.sp, lineHeight = 18.sp, color = iosSecondaryText())
}

@Composable
internal fun IosFooter(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 4.dp), fontSize = 13.sp, lineHeight = 18.sp,
        color = iosSecondaryText())
}

/** iOS "inset grouped" card. Rows separate themselves with [IosSeparator]. */
@Composable
internal fun IosInsetGroup(title: String? = null, footer: String? = null, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) IosSectionHeader(title) else Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(groupShape).background(iosGroupFill()), content = content)
        footer?.let { IosFooter(it) }
    }
}

/** Hairline (one physical pixel) inset to where the row text starts, as UITableView draws it. */
@Composable
internal fun IosSeparator(inset: Dp = IosUi.textInset) {
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Box(Modifier.fillMaxWidth().padding(start = inset).height(hairline).background(iosSeparator()))
}

@Composable
internal fun IosRowIcon(icon: ImageVector, tint: Color) {
    Box(Modifier.size(IosUi.iconBox).background(tint, ContinuousRoundedShape(7.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(19.dp), tint = Color.White)
    }
}

@Composable
private fun DisclosureChevron() {
    Icon(IosSymbols.chevronRight, null, Modifier.padding(start = 6.dp).size(14.dp), tint = iosTertiaryText())
}

/** Leading tinted icon, 17pt title, optional value in secondary colour, trailing chevron. */
@Composable
internal fun IosRow(
    title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = IosUi.grey,
    value: String? = null, detail: String? = null, chevron: Boolean = true, tag: String? = null,
    enabled: Boolean = true, titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (titleColor == Color.Unspecified) iosLabel() else titleColor
    Row(modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(interaction, IosRowHighlight(iosRowHighlightColor()), enabled,
            role = Role.Button, onClick = onClick) else Modifier)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .heightIn(min = IosUi.rowHeight).padding(start = 16.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IosRowIcon(icon, tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, color = if (enabled) ink else ink.copy(alpha = .4f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = iosSecondaryText(), maxLines = 4) }
        }
        value?.let { Text(it, Modifier.padding(start = 12.dp).widthIn(max = 200.dp), fontSize = 17.sp, lineHeight = 22.sp,
            color = iosSecondaryText(), textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        trailing?.invoke()
        if (chevron && onClick != null) DisclosureChevron()
    }
}

@Composable
internal fun IosToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, tint: Color = IosUi.grey, detail: String? = null, tag: String? = null,
    switchTag: String? = null, contentDescription: String? = null, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    // UITableView rows with a switch don't highlight; the switch knob reacts to the touch instead.
    Row(modifier.fillMaxWidth()
        .toggleable(value = checked, interactionSource = interaction, indication = null, enabled = enabled,
            role = Role.Switch, onValueChange = onChange)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .heightIn(min = IosUi.rowHeight).padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IosRowIcon(icon, tint); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, color = iosLabel().let { if (enabled) it else it.copy(alpha = .4f) })
            detail?.let { Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = iosSecondaryText()) }
        }
        IosSwitch(checked, null, enabled = enabled, interactionSource = interaction,
            modifier = Modifier.then(if (switchTag != null) Modifier.testTag(switchTag) else Modifier)
                .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier))
    }
}

/** A selectable row with a trailing blue checkmark (iOS single-choice list). */
@Composable
internal fun IosCheckRow(title: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier,
    detail: String? = null, tag: String? = null) {
    Row(modifier.fillMaxWidth().selectable(selected, remember { MutableInteractionSource() },
        IosRowHighlight(iosRowHighlightColor()), role = Role.RadioButton, onClick = onSelect)
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .heightIn(min = IosUi.rowHeight).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, color = iosLabel())
            detail?.let { Text(it, fontSize = 13.sp, lineHeight = 17.sp, color = iosSecondaryText()) }
        }
        if (selected) Icon(IosSymbols.checkmark, null, Modifier.size(18.dp), tint = iosAccent())
    }
}

/** Title + current value above a UISlider, inside a grouped card. */
@Composable
internal fun IosSliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit,
    modifier: Modifier = Modifier, valueLabel: String = "${value.toInt()}", tag: String? = null, icon: ImageVector? = null,
    tint: Color = IosUi.grey, steps: Int = 0) {
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { IosRowIcon(icon, tint); Spacer(Modifier.width(14.dp)) }
            Text(title, Modifier.weight(1f), fontSize = 17.sp, color = iosLabel())
            Text(valueLabel, fontSize = 17.sp, color = iosSecondaryText())
        }
        IosSlider(value, onChange, valueRange = range, steps = steps, label = title,
            modifier = Modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier))
    }
}

/** iOS 26 capsule button: filled (blue, white text) or tinted (accent text on a pale accent fill). */
@Composable
internal fun IosButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true,
    enabled: Boolean = true, tag: String? = null, color: Color = iosAccent()) {
    val tinted = if (iosSheetDark()) color.copy(alpha = .24f) else color.copy(alpha = .14f)
    val fill = if (filled) color else tinted
    Box(modifier.heightIn(min = 50.dp).clip(CircleShape)
        .clickable(remember { MutableInteractionSource() }, IosDimIndication, enabled, role = Role.Button, onClick = onClick)
        .background(if (enabled) fill else fill.copy(alpha = fill.alpha * .4f))
        .then(if (tag != null) Modifier.testTag(tag) else Modifier)
        .padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        val ink = if (filled) Color.White else color
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            color = if (enabled) ink else ink.copy(alpha = .5f))
    }
}

internal data class IosChipItem(
    val key: String,
    val label: String,
    val tag: String? = null,
    val preview: @Composable BoxScope.() -> Unit,
)

/**
 * Horizontal row of icon chips (iOS "默认 / 深色 / 透明 / 色调" style): a 64dp preview tile
 * with a blue ring when selected and a label underneath.
 */
@Composable
internal fun IosChipRow(items: List<IosChipItem>, selectedKey: String?, onSelect: (IosChipItem) -> Unit,
    modifier: Modifier = Modifier, tileSize: Dp = 64.dp) {
    val dark = iosSheetDark()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item ->
            val selected = item.key == selectedKey
            val shape = ContinuousRoundedShape(tileSize * .25f)
            Column(Modifier.width(tileSize + 16.dp)
                .selectable(selected, remember { MutableInteractionSource() }, IosDimIndication, role = Role.RadioButton,
                    onClick = { onSelect(item) })
                .then(if (item.tag != null) Modifier.testTag(item.tag) else Modifier)
                .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(tileSize).clip(shape)
                    .background(iosGroupFill())
                    .border(if (selected) 2.5.dp else 1.dp,
                        if (selected) IosUi.blue else if (dark) Color.White.copy(alpha = .14f) else Color.Black.copy(alpha = .08f), shape),
                    contentAlignment = Alignment.Center) { item.preview(this) }
                Spacer(Modifier.height(6.dp))
                Text(item.label, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) IosUi.blue else iosLabel())
            }
        }
    }
}

/** Shared tile glyphs so every Settings surface uses the same SF-style artwork. */
@Composable
internal fun IosChipGlyph(icon: ImageVector, tint: Color) {
    Icon(icon, null, Modifier.size(28.dp), tint = tint)
}
