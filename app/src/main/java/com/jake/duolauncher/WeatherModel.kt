package com.jake.duolauncher

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Pure weather model for 18Duo's Apple-style Weather widgets: Open-Meteo request/response,
// WMO code → Apple condition / SF-style symbol / sky gradient, unit and label formatting,
// range bars and the sun path. No Android types, so everything here is JVM-unit-tested with
// recorded Open-Meteo JSON (app/src/test/resources/openmeteo-*.json).
// ---------------------------------------------------------------------------------------

/** The SF Symbols Apple's Weather widgets use (multicolour), drawn by [drawWeatherSymbol]. */
internal enum class WeatherSymbol {
    SUN, MOON_STARS, CLOUD_SUN, CLOUD_MOON, CLOUD, FOG, DRIZZLE, RAIN, HEAVY_RAIN, SLEET, SNOW, BOLT_RAIN, WIND,
    SUNRISE, SUNSET, LOCATION,
}

/** Apple's widget backgrounds: a vertical sky gradient chosen by condition and day/night. */
internal enum class WeatherSky(val top: Long, val bottom: Long) {
    // Clear/partly cloudy day: iPhone Duo newsroom video (San Francisco, #2A558B → #4479B8), Apple's
    // iOS 17.2 Sunrise/Sunset preview (#26477D → #5385B6) and the HIG hero (#2D6094 → #6C98C3).
    CLEAR_DAY(0xFF2A578E, 0xFF5088C1),
    CLEAR_NIGHT(0xFF0C1630, 0xFF2B3B5E),
    // Overcast: Apple's iOS 17.2 Details / Daily Forecast widgets (#646E7A → #4E5A64, darker at the foot).
    CLOUDY_DAY(0xFF646E7A, 0xFF4E5A64),
    CLOUDY_NIGHT(0xFF2B3038, 0xFF1C2027),
    RAIN_DAY(0xFF55606E, 0xFF3A4552),
    RAIN_NIGHT(0xFF262C35, 0xFF171B21),
    SNOW_DAY(0xFF7A8694, 0xFF5C6876),
    SNOW_NIGHT(0xFF2E343D, 0xFF1E232A),
    FOG_DAY(0xFF7C8086, 0xFF62686E),
    FOG_NIGHT(0xFF2F3236, 0xFF202326),
    STORM_DAY(0xFF3A404E, 0xFF242832),
    STORM_NIGHT(0xFF1E2028, 0xFF121419),
}

/** Apple Weather's condition vocabulary (WeatherKit), reduced to what WMO codes can express. */
internal enum class WeatherCondition(
    private val dayZh: String, private val dayEn: String, private val nightZh: String, private val nightEn: String,
    private val daySymbol: WeatherSymbol, private val nightSymbol: WeatherSymbol,
    private val daySky: WeatherSky, private val nightSky: WeatherSky,
) {
    CLEAR("晴朗", "Sunny", "晴朗", "Clear", WeatherSymbol.SUN, WeatherSymbol.MOON_STARS, WeatherSky.CLEAR_DAY, WeatherSky.CLEAR_NIGHT),
    MOSTLY_CLEAR("大部晴朗", "Mostly Sunny", "大部晴朗", "Mostly Clear", WeatherSymbol.SUN, WeatherSymbol.MOON_STARS,
        WeatherSky.CLEAR_DAY, WeatherSky.CLEAR_NIGHT),
    PARTLY_CLOUDY("局部多云", "Partly Cloudy", "局部多云", "Partly Cloudy", WeatherSymbol.CLOUD_SUN, WeatherSymbol.CLOUD_MOON,
        WeatherSky.CLEAR_DAY, WeatherSky.CLEAR_NIGHT),
    CLOUDY("多云", "Cloudy", "多云", "Cloudy", WeatherSymbol.CLOUD, WeatherSymbol.CLOUD, WeatherSky.CLOUDY_DAY, WeatherSky.CLOUDY_NIGHT),
    FOGGY("有雾", "Foggy", "有雾", "Foggy", WeatherSymbol.FOG, WeatherSymbol.FOG, WeatherSky.FOG_DAY, WeatherSky.FOG_NIGHT),
    DRIZZLE("毛毛雨", "Drizzle", "毛毛雨", "Drizzle", WeatherSymbol.DRIZZLE, WeatherSymbol.DRIZZLE, WeatherSky.RAIN_DAY, WeatherSky.RAIN_NIGHT),
    FREEZING_DRIZZLE("冻毛毛雨", "Freezing Drizzle", "冻毛毛雨", "Freezing Drizzle", WeatherSymbol.SLEET, WeatherSymbol.SLEET,
        WeatherSky.RAIN_DAY, WeatherSky.RAIN_NIGHT),
    RAIN("雨", "Rain", "雨", "Rain", WeatherSymbol.RAIN, WeatherSymbol.RAIN, WeatherSky.RAIN_DAY, WeatherSky.RAIN_NIGHT),
    HEAVY_RAIN("大雨", "Heavy Rain", "大雨", "Heavy Rain", WeatherSymbol.HEAVY_RAIN, WeatherSymbol.HEAVY_RAIN,
        WeatherSky.RAIN_DAY, WeatherSky.RAIN_NIGHT),
    FREEZING_RAIN("冻雨", "Freezing Rain", "冻雨", "Freezing Rain", WeatherSymbol.SLEET, WeatherSymbol.SLEET,
        WeatherSky.RAIN_DAY, WeatherSky.RAIN_NIGHT),
    FLURRIES("小雪", "Flurries", "小雪", "Flurries", WeatherSymbol.SNOW, WeatherSymbol.SNOW, WeatherSky.SNOW_DAY, WeatherSky.SNOW_NIGHT),
    SNOW("雪", "Snow", "雪", "Snow", WeatherSymbol.SNOW, WeatherSymbol.SNOW, WeatherSky.SNOW_DAY, WeatherSky.SNOW_NIGHT),
    HEAVY_SNOW("大雪", "Heavy Snow", "大雪", "Heavy Snow", WeatherSymbol.SNOW, WeatherSymbol.SNOW, WeatherSky.SNOW_DAY, WeatherSky.SNOW_NIGHT),
    THUNDERSTORMS("雷暴", "Thunderstorms", "雷暴", "Thunderstorms", WeatherSymbol.BOLT_RAIN, WeatherSymbol.BOLT_RAIN,
        WeatherSky.STORM_DAY, WeatherSky.STORM_NIGHT),
    WINDY("有风", "Windy", "有风", "Windy", WeatherSymbol.WIND, WeatherSymbol.WIND, WeatherSky.CLEAR_DAY, WeatherSky.CLEAR_NIGHT);

    fun label(day: Boolean, chinese: Boolean): String = when {
        chinese -> if (day) dayZh else nightZh
        else -> if (day) dayEn else nightEn
    }
    fun symbol(day: Boolean): WeatherSymbol = if (day) daySymbol else nightSymbol
    fun sky(day: Boolean): WeatherSky = if (day) daySky else nightSky
}

/** Sustained wind from which Apple reports "Windy" for an otherwise fair sky (Beaufort 6). */
internal const val WINDY_KMH = 39.0

/** WMO weather interpretation code (Open-Meteo `weather_code`) → Apple condition. */
internal fun weatherCondition(code: Int, windKmh: Double? = null): WeatherCondition {
    val condition = when (code) {
        0 -> WeatherCondition.CLEAR
        1 -> WeatherCondition.MOSTLY_CLEAR
        2 -> WeatherCondition.PARTLY_CLOUDY
        3 -> WeatherCondition.CLOUDY
        45, 48 -> WeatherCondition.FOGGY
        51, 53, 55 -> WeatherCondition.DRIZZLE
        56, 57 -> WeatherCondition.FREEZING_DRIZZLE
        61, 63, 80, 81 -> WeatherCondition.RAIN
        65, 82 -> WeatherCondition.HEAVY_RAIN
        66, 67 -> WeatherCondition.FREEZING_RAIN
        71, 85 -> WeatherCondition.FLURRIES
        73, 77 -> WeatherCondition.SNOW
        75, 86 -> WeatherCondition.HEAVY_SNOW
        95, 96, 99 -> WeatherCondition.THUNDERSTORMS
        else -> WeatherCondition.CLOUDY
    }
    val fair = condition == WeatherCondition.CLEAR || condition == WeatherCondition.MOSTLY_CLEAR ||
        condition == WeatherCondition.PARTLY_CLOUDY
    return if (fair && windKmh != null && windKmh >= WINDY_KMH) WeatherCondition.WINDY else condition
}

// ---- Open-Meteo snapshot -----------------------------------------------------------------

internal data class WeatherNow(
    val time: Long, val temperatureC: Double, val apparentC: Double?, val humidity: Int?, val code: Int,
    val isDay: Boolean, val windKmh: Double?,
)

internal data class WeatherHour(val time: Long, val temperatureC: Double, val code: Int, val isDay: Boolean,
    val precipitationChance: Int?, val uvIndex: Double?)

internal data class WeatherDay(val time: Long, val code: Int, val maxC: Double, val minC: Double,
    val sunrise: Long?, val sunset: Long?, val uvMax: Double?, val precipitationChance: Int?)

/** One Open-Meteo forecast; all times are epoch seconds, [timezone] is the location's IANA zone. */
internal data class WeatherSnapshot(
    val fetchedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val now: WeatherNow,
    val hours: List<WeatherHour>,
    val days: List<WeatherDay>,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())
}

/** Approximate coordinates only ever leave the device rounded to 0.01° (≈ 1 km). */
internal fun roundCoordinate(value: Double): Double = (value * 100.0).roundToInt() / 100.0

internal const val OPEN_METEO_FORECAST = "https://api.open-meteo.com/v1/forecast"
internal const val OPEN_METEO_GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"

/** The single forecast request: current conditions, 48 hours, 10 days, sunrise/sunset. Celsius/km/h. */
internal fun openMeteoForecastUrl(latitude: Double, longitude: Double): String =
    "$OPEN_METEO_FORECAST?latitude=${roundCoordinate(latitude)}&longitude=${roundCoordinate(longitude)}" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m" +
        "&hourly=temperature_2m,weather_code,is_day,precipitation_probability,uv_index" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,precipitation_probability_max" +
        "&timezone=auto&timeformat=unixtime&forecast_days=10&forecast_hours=48"

/** City search (typed by the user) — the only other request the Weather widgets make. */
internal fun openMeteoGeocodingUrl(query: String, chinese: Boolean): String =
    "$OPEN_METEO_GEOCODING?name=${URLEncoder.encode(query.trim(), "UTF-8")}&count=6" +
        "&language=${if (chinese) "zh" else "en"}&format=json"

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
private fun Any?.list(): List<Any?>? = this as? List<Any?>
private fun Any?.num(): Double? = (this as? Number)?.toDouble()

/** Parses an Open-Meteo `/v1/forecast` response (timeformat=unixtime); null when it is unusable. */
internal fun parseOpenMeteoForecast(json: String, fetchedAt: Long): WeatherSnapshot? = runCatching {
    val root = MiniJson.parse(json).obj() ?: return null
    if (root["error"] == true) return null
    val current = root["current"].obj() ?: return null
    val now = WeatherNow(
        time = current["time"].num()?.toLong() ?: return null,
        temperatureC = current["temperature_2m"].num() ?: return null,
        apparentC = current["apparent_temperature"].num(),
        humidity = current["relative_humidity_2m"].num()?.roundToInt(),
        code = current["weather_code"].num()?.toInt() ?: return null,
        isDay = (current["is_day"].num() ?: 1.0) >= .5,
        windKmh = current["wind_speed_10m"].num(),
    )
    val hourly = root["hourly"].obj()
    val hours = hourly?.let { h ->
        val times = h["time"].list().orEmpty()
        times.indices.mapNotNull { i ->
            WeatherHour(
                time = times[i].num()?.toLong() ?: return@mapNotNull null,
                temperatureC = h["temperature_2m"].list()?.getOrNull(i).num() ?: return@mapNotNull null,
                code = h["weather_code"].list()?.getOrNull(i).num()?.toInt() ?: return@mapNotNull null,
                isDay = (h["is_day"].list()?.getOrNull(i).num() ?: 1.0) >= .5,
                precipitationChance = h["precipitation_probability"].list()?.getOrNull(i).num()?.roundToInt(),
                uvIndex = h["uv_index"].list()?.getOrNull(i).num(),
            )
        }
    }.orEmpty()
    val daily = root["daily"].obj()
    val days = daily?.let { d ->
        val times = d["time"].list().orEmpty()
        times.indices.mapNotNull { i ->
            WeatherDay(
                time = times[i].num()?.toLong() ?: return@mapNotNull null,
                code = d["weather_code"].list()?.getOrNull(i).num()?.toInt() ?: return@mapNotNull null,
                maxC = d["temperature_2m_max"].list()?.getOrNull(i).num() ?: return@mapNotNull null,
                minC = d["temperature_2m_min"].list()?.getOrNull(i).num() ?: return@mapNotNull null,
                sunrise = d["sunrise"].list()?.getOrNull(i).num()?.toLong(),
                sunset = d["sunset"].list()?.getOrNull(i).num()?.toLong(),
                uvMax = d["uv_index_max"].list()?.getOrNull(i).num(),
                precipitationChance = d["precipitation_probability_max"].list()?.getOrNull(i).num()?.roundToInt(),
            )
        }
    }.orEmpty()
    WeatherSnapshot(fetchedAt, root["latitude"].num() ?: 0.0, root["longitude"].num() ?: 0.0,
        root["timezone"] as? String ?: "UTC", now, hours, days)
}.getOrNull()

/** A place found by the city search. */
internal data class WeatherPlace(val name: String, val detail: String, val latitude: Double, val longitude: Double)

internal fun parseOpenMeteoGeocoding(json: String): List<WeatherPlace> = runCatching {
    val results = MiniJson.parse(json).obj()?.get("results").list().orEmpty()
    results.mapNotNull { item ->
        val o = item.obj() ?: return@mapNotNull null
        val name = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val detail = listOfNotNull(o["admin1"] as? String, o["country"] as? String)
            .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
        WeatherPlace(name, detail, o["latitude"].num() ?: return@mapNotNull null, o["longitude"].num() ?: return@mapNotNull null)
    }
}.getOrDefault(emptyList())

// ---- Units ----------------------------------------------------------------------------------

internal enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

private val FAHRENHEIT_REGIONS = setOf("US", "BS", "BZ", "KY", "PW", "LR", "FM", "MH", "PR", "GU", "VI", "AS", "MP")
private val MPH_REGIONS = setOf("US", "GB", "LR", "MM", "PR", "GU", "VI", "AS", "MP")

/** The locale's temperature unit; Android's per-locale preference (`-u-mu-`) wins, as on iOS. */
internal fun temperatureUnitFor(locale: Locale): TemperatureUnit {
    when (runCatching { locale.getUnicodeLocaleType("mu") }.getOrNull()) {
        "fahrenhe" -> return TemperatureUnit.FAHRENHEIT
        "celsius" -> return TemperatureUnit.CELSIUS
    }
    return if (locale.country.uppercase(Locale.ROOT) in FAHRENHEIT_REGIONS) TemperatureUnit.FAHRENHEIT else TemperatureUnit.CELSIUS
}

internal fun usesMph(locale: Locale): Boolean = locale.country.uppercase(Locale.ROOT) in MPH_REGIONS

/** Apple rounds to whole degrees; −0 prints as 0. */
internal fun displayTemperature(celsius: Double, unit: TemperatureUnit): Int {
    val value = if (unit == TemperatureUnit.FAHRENHEIT) celsius * 9.0 / 5.0 + 32.0 else celsius
    return Math.round(value).toInt().let { if (it == 0) 0 else it }
}

internal fun degrees(value: Int): String = "$value°"

internal fun highLowLabel(high: Int, low: Int, chinese: Boolean): String =
    if (chinese) "最高${degrees(high)} 最低${degrees(low)}" else "H:${degrees(high)} L:${degrees(low)}"

internal fun windLabel(kmh: Double, locale: Locale, chinese: Boolean): String = when {
    usesMph(locale) -> "${(kmh / 1.609344).roundToInt()} mph"
    chinese -> "${kmh.roundToInt()}公里/时"
    else -> "${kmh.roundToInt()} km/h"
}

// ---- Labels ---------------------------------------------------------------------------------

/** Hour column label: English 12-hour "9" + small-caps "AM"; 24-hour "09"; Chinese "9时" / "上午9时". */
internal data class HourLabel(val main: String, val suffix: String)

internal fun hourLabel(time: ZonedDateTime, chinese: Boolean, twentyFourHour: Boolean): HourLabel {
    val hour = time.hour
    val twelve = (hour % 12).let { if (it == 0) 12 else it }
    return when {
        chinese && twentyFourHour -> HourLabel("${hour}时", "")
        chinese -> HourLabel("${if (hour < 12) "上午" else "下午"}${twelve}时", "")
        twentyFourHour -> HourLabel("%02d".format(hour), "")
        else -> HourLabel("$twelve", if (hour < 12) "AM" else "PM")
    }
}

/** Clock time for the Sunrise/Sunset widget: "5:13" + "PM", "17:13", or Chinese "下午5:13". */
internal fun clockLabel(time: ZonedDateTime, chinese: Boolean, twentyFourHour: Boolean): HourLabel {
    val minute = "%02d".format(time.minute)
    val twelve = (time.hour % 12).let { if (it == 0) 12 else it }
    return when {
        twentyFourHour -> HourLabel("${time.hour}:$minute", "")
        chinese -> HourLabel("${if (time.hour < 12) "上午" else "下午"}$twelve:$minute", "")
        else -> HourLabel("$twelve:$minute", if (time.hour < 12) "AM" else "PM")
    }
}

internal fun dayLabel(date: LocalDate, today: LocalDate, locale: Locale, chinese: Boolean): String = when {
    date == today -> if (chinese) "今天" else "Today"
    chinese -> "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
    else -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
}

// ---- What the widgets draw ----------------------------------------------------------------

internal data class HourView(val time: ZonedDateTime, val temperature: Int, val symbol: WeatherSymbol)

internal data class DayView(val date: LocalDate, val condition: WeatherCondition, val symbol: WeatherSymbol,
    val high: Int, val low: Int, val precipitationChance: Int?)

internal data class WeatherView(
    val zone: ZoneId,
    val temperature: Int,
    val condition: WeatherCondition,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val feelsLike: Int?,
    val windKmh: Double?,
    val precipitationChance: Int?,
    val uvIndex: Int?,
    val hours: List<HourView>,
    /** Today first. */
    val days: List<DayView>,
    val sunrises: List<Long>,
    val sunsets: List<Long>,
    val nowSeconds: Long,
    val fetchedAt: Long,
) {
    val symbol: WeatherSymbol get() = condition.symbol(isDay)
    val sky: WeatherSky get() = condition.sky(isDay)
}

/** A cache older than this draws "now" from the hourly forecast instead of the stale current reading. */
internal const val WEATHER_CURRENT_FRESH_MS = 60 * 60 * 1000L

/**
 * Turns a snapshot into what the widgets show at [nowMs]. Past hours and days drop off, so an
 * offline Home keeps showing a sensible forecast from the cache; null once the cache no longer
 * covers today (the widgets then fall back to Apple's placeholder).
 */
internal fun weatherView(snapshot: WeatherSnapshot, nowMs: Long, unit: TemperatureUnit, hourCount: Int = 6): WeatherView? {
    val zone = snapshot.zone
    val nowSeconds = nowMs / 1000
    val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
    val days = snapshot.days.map { it to Instant.ofEpochSecond(it.time).atZone(zone).toLocalDate() }
        .filter { (_, date) -> !date.isBefore(today) }
    val todayData = days.firstOrNull()?.takeIf { it.second == today }?.first ?: return null
    val currentHourStart = nowSeconds - Math.floorMod(nowSeconds, 3600L)
    val upcoming = snapshot.hours.filter { it.time >= currentHourStart }
    val fresh = nowMs - snapshot.fetchedAt < WEATHER_CURRENT_FRESH_MS
    val nowHour = upcoming.firstOrNull()?.takeIf { it.time == currentHourStart }
    val temperatureC = if (fresh || nowHour == null) snapshot.now.temperatureC else nowHour.temperatureC
    val code = if (fresh || nowHour == null) snapshot.now.code else nowHour.code
    val isDay = if (fresh || nowHour == null) snapshot.now.isDay else nowHour.isDay
    if (!fresh && nowHour == null && nowMs - snapshot.fetchedAt > 3 * WEATHER_CURRENT_FRESH_MS) return null
    val wind = if (fresh) snapshot.now.windKmh else null
    val condition = weatherCondition(code, wind)
    val temperature = displayTemperature(temperatureC, unit)
    // Apple keeps the current temperature inside the day's range.
    val high = maxOf(displayTemperature(todayData.maxC, unit), temperature)
    val low = minOf(displayTemperature(todayData.minC, unit), temperature)
    return WeatherView(
        zone = zone, temperature = temperature, condition = condition, isDay = isDay, high = high, low = low,
        feelsLike = (if (fresh) snapshot.now.apparentC else null)?.let { displayTemperature(it, unit) },
        windKmh = wind,
        precipitationChance = (nowHour ?: upcoming.firstOrNull())?.precipitationChance ?: todayData.precipitationChance,
        uvIndex = ((nowHour ?: upcoming.firstOrNull())?.uvIndex ?: todayData.uvMax)?.roundToInt(),
        hours = upcoming.take(hourCount).map { hour ->
            HourView(Instant.ofEpochSecond(hour.time).atZone(zone), displayTemperature(hour.temperatureC, unit),
                weatherCondition(hour.code).symbol(hour.isDay))
        },
        days = days.map { (day, date) ->
            val dayCondition = weatherCondition(day.code)
            DayView(date, dayCondition, dayCondition.symbol(true), displayTemperature(day.maxC, unit),
                displayTemperature(day.minC, unit), day.precipitationChance)
        },
        sunrises = snapshot.days.mapNotNull { it.sunrise },
        sunsets = snapshot.days.mapNotNull { it.sunset },
        nowSeconds = nowSeconds,
        fetchedAt = snapshot.fetchedAt,
    )
}

// ---- Daily range bars -----------------------------------------------------------------------

/** Position of one day's low→high segment (and today's current-temperature dot) on the shared scale. */
internal data class RangeBar(val start: Float, val end: Float, val dot: Float?)

internal fun rangeBars(days: List<DayView>, currentTemperature: Int?): List<RangeBar> {
    if (days.isEmpty()) return emptyList()
    val lowest = days.minOf { it.low }
    val highest = days.maxOf { it.high }
    val span = (highest - lowest).coerceAtLeast(1).toFloat()
    return days.mapIndexed { index, day ->
        val start = (day.low - lowest) / span
        val end = (day.high - lowest) / span
        val dot = if (index == 0 && currentTemperature != null)
            ((currentTemperature - lowest) / span).coerceIn(start, end) else null
        RangeBar(start, end.coerceAtLeast(start), dot)
    }
}

/** Apple's temperature colour ramp for the range bars (blue cold → green → yellow → orange → red hot), by °C. */
private val TEMPERATURE_STOPS = listOf(
    -10.0 to 0xFF5E5CE6, 0.0 to 0xFF5AC8FA, 10.0 to 0xFF63D7B6, 16.0 to 0xFFA8DD4A,
    21.0 to 0xFFFFD60A, 27.0 to 0xFFFF9F0A, 34.0 to 0xFFFF453A,
)

internal fun temperatureColor(celsius: Double): Long {
    val first = TEMPERATURE_STOPS.first(); val last = TEMPERATURE_STOPS.last()
    if (celsius <= first.first) return first.second
    if (celsius >= last.first) return last.second
    val upper = TEMPERATURE_STOPS.indexOfFirst { it.first >= celsius }
    val (t0, c0) = TEMPERATURE_STOPS[upper - 1]; val (t1, c1) = TEMPERATURE_STOPS[upper]
    val f = ((celsius - t0) / (t1 - t0)).toFloat()
    fun channel(shift: Int): Long {
        val a = (c0 shr shift) and 0xFF; val b = (c1 shr shift) and 0xFF
        return (a + (b - a) * f).roundToInt().toLong().coerceIn(0, 255)
    }
    return (0xFFL shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

internal fun toCelsius(value: Int, unit: TemperatureUnit): Double =
    if (unit == TemperatureUnit.FAHRENHEIT) (value - 32) * 5.0 / 9.0 else value.toDouble()

// ---- Sunrise / Sunset -------------------------------------------------------------------------

/** What the Sunrise and Sunset widget headlines: the next event and the one after it. */
internal data class SunEvents(val nextIsSunset: Boolean, val next: Long?, val following: Long?)

internal fun sunEvents(nowSeconds: Long, sunrises: List<Long>, sunsets: List<Long>): SunEvents {
    val events = (sunrises.map { it to false } + sunsets.map { it to true }).sortedBy { it.first }
    val upcoming = events.filter { it.first > nowSeconds }
    val next = upcoming.firstOrNull() ?: return SunEvents(nextIsSunset = false, next = null, following = null)
    val following = upcoming.firstOrNull { it.second != next.second }
    return SunEvents(next.second, next.first, following?.first)
}

/**
 * The sun's curve across the widget: the width spans [window] seconds (16 h, measured from
 * Apple's widget: a 9.3-hour December day spans 59 % of it) centred on solar noon — or, at night,
 * on solar midnight so the sun stays on the widget below the horizon. The curve is the sun's
 * 24-hour cosine, offset so it crosses [horizon] exactly at sunrise and sunset: a long summer day
 * rides high and wide, a winter day barely clears the line. Returns (x, y) fractions.
 */
internal data class SunCurve(val noon: Long, val dayLength: Long, val center: Long = noon, val horizon: Float = .57f,
    val amplitude: Float = .14f, val window: Long = 16 * 3600L, val ceiling: Float = SUN_CURVE_CEILING) {
    private val horizonLevel: Double get() = cos(PI * dayLength.coerceIn(0, 86_400) / 86_400.0)
    /**
     * Apple keeps the arc below the big time digits (baseline 62.4 of 158 pt): the noon height
     * above the horizon is eased into [ceiling] — unchanged up to a winter day's 0.08, then
     * approaching (never crossing) the limit, so a 16-hour summer day no longer runs through "6:30".
     */
    private val heightScale: Double get() {
        val rise = amplitude * (1 - horizonLevel)
        val limit = (horizon - ceiling).toDouble()
        val knee = minOf(.08, limit)
        if (rise <= knee || rise <= 0.0) return 1.0
        val eased = knee + (limit - knee) * kotlin.math.tanh((rise - knee) / (limit - knee).coerceAtLeast(1e-6))
        return eased / rise
    }
    fun y(x: Float): Float {
        val fromNoon = (center - noon) + (x - .5) * window
        return (horizon - amplitude * heightScale * (cos(2 * PI * fromNoon / 86_400.0) - horizonLevel)).toFloat()
    }
    fun x(timeSeconds: Long): Float {
        val offset = Math.floorMod(timeSeconds - center + 43_200L, 86_400L) - 43_200L
        return .5f + offset / window.toFloat()
    }
}

/** Highest point (fraction of the card) the sun's arc may reach: 72 pt, clear of the time above it. */
internal const val SUN_CURVE_CEILING = .455f

internal fun sunCurve(nowSeconds: Long, sunrises: List<Long>, sunsets: List<Long>): SunCurve? {
    // The day whose sunrise/sunset pair is nearest to now.
    val pairs = sunrises.mapNotNull { rise -> sunsets.firstOrNull { it > rise && it - rise < 86_400 }?.let { rise to it } }
    val pair = pairs.minByOrNull { (rise, set) -> kotlin.math.abs((rise + set) / 2 - nowSeconds) } ?: return null
    val curve = SunCurve(noon = (pair.first + pair.second) / 2, dayLength = pair.second - pair.first)
    val x = curve.x(nowSeconds)
    if (x in .06f..0.94f) return curve
    val midnight = curve.noon + if (nowSeconds >= curve.noon) 43_200L else -43_200L
    return curve.copy(center = midnight)
}

// ---- Refresh policy ---------------------------------------------------------------------------

internal const val WEATHER_REFRESH_MS = 30 * 60 * 1000L

/** Fetch when the cache is ≥ 30 min old, backing off 2, 4, 8 … ≤ 30 min after failures. */
internal fun weatherRefreshDue(nowMs: Long, fetchedAt: Long?, lastAttempt: Long?, failures: Int): Boolean {
    if (fetchedAt != null && nowMs - fetchedAt in 0 until WEATHER_REFRESH_MS) return false
    if (lastAttempt == null || failures <= 0) return true
    val backoff = minOf(WEATHER_REFRESH_MS, 2 * 60 * 1000L shl (failures - 1).coerceAtMost(4))
    return nowMs - lastAttempt !in 0 until backoff
}

// ---- Minimal JSON reader (Open-Meteo responses are plain objects/arrays/numbers) ----------------

/** A small strict JSON reader: objects → Map, arrays → List, numbers → Double. Throws on malformed input. */
internal object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        require(reader.pos == text.length) { "Trailing characters at ${reader.pos}" }
        return value
    }

    private class Reader(val s: String) {
        var pos = 0

        fun skipWhitespace() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            skipWhitespace()
            require(pos < s.length) { "Unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, pos)) { "Bad literal at $pos" }
            pos += word.length
            return result
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (s.getOrNull(pos) == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                require(s.getOrNull(pos) == '"') { "Expected key at $pos" }
                val key = string()
                skipWhitespace()
                require(s.getOrNull(pos) == ':') { "Expected ':' at $pos" }
                pos++
                map[key] = value()
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (s.getOrNull(pos) == ']') { pos++; return list }
            while (true) {
                list += value()
                skipWhitespace()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun string(): String {
            pos++
            val out = StringBuilder()
            while (true) {
                require(pos < s.length) { "Unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        val e = s[pos++]
                        when (e) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b'); 'f' -> out.append('\u000C'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> { out.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> throw IllegalArgumentException("Bad escape at $pos")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return s.substring(start, pos).toDouble()
        }
    }
}
