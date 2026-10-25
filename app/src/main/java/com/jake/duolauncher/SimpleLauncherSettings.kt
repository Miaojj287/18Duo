package com.jake.duolauncher

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FirstRunSetupSheet(isDefaultHome: Boolean, onMakeDefault: () -> Unit, onSkip: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    // Poll while open: the accessibility service binds asynchronously after the user enables it.
    val wizard by produceState(activity.setupWizardState, isDefaultHome) {
        while (true) { activity.refreshSetupChecks(); value = activity.setupWizardState; kotlinx.coroutines.delay(1000) }
    }
    SetupWizardSheet(wizard, onMakeDefault = onMakeDefault, onAccessibility = activity::openAccessibilitySettings,
        onGestures = activity::enableGesturesFromWizard, onHidePill = activity::showWirelessSetup,
        onSkip = onSkip, onComplete = onSkip, backgrounds = activity.backgrounds)
}

private enum class SettingsPage { MAIN, LAYOUT, APPEARANCE, SYSTEM, BACKUP }

@Composable
internal fun SimpleLauncherSettings(state: LauncherState, model: LauncherModel, isDefaultHome: Boolean,
    wallpaper: Boolean, onClose: () -> Unit, onMakeDefault: () -> Unit, onShadeSetup: () -> Unit,
    onWidgets: () -> Unit, onWallpaper: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    backgrounds: LauncherBackgroundController, onSystemWallpaper: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val preferences = activity.corePreferences
    var page by androidx.compose.runtime.saveable.rememberSaveable(wallpaper) {
        mutableStateOf(if (wallpaper) SettingsPage.APPEARANCE else SettingsPage.MAIN)
    }
    var confirmRecommended by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    val closePanel = LocalPanelClose.current ?: onClose
    val goBack = { if (page != SettingsPage.MAIN) page = SettingsPage.MAIN else closePanel() }
    ModalDialogBackHandler(goBack)
    IosAlertDialog(if (!confirmRecommended) null else IosAlertRequest("recommended-layout",
        stringResource(R.string.recommended_layout),
        uxText("按 iPhone Duo 的默认样式重新排列 Dock、桌面应用和文件夹；今日视图和第 1 页的空位放上默认小组件，已有小组件保留。可以撤销。",
            "Arrange the Dock, Home apps and folders the way iPhone Duo does; default widgets fill Today View and page 1 where there is room, existing widgets stay. You can undo this."), listOf(
            IosAlertAction(uxText("整理", "Arrange"), "recommended-layout-confirm") {
                model.applyRecommendedAppLayout(); confirmRecommended = false
            },
            IosAlertAction(uxText("取消", "Cancel"), "recommended-layout-cancel", IosAlertRole.Cancel) { confirmRecommended = false })),
        onDismiss = { confirmRecommended = false })
    fun pageTitle(current: SettingsPage) = when (current) {
        SettingsPage.MAIN -> uxText("设置", "Settings")
        SettingsPage.LAYOUT -> uxText("桌面与图标", "Home & icons")
        SettingsPage.APPEARANCE -> uxText("壁纸与外观", "Wallpaper & appearance")
        SettingsPage.SYSTEM -> uxText("系统与手势", "System & gestures")
        SettingsPage.BACKUP -> uxText("备份与关于", "Backup & about")
    }
    IosSheetScaffold(title = pageTitle(page), modifier = Modifier.testTag("settings-page"),
        leading = if (page != SettingsPage.MAIN) ({ IosBackButton(uxText("设置", "Settings"), goBack, "settings-more-back") }) else null,
        onDone = closePanel, doneTag = "settings-close", largeTitleScroll = scroll) {
        androidx.compose.animation.AnimatedContent(targetState = page, modifier = Modifier.weight(1f),
            transitionSpec = {
                // UINavigationController push/pop: the new page slides in fully from the trailing
                // edge on a critically damped spring while the old one drifts a third of the way out.
                val reduced = preferences.reduceMotion || LauncherMotion.reduced
                val forward = targetState != SettingsPage.MAIN
                val slide = LauncherMotion.navigationPush
                if (reduced) androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(0)) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(0))
                else androidx.compose.animation.slideInHorizontally(slide) { if (forward) it else -it / 3 } togetherWith
                    (androidx.compose.animation.slideOutHorizontally(slide) { if (forward) -it / 3 else it } +
                        androidx.compose.animation.fadeOut(LauncherMotion.dissolveOut))
            }, label = "settings navigation") { current ->
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                IosLargeTitle(pageTitle(current))
                when (current) {
                    SettingsPage.MAIN -> {
                        IosInsetGroup(uxText("开始使用 · 必要设置", "Get started · Essential setup")) {
                            val wizard = activity.setupWizardState
                            SettingsLink(IosSymbols.checklist, stringResource(R.string.wizard_reopen),
                                if (wizard.complete) stringResource(R.string.wizard_summary_done)
                                else stringResource(R.string.wizard_summary_pending, SetupStep.entries.size - wizard.completedCount),
                                "setup-wizard") { onClose(); activity.reopenSetup() }
                        }
                        IosInsetGroup(uxText("个性化", "Personalize")) {
                            SettingsLink(IosSymbols.editHomeScreen, uxText("桌面与图标", "Home & icons"), null, "settings-layout", IosUi.blue) { page = SettingsPage.LAYOUT }
                            IosSeparator()
                            SettingsEntry(IosSymbols.photo, R.string.editor_wallpaper, onClick = onWallpaper, tint = IosUi.teal)
                            IosSeparator()
                            SettingsEntry(IosSymbols.widgets, R.string.editor_widgets, onClick = onWidgets, tint = IosUi.purple)
                        }
                        IosInsetGroup(uxText("体验", "Experience")) {
                            SettingsLink(IosSymbols.gear, uxText("系统与手势", "System & gestures"), null, "setup-entry", IosUi.indigo) { page = SettingsPage.SYSTEM }
                            IosSeparator()
                            // Round 22: one switch. On the cover, Home held still in a tent for 5 s shows the clock
                            // (red in the dark); every other StandBy option is gone.
                            SettingToggle(IosSymbols.standby, uxText("站立时钟", "StandBy"), preferences.standbyEnabled,
                                activity::setAutomaticStandby, "settings-standby", IosUi.orange)
                            IosSeparator()
                            SettingToggle(IosSymbols.sparkles, uxText("折叠动效", "Fold animation"), preferences.foldAnimationEnabled,
                                preferences::setFoldAnimation, "settings-fold-animation", IosUi.pink)
                        }
                        IosInsetGroup {
                            SettingsLink(IosSymbols.save, uxText("备份与关于", "Backup & about"), null, "settings-more", IosUi.grey) { page = SettingsPage.BACKUP }
                            if (state.canUndoEdit) {
                                IosSeparator()
                                SettingsEntry(IosSymbols.undo, R.string.undo_layout, onClick = { model.undoEdit() }, tint = IosUi.yellow)
                            }
                        }
                    }
                    SettingsPage.LAYOUT -> {
                        IosInsetGroup(uxText("图标", "Icons"), footer = uxText(
                            "仅更换 18Duo 桌面内的图标；主题包未覆盖的应用继续显示原图标。",
                            "Changes icons inside 18Duo only. Apps missing from the pack keep their original icons.")) {
                            SettingToggle(IosSymbols.sparkles, uxText("苹果风格图标", "Apple-style icons"),
                                preferences.appleIconsEnabled, { enabled ->
                                    if (preferences.setAppleIcons(enabled)) model.refresh()
                                }, "apple-icons-switch", IosUi.pink)
                            IosSeparator()
                            SettingToggle(IosSymbols.textFormat, uxText("显示应用名", "Show app names"), state.labels, model::setLabels, "label-switch", IosUi.blue)
                        }
                        IosInsetGroup(uxText("小组件外观", "Widget appearance"),
                            footer = uxText("“自动”跟随系统的浅色或深色外观；“透明”使用 Liquid Glass 透明样式。",
                                "Automatic follows the system Light or Dark appearance; Clear uses the Liquid Glass look.")) {
                            WidgetStyle.entries.forEachIndexed { index, style ->
                                if (index > 0) IosSeparator(inset = 16.dp)
                                IosCheckRow(style.label, preferences.widgetStyle == style, { preferences.chooseWidgetStyle(style) },
                                    tag = "widget-style-${style.key}")
                            }
                        }
                        IosInsetGroup("Dock") {
                            SettingToggle(IosSymbols.history, uxText("显示最近使用的两个应用", "Show two recent apps"), preferences.showRecentApps,
                                preferences::setRecentApps, "recent-apps-switch", IosUi.green)
                            IosSeparator()
                            SettingsLink(IosSymbols.person, uxText("读取系统最近使用记录", "System app history"),
                                if (activity.recentApps.hasUsageAccess) uxText("已允许", "Allowed") else uxText("未允许 · 当前记录从桌面打开的应用", "Off · using apps opened from Home"), "recent-usage-access", IosUi.grey) {
                                activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        }
                        val leadingContents = state.leadingSlots.any { it != null } || state.widgetPlacements.any { it.page == -1 }
                        IosInsetGroup(uxText("页面", "Pages"),
                            footer = uxText("今日视图位于主屏幕第一页左侧，展开时显示在左半屏。关闭后会收起它，已有小组件会保留。",
                                "Today View sits left of Home page 1 and fills the left half when unfolded. Hiding it keeps its widgets.")) {
                            SettingToggle(IosSymbols.widgets, uxText("显示今日视图", "Show Today View"), preferences.widgetPageVisible(leadingContents),
                                preferences::setWidgetPage, "widget-page-switch", IosUi.purple)
                        }
                        IosInsetGroup(uxText("状态与反馈", "Status & feedback")) {
                            SettingToggle(IosSymbols.clock, uxText("显示状态信息", "Show status information"), state.verticalStatus, model::setVerticalStatus, "status-switch", IosUi.orange)
                            IosSeparator()
                            SettingToggle(IosSymbols.accessibility, uxText("减少动态效果", "Reduce motion"), preferences.reduceMotion,
                                preferences::setMotionReduction, "reduce-motion-switch", IosUi.grey)
                        }
                    }
                    SettingsPage.APPEARANCE -> WallpaperSettings(backgrounds, onSystemWallpaper)
                    SettingsPage.SYSTEM -> {
                        SnapshotCapabilityNotice()
                        IosInsetGroup(uxText("系统连接", "System connection")) {
                            RecommendedSetup(isDefaultHome, onMakeDefault, onShadeSetup, activity::showNavigationSetup)
                            IosSeparator()
                            SettingsLink(IosSymbols.wifi, uxText("无线调试授权", "Wireless authorization"), null, "wireless-setup", IosUi.blue) {
                                activity.showWirelessSetup()
                            }
                        }
                        IosInsetGroup {
                            SettingsLink(IosSymbols.arrowClockwise, uxText("重新打开配置向导", "Open setup again"), null, "reopen-setup", IosUi.grey) { onClose(); activity.reopenSetup() }
                            if (isDefaultHome) { IosSeparator(); SettingsEntry(IosSymbols.house, R.string.restore_system_home,
                                tag = "restore-system-home", onClick = activity::showRestoreSystemHome, tint = IosUi.grey) }
                        }
                    }
                    SettingsPage.BACKUP -> {
                        IosInsetGroup(uxText("布局备份", "Layout backup")) {
                            SettingsEntry(IosSymbols.save, R.string.backup_save, onClick = onExport, tint = IosUi.blue)
                            IosSeparator()
                            SettingsEntry(IosSymbols.arrowClockwise, R.string.backup_restore, onClick = onImport, tint = IosUi.green)
                            IosSeparator()
                            SettingsEntry(IosSymbols.editHomeScreen, R.string.recommended_layout,
                                tag = "apply-recommended-layout", onClick = { confirmRecommended = true }, tint = IosUi.orange)
                        }
                        EighteenDuoAbout()
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperSettings(backgrounds: LauncherBackgroundController, onSystemWallpaper: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision = LauncherBackgroundCache.revision.intValue
    val committed = remember(revision) { cachedLauncherBackground(context) }
    val preview = backgrounds.previewBitmap ?: committed
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(240.dp).clip(ContinuousRoundedShape(24.dp)).testTag("wallpaper-preview-card")) {
        DuneWallpaper(Modifier.fillMaxSize())
        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            // Legible on bright photos too: the same soft shadow as Home Screen labels.
            val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .45f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)
            Text(uxText("你的桌面", "Your Home"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(shadow = shadow))
            Text(if (backgrounds.previewPending) uxText("预览", "Preview") else uxText("当前壁纸", "Current wallpaper"), color = Color.White.copy(alpha = .9f),
                style = MaterialTheme.typography.bodySmall.copy(shadow = shadow))
        }
    }
    IosSectionHeader(uxText("壁纸", "Wallpaper"))
    val selected = when {
        backgrounds.previewPending -> "photo"
        backgrounds.photoSelected -> "photo"
        else -> "dunes"
    }
    IosChipRow(listOf(
        IosChipItem("photo", uxText("照片", "Photos"), "wallpaper-chip-photo") {
            IosChipGlyph(IosSymbols.photo, IosUi.teal)
        },
        IosChipItem("dunes", uxText("沙丘", "Dunes"), "wallpaper-chip-dunes") { BundledDunesThumb() },
        IosChipItem("system", uxText("系统壁纸", "System"), "wallpaper-chip-system") {
            IosChipGlyph(IosSymbols.iphone, IosUi.indigo)
        },
    ), selectedKey = selected, onSelect = { item -> when (item.key) {
        "photo" -> backgrounds.choosePhoto()
        "dunes" -> backgrounds.reset()
        else -> onSystemWallpaper()
    } })
    IosFooter(uxText("照片仅保存在本机，不会写入布局备份。弹窗与设置的玻璃深浅会跟随壁纸亮度自动变化。",
        "Photos stay on this device and are not included in layout backups. Sheets pick dark or light glass from the wallpaper automatically."))
    // Keep the system Home + Lock wallpaper equal to 18Duo's (WallpaperSync); on by default.
    var systemSync by remember { mutableStateOf(WallpaperSync.enabled(context)) }
    IosInsetGroup(footer = uxText("开启后，更换 18Duo 壁纸时也会设为系统桌面和锁屏壁纸，并按外屏、内屏分别裁切。",
        "When on, changing the 18Duo wallpaper also sets the system Home and Lock Screen, cropped for each screen.")) {
        IosToggleRow(uxText("同步到系统桌面和锁屏", "Sync to System Home & Lock Screen"), systemSync,
            { systemSync = it; WallpaperSync.setEnabled(context, it) }, icon = IosSymbols.iphone, tint = IosUi.indigo,
            tag = "wallpaper-system-sync")
    }
    if (backgrounds.previewPending) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IosButton(uxText("取消", "Cancel"), backgrounds::cancelPreview, Modifier.weight(1f), filled = false, tag = "background-preview-cancel")
        IosButton(uxText("应用壁纸", "Apply wallpaper"), backgrounds::applyPreview, Modifier.weight(1f), tag = "background-preview-apply")
    }
    if (backgrounds.loading) Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        IosActivityIndicator(color = iosSecondaryText())
    }
    (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { IosFooter(it) }
}

@Composable
private fun SettingsLink(icon: ImageVector, title: String, detail: String? = null, tag: String, tint: Color = IosUi.grey,
    onClick: () -> Unit) {
    // Status sentences read as a subtitle (iOS Settings does the same for long states); a trailing
    // value would squeeze the title, especially with Chinese text or a 1.3× font scale.
    IosRow(title, icon = icon, tint = tint, detail = detail, tag = tag, onClick = onClick)
}

@Composable
private fun SettingToggle(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String,
    tint: Color = IosUi.grey) {
    IosToggleRow(title, checked, onChange, icon = icon, tint = tint, tag = tag)
}

@Composable
internal fun FoldAnimationSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(R.string.duo18_fold_animation)
    IosToggleRow(title, enabled, onChange, icon = IosSymbols.sparkles, tint = IosUi.pink,
        detail = stringResource(R.string.duo18_fold_animation_detail), switchTag = "settings-fold-animation",
        contentDescription = title)
}

@Composable
internal fun RecommendedSetup(isDefaultHome: Boolean, onMakeDefault: () -> Unit,
    onAccessibility: () -> Unit, onGestures: () -> Unit, firstRun: Boolean = false) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val connected by produceState(SystemShadeAccessibilityService.isConnected()) {
        while (true) { value = SystemShadeAccessibilityService.isConnected(); kotlinx.coroutines.delay(1000) }
    }
    if (firstRun) {
        IosLargeTitle(uxText("开始使用", "Get Started"))
        Text(stringResource(R.string.setup_explanation), style = MaterialTheme.typography.bodyMedium)
        IosFooter(uxText("长按桌面空白处 2 秒，再选择设置，可随时继续配置。", "Hold an empty area of Home for 2 seconds, then Settings to continue setup."),
            Modifier.padding(horizontal = 0.dp))
    }
    val gestureMode = android.provider.Settings.Global.getInt(activity.contentResolver, "force_fsg_nav_bar", 0) == 1
    val rows: @Composable ColumnScope.() -> Unit = {
        SettingsEntry(IosSymbols.house, R.string.setup_default, null,
            if (isDefaultHome) R.string.setup_enabled else R.string.setup_needed,
            "setup-default", onMakeDefault, tint = IosUi.blue)
        IosSeparator()
        SettingsEntry(IosSymbols.accessibility, R.string.setup_accessibility, null,
            if (connected) R.string.setup_enabled else R.string.setup_needed,
            "setup-accessibility", onAccessibility, tint = IosUi.green)
        IosSeparator()
        // Round 22: only the state, no explanation under the title.
        IosRow(uxText("全面屏手势", "Full-screen gestures"), icon = IosSymbols.swipeUp, tint = IosUi.indigo,
            value = if (isDefaultHome && connected && gestureMode) uxText("已开启", "Enabled") else uxText("未开启", "Not enabled"),
            tag = "setup-gestures", onClick = onGestures)
    }
    if (firstRun) IosInsetGroup(uxText("必要设置", "Essential setup"), content = rows) else Column(content = rows)
}

@Composable
internal fun SettingsEntry(icon: ImageVector, title: Int, detail: Int? = null, status: Int? = null,
    tag: String = "settings-entry-$title", onClick: () -> Unit, tint: Color = IosUi.grey) {
    IosRow(stringResource(title), icon = icon, tint = tint, detail = detail?.let { stringResource(it) },
        value = status?.let { stringResource(it) }, tag = tag, onClick = onClick)
}

/** The bundled iPhone Duo dunes, never the user's photo (DuneWallpaper draws the committed background). */
@Composable
internal fun BundledDunesThumb() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dunes = remember(context) { bundledDefaultWallpaper(context.applicationContext)?.asImageBitmap() }
    if (dunes != null) Image(dunes, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    else DuneWallpaper(Modifier.fillMaxSize())
}
