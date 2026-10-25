package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

internal fun nativeDialogContext(context: Context): Context = ContextThemeWrapper(context,
    if (DuoAppearanceRuntime.dark) R.style.Theme_Duo_Dialog_Dark else R.style.Theme_Duo_Dialog)

internal fun nativeDialogBuilder(context: Context) = AlertDialog.Builder(nativeDialogContext(context))

/** Colours of the native (View-based) iOS 26 alert, matching the Compose [IosAlertHost]. */
internal object NativeIos {
    fun ink(dark: Boolean) = if (dark) 0xFFF5F5F7.toInt() else 0xFF1C1C1E.toInt()
    fun secondary(dark: Boolean) = if (dark) 0x99EBEBF5.toInt() else 0x993C3C43.toInt()
    fun blue(dark: Boolean) = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    const val RED = 0xFFFF453A.toInt()
    fun fill(dark: Boolean) = if (dark) 0xFF3A3A3C.toInt() else 0xFFE9E9EB.toInt()
    fun field(dark: Boolean) = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    /** Alert body: translucent when the platform can blur behind it (cross-window blur), opaque otherwise. */
    fun body(dark: Boolean, blurred: Boolean) = when {
        dark && blurred -> 0xD82C2C2E.toInt()
        dark -> 0xFF2C2C2E.toInt()
        blurred -> 0xDEF2F2F7.toInt()
        else -> 0xFFF2F2F7.toInt()
    }

    fun font(context: Context, weight: Int): Typeface? = runCatching {
        ResourcesCompat.getFont(context, when {
            weight >= 600 -> R.font.inter_semibold
            weight >= 500 -> R.font.inter_medium
            else -> R.font.inter_regular
        })
    }.getOrNull()

    /** Pill with an iOS press state (slightly denser fill), no ripple. */
    fun capsule(color: Int, pressed: Int, radius: Float) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(pressed); cornerRadius = radius })
        addState(intArrayOf(), GradientDrawable().apply { setColor(color); cornerRadius = radius })
    }
}

private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/**
 * Turns a shown platform AlertDialog into the iOS 26 alert used everywhere else in 18Duo:
 * 34dp continuous-looking corners, glass (cross-window blur) body, leading-aligned semibold title,
 * Inter text, and capsule buttons — two side by side with Cancel leading, three or more stacked
 * with Cancel last. The original buttons stay in place (hidden) and receive the clicks, so every
 * caller's listeners and dismissal behaviour are unchanged. Safe to call more than once.
 */
internal fun styleNativeDialog(dialog: AlertDialog, maximumWidthDp: Int = 320) {
    val context = dialog.context
    val density = context.resources.displayMetrics.density
    fun dp(v: Float) = v * density
    val dark = DuoAppearanceRuntime.dark
    val window = dialog.window ?: return
    val blurred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true
    window.setBackgroundDrawable(GradientDrawable().apply {
        setColor(NativeIos.body(dark, blurred))
        cornerRadius = dp(34f)
    })
    if (blurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius(dp(30f).toInt())
    window.setGravity(Gravity.CENTER)
    window.setDimAmount(.22f)
    val width = context.resources.displayMetrics.widthPixels
    window.setLayout(minOf(dp(maximumWidthDp.toFloat()).toInt(), width - dp(32f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)

    val decor = window.decorView
    if (decor.getTag(R.id.native_ios_alert_styled) == true) return
    decor.setTag(R.id.native_ios_alert_styled, true)
    val ink = NativeIos.ink(dark)
    val semibold = NativeIos.font(context, 600)
    val regular = NativeIos.font(context, 400)
    fun internalId(name: String) = context.resources.getIdentifier(name, "id", "android")
    decor.findViewById<TextView>(internalId("alertTitle"))?.apply {
        setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); semibold?.let { typeface = it }
        gravity = Gravity.START; letterSpacing = duoTrackingEm(17f)
    }
    decor.findViewById<TextView>(android.R.id.message)?.apply {
        setTextColor(withAlpha(ink, .78f)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); regular?.let { typeface = it }
        setLineSpacing(dp(2f), 1f); letterSpacing = duoTrackingEm(15f)
    }

    val originals = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)
        .mapNotNull { which -> dialog.getButton(which)?.takeIf { it.visibility == View.VISIBLE && !it.text.isNullOrEmpty() }?.let { which to it } }
    val panel = decor.findViewById<View>(internalId("buttonPanel"))
    val host = panel?.parent as? ViewGroup
    if (originals.isEmpty() || panel == null || host == null) {
        // Unknown OEM layout: at least drop the Material caps and ripple colours.
        originals.forEach { (which, button) -> styleCapsule(button, which, dark, regular, semibold, density) }
        return
    }
    val stacked = originals.size > 2 || originals.sumOf { measureLabel(it.second.text) } > 16
    val bar = LinearLayout(context).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(dp(20f).toInt(), dp(4f).toInt(), dp(20f).toInt(), dp(18f).toInt())
    }
    // iOS 26: side by side → Cancel leading; stacked → default first, Cancel last.
    val ordered = if (stacked) originals else originals.sortedBy { if (it.first == AlertDialog.BUTTON_NEGATIVE) 0 else 1 }
    ordered.forEachIndexed { index, (which, original) ->
        val copy = Button(context).apply {
            text = original.text
            contentDescription = original.contentDescription
            isEnabled = original.isEnabled
            setOnClickListener { original.performClick() }
        }
        styleCapsule(copy, which, dark, regular, semibold, density)
        val params = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f).toInt())
            .apply { if (index > 0) topMargin = dp(8f).toInt() }
            else LinearLayout.LayoutParams(0, dp(48f).toInt(), 1f).apply { if (index > 0) marginStart = dp(10f).toInt() }
        bar.addView(copy, params)
    }
    panel.visibility = View.GONE
    host.addView(bar, host.indexOfChild(panel) + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** Rough label width in Latin-letter units (CJK counts double), as [IosAlertHost] decides stacking. */
private fun measureLabel(text: CharSequence?): Int = text?.fold(0) { width, ch -> width + if (ch.code > 0x2E80) 2 else 1 } ?: 0

private fun styleCapsule(button: Button, which: Int, dark: Boolean, regular: Typeface?, semibold: Typeface?, density: Float) {
    val ink = NativeIos.ink(dark)
    val cancel = which == AlertDialog.BUTTON_NEGATIVE
    button.isAllCaps = false
    button.stateListAnimator = null
    button.minHeight = 0; button.minimumHeight = 0; button.minWidth = 0; button.minimumWidth = 0
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    button.typeface = if (cancel) regular ?: button.typeface else semibold ?: button.typeface
    button.letterSpacing = duoTrackingEm(17f)
    button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(withAlpha(ink, .3f), if (cancel) ink else NativeIos.blue(dark))))
    button.background = NativeIos.capsule(withAlpha(ink, .09f), withAlpha(ink, .17f), 24f * density)
    button.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
    button.isSingleLine = true
}

/** iOS search-field-like text input for View dialogs (grey rounded fill, no underline). */
internal fun styleNativeField(field: TextView, dark: Boolean) {
    val density = field.resources.displayMetrics.density
    field.background = GradientDrawable().apply { setColor(NativeIos.field(dark)); cornerRadius = 10f * density }
    field.setTextColor(NativeIos.ink(dark)); field.setHintTextColor(NativeIos.secondary(dark))
    NativeIos.font(field.context, 400)?.let { field.typeface = it }
}

/** Capsule button for View dialogs: filled blue (primary) or tinted fill; dims when pressed. */
internal fun styleNativeButton(button: Button, primary: Boolean, dark: Boolean) {
    val density = button.resources.displayMetrics.density
    val blue = NativeIos.blue(dark)
    button.isAllCaps = false
    button.stateListAnimator = null
    button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
    NativeIos.font(button.context, 600)?.let { button.typeface = it }
    button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(withAlpha(if (primary) Color.WHITE else blue, .45f), if (primary) Color.WHITE else blue)))
    val fill = if (primary) blue else withAlpha(blue, if (dark) .24f else .14f)
    button.background = NativeIos.capsule(fill, withAlpha(fill, ((fill ushr 24) / 255f) * .7f), 25f * density)
}

/**
 * The iOS replacement for a Toast where no Compose host exists (the accessibility-service
 * overlays): a capsule that drops in from the top, stays a moment and leaves. Never steals focus
 * or touches.
 */
internal fun showNativeBanner(service: AccessibilityService, text: CharSequence, long: Boolean = false) {
    val wm = service.getSystemService(WindowManager::class.java) ?: return
    val density = service.resources.displayMetrics.density
    val dark = DuoAppearanceRuntime.dark
    val view = TextView(service).apply {
        this.text = text; contentDescription = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        NativeIos.font(service, 500)?.let { typeface = it }
        setTextColor(NativeIos.ink(dark)); gravity = Gravity.CENTER; maxLines = 3
        setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        background = GradientDrawable().apply {
            setColor(if (dark) 0xF02C2C2E.toInt() else 0xF5F9F9FB.toInt()); cornerRadius = 26f * density
            setStroke(density.toInt().coerceAtLeast(1), if (dark) 0x33FFFFFF else 0x14000000)
        }
        elevation = 12f * density
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (44 * density).toInt(); title = "18Duo-banner"
    }
    if (runCatching { wm.addView(view, params) }.isFailure) return
    val ease = PathInterpolator(.2f, .9f, .25f, 1f)
    view.translationY = -80f * density; view.alpha = 0f
    view.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(ease).start()
    Handler(Looper.getMainLooper()).postDelayed({
        view.animate().translationY(-80f * density).alpha(0f).setDuration(260).setInterpolator(ease)
            .withEndAction { runCatching { wm.removeViewImmediate(view) } }.start()
    }, iosBannerMillis(text.toString(), long))
}
