package com.jake.duolauncher

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

/** Photos ▸ Featured Photo (small, medium): the launcher's current wallpaper, the only photo 18Duo reads. */
internal fun photosWidgetSpecs(): List<IosWidgetSpec> = listOf(NativeWidgetSize.SMALL, NativeWidgetSize.MEDIUM).map { family ->
    IosWidgetSpec(if (family == NativeWidgetSize.SMALL) -116 else -117, NativeWidgetKind.PHOTOS, "photos", family,
        "精选照片", "Featured Photo", "精选照片，取自当前桌面壁纸。", "Featured photo from the current wallpaper.",
        featured = if (family == NativeWidgetSize.MEDIUM) 30 else null,
        opens = { Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY) }) {
        PhotosWidget(this)
    }
}

@Composable
private fun rememberWallpaperPhoto(): Bitmap? {
    val context = LocalContext.current
    val revision = LauncherBackgroundCache.revision.intValue
    return remember(revision) { cachedLauncherBackground(context) ?: bundledDefaultWallpaper(context) }
}

@Composable
private fun PhotosWidget(scope: IosWidgetContext) {
    val photo = rememberWallpaperPhoto()
    val medium = scope.family != NativeWidgetSize.SMALL
    IosWidgetCard(scope.modifier, scope.family, background = {
        if (photo != null) Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = if (medium) Alignment.Center else Alignment.TopCenter)
        else Box(Modifier.fillMaxSize().background(Color(0xFF1C1C1E)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Color.Transparent, 1f to Color.Black.copy(alpha = .55f))))
    }) { s ->
        Column(Modifier.align(Alignment.BottomStart).padding(s.pt(14f))) {
            WidgetText(uxText("精选", "Featured"), s, 13f, weight = FontWeight.SemiBold, color = Color.White)
            // Says where the picture comes from; a date here read as "a photo taken today" (round-3 review).
            WidgetText(uxText("桌面墙纸", "Home Screen Wallpaper"), s, 11f, color = Color.White.copy(alpha = .85f))
        }
    }
}
