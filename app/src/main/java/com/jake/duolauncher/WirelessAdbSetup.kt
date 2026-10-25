package com.jake.duolauncher

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** User-operated setup panel. Accessibility is used ONLY to display our own UI, not read Settings. */
internal class WirelessAdbSetup(private val service: AccessibilityService, private val enable: () -> Unit) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var client: AdbClient? = null
    @Volatile private var pairingClient: AdbPairingClient? = null
    private var key: AdbKey? = null // created only on worker; private key encrypted with Android Keystore
    private var dialog: AlertDialog? = null
    private var outcomeDialog: AlertDialog? = null
    private var bubble: TextView? = null
    private var discovery: LocalAdbDiscovery? = null
    private var pairPort: Int? = null
    @Volatile private var connectPort: Int? = null
    private var pairInput: EditText? = null
    private var connectInput: EditText? = null
    private var status: TextView? = null
    private val expiry = Runnable { close() }

    private fun str(id: Int) = service.getString(id)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun allowed() = wirelessSetupAllowed(
        service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
        SystemShadeAccessibilityService.isConnected())
    private fun granted() = service.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun show() {
        if (closed || dialog?.isShowing == true) return
        if (!allowed()) {
            showNativeBanner(service, str(R.string.wireless_prerequisites), long = true)
            return
        }
        main.removeCallbacks(expiry); main.postDelayed(expiry, 10 * 60_000L)
        removeBubble()
        val uiContext = nativeDialogContext(service)
        val dark = DuoAppearanceRuntime.dark
        val authorized = granted()
        val ink = NativeIos.ink(dark)
        val secondary = NativeIos.secondary(dark)
        val blue = NativeIos.blue(dark)
        val regular = NativeIos.font(uiContext, 400)
        val semibold = NativeIos.font(uiContext, 600)
        val content = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(12)) }
        fun text(id: Int, size: Float = 15f) = TextView(uiContext).apply {
            text = str(id); setTextColor(secondary); textSize = size; regular?.let { typeface = it }
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, dp(6)); content.addView(this)
        }
        fun section(id: Int) = TextView(uiContext).apply {
            text = str(id); setTextColor(ink); textSize = 17f
            semibold?.let { typeface = it } ?: setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(2))
            content.addView(this)
        }
        // iOS 26 capsule buttons (filled blue for the primary action), no Material ripple.
        fun button(id: Int, primary: Boolean = false, action: () -> Unit) = Button(uiContext).apply {
            text = str(id)
            styleNativeButton(this, primary, dark)
            setOnClickListener { action() }
            content.addView(this, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10); bottomMargin = dp(2) })
        }
        /** iOS disclosure row: accent label with a chevron that turns when the group opens. */
        fun disclosure(label: String, color: Int, size: Float, centred: Boolean, target: () -> View) = TextView(uiContext).apply {
            text = label
            fun refresh() {
                val chevron = uiContext.getDrawable(if (target().visibility == View.VISIBLE) R.drawable.ios_chevron_up
                    else R.drawable.ios_chevron_down)?.mutate()?.apply { setTint(color) }
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null)
            }
            refresh(); compoundDrawablePadding = dp(6); setTextColor(color); textSize = size; regular?.let { typeface = it }
            gravity = if (centred) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true; minHeight = dp(44)
            setOnClickListener {
                val view = target()
                view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refresh()
            }
        }
        text(R.string.wireless_intro)
        status = text(if (authorized) R.string.wireless_authorized else R.string.wireless_status_ready, 13f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(NativeIos.field(dark))
                cornerRadius = dp(12).toFloat()
            }
        }
        val formStart = content.childCount
        section(R.string.wireless_step_open_title)
        text(R.string.wireless_step_open_detail, 13f)
        button(R.string.wireless_open_settings) {
            if (busy.get()) return@button
            pairPort = localAdbPort(pairInput?.text.toString()); connectPort = localAdbPort(connectInput?.text.toString())
            dialog?.dismiss(); dialog = null
            showBubble()
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { service.startActivity(direct) }.recoverCatching {
                service.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { showNativeBanner(service, str(R.string.wireless_open_manually), long = true) }
        }
        section(R.string.wireless_step_pair_title)
        text(R.string.wireless_step_pair_detail, 13f)
        fun input(id: Int, max: Int) = EditText(uiContext).apply {
            hint = str(id); contentDescription = str(id); setTextColor(ink); setHintTextColor(secondary)
            textSize = 17f; inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(max)); isSaveEnabled = false
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NEXT
            setPadding(dp(14), 0, dp(14), 0)
            styleNativeField(this, dark)
            content.addView(this, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        }
        pairInput = input(R.string.wireless_pair_port, 5).apply { pairPort?.let { setText(it.toString()) } }
        val code = input(R.string.wireless_code, 6)
        button(R.string.wireless_pair_authorize, primary = true) {
            val pp = localAdbPort(pairInput?.text.toString())
            val cp = localAdbPort(connectInput?.text.toString())
            val secret = code.text.toString()
            if (pp == null || !validAdbPairingCode(secret)) {
                status?.setText(R.string.wireless_invalid_input)
                status?.setTextColor(0xffff453a.toInt())
            } else {
                code.text.clear(); status?.setTextColor(secondary)
                start(pp, cp, secret)
            }
        }
        val advanced = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val advancedTrigger = disclosure(str(R.string.wireless_direct_connect), blue, 15f, centred = false) { advanced }
            .apply { setPadding(0, dp(8), 0, dp(8)) }
        content.addView(advancedTrigger, LinearLayout.LayoutParams(-1, -2))
        connectInput = input(R.string.wireless_connect_port, 5).apply { connectPort?.let { setText(it.toString()) } }
        content.removeView(connectInput); advanced.addView(connectInput)
        val reconnect = button(R.string.wireless_connect_authorize) {
            val cp = localAdbPort(connectInput?.text.toString())
            if (cp == null) status?.setText(R.string.wireless_missing_connect) else start(null, cp, null)
        }
        content.removeView(reconnect); advanced.addView(reconnect)
        content.addView(advanced)
        val form = LinearLayout(uiContext).apply { orientation = LinearLayout.VERTICAL }
        val formChildren = (formStart until content.childCount).map(content::getChildAt)
        formChildren.forEach { content.removeView(it); form.addView(it) }
        if (authorized) {
            form.visibility = View.GONE
            button(R.string.wireless_enable, primary = true) {
                if (!busy.get() && allowed()) { close(); enable() }
            }
            content.addView(disclosure(str(R.string.wireless_reconnect), blue, 15f, centred = false) { form },
                LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(form)
        val privacy = text(R.string.wireless_privacy, 12f)
        privacy.visibility = View.GONE
        content.addView(disclosure(str(R.string.wireless_privacy_title), secondary, 13f, centred = true) { privacy },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val scroll = object : ScrollView(uiContext) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (wm.currentWindowMetrics.bounds.height() * .70f).toInt()
                val offered = View.MeasureSpec.getSize(heightMeasureSpec)
                val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    cap else minOf(offered, cap)
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(content); isFillViewport = false }
        dialog = nativeDialogBuilder(service).setTitle(R.string.wireless_title)
            .setView(scroll)
            .setNegativeButton(R.string.close_menu) { _, _ -> close() }.create().also { d ->
                d.setCanceledOnTouchOutside(false)
                d.setOnCancelListener { close() }
                d.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
                d.show(); styleNativeDialog(d)
                d.window?.setLayout(minOf(dp(440), wm.currentWindowMetrics.bounds.width() - dp(32)),
                    WindowManager.LayoutParams.WRAP_CONTENT)
            }
        if (discovery == null) discovery = LocalAdbDiscovery(service) { pairing, port ->
            main.post {
                if (!closed) {
                    if (pairing) { pairPort = port; if (port != null && pairInput?.hasFocus() != true) pairInput?.setText(port.toString()) }
                    else { connectPort = port; if (port != null && connectInput?.hasFocus() != true) connectInput?.setText(port.toString()) }
                }
            }
        }.also { it.start() }
    }

    private fun showBubble() {
        if (closed || bubble != null) return
        val view = TextView(service).apply {
            text = str(R.string.wireless_bubble); contentDescription = text
            textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            NativeIos.font(service, 600)?.let { typeface = it }
            background = GradientDrawable().apply { setColor(0xF0007AFF.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0x55ffffff) }
            elevation = dp(10).toFloat()
            setOnClickListener { show() }; setOnLongClickListener { close(); true }
        }
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(96); title = "18Duo-local-pairing"
        }
        runCatching { wm.addView(view, params); bubble = view }.onFailure { close() }
    }

    private fun start(pair: Int?, connect: Int?, code: String?) {
        if (closed || !allowed() || !busy.compareAndSet(false, true)) return
        status?.setText(R.string.wireless_working)
        val timedOut = AtomicBoolean(false)
        val timeout = Runnable { timedOut.set(true); client?.abort(); pairingClient?.abort() }
        main.postDelayed(timeout, 45_000)
        executor.execute {
            var result = R.string.wireless_failed
            try {
                if (closed || !allowed()) return@execute
                val identity = key ?: AdbKey(PreferenceAdbKeyStore(service.getSharedPreferences("duo18_local_adb", 0)), "18Duo@localhost").also { key = it }
                if (pair != null && code != null) {
                    AdbPairingClient("127.0.0.1", pair, code, identity).use { connection ->
                        pairingClient = connection
                        check(!closed && !timedOut.get() && connection.start())
                    }
                    pairingClient = null
                    main.post { if (!closed) status?.setText(R.string.wireless_pairing_complete) }
                }
                val port = connect ?: connectPort
                if (port == null) { result = R.string.wireless_paired_need_connect; return@execute }
                check(!closed && !timedOut.get() && allowed())
                AdbClient("127.0.0.1", port, identity).use { connection ->
                    client = connection
                    check(!closed && !timedOut.get())
                    connection.connect()
                    main.post {
                        if (!closed) {
                            status?.setText(R.string.wireless_connected)
                        }
                    }
                    check(!closed && !timedOut.get() && allowed())
                    // No files, app data, input injection or external commands are accessed.
                    connection.shellCommand(DUO18_NAVIGATION_GRANT) { /* never record shell output */ }
                }
                client = null
                result = if (granted()) R.string.wireless_authorized else R.string.wireless_grant_denied
            } catch (_: Exception) {
                result = R.string.wireless_failed
            } catch (_: LinkageError) {
                result = R.string.wireless_native_failed
            } finally {
                runCatching { client?.close() }; client = null
                // Native pairing context is destroyed by the owning worker's use/finally only.
                pairingClient = null
                busy.set(false)
                main.removeCallbacks(timeout)
                val message = result
                main.post {
                    if (!closed) {
                        status?.setText(message)
                        showOutcome(message)
                    }
                }
            }
        }
    }

    /** A visible modal result, independent of scroll position and the keyboard. */
    private fun showOutcome(message: Int) {
        if (closed) return
        val success = message == R.string.wireless_authorized && granted()
        val paired = message == R.string.wireless_paired_need_connect
        outcomeDialog?.dismiss()
        outcomeDialog = createWirelessAdbOutcome(service, success, paired, message,
            onEnable = { if (granted() && allowed()) { close(); enable() } },
            onClose = { close() }).also { result ->
                result.setCanceledOnTouchOutside(false)
                result.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                }
                result.show(); styleNativeDialog(result)
                result.window?.setLayout(minOf(dp(360), wm.currentWindowMetrics.bounds.width() - dp(24)), -2)
            }
    }

    private fun removeBubble() { bubble?.let { runCatching { wm.removeViewImmediate(it) } }; bubble = null }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null)
        discovery?.close(); discovery = null
        outcomeDialog?.dismiss(); outcomeDialog = null
        dialog?.dismiss(); dialog = null; removeBubble()
        // Closing only sockets interrupts I/O without freeing native state concurrently.
        client?.abort(); pairingClient?.abort()
        executor.shutdownNow(); pairInput = null; connectInput = null; status = null
    }
}
