package com.jake.duolauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * iOS jiggle-mode chrome: a glass "编辑" capsule (its menu adds widgets, edits the wallpaper and
 * opens Settings) and a glass "完成" capsule. They sit in the band above the first icon row,
 * inside the grid's horizontal extent, so they never touch the Duo's status column, the
 * Dock, the first row's − badges or a camera cutout (see [jiggleChromeLayout]).
 * [grid] and [obstacles] are root coordinates; [window] is the root size. [band] (root px) is
 * the vertical band from the Home frame ([DuoHomeFrame.jiggleBand]): the grid itself moves down
 * by its [JiggleBand.gridShift] while jiggling, so it is not derived from the measured grid.
 */
@Composable
internal fun JiggleModeChrome(grid: () -> Rect?, obstacles: List<Rect>, intensity: State<Float>,
    onAddWidget: () -> Unit, onWallpaper: () -> Unit, onSettings: () -> Unit, onDone: () -> Unit,
    band: JiggleBand? = null) {
    val title = stringResource(R.string.editor_title)
    val density = LocalDensity.current
    var menuOpen by remember { mutableStateOf(false) }
    var editBounds by remember { mutableStateOf<Rect?>(null) }
    val badgeOverhang = with(density) { JIGGLE_BADGE_REACH_DP.dp.toPx() }
    val obstacleBounds = remember(obstacles) { obstacles.map { MenuBounds(it.left, it.top, it.right, it.bottom) } }
    androidx.activity.compose.BackHandler(enabled = menuOpen) { menuOpen = false }
    Box(Modifier.fillMaxSize()) {
        Layout(content = {
            JigglePill("editor-settings", { menuOpen = true },
                Modifier.onGloballyPositioned { editBounds = it.boundsInRoot() }) {
                JigglePillLabel(uxText("编辑", "Edit"), bold = false)
            }
            JigglePill("editor-done", onDone) { JigglePillLabel(stringResource(R.string.editor_done)) }
        }, modifier = Modifier.fillMaxSize().testTag("editor-title").semantics { contentDescription = title }
            .graphicsLayer {
                // Fade with the edit-mode spring; they never scale (they are chrome, not icons).
                alpha = intensity.value.coerceIn(0f, 1f)
            }) { measurables, constraints ->
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val window = MenuBounds(0f, 0f, width, height)
            val gridRect = grid()?.let { MenuBounds(it.left, it.top, it.right, it.bottom) }
                ?: MenuBounds(16.dp.toPx(), 56.dp.toPx(), width - 16.dp.toPx(), height)
            // The band height does not depend on the capsules' widths.
            val probe = jiggleChromeLayout(window, gridRect, 1f, 1f, emptyList(), topMargin = 8.dp.toPx(),
                badgeOverhang = badgeOverhang, preferredHeight = 34.dp.toPx(), minimumHeight = 28.dp.toPx(), gap = 6.dp.toPx(),
                band = band)
            val pillHeight = probe.edit.height.roundToInt()
            val pillConstraints = Constraints(minHeight = pillHeight, maxHeight = pillHeight)
            val edit = measurables[0].measure(pillConstraints)
            val done = measurables[1].measure(pillConstraints)
            val layout = jiggleChromeLayout(window, gridRect, edit.width.toFloat(), done.width.toFloat(), obstacleBounds,
                topMargin = 8.dp.toPx(), badgeOverhang = badgeOverhang, preferredHeight = 34.dp.toPx(),
                minimumHeight = 28.dp.toPx(), gap = 6.dp.toPx(), band = band)
            layout(constraints.maxWidth, constraints.maxHeight) {
                edit.place(layout.edit.left.roundToInt(), layout.edit.top.roundToInt())
                done.place(layout.done.left.roundToInt(), layout.done.top.roundToInt())
            }
        }
        IosContextMenuHost(
            request = if (!menuOpen) null else IosMenuRequest(
                key = "jiggle-edit-menu", anchor = editBounds,
                sections = listOf(listOf(
                    IosMenuAction(uxText("添加小组件", "Add Widget"), IosSymbols.widgets, "jiggle-menu-widgets") {
                        menuOpen = false; onAddWidget()
                    },
                    IosMenuAction(uxText("编辑墙纸", "Edit Wallpaper"), IosSymbols.photo, "jiggle-menu-wallpaper") {
                        menuOpen = false; onWallpaper()
                    },
                    IosMenuAction(uxText("设置", "Settings"), IosSymbols.gear, "jiggle-menu-settings") {
                        menuOpen = false; onSettings()
                    },
                )),
                overlayTag = "jiggle-menu-overlay", menuTag = "jiggle-menu", width = 230.dp, blurBackground = false),
            onDismissRequest = { menuOpen = false }, obstacles = obstacles)
    }
}
