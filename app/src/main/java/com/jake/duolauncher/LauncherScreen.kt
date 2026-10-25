@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.UserManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

internal val Ink: Color
    @Composable get() = LocalDuoPalette.current.ink
internal val Glass: Color
    @Composable get() = LocalDuoPalette.current.glass

private data class WindowCutoutSnapshot(
    val statusColumnCutoutDepthPx: Int = 0,
    val bounds: List<IntRect> = emptyList(),
    val safeTopPx: Int = 0,
    val safeBottomPx: Int = 0,
    val safeSidePx: Int = 0,
)

private fun readWindowCutout(context: android.content.Context, view: android.view.View): WindowCutoutSnapshot {
    val rootInsets = ViewCompat.getRootWindowInsets(view)
    val compatCutout = rootInsets?.displayCutout
    // HyperOS can deliver consumed or stale cutout data on the decor view even
    // though rootInsets exists. Metrics belongs to the current window/display.
    val platformCutout = context.getSystemService(WindowManager::class.java)
        ?.currentWindowMetrics?.windowInsets?.displayCutout
    val bounds = (platformCutout?.boundingRects ?: compatCutout?.boundingRects.orEmpty())
        .map { IntRect(it.left, it.top, it.right, it.bottom) }
    val windowWidthPx = platformCutout?.let {
        context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.width()
    }?.takeIf { it > 0 } ?: view.width
    // Only a cutout entering the right-most quarter can obstruct the status column.
    // A centred cover-screen camera therefore leaves the status high, while a top-right
    // inner-screen camera moves it down. This also adapts to Samsung without model constants.
    val statusDepthPx = bounds.filter { windowWidthPx > 0 && it.right > windowWidthPx * .75f }
        .maxOfOrNull { it.bottom.coerceAtLeast(it.height) } ?: 0
    val compatInsets = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    return WindowCutoutSnapshot(statusColumnCutoutDepthPx = statusDepthPx, bounds = bounds,
        safeTopPx = compatInsets?.top ?: 0, safeBottomPx = compatInsets?.bottom ?: 0,
        safeSidePx = maxOf(platformCutout?.safeInsetLeft ?: compatCutout?.safeInsetLeft ?: 0,
            platformCutout?.safeInsetRight ?: compatCutout?.safeInsetRight ?: 0))
}

@Composable
private fun rememberWindowCutout(context: android.content.Context, view: android.view.View): WindowCutoutSnapshot {
    var snapshot by remember(context, view) { mutableStateOf(readWindowCutout(context, view)) }
    DisposableEffect(context, view) {
        // Reverse landscape has the same window dimensions. Insets arrive after
        // configuration callbacks, so observe the committed view before drawing.
        // Round 22: readWindowCutout asks WindowManager (two binder calls) — it ran before every frame and
        // was ~5 % of Home's main thread on the phone. Re-read only when the committed window changed
        // (new insets dispatch, size or rotation), and once more a little later for late HyperOS metrics.
        val observer = view.viewTreeObserver
        var lastInsets: Any? = null
        var lastKey = Long.MIN_VALUE
        val reread = Runnable {
            val current = readWindowCutout(context, view)
            if (current != snapshot) snapshot = current
        }
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            val insets = view.rootWindowInsets
            val key = (view.width.toLong() shl 32) or (view.height.toLong() shl 4) or (view.display?.rotation ?: 0).toLong()
            if (insets !== lastInsets || key != lastKey) {
                lastInsets = insets; lastKey = key
                reread.run()
                view.removeCallbacks(reread)
                view.postDelayed(reread, 300L)
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        onDispose {
            view.removeCallbacks(reread)
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
            else view.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }
    return snapshot
}

private fun findFreeWidgetIndex(layout: HomeLayout, page: Int, spanX: Int, spanY: Int): Int? {
    val blocked = layout.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    for (row in 0..widgetRowsForPage(page) - spanY) for (column in 0..GRID_COLUMNS - spanX) {
        val cells = buildList {
            repeat(spanY) { y -> repeat(spanX) { x -> add(homeCellIndex(page, (row + y) * GRID_COLUMNS + column + x)) } }
        }
        if (cells.none { it in blocked || layout.slotAt(it) != null }) return cells.first()
    }
    return null
}

@Composable
fun DuoTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val palette = if (dark) DarkDuoPalette else LightDuoPalette
    CompositionLocalProvider(LocalDuoPalette provides palette) {
        val colors = if (dark) darkColorScheme(
            primary = Color(0xFF78AAFF), onPrimary = Color(0xFF10213D),
            background = Color(0xFF111113), surface = Color(0xFF1C1C1E),
            surfaceContainer = Color(0xFF242426), surfaceVariant = Color(0xFF2C2C2E),
            onSurface = palette.ink, onSurfaceVariant = Color(0xFFB3B3BB),
            outline = Color(0xFF797981), outlineVariant = Color(0xFF38383A),
            secondary = Color(0xFFB9C8E0), secondaryContainer = Color(0xFF263349),
            onSecondaryContainer = palette.ink,
        ) else lightColorScheme(
            primary = Color(0xFF007AFF), onPrimary = Color.White,
            background = Color(0xFFF2F2F7), surface = Color.White,
            surfaceContainer = Color(0xFFF2F2F7), surfaceVariant = Color(0xFFF2F2F7),
            onSurface = palette.ink, onSurfaceVariant = Color(0xFF66666F),
            outline = Color(0xFF8E8E93), outlineVariant = Color(0xFFE5E5EA),
            secondary = Color(0xFF52627A), secondaryContainer = Color(0xFFEAF1FF),
            onSecondaryContainer = palette.ink,
        )
        MaterialTheme(colorScheme = colors, typography = LauncherTypography) {
            // iOS never ripples: every default clickable dims like a UIButton instead.
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides IosDimIndication, content = content)
        }
    }
}


@Composable
fun LauncherScreen(
    state: LauncherState, model: LauncherModel, widgets: WidgetController, homeRequests: Int,
    onLaunch: (AppEntry) -> Unit, onMakeDefault: () -> Unit, onAppInfo: (AppEntry) -> Unit,
    isDefaultHome: Boolean, deviceStatus: DeviceStatus, onStatusMode: (Boolean) -> Unit, onWallpaperPreview: () -> Unit,
    searchRequests: Int = 0,
    settingsRequests: Int = 0,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = { app, _ -> onLaunch(app) },
    showFirstRun: Boolean = false,
    onFinishFirstRun: () -> Unit = {},
    onShadeSetup: () -> Unit = {},
) {
    var sheet by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settingsRequests) { if (settingsRequests > 0) sheet = "settings" }
    var dockSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetSlot by rememberSaveable { mutableIntStateOf(0) }
    var widgetTargetIndex by rememberSaveable { mutableIntStateOf(Int.MIN_VALUE) }
    var widgetExactTarget by rememberSaveable { mutableStateOf(false) }
    var widgetPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var widgetProfileSerial by rememberSaveable { mutableStateOf<Long?>(null) }
    var widgetSession by remember { mutableStateOf<WidgetPickerSession?>(null) }
    var widgetPlacementMessage by remember { mutableStateOf<String?>(null) }
    var emptyCellIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var desktopEditing by rememberSaveable { mutableStateOf(false) }
    var emptyMenuVisible by rememberSaveable { mutableStateOf(false) }
    // iOS Edit ▸ 自定义: a card at the bottom while Home keeps jiggling (not a settings sheet).
    var emptyMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    var resizeGridSizing by remember { mutableStateOf<WidgetGridSizing?>(null) }
    var ghostIconSizeDp by remember { mutableFloatStateOf(66f) }
    var resizeSlot by remember { mutableStateOf<Int?>(null) }
    var resizeWidth by rememberSaveable { mutableIntStateOf(1) }
    var resizeHeight by rememberSaveable { mutableIntStateOf(1) }
    var resizeConstraints by remember { mutableStateOf<WidgetSpanConstraints?>(null) }
    var resizePitchX by remember { mutableFloatStateOf(1f) }
    var resizePitchY by remember { mutableFloatStateOf(1f) }
    var resizeTopPitch by remember { mutableFloatStateOf(1f) }
    var resizeAppPitch by remember { mutableFloatStateOf(1f) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var appMoveMenu by rememberSaveable { mutableStateOf(false) }
    var appMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    var customizationPage by rememberSaveable { mutableStateOf(CustomizationPage.OVERVIEW) }
    LaunchedEffect(selectedId) { if (selectedId == null) appMoveMenu = false }
    LaunchedEffect(sheet) { if (sheet.isEmpty()) customizationPage = CustomizationPage.OVERVIEW }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameFolderOnOpen by rememberSaveable { mutableStateOf(false) }
    var folderMenuAppId by remember { mutableStateOf<String?>(null) }
    val iconRegistry = remember { HomeIconRegistry() }
    val homePageScrolls = remember { HashMap<Int, ScrollState>() }
    // 0 → 1 presentation of item menus and folders; Home blurs behind them in the draw phase.
    val appMenuBlur = remember { mutableFloatStateOf(0f) }
    val widgetMenuBlur = remember { mutableFloatStateOf(0f) }
    val folderProgress = remember { mutableFloatStateOf(0f) }
    var removeAlert by remember { mutableStateOf<IosAlertRequest?>(null) }
    var widgetMenuSnapshot by remember { mutableStateOf<ImageBitmap?>(null) }
    // Drag hover: the reflow target commits after a short rest; resting on another app's
    // icon instead offers a folder (iOS), shown by that icon's growing platter.
    var committedInsertion by remember { mutableStateOf<DropTarget?>(null) }
    var folderHoverId by remember { mutableStateOf<String?>(null) }
    var settleSerial by remember { mutableIntStateOf(0) }
    // The page in view as a posture-independent anchor (cover page / right pane of a spread,
    // see PagerPosture): Home 1 = 0. Only restores a recreated window; a live posture change
    // hands the page over through [pagerHandoff].
    var savedAnchorPage by rememberSaveable(key = "duo.page.anchor.v4") { mutableIntStateOf(0) }
    var lastHomePage by rememberSaveable { mutableIntStateOf(0) }
    // App Library search (query + list open), kept across a launch-and-return (keepsLibrarySearchOnHome).
    val librarySearch = rememberLibrarySearchSession()
    var pinQuery by rememberSaveable { mutableStateOf("") }
    val launcherActivity = androidx.activity.compose.LocalActivity.current as MainActivity
    // Spotlight (Search button, swipe down on Home, "duo_destination=search").
    var spotlightOpen by rememberSaveable { mutableStateOf(false) }
    // iOS keeps Search when you come back from an app opened from it: the query survives the launch
    // (the overlay itself hides at once) and Search reopens, keyboard down, when Home resumes.
    var spotlightQuery by rememberSaveable { mutableStateOf("") }
    var spotlightReturn by rememberSaveable { mutableStateOf(false) }
    var spotlightRestored by remember { mutableStateOf(false) }
    val spotlightHistory = remember(launcherActivity) { SpotlightHistory(launcherActivity) }
    // One 0→1 value for Spotlight that the pull-down drives and Home blurs/recedes with (r6-motion).
    val spotlightPresence = rememberSpotlightPresence()
    // Where Home was when it opened an app (HomeReturn.kt): survives process death between launch and return.
    var launchOrigin by rememberSaveable(stateSaver = LaunchOriginSaver) { mutableStateOf<LaunchOrigin?>(null) }
    /** An Android widget was just tapped (see providerWidgetTap): consumed by the next pause. */
    var pendingProviderTap by remember { mutableStateOf<PendingProviderTap?>(null) }
    var resumeSerial by remember { mutableIntStateOf(0) }
    val launcherRootView = LocalView.current.rootView
    val drag = remember { HomeDragState() }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(emptyCellIndex) {
        emptyCellIndex?.let { index ->
            // Outside edit mode a stabilised hold on blank space enters jiggle mode; inside it,
            // the same hold opens the compact page menu beside the pressed cell.
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!desktopEditing) desktopEditing = true
            else { emptyMenuAnchor = drag.regions[DropTarget.Home(index)]?.bounds; emptyMenuVisible = true }
            emptyCellIndex = null
        }
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val windowDensity = LocalDensity.current
    // iOS edit mode keeps the desktop at full size; resize maths used to follow the old shrunken editor card.
    val editorScale = 1f
    val reduceMotion = rememberReduceMotion()
    val editIntensity = animateFloatAsState(if (desktopEditing) 1f else 0f,
        if (reduceMotion) snap() else LauncherMotion.editPresence,
        label = "jiggle presence")
    val editSettling by remember { derivedStateOf { editIntensity.value > .002f } }
    val editDecorated = desktopEditing || editSettling
    val jiggleClock = rememberJiggleClock(editDecorated, reduceMotion)
    val composeView = LocalView.current
    // The leading component page is a pager page on the cover and the left pane of
    // the first unfolded spread, so only the cover needs a physical page before Home.
    val windowCutout = rememberWindowCutout(launcherActivity, launcherRootView)
    val appsById = remember(state.apps) { state.apps.associateBy { it.id } }
    LaunchedEffect(drag.moved) {
        // Dragging out of a long press dismisses the menu and, as on iOS, starts editing the Home Screen.
        if (drag.moved) {
            selectedId = null
            if (sheet == "widgetActions") sheet = ""
            if (drag.source?.folderId != null) { folderMenuAppId = null; openFolderId = null }
            if (drag.source?.folderId == null) desktopEditing = true
        }
    }
    val glassBackdrop = rememberHazeState()
    val liveDesktopBackdrop = rememberHazeState()
    val dockBackdrop = rememberHazeState()
    SideEffect {
        // Never flatten soft-light glass to Haze's fallback tint during motion.
        glassBackdrop.blurEnabled = duoGlassBackdropEnabled()
    }
    val homePages = state.homePages
    val pendingNewPage = widgets.pendingPlacement?.page == homePages
    /** The temporary drop page outlives the drag while the pager slides off it (see slideAfterDrag). */
    var holdDropPage by remember { mutableStateOf(false) }
    val visibleHomePages = homePages + if (drag.active || widgetSession != null || pendingNewPage || holdDropPage) 1 else 0
    var expandedWorkspace by remember { mutableStateOf(false) }
    val leadingHasContent = state.leadingSlots.any { it != null } || state.widgetPlacements.any { it.page == -1 }
    val leadingVisible = launcherActivity.corePreferences.widgetPageVisible(leadingHasContent)
    val pagerPosture = PagerPosture(
        spread = launcherActivity.panelOrientation.panel.value != FoldPanel.COVER &&
            windowSize.width > windowSize.height && with(windowDensity) { windowSize.width.toDp().value } >= 650f,
        leading = leadingVisible,
    )
    val firstHome = pagerPosture.firstHome
    val pageCount = visibleHomePages + 1
    // iPhone Duo (r21): Today View and the App Library are panes of the strip; the library blurs what it leaves (SpreadEnds).
    val spreadEnds = SpreadEnds.of(pagerPosture.spread, pagerPosture.leading, pageCount + firstHome)
    // One pager per posture, created on the page in view (iPhone Duo: the cover page is the spread's
    // right half) in the same composition that switches the layout — never remapped after a frame.
    // Not saveable per posture: a stale restored page could resurface on a later posture change;
    // a recreated window starts from [savedAnchorPage] instead.
    val pagerHandoff = remember { PagerPostureHandoff() }
    val workspacePages = rememberUpdatedState(pageCount)
    val nativePager = remember(pagerPosture) {
        // Each pager counts its own posture's pages, so the outgoing one still reports its true
        // target (e.g. the App Library) when the incoming one reads it below.
        val postureFirstHome = pagerPosture.firstHome
        val (entryPage, entryAnchor) = androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation {
            val anchor = pagerHandoff.anchor() ?: savedAnchorPage
            pagerHandoff.entryPage(pagerPosture, anchor, workspacePages.value + postureFirstHome,
                spreadEnds.todayOverlay) to anchor
        }
        PagerState(currentPage = entryPage) { workspacePages.value + postureFirstHome }
            .also { pagerHandoff.opened(it, entryPage, entryAnchor.takeIf { _ -> spreadEnds.todayOverlay }) }
    }
    SideEffect { pagerHandoff.attach(nativePager, pagerPosture) }
    // Rubber band + Reduce Motion dissolve of the paging workspace, read only in draw blocks.
    val workspaceEffects = remember { WorkspaceScrollEffects() }
    val pager = remember(nativePager, firstHome) { LauncherPager(nativePager, firstHome, workspaceEffects) }
    /**
     * Ends a drag ([clear]) and brings the pager to [destination] with a slide (iOS), never a jump: a
     * release during an edge turn finishes that turn, a cancel slides back. Released on the temporary
     * drop page, that page stays until the pager has left it (removed first, the App Library flashed).
     */
    suspend fun slideAfterDrag(destination: Int, clear: () -> Unit) {
        if (pager.currentPage >= model.state.value.homePages) holdDropPage = true
        clear()
        withFrameNanos { }
        try {
            if (pager.currentPage != destination || nativePager.currentPageOffsetFraction != 0f) {
                if (LauncherMotion.animationsOff) pager.scrollToPage(destination) else pager.animateScrollToPage(destination)
            }
        } finally { holdDropPage = false }
    }
    LaunchedEffect(drag.moved) {
        // An app dragged out of the App Library goes to Home (iOS); a still press keeps the Library and
        // shows its menu there (it used to flip to Home at once), and a folder on Home or Today stays put.
        if (drag.moved && drag.source?.target is DropTarget.Library &&
            (drag.source?.folderId == null || pager.currentPage >= homePages)) {
            withFrameNanos { }
            pager.scrollToPage(lastHomePage.coerceIn(0, homePages - 1))
        }
    }
    fun leaveTemporaryWidgetPage() {
        val persistedPages = model.state.value.homePages
        if (pager.currentPage >= persistedPages)
            pager.requestScrollToPage((persistedPages - 1).coerceAtLeast(0))
    }
    var priorPendingPlacement by remember { mutableStateOf<WidgetPlacement?>(null) }
    LaunchedEffect(widgets.pendingPlacement, state.layout) {
        val pending = widgets.pendingPlacement
        if (pending != null) priorPendingPlacement = pending
        else priorPendingPlacement?.let { prior ->
            if (model.placement(prior.slot) == null && prior.page >= homePages) leaveTemporaryWidgetPage()
            priorPendingPlacement = null
        }
    }
    val pageGestures = remember(nativePager) { PageGestureLimits(nativePager) }
    SideEffect { pageGestures.editing = drag.active || widgetSession != null || resizeSlot != null }
    val scope = rememberCoroutineScope()
    var previousHomePages by remember { mutableIntStateOf(homePages) }
    var previousEditRevision by remember { mutableIntStateOf(state.editRevision) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(pager, homePages) {
        snapshotFlow { nativePager.settledPage to drag.active }.distinctUntilChanged().collect { (physicalPage, moving) ->
            if (!moving) {
                savedAnchorPage = pagerPosture.anchorPage(physicalPage)
                val logicalPage = physicalPage - firstHome
                if (logicalPage in 0 until homePages) lastHomePage = logicalPage
            }
        }
    }
    LaunchedEffect(homePages, state.editRevision) {
        if (homePages != previousHomePages && !drag.active) {
            // Pin edits in the library keep the library selected; a completed drop stays on home.
            if (state.editRevision == previousEditRevision) {
                if (pager.currentPage == previousHomePages) pager.scrollToPage(homePages)
                else if (pager.currentPage >= pageCount) pager.scrollToPage(homePages - 1)
            } else if (pager.currentPage >= homePages) pager.scrollToPage(homePages - 1)
        }
        previousHomePages = homePages
        previousEditRevision = state.editRevision
    }
    LaunchedEffect(pager.settledPage) { if (pager.settledPage != homePages) focus.clearFocus() }
    // Swiping away from an App Library search abandons it (iOS); before, a hidden search made the next
    // Home press only clear it, and the Library came back on the stale list. A launch keeps it (origin).
    val librarySettled = remember { intArrayOf(Int.MIN_VALUE) }
    LaunchedEffect(pager.settledPage, homePages) {
        val left = librarySettled[0] == homePages && pager.settledPage != homePages
        librarySettled[0] = pager.settledPage
        if (left && launchOrigin == null && librarySearch.searching) librarySearch.cancel()
    }
    LaunchedEffect(state.verticalStatus) { onStatusMode(state.verticalStatus) }
    // Automatic StandBy may take over only an idle Home, never open UI or an edit in progress (code review P1-4).
    LaunchedEffect(Unit) { snapshotFlow { spotlightOpen || sheet.isNotEmpty() || openFolderId != null || desktopEditing ||
        drag.active || drag.settle != null || widgetSession != null || selectedId != null || emptyMenuVisible ||
        resizeSlot != null || removeAlert != null || folderMenuAppId != null }.collect { launcherActivity.panelOrientation.setHomeBusy(it) } }
    LaunchedEffect(spotlightOpen, spotlightReturn) {
        if (!spotlightOpen) spotlightRestored = false
        if (!spotlightOpen && !spotlightReturn) { delay(250); spotlightQuery = "" }
    }
    LaunchedEffect(spotlightReturn) {
        // A launch that never left Home (refused, or the app finished at once) forgets the return.
        if (spotlightReturn) { delay(1500); if (launcherActivity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) spotlightReturn = false }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && spotlightReturn) {
                spotlightReturn = false; spotlightRestored = true; spotlightOpen = true
            }
            // A provider widget's tap that took Home away: its app returns into that widget.
            if (event == Lifecycle.Event.ON_PAUSE) pendingProviderTap?.let { tap ->
                pendingProviderTap = null
                if (android.os.SystemClock.uptimeMillis() - tap.at <= PROVIDER_TAP_LAUNCH_MS && tap.tile.attached) {
                    WidgetLaunchTiles.arm(tap.app.id, tap.tile, tap.art)
                    WidgetLaunchTiles.take(tap.app.id)
                    val location = IntArray(2).also(composeView.rootView::getLocationOnScreen)
                    NavigationProgressBus.noteLaunched(tap.app.id, ZoomBounds(tap.window.left + location[0],
                        tap.window.top + location[1], tap.window.right + location[0], tap.window.bottom + location[1]))
                }
            }
            // Return to origin: the launch that takes Home away arms its origin; the next resume is its return.
            if (event == Lifecycle.Event.ON_PAUSE) launchOrigin?.let { origin ->
                if (!origin.armed && originArms(origin.launchedAt, android.os.SystemClock.uptimeMillis()))
                    launchOrigin = origin.copy(armed = true)
            }
            if (event == Lifecycle.Event.ON_RESUME) resumeSerial++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Back from an app (resume, after Back or the Home gesture): Home normally never moved, so this only
    // puts back what a recreated window or a posture change lost (HomeReturn.kt, originRestorePage).
    LaunchedEffect(resumeSerial) {
        if (resumeSerial == 0) return@LaunchedEffect
        val origin = launchOrigin ?: return@LaunchedEffect
        launchOrigin = null
        if (!origin.armed) return@LaunchedEffect
        origin.folderId?.let { id -> if (openFolderId == null && state.folders.any { it.id == id }) openFolderId = id }
        if (origin.category != null && librarySearch.openCategory == null) librarySearch.openCategory = origin.category
        originRestorePage(origin, pagerPosture, nativePager.pageCount, spreadEnds.todayOverlay, nativePager.currentPage)
            ?.let { nativePager.scrollToPage(it) }
    }
    LaunchedEffect(homeRequests) { if (homeRequests > 0) {
        // iOS: Home coming back from an app is exactly where it was left (Today View, a folder, the App
        // Library and its category or search, Spotlight). Only a Home press with Home already in front
        // navigates: it closes what is open, and with nothing open goes to the first Home page.
        val anythingOpen = openFolderId != null || spotlightOpen || librarySearch.openCategory != null ||
            librarySearch.searching || desktopEditing || sheet.isNotEmpty() || selectedId != null ||
            emptyMenuVisible || widgetSession != null || resizeSlot != null || removeAlert != null || folderMenuAppId != null
        val firstPage = firstHomePhysicalPage(pagerPosture, spreadEnds.todayOverlay, nativePager.pageCount)
        val action = homeRequestAction(launcherActivity.homeIntentFromApp, anythingOpen, nativePager.currentPage, firstPage)
        // Gestures and menus never outlive leaving Home. A drag cut off on the temporary drop page goes
        // back to where it began (it used to leave the App Library in view).
        val dragOrigin = drag.originPage.takeIf { drag.active && pager.currentPage >= homePages && action != HomeRequestAction.FIRST_PAGE }
        if (dragOrigin != null) { holdDropPage = true; scope.launch { slideAfterDrag(dragOrigin.coerceAtMost(homePages - 1)) { } } }
        drag.clear(); widgetSession = null; resizeSlot = null; selectedId = null; appMoveMenu = false
        emptyCellIndex = null; emptyMenuVisible = false; removeAlert = null; folderMenuAppId = null; drag.settle = null
        focus.clearFocus(); keyboard?.hide()
        if (action != HomeRequestAction.KEEP) launchOrigin = null
        when (action) {
            HomeRequestAction.KEEP -> Unit
            HomeRequestAction.CLOSE -> {
                sheet = ""; widgetPackage = null; widgetExactTarget = false; widgetPlacementMessage = null
                openFolderId = null; desktopEditing = false
                spotlightOpen = false; spotlightReturn = false
                librarySearch.cancel(); librarySearch.openCategory = null
            }
            HomeRequestAction.FIRST_PAGE -> pager.animateToPhysicalPage(firstPage)
            HomeRequestAction.NONE -> Unit
        }
    } }
    // Every launch from Home records where Home is (page, folder, library category/search, Spotlight).
    val originPager = rememberUpdatedState(Triple(pagerPosture, nativePager, spreadEnds))
    fun recordOrigin(app: AppEntry) {
        val (posture, pagerState, _) = originPager.value
        val physical = pagerState.currentPage
        val page = originPageOf(physical, posture, pagerState.pageCount)
        launchOrigin = LaunchOrigin(app.id, page, physical, posture.spread, posture.leading,
            folderId = openFolderId, category = librarySearch.openCategory?.takeIf { page == OriginPage.LIBRARY },
            librarySearch = librarySearch.searching && page == OriginPage.LIBRARY,
            spotlight = spotlightOpen || spotlightReturn, launchedAt = android.os.SystemClock.uptimeMillis())
    }
    val recordingLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit = remember(onLaunchFrom) { { app, bounds ->
        recordOrigin(app)
        // Android re-shows a window's keyboard when it regains focus: put it away as Home is left, so the
        // App Library search comes back with its results and the keyboard down (iOS).
        focus.clearFocus(); keyboard?.hide()
        onLaunchFrom(app, bounds)
    } }
    val recordingLaunch: (AppEntry) -> Unit = remember(recordingLaunchFrom) { { app -> recordingLaunchFrom(app, null) } }
    @Suppress("NAME_SHADOWING") val onLaunchFrom = recordingLaunchFrom
    @Suppress("NAME_SHADOWING") val onLaunch = recordingLaunch
    // A widget opens its app through the icon pipeline: zoom out of the widget, launch origin, and a
    // return flight back into it (owner round 9: it used to slide in sideways and vanish on return).
    val latestApps = rememberUpdatedState(state.apps)
    val latestLaunchFrom = rememberUpdatedState(recordingLaunchFrom)
    val widgetAppOpener = remember(launcherActivity, composeView) { WidgetAppOpener { intent, tile ->
        val app = resolveWidgetApp(launcherActivity, intent, latestApps.value) ?: return@WidgetAppOpener false
        val root = tile?.boundsInRoot()?.takeIf { it.width >= 1f && it.height >= 1f }
        val window = tile?.boundsInWindow()?.takeIf { it.width >= 1f && it.height >= 1f }
        if (tile == null || root == null || window == null) { latestLaunchFrom.value(app, null); return@WidgetAppOpener true }
        // The widget's own pixels are what the card starts as (its provider view cannot be drawn twice).
        captureWindowRegion(launcherActivity, composeView, root) { image ->
            WidgetLaunchTiles.arm(app.id, tile, image?.asAndroidBitmap())
            latestLaunchFrom.value(app, window.toAndroidBounds())
        }
        true
    } }
    // An Android widget starts its app itself (a PendingIntent the platform sends; no public API lets a
    // launcher animate it), so its window still opens the system's way. If the tap took Home away, the
    // swipe home at least shrinks the app back into that widget and Home stays where it was.
    val latestRecordOrigin = rememberUpdatedState<(AppEntry) -> Unit>(::recordOrigin)
    val providerWidgetTap = remember(launcherActivity, composeView) { ProviderWidgetTap { pkg, tile ->
        val app = latestApps.value.firstOrNull { it.isPersonal && it.available && it.packageName == pkg } ?: return@ProviderWidgetTap
        val root = tile.boundsInRoot()?.takeIf { it.width >= 1f && it.height >= 1f } ?: return@ProviderWidgetTap
        val window = tile.boundsInWindow() ?: return@ProviderWidgetTap
        val tap = PendingProviderTap(app, tile, window, android.os.SystemClock.uptimeMillis())
        pendingProviderTap = tap
        latestRecordOrigin.value(app)
        captureWindowRegion(launcherActivity, composeView, root) { image -> tap.art = image?.asAndroidBitmap() }
    } }
    // The return flight lands only in icons of the surface in front (LaunchBounds, frontIconLayers).
    val todayOverlayIn by remember(nativePager, spreadEnds) { derivedStateOf {
        spreadEnds.todayOverlay && nativePager.currentPage == 0 && kotlin.math.abs(nativePager.currentPageOffsetFraction) < .02f
    } }
    val fullLibraryIn by remember(nativePager, spreadEnds) { derivedStateOf {
        spreadEnds.fullLibrary && nativePager.currentPage == spreadEnds.libraryPage
    } }
    val onLibraryPage by remember(nativePager) { derivedStateOf { nativePager.currentPage == nativePager.pageCount - 1 } }
    val iconFront = frontIconLayers(spotlight = spotlightOpen || spotlightReturn, sheet = sheet.isNotEmpty(),
        folder = openFolderId != null, category = librarySearch.openCategory != null && onLibraryPage,
        todayOverlayIn = todayOverlayIn, fullLibraryIn = fullLibraryIn)
    SideEffect { LaunchIconRegistry.setFront(iconFront) }
    // The App Library sorts its Suggestions, categories and search by recent use. While it is in view the
    // order is frozen, so an app opened from it comes back into the very tile it left (the launch records
    // it as most recent at once); the new order shows on the next visit.
    val libraryRecentOrder = remember { mutableStateOf(launcherActivity.recentApps.ids) }
    LaunchedEffect(onLibraryPage) {
        snapshotFlow { launcherActivity.recentApps.ids }.collect { if (!onLibraryPage) libraryRecentOrder.value = it }
    }
    /** iPhone: the Search button and the swipe down open Spotlight. */
    @Suppress("UNUSED_PARAMETER")
    fun openSearch(bounds: android.graphics.Rect?) {
        selectedId = null; emptyMenuVisible = false; spotlightOpen = true; spotlightRestored = false
        // Spotlight is drawn below an open folder: an external "search" request closes the folder
        // (and ends editing) so the field and keyboard are never hidden behind it.
        openFolderId = null; folderMenuAppId = null; desktopEditing = false
    }
    LaunchedEffect(searchRequests) { if (searchRequests > 0) { drag.clear(); widgetSession = null; resizeSlot = null; sheet = ""; widgetPackage = null; widgetExactTarget = false; selectedId = null
        openSearch(null)
    } }
    val widgetPickerBack = {
        if (widgetSession != null) {
            leaveTemporaryWidgetPage(); widgetSession = null; widgetPlacementMessage = null
        } else {
            sheet = ""; widgetPackage = null; widgetExactTarget = false; widgetPlacementMessage = null
        }
    }
    BackHandler(enabled = sheet == "widgets") { widgetPickerBack() }
    BackHandler(enabled = sheet.isEmpty()) { if (resizeSlot != null) resizeSlot = null else if (drag.active) {
        val destination = if (drag.source?.target is DropTarget.Library) homePages else drag.originPage.coerceAtMost(homePages - 1)
        scope.launch { slideAfterDrag(destination) { drag.clear() } }
    } else if (selectedId != null) selectedId = null else if (emptyMenuVisible) emptyMenuVisible = false
    else if (desktopEditing) desktopEditing = false else {
        focus.clearFocus()
        // Home 1 wherever it is: physical page 1 is Home 1 only while Today View is shown.
        scope.launch { pager.animateToPhysicalPage(firstHomePhysicalPage(pagerPosture, spreadEnds.todayOverlay, nativePager.pageCount)) }
    } }
    val openLeadingPage = { scope.launch { pager.animateScrollToPage(if (firstHome > 0) -1 else 0) }; Unit }
    val openLibrary = { scope.launch { pager.animateScrollToPage(homePages) }; Unit }

    val dragWindowPage = if (expandedWorkspace && (drag.active || widgetSession != null)) pager.settledPage else pager.currentPage
    val eligibleDragPages = remember(expandedWorkspace, dragWindowPage, visibleHomePages, spreadEnds) {
        // The Today column covers the (blurred) Home 1 under it: only its own cells take touches and drops.
        if (expandedWorkspace && spreadEnds.todayOverlay && dragWindowPage == 0) setOf(-1)
        else if (expandedWorkspace && dragWindowPage in 0 until visibleHomePages) {
            setOfNotNull((dragWindowPage - 1).takeIf { it >= -1 }, dragWindowPage)
        } else setOf(dragWindowPage)
    }
    val rawTarget = if (drag.active) drag.destination(drag.pointer, eligibleDragPages)?.target else null
    val target = if (rawTarget is DropTarget.Home && drag.source?.target is DropTarget.Widget) {
        val slot = (drag.source!!.target as DropTarget.Widget).index
        model.placement(slot)?.let {
            DropTarget.Home(adjustedWidgetDropIndex(rawTarget.index, it, drag.source!!.bounds, drag.origin))
        } ?: rawTarget
    } else rawTarget
    val blockedDock = drag.moved && target is DropTarget.Dock &&
        if (drag.source?.folderId != null) state.dock.none { it == null }
        else drag.source?.appId?.let { !canPlaceInDock(state.layout, it) } == true
    val candidateInsertion = target.takeIf { drag.moved && !blockedDock }
    // Folder targets act at once; cells and Dock slots reflow after the finger rests.
    val insertionTarget = if (candidateInsertion is DropTarget.Folder) candidateInsertion
        else committedInsertion?.takeIf { drag.active && drag.moved && !blockedDock }
    val widgetRawTarget = widgetSession?.let { session -> drag.regions.values.firstOrNull {
        it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(session.pointer)
    }?.target as? DropTarget.Home }
    val widgetDraft = widgetSession?.let { session -> session.candidate ?: widgetRawTarget?.let { cell ->
        widgetCandidate(state.layout, session.slot, session.targetIndex ?: cell.index, session.span.width, session.span.height)
    } ?: session.targetIndex?.let { widgetCandidate(state.layout, session.slot, it, session.span.width, session.span.height) } }
    val dropHomePage = if (pager.currentPage >= visibleHomePages)
        lastHomePage.coerceIn(0, homePages - 1) else pager.currentPage.coerceIn(0, homePages)
    val previewLayout = remember(state.layout, drag.source, insertionTarget, drag.moved) {
        val id = drag.source?.appId
        val folderSourceId = drag.source?.folderId
        when {
            // Out of a folder the cells make room too (before, nothing moved until the drop).
            id != null && folderSourceId != null && (insertionTarget is DropTarget.Home || insertionTarget is DropTarget.Dock) ->
                previewAppOutOfFolder(state.layout, folderSourceId, id, insertionTarget)
            id != null && insertionTarget is DropTarget.Home -> dropApp(state.layout, id, insertionTarget)
            id != null && insertionTarget is DropTarget.Dock -> dropApp(state.layout, id, insertionTarget)
            drag.source?.target is DropTarget.Widget && insertionTarget is DropTarget.Home ->
                moveWidget(state.layout, (drag.source!!.target as DropTarget.Widget).index, insertionTarget.index)
            else -> state.layout
        }
    }
    val dragAppId = drag.source?.takeIf { it.folderId == null && it.target !is DropTarget.Widget }?.appId
        ?.takeUnless(::isFolderId)
    // The folder partner is the Home icon the dragged icon's centre covers (iOS), wherever the finger
    // holds the icon; cells and the Dock keep following the finger.
    val ghostCenter = drag.ghostRest?.takeIf { dragAppId != null && drag.moved }?.let { rest ->
        val lift = DRAG_LIFT_SCALE
        Offset(drag.pointer.x - drag.grab.x * lift + rest.width * lift / 2f, drag.pointer.y - drag.grab.y * lift + rest.height * lift / 2f)
    }
    val hoverOccupant = ghostCenter?.takeIf { target !is DropTarget.Dock && target != DropTarget.Remove }?.let { center ->
        val pages = eligibleDragPages
        val icons = buildMap {
            fun offer(id: String?, index: Int) {
                if (id == null || id == dragAppId || !appsById.containsKey(id) || homeCellPage(index) !in pages) return
                iconRegistry[id]?.let { put(id, MenuBounds(it.left, it.top, it.right, it.bottom)) }
            }
            previewLayout.slots.forEachIndexed { index, id -> offer(id, index) }
            previewLayout.leadingSlots.forEachIndexed { local, id -> offer(id, homeCellIndex(-1, local)) }
        }
        folderPartnerAt(center.x, center.y, icons)
    }
    val hoverKind = if (dragAppId == null || hoverOccupant == null) DragHoverKind.INSERT else DragHoverKind.FOLDER
    // A light tick when the folder platter forms under the dragged app.
    LaunchedEffect(folderHoverId) { if (folderHoverId != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val restSlopPx = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(candidateInsertion, hoverKind, hoverOccupant, drag.active) {
        if (!drag.active) { committedInsertion = null; folderHoverId = null; return@LaunchedEffect }
        if (hoverKind == DragHoverKind.FOLDER) {
            if (folderHoverId != hoverOccupant) folderHoverId = null
            delay(DRAG_FOLDER_DWELL_MS)
            folderHoverId = hoverOccupant
        } else {
            folderHoverId = null
            if (candidateInsertion != committedInsertion) {
                // Reflow once the icon rests, not while it passes over a gap on its way somewhere.
                var from = drag.pointer
                while (true) {
                    delay(DRAG_REFLOW_DWELL_MS)
                    val now = drag.pointer
                    if (dragRested(from.x, from.y, now.x, now.y, restSlopPx)) break
                    from = now
                }
                committedInsertion = candidateInsertion
            }
        }
    }
    val edgeWidth = with(LocalDensity.current) { 30.dp.toPx() }
    val edgePointer = widgetSession?.takeIf { it.dragging }?.pointer ?: drag.pointer
    val edgeActive = (drag.active && drag.moved) || widgetSession?.dragging == true
    // Resting over a Dock slot never turns the page, even beside the screen edge.
    val edge = if (!edgeActive || target is DropTarget.Dock) 0 else dragEdgeDirection(edgePointer, drag.rootBounds, edgeWidth)
    LaunchedEffect(edgeActive, edge, pager) {
        var turns = 0
        if (edge != 0) while (drag.active || widgetSession?.dragging == true) {
            delay(edgeTurnDelayMs(turns++))
            val next = (pager.currentPage + edge).coerceIn(-1, homePages)
            if ((!drag.active && widgetSession?.dragging != true) || next == pager.currentPage) break
            // Do not key this effect on currentPage: it changes halfway through the
            // animation and would cancel the turn before the inner grid is visible.
            // Once the hold commits a turn, finish its animation while the finger moves
            // into the incoming page. Leaving the edge cancels only the next hold timer.
            scope.launch { pager.animateScrollToPage(next) }.join()
        }
    }
    /** iOS names a new folder after its apps' category (the app that stays decides). */
    fun newFolderTitle(staysId: String, joinsId: String): String = suggestedFolderTitle(
        appsById[staysId]?.let { libraryCategoryOf(launcherActivity, it) },
        appsById[joinsId]?.let { libraryCategoryOf(launcherActivity, it) })

    fun finishDrag(cancelled: Boolean) {
        val source = drag.source ?: return
        val moved = drag.moved
        val rawDropTarget = if (moved && !cancelled) drag.destination(drag.pointer, eligibleDragPages)?.target else null
        // Drop into the gap the user saw open (the committed reflow), else where the finger is.
        // A folder only takes the app while the finger is on it: a committed folder the finger has left is no gap.
        val useCommitted = rawDropTarget != null && rawDropTarget !is DropTarget.Folder &&
            committedInsertion != null && committedInsertion !is DropTarget.Folder && !blockedDock
        val destination = when {
            rawDropTarget == null -> null
            useCommitted -> committedInsertion
            rawDropTarget is DropTarget.Home && source.target is DropTarget.Widget ->
                model.placement(source.target.index)?.let {
                    DropTarget.Home(adjustedWidgetDropIndex(rawDropTarget.index, it, source.bounds, drag.origin))
                } ?: rawDropTarget
            else -> rawDropTarget
        }
        // A folder is made only if the finger still rests on the partner's icon at release.
        val folderPartner = folderHoverId?.takeIf {
            moved && !cancelled && dragAppId != null && it == hoverOccupant && hoverKind == DragHoverKind.FOLDER
        }
        // The folder takes the partner's place as Home shows it now (a reflow seen on the way stays).
        val partnerIndex = folderPartner?.let { previewLayout.indexOfShortcut(it) }
        val createdFolder = if (folderPartner != null && partnerIndex != null)
            model.createFolder(dragAppId!!, folderPartner, partnerIndex, newFolderTitle(folderPartner, dragAppId), previewLayout) else null
        val changed = when {
            createdFolder != null -> true
            source.folderId != null && destination is DropTarget.Folder ->
                model.addAppToFolder(destination.id, source.appId ?: "")
            source.folderId != null && destination != null && source.appId != null ->
                model.removeAppFromFolder(source.folderId, source.appId, destination)
            destination == DropTarget.Remove -> model.removePlacement(source.target)
            destination is DropTarget.Home && source.target is DropTarget.Widget -> model.moveWidgetTo(source.target.index, destination.index)
            destination != null && source.appId != null -> model.applyDrop(source.appId, destination)
            else -> false
        }
        val returnToLibrary = source.target is DropTarget.Library && source.folderId == null && !changed
        // The lifted item springs onto its new slot (or back), into a folder it joined, or
        // settles down in place after a still press in jiggle mode.
        val rest = drag.ghostRest
        val menuLift = !moved && !desktopEditing
        if (rest != null && !menuLift && !returnToLibrary) {
            val lift = DRAG_LIFT_SCALE
            val from = Rect(drag.pointer.x - drag.grab.x * lift, drag.pointer.y - drag.grab.y * lift,
                drag.pointer.x - drag.grab.x * lift + rest.width * lift, drag.pointer.y - drag.grab.y * lift + rest.height * lift)
            val widgetSlotIndex = (source.target as? DropTarget.Widget)?.index
            // An app pulled out of a folder and not placed anywhere dives back into its folder (its panel has closed).
            val joinedFolder = createdFolder ?: (destination as? DropTarget.Folder)?.id?.takeIf { changed }
                ?: source.folderId?.takeIf { !changed && moved && iconRegistry[it] != null }
            val itemId = widgetSlotIndex?.let { "widget-slot-$it" } ?: source.appId
            if (itemId != null) drag.settle = DropSettle(serial = ++settleSerial, itemId = itemId,
                appId = source.appId?.takeUnless(::isFolderId), folderId = source.appId?.takeIf(::isFolderId),
                widgetSnapshot = drag.snapshot, from = from,
                targetId = joinedFolder ?: itemId, target = if (joinedFolder != null) null else rest,
                shrink = joinedFolder != null, widgetId = source.widgetId)
        }
        if (createdFolder != null) scope.launch {
            // iOS opens the folder it just made so it can be named.
            delay(360)
            openFolderId = createdFolder
        }
        val destinationHomePage = (destination as? DropTarget.Home)?.index?.let(::homeCellPage)
        val currentWindow = pager.settledPage.coerceIn(0, visibleHomePages - 1)
        val page = if (createdFolder != null && partnerIndex != null) {
            if (expandedWorkspace && homeCellPage(partnerIndex) in eligibleDragPages) currentWindow else homeCellPage(partnerIndex)
        } else when (destination) {
            is DropTarget.Home -> if (expandedWorkspace && homeCellPage(destination.index) in eligibleDragPages) currentWindow else destinationHomePage!!
            is DropTarget.Dock -> dropHomePage
            is DropTarget.Widget -> 0
            else -> if (source.target is DropTarget.Library) pager.currentPage else drag.originPage
        }
        scope.launch {
            // Let a new home page compose before removing the temporary drop page.
            withFrameNanos { }
            val homesNow = model.state.value.homePages
            slideAfterDrag(if (returnToLibrary) homesNow else page.coerceIn(-1, homesNow - 1)) { drag.clear() }
            // In jiggle mode a release without movement only sets the item down again.
            if (!moved && !cancelled && !desktopEditing) {
                if (source.target is DropTarget.Widget) { widgetSlot = source.target.index; sheet = "widgetActions" }
                else if (source.appId?.let(::isFolderId) == true) selectedId = source.appId
                else if (source.folderId == null) selectedId = source.appId
            }
            committedInsertion = null; folderHoverId = null
        }
    }

    fun startResize(slot: Int) {
        val placement = model.placement(slot) ?: return
        val constraints = resizeGridSizing?.let { grid ->
            widgets.manager.getAppWidgetInfo(placement.id)?.let { widgets.sizing(it, grid) }
        }
        resizeSlot = slot; resizeWidth = placement.spanX; resizeHeight = placement.spanY
        resizeConstraints = constraints; sheet = ""
    }
    fun openWidgetPicker(page: Int = pager.currentPage) {
        widgetTargetIndex = homeCellIndex(page.coerceIn(-1, homePages - 1), 0)
        widgetExactTarget = false; widgetSlot = model.nextWidgetSlot()
        widgetPackage = null; widgetProfileSerial = null; sheet = "widgets"
    }
    val todayActions = remember(homePages) {
        TodayViewActions(onAddWidget = { openWidgetPicker(-1) }, onEdit = { desktopEditing = true }, onSearch = { openSearch(null) })
    }
    fun shareApp(app: AppEntry) {
        val packageName = app.component.packageName
        val text = if (packageName.isNotBlank()) "${app.label}\nhttps://play.google.com/store/apps/details?id=$packageName" else app.label
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, app.label).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { launcherActivity.startActivity(Intent.createChooser(send, app.label)) }
    }
    fun confirmRemoveApp(id: String, remove: () -> Unit) {
        val app = appsById[id] ?: return
        val label = app.label
        // iOS 26: 从主屏幕移除 / 删除 App (red) / 取消, stacked. Deleting hands over to the system
        // uninstaller; apps the system will not uninstall (and dual-app copies) only offer removal.
        removeAlert = IosAlertRequest("remove-app-$id", uxText("移除“$label”？", "Remove “$label”?"),
            uxText("“从主屏幕移除”会将 App 保留在 App 资源库中。", "“Remove from Home Screen” keeps the app in the App Library."),
            buildList {
                add(IosAlertAction(uxText("从主屏幕移除", "Remove from Home Screen"), "alert-remove-home") {
                    removeAlert = null; remove()
                })
                if (canUninstall(launcherActivity, app)) add(IosAlertAction(uxText("删除 App", "Delete App"),
                    "alert-delete-app", IosAlertRole.Destructive) { removeAlert = null; requestUninstall(launcherActivity, app) })
                add(IosAlertAction(uxText("取消", "Cancel"), "alert-cancel", IosAlertRole.Cancel) { removeAlert = null })
            })
    }
    // iOS 18+ "要求面容 ID" / "隐藏并要求面容 ID" (round 10), with the phone's own owner proof.
    var hideSheetApp by remember { mutableStateOf<AppEntry?>(null) }
    /** False (and an alert pointing at Settings) when the phone has no screen lock to prove the owner with. */
    fun privacyAvailable(app: AppEntry): Boolean {
        val ctx = launcherActivity
        if (!AppPrivacy.deviceSecure(ctx)) {
            removeAlert = IosAlertRequest("privacy-no-lock-${app.id}", uxText("需要锁屏密码", "Screen Lock Required"),
                uxText("请先在系统设置中设置锁屏密码，才能用指纹或密码保护 App。",
                    "Set a screen lock in Settings before protecting apps with your fingerprint or passcode."),
                listOf(IosAlertAction(uxText("前往设置", "Settings"), "alert-privacy-settings") {
                    removeAlert = null; AppPrivacy.openSecuritySettings(ctx)
                }, IosAlertAction(uxText("取消", "Cancel"), "alert-cancel", IosAlertRole.Cancel) { removeAlert = null }))
            return false
        }
        return true
    }
    /** Round 12, owner: the menu itself must offer hiding. Prove the owner, then the "隐藏“App”？" sheet. */
    fun startHide(app: AppEntry) {
        if (!privacyAvailable(app)) return
        val ctx = launcherActivity
        AppPrivacy.authenticate(ctx, uxText("隐藏“${app.label}”", "Hide “${app.label}”"), null) { ok -> if (ok) hideSheetApp = app }
    }
    fun askRequireAuthentication(app: AppEntry) {
        val ctx = launcherActivity
        if (!privacyAvailable(app)) return
        val method = AppPrivacy.methodName(ctx)
        removeAlert = IosAlertRequest("privacy-${app.id}", uxText("要为“${app.label}”要求${method}吗？", "Require $method for “${app.label}”?"),
            if (AppPrivacy.method(ctx) == AppPrivacy.Method.PASSCODE) uxText("此 App 需要输入锁屏密码才能打开。",
                "This app will require your screen lock to open.")
            else uxText("此 App 需要验证${method}或锁屏密码才能打开。",
                "This app will require $method or your screen lock to open."),
            buildList {
                add(IosAlertAction(AppPrivacy.requireLabel(ctx), "alert-privacy-lock") {
                    removeAlert = null
                    AppPrivacy.authenticate(ctx, AppPrivacy.requireLabel(ctx), app.label) { ok -> if (ok) AppPrivacy.lock(ctx, app.id) }
                })
                if (AppPrivacy.canHide(ctx, app)) add(IosAlertAction(AppPrivacy.hideLabel(ctx), "alert-privacy-hide") {
                    removeAlert = null
                    AppPrivacy.authenticate(ctx, AppPrivacy.hideLabel(ctx), app.label) { ok -> if (ok) hideSheetApp = app }
                })
                add(IosAlertAction(uxText("取消", "Cancel"), "alert-cancel", IosAlertRole.Cancel) { removeAlert = null })
            })
    }
    /** "不要求…": proving the owner again, as iOS does, before the protection comes off. */
    fun releaseAuthentication(app: AppEntry, addToHome: Boolean = false) {
        val ctx = launcherActivity
        fun release() {
            AppPrivacy.release(ctx, app.id)
            if (addToHome) model.setPinned(app.id, true)
        }
        // Inside the unlocked Hidden folder the owner has just authenticated.
        if (AppPrivacy.isHidden(app.id) && AppPrivacy.revealed) release()
        else AppPrivacy.authenticate(ctx, AppPrivacy.releaseLabel(ctx), app.label) { ok -> if (ok) release() }
    }
    /** "隐藏 App", then "要求指纹" (or "不要求指纹" once it is locked); either is left out where it cannot apply. */
    fun privacyMenuActions(app: AppEntry, close: () -> Unit): List<IosMenuAction> = listOfNotNull(
        if (AppPrivacy.canHide(launcherActivity, app)) IosMenuAction(uxText("隐藏 App", "Hide App"), IosSymbols.eyeSlash,
            "context-hide") { close(); startHide(app) } else null,
        when {
            AppPrivacy.isLocked(app.id) -> IosMenuAction(AppPrivacy.releaseLabel(launcherActivity), IosSymbols.ownerAuthOff,
                "context-privacy") { close(); releaseAuthentication(app) }
            AppPrivacy.canLock(launcherActivity, app) -> IosMenuAction(AppPrivacy.requireLabel(launcherActivity), IosSymbols.ownerAuth,
                "context-privacy") { close(); askRequireAuthentication(app) }
            else -> null
        },
    )
    fun confirmRemoveWidget(slot: Int) {
        removeAlert = IosAlertRequest("remove-widget-$slot", uxText("移除小组件？", "Remove Widget?"),
            uxText("移除此小组件不会删除任何数据。", "Removing this widget will not delete any data."),
            listOf(IosAlertAction(uxText("移除", "Remove"), "alert-remove-widget", IosAlertRole.Destructive) {
                removeAlert = null; widgets.remove(slot)
            }, IosAlertAction(uxText("取消", "Cancel"), "alert-cancel", IosAlertRole.Cancel) { removeAlert = null }))
    }
    fun confirmDissolveFolder(id: String) {
        val title = state.layout.folder(id)?.title ?: return
        removeAlert = IosAlertRequest("dissolve-$id", uxText("移除“$title”文件夹？", "Remove the “$title” folder?"),
            uxText("其中的 App 会放回主屏幕。", "Its apps go back to the Home Screen."),
            listOf(IosAlertAction(uxText("移除文件夹", "Remove Folder"), "alert-dissolve-folder", IosAlertRole.Destructive) {
                removeAlert = null; model.dissolveFolder(id)
            }, IosAlertAction(uxText("取消", "Cancel"), "alert-cancel", IosAlertRole.Cancel) { removeAlert = null }))
    }
    val editActions = rememberUpdatedState(HomeEditActions(
        onRemoveApp = { id -> confirmRemoveApp(id) { model.setPinned(id, false) } },
        onDissolveFolder = { confirmDissolveFolder(it) },
        onRemoveWidget = { confirmRemoveWidget(it) },
        onResizeWidget = { startResize(it) },
        onRemoveDock = { index -> state.dock.getOrNull(index)?.let { id ->
            confirmRemoveApp(id) { model.removePlacement(DropTarget.Dock(index)) } } },
        onTapEmpty = { if (desktopEditing) { desktopEditing = false; emptyMenuVisible = false } }))
    val editingState = rememberUpdatedState(desktopEditing)
    val decoratedState = rememberUpdatedState(editDecorated)
    val liftedState = rememberUpdatedState(selectedId)
    val hiddenState = rememberUpdatedState(setOfNotNull(drag.settle?.itemId))
    val folderHoverState = rememberUpdatedState(folderHoverId)
    val editChrome = remember {
        HomeEditChrome(editingState, decoratedState, jiggleClock, editIntensity, liftedState, hiddenState,
            folderHoverState, iconRegistry, editActions)
    }
    // The resting rectangle a lifted item rises from (root px): the registered icon, else
    // the icon's place inside its cell, else the item's own bounds (widgets).
    fun liftedRestRect(source: DragRegion): Rect {
        if (source.widgetId != null) return source.bounds
        val id = source.appId
        if (id != null && source.target !is DropTarget.Library) iconRegistry[id]?.let { return it }
        val px = with(windowDensity) { ghostIconSizeDp.dp.toPx() }
        val cell = iconRectInCell(MenuBounds(source.bounds.left, source.bounds.top, source.bounds.right, source.bounds.bottom),
            px, topAligned = source.target is DropTarget.Home || source.folderId != null)
        return Rect(cell.left, cell.top, cell.right, cell.bottom)
    }
    // Home's own zoom for app opens and gesture returns; the switcher itself lives in the nav overlay.
    val appOpenTransform by rememberAppOpenTransform()
    // Unlock → Home arrival (UnlockArrival.kt): the spring runs here, the layers below only read it.
    UnlockArrivalEffect()
    // Menus, alerts, pickers and Spotlight register their area here; a touch that lands on one never
    // reaches the root lift detector (round 3: a long press on a menu row lifted the icon beneath).
    val inputShield = remember { OverlayInputShield() }
    // Round 7: the fold projection wraps the whole window (wallpaper through Spotlight) so the
    // tilting leaf is one optical image; the signal/state is shared with the layout below.
    val rootIsCover = launcherActivity.panelOrientation.panel.value == FoldPanel.COVER
    val foldTransition = rememberFoldTransition(!rootIsCover,
        enabled = launcherActivity.corePreferences.foldAnimationEnabled)
    Box(Modifier.fillMaxSize().testTag("launcher-root")
        .foldProjection(foldTransition, innerDisplay = !rootIsCover)
        .homeDragInput(drag,
        enabled = sheet.isEmpty() && !showFirstRun && selectedId == null && resizeSlot == null && !emptyMenuVisible &&
            removeAlert == null && pager.currentPage >= -1 && !spotlightOpen,
        page = pager.currentPage, eligiblePages = eligibleDragPages,
        grabArea = { region ->
            // Home and Dock icons grab only on their artwork and label, not across the whole cell.
            if (region.widgetId != null || (region.target !is DropTarget.Home && region.target !is DropTarget.Dock)) null
            else region.appId?.let { iconRegistry[it] }?.let { icon ->
                with(windowDensity) {
                    val labelPx = if (region.target is DropTarget.Dock || !state.labels) 0f else duoLabelHeight(fontScale).dp.toPx()
                    iconGrabArea(MenuBounds(icon.left, icon.top, icon.right, icon.bottom), labelPx, 6.dp.toPx())
                }.let { Rect(it.left, it.top, it.right, it.bottom) }
            }
        },
        onStart = {
            focus.clearFocus(); keyboard?.hide(); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            drag.settle = null
            drag.source?.let { source ->
                val rest = liftedRestRect(source)
                drag.ghostRest = rest
                drag.grab = drag.origin - rest.topLeft
                if (source.widgetId != null) {
                    // Copy the widget's pixels before the menu blur starts; the provider view
                    // itself cannot be drawn a second time above the blur or under the finger.
                    widgetMenuSnapshot = null
                    captureWindowRegion(launcherActivity, composeView, rest) { image ->
                        if (drag.source?.widgetId == source.widgetId) drag.snapshot = image
                        widgetMenuSnapshot = image
                    }
                    (source.target as? DropTarget.Widget)?.let { widget ->
                        if (!desktopEditing) { widgetSlot = widget.index; sheet = "widgetActions" }
                    }
                }
            }
            if (drag.source?.appId != null && drag.source?.folderId == null) {
                appMenuAnchor = drag.source?.bounds
                if (!desktopEditing) selectedId = drag.source?.appId
            }
            // Inside an open folder the menu stays in the folder (iOS); dragging closes it.
            if (drag.source?.folderId != null && !desktopEditing) folderMenuAppId = drag.source?.appId
        },
        onFinish = { cancelled -> finishDrag(cancelled) }, blocked = inputShield::blocks)) {
        CompositionLocalProvider(LocalOverlayInputShield provides inputShield) {
        // Home's single full-screen wallpaper draw (Core.16 drew it up to three times per frame).
        // The Dock, Spotlight, menus and alerts sample it as the bottom area of their backdrops;
        // the sources sit outside the zoom layer so sampled pixels match the zoomed wallpaper.
        // Core.35: the live dunes move with Apple's own camera; only a photo zooms with the hinge.
        val liveDunes = remember(LauncherBackgroundCache.revision.intValue) { liveDunesEnabled(launcherActivity) }
        DuneWallpaper(Modifier.fillMaxSize().hazeSource(glassBackdrop)
            .hazeSource(dockBackdrop, zIndex = WALLPAPER_BACKDROP_Z)
            .hazeSource(liveDesktopBackdrop, zIndex = WALLPAPER_BACKDROP_Z)
            .graphicsLayer {
                // The hinge zoom (Apple: the wallpaper reacts to the fold) multiplies the app-open zoom
                // and the unlock arrival's settle (UnlockArrivalMotion): one product, no effect overrides another.
                val zoom = homeWallpaperScale(appOpenTransform?.wallpaperScale ?: 1f, if (liveDunes) 1f else foldWallpaperZoom(foldTransition, !rootIsCover),
                    UnlockArrival.frame().wallpaperScale)
                scaleX = zoom; scaleY = zoom
            }.homeBackdropBlur {
            maxOf(24.dp.toPx() * maxOf(folderProgress.floatValue, appMenuBlur.floatValue, widgetMenuBlur.floatValue),
                // r12b-switcher: a swipe up from Home blurs the live Home itself (no frozen copy to hand over).
                SwitcherMotion.HOME_BLUR_DP.dp.toPx() * (appOpenTransform?.homeBlur ?: 0f),
                spreadEnds.wallpaperBlurDp(nativePager.currentPage + nativePager.currentPageOffsetFraction).dp.toPx(),
                SpotlightPullMath.homeBlurDp(spotlightPresence.homeEffect()).dp.toPx(),
                coverTodayBlurDp(coverTodayProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction, pagerPosture)).dp.toPx())
        }.drawWithContent {
            drawContent()
            // Cover Today View: the blurred wallpaper also darkens with the swipe (iOS).
            val today = coverTodayProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction, pagerPosture)
            if (today > 0f) drawRect(Color.Black, alpha = COVER_TODAY_DIM * today)
        })
        CompositionLocalProvider(LocalDuoGlassBackdrop provides glassBackdrop,
            LocalWidgetPlacementSession provides widgetSession, LocalHomeEditChrome provides editChrome,
            LocalTodayViewActions provides todayActions, LocalHomePageScrolls provides homePageScrolls,
            LocalWidgetAppOpener provides widgetAppOpener, LocalProviderWidgetTap provides providerWidgetTap) {
        BoxWithConstraints(Modifier.fillMaxSize().homeBackdropBlur {
            // r12b-switcher: the Home swipe's blur (see the wallpaper layer above).
            maxOf(24.dp.toPx() * folderProgress.floatValue, SwitcherMotion.HOME_BLUR_DP.dp.toPx() * (appOpenTransform?.homeBlur ?: 0f)) }
            .homeSwitcherLayer { appOpenTransform }) {
            val isCoverDisplay = launcherActivity.panelOrientation.panel.value == FoldPanel.COVER
            val landscape = maxWidth > maxHeight
            val sideDock = desktopSideDock(isCoverDisplay, landscape)
            // Round 22: icon size and row spacing are no longer adjustable: always the iPhone Duo defaults.
            val preset = LayoutPreset()
            val density = LocalDensity.current
            val contentHeight = maxHeight
            val inLibrary = pager.currentPage == visibleHomePages
            // iPhone Duo geometry: measured fractions of this panel (DuoHomeLayout.kt), moved only
            // where a real camera cutout intersects an element, and only by the minimum amount.
            // Steady while Home is on screen; an app that moved into the Dock or vanished leaves at once.
            val recentIds = rememberSettledRecentIds(if (launcherActivity.corePreferences.showRecentApps) recentDockIds(
                launcherActivity.recentApps.ids, appsById.keys, state.dock) else emptyList(), busy = { appOpenTransform != null })
                .filter { it in appsById && it !in state.dock && !AppPrivacy.isHidden(it) }
            val recent = recentIds.mapNotNull(appsById::get)
            val cutoutBoxes = with(density) { windowCutout.bounds.map {
                DuoBox(it.left.toDp().value, it.top.toDp().value, it.right.toDp().value, it.bottom.toDp().value)
            } }
            val labelHeightDp = duoLabelHeight(density.fontScale)
            val homeFrame = remember(isCoverDisplay, maxWidth, contentHeight, labelHeightDp, recent.size, cutoutBoxes, preset) {
                duoHomeFrame(duoHomePose(isCoverDisplay, maxWidth.value, contentHeight.value), maxWidth.value,
                    contentHeight.value, labelHeightDp, dockExtraSlots = recent.size)
                    .withDockPreference(preset).avoidingCutouts(cutoutBoxes)
            }
            val geometry = homeFrame.toHomeGeometry(preset, state.labels)
            val homeBottomSpaceDp = homeFrame.homeBottomSpace(geometry)
            val railGeometry = homeFrame.status.railGeometry(density.fontScale, density.density)
            // Jiggle chrome band; where the first row sits too close to the top (rotated cover) the
            // pages move down while jiggling instead of letting Edit/Done cover the − badges.
            val jiggleBand = remember(homeFrame, geometry) { homeFrame.jiggleBand(geometry) }
            val jiggleShiftPx = with(density) { jiggleBand.gridShift.dp.toPx() }
            val safeTopDp = with(density) { windowCutout.safeTopPx.toDp().value }
            SideEffect {
                resizeGridSizing = geometry.widgetGridSizing()
                ghostIconSizeDp = geometry.shortcutArtworkSize ?: homeIconArtworkSize(geometry.iconSize, geometry.iconSize)
                resizePitchX = with(density) { (geometry.gridWidth / GRID_COLUMNS).dp.toPx() } * editorScale
                resizePitchY = with(density) { minOf((geometry.widgetHeight + 18f) / 2f, geometry.rowHeight).dp.toPx() } * editorScale
                resizeTopPitch = with(density) { ((geometry.widgetHeight + 18f) / 2f).dp.toPx() } * editorScale
                resizeAppPitch = with(density) { geometry.rowHeight.dp.toPx() } * editorScale
            }
            LaunchedEffect(geometry.gridWidth, geometry.widgetHeight, geometry.rowHeight) { resizeSlot = null }
            SideEffect { expandedWorkspace = geometry.expanded }
            LaunchedEffect(geometry.expanded) {
                if (!geometry.expanded) {
                    val sessionTargetsLeading = widgetSession?.let { session ->
                        session.candidate?.page == -1 || session.targetIndex?.let(::homeCellPage) == -1
                    } == true
                    val savedTargetLeading = widgetTargetIndex != Int.MIN_VALUE && homeCellPage(widgetTargetIndex) == -1
                    if (sessionTargetsLeading || savedTargetLeading) {
                        widgetSession = null
                        widgetTargetIndex = Int.MIN_VALUE
                        widgetExactTarget = false
                        widgetPackage = null
                        widgetProfileSerial = null
                        widgetPlacementMessage = null
                        sheet = ""
                    }
                    val dragTouchesLeading = drag.source?.page == -1 ||
                        ((target as? DropTarget.Home)?.index?.let(::homeCellPage) == -1)
                    if (dragTouchesLeading) {
                        drag.clear()
                    }
                }
            }
            val windowHeightDp = maxHeight.value
            val windowWidth = maxWidth
            val coverLeadingInset = coverLeadingEdgeInset(
                isCoverDisplay, contentHeight.value, windowHeightDp)
            val coverPagerHeight = contentHeight + (coverLeadingInset * 2f).dp
            // Unfolded, pages sit at equal gaps (edge → grid → hinge → grid → Dock) and advance by one
            // grid + gap; the workspace ends where a third page's pane would start (it fades out there).
            val homeGrid = homeFrame.grid
            val pagerWidth = if (geometry.expanded) (2f * homeGrid.pageStride).dp else maxWidth
            val panelWidth = if (geometry.expanded) homeGrid.pageStride.dp else maxWidth
            val paneContentInset = homeGrid.cellLeft.dp
            val homeStride = if (geometry.expanded) panelWidth else maxWidth
            val bottomSpace = homeBottomSpaceDp.dp
            val workspaceMotion = remember(firstHome, visibleHomePages, pagerWidth, homeStride, density, spreadEnds.fullLibrary) {
                WorkspacePageMotion(firstHome, visibleHomePages, with(density) { (if (geometry.expanded) pagerWidth else maxWidth).toPx() }, with(density) { homeStride.toPx() },
                    // The full-screen App Library is one more page: its step moves the whole spread (SpreadEnds),
                    // at least a screen width so the page rests beyond the edge until it is pulled in.
                    libraryStride = with(density) { if (spreadEnds.fullLibrary) maxOf(2f * homeStride.toPx(), maxWidth.toPx()) else homeStride.toPx() })
            }
            val dockScroll = rememberScrollState()
            var downwardSwipeOpensSpotlight by remember { mutableStateOf(false) }
            var gestureOriginInRoot by remember { mutableStateOf(Offset.Zero) }
            var gestureOriginInWindow by remember { mutableStateOf(Offset.Zero) }
            val pagerInputEnabled = pager.currentPage in -firstHome..visibleHomePages && !drag.active &&
                widgetSession == null && resizeSlot == null && sheet.isEmpty() && !showFirstRun && selectedId == null &&
                openFolderId == null && emptyCellIndex == null &&
                launcherActivity.backups.preview == null && !launcherActivity.backups.pickerPending &&
                !launcherActivity.backgrounds.pickerPending && widgets.setupStatus == null &&
                widgets.reconfigureWidgetId == null && !spotlightOpen &&
                // An expanded App Library category is modal, like a Home folder: no paging under it.
                librarySearch.openCategory == null
            Box(Modifier.fillMaxSize().onGloballyPositioned {
                gestureOriginInRoot = it.boundsInRoot().topLeft
                gestureOriginInWindow = it.boundsInWindow().topLeft
            }.onePageGestures(
                nativePager,
                pageGestures,
                motion = workspaceMotion,
                enabled = pagerInputEnabled,
                // Positive IDs are provider-owned Android views. Leave their vertical
                // stream untouched so scrollable widgets retain native gesture handling.
                // A dock that is already scrolled also gets first use of a downward drag.
                canStartDownwardSwipe = { point ->
                    val topStrip = maxOf(48f, safeTopDp + 24f) * density.density
                    val inTopStrip = point.y <= topStrip
                    // iOS: the top edge pulls down Notification Center; anywhere else on a Home page
                    // pulls down Spotlight. Today View and the App Library keep their own vertical
                    // scrolling, so they never start Spotlight.
                    // A rotated-cover page that scrolls (portrait fallback) and is scrolled down
                    // scrolls back up first; only from its top does the swipe open Spotlight.
                    val pageScrolled = (homePageScrolls[pager.currentPage]?.value ?: 0) > 0
                    val spotlightPage = !pageScrolled && pager.currentPage in 0 until homePages &&
                        spreadEnds.todayProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction) < .5f && !startsInExpandedLeadingPane(
                        geometry.expanded && leadingVisible && nativePager.currentPage == 0, point.x,
                        with(density) { panelWidth.toPx() }, nativePager.currentPageOffsetFraction * with(density) { panelWidth.toPx() })
                    downwardSwipeOpensSpotlight = !inTopStrip
                    if (desktopEditing || pager.currentPage !in -firstHome until visibleHomePages ||
                        (!inTopStrip && !spotlightPage)) false else {
                        val region = drag.hit(point + gestureOriginInRoot, eligibleDragPages)
                        val rootOnScreen = IntArray(2).also(launcherRootView::getLocationOnScreen)
                        val screenPoint = point + gestureOriginInWindow +
                            Offset(rootOnScreen[0].toFloat(), rootOnScreen[1].toFloat())
                        !(region?.target is DropTarget.Dock && dockScroll.value > 0) &&
                            !nativeWidgetConsumesVerticalGesture(launcherRootView, screenPoint)
                    }
                },
                onDownwardSwipe = { panel ->
                    if (downwardSwipeOpensSpotlight) openSearch(null) else launcherActivity.openSystemShade(panel)
                },
                // iOS: Spotlight comes down with the finger.
                onDownwardDrag = { _ ->
                    if (!downwardSwipeOpensSpotlight) null
                    else spotlightPresence.beginPull(SpotlightPullMath.travelPx(with(density) { contentHeight.toPx() },
                        density.density)) { openSearch(null) }
                },
                effects = workspaceEffects,
            ).pointerInput(pagerInputEnabled, desktopEditing) {
                // iOS: a tap on the bare Home Screen background ends jiggle mode.
                if (pagerInputEnabled) detectStationaryHomeTaps(onTap = { if (desktopEditing) editChrome.onTapEmpty() }, onLongPress = {
                    if (!desktopEditing && pager.currentPage < visibleHomePages)
                        emptyCellIndex = homeCellIndex(pager.currentPage.coerceIn(-1, homePages - 1), 0)
                })
            }) {
            // Item context menus blur Home (wallpaper, icons, Dock, status) behind the lifted item.
            Box(Modifier.fillMaxSize().testTag("editor-live-desktop").hazeSource(liveDesktopBackdrop)
                .homeBackdropBlur { maxOf(22.dp.toPx() * maxOf(appMenuBlur.floatValue, widgetMenuBlur.floatValue),
                    spreadEnds.todayBlurDp(nativePager.currentPage + nativePager.currentPageOffsetFraction).dp.toPx(),
                    SpotlightPullMath.homeBlurDp(spotlightPresence.homeEffect()).dp.toPx()) }
                .graphicsLayer {
                    // Home recedes a little behind Spotlight, in step with its blur.
                    val s = if (reduceMotion) 1f else SpotlightPullMath.homeScale(spotlightPresence.homeEffect())
                    scaleX = s; scaleY = s
                }) {
            val pagerDescription = when {
                geometry.expanded && leadingVisible && nativePager.currentPage == 0 -> launcherFormat(R.string.ui_widget_and_home)
                pager.currentPage == visibleHomePages -> launcherText("All apps")
                geometry.expanded -> launcherFormat(R.string.ui_home_pages, nativePager.currentPage + if (leadingVisible) 0 else 1, nativePager.currentPage + if (leadingVisible) 1 else 2)
                pager.currentPage == -1 -> launcherText("Widgets")
                else -> launcherFormat(R.string.ui_home_page_count, pager.currentPage + 1, visibleHomePages)
            }
            val pagerModifier = Modifier.fillMaxWidth().then(
                if (coverLeadingInset > 0f) Modifier
                    .height(coverPagerHeight)
                else Modifier.fillMaxHeight()
            )
                .testTag("app-pager")
                .semantics { stateDescription = pagerDescription }
            // API 31–32 only: the cover's graphicsLayer fallback tilts this pane as one texture, so
            // while it tilts it needs its own opaque wallpaper. API 33+ projects at the launcher
            // root, where the single root wallpaper is already part of the image.
            val expandedNow = geometry.expanded
            val paneProjecting = remember(expandedNow, foldTransition) { {
                android.os.Build.VERSION.SDK_INT < 33 && !expandedNow && foldTransition.coverFold.value > .001f
            } }
            Box(Modifier.fillMaxSize().foldCoverPane(
                progress = foldTransition.coverFold,
                enabled = { !geometry.expanded },
            )) {
            CompositionLocalProvider(LocalDuoGlassBackdrop provides glassBackdrop) {
            // The strip's panes; iPhone Duo's App Library pane is drawn a second time, alone, above the status
            // rail and the Dock (r21, owner: "它的层级应该是在右边Bar的层级之上").
            val workspaceLayer: @Composable (WorkspaceLayer) -> Unit = { layer ->
                ExpandedWorkspace(
                    layer = layer,
                    nativePager = nativePager, motion = workspaceMotion, effects = workspaceEffects,
                    visibleHomePages = visibleHomePages, panelWidth = if (geometry.expanded) panelWidth else windowWidth,
                    paneContentInset = paneContentInset, wallpaperWidth = windowWidth,
                    contentHeight = contentHeight, bottomSpace = bottomSpace, geometry = geometry,
                    foldProgress = foldTransition.reveal,
                    state = state, deviceStatus = deviceStatus,
                    previewSlots = previewLayout.slots, previewLeadingSlots = previewLayout.leadingSlots,
                    previewWidgetPlacements = previewLayout.widgetPlacements, appsById = appsById,
                    widgets = widgets, drag = drag, target = target, insertionTarget = insertionTarget,
                    librarySearch = librarySearch, ends = spreadEnds, libraryRecentOrder = libraryRecentOrder.value,
                    onLaunch = onLaunch, onLaunchFrom = onLaunchFrom, onPinned = model::setPinned,
                    onTurnOnWork = { model.turnOnWork(it) },
                    onActions = { selectedId = it.id }, onWidget = { widgetSlot = it; sheet = "widgetActions" },
                    onFolder = { openFolderId = it },
                    onEmptyWidget = { emptyCellIndex = it },
                    onRefresh = model::refresh,
                )
            }
            // Record moving artwork before stationary chrome; the root wallpaper is this backdrop's
            // bottom area. A separate source keeps the Dock from sampling its own icons/material.
            Box(Modifier.fillMaxSize().clipToBounds().measuredHazeSource(dockBackdrop)) {
                DuneWallpaper(Modifier.fillMaxSize().drawWithContent { if (paneProjecting()) drawContent() })
                // Only the physical screen clips animated pages. Resting grids reserve
                // the Dock column; page artwork can cross that column during a swipe.
                HorizontalPager(nativePager, pagerModifier, userScrollEnabled = false,
                    overscrollEffect = null, key = { it }) { }
                // An expanded App Library category closes on a tap anywhere outside it, also beside the
                // cover's library pane (the side column), above the (inert) pager but beneath the pages so the library keeps its own taps.
                if (librarySearch.openCategory != null) Box(Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { librarySearch.openCategory = null } })
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        translationY = jiggleShiftPx * editIntensity.value
                    }.unlockArrivalContent()) {
                    workspaceLayer(if (spreadEnds.duoChrome) WorkspaceLayer.PAGES else WorkspaceLayer.ALL)
                    } // Jiggle shift.
            }
            // r13: the status rail, the Dock, the page dots and Search belong to the last Home page and slide out
            // with it into the App Library by the very pager offset its icons move (SpreadEnds.libraryChromeShift),
            // and back in with it -- one sheet, no cross-fade.
            val chromeOffset = remember(workspaceMotion) { workspaceMotion::offset }
            val libraryChromeShift: () -> Float = {
                spreadEnds.libraryChromeShift(nativePager.currentPage + nativePager.currentPageOffsetFraction, chromeOffset)
            }
            val libraryChromeGone by remember(nativePager, spreadEnds, visibleHomePages) { derivedStateOf {
                if (spreadEnds.libraryVeil) spreadEnds.libraryProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction) > .999f
                else pager.currentPage == visibleHomePages
            } }
            val status = homeFrame.status
            // The App Library and the status rail are never on screen together at rest (owner, r13): the rail has
            // left with the last Home page and is not composed while the library is fully in.
            // iPhone Duo keeps the rail over its App Library (r21, 2.webp / 3.webp: SpreadEnds.duoChrome).
            if (state.verticalStatus && sheet.isEmpty() && !showFirstRun &&
                !(spreadEnds.libraryVeil && !spreadEnds.duoChrome && libraryChromeGone)) {
                // Time + ring centred on the control column at Apple's heights (cover 12.8/17.9 %H,
                // unfolded 6.6/12.3 %H, open portrait 4.3/8.1 %H in the top-right corner).
                val railWidth = if (status.horizontal)
                    status.ringBox.right - status.timeBox.left else maxOf(status.timeBox.width, status.ringDiameter)
                val railLeft = if (status.horizontal) status.timeBox.left else status.timeCenterX - railWidth / 2f
                val hideWhileJiggling = jiggleHidesStatus(homeFrame.pose, status.horizontal)
                StatusRail(deviceStatus,
                    Modifier.align(Alignment.TopStart)
                        .offset(x = railLeft.dp, y = (status.railTop + coverLeadingInset).dp)
                        .width(railWidth.dp)
                        // iPhone hides the status bar in jiggle mode; Edit/Done take its place.
                        .graphicsLayer {
                            if (hideWhileJiggling) alpha = 1f - editIntensity.value.coerceIn(0f, 1f)
                            translationX = libraryChromeShift()
                        }
                        // Unlock arrival (r11-lock): the rail fades in at once on the first Home frame.
                        .unlockArrivalStatus(),
                    iconSize = status.ringDiameter.dp, geometry = railGeometry, horizontal = status.horizontal)
            }
            val dockFrame = homeFrame.dock
            // Only the recent apps the fitted Dock has room for (none on the rotated cover; DuoHomeLayout).
            val dockRecent = recent.take((dockFrame.slots - 4).coerceAtLeast(0))
            // Concentric with the icons it holds (icon corner + inset), not a capsule.
            val dockShape = RoundedCornerShape(dockFrame.cornerRadius.dp)
            // Snaps when the pose/window/cutouts change (rotation, fold): the Dock must not glide from
            // its side-column height to the bottom bar. Preference changes still glide.
            val dockY by animatePlacementAsState(dockFrame.box.top,
                listOf(homeFrame.pose, windowWidth, windowHeightDp, cutoutBoxes, dockFrame.horizontal), LauncherMotion.settle)
            val dockCellInset = ((dockFrame.pitch - dockFrame.icon) / 2f).coerceAtLeast(0f)
            val dockHiddenByLibrary by remember(nativePager, spreadEnds) { derivedStateOf {
                spreadEnds.libraryProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction) > .5f } }
            if (!dockFrame.horizontal) {
                Surface(Modifier.align(Alignment.TopStart)
                    .offset(x = dockFrame.box.left.dp, y = (dockY + coverLeadingInset).dp)
                    .width(dockFrame.box.width.dp).height(dockFrame.box.height.dp)
                    .unlockArrivalChrome { Offset(dockFrame.box.left + dockFrame.box.width / 2f - windowWidth.value / 2f,
                        dockY + coverLeadingInset + dockFrame.box.height / 2f - windowHeightDp / 2f) }
                    // Before the glass, so the material moves with the icons.
                    .libraryChromeCarry(libraryChromeShift, hidden = dockHiddenByLibrary)
                    // iPhone Duo: the Dock stays where it is and blurs under the App Library (r21, 2.webp).
                    .dockLibraryBlur { spreadEnds.dockBlurDp(nativePager.currentPage + nativePager.currentPageOffsetFraction).dp.toPx() }
                    .duoGlass(DuoGlassRole.Dock, dockShape, backdropState = dockBackdrop).testTag("dock"),
                    shape = dockShape, color = Color.Transparent, contentColor = duoGlassContentColor(DuoGlassRole.Dock)) {
                    CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.DOCK) {
                    Column(Modifier.verticalScroll(dockScroll)
                        .padding(vertical = (dockFrame.padding - dockCellInset).coerceAtLeast(0f).dp)) {
                        DockAppColumn(state.dock, previewLayout.dock, appsById, dockFrame.pitch,
                            dockFrame.icon, drag, insertionTarget,
                            onLaunch = onLaunchFrom, onChoose = { dockSlot = it; sheet = "dock" },
                            outerPadding = (dockFrame.padding - dockCellInset).coerceAtLeast(0f))
                        if (dockRecent.isNotEmpty()) RecentDockApps(dockRecent, false, dockFrame.pitch, onLaunchFrom,
                            iconSize = dockFrame.icon)
                    }
                    }
                }
            } else {
                Surface(Modifier.align(Alignment.TopStart)
                    .offset(x = dockFrame.box.left.dp, y = dockY.dp)
                    .width(dockFrame.box.width.dp).height(dockFrame.box.height.dp)
                    .unlockArrivalChrome { Offset(dockFrame.box.left + dockFrame.box.width / 2f - windowWidth.value / 2f,
                        dockY + dockFrame.box.height / 2f - windowHeightDp / 2f) }
                    // The bottom bar leaves with the last Home page too (r13), the glass moving with its icons.
                    .libraryChromeCarry(libraryChromeShift, hidden = dockHiddenByLibrary)
                    .dockLibraryBlur { spreadEnds.dockBlurDp(nativePager.currentPage + nativePager.currentPageOffsetFraction).dp.toPx() }
                    .duoGlass(DuoGlassRole.Dock, dockShape, backdropState = dockBackdrop).testTag("dock"),
                    shape = dockShape, color = Color.Transparent) {
                    // Dock icons stand under the grid's four columns, as on iPhone, even if the glass is trimmed.
                    val contentWidth = dockFrame.pitch * 4 + if (dockRecent.isEmpty()) 0f else dockRecent.size * dockFrame.pitch + 12f
                    CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.DOCK) {
                    Row(Modifier.fillMaxHeight().offset(x = (dockFrame.contentCenterX - dockFrame.box.left - contentWidth / 2f).dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width((dockFrame.pitch * 4).dp)) {
                            DockAppColumn(state.dock, previewLayout.dock, appsById,
                                dockFrame.pitch, dockFrame.icon, drag, insertionTarget,
                                onLaunch = onLaunchFrom, onChoose = { dockSlot = it; sheet = "dock" }, horizontal = true,
                                barHeight = dockFrame.box.height)
                        }
                        if (dockRecent.isNotEmpty()) RecentDockApps(dockRecent, true, dockFrame.pitch, onLaunchFrom,
                            iconSize = dockFrame.icon)
                    }
                    }
                }
            }
            val pageNavigationProgress by remember(nativePager, firstHome, geometry.expanded) {
                derivedStateOf {
                    coverPageNavigationProgress(
                        geometry.expanded,
                        firstHome,
                        nativePager.currentPage + nativePager.currentPageOffsetFraction,
                    )
                }
            }
            val pageNavigationVisible by remember(nativePager, firstHome, geometry.expanded) {
                derivedStateOf {
                    showPageNavigation(
                        geometry.expanded,
                        firstHome,
                        nativePager.settledPage,
                        nativePager.isScrollInProgress,
                    )
                }
            }
            val homeStridePx = with(density) { homeStride.toPx() }
            val dots = homeFrame.dots
            // Cover: centred under the grid and carried along with Home 1 toward the Today page.
            // Unfolded: centred under BOTH grids (≈45.5 %W), never under the right page only.
            // On the way into the App Library the dots (like the Search button below) leave with the last Home
            // page and are dropped once the library is fully in (r13, SpreadEnds.libraryChromeShift).
            if (pageNavigationVisible && !libraryChromeGone) Row(Modifier.align(Alignment.TopStart)
                .centeredAt(dots.centerX.dp, (dots.centerY + coverLeadingInset).dp)
                .graphicsLayer {
                    translationX = (if (geometry.expanded) 0f else homeStridePx * pageNavigationProgress) + libraryChromeShift()
                    alpha = spreadEnds.chromeAlpha(nativePager.currentPage + nativePager.currentPageOffsetFraction)
                }
                .testTag("page-navigation")
                // In jiggle mode the dots sit in a glass pill, as on iOS.
                .then(if (desktopEditing) Modifier.duoGlass(DuoGlassRole.Floating, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag("page-navigation-pill") else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((dots.pitch - dots.dot).dp)) {
                repeat(homePages) { index ->
                    Box(Modifier.size(dots.dot.dp).testTag("home-page-dot-$index")
                        .semantics { contentDescription = launcherFormat(R.string.ui_home_page, index + 1) }
                        // Unfolded, both pages of the spread in view are lit (the right half and the one beside it).
                        .background(Color.White.copy(alpha = if (if (!geometry.expanded) pager.currentPage == index
                            else pagerPosture.anchorPage(nativePager.currentPage).let { index == it || index == it - 1 })
                            1f else .38f), CircleShape))
                }
            }
            if (!libraryChromeGone && !drag.active && !desktopEditing) {
                val search = homeFrame.search
                val controlSize = search.width.dp
                // iOS Today View has no "back to Home" control; swiping right-to-left returns.
                val searchBounds = remember { android.graphics.Rect() }
                // Search: Ø ≈ 10.3 %W glass circle at (89.6 %W, 92.9 %H) on the cover, (94.9 %W, 92.7 %H) unfolded.
                Box(Modifier.align(Alignment.TopStart)
                    .centeredAt(search.centerX.dp, (search.centerY + coverLeadingInset).dp)
                    .unlockArrivalChrome { Offset(search.centerX - windowWidth.value / 2f,
                        search.centerY + coverLeadingInset - windowHeightDp / 2f) }
                    .graphicsLayer {
                        translationX = libraryChromeShift()
                        alpha = spreadEnds.chromeAlpha(nativePager.currentPage + nativePager.currentPageOffsetFraction)
                    }
                    .onGloballyPositioned { searchBounds.set(it.boundsInWindow().toAndroidBounds()) }) {
                    // Samples the pages like the Dock, so icons scrolling beneath it bend through it (owner, r13).
                    CircleControl(IosSymbols.magnifyingGlass, androidx.compose.ui.res.stringResource(R.string.search_system), "search", controlSize,
                        backdrop = dockBackdrop) {
                        openSearch(searchBounds)
                    }
                }
            }
            // iPhone Duo (r21): the App Library slides in OVER the status rail and the blurred Dock, on the same strip.
            if (spreadEnds.duoChrome) Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = jiggleShiftPx * editIntensity.value
            }.unlockArrivalContent()) { workspaceLayer(WorkspaceLayer.LIBRARY) }
            } // Pane backdrop provider.
            } // Cover projection.
            } // Live desktop only: modal windows and drop feedback remain in root coordinates.
            // iPadOS Today View (5.webp / 9.webp): the widget column over the blurred, dimmed Home. Composed
            // while it is within the workspace's composition reach, so a swipe never builds its widgets.
            val todayNear by remember(nativePager, spreadEnds) { derivedStateOf {
                spreadEnds.todayOverlay && (nativePager.settledPage <= 2 || nativePager.targetPage <= 1 || nativePager.currentPage <= 1)
            } }
            if (todayNear && geometry.expanded) TodayOverlayLayer(
                progress = { spreadEnds.todayProgress(nativePager.currentPage + nativePager.currentPageOffsetFraction) },
                travelPx = with(density) { (paneContentInset + (geometry.gridWidth + geometry.pageGutter + PANE_DRAW_MARGIN_DP).dp).toPx() },
                overscroll = { workspaceEffects.overscroll },
                workspaceAlpha = { workspaceEffects.alpha },
                modifier = Modifier.graphicsLayer { translationY = jiggleShiftPx * editIntensity.value }.unlockArrivalContent(),
            ) {
                Box(Modifier.offset(x = paneContentInset).width((geometry.gridWidth + geometry.pageGutter).dp).fillMaxHeight()
                    .testTag("expanded-leading-home")) {
                    CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.TODAY_OVERLAY) {
                    HomePagePane(-1, state, previewLayout.slots, previewLayout.leadingSlots, previewLayout.widgetPlacements,
                        appsById, geometry, contentHeight, bottomSpace, widgets, drag, target, insertionTarget,
                        showLargeWidget = true, deviceStatus = deviceStatus, onLaunch = onLaunchFrom,
                        onActions = { selectedId = it.id }, onWidget = { widgetSlot = it; sheet = "widgetActions" },
                        onFolder = { openFolderId = it }, onEmptyWidget = { emptyCellIndex = it }, onRefresh = model::refresh)
                    }
                }
            }
            val cutoutRects = remember(windowCutout.bounds) {
                windowCutout.bounds.filterNot { it.width == 0 || it.height == 0 }
                    .map { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            }
            // Edit/Done stay put while icons are dragged (iOS); they only yield to menus and folders.
            if (editDecorated && sheet.isEmpty() && selectedId == null && resizeSlot == null && openFolderId == null) {
                val windowRightPx = with(density) { windowWidth.toPx() }
                JiggleModeChrome(grid = {
                    // The visible icon grid(s): Edit/Done stay within it, above its first row.
                    drag.regions.values.filter { region ->
                        region.target is DropTarget.Home && region.scope == null && region.page in eligibleDragPages &&
                            region.bounds.width > 0f && region.bounds.right > 0f && region.bounds.left < windowRightPx
                    }.map { it.bounds }.reduceOrNull { a, b ->
                        Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
                    }
                }, obstacles = cutoutRects, intensity = editIntensity,
                    band = with(density) {
                        JiggleBand(jiggleBand.top.dp.toPx(), jiggleBand.height.dp.toPx(), jiggleShiftPx)
                    },
                    // Round 13: in jiggle mode over the Today View (unfolded overlay) the widget goes to Today, not to
                    // the Home page underneath (found while testing the owner's Today "编辑" report).
                    onAddWidget = { if (todayOverlayIn) openWidgetPicker(-1) else openWidgetPicker() },
                    onWallpaper = { sheet = "settings:wallpaper" },
                    onSettings = { sheet = "settings" },
                    onDone = { desktopEditing = false })
            }
            // Spotlight samples the live desktop (wallpaper, pages, Dock) so Home blurs behind it.
            SpotlightOverlay(visible = spotlightOpen && sheet.isEmpty() && !showFirstRun,
                apps = remember(state.apps, AppPrivacy.hidden) { state.apps.withoutHidden() },
                recentIds = launcherActivity.recentApps.ids, wide = geometry.expanded, backdrop = liveDesktopBackdrop,
                history = spotlightHistory, onDismiss = { spotlightOpen = false },
                onLaunch = { app, bounds -> spotlightReturn = true; onLaunchFrom(app, bounds) },
                query = spotlightQuery, onQueryChange = { spotlightQuery = it }, autoFocus = !spotlightRestored,
                presence = spotlightPresence,
                onLeaveForApp = { spotlightReturn = true },
                onShareApp = ::shareApp, onAppInfo = onAppInfo, onHideApp = ::startHide,
                onLauncherAction = { action ->
                    when (action) {
                        "settings" -> sheet = "settings"
                        "wallpaper" -> sheet = "settings:wallpaper"
                        "widgets" -> if (todayOverlayIn) openWidgetPicker(-1) else openWidgetPicker()
                    }
                })
            CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
                IosContextMenuHost(
                    request = if (!(desktopEditing && emptyMenuVisible && sheet.isEmpty())) null else IosMenuRequest(
                        key = "jiggle-page-menu", anchor = emptyMenuAnchor,
                        sections = listOf(listOf(
                            IosMenuAction(uxText("添加小组件", "Add Widget"), IosSymbols.widgets, "jiggle-menu-widgets") {
                                emptyMenuVisible = false; if (todayOverlayIn) openWidgetPicker(-1) else openWidgetPicker()
                            },
                            IosMenuAction(uxText("编辑墙纸", "Edit Wallpaper"), IosSymbols.photo, "jiggle-menu-wallpaper") {
                                emptyMenuVisible = false; sheet = "settings:wallpaper"
                            },
                            IosMenuAction(uxText("设置", "Settings"), IosSymbols.gear, "jiggle-menu-settings") {
                                emptyMenuVisible = false; sheet = "settings"
                            },
                        )),
                        overlayTag = "jiggle-menu-overlay", menuTag = "jiggle-menu", width = 230.dp, blurBackground = false),
                    onDismissRequest = { emptyMenuVisible = false }, obstacles = cutoutRects)
            }
            BackHandler(enabled = sheet == "widgetActions") { sheet = "" }
            val widgetMenuPlacement = if (sheet == "widgetActions") model.placement(widgetSlot) else null
            if (sheet == "widgetActions" && widgetMenuPlacement == null) LaunchedEffect(widgetSlot) { sheet = "" }
            CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
                val placement = widgetMenuPlacement
                val widgetRequest = if (placement == null) null else {
                    val constraints = nativeWidgetSpanConstraints(placement.id)
                        ?: widgets.manager.getAppWidgetInfo(placement.id)?.let { widgets.sizing(it, geometry.widgetGridSizing()) }
                    val minWidth = constraints?.minimum?.width ?: 2
                    val minHeight = constraints?.minimum?.height ?: 2
                    val maxWidth = minOf(GRID_COLUMNS - placement.column, constraints?.maximum?.width ?: GRID_COLUMNS)
                    val maxHeight = minOf(widgetRowsForPage(placement.page) - placement.row, constraints?.maximum?.height ?: GRID_ROWS)
                    val canResize = placement.page >= -1 && placement.row in 0 until widgetRowsForPage(placement.page) &&
                        !(placement.id >= 0 && constraints == null) && minWidth <= maxWidth && minHeight <= maxHeight &&
                        (constraints == null || constraints.canResizeHorizontally || constraints.canResizeVertically)
                    val slot = widgetSlot
                    val snapshot = widgetMenuSnapshot
                    // iOS 18: the menu starts with the widget's sizes; the free-form "尺寸" row stays
                    // only for provider widgets, whose spans are not limited to Apple's three sizes.
                    val sizeChoices = if (placement.row in 0 until widgetRowsForPage(placement.page))
                        widgetSizeChoices(state.layout, slot, constraints) else emptyList()
                    val sizeHeader: (@Composable () -> Unit)? = if (sizeChoices.isEmpty()) null else { {
                        WidgetSizeRow(sizeChoices) { choice ->
                            // iOS: icons in the way flow on (or to a new page); recomputed on the current layout.
                            if (choice.enabled && !choice.selected) model.resizeWidgetReflowing(slot, choice.size)
                            sheet = ""
                        }
                    } }
                    val widgetAnchor = drag.regions[DropTarget.Widget(slot)]?.bounds
                    // The copy is clipped to the card's own outline (continuous corners for 18Duo's widgets).
                    val previewShape = with(density) {
                        widgetSnapshotShape(placement.id, (widgetAnchor?.width ?: 0f).toDp().value, (widgetAnchor?.height ?: 0f).toDp().value)
                    }
                    IosMenuRequest(key = "widget-$slot", anchor = widgetAnchor,
                        header = sizeHeader, headerHeight = WIDGET_SIZE_ROW_HEIGHT,
                        sections = widgetContextSections(canResize = canResize,
                            canConfigure = widgets.canReconfigure(placement.id) || isWeatherWidgetId(placement.id),
                            onResize = {
                                resizeSlot = slot; resizeWidth = placement.spanX; resizeHeight = placement.spanY
                                resizeConstraints = constraints; sheet = ""
                            }, onConfigure = {
                                // Weather widgets: "Edit Widget" opens their location prompt (r3-widgets-wb).
                                if (isWeatherWidgetId(placement.id)) WeatherSetupController.open() else widgets.reconfigure(placement.id)
                                sheet = ""
                            },
                            onReplace = {
                                widgetPackage = null
                                widgetProfileSerial = widgets.manager.getAppWidgetInfo(placement.id)?.profile?.let {
                                    launcherActivity.getSystemService(UserManager::class.java).getSerialNumberForUser(it)
                                }?.takeIf { it >= 0 }
                                widgetExactTarget = false; sheet = "widgets"
                            }, onRemove = { sheet = ""; confirmRemoveWidget(slot) },
                            onEditHome = { sheet = ""; desktopEditing = true }).map { section ->
                            section.filterNot { sizeHeader != null && isNativeWidgetId(placement.id) && it.tag == "widget-context-resize" }
                        },
                        overlayTag = "widget-context-overlay", menuTag = "widget-context-menu",
                        preview = snapshot?.let { image -> {
                            Image(image, null, Modifier.fillMaxSize().clip(previewShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                        } }, previewShape = previewShape)
                }
                IosContextMenuHost(widgetRequest, onDismissRequest = { sheet = "" },
                    onProgress = { widgetMenuBlur.floatValue = it }, obstacles = cutoutRects, hidePreview = drag.moved)
            }
            if (sheet.isNotEmpty() && sheet != "widgets" && sheet != "widgetActions") {
                val activeCustomizationPage = if (sheet == "settings:wallpaper") CustomizationPage.WALLPAPER else customizationPage
                LauncherPanel(onDismiss = {
                    customizationPage = CustomizationPage.OVERVIEW
                    sheet = ""; widgetPackage = null; widgetExactTarget = false
                }) {
                    ModalDialogBackHandler {
                        if ((sheet == "settings" || sheet == "settings:wallpaper") &&
                            activeCustomizationPage != CustomizationPage.OVERVIEW) {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = "settings"
                        } else {
                            customizationPage = CustomizationPage.OVERVIEW
                            sheet = ""; widgetPackage = null; widgetExactTarget = false
                        }
                    }
                    when (sheet) {
                        "dock" -> DockAppPicker(state.apps.withoutHidden(), dockSlot,
                            onSelect = {
                                if (canPlaceInDock(state.layout, it.id)) {
                                    model.applyDrop(it.id, DropTarget.Dock(dockSlot)); sheet = ""
                                }
                            },
                            onClear = { model.removePlacement(DropTarget.Dock(dockSlot)) },
                            onLongClick = { selectedId = it.id; sheet = "" },
                            canSelect = { canPlaceInDock(state.layout, it.id) },
                            blockedHint = if (state.dock.none { it == null }) launcherText("Dock full • Move an app out first") else null)
                        "pins" -> Column(Modifier.fillMaxHeight(.9f).imePadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                                IosGlassCircleButton(IosSymbols.checkmark, launcherText("Done"), { sheet = "" }, prominent = true)
                            }
                            AppLibrary(state, pinQuery, { pinQuery = it }, onLaunch, model::setPinned,
                                onActions = { selectedId = it.id; sheet = "" }, editing = true, modifier = Modifier.weight(1f).fillMaxWidth(),
                                onTurnOnWork = { model.turnOnWork(it) })
                        }
                        "settings", "settings:wallpaper" -> SimpleLauncherSettings(state, model, isDefaultHome,
                            wallpaper = sheet == "settings:wallpaper",
                            onMakeDefault = { sheet = ""; onMakeDefault() },
                            onClose = { customizationPage = CustomizationPage.OVERVIEW; sheet = "" },
                            onWidgets = { widgetSlot = model.nextWidgetSlot(); widgetTargetIndex = homeCellIndex(pager.currentPage.coerceIn(-1, homePages - 1), 0); widgetPackage = null; widgetProfileSerial = null; widgetExactTarget = false; sheet = "widgets" },
                            onWallpaper = { sheet = "settings:wallpaper" },
                            onExport = { sheet = ""; launcherActivity.backups.startExport() },
                            onImport = { sheet = ""; launcherActivity.backups.startImport() },
                            onShadeSetup = { sheet = ""; onShadeSetup() },
                            backgrounds = launcherActivity.backgrounds,
                            onSystemWallpaper = { sheet = ""; onWallpaperPreview() })
                    }
                }
            }
            if (showFirstRun) {
                LauncherPanel(onDismiss = onFinishFirstRun, modifier = Modifier.testTag("first-run-setup")) {
                    ModalDialogBackHandler(onFinishFirstRun)
                    FirstRunSetupSheet(isDefaultHome = isDefaultHome, onMakeDefault = onMakeDefault, onSkip = onFinishFirstRun)
                }
            }
            if (sheet == "widgets") {
                val catalogProfiles = remember(state.profiles) { state.profiles.filter { it.isPersonal || it.isWork } }
                val selectedProfile = catalogProfiles.firstOrNull { it.userSerial == widgetProfileSerial }
                    ?: catalogProfiles.firstOrNull { it.isPersonal } ?: AppProfile(0, launcherText("Personal"), true, false, false, true, true)
                val userManager = remember(launcherActivity) { launcherActivity.getSystemService(UserManager::class.java) }
                val providers = remember(widgetPackage, selectedProfile, sheet, state.apps) {
                    val user = userManager.getUserForSerialNumber(selectedProfile.userSerial)
                    if (user == null || !selectedProfile.available || !selectedProfile.unlocked || selectedProfile.quiet) emptyList()
                    else runCatching { widgetPackage?.let { widgets.providersForPackage(it, user) }
                        ?: widgets.providers(user) }.getOrDefault(emptyList()).filter { provider ->
                        provider.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 &&
                            provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER == 0
                    }
                }
                val catalog by produceState<List<WidgetCatalogEntry>?>(null, providers, selectedProfile.userSerial, sheet) {
                    value = withContext(Dispatchers.IO) { widgetCatalog(launcherActivity, providers, selectedProfile) }
                }
                val topPitch = (geometry.widgetHeight + 18f) / 2f
                val pickerSizing = remember(geometry) { geometry.widgetGridSizing() }
                val footprint: (AppWidgetProviderInfo) -> WidgetSpan? = { provider ->
                    widgets.sizing(provider, pickerSizing)?.takeIf { it.minimumFitsGrid }?.preferred
                }
                /** Host widgets go through binding; 18Duo's own widgets are committed directly. */
                fun commitWidgetSession(session: WidgetPickerSession, draft: WidgetPlacement, contentSize: WidgetContentSize? = null) {
                    session.provider?.let { widgets.add(draft, it, pickerSizing, contentSize) }
                        ?: session.nativeId?.let { model.placeWidget(draft.copy(id = it)) }
                }
                fun beginWidgetPlacement(provider: AppWidgetProviderInfo?, nativeId: Int?, preferredSpan: WidgetSpan,
                    constraints: WidgetSpanConstraints?) {
                    val existing = model.placement(widgetSlot)
                    val span = existing?.let { placement ->
                        WidgetSpan(placement.spanX, placement.spanY).takeIf {
                            constraints != null && it.width in constraints.minimum.width..constraints.maximum.width &&
                                it.height in constraints.minimum.height..constraints.maximum.height
                        }
                    } ?: preferredSpan
                    val special = existing?.takeIf { it.page > 0 && it.row + it.spanY > GRID_ROWS }
                    if (special != null) {
                        widgetSession = WidgetPickerSession(provider, widgetSlot,
                            WidgetSpan(special.spanX, special.spanY), Offset.Zero,
                            dragging = false, candidate = special, nativeId = nativeId)
                        widgetPlacementMessage = null
                        scope.launch { pager.scrollToPage(special.page.coerceAtLeast(0).coerceAtMost(homePages - 1)) }
                        return
                    }
                    val requestedIndex = existing?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                        ?: widgetTargetIndex.takeUnless { it == Int.MIN_VALUE } ?: 0
                    val requestedPage = homeCellPage(requestedIndex).coerceIn(-1, homePages)
                    // The page asked for, then the Home pages, and Today View last and only while it is shown
                    // (a hidden Today page swallowed the widget; a free Home page lost to Today).
                    val availablePages = (0..homePages).toList() + listOfNotNull((-1).takeIf { leadingVisible })
                    val autoPages = (listOf(requestedPage) + availablePages.filter { it != requestedPage })
                    val freeIndex = if (existing != null || widgetExactTarget) requestedIndex.takeIf {
                        widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null
                    } else autoPages.asSequence().flatMap { page ->
                        (0 until widgetRowsForPage(page) * GRID_COLUMNS).asSequence().map { homeCellIndex(page, it) }
                    }.firstOrNull { widgetCandidate(state.layout, widgetSlot, it, span.width, span.height) != null }
                    val targetIndex = freeIndex ?: requestedIndex
                    // iOS "添加小组件" drops the widget straight into the first free space and leaves
                    // Home in jiggle mode; only a replacement or a full Home needs the placement step.
                    val immediate = if (existing == null && !widgetExactTarget && freeIndex != null)
                        widgetCandidate(state.layout, widgetSlot, freeIndex, span.width, span.height) else null
                    if (immediate != null) {
                        commitWidgetSession(WidgetPickerSession(provider, widgetSlot, span, Offset.Zero,
                            dragging = false, targetIndex = freeIndex, nativeId = nativeId), immediate)
                        widgetSession = null; widgetPlacementMessage = null; sheet = ""; widgetPackage = null
                        desktopEditing = true
                        scope.launch { pager.animateScrollToPage(immediate.page.coerceIn(-1, homePages - 1)) }
                        return
                    }
                    widgetSession = WidgetPickerSession(provider, widgetSlot, span, Offset.Zero,
                        dragging = false, targetIndex = targetIndex, nativeId = nativeId)
                    widgetPlacementMessage = if (freeIndex == null)
                        launcherText("There isn’t room for this size. Choose another page or move an item first.") else null
                    scope.launch { pager.scrollToPage(homeCellPage(targetIndex).coerceIn(-1, homePages)) }
                }
                fun beginWidgetDrag(provider: AppWidgetProviderInfo?, nativeId: Int?, span: WidgetSpan, point: Offset) {
                    widgetSession = WidgetPickerSession(provider, widgetSlot, span, point, dragging = true, nativeId = nativeId)
                    widgetPlacementMessage = null
                    val destination = widgetTargetIndex.takeUnless { it == Int.MIN_VALUE }
                        ?.let(::homeCellPage) ?: lastHomePage
                    scope.launch { pager.scrollToPage(destination.coerceIn(-1, homePages - 1)) }
                }
                VisualWidgetPicker(catalog, catalogProfiles.ifEmpty { listOf(selectedProfile) }, selectedProfile,
                    onSelectProfile = { widgetProfileSerial = it.userSerial; widgetPlacementMessage = null },
                    onTurnOnWork = { model.turnOnWork(it) }, hiddenForDrag = widgetSession != null,
                    footprint = footprint,
                    onBack = widgetPickerBack,
                    onTap = { provider -> footprint(provider)?.let { beginWidgetPlacement(provider, null, it, widgets.sizing(provider, pickerSizing)) } },
                    onDragStart = { provider, point -> footprint(provider)?.let { beginWidgetDrag(provider, null, it, point) } },
                    nativeEntries = if (widgetPackage == null) remember { nativeWidgetCatalog() } else emptyList(),
                    onTapNative = { entry -> beginWidgetPlacement(null, entry.id, entry.span, nativeWidgetSpanConstraints(entry.id)) },
                    onDragStartNative = { entry, point -> beginWidgetDrag(null, entry.id, entry.span, point) },
                    sizeOptions = { provider -> widgetSizeVariants(widgets.sizing(provider, pickerSizing)) },
                    onAddSized = { provider, span -> beginWidgetPlacement(provider, null, span, widgets.sizing(provider, pickerSizing)) },
                    onDragStartSized = { provider, span, point -> beginWidgetDrag(provider, null, span, point) },
                    apps = state.apps,
                    onDrag = { point -> widgetSession = widgetSession?.copy(pointer = point) },
                    onDrop = {
                        val session = widgetSession
                        // Resolve from the final pointer, not the previous rendered frame.
                        // A quick release may arrive before Compose has drawn its preview.
                        val finalCell = session?.let { selected -> drag.regions.values.firstOrNull {
                            it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(selected.pointer)
                        }?.target as? DropTarget.Home }
                        val finalDraft = session?.candidate ?: finalCell?.let { cell -> session?.let {
                            widgetCandidate(state.layout, it.slot, cell.index, it.span.width, it.span.height)
                        } }
                        if (session != null && finalDraft != null) {
                            commitWidgetSession(session, finalDraft)
                            widgetSession = null; sheet = ""; widgetPackage = null
                        } else {
                            leaveTemporaryWidgetPage(); widgetSession = null
                            widgetPlacementMessage = launcherText("There isn’t room there. Try another space or page.")
                        }
                    },
                    onCancelDrag = {
                        if (widgetSession != null) {
                            leaveTemporaryWidgetPage(); widgetSession = null
                        }
                    })
                widgetSession?.let { session ->
                    val placementDensity = LocalDensity.current
                    val sessionEntry = session.provider?.let { selected -> catalog?.firstOrNull {
                        it.provider.provider == selected.provider && it.provider.profile == selected.profile } }
                    // Legacy overflow replacements are locked to their existing view
                    // bounds and may begin below the canonical six-row grid. They have
                    // no Home-cell address; specialAnchor below is their visual anchor.
                    val candidateIndex = widgetDraft?.takeIf { session.candidate == null }
                        ?.let { homeCellIndex(it.page, it.row * GRID_COLUMNS + it.column) }
                    val visualIndex = candidateIndex ?: widgetRawTarget?.index ?: session.targetIndex
                    val specialAnchor = session.candidate?.let { drag.regions[DropTarget.Widget(session.slot)]?.bounds }
                    val anchor = specialAnchor ?: visualIndex?.let { drag.regions[DropTarget.Home(it)]?.bounds }
                    Box(Modifier.fillMaxSize().testTag("widget-placement-mode")
                        .then(if (!session.dragging && session.candidate == null) Modifier.pointerInput(session.slot, session.span) {
                            detectTapGestures { local ->
                                val point = local + drag.rootOrigin
                                val cell = drag.regions.values.firstOrNull {
                                    it.target is DropTarget.Home && it.page in eligibleDragPages && it.bounds.contains(point)
                                }?.target as? DropTarget.Home
                                cell?.let { widgetSession = session.copy(pointer = point, targetIndex = it.index) }
                            }
                        } else Modifier)) {
                        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
                            .testTag("widget-placement-toolbar"), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // iOS 26 toolbar: separate glass circles/capsules, no Material text buttons.
                            IosGlassCircleButton(IosSymbols.chevronLeft, launcherText("Back to widgets"), widgetPickerBack,
                                tag = "widget-placement-back")
                            if (session.candidate != null) Text(launcherText("Replace here"), color = Ink,
                                modifier = Modifier.testTag("widget-replacement-locked"))
                            val targetPage = homeCellPage(session.targetIndex ?: 0)
                            if (!session.dragging && session.candidate == null) IosGlassCircleButton(IosSymbols.chevronLeft,
                                launcherText("Previous home page"), enabled = targetPage > -1, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = targetPage - 1
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local.coerceAtMost(HOME_CELLS - 1)))
                                scope.launch { pager.animateScrollToPage(page) }
                            })
                            Text("${session.span.width} × ${session.span.height}", color = Color.White, fontWeight = FontWeight.SemiBold,
                                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .4f), Offset(0f, 1f), 3f)))
                            if (!session.dragging && session.candidate == null) IosGlassCircleButton(IosSymbols.chevronRight,
                                launcherText("Next home page"), enabled = targetPage < homePages, onClick = {
                                val local = homeCellLocal(session.targetIndex ?: 0)
                                val page = (targetPage + 1).coerceAtMost(homePages)
                                widgetSession = session.copy(targetIndex = homeCellIndex(page, local.coerceAtMost(HOME_CELLS - 1)))
                                scope.launch { pager.animateScrollToPage(page) }
                            })
                            if (!session.dragging) IosGlassCapsuleButton(launcherText("Place"), enabled = widgetDraft != null, prominent = true, onClick = {
                                widgetDraft?.let { draft ->
                                    val contentSize = specialAnchor?.let { bounds -> with(placementDensity) {
                                        WidgetContentSize(bounds.width.toDp().value / editorScale, bounds.height.toDp().value / editorScale)
                                    } }
                                    commitWidgetSession(session, draft, contentSize)
                                    widgetSession = null; sheet = ""; widgetPackage = null
                                }
                            }, tag = "widget-placement-apply")
                            IosGlassCircleButton(IosSymbols.xmark, launcherText("Cancel"),
                                { leaveTemporaryWidgetPage(); widgetSession = null; sheet = ""; widgetPackage = null },
                                tag = "widget-placement-cancel")
                        }
                        if (anchor != null) {
                            val density = LocalDensity.current
                            val cellWidthPx = with(density) { (geometry.gridWidth / GRID_COLUMNS).dp.toPx() } * editorScale
                            fun pickerRowTop(row: Int): Float = (if (row <= 2) row * with(density) { topPitch.dp.toPx() }
                                else with(density) { (geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight).dp.toPx() }) * editorScale
                            val candidateRow = homeCellLocal(visualIndex ?: 0) / GRID_COLUMNS
                            val previewWidth = specialAnchor?.let { with(density) { it.width.toDp() } }
                                ?: with(density) { (cellWidthPx * session.span.width - 10.dp.toPx() * editorScale).toDp() }
                            val previewHeight = specialAnchor?.let { with(density) { it.height.toDp() } }
                                ?: with(density) { (pickerRowTop(candidateRow + session.span.height) -
                                    pickerRowTop(candidateRow) - 18.dp.toPx() * editorScale).coerceAtLeast(48.dp.toPx() * editorScale).toDp() }
                            val previewX = if (specialAnchor != null) anchor.left
                                else anchor.left + with(density) { 5.dp.toPx() } * editorScale
                            Surface(Modifier.offset { IntOffset(previewX.roundToInt(), anchor.top.roundToInt()) }
                                .size(previewWidth, previewHeight).testTag("widget-placement-preview")
                                .semantics { stateDescription = if (widgetDraft != null) launcherText("Ready to place") else launcherText("No room here") },
                                color = if (widgetDraft != null) Glass.copy(alpha = .82f) else Color(0xFFE7B6B6).copy(alpha = .9f),
                                shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(3.dp,
                                    if (widgetDraft != null) Color.White else Color(0xFFFF6B6B))) {
                                Box(Modifier.fillMaxSize()) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    else if (session.nativeId != null) NativeWidget(session.nativeId,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    else Column(Modifier.align(Alignment.Center).padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(session.provider?.loadLabel(launcherActivity.packageManager)?.toString()
                                            ?: launcherText("Widgets"), color = Ink,
                                            textAlign = TextAlign.Center)
                                        Text("${session.span.width} × ${session.span.height}", color = Ink)
                                    }
                                    if (widgetDraft == null) Box(Modifier.matchParentSize()
                                        .background(Color(0xFFB83B3B).copy(alpha = .34f)), contentAlignment = Alignment.Center) {
                                        Text(launcherText("No room here"), color = Color.White, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        } else if (session.dragging) {
                            Surface(Modifier.offset { IntOffset((session.pointer.x - 90.dp.toPx()).roundToInt(),
                                (session.pointer.y - 60.dp.toPx()).roundToInt()) }.size(180.dp, 120.dp)
                                .testTag("widget-placement-preview").semantics { stateDescription = launcherText("No room here") },
                                color = Color(0xFFE7B6B6).copy(alpha = .9f), shape = RoundedCornerShape(24.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (sessionEntry != null) WidgetProviderPreview(sessionEntry, session.span,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    else if (session.nativeId != null) NativeWidget(session.nativeId,
                                        Modifier.fillMaxSize().padding(5.dp).clip(RoundedCornerShape(18.dp)))
                                    Box(Modifier.matchParentSize().background(Color(0xFFB83B3B).copy(alpha = .34f)),
                                        contentAlignment = Alignment.Center) { Text(launcherText("No room here"), color = Color.White) }
                                }
                            }
                        }
                    }
                }
                widgetPlacementMessage?.let { message ->
                    Text(message, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)
                        .duoGlass(DuoGlassRole.Floating, CircleShape).padding(horizontal = 20.dp, vertical = 12.dp),
                        color = duoGlassContentColor(DuoGlassRole.Floating), fontSize = 15.sp, textAlign = TextAlign.Center)
                }
            }
        }
        DisplayCutoutTouchGuards(windowCutout, Modifier.matchParentSize())
        resizeSlot?.let { slot ->
            val placement = model.placement(slot)
            val bounds = drag.regions[DropTarget.Widget(slot)]?.bounds
            if (placement != null && bounds != null) {
                val minW = resizeConstraints?.minimum?.width ?: 2
                val minH = resizeConstraints?.minimum?.height ?: 2
                val maxW = minOf(GRID_COLUMNS - placement.column, resizeConstraints?.maximum?.width ?: GRID_COLUMNS)
                val maxH = minOf(widgetRowsForPage(placement.page) - placement.row, resizeConstraints?.maximum?.height ?: GRID_ROWS)
                val feasible = placement.page >= -1 && placement.row in 0 until widgetRowsForPage(placement.page) &&
                    !(placement.id >= 0 && resizeConstraints == null) && minW <= maxW && minH <= maxH
                val candidate = resizeWidget(state.layout, slot, resizeWidth, resizeHeight)
                val valid = feasible && ((resizeWidth == placement.spanX && resizeHeight == placement.spanY) || candidate != state.layout)
                val widthPx = (bounds.width + (resizeWidth - placement.spanX) * resizePitchX).coerceAtLeast(resizePitchX)
                val density = LocalDensity.current
                fun resizeRowTop(row: Int) = if (row <= 2) row * resizeTopPitch else 2 * resizeTopPitch + (row - 2) * resizeAppPitch
                val heightPx = (resizeRowTop(placement.row + resizeHeight) - resizeRowTop(placement.row) -
                    with(density) { 18.dp.toPx() }).coerceAtLeast(resizePitchY)
                Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .size(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .background(Color.White.copy(alpha = .08f), RoundedCornerShape(24.dp))
                    .border(2.dp, if (valid) Color.White.copy(alpha = .9f) else DestructiveRed, RoundedCornerShape(24.dp))
                    .testTag("widget-resize-preview-$slot")) {
                    Box(Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(44.dp)
                        .background(if (valid) Color.White else Color(0xFFFF6B6B), CircleShape)
                        .testTag("widget-resize-handle-$slot")
                        .pointerInput(slot, resizeConstraints) {
                            var dx = 0f; var dy = 0f; var startWidth = resizeWidth; var startHeight = resizeHeight
                            detectDragGestures(onDragStart = {
                                dx = 0f; dy = 0f; startWidth = resizeWidth; startHeight = resizeHeight
                            }, onDrag = { change, amount ->
                                change.consume(); dx += amount.x; dy += amount.y
                                if (feasible && resizeConstraints?.canResizeHorizontally != false) {
                                    val raw = (startWidth + (dx / resizePitchX).roundToInt()).coerceIn(minW, maxW)
                                    // iOS-style widgets only come in small (2) or medium (4) widths.
                                    resizeWidth = if (isNativeWidgetId(placement.id)) nativeWidgetSnapSpanX(raw).coerceIn(minW, maxW) else raw
                                }
                                if (feasible && resizeConstraints?.canResizeVertically != false)
                                    resizeHeight = (startHeight + (dy / resizePitchY).roundToInt()).coerceIn(minH, maxH)
                            })
                        }, contentAlignment = Alignment.Center) {
                        Icon(IosSymbols.resize, launcherText("Drag to resize widget"), tint = Color(0xFF1C1C1E), modifier = Modifier.size(20.dp))
                    }
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JigglePill("widget-resize-cancel", { resizeSlot = null }, Modifier.height(34.dp)) {
                            JigglePillLabel(uxText("取消", "Cancel"), bold = false)
                        }
                        JigglePill("widget-resize-apply", {
                            if (valid) { model.resizeWidget(slot, resizeWidth, resizeHeight); resizeSlot = null }
                        }, Modifier.height(34.dp).alpha(if (valid) 1f else .45f)) { JigglePillLabel(uxText("完成", "Done")) }
                    }
                    if (!feasible) Text(launcherText("Move this widget into the Home Screen grid before resizing."),
                        color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f)).padding(8.dp))
                }
            }
        }
        val menuRequest = selectedId?.let { id ->
            val app = appsById[id]
            val folder = state.layout.folder(id)
            val parentFolder = state.folders.firstOrNull { id in it.appIds }
            val inDock = id in state.dock
            val onHome = state.layout.indexOfShortcut(id) != null
            val pinned = onHome || inDock || parentFolder != null
            if (app == null && folder == null) return@let null
            val close = { appMoveMenu = false; selectedId = null }
            // Lift the real icon (registered before its jiggle transform); a Library row has none.
            // A Home page kept composed off-screen (beside the App Library) does not count.
            val iconRect = iconRegistry[id]?.takeIf { rect ->
                (onHome || inDock) && rect.left >= -1f && rect.top >= -1f &&
                    rect.right <= windowSize.width + 1f && rect.bottom <= windowSize.height + 1f
            }
            val anchor = iconRect ?: appMenuAnchor ?: drag.regions.values.firstOrNull {
                it.appId == id && (it.target is DropTarget.Home || it.target is DropTarget.Dock)
            }?.bounds
            val iconDp = iconRect?.let { with(windowDensity) { it.width.toDp().value } } ?: ghostIconSizeDp
            val sections = if (folder != null) listOf(
                listOf(IosMenuAction(uxText("重新命名", "Rename"), IosSymbols.pencil, "context-primary") {
                    close(); renameFolderOnOpen = true; openFolderId = id
                }, IosMenuAction(uxText("编辑主屏幕", "Edit Home Screen"), IosSymbols.editHomeScreen, "context-edit-home") {
                    close(); desktopEditing = true
                }),
                listOf(IosMenuAction(uxText("移除文件夹", "Remove Folder"), IosSymbols.folderMinus, "context-remove",
                    destructive = true) { close(); confirmDissolveFolder(id) }),
            ) else if (app != null && AppPrivacy.isHidden(id)) listOf(
                // iOS: a hidden app's menu in the Hidden folder.
                listOf(IosMenuAction(uxText("添加到主屏幕", "Add to Home Screen"), IosSymbols.addToHome, "context-add-home") {
                    close(); releaseAuthentication(app, addToHome = true)
                }, IosMenuAction(AppPrivacy.releaseLabel(launcherActivity), IosSymbols.ownerAuthOff, "context-privacy") {
                    close(); releaseAuthentication(app)
                }),
                listOf(IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "context-primary") { onAppInfo(app); close() }),
            ) else if (!pinned) listOf(
                listOf(IosMenuAction(uxText("添加到主屏幕", "Add to Home Screen"), IosSymbols.addToHome, "context-add-home") {
                    model.setPinned(id, true); close()
                }),
                privacyMenuActions(app!!, close),
                listOf(
                    IosMenuAction(uxText("分享 App", "Share App"), IosSymbols.share, "context-share") { shareApp(app); close() },
                    IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "context-primary") { onAppInfo(app); close() },
                ),
            ) else listOf(
                app?.let { privacyMenuActions(it, close) }.orEmpty(),
                listOf(
                    IosMenuAction(uxText("分享 App", "Share App"), IosSymbols.share, "context-share") { shareApp(app!!); close() },
                    IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "context-primary") { onAppInfo(app!!); close() },
                ),
                // No "创建文件夹" here: iOS only makes a folder by dropping one app onto another.
                listOf(IosMenuAction(uxText("编辑主屏幕", "Edit Home Screen"), IosSymbols.editHomeScreen, "context-edit-home") {
                    close(); desktopEditing = true
                }),
                listOf(IosMenuAction(uxText("移除 App", "Remove App"), IosSymbols.minusCircle, "context-remove", destructive = true) {
                    close()
                    confirmRemoveApp(id) {
                        when {
                            parentFolder != null -> model.removeAppFromFolder(parentFolder.id, id, DropTarget.Remove)
                            inDock -> model.removePlacement(DropTarget.Dock(state.dock.indexOf(id)))
                            else -> model.setPinned(id, false)
                        }
                    }
                }),
            )
            val preview: (@Composable () -> Unit)? = if (iconRect == null) null else if (folder != null) {
                { FolderIconArt(folder, appsById, iconDp, Modifier.fillMaxSize()) }
            } else {
                { Image(app!!.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(homeIconShape(iconDp))) }
            }
            IosMenuRequest(key = "item-$id", anchor = anchor, sections = sections.filter { it.isNotEmpty() },
                overlayTag = "app-context-overlay", menuTag = "app-context-menu",
                preview = preview, previewShape = homeIconShape(iconDp), blurBackground = preview != null)
        }
        CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
            IosContextMenuHost(menuRequest, onDismissRequest = { appMoveMenu = false; selectedId = null },
                onProgress = { appMenuBlur.floatValue = it },
                obstacles = remember(windowCutout.bounds) {
                    windowCutout.bounds.filterNot { it.width == 0 || it.height == 0 }
                        .map { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                }, hidePreview = drag.moved)
        }
        launcherActivity.backups.preview?.let { preview ->
            LayoutRestorePreview(preview, onRestore = {
                launcherActivity.backups.applyImport(); sheet = ""
            }, onCancel = launcherActivity.backups::cancelImport)
        }
        LauncherSystemAlerts(launcherActivity, widgets, onLeaveTemporaryWidgetPage = { leaveTemporaryWidgetPage() })
        FoldProjectionEdges(
            progress = foldTransition.coverFold,
            side = FoldShadeSide.RIGHT,
            cover = true,
            enabled = { !geometry.expanded },
        )
        }
        val openFolder = openFolderId?.let { id -> state.folders.firstOrNull { it.id == id } }
        if (openFolderId != null && openFolder == null) LaunchedEffect(openFolderId) { openFolderId = null }
        // Always composed: the folder zooms out of its icon and, when closed, back into it.
        CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.FOLDER) {
        FolderPanelHost(openFolder, appsById, drag, pager.currentPage, editChrome, iconSize = ghostIconSizeDp,
            renameOnOpen = renameFolderOnOpen, menuAppId = folderMenuAppId, onMenuApp = { folderMenuAppId = it },
            onProgress = { folderProgress.floatValue = it },
            onDismiss = { openFolderId = null; renameFolderOnOpen = false; folderMenuAppId = null },
            onRename = { folderId, title -> model.renameFolder(folderId, title) },
            onLaunch = onLaunchFrom,
            onRemoveApp = { app -> openFolderId?.let { folderId ->
                confirmRemoveApp(app.id) { model.removeAppFromFolder(folderId, app.id, DropTarget.Remove) }
            } },
            onAppInfo = onAppInfo,
            onHideApp = { app -> folderMenuAppId = null; startHide(app) },
            onEditHome = { openFolderId = null; desktopEditing = true })
        }
        }
        // The lifted item and its settle; nothing else (no frames, edge bars or trash zone).
        HomeDragGhostLayer(drag, appsById, state.folders, iconRegistry,
            hideResting = !drag.moved && (selectedId != null || sheet == "widgetActions" || folderMenuAppId != null))
        CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
            IosAlertHost(removeAlert, onDismiss = { removeAlert = null })
        }
        CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) {
            HideAppSheetHost(hideSheetApp, onHide = { app ->
                hideSheetApp = null
                AppPrivacy.hide(launcherActivity, app.id)
                model.removeEverywhere(app.id)
            }, onDismiss = { hideSheetApp = null })
        }
        // Status messages that Android would toast drop in as an iOS glass banner instead.
        CompositionLocalProvider(LocalDuoGlassBackdrop provides liveDesktopBackdrop) { IosBannerHost(insidePanel = false) }
        AppOpenZoomOverlay { appOpenTransform }
        } // Overlay input shield.
    }
}

/** Bundle form of a pending [LaunchOrigin] (rememberSaveable). */
private val LaunchOriginSaver = androidx.compose.runtime.saveable.Saver<LaunchOrigin?, ArrayList<Any>>(
    save = { origin -> origin?.let { ArrayList(it.toList()) } },
    restore = { LaunchOrigin.fromList(it) },
)

/** Wallpaper areas sit below every other source of the Dock and live-desktop backdrops. */
private const val WALLPAPER_BACKDROP_Z = -1f

/**
 * Keep the wallpaper edge-to-edge while making the physical camera voids inert.
 * The platform bounds update with fold posture and rotation, so this also covers
 * non-Xiaomi devices without model-specific coordinates.
 */
@Composable
private fun DisplayCutoutTouchGuards(cutout: WindowCutoutSnapshot, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier) {
        cutout.bounds.filterNot { it.width == 0 || it.height == 0 }.forEachIndexed { index, rect ->
            Box(Modifier
                .offset { IntOffset(rect.left, rect.top) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .pointerInput(rect) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                }
                .testTag("cutout-touch-guard-$index")
                .semantics { contentDescription = launcherFormat(R.string.cutout_safety) })
        }
    }
}

@Composable
private fun ProjectedExpandedPane(
    modifier: Modifier,
    foldProgress: State<Float>,
    isLeft: () -> Boolean,
    wallpaperWidthPx: Float,
    viewportLeftPx: () -> Float,
    content: @Composable BoxScope.() -> Unit,
) {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        // Page/dock geometry is for layout only. The projection is rooted at the
        // full desktop above, so pagination cannot relocate the physical hinge.
        Box(modifier, content = content)
        return
    }
    val projectedBackdrop = rememberHazeState()
    val rootBackdrop = LocalDuoGlassBackdrop.current
    val projecting = isLeft() && foldProgress.value < .999f
    Box(modifier.clipToBounds()) {
        Box(Modifier.fillMaxSize().foldLeftPane(foldProgress, isLeft)) {
            // Ordinary paging and edge resistance move content over the stationary
            // root wallpaper. Only a folding pane needs wallpaper in its texture.
            if (projecting) DuneWallpaperViewport(
                fullWidthPx = wallpaperWidthPx,
                viewportLeftPx = viewportLeftPx,
                modifier = Modifier.fillMaxSize().clipToBounds().measuredHazeSource(projectedBackdrop),
            )
            // Sampling a source outside a non-affine RenderEffect makes Haze's
            // source coordinates disagree with the projected layer. Sample the
            // local, unprojected wallpaper first, then project the complete pane.
            CompositionLocalProvider(LocalDuoGlassBackdrop provides
                if (projecting) projectedBackdrop else rootBackdrop) { content() }
        }
        FoldProjectionEdges(
            progress = foldProgress,
            side = FoldShadeSide.LEFT,
            enabled = isLeft,
        )
    }
}

/** Which of the strip's panes one [ExpandedWorkspace] draws: all, the Home/Today panes, or the App Library alone. */
private enum class WorkspaceLayer { ALL, PAGES, LIBRARY }

@Composable
private fun ExpandedWorkspace(
    layer: WorkspaceLayer = WorkspaceLayer.ALL,
    nativePager: androidx.compose.foundation.pager.PagerState,
    motion: WorkspacePageMotion,
    effects: WorkspaceScrollEffects,
    visibleHomePages: Int,
    panelWidth: Dp,
    paneContentInset: Dp,
    wallpaperWidth: Dp,
    contentHeight: Dp,
    bottomSpace: Dp,
    geometry: HomeGeometry,
    foldProgress: State<Float>,
    state: LauncherState,
    deviceStatus: DeviceStatus,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    librarySearch: LibrarySearchSession,
    libraryRecentOrder: List<String>? = null,
    ends: SpreadEnds,
    onLaunch: (AppEntry) -> Unit,
    onLaunchFrom: (AppEntry, android.graphics.Rect?) -> Unit,
    onPinned: (String, Boolean) -> Unit,
    onTurnOnWork: (Long) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    val density = LocalDensity.current
    val stride = motion.homeStride
    val showWidgetPage = (androidx.activity.compose.LocalActivity.current as MainActivity).corePreferences
        .widgetPageVisible(state.leadingSlots.any { it != null } || state.widgetPlacements.any { it.page == -1 })
    val initialHomeOrigin = with(density) { ((if (geometry.expanded && showWidgetPage) panelWidth else 0.dp) + paneContentInset).toPx() }
    val homePaneWidth = with(density) { (geometry.gridWidth + geometry.pageGutter).dp.toPx() }
    // Unfolded pages advance by grid + gap, which is narrower than inset + grid: keep the whole grid inside its pane.
    val paneBoxWidth = maxOf(panelWidth, paneContentInset + (geometry.gridWidth + geometry.pageGutter).dp)
    val paneInsetPx = with(density) { paneContentInset.toPx() }
    val wallpaperWidthPx = with(density) { wallpaperWidth.toPx() }
    // Every pane is drawn opaque and moves rigidly with the finger; unfolded, the page after the
    // right-hand one waits just beyond the screen edge (see WorkspacePaneTrack, innerSpreadGaps).
    val track = remember(paneInsetPx, stride, geometry.expanded, homePaneWidth, wallpaperWidthPx, density) {
        WorkspacePaneTrack(restLeft = paneInsetPx, stride = stride, restingPanes = if (geometry.expanded) 2 else 1,
            paneWidth = homePaneWidth, screenWidth = wallpaperWidthPx, margin = with(density) { PANE_DRAW_MARGIN_DP.dp.toPx() })
    }
    val stateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val firstPane = if (showWidgetPage && panelWidth.value > 0f) -1 else 0
    // Logical panes: Today = -1, Home k = k − 1 ... App Library = visibleHomePages. Recomposes only
    // when these sets change (at most once per settled page), never per frame.
    fun panesWithin(reach: Int) = derivedStateOf(structuralEqualityPolicy()) {
        val pages = composedPhysicalPages(nativePager.settledPage, nativePager.targetPage,
            nativePager.currentPage, nativePager.pageCount, reach)
        track.panesSeenBetween(motion.offset(pages.first.toFloat()), motion.offset(pages.last.toFloat()),
            initialHomeOrigin, firstPane, visibleHomePages)
    }
    val neededPanes by remember(nativePager, motion, track, initialHomeOrigin, firstPane, visibleHomePages) { panesWithin(2) }
    val keptPanes by remember(nativePager, motion, track, initialHomeOrigin, firstPane, visibleHomePages) { panesWithin(3) }
    val previousPanes = remember(nativePager, firstPane, visibleHomePages) { arrayOf(emptyList<Int>()) }
    val composedPanes = retainedPanes(neededPanes, previousPanes[0], keptPanes)
    SideEffect { previousPanes[0] = composedPanes }
    /** Screen x of a pane's content: the rigid strip. Unfolded, Home stays put while Today View or the
     * App Library slides over it (iPadOS, [SpreadEnds]). */
    val paneScreenX: (Float) -> Float = { contentX ->
        track.screenX(contentX - motion.offset(ends.homePosition(nativePager.currentPage + nativePager.currentPageOffsetFraction)))
    }
    // Draw-phase only. A pane nobody can see is skipped and held still (no per-frame layer moves).
    // Round 22: whole pixels. Widgets and glass are clipped to continuous-corner paths, whose anti-aliased
    // masks Skia rasterises on the CPU and caches per sub-pixel offset: a fractional slide re-rasterised every
    // mask on every frame of a swipe (157 MB of masks on the phone). A whole-pixel slide reuses them.
    val place: Modifier.(Float, Float) -> Modifier = { contentX, plateInset ->
        graphicsLayer {
            val x = paneScreenX(contentX)
            val seen = track.onScreen(x - effects.overscroll)
            translationX = kotlin.math.round((if (seen) x else track.parkedX(x)) - plateInset)
            alpha = if (seen) 1f else 0f
        }
    }
    val leadingX = initialHomeOrigin - stride
    // Unfolded, Today View is LauncherScreen's TodayOverlayLayer above the blurred Home instead of a pane.
    val showLeading = -1 in composedPanes && !ends.todayOverlay && layer != WorkspaceLayer.LIBRARY
    val visibleHomes = if (layer == WorkspaceLayer.LIBRARY) emptyList() else composedPanes.filter { it in 0 until visibleHomePages }
    // The full-screen App Library is composed whenever its physical page is within the composition reach.
    val fullLibraryNear by remember(nativePager, ends) { derivedStateOf {
        ends.fullLibrary && ends.libraryPage in composedPhysicalPages(nativePager.settledPage, nativePager.targetPage,
            nativePager.currentPage, nativePager.pageCount)
    } }
    val showLibrary = layer != WorkspaceLayer.PAGES && if (ends.fullLibrary) fullLibraryNear else visibleHomePages in composedPanes

    Box(Modifier.fillMaxSize().testTag(if (layer == WorkspaceLayer.LIBRARY) "expanded-workspace-library" else "expanded-workspace")) {
    Box(Modifier.fillMaxSize()
        .graphicsLayer {
        // iOS rubber band / bounce and the Reduce Motion page dissolve, without recomposition.
        // Whole pixels: see `place` (round 22).
        translationX = -kotlin.math.round(effects.overscroll)
        alpha = effects.alpha
    }) {
        if (showLeading) {
            key("expanded-leading-home") {
                ProjectedExpandedPane(
                    modifier = Modifier.place(leadingX, paneInsetPx).width(paneBoxWidth).fillMaxHeight()
                        .testTag("expanded-leading-home"),
                    foldProgress = foldProgress,
                    isLeft = { true },
                    wallpaperWidthPx = wallpaperWidthPx,
                    viewportLeftPx = { paneScreenX(leadingX) - paneInsetPx },
                ) {
                    Box(Modifier.offset(x = paneContentInset).width((geometry.gridWidth + geometry.pageGutter).dp).fillMaxHeight()) {
                        HomePagePane(
                            -1, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                            widgets, drag, target, insertionTarget, showLargeWidget = true,
                            deviceStatus = deviceStatus,
                            onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                            onFolder = onFolder, onEmptyWidget = onEmptyWidget, onRefresh = onRefresh,
                            modifier = Modifier,
                        )
                    }
                }
            }
        }

        visibleHomes.forEach { page ->
            key("expanded-home-$page") {
                stateHolder.SaveableStateProvider("expanded-home-$page") {
                    val contentX = initialHomeOrigin + page * stride
                    val isLeft = {
                        val position = nativePager.currentPage + nativePager.currentPageOffsetFraction
                        contentX - motion.offset(position) < initialHomeOrigin - stride * .5f
                    }
                    ProjectedExpandedPane(
                        modifier = Modifier.place(contentX, paneInsetPx).width(paneBoxWidth).fillMaxHeight()
                            // iPhone Duo: the page left of the App Library stays in view, blurred (r21, 3.webp).
                            .homeBackdropBlur { ends.homePaneBlurDp(nativePager.currentPage + nativePager.currentPageOffsetFraction).dp.toPx() },
                        foldProgress = foldProgress,
                        isLeft = isLeft,
                        wallpaperWidthPx = wallpaperWidthPx,
                        viewportLeftPx = { paneScreenX(contentX) - paneInsetPx },
                    ) {
                        Box(Modifier.offset(x = paneContentInset).width((geometry.gridWidth + geometry.pageGutter).dp).fillMaxHeight()) {
                            HomePagePane(
                                page, state, previewSlots, previewLeadingSlots, previewWidgetPlacements, appsById, geometry, contentHeight, bottomSpace,
                                widgets, drag, target, insertionTarget, showLargeWidget = page > 0,
                                deviceStatus = deviceStatus,
                                onLaunch = onLaunchFrom, onActions = onActions, onWidget = onWidget,
                                onFolder = onFolder,
                                onEmptyWidget = onEmptyWidget,
                                onRefresh = onRefresh,
                            )
                        }
                    }
                }
            }
        }

        if (showLibrary && !ends.fullLibrary) {
            key("library-pane") {
                // All Apps follows the final home by exactly one pane. At the
                // terminal stop that home remains on the left and this list is right.
                // Round 13 (owner: "外屏时资源库没有居中"): on a single panel the library is a page of its own and the
                // Dock column has left with the last Home page, so it spans the whole screen, centred; beside a Home
                // page (unfolded spread) it keeps the pane's grid box.
                // r21: iPhone Duo's cover library stays in the grid's box beside the status rail and the blurred
                // Dock (2.webp), like the unfolded right pane (3.webp).
                val libraryAlone = ends.libraryVeil && !geometry.expanded && !ends.duoChrome
                Box(Modifier.place(initialHomeOrigin + visibleHomePages * stride - (if (libraryAlone) paneInsetPx else 0f), 0f)
                    .width(if (libraryAlone) wallpaperWidth else (geometry.gridWidth + geometry.pageGutter).dp).fillMaxHeight()) {
                    CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.LIBRARY) {
                    AppLibrary(state, librarySearch.query, { librarySearch.query = it }, onLaunch, onPinned,
                        onActions = onActions, modifier = Modifier.libraryHomeBounds(geometry, bottomSpace, edgeToEdge = ends.libraryVeil,
                            centred = libraryAlone),
                        drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = onTurnOnWork,
                        search = librarySearch, recentOrder = libraryRecentOrder,
                        safeTop = if (ends.libraryVeil) geometry.contentTop.dp else 0.dp,
                        homeBottom = if (ends.libraryVeil) bottomSpace + 8.dp else 0.dp)
                } }
            }
        }
    } // Home strip.
    if (showLibrary && ends.fullLibrary) {
        key("library-pane") {
            // iPadOS's full-screen App Library (8.webp) is one more page of the same rigid strip: it sits one
            // whole spread after the last Home stop and moves exactly with the finger while the Home spread
            // leaves to the left ([WorkspacePageMotion.libraryStride]). Like the next Home page it slides in
            // from beyond the screen edge, so the page and an opened category can use the whole screen.
            val libraryOffset = motion.offset(ends.libraryPage.toFloat())
            Box(Modifier.fillMaxSize()
                .graphicsLayer {
                val position = nativePager.currentPage + nativePager.currentPageOffsetFraction
                translationX = kotlin.math.round(fullLibraryShift(ends.libraryProgress(position), libraryOffset,
                    motion.offset(position), parked = wallpaperWidthPx + stride) - effects.overscroll)
                alpha = effects.alpha
            }.inertPointerTarget()) { CompositionLocalProvider(LocalHomeIconLayer provides IconLayer.LIBRARY) {
                AppLibrary(state, librarySearch.query, { librarySearch.query = it }, onLaunch, onPinned,
                    onActions = onActions, modifier = Modifier.fillMaxSize().testTag("library-page"),
                    drag = drag, page = visibleHomePages, onLaunchFrom = onLaunchFrom, onTurnOnWork = onTurnOnWork,
                    search = librarySearch, recentOrder = libraryRecentOrder)
            } }
        }
    }
    } // Expanded workspace.
}


/** Each Home page's own vertical scroll (portrait-fallback and Today pages), by logical page. */
private val LocalHomePageScrolls = staticCompositionLocalOf<MutableMap<Int, ScrollState>?> { null }

// Match HomePagePane's grid inset. Where the App Library is a page of its own ([SpreadEnds.libraryVeil]: the
// cover, the unfolded portrait) it is drawn edge to edge -- the categories scroll up under the status area and
// the floating search field and run off the bottom edge, as on iOS 26 -- while AppLibrary keeps its header at
// the Home grid's safe top (safeTop). Beside a Home page it stays inside that page's usable region.
private fun Modifier.libraryHomeBounds(geometry: HomeGeometry, bottomSpace: Dp, edgeToEdge: Boolean, centred: Boolean = false): Modifier =
    fillMaxSize().then(if (edgeToEdge) Modifier.padding(start = geometry.pageGutter.dp, end = if (centred) geometry.pageGutter.dp else 0.dp)
        else Modifier.padding(start = geometry.pageGutter.dp, top = geometry.contentTop.dp, bottom = bottomSpace + 8.dp))
        .testTag("library-page")

@Composable
private fun HomePagePane(
    page: Int,
    state: LauncherState,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    previewWidgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    baseGeometry: HomeGeometry,
    contentHeight: Dp,
    bottomSpace: Dp,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    insertionTarget: DropTarget?,
    showLargeWidget: Boolean,
    deviceStatus: DeviceStatus,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit = {},
    onRefresh: () -> Unit,
    contentTopInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val homeScroll = rememberScrollState()
    LocalHomePageScrolls.current?.let { scrolls ->
        DisposableEffect(scrolls, page, homeScroll) {
            scrolls[page] = homeScroll
            onDispose { if (scrolls[page] === homeScroll) scrolls.remove(page) }
        }
    }
    val editChrome = LocalHomeEditChrome.current
    val picker = LocalWidgetPlacementSession.current
    val geometry = baseGeometry
    // A rotated cover flows a page into six columns by four rows; a page whose widgets cannot flow
    // keeps its portrait grid and scrolls instead of clipping.
    val portraitFallback = geometry.columns != GRID_COLUMNS && page >= 0 &&
        landscapeFlow(previewWidgetPlacements.filter { it.page == page }, geometry.columns, LANDSCAPE_ROWS) == null
    val scrollablePage = page == -1 || portraitFallback
    val paneBottomSpace = bottomSpace
    LaunchedEffect(geometry.columns) { homeScroll.scrollTo(0) }
    // Plain holder, not state: the pane moves every frame while Home pages, and reading its
    // bounds in composition recomposed every visible page on every frame.
    val paneBounds = remember { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
    val pageStart = homeCellIndex(page, 0)
    val backgroundTarget = (pageStart until pageStart + HOME_CELLS).firstOrNull { index ->
        state.layout.slotAt(index) == null && state.widgetPlacements.none { index in it.coveredIndices() }
    } ?: pageStart
    val verticalEdge = with(LocalDensity.current) { 64.dp.toPx() }
    val verticalStep = with(LocalDensity.current) { 12.dp.toPx() }
    val pickerPointer = rememberUpdatedState(picker?.takeIf { it.dragging }?.pointer)
    val moving = drag.active || picker?.dragging == true
    LaunchedEffect(moving, page) {
        while (moving && scrollablePage) {
            val pointer = pickerPointer.value ?: drag.pointer
            val amount = widgetAutoScrollDelta(pointer, paneBounds[0], verticalEdge, verticalStep)
            if (amount != 0f) homeScroll.scrollBy(amount)
            delay(16)
        }
    }
    val placementTarget = picker?.targetIndex
    val placementDensity = LocalDensity.current
    LaunchedEffect(placementTarget, picker?.dragging, page) {
        if (scrollablePage && picker?.dragging == false && placementTarget != null && homeCellPage(placementTarget) == page) {
            val row = homeCellLocal(placementTarget) / GRID_COLUMNS
            val top = (if (row <= 2) row * (geometry.widgetHeight + 18f) / 2f
                else geometry.widgetHeight + 18f + (row - 2) * geometry.rowHeight) + if (page == -1) TODAY_SEARCH_BLOCK else 0f
            homeScroll.animateScrollTo(with(placementDensity) { top.dp.roundToPx() })
        }
    }
    Box(modifier.testTag("home-page-$page")
        .semantics {
            onLongClick(launcherText("Home options")) {
                if (!drag.active) onEmptyWidget(backgroundTarget)
                !drag.active
            }
        }
        .pointerInput(backgroundTarget, drag.active) {
            detectStationaryHomeTaps(onTap = { if (!drag.active) editChrome.onTapEmpty() },
                onLongPress = { if (!drag.active) onEmptyWidget(backgroundTarget) })
        }
        .onGloballyPositioned { paneBounds[0] = it.boundsInRoot() }
        .width((geometry.gridWidth + geometry.pageGutter).dp)
        .height(homePaneViewportHeight(page, contentHeight.value, paneBottomSpace.value).dp)) {
        Box(Modifier.width(16.dp).fillMaxHeight().testTag("home-options-margin-$page")
            .pointerInput(backgroundTarget, drag.active) {
                detectStationaryHomeTaps(onTap = { if (!drag.active) editChrome.onTapEmpty() }, onLongPress = {
                    if (!drag.active) onEmptyWidget(backgroundTarget)
                })
            })
        // iPhone Duo's scrolled Today View: the widgets sit in one clear glass sheet (r21, TodaySheet.kt).
        val todaySheet = if (page != -1) null else remember(geometry, contentHeight, contentTopInset) {
            val column = todaySearchFieldFrame(geometry.gridWidth, geometry.columns, geometry.iconSize, geometry.referenceCardSize)
            val card = nativeWidgetCornerRadius(column.width / 2f, column.width / 2f)
            todaySheetFrame(column.left, column.width, geometry.contentTop + contentTopInset.value,
                homePaneViewportHeight(page, contentHeight.value, paneBottomSpace.value), card)
        }
        val sheetProgress = rememberTodaySheetProgress(homeScroll)
        if (todaySheet != null) TodaySheetGlass(todaySheet, sheetProgress, Modifier.offset(x = geometry.pageGutter.dp))
        Column(Modifier.offset(x = geometry.pageGutter.dp).width(geometry.gridWidth.dp).fillMaxHeight()
            .testTag("home-scroll-$page")
            .then(when {
                todaySheet != null -> Modifier.todaySheetContent(todaySheet, sheetProgress).verticalScroll(homeScroll)
                scrollablePage -> Modifier.scrollEdgeFade({ homeScroll.value > 0 }, { homeScroll.value < homeScroll.maxValue })
                    .verticalScroll(homeScroll)
                else -> Modifier
            })
            // Today View scrolls its last widget up into the sheet, clear of its bottom rim.
            .padding(top = geometry.contentTop.dp + contentTopInset,
                bottom = if (todaySheet != null) (TODAY_SHEET_BOTTOM_DP + TODAY_SHEET_SIDE_DP).dp else 8.dp)) {
            // iPhone Duo's Today View (Apple newsroom opening/home-screen videos) starts with its first widget on
            // Home's row 1, with no search field above it; Spotlight stays on the Search button and the swipe down.
            Box {
            SharedHomeGrid(page, state.homeSlots, state.leadingSlots, previewSlots, previewLeadingSlots, previewWidgetPlacements,
                appsById, geometry, state.labels, widgets, drag, target, deviceStatus,
                folders = state.folders, onLaunch = onLaunch, onActions = onActions, onWidget = onWidget,
                onFolder = onFolder, onEmptyWidget = onEmptyWidget)
            // r21: no "编辑" capsule under Today View's widgets -- iPhone Duo has none (owner).
            }
            if (state.loading) IosActivityIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            if (state.error != null) Text(state.error, color = Color.White,
                modifier = Modifier.clickable(onClick = onRefresh).padding(12.dp))
        }
        if (todaySheet != null) {
            TodaySheetHiddenBands(todaySheet, homeScroll, Modifier.offset(x = geometry.pageGutter.dp).width(geometry.gridWidth.dp))
            TodayScrollBar(todaySheet, homeScroll, Modifier.offset(x = geometry.pageGutter.dp))
        }
        if (page == -1) LocalTodayViewActions.current?.let { actions ->
            val empty = todayContentRows(previewWidgetPlacements, previewLeadingSlots) == 0
            if (empty && !moving && picker == null && widgets.pendingPlacement?.page != -1) Box(
                Modifier.offset(x = geometry.pageGutter.dp).width(geometry.gridWidth.dp).fillMaxHeight()
                    .padding(top = (geometry.contentTop + TODAY_SEARCH_BLOCK).dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                TodayViewEmptyState(actions, LocalHomeEditChrome.current.editing)
            }
        }
    }
}

/** Places the element so its centre lands on ([x], [y]) of the parent's top-start corner. */
private fun Modifier.centeredAt(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(x.roundToPx() - placeable.width / 2, y.roundToPx() - placeable.height / 2)
    }
}

@Composable
private fun CircleControl(icon: ImageVector, label: String, tag: String, visualSize: Dp,
    backdrop: dev.chrisbanes.haze.HazeState? = LocalDuoGlassBackdrop.current, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // No Material IconButton ripple: the glass itself swells under the finger (iOS 26).
    Box(Modifier.size(visualSize.coerceAtLeast(48.dp)).testTag(tag)
        .clickable(interaction, null, role = Role.Button, onClick = action), contentAlignment = Alignment.Center) {
        Box(Modifier.size(visualSize).testTag("$tag-visual")
            .duoGlass(DuoGlassRole.Control, CircleShape, interaction, backdropState = backdrop), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = if (tag == "search") Color.White else duoGlassContentColor(DuoGlassRole.Control), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun SharedHomeGrid(
    page: Int,
    savedSlots: List<String?>,
    savedLeadingSlots: List<String?>,
    previewSlots: List<String?>,
    previewLeadingSlots: List<String?>,
    widgetPlacements: List<WidgetPlacement>,
    appsById: Map<String, AppEntry>,
    geometry: HomeGeometry,
    labels: Boolean,
    widgets: WidgetController,
    drag: HomeDragState,
    target: DropTarget?,
    deviceStatus: DeviceStatus,
    folders: List<FolderEntry>,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onActions: (AppEntry) -> Unit,
    onWidget: (Int) -> Unit,
    onFolder: (String) -> Unit,
    onEmptyWidget: (Int) -> Unit,
) {
    val rowHeight = geometry.rowHeight
    val iconSize = geometry.iconSize
    val shortcutArtwork = geometry.shortcutArtworkSize ?: homeIconArtworkSize(iconSize, iconSize)
    val pageStart = homeCellIndex(page, 0)
    val pageRange = pageStart until pageStart + HOME_CELLS
    fun savedAt(index: Int) = if (page == -1) savedLeadingSlots.getOrNull(homeCellLocal(index)) else savedSlots.getOrNull(index)
    fun previewAt(index: Int) = if (page == -1) previewLeadingSlots.getOrNull(homeCellLocal(index)) else previewSlots.getOrNull(index)
    fun savedIndexOf(id: String) = shortcutIndexOnPage(page, id, savedSlots, savedLeadingSlots)
    fun previewIndexOf(id: String) = shortcutIndexOnPage(page, id, previewSlots, previewLeadingSlots)
    val draggedId = drag.source?.appId
    val source = drag.source?.target as? DropTarget.Home
    val draggedPreviewIndex = draggedId?.let(::previewIndexOf)
    // A moving item is drawn only by the root ghost: its resting copy stays hidden wherever the finger is
    // (over a full Dock, or before the Dock's reflow has caught up, it used to show again in its old slot).
    val hiddenIndex = if (drag.active && drag.moved) draggedPreviewIndex else null
    // The lifted item is drawn by the root ghost; its resting copy is hidden, never dimmed.
    val dimDragged = drag.active && !drag.moved && source != null
    val edit = LocalHomeEditChrome.current
    val pending = widgets.pendingPlacement?.takeIf { it.page == page }
    val pendingIsReplacement = pending != null && widgetPlacements.any { it.slot == pending.slot }
    val pageWidgets = widgetPlacements.filter { it.page == page } + listOfNotNull(pending?.takeUnless { pendingIsReplacement })
    val editingWidgets = drag.source?.target is DropTarget.Widget || LocalWidgetPlacementSession.current != null
    // A rotated cover shows the page as six columns by four rows (iOS list flow); the Today page and
    // a page whose widgets cannot flow keep their portrait four columns, centred on the wider canvas.
    val flow = if (geometry.columns != GRID_COLUMNS && page >= 0)
        landscapeFlow(pageWidgets, geometry.columns, LANDSCAPE_ROWS) else null
    val columns = if (flow != null) geometry.columns else GRID_COLUMNS
    val columnOffset = (geometry.columns - columns) / 2f
    fun cellColumn(local: Int) = flow?.cell(local)?.first ?: (local % GRID_COLUMNS)
    fun cellRow(local: Int) = flow?.cell(local)?.second ?: (local / GRID_COLUMNS)
    val renderedRows = if (flow != null) LANDSCAPE_ROWS
        else homeRenderedRows(page, pageWidgets.maxOfOrNull { it.row + it.spanY } ?: 0, editingWidgets)
    val topPitch = (geometry.widgetHeight + 18f) / 2f
    fun rowTop(row: Int) = if (row <= 2) row * topPitch else geometry.widgetHeight + 18f + (row - 2) * rowHeight
    val gridHeight = geometry.appGridHeight ?: if (page == -1) rowTop(renderedRows)
        else rowTop(renderedRows - 1) + maxOf(48f, shortcutArtwork + geometry.labelHeight)
    BoxWithConstraints(Modifier.fillMaxWidth().height(gridHeight.dp)) {
        val density = LocalDensity.current
        val cellWidth = maxWidth / geometry.columns
        val cellWidthPx = with(density) { cellWidth.toPx() }
        val rowHeightPx = with(density) { rowHeight.dp.toPx() }
        // A rotation, fold or unfold gives a new layout: items jump there (iOS), only edits glide.
        val frameKey = PlacementFrameKey(columns, cellWidthPx.roundToInt(), rowHeightPx.roundToInt(),
            with(density) { topPitch.dp.toPx() }.roundToInt())

        repeat(if (page == -1) renderedRows * GRID_COLUMNS else HOME_CELLS) { localIndex ->
            val globalIndex = homeCellIndex(page, localIndex)
            val cell = DropTarget.Home(globalIndex)
            val savedId = savedAt(globalIndex)
            val savedApp = appsById[savedId]
            val savedFolder = folders.firstOrNull { it.id == savedId }
            if (flow != null && flow.cell(localIndex) == null) return@repeat
            val row = cellRow(localIndex)
            val cellHeight = minOf(rowTop(row + 1), gridHeight) - rowTop(row)
            Box(Modifier.offset(x = cellWidth * (columnOffset + cellColumn(localIndex)), y = rowTop(row).dp)
                .width(cellWidth).height(cellHeight.dp).testTag("home-cell-$globalIndex")
                .dropRegion(drag, cell, savedApp?.id ?: savedFolder?.id, page)
                .then(if (savedId == null) Modifier.semantics {
                    onLongClick(launcherText("Home options")) {
                        if (!drag.active) onEmptyWidget(globalIndex)
                        !drag.active
                    }
                }.pointerInput(globalIndex, drag.active) {
                    detectStationaryHomeTaps(onTap = { if (!drag.active) edit.onTapEmpty() },
                        onLongPress = { if (!drag.active) onEmptyWidget(globalIndex) })
                } else Modifier.focusable().pointerInput(globalIndex, drag.active) {
                    // The cell around an icon is bare Home: a hold in the gutter beside the icon
                    // enters jiggle mode (iOS); the icon itself is grabbed by the root lift detector.
                    // It stays focusable (as before): when a key press leaves touch mode, Compose's
                    // initial focus lands on a cell, never on a search field that would raise the IME.
                    detectStationaryHomeTaps(onTap = { if (!drag.active) edit.onTapEmpty() },
                        onLongPress = { if (!drag.active) onEmptyWidget(globalIndex) })
                }),
                // iOS draws no slot frames while dragging: the open gap is simply empty.
                contentAlignment = Alignment.TopCenter) { }
        }

        val ids = (if (page == -1) savedLeadingSlots + previewLeadingSlots
            else savedSlots.slicePage(pageRange) + previewSlots.slicePage(pageRange)).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedIndexOf(id)
            val previewIndex = previewIndexOf(id)
            val renderIndex = previewIndex?.takeIf { it in pageRange } ?: savedIndex?.takeIf { it in pageRange } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val localIndex = renderIndex - pageStart
                val row = cellRow(localIndex)
                val visible = previewIndex != null && previewIndex in pageRange && renderIndex != hiddenIndex
                val restingHidden = (dimDragged && id == draggedId) || id == edit.liftedId || id in edit.hiddenIds
                val animatedOffset by animatePlacementAsState(
                    IntOffset(((columnOffset + cellColumn(localIndex)) * cellWidthPx).roundToInt(), with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                    frameKey, LauncherMotion.placement, snap = restingHidden || !visible)
                val opacity = if (restingHidden) 0f else 1f
                Box(Modifier.width(cellWidth).height(minOf(rowHeight, gridHeight - rowTop(row)).dp).graphicsLayer {
                    translationX = animatedOffset.x.toFloat()
                    translationY = animatedOffset.y.toFloat()
                }
                    .graphicsLayer { alpha = opacity }.testTag("home-app-$id"), contentAlignment = Alignment.TopCenter) {
                    if (visible) AppTile(app, shortcutArtwork, labels,
                        onClick = { onLaunch(app, it) }, onLongClick = { onActions(app) },
                        labelLines = geometry.shortcutLabelLines, labelSp = geometry.metrics?.labelSp ?: 11f,
                        folderHover = edit.folderHoverId == id)
                }
            }
        }
        folders.forEach { folder ->
            val savedIndex = savedIndexOf(folder.id)
            val previewIndex = previewIndexOf(folder.id)
            if (previewIndex == null || previewIndex == hiddenIndex) return@forEach
            val renderIndex = previewIndex?.takeIf { it in pageRange } ?: savedIndex?.takeIf { it in pageRange } ?: return@forEach
            // Keyed like the apps: a folder removed before this one must not hand it its motion (it flew across the grid).
            key(folder.id) {
            val localIndex = renderIndex - pageStart
            val row = cellRow(localIndex)
            val folderHidden = (dimDragged && folder.id == draggedId) || folder.id == edit.liftedId || folder.id in edit.hiddenIds
            val animatedOffset by animatePlacementAsState(
                IntOffset(((columnOffset + cellColumn(localIndex)) * cellWidthPx).roundToInt(),
                    with(density) { rowTop(row).dp.toPx() }.roundToInt()),
                frameKey, LauncherMotion.placement, snap = folderHidden)
            FolderTile(folder, appsById, shortcutArtwork, labels, drag, page,
                Modifier.width(cellWidth).height(minOf(rowHeight, gridHeight - rowTop(row)).dp).graphicsLayer {
                    translationX = animatedOffset.x.toFloat()
                    translationY = animatedOffset.y.toFloat()
                    alpha = if (folderHidden) 0f else 1f
                }
                    .testTag("home-folder-${folder.id}"), onClick = { onFolder(folder.id) },
                labelLines = geometry.shortcutLabelLines, labelSp = geometry.metrics?.labelSp ?: 11f)
            }
        }
        pageWidgets.forEach { placement ->
            key("widget-${placement.slot}") {
                val (widgetColumn, widgetRow) = flow?.widget(placement.slot) ?: (placement.column to placement.row)
                val y = rowTop(widgetRow)
                val frame = widgetPlacementFrame(page, placement.spanX, placement.spanY,
                    cellWidth.value, iconSize,
                    rowTop(widgetRow + placement.spanY - 1) - y + iconSize,
                    rowTop(widgetRow + placement.spanY) - y, geometry.referenceCardSize)
                val x = cellWidth * (columnOffset + widgetColumn) + frame.insetX.dp
                val width = frame.width.dp
                val height = frame.height
                // A widget under the finger or under its settling copy goes straight to its slot.
                val widgetResting = "widget-slot-${placement.slot}" in edit.hiddenIds ||
                    (drag.source?.target == DropTarget.Widget(placement.slot) && drag.moved)
                val widgetWidth by animatePlacementAsState(width, frameKey, LauncherMotion.size, snap = widgetResting)
                val widgetHeight by animatePlacementAsState(height.dp, frameKey, LauncherMotion.size, snap = widgetResting)
                val widgetOffset by animatePlacementAsState(
                    IntOffset(with(density) { x.toPx() }.roundToInt(), with(density) { y.dp.toPx() }.roundToInt()),
                    frameKey, LauncherMotion.placement, snap = widgetResting)
                val widgetEntered = true
                val widgetScale by animateFloatAsState(
                    if (widgetEntered) 1f else .94f,
                    animationSpec = LauncherMotion.settle,
                    label = "widget insertion scale ${placement.slot}",
                )
                val widgetAlpha by animateFloatAsState(
                    if (widgetEntered) 1f else 0f,
                    animationSpec = LauncherMotion.settle,
                    label = "widget insertion alpha ${placement.slot}",
                )
                val widgetModifier = Modifier.width(widgetWidth).height(widgetHeight).graphicsLayer {
                    translationX = widgetOffset.x.toFloat()
                    translationY = widgetOffset.y.toFloat()
                    scaleX = widgetScale
                    scaleY = widgetScale
                    alpha = widgetAlpha
                }
                if (placement == pending) Surface(widgetModifier
                    .testTag("widget-pending-${placement.slot}").semantics(mergeDescendants = true) {
                        contentDescription = launcherFormat(R.string.pending_widget, widgets.pendingProvider?.shortClassName ?: launcherText("Widgets"))
                    }, color = Glass.copy(alpha = .72f),
                    shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(2.dp, Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        IosActivityIndicator(color = Ink)
                        Spacer(Modifier.height(8.dp)); Text(launcherText("Finish widget setup"), color = Ink)
                    }
                } else MovableWidget(placement.id, placement.slot, widgets, drag, target,
                    widgetModifier.graphicsLayer { alpha = if ("widget-slot-${placement.slot}" in edit.hiddenIds) 0f else 1f },
                    page = page, deviceStatus = deviceStatus, wide = placement.spanX >= GRID_COLUMNS,
                    iconSize = shortcutArtwork) {
                    onWidget(placement.slot)
                }
                // iOS prints the app's name under every Home Screen widget, like an icon label
                // (hig/designing-for-iphone-hero-*.png: "Weather", "Calendar"); so does iPhone Duo's Today View
                // (r21, 1.webp: 日历, 备忘录, 提醒事项, 地图).
                if (labels && placement != pending) {
                    val context = LocalContext.current
                    val name = remember(placement.id) { widgetAppName(placement.id, context, widgets.manager) }
                    val hidden = widgetResting
                    if (name != null) Box(Modifier.width(widgetWidth).graphicsLayer {
                        translationX = widgetOffset.x.toFloat()
                        translationY = widgetOffset.y.toFloat() + widgetHeight.toPx()
                        alpha = if (hidden) 0f else widgetAlpha
                    // Centred under the widget (r21, the owner's iPhone Duo: 日历, 天气 sit in the middle).
                    }.testTag("widget-label-${placement.slot}"), contentAlignment = Alignment.TopCenter) { HomeShortcutLabel(name) }
                }
            }
        }
    }
}

@Composable
private fun DockAppColumn(
    savedDock: List<String?>,
    previewDock: List<String?>,
    appsById: Map<String, AppEntry>,
    rowHeight: Float,
    iconSize: Float,
    drag: HomeDragState,
    target: DropTarget?,
    onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onChoose: (Int) -> Unit,
    horizontal: Boolean = false,
    barHeight: Float = 64f,
    /** Glass padding outside the first/last cell along the Dock, available to the − badge overhang. */
    outerPadding: Float = 6f,
) {
    val edit = LocalHomeEditChrome.current
    val draggedId = drag.source?.appId
    val dockTarget = (target as? DropTarget.Dock)?.index
    val source = drag.source?.target as? DropTarget.Dock
    val draggedPreviewIndex = previewDock.indexOf(draggedId)
    val hiddenIndex = when {
        !drag.active || !drag.moved -> null
        dockTarget != null -> draggedPreviewIndex.takeIf { it >= 0 }
        source != null && target !is DropTarget.Home -> draggedPreviewIndex.takeIf { it >= 0 }
        else -> null
    }
    val dimDragged = drag.active && !drag.moved && source != null
    val launchBounds = remember(savedDock.size) { List(savedDock.size) { android.graphics.Rect() } }
    // Keep jiggling − badges inside the Dock glass: the overhang never exceeds the padding
    // around the icon (and clears the concentric corner of the first and last slot).
    var columnWidthPx by remember { mutableIntStateOf(0) }
    val interactions = remember(savedDock.size) { List(savedDock.size) { MutableInteractionSource() } }
    val density = LocalDensity.current
    val rowHeightPx = with(density) { rowHeight.dp.toPx() }
    // The Dock turning horizontal (inner portrait) or resizing is a new layout, not a move.
    val dockFrameKey = PlacementFrameKey(savedDock.size, 0, rowHeightPx.roundToInt(), 0, horizontal)
    fun cellModifier() = if (horizontal) Modifier.width(rowHeight.dp).fillMaxHeight()
        else Modifier.fillMaxWidth().height(rowHeight.dp)
    // iOS keeps the Dock compact: with fewer than four apps they sit centred as a block and empty
    // positions show nothing (no "+"). Only while an item is dragged do the slots open up, so a
    // drop lands on a definite position; the icons glide between the two arrangements.
    val compact = !(drag.active && drag.moved)
    val resting = remember(previewDock) { dockRestingPositions(previewDock) }
    fun slotPosition(index: Int): Float = if (compact) resting.getOrNull(index) ?: index.toFloat() else index.toFloat()
    fun cellOffset(index: Int) = if (horizontal) Modifier.offset(x = (rowHeight * slotPosition(index)).dp)
        else Modifier.offset(y = (rowHeight * slotPosition(index)).dp)
    val crossPaddingDp = with(density) { (columnWidthPx.toDp().value - iconSize) / 2f }.takeIf { columnWidthPx > 0 } ?: 8f
    val alongPaddingDp = (rowHeight - iconSize) / 2f
    val badgeBase = removeBadgeGeometry(iconSize)
    val (badgeOverhangX, badgeOverhangY) = if (horizontal)
        dockBadgeOverhang(iconSize, alongPaddingDp, crossPaddingDp).let { it.first to it.second }
        else dockBadgeOverhang(iconSize, crossPaddingDp, alongPaddingDp + outerPadding)
    Box((if (horizontal) Modifier.fillMaxWidth().height(barHeight.dp)
        else Modifier.fillMaxWidth().height((rowHeight * savedDock.size).dp)).onGloballyPositioned {
            columnWidthPx = if (horizontal) it.size.height else it.size.width
        }.semantics {
            // Empty positions are invisible, so screen readers reach the picker from the Dock itself.
            customActions = savedDock.indices.filter { appsById[savedDock[it]] == null }.map { index ->
                CustomAccessibilityAction(launcherFormat(R.string.dock_empty_slot, index + 1)) { onChoose(index); true }
            }
        }) {
        savedDock.indices.forEach { index ->
            val cell = DropTarget.Dock(index)
            val savedApp = appsById[savedDock[index]]
            val previewId = previewDock.getOrNull(index)
            Box((if (compact && savedApp == null) Modifier.size(0.dp) else cellModifier()).then(cellOffset(index))
                .testTag("dock-slot-$index").dropRegion(drag, cell, savedApp?.id)
                .semantics(mergeDescendants = true) { contentDescription = savedApp?.label ?: launcherFormat(R.string.dock_empty_slot, index + 1) }
                .combinedClickable(interactionSource = interactions[index], indication = null, role = Role.Button, onClick = {
                    if (edit.editing) Unit else if (savedApp != null) onLaunch(savedApp, launchBounds[index]) else onChoose(index)
                }, onLongClick = null)
                .semantics { onLongClick(launcherFormat(R.string.choose_dock_app)) { onChoose(index); true } })
        }

        val ids = (savedDock + previewDock).filterNotNull().distinct()
        ids.forEach { id ->
            val savedIndex = savedDock.indexOf(id)
            val previewIndex = previewDock.indexOf(id)
            val renderIndex = previewIndex.takeIf { it >= 0 } ?: savedIndex.takeIf { it >= 0 } ?: return@forEach
            val app = appsById[id] ?: return@forEach
            key(id) {
                val visible = previewIndex >= 0 && renderIndex != hiddenIndex
                val restingHidden = (dimDragged && id == draggedId) || id == edit.liftedId || id in edit.hiddenIds
                val animatedOffset by animatePlacementAsState(
                    if (horizontal) IntOffset((slotPosition(renderIndex) * rowHeightPx).roundToInt(), 0)
                    else IntOffset(0, (slotPosition(renderIndex) * rowHeightPx).roundToInt()),
                    dockFrameKey, LauncherMotion.placement, snap = restingHidden || !visible)
                val opacity = if (!visible || restingHidden) 0f else 1f
                val motion = remember(id) { jiggleMotion("dock-$id") }
                Box(cellModifier().graphicsLayer {
                    translationX = animatedOffset.x.toFloat()
                    translationY = animatedOffset.y.toFloat()
                    alpha = opacity
                }
                    .testTag("dock-app-$id"), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(iconSize.dp).registerHomeIcon(edit.icons, id).jiggle(edit, motion)) {
                        Image(app.icon.asImageBitmap(), null, Modifier.matchParentSize().testTag("dock-icon-$id")
                            .then(if (savedIndex >= 0) Modifier.homeIconTarget(id, HomeIconKind.DOCK) else Modifier)
                            .onGloballyPositioned { if (savedIndex >= 0) launchBounds[savedIndex].set(it.boundsInWindow().toAndroidBounds()) }
                            // The bitmap is already masked to the continuous tile (iosIconBitmap):
                            // no per-frame path clip while pages move past the Dock.
                            // iOS: a Dock press darkens the icon, it never shrinks it.
                            .then(interactions.getOrNull(renderIndex)?.let { Modifier.iosIconHighlight(it, enabled = !edit.editing) }
                                ?: Modifier))
                        if (edit.decorated && savedIndex >= 0) RemoveBadge(uxText("移除 ${app.label}", "Remove ${app.label}"),
                            "dock-remove-$id", { edit.onRemoveDock(savedIndex) },
                            Modifier.align(Alignment.TopStart).offset((-badgeOverhangX).dp, (-badgeOverhangY).dp),
                            diameter = badgeBase.diameter.dp, intensity = edit.intensity)
                    }
                }
            }
        }
    }
}

private fun <T> List<T>.slicePage(range: IntRange): List<T> =
    if (isEmpty() || range.first >= size) emptyList() else subList(range.first, minOf(range.last + 1, size))

@Composable
private fun FolderTile(folder: FolderEntry, apps: Map<String, AppEntry>, size: Float, labels: Boolean,
    drag: HomeDragState, page: Int, modifier: Modifier = Modifier, onClick: () -> Unit, labelLines: Int = 1,
    labelSp: Float = 11f) {
    val edit = LocalHomeEditChrome.current
    val interaction = remember { MutableInteractionSource() }
    val motion = remember(folder.id) { jiggleMotion(folder.id) }
    // A dragged app resting over the folder: its platter swells to accept it (iOS).
    val accepting = drag.active && drag.moved && edit.folderHoverId == null &&
        drag.regions[DropTarget.Folder(folder.id)]?.bounds?.contains(drag.pointer) == true &&
        drag.source?.appId?.let(::isFolderId) == false && drag.source?.folderId != folder.id
    val scale by animateFloatAsState(if (accepting) 1.14f else 1f, animationSpec = LauncherMotion.lift, label = "folder accept")
    val badge = removeBadgeGeometry(size)
    val iconPx = with(LocalDensity.current) { size.dp.toPx() }
    // Like an app, only the folder icon and its title take a tap; the cell around them is bare Home.
    val tap = Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    Column(modifier.registerHomeIcon(edit.icons, folder.id, iconPx)
        .semantics(mergeDescendants = true) {
        contentDescription = launcherFormat(R.string.folder_summary, folder.title, folder.appIds.size)
        role = Role.Button
        onClick { onClick(); true }
    }.jiggle(edit, motion), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).homeIconTarget(folder.appIds, HomeIconKind.FOLDER).then(tap)) {
            FolderIconArt(folder, apps, size, Modifier.matchParentSize().graphicsLayer { scaleX = scale; scaleY = scale }
                .dropRegion(drag, DropTarget.Folder(folder.id), page = page, folderId = folder.id)
                .testTag("folder-drop-${folder.id}"), interaction = interaction)
            if (edit.decorated) RemoveBadge(launcherFormat(R.string.compact_dissolve_folder), "folder-remove-${folder.id}",
                { edit.onDissolveFolder(folder.id) }, Modifier.align(Alignment.TopStart).offset(badge.offsetX.dp, badge.offsetY.dp),
                diameter = badge.diameter.dp, intensity = edit.intensity)
        }
        if (labels) HomeShortcutLabel(folder.title, labelLines, labelSp, tap)
    }
}

@Composable
private fun AppTile(app: AppEntry, size: Float, labels: Boolean, modifier: Modifier = Modifier, onClick: (android.graphics.Rect) -> Unit, onLongClick: () -> Unit,
    labelLines: Int = 1, labelSp: Float = 11f, folderHover: Boolean = false) {
    val edit = LocalHomeEditChrome.current
    val interaction = remember { MutableInteractionSource() }
    // A press darkens the artwork (iosIconHighlight), it never scales (iOS). The context-menu
    // lift is drawn by the menu overlay above the blur, so this tile never scales up itself.
    // Another app resting on this icon: a folder platter grows behind it (iOS folder creation).
    val platter by animateFloatAsState(if (folderHover) 1f else 0f, animationSpec = LauncherMotion.lift, label = "folder platter")
    val iconSize by animateDpAsState(size.dp, LauncherMotion.size, label = "icon size")
    val bounds = remember { android.graphics.Rect() }
    val motion = remember(app.id) { jiggleMotion(app.id) }
    val badge = removeBadgeGeometry(size)
    val iconPx = with(LocalDensity.current) { size.dp.toPx() }
    // Only the artwork and the label text take a tap (iOS): the rest of the cell is bare Home,
    // so a hold beside an icon enters jiggle mode instead of grabbing it.
    val tap = Modifier.clickable(interactionSource = interaction, indication = null,
        role = Role.Button, onClick = { if (!edit.editing) onClick(bounds) })
    Column(modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {
            contentDescription = app.label
            role = Role.Button
            onClick { if (!edit.editing) onClick(bounds); true }
        }
        .semantics { onLongClick(launcherFormat(R.string.app_options)) { onLongClick(); true } }.padding(horizontal = 2.dp)
        .registerHomeIcon(edit.icons, app.id, iconPx)
        .jiggle(edit, motion),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(iconSize).then(tap)) {
            if (platter > 0f) Box(Modifier.matchParentSize().graphicsLayer {
                val s = 1f + .26f * platter
                scaleX = s; scaleY = s; alpha = platter
            }.duoGlass(DuoGlassRole.Folder, homeIconShape(size)).testTag("folder-forming-${app.id}"))
            Image(app.icon.asImageBitmap(), null, Modifier.matchParentSize().homeIconTarget(app.id)
                .onGloballyPositioned { bounds.set(it.boundsInWindow().toAndroidBounds()) }
                // Pre-masked tile bitmap (iosIconBitmap): skipping a path clip per icon keeps paging cheap.
                .graphicsLayer { val s = 1f - .12f * platter; scaleX = s; scaleY = s }
                .iosIconHighlight(interaction, enabled = !edit.editing))
            if (edit.decorated) RemoveBadge(uxText("移除 ${app.label}", "Remove ${app.label}"), "home-remove-${app.id}",
                { edit.onRemoveApp(app.id) }, Modifier.align(Alignment.TopStart).offset(badge.offsetX.dp, badge.offsetY.dp),
                diameter = badge.diameter.dp, intensity = edit.intensity)
        }
        if (labels) HomeShortcutLabel(app.label, labelLines, labelSp, tap)
    }
}

@Composable
private fun HomeShortcutLabel(text: String, lines: Int = 1, labelSp: Float = 11f, tap: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = labelSp.sp, lineHeight = (labelSp * 1.25f).sp, maxLines = lines,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        // SF's Home label reads heavier than Inter Regular at 11 sp; Medium matches its colour.
        style = TextStyle(fontFamily = DuoFonts.text, fontWeight = FontWeight.Medium, letterSpacing = duoTracking(labelSp),
            shadow = Shadow(Color.Black.copy(alpha = .55f), Offset(0f, 1f), 3f)),
        // As wide as the text (at most the cell), so a tap beside a short label is not a tap on the app.
        modifier = Modifier.padding(top = 5.5.dp, start = 2.dp, end = 2.dp).wrapContentWidth().then(tap))
}

@Composable
private fun WidgetSlot(id: Int, slot: Int, controller: WidgetController, modifier: Modifier, onAdd: () -> Unit, fallback: @Composable () -> Unit) {
    var restoreMessage by remember(slot) { mutableStateOf<String?>(null) }
    // The card an app it opens zooms out of, and flies back into (iOS).
    val tile = remember(slot) { WidgetTile() }
    BoxWithConstraints(modifier.clip(ContinuousRoundedShape(WIDGET_SLOT_CORNER_DP.dp)).widgetLaunchTile(tile)
        .testTag("widget-slot-$slot")) {
        val displayedContentSize = WidgetContentSize(maxWidth.value, maxHeight.value)
        val cornerDp = if (isNativeWidgetId(id)) maxOf(WIDGET_SLOT_CORNER_DP, nativeWidgetCornerRadius(maxWidth.value, maxHeight.value))
            else WIDGET_SLOT_CORNER_DP
        tile.cornerRadiusPx = with(LocalDensity.current) { cornerDp.dp.toPx() }
        if (id == NEEDS_BINDING_WIDGET) {
            val restore = controller.restoreDescriptor(slot)
            val restoreShape = RoundedCornerShape(24.dp)
            Surface(Modifier.fillMaxSize().duoGlass(DuoGlassRole.Elevated, restoreShape)
                .testTag("widget-restore-$slot"), color = Color.Transparent,
                contentColor = duoGlassContentColor(DuoGlassRole.Elevated), shape = restoreShape) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(restore?.title ?: launcherFormat(R.string.widget_saved_title), color = LocalContentColor.current,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(restore?.profileLabel ?: launcherFormat(R.string.widget_profile_unavailable),
                        color = LocalContentColor.current.copy(alpha = .72f), fontSize = 13.sp)
                    restoreMessage?.let { Text(it, color = IosUi.red, fontSize = 13.sp, textAlign = TextAlign.Center) }
                    Row {
                        IosPlainButton(launcherText("Reconnect"), {
                            if (!controller.rebindRestoredWidget(slot, contentSize = displayedContentSize))
                                restoreMessage = launcherFormat(R.string.widget_provider_unavailable)
                        }, tag = "widget-restore-reconnect-$slot", fontSize = 15f)
                        IosPlainButton(launcherText("Replace"), onAdd, tag = "widget-restore-replace-$slot", fontSize = 15f)
                    }
                }
            }
            return@BoxWithConstraints
        }
        if (isNativeWidgetId(id)) {
            CompositionLocalProvider(LocalWidgetTile provides tile) { NativeWidget(id, Modifier.fillMaxSize()) }
            return@BoxWithConstraints
        }
        val info = remember(id) { if (id >= 0) controller.manager.getAppWidgetInfo(id) else null }
        if (info == null) fallback()
        else {
            val providerTap = LocalProviderWidgetTap.current
            val providerPackage = info.provider.packageName
            key(id) {
                Box(Modifier.fillMaxSize().then(if (providerTap == null) Modifier else Modifier.pointerInput(providerPackage, tile, providerTap) {
                    // Only watches (never consumes): the provider's own views still get every event.
                    detectProviderWidgetTap { providerTap.tapped(providerPackage, tile) }
                })) {
                    AndroidView(factory = { context ->
                        val nativeView = controller.host.createView(context, id, info)
                        if (info.provider.flattenToString() == "com.moji.mjweather/com.moji.mjweather.CMojiWidget4x2")
                            FittedNativeWidget(context, nativeView, minimumHeightDp = 200f)
                        else nativeView
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

private fun widgetLabel(id: Int, controller: WidgetController) = controller.label(id)

@Composable
private fun MovableWidget(id: Int, slot: Int, controller: WidgetController, drag: HomeDragState,
    target: DropTarget?, modifier: Modifier, page: Int, deviceStatus: DeviceStatus,
    wide: Boolean, iconSize: Float = 54f, onAdd: () -> Unit) {
    val cell = DropTarget.Widget(slot)
    val context = LocalContext.current
    val edit = LocalHomeEditChrome.current
    var sizeRatio by remember { mutableFloatStateOf(2f) }
    val density = LocalDensity.current
    // Widgets jiggle too, rotating less so their corners travel about as far as an icon's.
    val motion = remember(slot, sizeRatio) { jiggleMotion("widget-$slot", sizeRatio) }
    val badge = removeBadgeGeometry(iconSize)
    Box(modifier.onGloballyPositioned {
        val ratio = with(density) { maxOf(it.size.width, it.size.height).toDp().value } / iconSize.coerceAtLeast(1f)
        if (kotlin.math.abs(ratio - sizeRatio) > .25f) sizeRatio = ratio
    }.registerHomeIcon(edit.icons, "widget-slot-$slot").jiggle(edit, motion)) {
        WidgetSlot(id, slot, controller, Modifier.fillMaxSize().dropRegion(drag, cell, page = page, widgetId = id)
            // A widget picked up leaves its place empty; the ghost (a pixel copy) follows the finger.
            .alpha(if (drag.source?.target == cell && drag.moved) 0f else 1f)
            .semantics { onLongClick(launcherFormat(R.string.move_replace_widget)) { onAdd(); true } }, onAdd) {
            val emptyShape = RoundedCornerShape(24.dp)
            Surface(Modifier.fillMaxSize().clickable(onClick = onAdd),
                color = Glass, shape = emptyShape) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(IosSymbols.plus, null, Modifier.size(22.dp))
                    Text(launcherFormat(R.string.widget_unavailable_hint), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
        if (edit.decorated) {
            RemoveBadge(uxText("移除小组件", "Remove widget"), "widget-remove-$slot", { edit.onRemoveWidget(slot) },
                Modifier.align(Alignment.TopStart).offset(badge.offsetX.dp, badge.offsetY.dp),
                diameter = badge.diameter.dp, intensity = edit.intensity)
            if (id >= 0 && edit.editing) WidgetResizeHandle(uxText("调整小组件尺寸", "Resize widget"), "widget-resize-$slot",
                { edit.onResizeWidget(slot) }, Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp))
        }
    }
}

