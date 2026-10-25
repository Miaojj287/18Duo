package com.jake.duolauncher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The widget sizes iOS offers from a widget's context menu (iPhone: small, medium, large). */
enum class IosWidgetSize(val spanX: Int, val spanY: Int, val zh: String, val en: String) {
    SMALL(2, 2, "小", "Small"), MEDIUM(4, 2, "中", "Medium"), LARGE(4, 4, "大", "Large");

    val label: String get() = uxText(zh, en)
}

/**
 * One button of the size row. [column] / [row] are where the widget goes at that size (iOS keeps
 * its row and moves it left — or up, at the bottom of a page — only as far as the bigger size
 * needs); [movedIcons] is how many icons flow on to make room. Both are null when no spot avoids
 * the page's other widgets, so the button is shown dimmed.
 */
data class WidgetSizeChoice(val size: IosWidgetSize, val selected: Boolean, val column: Int?, val row: Int? = null,
    val movedIcons: Int = 0) {
    val enabled: Boolean get() = selected || column != null
}

/**
 * The size row of [slot]'s context menu: every iOS size the widget supports ([constraints];
 * native widgets come in small, medium and large), in iOS order. Fewer than two supported sizes
 * means no row at all.
 */
fun widgetSizeChoices(layout: HomeLayout, slot: Int, constraints: WidgetSpanConstraints?): List<WidgetSizeChoice> {
    val placement = layout.placement(slot) ?: return emptyList()
    if (constraints == null || placement.page < -1) return emptyList()
    val supported = IosWidgetSize.entries.filter { size ->
        size.spanX in constraints.minimum.width..constraints.maximum.width &&
            size.spanY in constraints.minimum.height..constraints.maximum.height &&
            (constraints.canResizeHorizontally || size.spanX == placement.spanX) &&
            (constraints.canResizeVertically || size.spanY == placement.spanY)
    }
    if (supported.size < 2) return emptyList()
    return supported.map { size ->
        val selected = size.spanX == placement.spanX && size.spanY == placement.spanY
        if (selected) return@map WidgetSizeChoice(size, true, placement.column, placement.row)
        val plan = widgetReflowPlan(layout, slot, size)
        WidgetSizeChoice(size, false, plan?.placement?.column, plan?.placement?.row, plan?.moved ?: 0)
    }
}

/** [layout] with [slot] at [size] in [column] of its row, or null when that does not fit without moving anything. */
fun resizedWidgetLayout(layout: HomeLayout, slot: Int, size: IosWidgetSize, column: Int): HomeLayout? {
    val placement = layout.placement(slot) ?: return null
    val moved = if (column == placement.column) layout
        else moveWidget(layout, slot, homeCellIndex(placement.page, placement.row * GRID_COLUMNS + column))
    if (column != placement.column && moved == layout) return null
    val resized = resizeWidget(moved, slot, size.spanX, size.spanY)
    return resized.takeIf { it != moved }
}

/** A planned iOS resize: the new [layout], the widget's new [placement], and how many icons [moved]. */
class WidgetReflowPlan(val layout: HomeLayout, val placement: WidgetPlacement, val moved: Int)

/** [layout] after an iOS-style resize of [slot] to [size] (see [widgetReflowPlan]); null when it cannot be done. */
fun reflowedWidgetLayout(layout: HomeLayout, slot: Int, size: IosWidgetSize): HomeLayout? = widgetReflowPlan(layout, slot, size)?.layout

/**
 * iOS resizes a widget in place and moves the icons out of its way instead of refusing: the
 * widget keeps its row (moving left / up only as far as the size needs), the icons it now covers
 * flow on in reading order to the following cells of the page — each pushing the next along until
 * a free cell absorbs it — and whatever no longer fits goes to a new page inserted right after this
 * one. Other widgets never move, so a size whose every spot overlaps one stays unavailable, as does
 * an overflow on the Today page (it has no "next page"). No icon is ever dropped: the result keeps
 * exactly the same shortcuts, or the plan is refused. One commit, so one Undo restores everything.
 */
fun widgetReflowPlan(layout: HomeLayout, slot: Int, size: IosWidgetSize): WidgetReflowPlan? {
    val old = layout.placement(slot) ?: return null
    if (old.page < -1) return null
    val id = nativeWidgetIdForSpan(old.id, size.spanX, size.spanY)
    val span = nativeWidgetSize(id)?.span ?: WidgetSpan(size.spanX, size.spanY)
    val rows = widgetRowsForPage(old.page)
    if (span.width > GRID_COLUMNS || span.height > rows) return null
    // Keep the widget's corner; shift left / up only when the bigger size would run off the page.
    val column = if (old.column + span.width <= GRID_COLUMNS) old.column else GRID_COLUMNS - span.width
    val row = if (old.row + span.height <= rows) old.row else rows - span.height
    val target = old.copy(id = id, column = column, row = row, spanX = span.width, spanY = span.height)
    val others = layout.widgetPlacements.filter { it.slot != slot && it.page == old.page }
    if (others.any { it.column < target.column + target.spanX && target.column < it.column + it.spanX &&
            it.row < target.row + target.spanY && target.row < it.row + it.spanY }) return null
    return reflowAround(layout, old, target, others)
}

private fun reflowAround(layout: HomeLayout, old: WidgetPlacement, target: WidgetPlacement, others: List<WidgetPlacement>): WidgetReflowPlan? {
    val page = old.page
    // Icons live in the first HOME_CELLS cells of a page (the Today page's later rows hold widgets only).
    val iconCells = (0 until HOME_CELLS).map { homeCellIndex(page, it) }
    val blocked = others.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    val covered = target.coveredIndices()
    val queue = ArrayDeque<String>()
    var next = layout.copy(widgetPlacements = layout.widgetPlacements.filterNot { it.slot == old.slot })
    var moved = 0
    for (cell in iconCells) {
        if (cell in blocked) continue
        val here = next.slotAt(cell)
        if (cell in covered) {
            if (here != null) { queue.addLast(here); moved++; next = next.withSlot(cell, null) }
        } else if (queue.isNotEmpty()) {
            if (here != null) queue.addLast(here)
            next = next.withSlot(cell, queue.removeFirst())
        }
    }
    if (queue.isNotEmpty()) {
        if (page < 0) return null
        next = insertHomePageAfter(next, page, queue.toList())
    }
    val placed = placeWidget(next, target)
    if (placed.placement(old.slot) != target) return null
    // Safety net: exactly the same shortcuts before and after, or no change at all.
    fun shortcuts(l: HomeLayout) = (l.slots + l.leadingSlots).filterNotNull().sorted()
    if (shortcuts(placed) != shortcuts(layout) || placed.dock != layout.dock || placed.folders != layout.folders) return null
    return WidgetReflowPlan(placed, target, moved)
}

/** Inserts a page after [page] holding [icons] from its first cell; later pages and their widgets shift by one. */
internal fun insertHomePageAfter(layout: HomeLayout, page: Int, icons: List<String>): HomeLayout {
    require(page >= 0 && icons.size <= HOME_CELLS)
    val at = (page + 1) * HOME_CELLS
    val slots = layout.slots.toMutableList()
    while (slots.size < at) slots.add(null)
    slots.addAll(at, icons + List(HOME_CELLS - icons.size) { null })
    return layout.copy(slots = slots.dropLastWhile { it == null },
        widgetPlacements = layout.widgetPlacements.map { if (it.page > page) it.copy(page = it.page + 1) else it })
}

internal val WIDGET_SIZE_ROW_HEIGHT = 52.dp

/** iOS 18 widget menu header: one glyph per size, the current one filled. */
@Composable
internal fun WidgetSizeRow(choices: List<WidgetSizeChoice>, onPick: (WidgetSizeChoice) -> Unit) {
    Row(Modifier.fillMaxSize().testTag("widget-size-row"), verticalAlignment = Alignment.CenterVertically) {
        choices.forEach { choice ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val tint = LocalContentColor.current
            Box(Modifier.weight(1f).fillMaxHeight().testTag("widget-size-${choice.size.name.lowercase()}")
                .background(tint.copy(alpha = if (pressed && choice.enabled) .10f else 0f))
                .semantics {
                    contentDescription = choice.size.label
                    selected = choice.selected
                }
                .clickable(interactionSource = interaction, indication = null, enabled = choice.enabled && !choice.selected,
                    role = Role.Button) { onPick(choice) },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(34.dp * LocalContextMenuScale.current)) {
                    // Glyph proportions follow the widget's cell shape: 1:1, 2:1 and a larger 1:1.
                    val unit = size.width / 4f
                    val w = choice.size.spanX * unit * .92f
                    val h = choice.size.spanY * unit * .92f
                    val topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                    val radius = CornerRadius(minOf(w, h) * .24f)
                    val alpha = if (choice.enabled) 1f else .3f
                    if (choice.selected) drawRoundRect(tint.copy(alpha = alpha), topLeft, Size(w, h), radius)
                    else drawRoundRect(tint.copy(alpha = alpha), topLeft + Offset(.8.dp.toPx(), .8.dp.toPx()),
                        Size(w - 1.6.dp.toPx(), h - 1.6.dp.toPx()), radius, style = Stroke(1.6.dp.toPx()))
                }
            }
        }
    }
}
