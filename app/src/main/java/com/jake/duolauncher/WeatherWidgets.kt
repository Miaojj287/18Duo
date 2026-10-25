package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// ---------------------------------------------------------------------------------------
// Apple's six Weather widgets (iOS 17.2 → 26 gallery order): Forecast small / medium / large,
// Details, Daily Forecast, Sunrise and Sunset. Geometry in points on Apple's canvas, measured
// from Apple's own images (reference/widgets/SOURCES.md, docs/uiux-core17-widgets.md).
// ---------------------------------------------------------------------------------------

/** Content inset of Apple's iOS 17.2 Details / Daily / Sunrise-Sunset widgets (16 pt margin + SF side bearing). */
private const val INSET = 17f
/** Forecast widgets on iPhone Duo sit a little further in (HIG hero + newsroom video: text at 12.1–12.9 % of the width). */
private const val FORECAST_INSET = 19f

private val White = Color.White
private val Secondary = Color.White.copy(alpha = .62f)

/** Apple's four Weather widgets (Forecast comes in small / medium / large). */
internal enum class WeatherWidgetStyle(val widget: String, val nameZh: String, val nameEn: String, val descriptionZh: String, val descriptionEn: String) {
    FORECAST("weather", "预报", "Forecast", "查看某个位置的当前天气状况和预报。", "See the current weather conditions and forecast for a location."),
    DETAILS("weather-details", "详细信息", "Details", "查看降水概率、紫外线指数、风速等。", "See the chance of precipitation, UV index, wind, and more."),
    DAILY("weather-daily", "每日预报", "Daily Forecast", "查看某个位置的当前天气状况和每日预报。",
        "See the current weather conditions and daily forecast for a location."),
    SUN("weather-sun", "日出和日落", "Sunrise and Sunset", "查看你所在位置即将到来的日出和日落。", "See the upcoming sunrise and sunset for your location."),
}

/**
 * Registry entries (IosNativeWidgets.kt), in Apple's gallery page order. Forecast small / medium
 * keep the Core.17 ids and keys (-112 / -113, native:weather:small|medium) so placed widgets carry
 * over; the rest take the Weather block (-160 …).
 */
internal fun weatherWidgetSpecs(): List<IosWidgetSpec> {
    fun spec(id: Int, style: WeatherWidgetStyle, family: NativeWidgetSize, featured: Int? = null) =
        IosWidgetSpec(id, NativeWidgetKind.WEATHER, style.widget, family, style.nameZh, style.nameEn,
            style.descriptionZh, style.descriptionEn, featured = featured) {
            AppleWeatherWidget(style, family, bareModifier, preview, interactive)
        }
    return listOf(
        spec(-112, WeatherWidgetStyle.FORECAST, NativeWidgetSize.SMALL, featured = 50),
        spec(-113, WeatherWidgetStyle.FORECAST, NativeWidgetSize.MEDIUM),
        spec(-160, WeatherWidgetStyle.FORECAST, NativeWidgetSize.LARGE),
        spec(-161, WeatherWidgetStyle.DETAILS, NativeWidgetSize.SMALL),
        spec(-162, WeatherWidgetStyle.DAILY, NativeWidgetSize.SMALL),
        spec(-163, WeatherWidgetStyle.SUN, NativeWidgetSize.SMALL),
    )
}

internal fun isWeatherWidgetId(id: Int): Boolean = nativeWidgetSpec(id)?.app == NativeWidgetKind.WEATHER

private fun chinese() = Locale.getDefault().language == "zh"

@Composable
private fun nowSeconds(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - now % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** What the gallery shows before any location is chosen (iOS previews widgets with sample weather too). */
internal fun sampleWeather(nowMs: Long): WeatherView {
    val zone = ZoneId.systemDefault()
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val unit = temperatureUnitFor(Locale.getDefault())
    fun t(c: Double) = displayTemperature(c, unit)
    val hourCs = listOf(22.0, 23.0, 24.0, 24.0, 23.0, 21.0)
    val hourSymbols = listOf(WeatherSymbol.CLOUD_SUN, WeatherSymbol.SUN, WeatherSymbol.SUN, WeatherSymbol.CLOUD_SUN,
        WeatherSymbol.CLOUD, WeatherSymbol.CLOUD)
    val today = now.toLocalDate()
    val dayData = listOf(
        Triple(WeatherCondition.PARTLY_CLOUDY, 25.0, 17.0), Triple(WeatherCondition.CLEAR, 27.0, 18.0),
        Triple(WeatherCondition.CLOUDY, 24.0, 18.0), Triple(WeatherCondition.RAIN, 21.0, 16.0),
        Triple(WeatherCondition.PARTLY_CLOUDY, 23.0, 15.0), Triple(WeatherCondition.CLEAR, 26.0, 16.0),
        Triple(WeatherCondition.MOSTLY_CLEAR, 27.0, 18.0),
    )
    val noon = today.atTime(12, 10).atZone(zone).toEpochSecond()
    return WeatherView(
        zone = zone, temperature = t(22.0), condition = WeatherCondition.PARTLY_CLOUDY, isDay = true,
        high = t(25.0), low = t(17.0), feelsLike = t(23.0), windKmh = 9.0, precipitationChance = 10, uvIndex = 4,
        hours = hourCs.indices.map { i -> HourView(now.withMinute(0).plusHours(i.toLong()), t(hourCs[i]), hourSymbols[i]) },
        days = dayData.mapIndexed { i, (c, hi, lo) -> DayView(today.plusDays(i.toLong()), c, c.symbol(true), t(hi), t(lo), null) },
        sunrises = (0..2).map { noon - 6 * 3600 - 20 * 60 + it * 86_400L },
        sunsets = (0..2).map { noon + 6 * 3600 + 20 * 60 + it * 86_400L },
        nowSeconds = nowMs / 1000, fetchedAt = nowMs,
    )
}

/**
 * Entry point from [NativeWidget] for every weather kind. Placed widgets keep the forecast fresh
 * (only while Home is visible), ask for a location on first tap, then open the Weather app.
 */
@Composable
internal fun AppleWeatherWidget(style: WeatherWidgetStyle, size: NativeWidgetSize, modifier: Modifier, preview: Boolean, interactive: Boolean) {
    val context = LocalContext.current
    val state = rememberWeatherState(active = !preview)
    val nowMs = nowSeconds()
    val unit = remember { temperatureUnitFor(Locale.getDefault()) }
    val view = state.snapshot?.let { weatherView(it, nowMs, unit) } ?: if (preview) sampleWeather(nowMs) else null
    val place = when {
        state.snapshot != null || state.configured -> state.place ?: view?.zone?.let(::cityName)
        // The gallery's sample weather is captioned with Apple's sample city, not the user's own city,
        // so it cannot be mistaken for real local weather (round-3 review).
        preview -> uxText("库比蒂诺", "Cupertino")
        else -> cityName(ZoneId.systemDefault())
    }
    val tone = currentWidgetTone()
    val clear = tone == WidgetTone.CLEAR
    val configured = state.configured || preview
    val sky = view?.sky ?: WeatherSky.CLEAR_DAY
    val background = if (clear) AppleWidgetBackground.Glass(widgetColors(WidgetTone.CLEAR).background)
        else AppleWidgetBackground.Sky(Color(sky.top), Color(sky.bottom))
    val hostToken = remember { Any() }
    if (!preview) WeatherSetupHost(hostToken)
    val needsSetup = !state.configured || state.problem == WeatherProblem.LOCATION_DENIED
    val openApp = rememberWidgetAppOpener()
    val tap: (() -> Unit)? = if (!interactive) null else ({
        val app = if (needsSetup) null else installedWeatherApp(context)
        if (app != null) openApp(app)
        else WeatherSetupController.open(hostToken)
    })
    val tapModifier = if (tap == null) modifier else
        modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = tap)
    AppleWidgetCard(tapModifier, size, background, preview, clear) { s ->
        val symbolColor = if (clear) White else null
        when {
            !configured -> WeatherSetupFace(size, s)
            view == null -> WeatherPlaceholder(style, size, s, place, state)
            else -> when (style) {
                WeatherWidgetStyle.DETAILS -> DetailsFace(view, s, state.currentLocation, symbolColor)
                WeatherWidgetStyle.DAILY -> DailyFace(view, s, state.currentLocation, symbolColor)
                WeatherWidgetStyle.SUN -> SunFace(context, view, s, symbolColor)
                WeatherWidgetStyle.FORECAST -> ForecastFace(context, view, size, s, place.orEmpty(), state.currentLocation, symbolColor)
            }
        }
    }
}

// ---- Forecast (small / medium / large) ----------------------------------------------------

@Composable
private fun BoxScope.CityLine(s: AppleWidgetScale, place: String, currentLocation: Boolean, maxWidth: Float) {
    Row(Modifier.align(Alignment.TopStart).offsetPt(s, FORECAST_INSET, 0f).paddingFromBaseline(top = s.pt(28.8f)),
        verticalAlignment = Alignment.CenterVertically) {
        Text(place, style = appleTextStyle(s, 15f, FontWeight.SemiBold, White), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = s.pt(maxWidth)))
        if (currentLocation) {
            Spacer(Modifier.width(s.pt(3f)))
            WeatherGlyph(WeatherSymbol.LOCATION, s.pt(9f), monochrome = White)
        }
    }
}

@Composable
private fun BoxScope.Glyph(symbol: WeatherSymbol, s: AppleWidgetScale, box: Float, centerX: Float, centerY: Float, mono: Color?) {
    WeatherGlyph(symbol, s.pt(box), Modifier.align(Alignment.TopStart).offsetPt(s, centerX - box / 2f, centerY - box / 2f), mono)
}

@Composable
private fun BoxScope.HourlyRow(context: Context, view: WeatherView, s: AppleWidgetScale, top: Float, mono: Color?) {
    val chinese = chinese()
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    val hours = view.hours.take(6)
    val first = FORECAST_INSET + 18f
    val last = 338f - FORECAST_INSET - 18f
    hours.forEachIndexed { i, hour ->
        val cx = first + (last - first) * i / 5f
        val label = hourLabel(hour.time, chinese, twentyFour)
        // Chinese 12-hour labels ("上午11时") are twice as wide: a smaller size keeps the six apart (round-3 review).
        BaselineText(meridiemText(label.main, label.suffix), s, if (chinese && !twentyFour) 10f else 11.5f, FontWeight.SemiBold,
            White.copy(alpha = .9f), baseline = top + 14f, inset = cx - 169f, center = true)
        Glyph(hour.symbol, s, 18f, cx, top + 29.5f, mono)
        BaselineText(degrees(hour.temperature), s, 13f, FontWeight.SemiBold, White, baseline = top + 54.5f,
            inset = cx - 169f + 2f, center = true)
    }
}

@Composable
private fun BoxScope.ForecastFace(context: Context, view: WeatherView, size: NativeWidgetSize, s: AppleWidgetScale, place: String,
    currentLocation: Boolean, mono: Color?) {
    val chinese = chinese()
    val condition = view.condition.label(view.isDay, chinese)
    val hiLo = highLowLabel(view.high, view.low, chinese)
    val inset = FORECAST_INSET
    if (size == NativeWidgetSize.SMALL) {
        CityLine(s, place, currentLocation, 158f - 2 * inset - 12f)
        BaselineText(degrees(view.temperature), s, 42f, FontWeight.Normal, White, baseline = 69f, inset = inset - 1f)
        Glyph(view.symbol, s, 16f, inset + 7.5f, 100.5f, mono)
        BaselineText(condition, s, 12f, FontWeight.SemiBold, White, baseline = 122f, inset = inset, maxWidth = 158f - 2 * inset + 4f)
        BaselineText(hiLo, s, 13.5f, FontWeight.Medium, White, baseline = 140f, inset = inset, maxWidth = 158f - 2 * inset + 4f)
        return
    }
    CityLine(s, place, currentLocation, 170f)
    BaselineText(degrees(view.temperature), s, 42f, FontWeight.Normal, White, baseline = 69f, inset = inset - 1f)
    Glyph(view.symbol, s, 17f, 338f - inset - 8.5f, 24.5f, mono)
    BaselineText(condition, s, 12f, FontWeight.SemiBold, White, baseline = 52f, inset = inset, alignEnd = true, maxWidth = 140f)
    BaselineText(hiLo, s, 13.5f, FontWeight.Medium, White, baseline = 68.5f, inset = inset, alignEnd = true, maxWidth = 160f)
    HourlyRow(context, view, s, 86f, mono)
    if (size == NativeWidgetSize.LARGE) DailyList(view, s, mono)
}

/** Large: hairline, then six days with Apple's temperature range bars (today first, with a "now" dot). */
@Composable
private fun BoxScope.DailyList(view: WeatherView, s: AppleWidgetScale, mono: Color?) {
    val chinese = chinese()
    val locale = Locale.getDefault()
    val days = view.days.take(6)
    val today = Instant.ofEpochSecond(view.nowSeconds).atZone(view.zone).toLocalDate()
    val bars = rangeBars(days, view.temperature)
    val unit = remember { temperatureUnitFor(locale) }
    Box(Modifier.align(Alignment.TopStart).offsetPt(s, FORECAST_INSET, 159f).size(s.pt(338f - 2 * FORECAST_INSET), s.pt(.5f))
        .background(White.copy(alpha = .25f)))
    val barLeft = 160f; val barRight = 277f
    days.forEachIndexed { i, day ->
        val center = 181f + i * 29.5f
        val baseline = center + 5.3f
        BaselineText(dayLabel(day.date, today, locale, chinese), s, 14.5f, FontWeight.SemiBold, White, baseline, FORECAST_INSET, maxWidth = 56f)
        Glyph(day.symbol, s, 18f, 92f, center, mono)
        BaselineText(degrees(day.low), s, 14.5f, FontWeight.Medium, Secondary, baseline, inset = 338f - 152f, alignEnd = true)
        BaselineText(degrees(day.high), s, 14.5f, FontWeight.Medium, White, baseline, inset = FORECAST_INSET, alignEnd = true)
        val bar = bars[i]
        val low = Color(temperatureColor(toCelsius(day.low, unit)))
        val high = Color(temperatureColor(toCelsius(day.high, unit)))
        Canvas(Modifier.align(Alignment.TopStart).offsetPt(s, barLeft, center - 2f).size(s.pt(barRight - barLeft), s.pt(4f))) {
            val r = size.height / 2f
            drawRoundRect(Color.Black.copy(alpha = .16f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            val x0 = bar.start * size.width; val x1 = maxOf(bar.end * size.width, x0 + size.height)
            val brush = if (mono != null) Brush.linearGradient(listOf(mono, mono))
                else Brush.horizontalGradient(listOf(low, high), startX = x0, endX = x1)
            drawRoundRect(brush, Offset(x0, 0f), Size(x1 - x0, size.height), androidx.compose.ui.geometry.CornerRadius(r))
            bar.dot?.let { dot ->
                val x = (dot * size.width).coerceIn(x0 + r, x1 - r)
                drawCircle(Color.Black.copy(alpha = .25f), r * 1.55f, Offset(x, r))
                drawCircle(White, r * 1.1f, Offset(x, r))
            }
        }
    }
}

// ---- Details / Daily Forecast (small) ------------------------------------------------------

/** Shared header of Apple's Details and Daily Forecast widgets: big temperature, symbol, H / L. */
@Composable
private fun BoxScope.CompactHeader(view: WeatherView, s: AppleWidgetScale, currentLocation: Boolean, mono: Color?) {
    val chinese = chinese()
    if (currentLocation) Glyph(WeatherSymbol.LOCATION, s, 9f, 80f, 22f, White)
    Glyph(view.symbol, s, 16.5f, 158f - INSET - 8f, 22f, mono)
    BaselineText(degrees(view.temperature), s, 33f, FontWeight.Normal, White, baseline = 59.4f, inset = INSET - 1f)
    BaselineText(if (chinese) "最高${degrees(view.high)}" else "H:${degrees(view.high)}", s, 13f, FontWeight.SemiBold, White,
        baseline = 43.6f, inset = INSET + 1f, alignEnd = true)
    BaselineText(if (chinese) "最低${degrees(view.low)}" else "L:${degrees(view.low)}", s, 13f, FontWeight.SemiBold, White,
        baseline = 58.6f, inset = INSET + 1f, alignEnd = true)
}

private val COMPACT_ROW_BASELINES = listOf(79.3f, 100f, 120.7f, 141.4f)

@Composable
private fun BoxScope.DetailsFace(view: WeatherView, s: AppleWidgetScale, currentLocation: Boolean, mono: Color?) {
    val chinese = chinese()
    val locale = Locale.getDefault()
    CompactHeader(view, s, currentLocation, mono)
    val rows = listOf(
        (if (chinese) "降水" else "Precip") to (view.precipitationChance?.let { "$it%" } ?: "--"),
        (if (chinese) "风速" else "Wind") to (view.windKmh?.let { windLabel(it, locale, chinese) } ?: "--"),
        (if (chinese) "紫外线" else "UV Index") to (view.uvIndex?.toString() ?: "--"),
        (if (chinese) "体感温度" else "Feels Like") to (view.feelsLike?.let(::degrees) ?: "--"),
    )
    rows.forEachIndexed { i, (label, value) ->
        BaselineText(label, s, 13f, FontWeight.Medium, White, COMPACT_ROW_BASELINES[i], INSET, maxWidth = 74f)
        BaselineText(value, s, 13f, FontWeight.Medium, Secondary, COMPACT_ROW_BASELINES[i], INSET, alignEnd = true, maxWidth = 60f)
    }
}

@Composable
private fun BoxScope.DailyFace(view: WeatherView, s: AppleWidgetScale, currentLocation: Boolean, mono: Color?) {
    val chinese = chinese()
    val locale = Locale.getDefault()
    CompactHeader(view, s, currentLocation, mono)
    val today = Instant.ofEpochSecond(view.nowSeconds).atZone(view.zone).toLocalDate()
    // Apple's Daily Forecast lists the next four days (today is the header).
    view.days.filter { it.date.isAfter(today) }.take(4).forEachIndexed { i, day ->
        val baseline = COMPACT_ROW_BASELINES[i]
        BaselineText(dayLabel(day.date, today, locale, chinese), s, 13f, FontWeight.SemiBold, White, baseline, INSET, maxWidth = 40f)
        Glyph(day.symbol, s, 19f, 62.4f, baseline - 4.6f, mono)
        // Apple prints the low without a degree sign here ("30 39°").
        BaselineText("${day.low}", s, 13f, FontWeight.Medium, Secondary, baseline, inset = 158f - 113.1f, alignEnd = true)
        BaselineText(degrees(day.high), s, 13f, FontWeight.Medium, White, baseline, inset = INSET, alignEnd = true)
    }
}

// ---- Sunrise and Sunset (small) --------------------------------------------------------------

@Composable
private fun BoxScope.SunFace(context: Context, view: WeatherView, s: AppleWidgetScale, mono: Color?) {
    val chinese = chinese()
    val twentyFour = android.text.format.DateFormat.is24HourFormat(context)
    val events = sunEvents(view.nowSeconds, view.sunrises, view.sunsets)
    val curve = sunCurve(view.nowSeconds, view.sunrises, view.sunsets)
    val sunset = events.nextIsSunset
    Glyph(if (sunset) WeatherSymbol.SUNSET else WeatherSymbol.SUNRISE, s, 14f, INSET + 7f, 23.4f, mono ?: White)
    val title = when {
        chinese -> if (sunset) "日落" else "日出"
        else -> if (sunset) "SUNSET" else "SUNRISE"
    }
    BaselineText(title, s, 14f, FontWeight.SemiBold, White, baseline = 29.2f, inset = INSET + 16.5f)
    val next = events.next?.let { Instant.ofEpochSecond(it).atZone(view.zone) }
    val nextLabel = next?.let { clockLabel(it, chinese, twentyFour) } ?: HourLabel("--:--", "")
    BaselineText(meridiemText(nextLabel.main, nextLabel.suffix, .72f), s, 28f, FontWeight.Normal, White, baseline = 62.4f, inset = INSET - .5f)
    if (curve != null) Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val horizon = curve.horizon * h
        val path = Path()
        val steps = 64
        for (k in 0..steps) {
            val x = k / steps.toFloat()
            val px = x * w; val py = curve.y(x) * h
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        // Apple: night is only a shade darker; the curve is light above the horizon and dark below it.
        drawRect(Color.Black.copy(alpha = .09f), Offset(0f, horizon), Size(w, h - horizon))
        val stroke = Stroke(1.8f * s.unit * density, cap = StrokeCap.Round)
        clipRect(bottom = horizon) { drawPath(path, White.copy(alpha = .5f), style = stroke) }
        clipRect(top = horizon) { drawPath(path, Color.Black.copy(alpha = .22f), style = stroke) }
        drawLine(White.copy(alpha = .32f), Offset(0f, horizon), Offset(w, horizon), .9f * s.unit * density)
        val sx = curve.x(view.nowSeconds) * w
        val sy = curve.y(curve.x(view.nowSeconds)) * h
        val up = sy <= horizon
        val pt = s.unit * density
        if (up) drawCircle(Brush.radialGradient(listOf(White.copy(alpha = .55f), White.copy(alpha = 0f)), Offset(sx, sy), 11f * pt),
            11f * pt, Offset(sx, sy))
        drawCircle(if (up) White else White.copy(alpha = .5f), 4.8f * pt, Offset(sx, sy))
    }
    val following = events.following?.let { Instant.ofEpochSecond(it).atZone(view.zone) }
    val followingLabel = following?.let { clockLabel(it, chinese, twentyFour) } ?: HourLabel("--:--", "")
    val prefix = when {
        chinese -> if (sunset) "日出：" else "日落："
        else -> if (sunset) "Sunrise: " else "Sunset: "
    }
    BaselineText(meridiemText(prefix + followingLabel.main, followingLabel.suffix), s, 13f, FontWeight.Medium, White,
        baseline = 138f, inset = INSET - .5f, maxWidth = 158f - 2 * INSET)
}

// ---- Setup and placeholder faces -----------------------------------------------------------

@Composable
private fun BoxScope.WeatherSetupFace(size: NativeWidgetSize, s: AppleWidgetScale) {
    val (w, h) = appleWidgetCanvas(size)
    BaselineText(uxText("天气", "Weather"), s, 14f, FontWeight.SemiBold, White, baseline = 27.8f, inset = INSET)
    Glyph(WeatherSymbol.LOCATION, s, 22f, INSET + 11f, h / 2f, White)
    BaselineText(uxText("选取位置", "Choose Location"), s, if (size == NativeWidgetSize.SMALL) 15f else 17f, FontWeight.SemiBold, White,
        baseline = h - 37f, inset = INSET, maxWidth = w - 2 * INSET)
    BaselineText(uxText("轻点以设置", "Tap to set up"), s, 12f, FontWeight.Medium, Secondary,
        baseline = h - 19f, inset = INSET, maxWidth = w - 2 * INSET)
}

/** Apple's redacted placeholder while loading, plus a one-line reason when data could not be fetched. */
@Composable
private fun BoxScope.WeatherPlaceholder(style: WeatherWidgetStyle, size: NativeWidgetSize, s: AppleWidgetScale, place: String?, state: WeatherState) {
    val (w, h) = appleWidgetCanvas(size)
    if (place != null && style == WeatherWidgetStyle.FORECAST) BaselineText(place, s, 14f, FontWeight.SemiBold, White, 27.8f, INSET,
        maxWidth = minOf(w, 158f) - 2 * INSET)
    else Bar(s, INSET, 18f, 70f, 11f)
    Bar(s, INSET, 40f, 52f, 30f)
    if (size != NativeWidgetSize.SMALL) Bar(s, w - INSET - 76f, 18f, 76f, 11f)
    val reason = when (state.problem) {
        // The full English phrase is cut off on a small card ("No internet connecti…", round-3 review).
        WeatherProblem.OFFLINE -> uxText("无法连接网络", if (size == NativeWidgetSize.SMALL) "No Internet" else "No internet connection")
        WeatherProblem.LOCATION_OFF -> uxText("定位服务已关闭", "Location Services are off")
        WeatherProblem.LOCATION_DENIED -> uxText("轻点以允许使用位置", "Tap to allow location")
        WeatherProblem.LOCATION_UNAVAILABLE -> uxText("无法确定位置", "Location unavailable")
        WeatherProblem.SERVER -> uxText("天气暂时不可用", "Weather unavailable")
        null -> null
    }
    if (reason != null && !state.loading) BaselineText(reason, s, 12f, FontWeight.Medium, Secondary, baseline = h - 19f, inset = INSET,
        maxWidth = w - 2 * INSET)
    else {
        Bar(s, INSET, h - 46f, 64f, 10f)
        Bar(s, INSET, h - 28f, 92f, 10f)
    }
}

@Composable
private fun BoxScope.Bar(s: AppleWidgetScale, x: Float, y: Float, width: Float, height: Float) {
    Box(Modifier.align(Alignment.TopStart).offsetPt(s, x, y).size(s.pt(width), s.pt(height))
        .background(White.copy(alpha = .22f), RoundedCornerShape(s.pt(height / 2f))))
}
