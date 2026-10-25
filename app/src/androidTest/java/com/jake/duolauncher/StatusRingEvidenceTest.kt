package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Evidence for owner item 5: draws the real status ring (StatusRail) with its outer diameter at
 * exactly 354 px — the size of Apple's ring in status-bar.mp4 at 1920×1080 — on black, for every
 * Wi-Fi level, into Android/data/<pkg>/files/status-ring/. tools compare it with Apple's frame
 * (evidence/r3-chrome/wifi). Also checks that the drawing stays inside its box and is symmetric.
 */
class StatusRingEvidenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun renderRingAtAppleVideoScale() {
        var status by mutableStateOf(DeviceStatus(battery = 100, wifiConnected = true, wifiLevel = 4, cellularLevel = 4))
        var ringPx = 0
        compose.setContent {
            val density = LocalDensity.current
            val diameter = with(density) { 354.toDp() }
            ringPx = with(density) { diameter.roundToPx() }
            val duo = DuoStatus(timeCenterX = 0f, timeCenterY = 0f, ringCenterX = 0f, ringCenterY = 60f,
                ringDiameter = diameter.value, timeSize = 40f, horizontal = false)
            // The dots hang just below the ring's box (0.476 D + 0.05 D), so the host is captured.
            Box(Modifier.testTag("ring-host").background(Color.Black).padding(24.dp)) {
                Column {
                    StatusRail(status, Modifier.width(diameter), iconSize = diameter,
                        geometry = duo.railGeometry(1f, density.density))
                }
            }
        }
        val out = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "status-ring")
            .apply { mkdirs() }
        fun save(name: String): Bitmap {
            compose.waitForIdle()
            val host = compose.onNodeWithTag("ring-host").captureToImage().asAndroidBitmap()
            File(out, "$name.png").outputStream().use { host.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return compose.onNodeWithTag("status-connectivity-ring", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        }
        val full = save("ring-full")
        assertEquals(ringPx, full.width)
        // Mirror symmetry about the vertical axis (the glyph and the dots are centred under the gap).
        var asymmetric = 0
        for (y in 0 until full.height step 2) for (x in 0 until full.width / 2 step 2) {
            val a = full.getPixel(x, y) and 0xFF
            val b = full.getPixel(full.width - 1 - x, y) and 0xFF
            if (kotlin.math.abs(a - b) > 60) asymmetric++
        }
        assertTrue("ring drawing is mirror-symmetric ($asymmetric)", asymmetric < 40)
        for (level in 0..3) {
            status = status.copy(wifiLevel = level, cellularLevel = level, battery = 25 * level + 10)
            save("ring-level-$level")
        }
        status = DeviceStatus(battery = 62, wifiConnected = true, wifiLevel = null, cellularLevel = 2)
        save("ring-unknown-wifi")
    }
}
