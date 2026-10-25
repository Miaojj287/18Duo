package com.jake.duolauncher

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.UserHandle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------
// Device bindings for the pure search logic in SearchMatching.kt / SpotlightCatalog.kt.
// ---------------------------------------------------------------------------------------

/** ICU's Han-Latin transliterator ships with Android (API 29+); no dependency is added. */
internal object DeviceRomanizer : Romanizer {
    private val transliterator by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
    }
    override fun romanize(text: String): String = transliterator?.let { t -> synchronized(t) { t.transliterate(text) } } ?: text
}

/** Labels repeat across Spotlight, the App Library and its index; romanise each once per process. */
internal object SearchKeys {
    private val cache = ConcurrentHashMap<String, SearchKey>()
    fun of(label: String): SearchKey = cache.getOrPut(label) { searchKey(label, DeviceRomanizer) }
    suspend fun warm(labels: Collection<String>) = withContext(Dispatchers.Default) { labels.forEach(::of) }
}

/** How long after the apps load Spotlight's keys are warmed (Home draws its first frames first). */
internal const val SEARCH_KEY_WARM_DELAY_MS = 600L

/** Every name Spotlight ranks before it is ever opened: app labels and Settings titles/keywords. */
internal fun spotlightWarmLabels(apps: List<AppEntry>): List<String> =
    apps.map(AppEntry::label) + SETTINGS_LINKS.flatMap { listOf(it.zh, it.en) + it.keywords }

/** Submitted queries, newest first; kept on this device only. */
internal class SpotlightHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("spotlight", Context.MODE_PRIVATE)
    var recent by mutableStateOf(prefs.getString("recent", "").orEmpty().split('\n').filter(String::isNotBlank))
        private set
    fun record(query: String) {
        recent = pushRecentSearch(recent, query)
        prefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }
    fun clear() { recent = emptyList(); prefs.edit().remove("recent").apply() }
}

internal data class SpotlightShortcut(
    val app: AppEntry,
    val info: ShortcutInfo,
    val label: String,
)

/**
 * Static and dynamic app shortcuts (the Android twin of iOS app actions). Only the default
 * Home app may read them; anywhere else the list is simply empty.
 */
internal suspend fun loadSpotlightShortcuts(context: Context, apps: List<AppEntry>): List<SpotlightShortcut> =
    withContext(Dispatchers.IO) {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@withContext emptyList()
        if (!runCatching { launcherApps.hasShortcutHostPermission() }.getOrDefault(false)) return@withContext emptyList()
        val byComponent = apps.filter(AppEntry::available).associateBy { it.userSerial to it.component }
        val byPackage = apps.filter(AppEntry::available).groupBy { it.userSerial to it.packageName }
        apps.filter(AppEntry::available).distinctBy(AppEntry::userSerial).flatMap { profileApp ->
            val user: UserHandle = profileApp.user
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            runCatching { launcherApps.getShortcuts(query, user) }.getOrNull().orEmpty().mapNotNull { info ->
                if (!info.isEnabled) return@mapNotNull null
                val owner = info.activity?.let { byComponent[profileApp.userSerial to it] }
                    ?: byPackage[profileApp.userSerial to info.`package`]?.firstOrNull() ?: return@mapNotNull null
                val label = (info.shortLabel ?: info.longLabel)?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                SpotlightShortcut(owner, info, label)
            }
        }.sortedWith(compareBy({ it.app.label }, { it.info.rank }))
    }

private val shortcutIcons = ConcurrentHashMap<String, Bitmap>()

@Composable
private fun rememberShortcutIcon(shortcut: SpotlightShortcut): ImageBitmap? {
    val context = LocalContext.current
    val key = "${shortcut.app.userSerial}/${shortcut.info.`package`}/${shortcut.info.id}"
    val icon by produceState(shortcutIcons[key], key) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching {
                context.getSystemService(LauncherApps::class.java)
                    .getShortcutIconDrawable(shortcut.info, context.resources.displayMetrics.densityDpi)
                    ?.let(::launcherIcon)
            }.getOrNull()?.also { shortcutIcons[key] = it }
        }
    }
    return remember(icon) { icon?.asImageBitmap() }
}

internal fun startSpotlightShortcut(context: Context, shortcut: SpotlightShortcut, bounds: android.graphics.Rect?): Boolean =
    runCatching {
        context.getSystemService(LauncherApps::class.java).startShortcut(shortcut.info, bounds, null)
    }.isSuccess

/**
 * Hands a query to the user's default browser so it uses the user's own search engine
 * (iOS sends "Search Web" to Safari's engine). Falls back to a plain web URL.
 */
internal fun openWebSearch(context: Context, query: String): Boolean {
    val pm = context.packageManager
    val browser = runCatching {
        pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it != "android" && it.isNotBlank() }
    val url = Uri.parse(webSearchUrl(query))
    val webSearch = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query.trim())
    val candidates = listOfNotNull(
        browser?.let { Intent(webSearch).setPackage(it) },
        browser?.let { Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(it) },
        Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        } catch (_: Exception) { }
    }
    return false
}

internal fun openSettingsLink(context: Context, link: SettingsLink): Boolean {
    for (intent in listOf(Intent(link.action), Intent(android.provider.Settings.ACTION_SETTINGS))) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        } catch (_: Exception) { }
    }
    return false
}

// ---------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------

private object SpotlightStyle {
    val fieldHeight = 48.dp
    val sectionRadius = 24.dp
    val rowHeight = 56.dp
}

@Composable private fun spotlightInk(dark: Boolean) = if (dark) Color.White else Color(0xFF111114)
@Composable private fun spotlightSecondary(dark: Boolean) = spotlightInk(dark).copy(alpha = .58f)
@Composable private fun spotlightPlatter(dark: Boolean) = if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .58f)
/** The field floats over unblurred wallpaper (bottom of the cover), so it carries a stronger tint. */
@Composable private fun spotlightFieldFrost(dark: Boolean) = if (dark) Color.Black.copy(alpha = .30f) else Color.White.copy(alpha = .62f)
@Composable private fun spotlightSeparator(dark: Boolean) = spotlightInk(dark).copy(alpha = if (dark) .14f else .10f)

/**
 * iOS-style Spotlight over the live Home Screen: the Home blurs behind a Liquid-Glass search
 * field with the keyboard up. On the portrait cover the field sits above the keyboard (iOS 26);
 * on the unfolded landscape panel it floats at the top centre so results stay above the keys.
 */
@Composable
internal fun SpotlightOverlay(
    visible: Boolean,
    apps: List<AppEntry>,
    recentIds: List<String>,
    wide: Boolean,
    backdrop: HazeState?,
    history: SpotlightHistory,
    onDismiss: () -> Unit,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onLauncherAction: (String) -> Unit,
    modifier: Modifier = Modifier,
    onShareApp: (AppEntry) -> Unit = {},
    onAppInfo: (AppEntry) -> Unit = {},
    onHideApp: ((AppEntry) -> Unit)? = null,
    /**
     * The query lives with the caller: launching an app hides this overlay at once (the frame
     * below must be plain Home), and coming back from that app reopens Search on the same query.
     */
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    /** False when Search reopens after returning from an app: results show, the keyboard stays down. */
    autoFocus: Boolean = true,
    /**
     * Shared with Home and its pull-down gesture (iOS: Spotlight follows the finger). With it, Home
     * itself blurs, dims and recedes by [SpotlightPresence.homeEffect], so this overlay only adds
     * its tint; without it the overlay blurs [backdrop] as before.
     */
    presence: SpotlightPresence? = null,
    /** Search is left for another app (a shortcut, a Settings link, the web): it comes back on the return (round 6). */
    onLeaveForApp: () -> Unit = {},
) {
    val reduced = LauncherMotion.reduced
    val ownPresence = rememberSpotlightPresence()
    val motion = presence ?: ownPresence
    val progress = motion.progress
    LaunchedEffect(visible) {
        // A finger that is pulling Search open (or dragging it closed) owns the value.
        if (motion.dragging) return@LaunchedEffect
        val target = if (visible) 1f else 0f
        // The running velocity carries over: a release that committed open keeps its fling.
        if (reduced) progress.animateTo(target, if (visible) LauncherMotion.dissolveIn else LauncherMotion.dissolveOut)
        // SwiftUI .snappy-like: fast arrival, no visible bounce on the blur.
        else progress.animateTo(target, LauncherMotion.spotlightSettle)
        if (target == 0f) motion.interactive = false
    }
    // Launching hides Spotlight at once: Home is frozen for the launch zoom right after, and that
    // frame is also the Home the app returns to, so it must not contain the search UI.
    var launchHidden by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) { launchHidden = false; motion.hidden = false } }
    // A pull after a launch that never left Home (it failed) shows the search again, not just the blur.
    LaunchedEffect(motion.dragging) { if (motion.dragging) launchHidden = false }
    DisposableEffect(visible) {
        HomeSnapshotStore.searchVisible = visible
        onDispose { HomeSnapshotStore.searchVisible = false }
    }
    // Romanising (ICU Han-Latin, pinyin) is slow the first time: warm every app name and Settings
    // title/keyword off the main thread as soon as the apps load, so even the first Spotlight
    // keystroke only ranks cached keys (code review P2-8e; this effect runs while Spotlight is
    // closed, before the early return below). A short pause keeps it off Home's first frames.
    LaunchedEffect(apps) {
        if (apps.isEmpty()) return@LaunchedEffect
        delay(SEARCH_KEY_WARM_DELAY_MS)
        SearchKeys.warm(spotlightWarmLabels(apps))
    }
    if (launchHidden || !visible && progress.value == 0f && !progress.isRunning && !motion.dragging) return
    val dark = LocalDuoPalette.current.dark
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(visible) { onDismiss() }
    LaunchedEffect(visible) {
        if (visible) {
            // Wait one frame so the field is attached before asking for the IME.
            if (autoFocus) { delay(16); runCatching { focusRequester.requestFocus() }; keyboard?.show() }
        } else { focusManager.clearFocus(); keyboard?.hide() }
    }
    var shortcuts by remember { mutableStateOf<List<SpotlightShortcut>>(emptyList()) }
    LaunchedEffect(visible, apps) {
        if (visible) loadSpotlightShortcuts(context, apps).let { loaded -> SearchKeys.warm(loaded.map { it.label }); shortcuts = loaded }
    }
    val launchable = remember(apps) { apps.filter { it.available } }
    // Keys are cached (and warmed above), so ranking a few hundred names per keystroke is cheap;
    // planning synchronously keeps results and their scroll position in step with the field.
    val plan = remember(query, launchable, recentIds) {
        planSpotlight(query, launchable.map { it.id to SearchKeys.of(it.label) }, recentIds, SearchKeys::of,
            appLimit = if (wide) 16 else 12)
    }
    val appsById = remember(apps) { apps.associateBy(AppEntry::id) }
    val matchedShortcuts = remember(query, shortcuts) {
        if (query.isBlank()) emptyList() else rankMatches(query, shortcuts, { SearchKeys.of(it.label) })
            .filter { it.score >= SearchScore.PINYIN_INNER }.map { it.item }.take(5)
    }
    val topHit = plan.topHitAppId?.let(appsById::get)
    val topHitShortcuts = remember(topHit, shortcuts) {
        topHit?.let { hit -> shortcuts.filter { it.app.id == hit.id }.sortedBy { it.info.rank }.take(4) }.orEmpty()
    }
    val view = LocalView.current
    // Round 4 (review r3): long-pressing an app result lifts it with an iOS context menu instead of
    // launching it on release. The keyboard stays put so the lifted icon matches its resting copy.
    var appMenu by remember { mutableStateOf<Pair<AppEntry, androidx.compose.ui.geometry.Rect>?>(null) }
    LaunchedEffect(visible, query) { appMenu = null }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun openAppMenu(app: AppEntry, bounds: android.graphics.Rect) {
        if (bounds.isEmpty) return
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        appMenu = app to androidx.compose.ui.geometry.Rect(bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat())
    }
    fun finish() { appMenu = null; focusManager.clearFocus(); keyboard?.hide(); onDismiss() }
    /** Back closes an open result menu first, then Search. */
    fun back() { if (appMenu != null) appMenu = null else finish() }
    fun launch(app: AppEntry, bounds: android.graphics.Rect?) {
        if (query.isNotBlank()) history.record(query)
        val from = bounds?.let { android.graphics.Rect(it) }
        launchHidden = true; motion.hidden = true
        finish()
        // Two frames: Home without Spotlight is on screen before the launch freezes it.
        view.postOnAnimation { view.postOnAnimation { onLaunch(app, from) } }
    }
    fun submit() {
        val first = topHit ?: plan.appIds.firstOrNull()?.let(appsById::get)
        when {
            query.isBlank() -> Unit
            first != null -> launch(first, null)
            else -> { history.record(query); if (openWebSearch(context, query)) { onLeaveForApp(); finish() } }
        }
    }
    val p = progress.value
    val currentFinish by rememberUpdatedState(::back)
    // iOS: Back (or the keyboard's dismiss) closes Search and its keyboard in one go. The IME
    // registers its own Back callback above ours once it shows, so ours sits at overlay priority
    // (gesture Back, API 33+) and also intercepts the Back key before the IME sees it.
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val activity = view.context.spotlightActivity()
        DisposableEffect(visible, activity) {
            val dispatcher = activity?.onBackInvokedDispatcher
            if (!visible || dispatcher == null) return@DisposableEffect onDispose { }
            val callback = android.window.OnBackInvokedCallback { currentFinish() }
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            onDispose { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }
    val density = LocalDensity.current
    val viewHeight = view.height.toFloat()
    SideEffect { motion.viewportHeightPx = viewHeight }
    val closeTravel = SpotlightPullMath.travelPx(viewHeight, density.density)
    // iOS: dragging Search away tracks the finger; the keyboard drops as soon as the drag starts
    // and stays down if Search springs back open.
    fun beginCloseDrag(direction: Float): VerticalDragSession {
        appMenu = null; focusManager.clearFocus(); keyboard?.hide()
        return motion.beginClose(closeTravel, direction) { onDismiss() }
    }
    val closeDrag = remember { arrayOfNulls<VerticalDragSession>(1) }
    var closeDragDy by remember { mutableFloatStateOf(0f) }
    val currentBeginClose by rememberUpdatedState(::beginCloseDrag)
    // Results that cannot scroll in the drag's direction (at the top, or too short to scroll) hand
    // the drag to Search itself, so pulling them down past the top closes it, as on iOS.
    val resultsPull = remember {
        spotlightResultsPull({ direction -> currentBeginClose(direction) })
    }
    Box(modifier.fillMaxSize().testTag("spotlight")
        .then(if (presence != null) Modifier.drawBehind {
            drawRect(if (dark) Color.Black.copy(alpha = .30f) else Color.White.copy(alpha = .20f),
                alpha = (if (motion.hidden) 0f else progress.value).coerceIn(0f, 1f))
        } else if (backdrop != null) Modifier.hazeEffect(backdrop, HazeStyle(
            backgroundColor = if (dark) Color.Black else Color.White,
            tint = HazeTint(if (dark) Color.Black.copy(alpha = .30f) else Color.White.copy(alpha = .20f)),
            blurRadius = 34.dp, noiseFactor = .02f,
            fallbackTint = HazeTint(if (dark) Color(0xE6101012) else Color(0xE6F2F2F7)),
        )) { alpha = p } else Modifier.background((if (dark) Color(0xFF101012) else Color(0xFFF2F2F7)).copy(alpha = .9f * p)))
        .onInterceptKeyBeforeSoftKeyboard { event ->
            if (event.key != Key.Back) false else { if (event.type == KeyEventType.KeyUp) currentFinish(); true }
        }
        .overlayInputShield(visible)
        // Only while open: fading out, a tap goes to the Home icon under it (it was swallowed).
        .then(if (visible) Modifier.pointerInput(Unit) { detectTapGestures { currentFinish() } } else Modifier)
        // A vertical drag on the field or the empty space closes Search with the finger: up on
        // the cover and unfolded alike (the results list scrolls its own drags first).
        .draggable(rememberDraggableState { delta ->
            closeDragDy += delta
            closeDrag[0]?.drag(closeDragDy)
        }, Orientation.Vertical, enabled = visible,
            onDragStarted = { closeDragDy = 0f; closeDrag[0] = currentBeginClose(-1f) },
            onDragStopped = { velocity -> closeDrag[0]?.release(velocity); closeDrag[0] = null })) {
        // Button/Back: the iOS 26 entrance (rises on the cover, drops in unfolded). A finger: the
        // field comes down with the pull and the results fade in behind it, the content moving
        // with the finger past the ends ([SpotlightPresence.follow]).
        val fieldLayer = Modifier.graphicsLayer {
            val q = progress.value
            alpha = q.coerceIn(0f, 1f)
            if (!reduced) {
                val s = .96f + .04f * q.coerceIn(0f, 1f)
                scaleX = s; scaleY = s
                translationY = if (motion.interactive) -(1f - q) * 44.dp.toPx() + motion.follow.value
                    else (1f - q) * (if (wide) -18.dp.toPx() else 28.dp.toPx())
            }
        }
        val resultsLayer = Modifier.graphicsLayer {
            val q = progress.value
            alpha = if (motion.interactive) SpotlightPullMath.resultsAlpha(q) else q.coerceIn(0f, 1f)
            if (!reduced) {
                val s = .96f + .04f * q.coerceIn(0f, 1f)
                scaleX = s; scaleY = s
                translationY = if (motion.interactive) -(1f - q) * 24.dp.toPx() + motion.follow.value
                    else (1f - q) * (if (wide) -18.dp.toPx() else 28.dp.toPx())
            }
        }
        val results: @Composable (Modifier) -> Unit = { resultModifier ->
            SpotlightResults(query = query, plan = plan, appsById = appsById, recentIds = recentIds,
                topHitShortcuts = topHitShortcuts, shortcuts = matchedShortcuts, history = history, wide = wide, dark = dark,
                onQuery = onQueryChange, onLaunch = ::launch, onLongPress = ::openAppMenu,
                onShortcut = { shortcut, bounds ->
                    if (query.isNotBlank()) history.record(query)
                    if (startSpotlightShortcut(context, shortcut, bounds)) { onLeaveForApp(); finish() }
                },
                onSettings = { link ->
                    if (query.isNotBlank()) history.record(query)
                    if (link.opensLauncherSheet) { finish(); onLauncherAction(link.action.removePrefix(DUO_ACTION_PREFIX)) }
                    else if (openSettingsLink(context, link)) { onLeaveForApp(); finish() }
                },
                onWeb = { history.record(query); if (openWebSearch(context, query)) { onLeaveForApp(); finish() } },
                modifier = resultModifier.then(resultsLayer).nestedScroll(resultsPull))
        }
        val field: @Composable (Modifier) -> Unit = { fieldModifier ->
            SpotlightField(query, onQueryChange, focusRequester, dark, onSubmit = ::submit,
                onClose = ::finish, modifier = fieldModifier.then(fieldLayer))
        }
        if (wide) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(top = 18.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val width = Modifier.widthIn(max = 600.dp).fillMaxWidth(.66f)
                field(width)
                Spacer(Modifier.height(12.dp))
                results(width.weight(1f, fill = false))
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                results(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp))
                field(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        val menu = appMenu
        // The menu stays above the keyboard (the IME window would cover it).
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current).toFloat()
        val rootSize = LocalView.current.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
        IosContextMenuHost(
            request = if (menu == null || !visible) null else IosMenuRequest(
                key = "spotlight-${menu.first.id}", anchor = menu.second,
                sections = listOfNotNull(
                    if (onHideApp != null && AppPrivacy.canHide(LocalContext.current, menu.first)) listOf(
                        IosMenuAction(uxText("隐藏 App", "Hide App"), IosSymbols.eyeSlash, "spotlight-menu-hide") {
                            appMenu = null; onHideApp(menu.first)
                        }) else null,
                    listOf(
                    IosMenuAction(uxText("分享 App", "Share App"), IosSymbols.share, "spotlight-menu-share") {
                        appMenu = null; onShareApp(menu.first)
                    },
                    IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "spotlight-menu-info") {
                        appMenu = null; onAppInfo(menu.first)
                    },
                )),
                overlayTag = "spotlight-app-overlay", menuTag = "spotlight-app-menu",
                preview = { AppIcon(menu.first, with(LocalDensity.current) { menu.second.width.toDp() }) },
                previewShape = homeIconShape(with(LocalDensity.current) { menu.second.width.toDp().value })),
            onDismissRequest = { appMenu = null },
            safeArea = androidx.compose.ui.geometry.Rect(0f, 0f, rootSize.width, (rootSize.height - imeBottom).coerceAtLeast(1f)))
    }
}

@Composable
private fun SpotlightField(
    query: String, onQuery: (String) -> Unit, focusRequester: FocusRequester, dark: Boolean,
    onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
) {
    val ink = spotlightInk(dark)
    val secondary = spotlightSecondary(dark)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val capsule = RoundedCornerShape(SpotlightStyle.fieldHeight / 2)
        val placeholder = uxText("搜索", "Search")
        BasicTextField(query, onQuery, singleLine = true,
            textStyle = TextStyle(color = ink, fontSize = 17.sp),
            cursorBrush = SolidColor(IosUi.blue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onGo = { onSubmit() }, onDone = { onSubmit() }),
            modifier = Modifier.weight(1f).height(SpotlightStyle.fieldHeight)
                // Frosted like the result platters so the text reads over any wallpaper.
                .duoGlass(DuoGlassRole.Floating, capsule, frost = spotlightFieldFrost(dark))
                .focusRequester(focusRequester).testTag("spotlight-field")
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(IosSymbols.magnifyingGlass, null, Modifier.size(20.dp), tint = secondary)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(placeholder, color = secondary, fontSize = 17.sp, maxLines = 1)
                        inner()
                    }
                    if (query.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onQuery("") }
                        .testTag("spotlight-clear"), contentAlignment = Alignment.Center) {
                        IosClearGlyph(secondary.copy(alpha = .5f), 17.dp, uxText("清除文本", "Clear text"))
                    }
                }
            })
        Spacer(Modifier.width(10.dp))
        GlassCircleButton(IosSymbols.xmark, uxText("关闭搜索", "Close Search"), "spotlight-close",
            SpotlightStyle.fieldHeight, ink, onClose, frost = spotlightFieldFrost(dark))
    }
}

/** A Liquid-Glass circle with an iOS press response (sinks, no ripple). */
@Composable
internal fun GlassCircleButton(icon: ImageVector, label: String, tag: String, size: Dp, tint: Color, onClick: () -> Unit,
    frost: Color = Color.Transparent) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(size).duoGlass(DuoGlassRole.Floating, CircleShape, interaction, frost = frost)
        .clickable(interaction, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
private fun SpotlightResults(
    query: String,
    plan: SpotlightPlan,
    appsById: Map<String, AppEntry>,
    recentIds: List<String>,
    topHitShortcuts: List<SpotlightShortcut>,
    shortcuts: List<SpotlightShortcut>,
    history: SpotlightHistory,
    wide: Boolean,
    dark: Boolean,
    onQuery: (String) -> Unit,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onLongPress: (AppEntry, android.graphics.Rect) -> Unit,
    onShortcut: (SpotlightShortcut, android.graphics.Rect?) -> Unit,
    onSettings: (SettingsLink) -> Unit,
    onWeb: () -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // Results are keyed; without this a row that existed before the keystroke would keep its place.
    LaunchedEffect(query) { listState.scrollToItem(0) }
    val columns = if (wide) 6 else 4
    val density = LocalDensity.current
    // With the keyboard up the cover leaves ≈210 dp for results: the Top Hit drops its action pills
    // below 260 dp so the next section is visible too.
    var viewportHeight by remember { mutableStateOf(Dp.Unspecified) }
    val compactTopHit = viewportHeight != Dp.Unspecified && viewportHeight < 260.dp
    // The list consumes taps on results; empty space between platters still dismisses. Rows fade
    // out above the floating field (iOS 26 scroll edge) instead of being cut by its top edge.
    LazyColumn(modifier.onSizeChanged { viewportHeight = with(density) { it.height.toDp() } }
        .iosScrollEdgeFade(top = 14.dp, bottom = 28.dp, fadeTop = { listState.canScrollBackward },
            fadeBottom = { listState.canScrollForward })
        .testTag("spotlight-results"), state = listState,
        contentPadding = PaddingValues(top = if (wide) 0.dp else 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (query.isBlank()) {
            val suggestions = recentIds.mapNotNull(appsById::get).filter(AppEntry::available).distinctBy(AppEntry::id).take(columns)
            if (suggestions.isNotEmpty()) item("suggestions") {
                SpotlightSection(uxText("Siri 建议", "Siri Suggestions"), dark) {
                    SpotlightAppGrid(suggestions, columns, dark, onLaunch, onLongPress, "spotlight-suggestion")
                }
            }
            if (history.recent.isNotEmpty()) item("recent") {
                SpotlightSection(uxText("最近搜索", "Recent Searches"), dark,
                    action = uxText("清除", "Clear") to history::clear) {
                    history.recent.take(6).forEachIndexed { index, text ->
                        if (index > 0) SpotlightDivider(dark, 48.dp)
                        SpotlightRow(title = text, dark = dark, tag = "spotlight-recent-$index",
                            leading = { GlyphTile(IosSymbols.history, Color.Transparent, spotlightSecondary(dark), 28.dp) },
                            onClick = { onQuery(text) })
                    }
                }
            }
            return@LazyColumn
        }
        val top = plan.topHitAppId?.let(appsById::get)
        if (top != null) item("top-hit") {
            SpotlightSection(uxText("最佳结果", "Top Hit"), dark) {
                TopHitRow(top, if (compactTopHit) emptyList() else topHitShortcuts, dark, onLaunch, onLongPress, onShortcut)
            }
        }
        val others = plan.appIds.mapNotNull(appsById::get)
        if (others.isNotEmpty()) item("apps") {
            SpotlightSection("App", dark) { SpotlightAppGrid(others, columns, dark, onLaunch, onLongPress, "spotlight-app") }
        }
        if (shortcuts.isNotEmpty()) item("shortcuts") {
            SpotlightSection(uxText("App 快捷操作", "App Actions"), dark) {
                shortcuts.forEachIndexed { index, shortcut ->
                    if (index > 0) SpotlightDivider(dark, 60.dp)
                    val bounds = remember { android.graphics.Rect() }
                    SpotlightRow(title = shortcut.label, subtitle = shortcut.app.label, dark = dark,
                        tag = "spotlight-shortcut-$index",
                        leading = { ShortcutIcon(shortcut, Modifier.onGloballyPositioned {
                            bounds.set(it.boundsInWindow().toAndroidBounds()) }) },
                        onClick = { onShortcut(shortcut, bounds) })
                }
            }
        }
        if (plan.settings.isNotEmpty()) item("settings") {
            val settingsApp = remember(appsById) { appsById.values.firstOrNull { it.packageName == "com.android.settings" && it.isPersonal } }
            SpotlightSection(uxText("设置", "Settings"), dark) {
                plan.settings.forEachIndexed { index, link ->
                    if (index > 0) SpotlightDivider(dark, 60.dp)
                    SpotlightRow(title = link.title,
                        subtitle = if (link.opensLauncherSheet) "18Duo" else uxText("设置", "Settings"), dark = dark,
                        tag = "spotlight-settings-${link.id}",
                        leading = {
                            if (!link.opensLauncherSheet && settingsApp != null) AppIcon(settingsApp, 34.dp)
                            else if (link.opensLauncherSheet) LauncherOwnIcon(34.dp)
                            else GlyphTile(IosSymbols.gear, Color(0xFF8E8E93), Color.White, 34.dp)
                        },
                        onClick = { onSettings(link) })
                }
            }
        }
        item("web") {
            SpotlightSection(uxText("网页", "Web"), dark) {
                SpotlightRow(title = uxText("搜索网页", "Search Web"), subtitle = "“${query.trim()}”", dark = dark,
                    tag = "spotlight-web",
                    leading = { GlyphTile(IosSymbols.compass, IosUi.blue, Color.White, 34.dp) },
                    onClick = onWeb)
            }
        }
        if (top == null && others.isEmpty() && shortcuts.isEmpty() && plan.settings.isEmpty()) item("empty") {
            Text(uxText("没有 App 结果", "No App Results"), Modifier.fillMaxWidth().padding(top = 6.dp),
                color = spotlightSecondary(dark), fontSize = 15.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SpotlightSection(
    title: String, dark: Boolean, action: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, color = spotlightSecondary(dark),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            action?.let { (label, onClick) ->
                Text(label, Modifier.clip(RoundedCornerShape(8.dp))
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp), color = IosUi.blue, fontSize = 13.sp)
            }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(SpotlightStyle.sectionRadius))
            .background(spotlightPlatter(dark))
            .border(.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) .18f else .55f), Color.Transparent)),
                RoundedCornerShape(SpotlightStyle.sectionRadius))
            // Swallow taps on the platter itself so only the backdrop dismisses.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(vertical = 6.dp), content = content)
    }
}

@Composable
private fun SpotlightDivider(dark: Boolean, inset: Dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(.5.dp).background(spotlightSeparator(dark)))
}

@Composable
private fun SpotlightRow(
    title: String, dark: Boolean, tag: String, subtitle: String? = null,
    leading: @Composable () -> Unit, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth().heightIn(min = SpotlightStyle.rowHeight - 10.dp)
        .background(if (pressed) spotlightInk(dark).copy(alpha = .08f) else Color.Transparent)
        .clickable(interaction, null, role = Role.Button, onClick = onClick).testTag(tag)
        .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = spotlightInk(dark), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = spotlightSecondary(dark), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun TopHitRow(
    app: AppEntry, shortcuts: List<SpotlightShortcut>, dark: Boolean,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onLongPress: (AppEntry, android.graphics.Rect) -> Unit,
    onShortcut: (SpotlightShortcut, android.graphics.Rect?) -> Unit,
) {
    val bounds = remember { android.graphics.Rect() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(if (pressed) spotlightInk(dark).copy(alpha = .08f) else Color.Transparent)
            .combinedClickable(interaction, null, role = Role.Button, onLongClick = { onLongPress(app, bounds) }) { onLaunch(app, bounds) }
            .testTag("spotlight-top-hit").padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, 52.dp, Modifier.onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, color = spotlightInk(dark), fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(when {
                    app.isWork -> uxText("App · 工作", "App · Work")
                    app.isClone -> uxText("App · 双开", "App · Clone")
                    else -> "App"
                }, color = spotlightSecondary(dark), fontSize = 13.sp)
            }
        }
        if (shortcuts.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shortcuts.take(3).forEachIndexed { index, shortcut ->
                val chipBounds = remember { android.graphics.Rect() }
                val chipInteraction = remember { MutableInteractionSource() }
                val chipPressed by chipInteraction.collectIsPressedAsState()
                Row(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                    .background(spotlightInk(dark).copy(alpha = if (chipPressed) .14f else .07f))
                    .onGloballyPositioned { chipBounds.set(it.boundsInWindow().toAndroidBounds()) }
                    .clickable(chipInteraction, null, role = Role.Button) { onShortcut(shortcut, chipBounds) }
                    .testTag("spotlight-top-action-$index").padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    ShortcutIcon(shortcut, Modifier, 20.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(shortcut.label, color = spotlightInk(dark), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SpotlightAppGrid(
    apps: List<AppEntry>, columns: Int, dark: Boolean,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onLongPress: (AppEntry, android.graphics.Rect) -> Unit,
    tagPrefix: String,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { app ->
                    val bounds = remember(app.id) { android.graphics.Rect() }
                    val interaction = remember(app.id) { MutableInteractionSource() }
                    Column(Modifier.weight(1f).combinedClickable(interaction, null, role = Role.Button,
                        onLongClick = { onLongPress(app, bounds) }) { onLaunch(app, bounds) }
                        .semantics(mergeDescendants = true) { contentDescription = app.label }
                        .testTag("$tagPrefix-${app.id}").padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(app, 54.dp, Modifier.iosIconHighlight(interaction)
                            .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) })
                        Text(app.label, Modifier.fillMaxWidth().padding(top = 5.dp, start = 2.dp, end = 2.dp),
                            color = spotlightInk(dark), fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun AppIcon(app: AppEntry, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(app.icon) { app.icon.asImageBitmap() }
    Image(bitmap, null, modifier.size(size).clip(homeIconShape(size.value))
        .graphicsLayer { alpha = if (app.available) 1f else .45f })
}

@Composable
private fun ShortcutIcon(shortcut: SpotlightShortcut, modifier: Modifier, size: Dp = 34.dp) {
    val icon = rememberShortcutIcon(shortcut)
    if (icon != null) Image(icon, null, modifier.size(size).clip(homeIconShape(size.value)))
    else AppIcon(shortcut.app, size, modifier)
}

@Composable
private fun LauncherOwnIcon(size: Dp) {
    val context = LocalContext.current
    val bitmap = remember(context) {
        runCatching { launcherIcon(context.packageManager.getApplicationIcon(context.packageName)).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) Image(bitmap, null, Modifier.size(size).clip(homeIconShape(size.value)))
    else GlyphTile(IosSymbols.gear, IosUi.indigo, Color.White, size)
}

@Composable
internal fun GlyphTile(icon: ImageVector, background: Color, tint: Color, size: Dp) {
    Box(Modifier.size(size).clip(homeIconShape(size.value)).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .6f), tint = tint)
    }
}

private tailrec fun Context.spotlightActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.spotlightActivity()
    else -> null
}
