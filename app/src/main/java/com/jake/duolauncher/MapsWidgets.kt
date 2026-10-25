package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// =======================================================================================
// Maps ▸ Search (iOS 26 地图), small and medium. Measured on the owner's iPhone Duo
// (evidence/r21/ref/1.webp medium card 983 px = 338 pt; 4.webp agrees): a dark slate-green card
// (39, 48, 47) — Maps' dark base map with nothing loaded — and, in its lower left, a darker
// rounded panel (37, 37, 37; 7.9–156.4 × 69.8–152.7 pt, 20 pt corners) holding the glossy grey
// search button (Ø50.2 pt, (166, 173, 184) → (114, 117, 130), white magnifier) and an empty
// suggestion disc ((55, 57, 56)). The small card keeps the search button only.
//
// The widget reads no location. The magnifier opens the installed map app's own search (its
// published "搜索" app shortcut, which only the Home app may start), else the app itself; the rest
// of the card opens the map app. Preferred apps: Amap, Baidu Maps, Tencent Maps, Google Maps, then
// whatever handles geo: URIs.
// =======================================================================================

private const val MAPS_SEARCH_ZH = "搜索地点，或轻点打开地图。不读取你的位置。"
private const val MAPS_SEARCH_EN = "Search for a place, or tap to open Maps. Doesn’t read your location."

internal fun mapsWidgetSpecs(): List<IosWidgetSpec> = listOf(
    IosWidgetSpec(-190, NativeWidgetKind.MAPS, "maps", NativeWidgetSize.SMALL, "搜索", "Search",
        MAPS_SEARCH_ZH, MAPS_SEARCH_EN) { MapsWidget(this) },
    IosWidgetSpec(-191, NativeWidgetKind.MAPS, "maps", NativeWidgetSize.MEDIUM, "搜索", "Search",
        MAPS_SEARCH_ZH, MAPS_SEARCH_EN, featured = 70) { MapsWidget(this) },
)

/** Map apps in the order the Maps widget prefers them (the brief: Amap first on HyperOS). */
internal val MAP_PACKAGES = listOf("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map", "com.google.android.apps.maps")

/** The first of [MAP_PACKAGES] that [installed] reports; null when none is. */
internal fun preferredMapPackage(installed: (String) -> Boolean): String? = MAP_PACKAGES.firstOrNull(installed)

/** A map app's own search entry among its app shortcuts: "搜索" / "Search" (not "搜索附近的加油站"-style specials first). */
internal fun searchShortcutIndex(labels: List<String>): Int? {
    val normalized = labels.map { it.trim().lowercase() }
    normalized.indexOfFirst { it == "搜索" || it == "search" || it == "搜索地点" || it == "search maps" }.takeIf { it >= 0 }?.let { return it }
    return normalized.indexOfFirst { it.contains("搜索") || it.contains("search") }.takeIf { it >= 0 }
}

private fun mapPackage(context: Context): String? =
    preferredMapPackage { context.packageManager.getLaunchIntentForPackage(it) != null }

/** The map app's main screen, else whatever shows a geo: URI; null when nothing can. */
internal fun mapsAppIntent(context: Context): Intent? {
    mapPackage(context)?.let { pkg -> context.packageManager.getLaunchIntentForPackage(pkg)?.let { return it } }
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
    return geo.takeIf { runCatching { context.packageManager.resolveActivity(it, 0) }.getOrNull() != null }
}

/**
 * Starts the map app's search: its "搜索" app shortcut when it publishes one (LauncherApps; only
 * the default Home may start shortcuts), else false so the caller opens the app instead.
 */
private fun startMapSearch(context: Context): Boolean {
    val pkg = mapPackage(context) ?: return false
    val apps = context.getSystemService(LauncherApps::class.java) ?: return false
    return runCatching {
        if (!apps.hasShortcutHostPermission()) return false
        val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val shortcuts = apps.getShortcuts(query, Process.myUserHandle()).orEmpty().filter { it.isEnabled }
        val index = searchShortcutIndex(shortcuts.map { (it.shortLabel ?: it.longLabel)?.toString().orEmpty() }) ?: return false
        apps.startShortcut(shortcuts[index], null, null)
        true
    }.getOrDefault(false)
}

/*
 * Card points (1.webp): panel 7.9 / 69.8, 148.5 × 82.9 (small: the search button's 82.1-pt square);
 * search button centred (49.2, 111.1), suggestion disc (115.2, 111.25), both Ø50.2; the magnifier's
 * lens Ø19.6 at (45.9, 107.5) with a 3.5 pt stroke, its handle to (59.7, 121.6).
 */
private const val MAPS_PANEL_LEFT = 7.9f
private const val MAPS_PANEL_TOP = 69.8f
private const val MAPS_PANEL_HEIGHT = 82.9f
private const val MAPS_DISC = 50.2f
private const val MAPS_DISC_Y = 111.1f
private const val MAPS_SEARCH_X = 49.2f
private const val MAPS_SUGGESTION_X = 115.2f

private class MapsPalette(val card: Color, val panel: Color, val disc: Color, val button: Brush)

private fun mapsPalette(colors: WidgetColors): MapsPalette = when {
    colors.clear -> MapsPalette(Color.Transparent, Color.Black.copy(alpha = .22f), Color.White.copy(alpha = .14f),
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.White.copy(alpha = .3f))))
    colors.background.luminanceValue() > .5f -> MapsPalette(Color(0xFFDDE5E1), Color(0xFFF7F7F7), Color(0xFFE6E7E7),
        Brush.verticalGradient(listOf(Color(0xFFB9BFC9), Color(0xFF8E939F))))
    else -> MapsPalette(Color(0xFF27302F), Color(0xFF252525), Color(0xFF373938),
        Brush.verticalGradient(listOf(Color(0xFFA6ADB8), Color(0xFF727582))))
}

@Composable
private fun MapsWidget(scope: IosWidgetContext) {
    val colors = LocalWidgetColors.current
    val palette = mapsPalette(colors)
    val context = LocalContext.current
    val openApp = rememberWidgetAppOpener()
    val medium = scope.family != NativeWidgetSize.SMALL
    val card = scope.bareModifier.widgetTap(scope.interactive) { openApp(mapsAppIntent(context)) }
    val background: (@Composable BoxScope.(WidgetScale) -> Unit)? =
        if (colors.clear) null else { _ -> Box(Modifier.fillMaxSize().background(palette.card)) }
    IosWidgetCard(card, scope.family, background = background) { s ->
        val panelWidth = if (medium) 148.5f else 82.1f
        Box(Modifier.align(Alignment.TopStart).offset(s.pt(MAPS_PANEL_LEFT), s.pt(MAPS_PANEL_TOP))
            .size(s.pt(panelWidth), s.pt(MAPS_PANEL_HEIGHT)).background(palette.panel, RoundedCornerShape(s.pt(20f))))
        val search = uxText("搜索", "Search")
        Box(Modifier.align(Alignment.TopStart).offset(s.pt(MAPS_SEARCH_X - MAPS_DISC / 2), s.pt(MAPS_DISC_Y - MAPS_DISC / 2))
            .size(s.pt(MAPS_DISC)).background(palette.button, CircleShape)
            .semantics { contentDescription = search }
            .widgetTap(scope.interactive) { if (!startMapSearch(context)) openApp(mapsAppIntent(context)) }) {
            MagnifierGlyph(s)
        }
        if (medium) Box(Modifier.align(Alignment.TopStart).offset(s.pt(MAPS_SUGGESTION_X - MAPS_DISC / 2), s.pt(MAPS_DISC_Y - MAPS_DISC / 2))
            .size(s.pt(MAPS_DISC)).background(palette.disc, CircleShape))
    }
}

/** SF magnifyingglass in white, drawn in the button's own points (its top-left is the button's). */
@Composable
private fun MagnifierGlyph(s: WidgetScale) {
    Canvas(Modifier.fillMaxSize()) {
        val px = s.px(1f)
        val ox = MAPS_SEARCH_X - MAPS_DISC / 2; val oy = MAPS_DISC_Y - MAPS_DISC / 2
        val stroke = 3.5f
        val lens = Offset((45.9f - ox) * px, (107.5f - oy) * px)
        val r = (19.6f - stroke) / 2f
        drawCircle(Color.White, r * px, lens, style = Stroke(stroke * px))
        val d = r / kotlin.math.sqrt(2f)
        drawLine(Color.White, Offset(lens.x + (d + .6f) * px, lens.y + (d + .6f) * px),
            Offset((59.7f - ox) * px, (121.6f - oy) * px), strokeWidth = stroke * px, cap = StrokeCap.Round)
    }
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue
