package com.jake.duolauncher

import android.content.pm.ActivityInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import android.app.UiAutomation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on cover/inner geometries; writes review images without changing the saved Home layout. */
@RunWith(AndroidJUnit4::class)
class VisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val model get() = ViewModelProvider(compose.activity)[LauncherModel::class.java]
    @Before fun home() {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-root").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("first-run-setup").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        if (compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("settings-close").performClick()
        compose.waitUntil(10_000) { !model.state.value.loading }
    }
    private fun openSettings() {
        compose.onNodeWithTag("home-page-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("editor-settings").performClick(); compose.onNodeWithTag("jiggle-menu-settings").performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        device.takeScreenshot(java.io.File(compose.activity.getExternalFilesDir(null), "review-$name.png"))
    }
    @Test fun allMenuDestinationsAndThemesRemainUsable() {
        val originalLabels = model.state.value.labels
        try {
            openSettings(); capture("settings")
            compose.onNodeWithTag("settings-layout").performScrollTo().performClick()
            compose.onNodeWithTag("label-switch").performScrollTo().performClick()
            compose.runOnIdle { assertEquals(!originalLabels, model.state.value.labels) }
            capture("icons")
            compose.runOnIdle { model.setLabels(originalLabels) }
            compose.onNodeWithTag("settings-more-back").performClick()
            compose.onNodeWithTag("settings-entry-${R.string.editor_wallpaper}").performScrollTo().performClick()
            compose.onNodeWithTag("wallpaper-preview-card").assertExists(); capture("wallpaper")
            compose.onNodeWithTag("settings-more-back").performClick()
            compose.onNodeWithTag("setup-entry").performScrollTo().performClick(); capture("gestures")
            compose.onNodeWithTag("settings-more-back").performClick()
            compose.onNodeWithTag("settings-standby").performScrollTo().assertExists(); capture("standby")
            compose.onNodeWithTag("settings-close").performClick()
            openSettings()
            compose.onNodeWithTag("settings-entry-${R.string.editor_widgets}").performScrollTo().performClick()
            compose.onNodeWithTag("visual-widget-picker").assertIsDisplayed()
            compose.waitUntil(8_000) { compose.onAllNodesWithTag("widget-catalog-list").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("widget-native-header").assertExists()
            capture("widgets")
            device.pressBack()
        } finally {
            compose.runOnIdle { model.setLabels(originalLabels) }
        }
    }
    @Test fun menuIsExposedToPlatformAccessibilityAndBackClosesIt() {
        openSettings(); compose.waitForIdle(); Thread.sleep(500)
        val title = uxText("桌面与图标", "Home & icons")
        // Verify Android accessibility nodes as well as Compose's own semantics tree.
        assertNotNull("Settings rows must be discoverable through Android accessibility",
            device.wait(Until.findObject(By.text(title)), 3_000))
        device.pressBack()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun nativeWirelessPanelValidatesInputWithoutChangingNavigation() {
        if (InstrumentationRegistry.getArguments().getString("nativeQa") == "true")
            compose.waitUntil(40_000) { SystemShadeAccessibilityService.isConnected() }
        else assumeTrue(SystemShadeAccessibilityService.isConnected())
        val activity = compose.activity
        val mode = android.provider.Settings.Global.getInt(activity.contentResolver, "force_fsg_nav_bar", 0)
        compose.runOnIdle { assertTrue(SystemShadeAccessibilityService.showWirelessSetup()) }
        if (activity.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            device.wait(Until.findObject(By.text(activity.getString(R.string.wireless_reconnect))), 3_000)?.click()
        val pair = device.wait(Until.findObject(By.text(activity.getString(R.string.wireless_pair_authorize))), 3_000)
        assertNotNull(pair)
        val bounds = pair!!.visibleBounds
        assertTrue("Primary pairing action remains inside the display", bounds.width() > 0 && bounds.bottom <= device.displayHeight)
        pair.click()
        assertTrue(device.wait(Until.hasObject(By.text(activity.getString(R.string.wireless_invalid_input))), 3_000))
        device.dumpWindowHierarchy(java.io.File(activity.getExternalFilesDir(null), "review-wireless.xml"))
        device.findObject(By.text(activity.getString(R.string.close_menu))).click()
        assertEquals(mode, android.provider.Settings.Global.getInt(activity.contentResolver, "force_fsg_nav_bar", 0))
    }
}
