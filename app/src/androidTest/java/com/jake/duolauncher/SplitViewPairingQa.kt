package com.jake.duolauncher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * QA helper for the Split View local-shell route on an emulator (r8-split): pairs 18Duo's own ADB key
 * with the emulator's wireless debugging, exactly as WirelessAdbSetup does on the phone, without
 * typing into the setup sheet. Skipped unless run with the pairing port and code:
 *
 *   am instrument -w -e class com.jake.duolauncher.SplitViewPairingQa -e port 37123 -e code 123456 \
 *      com.jake.duolauncher.core.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SplitViewPairingQa {
    @Test fun pairLocalKey() {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("port")?.toIntOrNull()
        val code = args.getString("code")
        assumeTrue(port != null && code != null && validAdbPairingCode(code))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences("duo18_local_adb", 0)), "18Duo@localhost")
        val ok = AdbPairingClient("127.0.0.1", port!!, code!!, key).use { it.start() }
        assertTrue("pairing refused", ok)
        // The key store writes with apply(); instrumentation kills the process right after the test.
        context.getSharedPreferences("duo18_local_adb", 0).edit().commit()
        Thread.sleep(1_500)
        assertTrue(context.getSharedPreferences("duo18_local_adb", 0).contains("adbkey"))
    }
}
