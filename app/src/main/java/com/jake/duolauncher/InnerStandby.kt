package com.jake.duolauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A photo page shows one picture this long before the next crossfades in. */
internal const val INNER_STANDBY_PHOTO_MILLIS = 20_000L
private const val INNER_STANDBY_PREFS = "standby"
private const val PREF_PHOTOS_ASKED = "inner_photos_asked"

/**
 * Round 24 (owner photo 1, iPhone Duo): the inner screen standing open like a book shows the clock page on the left
 * and the photo library on the right. It fades in over Home, any touch dismisses it (Home stays put underneath), and
 * the canvas is turned upright when the window isn't in the book rotation (a locked rotation), like cover StandBy.
 */
@Composable
internal fun InnerStandbyOverlay(visible: Boolean, target: Int, onExit: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    var composed by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            composed = true
            if (LauncherMotion.animationsOff) alpha.snapTo(1f)
            else alpha.animateTo(1f, LauncherMotion.springSpec(LauncherMotion.smoothSpring, .001f))
        } else {
            if (LauncherMotion.animationsOff) alpha.snapTo(0f) else alpha.animateTo(0f, tween(260))
            composed = false
        }
    }
    if (!composed) return
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val displayRotation = remember(view, configuration) { view.display?.rotation ?: Surface.ROTATION_0 }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("inner-standby").graphicsLayer { this.alpha = alpha.value }
        .background(Color.Black)
        .pointerInput(visible) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                if (visible) onExit()
            }
        }, contentAlignment = Alignment.Center) {
        val turns = standbyQuarterTurns(target, displayRotation, maxHeight > maxWidth)
        val sideways = turns % 2 == 1
        Box(Modifier.wrapContentSize(unbounded = true)
            .requiredSize(if (sideways) maxHeight else maxWidth, if (sideways) maxWidth else maxHeight)
            .graphicsLayer { rotationZ = turns * 90f }) {
            InnerStandbyPages(active = visible)
        }
    }
}

@Composable
private fun InnerStandbyPages(active: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    StandbyWindowEffects(view, activity, active)
    val locale = LocalConfiguration.current.locales[0]
    val use24h = android.text.format.DateFormat.is24HourFormat(context)
    val clock = rememberStandbyClock()
    val night = rememberStandbyNight(context, clock)
    StandbyNightBrightness(activity, night && active)
    val frame = remember(clock, locale, use24h) { StandbyFrame(clock, locale, use24h) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { StandbyBookClockPage(frame, StandbyStyles.analog(0, night)) }
        Box(Modifier.weight(1f).fillMaxHeight()) { InnerStandbyPhotoPage(night) }
    }
}

/** Library photos (newest camera shots first), else the wallpaper; one every [INNER_STANDBY_PHOTO_MILLIS]. */
@Composable
private fun InnerStandbyPhotoPage(night: Boolean) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPhotoAccess(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPhotoAccess(context)
    }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(INNER_STANDBY_PREFS, Context.MODE_PRIVATE)
        // iOS asks for Photos the first time StandBy wants them; asked once, the answer stands (Settings changes it).
        if (!granted && !prefs.getBoolean(PREF_PHOTOS_ASKED, false)) {
            prefs.edit().putBoolean(PREF_PHOTOS_ASKED, true).apply()
            ask.launch(photoPermissions())
        }
    }
    val uris by produceState(emptyList<Uri>(), granted) {
        value = if (granted) withContext(Dispatchers.IO) { runCatching { libraryPhotos(context) }.getOrDefault(emptyList()) } else emptyList()
    }
    val wallpapers = rememberStandbyPhotos()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(uris.size) {
        while (uris.size > 1) { delay(INNER_STANDBY_PHOTO_MILLIS); index++ }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("inner-standby-photo")) {
        val width = constraints.maxWidth; val height = constraints.maxHeight
        val uri = uris.getOrNull(Math.floorMod(index, uris.size.coerceAtLeast(1)))
        val photo by produceState<ImageBitmap?>(null, uri, width, height) {
            value = uri?.let { withContext(Dispatchers.IO) { runCatching { decodePhoto(context, it, width, height) }.getOrNull() } }
        }
        Crossfade(photo ?: wallpapers.firstOrNull(), animationSpec = tween(900), label = "inner-standby-photo") { image ->
            PhotoFill(image, night)
        }
    }
}

@Composable
private fun PhotoFill(photo: ImageBitmap?, night: Boolean) {
    val nightFilter = remember { ColorFilter.colorMatrix(ColorMatrix(standbyNightPhotoMatrix)) }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color.Black)
        if (photo == null || photo.width <= 0 || photo.height <= 0) return@Canvas
        val crop = wallpaperSourceCrop(photo.width, photo.height, size.width.toInt().coerceAtLeast(1),
            size.height.toInt().coerceAtLeast(1), rightAnchored = false)
        drawImage(photo, IntOffset(crop.x, crop.y), IntSize(crop.width, crop.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), colorFilter = if (night) nightFilter else null)
    }
}

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Full or "selected photos" access (Android 14's partial grant lists only what the user picked). */
private fun hasPhotoAccess(context: Context): Boolean = photoPermissions().any {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

/** Camera photos first (iOS features the user's own pictures, never screenshots), newest first, shuffled within 60. */
private fun libraryPhotos(context: Context): List<Uri> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    fun query(selection: String?): List<Uri> {
        val result = ArrayList<Uri>()
        context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), selection, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && result.size < 60) result += ContentUris.withAppendedId(collection, cursor.getLong(id))
        }
        return result
    }
    val camera = query("${MediaStore.Images.Media.RELATIVE_PATH} LIKE 'DCIM/%' AND " +
        "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE '%Screenshots%' AND ${MediaStore.Images.Media.WIDTH} >= 800")
    return (camera.ifEmpty { query("${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE '%Screenshots%'") }).shuffled()
}

/** Decoded no larger than about twice the page (ImageDecoder applies the EXIF rotation). */
private fun decodePhoto(context: Context, uri: Uri, width: Int, height: Int): ImageBitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val scale = maxOf(width.toFloat() / info.size.width, height.toFloat() / info.size.height).coerceAtMost(1f)
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }.asImageBitmap()
}
