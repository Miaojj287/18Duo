package com.jake.duolauncher

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StandbyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun liveStandbyPagesAreUsableAndKeepScreenOnOnlyWhileVisible() {
        val visible = mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (visible.value) StandbyScreen(onExit = { visible.value = false })
        }
        // Lit only on external power (code review P1-3); on battery Android's timeout and lock apply.
        val plugged = standbyOnExternalPower(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.runOnIdle { assertEquals(standbyKeepsScreenOn(plugged), view.keepScreenOn) }
        // Chrome is shown on entry, so the dots are live.
        compose.onNodeWithTag("standby-page-${StandbyFace.CALENDAR.ordinal}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("standby-full-calendar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("standby-page-${StandbyFace.PHOTO.ordinal}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("standby-photo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("standby-view-${StandbyFace.PHOTO.ordinal}").assertIsDisplayed()
        compose.onNodeWithTag("standby-exit").performClick()
        compose.onNodeWithTag("standby-root").assertDoesNotExist()
        compose.runOnIdle { assertFalse(view.keepScreenOn) }
    }

    /** Code review P2-8d: the clock ticks every second, but only the faces' draw scopes read it. */
    @Test fun clockTicksRedrawWithoutRecomposing() {
        compose.mainClock.autoAdvance = false
        compose.setContent { StandbyScreen(onExit = {}) }
        fun changes() = Recomposer.runningRecomposers.value.sumOf { it.changeCount }
        // Settle the entry, the chrome fade and the photo load (real-time IO) first.
        var settled = 0
        var last = changes()
        for (attempt in 0 until 30) {
            compose.mainClock.advanceTimeBy(1_000L)
            Thread.sleep(150L)
            val now = changes()
            settled = if (now == last) settled + 1 else 0
            last = now
            if (settled >= 3 && attempt * 1_000L > STANDBY_CHROME_MILLIS) break
        }
        val before = changes()
        repeat(5) { compose.mainClock.advanceTimeBy(1_000L) }
        assertEquals("a clock tick must not recompose StandBy", before, changes())
    }

    /** Interaction review S1: in a window that stays portrait StandBy turns its landscape canvas to fill it. */
    @Test fun portraitWindowGetsARotatedLandscapeCanvas() {
        val exits = mutableStateOf(0)
        compose.setContent {
            // Cover proportions (425 × 622 dp) scaled to fit any test screen.
            Box(Modifier.size(300.dp, 439.dp).testTag("portrait-window")) {
                StandbyScreen(onExit = { exits.value++ }, target = android.view.Surface.ROTATION_90)
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        val canvas = compose.onNodeWithTag("standby-root").fetchSemanticsNode()
        // Laid out landscape (439 × 300 dp) …
        with(compose.density) {
            assertEquals(439.dp.roundToPx().toFloat(), canvas.size.width.toFloat(), 2f)
            assertEquals(300.dp.roundToPx().toFloat(), canvas.size.height.toFloat(), 2f)
        }
        // … and turned so it covers the whole portrait window (not its top third).
        val window = compose.onNodeWithTag("portrait-window").fetchSemanticsNode().boundsInRoot
        val bounds = canvas.boundsInRoot
        assertEquals(window.width, bounds.width, 2f)
        assertEquals(window.height, bounds.height, 2f)
        // Its controls still work through the rotation.
        compose.onNodeWithTag("standby-exit").performClick()
        compose.runOnIdle { assertEquals(1, exits.value) }
    }

    @Test fun chromeFadesAndATapBringsItBack() {
        compose.mainClock.autoAdvance = false
        compose.setContent { StandbyScreen(onExit = {}) }
        compose.mainClock.advanceTimeBy(STANDBY_CHROME_MILLIS + 1_000L)
        // Hidden chrome must not swallow a tap meant to reveal it.
        compose.onNodeWithTag("standby-exit").assertIsNotEnabled()
        compose.onNodeWithTag("standby-root").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithTag("standby-exit").assertIsEnabled()
    }

    @Test fun analogDialIsSquareAndAvoidsACutoutOnTheTrailingEdge() {
        val frame = StandbyFrame(ZonedDateTime.parse("2026-09-09T14:46:26+08:00[Asia/Shanghai]"), Locale.US, false)
        compose.setContent {
            Box(Modifier.size(622.dp, 425.dp)) { StandbyAnalogFace(frame, StandbyStyles.analog(0, night = false)) }
        }
        val pixels = compose.onNodeWithTag("standby-analog").captureToImage().toPixelMap()
        val layout = standbyAnalogLayout(pixels.width.toFloat(), pixels.height.toFloat(), 5)
        val dial = layout.clockBounds
        assertEquals(dial.width, dial.height, 1f)
        // Something white is drawn at the 3 o'clock tick, right where the layout puts the dial edge.
        val y = dial.center.y.toInt()
        val lit = (dial.right.toInt() - 12 until dial.right.toInt() - 2).count { pixels[it, y].red > .8f }
        assertTrue("3 o'clock tick must reach the dial edge", lit > 4)
        // A right-edge cutout moves only the dial, by the minimum amount.
        val cutout = Rect(pixels.width - 40f, y - 30f, pixels.width.toFloat(), y + 30f)
        val moved = standbyAnalogLayout(pixels.width.toFloat(), pixels.height.toFloat(), 5, listOf(cutout), 6f)
        assertTrue(moved.clockBounds.right <= cutout.left - 6f + .5f)
        assertEquals(layout.headerLeft, moved.headerLeft, .01f)
        assertEquals(layout.calendar, moved.calendar)
    }

    @Test fun nightModeDrawsOnlyRedLight() {
        val frame = StandbyFrame(ZonedDateTime.parse("2026-04-01T09:41:00+08:00[Asia/Shanghai]"), Locale.US, false)
        val current = mutableStateOf(StandbyFace.DIGITAL)
        compose.setContent { Box(Modifier.fillMaxSize()) { StandbyFaceContent(current.value, 0, frame, true, emptyList()) } }
        for ((face, tag) in listOf(StandbyFace.DIGITAL to "standby-digital", StandbyFace.CALENDAR to "standby-full-calendar",
                StandbyFace.ANALOG to "standby-analog")) {
            compose.runOnIdle { current.value = face }
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            var red = 0
            // Edges can carry the test activity's own system-bar scrims (DecorView), so sample the interior.
            for (y in pixels.height / 8 until pixels.height * 7 / 8 step 3) for (x in pixels.width / 12 until pixels.width * 5 / 6 step 3) {
                val c = pixels[x, y]
                assertTrue("$face has green/blue light at $x,$y: $c", c.green < .12f && c.blue < .12f)
                if (c.red > .5f) red++
            }
            assertTrue("$face must still draw marks", red > 50)
        }
    }
}
