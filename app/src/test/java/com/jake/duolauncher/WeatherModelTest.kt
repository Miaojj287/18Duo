package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class WeatherModelTest {
    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource(name)) { "missing fixture $name" }.readText()

    private val shanghai = ZoneId.of("Asia/Shanghai")
    /** 2026-10-20 14:15 +08:00, the `current.time` of the recorded Shanghai forecast. */
    private val fetchedAt = ZonedDateTime.of(2026, 10, 20, 14, 15, 0, 0, shanghai).toInstant().toEpochMilli()
    private val snapshot by lazy { requireNotNull(parseOpenMeteoForecast(fixture("openmeteo-forecast-shanghai.json"), fetchedAt)) }

    @Test fun `parses an Open-Meteo unixtime forecast`() {
        assertEquals("Asia/Shanghai", snapshot.timezone)
        assertEquals(shanghai, snapshot.zone)
        assertEquals(24.6, snapshot.now.temperatureC, 1e-9)
        assertEquals(25.3, snapshot.now.apparentC!!, 1e-9)
        assertEquals(2, snapshot.now.code)
        assertTrue(snapshot.now.isDay)
        assertEquals(11.2, snapshot.now.windKmh!!, 1e-9)
        assertEquals(48, snapshot.hours.size)
        assertEquals(10, snapshot.days.size)
        assertEquals(ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, shanghai).toEpochSecond(), snapshot.hours.first().time)
        assertEquals(LocalDate.of(2026, 10, 20), java.time.Instant.ofEpochSecond(snapshot.days.first().time).atZone(shanghai).toLocalDate())
        assertEquals(-1.4, snapshot.days[7].minC, 1e-9)
        assertNotNull(snapshot.days.first().sunrise)
        assertEquals(45, snapshot.hours[18].precipitationChance)
    }

    @Test fun `parses a response recorded from the live API on the QA emulator`() {
        // Recorded 2026-10-20 10:45 +08:00 by the app itself (Use Current Location, Shanghai) — exactly the
        // request in openMeteoForecastUrl, so a parameter Open-Meteo stopped accepting would show up here.
        val json = fixture("openmeteo-forecast-recorded-shanghai-20261020.json")
        val recorded = requireNotNull(parseOpenMeteoForecast(json, 1_792_464_300_000L))
        assertEquals("Asia/Shanghai", recorded.timezone)
        assertEquals(48, recorded.hours.size)
        assertEquals(10, recorded.days.size)
        assertTrue(recorded.hours.all { it.uvIndex != null && it.precipitationChance != null })
        assertTrue(recorded.days.all { it.sunrise != null && it.sunset != null && it.sunrise!! < it.sunset!! })
        val view = requireNotNull(weatherView(recorded, 1_792_464_300_000L, TemperatureUnit.CELSIUS))
        assertEquals(WeatherCondition.CLOUDY, view.condition)
        assertEquals(26, view.temperature)
        assertTrue(view.low <= view.temperature && view.temperature <= view.high)
        assertEquals(10, view.hours.first().time.hour)
    }

    @Test fun `rejects unusable responses`() {
        assertNull(parseOpenMeteoForecast("""{"error":true,"reason":"Cannot initialize WeatherVariable from invalid String value"}""", 0))
        assertNull(parseOpenMeteoForecast("""{"latitude":1}""", 0))
        assertNull(parseOpenMeteoForecast("not json", 0))
        assertNull(parseOpenMeteoForecast("""{"current":{"time":1,"temperature_2m":3}}""", 0))
    }

    @Test fun `fresh view uses the current reading and keeps it inside today's range`() {
        val view = weatherView(snapshot, fetchedAt + 5 * 60_000, TemperatureUnit.CELSIUS)!!
        assertEquals(25, view.temperature)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, view.condition)
        assertEquals(WeatherSymbol.CLOUD_SUN, view.symbol)
        assertEquals(WeatherSky.CLEAR_DAY, view.sky)
        assertEquals(25, view.high)
        assertEquals(17, view.low)
        assertEquals(25, view.feelsLike)
        assertEquals(6, view.hours.size)
        assertEquals(14, view.hours.first().time.hour)
        assertEquals(LocalDate.of(2026, 10, 20), view.days.first().date)
        assertEquals(5, view.precipitationChance)
        assertEquals(3, view.uvIndex)
    }

    @Test fun `stale cache shows the forecast hour instead of an old reading`() {
        val later = fetchedAt + 3 * 60 * 60_000L // 17:15, offline since 14:15
        val view = weatherView(snapshot, later, TemperatureUnit.CELSIUS)!!
        assertEquals(17, view.hours.first().time.hour)
        assertEquals(displayTemperature(snapshot.hours[3].temperatureC, TemperatureUnit.CELSIUS), view.temperature)
        assertFalse(view.isDay)
        assertEquals(WeatherSky.CLEAR_NIGHT, view.sky)
        assertNull(view.feelsLike)
        assertNull(view.windKmh)
        // Next morning: yesterday's daily row drops off and today's (rain) leads.
        val tomorrow = weatherView(snapshot, fetchedAt + 20 * 60 * 60_000L, TemperatureUnit.CELSIUS)!!
        assertEquals(LocalDate.of(2026, 10, 21), tomorrow.days.first().date)
        assertEquals(WeatherCondition.RAIN, tomorrow.days.first().condition)
        // Beyond the forecast: placeholder instead of made-up weather.
        assertNull(weatherView(snapshot, fetchedAt + 12L * 24 * 60 * 60_000L, TemperatureUnit.CELSIUS))
    }

    @Test fun `WMO codes map to Apple conditions symbols and skies`() {
        val expected = mapOf(0 to WeatherCondition.CLEAR, 1 to WeatherCondition.MOSTLY_CLEAR, 2 to WeatherCondition.PARTLY_CLOUDY,
            3 to WeatherCondition.CLOUDY, 45 to WeatherCondition.FOGGY, 48 to WeatherCondition.FOGGY, 51 to WeatherCondition.DRIZZLE,
            56 to WeatherCondition.FREEZING_DRIZZLE, 61 to WeatherCondition.RAIN, 65 to WeatherCondition.HEAVY_RAIN,
            66 to WeatherCondition.FREEZING_RAIN, 71 to WeatherCondition.FLURRIES, 73 to WeatherCondition.SNOW,
            75 to WeatherCondition.HEAVY_SNOW, 77 to WeatherCondition.SNOW, 80 to WeatherCondition.RAIN, 82 to WeatherCondition.HEAVY_RAIN,
            85 to WeatherCondition.FLURRIES, 86 to WeatherCondition.HEAVY_SNOW, 95 to WeatherCondition.THUNDERSTORMS,
            99 to WeatherCondition.THUNDERSTORMS)
        expected.forEach { (code, condition) -> assertEquals("code $code", condition, weatherCondition(code)) }
        assertEquals(WeatherCondition.CLOUDY, weatherCondition(42))
        assertEquals(WeatherCondition.WINDY, weatherCondition(1, 45.0))
        assertEquals(WeatherCondition.RAIN, weatherCondition(61, 60.0))
        assertEquals(WeatherSymbol.SUN, WeatherCondition.CLEAR.symbol(true))
        assertEquals(WeatherSymbol.MOON_STARS, WeatherCondition.CLEAR.symbol(false))
        assertEquals(WeatherSymbol.CLOUD_MOON, WeatherCondition.PARTLY_CLOUDY.symbol(false))
        assertEquals(WeatherSymbol.BOLT_RAIN, WeatherCondition.THUNDERSTORMS.symbol(true))
        assertEquals(WeatherSky.CLOUDY_DAY, WeatherCondition.CLOUDY.sky(true))
        assertEquals(WeatherSky.RAIN_NIGHT, WeatherCondition.DRIZZLE.sky(false))
        assertEquals("Sunny", WeatherCondition.CLEAR.label(true, chinese = false))
        assertEquals("Clear", WeatherCondition.CLEAR.label(false, chinese = false))
        assertEquals("局部多云", WeatherCondition.PARTLY_CLOUDY.label(true, chinese = true))
        // Every sky is opaque and darker at night than by day.
        WeatherSky.entries.forEach { assertEquals(0xFF, (it.top shr 24).toInt() and 0xFF) }
        assertTrue(luminance(WeatherSky.CLEAR_NIGHT.top) < luminance(WeatherSky.CLEAR_DAY.top))
    }

    private fun luminance(argb: Long) = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    @Test fun `units follow the locale like iOS`() {
        assertEquals(TemperatureUnit.FAHRENHEIT, temperatureUnitFor(Locale.US))
        assertEquals(TemperatureUnit.CELSIUS, temperatureUnitFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals(TemperatureUnit.CELSIUS, temperatureUnitFor(Locale.forLanguageTag("en-US-u-mu-celsius")))
        assertEquals(TemperatureUnit.FAHRENHEIT, temperatureUnitFor(Locale.forLanguageTag("zh-CN-u-mu-fahrenhe")))
        assertEquals(76, displayTemperature(24.6, TemperatureUnit.FAHRENHEIT))
        assertEquals(0, displayTemperature(-0.4, TemperatureUnit.CELSIUS))
        assertEquals(-3, displayTemperature(-2.6, TemperatureUnit.CELSIUS))
        assertEquals("7 mph", windLabel(11.2, Locale.US, chinese = false))
        assertEquals("11 km/h", windLabel(11.2, Locale.GERMANY, chinese = false))
        assertEquals("11公里/时", windLabel(11.2, Locale.SIMPLIFIED_CHINESE, chinese = true))
        assertEquals(24.0, toCelsius(24, TemperatureUnit.CELSIUS), 1e-9)
        assertEquals(0.0, toCelsius(32, TemperatureUnit.FAHRENHEIT), 1e-9)
    }

    @Test fun `labels match Apple's wording`() {
        assertEquals("H:25° L:17°", highLowLabel(25, 17, chinese = false))
        assertEquals("最高25° 最低17°", highLowLabel(25, 17, chinese = true))
        val afternoon = ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, shanghai)
        assertEquals(HourLabel("2", "PM"), hourLabel(afternoon, chinese = false, twentyFourHour = false))
        assertEquals(HourLabel("12", "AM"), hourLabel(afternoon.withHour(0), chinese = false, twentyFourHour = false))
        assertEquals(HourLabel("09", ""), hourLabel(afternoon.withHour(9), chinese = false, twentyFourHour = true))
        assertEquals(HourLabel("14时", ""), hourLabel(afternoon, chinese = true, twentyFourHour = true))
        assertEquals(HourLabel("下午2时", ""), hourLabel(afternoon, chinese = true, twentyFourHour = false))
        val sunset = afternoon.withHour(17).withMinute(20)
        assertEquals(HourLabel("5:20", "PM"), clockLabel(sunset, chinese = false, twentyFourHour = false))
        assertEquals(HourLabel("17:20", ""), clockLabel(sunset, chinese = true, twentyFourHour = true))
        val today = LocalDate.of(2026, 10, 20)
        assertEquals("Today", dayLabel(today, today, Locale.US, chinese = false))
        assertEquals("Wed", dayLabel(today.plusDays(1), today, Locale.US, chinese = false))
        assertEquals("周三", dayLabel(today.plusDays(1), today, Locale.SIMPLIFIED_CHINESE, chinese = true))
        assertEquals("今天", dayLabel(today, today, Locale.SIMPLIFIED_CHINESE, chinese = true))
    }

    @Test fun `range bars share one scale and today's dot stays on its bar`() {
        val view = weatherView(snapshot, fetchedAt, TemperatureUnit.CELSIUS)!!
        val days = view.days.take(6)
        val bars = rangeBars(days, view.temperature)
        assertEquals(days.size, bars.size)
        bars.forEach { assertTrue(it.start in 0f..1f && it.end in it.start..1f) }
        assertEquals(0f, bars.minOf { it.start }, 1e-6f)
        assertEquals(1f, bars.maxOf { it.end }, 1e-6f)
        assertNotNull(bars.first().dot)
        assertTrue(bars.first().dot!! in bars.first().start..bars.first().end)
        assertNull(bars[1].dot)
        assertTrue(rangeBars(emptyList(), 3).isEmpty())
        val flat = rangeBars(listOf(DayView(LocalDate.MIN, WeatherCondition.CLEAR, WeatherSymbol.SUN, 5, 5, null)), 5)
        assertEquals(0f, flat.single().start, 0f)
    }

    @Test fun `temperature colours run from blue to red`() {
        assertEquals(0xFF5E5CE6, temperatureColor(-30.0))
        assertEquals(0xFFFF453A, temperatureColor(45.0))
        assertEquals(0xFF5AC8FA, temperatureColor(0.0))
        val warm = temperatureColor(24.0)
        assertTrue(((warm shr 16) and 0xFF) == 0xFFL) // between yellow and orange: full red channel
        assertTrue((temperatureColor(5.0) and 0xFF) > (temperatureColor(30.0) and 0xFF)) // colder = bluer
    }

    @Test fun `sunrise and sunset widget headlines the next event`() {
        val view = weatherView(snapshot, fetchedAt, TemperatureUnit.CELSIUS)!!
        val afternoon = sunEvents(view.nowSeconds, view.sunrises, view.sunsets)
        assertTrue(afternoon.nextIsSunset)
        assertEquals(ZonedDateTime.of(2026, 10, 20, 17, 20, 0, 0, shanghai).toEpochSecond(), afternoon.next)
        assertEquals(ZonedDateTime.of(2026, 10, 21, 5, 50, 0, 0, shanghai).toEpochSecond(), afternoon.following)
        val evening = sunEvents(ZonedDateTime.of(2026, 10, 20, 19, 0, 0, 0, shanghai).toEpochSecond(), view.sunrises, view.sunsets)
        assertFalse(evening.nextIsSunset)
        assertEquals(ZonedDateTime.of(2026, 10, 21, 5, 50, 0, 0, shanghai).toEpochSecond(), evening.next)
        assertNull(sunEvents(Long.MAX_VALUE / 2, view.sunrises, view.sunsets).next)
        val curve = sunCurve(view.nowSeconds, view.sunrises, view.sunsets)!!
        val rise = view.sunrises.first(); val set = view.sunsets.first()
        assertEquals(curve.horizon, curve.y(curve.x(rise)), 1e-3f)
        assertEquals(curve.horizon, curve.y(curve.x(set)), 1e-3f)
        assertEquals(.5f, curve.x(curve.noon), 1e-6f)
        assertTrue(curve.y(.5f) < curve.horizon) // noon above the horizon (smaller y)
        assertTrue(curve.y(0f) > curve.horizon)  // midnight below
        assertTrue(curve.x(view.nowSeconds) in .5f..1f)
        // Apple's window: a 9.3-hour winter day spans ~59 % of the width (16-hour window).
        val winter = SunCurve(noon = 0, dayLength = (9.3 * 3600).toLong())
        assertEquals(.58f, winter.x((4.65 * 3600).toLong()) - winter.x(-(4.65 * 3600).toLong()), .01f)
        // At night the window moves to solar midnight: the sun stays on the widget, below the horizon.
        val late = ZonedDateTime.of(2026, 10, 20, 23, 30, 0, 0, shanghai).toEpochSecond()
        val night = sunCurve(late, view.sunrises, view.sunsets)!!
        assertTrue(night.x(late) in 0f..1f)
        assertTrue(night.y(night.x(late)) > night.horizon)
        assertEquals(night.horizon, night.y(night.x(view.sunsets.first())), 1e-3f)
        // Round-3 review: a long summer day's arc ran through the time digits. It now stays below 72 pt
        // (of 158), still crosses the horizon at sunrise/sunset, and a winter day keeps Apple's height.
        val summer = SunCurve(noon = 0, dayLength = 16 * 3600L)
        assertTrue(summer.y(.5f) >= SUN_CURVE_CEILING - 1e-4f)
        assertEquals(summer.horizon, summer.y(summer.x(8 * 3600L)), 1e-3f)
        assertTrue(summer.y(.5f) < winter.y(.5f)) // still taller than winter
        val unclamped = winter.copy(ceiling = 0f)
        assertEquals(unclamped.y(.5f), winter.y(.5f), .006f)
    }

    @Test fun `refresh waits 30 minutes and backs off after failures`() {
        val now = 10_000_000L
        assertFalse(weatherRefreshDue(now, now - 10 * 60_000, null, 0))
        assertTrue(weatherRefreshDue(now, now - 31 * 60_000, null, 0))
        assertTrue(weatherRefreshDue(now, null, null, 0))
        assertFalse(weatherRefreshDue(now, null, now - 60_000, 1))
        assertTrue(weatherRefreshDue(now, null, now - 3 * 60_000, 1))
        assertFalse(weatherRefreshDue(now, null, now - 3 * 60_000, 2))
        assertFalse(weatherRefreshDue(now, null, now - 29 * 60_000, 9))
        assertTrue(weatherRefreshDue(now, null, now - 31 * 60_000, 9))
        // A clock that jumped backwards never blocks a refresh forever.
        assertTrue(weatherRefreshDue(now, now + 60 * 60_000, null, 0))
    }

    @Test fun `requests send only rounded coordinates or the typed city`() {
        val url = openMeteoForecastUrl(31.230416, 121.473701)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=31.23&longitude=121.47&"))
        assertTrue("timezone=auto" in url && "timeformat=unixtime" in url && "forecast_days=10" in url)
        assertFalse("apikey" in url.lowercase())
        assertEquals(-33.87, roundCoordinate(-33.8688), 1e-9)
        val search = openMeteoGeocodingUrl(" 上海 ", chinese = true)
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=%E4%B8%8A%E6%B5%B7&count=6&language=zh&format=json", search)
    }

    @Test fun `city search results keep name and region`() {
        val places = parseOpenMeteoGeocoding(fixture("openmeteo-geocoding.json"))
        assertEquals(2, places.size)
        assertEquals(WeatherPlace("上海", "中国", 31.22222, 121.45806), places[0])
        assertEquals("California, United States", places[1].detail)
        assertTrue(parseOpenMeteoGeocoding("""{"generationtime_ms":0.5}""").isEmpty())
        assertTrue(parseOpenMeteoGeocoding("<html>").isEmpty())
    }

    @Test fun `mini json reads what Open-Meteo sends and rejects malformed text`() {
        @Suppress("UNCHECKED_CAST")
        val parsed = MiniJson.parse("""{"a":[1,-2.5e2,true,null],"s":"x\"y°\n","o":{}}""") as Map<String, Any?>
        assertEquals(listOf(1.0, -250.0, true, null), parsed["a"])
        assertEquals("x\"y°\n", parsed["s"])
        assertEquals(emptyMap<String, Any?>(), parsed["o"])
        listOf("{", "[1,]", "{\"a\" 1}", "tru", "{} x", "\"open").forEach { bad ->
            assertTrue(bad, runCatching { MiniJson.parse(bad) }.isFailure)
        }
    }

    @Test fun `weather and battery specs follow Apple's gallery and keep placed widgets`() {
        assertEquals(emptyList<String>(), iosWidgetRegistryProblems(iosWidgetSpecs))
        val weather = iosWidgetSpecs.filter { it.app == NativeWidgetKind.WEATHER }.map { it.widget to it.family }
        assertEquals(listOf("weather" to NativeWidgetSize.SMALL, "weather" to NativeWidgetSize.MEDIUM, "weather" to NativeWidgetSize.LARGE,
            "weather-details" to NativeWidgetSize.SMALL, "weather-daily" to NativeWidgetSize.SMALL, "weather-sun" to NativeWidgetSize.SMALL), weather)
        val battery = iosWidgetSpecs.filter { it.app == NativeWidgetKind.BATTERY }.map { it.family }
        assertEquals(NativeWidgetSize.entries, battery)
        // Ids already persisted by Core.17 stay where they were; new ones use the Weather / Battery blocks.
        assertEquals(-112, parseNativeWidgetKey("native:weather:small"))
        assertEquals(-113, parseNativeWidgetKey("native:weather:medium"))
        assertEquals(-100, parseNativeWidgetKey("native:battery:small"))
        assertEquals(-101, parseNativeWidgetKey("native:battery:medium"))
        assertEquals(-160, parseNativeWidgetKey("native:weather:large"))
        assertEquals(-163, parseNativeWidgetKey("native:weather-sun:small"))
        assertEquals(-180, parseNativeWidgetKey("native:battery:large"))
        assertTrue(isWeatherWidgetId(-162))
        assertFalse(isWeatherWidgetId(-100))
        assertFalse(isWeatherWidgetId(nativeWidgetId(NativeWidgetKind.CLOCK, NativeWidgetSize.SMALL)))
        // Forecast grows to large; Details / Daily / Sunrise-Sunset are small only (iOS 17.2+).
        val forecast = nativeWidgetSpanConstraints(-112)!!
        assertEquals(WidgetSpan(4, 4), forecast.maximum)
        assertTrue(forecast.canResizeVertically)
        assertEquals(-160, nativeWidgetIdForSpan(-112, 4, 4))
        assertEquals(WidgetSpan(2, 2), nativeWidgetSpanConstraints(-161)!!.maximum)
        assertEquals("Status", nativeWidgetSpec(-180)!!.nameEn)
    }

    @Test fun `every widget family shares one corner radius`() {
        val small = nativeWidgetCornerRadius(143f, 143f)
        assertEquals(small, nativeWidgetCornerRadius(302.6f, 324f), 1.5f)
        assertEquals(12f, nativeWidgetCornerRadius(40f, 40f), 0f)
    }

    @Test fun `apple canvases and battery rings`() {
        assertEquals(158f to 158f, appleWidgetCanvas(NativeWidgetSize.SMALL))
        assertEquals(338f to 158f, appleWidgetCanvas(NativeWidgetSize.MEDIUM))
        assertEquals(338f to 354f, appleWidgetCanvas(NativeWidgetSize.LARGE))
        assertEquals(-90f to 180f, batteryRingSweep(50, charging = false, gapDegrees = 26f))
        val (start, sweep) = batteryRingSweep(100, charging = true, gapDegrees = 26f)
        assertEquals(-77f, start, 1e-4f); assertEquals(334f, sweep, 1e-4f)
        assertEquals(-90f to 0f, batteryRingSweep(null, charging = false, gapDegrees = 0f))
        val green = appleBatteryColor(BatteryStatus(80, false, false), monochrome = false)
        assertEquals(green, appleBatteryColor(BatteryStatus(21, false, false), monochrome = false))
        assertTrue(green != appleBatteryColor(BatteryStatus(20, false, false), monochrome = false))
        assertEquals(green, appleBatteryColor(BatteryStatus(15, true, false), monochrome = false)) // charging stays green
        assertTrue(green != appleBatteryColor(BatteryStatus(80, false, true), monochrome = false)) // Low Power Mode: yellow
        assertEquals(androidx.compose.ui.graphics.Color.White, appleBatteryColor(BatteryStatus(10, false, false), monochrome = true))
    }
}
