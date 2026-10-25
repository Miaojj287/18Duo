package com.jake.duolauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Review tool for the Core.17 polish pass: renders the iOS controls (switch, slider, range slider,
 * buttons, search field) and the Dock app picker sheet in light and dark into
 * Android/data/<pkg>/files/polish-gallery, and checks that the switch toggles through its row.
 */
class PolishGalleryTest {
    @get:Rule val compose = createComposeRule()

    private fun icon(color: Int): Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(0f, 0f, 96f, 96f, Paint().apply { this.color = color })
    }

    @Test fun renderControlsAndDockPicker() {
        val apps = listOf(
            AppEntry("a/.Mail", "Mail", icon(0xFF1E88E5.toInt())),
            AppEntry("b/.Notes", "Notes", icon(0xFFFDD835.toInt())),
            AppEntry("c/.Maps", "Maps", icon(0xFF43A047.toInt())),
            AppEntry("d/.Music", "Music", icon(0xFFE53935.toInt())),
        )
        var dark by mutableStateOf(true)
        var checked by mutableStateOf(false)
        var picker by mutableStateOf(false)
        compose.setContent {
            DuoTheme(dark) {
                Box(Modifier.size(425.dp, 622.dp).background(iosGroupedBackground()).testTag("gallery-root")) {
                    if (picker) CompositionLocalProvider(LocalPanelClose provides {}) {
                        DockAppPicker(apps, 3, onSelect = {}, onClear = {}, onLongClick = {},
                            canSelect = { it.label != "Music" })
                    } else Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        var slider by remember { mutableFloatStateOf(58f) }
                        var range by remember { mutableStateOf(10f..80f) }
                        IosInsetGroup("Controls") {
                            IosToggleRow("Show app names", checked, { checked = it }, icon = IosSymbols.textFormat,
                                tint = IosUi.blue, tag = "gallery-toggle")
                            IosSeparator()
                            IosSliderRow("Icon size", slider, 48f..68f, { slider = it })
                            IosSeparator(inset = 16.dp)
                            Box(Modifier.padding(16.dp)) {
                                IosRangeSlider(range, { range = it }, valueRange = 5f..170f, steps = 32, minimumSpan = 5f)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IosGlassCircleButton(IosSymbols.xmark, "Close", {})
                            IosGlassCircleButton(IosSymbols.chevronLeft, "Back", {})
                            IosGlassCircleButton(IosSymbols.checkmark, "Done", {}, prominent = true)
                            IosGlassCapsuleButton("Place", {}, prominent = true)
                        }
                        Spacer(Modifier.height(16.dp))
                        IosButton("Open Accessibility", {}, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        IosSearchField("", {}, "Search apps")
                    }
                }
            }
        }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "polish-gallery")
            .apply { mkdirs() }
        fun save(name: String) {
            compose.waitForIdle()
            val image = compose.onNodeWithTag("gallery-root").captureToImage().asAndroidBitmap()
            File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        save("controls-dark-off")
        compose.onNodeWithTag("gallery-toggle").performClick()
        compose.runOnIdle { assertTrue("the whole row toggles the switch", checked) }
        save("controls-dark-on")
        dark = false
        save("controls-light-on")

        picker = true
        save("dock-picker-light")
        assertTrue(File(out, "dock-picker-light.png").length() > 1000)
    }
}
