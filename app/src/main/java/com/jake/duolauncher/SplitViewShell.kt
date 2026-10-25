package com.jake.duolauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The precise Split View route: this phone's own ADB shell over loopback, with the key 18Duo
 * paired once in the wireless-debugging setup (WirelessAdbSetup). Android gives a third-party
 * Home no API to put a *chosen* task on a *chosen* side; WMShell's shell commands do exactly that
 * (`splitscreen moveToSideStage`, HyperOS `SoScSplitScreen moveToSoScSplitStage`).
 *
 * Only used when the owner already set up local debugging (paired key + WRITE_SECURE_SETTINGS).
 * If wireless debugging is off it is switched on for the moment of the split and restored
 * afterwards. Never connects anywhere but 127.0.0.1; only [SplitShellCommands] strings are sent;
 * output is parsed, never logged. Blocking: call from a worker thread.
 */
internal class SplitViewShell(private val context: Context) : AutoCloseable {
    private var client: AdbClient? = null
    private var restoreWireless = false
    private var openedAt = 0L

    val usable: Boolean get() = Companion.usable(context)

    /** Connects (enabling wireless debugging if needed); false if the shell cannot be reached now. */
    fun open(timeoutMs: Long = 3_000L): Boolean {
        if (client != null) return true
        if (!usable) return false
        val resolver = context.contentResolver
        val wasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1
        if (!wasOn) {
            if (!onWifi()) return false
            if (!runCatching { Settings.Global.putInt(resolver, ADB_WIFI, 1) }.getOrDefault(false)) return false
            restoreWireless = true
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val identity = AdbKey(PreferenceAdbKeyStore(context.getSharedPreferences(KEY_PREFS, 0)), "18Duo@localhost")
        // The port changes whenever wireless debugging restarts: try the last one, then ask mDNS.
        val ports = LinkedHashSet<Int>()
        lastPort.get().takeIf { it > 0 }?.let { ports += it }
        for (port in ports) if (connect(port, identity)) return true
        val found = discover(deadline - SystemClock.uptimeMillis()) ?: run { close(); return false }
        if (connect(found, identity)) return true
        close(); return false
    }

    private fun connect(port: Int, identity: AdbKey): Boolean = try {
        val c = AdbClient("127.0.0.1", port, identity)
        c.connect()
        client = c; lastPort.set(port); openedAt = SystemClock.uptimeMillis()
        true
    } catch (e: Exception) {
        Log.i(TAG, "local shell not reachable on the remembered port")
        false
    } catch (_: LinkageError) { false }

    private fun discover(timeoutMs: Long): Int? {
        if (timeoutMs <= 0) return null
        val latch = CountDownLatch(1)
        val port = AtomicInteger(0)
        val discovery = LocalAdbDiscovery(context) { pairing, p ->
            if (!pairing && p != null) { port.set(p); latch.countDown() }
        }
        discovery.start()
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { discovery.close() }
        return port.get().takeIf { it > 0 }
    }

    /** Runs one fixed command; null if the shell is gone. Output capped at 256 KB. */
    fun run(command: String): String? {
        val c = client ?: return null
        val out = ByteArrayOutputStream()
        return try {
            c.shellCommand(command) { if (out.size() < MAX_OUTPUT) out.write(it, 0, minOf(it.size, MAX_OUTPUT - out.size())) }
            out.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            Log.i(TAG, "local shell command failed")
            runCatching { c.close() }; client = null
            null
        }
    }

    override fun close() {
        runCatching { client?.close() }; client = null
        if (restoreWireless) {
            restoreWireless = false
            runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI, 0) }
        }
    }

    private fun onWifi(): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "DuoSplit"
        private const val ADB_WIFI = "adb_wifi_enabled"
        private const val KEY_PREFS = "duo18_local_adb"
        private const val MAX_OUTPUT = 256 * 1024
        private val lastPort = AtomicInteger(0)

        /** The owner paired 18Duo's key and granted the navigation permission (WirelessAdbSetup). */
        fun usable(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED &&
                context.getSharedPreferences(KEY_PREFS, 0).contains("adbkey") &&
                SplitViewSettings.localDebugging(context)
    }
}

/** User switches for Split View (Settings rows can be added by the integrator; defaults are Apple's). */
internal object SplitViewSettings {
    private const val PREFS = "duo18_core_preferences"
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("split_view_gesture", true)
    fun localDebugging(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("split_view_local_shell", true)
    /** QA only: force one route ("shell", "toggle"); empty = automatic. */
    fun forcedRoute(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("split_view_route", "").orEmpty()
}
