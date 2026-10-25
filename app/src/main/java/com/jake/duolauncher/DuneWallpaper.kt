package com.jake.duolauncher

import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.content.SharedPreferences
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun DuneWallpaper(modifier: Modifier = Modifier.fillMaxSize()) {
    val palette = LocalDuoPalette.current
    val context = LocalContext.current.applicationContext
    val revision = LauncherBackgroundCache.revision.intValue
    val initial = remember(revision) { LauncherBackgroundCache.bitmap?.takeUnless { it.isRecycled } }
    val photo = produceState(initialValue = initial, key1 = context, key2 = revision) {
        value = withContext(Dispatchers.IO) { loadLauncherBackground(context) }
    }.value
    // Core.35: the default wallpaper is Apple's live dunes, moved by the hinge.
    if (photo == null && liveDunesEnabled(context)) { LiveDunes(modifier); return }
    val bundled = remember(context) { bundledDefaultWallpaper(context)?.asImageBitmap() }
    // Wrap once, not per frame.
    val image = remember(photo, bundled) { photo?.asImageBitmap() ?: bundled }
    // The crop depends only on the image and the size.
    Spacer(modifier.drawWithCache {
        val crop = image?.let { wallpaperCropFor(it, size.width, size.width, size.height, rightAnchored = photo == null) }
        onDrawBehind { drawCroppedBackground(image, crop, palette.dark) }
    })
}

/** No photo is set and the live dunes have not failed on this device. */
internal fun liveDunesEnabled(context: Context): Boolean = !DuneLive.failed && !launcherBackgroundEnabled(context)

/**
 * Core.35: Apple's live dunes ([DuneLive]). A window-filling instance decides the render size and
 * feeds the hinge to Apple's spring; any other instance (a preview, a pane slice) draws a centred
 * crop of the same frame, or with [fullWidthPx] the slice of a [fullWidthPx]-wide window starting
 * at [viewportLeftPx]. Until the first frame (a cold start compiles Apple's programs) the same
 * scene stands in, rendered by Apple at the nearer endpoint ([DuneStills]).
 */
@Composable
private fun LiveDunes(modifier: Modifier, fullWidthPx: Float = 0f, viewportLeftPx: () -> Float = { 0f }) {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    remember(context) { DuneLive.bind(context) }
    val signal = remember { foldSignalState() }
    LaunchedEffect(signal) { snapshotFlow { dunesHinge(signal.value) }.collect { DuneLive.setHinge(it) } }
    val token = remember { Any() }
    DisposableEffect(token) { onDispose { DuneLive.detach(token) } }
    val stills = produceState<Pair<ImageBitmap?, ImageBitmap?>>(null to null, context) {
        value = withContext(Dispatchers.IO) {
            DuneStills.folded(context)?.asImageBitmap() to DuneStills.open(context)?.asImageBitmap()
        }
    }
    Spacer(modifier.onSizeChanged { size ->
        val root = view.rootView
        if (fullWidthPx <= 0f && size.width >= root.width * .9f && size.height >= root.height * .9f)
            DuneLive.attach(token, size.width, size.height)
    }.drawBehind {
        val frame = DuneLive.frame.value
        val image = frame?.image ?: stills.value.let { (folded, open) -> if (DuneLive.fold.value < .5f) folded ?: open else open ?: folded }
        if (image == null || image.width <= 0 || image.height <= 0) { drawDunes(); return@drawBehind }
        val fullWidth = if (fullWidthPx > 0f) fullWidthPx else size.width
        val crop = wallpaperSourceCrop(image.width, image.height, fullWidth.toInt().coerceAtLeast(1),
            size.height.toInt().coerceAtLeast(1), rightAnchored = false)
        drawImage(image, srcOffset = IntOffset(crop.x, crop.y), srcSize = IntSize(crop.width, crop.height),
            dstOffset = IntOffset(-(if (fullWidthPx > 0f) viewportLeftPx() else 0f).toInt(), 0),
            dstSize = IntSize(fullWidth.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
    })
}

/** Null when the image has no pixels (the painted dunes stand in). */
private fun wallpaperCropFor(image: ImageBitmap, width: Float, fullWidth: Float, height: Float,
    rightAnchored: Boolean): WallpaperSourceCrop? {
    if (image.width <= 0 || image.height <= 0) return null
    return wallpaperSourceCrop(image.width, image.height, fullWidth.toInt().coerceAtLeast(width.toInt().coerceAtLeast(1)),
        height.toInt().coerceAtLeast(1), rightAnchored)
}

/** [drawLauncherBackground] / [drawLauncherBackgroundViewport] with the crop computed once. */
private fun DrawScope.drawCroppedBackground(photo: ImageBitmap?, crop: WallpaperSourceCrop?, dark: Boolean,
    fullWidthPx: Float = size.width, viewportLeftPx: Float = 0f) {
    if (photo == null || crop == null) { drawDunes(dark); return }
    drawImage(
        image = photo,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset(-viewportLeftPx.toInt(), 0),
        dstSize = IntSize(fullWidthPx.toInt().coerceAtLeast(size.width.toInt().coerceAtLeast(1)), size.height.toInt().coerceAtLeast(1)),
    )
}

private object BundledDefaultWallpaperCache {
    @Volatile var bitmap: Bitmap? = null
}

internal fun bundledDefaultWallpaper(context: Context): Bitmap? {
    BundledDefaultWallpaperCache.bitmap?.takeUnless { it.isRecycled }?.let { return it }
    return synchronized(BundledDefaultWallpaperCache) {
        BundledDefaultWallpaperCache.bitmap?.takeUnless { it.isRecycled } ?: BitmapFactory.decodeResource(
            context.resources,
            R.drawable.duo_wallpaper_c,
            BitmapFactory.Options().apply { inScaled = false },
        )?.also { BundledDefaultWallpaperCache.bitmap = it }
    }
}

internal data class WallpaperSourceCrop(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun wallpaperSourceCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
    rightAnchored: Boolean,
): WallpaperSourceCrop {
    val safeSourceWidth = sourceWidth.coerceAtLeast(1)
    val safeSourceHeight = sourceHeight.coerceAtLeast(1)
    val safeDestinationWidth = destinationWidth.coerceAtLeast(1)
    val safeDestinationHeight = destinationHeight.coerceAtLeast(1)
    val sourceAspect = safeSourceWidth.toFloat() / safeSourceHeight
    val destinationAspect = safeDestinationWidth.toFloat() / safeDestinationHeight
    val cropWidth: Int
    val cropHeight: Int
    if (sourceAspect > destinationAspect) {
        cropHeight = safeSourceHeight
        cropWidth = (cropHeight * destinationAspect).toInt().coerceIn(1, safeSourceWidth)
    } else {
        cropWidth = safeSourceWidth
        cropHeight = (cropWidth / destinationAspect).toInt().coerceIn(1, safeSourceHeight)
    }
    return WallpaperSourceCrop(
        x = if (rightAnchored) safeSourceWidth - cropWidth else (safeSourceWidth - cropWidth) / 2,
        y = (safeSourceHeight - cropHeight) / 2,
        width = cropWidth,
        height = cropHeight,
    )
}

internal fun DrawScope.drawLauncherBackground(
    photo: ImageBitmap?,
    dark: Boolean = false,
    rightAnchored: Boolean = false,
) {
    if (photo == null || photo.width <= 0 || photo.height <= 0) {
        drawDunes(dark)
        return
    }
    val destinationWidth = size.width.toInt().coerceAtLeast(1)
    val destinationHeight = size.height.toInt().coerceAtLeast(1)
    val crop = wallpaperSourceCrop(photo.width, photo.height, destinationWidth, destinationHeight, rightAnchored)
    drawImage(
        image = photo,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstSize = IntSize(destinationWidth, destinationHeight),
    )
}

/** Draws the exact horizontal slice of the full launcher wallpaper that belongs
 * behind a projected pane. This keeps the duplicate opaque pane pixel-aligned
 * with the stationary wallpaper before the fold begins. */
internal fun DrawScope.drawLauncherBackgroundViewport(
    photo: ImageBitmap?,
    fullWidthPx: Float,
    viewportLeftPx: Float,
    dark: Boolean = false,
    rightAnchored: Boolean = false,
) {
    if (photo == null || photo.width <= 0 || photo.height <= 0) {
        drawDunes(dark)
        return
    }
    val fullWidth = fullWidthPx.toInt().coerceAtLeast(size.width.toInt().coerceAtLeast(1))
    val destinationHeight = size.height.toInt().coerceAtLeast(1)
    val crop = wallpaperSourceCrop(photo.width, photo.height, fullWidth, destinationHeight, rightAnchored)
    // Draw the full image at its original scale and let the pane clip it. Clamping
    // both crop endpoints used to stretch a single image column across offscreen
    // retained pages, which could leak into glass sampling during a fold.
    drawImage(
        image = photo,
        srcOffset = IntOffset(crop.x, crop.y),
        srcSize = IntSize(crop.width, crop.height),
        dstOffset = IntOffset(-viewportLeftPx.toInt(), 0),
        dstSize = IntSize(fullWidth, destinationHeight),
    )
}

@Composable
internal fun DuneWallpaperViewport(
    fullWidthPx: Float,
    viewportLeftPx: () -> Float,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val palette = LocalDuoPalette.current
    val context = LocalContext.current.applicationContext
    val revision = LauncherBackgroundCache.revision.intValue
    val initial = remember(revision) { LauncherBackgroundCache.bitmap?.takeUnless { it.isRecycled } }
    val photo = produceState(initialValue = initial, key1 = context, key2 = revision) {
        value = withContext(Dispatchers.IO) { loadLauncherBackground(context) }
    }.value
    if (photo == null && liveDunesEnabled(context)) { LiveDunes(modifier, fullWidthPx, viewportLeftPx); return }
    val bundled = remember(context) { bundledDefaultWallpaper(context)?.asImageBitmap() }
    val image = remember(photo, bundled) { photo?.asImageBitmap() ?: bundled }
    // Panes move every frame while Home pages: only the viewport offset changes per draw.
    Spacer(modifier.drawWithCache {
        val crop = image?.let { wallpaperCropFor(it, size.width, fullWidthPx, size.height, rightAnchored = photo == null) }
        onDrawBehind {
            drawCroppedBackground(image, crop, palette.dark, fullWidthPx = fullWidthPx, viewportLeftPx = viewportLeftPx())
        }
    })
}

internal fun DrawScope.drawDunes(dark: Boolean = false) {
        val w = size.width; val h = size.height
        drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF132832), Color(0xFF263E49), Color(0xFF463F35))
            else listOf(Color(0xFF41687E), Color(0xFF94ADB5), Color(0xFFD8CEB6))))
        fun dune(y: Float, crest: Float, color: Color) {
            val path = Path().apply {
                moveTo(0f, h * y)
                cubicTo(w * .3f, h * (y - crest), w * .6f, h * (y + crest), w, h * (y - crest * .35f))
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(path, color)
        }
        dune(.57f, .17f, if (dark) Color(0xFF5B5040) else Color(0xFFC9B38E))
        dune(.72f, .12f, if (dark) Color(0xFF453D32) else Color(0xFFA49373))
        dune(.85f, .19f, if (dark) Color(0xFF302C26) else Color(0xFF84775F))
        for (n in 0..28) {
            val y = h * (.84f + n * .011f)
            val path = Path().apply {
                moveTo(0f, y)
                cubicTo(w * .35f, y - h * .17f, w * .65f, y + h * .05f, w, y - h * .06f)
            }
            drawPath(path, Color.White.copy(alpha = .045f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.3f))
        }
}

/** A static scene rendered on surface changes: no animation loop or background polling. */
class DuneWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = DuneEngine()

    inner class DuneEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val painter = CanvasDrawScope()
        private val appearance = AppearanceStore(this@DuneWallpaperService)
        private val backgroundPrefs = launcherBackgroundPreferences(this@DuneWallpaperService)
        private val loader = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val defaultPhoto by lazy(LazyThreadSafetyMode.NONE) {
            bundledDefaultWallpaper(this@DuneWallpaperService)?.asImageBitmap()
        }
        private var photo: ImageBitmap? = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
        private var photoLoad = 0
        private var photoLoading = false
        private var photoFailed = false
        private var visible = false
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); appearance.refresh(); render(holder) }
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height); appearance.refresh(); render(holder)
        }
        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                photoLoad++
                photoLoading = false
                photoFailed = false
                photo = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
                backgroundPrefs.registerOnSharedPreferenceChangeListener(this)
                appearance.refresh(); render(surfaceHolder)
            } else {
                backgroundPrefs.unregisterOnSharedPreferenceChangeListener(this)
            }
        }
        override fun onDestroy() {
            photoLoad++
            loader.cancel()
            backgroundPrefs.unregisterOnSharedPreferenceChangeListener(this)
            super.onDestroy()
        }
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (sharedPreferences === backgroundPrefs) {
                photoLoad++
                photo = cachedLauncherBackground(this@DuneWallpaperService)?.asImageBitmap()
                photoLoading = false
                photoFailed = false
            }
            if (visible) { appearance.refresh(); render(surfaceHolder) }
        }
        private fun render(holder: SurfaceHolder) {
            if (!holder.surface.isValid) return
            if (launcherBackgroundEnabled(this@DuneWallpaperService) && photo == null && !photoFailed) {
                if (photoLoading) return
                photoLoading = true
                val request = ++photoLoad
                loader.launch {
                    val loaded = withContext(Dispatchers.IO) { loadLauncherBackground(this@DuneWallpaperService) }
                    if (request == photoLoad) {
                        photoLoading = false
                        photo = loaded?.asImageBitmap()
                        photoFailed = loaded == null
                        if (visible) render(holder)
                    }
                }
                return
            }
            if (!launcherBackgroundEnabled(this@DuneWallpaperService)) { photo = null; photoFailed = false }
            val canvas = try { holder.lockCanvas() } catch (_: IllegalArgumentException) { null } ?: return
            try {
                painter.draw(Density(resources.displayMetrics.density), LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(canvas), Size(canvas.width.toFloat(), canvas.height.toFloat())) {
                        drawLauncherBackground(photo ?: defaultPhoto, appearance.state.dark, rightAnchored = photo == null)
                    }
            } finally { holder.unlockCanvasAndPost(canvas) }
        }
    }
}

/** The bundled wallpaper if Home has decoded it already (never decodes; safe from any thread). */
internal fun cachedBundledWallpaper(): Bitmap? = BundledDefaultWallpaperCache.bitmap?.takeUnless { it.isRecycled }

/** The default wallpaper as Home shows it now: the live dunes at rest when they have rendered (a
 * centred frame of the window's own size), else the bundled still. Safe from any thread. */
internal fun currentDefaultWallpaper(): Bitmap? = DuneLive.restBitmap() ?: cachedBundledWallpaper()
