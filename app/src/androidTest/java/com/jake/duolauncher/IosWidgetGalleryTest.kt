package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Review tool for 18Duo's iOS widgets: draws every registered widget at the cover's real card
 * sizes (small 143 dp, medium 303 × 143 dp, large 303 × 324 dp) on the dune wallpaper, in Light,
 * Dark and Clear, as placed (live data) and as the gallery preview, into
 * Android/data/<pkg>/files/ios-widgets/<app>-<tone>-<live|preview>.png.
 */
class IosWidgetGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderEveryWidgetInEveryTone() {
        // `-e locale zh-CN` renders the Chinese UI (uxText and the date formats follow the default locale).
        val previous = java.util.Locale.getDefault()
        InstrumentationRegistry.getArguments().getString("locale")?.let { java.util.Locale.setDefault(java.util.Locale.forLanguageTag(it)) }
        try { render() } finally { java.util.Locale.setDefault(previous) }
    }

    private fun render() {
        var tone by mutableStateOf(WidgetTone.LIGHT)
        var preview by mutableStateOf(false)
        var app by mutableStateOf(NativeWidgetKind.CLOCK)
        var family by mutableStateOf(NativeWidgetSize.SMALL)
        compose.setContent {
            DuoTheme(tone == WidgetTone.DARK) {
                CompositionLocalProvider(LocalWidgetToneOverride provides tone) {
                    val context = LocalContext.current
                    val wallpaper = remember { bundledDefaultWallpaper(context) }
                    // The emulated cover is 425 × 622 dp: one family per sheet keeps every card on screen.
                    Box(Modifier.wrapContentSize().testTag("widget-sheet")) {
                        wallpaper?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                        val specs = iosWidgetSpecs.filter { it.app == app && it.family == family }
                        val perRow = if (family == NativeWidgetSize.SMALL) 2 else 1
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Up to 6 smalls (3 rows), 3 mediums or 1 large fit the 622 dp cover.
                            specs.take(when (family) { NativeWidgetSize.SMALL -> 6; NativeWidgetSize.MEDIUM -> 3; else -> 1 })
                                .chunked(perRow).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    row.forEach { spec ->
                                        NativeWidget(spec.id, Modifier.size(cardWidth(family).dp, cardHeight(family).dp), preview = preview)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ios-widgets")
            .apply { mkdirs() }
        for (kind in NativeWidgetKind.entries) for (f in NativeWidgetSize.entries) {
            val specs = iosWidgetSpecs.filter { it.app == kind && it.family == f }
            if (specs.isEmpty()) continue
            for (t in WidgetTone.entries) for (p in listOf(false, true)) {
                app = kind; family = f; tone = t; preview = p
                compose.waitForIdle()
                Thread.sleep(250)
                val image = compose.onNodeWithTag("widget-sheet").captureToImage().asAndroidBitmap()
                val file = File(out, "${kind.key}-${f.key}-${t.name.lowercase()}-${if (p) "preview" else "live"}.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(file.length() > 1000)
            }
        }
    }

    /** CJK falls back to the system face: its lines must still keep the widget's point line height. */
    @Test fun widgetTextKeepsItsLineHeightForChinese() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val s = remember { WidgetScale(1f, density) }
            Column {
                WidgetText("产品评审", s, 13f, Modifier.testTag("cjk"), lineHeight = 16f)
                WidgetText("Review", s, 13f, Modifier.testTag("latin"), lineHeight = 16f)
            }
        }
        val cjk = compose.onNodeWithTag("cjk").fetchSemanticsNode().size.height
        val latin = compose.onNodeWithTag("latin").fetchSemanticsNode().size.height
        val expected = 16f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        android.util.Log.i("18DuoWidgetText", "cjk=$cjk latin=$latin expected=$expected")
        assertTrue("cjk=$cjk expected=$expected", kotlin.math.abs(cjk - expected) <= 2f)
        assertTrue("latin=$latin expected=$expected", kotlin.math.abs(latin - expected) <= 2f)
    }

    private fun cardWidth(family: NativeWidgetSize) = if (family == NativeWidgetSize.SMALL) 143.2f else 303f
    private fun cardHeight(family: NativeWidgetSize) = when (family) {
        NativeWidgetSize.SMALL, NativeWidgetSize.MEDIUM -> 143.2f
        NativeWidgetSize.LARGE -> 324.2f
    }
}
