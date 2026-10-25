package com.jake.duolauncher

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/** One cell of the AP16 Control Center, in the theme's 870-unit-wide coordinate space (1 unit = 1 px at 1080 wide). */
internal data class CcCell(val x: Int, val y: Int, val w: Int, val h: Int)

/** The AP16 lock-screen Control Center grid (manifest.xml "控制中心"), unchanged except where noted in [AppleControlCenter]. */
internal object Ap16ControlGrid {
    const val WIDTH = 870
    const val HEIGHT = 1554
    const val SHORTCUT_ROWS_TOP = 1140
    val connectivity = CcCell(0, 0, 414, 414)
    val mute = CcCell(35, 35, 154, 154)
    val bluetooth = CcCell(225, 35, 154, 154)
    val wifi = CcCell(35, 225, 154, 154)
    val powerSave = CcCell(225, 225, 70, 70)
    val alarm = CcCell(309, 225, 70, 70)
    val earphone = CcCell(225, 309, 70, 70)
    val music = CcCell(456, 0, 414, 414)
    val flashlight = CcCell(0, 456, 186, 186)
    val standby = CcCell(228, 456, 186, 186)
    val brightness = CcCell(456, 456, 186, 414)
    val volume = CcCell(684, 456, 186, 414)
    val focus = CcCell(0, 684, 414, 186)
    val darkMode = CcCell(0, 912, 414, 186)
    val calculator = CcCell(456, 912, 186, 186)
    val miHome = CcCell(684, 912, 186, 186)
    fun shortcut(row: Int, index: Int) = CcCell(228 * index, SHORTCUT_ROWS_TOP + 228 * row, 186, 186)

    /** Unit → px scale: as wide as the theme's 80 % column, never past the height, within a sane band of its own size. */
    fun scale(availableWidthPx: Int, availableHeightPx: Int, density: Float, rows: Int): Float {
        val height = if (rows >= 2) HEIGHT else if (rows == 1) HEIGHT - 228 else SHORTCUT_ROWS_TOP - 42
        val native = density / 3f
        val byWidth = availableWidthPx * .8f / WIDTH
        return minOf(byWidth.coerceIn(native * .85f, native * 1.25f), availableHeightPx / height.toFloat())
    }
}

/** Lays children at [CcCell]s scaled by [scale]; children carry their cell as tag. */
private class CcGrid(context: Context) : ViewGroup(context) {
    var scale = 1f
    var contentHeight = Ap16ControlGrid.HEIGHT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cell = child.getTag(R.id.cc_cell) as? CcCell ?: continue
            child.measure(MeasureSpec.makeMeasureSpec((cell.w * scale).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((cell.h * scale).roundToInt(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension((Ap16ControlGrid.WIDTH * scale).roundToInt(), (contentHeight * scale).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val cell = child.getTag(R.id.cc_cell) as? CcCell ?: continue
            val x = (cell.x * scale).roundToInt()
            val y = (cell.y * scale).roundToInt()
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}

/**
 * Control Center after the AP16 theme's lock-screen version: connectivity and music tiles, round
 * buttons, two vertical sliders, Focus and Dark Mode pills, and Alipay / WeChat shortcut rows.
 * What an ordinary app may not switch itself (Wi-Fi, Bluetooth, Dark Mode) opens the system panel,
 * as Xiaomi's own shortcuts do; ringer, flashlight, volume, media and (with permission) Focus and
 * brightness change directly.
 */
internal class AppleControlCenter(
    private val context: Context,
    private val art: Ap16Art,
    private val launch: (Intent) -> Unit,
    private val dismiss: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val chinese = java.util.Locale.getDefault().language == "zh"
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val torchCamera: String? = runCatching {
        camera.cameraIdList.firstOrNull { id ->
            val c = camera.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera) { torchOn = enabled; refresh() }
        }
    }
    private val refreshers = ArrayList<() -> Unit>()
    private var mediaTitle: TextView? = null
    private var mediaArtist: TextView? = null
    private var mediaArt: ImageView? = null
    private var mediaPlay: ImageView? = null
    private var grid: CcGrid? = null

    private fun label(zh: String, en: String) = if (chinese) zh else en

    fun build(availableWidthPx: Int, availableHeightPx: Int): View {
        val rows = shortcutRows()
        val g = CcGrid(context)
        g.scale = Ap16ControlGrid.scale(availableWidthPx, availableHeightPx, density, rows.size)
        g.contentHeight = when (rows.size) { 0 -> Ap16ControlGrid.SHORTCUT_ROWS_TOP - 42; 1 -> Ap16ControlGrid.HEIGHT - 228; else -> Ap16ControlGrid.HEIGHT }
        grid = g
        val s = g.scale
        fun put(view: View, cell: CcCell) { view.setTag(R.id.cc_cell, cell); g.addView(view) }
        fun units(v: Int) = v * s

        // Connectivity: Silent, Bluetooth, Wi-Fi, and three status lights (Low Power, alarm, headphones).
        put(plate(units(110)), Ap16ControlGrid.connectivity)
        put(toggle("cc/mute", label("静音模式", "Silent Mode"), { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL }) {
            val next = if (audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE
                else AudioManager.RINGER_MODE_NORMAL
            runCatching { audio.ringerMode = next }.onFailure { launch(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        }, Ap16ControlGrid.mute)
        put(toggle("cc/bluetooth", label("蓝牙", "Bluetooth"),
            { Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) == 1 }) {
            launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }, Ap16ControlGrid.bluetooth)
        put(toggle("cc/wifi", "Wi-Fi", { context.getSystemService(WifiManager::class.java).isWifiEnabled }) {
            launch(Intent(Settings.Panel.ACTION_WIFI))
        }, Ap16ControlGrid.wifi)
        put(light("cc/powersave", label("省电模式", "Low Power Mode")) {
            context.getSystemService(PowerManager::class.java).isPowerSaveMode
        }, Ap16ControlGrid.powerSave)
        put(light("cc/alarm", label("闹钟", "Alarm")) {
            context.getSystemService(AlarmManager::class.java).nextAlarmClock != null
        }, Ap16ControlGrid.alarm)
        put(light("cc/earphone", label("耳机", "Headphones")) { headphones() }, Ap16ControlGrid.earphone)

        put(musicTile(s), Ap16ControlGrid.music)

        put(roundButton("cc/alwayson", label("手电筒", "Flashlight"), active = { torchOn },
            activeFill = Color.WHITE) { toggleTorch() }, Ap16ControlGrid.flashlight)
        put(roundButton("cc/standby", "StandBy") {
            launch(Intent(context, MainActivity::class.java).putExtra("duo_destination", "standby")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }, Ap16ControlGrid.standby)
        put(slider("cc/sun", label("亮度", "Brightness"), ::brightnessFraction, ::setBrightness, sunGlyph = true),
            Ap16ControlGrid.brightness)
        put(slider("cc/volume", label("音量", "Volume"), {
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1).toFloat()
        }, { f ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                (f * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0)
        }), Ap16ControlGrid.volume)

        val notifications = context.getSystemService(NotificationManager::class.java)
        put(pill("cc/focus", label("专注模式", "Focus"),
            subtitle = { if (notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) label("勿扰模式 · 开", "Do Not Disturb · On") else null },
            active = { notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL }) {
            if (notifications.isNotificationPolicyAccessGranted) {
                val on = notifications.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
                notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL
                    else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }, Ap16ControlGrid.focus)
        put(pill("cc/darkmode", label("深色模式", "Dark Mode"), glyphUnits = 92,
            subtitle = { if (night()) label("开", "On") else label("关", "Off") }, active = { false }) {
            launch(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }, Ap16ControlGrid.darkMode)
        appButton("cc/calculator", label("计算器", "Calculator"), "com.miui.calculator",
            ComponentName("com.miui.calculator", "com.miui.calculator.cal.CalculatorActivity"))?.let { put(it, Ap16ControlGrid.calculator) }
        appButton("cc/mihome", label("米家", "Mi Home"), "com.xiaomi.smarthome", null)?.let { put(it, Ap16ControlGrid.miHome) }

        rows.forEachIndexed { row, spec ->
            spec.actions.forEachIndexed { index, action ->
                put(roundButton("cc/${spec.glyphPrefix}_$index", action.label) { runShortcut(spec.packageName, action) },
                    Ap16ControlGrid.shortcut(row, index))
            }
        }
        torchCamera?.let { runCatching { camera.registerTorchCallback(torchCallback, null) } }
        refresh()
        return g
    }

    fun release() {
        runCatching { camera.unregisterTorchCallback(torchCallback) }
        refreshers.clear()
        grid = null
    }

    /** Called about once a second while the panel is open. */
    fun refresh() {
        refreshers.forEach { runCatching(it) }
        refreshMedia()
    }

    private fun night() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun headphones(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun toggleTorch() {
        val id = torchCamera ?: return
        runCatching { camera.setTorchMode(id, !torchOn) }.onFailure { Log.w("AppleShade", "Torch unavailable", it) }
    }

    // ---- brightness ----

    private val brightnessMax: Int by lazy {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        (if (id != 0) runCatching { res.getInteger(id) }.getOrNull() else null)?.takeIf { it > 1 } ?: 255
    }

    private fun brightnessFraction(): Float =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessMax / 2) / brightnessMax.toFloat()

    private fun setBrightness(fraction: Float) {
        if (!Settings.System.canWrite(context)) return
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
            (fraction * brightnessMax).roundToInt().coerceIn(1, brightnessMax))
    }

    // ---- shortcuts ----

    private class ShortcutAction(val label: String, val shortcutId: String?, val fallback: Uri?)
    private class ShortcutRow(val packageName: String, val glyphPrefix: String, val actions: List<ShortcutAction>)

    /** The rows of the AP16 grid whose app is installed (Alipay first, then WeChat). */
    private fun shortcutRows(): List<ShortcutRow> = listOfNotNull(
        ShortcutRow("com.eg.android.AlipayGphone", "alipay", listOf(
            ShortcutAction(label("支付宝", "Alipay"), null, null),
            ShortcutAction(label("支付宝扫一扫", "Alipay Scan"), "1001", Uri.parse("alipayqr://platformapi/startapp?saId=10000007")),
            ShortcutAction(label("支付宝付款码", "Alipay Pay Code"), "1002", Uri.parse("alipays://platformapi/startapp?appId=20000056")),
            ShortcutAction(label("支付宝收钱", "Alipay Receive"), "1003", Uri.parse("alipays://platformapi/startapp?appId=20000123")),
        )).takeIf { installed(it.packageName) },
        ShortcutRow("com.tencent.mm", "wepay", listOf(
            ShortcutAction(label("微信", "WeChat"), null, null),
            ShortcutAction(label("微信扫一扫", "WeChat Scan"), "launch_type_scan_qrcode", null),
            ShortcutAction(label("微信收付款", "WeChat Pay"), "launch_type_offline_wallet", null),
            ShortcutAction(label("微信二维码", "WeChat QR Code"), "launch_type_my_qrcode", null),
        )).takeIf { installed(it.packageName) },
    )

    private fun installed(pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** App shortcuts are the apps' own published entries (as on Home's long-press menu); Home may start them. */
    private fun runShortcut(pkg: String, action: ShortcutAction) {
        val id = action.shortcutId
        if (id != null) {
            val started = runCatching {
                dismiss()
                context.getSystemService(LauncherApps::class.java).startShortcut(pkg, id, null, null, Process.myUserHandle())
            }.isSuccess
            if (started) return
            Log.w("AppleShade", "Shortcut $pkg/$id unavailable")
        }
        val fallback = action.fallback?.let { Intent(Intent.ACTION_VIEW, it).setPackage(pkg) }
            ?: context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch(fallback)
    }

    private fun appButton(glyph: String, title: String, pkg: String, component: ComponentName?): View? {
        val intent = component?.let { c -> Intent(Intent.ACTION_MAIN).setComponent(c).takeIf { installed(pkg) } }
            ?: context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        return roundButton(glyph, title) { launch(intent) }
    }

    // ---- views ----

    private fun plate(radius: Float, active: Int? = null) = View(context).apply {
        background = GlassPlate(radius, density).also { it.active = active }
    }

    /** iOS tiles sink slightly and brighten under the finger. */
    private fun View.pressable(onTap: () -> Unit) {
        isClickable = true
        setOnClickListener { onTap(); refresh() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.93f).scaleY(.93f).setDuration(110).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
            false
        }
    }

    /** A connectivity toggle: the theme's own circle artwork for off (`_0`) and on (`_1`). */
    private fun toggle(glyph: String, title: String, state: () -> Boolean, onTap: () -> Unit): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = title
        pressable(onTap)
        refreshers += { setImageBitmap(art.image("${glyph}_${if (state()) 1 else 0}")) }
    }

    /** A status light in the connectivity tile: shown, not tapped (as in the theme). */
    private fun light(glyph: String, title: String, state: () -> Boolean): View = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = title
        refreshers += { setImageBitmap(art.image("${glyph}_${if (state()) 1 else 0}")) }
    }

    private fun roundButton(glyph: String, title: String, active: () -> Boolean = { false },
        activeFill: Int = Color.WHITE, onTap: () -> Unit): View {
        val plate = GlassPlate(1_000f, density)
        val frame = FrameLayout(context).apply {
            background = plate
            contentDescription = title
            pressable(onTap)
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val hasStates = art.image("${glyph}_0") != null
        refreshers += {
            val on = active()
            plate.active = if (on) activeFill else null
            image.setImageBitmap(art.image(if (hasStates) "${glyph}_${if (on) 1 else 0}" else glyph))
        }
        return frame
    }

    private fun pill(glyph: String, title: String, glyphUnits: Int = 110, subtitle: () -> String?, active: () -> Boolean,
        onTap: () -> Unit): View {
        val s = grid?.scale ?: 1f
        val frame = FrameLayout(context).apply {
            background = GlassPlate(93 * s, density)
            contentDescription = title
            pressable(onTap)
        }
        val badge = GlassPlate(1_000f, density)
        val icon = ImageView(context).apply {
            background = badge
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = ((110 - glyphUnits.coerceAtMost(110)) / 2f * s + 22 * s).roundToInt()
            setPadding(pad, pad, pad, pad)
            setImageBitmap(art.image(glyph))
        }
        frame.addView(icon, FrameLayout.LayoutParams((110 * s).roundToInt(), (110 * s).roundToInt(),
            Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = (38 * s).roundToInt() })
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = text(title, 40 * s, bold = true)
        val sub = text("", 34 * s).apply { alpha = .6f }
        column.addView(name); column.addView(sub)
        frame.addView(column, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = (172 * s).roundToInt(); rightMargin = (16 * s).roundToInt() })
        refreshers += {
            val line = subtitle()
            sub.text = line.orEmpty()
            sub.visibility = if (line == null) View.GONE else View.VISIBLE
            badge.active = if (active()) Color.WHITE else null
            badge.fillAlpha = 70
        }
        return frame
    }

    private fun text(value: String, sizePx: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, if (bold) R.font.inter_semibold else R.font.inter_regular)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    /** A vertical iOS slider: white fill rising from the bottom, the glyph near its foot. */
    private fun slider(glyph: String, title: String, read: () -> Float, write: (Float) -> Unit, sunGlyph: Boolean = false): View =
        object : View(context) {
            private val track = GlassPlate(1_000f, density)
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 255, 255, 255) }
            private val clip = android.graphics.Path()
            private val box = RectF()
            private var value = read().coerceIn(0f, 1f)
            private var dragging = false
            private var startY = 0f
            private var startValue = 0f
            private val icon: Bitmap? = art.image(glyph)

            init {
                contentDescription = title
                isClickable = true
                refreshers += { if (!dragging) { value = read().coerceIn(0f, 1f); invalidate() } }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, view.width / 2f)
                }
                clipToOutline = true
            }

            override fun onDraw(canvas: Canvas) {
                track.setBounds(0, 0, width, height)
                track.draw(canvas)
                val top = height * (1f - value)
                box.set(0f, top, width.toFloat(), height.toFloat())
                clip.reset(); clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), width / 2f, width / 2f, android.graphics.Path.Direction.CW)
                canvas.save(); canvas.clipPath(clip); canvas.drawRect(box, fill); canvas.restore()
                val bitmap = icon ?: return
                val size = width * (if (sunGlyph) .45f else .42f)
                val cx = width / 2f
                val cy = height * (320f / 414f)
                val dst = RectF(cx - size / 2f, cy - size * bitmap.height / bitmap.width / 2f,
                    cx + size / 2f, cy + size * bitmap.height / bitmap.width / 2f)
                canvas.drawBitmap(bitmap, null, dst, null)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging = true; startY = event.y; startValue = value
                        parent?.requestDisallowInterceptTouchEvent(true)
                        animate().scaleX(1.04f).scaleY(1.02f).setDuration(120).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        value = (startValue + (startY - event.y) / height).coerceIn(0f, 1f)
                        write(value); invalidate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        if (sunGlyph && !Settings.System.canWrite(context))
                            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
                return true
            }
        }

    // ---- music ----

    private fun musicTile(s: Float): View {
        val tile = FrameLayout(context).apply { background = GlassPlate(110 * s, density) }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GlassPlate(40 * s, density).also { it.fillAlpha = 60 }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 40 * s)
            }
            clipToOutline = true
        }
        tile.addView(cover, FrameLayout.LayoutParams((144 * s).roundToInt(), (144 * s).roundToInt()).apply {
            leftMargin = (33 * s).roundToInt(); topMargin = (33 * s).roundToInt()
        })
        val title = text(label("未在播放", "Not Playing"), 40 * s, bold = true)
        val artist = text("", 36 * s).apply { alpha = .6f }
        tile.addView(title, FrameLayout.LayoutParams(-1, -2).apply {
            leftMargin = (34 * s).roundToInt(); rightMargin = (34 * s).roundToInt(); topMargin = (200 * s).roundToInt()
        })
        tile.addView(artist, FrameLayout.LayoutParams(-1, -2).apply {
            leftMargin = (34 * s).roundToInt(); rightMargin = (34 * s).roundToInt(); topMargin = (250 * s).roundToInt()
        })
        fun button(glyph: String, x: Int, title: String, onTap: () -> Unit) = ImageView(context).apply {
            setImageBitmap(art.image(glyph))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.WHITE)
            contentDescription = title
            pressable(onTap)
            tile.addView(this, FrameLayout.LayoutParams((90 * s).roundToInt(), (90 * s).roundToInt()).apply {
                leftMargin = (x * s).roundToInt(); topMargin = (300 * s).roundToInt()
            })
        }
        button("cc/music_prev", 50, label("上一首", "Previous")) { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
        mediaPlay = button("cc/music_play", 162, label("播放/暂停", "Play/Pause")) { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        button("cc/music_next", 274, label("下一首", "Next")) { media(KeyEvent.KEYCODE_MEDIA_NEXT) }
        mediaTitle = title; mediaArtist = artist; mediaArt = cover
        return tile
    }

    /** The first active media session; readable only with notification access (Android's rule for launchers too). */
    private fun controller(): MediaController? = runCatching {
        if (!AppleShadeSettings.notificationAccess(context)) return null
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, AppleNotificationListener::class.java))
            .firstOrNull { it.metadata != null }
    }.getOrNull()

    private fun refreshMedia() {
        val c = controller()
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        mediaTitle?.apply {
            text = title ?: label("未在播放", "Not Playing")
            alpha = if (title == null) .6f else 1f
        }
        mediaArtist?.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val cover = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        mediaArt?.setImageBitmap(cover)
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        mediaPlay?.setImageBitmap(art.image(if (playing) "cc/music_pause" else "cc/music_play"))
    }

    private fun media(code: Int) {
        val c = controller()
        if (c != null) {
            val controls = c.transportControls
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            }
            return
        }
        val down = android.os.SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(down, down, KeyEvent.ACTION_UP, code, 0))
    }
}
