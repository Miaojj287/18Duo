package com.jake.duolauncher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Locale

/*
 * Lock Screen accessory widgets (iOS 16–26 "锁屏小组件"), built from data the launcher already has:
 * weather (WeatherRepository), battery, calendar date / next event (CalendarContract when permitted),
 * next alarm (AlarmManager.nextAlarmClock) and a world clock. Lock Screen widgets are vibrant white
 * on the wallpaper with no platter of their own — in the recording they ride inside the glass slab.
 * Sizes are in the slab's units: a circular widget is Ø [LockSlabLayout.unit], a rectangular one two slots.
 */

/** One accessory widget, drawn into a box of the slot's size. [s] = unit / 60 pt: scales the type. */
@Composable
internal fun LockAccessoryWidget(kind: LockWidgetKind, unitDp: Float, preview: Boolean, modifier: Modifier = Modifier) {
    val s = unitDp / LockSlabSpec.UNIT_PT
    val label = uxText(kind.zh, kind.en)
    Box(modifier.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        when (kind) {
            LockWidgetKind.WEATHER -> WeatherAccessory(s, preview)
            LockWidgetKind.BATTERY -> BatteryAccessory(s, unitDp, preview)
            LockWidgetKind.CALENDAR_DATE -> DateAccessory(s)
            LockWidgetKind.NEXT_EVENT -> NextEventAccessory(s, preview)
            LockWidgetKind.ALARM -> AlarmAccessory(s, preview)
            LockWidgetKind.WORLD_CLOCK -> WorldClockAccessory(s)
        }
    }
}

private val LockInk = Color.White
private val LockInkDim = Color.White.copy(alpha = .72f)
private val LockTrack = Color.White.copy(alpha = .28f)

@Composable
private fun AccessoryText(text: String, s: Float, pt: Float, weight: FontWeight = FontWeight.SemiBold, color: Color = LockInk,
    modifier: Modifier = Modifier, maxLines: Int = 1) {
    val size = pt * s
    Text(text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = DuoFonts.forSize(size), fontWeight = weight, fontSize = size.sp,
            lineHeight = (size * 1.2f).sp, letterSpacing = duoTracking(size)))
}

// Weather: SF symbol + "26°" on the first line, the condition, then high/low (recording f001). --------

@Composable
private fun WeatherAccessory(s: Float, preview: Boolean) {
    val state = rememberWeatherState(active = !preview)
    val context = LocalContext.current
    val unit = remember { temperatureUnitFor(Locale.getDefault()) }
    val chinese = Locale.getDefault().language == "zh"
    val view = state.snapshot?.let { weatherView(it, System.currentTimeMillis(), unit) }
    Column(Modifier.padding(start = (4f * s).dp), horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val symbol = view?.symbol ?: WeatherSymbol.LOCATION
            Canvas(Modifier.size((17f * s).dp)) {
                drawWeatherSymbol(symbol, Offset.Zero, size.minDimension, WeatherSymbolPalette.mono(LockInk))
            }
            Spacer(Modifier.width((2f * s).dp))
            AccessoryText(view?.let { degrees(it.temperature) } ?: "--°", s, 16f)
        }
        AccessoryText(view?.condition?.label(view.isDay, chinese) ?: state.place
            ?: uxText("未设置位置", "No location"), s, 13f, FontWeight.Medium, maxLines = 1)
        AccessoryText(view?.let { highLowLabel(it.high, it.low, chinese) } ?: "", s, 13f, FontWeight.Medium, LockInkDim)
    }
    // Keep the repository warm from the lock screen too (it only refreshes when due).
    if (!preview) androidx.compose.runtime.LaunchedEffect(context) { WeatherRepository.refreshIfDue(context) }
}

// Battery: an open ring with the phone inside, a bolt while charging (recording: battery widget). -----

@Composable
private fun rememberLockBattery(): BatteryReading {
    val context = LocalContext.current
    return produceState(batteryReading(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))), context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { value = batteryReading(intent) }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitDispose { runCatching { context.unregisterReceiver(receiver) } }
    }.value
}

@Composable
private fun BatteryAccessory(s: Float, unitDp: Float, preview: Boolean) {
    val reading = rememberLockBattery()
    val percent = reading.percent ?: if (preview) 100 else null
    Canvas(Modifier.size(unitDp.dp).semantics { contentDescription = percent?.let { "$it%" } ?: "" }) {
        val stroke = 5f * s * density
        val inset = stroke / 2f + 1f * density
        val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
        val (start, sweep) = batteryRingSweep(percent, reading.charging, 70f)
        drawArc(LockTrack, start, if (reading.charging) 360f - 70f else 360f, false, Offset(inset, inset), arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(LockInk, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLockPhoneGlyph(c, size.height * .42f, LockInk)
        if (reading.charging) drawLockBolt(Offset(c.x, inset + size.height * .08f), size.height * .16f, LockInk)
    }
}

private fun DrawScope.drawLockPhoneGlyph(center: Offset, height: Float, color: Color) {
    val w = height * .52f
    val r = androidx.compose.ui.geometry.CornerRadius(height * .12f)
    drawRoundRect(color, Offset(center.x - w / 2f, center.y - height / 2f), Size(w, height), r)
    drawRoundRect(Color.Black.copy(alpha = .55f), Offset(center.x - w / 2f + w * .14f, center.y - height / 2f + height * .1f),
        Size(w * .72f, height * .78f), androidx.compose.ui.geometry.CornerRadius(height * .06f))
}

private fun DrawScope.drawLockBolt(top: Offset, height: Float, color: Color) {
    val w = height * .55f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(top.x + w * .1f, top.y); lineTo(top.x - w * .45f, top.y + height * .58f); lineTo(top.x - w * .02f, top.y + height * .58f)
        lineTo(top.x - w * .1f, top.y + height); lineTo(top.x + w * .45f, top.y + height * .42f); lineTo(top.x + w * .02f, top.y + height * .42f)
        close()
    }
    drawPath(path, color)
}

// Calendar date: weekday over the day number (iOS circular Calendar widget). ---------------------------

@Composable
private fun DateAccessory(s: Float) {
    val now = rememberMinuteTime()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AccessoryText(now.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()).uppercase(Locale.getDefault()),
            s, 11f, FontWeight.SemiBold, LockInkDim)
        AccessoryText("${now.dayOfMonth}", s, 26f, FontWeight.Medium)
    }
}

// Next event: title + start time; asks for nothing itself (the editor's sheet explains permission). -----

private fun nextEvent(context: Context, now: ZonedDateTime): CalendarEvent? {
    if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val start = now.toInstant().toEpochMilli()
    val end = now.plusDays(7).toInstant().toEpochMilli()
    return runCatching {
        val projection = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR)
        CalendarContract.Instances.query(context.contentResolver, projection, start, end)?.use { cursor ->
            var best: CalendarEvent? = null
            while (cursor.moveToNext()) {
                val event = CalendarEvent(cursor.getString(0).orEmpty().ifBlank { uxText("（无标题）", "(No title)") },
                    cursor.getLong(1), cursor.getLong(2), cursor.getInt(3) == 1, cursor.getInt(4))
                if (event.end < start) continue
                if (best == null || event.begin < best.begin) best = event
            }
            best
        }
    }.getOrNull()
}

@Composable
private fun NextEventAccessory(s: Float, preview: Boolean) {
    val context = LocalContext.current
    val now = rememberMinuteTime()
    val granted = context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val event = produceState<CalendarEvent?>(null, context, now.minute, granted) {
        value = withContext(Dispatchers.IO) { nextEvent(context, now) }
    }.value ?: if (preview && !granted) sampleCalendarEvents(now.toLocalDate(), now.zone).firstOrNull() else null
    val use24 = rememberIs24HourFormat()
    Column(Modifier.padding(start = (4f * s).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size((12f * s).dp)) {
                drawRoundRect(LockInkDim, Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(size.width * .2f))
                drawRect(LockInk, Offset(0f, 0f), Size(size.width, size.height * .3f))
            }
            Spacer(Modifier.width((4f * s).dp))
            AccessoryText(uxText("日程", "Calendar").uppercase(Locale.getDefault()), s, 11f, FontWeight.SemiBold, LockInkDim)
        }
        when {
            !granted -> AccessoryText(uxText("需要日历权限", "Needs calendar access"), s, 13f, FontWeight.Medium, maxLines = 2)
            event == null -> AccessoryText(uxText("本周没有日程", "No events this week"), s, 13f, FontWeight.Medium, maxLines = 2)
            else -> {
                AccessoryText(event.title, s, 15f)
                AccessoryText(if (event.allDay) uxText("全天", "All day") else
                    eventStartLabel(event, now.zone, use24), s, 13f, FontWeight.Medium, LockInkDim)
            }
        }
    }
}

// Next alarm: bell, the alarm time, its day/countdown (AlarmManager.nextAlarmClock). -----------------

@Composable
private fun AlarmAccessory(s: Float, preview: Boolean) {
    val context = LocalContext.current
    val now = rememberMinuteTime()
    val trigger = produceState<Long?>(null, context) {
        fun read() = runCatching { context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime }.getOrNull()
        value = read()
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, intent: Intent?) { value = read() } }
        runCatching { context.registerReceiver(receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) }
        val poll = launch { while (true) { delay(60_000L); value = read() } }
        awaitDispose { poll.cancel(); runCatching { context.unregisterReceiver(receiver) } }
    }.value ?: if (preview) now.toLocalDate().plusDays(1).atTime(7, 0).atZone(now.zone).toInstant().toEpochMilli() else null
    val use24 = rememberIs24HourFormat()
    Column(Modifier.padding(start = (4f * s).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size((12f * s).dp)) { drawLockBell(LockInk) }
            Spacer(Modifier.width((4f * s).dp))
            AccessoryText(uxText("闹钟", "Alarm").uppercase(Locale.getDefault()), s, 11f, FontWeight.SemiBold, LockInkDim)
        }
        if (trigger == null) AccessoryText(uxText("没有闹钟", "No alarm"), s, 13f, FontWeight.Medium)
        else {
            val summary = alarmSummary(trigger, now)
            AccessoryText(digitalClockText(Instant.ofEpochMilli(trigger).atZone(now.zone), use24), s, 20f, FontWeight.Medium)
            AccessoryText("${summary.day} · ${summary.countdown}", s, 13f, FontWeight.Medium, LockInkDim)
        }
    }
}

private fun DrawScope.drawLockBell(color: Color) {
    val w = size.width; val h = size.height
    val body = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * .15f, h * .72f); lineTo(w * .22f, h * .62f); lineTo(w * .22f, h * .42f)
        arcTo(androidx.compose.ui.geometry.Rect(w * .22f, h * .12f, w * .78f, h * .72f), 180f, 180f, false)
        lineTo(w * .78f, h * .62f); lineTo(w * .85f, h * .72f); close()
    }
    drawPath(body, color)
    drawArc(color, 0f, 180f, true, Offset(w * .38f, h * .68f), Size(w * .24f, h * .24f))
}

// World clock: the second city of the project's world-clock list, its code and time. -------------------

@Composable
private fun WorldClockAccessory(s: Float) {
    val now = rememberMinuteTime()
    val zone = remember(now.zone) { cityDigitalZone(now.zone) }
    val use24 = rememberIs24HourFormat()
    val there = now.withZoneSameInstant(zone)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AccessoryText(cityAbbreviation(zone), s, 11f, FontWeight.SemiBold, LockInkDim)
        AccessoryText(digitalClockText(there, use24), s, 17f, FontWeight.Medium)
    }
}
