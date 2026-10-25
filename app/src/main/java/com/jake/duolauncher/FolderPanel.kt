@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jake.duolauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal const val FOLDER_PAGE_CAPACITY = 9

internal fun folderPageCount(appCount: Int): Int =
    ((appCount.coerceAtLeast(1) - 1) / FOLDER_PAGE_CAPACITY) + 1

/** iOS folder platter side: 80 % of the narrow panel side, capped, leaving room for the title. */
internal fun folderPanelSide(windowWidth: Float, windowHeight: Float): Float =
    minOf(windowWidth * .8f, 340f, windowHeight - 132f).coerceAtLeast(200f)

// One zoom for everything that grows out of a tapped tile (round 11): the same pair as the App
// Library category, 0.42 s with a hint of bounce out, 0.36 s critically damped back in.
private val folderOpenSpring get() = LauncherMotion.zoomOpen
private val folderCloseSpring get() = LauncherMotion.zoomClose

/**
 * The closed folder's icon art: the first nine apps as a 3×3 grid of mini icons on a glass
 * platter (iOS). [size] is the Home icon size in dp.
 */
@Composable
internal fun FolderIconArt(folder: FolderEntry, apps: Map<String, AppEntry>, size: Float, modifier: Modifier = Modifier,
    interaction: MutableInteractionSource? = null) {
    val shape = homeIconShape(size)
    // A folder is pressed like an app icon (darkens, no swell): the glass gets no press of its own.
    Box(modifier.size(size.dp).then(if (interaction != null) Modifier.iosIconHighlight(interaction) else Modifier)
        .duoGlass(DuoGlassRole.Folder, shape)) {
        folderPreviewSlots(size, folder.appIds.size).forEachIndexed { index, slot ->
            apps[folder.appIds[index]]?.let { app ->
                Image(app.icon.asImageBitmap(), null, Modifier.offset(slot.x.dp, slot.y.dp).size(slot.size.dp)
                    .clip(homeIconShape(slot.size)))
            }
        }
    }
}

/**
 * Keeps the open folder composed while it zooms back into its icon. [folder] null closes it.
 * [onProgress] reports the 0 → 1 zoom so Home can blur behind it.
 */
@Composable
internal fun FolderPanelHost(
    folder: FolderEntry?, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int, chrome: HomeEditChrome,
    iconSize: Float, renameOnOpen: Boolean, menuAppId: String?, onMenuApp: (String?) -> Unit,
    onProgress: (Float) -> Unit, onDismiss: () -> Unit,
    /** (folder id, new title): the id travels with the call, the folder may already be closed. */
    onRename: (String, String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onRemoveApp: (AppEntry) -> Unit, onAppInfo: (AppEntry) -> Unit, onEditHome: () -> Unit,
    onHideApp: ((AppEntry) -> Unit)? = null,
) {
    val retained = remember { arrayOfNulls<FolderEntry>(1) }
    var visibleId by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(0f) }
    val reduced = rememberReduceMotion()
    val currentProgress by rememberUpdatedState(onProgress)
    val id = folder?.id
    LaunchedEffect(id) {
        if (id != null) {
            if (visibleId != id) progress.snapTo(0f)
            visibleId = id
            // Reduce Motion: the folder and the blur behind it cross-dissolve instead of zooming (iOS).
            progress.animateTo(1f, if (reduced) LauncherMotion.dissolveIn else folderOpenSpring) { currentProgress(value) }
            currentProgress(1f)
        } else if (visibleId != null) {
            progress.animateTo(0f, if (reduced) LauncherMotion.dissolveOut else folderCloseSpring) {
                currentProgress(value.coerceAtLeast(0f))
            }
            currentProgress(0f)
            visibleId = null
        }
    }
    SideEffect { if (folder != null) retained[0] = folder }
    val shown = folder ?: retained[0]?.takeIf { visibleId != null } ?: return
    key(shown.id) {
        FolderPanel(shown, apps, drag, page, chrome, iconSize, open = folder != null, progress = progress,
            origin = chrome.icons[shown.id], renameOnOpen = renameOnOpen, menuAppId = menuAppId.takeIf { folder != null },
            onMenuApp = onMenuApp, onDismiss = onDismiss, onRename = onRename,
            onLaunch = onLaunch, onRemoveApp = onRemoveApp, onAppInfo = onAppInfo, onEditHome = onEditHome,
            onHideApp = onHideApp)
    }
}

@Composable
private fun FolderPanel(
    folder: FolderEntry, apps: Map<String, AppEntry>, drag: HomeDragState, page: Int, chrome: HomeEditChrome,
    iconSize: Float, open: Boolean, progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, origin: Rect?, renameOnOpen: Boolean,
    menuAppId: String?, onMenuApp: (String?) -> Unit, onDismiss: () -> Unit, onRename: (String, String) -> Unit, onLaunch: (AppEntry, android.graphics.Rect?) -> Unit,
    onRemoveApp: (AppEntry) -> Unit, onAppInfo: (AppEntry) -> Unit, onEditHome: () -> Unit,
    onHideApp: ((AppEntry) -> Unit)? = null,
) {
    var title by rememberSaveable(folder.id) { mutableStateOf(folder.title) }
    var editing by rememberSaveable(folder.id) { mutableStateOf(renameOnOpen) }
    // Long-pressing a child is detected by the root drag input (so a hold can turn into a
    // drag out of the folder); the menu itself lives here, anchored to the child's icon.
    val menuApp = menuAppId?.let(apps::get)?.takeIf { it.id in folder.appIds }
    val childIcons = remember { HashMap<String, Rect>() }
    // A child's menu blurs the folder plate behind its lifted icon, as Home is blurred for Home items.
    val menuBlur = remember { mutableFloatStateOf(0f) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val commitTitle = {
        // Closing by Home or by dragging an app out runs this after the host has already
        // forgotten which folder was open, so the id is passed along with the title.
        title.trim().takeIf { it.isNotEmpty() && it != folder.title }?.let { onRename(folder.id, it) }
        editing = false
        // Leaving the field must end the text session, or the IME returns on the next key event.
        focusManager.clearFocus(force = true); keyboard?.hide(); Unit
    }
    // Closing by Home/drag while renaming keeps what was typed (iOS commits on close).
    LaunchedEffect(open) { if (!open && editing) commitTitle() }
    // Putting the keyboard away (Back, the keyboard's own hide key) ends the rename too: a field left open
    // without its keyboard is a state iOS never shows.
    val imeInsets = WindowInsets.ime
    val imeDensity = LocalDensity.current
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        var shown = false
        snapshotFlow { imeInsets.getBottom(imeDensity) > 0 }.collect { up ->
            if (up) shown = true else if (shown) commitTitle()
        }
    }
    BackHandler(enabled = open) { if (menuApp != null) onMenuApp(null) else if (editing) commitTitle() else onDismiss() }
    DisposableEffect(drag, folder.id, open) {
        if (open) drag.activeSourceScope = folder.id
        onDispose { if (drag.activeSourceScope == folder.id) drag.activeSourceScope = null }
    }
    val density = LocalDensity.current
    var hostBounds by remember { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()
        .then(if (open) Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClickLabel = launcherFormat(R.string.folder_close),
            onClick = { if (editing) commitTitle() else onDismiss() }) else Modifier)
        // Round 16 (owner: "编辑名字你不要做成往上弹"): renaming never squeezes the plate above the keyboard. As on
        // iOS the folder stays where it is and the keyboard covers its lower part; it only lifts as far as needed
        // to keep the name field itself visible (see imeLift below).
        .onGloballyPositioned { hostBounds = it.boundsInRoot() }.testTag("folder-panel")) {
        // iOS dims Home slightly under its blur while a folder is open.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
            .background(Color.Black.copy(alpha = .16f)))
        val side = folderPanelSide(maxWidth.value, maxHeight.value)
        // Home-size icons; a panel squeezed by the keyboard shrinks them so labels still fit.
        val childIconSize = minOf(iconSize, (side * .86f - 22f) / 3f - 20f).coerceAtLeast(28f)
        val titleBlock = with(density) { (FOLDER_TITLE_HEIGHT + FOLDER_TITLE_GAP).toPx() }
        val sidePx = with(density) { side.dp.toPx() }
        // Resting platter rectangle (root px), computed rather than measured: a measurement
        // inside the zooming layer would already include the zoom.
        val panelBounds = hostBounds?.let { host ->
            val left = host.left + (host.width - sidePx) / 2f
            val top = host.top + (host.height - sidePx - titleBlock) / 2f + titleBlock
            Rect(left, top, left + sidePx, top + sidePx)
        }
        val radius = (side * .12f).dp
        val panelShape = RoundedCornerShape(radius)
        val pageCount = folderPageCount(folder.appIds.size)
        val pagerState = rememberPagerState(pageCount = { pageCount })
        LaunchedEffect(pageCount) {
            if (pagerState.currentPage >= pageCount) pagerState.scrollToPage(pageCount - 1)
        }
        val ime = WindowInsets.ime
        val fieldClearance = with(density) { 12.dp.toPx() }
        // Panel and title move as one plate that zooms out of / back into the folder icon.
        Column(Modifier.align(Alignment.Center).width(side.dp)
            .graphicsLayer {
                // Read in the layer: the keyboard's slide only moves this layer, it never re-lays out the folder.
                val lift = panelBounds?.let { panel ->
                    folderRenameLift(fieldBottom = panel.top - FOLDER_TITLE_GAP.toPx(),
                        keyboardTop = (hostBounds?.bottom ?: 0f) - ime.getBottom(density), clearance = fieldClearance)
                } ?: 0f
                translationY = -lift
                val p = progress.value
                val panel = panelBounds
                if (LauncherMotion.reduced) {
                    // Dissolve in place: no zoom out of the icon.
                } else if (origin != null && panel != null && panel.width > 0f) {
                    val zoom = folderZoomTransform(MenuBounds(origin.left, origin.top, origin.right, origin.bottom),
                        MenuBounds(panel.left, panel.top, panel.right, panel.bottom), p)
                    scaleX = zoom.scaleX; scaleY = zoom.scaleY
                    translationX = zoom.translationX; translationY = zoom.translationY - lift
                    // The platter's centre is the zoom origin, not the column's (title above it).
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f,
                        ((sidePx / 2f + titleBlock) / (sidePx + titleBlock)).coerceIn(0f, 1f))
                } else {
                    val scale = .6f + .4f * p
                    scaleX = scale; scaleY = scale
                }
                alpha = if (LauncherMotion.reduced) p.coerceIn(0f, 1f) else (p * 1.6f).coerceIn(0f, 1f)
            }.homeBackdropBlur { 16.dp.toPx() * menuBlur.floatValue }) {
            FolderTitle(folder.title, title, editing, onTitle = { title = it }, onStartEditing = {
                if (open) { title = folder.title; editing = true }
            }, onCommit = commitTitle, progress = progress)
            Spacer(Modifier.height(FOLDER_TITLE_GAP))
            Box(Modifier.size(side.dp)
                // iOS 26: a clear, lightly lifted slab over the blurred Home (round 16 reference), not graphite.
                .duoGlass(DuoGlassRole.FolderPanel, panelShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { if (editing) commitTitle() })
                .testTag("folder-panel-content")) {
                val padding = (side * .07f).dp
                Column(Modifier.fillMaxSize().padding(start = padding, end = padding, top = padding, bottom = 6.dp)) {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("folder-pager"),
                        beyondViewportPageCount = 1, key = { it }) { folderPage ->
                        val pageAppIds = folder.appIds.drop(folderPage * FOLDER_PAGE_CAPACITY).take(FOLDER_PAGE_CAPACITY)
                        Column(Modifier.fillMaxSize()) {
                            pageAppIds.chunked(3).forEach { rowIds ->
                                Row(Modifier.fillMaxWidth().weight(1f)) {
                                    rowIds.forEach { appId ->
                                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                            apps[appId]?.let { app ->
                                                FolderChild(app, folder.id, drag, page, chrome, childIconSize,
                                                    lifted = menuApp?.id == app.id, icons = childIcons,
                                                    onLaunch = { bounds -> if (!chrome.editing) onLaunch(app, bounds) },
                                                    onMenu = { onMenuApp(app.id) },
                                                    onRemove = { onRemoveApp(app) })
                                            }
                                        }
                                    }
                                    repeat(3 - rowIds.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                            repeat(3 - pageAppIds.chunked(3).size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(16.dp).testTag("folder-page-indicator"),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        if (pageCount > 1) repeat(pageCount) { index ->
                            val selected = pagerState.currentPage == index
                            Box(Modifier.padding(horizontal = 4.dp).size(7.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (selected) .95f else .38f))
                                .testTag("folder-page-dot-$index"))
                        }
                    }
                }
            }
        }
        val app = menuApp
        IosContextMenuHost(
            request = if (app == null || !open) null else IosMenuRequest(
                key = "folder-child-${app.id}", anchor = childIcons[app.id],
                sections = listOfNotNull(
                    // Round 12: hiding from inside a folder too (the app leaves the folder).
                    if (onHideApp != null && AppPrivacy.canHide(LocalContext.current, app)) listOf(
                        IosMenuAction(uxText("隐藏 App", "Hide App"), IosSymbols.eyeSlash, "folder-child-menu-hide") {
                            onMenuApp(null); onHideApp(app)
                        }) else null,
                    listOf(
                        IosMenuAction(uxText("App 信息", "App Info"), IosSymbols.info, "folder-child-menu-info") {
                            onMenuApp(null); onAppInfo(app)
                        },
                        IosMenuAction(uxText("编辑主屏幕", "Edit Home Screen"), IosSymbols.editHomeScreen, "folder-child-menu-edit-home") {
                            onMenuApp(null); onEditHome()
                        },
                    ),
                    listOf(IosMenuAction(uxText("移除 App", "Remove App"), IosSymbols.minusCircle, "folder-child-menu-remove",
                        destructive = true) { onMenuApp(null); onRemoveApp(app) }),
                ),
                overlayTag = "folder-child-overlay", menuTag = "folder-child-menu",
                preview = { Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize().clip(homeIconShape(childIconSize))) },
                previewShape = homeIconShape(childIconSize)),
            onDismissRequest = { onMenuApp(null) }, onProgress = { menuBlur.floatValue = it }, hidePreview = drag.moved)
    }
}

private val FOLDER_TITLE_HEIGHT: Dp = 56.dp
private val FOLDER_TITLE_GAP: Dp = 14.dp
/** The rename capsule overhangs the platter a little on each side (iOS 26 reference). */
private val FOLDER_FIELD_OVERHANG: Dp = 10.dp
/** The title's leading inset; the same inside the capsule, so the text does not jump when editing starts. */
private val FOLDER_TITLE_INSET: Dp = 14.dp

/**
 * How far the open folder lifts while renaming: only enough for the name field to clear the keyboard
 * by [clearance]; 0 whenever it is already visible (the usual case on every 18Duo screen).
 */
internal fun folderRenameLift(fieldBottom: Float, keyboardTop: Float, clearance: Float): Float =
    if (keyboardTop <= 0f) 0f else (fieldBottom + clearance - keyboardTop).coerceAtLeast(0f)

/**
 * Large white title above the platter, leading-aligned (iOS 14+). A tap turns it in place into the
 * iOS 26 rename field: a clear glass capsule a little wider than the platter, same text, blue caret,
 * round clear button.
 */
@Composable
private fun FolderTitle(saved: String, draft: String, editing: Boolean, onTitle: (String) -> Unit,
    onStartEditing: () -> Unit, onCommit: () -> Unit, progress: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>) {
    val style = TextStyle(color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
        fontFamily = DuoFonts.display, letterSpacing = duoTracking(30f),
        shadow = Shadow(Color.Black.copy(alpha = .28f), Offset(0f, 1f), 6f))
    val field by androidx.compose.animation.core.animateFloatAsState(if (editing) 1f else 0f,
        if (LauncherMotion.reduced) androidx.compose.animation.core.snap() else LauncherMotion.glassPress, label = "rename field")
    Box(Modifier.fillMaxWidth().height(FOLDER_TITLE_HEIGHT).graphicsLayer {
        alpha = ((progress.value - .35f) / .65f).coerceIn(0f, 1f)
    }, contentAlignment = Alignment.CenterStart) {
        if (editing) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            // The capsule grows out of the title (scale + fade), the text stays where it was.
            Row(Modifier
                .layout { measurable, constraints ->
                    val overhang = FOLDER_FIELD_OVERHANG.roundToPx()
                    val width = constraints.maxWidth + overhang * 2
                    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-overhang, 0) }
                }
                .height(FOLDER_TITLE_HEIGHT)
                .graphicsLayer { alpha = field; val s = .94f + .06f * field; scaleX = s; scaleY = s }
                .duoGlass(DuoGlassRole.FolderPanel, CircleShape)
                .padding(start = FOLDER_FIELD_OVERHANG + FOLDER_TITLE_INSET, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(draft, onTitle, Modifier.weight(1f).focusRequester(focus).testTag("folder-name"),
                    singleLine = true, textStyle = style.copy(shadow = null),
                    cursorBrush = SolidColor(Color(0xFF0A84FF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommit() }))
                if (draft.isNotEmpty()) ClearTextButton { onTitle("") }
            }
        } else {
            Text(saved, Modifier.padding(start = FOLDER_TITLE_INSET).fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStartEditing)
                .semantics { contentDescription = launcherFormat(R.string.compact_rename_folder) }
                .testTag("folder-rename"), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** iOS xmark.circle.fill clear button for text fields. */
@Composable
private fun ClearTextButton(onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        role = Role.Button, onClickLabel = uxText("清除文本", "Clear text"), onClick = onClick).testTag("folder-name-clear"),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            drawCircle(Color.White.copy(alpha = .62f))
            val s = size.minDimension * .28f
            val c = center
            val stroke = 1.6.dp.toPx()
            drawLine(Color(0xFF505055), Offset(c.x - s, c.y - s), Offset(c.x + s, c.y + s), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFF505055), Offset(c.x + s, c.y - s), Offset(c.x - s, c.y + s), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

@Composable
private fun FolderChild(
    app: AppEntry, folderId: String, drag: HomeDragState, page: Int, chrome: HomeEditChrome, iconSize: Float,
    lifted: Boolean, icons: HashMap<String, Rect>, onLaunch: (android.graphics.Rect?) -> Unit, onMenu: () -> Unit,
    onRemove: () -> Unit,
) {
    val motion = remember(app.id) { jiggleMotion("folder-${app.id}") }
    val launchBounds = remember { android.graphics.Rect() }
    val badge = removeBadgeGeometry(iconSize)
    Column(Modifier.fillMaxWidth().testTag("folder-child-${app.id}")
        .dropRegion(drag, DropTarget.Library(app.id), app.id, page, folderId = folderId, scope = folderId)
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            role = Role.Button,
            onClickLabel = if (app.available) launcherFormat(R.string.folder_open_app, app.label) else null,
            onLongClickLabel = launcherFormat(R.string.folder_app_actions, app.label),
            onClick = { if (app.available) onLaunch(launchBounds) },
            onLongClick = null)
        .semantics { onLongClick(launcherFormat(R.string.folder_app_actions, app.label)) { if (!chrome.editing) onMenu(); true } }
        .semantics { contentDescription = app.label + if (app.available) "" else ", " + launcherFormat(R.string.ui_unavailable) }
        .jiggle(chrome, motion),
        horizontalAlignment = Alignment.CenterHorizontally) {
        // A flight target (round 6): an app opened from the open folder shrinks back into this icon.
        Box(Modifier.size(iconSize.dp).homeIconTarget(app.id).onGloballyPositioned {
            val b = it.boundsInRoot()
            icons[app.id] = b
            launchBounds.set(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
        }.graphicsLayer { alpha = if (lifted) 0f else 1f }) {
            Image(app.icon.asImageBitmap(), null, Modifier.matchParentSize().clip(homeIconShape(iconSize)))
            if (chrome.decorated) RemoveBadge(uxText("移除 ${app.label}", "Remove ${app.label}"), "folder-remove-${app.id}",
                onRemove, Modifier.offset(badge.offsetX.dp, badge.offsetY.dp), diameter = badge.diameter.dp,
                intensity = chrome.intensity)
        }
        Text(app.label, Modifier.fillMaxWidth().padding(top = 5.dp, start = 2.dp, end = 2.dp),
            color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .35f), Offset(0f, 1f), 3f)))
        if (app.isWork || !app.available) Text(
            if (app.available) launcherText(app.profileLabel) else launcherFormat(R.string.ui_unavailable),
            color = Color.White.copy(alpha = .7f), fontSize = 8.sp, lineHeight = 9.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
