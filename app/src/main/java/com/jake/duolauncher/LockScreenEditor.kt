package com.jake.duolauncher

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The lock screen customisation editor ("自定锁屏", iOS 16–26): the lock screen itself is the preview
 * (LockScreenUi draws it with [LockScreenEditorState] non-null); this file adds the iOS chrome —
 * the dimmed wallpaper's outline around the clock with the iOS 26 size handle at its bottom-right
 * corner, the 「时间」 sheet (font, colour, hue/vibrancy), the ⊖ badges and 「添加小组件」 pill on the
 * widget row with its sheet (widgets, position), and Cancel / Done at the top.
 */

@Stable
internal class LockScreenEditorState(initial: LockScreenConfig, val onDone: (LockScreenConfig) -> Unit, val onCancel: () -> Unit) {
    var draft by mutableStateOf(initial)
    /** "time" or "widgets", or null. */
    var sheet by mutableStateOf<String?>(null)
    val clockBounds = LockClockBounds()
    /** Identity ([lockWidgetKeys]) of the widget lifted for reorder, and how far it trails the finger from its slot (dp). */
    var dragKey by mutableStateOf<String?>(null)
    var dragOffsetDp by mutableStateOf(0f)

    fun update(change: (LockScreenConfig) -> LockScreenConfig) { draft = change(draft) }

    /**
     * iOS reorder: a long press lifts the widget, it follows the finger, and it trades places with a
     * neighbour once it has passed half of that neighbour; the neighbour slides over ([LockWidgetRow]).
     */
    fun widgetDragModifier(key: String, pitchDp: Float, onLift: () -> Unit): Modifier = Modifier.pointerInput(key, pitchDp) {
        detectDragGesturesAfterLongPress(
            onDragStart = { dragKey = key; dragOffsetDp = 0f; onLift() },
            onDragEnd = { dragKey = null; dragOffsetDp = 0f }, onDragCancel = { dragKey = null; dragOffsetDp = 0f },
        ) { change, delta ->
            change.consume()
            dragOffsetDp += delta.x / density
            val widgets = draft.widgets
            val index = lockWidgetKeys(widgets).indexOf(key)
            if (index < 0) return@detectDragGesturesAfterLongPress
            val next = widgets.getOrNull(index + 1)
            val previous = widgets.getOrNull(index - 1)
            if (next != null && dragOffsetDp > next.units * pitchDp * .5f) {
                draft = draft.copy(widgets = LockWidgetSlots.move(widgets, index, index + 1))
                dragOffsetDp -= next.units * pitchDp
            } else if (previous != null && dragOffsetDp < -previous.units * pitchDp * .5f) {
                draft = draft.copy(widgets = LockWidgetSlots.move(widgets, index, index - 1))
                dragOffsetDp += previous.units * pitchDp
            }
        }
    }
}

/** Stable identities for the row's widgets ("weather#0", "battery#0", "battery#1"): they survive reorders. */
internal fun lockWidgetKeys(widgets: List<LockWidgetKind>): List<String> {
    val seen = HashMap<LockWidgetKind, Int>()
    return widgets.map { kind -> val n = seen[kind] ?: 0; seen[kind] = n + 1; "${kind.key}#$n" }
}

@Composable
internal fun BoxScope.LockWidgetRemoveBadge(index: Int, editor: LockScreenEditorState) {
    val label = uxText("移除小组件", "Remove widget")
    Box(Modifier.align(Alignment.TopStart).offset((-8).dp, (-8).dp).size(26.dp)
        .testTag("lock-widget-remove-$index")
        .semantics { contentDescription = label; role = Role.Button }
        .clickable { editor.update { it.copy(widgets = LockWidgetSlots.remove(it.widgets, index)) } }
        .padding(2.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xE63A3A3C))
            drawCircle(Color.White.copy(alpha = .35f), radius = size.minDimension / 2f - .5.dp.toPx(), style = Stroke(1.dp.toPx()))
            drawLine(Color.White, Offset(size.width * .3f, size.height / 2f), Offset(size.width * .7f, size.height / 2f), 2.2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** "添加小组件" where the row has room: a capsule in the free slots (iOS) that opens the widget sheet. */
@Composable
internal fun LockWidgetAddPill(config: LockScreenConfig, slab: LockSlabLayout, rowLeft: Float, centerY: Float,
    editor: LockScreenEditorState) {
    val used = LockWidgetSlots.used(config.widgets)
    val free = LockWidgetSlots.UNITS - used
    if (free <= 0) return // a full row: tapping a widget opens the sheet instead
    val label = uxText("添加小组件", "Add widget")
    val left = slab.slotLeft(rowLeft, used)
    val width = slab.unit + slab.pitch * (free - 1)
    // Round 12.4 (owner's iOS 26 editor screenshots): an empty row is one outlined box the row's full
    // width and a little taller than a widget, "⊕ 添加小组件" centred; with widgets present the free
    // slots keep the dashed capsule.
    val empty = used == 0
    val pad = if (empty) LockEditorSpec.ADD_BOX_PAD_PT * slab.unit / LockSlabSpec.UNIT_PT else 0f
    Box(Modifier.offset { IntOffset(left.dp.roundToPx(), (centerY - slab.unit / 2f - pad).dp.roundToPx()) }
        .size(width.dp, (slab.unit + pad * 2f).dp)
        .testTag("lock-add-widget")
        .semantics { contentDescription = label; role = Role.Button }
        .pointerInput(Unit) { detectTapGestures { editor.sheet = "widgets" } }) {
        Canvas(Modifier.fillMaxSize()) {
            if (empty) {
                val r = LockEditorSpec.ADD_BOX_RADIUS_PT.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = .10f), Offset.Zero, size, CornerRadius(r))
                drawRoundRect(Color.White.copy(alpha = .7f), Offset.Zero, size, CornerRadius(r), style = Stroke(1.5.dp.toPx()))
            } else {
                val r = size.height / 2f
                drawRoundRect(Color.White.copy(alpha = .18f), Offset.Zero, size, CornerRadius(r))
                drawRoundRect(Color.White.copy(alpha = .6f), Offset.Zero, size, CornerRadius(r), style = Stroke(1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            }
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (empty) {
                Canvas(Modifier.size(14.dp)) {
                    drawCircle(Color.White.copy(alpha = .9f))
                    val c = size.width / 2f; val a = size.width * .22f
                    drawLine(Color(0xFF6E6E73), Offset(c - a, c), Offset(c + a, c), 1.6.dp.toPx())
                    drawLine(Color(0xFF6E6E73), Offset(c, c - a), Offset(c, c + a), 1.6.dp.toPx())
                }
                Spacer(Modifier.width(5.dp))
            }
            Text(if (free >= 2) label else "+", color = Color.White, fontSize = if (empty) 15.sp else 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The iOS 26 Lock Screen editor's chrome, measured on the owner's two screenshots (1320×2868 iPhone,
 * 440 pt wide; `reference/user-videos-r12/ios26-lock-editor-*.webp`): 取消 / 完成 pills 63 × 27 pt
 * at 19 pt from the top and 52 pt from the sides; no title; a 1.5 pt outline around the date line
 * (6 pt padding) and another around the time (10 pt), 14 pt corners, 29 pt from the panel's sides;
 * the size handle a Ø 36 pt glass disc with a rounded-corner bracket at the time box's bottom-right;
 * the empty widget row one outlined box 91 pt tall (a 60 pt widget plus 15.5 pt each way), 16 pt corners.
 */
internal object LockEditorSpec {
    const val PILL_HEIGHT_PT = 27f
    const val PILL_TEXT_PT = 15f
    const val PILL_TOP_PT = 19f
    const val PILL_SIDE_PT = 52f
    const val DATE_BOX_PAD_PT = 6f
    const val CLOCK_BOX_PAD_PT = 10f
    const val BOX_RADIUS_PT = 14f
    const val BOX_SIDE_PT = 29f
    const val HANDLE_PT = 36f
    const val ADD_BOX_PAD_PT = 15.5f
    const val ADD_BOX_RADIUS_PT = 16f
}

/** Everything the editor draws on top of the preview. */
@Composable
internal fun LockScreenEditorOverlay(editor: LockScreenEditorState, geometry: LockScreenGeometry, slab: LockSlabLayout,
    widthDp: Float, heightDp: Float) {
    val haptics = LocalHapticFeedback.current
    // Points of the owner's iPhone screenshots become dp by the panel's short edge / 440 pt (as the slab does).
    val pt = minOf(widthDp, heightDp) / LockSlabSpec.REC_WIDTH_PT
    Box(Modifier.fillMaxSize()) {
        // Round 12.4 (iOS 26 editor): one outline around the date line and one around the time, both the
        // text area's width; the size handle sits on the time box's bottom-right corner.
        val rect = editor.clockBounds.rect
        val dateRect = editor.clockBounds.dateRect
        val boxLeft = minOf(geometry.textAreaLeft, rect.left, dateRect.left) - LockEditorSpec.BOX_SIDE_PT * pt * .5f
        val boxRight = maxOf(geometry.textAreaRight, rect.right, dateRect.right) + LockEditorSpec.BOX_SIDE_PT * pt * .5f
        val radius = LockEditorSpec.BOX_RADIUS_PT * pt
        // The two boxes never overlap: where the date sits close to the time they meet at the midpoint, 2 pt apart.
        val seam = if (dateRect.width > 0f && rect.width > 0f) (dateRect.bottom + rect.top) / 2f else Float.NaN
        if (dateRect.width > 0f) {
            val pad = LockEditorSpec.DATE_BOX_PAD_PT * pt
            val bottom = if (seam.isNaN()) dateRect.bottom + pad else minOf(dateRect.bottom + pad, seam - 2f * pt)
            Box(Modifier.offset { IntOffset(boxLeft.dp.roundToPx(), (dateRect.top - pad).dp.roundToPx()) }
                .size((boxRight - boxLeft).dp, (bottom - (dateRect.top - pad)).coerceAtLeast(1f).dp)
                .testTag("lock-date-outline")) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(Color.White.copy(alpha = .7f), Offset.Zero, size, CornerRadius((radius * .6f).dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        if (rect.width > 0f) {
            val pad = LockEditorSpec.CLOCK_BOX_PAD_PT * pt
            val top = if (seam.isNaN()) rect.top - pad else maxOf(rect.top - pad, seam + 2f * pt)
            Box(Modifier.offset { IntOffset(boxLeft.dp.roundToPx(), top.dp.roundToPx()) }
                .size((boxRight - boxLeft).dp, (rect.bottom + pad - top).coerceAtLeast(1f).dp)
                .testTag("lock-clock-outline")
                .semantics { contentDescription = uxText("时间样式", "Time style"); role = Role.Button }
                .pointerInput(Unit) { detectTapGestures { editor.sheet = "time" } }) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(Color.White.copy(alpha = .7f), Offset.Zero, size, CornerRadius(radius.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            }
            // Size handle (iOS 26): a glass disc on the box's bottom-right corner carrying a rounded "┘"
            // bracket; drag down to make the time taller.
            val handle = LockEditorSpec.HANDLE_PT * pt
            Box(Modifier.offset { IntOffset((boxRight - handle / 2f).dp.roundToPx(), (rect.bottom + pad - handle / 2f).dp.roundToPx()) }
                .size(handle.dp)
                .testTag("lock-clock-size-handle")
                .semantics { contentDescription = uxText("调整时间大小", "Resize time"); role = Role.Button }
                .pointerInput(geometry) {
                    detectDragGestures(onDragStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }) { change, delta ->
                        change.consume()
                        val before = editor.draft.stretch
                        // The handle sits on the corner: down, right or down-right all pull it outward (iOS 26).
                        val along = LockClockSize.handleTravel(delta.x, delta.y) / density
                        val after = LockClockSize.stretchAfterDrag(before, along, geometry.clockDigitHeight)
                        if (after != before) {
                            editor.update { it.copy(stretch = after) }
                            if ((after == 0f || after == 1f) && before != after)
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                }
                .duoGlass(DuoGlassRole.Control, CircleShape, dark = false)) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = size.minDimension / 2f
                    val r = c * .5f
                    val stroke = Stroke(c * .17f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(c + r, c - r)
                        lineTo(c + r, c)
                        arcTo(Rect(c - r, c - r, c + r, c + r), 0f, 90f, false)
                        lineTo(c - r, c + r)
                    }
                    drawPath(path, Color.White, style = stroke)
                }
            }
        }
        // Top row (iOS 26): compact 取消 / 完成 pills in the status bar's row, no title.
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .padding(top = (LockEditorSpec.PILL_TOP_PT * pt).dp, start = (LockEditorSpec.PILL_SIDE_PT * pt).dp, end = (LockEditorSpec.PILL_SIDE_PT * pt).dp),
            verticalAlignment = Alignment.CenterVertically) {
            IosGlassCapsuleButton(uxText("取消", "Cancel"), editor.onCancel, tag = "lock-editor-cancel",
                height = (LockEditorSpec.PILL_HEIGHT_PT * pt).dp, fontSize = LockEditorSpec.PILL_TEXT_PT * pt, horizontalPadding = (14f * pt).dp)
            Spacer(Modifier.weight(1f))
            IosGlassCapsuleButton(uxText("完成", "Done"), { editor.onDone(editor.draft) }, tag = "lock-editor-done", prominent = true,
                height = (LockEditorSpec.PILL_HEIGHT_PT * pt).dp, fontSize = LockEditorSpec.PILL_TEXT_PT * pt, horizontalPadding = (14f * pt).dp)
        }
        when (editor.sheet) {
            "time" -> EditorSheet(uxText("时间", "Time"), { editor.sheet = null }, heightDp, "lock-time-sheet") { TimeSheet(editor) }
            "widgets" -> EditorSheet(uxText("添加小组件", "Add Widgets"), { editor.sheet = null }, heightDp, "lock-widgets-sheet", tall = true) { WidgetsSheet(editor) }
        }
    }
}

/** A short iOS detent sheet at the bottom of the preview (tap outside to close). */
@Composable
private fun BoxScope.EditorSheet(title: String, onClose: () -> Unit, heightDp: Float, tag: String, tall: Boolean = false,
    content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } })
    val shape = ContinuousRoundedShape(IosUi.sheetRadius)
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = 520.dp).padding(horizontal = 8.dp, vertical = 8.dp)
        .height(if (tall) (heightDp * .62f).coerceIn(320f, 560f).dp else (heightDp * .46f).coerceIn(250f, 420f).dp)
        .clip(shape).iosSheetSurface(shape).testTag(tag)
        .pointerInput(Unit) { detectTapGestures { } }) {
        IosSheetScaffold(title, onDone = onClose, doneTag = "$tag-done", grabber = false) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) { content() }
        }
    }
}

// 「时间」 sheet -------------------------------------------------------------------------------------

@Composable
private fun TimeSheet(editor: LockScreenEditorState) {
    val context = LocalContext.current
    val draft = editor.draft
    IosSectionHeader(uxText("字体", "Font"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LockClockFont.entries.forEach { font ->
            val selected = font == draft.font
            val typeface = remember(font) { lockClockTypeface(context, font) }
            val label = uxText(font.zh, font.en)
            Box(Modifier.size(58.dp).clip(CircleShape).background(if (selected) Color(draft.color).copy(alpha = .25f) else iosGroupFill())
                .border(if (selected) 2.dp else 1.dp, if (selected) Color(draft.color) else iosSeparator(), CircleShape)
                .testTag("lock-font-${font.key}")
                .semantics { contentDescription = label; role = Role.RadioButton }
                .clickable { editor.update { it.copy(font = font) } }, contentAlignment = Alignment.Center) {
                val ink = iosLabel()
                Canvas(Modifier.fillMaxSize()) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.typeface = typeface; textAlign = Paint.Align.CENTER; color = ink.toArgb()
                        textScaleX = font.widthScale
                        isFakeBoldText = font.weight > 700
                    }
                    StandbyTextMetrics.sizeForCap(paint, 20.dp.toPx(), "4")
                    drawContext.canvas.nativeCanvas.drawText("12", size.width / 2f, size.height / 2f + 10.dp.toPx(), paint)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    // Round 12 「对齐」(owner: "没办法调整位置"): 居中 is iOS's 0.5 W; 靠左 / 靠右 hang the time, the date
    // and the under-date widget row on the text area's edge. Stored in the config JSON as `align`.
    IosInsetGroup(footer = uxText("时间、日期和「时间下方」的小组件一起对齐。居中时与 iPhone 一致。",
        "The time, the date and widgets placed below the time align together. Centered matches the iPhone.")) {
        Row(Modifier.fillMaxWidth().heightIn(min = IosUi.rowHeight).padding(start = 16.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
            .testTag("lock-align-row"), verticalAlignment = Alignment.CenterVertically) {
            Text(uxText("对齐", "Alignment"), Modifier.weight(1f), fontSize = 17.sp, color = iosLabel())
            IosSegmentedControl(LockTextAlign.entries.map { uxText(it.zh, it.en) }, draft.align.ordinal,
                { editor.update { c -> c.copy(align = LockTextAlign.entries[it]) } }, Modifier.width(204.dp),
                tags = LockTextAlign.entries.map { "lock-align-${it.key}" })
        }
    }
    Spacer(Modifier.height(6.dp))
    // Round 12.4: iOS 「中国农历」 — the lunar date after the date line (only drawn in a Chinese locale).
    IosInsetGroup {
        IosToggleRow(uxText("农历", "Chinese lunar date"), draft.lunar, { editor.update { c -> c.copy(lunar = it) } },
            detail = uxText("日期后显示「丙午年九月十二」", "Adds the lunar date after the date line"), tag = "lock-lunar")
    }
    Spacer(Modifier.height(6.dp))
    IosSectionHeader(uxText("颜色", "Color"))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Round 13: iOS 26 lists 「玻璃」 first — clear glass digits that refract the wallpaper.
        Box(Modifier.size(34.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .25f))))
            .border(if (draft.glass) 3.dp else 1.dp, if (draft.glass) iosLabel() else iosSeparator(), CircleShape)
            .testTag("lock-color-glass")
            .semantics { role = Role.RadioButton; contentDescription = uxText("玻璃", "Glass") }
            .clickable { editor.update { it.copy(glass = true, color = LockClockColors.WHITE) } })
        LockClockColors.swatches.forEachIndexed { i, argb ->
            val selected = !draft.glass && argb == draft.color
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color(argb))
                .border(if (selected) 3.dp else 1.dp, if (selected) iosLabel() else iosSeparator(), CircleShape)
                .testTag("lock-color-$i")
                .semantics { role = Role.RadioButton; contentDescription = uxText("颜色 $i", "Color $i") }
                .clickable { editor.update { it.copy(color = argb, glass = false) } })
        }
    }
    Spacer(Modifier.height(6.dp))
    val (hue, vibrancy) = remember(draft.color) { LockClockColors.hueAndVibrancy(draft.color) }
    IosInsetGroup {
        IosSliderRow(uxText("色相", "Hue"), hue, 0f..360f, { h ->
            editor.update { c -> c.copy(glass = false, color = LockClockColors.fromHue(h, vibrancy.coerceAtLeast(.08f))) } }, tag = "lock-hue")
        IosSeparator()
        IosSliderRow(uxText("鲜艳度", "Vibrancy"), vibrancy, 0f..1f, { v ->
            editor.update { c -> c.copy(glass = false, color = LockClockColors.fromHue(hue, v)) } }, valueLabel = "${(vibrancy * 100).roundToInt()}%", tag = "lock-vibrancy")
    }
    IosFooter(uxText("拖动时间右下角的圆点可以调整时间的高度；宽度随之变化，但不会超出屏幕。",
        "Drag the dot at the time's bottom-right corner to change its height; the width follows but never leaves the screen."))
    Spacer(Modifier.height(8.dp))
    val heightScale = LockClockSize.heightScale(draft.stretch)
    IosInsetGroup {
        IosSliderRow(uxText("大小", "Size"), draft.stretch, 0f..1f, { editor.update { c -> c.copy(stretch = it) } },
            valueLabel = "×%.2f".format(heightScale), tag = "lock-size")
    }
}

// 「添加小组件」 sheet ----------------------------------------------------------------------------------

@Composable
private fun WidgetsSheet(editor: LockScreenEditorState) {
    val draft = editor.draft
    val used = LockWidgetSlots.used(draft.widgets)
    val haptics = LocalHapticFeedback.current
    // iOS 26 widget picker: the row as it stands, then live previews of every widget on dark platters;
    // tapping one drops it into the row's free slots.
    LockWidgetRowPreview(draft.widgets, onRemove = { i -> editor.update { it.copy(widgets = LockWidgetSlots.remove(it.widgets, i)) } })
    Text(uxText("已用 $used / ${LockWidgetSlots.UNITS} 格 · 长按锁屏上的小组件可拖动排序", "$used of ${LockWidgetSlots.UNITS} spaces used · long-press a widget on the Lock Screen to reorder"),
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), fontSize = 12.sp, color = iosSecondaryText(),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    IosSectionHeader(uxText("小组件位置", "Widget position"))
    IosSegmentedControl(LockWidgetPosition.entries.map { uxText(it.zh, it.en) }, draft.position.ordinal,
        { editor.update { c -> c.copy(position = LockWidgetPosition.entries[it]) } }, Modifier.fillMaxWidth(),
        tags = LockWidgetPosition.entries.map { "lock-position-${it.key}" })
    listOf(true to uxText("圆形", "Circular"), false to uxText("矩形", "Rectangular")).forEach { (circular, title) ->
        IosSectionHeader(title)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LockWidgetKind.entries.filter { it.circular == circular }.forEach { kind ->
                val fits = LockWidgetSlots.canAdd(draft.widgets, kind)
                LockWidgetGalleryTile(kind, fits) {
                    if (fits) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        editor.update { it.copy(widgets = LockWidgetSlots.add(it.widgets, kind)) }
                    }
                }
            }
        }
    }
    IosFooter(uxText("圆形小组件占 1 格，矩形占 2 格，一行共 4 格。", "A circular widget takes 1 space, a rectangular one 2; a row has 4."))
}

private val LockPlatter = Color(0xFF2C2C2E)

/** One picker tile: the widget drawn live on a dark platter, its name under it; dimmed when the row has no room. */
@Composable
private fun LockWidgetGalleryTile(kind: LockWidgetKind, fits: Boolean, onAdd: () -> Unit) {
    val unit = 52f
    val label = uxText(kind.zh, kind.en)
    val width = if (kind.circular) unit + 28f else unit * 2f + 36f
    Column(Modifier.width(width.dp).graphicsLayer { alpha = if (fits) 1f else .38f }
        .testTag("lock-add-${kind.key}")
        .semantics { contentDescription = label; role = Role.Button }
        .clickable(enabled = fits, onClick = onAdd), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height((unit + 28f).dp).clip(ContinuousRoundedShape(18.dp)).background(LockPlatter),
            contentAlignment = Alignment.Center) {
            LockAccessoryWidget(kind, unit, preview = true, Modifier.size((if (kind.circular) unit else unit * 2f + 8f).dp, unit.dp))
        }
        Text(label, Modifier.padding(top = 5.dp), fontSize = 12.sp, color = iosLabel(), maxLines = 1)
        Text(if (fits) uxText("轻点添加", "Tap to add") else uxText("空间不足", "No space"), fontSize = 11.sp, color = iosSecondaryText(), maxLines = 1)
    }
}

/** The row as it is now, at small size on a platter, each widget with its ⊖. */
@Composable
private fun LockWidgetRowPreview(widgets: List<LockWidgetKind>, onRemove: (Int) -> Unit) {
    val unit = 44f
    val gap = 8f
    Box(Modifier.fillMaxWidth().height((unit + 30f).dp).clip(ContinuousRoundedShape(20.dp)).background(LockPlatter)
        .testTag("lock-row-preview"), contentAlignment = Alignment.Center) {
        if (widgets.isEmpty()) Text(uxText("没有小组件", "No widgets"), fontSize = 14.sp, color = Color.White.copy(alpha = .6f))
        Row(horizontalArrangement = Arrangement.spacedBy(gap.dp), verticalAlignment = Alignment.CenterVertically) {
            widgets.forEachIndexed { i, kind ->
                val w = if (kind.circular) unit else unit * 2f + gap
                Box(Modifier.size(w.dp, unit.dp)) {
                    LockAccessoryWidget(kind, unit, preview = true, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.TopStart).offset((-7).dp, (-7).dp).size(20.dp)
                        .testTag("lock-current-$i")
                        .semantics { contentDescription = uxText("移除小组件", "Remove widget"); role = Role.Button }
                        .clickable { onRemove(i) }) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color(0xFF636366))
                            drawLine(Color.White, Offset(size.width * .3f, size.height / 2f), Offset(size.width * .7f, size.height / 2f), 2.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}
