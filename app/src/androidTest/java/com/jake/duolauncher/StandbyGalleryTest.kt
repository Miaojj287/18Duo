package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Review tool: renders every StandBy face at the Xiaomi cover's landscape size (1712×1168 px) at the moments
 * Apple shows (owner photo 4: Wed Sep 9 2:46:26; standby.mp4: Wed Apr 1 9:41) into
 * Android/data/<pkg>/files/standby-gallery for side-by-side comparison.
 */
class StandbyGalleryTest {
    @get:Rule val compose = createComposeRule()

    private data class Case(val name: String, val face: StandbyFace, val variant: Int, val time: String,
        val locale: Locale = Locale.US, val night: Boolean = false, val cutoutRight: Boolean? = null,
        val use24h: Boolean = false, val book: Boolean = false)

    @Test fun renderGallery() {
        val photo = 0
        val april = "2026-04-01T09:41:02+08:00[Asia/Shanghai]"
        val duo = "2026-10-26T01:20:33+08:00[Asia/Shanghai]"
        val zh = Locale.SIMPLIFIED_CHINESE
        val cases = listOf(
            // Round 24: the owner's iPhone Duo photos (Mon Oct 26 2026, 01:20, Shenzhen).
            Case("r24-widgets", StandbyFace.WIDGETS, 0, duo, zh, use24h = true),
            Case("r24-analog", StandbyFace.ANALOG, 0, duo, zh, use24h = true),
            Case("r24-calendar", StandbyFace.CALENDAR, 0, duo, zh, use24h = true),
            Case("r24-calendar-white", StandbyFace.CALENDAR, 1, duo, zh, use24h = true),
            Case("r24-calendar-black", StandbyFace.CALENDAR, 2, duo, zh, use24h = true),
            Case("r24-weather", StandbyFace.WEATHER, 0, duo, zh, use24h = true),
            Case("r24-weather-en", StandbyFace.WEATHER, 0, april),
            Case("r24-widgets-night", StandbyFace.WIDGETS, 0, duo, zh, night = true, use24h = true),
            Case("r24-weather-night", StandbyFace.WEATHER, 0, duo, zh, night = true, use24h = true),
            Case("r24-calendar-night", StandbyFace.CALENDAR, 0, duo, zh, night = true, use24h = true),
            Case("r24-book", StandbyFace.ANALOG, 0, duo, zh, use24h = true, book = true),
            Case("r24-book-night", StandbyFace.ANALOG, 0, duo, zh, night = true, use24h = true, book = true),
            Case("analog-black-photo4", StandbyFace.ANALOG, 0, "2026-09-09T14:46:26+08:00[Asia/Shanghai]"),
            Case("analog-yellow-video", StandbyFace.ANALOG, 1, april),
            Case("analog-white", StandbyFace.ANALOG, 2, april),
            Case("digital-green-video", StandbyFace.DIGITAL, 0, april),
            Case("digital-black-green", StandbyFace.DIGITAL, 1, april),
            Case("digital-orange", StandbyFace.DIGITAL, 2, april),
            Case("digital-black-white-24h", StandbyFace.DIGITAL, 3, "2026-04-01T22:48:00+08:00[Asia/Shanghai]", use24h = true),
            Case("digital-widest-cutout", StandbyFace.DIGITAL, 0, "2026-04-01T10:08:00+08:00[Asia/Shanghai]", cutoutRight = false),
            Case("calendar-light-video", StandbyFace.CALENDAR, 0, april),
            Case("calendar-light-two-digit", StandbyFace.CALENDAR, 0, "2026-10-20T13:05:00+08:00[Asia/Shanghai]"),
            // Two-digit dates in the first and last columns stay inside the grid (visual review P1-8).
            Case("calendar-two-digit-first-column", StandbyFace.CALENDAR, 0, "2026-10-11T13:05:00+08:00[Asia/Shanghai]"),
            Case("calendar-two-digit-last-column", StandbyFace.CALENDAR, 0, "2026-10-31T13:05:00+08:00[Asia/Shanghai]"),
            Case("calendar-two-digit-28", StandbyFace.CALENDAR, 0, "2026-10-28T13:05:00+08:00[Asia/Shanghai]"),
            Case("zh-calendar-two-digit", StandbyFace.CALENDAR, 0, "2026-10-20T13:05:00+08:00[Asia/Shanghai]", Locale.SIMPLIFIED_CHINESE),
            Case("calendar-dark", StandbyFace.CALENDAR, 1, april),
            Case("photo-bundled", StandbyFace.PHOTO, photo, april),
            Case("night-analog", StandbyFace.ANALOG, 0, "2026-09-09T02:46:26+08:00[Asia/Shanghai]", night = true),
            Case("night-digital", StandbyFace.DIGITAL, 0, "2026-09-09T02:46:26+08:00[Asia/Shanghai]", night = true),
            Case("night-calendar", StandbyFace.CALENDAR, 0, "2026-09-09T02:46:26+08:00[Asia/Shanghai]", night = true),
            Case("night-photo", StandbyFace.PHOTO, photo, "2026-09-09T02:46:26+08:00[Asia/Shanghai]", night = true),
            Case("zh-analog", StandbyFace.ANALOG, 0, "2026-09-09T14:46:26+08:00[Asia/Shanghai]", Locale.SIMPLIFIED_CHINESE),
            Case("zh-digital", StandbyFace.DIGITAL, 0, april, Locale.SIMPLIFIED_CHINESE),
            Case("zh-calendar", StandbyFace.CALENDAR, 0, april, Locale.SIMPLIFIED_CHINESE),
            Case("cutout-right-analog", StandbyFace.ANALOG, 0, "2026-09-09T14:46:26+08:00[Asia/Shanghai]", cutoutRight = true),
            Case("cutout-left-analog-6rows", StandbyFace.ANALOG, 0, "2026-08-31T14:46:26+08:00[Asia/Shanghai]", cutoutRight = false),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "standby-gallery").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val photos = listOfNotNull(bundledDefaultWallpaper(context)?.asImageBitmap())
        val current = mutableStateOf(cases.first())
        var recorder: GraphicsLayer? = null
        compose.setContent {
            // Recorded off-window: the full 1712 px width survives side navigation bars in a test activity.
            val layer = rememberGraphicsLayer()
            SideEffect { recorder = layer }
            val case = current.value
            val density = LocalDensity.current
            // Cover landscape: 1712 × 1168 px whatever the test window's density; the inner in the book pose 2364 × 1672.
            val width = with(density) { (if (case.book) 2364f else 1712f).toDp() }
            val height = with(density) { (if (case.book) 1672f else 1168f).toDp() }
            val nowMs = ZonedDateTime.parse(case.time).toInstant().toEpochMilli()
            val sample = sampleWeather(nowMs).let { if (case.locale.language == "zh")
                it.copy(isDay = false, condition = WeatherCondition.MOSTLY_CLEAR, temperature = 25, high = 31, low = 24) else it }
            val weather = StandbyWeather(sample, if (case.locale.language == "zh") "深圳市南山区" else "Cupertino",
                currentLocation = true, configured = true, loading = false)
            // Xiaomi cover camera: 54 px hole whose cutout rect reaches 126 px in from the short edge, mid-height.
            val cutouts = when (case.cutoutRight) {
                true -> listOf(Rect(1712f - 126f, 557f, 1712f, 611f))
                false -> listOf(Rect(0f, 557f, 126f, 611f))
                null -> emptyList()
            }
            val frame = StandbyFrame(ZonedDateTime.parse(case.time), case.locale, case.use24h, cutouts,
                cutoutMargin = with(density) { 6.dp.toPx() })
            Box(Modifier.requiredSize(width, height).testTag("gallery").drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) {
                if (case.book) androidx.compose.foundation.layout.Row(Modifier.requiredSize(width, height)) {
                    Box(Modifier.requiredSize(width / 2, height)) { StandbyBookClockPage(frame, StandbyStyles.analog(0, case.night)) }
                    androidx.compose.foundation.Image(photos.first(), null, Modifier.requiredSize(width / 2, height),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else StandbyFaceContent(case.face, case.variant, frame, case.night, photos, weather)
                if (case.cutoutRight != null) androidx.compose.foundation.Canvas(Modifier.requiredSize(width, height)) {
                    val r = 27f
                    val c = if (case.cutoutRight) Offset(1712f - 126f + r, 584f) else Offset(126f - r, 584f)
                    drawCircle(Color(0xFF202020), r, c)
                    drawRect(Color.Red.copy(alpha = .35f), Offset(cutouts[0].left, cutouts[0].top), cutouts[0].size)
                }
            }
        }
        for (case in cases) {
            compose.runOnIdle { current.value = case }
            compose.waitForIdle()
            val image = runBlocking { withContext(Dispatchers.Main) { recorder!!.toImageBitmap() } }.asAndroidBitmap()
            File(dir, "${case.name}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(image.width > 0 && image.height > 0)
        }
    }
}
