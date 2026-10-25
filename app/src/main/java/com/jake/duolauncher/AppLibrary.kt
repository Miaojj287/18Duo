@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------
// App metadata for categorisation (category, system flag, install time), read once per package.
// ---------------------------------------------------------------------------------------

private data class AppMeta(val androidCategory: Int, val system: Boolean, val installedAt: Long)

private val appMetaCache = ConcurrentHashMap<String, AppMeta>()

private suspend fun loadLibraryApps(context: Context, apps: List<AppEntry>): List<LibraryApp> = withContext(Dispatchers.IO) {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    apps.map { app ->
        val key = "${app.userSerial}/${app.packageName}"
        val meta = appMetaCache[key] ?: run {
            val info = runCatching { launcherApps.getApplicationInfo(app.packageName, 0, app.user) }.getOrNull()
            val installed = runCatching {
                launcherApps.getActivityList(app.packageName, app.user).firstOrNull()?.firstInstallTime
            }.getOrNull() ?: 0L
            val system = info != null && info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            AppMeta(info?.category ?: AndroidAppCategory.UNDEFINED, system, installed)
                .also { if (info != null) appMetaCache[key] = it }
        }
        SearchKeys.of(app.label)
        LibraryApp(app.id, app.label, categorizeApp(app.packageName, meta.androidCategory, meta.system), meta.installedAt, meta.system)
    }
}

// ---------------------------------------------------------------------------------------
// App Library page (after the last Home page) and the "Add to Home" picker sheet.
// ---------------------------------------------------------------------------------------

@Composable
internal fun AppLibrary(
    state: LauncherState, query: String, onQuery: (String) -> Unit,
    onLaunch: (AppEntry) -> Unit, onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier, editing: Boolean = false,
    drag: HomeDragState? = null, page: Int? = null,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    onTurnOnWork: (Long) -> Unit = {},
    /** The hoisted search session (Home's App Library); null keeps the list mode local (previews, tests). */
    search: LibrarySearchSession? = null,
    /** Recent-apps order to sort by (null: live). Home freezes it while the library is in view (round 6). */
    recentOrder: List<String>? = null,
    /**
     * Where the header (search field) sits from the top of the box. A library page drawn edge to edge passes the
     * Home grid's safe top (the categories scroll up under it, iOS 26); a pane that is already inset passes 0.
     */
    safeTop: Dp = 0.dp,
    /** The band at the bottom of an edge-to-edge box that the library used to stop above (page dots / Dock).
     * The A–Z list and an expanded category keep their Core.27 box (safeTop … this). */
    homeBottom: Dp = 0.dp,
) {
    val hasWork = state.profiles.any { it.isWork } || state.apps.any { it.isWork }
    val localShowWork = rememberSaveable { mutableStateOf(false) }
    // Hoisted with the search (Personal / Work tab), so an expanded work category comes back as it was.
    val showWork = if (search != null) search.work else localShowWork.value
    val selectedProfile = if (showWork) state.profiles.firstOrNull { it.isWork } else state.profiles.firstOrNull { it.isPersonal }
    val allProfileApps = remember(state.apps, showWork, hasWork) { state.apps.filter { !hasWork || it.isWork == showWork } }
    // Hidden apps (AppPrivacy) leave every category, the A–Z list and the search; only "已隐藏" lists them.
    val hiddenIds = AppPrivacy.hidden
    val profileApps = remember(allProfileApps, hiddenIds) { allProfileApps.withoutHidden() }
    val hiddenApps = remember(allProfileApps, hiddenIds) { allProfileApps.filter { it.id in hiddenIds } }
    if (editing) {
        PinPickerList(state, profileApps, query, onQuery, onPin, onActions, hasWork, showWork, { localShowWork.value = it },
            selectedProfile, onTurnOnWork, modifier)
        return
    }
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current as? MainActivity
    val recentIds = recentOrder ?: activity?.recentApps?.ids.orEmpty()
    val focusManager = LocalFocusManager.current
    val localListMode = rememberSaveable { mutableStateOf(false) }
    val listMode = search?.open ?: localListMode.value
    fun setListMode(open: Boolean) { if (search != null) search.open = open else localListMode.value = open }
    // The expanded category lives in the hoisted session (round 6) so it outlives this pane.
    val localCategory = rememberSaveable { mutableStateOf<String?>(null) }
    val openCategory = if (search != null) search.openCategory else localCategory.value
    fun setCategory(name: String?) { if (search != null) search.openCategory = name else localCategory.value = name }
    fun setShowWork(work: Boolean) {
        if (work != showWork) setCategory(null)
        if (search != null) search.work = work else localShowWork.value = work
    }
    // Each tile's platter bounds (root px), read in the draw phase by the zoom; never state.
    val tileBounds = remember { HashMap<String, Rect>() }
    // 0 = the category rests in its tile, 1 = expanded. Restored open (back from an app), it is already open.
    val categoryProgress = remember { Animatable(if (openCategory != null) 1f else 0f) }
    val categoryOpen = openCategory != null
    LaunchedEffect(categoryOpen) {
        // Retargets from wherever the spring is, keeping its velocity: a tap outside mid-zoom reverses it.
        categoryProgress.animateTo(if (categoryOpen) 1f else 0f, when {
            LauncherMotion.reduced -> if (categoryOpen) LauncherMotion.dissolveIn else LauncherMotion.dissolveOut
            categoryOpen -> libraryCategoryOpenSpring
            else -> libraryCategoryCloseSpring
        })
    }
    // A long press keeps the category open under its menu; dragging the app out takes it to Home (iOS),
    // and the category does not stay open behind it.
    if (drag != null && categoryOpen) LaunchedEffect(drag) {
        snapshotFlow { drag.source?.target is DropTarget.Library && drag.moved }.first { it }
        categoryProgress.snapTo(0f); setCategory(null)
    }
    val searching = listMode || query.isNotEmpty()
    fun leaveList() { onQuery(""); setListMode(false); focusManager.clearFocus() }
    BackHandler(searching || openCategory != null) { if (openCategory != null) setCategory(null) else leaveList() }
    // iOS: pulling the categories down, or tapping the field, turns them into the A–Z list with the field focused
    // and the keyboard rising. One spring (0 = categories, 1 = list) drives both in the draw phase: the categories
    // recede and fade while the list fades in and rises into place; Cancel reverses it from wherever it is. Both
    // stay composed while it runs, so neither tree is built in the frame the list appears (the old flash).
    val searchProgress = remember { Animatable(if (searching) 1f else 0f) }
    LaunchedEffect(searching) {
        searchProgress.animateTo(if (searching) 1f else 0f, when {
            LauncherMotion.reduced -> if (searching) LauncherMotion.dissolveIn else LauncherMotion.dissolveOut
            else -> LauncherMotion.springSpec(LIBRARY_SEARCH_SPRING, .001f)
        })
    }
    val listShown by remember(searching) { derivedStateOf { searching || searchProgress.value > .001f } }
    // A pull-down focuses the field exactly as a tap does; consumed once, so a re-created field never re-focuses.
    var focusPending by remember { mutableStateOf(false) }
    val pullDown: () -> Unit = { setListMode(true); focusPending = true }
    // Hoisted: the categories keep their scroll under the list, and the top scroll edge follows it.
    val gridState = rememberLazyGridState()
    // The shared Home backdrop (root wallpaper): the categories join it so the field and the scroll edge see them.
    val edgeBackdrop = LocalDuoGlassBackdrop.current
    val libraryApps by produceState<List<LibraryApp>?>(null, profileApps) { value = loadLibraryApps(context, profileApps) }
    val folders = remember(libraryApps, recentIds, hiddenApps) {
        libraryApps?.let { apps ->
            val built = buildLibraryFolders(apps, recentIds, System.currentTimeMillis()) { app -> SearchKeys.of(app.label).sortKey }
            // iOS 18+: "已隐藏" sits at the very bottom of the App Library whether or not anything is hidden.
            if (built.isEmpty() && hiddenApps.isEmpty()) built
            else built + LibraryFolder(LibraryCategory.HIDDEN, hiddenApps.sortedBy { SearchKeys.of(it.label).sortKey }.map(AppEntry::id))
        }
    }
    val appsById = remember(allProfileApps) { allProfileApps.associateBy(AppEntry::id) }
    // The Hidden folder locks again whenever the library is left: another page, the screen off, an app opened.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) AppPrivacy.relock()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); AppPrivacy.relock() }
    }
    val revealHidden: (LibraryFolder, Rect) -> Unit = { folder, bounds ->
        AppPrivacy.authenticate(context, uxText("显示已隐藏的 App", "Show Hidden Apps")) { ok -> if (ok) AppPrivacy.reveal() }
    }
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(modifier.onGloballyPositioned { rootBounds = it.boundsInRoot() }) {
        // The search field and segmented control share the folders' outer edges, as on iOS.
        val narrow = folderMetrics(maxWidth)
        val edge = narrow.edge
        val edgeEnd = narrow.edgeEnd
        // iPadOS blurs and dims the library behind an opened category, in step with its zoom.
        val behind = Modifier.libraryBehindCategory { categoryProgress.value }
        val onOpenCategory: (LibraryFolder, Rect) -> Unit = { folder, bounds ->
            tileBounds[folder.category.name] = bounds; setCategory(folder.category.name)
        }
        val wideLibrary = maxWidth.value >= LIBRARY_WIDE_MIN_WIDTH
        if (wideLibrary) WideLibraryContent(
            frame = wideLibraryFrame(maxWidth.value, maxHeight.value, extraTop = if (hasWork) WIDE_LIBRARY_WORK_BAND else 0f),
            query = query, onQuery = onQuery, searching = searching, onFocused = { setListMode(true) }, onCancel = ::leaveList,
            hasWork = hasWork, showWork = showWork, onShowWork = ::setShowWork, selectedProfile = selectedProfile,
            onTurnOnWork = onTurnOnWork, loading = state.loading, folders = folders, appsById = appsById,
            profileApps = profileApps, recentIds = recentIds, drag = drag, page = page, onLaunchFrom = onLaunchFrom,
            onActions = onActions, categoryOpen = openCategory != null, onOpenCategory = onOpenCategory,
            onPullDown = pullDown, tileBounds = tileBounds,
            modifier = Modifier.fillMaxSize().then(behind), onRevealHidden = revealHidden,
            gridState = gridState, searchProgress = { searchProgress.value }, listShown = listShown,
            edgeBackdrop = edgeBackdrop,
            focusPending = focusPending, onFocusHandled = { focusPending = false },
        ) else
        Box(Modifier.fillMaxSize().then(behind)) {
            // iOS 26 (user-photos-r13/2.webp): the categories run edge to edge, up under the status area and the
            // floating field (which refracts them) and off the bottom edge; only their resting first
            // row starts below the header.
            // iPhone Duo: the first row rests 0.0996 L below the field (r21, 2.webp / 3.webp; L = the field's width).
            val header = LIBRARY_FIELD_HEIGHT.dp + narrow.libraryWidth * LIBRARY_FIELD_TO_GRID + if (hasWork) 56.dp else 0.dp
            val contentTop = safeTop + header
            val pausedProfile = selectedProfile?.takeIf { showWork && it.available == false }
            if (pausedProfile != null) Box(Modifier.fillMaxSize().padding(top = contentTop)) {
                WorkPausedNotice(pausedProfile, onTurnOnWork, Color.White)
            } else {
                val categories = Modifier.fillMaxSize().libraryEdgeSource(edgeBackdrop)
                    .graphicsLayer { libraryCategoriesRecede(searchProgress.value) }.libraryInert(searching)
                val currentFolders = folders
                when {
                    currentFolders == null || (currentFolders.isEmpty() && state.loading) ->
                        Box(categories.padding(top = contentTop), contentAlignment = Alignment.Center) { IosActivityIndicator(color = Color.White) }
                    currentFolders.isEmpty() -> Text(launcherText("No apps found"), categories.padding(top = contentTop + 24.dp),
                        color = Color.White, textAlign = TextAlign.Center, style = LibraryLabelShadow)
                    // Under the list its icons are not drop regions: the list's rows are (same app ids).
                    else -> LibraryFolderGrid(currentFolders, appsById, drag.takeIf { openCategory == null && !searching }, page,
                        onLaunchFrom, onActions,
                        onOpenCategory = onOpenCategory,
                        onPullDown = pullDown, tileBounds = tileBounds,
                        modifier = categories, onRevealHidden = revealHidden,
                        gridState = gridState, contentTop = contentTop)
                }
                // The list keeps its Core.27 box (below the header, above the old Dock band) and stops above the keyboard.
                if (listShown) LibraryAlphabetList(profileApps, query, recentIds, drag, page, onLaunchFrom, onActions,
                    Modifier.fillMaxSize().padding(start = edge, end = edgeEnd, top = contentTop)
                        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets(bottom = homeBottom)))
                        .graphicsLayer { libraryListReveal(searchProgress.value) }.libraryInert(!searching))
            }
            Column(Modifier.fillMaxWidth().padding(top = safeTop)) {
            Row(Modifier.fillMaxWidth().padding(start = edge, end = edgeEnd, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                LibrarySearchField(query, onQuery, Modifier.weight(1f),
                    onFocused = { setListMode(true) }, tag = "library-search", centered = !searching,
                    focusPending = focusPending, onFocusHandled = { focusPending = false })
                val cancelWidth by animateDpAsState(if (searching) 64.dp else 0.dp, LauncherMotion.size, label = "library cancel")
                if (cancelWidth > 0.dp) Box(Modifier.width(cancelWidth).height(44.dp)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { leaveList() }
                    .testTag("library-cancel"), contentAlignment = Alignment.CenterEnd) {
                    Text(uxText("取消", "Cancel"), color = Color.White, fontSize = 16.sp, maxLines = 1,
                        style = LibraryLabelShadow)
                }
            }
            if (hasWork) IosSegmentedControl(listOf(launcherText("Personal"), launcherText("Work")),
                if (showWork) 1 else 0, { setShowWork(it == 1) }, Modifier.fillMaxWidth().padding(start = edge, end = edgeEnd, bottom = 12.dp),
                tags = listOf("library-personal", "library-work"))
            }
        }
        // Kept composed while it zooms back into its tile.
        val retained = remember { arrayOfNulls<LibraryFolder>(1) }
        val category = openCategory?.let { name -> folders?.firstOrNull { it.category.name == name } }
        SideEffect { if (category != null) retained[0] = category }
        val closing by remember { derivedStateOf { categoryProgress.value > .001f } }
        val shown = category ?: retained[0]?.takeIf { closing }
        if (shown != null) key(shown.category) {
            // Its icons are the return flight's targets while it is open (r6-return, IconLayer).
            CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.LIBRARY_CATEGORY) {
            val safeTopPx = with(LocalDensity.current) { safeTop.toPx() }
            // The whole edge-to-edge box closes an open category on a tap (the categories now reach under the
            // header and into the old Dock band); the panel itself keeps its Core.27 box and geometry.
            Box(Modifier.fillMaxSize().then(if (category != null) Modifier.pointerInput(Unit) { detectTapGestures { setCategory(null) } }
                else Modifier)) {
            Box(Modifier.fillMaxSize().padding(top = safeTop, bottom = homeBottom)) {
            LibraryCategoryPanel(shown, appsById, drag.takeIf { shown.category != LibraryCategory.HIDDEN }, page, onLaunchFrom, onActions,
                progress = categoryProgress, open = category != null, wide = wideLibrary,
                origin = { tileBounds[shown.category.name]?.translate(-rootBounds.left, -rootBounds.top - safeTopPx) },
                onDismiss = { setCategory(null) })
            }
            }
            }
        }
        // A category that no longer exists (its last app was removed, another profile) closes.
        if (openCategory != null && folders != null && category == null) LaunchedEffect(Unit) { setCategory(null) }
        // The Hidden folder locked again (screen off, an app opened, the library left): it cannot stay open.
        if (openCategory == LibraryCategory.HIDDEN.name && !AppPrivacy.revealed) LaunchedEffect(Unit) { setCategory(null) }
    }
}

private val LibraryLabelShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .4f), Offset(0f, 1f), 3f))

@Composable
private fun LibrarySearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier, onFocused: () -> Unit, tag: String,
    height: Dp = 44.dp,
    /**
     * iOS: at rest the magnifier and "App 资源库" sit in the middle of the capsule; tapping the field glides them
     * to its leading edge (in step with the field making room for "取消") and they come back on Cancel.
     */
    centered: Boolean = false,
    /** Focus the field now (a pull-down on the categories); [onFocusHandled] consumes the request. */
    focusPending: Boolean = false, onFocusHandled: () -> Unit = {}) {
    val placeholder = uxText("App资源库", "App Library")
    val ink = duoGlassContentColor(DuoGlassRole.Control)
    val capsule = RoundedCornerShape(50)
    // Focusable only once a finger is on it: a key event (adb keyevent, a volume key) takes Android out
    // of touch mode, and Compose would then give this first text field "initial focus" and pop the
    // keyboard up over Home. Disarmed again whenever it loses focus.
    var armed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // A pull-down arms and focuses the field as a finger on it would; the keyboard rises with the list (iOS).
    LaunchedEffect(focusPending) {
        if (!focusPending) return@LaunchedEffect
        armed = true
        withFrameNanos { }
        if (runCatching { focusRequester.requestFocus() }.isFailure) armed = false
        keyboard?.show()
        onFocusHandled()
    }
    BasicTextField(query, onQuery, singleLine = true,
        textStyle = TextStyle(color = ink, fontSize = 17.sp),
        cursorBrush = SolidColor(IosUi.blue),
        modifier = modifier.height(height).duoGlass(DuoGlassRole.Control, capsule)
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); armed = true }
            }
            .focusRequester(focusRequester)
            .focusProperties { canFocus = armed }
            .onFocusChanged { if (it.isFocused) onFocused() else if (!it.hasFocus) armed = false }
            .testTag(tag).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val placeholderWidth = remember(placeholder, measurer, density) {
                measurer.measure(placeholder, TextStyle(fontSize = 17.sp), maxLines = 1).size.width.toFloat()
            }
            // 0 = leading (searching), 1 = centred (at rest). Retargets mid-flight like the field's own glide.
            val centring by androidx.compose.animation.core.animateFloatAsState(if (centered && query.isEmpty()) 1f else 0f,
                LauncherMotion.motion(LIBRARY_FIELD_GLIDE, .001f), label = "library placeholder")
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val shift = with(density) {
                // Magnifier + gap + text, centred in the row's free width (12 dp start, 4 dp end padding).
                val content = 18.dp.toPx() + 7.dp.toPx() + placeholderWidth
                ((constraints.maxWidth - 16.dp.toPx() - content) / 2f).coerceAtLeast(0f)
            }
            Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(IosSymbols.magnifyingGlass, null, Modifier.size(18.dp)
                    .graphicsLayer { translationX = shift * centring }, tint = ink.copy(alpha = .62f))
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, Modifier.graphicsLayer { translationX = shift * centring },
                        color = ink.copy(alpha = .62f), fontSize = 17.sp, maxLines = 1, softWrap = false)
                    inner()
                }
                if (query.isNotEmpty()) Box(Modifier.size(36.dp).clip(CircleShape)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onQuery("") },
                    contentAlignment = Alignment.Center) {
                    IosClearGlyph(ink.copy(alpha = .45f), 17.dp, launcherText("Clear search"))
                }
            }
            }
        })
}

/** The placeholder's glide between the centre and the leading edge: the field's own settle (response ≈ 0.29 s). */
private val LIBRARY_FIELD_GLIDE = IosSpring.fromStiffness(480f, 1f)

/** Categories ⇄ A–Z list (pull-down, tap on the field, Cancel): response 0.35 s, no bounce. */
private val LIBRARY_SEARCH_SPRING = IosSpring(.35f, 1f)
/** How much the categories recede (scale) and how far the list rises (dp) in that transition. */
private const val LIBRARY_SEARCH_RECEDE = .06f
private const val LIBRARY_LIST_RISE_DP = 24f
/** The narrow library's search field height (dp); the header is the field plus its 12 dp gap. */
private const val LIBRARY_FIELD_HEIGHT = 44f
/** zIndex of the categories in the shared Home backdrop: above the root wallpaper (0), so the categories' own
 * platters (LiquidGlass samples only lower zIndex inside a source) never sample themselves. */
private const val LIBRARY_BACKDROP_Z = 1f
/** Room under the last row when scrolled to the end (dp). */
private const val LIBRARY_GRID_BOTTOM_DP = 28f

/** The categories as the A–Z list takes over ([p] 0 → 1): they recede a little toward the field and fade. */
private fun GraphicsLayerScope.libraryCategoriesRecede(p: Float) {
    alpha = (1f - p).coerceIn(0f, 1f)
    if (LauncherMotion.reduced) return
    val s = 1f - LIBRARY_SEARCH_RECEDE * p.coerceIn(0f, 1f)
    scaleX = s; scaleY = s
    transformOrigin = TransformOrigin(.5f, 0f)
}

/** The A–Z list arriving ([p] 0 → 1): it fades in as it rises the last few points into place. */
private fun GraphicsLayerScope.libraryListReveal(p: Float) {
    alpha = p.coerceIn(0f, 1f)
    if (!LauncherMotion.reduced) translationY = LIBRARY_LIST_RISE_DP.dp.toPx() * (1f - p.coerceIn(0f, 1f))
}

/** The categories join the shared Home backdrop, so glass over them (the search field) and the scroll edge see them. */
private fun Modifier.libraryEdgeSource(backdrop: HazeState?): Modifier =
    if (backdrop == null) this else hazeSource(backdrop, zIndex = LIBRARY_BACKDROP_Z)

/** Categories under the A–Z list (or fading to it): no touches, no long presses, no semantics (no duplicate ids). */
private fun Modifier.libraryInert(inert: Boolean): Modifier = if (!inert) this else
    clearAndSetSemantics { }.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
    }


/** Geometry of one App Library platter. [edge] / [edgeEnd] are the grid's leading / trailing insets in its box. */
private data class FolderMetrics(val side: Dp, val gap: Dp, val edge: Dp, val padding: Dp, val large: Dp, val inner: Dp,
    val edgeEnd: Dp = edge, val rowGap: Dp = 10.dp, val labelSize: androidx.compose.ui.unit.TextUnit = 12.sp) {
    /** L: the width of the search field and of the two columns of platters. */
    val libraryWidth: Dp get() = side * 2 + gap
}

/**
 * iPhone Duo's App Library (r21, the owner's real screenshots 2.webp cover and 3.webp inner): everything scales with
 * the library's width L, the same on both screens. Platters 0.4715 L (gap 0.057 L), 7.3 % padding and 9.5 % between
 * the icons inside, a row every 0.591 L, the category name centred 0.0376 L under its platter. L spans the Home
 * grid's box less 3 % at the leading and 1 % at the trailing edge (cover 5.1–76.7 %W, inner 54.1–89.3 %W).
 */
private fun folderMetrics(width: Dp): FolderMetrics {
    val edge = width * .03f
    val edgeEnd = width * .01f
    val library = width - edge - edgeEnd
    val side = library * .4715f
    val gap = library - side * 2
    val padding = side * .073f
    val inner = side * .095f
    val rowGap = (library * (LIBRARY_ROW_PITCH - .4715f) - LIBRARY_LABEL_BLOCK.dp).coerceAtLeast(6.dp)
    // The category names are small on iPhone Duo (2.webp: CJK glyphs ≈ 0.033 L tall ≈ 10 dp).
    return FolderMetrics(side, gap, edge, padding, (side - padding * 2 - inner) / 2, inner, edgeEnd, rowGap, 11.sp)
}

/** iPhone Duo App Library rhythm, in units of the library width L (r21). */
private const val LIBRARY_ROW_PITCH = .591f
private const val LIBRARY_FIELD_TO_GRID = .0996f
/** The category name under a narrow platter: its gap plus one 15 sp line (centre 0.0376 L ≈ 11 dp below). */
private const val LIBRARY_LABEL_GAP_DP = 4f
private const val LIBRARY_LABEL_BLOCK = LIBRARY_LABEL_GAP_DP + 15f

/**
 * iPadOS platter (8.webp, measured on the 2000 px photo): 8 % padding, 8 % between the icons, so each of
 * the three large icons is 38 % of the platter and the 2×2 cluster fills the fourth quadrant.
 */
private fun wideFolderMetrics(frame: WideLibraryFrame): FolderMetrics {
    val side = frame.folderSide.dp
    val padding = side * .08f
    val inner = side * .08f
    return FolderMetrics(side, frame.columnGap.dp, frame.gridLeft.dp, padding, (side - padding * 2 - inner) / 2, inner)
}

/** Height (dp) of the category name under an iPad platter: its 8 dp gap plus one 15 sp line. */
private const val WIDE_LIBRARY_LABEL_BLOCK = 23f
/** Room for the Personal / Work segmented control under the iPad search field. */
internal const val WIDE_LIBRARY_WORK_BAND = 44f

/**
 * The App Library on the unfolded inner screen, laid out like iPadOS (user-photos-r5/8.webp idle,
 * 4.webp searching; see [wideLibraryFrame]): the field glides from the centre to the left of a "取消"
 * button and the platter grid gives way to one centred glass list with an alphabetical index.
 */
@Composable
private fun WideLibraryContent(
    frame: WideLibraryFrame, query: String, onQuery: (String) -> Unit, searching: Boolean,
    onFocused: () -> Unit, onCancel: () -> Unit,
    hasWork: Boolean, showWork: Boolean, onShowWork: (Boolean) -> Unit, selectedProfile: AppProfile?,
    onTurnOnWork: (Long) -> Unit, loading: Boolean, folders: List<LibraryFolder>?, appsById: Map<String, AppEntry>,
    profileApps: List<AppEntry>, recentIds: List<String>, drag: HomeDragState?, page: Int?,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    categoryOpen: Boolean, onOpenCategory: (LibraryFolder, Rect) -> Unit, onPullDown: () -> Unit,
    modifier: Modifier, tileBounds: MutableMap<String, Rect>? = null,
    onRevealHidden: (LibraryFolder, Rect) -> Unit = { _, _ -> },
    gridState: LazyGridState, searchProgress: () -> Float, listShown: Boolean,
    edgeBackdrop: HazeState?, focusPending: Boolean, onFocusHandled: () -> Unit,
) {
    Box(modifier.testTag("library-wide")) {
        val fieldLeft by animateDpAsState((if (searching) frame.searchFieldLeft else frame.fieldLeft).dp,
            LauncherMotion.size, label = "library field x")
        val fieldWidth by animateDpAsState((if (searching) frame.searchFieldWidth else frame.fieldWidth).dp,
            LauncherMotion.size, label = "library field width")
        val cancelAlpha by androidx.compose.animation.core.animateFloatAsState(if (searching) 1f else 0f,
            LauncherMotion.settle, label = "library cancel")
        val pausedProfile = selectedProfile?.takeIf { showWork && it.available == false }
        if (pausedProfile != null) Box(Modifier.fillMaxWidth().padding(top = frame.gridTop.dp)) {
            WorkPausedNotice(pausedProfile, onTurnOnWork, Color.White)
        } else {
            // Same transition as the iPhone library: the platters recede and fade, the glass list fades in.
            val categories = Modifier.fillMaxSize().libraryEdgeSource(edgeBackdrop)
                .graphicsLayer { libraryCategoriesRecede(searchProgress()) }.libraryInert(searching)
            when {
                folders == null || (folders.isEmpty() && loading) -> Box(categories, contentAlignment = Alignment.Center) {
                    IosActivityIndicator(color = Color.White)
                }
                folders.isEmpty() -> Text(launcherText("No apps found"), categories.padding(top = frame.gridTop.dp),
                    color = Color.White, textAlign = TextAlign.Center, style = LibraryLabelShadow)
                // The grid fills the screen and scrolls up under the field (iOS 26); its first row rests at gridTop.
                else -> LibraryFolderGrid(folders, appsById, drag.takeIf { !categoryOpen && !searching }, page, onLaunchFrom, onActions,
                    onOpenCategory = onOpenCategory, onPullDown = onPullDown,
                    modifier = categories, wide = frame, tileBounds = tileBounds,
                    onRevealHidden = onRevealHidden, gridState = gridState, contentTop = frame.gridTop.dp,
                    )
            }
            if (listShown) LibraryAlphabetList(profileApps, query, recentIds, drag, page, onLaunchFrom, onActions,
                Modifier.padding(start = frame.listLeft.dp, top = frame.listTop.dp, bottom = 20.dp).imePadding()
                    .width(frame.listWidth.dp).fillMaxHeight().graphicsLayer { libraryListReveal(searchProgress()) }.libraryInert(!searching), wide = true)
        }
        LibrarySearchField(query, onQuery, Modifier.offset(x = fieldLeft, y = frame.fieldTop.dp).width(fieldWidth),
            onFocused = onFocused, tag = "library-search", height = frame.fieldHeight.dp, centered = !searching,
            focusPending = focusPending, onFocusHandled = onFocusHandled)
        if (cancelAlpha > .01f) Box(Modifier.offset(x = frame.cancelLeft.dp, y = frame.fieldTop.dp)
            .size(frame.cancelWidth.dp, frame.fieldHeight.dp).graphicsLayer { alpha = cancelAlpha }
            .clickable(remember { MutableInteractionSource() }, null, enabled = searching, role = Role.Button) { onCancel() }
            .testTag("library-cancel"), contentAlignment = Alignment.Center) {
            Text(uxText("取消", "Cancel"), color = Color.White, fontSize = 16.sp, maxLines = 1, style = LibraryLabelShadow)
        }
        if (hasWork) IosSegmentedControl(listOf(launcherText("Personal"), launcherText("Work")),
            if (showWork) 1 else 0, { onShowWork(it == 1) },
            Modifier.offset(x = fieldLeft, y = (frame.fieldTop + frame.fieldHeight + 8f).dp).width(fieldWidth),
            tags = listOf("library-personal", "library-work"))
    }
}

@Composable
private fun LibraryFolderGrid(
    folders: List<LibraryFolder>, appsById: Map<String, AppEntry>, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    onOpenCategory: (LibraryFolder, Rect) -> Unit, onPullDown: () -> Unit, modifier: Modifier,
    wide: WideLibraryFrame? = null, tileBounds: MutableMap<String, Rect>? = null,
    onRevealHidden: (LibraryFolder, Rect) -> Unit = { _, _ -> },
    gridState: LazyGridState = rememberLazyGridState(),
    /** Where the first row rests; the categories scroll up from here under the header (iOS 26). */
    contentTop: Dp = 0.dp,
) {
    val density = LocalDensity.current
    val pullThreshold = with(density) { 72.dp.toPx() }
    val pull = remember(gridState) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Overscroll at the top (iOS: pull the App Library down) reveals the A–Z list.
                if (source == NestedScrollSource.UserInput && available.y > 0f && gridState.firstVisibleItemIndex == 0 &&
                    gridState.firstVisibleItemScrollOffset == 0) {
                    distance += available.y
                    if (distance > pullThreshold) { distance = 0f; onPullDown() }
                } else if (available.y < 0f || consumed.y != 0f) distance = 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                distance = 0f; return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    BoxWithConstraints(modifier.testTag("all-apps-list")) {
        val metrics = if (wide != null) wideFolderMetrics(wide) else folderMetrics(maxWidth)
        val rowGap = if (wide != null) (wide.rowPitch - wide.folderSide - WIDE_LIBRARY_LABEL_BLOCK).coerceAtLeast(8f).dp else metrics.rowGap
        // Round 13 (owner: no blur or fade at the top or bottom): rows pass sharp under the floating field (which
        // refracts them) and leave through the screen's own edges.
        LazyVerticalGrid(GridCells.Fixed(wide?.columns ?: 2), Modifier.fillMaxSize()
            .nestedScroll(pull), state = gridState,
            contentPadding = PaddingValues(start = metrics.edge, end = metrics.edgeEnd, top = contentTop, bottom = LIBRARY_GRID_BOTTOM_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(metrics.gap),
            verticalArrangement = Arrangement.spacedBy(rowGap)) {
            items(folders, key = { it.category.name }) { folder ->
                val hiddenFolder = folder.category == LibraryCategory.HIDDEN
                LibraryFolderTile(folder, appsById, metrics,
                    // Hidden apps never leave their folder by a drag: "添加到主屏幕" unhides them first (iOS).
                    drag.takeIf { !folder.category.smart && !hiddenFolder }, page, onLaunch, onActions,
                    if (hiddenFolder && !AppPrivacy.revealed) onRevealHidden else onOpenCategory,
                    labelGap = if (wide != null) 8.dp else LIBRARY_LABEL_GAP_DP.dp, tileBounds = tileBounds,
                    locked = hiddenFolder && (!AppPrivacy.revealed || folder.appIds.isEmpty()))
            }
        }
    }
}

@Composable
private fun LibraryFolderTile(
    folder: LibraryFolder, appsById: Map<String, AppEntry>, metrics: FolderMetrics, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    onOpenCategory: (LibraryFolder, Rect) -> Unit,
    labelGap: Dp = 6.dp, tileBounds: MutableMap<String, Rect>? = null,
    /** "已隐藏" before Face ID (here: the owner's fingerprint or PIN): blank frosted icon shapes, the whole tile a button. */
    locked: Boolean = false,
) {
    val shape = RoundedCornerShape(metrics.side * .2f)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Column(Modifier.testTag("library-folder-${folder.category.name.lowercase()}"), horizontalAlignment = Alignment.CenterHorizontally) {
        // The expanded category zooms out of (and back into) this platter: its bounds stay current while
        // the grid scrolls or the page slides.
        Box(Modifier.size(metrics.side).onGloballyPositioned {
            bounds = it.boundsInRoot(); tileBounds?.put(folder.category.name, bounds)
        }
            // The Home folder icon's own glass (r21, owner: the platters must match the Dock and the folders on Home,
            // not a flat grey): the blurred wallpaper shows through, lensed at the bevel, under the bright rim.
            .duoGlass(DuoGlassRole.Folder, shape)
            .then(if (locked) Modifier.clip(shape)
                .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onOpenCategory(folder, bounds) }
                .semantics { contentDescription = uxText("显示已隐藏的 App", "Show Hidden Apps") }
                .testTag("library-hidden-locked") else Modifier)
            .padding(metrics.padding)) {
            if (locked) { LockedHiddenArt(metrics); return@Box }
            val large = folder.largeIds.mapNotNull(appsById::get)
            large.forEachIndexed { index, app ->
                Box(Modifier.offset(x = (metrics.large + metrics.inner) * (index % 2), y = (metrics.large + metrics.inner) * (index / 2))) {
                    LibraryIcon(app, metrics.large, drag, page, onLaunch, onActions)
                }
            }
            val cluster = folder.clusterIds.mapNotNull(appsById::get)
            if (cluster.isNotEmpty()) {
                val tiny = (metrics.large - metrics.inner * .6f) / 2
                val clusterLabel = uxText("打开“${folder.category.zh}”", "Open ${folder.category.en}")
                Box(Modifier.offset(x = metrics.large + metrics.inner, y = metrics.large + metrics.inner).size(metrics.large)
                    .clip(RoundedCornerShape(metrics.large * .22f))
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onOpenCategory(folder, bounds) }
                    .semantics { contentDescription = clusterLabel }
                    .testTag("library-cluster-${folder.category.name.lowercase()}")) {
                    cluster.forEachIndexed { index, app ->
                        AppIcon(app, tiny, Modifier.offset(x = (tiny + metrics.inner * .6f) * (index % 2),
                            y = (tiny + metrics.inner * .6f) * (index / 2)))
                    }
                }
            }
        }
        Text(folder.category.title, Modifier.width(metrics.side).padding(top = labelGap)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onOpenCategory(folder, bounds) },
            color = Color.White, fontSize = metrics.labelSize, lineHeight = 15.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = LibraryLabelShadow)
    }
}

/**
 * The locked "已隐藏" tile (iOS 26, TidBITS 2026-06): three blank icon shapes and a 2×2 cluster of blank minis
 * in the platter's own frosted tone — no app, no count gives anything away.
 */
@Composable
private fun LockedHiddenArt(metrics: FolderMetrics) {
    val blank = Color.White.copy(alpha = if (LocalDuoPalette.current.dark) .14f else .26f)
    val tiny = (metrics.large - metrics.inner * .6f) / 2
    for (index in 0 until 3) Box(Modifier.offset(x = (metrics.large + metrics.inner) * (index % 2),
        y = (metrics.large + metrics.inner) * (index / 2)).size(metrics.large)
        .background(blank, homeIconShape(metrics.large.value)))
    for (index in 0 until 4) Box(Modifier.offset(
        x = metrics.large + metrics.inner + (tiny + metrics.inner * .6f) * (index % 2),
        y = metrics.large + metrics.inner + (tiny + metrics.inner * .6f) * (index / 2)).size(tiny)
        .background(blank, homeIconShape(tiny.value)))
}

/** A launchable library icon. With [drag], the root long-press lifts it toward Home exactly like before. */
@Composable
internal fun LibraryIcon(
    app: AppEntry, size: Dp, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val launchBounds = remember { android.graphics.Rect() }
    val interaction = remember { MutableInteractionSource() }
    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
    val click = { if (app.available) onLaunch(app, launchBounds) }
    Box(modifier.size(size).then(dragModifier).testTag("library-app-${app.id}")
        .then(if (drag == null) Modifier.combinedClickable(interaction, null, role = Role.Button, onClick = click,
            onLongClick = { onActions(app) })
        else Modifier.clickable(interaction, null, role = Role.Button, onClick = click)
            .semantics { onLongClick(launcherFormat(R.string.app_options)) { onActions(app); true } })
        .semantics { contentDescription = app.label }
        .iosIconHighlight(interaction)) {
        AppIcon(app, size, Modifier.testTag("library-icon-${app.id}").homeIconTarget(app.id)
            .onGloballyPositioned { launchBounds.set(it.boundsInWindow().toAndroidBounds()) })
    }
}

/** Searching (or pulling down) shows every app alphabetically with an A–Z index, like iOS. */
@Composable
private fun LibraryAlphabetList(
    apps: List<AppEntry>, query: String, recentIds: List<String>, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit, modifier: Modifier,
    /** iPadOS list (4.webp): no section headers, hairline separators, a white index. */
    wide: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val trimmed = query.trim()
    val sections = remember(apps) { alphabeticalSections(apps) { SearchKeys.of(it.label) } }
    val ranked = remember(apps, trimmed, recentIds) {
        if (trimmed.isEmpty()) emptyList() else {
            val rank = recentIds.withIndex().associate { it.value to it.index }
            rankMatches(trimmed, apps, { SearchKeys.of(it.label) }, { rank[it.id] }).map { it.item }
        }
    }
    LaunchedEffect(trimmed) { listState.scrollToItem(0) }
    val ink = duoGlassContentColor(DuoGlassRole.Elevated)
    val shape = RoundedCornerShape(if (wide) 22.dp else 26.dp)
    Box(modifier.clip(shape).duoGlass(DuoGlassRole.Elevated, shape).testTag("library-list")) {
        LazyColumn(Modifier.fillMaxSize().padding(end = if (trimmed.isEmpty()) (if (wide) 26.dp else 18.dp) else 0.dp), state = listState,
            contentPadding = PaddingValues(vertical = 6.dp)) {
            if (trimmed.isNotEmpty()) {
                if (ranked.isEmpty()) item("none") {
                    Text(launcherText("No apps found"), Modifier.fillMaxWidth().padding(24.dp), color = ink.copy(alpha = .6f),
                        textAlign = TextAlign.Center)
                }
                itemsIndexed(ranked, key = { _, app -> app.id }) { index, app ->
                    LibraryListRow(app, ink, drag, page, onLaunch, onActions, wide, separator = wide && index < ranked.lastIndex)
                }
            } else if (wide) {
                val all = sections.flatMap { it.second }
                itemsIndexed(all, key = { _, app -> app.id }) { index, app ->
                    LibraryListRow(app, ink, drag, page, onLaunch, onActions, wide = true, separator = index < all.lastIndex)
                }
            } else sections.forEach { (letter, members) ->
                stickyHeader(key = "header-$letter") {
                    Text(letter.toString(), Modifier.fillMaxWidth().background(ink.copy(alpha = .06f))
                        .padding(horizontal = 16.dp, vertical = 3.dp).semantics { heading() }.testTag("library-section-$letter"),
                        color = ink.copy(alpha = .62f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                items(members, key = { it.id }) { app -> LibraryListRow(app, ink, drag, page, onLaunch, onActions) }
            }
        }
        if (trimmed.isEmpty() && sections.size > 1) {
            // Without headers (iPad) a letter jumps to its first app; with them, to its header.
            val headerIndex = remember(sections, wide) {
                var position = 0
                sections.associate { (letter, members) -> letter to position.also { position += members.size + if (wide) 0 else 1 } }
            }
            SectionIndex(sections.map { it.first }, if (wide) ink.copy(alpha = .9f) else IosUi.blue,
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = if (wide) 6.dp else 0.dp).width(18.dp)) { letter ->
                headerIndex[letter]?.let { scope.launch { listState.scrollToItem(it) } }
            }
        }
    }
}

@Composable
private fun LibraryListRow(
    app: AppEntry, ink: Color, drag: HomeDragState?, page: Int?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit, onActions: (AppEntry) -> Unit,
    wide: Boolean = false, separator: Boolean = false,
) {
    val bounds = remember { android.graphics.Rect() }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragModifier = if (drag != null) Modifier.dropRegion(drag, DropTarget.Library(app.id), app.id, page) else Modifier
    val click = { if (app.available) onLaunch(app, bounds) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(dragModifier)
        .background(if (pressed) ink.copy(alpha = .08f) else Color.Transparent)
        .then(if (drag == null) Modifier.combinedClickable(interaction, null, role = Role.Button, onClick = click,
            onLongClick = { onActions(app) })
        else Modifier.clickable(interaction, null, role = Role.Button, onClick = click)
            .semantics { onLongClick(launcherFormat(R.string.app_options)) { onActions(app); true } })
        .testTag("library-app-${app.id}")
        // iPad (4.webp): a hairline under each row, inset to the name, like a grouped list.
        .then(if (separator) Modifier.drawBehind {
            val x = (LIBRARY_WIDE_ROW_INSET + LIBRARY_WIDE_ROW_ICON + LIBRARY_WIDE_ROW_INSET).dp.toPx()
            drawLine(ink.copy(alpha = .16f), Offset(x, size.height - .5f), Offset(size.width, size.height - .5f), 1f)
        } else Modifier)
        .padding(horizontal = if (wide) LIBRARY_WIDE_ROW_INSET.dp else 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, if (wide) LIBRARY_WIDE_ROW_ICON.dp else 38.dp, Modifier.testTag("library-icon-${app.id}").homeIconTarget(app.id)
            .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) })
        Spacer(Modifier.width(if (wide) LIBRARY_WIDE_ROW_INSET.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = ink.copy(alpha = if (app.available) 1f else .45f), fontSize = 17.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (!app.available) Text(launcherFormat(R.string.ui_unavailable), color = ink.copy(alpha = .5f), fontSize = 12.sp)
        }
    }
}

/** iPad list row (4.webp, 860 dp panel): 11 dp inset, 34 dp icon, the name 56 dp from the panel edge. */
private const val LIBRARY_WIDE_ROW_INSET = 11f
private const val LIBRARY_WIDE_ROW_ICON = 34f

/** The narrow A–Z strip on the right edge; tap or slide along it to jump. Too short for every letter, it
 * shows iOS's shortened "A • F • K …" form ([compactSectionIndex]). */
@Composable
private fun SectionIndex(letters: List<Char>, ink: Color, modifier: Modifier, onLetter: (Char) -> Unit) {
    var height by remember { mutableStateOf(1f) }
    val slotPx = with(LocalDensity.current) { 13.dp.toPx() }
    val shown = remember(letters, height, slotPx) { compactSectionIndex(letters, (height / slotPx).toInt().coerceAtLeast(3)) }
    val current by rememberUpdatedState(onLetter)
    fun pick(y: Float) {
        val index = ((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)
        current(letters[index])
    }
    Column(modifier.onGloballyPositioned { height = it.size.height.toFloat().coerceAtLeast(1f) }
        .pointerInput(letters) { detectTapGestures { pick(it.y) } }
        .pointerInput(letters) { detectVerticalDragGestures { change, _ -> change.consume(); pick(change.position.y) } }
        .testTag("library-index"),
        verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
        shown.forEach { letter ->
            Text(letter?.toString() ?: "•", color = ink, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WorkPausedNotice(profile: AppProfile, onTurnOnWork: (Long) -> Unit, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(launcherFormat(if (profile.quiet) R.string.ui_profile_paused else R.string.ui_profile_unavailable, launcherText("Work")),
            color = color, textAlign = TextAlign.Center, style = LibraryLabelShadow)
        if (profile.quiet) TodayGlassCapsule(launcherText("Turn on work apps"), "turn-on-work",
            { onTurnOnWork(profile.userSerial) }, Modifier.padding(top = 12.dp))
    }
}

/** iOS segmented control: a capsule track with a sliding white thumb. */
@Composable
internal fun IosSegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    tags: List<String> = emptyList(), dark: Boolean = iosSheetDark()) {
    val track = if (dark) Color.White.copy(alpha = .14f) else Color(0xFF767680).copy(alpha = .16f)
    val thumb = if (dark) Color(0xFF636366) else Color.White
    val ink = if (dark) Color.White else Color.Black
    BoxWithConstraints(modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(track).padding(2.dp)) {
        val segment = maxWidth / options.size.coerceAtLeast(1)
        val offset by animateDpAsState(segment * selected, LauncherMotion.size, label = "segment thumb")
        Box(Modifier.offset(x = offset).width(segment).fillMaxHeight()
            .background(thumb, RoundedCornerShape(7.dp)).border(.5.dp, Color.Black.copy(alpha = .04f), RoundedCornerShape(7.dp)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).fillMaxHeight()
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = index == selected }
                    .then(tags.getOrNull(index)?.let { Modifier.testTag(it) } ?: Modifier),
                    contentAlignment = Alignment.Center) {
                    Text(label, color = ink, fontSize = 13.sp,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/** "Add to Home" sheet: an inset-grouped list; the trailing circle pins or unpins. */
@Composable
private fun PinPickerList(
    state: LauncherState, apps: List<AppEntry>, query: String, onQuery: (String) -> Unit,
    onPin: (String, Boolean) -> Unit, onActions: (AppEntry) -> Unit,
    hasWork: Boolean, showWork: Boolean, onShowWork: (Boolean) -> Unit,
    selectedProfile: AppProfile?, onTurnOnWork: (Long) -> Unit, modifier: Modifier,
) {
    val pinned = remember(state.homeSlots, state.leadingSlots) {
        (state.homeSlots.asSequence() + state.leadingSlots.asSequence()).filterNotNull().toSet()
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val trimmed = query.trim()
    val visible = remember(apps, trimmed) {
        if (trimmed.isEmpty()) alphabeticalSections(apps) { SearchKeys.of(it.label) }.flatMap { it.second }
        else rankMatches(trimmed, apps, { SearchKeys.of(it.label) }).map { it.item }
    }
    Column(modifier.padding(horizontal = 16.dp)) {
        Text(launcherText("Add to Home"), Modifier.padding(bottom = 10.dp).semantics { heading() }, color = ink,
            fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val searchLabel = launcherText("Search apps")
        BasicTextField(query, onQuery, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 17.sp),
            cursorBrush = SolidColor(IosUi.blue),
            modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp))
                .background(if (iosSheetDark()) Color(0xFF767680).copy(alpha = .24f) else Color(0xFF767680).copy(alpha = .12f))
                .testTag("pin-search").semantics { contentDescription = searchLabel },
            decorationBox = { inner ->
                Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(IosSymbols.magnifyingGlass, null, Modifier.size(17.dp), tint = iosSecondaryText())
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(searchLabel, color = iosSecondaryText(), fontSize = 17.sp, maxLines = 1)
                        inner()
                    }
                }
            })
        if (hasWork) IosSegmentedControl(listOf(launcherText("Personal"), launcherText("Work")), if (showWork) 1 else 0,
            { onShowWork(it == 1) }, Modifier.fillMaxWidth().padding(top = 10.dp))
        Spacer(Modifier.height(10.dp))
        if (showWork && selectedProfile?.available == false) {
            WorkPausedNotice(selectedProfile, onTurnOnWork, ink)
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(IosUi.groupRadius)).background(iosGroupFill())
            .testTag("all-apps-list"), contentPadding = PaddingValues(bottom = 8.dp)) {
            if (visible.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) IosActivityIndicator(color = iosSecondaryText())
                    else Text(launcherText("No apps found"), color = iosSecondaryText())
                }
            }
            items(visible, key = { it.id }) { app ->
                val isPinned = app.id in pinned
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("library-app-${app.id}")
                    .combinedClickable(remember { MutableInteractionSource() }, null, role = Role.Button,
                        onClick = { onPin(app.id, !isPinned) }, onLongClick = { onActions(app) })
                    .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(app.label, Modifier.weight(1f), color = ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val label = launcherFormat(if (isPinned) R.string.unpin_app else R.string.pin_app, app.label)
                    Box(Modifier.size(28.dp).clip(CircleShape)
                        .background(if (isPinned) IosUi.green else IosUi.blue)
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Button) { onPin(app.id, !isPinned) }
                        .semantics { contentDescription = label }.testTag("pin-${app.id}"),
                        contentAlignment = Alignment.Center) {
                        Icon(if (isPinned) IosSymbols.checkmark else IosSymbols.plus, null, Modifier.size(16.dp), tint = Color.White)
                    }
                }
                Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(.5.dp).background(iosSeparator()))
            }
        }
    }
}
