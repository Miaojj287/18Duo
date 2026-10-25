package com.jake.duolauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Today View = the leading widget page (logical page -1). iOS keeps it to the left of Home
 * page 1 at all times, and iPhone Duo shows it as the left half whenever the phone is
 * unfolded, so it is no longer hidden just because it is empty. Only an explicit "hide"
 * from settings removes it ("SHOW" and the default "AUTO" both keep it).
 */
internal fun todayViewVisible(mode: String, @Suppress("UNUSED_PARAMETER") hasContent: Boolean): Boolean = mode != "HIDE"

/** Lowest grid row below the Today View's content (widgets and any legacy shortcuts). */
internal fun todayContentRows(widgets: List<WidgetPlacement>, leadingSlots: List<String?>, columns: Int = GRID_COLUMNS): Int {
    val widgetRows = widgets.filter { it.page == -1 }.maxOfOrNull { it.row + it.spanY } ?: 0
    val slotRows = leadingSlots.indexOfLast { it != null }.takeIf { it >= 0 }?.let { it / columns + 1 } ?: 0
    return maxOf(widgetRows, slotRows)
}

/** Actions the Today View chrome needs from LauncherScreen, provided without new parameters. */
@Immutable
internal data class TodayViewActions(
    val onAddWidget: () -> Unit = {},
    val onEdit: () -> Unit = {},
    /** The search field at the top of Today View opens Spotlight (iOS). */
    val onSearch: () -> Unit = {},
)

/** Height of Today View's search capsule and the gap between it and the first widget row. */
internal const val TODAY_SEARCH_HEIGHT = 40f
internal const val TODAY_SEARCH_GAP = 16f
/**
 * iPhone Duo's Today View has no search field above its widgets (Apple's opening/home-screen videos: the first
 * widget sits on Home's row 1). The capsule stays available behind this switch; off, it takes no space.
 */
internal const val TODAY_SEARCH_IN_TODAY_VIEW = false
/** Vertical space the search field takes above Today View's widgets. */
internal val TODAY_SEARCH_BLOCK: Float = if (TODAY_SEARCH_IN_TODAY_VIEW) TODAY_SEARCH_HEIGHT + TODAY_SEARCH_GAP else 0f

/** Horizontal frame (dp, relative to the page's grid) of a full-width element in the widget column. */
internal data class TodayColumnFrame(val left: Float, val width: Float)

/**
 * The Today View search field spans exactly the widget column: from the left edge of the first
 * column's widget card to the right edge of the last, i.e. the frame of a widget four columns
 * wide ([widgetPlacementFrame]), centred like the Today grid on a wider (rotated cover) canvas.
 */
internal fun todaySearchFieldFrame(gridWidth: Float, columns: Int, iconSize: Float, referenceCardSize: Float?): TodayColumnFrame {
    val cell = gridWidth / columns.coerceAtLeast(1)
    val todayColumns = minOf(columns, GRID_COLUMNS).coerceAtLeast(1)
    val frame = widgetPlacementFrame(-1, todayColumns, 2, cell, iconSize, iconSize, 2f * cell, referenceCardSize)
    val columnOffset = (columns - todayColumns) / 2f
    return TodayColumnFrame(cell * columnOffset + frame.insetX, frame.width)
}

internal val LocalTodayViewActions = staticCompositionLocalOf<TodayViewActions?> { null }

private val TodayLabelShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .35f), Offset(0f, 1f), 4f))

/** iOS's plain glass capsule ("编辑" under the Today View widgets). No ripple; it sinks when pressed. */
@Composable
internal fun TodayGlassCapsule(label: String, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(modifier.heightIn(min = 40.dp).duoGlass(DuoGlassRole.Control, shape, interaction)
        // A long press on the capsule is not a long press on the Today cell or icon under it.
        .overlayInputShield()
        .clickable(interaction, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag)
        .padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = TodayLabelShadow)
    }
}

/**
 * iOS Today View's search field: a Liquid Glass capsule with the magnifying glass and "搜索",
 * at the top of the widget column. It is a button — a tap opens Spotlight, which owns the real
 * text field and keyboard. While Home is jiggling it is inert, so a tap ends editing as on the
 * bare background.
 */
@Composable
internal fun TodaySearchField(onSearch: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val label = uxText("搜索", "Search")
    val ink = Color.White.copy(alpha = .86f)
    Row(modifier.height(TODAY_SEARCH_HEIGHT.dp).duoGlass(DuoGlassRole.Control, shape, interaction)
        .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onSearch)
        .semantics { contentDescription = label }.testTag("today-search")
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(IosSymbols.magnifyingGlass, null, Modifier.size(18.dp), tint = ink)
        Spacer(Modifier.width(7.dp))
        Text(label, color = ink, fontSize = 17.sp, maxLines = 1, style = TodayLabelShadow)
    }
}

/** Shown when Today View has nothing in it: a ghost widget stack and the two ways to fill it. */
@Composable
internal fun TodayViewEmptyState(actions: TodayViewActions, editing: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.testTag("today-empty"), horizontalAlignment = Alignment.CenterHorizontally) {
        // Placeholder silhouettes at true widget proportions (two small, one medium).
        val ghost = Color.White.copy(alpha = .16f)
        val rim = Color.White.copy(alpha = .30f)
        val shape = RoundedCornerShape(22.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { Box(Modifier.size(78.dp).background(ghost, shape).border(1.dp, rim, shape)) }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.size(168.dp, 78.dp).background(ghost, shape).border(1.dp, rim, shape))
        Spacer(Modifier.height(22.dp))
        Text(uxText("今日视图", "Today View"), Modifier.semantics { heading() }, color = Color.White,
            fontSize = 22.sp, fontWeight = FontWeight.Bold, style = TodayLabelShadow)
        Text(uxText("添加小组件，一眼看到今天的信息。", "Add widgets to see today at a glance."),
            // Full white with a firmer shadow: the line sits on the dunes' bright sky band.
            Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp).widthIn(max = 320.dp), color = Color.White,
            fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 6f)))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TodayGlassCapsule(uxText("添加小组件", "Add Widget"), "today-add-widget", actions.onAddWidget,
                leading = { Icon(IosSymbols.plus, null, Modifier.size(18.dp).padding(end = 2.dp), tint = Color.White) })
            // No "编辑" capsule: iPhone Duo has none (r21, owner).
        }
    }
}

/**
 * iPadOS Today View on the unfolded inner screen (user-photos-r5/5.webp at rest, 9.webp mid-swipe): the
 * widget column slides in from the left edge over the Home Screen, which LauncherScreen keeps in place and
 * blurs ([SpreadEnds.todayBlurDp]); this layer adds the slight dim and makes the blurred Home inert while
 * any of the column is in (a tap there does nothing; paging still works through the ancestors).
 * [content] is the Today pane (the logical page −1 grid) at its resting x.
 */
@Composable
internal fun TodayOverlayLayer(
    progress: () -> Float,
    travelPx: Float,
    overscroll: () -> Float,
    modifier: Modifier = Modifier,
    /** The workspace's Reduce Motion cross-dissolve, so the column fades with Home instead of popping. */
    workspaceAlpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    val shown by remember(progress) { derivedStateOf { progress() > .01f } }
    Box(modifier.fillMaxSize().testTag("today-overlay")) {
        if (shown) Box(Modifier.fillMaxSize().drawBehind {
            drawRect(Color.Black, alpha = (TODAY_OVERLAY_DIM * progress()).coerceIn(0f, 1f))
        }.inertPointerTarget().testTag("today-overlay-veil"))
        Box(Modifier.fillMaxSize().graphicsLayer {
            val p = progress()
            // Whole pixels so the widgets' corner masks are reused while it slides (round 22, LauncherScreen `place`).
            translationX = kotlin.math.round(todayColumnShift(p, travelPx) - overscroll())
            // Off screen at rest on Home: also never drawn, so its widgets cost nothing there.
            alpha = if (p > .001f) workspaceAlpha() else 0f
        }, content = content)
    }
}

/**
 * iPhone Today View (the cover, and any single-page posture): it is an ordinary page left of Home 1,
 * and as it slides in the wallpaper behind it blurs and darkens with the finger, reversing with it —
 * the same veil the unfolded Today overlay draws ([SpreadEnds.todayBlurDp]). 0 on Home 1, 1 on Today.
 */
internal fun coverTodayProgress(position: Float, posture: PagerPosture): Float =
    if (posture.spread || posture.firstHome <= 0) 0f else (posture.firstHome - position).coerceIn(0f, 1f)

/** Wallpaper blur radius (dp) behind the cover's Today View. */
internal fun coverTodayBlurDp(progress: Float): Float = veilBlurDp(progress)

/** Black over the blurred wallpaper behind the cover's Today View (iOS darkens it a little). */
internal const val COVER_TODAY_DIM = .14f
