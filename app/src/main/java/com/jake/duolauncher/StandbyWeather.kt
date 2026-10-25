package com.jake.duolauncher

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.Instant
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Weather for StandBy's Widgets and Weather faces (round 24, owner photos 3 and 5 of the iPhone Duo cover), from the
 * same Open-Meteo repository the Home weather widgets use. Null [view] = no forecast yet (location not set, offline).
 */
@Immutable
internal data class StandbyWeather(
    val view: WeatherView?,
    val place: String?,
    val currentLocation: Boolean,
    val configured: Boolean,
    val loading: Boolean,
)

/** Kept fresh while StandBy is on screen; a location granted for the Home widgets is used without asking again. */
@Composable
internal fun rememberStandbyWeather(): StandbyWeather {
    val context = LocalContext.current
    val state = rememberWeatherState(active = true)
    LaunchedEffect(state.configured) {
        if (!state.configured && WeatherRepository.hasLocationPermission(context)) WeatherRepository.useCurrentLocation(context)
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - nowMs % 60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val unit = remember { temperatureUnitFor(Locale.getDefault()) }
    val view = state.snapshot?.let { weatherView(it, nowMs, unit) }
    return StandbyWeather(view, state.place ?: view?.zone?.let(::cityName), state.currentLocation, state.configured, state.loading)
}

private fun weatherMessage(weather: StandbyWeather?, zh: Boolean): String = when {
    weather?.loading == true -> if (zh) "正在获取天气…" else "Loading weather…"
    weather?.configured == true -> if (zh) "天气暂不可用" else "Weather unavailable"
    else -> if (zh) "在桌面天气小组件中设置位置" else "Set a location in a Weather widget"
}

/** Photo 3: a clear night is a saturated blue (brighter than the Home widgets' sky). */
private fun standbySky(view: WeatherView?): Pair<Color, Color> {
    val sky = view?.sky ?: WeatherSky.CLEAR_NIGHT
    return if (sky == WeatherSky.CLEAR_NIGHT) Color(0xFF0B2196) to Color(0xFF2346D8) else Color(sky.top) to Color(sky.bottom)
}

// Weather face ----------------------------------------------------------------------------------------------

/** Photo 3, fractions of W / H. */
internal object StandbyWeatherGeometry {
    const val LEFT = .075f
    const val CONDITION_Y = .1f
    const val CONDITION_CAP = .042f
    const val RANGE_Y = .172f
    const val RANGE_CAP = .04f
    const val TEMPERATURE_Y = .385f
    const val TEMPERATURE_CAP = .25f
    const val TIME_RIGHT = .935f
    const val TIME_Y = .172f
    const val TIME_CAP = .052f
    const val DAY_PITCH = .178f
    const val DAY_Y = .6f
    const val DAY_CAP = .038f
    const val DAY_SYMBOL_Y = .715f
    const val DAY_SYMBOL = .095f
    const val HIGH_Y = .82f
    const val LOW_Y = .875f
    const val TEMP_CAP = .031f
    const val MOON_X = .665f
    const val MOON_Y = .15f
    const val MOON_R = .075f
}

@Composable
internal fun StandbyWeatherFace(frame: StandbyFrame, weather: StandbyWeather?, night: Boolean, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    val stars = remember { List(110) { Triple(Random(it * 7919).nextFloat(), Random(it * 104729).nextFloat(), Random(it * 31).nextFloat()) } }
    val view = weather?.view
    val ink = if (night) StandbyStyles.nightRed else Color(0xFFE8EDFF)
    val palette = if (night) WeatherSymbolPalette.mono(StandbyStyles.nightRed) else WeatherSymbolPalette.Multicolor
    Box(modifier.fillMaxSize().testTag("standby-weather").semantics {
        contentDescription = view?.let { "${it.condition.label(it.isDay, frame.zh)} ${degrees(it.temperature)}" } ?: weatherMessage(weather, frame.zh)
    }) {
        Canvas(Modifier.fillMaxSize()) {
            if (night) { drawRect(Color.Black); return@Canvas }
            val (top, bottom) = standbySky(view)
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
            val w = size.width; val h = size.height; val g = StandbyWeatherGeometry
            if (view != null && !view.isDay) {
                for ((x, y, s) in stars) drawCircle(Color.White.copy(alpha = .35f + .55f * s), h * (.0018f + .0032f * s), Offset(x * w, y * h))
                if (view.condition == WeatherCondition.CLEAR || view.condition == WeatherCondition.MOSTLY_CLEAR) {
                    val moon = Offset(w * g.MOON_X, h * g.MOON_Y)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFB4C4FF), Color(0xFF8EA4F4)), moon, h * g.MOON_R), h * g.MOON_R, moon)
                    drawCircle(Color(0xFF7F95E8).copy(alpha = .5f), h * g.MOON_R * .22f, moon + Offset(-h * .02f, h * .018f))
                    drawCircle(Color(0xFF7F95E8).copy(alpha = .4f), h * g.MOON_R * .14f, moon + Offset(h * .03f, -h * .022f))
                }
            } else if (view != null && view.condition.symbol(true) == WeatherSymbol.SUN) {
                val sun = Offset(w * g.MOON_X, h * g.MOON_Y)
                drawCircle(Brush.radialGradient(listOf(Color(0x66FFF2B0), Color.Transparent), sun, h * .45f), h * .45f, sun)
            }
        }
        // Offscreen: the symbols' knock-out gaps clear only this layer, never the sky under it.
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val w = size.width; val h = size.height; val g = StandbyWeatherGeometry
            translate(frame.shift.x, frame.shift.y) {
                val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
                val time = tools.text(StandbyFonts.sans(600), h * g.TIME_CAP, ink, Paint.Align.RIGHT)
                drawText(time, "$hours:$minutes", w * g.TIME_RIGHT, baselineFor(h * g.TIME_Y, h * g.TIME_CAP))
                if (view == null) {
                    val message = tools.text(StandbyFonts.sans(600), h * .045f, ink, Paint.Align.CENTER, sample = "E")
                    drawText(message, weatherMessage(weather, frame.zh), w / 2f, baselineFor(h / 2f, h * .045f))
                    return@translate
                }
                val left = w * g.LEFT
                val conditionCap = h * g.CONDITION_CAP
                drawWeatherSymbol(view.symbol, Offset(left - conditionCap * .1f, h * g.CONDITION_Y - conditionCap * .75f),
                    conditionCap * 1.5f, palette)
                val condition = tools.text(StandbyFonts.sans(600), conditionCap, ink, sample = "E")
                drawText(condition, view.condition.label(view.isDay, frame.zh), left + conditionCap * 1.55f,
                    baselineFor(h * g.CONDITION_Y, conditionCap))
                val range = tools.text(StandbyFonts.sans(600), h * g.RANGE_CAP, ink, sample = "E")
                drawText(range, highLowLabel(view.high, view.low, frame.zh), left, baselineFor(h * g.RANGE_Y, h * g.RANGE_CAP))
                val big = tools.text(StandbyFonts.sans(500), h * g.TEMPERATURE_CAP, ink)
                big.getTextBounds("2", 0, 1, tools.bounds)
                drawText(big, degrees(view.temperature), left - tools.bounds.left, baselineFor(h * g.TEMPERATURE_Y, h * g.TEMPERATURE_CAP))
                val today = Instant.ofEpochSecond(view.nowSeconds).atZone(view.zone).toLocalDate()
                view.days.filter { it.date.isAfter(today) }.take(5).forEachIndexed { index, day ->
                    val x = left + index * w * g.DAY_PITCH
                    val dayPaint = tools.text(StandbyFonts.sans(600), h * g.DAY_CAP, ink, sample = "E")
                    drawText(dayPaint, dayLabel(day.date, today, frame.locale, frame.zh), x, baselineFor(h * g.DAY_Y, h * g.DAY_CAP))
                    drawWeatherSymbol(day.condition.symbol(true), Offset(x, h * (g.DAY_SYMBOL_Y - g.DAY_SYMBOL / 2f)), h * g.DAY_SYMBOL, palette)
                    val tempPaint = tools.text(StandbyFonts.sans(600), h * g.TEMP_CAP, ink)
                    drawText(tempPaint, degrees(day.high), x + h * .012f, baselineFor(h * g.HIGH_Y, h * g.TEMP_CAP))
                    tempPaint.color = ink.copy(alpha = .72f).toArgb()
                    drawText(tempPaint, degrees(day.low), x + h * .012f, baselineFor(h * g.LOW_Y, h * g.TEMP_CAP))
                }
            }
        }
    }
}

// Widgets face ----------------------------------------------------------------------------------------------

/** Photo 5: round clock and month on the left, the weather widget filling the right. Fractions of W / H. */
internal object StandbyWidgetsGeometry {
    const val CLOCK_X = .18f
    const val CLOCK_Y = .285f
    const val CLOCK_R = .165f
    const val MONTH_FIRST_COLUMN = .068f
    const val MONTH_PITCH = .0345f
    const val MONTH_LABEL_Y = .55f
    const val MONTH_WEEKDAY_Y = .603f
    const val MONTH_FIRST_ROW = .66f
    const val MONTH_ROW_PITCH = .052f
    const val MONTH_CAP = .024f
    const val MONTH_TODAY = .05f
    const val WEATHER_LEFT = .36f
    const val CITY_Y = .2f
    const val CITY_CAP = .045f
    const val TEMPERATURE_Y = .43f
    const val TEMPERATURE_CAP = .27f
    const val SYMBOL_Y = .625f
    const val SYMBOL = .075f
    const val CONDITION_Y = .72f
    const val CONDITION_CAP = .045f
    const val RANGE_Y = .855f
    const val RANGE_CAP = .085f
}

@Composable
internal fun StandbyWidgetsFace(frame: StandbyFrame, weather: StandbyWeather?, night: Boolean, modifier: Modifier = Modifier) {
    val tools = remember { StandbyPaint() }
    val ink = if (night) StandbyStyles.nightRed else Color.White
    val dim = ink.copy(alpha = .55f)
    val red = if (night) StandbyStyles.nightRed else Color(0xFFFF3B30)
    val second = if (night) StandbyStyles.nightRed else Color(0xFFFF9500)
    val palette = if (night) WeatherSymbolPalette.mono(StandbyStyles.nightRed) else WeatherSymbolPalette.Multicolor
    val view = weather?.view
    Box(modifier.fillMaxSize().testTag("standby-widgets").semantics { contentDescription = standbyWidgetsDescription(frame, view) }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black)
            val w = size.width; val h = size.height; val g = StandbyWidgetsGeometry
            translate(frame.shift.x, frame.shift.y) {
                drawRoundClock(tools, Offset(w * g.CLOCK_X, h * g.CLOCK_Y), h * g.CLOCK_R, frame, ink, dim, second)
                drawWidgetsMonth(tools, frame, ink, dim, red)
                val left = w * g.WEATHER_LEFT
                if (view == null) {
                    val message = tools.text(StandbyFonts.sans(600), h * .04f, ink, sample = "E")
                    drawText(message, weatherMessage(weather, frame.zh), left, baselineFor(h * .45f, h * .04f))
                    return@translate
                }
                val city = tools.text(StandbyFonts.sans(700), h * g.CITY_CAP, ink, sample = "E")
                val place = weather.place.orEmpty()
                drawText(city, place, left, baselineFor(h * g.CITY_Y, h * g.CITY_CAP))
                if (weather.currentLocation) drawWeatherSymbol(WeatherSymbol.LOCATION,
                    Offset(left + city.measureText(place) + h * .015f, h * g.CITY_Y - h * g.CITY_CAP * .6f), h * g.CITY_CAP * 1.2f,
                    WeatherSymbolPalette.mono(ink))
                val big = tools.text(StandbyFonts.sans(600), h * g.TEMPERATURE_CAP, ink)
                big.getTextBounds("2", 0, 1, tools.bounds)
                drawText(big, degrees(view.temperature), left - tools.bounds.left, baselineFor(h * g.TEMPERATURE_Y, h * g.TEMPERATURE_CAP))
                drawWeatherSymbol(view.symbol, Offset(left, h * (g.SYMBOL_Y - g.SYMBOL / 2f)), h * g.SYMBOL, palette)
                val condition = tools.text(StandbyFonts.sans(700), h * g.CONDITION_CAP, ink, sample = "E")
                drawText(condition, view.condition.label(view.isDay, frame.zh), left, baselineFor(h * g.CONDITION_Y, h * g.CONDITION_CAP))
                drawWidgetsRange(tools, view, left, frame.zh, ink)
            }
        }
    }
}

private fun standbyWidgetsDescription(frame: StandbyFrame, view: WeatherView?): String {
    val (hours, minutes) = standbyClockParts(frame.minute, frame.use24h)
    return listOfNotNull("$hours:$minutes", view?.let { "${it.condition.label(it.isDay, frame.zh)} ${degrees(it.temperature)}" })
        .joinToString(", ")
}

/** "最/高 31°  最/低 24°" with the labels stacked (photo 5); "H 31°  L 24°" elsewhere. */
private fun DrawScope.drawWidgetsRange(tools: StandbyPaint, view: WeatherView, left: Float, zh: Boolean, ink: Color) {
    val h = size.height; val g = StandbyWidgetsGeometry
    val cap = h * g.RANGE_CAP
    val y = h * g.RANGE_Y
    var x = left
    for ((labels, value) in listOf((if (zh) "最高" else "H") to view.high, (if (zh) "最低" else "L") to view.low)) {
        val small = tools.text(StandbyFonts.sans(700), cap * .36f, ink, Paint.Align.CENTER, sample = "高")
        if (zh) {
            drawText(small, labels.substring(0, 1), x + cap * .18f, baselineFor(y - cap * .24f, cap * .36f))
            drawText(small, labels.substring(1), x + cap * .18f, baselineFor(y + cap * .24f, cap * .36f))
        } else drawText(small, labels, x + cap * .18f, baselineFor(y, cap * .36f))
        x += cap * .46f
        val number = tools.text(StandbyFonts.sans(500), cap, ink)
        drawText(number, degrees(value), x, baselineFor(y, cap))
        x += number.measureText(degrees(value)) + cap * .35f
    }
}

/** Photo 5's round clock: figures 1–12, minute ticks on the rim, white hands, orange second hand. */
private fun DrawScope.drawRoundClock(tools: StandbyPaint, c: Offset, r: Float, frame: StandbyFrame, ink: Color, dim: Color,
    secondColor: Color) {
    for (tick in 0 until 60) {
        val v = standbyClockVector(tick * 6f)
        val major = tick % 5 == 0
        drawLine(if (major) ink else dim, c + v * (r * if (major) .9f else .93f), c + v * r,
            r * if (major) .022f else .012f, StrokeCap.Butt)
    }
    val numeral = tools.text(StandbyFonts.sans(600), r * .17f, ink, Paint.Align.CENTER)
    for (hour in 1..12) {
        val p = c + standbyClockVector(hour * 30f) * (r * .74f)
        drawText(numeral, hour.toString(), p.x, baselineFor(p.y, r * .17f))
    }
    val now = frame.now
    val angles = standbyHandAngles(now.hour, now.minute, now.second)
    fun hand(angle: Float, length: Float) {
        val v = standbyClockVector(angle)
        drawLine(ink, c, c + v * (r * .14f), r * .025f, StrokeCap.Round)
        drawLine(ink, c + v * (r * .14f), c + v * (r * length), r * .06f, StrokeCap.Round)
    }
    hand(angles.hour, .5f)
    hand(angles.minute, .82f)
    val s = standbyClockVector(angles.second)
    drawLine(secondColor, c - s * (r * .15f), c + s * (r * .92f), r * .016f, StrokeCap.Round)
    drawCircle(Color.Black, r * .04f, c)
    drawCircle(secondColor, r * .03f, c, style = Stroke(r * .016f))
}

/** Photo 5's month: "10月 │ 九月十七", weekday letters, five weeks, today on a red disc. */
private fun DrawScope.drawWidgetsMonth(tools: StandbyPaint, frame: StandbyFrame, ink: Color, dim: Color, red: Color) {
    val w = size.width; val h = size.height; val g = StandbyWidgetsGeometry
    val date = frame.minute.toLocalDate()
    val cap = h * g.MONTH_CAP
    fun columnX(i: Int) = w * (g.MONTH_FIRST_COLUMN + i * g.MONTH_PITCH)
    val labelLeft = columnX(0) - w * g.MONTH_PITCH * .4f
    val label = tools.text(StandbyFonts.sans(700), cap, red, sample = "E")
    val baseline = baselineFor(h * g.MONTH_LABEL_Y, cap)
    val month = standbyMonthLabel(date, frame.locale)
    drawText(label, month, labelLeft, baseline)
    standbyLunarMonthDay(date, frame.locale)?.let { lunar ->
        var x = labelLeft + label.measureText(month) + cap * .45f
        drawLine(dim, Offset(x, baseline - cap * 1.05f), Offset(x, baseline + cap * .15f), cap * .08f)
        x += cap * .45f
        label.color = ink.toArgb()
        label.typeface = StandbyFonts.sans(600)
        drawText(label, lunar, x, baseline)
    }
    val letters = tools.text(StandbyFonts.sans(600), cap * .95f, ink, Paint.Align.CENTER, sample = "E")
    standbyWeekdayLetters(frame.firstDay, frame.locale).forEachIndexed { i, letter ->
        drawText(letters, letter, columnX(i), baselineFor(h * g.MONTH_WEEKDAY_Y, cap * .95f))
    }
    val dayPaint = tools.text(StandbyFonts.sans(600), cap, ink, Paint.Align.CENTER)
    standbyMonthCells(date, frame.firstDay).forEachIndexed { index, day ->
        if (day == null) return@forEachIndexed
        val center = Offset(columnX(index % 7), h * (g.MONTH_FIRST_ROW + index / 7 * g.MONTH_ROW_PITCH))
        dayPaint.color = if (day == date.dayOfMonth) { drawCircle(red, h * g.MONTH_TODAY / 2f, center); Color.White.toArgb() }
            else ink.toArgb()
        drawText(dayPaint, day.toString(), center.x, baselineFor(center.y, cap))
    }
}
