package com.jake.duolauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The other half of a Split View started by the gesture. iPhone Duo shows the Home Screen there
 * (Apple split-view video, 3.6–5.0 s: the left half becomes Home, a tap opens the app in that half);
 * 18Duo shows its wallpaper under a frosted Liquid-Glass sheet with every app as an iOS icon. A tap
 * opens that app in this half (Android launches a task started from a split stage into the same
 * stage), after which the picker closes itself.
 *
 * It also reports to [SplitViewBus] whether it really is one half of the panel and which one, which
 * is how the controller verifies the split and its side without reading any other app's windows.
 */
class SplitViewPickerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var token = 0
    private var launched = false
    private var exclude: String? = null
    private lateinit var root: FrameLayout
    private lateinit var grid: GridLayout
    private val abandonListener: () -> Unit = { main.post { if (SplitViewBus.abandon == token) finishAndRemoveTask() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finishAndRemoveTask() }
        super.onCreate(savedInstanceState)
        token = intent.getIntExtra(EXTRA_TOKEN, 0)
        exclude = intent.getStringExtra(EXTRA_EXCLUDE)
        overridePendingTransition(0, 0)
        window.setBackgroundDrawableResource(android.R.color.black)
        val d = resources.displayMetrics.density
        root = FrameLayout(this)
        root.addView(WallpaperView(), FrameLayout.LayoutParams(-1, -1))
        // The glass sheet: the same light tint the pane had on the stage, so the hand-off is invisible.
        root.addView(View(this).apply { setBackgroundColor(if (DuoAppearanceRuntime.dark) 0x5C1C1C1E else 0x2EFFFFFF) },
            FrameLayout.LayoutParams(-1, -1))
        grid = GridLayout(this).apply { columnCount = COLUMNS; useDefaultMargins = false }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SplitViewPickerActivity).apply {
                text = uxText("选择另一个 App", "Choose Another App")
                setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                setShadowLayer(3f * d, 0f, 1f * d, 0x40000000)
                gravity = Gravity.CENTER
                setPadding(0, (8 * d).toInt(), 0, (14 * d).toInt())
            }, LinearLayout.LayoutParams(-1, -2))
            addView(grid, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            addView(column, ViewGroup.LayoutParams(-1, -2))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top + (12 * d).toInt(), bars.right, bars.bottom + (24 * d).toInt())
                insets
            }
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        SplitViewBus.listen(abandonListener)
        loadApps()
    }

    private fun loadApps() {
        val d = resources.displayMetrics.density
        loader.execute {
            val apps = runCatching {
                getSystemService(LauncherApps::class.java).getActivityList(null, Process.myUserHandle())
                    .filter { it.componentName.packageName != packageName && it.componentName.packageName != exclude }
            }.getOrDefault(emptyList())
            val collator = Collator.getInstance(Locale.getDefault())
            val items = apps.sortedWith { a, b -> collator.compare(a.label.toString(), b.label.toString()) }.map {
                Triple(it.componentName, it.label.toString(), runCatching { launcherIcon(it.getBadgedIcon(0)) }.getOrNull())
            }
            main.post { if (!isDestroyed) items.forEach { (component, label, icon) -> addApp(component, label, icon, d) } }
        }
    }

    /** One Home-Screen icon: 54 dp artwork, 11 sp label under it (Apple's Home metrics, BRIEF). */
    private fun addApp(component: ComponentName, label: String, icon: Bitmap?, d: Float) {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (10 * d).toInt())
            isClickable = true; isFocusable = true; contentDescription = label
            setOnClickListener { open(component) }
        }
        cell.addView(ImageView(this).apply { setImageBitmap(icon); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams((ICON * d).toInt(), (ICON * d).toInt()))
        cell.addView(TextView(this).apply {
            text = label; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            setShadowLayer(2f * d, 0f, .5f * d, 0x55000000)
            setPadding(0, (5 * d).toInt(), 0, 0)
        }, LinearLayout.LayoutParams((CELL * d).toInt(), -2))
        grid.addView(cell)
    }

    private fun open(component: ComponentName) {
        if (launched) return
        launched = true
        // Started from inside a split stage, the new task joins this stage (TaskDisplayArea launch root rule).
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }.onFailure { launched = false }
    }

    override fun onResume() { super.onResume(); report(true) }

    override fun onEnterAnimationComplete() { super.onEnterAnimationComplete(); SplitViewBus.shown(token) }
    override fun onPause() { super.onPause(); report(false) }

    override fun onStop() {
        super.onStop()
        // The chosen app now covers this half; the picker's job is done.
        if (launched) finishAndRemoveTask()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        report(!isFinishing)
        // The split ended (divider dragged away, the other app closed): never stay behind full screen.
        if (!isInMultiWindowMode && wasInSplit) finishAndRemoveTask()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        main.post { report(!isFinishing && hasWindowFocus()) }
    }

    private var wasInSplit = false

    private fun report(resumed: Boolean) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val full = windowManager.maximumWindowMetrics.bounds
        val inSplit = isInMultiWindowMode && bounds.width() < full.width() * .75f
        val side = if (!inSplit) null else if (bounds.centerX() < full.centerX()) SplitSide.LEFT else SplitSide.RIGHT
        if (inSplit) wasInSplit = true
        SplitViewBus.publish(token, resumed || SplitViewBus.resumed, inSplit, side)
    }

    @Deprecated("API 31–32 only; API 33+ uses the OnBackInvokedCallback registered in onCreate")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() { finishAndRemoveTask() }

    override fun onDestroy() {
        SplitViewBus.forget(abandonListener)
        loader.shutdownNow()
        super.onDestroy()
    }

    /** Home's wallpaper, cropped as Home crops it for this window, blurred like the stage's backdrop. */
    private inner class WallpaperView : View(this@SplitViewPickerActivity) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        init {
            val radius = 26f * resources.displayMetrics.density
            setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.BLACK)
            val photo = LauncherBackgroundCache.bitmap?.takeUnless { it.isRecycled }
            val live = if (photo == null) DuneLive.restBitmap() else null
            val wall = photo ?: live ?: cachedBundledWallpaper() ?: return
            // The picker is one half of the panel: crop the panel-sized wallpaper and show this half of it.
            val full = windowManager.maximumWindowMetrics.bounds
            val mine = windowManager.currentWindowMetrics.bounds
            val crop = wallpaperSourceCrop(wall.width, wall.height, full.width(), full.height(), photo == null && live == null)
            val sx = crop.width / full.width().toFloat(); val sy = crop.height / full.height().toFloat()
            val src = Rect((crop.x + mine.left * sx).toInt(), (crop.y + mine.top * sy).toInt(),
                (crop.x + mine.right * sx).toInt(), (crop.y + mine.bottom * sy).toInt())
            canvas.drawBitmap(wall, src, Rect(0, 0, width, height), paint)
        }
    }

    companion object {
        const val EXTRA_TOKEN = "duo_split_token"
        const val EXTRA_EXCLUDE = "duo_split_exclude"
        private const val COLUMNS = 4
        private const val ICON = 54f
        private const val CELL = 80f
    }
}
