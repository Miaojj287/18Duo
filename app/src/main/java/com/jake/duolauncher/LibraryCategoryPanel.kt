package com.jake.duolauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * Where an expanded App Library category rests (round 10, owner: "app资源库里的展开文件夹样式不太对，你去网上看看ios
 * 怎么做的"). iOS (iPhoneLife's App Library guide, screenshots img_2135 → img_2136) opens a category as a full-screen
 * view, not a folder platter: the library behind blurs, the category name is a large bold title on the leading
 * edge of the first icon, and every app sits in a Home Screen grid (Home pitch, Home icon size, white names) that
 * scrolls vertically, the title scrolling with it. Values in dp of the library's own box.
 */
internal data class LibraryCategoryFrame(
    /** Leading edge of the first cell; cells are [pitch] wide. */
    val gridLeft: Float, val pitch: Float, val columns: Int, val icon: Float, val rowGap: Float,
    /** Top of the title (the first scrolling row), its size, and the gap from the title to the first icon row. */
    val titleTop: Float, val titleSize: Float, val titleGap: Float,
) {
    val gridWidth: Float get() = pitch * columns
    /** The title starts where the first icon does, as on iOS. */
    val titleLeft: Float get() = gridLeft + (pitch - icon) / 2f
    val rowHeight: Float get() = icon + LIBRARY_CATEGORY_LABEL_BLOCK + rowGap
}

/** Icon-to-name gap plus one 12 sp name line. */
internal const val LIBRARY_CATEGORY_LABEL_BLOCK = 6f + 15f
internal const val LIBRARY_CATEGORY_TITLE_HEIGHT = 41f

/**
 * [wide] = the full-screen iPad library on the unfolded inner screen (860 × 608 dp): six columns at the iPad
 * Home pitch, centred under the search field. Otherwise the iPhone library (cover pane): its four Home columns
 * across the whole pane (the App Library tiles span exactly these columns). The title sits about where the
 * library's first row of tiles was, below the search field, as in the iPhone screenshot (title centre ≈ 22 %
 * of the screen, first icon row ≈ 31 %).
 */
internal fun libraryCategoryFrame(width: Float, height: Float, wide: Boolean): LibraryCategoryFrame {
    if (wide) {
        val columns = 6
        val pitch = minOf(112f, width * .78f / columns)
        val icon = minOf(64f, pitch * .62f)
        return LibraryCategoryFrame(gridLeft = (width - pitch * columns) / 2f, pitch = pitch, columns = columns, icon = icon,
            rowGap = 26f, titleTop = (height * .15f).coerceAtLeast(56f), titleSize = 34f, titleGap = 22f)
    }
    val pitch = width / 4f
    val icon = (pitch * .675f).coerceIn(44f, 64f)
    return LibraryCategoryFrame(gridLeft = 0f, pitch = pitch, columns = 4, icon = icon,
        rowGap = 20f, titleTop = (height * .1f).coerceIn(56f, 96f), titleSize = if (width < 340f) 30f else 34f, titleGap = 26f)
}

/** Top and bottom (dp) of what an open category of [count] apps shows at rest: its title down to its last row. */
internal fun libraryCategoryViewRect(frame: LibraryCategoryFrame, count: Int, boxHeight: Float): Pair<Float, Float> {
    val rows = libraryCategoryRows(count, frame.columns).coerceAtLeast(1)
    val bottom = frame.titleTop + LIBRARY_CATEGORY_TITLE_HEIGHT + frame.titleGap + rows * frame.rowHeight - frame.rowGap
    return frame.titleTop to minOf(bottom, boxHeight)
}

/** Rows a category of [count] apps needs in [columns] columns. */
internal fun libraryCategoryRows(count: Int, columns: Int): Int = ceil(count.coerceAtLeast(0) / columns.coerceAtLeast(1).toFloat()).toInt()

/**
 * Opening: SwiftUI-style spring, response 0.42 s with a hint of bounce (damping 0.86) — the zoom out of a small
 * tile overshoots by a hair and settles, like a Home folder. Closing: response 0.36 s, critically damped, into
 * the tile without overshoot. Both retarget from the current value and velocity.
 */
internal val LIBRARY_CATEGORY_OPEN = LauncherMotion.zoomOpenSpring
internal val LIBRARY_CATEGORY_CLOSE = LauncherMotion.zoomCloseSpring
internal val libraryCategoryOpenSpring get() = LauncherMotion.zoomOpen
internal val libraryCategoryCloseSpring get() = LauncherMotion.zoomClose

/**
 * The expanded category. [progress] (0 = resting in its tile, 1 = open) is owned by the library and springs
 * toward the open state: a tap on empty space, Back or Home sends it back from wherever it is (the spring keeps
 * its velocity, so it can reverse mid-flight). The whole view (title + grid) zooms out of [origin] (the tile,
 * in this box's px) and back into it, while the library behind blurs in proportion ([libraryBehindCategory]).
 */
@Composable
internal fun LibraryCategoryPanel(
    folder: LibraryFolder, appsById: Map<String, AppEntry>, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    progress: Animatable<Float, AnimationVector1D>, open: Boolean, wide: Boolean,
    origin: () -> Rect?, onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()
        // Only an open category takes the touches around it: while it zooms back the library is live again.
        .then(if (open) Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .semantics { onClick(uxText("关闭", "Close")) { onDismiss(); true } } else Modifier)
        .testTag("library-category-panel")) {
        val frame = remember(maxWidth, maxHeight, wide) { libraryCategoryFrame(maxWidth.value, maxHeight.value, wide) }
        val apps = remember(folder, appsById) { folder.appIds.mapNotNull(appsById::get) }
        val boxHeight = maxHeight.value
        val ink = Color.White
        val gridState = rememberLazyGridState()
        LazyVerticalGrid(GridCells.Fixed(frame.columns),
            Modifier.offset(x = frame.gridLeft.dp).width(frame.gridWidth.dp).fillMaxHeight()
                .graphicsLayer {
                    val p = progress.value
                    if (LauncherMotion.reduced) { alpha = p.coerceIn(0f, 1f); return@graphicsLayer }
                    // What the tile grows into: the category as it rests on screen (title to last row, clipped by
                    // the box), scaled uniformly — a folder grows, it never stretches.
                    val view = libraryCategoryViewRect(frame, apps.size, boxHeight).let {
                        Rect(0f, it.first.dp.toPx(), size.width, it.second.dp.toPx())
                    }
                    val o = origin()?.translate(-frame.gridLeft.dp.toPx(), 0f)
                    if (o != null && o.width > 0f) {
                        val start = minOf(o.width / view.width, o.height / view.height).coerceIn(.05f, 1f)
                        val s = start + (1f - start) * p
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(view.center.x / size.width.coerceAtLeast(1f),
                            view.center.y / size.height.coerceAtLeast(1f))
                        translationX = (o.center.x - view.center.x) * (1f - p)
                        translationY = (o.center.y - view.center.y) * (1f - p)
                    } else {
                        val s = .6f + .4f * p
                        scaleX = s; scaleY = s
                    }
                    // The tile is already on screen: the view is opaque well before it is full size.
                    alpha = (p * 2.2f).coerceIn(0f, 1f)
                }
                .iosScrollEdgeFade(top = 24.dp, bottom = 28.dp, fadeTop = { gridState.canScrollBackward },
                    fadeBottom = { gridState.canScrollForward })
                .testTag("library-category-grid"),
            state = gridState,
            contentPadding = PaddingValues(top = frame.titleTop.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(frame.rowGap.dp)) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Text(folder.category.title,
                    Modifier.fillMaxWidth().padding(start = ((frame.pitch - frame.icon) / 2f).dp, end = 8.dp,
                        bottom = (frame.titleGap - frame.rowGap).coerceAtLeast(0f).dp)
                        .semantics { heading() }.testTag("library-category-title"),
                    color = ink, fontSize = frame.titleSize.sp, lineHeight = (frame.titleSize + 7f).sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .18f), Offset(0f, 1f), 8f)))
            }
            items(apps, key = { it.id }) { app ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LibraryIcon(app, frame.icon.dp, drag, page, onLaunch, onActions)
                    Text(app.label, Modifier.fillMaxWidth().padding(top = 6.dp, start = 3.dp, end = 3.dp),
                        color = ink.copy(alpha = if (app.available) 1f else .5f), fontSize = 12.sp, lineHeight = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .25f), Offset(0f, 1f), 3f)))
                    // Work-profile and cloned (dual) apps are told apart by their profile, as on Home.
                    if (app.isWork || !app.available) Text(
                        if (app.available) launcherText(app.profileLabel) else launcherFormat(R.string.ui_unavailable),
                        color = ink.copy(alpha = .7f), fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The library behind an open category: blurred (and very slightly faded into the wallpaper) in proportion to
 * [progress], drawn in the draw phase only.
 */
internal fun Modifier.libraryBehindCategory(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    val radius = LIBRARY_CATEGORY_BLUR_DP.dp.toPx() * p
    // Decal and unclipped: the library box is not the screen on the cover (it stops short of the side
    // column), so its blurred edge must melt into the wallpaper instead of showing a hard rectangle.
    renderEffect = if (radius > .5f) BlurEffect(radius, radius, TileMode.Decal) else null
    alpha = 1f - LIBRARY_CATEGORY_FADE * p
}

/** iOS blurs the library away almost completely under an opened category and barely darkens it. */
internal const val LIBRARY_CATEGORY_BLUR_DP = 34f
internal const val LIBRARY_CATEGORY_FADE = .3f
