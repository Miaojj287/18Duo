package com.jake.duolauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MRU entries are apps actually opened, never fabricated background tasks. */
internal fun recentDockIds(history: List<String>, available: Set<String>, pinned: List<String?>,
    count: Int = 2): List<String> = history.distinct().filter { it in available && it !in pinned }.take(count)

/** A usage-stats launch older than the user's swipe-away stays hidden; a newer one brings the app back. */
internal fun recentStillDismissed(lastOpened: Long, dismissedAt: Long): Boolean = dismissedAt > 0L && lastOpened <= dismissedAt

internal class RecentAppsController(private val context: Context) {
    private val prefs = context.getSharedPreferences("recent_apps", 0)
    var ids by mutableStateOf(prefs.getString("history", "").orEmpty().split('\n').filter(String::isNotBlank))
        private set
    val hasUsageAccess: Boolean get() = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    fun record(app: AppEntry) = recordId(app.id)

    fun recordId(id: String) {
        // The exact copy (profile) just opened: what the switcher and the return flight key on.
        NavigationProgressBus.noteLaunched(id)
        ids = (listOf(id) + ids).distinct().take(24)
        prefs.edit().putString("history", ids.joinToString("\n")).remove("dismissed:$id").apply()
    }

    /** Swiping a deck card away hides the app until it is opened again, even with usage access. */
    fun remove(id: String) {
        ids = ids.filter { it != id }
        prefs.edit().putString("history", ids.joinToString("\n")).putLong("dismissed:$id", System.currentTimeMillis()).apply()
    }

    /** Re-read what another process-local controller (the gesture service) may have changed. */
    fun reload() {
        ids = prefs.getString("history", "").orEmpty().split('\n').filter(String::isNotBlank)
    }
    suspend fun refresh(apps: List<AppEntry>) {
        if (!hasUsageAccess) return
        val personal = apps.filter { it.isPersonal && it.available }.associateBy { it.packageName }
        val recent = withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 86_400_000L, now)
                val event = UsageEvents.Event()
                val lastOpened = mutableMapOf<String, Long>()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName in personal)
                        lastOpened[event.packageName] = event.timeStamp
                }
                lastOpened.entries.sortedByDescending { it.value }.mapNotNull { entry ->
                    val id = personal[entry.key]?.id ?: return@mapNotNull null
                    if (recentStillDismissed(entry.value, prefs.getLong("dismissed:$id", 0L))) null else id
                }
            }.getOrDefault(emptyList())
        }
        // Keep tracked work-profile launches; Android usage events don't identify their user.
        ids = (recent + ids).distinct().filter { id -> apps.any { it.id == id } }.take(24)
        prefs.edit().putString("history", ids.joinToString("\n")).apply()
    }
}

/** One app in the overlay's switcher. [component] is null for a foreground app with no launcher entry. */
internal data class RecentTask(
    val id: String,
    val packageName: String,
    val component: ComponentName?,
    val user: UserHandle,
    val label: String,
    val icon: Bitmap?,
    /** Splash-like fill for a card without a snapshot: the icon's own background colour. */
    val placeholderColor: Int,
)

/**
 * [tasks] are most recent first, one per app and profile; when an app is in front it is
 * [tasks]`[0]` and [foreground] is true. [fileable]: sure enough about that app *and* its profile
 * to remember the gesture's screenshot as its snapshot (see NavigationForeground.kt).
 */
internal data class RecentTaskList(val tasks: List<RecentTask>, val foreground: Boolean, val usageAccess: Boolean,
    val fileable: Boolean = false) {
    val current: RecentTask? get() = if (foreground) tasks.firstOrNull() else null
}

/**
 * Most-recent-first order for the switcher: usage events (if the user granted usage access) give
 * the real order including the app in front; the launcher's own launch history fills in what usage
 * events cannot name (work profile). Apps the user swiped away stay hidden until reopened.
 */
internal fun orderRecentIds(
    usage: List<Pair<String, Long>>,
    history: List<String>,
    dismissedAt: (String) -> Long,
    limit: Int = 12,
): List<String> {
    val fromUsage = usage.sortedByDescending { it.second }
        .filterNot { (id, time) -> recentStillDismissed(time, dismissedAt(id)) }
        .map { it.first }
    return (fromUsage + history).distinct().take(limit)
}

/**
 * iOS keeps the order of apps you are flicking through along the home indicator until you settle
 * in one, so a swipe back returns to where you came from. [freezeMillis] stands in for "settled".
 */
internal class QuickSwitchSession(private val freezeMillis: Long = 4_000L) {
    private var frozen: List<String>? = null
    private var at = 0L

    /** Returns the order to use and the index of [current] within it. */
    fun order(live: List<String>, current: String?, now: Long): Pair<List<String>, Int> {
        // Not knowing the app in front yet (its task list is still loading) says nothing about
        // whether the user settled: keep the frozen order for the rebuild that follows.
        if (current == null) return live to -1
        val held = frozen
        if (held != null && now - at <= freezeMillis && current in held)
            return held to held.indexOf(current)
        frozen = null
        return (listOf(current) + live.filter { it != current }) to 0
    }

    fun committed(order: List<String>, now: Long) { frozen = order; at = now }

    fun reset() { frozen = null }
}

/** Loads [RecentTaskList] for the overlay. Blocking; call from a worker thread. */
internal class RecentTaskSource(private val context: Context) {
    private val prefs = context.getSharedPreferences("recent_apps", 0)
    private val icons = HashMap<String, Pair<Bitmap, Int>>()
    private val appleIconPack = AppleIconPack(context.assets)
    private var cachedAppleIconsEnabled: Boolean? = null

    val hasUsageAccess: Boolean get() = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    /**
     * @param window the last window-state event (accessibility), the most direct "who is in front".
     */
    fun load(launcherInFront: Boolean, window: WindowSample? = null): RecentTaskList {
        // A settings toggle must also invalidate the switcher's return-flight icon. Otherwise its
        // system artwork flies into a Home tile that already displays the themed artwork.
        val appleIconsEnabled = CorePreferences.appleIconsEnabled(context)
        if (cachedAppleIconsEnabled != appleIconsEnabled) {
            synchronized(icons) { icons.clear() }
            cachedAppleIconsEnabled = appleIconsEnabled
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val me = Process.myUserHandle()
        val history = prefs.getString("history", "").orEmpty().split('\n').filter(String::isNotBlank)
        val access = hasUsageAccess
        var resumed: UsageForeground? = null
        val usage = ArrayList<Pair<String, Long>>()
        if (access) runCatching {
            val now = System.currentTimeMillis()
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 86_400_000L, now)
            val event = UsageEvents.Event()
            val last = HashMap<String, Long>()
            val lifecycle = ArrayList<UsageSample>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type != USAGE_RESUMED && type != USAGE_PAUSED && type != USAGE_STOPPED && type != USAGE_DESTROYED) continue
                lifecycle += UsageSample(type, event.packageName, event.className, event.timeStamp)
                if (type == USAGE_RESUMED) last[event.packageName] = event.timeStamp
            }
            resumed = usageForeground(lifecycle, context.packageName)
            for ((pkg, time) in last) {
                if (pkg == context.packageName) continue
                val activity = runCatching { launcherApps.getActivityList(pkg, me).firstOrNull() }.getOrNull() ?: continue
                usage += activity.componentName.flattenToString() to time
            }
        }
        val others = runCatching { launcherApps.profiles.filter { it != me } }.getOrDefault(emptyList())
        fun otherProfilesWith(pkg: String): List<UserHandle> = others.filter { profile ->
            runCatching { launcherApps.getActivityList(pkg, profile).isNotEmpty() }.getOrDefault(false)
        }
        fun otherProfileWith(pkg: String): UserHandle? = otherProfilesWith(pkg).firstOrNull()
        val guess = if (launcherInFront) ForegroundGuess(null, home = true, fileable = false)
            else resolveForeground(resumed, window, context.packageName,
                launchedAfterWindow = NavigationProgressBus.lastLaunch?.let { launch ->
                    val launchedWall = System.currentTimeMillis() - (android.os.SystemClock.uptimeMillis() - launch.at)
                    launch.packageName.takeIf { window != null && launchedWall > window.at }
                }) { otherProfileWith(it) != null }
        val ids = orderRecentIds(usage, history, { prefs.getLong("dismissed:$it", 0L) })
        // One card per app and profile: a usage entry and a launcher record of the same app collapse.
        val tasks = ids.mapNotNull { id -> resolve(id, launcherApps, userManager, appleIconsEnabled) }
            .distinctBy { it.packageName to it.user }.toMutableList()
        val fg = guess.packageName
        var foreground = false
        if (fg != null) {
            // Which copy: our profile's unless usage events say it is not resumed, or Home / the
            // switcher last opened another profile's copy of it (dual apps share the package name).
            val user = if (guess.fileable) me else runCatching {
                val candidates = otherProfilesWith(fg)
                val serials = candidates.map { userManager.getSerialNumberForUser(it) }
                val personalResumed = if (!access) null else resumed?.packageName == fg
                val serial = foregroundProfileSerial(fg, userManager.getSerialNumberForUser(me), personalResumed,
                    NavigationProgressBus.lastLaunch, serials)
                candidates.getOrNull(serials.indexOf(serial)) ?: me
            }.getOrDefault(me)
            val index = tasks.indexOfFirst { it.packageName == fg && it.user == user }
            if (index >= 0) {
                tasks.add(0, tasks.removeAt(index)); foreground = true
            } else (profileTask(fg, user, launcherApps, userManager, appleIconsEnabled) ?: unlaunchable(fg, appleIconsEnabled))?.let {
                tasks.removeAll { t -> t.packageName == it.packageName && t.user == it.user }
                tasks.add(0, it); foreground = true
            }
        }
        val fileable = foreground && guess.fileable && tasks.first().user == me
        return RecentTaskList(tasks, foreground, access, fileable)
    }

    /** The app's launcher entry in [user] (a work-profile app in front that our history never launched). */
    private fun profileTask(pkg: String, user: UserHandle, launcherApps: LauncherApps, userManager: UserManager,
        appleIconsEnabled: Boolean): RecentTask? {
        val activity = runCatching { launcherApps.getActivityList(pkg, user).firstOrNull() }.getOrNull() ?: return null
        val component = activity.componentName.flattenToString()
        val id = runCatching {
            profileAppId(component, userManager.getSerialNumberForUser(user), userManager.getSerialNumberForUser(Process.myUserHandle()))
        }.getOrNull() ?: return null
        return resolve(id, launcherApps, userManager, appleIconsEnabled)
    }

    private fun resolve(id: String, launcherApps: LauncherApps, userManager: UserManager,
        appleIconsEnabled: Boolean): RecentTask? {
        val identity = parseProfileAppId(id) ?: return null
        val component = ComponentName.unflattenFromString(identity.component) ?: return null
        if (component.packageName == context.packageName) return null
        val user = identity.userSerial?.let { userManager.getUserForSerialNumber(it) } ?: Process.myUserHandle()
        val info = runCatching {
            launcherApps.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(component), user)
        }.getOrNull() ?: return null
        val (icon, color) = synchronized(icons) { icons[id] } ?: run {
            val bitmap = (if (appleIconsEnabled) appleIconPack.iconFor(component, context,
                user.takeUnless { it == Process.myUserHandle() }) else null) ?: launcherIcon(
                runCatching { info.getBadgedIcon(0) }.getOrElse { context.packageManager.defaultActivityIcon })
            val splash = if (user == Process.myUserHandle()) SplashColors.color(context, component) else null
            (bitmap to launchPlaceholderColor(splash, bitmap)).also { synchronized(icons) { icons[id] = it } }
        }
        return RecentTask(id, component.packageName, component, user, info.label.toString(), icon, color)
    }

    /** A foreground app without a launcher entry (a system screen): shown, but only as itself. */
    private fun unlaunchable(packageName: String, appleIconsEnabled: Boolean): RecentTask? = runCatching {
        val pm = context.packageManager
        val app = pm.getApplicationInfo(packageName, 0)
        val bitmap = (if (appleIconsEnabled) appleIconPack.iconFor(ComponentName(packageName, packageName), context, null)
            else null) ?: launcherIcon(pm.getApplicationIcon(app))
        RecentTask(packageName, packageName, null, Process.myUserHandle(), pm.getApplicationLabel(app).toString(),
            bitmap, launchPlaceholderColor(null, bitmap))
    }.getOrNull()

}

/** Average of the icon's border pixels — for most icons, its background (≈ the app's splash colour). */
internal fun iconBackgroundColor(icon: Bitmap): Int {
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    val w = icon.width; val h = icon.height
    if (w <= 0 || h <= 0) return android.graphics.Color.rgb(242, 242, 247)
    val inset = (w * .12f).toInt()
    for (i in 0 until 16) {
        val t = inset + (w - 2 * inset) * i / 15
        for ((x, y) in listOf(t to inset, t to h - 1 - inset, inset to t, w - 1 - inset to t)) {
            val c = icon.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            if ((c ushr 24) < 128) continue
            r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
        }
    }
    if (n == 0) return android.graphics.Color.rgb(242, 242, 247)
    return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * The fill of a card that has no snapshot yet (iOS would show the app's launch screen): the
 * app's own launch colour when it is a real colour, else the dominant colour of its icon
 * artwork, else the icon's background — never a flat system grey.
 */
internal fun launchPlaceholderColor(splash: Int?, icon: Bitmap): Int {
    val pixels = runCatching {
        val step = maxOf(1, minOf(icon.width, icon.height) / 24)
        val out = ArrayList<Int>()
        var y = 0
        while (y < icon.height) { var x = 0; while (x < icon.width) { out += icon.getPixel(x, y); x += step }; y += step }
        out.toIntArray()
    }.getOrDefault(IntArray(0))
    return chooseLaunchColor(splash, dominantColor(pixels), iconBackgroundColor(icon))
}

internal fun saturation(argb: Int): Float {
    val r = argb shr 16 and 0xFF; val g = argb shr 8 and 0xFF; val b = argb and 0xFF
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    return if (max == 0) 0f else (max - min) / max.toFloat()
}

/** Pure choice behind [launchPlaceholderColor] (JVM-tested). */
internal fun chooseLaunchColor(splash: Int?, dominant: Int?, border: Int): Int = when {
    splash != null && saturation(splash) >= .2f -> splash
    dominant != null -> dominant
    splash != null -> splash
    else -> border
}

/**
 * The most common clearly coloured hue of an icon (coarse 3-bit-per-channel buckets, averaged
 * within the winning bucket), or null for a mostly grey/white/black icon.
 */
internal fun dominantColor(pixels: IntArray): Int? {
    val counts = HashMap<Int, IntArray>()
    var opaque = 0
    for (c in pixels) {
        if ((c ushr 24) < 200) continue
        opaque++
        if (saturation(c) < .28f) continue
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        val v = maxOf(r, g, b)
        if (v < 40) continue
        val key = (r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
        val acc = counts.getOrPut(key) { IntArray(4) }
        acc[0] += r; acc[1] += g; acc[2] += b; acc[3]++
    }
    val best = counts.values.maxByOrNull { it[3] } ?: return null
    // A few coloured pixels in a white icon (e.g. a small logo) do not make it a coloured app.
    if (best[3] < opaque * .12f) return null
    return (0xFF shl 24) or (best[0] / best[3] shl 16) or (best[1] / best[3] shl 8) or (best[2] / best[3])
}

/**
 * The colour Android 12+ paints an app's splash screen with (its launch theme's
 * `windowSplashScreenBackground`, else a solid `windowBackground`). It is the closest thing to
 * iOS's launch screen, so cards without a snapshot use it; it also guesses the home indicator's
 * contrast before a first snapshot exists. Cached per component; resolving costs a few ms.
 */
internal object SplashColors {
    private val byComponent = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val byPackage = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val NONE = 0

    fun color(context: Context, component: ComponentName): Int? {
        val key = component.flattenToString()
        byComponent[key]?.let { return it.takeIf { c -> c != NONE } }
        val resolved = runCatching { resolve(context, component) }.getOrNull()
        byComponent[key] = resolved ?: NONE
        resolved?.let { byPackage[component.packageName] = it }
        return resolved
    }

    fun forPackage(packageName: String?): Int? = packageName?.let { byPackage[it] }

    private fun resolve(context: Context, component: ComponentName): Int? {
        val pm = context.packageManager
        val info = pm.getActivityInfo(component, 0)
        val themed = context.createPackageContext(component.packageName, Context.CONTEXT_RESTRICTED)
        themed.setTheme(info.themeResource)
        val a = themed.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenBackground,
            android.R.attr.windowBackground))
        try {
            if (a.hasValue(0)) a.getColor(0, 0).takeIf { (it ushr 24) >= 0xF0 }?.let { return it }
            val background = runCatching { a.getDrawable(1) }.getOrNull()
            return (background as? android.graphics.drawable.ColorDrawable)?.color?.takeIf { (it ushr 24) >= 0xF0 }
        } finally { a.recycle() }
    }
}
