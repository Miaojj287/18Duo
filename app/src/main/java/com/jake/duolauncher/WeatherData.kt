package com.jake.duolauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

// ---------------------------------------------------------------------------------------
// Weather data source for the Apple-style Weather widgets. Privacy model (PRIVACY.md):
// nothing happens until the user taps a Weather widget and picks "Use Current Location"
// (ACCESS_COARSE_LOCATION, asked only then) or types a city. Requests go to Open-Meteo only
// (no key, no account): approximate coordinates rounded to 0.01°, or the typed city name for
// the search. Results are cached for 30 min and refreshed only while Home is in front —
// there is no background job and no background location.
// ---------------------------------------------------------------------------------------

private val WEATHER_PACKAGES = listOf("com.miui.weather2", "com.moji.mjweather", "com.google.android.apps.weather",
    "com.huawei.android.totemweather", "com.oneplus.weather", "com.sec.android.daemonapp", "com.coloros.weather2")

/** The phone's own Weather app, which a tap on a configured Weather widget opens (iOS opens Weather). */
internal fun installedWeatherApp(context: Context): android.content.Intent? = WEATHER_PACKAGES.firstNotNullOfOrNull { pkg ->
    context.packageManager.getLaunchIntentForPackage(pkg)
}

internal enum class WeatherLocationMode(val key: String) {
    NONE("none"), CURRENT("current"), CITY("city");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NONE }
}

internal enum class WeatherProblem { OFFLINE, LOCATION_OFF, LOCATION_DENIED, LOCATION_UNAVAILABLE, SERVER }

internal data class WeatherState(
    val mode: WeatherLocationMode = WeatherLocationMode.NONE,
    /** City shown in the widget (typed city, reverse-geocoded locality, or the zone's city). */
    val place: String? = null,
    val snapshot: WeatherSnapshot? = null,
    val loading: Boolean = false,
    val problem: WeatherProblem? = null,
    /** Bumped when the user changes the location, so widgets fetch at once instead of on their next tick. */
    val revision: Int = 0,
) {
    val configured: Boolean get() = mode != WeatherLocationMode.NONE
    val currentLocation: Boolean get() = mode == WeatherLocationMode.CURRENT
}

internal object WeatherRepository {
    private const val PREFS = "duo18_weather"
    private const val KEY_MODE = "mode"
    private const val KEY_CITY = "city_name"
    private const val KEY_CITY_LAT = "city_lat"
    private const val KEY_CITY_LON = "city_lon"
    private const val KEY_CACHE = "cache_json"
    private const val KEY_CACHE_TIME = "cache_time"
    private const val KEY_CACHE_PLACE = "cache_place"

    private val mutable = MutableStateFlow(WeatherState())
    private val mutex = Mutex()
    @Volatile private var loaded = false
    @Volatile private var lastAttempt: Long? = null
    @Volatile private var failures = 0

    fun state(context: Context): StateFlow<WeatherState> {
        ensureLoaded(context.applicationContext)
        return mutable
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            val mode = WeatherLocationMode.fromKey(prefs.getString(KEY_MODE, null))
            val time = prefs.getLong(KEY_CACHE_TIME, 0L)
            val snapshot = prefs.getString(KEY_CACHE, null)?.let { parseOpenMeteoForecast(it, time) }
            val place = if (mode == WeatherLocationMode.CITY) prefs.getString(KEY_CITY, null) else prefs.getString(KEY_CACHE_PLACE, null)
            mutable.value = WeatherState(mode, place, snapshot.takeIf { mode != WeatherLocationMode.NONE })
            loaded = true
        }
    }

    /** User picked "Use Current Location" (permission already granted). */
    fun useCurrentLocation(context: Context) {
        ensureLoaded(context)
        prefs(context).edit().putString(KEY_MODE, WeatherLocationMode.CURRENT.key).remove(KEY_CACHE).remove(KEY_CACHE_TIME).apply()
        failures = 0; lastAttempt = null
        mutable.value = WeatherState(WeatherLocationMode.CURRENT, null, null, loading = true, revision = mutable.value.revision + 1)
    }

    /** User typed and picked a city. */
    fun useCity(context: Context, place: WeatherPlace) {
        ensureLoaded(context)
        prefs(context).edit().putString(KEY_MODE, WeatherLocationMode.CITY.key).putString(KEY_CITY, place.name)
            .putString(KEY_CITY_LAT, roundCoordinate(place.latitude).toString())
            .putString(KEY_CITY_LON, roundCoordinate(place.longitude).toString())
            .remove(KEY_CACHE).remove(KEY_CACHE_TIME).apply()
        failures = 0; lastAttempt = null
        mutable.value = WeatherState(WeatherLocationMode.CITY, place.name, null, loading = true, revision = mutable.value.revision + 1)
    }

    /** Forget the location and the cached forecast (the widget returns to its setup state). */
    fun turnOff(context: Context) {
        ensureLoaded(context)
        prefs(context).edit().clear().apply()
        failures = 0; lastAttempt = null
        mutable.value = WeatherState(revision = mutable.value.revision + 1)
    }

    /** Called in a loop while Home is in front; fetches only when the cache is due. */
    suspend fun refreshIfDue(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        ensureLoaded(app)
        if (!mutable.value.configured) return
        val now = System.currentTimeMillis()
        if (!force && !weatherRefreshDue(now, mutable.value.snapshot?.fetchedAt, lastAttempt, failures)) return
        if (!mutex.tryLock()) return
        try {
            lastAttempt = now
            val revision = mutable.value.revision
            mutable.value = mutable.value.copy(loading = true)
            val result = withContext(Dispatchers.IO) { runCatching { fetch(app) } }
            // The user picked another location meanwhile: drop this answer, the new revision fetches its own.
            if (mutable.value.revision != revision) return
            result.onSuccess { (snapshot, place, json) ->
                failures = 0
                val mode = mutable.value.mode
                prefs(app).edit().putString(KEY_CACHE, json).putLong(KEY_CACHE_TIME, snapshot.fetchedAt)
                    .putString(KEY_CACHE_PLACE, place).apply()
                mutable.value = mutable.value.copy(mode = mode, place = place, snapshot = snapshot, loading = false, problem = null)
            }.onFailure { error ->
                failures++
                val problem = (error as? WeatherFetchException)?.problem ?: if (isOnline(app)) WeatherProblem.SERVER else WeatherProblem.OFFLINE
                mutable.value = mutable.value.copy(loading = false, problem = problem)
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Re-checks soon after a permission/location change instead of waiting for the backoff. */
    fun resetBackoff() { failures = 0; lastAttempt = null }

    /**
     * The phone has a working network again: a widget showing "No internet connection" (or any
     * failed state) refetches now instead of waiting out the 2–30 min backoff. The stale reason is
     * cleared at once so the placeholder shows its loading bars while the request runs.
     */
    suspend fun onNetworkAvailable(context: Context) {
        val state = mutable.value
        if (!state.configured || state.problem == null) return
        resetBackoff()
        if (state.problem == WeatherProblem.OFFLINE || state.problem == WeatherProblem.SERVER) mutable.value = state.copy(problem = null)
        refreshIfDue(context, force = true)
    }

    /**
     * Emits when the default network gains internet (ConnectivityManager callback, no polling).
     * INTERNET, not VALIDATED: where Google's connectivity check is unreachable (the QA emulator,
     * many networks in China) Android keeps the network at PARTIAL_CONNECTIVITY while Open-Meteo
     * answers fine — the same rule [isOnline] uses. Deduplicated, so signal/bandwidth updates do not refetch.
     */
    fun networkReturns(context: Context): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow<Boolean> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) { awaitClose { }; return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
            override fun onLost(network: android.net.Network) { trySend(false) }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().filter { it }.map { }

    private class WeatherFetchException(val problem: WeatherProblem) : IOException(problem.name)

    private suspend fun fetch(context: Context): Triple<WeatherSnapshot, String, String> {
        val state = mutable.value
        val prefs = prefs(context)
        val (latitude, longitude, place) = when (state.mode) {
            WeatherLocationMode.CITY -> Triple(
                prefs.getString(KEY_CITY_LAT, null)?.toDoubleOrNull() ?: throw WeatherFetchException(WeatherProblem.LOCATION_UNAVAILABLE),
                prefs.getString(KEY_CITY_LON, null)?.toDoubleOrNull() ?: throw WeatherFetchException(WeatherProblem.LOCATION_UNAVAILABLE),
                prefs.getString(KEY_CITY, null))
            WeatherLocationMode.CURRENT -> {
                val location = coarseLocation(context)
                Triple(location.latitude, location.longitude, locality(context, location))
            }
            WeatherLocationMode.NONE -> throw WeatherFetchException(WeatherProblem.LOCATION_UNAVAILABLE)
        }
        if (!isOnline(context)) throw WeatherFetchException(WeatherProblem.OFFLINE)
        val json = httpGet(openMeteoForecastUrl(latitude, longitude))
        val snapshot = parseOpenMeteoForecast(json, System.currentTimeMillis()) ?: throw WeatherFetchException(WeatherProblem.SERVER)
        val name = place ?: state.place ?: cityName(snapshot.zone)
        return Triple(snapshot, name, json)
    }

    /** City search for the "Enter City" prompt. Sends only the typed text. */
    suspend fun searchCity(query: String): List<WeatherPlace>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching { parseOpenMeteoGeocoding(httpGet(openMeteoGeocodingUrl(query, Locale.getDefault().language == "zh"))) }.getOrNull()
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw WeatherFetchException(WeatherProblem.SERVER)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    internal fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** One foreground coarse fix: a recent last-known location, else a single current-location request. */
    @SuppressLint("MissingPermission")
    private suspend fun coarseLocation(context: Context): Location {
        if (!hasLocationPermission(context)) throw WeatherFetchException(WeatherProblem.LOCATION_DENIED)
        val manager = context.getSystemService(LocationManager::class.java) ?: throw WeatherFetchException(WeatherProblem.LOCATION_UNAVAILABLE)
        if (!manager.isLocationEnabled) throw WeatherFetchException(WeatherProblem.LOCATION_OFF)
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val lastKnown = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < WEATHER_REFRESH_MS) return lastKnown
        for (provider in providers) {
            val fix = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                    }.onFailure { if (continuation.isActive) continuation.resume(null) }
                }
            }
            if (fix != null) return fix
        }
        return lastKnown ?: throw WeatherFetchException(WeatherProblem.LOCATION_UNAVAILABLE)
    }

    /**
     * City name for a coordinate via the platform geocoder, when the device has one. The geocoder
     * is a network service on most phones (Google / Xiaomi), so it gets the same 0.01° (≈ 1 km)
     * coordinates as Open-Meteo, as the in-app prompt and PRIVACY.md promise.
     */
    @Suppress("DEPRECATION")
    private suspend fun locality(context: Context, location: Location): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val latitude = roundCoordinate(location.latitude)
        val longitude = roundCoordinate(location.longitude)
        val address = if (Build.VERSION.SDK_INT >= 33) withTimeoutOrNull(6_000L) {
            suspendCancellableCoroutine { continuation ->
                runCatching {
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) { if (continuation.isActive) continuation.resume(null) }
                    })
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        } else runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
        return address?.let { it.locality ?: it.subAdminArea ?: it.adminArea }?.takeIf { it.isNotBlank() }
    }
}

/**
 * The weather state for a widget. [active] widgets (placed on Home, not gallery previews) keep
 * it fresh, but only while Home is STARTED, i.e. visible: no background refresh.
 */
@Composable
internal fun rememberWeatherState(active: Boolean): WeatherState {
    val context = LocalContext.current
    val state by WeatherRepository.state(context).collectAsState()
    if (active) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, state.revision) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Connectivity back (round-3 review: the offline reason lingered for the whole backoff).
                launch { WeatherRepository.networkReturns(context).collect { WeatherRepository.onNetworkAvailable(context) } }
                while (true) {
                    WeatherRepository.refreshIfDue(context)
                    delay(60_000L)
                }
            }
        }
    }
    return state
}
