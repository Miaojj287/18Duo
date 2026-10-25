package com.jake.duolauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

// ---------------------------------------------------------------------------------------
// Apple's Batteries widget (iOS 26 gallery: small, medium, large — all "Status"): a Liquid
// Glass platter, green rings / bars, white content. Android exposes no public battery level for
// Bluetooth accessories, so the phone fills the first slot and the rest stay empty, exactly as
// iOS draws them when fewer devices are connected (empty rings / blank list rows).
// ---------------------------------------------------------------------------------------

internal data class BatteryStatus(val percent: Int?, val charging: Boolean, val lowPower: Boolean)

/** iOS battery colours: red at or below 20 % (not charging), yellow in Low Power Mode, else green. */
internal fun appleBatteryColor(status: BatteryStatus, monochrome: Boolean): Color = when {
    monochrome -> Color.White
    status.percent == null -> Color.White.copy(alpha = .5f)
    status.lowPower -> Color(0xFFFFD60A)
    status.percent <= 20 && !status.charging -> Color(0xFFFF453A)
    else -> Color(0xFF30D158)
}

/** Fraction of the ring that is filled; a charging ring leaves a gap at 12 o'clock for the bolt. */
internal fun batteryRingSweep(percent: Int?, charging: Boolean, gapDegrees: Float): Pair<Float, Float> {
    val start = if (charging) -90f + gapDegrees / 2f else -90f
    val available = if (charging) 360f - gapDegrees else 360f
    return start to available * ((percent ?: 0).coerceIn(0, 100) / 100f)
}

@Composable
private fun rememberBatteryStatus(): BatteryStatus {
    val context = LocalContext.current
    fun read(intent: Intent?): BatteryStatus {
        val reading = batteryReading(intent)
        val power = context.getSystemService(PowerManager::class.java)
        return BatteryStatus(reading.percent, reading.charging, power?.isPowerSaveMode == true)
    }
    val initial = remember(context) { read(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))) }
    return produceState(initial, context) {
        var last: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) last = intent
                value = read(last)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        context.registerReceiver(receiver, filter)
        awaitDispose { runCatching { context.unregisterReceiver(receiver) } }
    }.value
}

@Composable
internal fun AppleBatteryWidget(size: NativeWidgetSize, modifier: Modifier, preview: Boolean) {
    val status = rememberBatteryStatus()
    val context = LocalContext.current
    val name = remember(context) { deviceDisplayName(context) }
    val tone = currentWidgetTone()
    val clear = tone == WidgetTone.CLEAR
    val veil = when (tone) {
        WidgetTone.CLEAR -> widgetColors(WidgetTone.CLEAR).background
        WidgetTone.DARK -> Color.Black.copy(alpha = .42f)
        WidgetTone.LIGHT -> Color.Black.copy(alpha = .28f)
    }
    AppleWidgetCard(modifier, size, AppleWidgetBackground.Glass(veil), preview, clear) { s ->
        when (size) {
            NativeWidgetSize.SMALL -> BatterySmall(status, s, clear)
            NativeWidgetSize.MEDIUM -> BatteryMedium(status, s, clear)
            else -> BatteryLarge(status, name, s, clear)
        }
    }
}

/** One device: a single large ring with the device inside and its exact percentage below (iOS). */
@Composable
private fun BoxScope.BatterySmall(status: BatteryStatus, s: AppleWidgetScale, clear: Boolean) {
    BatteryRing(status, s, centerX = 79f, centerY = 64f, diameter = 92f, stroke = 8.5f, glyph = 30f, clear = clear)
    BaselineText(status.percent?.let { "$it%" } ?: "--", s, 22f, FontWeight.Normal, Color.White, baseline = 137f, center = true)
}

/** Four slots in a row: the phone, then empty rings (Bluetooth accessory levels are not public on Android). */
@Composable
private fun BoxScope.BatteryMedium(status: BatteryStatus, s: AppleWidgetScale, clear: Boolean) {
    for (i in 0 until 4) {
        val cx = 48f + i * 80.67f
        if (i == 0) {
            BatteryRing(status, s, cx, 63f, 64f, 6f, 21f, clear)
            BaselineText(status.percent?.let { "$it%" } ?: "--", s, 21f, FontWeight.Normal, Color.White, baseline = 127f,
                inset = cx - 169f, center = true)
        } else EmptyRing(s, cx, 63f, 64f, 6f)
    }
}

/** List rows: glyph, name, percentage and a battery glyph; six slots separated by hairlines. */
@Composable
private fun BoxScope.BatteryLarge(status: BatteryStatus, name: String, s: AppleWidgetScale, clear: Boolean) {
    val color = appleBatteryColor(status, clear)
    for (i in 0 until 6) {
        val center = 30f + i * 59f
        if (i < 5) Box(Modifier.align(Alignment.TopStart).offsetPt(s, 48f, center + 29.5f).size(s.pt(338f - 48f), s.pt(.5f))
            .background(Color.White.copy(alpha = .18f)))
        if (i != 0) continue
        Canvas(Modifier.align(Alignment.TopStart).offsetPt(s, 17f, center - 9f).size(s.pt(18f), s.pt(18f))) {
            drawPhoneGlyph(Offset(size.width / 2f, size.height / 2f), size.height, Color.White)
        }
        BaselineText(name, s, 15f, FontWeight.Normal, Color.White, baseline = center + 5.2f, inset = 48f, maxWidth = 190f)
        BaselineText(status.percent?.let { "$it%" } ?: "--", s, 12f, FontWeight.Normal, Color.White.copy(alpha = .78f),
            baseline = center + 4.2f, inset = 17f + 25f + 8f, alignEnd = true)
        Canvas(Modifier.align(Alignment.TopStart).offsetPt(s, 338f - 17f - 25f, center - 6f).size(s.pt(25f), s.pt(12f))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawBatteryGlyph(status, color, s.unit * density)
        }
    }
}

@Composable
private fun BoxScope.BatteryRing(status: BatteryStatus, s: AppleWidgetScale, centerX: Float, centerY: Float, diameter: Float,
    stroke: Float, glyph: Float, clear: Boolean) {
    val color = appleBatteryColor(status, clear)
    Canvas(Modifier.align(Alignment.TopStart).offsetPt(s, centerX - diameter / 2f, centerY - diameter / 2f)
        .size(s.pt(diameter), s.pt(diameter)).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = stroke * s.unit * density
        val inset = w / 2f
        val arcSize = Size(size.width - w, size.height - w)
        val gap = if (status.charging) 30f else 0f
        val (start, sweep) = batteryRingSweep(status.percent, status.charging, gap)
        val trackStart = if (status.charging) -90f + gap / 2f else -90f
        drawArc(Color.White.copy(alpha = .2f), trackStart, 360f - gap, false, Offset(inset, inset), arcSize,
            style = Stroke(w, cap = if (status.charging) StrokeCap.Round else StrokeCap.Butt))
        if (sweep > 0f) drawArc(color, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        drawPhoneGlyph(center, glyph * s.unit * density, Color.White)
        // iOS: a white bolt sits in the gap at 12 o'clock while charging.
        if (status.charging) drawBolt(Offset(center.x, inset), stroke * 2.4f * s.unit * density, Color.White)
    }
}

@Composable
private fun BoxScope.EmptyRing(s: AppleWidgetScale, centerX: Float, centerY: Float, diameter: Float, stroke: Float) {
    Canvas(Modifier.align(Alignment.TopStart).offsetPt(s, centerX - diameter / 2f, centerY - diameter / 2f).size(s.pt(diameter), s.pt(diameter))) {
        val w = stroke * s.unit * density
        drawCircle(Color.White.copy(alpha = .14f), (size.minDimension - w) / 2f, style = Stroke(w))
    }
}

/** SF "iphone": a rounded slab outline with the Dynamic Island. */
private fun DrawScope.drawPhoneGlyph(center: Offset, height: Float, color: Color) {
    val width = height * .52f
    val stroke = height * .085f
    drawRoundRect(color, Offset(center.x - width / 2f + stroke / 2f, center.y - height / 2f + stroke / 2f),
        Size(width - stroke, height - stroke), CornerRadius(width * .26f), style = Stroke(stroke))
    val island = Size(width * .36f, height * .07f)
    drawRoundRect(color, Offset(center.x - island.width / 2f, center.y - height / 2f + height * .1f), island, CornerRadius(island.height / 2f))
}

private fun DrawScope.drawBolt(center: Offset, height: Float, color: Color) {
    val w = height * .62f
    val left = center.x - w / 2f; val top = center.y - height / 2f
    val path = Path().apply {
        moveTo(left + w * .62f, top); lineTo(left, top + height * .58f); lineTo(left + w * .45f, top + height * .58f)
        lineTo(left + w * .3f, top + height); lineTo(left + w, top + height * .4f); lineTo(left + w * .55f, top + height * .4f); close()
    }
    drawPath(path, color)
}

/** SF "battery.100" with the level filled in Apple's colours and a bolt while charging. */
private fun DrawScope.drawBatteryGlyph(status: BatteryStatus, color: Color, pxPerPt: Float) {
    val body = Size(size.width - 2.2f * pxPerPt, size.height)
    val stroke = 1f * pxPerPt
    drawRoundRect(Color.White.copy(alpha = .45f), Offset(stroke / 2f, stroke / 2f), Size(body.width - stroke, body.height - stroke),
        CornerRadius(3.4f * pxPerPt), style = Stroke(stroke))
    drawRoundRect(Color.White.copy(alpha = .45f), Offset(body.width + .6f * pxPerPt, size.height * .32f),
        Size(1.6f * pxPerPt, size.height * .36f), CornerRadius(.8f * pxPerPt))
    val pad = 2f * pxPerPt
    val fullWidth = body.width - 2 * pad
    val level = (status.percent ?: 0).coerceIn(0, 100) / 100f
    if (level > 0f) drawRoundRect(color, Offset(pad, pad), Size(maxOf(fullWidth * level, 2f * pxPerPt), body.height - 2 * pad),
        CornerRadius(1.8f * pxPerPt))
    if (status.charging) {
        val h = body.height * 1.05f
        val c = Offset(body.width / 2f, body.height / 2f)
        val w = h * .62f
        val left = c.x - w / 2f; val top = c.y - h / 2f
        val bolt = Path().apply {
            moveTo(left + w * .62f, top); lineTo(left, top + h * .58f); lineTo(left + w * .45f, top + h * .58f)
            lineTo(left + w * .3f, top + h); lineTo(left + w, top + h * .4f); lineTo(left + w * .55f, top + h * .4f); close()
        }
        drawPath(bolt, Color.Black, style = Stroke(1.6f * pxPerPt), blendMode = BlendMode.Clear)
        drawPath(bolt, Color.White)
    }
}

/**
 * Registry entries (IosNativeWidgets.kt): Apple's Batteries "Status" widget in its three sizes.
 * Small / medium keep the Core.17 ids and keys (-100 / -101, native:battery:small|medium).
 */
internal fun batteryWidgetSpecs(): List<IosWidgetSpec> {
    fun spec(id: Int, family: NativeWidgetSize, featured: Int? = null) =
        IosWidgetSpec(id, NativeWidgetKind.BATTERY, "battery", family, "状态", "Status",
            "查看本机的电池状态。Android 不向 App 公开蓝牙配件的电量，其余位置保持为空。",
            "See the status of this phone’s battery. Android doesn’t share Bluetooth accessory levels with apps, so other slots stay empty.",
            featured = featured) { AppleBatteryWidget(family, modifier, preview) }
    return listOf(spec(-100, NativeWidgetSize.SMALL, featured = 40), spec(-101, NativeWidgetSize.MEDIUM), spec(-180, NativeWidgetSize.LARGE))
}

internal data class BatteryReading(val percent: Int?, val charging: Boolean)

internal fun batteryReading(intent: Intent?): BatteryReading {
    if (intent == null) return BatteryReading(null, false)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    return BatteryReading(if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
}

/** The phone's name for the large list (Settings ▸ About ▸ Device name), else its model. */
internal fun deviceDisplayName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
