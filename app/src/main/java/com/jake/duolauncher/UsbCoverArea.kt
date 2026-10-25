package com.jake.duolauncher

import android.content.Context
import java.net.Socket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Authenticated local connection to the user-started USB helper. No shell text or
 * arbitrary command execution can be sent; its clock protocol is COVER_STANDBY/LOCK_CLOCK/STOP/PING.
 * Each connection authenticates both parties with
 * a fresh HMAC challenge. */
internal class UsbCoverArea(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val operation = AtomicReference("PING")
    private val angles = AtomicReference(StandbyAngles())
    @Volatile private var socket: Socket? = null
    @Volatile private var started = false
    @Volatile private var generation = 0
    @Volatile var available = false
        private set
    @Volatile var selected = false
        private set
    @Volatile var message: String? = null
        private set
    fun start() {
        if (started) return
        operation.set("PING")
        started = true
        val epoch = ++generation
        executor.execute {
            while (started && generation == epoch) {
                try {
                    val token = context.getSharedPreferences("usb_cover_pairing", 0).getString("token", null)
                    if (token == null) { Thread.sleep(2000); continue }
                    Socket().use { connection ->
                        socket = connection
                        connection.connect(InetSocketAddress("127.0.0.1", 48632), 2000)
                        connection.soTimeout = 5000
                        val input = BufferedReader(InputStreamReader(connection.inputStream))
                        val output = PrintWriter(connection.outputStream, true)
                        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                        fun proof(text: String): String {
                            val mac = Mac.getInstance("HmacSHA256")
                            mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
                            return Base64.encodeToString(mac.doFinal(text.toByteArray()), Base64.NO_WRAP)
                        }
                        output.println(nonce)
                        val remote = (input.readLine() ?: error("No proof")).split(':', limit = 2)
                        check(remote.size == 2 && remote[0].matches(Regex("[a-f0-9]{64}")))
                        val challenge = "$nonce:${remote[0]}"
                        check(MessageDigest.isEqual(remote[1].toByteArray(), proof("server:$challenge").toByteArray()))
                        output.println(proof("client:$challenge"))
                        check(input.readLine() == "READY")
                        if (!started || generation != epoch) return@use
                        available = true; android.util.Log.i("18DuoUsbCover", "paired helper connected"); notifyChanged()
                        var sentAngles: StandbyAngles? = null
                        while (started && generation == epoch) {
                            val configured = angles.get()
                            if (sentAngles != configured) {
                                output.println("CONFIG:${configured.minimum}:${configured.maximum}")
                                check(input.readLine() == "READY")
                                sentAngles = configured
                            }
                            output.println(operation.getAndSet("PING"))
                            val result = input.readLine() ?: error("Disconnected")
                            val before = selected
                            selected = result == "ACTIVE"
                            message = if (result.startsWith("ERROR:")) result.removePrefix("ERROR:") else null
                            if (before != selected || message != null) notifyChanged()
                            Thread.sleep(400)
                        }
                    }
                } catch (_: Exception) {
                    if (generation == epoch) {
                        val hadConnection = available || selected
                        available = false; selected = false
                        if (hadConnection) notifyChanged()
                    }
                } finally { if (generation == epoch) socket = null }
                if (started && generation == epoch) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@execute }
                }
            }
        }
    }
    fun configure(angles: StandbyAngles) { this.angles.set(angles) }
    fun requestLockedClock() { if (available) operation.set("LOCK_CLOCK") }
    fun requestCoverStandby() { if (available) operation.set("COVER_STANDBY") }
    fun release() { if (selected || operation.get() == "LOCK_CLOCK" || operation.get() == "COVER_STANDBY") operation.set("STOP") }
    fun stop() {
        if (!started) return
        started = false
        ++generation
        operation.set("STOP")
        try { socket?.close() } catch (_: Exception) { }
        available = false; selected = false
    }
    fun destroy() { stop(); executor.shutdownNow() }
    private fun notifyChanged() { main.post { changed() } }
}
