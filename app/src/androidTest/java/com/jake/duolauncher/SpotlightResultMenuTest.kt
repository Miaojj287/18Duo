package com.jake.duolauncher

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Round 4 (review r3): a long press on a Spotlight app result opens an iOS context menu instead of launching it. */
@RunWith(AndroidJUnit4::class)
class SpotlightResultMenuTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[LauncherModel::class.java]
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Before fun home() {
        compose.waitUntil(15_000) { exists("launcher-root") }
        if (exists("first-run-setup")) compose.onNodeWithTag("setup-explore").performClick()
        if (exists("settings-close")) compose.onNodeWithTag("settings-close").performClick()
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    @Test fun longPressingAResultShowsAMenuInsteadOfLaunching() {
        val app = model.state.value.apps.first { it.available && it.label.length >= 3 }
        compose.onNodeWithTag("search").performClick()
        compose.waitUntil(5_000) { exists("spotlight-field") }
        compose.onNodeWithTag("spotlight-field").performTextInput(app.label.take(3))
        compose.waitUntil(5_000) { exists("spotlight-top-hit") }
        compose.onNodeWithTag("spotlight-top-hit").performTouchInput { longClick(durationMillis = 1_200) }
        compose.waitUntil(5_000) { exists("spotlight-app-menu") }
        compose.waitForIdle(); Thread.sleep(400)
        assertEquals("nothing launched", compose.activity.packageName, device.currentPackageName)
        assertTrue(exists("spotlight-menu-share") && exists("spotlight-menu-info") && exists("spotlight"))
        val dir = java.io.File(compose.activity.getExternalFilesDir(null), "spotlight-menu").apply { mkdirs() }
        device.takeScreenshot(java.io.File(dir, "spotlight-result-menu.png"))
        // Back closes the menu first; Search stays open with its query.
        device.pressBack(); Thread.sleep(600); compose.waitForIdle()
        compose.waitUntil(5_000) { !exists("spotlight-app-menu") }
        if (exists("spotlight")) { device.pressBack(); Thread.sleep(600); compose.waitForIdle() }
        compose.waitUntil(5_000) { !exists("spotlight") || run { device.pressBack(); Thread.sleep(400); false } }
    }
}
