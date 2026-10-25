package com.jake.duolauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.RemoteViews
import android.appwidget.AppWidgetManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

internal data class WidgetCatalogEntry(
    val provider: AppWidgetProviderInfo,
    val providerLabel: String,
    val appLabel: String,
    val description: String,
    val userSerial: Long = 0,
    val profileLabel: String = launcherText("Personal"),
    val isWork: Boolean = false,
)

internal val LocalWidgetPlacementSession = compositionLocalOf<WidgetPickerSession?> { null }

internal data class WidgetPickerSession(
    val provider: AppWidgetProviderInfo?,
    val slot: Int,
    val span: WidgetSpan,
    val pointer: Offset,
    val dragging: Boolean,
    val targetIndex: Int? = null,
    val candidate: WidgetPlacement? = null,
    /** Set instead of [provider] for one of 18Duo's own iOS-style widgets. */
    val nativeId: Int? = null,
)

internal fun widgetCatalog(context: Context, providers: List<AppWidgetProviderInfo>, profile: AppProfile): List<WidgetCatalogEntry> {
    val pm = context.packageManager
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    return providers.filterNot { isHiddenWidgetPackage(it.provider.packageName) }.map { provider ->
        val packageName = provider.provider.packageName
        val appLabel = runCatching {
            launcherApps.getApplicationInfo(packageName, 0, provider.profile).loadLabel(pm).toString()
        }.recoverCatching {
            if (provider.profile != Process.myUserHandle()) throw it
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }
            .getOrDefault(packageName)
        val providerLabel = provider.loadLabel(pm).toString()
        val description = if (android.os.Build.VERSION.SDK_INT >= 31)
            runCatching { provider.loadDescription(context)?.toString().orEmpty() }.getOrDefault("") else ""
        WidgetCatalogEntry(provider, providerLabel, appLabel, description, profile.userSerial, profile.label, profile.isWork)
    }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.providerLabel.lowercase() }))
}

private sealed interface CatalogPreview {
    data class Remote(val views: RemoteViews) : CatalogPreview
    data class Picture(val bitmap: Bitmap) : CatalogPreview
    data object Missing : CatalogPreview
}

private object WidgetPreviewCache {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private val values = object : LinkedHashMap<String, CatalogPreview>(16, .75f, true) {}
    private var bytes = 0
    @Synchronized fun get(key: String) = values[key]
    @Synchronized fun put(key: String, value: CatalogPreview) {
        values.remove(key)?.let { bytes -= it.cost }
        values[key] = value; bytes += value.cost
        val iterator = values.entries.iterator()
        while (bytes > MAX_BYTES && iterator.hasNext()) { bytes -= iterator.next().value.cost; iterator.remove() }
    }
    private val CatalogPreview.cost get() = when (this) {
        is CatalogPreview.Picture -> bitmap.allocationByteCount
        is CatalogPreview.Remote -> 64 * 1024
        CatalogPreview.Missing -> 1
    }
}

private suspend fun loadWidgetPreview(context: Context, provider: AppWidgetProviderInfo, span: WidgetSpan): CatalogPreview {
    val density = context.resources.displayMetrics.densityDpi
    val key = "${provider.provider.flattenToString()}|${provider.profile.hashCode()}|$density|${span.width}x${span.height}"
    WidgetPreviewCache.get(key)?.let { return it }
    val loaded = withContext(Dispatchers.IO) {
        val manager = AppWidgetManager.getInstance(context)
        var remote: RemoteViews? = null
        if (android.os.Build.VERSION.SDK_INT >= 35 &&
            provider.generatedPreviewCategories and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0) {
            remote = runCatching { manager.getWidgetPreview(provider.provider, provider.profile,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) }.getOrNull()
        }
        if (remote == null && android.os.Build.VERSION.SDK_INT >= 31 && provider.previewLayout != 0)
            remote = runCatching { RemoteViews(provider.provider.packageName, provider.previewLayout) }.getOrNull()
        remote?.let(CatalogPreview::Remote) ?: run {
            val drawable = runCatching { provider.loadPreviewImage(context, density) }.getOrNull()
                ?: runCatching { provider.loadIcon(context, density) }.getOrNull()
            drawable
        }
    }
    // Drawable.draw may touch theme/view state. Convert it on Compose's UI thread; only resource
    // Provider enumeration and generated-preview IPC belong on the worker dispatcher.
    val result = when (loaded) {
        is CatalogPreview -> loaded
        is Drawable -> CatalogPreview.Picture(loaded.catalogBitmap())
        else -> CatalogPreview.Missing
    }
    WidgetPreviewCache.put(key, result)
    return result
}

private fun Drawable.catalogBitmap(): Bitmap {
    val sourceWidth = intrinsicWidth.takeIf { it > 0 } ?: 144
    val sourceHeight = intrinsicHeight.takeIf { it > 0 } ?: 144
    val scale = minOf(1f, 720f / sourceWidth, 480f / sourceHeight)
    val w = (sourceWidth * scale).toInt().coerceAtLeast(1)
    val h = (sourceHeight * scale).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = android.graphics.Canvas(bitmap)
        setBounds(0, 0, w, h)
        draw(canvas)
    }
}

/** Render fixed-size provider previews at their native footprint, then fit the whole preview.
 * RemoteViews may contain minimum widths; measuring them directly into a narrow catalog tile
 * clips their labels and artwork. This is a read-only preview, so scaling has no input cost. */
private class ScaledWidgetPreviewFrame(context: Context, span: WidgetSpan, provider: AppWidgetProviderInfo) :
    android.widget.FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val renderWidth = maxOf((span.width * 110f * density).toInt(), provider.minWidth, 1)
    private val renderHeight = maxOf((span.height * 110f * density).toInt(), provider.minHeight, 1)
    private var fit = 1f
    override fun dispatchTouchEvent(event: android.view.MotionEvent?): Boolean = false
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val height = android.view.View.MeasureSpec.getSize(heightMeasureSpec)
        fit = minOf(width.toFloat() / renderWidth, height.toFloat() / renderHeight)
        for (index in 0 until childCount) getChildAt(index).measure(
            android.view.View.MeasureSpec.makeMeasureSpec(renderWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(renderHeight, android.view.View.MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) getChildAt(index).apply {
            layout(0, 0, renderWidth, renderHeight)
            pivotX = 0f; pivotY = 0f; scaleX = fit; scaleY = fit
            translationX = (this@ScaledWidgetPreviewFrame.width - renderWidth * fit) / 2f
            translationY = (this@ScaledWidgetPreviewFrame.height - renderHeight * fit) / 2f
        }
    }
}

@Composable
internal fun WidgetProviderPreview(entry: WidgetCatalogEntry, span: WidgetSpan, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<CatalogPreview?>(null, entry.provider, span, context.resources.displayMetrics.densityDpi) {
        value = loadWidgetPreview(context, entry.provider, span)
    }
    // A faint plate only shows through transparent provider artwork; no Material grey card.
    val backdrop = if (preview == null || preview is CatalogPreview.Missing) Color.White.copy(alpha = .16f)
        else Color.Black.copy(alpha = .10f)
    Box(modifier.background(backdrop), contentAlignment = Alignment.Center) {
        when (val value = preview) {
            is CatalogPreview.Remote -> AndroidView(factory = { previewContext ->
                ScaledWidgetPreviewFrame(previewContext, span, entry.provider).apply {
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isFocusable = false
                    isClickable = false
                    runCatching { addView(value.views.apply(previewContext, this)) }
                        .onFailure { addView(android.widget.TextView(previewContext).apply { text = entry.providerLabel }) }
                }
            }, modifier = Modifier.fillMaxSize())
            is CatalogPreview.Picture -> Image(value.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit)
            CatalogPreview.Missing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(IosSymbols.widgets, null, Modifier.size(28.dp), tint = Color.White.copy(alpha = .8f))
                Text(entry.providerLabel, Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp), color = Color.White,
                    fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
            }
            null -> IosActivityIndicator(color = Color.White.copy(alpha = .9f))
        }
    }
}

/** UIActivityIndicatorView: eight fading spokes that step round, instead of a Material ring. */
@Composable
internal fun IosActivityIndicator(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Color.White) {
    val transition = rememberInfiniteTransition(label = "ios spinner")
    val step by transition.animateFloat(0f, 8f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "ios spinner step")
    val head = if (LauncherMotion.reduced) 0 else step.toInt() % 8
    Canvas(modifier.size(size).semantics { contentDescription = uxText("正在载入", "Loading") }) {
        val radius = this.size.minDimension / 2f
        val stroke = radius * .2f
        for (index in 0 until 8) {
            val age = (head - index + 8) % 8
            val angle = Math.toRadians(index * 45.0 - 90.0)
            val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
            drawLine(color.copy(alpha = color.alpha * (1f - age * .1f).coerceAtLeast(.25f)),
                center + Offset(dx * radius * .45f, dy * radius * .45f), center + Offset(dx * (radius - stroke / 2), dy * (radius - stroke / 2)),
                strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------------------------------
// iOS add-widget sheet: search, featured previews, apps with widgets, per-app size pages.
// ---------------------------------------------------------------------------------------

/** One app (or 18Duo widget kind) in the gallery list. */
private data class GalleryGroup(
    val key: String,
    val title: String,
    val nativeKind: NativeWidgetKind? = null,
    val entries: List<WidgetCatalogEntry> = emptyList(),
    val packageName: String? = null,
    val userSerial: Long = 0,
    val isWork: Boolean = false,
)

/** One size page in an app's detail pager. */
private sealed interface GalleryVariant {
    val title: String
    val description: String
    val span: WidgetSpan
    data class Native(val entry: NativeWidgetEntry) : GalleryVariant {
        override val title get() = entry.spec.name
        override val description get() = entry.spec.description
        override val span get() = entry.span
    }
    data class Provider(val entry: WidgetCatalogEntry, override val span: WidgetSpan, val fits: Boolean) : GalleryVariant {
        override val title get() = entry.providerLabel
        override val description get() = entry.description
    }
}

private fun nativeKindIcon(kind: NativeWidgetKind): Triple<ImageVector, Color, Color> = when (kind) {
    NativeWidgetKind.BATTERY -> Triple(IosSymbols.battery, Color(0xFF30D158), Color.White)
    NativeWidgetKind.CLOCK -> Triple(IosSymbols.clock, Color(0xFF1C1C1E), Color.White)
    NativeWidgetKind.CALENDAR -> Triple(IosSymbols.calendar, Color.White, Color(0xFFFF3B30))
    NativeWidgetKind.WEATHER -> Triple(IosSymbols.cloud, Color(0xFF2F8CFF), Color.White)
    NativeWidgetKind.PHOTOS -> Triple(IosSymbols.photo, Color(0xFFFF9F0A), Color.White)
    NativeWidgetKind.NOTES -> Triple(IosSymbols.pencil, Color(0xFFFFCC00), Color.White)
    NativeWidgetKind.REMINDERS -> Triple(IosSymbols.checklist, Color.White, Color(0xFF007AFF))
    NativeWidgetKind.MAPS -> Triple(IosSymbols.location, Color(0xFF34C759), Color.White)
}

@Composable
private fun GalleryGroupIcon(group: GalleryGroup, apps: Map<Pair<Long, String>, AppEntry>, size: Dp) {
    val kind = group.nativeKind
    val app = group.packageName?.let { apps[group.userSerial to it] }
    when {
        app != null -> AppIcon(app, size)
        kind != null -> { val (icon, background, tint) = nativeKindIcon(kind); GlyphTile(icon, background, tint, size) }
        else -> GlyphTile(IosSymbols.widgets, Color(0xFF8E8E93), Color.White, size)
    }
}

/** True-aspect preview of one variant, scaled down only when the stage is smaller. */
@Composable
private fun GalleryVariantPreview(variant: GalleryVariant, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    val (w, h) = galleryPreviewSize(variant.span)
    val fit = minOf(1f, maxWidth.value / w, maxHeight.value / h)
    val width = (w * fit).dp; val height = (h * fit).dp
    val corner = (minOf(w, h) * fit * .14f).coerceIn(10f, 24f).dp
    when (variant) {
        is GalleryVariant.Native -> NativeWidget(variant.entry.id, modifier.size(width, height), preview = true)
        is GalleryVariant.Provider -> Box(modifier.size(width, height).clip(RoundedCornerShape(corner))) {
            WidgetProviderPreview(variant.entry, variant.span, Modifier.fillMaxSize())
        }
    }
}

private fun Modifier.galleryDrag(
    key: Any, origin: () -> Offset, enabled: Boolean,
    onStart: (Offset) -> Unit, onDrag: (Offset) -> Unit, onDrop: () -> Unit, onCancel: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { point -> onStart(origin() + point) },
        onDrag = { change, _ -> change.consume(); onDrag(origin() + change.position) },
        onDragEnd = onDrop, onDragCancel = onCancel)
}

@Composable
internal fun VisualWidgetPicker(
    entries: List<WidgetCatalogEntry>?,
    profiles: List<AppProfile>,
    selectedProfile: AppProfile,
    onSelectProfile: (AppProfile) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    hiddenForDrag: Boolean,
    footprint: (AppWidgetProviderInfo) -> WidgetSpan?,
    onBack: () -> Unit,
    onTap: (AppWidgetProviderInfo) -> Unit,
    onDragStart: (AppWidgetProviderInfo, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancelDrag: () -> Unit,
    nativeEntries: List<NativeWidgetEntry> = emptyList(),
    onTapNative: (NativeWidgetEntry) -> Unit = {},
    onDragStartNative: (NativeWidgetEntry, Offset) -> Unit = { _, _ -> },
    /** iOS size pages (small/medium/large) a provider can take; defaults to its preferred size. */
    sizeOptions: (AppWidgetProviderInfo) -> List<WidgetSpan> = { listOfNotNull(footprint(it)) },
    onAddSized: (AppWidgetProviderInfo, WidgetSpan) -> Unit = { provider, _ -> onTap(provider) },
    onDragStartSized: (AppWidgetProviderInfo, WidgetSpan, Offset) -> Unit = { provider, _, point -> onDragStart(provider, point) },
    apps: List<AppEntry> = emptyList(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var detailStart by rememberSaveable { mutableIntStateOf(0) }
    val latestDrag by rememberUpdatedState(onDrag)
    val latestDrop by rememberUpdatedState(onDrop)
    val latestCancelDrag by rememberUpdatedState(onCancelDrag)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dark = iosSheetDark()
    val ink = MaterialTheme.colorScheme.onSurface
    val appIcons = remember(apps) { apps.associateBy { it.userSerial to it.packageName } }
    val groups = remember(entries, nativeEntries) {
        val kinds = nativeEntries.map { it.kind }.distinct()
        val byApp = entries.orEmpty().groupBy { it.provider.provider.packageName to it.userSerial }
        // iOS lists each app once: 18Duo's Calendar/Clock/… widgets join the matching app's row.
        val owners = nativeKindOwners(byApp.filterValues { !it.first().isWork }.map { (key, members) -> key.first to members.first().appLabel }, kinds)
        val installed = byApp.map { (key, members) ->
            val work = members.first().isWork
            GalleryGroup("app:${key.second}:${key.first}", members.first().appLabel, entries = members,
                packageName = key.first, userSerial = key.second, isWork = work,
                nativeKind = if (work) null else owners[key.first])
        }
        val native = kinds.filter { it !in owners.values }.map { kind ->
            GalleryGroup("native:${kind.key}", kind.label, nativeKind = kind)
        }
        (native + installed).sortedWith(compareBy({ SearchKeys.of(it.title).sortKey }, { it.title }))
    }
    fun variantsOf(group: GalleryGroup): List<GalleryVariant> {
        val native = group.nativeKind?.let { kind ->
            // Registry order = iOS page order: widget by widget, each small → large.
            nativeEntries.filter { it.kind == kind }.map(GalleryVariant::Native)
        }.orEmpty()
        return native + group.entries.flatMap { entry ->
            val sizes = sizeOptions(entry.provider)
            if (sizes.isEmpty()) listOf(GalleryVariant.Provider(entry, WidgetSpan(2, 2), fits = false))
            else sizes.map { GalleryVariant.Provider(entry, it, fits = true) }
        }
    }
    val detail = detailKey?.let { key -> groups.firstOrNull { it.key == key } }
    BackHandler(detail != null && !hiddenForDrag) { detailKey = null }
    fun closeKeyboard() { focusManager.clearFocus(); keyboard?.hide() }
    fun add(variant: GalleryVariant) {
        closeKeyboard()
        when (variant) {
            is GalleryVariant.Native -> onTapNative(variant.entry)
            is GalleryVariant.Provider -> if (variant.fits) onAddSized(variant.entry.provider, variant.span)
        }
    }
    fun dragStart(variant: GalleryVariant, point: Offset) {
        closeKeyboard()
        when (variant) {
            is GalleryVariant.Native -> onDragStartNative(variant.entry, point)
            is GalleryVariant.Provider -> if (variant.fits) onDragStartSized(variant.entry.provider, variant.span, point)
        }
    }
    val profileBlocked = !selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // iOS sheet arrival: slides up on SwiftUI's `.smooth` (fade only with Reduce Motion).
    val arrival = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        arrival.animateTo(1f, if (LauncherMotion.reduced) LauncherMotion.dissolveIn else LauncherMotion.sheetPresent)
    }
    // iOS sheet: pulled down by its grabber/header it follows the finger, the dim behind it
    // lifts in proportion, and the release either carries it off (with the finger's speed) or
    // springs it back. Closing by the xmark or a tap outside slides it away too instead of
    // vanishing in one frame.
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    var sheetHeight by remember { mutableFloatStateOf(1f) }
    var leaving by remember { mutableStateOf(false) }
    val motionScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currentBack by rememberUpdatedState(onBack)
    fun dismiss(velocity: Float = 0f) {
        if (leaving) return
        leaving = true
        closeKeyboard()
        motionScope.launch {
            if (LauncherMotion.reduced) arrival.animateTo(0f, LauncherMotion.dissolveOut)
            else if (!wide) pull.animateTo(sheetHeight, LauncherMotion.sheetDismiss, initialVelocity = velocity.coerceAtLeast(0f))
            else arrival.animateTo(0f, LauncherMotion.sheetDismiss)
            currentBack()
        }
    }
    var pullRaw by remember { mutableFloatStateOf(0f) }
    val pullHandle = if (wide) Modifier else Modifier.draggable(rememberDraggableState { delta ->
        if (!leaving) {
            pullRaw += delta
            val visible = SheetPullMath.visibleOffset(pullRaw, sheetHeight)
            motionScope.launch { pull.snapTo(visible) }
        }
    }, Orientation.Vertical, onDragStarted = {
        pullRaw = if (pull.value >= 0f) pull.value else IosScrollPhysics.rubberBandInverse(pull.value, sheetHeight)
    }, onDragStopped = { velocity ->
        if (SheetPullMath.dismisses(pull.value, velocity, sheetHeight, density.density)) dismiss(velocity)
        else if (!leaving) motionScope.launch {
            pull.animateTo(0f, LauncherMotion.sheetReturn, initialVelocity = velocity)
        }
    })
    Box(Modifier.fillMaxSize()
        .then(if (hiddenForDrag) Modifier else Modifier.drawBehind {
            drawRect(Color.Black, alpha = .22f * SheetPullMath.backdropFraction(arrival.value, pull.value, sheetHeight))
        }.pointerInput(Unit) { detectTapGestures { dismiss() } }),
        contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter) {
        val sheetShape = if (wide) RoundedCornerShape(IosUi.sheetRadius)
            else RoundedCornerShape(topStart = IosUi.sheetRadius, topEnd = IosUi.sheetRadius)
        Box((if (wide) Modifier.widthIn(max = 620.dp).fillMaxWidth(.9f).fillMaxHeight(.9f)
                else Modifier.fillMaxWidth().statusBarsPadding().padding(top = 18.dp).fillMaxHeight())
            .onSizeChanged { sheetHeight = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                val p = arrival.value
                alpha = if (hiddenForDrag) 0f else if (LauncherMotion.reduced) p.coerceIn(0f, 1f) else 1f
                if (!LauncherMotion.reduced) {
                    // iPad form sheet (unfolded) and iPhone sheet (cover): both rise from the bottom edge.
                    translationY = if (wide) SheetPullMath.formSheetTranslation(p, size.height)
                        else (1f - p) * size.height + pull.value
                }
            }
            .then(if (hiddenForDrag) Modifier.clearAndSetSemantics { }.focusProperties { canFocus = false } else Modifier)
            .iosSheetSurface(sheetShape)
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("visual-widget-picker")) {
            AnimatedContent(targetState = detail, label = "widget gallery page",
                transitionSpec = {
                    val forward = targetState != null
                    if (LauncherMotion.reduced) fadeIn(LauncherMotion.dissolveIn) togetherWith fadeOut(LauncherMotion.dissolveOut)
                    else (slideInHorizontally(LauncherMotion.navigationPush) { if (forward) it else -it / 3 } +
                        fadeIn(LauncherMotion.dissolveIn)) togetherWith
                        (slideOutHorizontally(LauncherMotion.navigationPush) { if (forward) -it / 3 else it } +
                            fadeOut(LauncherMotion.dissolveOut))
                }) { page ->
                if (page == null) Column(Modifier.fillMaxSize()) {
                    Column(pullHandle.testTag("widget-gallery-handle")) {
                    IosGrabber()
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GallerySearchField(query, { query = it }, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        GlassCircleButton(IosSymbols.xmark, uxText("关闭", "Close"), "widget-gallery-close", 40.dp, ink, { dismiss() })
                    }
                    }
                    if (profiles.any { it.isWork }) IosSegmentedControl(profiles.map { launcherText(it.label) },
                        profiles.indexOfFirst { it.userSerial == selectedProfile.userSerial }.coerceAtLeast(0),
                        { profiles.getOrNull(it)?.let(onSelectProfile) },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                    GalleryHome(query, groups, entries, nativeEntries, profileBlocked, selectedProfile, onTurnOnWork,
                        appIcons, dark, ink, wide,
                        onOpen = { group, start -> closeKeyboard(); detailStart = start; detailKey = group.key },
                        onAddNative = { add(GalleryVariant.Native(it)) },
                        onDragNative = { entry, point -> dragStart(GalleryVariant.Native(entry), point) },
                        footprintOf = footprint, variantsOf = ::variantsOf,
                        latestDrag = { latestDrag(it) }, latestDrop = { latestDrop() }, latestCancel = { latestCancelDrag() })
                } else GalleryDetail(page, variantsOf(page), detailStart, appIcons, ink, wide,
                    onBack = { detailKey = null }, onClose = { dismiss() }, onAdd = ::add, onDragStart = ::dragStart,
                    latestDrag = { latestDrag(it) }, latestDrop = { latestDrop() }, latestCancel = { latestCancelDrag() })
            }
        }
    }
}

@Composable
private fun GallerySearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val placeholder = launcherText("Search widgets").let { if (it == "Search widgets") uxText("搜索小组件", "Search Widgets") else it }
    val ink = MaterialTheme.colorScheme.onSurface
    BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 17.sp),
        cursorBrush = SolidColor(IosUi.blue),
        modifier = modifier.height(40.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFF767680).copy(alpha = if (iosSheetDark()) .24f else .12f))
            .testTag("widget-catalog-search").semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(IosSymbols.magnifyingGlass, null, Modifier.size(18.dp), tint = iosSecondaryText())
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, color = iosSecondaryText(), fontSize = 17.sp, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) Box(Modifier.size(34.dp).clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onQuery("") },
                    contentAlignment = Alignment.Center) {
                    IosClearGlyph(iosSecondaryText(), 17.dp, launcherText("Clear search"))
                }
            }
        })
}

@Composable
private fun GalleryHome(
    query: String,
    groups: List<GalleryGroup>,
    entries: List<WidgetCatalogEntry>?,
    nativeEntries: List<NativeWidgetEntry>,
    profileBlocked: Boolean,
    selectedProfile: AppProfile,
    onTurnOnWork: (Long) -> Unit,
    appIcons: Map<Pair<Long, String>, AppEntry>,
    dark: Boolean,
    ink: Color,
    wide: Boolean,
    onOpen: (GalleryGroup, Int) -> Unit,
    onAddNative: (NativeWidgetEntry) -> Unit,
    onDragNative: (NativeWidgetEntry, Offset) -> Unit,
    footprintOf: (AppWidgetProviderInfo) -> WidgetSpan?,
    variantsOf: (GalleryGroup) -> List<GalleryVariant>,
    latestDrag: (Offset) -> Unit,
    latestDrop: () -> Unit,
    latestCancel: () -> Unit,
) {
    val trimmed = query.trim()
    if (profileBlocked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(launcherFormat(if (selectedProfile.quiet) R.string.ui_profile_paused else R.string.ui_profile_unavailable,
                launcherText(selectedProfile.label)), color = ink, textAlign = TextAlign.Center)
            if (selectedProfile.isWork) IosButton(launcherText("Turn on"), { onTurnOnWork(selectedProfile.userSerial) },
                Modifier.padding(top = 12.dp))
        }
        return
    }
    val nativeById = remember(nativeEntries) { nativeEntries.associateBy { it.id } }
    // Search hits are individual widgets (iOS lists matching widgets, then their apps).
    data class Hit(val group: GalleryGroup, val variantIndex: Int, val title: String, val subtitle: String, val tag: String)
    val hits = remember(trimmed, groups, entries, nativeEntries) {
        if (trimmed.isEmpty()) emptyList() else groups.flatMap { group ->
            val groupScore = matchScore(trimmed, SearchKeys.of(group.title))
            val variants = variantsOf(group)
            val seen = mutableSetOf<String>()
            variants.mapIndexedNotNull { index, variant ->
                val identity = when (variant) {
                    is GalleryVariant.Native -> "native-${variant.entry.kind.key}-${variant.entry.spec.widget}"
                    is GalleryVariant.Provider -> variant.entry.provider.provider.flattenToString() + "/" + variant.entry.userSerial
                }
                if (!seen.add(identity)) return@mapIndexedNotNull null
                val score = maxOf(groupScore, matchScore(trimmed, SearchKeys.of(variant.title)),
                    if (variant.description.contains(trimmed, ignoreCase = true)) SearchScore.SUBSTRING else 0)
                if (score == 0) null else {
                    val tag = when (variant) {
                        is GalleryVariant.Native -> "widget-native-${nativeWidgetKey(variant.entry.id)}"
                        is GalleryVariant.Provider -> "widget-provider-${variant.entry.provider.provider.flattenToString()}" +
                            if (variant.entry.isWork) "-profile-${variant.entry.userSerial}" else ""
                    }
                    score to Hit(group, index, variant.title, group.title, tag)
                }
            }
        }.sortedByDescending { it.first }.map { it.second }
    }
    val listState = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().iosScrollEdgeFade(top = 16.dp, bottom = 22.dp,
            fadeTop = { listState.canScrollBackward }, fadeBottom = { listState.canScrollForward })
        .testTag("widget-catalog-list"), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (trimmed.isEmpty()) {
            val featuredIds = featuredNativeWidgetIds().filter { it in nativeById }
            if (featuredIds.isNotEmpty()) {
                item("featured-header") {
                    Text(uxText("精选", "Featured"), Modifier.padding(top = 4.dp, start = 4.dp).semantics { heading() }
                        .testTag("widget-native-header"), color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                item("featured") {
                  BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Previews keep their real Home size (iOS); rows pack by grid columns.
                    val columns = featuredColumns(maxWidth.value)
                    val scale = minOf(1f, maxWidth.value / galleryPreviewSize(WidgetSpan(4, 2)).first)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                      featuredRows(featuredIds, columns).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((16 * scale).dp, Alignment.CenterHorizontally)) {
                            row.forEach { id ->
                                val entry = nativeById.getValue(id)
                                var origin by remember { mutableStateOf(Offset.Zero) }
                                val (w, h) = galleryPreviewSize(entry.span)
                                Column(Modifier.width((w * scale).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    val interaction = remember { MutableInteractionSource() }
                                    // iOS gallery previews give no press feedback (and never scale).
                                    NativeWidget(entry.id, preview = true, modifier = Modifier.size((w * scale).dp, (h * scale).dp)
                                        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                                        .galleryDrag(entry.id, { origin }, true, { onDragNative(entry, it) },
                                            latestDrag, latestDrop, latestCancel)
                                        .clickable(interaction, null, role = Role.Button) { onAddNative(entry) }
                                        .testTag("widget-native-${nativeWidgetKey(entry.id) ?: entry.id}"))
                                    Text(entry.kind.label, Modifier.padding(top = 6.dp), color = iosSecondaryText(), fontSize = 13.sp)
                                }
                            }
                        }
                      }
                    }
                  }
                }
            }
            item("apps-header") {
                Text(uxText("App", "Apps"), Modifier.padding(top = 10.dp, start = 4.dp).semantics { heading() },
                    color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            item("apps") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(IosUi.groupRadius)).background(iosGroupFill())) {
                    groups.forEachIndexed { index, group ->
                        if (index > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(.5.dp).background(iosSeparator()))
                        GalleryRow(group.title, if (group.isWork) launcherText("Work") else null,
                            "widget-app-${group.packageName ?: group.nativeKind?.key}${if (group.isWork) "-profile-${group.userSerial}" else ""}",
                            leading = { GalleryGroupIcon(group, appIcons, 30.dp) }, onClick = { onOpen(group, 0) })
                    }
                    if (entries == null) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        IosActivityIndicator(color = iosSecondaryText())
                    }
                }
            }
            if (groups.isEmpty() && entries != null) item("empty") {
                GalleryEmpty(searching = false, ink = ink)
            }
        } else {
            if (hits.isEmpty()) item("empty") { GalleryEmpty(searching = true, ink = ink) }
            else item("hits") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(IosUi.groupRadius)).background(iosGroupFill())) {
                    hits.forEachIndexed { index, hit ->
                        if (index > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(.5.dp).background(iosSeparator()))
                        GalleryRow(hit.title, hit.subtitle, hit.tag,
                            leading = { GalleryGroupIcon(hit.group, appIcons, 30.dp) }, onClick = { onOpen(hit.group, hit.variantIndex) })
                    }
                }
            }
            if (entries == null) item("loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { IosActivityIndicator(color = iosSecondaryText()) }
            }
        }
    }
}

@Composable
private fun GalleryEmpty(searching: Boolean, ink: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(if (searching) R.string.ui_widget_empty_search_title else R.string.ui_widget_empty_profile_title),
            color = ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(stringResource(if (searching) R.string.ui_widget_empty_search_message else R.string.ui_widget_empty_profile_message),
            Modifier.padding(top = 8.dp), color = iosSecondaryText(), fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GalleryRow(title: String, subtitle: String?, tag: String, leading: @Composable () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .background(if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = .08f) else Color.Transparent)
        .clickable(interaction, null, role = Role.Button, onClick = onClick).testTag(tag)
        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = iosSecondaryText(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Icon(IosSymbols.chevronRight, null, Modifier.size(20.dp), tint = iosSecondaryText().copy(alpha = .5f))
    }
}

/** iOS widget detail: name + description, a pager of size previews, dots, and "添加小组件". */
@Composable
private fun GalleryDetail(
    group: GalleryGroup, variants: List<GalleryVariant>, start: Int,
    appIcons: Map<Pair<Long, String>, AppEntry>, ink: Color, wide: Boolean,
    onBack: () -> Unit, onClose: () -> Unit,
    onAdd: (GalleryVariant) -> Unit, onDragStart: (GalleryVariant, Offset) -> Unit,
    latestDrag: (Offset) -> Unit, latestDrop: () -> Unit, latestCancel: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (variants.size - 1).coerceAtLeast(0))) { variants.size }
    Column(Modifier.fillMaxSize().testTag("widget-detail")) {
        IosGrabber()
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
            Row(Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(10.dp))
                .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onBack)
                .padding(end = 8.dp).testTag("widget-detail-back"), verticalAlignment = Alignment.CenterVertically) {
                Icon(IosSymbols.chevronLeft, uxText("返回", "Back"), Modifier.size(30.dp), tint = IosUi.blue)
                Text(uxText("小组件", "Widgets"), color = IosUi.blue, fontSize = 17.sp)
            }
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                GalleryGroupIcon(group, appIcons, 22.dp)
                Spacer(Modifier.width(7.dp))
                Text(group.title, color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                GlassCircleButton(IosSymbols.xmark, uxText("关闭", "Close"), "widget-detail-close", 36.dp, ink, onClose)
            }
        }
        if (variants.isEmpty()) { GalleryEmpty(searching = false, ink = ink); return@Column }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("widget-detail-pager"), key = { index ->
            when (val v = variants[index]) {
                is GalleryVariant.Native -> "native-${v.entry.id}"
                is GalleryVariant.Provider -> "${v.entry.provider.provider.flattenToString()}/${v.entry.userSerial}/${v.span.width}x${v.span.height}"
            }
        }) { index ->
            val variant = variants[index]
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(variant.title, Modifier.padding(top = 8.dp), color = ink, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (variant.description.isNotBlank()) Text(variant.description, Modifier.padding(top = 4.dp).widthIn(max = 420.dp),
                    color = iosSecondaryText(), fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center, maxLines = 3)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    var origin by remember { mutableStateOf(Offset.Zero) }
                    val fits = variant !is GalleryVariant.Provider || variant.fits
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    GalleryVariantPreview(variant, maxWidth, maxHeight, Modifier
                        .graphicsLayer { val s = if (pressed) .96f else 1f; scaleX = s; scaleY = s; alpha = if (fits) 1f else .45f }
                        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                        .galleryDrag(variant, { origin }, fits, { onDragStart(variant, it) }, latestDrag, latestDrop, latestCancel)
                        .clickable(interaction, null, enabled = fits, role = Role.Button) { onAdd(variant) }
                        .testTag("widget-detail-preview-$index"))
                }
                Text(if (variant is GalleryVariant.Provider && !variant.fits) launcherText("Doesn’t fit this layout")
                    else widgetFamilyName(variant.span), color = iosSecondaryText(), fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (variants.size > 1) repeat(variants.size) { index ->
                Box(Modifier.padding(horizontal = 4.dp).size(7.dp).clip(CircleShape)
                    .background(ink.copy(alpha = if (pager.currentPage == index) .85f else .25f)))
            }
        }
        val current = variants.getOrNull(pager.currentPage)
        val fits = current != null && (current !is GalleryVariant.Provider || current.fits)
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 20.dp),
            contentAlignment = Alignment.Center) {
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(Modifier.widthIn(min = 220.dp).height(50.dp).graphicsLayer { val s = if (pressed) .97f else 1f; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(50)).background(IosUi.blue.copy(alpha = if (fits) 1f else .35f))
                .clickable(interaction, null, enabled = fits, role = Role.Button) { current?.let(onAdd) }
                .testTag("widget-detail-add").padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(IosSymbols.plus, null, Modifier.size(20.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(uxText("添加小组件", "Add Widget"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
