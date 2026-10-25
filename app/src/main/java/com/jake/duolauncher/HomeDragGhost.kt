package com.jake.duolauncher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** iOS picks an icon up to ≈1.1× around the finger, with a soft shadow. */
internal const val DRAG_LIFT_SCALE = 1.1f
private val liftSpring = spring<Float>(dampingRatio = .72f, stiffness = 520f, visibilityThreshold = .001f)
private val settleSpring = spring<Float>(dampingRatio = .82f, stiffness = 360f, visibilityThreshold = .001f)

/** Copies a root-coordinate rectangle of the window's current frame (widgets cannot be redrawn). */
internal fun captureWindowRegion(activity: android.app.Activity, rootView: android.view.View, rect: Rect,
    onResult: (ImageBitmap?) -> Unit) {
    val width = rect.width.roundToInt(); val height = rect.height.roundToInt()
    if (width <= 0 || height <= 0) { onResult(null); return }
    val location = IntArray(2).also(rootView::getLocationInWindow)
    val source = android.graphics.Rect(location[0] + rect.left.roundToInt(), location[1] + rect.top.roundToInt(),
        location[0] + rect.left.roundToInt() + width, location[1] + rect.top.roundToInt() + height)
    val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull()
        ?: run { onResult(null); return }
    runCatching {
        PixelCopy.request(activity.window, source, bitmap, { result ->
            onResult(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null)
        }, Handler(Looper.getMainLooper()))
    }.onFailure { onResult(null) }
}

/** Every placed widget is clipped to this continuous corner (the Home slot that hosts it). */
internal const val WIDGET_SLOT_CORNER_DP = 24f

/**
 * Outline for a widget's pixel copy (context-menu preview, drag ghost, settle): what the widget
 * visibly is on Home — its slot's continuous corner, or a native card's own if that is rounder.
 * The old plain 22 dp circle was squarer than both, so the copy's corners showed slivers of the
 * copied, sharp wallpaper. The clip also sits 1 px inside so the anti-aliased rim is not shown.
 */
internal fun widgetSnapshotShape(widgetId: Int?, widthDp: Float, heightDp: Float): Shape {
    val native = widgetId != null && isNativeWidgetId(widgetId)
    val radius = if (native) maxOf(WIDGET_SLOT_CORNER_DP, nativeWidgetCornerRadius(widthDp, heightDp)) else WIDGET_SLOT_CORNER_DP
    return InsetShape(ContinuousRoundedShape(radius.dp), insetPx = 1f)
}

/** [base] laid out [insetPx] inside the bounds on every side. */
internal class InsetShape(private val base: Shape, private val insetPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val inset = insetPx.coerceAtMost(size.minDimension / 4f).coerceAtLeast(0f)
        val inner = Size(size.width - 2f * inset, size.height - 2f * inset)
        val offset = Offset(inset, inset)
        return when (val outline = base.createOutline(inner, layoutDirection, density)) {
            is Outline.Rectangle -> Outline.Rectangle(outline.rect.translate(offset))
            is Outline.Rounded -> Outline.Rounded(outline.roundRect.translate(offset))
            is Outline.Generic -> Outline.Generic(Path().apply { addPath(outline.path, offset) })
        }
    }
    override fun equals(other: Any?): Boolean = other is InsetShape && other.base == base && other.insetPx == insetPx
    override fun hashCode(): Int = 31 * base.hashCode() + insetPx.hashCode()
}

/** The artwork of a lifted item, drawn at its rest size. */
@Composable
internal fun LiftedItemArt(appId: String?, folderId: String?, snapshot: ImageBitmap?, appsById: Map<String, AppEntry>,
    folders: List<FolderEntry>, sizeDp: Float, widgetShape: Shape = RoundedCornerShape(22.dp)) {
    val folder = folderId?.let { id -> folders.firstOrNull { it.id == id } }
    val app = appId?.let(appsById::get)
    when {
        folder != null -> FolderIconArt(folder, appsById, sizeDp, Modifier.fillMaxSize())
        app != null -> Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(homeIconShape(sizeDp)))
        snapshot != null -> Image(snapshot, null, Modifier.fillMaxSize().clip(widgetShape), contentScale = ContentScale.FillBounds)
        else -> Box(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Elevated, widgetShape))
    }
}

/**
 * Root-level drag visuals: the lifted item follows the finger from the exact point it was
 * grabbed, grows to [DRAG_LIFT_SCALE] with a shadow, and after release springs onto its new
 * slot (or back) while the resting tile stays hidden. No outline, gap or drop-zone frames:
 * iOS shows only the item itself and the other icons making room.
 */
@Composable
internal fun BoxScope.HomeDragGhostLayer(drag: HomeDragState, appsById: Map<String, AppEntry>,
    folders: List<FolderEntry>, icons: HomeIconRegistry, hideResting: Boolean) {
    val density = LocalDensity.current
    val source = drag.source
    val rest = drag.ghostRest
    if (source != null && rest != null && !hideResting) {
        val lift = remember(source.target) { Animatable(1f) }
        val reduced = rememberReduceMotion()
        LaunchedEffect(source.target) { if (reduced) lift.snapTo(DRAG_LIFT_SCALE) else lift.animateTo(DRAG_LIFT_SCALE, liftSpring) }
        val folderId = source.appId?.takeIf(::isFolderId)
        val appId = source.appId?.takeUnless(::isFolderId)
        val sizeDp = with(density) { rest.width.toDp().value }
        val widgetShape = remember(source.widgetId, rest.width, rest.height) {
            with(density) { widgetSnapshotShape(source.widgetId, rest.width.toDp().value, rest.height.toDp().value) }
        }
        Box(Modifier
            .offset { IntOffset((drag.pointer.x - drag.grab.x - drag.rootOrigin.x).roundToInt(),
                (drag.pointer.y - drag.grab.y - drag.rootOrigin.y).roundToInt()) }
            .size(with(density) { rest.width.toDp() }, with(density) { rest.height.toDp() })
            .graphicsLayer {
                val s = lift.value
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin((drag.grab.x / rest.width).coerceIn(0f, 1f),
                    (drag.grab.y / rest.height).coerceIn(0f, 1f))
                shadowElevation = 14.dp.toPx() * ((s - 1f) / (DRAG_LIFT_SCALE - 1f)).coerceIn(0f, 1f)
                shape = if (source.widgetId != null) widgetShape else homeIconShape(sizeDp)
                clip = false
                ambientShadowColor = Color.Black.copy(alpha = .3f)
                spotShadowColor = Color.Black.copy(alpha = .35f)
            }
            .testTag(if (folderId != null) "folder-drag-ghost" else "drag-ghost")) {
            LiftedItemArt(appId, folderId, drag.snapshot, appsById, folders, sizeDp, widgetShape)
        }
    }
    drag.settle?.let { settle ->
        key(settle.serial) {
            val progress = remember { Animatable(0f) }
            var target by remember { mutableStateOf(settle.target) }
            val reduced = rememberReduceMotion()
            LaunchedEffect(Unit) {
                // Let the committed layout place the item before reading its new rectangle.
                repeat(3) { withInfiniteAnimationFrameNanos { } }
                settle.targetId?.let { icons[it] }?.let { target = it }
                if (!reduced && target != null) progress.animateTo(1f, settleSpring)
                if (drag.settle?.serial == settle.serial) drag.settle = null
            }
            val to = target
            // (No early return inside key {}: inline composable lambdas cannot return non-locally.)
            if (to != null) {
            val from = MenuBounds(settle.from.left, settle.from.top, settle.from.right, settle.from.bottom)
            val sizeDp = with(density) { settle.from.width.toDp().value }
            val widgetShape = with(density) {
                widgetSnapshotShape(settle.widgetId, settle.from.width.toDp().value, settle.from.height.toDp().value)
            }
            Box(Modifier
                .offset { IntOffset((settle.from.left - drag.rootOrigin.x).roundToInt(), (settle.from.top - drag.rootOrigin.y).roundToInt()) }
                .size(with(density) { settle.from.width.toDp() }, with(density) { settle.from.height.toDp() })
                .graphicsLayer {
                    // [settle.from] is the ghost's visual rectangle at release (lift included).
                    val p = progress.value
                    // Aim at the slot as it is laid out now, every frame: a page scroll or a Dock
                    // re-centring that lands after the first look must not leave the ghost short.
                    val live = settle.targetId?.let { icons[it] } ?: to
                    val dest = MenuBounds(live.left, live.top, live.right, live.bottom)
                    val now = lerpBounds(from, if (settle.shrink) dest.let {
                        // Dive into the folder: end as a mini icon at the folder's centre.
                        val w = it.width * .3f
                        MenuBounds(it.centerX - w / 2f, it.centerY - w / 2f, it.centerX + w / 2f, it.centerY + w / 2f)
                    } else dest, p)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = now.width / from.width.coerceAtLeast(1f)
                    scaleY = now.height / from.height.coerceAtLeast(1f)
                    translationX = now.left - from.left
                    translationY = now.top - from.top
                    shadowElevation = 14.dp.toPx() * (1f - p)
                    shape = if (settle.widgetSnapshot != null) widgetShape else homeIconShape(sizeDp)
                    clip = false
                    alpha = if (settle.shrink) (1f - p * p) else 1f
                }
                .testTag("drag-settle")) {
                LiftedItemArt(settle.appId, settle.folderId, settle.widgetSnapshot, appsById, folders, sizeDp, widgetShape)
            }
            }
        }
    }
}
