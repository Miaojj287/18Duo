package com.jake.duolauncher

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/**
 * [IosAlertHost] in its own window, so an alert raised while a settings sheet (itself a Dialog
 * window) is open still appears above it — the role Material's AlertDialog used to play. The
 * window stays for the alert's exit spring, then goes away. It draws its own 22 % dim, so the
 * platform's dialog dim is turned off.
 */
@Composable
internal fun IosAlertDialog(request: IosAlertRequest?, onDismiss: () -> Unit) {
    var hosted by remember { mutableStateOf(request != null) }
    LaunchedEffect(request?.key) {
        if (request != null) hosted = true
        else if (hosted) { delay(if (LauncherMotion.reduced) 0L else 320L); hosted = false }
    }
    if (!hosted && request == null) return
    val bars = rememberHostSystemBars()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            ((view.parent as? DialogWindowProvider)?.window ?: (view as? DialogWindowProvider)?.window)?.let { window ->
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setTitle(request?.title ?: "")
                bars.applyTo(window)
            }
        }
        // A separate window cannot sample the sheet underneath (only the Home backdrop), which
        // tinted the glass with wallpaper colours over Settings; nor can it blur it. So the body is opaque.
        CompositionLocalProvider(LocalDuoGlassBackdrop provides null) { IosAlertHost(request, onDismiss, opaque = true) }
    }
}

/** A single-button informational alert ("好"/"OK"). */
internal fun iosNoticeRequest(key: Any, title: String, message: String?, onOk: () -> Unit, okTag: String = "ios-alert-ok") =
    IosAlertRequest(key, title, message, listOf(IosAlertAction(launcherText("OK"), okTag, IosAlertRole.Default, onOk)))

/**
 * The interruption/failure prompts Home can raise (document or photo picker interrupted, backup
 * result, widget binding failure, widget setup/settings waiting). They were Material
 * AlertDialogs; they are now iOS 26 glass alerts with the same actions and test tags.
 */
/** How long Home must be back in front with a widget setup still pending before it counts as interrupted. */
internal const val WIDGET_SETUP_INTERRUPT_GRACE_MS = 800L

/**
 * The "Finish widget setup" prompt is for a setup the user walked away from (process death, Home
 * pressed in the bind/configure screen). It used to appear the moment binding started — behind
 * Android's bind dialog — and its window then lingered after a successful bind, swallowing the
 * first tap on the new widget. Now it needs Home resumed for [WIDGET_SETUP_INTERRUPT_GRACE_MS]
 * with the setup still pending; a normal result arrives before onResume and never shows it.
 */
internal fun widgetSetupInterrupted(pending: Boolean, resumed: Boolean, resumedForMillis: Long): Boolean =
    pending && resumed && resumedForMillis >= WIDGET_SETUP_INTERRUPT_GRACE_MS

@Composable
internal fun LauncherSystemAlerts(activity: MainActivity, widgets: WidgetController, onLeaveTemporaryWidgetPage: () -> Unit) {
    val backups = activity.backups
    val backgrounds = activity.backgrounds
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val setupPending = widgets.pendingPlacement != null && widgets.setupStatus != null
    var setupInterrupted by remember { mutableStateOf(false) }
    LaunchedEffect(setupPending, resumed) {
        setupInterrupted = false
        if (setupPending && resumed) {
            delay(WIDGET_SETUP_INTERRUPT_GRACE_MS)
            setupInterrupted = widgetSetupInterrupted(true, true, WIDGET_SETUP_INTERRUPT_GRACE_MS)
        }
    }
    val request: IosAlertRequest? = when {
        backups.pickerPending -> IosAlertRequest("backup-picker", launcherText("Layout document"),
            launcherText("The system document picker is still open. Return to it to finish, or cancel this operation."), listOf(
                IosAlertAction(launcherText("Resume"), "backup-picker-resume") { backups.resumePendingPicker() },
                IosAlertAction(launcherText("Cancel"), "backup-picker-cancel", IosAlertRole.Cancel) { backups.cancelImport() }))
        backgrounds.pickerPending && !backgrounds.loading -> IosAlertRequest("background-picker",
            launcherText("Background photo"),
            launcherText("The photo picker was interrupted. Resume choosing a photo, or cancel and keep the current background."), listOf(
                IosAlertAction(launcherText("Resume"), "background-picker-resume") { backgrounds.choosePhoto() },
                IosAlertAction(launcherText("Cancel"), "background-picker-cancel", IosAlertRole.Cancel) { backgrounds.cancelPendingSelection() }))
        (backups.errorMessage ?: backups.successMessage) != null -> iosNoticeRequest("backup-message-${backups.errorMessage ?: backups.successMessage}",
            if (backups.errorMessage != null) launcherText("Layout backup problem") else launcherText("Layout backup"),
            backups.errorMessage ?: backups.successMessage, onOk = backups::clearMessage, okTag = "backup-message-ok")
        widgets.failureMessage != null -> iosNoticeRequest("widget-failure-${widgets.failureMessage}",
            launcherText("Widget not added"), widgets.failureMessage, onOk = widgets::clearFailure, okTag = "widget-bind-error")
        setupPending && setupInterrupted -> IosAlertRequest("widget-setup",
            launcherText("Finish widget setup"),
            launcherText("The widget is waiting at its chosen spot. Finish setup to add it, or cancel to remove the placeholder."), listOf(
                IosAlertAction(launcherText("Finish setup"), "widget-setup-finish") { widgets.finishPendingSetup() },
                IosAlertAction(launcherText("Cancel"), "widget-setup-cancel", IosAlertRole.Cancel) {
                    onLeaveTemporaryWidgetPage(); widgets.cancelPendingSetup()
                }))
        widgets.reconfigureWidgetId != null -> IosAlertRequest("widget-reconfigure", launcherText("Widget settings"),
            launcherText("Widget settings were interrupted. Resume configuration, or cancel and keep the widget unchanged."), listOf(
                IosAlertAction(launcherText("Resume"), "widget-reconfigure-resume") { widgets.finishPendingReconfigure() },
                IosAlertAction(launcherText("Cancel"), "widget-reconfigure-cancel", IosAlertRole.Cancel) { widgets.cancelPendingReconfigure() }))
        else -> null
    }
    // Outside taps never dismiss these (they guard pending work), matching the old onDismissRequest = {}.
    IosAlertDialog(request, onDismiss = {
        when {
            (backups.errorMessage ?: backups.successMessage) != null -> backups.clearMessage()
            widgets.failureMessage != null -> widgets.clearFailure()
        }
    })
}

/** Review before a layout restore replaces Home (was a Material AlertDialog). */
@Composable
internal fun LayoutRestorePreview(preview: LayoutImportPreview, onRestore: () -> Unit, onCancel: () -> Unit) {
    val lines = buildList {
        add(launcherFormat(R.string.backup_counts, preview.appCount, preview.folderCount, preview.widgetCount))
        if (preview.layout.leadingSlots.any { it != null } || preview.layout.widgetPlacements.any { it.page == -1 })
            add(launcherFormat(R.string.backup_leading))
        add(launcherFormat(R.string.backup_settings))
        add(launcherFormat(R.string.backup_no_photo))
        if (preview.missingApps.isNotEmpty()) {
            add(launcherFormat(R.string.backup_missing, preview.missingApps.size))
            preview.missingApps.forEach { saved ->
                val label = saved.substringAfterLast('(').removeSuffix(")").takeIf { it.isNotBlank() }
                    ?: uxText("不可用的应用", "Unavailable app")
                add(launcherFormat(R.string.backup_empty_slot, label))
            }
        }
        if (preview.profileIssues.isNotEmpty()) add(launcherFormat(R.string.backup_profile))
        val reconnect = preview.layout.widgetPlacements.count { it.id == NEEDS_BINDING_WIDGET }
        if (reconnect > 0) add(launcherFormat(R.string.backup_widget_reconnect, reconnect))
        add(launcherFormat(R.string.backup_confirm_help))
    }
    IosAlertDialog(IosAlertRequest("layout-restore-preview", launcherFormat(R.string.backup_preview), lines.joinToString("\n\n"),
        listOf(IosAlertAction(launcherText("Restore"), "layout-restore-apply", IosAlertRole.Default, onRestore),
            IosAlertAction(launcherText("Cancel"), "layout-restore-cancel", IosAlertRole.Cancel, onCancel))),
        onDismiss = onCancel)
}

/**
 * Which system bars the window under a dialog had hidden. A Dialog is its own focused window, and
 * Android shows the bars a focused window has not hidden — so the Weather/widget alerts used to
 * bring back the status bar and the navigation bar / taskbar over Home's immersive state (round-3
 * review 08, 12, 19). The dialog repeats the host's choice instead, with the same swipe-to-peek behaviour.
 */
internal class HostSystemBars(val statusHidden: Boolean, val navigationHidden: Boolean) {
    fun applyTo(window: android.view.Window) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (statusHidden) controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        if (navigationHidden) controller.hide(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
    }
}

/**
 * The bar state of the window this composition draws in. Home always hides the navigation bar
 * (MainActivity.setStatusMode) — read from the live insets it could look visible for a moment
 * while a keyboard closes (the Enter City → results step) — and hides the status bar unless the
 * user chose the horizontal status layout. A settings sheet (its own Dialog window) is mirrored as it is.
 */
@Composable
internal fun rememberHostSystemBars(): HostSystemBars {
    val view = LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    return remember(view, activity) {
        val insets = view.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view) }
        val onHome = activity is MainActivity && view.rootView === activity.window.decorView
        HostSystemBars(
            statusHidden = insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == false,
            navigationHidden = onHome || insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()) == false)
    }
}
