package com.jake.duolauncher

import android.app.UiAutomation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit physical QA. No ActivityScenario: restarting a bound service also restarts its host. */
@RunWith(AndroidJUnit4::class)
class NativeNavigationUiTest {
    @Test fun wirelessValidationPreservesNavigationAndRecoveryIsReachable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = "${context.packageName}/com.jake.duolauncher.SystemShadeAccessibilityService"
        assumeTrue(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').contains(component))
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val before = Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar", 0)
        // Instrumentation force-stops its target. Reconnect the already enabled service in system UI.
        if (!SystemShadeAccessibilityService.isConnected()) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val downloaded = device.wait(Until.findObject(By.text("已下载的应用")), 5_000)
            assertNotNull(downloaded); downloaded!!.click()
            val entry = device.wait(Until.findObject(By.text(context.getString(R.string.shade_service_label))), 5_000)
            assertNotNull(entry); entry!!.click()
            val toggle = device.wait(Until.findObject(By.clazz("android.widget.Switch")), 5_000)
            assertNotNull(toggle); assertTrue(toggle!!.isChecked); toggle.click()
            device.wait(Until.findObject(By.text("关闭")), 3_000)?.click()
            device.wait(Until.findObject(By.clazz("android.widget.Switch")), 3_000)!!.click()
        }
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (!SystemShadeAccessibilityService.isConnected() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("The enabled navigation service reconnects", SystemShadeAccessibilityService.isConnected())
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("duo_destination", "home"))
        SystemClock.sleep(500)
        instrumentation.runOnMainSync { assertTrue(SystemShadeAccessibilityService.showWirelessSetup()) }
        device.wait(Until.findObject(By.text(context.getString(R.string.wireless_reconnect))), 3_000)?.click()
        val pair = device.wait(Until.findObject(By.text(context.getString(R.string.wireless_pair_authorize))), 3_000)
        assertNotNull(pair); pair!!.click()
        assertTrue(device.wait(Until.hasObject(By.text(context.getString(R.string.wireless_invalid_input))), 3_000))
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "native-wireless-validated.xml"))
        device.findObject(By.text(context.getString(R.string.close_menu))).click()
        instrumentation.runOnMainSync { assertTrue(SystemShadeAccessibilityService.showNavigationRecovery()) }
        assertNotNull(device.wait(Until.findObject(By.text(context.getString(R.string.navigation_restore))), 3_000))
        device.pressBack()
        assertEquals(before, Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar", 0))
    }
}
