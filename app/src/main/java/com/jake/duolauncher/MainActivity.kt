package com.jake.duolauncher

import android.app.role.RoleManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    internal val recentApps by lazy { RecentAppsController(applicationContext) }
    internal val corePreferences by lazy { CorePreferences(this) }
    internal lateinit var panelOrientation: PanelOrientationController
    private val model: LauncherModel by viewModels()
    private lateinit var widgets: WidgetController
    internal lateinit var backups: BackupController
        private set
    internal lateinit var backgrounds: LauncherBackgroundController
        private set
    private val homeRequests = mutableIntStateOf(0)
    /**
     * Whether the latest Home intent brought Home back from another app (true) or arrived while Home was
     * already in front (false) — Launcher3's "already on home" test. LauncherScreen keeps the App Library
     * search across a launch-and-return only in the first case (keepsLibrarySearchOnHome).
     */
    internal var homeIntentFromApp = false
        private set
    private val searchRequests = mutableIntStateOf(0)
    private val settingsRequests = mutableIntStateOf(0)
    private val defaultHome = mutableStateOf(false)
    private val navigationServiceEnabled = mutableStateOf(false)
    private val gestureModeEnabled = mutableStateOf(false)
    /** Re-read on every resume so the wizard advances when the user returns from system Settings. */
    internal val setupWizardState: SetupWizardState
        get() = SetupWizardState(defaultHome.value, navigationServiceEnabled.value, gestureModeEnabled.value,
            SetupExperience.wallpaperStepDone(this))
    private val showFirstRun = mutableStateOf(false)
    private lateinit var setupExperience: SetupExperience
    private lateinit var status: DeviceStatusMonitor
    private lateinit var appearance: AppearanceStore
    private var shadeSetupDialog: android.app.AlertDialog? = null
    private var permissionGuideDialog: android.app.AlertDialog? = null
    private var returningFromShadeSettings = false
    /** Highest panel refresh rate (120 Hz) while Home is touched or animating; system default at rest. */
    private val refreshBoost = DisplayRefreshBoost.forActivity(this)

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        refreshBoost.onInput()
        panelOrientation.noteUserActivity()
        UnlockArrival.onUserTouch()
        return super.dispatchTouchEvent(event)
    }

    private val standbyPreferencesChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        corePreferences.refresh()
    }
    private val clockNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    internal fun setAutomaticStandby(enabled: Boolean) = corePreferences.setStandby(enabled)
    internal fun openLockClockPermissions() {
        val intent = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) { openPermissionAppDetails() }
    }
    internal fun enableClockNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            clockNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherMotion.reduced = corePreferences.reduceMotion
        LauncherMotion.attachSystemSettings(this)
        HomeSnapshotStore.attach(this)
        // Round 18: a recreated Home starts without its apps until the model has loaded them.
        if (model.state.value.loading) NavigationProgressBus.homeContentReset()
        AppPrivacy.load(this)
        UnlockArrivalWatcher.attach(this)
        LiveLocale.attach(this, this)
        configureUsbCover(intent)
        getSharedPreferences("duo18_core_preferences", 0).registerOnSharedPreferenceChangeListener(standbyPreferencesChanged)
        panelOrientation = PanelOrientationController(this)
        panelOrientation.refresh()
        setupExperience = SetupExperience(this)
        showFirstRun.value = setupExperience.entryDecision(SetupExperience.hadLauncherState(this)) ==
            SetupEntryDecision.SHOW ||
            // A reopened wizard survives the recreation a system wallpaper (theme colour) change causes.
            savedInstanceState?.getBoolean("duo.setup_visible") == true
        StandbyMonitorService.sync(this, !showFirstRun.value)
        panelOrientation.setStandbyAllowed(!showFirstRun.value && corePreferences.standbyEnabled)
        returningFromShadeSettings = savedInstanceState?.getBoolean(SHADE_SETTINGS_PENDING) == true
        val restoreShadeDialog = savedInstanceState?.getBoolean(SHADE_DIALOG_VISIBLE) == true
        appearance = AppearanceStore(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        widgets = WidgetController(this, model) { _ -> }.also { it.restore(savedInstanceState) }
        backups = BackupController(this, model, widgets) { _ -> }.also { it.restore() }
        backgrounds = LauncherBackgroundController(this) { _ -> }
        status = DeviceStatusMonitor(this).also { lifecycle.addObserver(it) }
        updateDefaultHome(); refreshSetupChecks()
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "standby") panelOrientation.enterStandby()
        if (savedInstanceState == null && (intent.getStringExtra("duo_destination") == "settings" ||
                intent.hasCategory(Intent.CATEGORY_LAUNCHER))) settingsRequests.intValue++
        intent.removeExtra("duo_destination")
        // Round 22: gate Compose's accessibility work from Home's first frame (see ComposeAccessibilityGate).
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                ComposeAccessibilityGate.install(window.decorView)
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            val desktopView = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.DisposableEffect(desktopView) {
                refreshBoost.contentView = desktopView
                onDispose { if (refreshBoost.contentView === desktopView) refreshBoost.contentView = null }
            }
            // Round 22: StandBy is one switch (Home on the cover only, fixed tent angles); the locked-clock monitor is gone.
            val standbyAllowed = !showFirstRun.value && corePreferences.standbyEnabled
            androidx.compose.runtime.SideEffect {
                panelOrientation.setStandbyAllowed(standbyAllowed)
                StandbyMonitorService.sync(this, !showFirstRun.value)
            }
            val state = model.state.collectAsStateWithLifecycle().value
            // Round 18: the overlay keeps its frozen Home until the live one shows its apps.
            if (!state.loading) androidx.compose.runtime.SideEffect { NavigationProgressBus.homeContentLoaded(window.decorView) }
            val deviceStatus = status.state.collectAsStateWithLifecycle().value
            androidx.compose.runtime.LaunchedEffect(state.apps) { recentApps.refresh(state.apps) }
            // Launch-screen colours come from other apps' themes: resolve them off the main thread
            // once, so a tap never has to (the launch zoom's card wears them).
            androidx.compose.runtime.LaunchedEffect(state.apps) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    state.apps.filter { it.isPersonal && it.available }.forEach { SplashColors.color(this@MainActivity, it.component) }
                }
            }
            androidx.compose.runtime.SideEffect { LauncherMotion.reduced = corePreferences.reduceMotion }
            // Sheets and dialogs follow the wallpaper: refresh the derived palette when it changes.
            val backgroundRevision = LauncherBackgroundCache.revision.intValue
            androidx.compose.runtime.LaunchedEffect(backgroundRevision) { appearance.refresh() }
            DuoTheme(appearance.state.dark) {
                StandbySurfaceHost(panelOrientation.surface.value, onVeiled = panelOrientation::standbyVeiled,
                standby = { active -> StandbyScreen(onExit = panelOrientation::dismissStandby,
                    target = panelOrientation.standbyTarget.value, active = active) },
                home = {
                LauncherScreen(state, model, widgets, homeRequests.intValue,
                    onLaunch = { launchApp(it) }, onMakeDefault = ::makeDefault, onAppInfo = ::appInfo,
                    isDefaultHome = defaultHome.value, deviceStatus = deviceStatus, onStatusMode = ::setStatusMode, onWallpaperPreview = ::previewWallpaper,
                    searchRequests = searchRequests.intValue,
                    settingsRequests = settingsRequests.intValue,
                    onLaunchFrom = ::launchApp,
                    showFirstRun = showFirstRun.value,
                    onFinishFirstRun = ::finishFirstRun,
                    onShadeSetup = ::showShadeSetup)
                })
                InnerStandbyOverlay(panelOrientation.innerStandby.value, panelOrientation.innerStandbyTarget.value,
                    onExit = panelOrientation::dismissInnerStandby)
                ConsumeLauncherRequests(searchRequests, settingsRequests)
            }
        }
        if (restoreShadeDialog) window.decorView.post { if (!isFinishing && !isDestroyed) showShadeSetup() }
    }

    override fun onStart() {
        super.onStart(); widgets.host.startListening()
        refreshBoost.attach()
        StandbyMonitorService.sync(this, !showFirstRun.value)
        panelOrientation.start()
        appearance.refresh()
    }
    override fun onStop() {
        refreshBoost.detach()
        panelOrientation.stop()
        widgets.host.stopListening(); super.onStop()
    }
    override fun onDestroy() {
        panelOrientation.destroy()
        getSharedPreferences("duo18_core_preferences", 0).unregisterOnSharedPreferenceChangeListener(standbyPreferencesChanged)
        shadeSetupDialog?.dismiss()
        permissionGuideDialog?.dismiss()
        HomeSnapshotStore.detach(this)
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        // Round 22: no semantics-tree work for services that cannot read it (see ComposeAccessibilityGate).
        ComposeAccessibilityGate.install(window.decorView)
        if (returningFromShadeSettings) {
            returningFromShadeSettings = false
        }
        // The navigation overlay hides the home indicator and hands its frozen Home back to us.
        NavigationProgressBus.homeResumed(android.os.SystemClock.uptimeMillis())
        // The overlay keeps covering Home until this window is really back on screen (round 3, item 4).
        window.decorView.viewTreeObserver.registerFrameCommitCallback {
            NavigationProgressBus.homeDrawn(android.os.SystemClock.uptimeMillis())
        }
        window.decorView.invalidate()
        if (hasWindowFocus()) NavigationProgressBus.homeFocused(android.os.SystemClock.uptimeMillis())
        // Round 12.1: a flick on Home asks for the first page here, in-process (no intent, no system action).
        NavigationProgressBus.inProcessHome = { homeIntentFromApp = false; homeRequests.intValue++ }
        // A card is flying into Home right now: keep this thread free for Home's first frames and
        // the icon hand-back; the refreshes below change nothing the user sees in that half second.
        val flying = AppOpenZoomController.state.value?.direction == AppOpenDirection.GESTURE
        AppOpenZoomController.launcherResumed(android.os.SystemClock.uptimeMillis())
        panelOrientation.refresh()
        val refreshes = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            model.refresh(); appearance.refresh(); updateDefaultHome()
            recentApps.reload()
            lifecycleScope.launch { recentApps.refresh(model.state.value.apps) }
        }
        if (flying) window.decorView.postDelayed(refreshes, RESUME_REFRESH_DELAY) else refreshes.run()
        // The service binds a moment after it is enabled; poll briefly so the wizard catches up.
        lifecycleScope.launch { repeat(6) { refreshSetupChecks(); kotlinx.coroutines.delay(500) } }
    }

    internal fun refreshSetupChecks() {
        navigationServiceEnabled.value = SystemShadeAccessibilityService.isConnected() ||
            SystemShadeAccessibilityService.isEnabled(this)
        gestureModeEnabled.value = Settings.Global.getInt(contentResolver, "force_fsg_nav_bar", 0) == 1
    }
    override fun onPause() {
        // Leaving Home some other way (notification, shortcut): keep the overlay's frozen Home current —
        // but never copy a launch card that is covering Home right now as "Home".
        if (AppOpenZoomController.state.value == null &&
            android.os.SystemClock.uptimeMillis() - (HomeSnapshotStore.latest?.capturedAt ?: 0L) > 1_500L) HomeSnapshotStore.capture()
        NavigationProgressBus.inProcessHome = null
        NavigationProgressBus.homePaused()
        AppOpenZoomController.launcherPaused()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        panelOrientation.refresh()
    }

    internal fun openSystemShade(panel: ShadePanel) {
        when (SystemShadeAccessibilityService.open(this, panel)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> IosBanner.show(launcherText("Shade gestures are starting. Swipe down again."))
            ShadeOpenResult.ACTION_REJECTED -> IosBanner.show(launcherText("Android couldn’t open the system panel."))
        }
    }

    internal fun showShadeSetup() {
        if (shadeSetupDialog?.isShowing == true) return
        shadeSetupDialog = nativeDialogBuilder(this)
            .setTitle(R.string.setup_accessibility)
            .setMessage(getString(R.string.shade_service_description) + "\n\n" + getString(R.string.setup_restricted_help))
            .setNeutralButton(R.string.setup_app_details) { _, _ -> openPermissionAppDetails() }
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    returningFromShadeSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    returningFromShadeSettings = false
                    IosBanner.show(launcherText("Accessibility settings are unavailable."), long = true)
                }
            }
            .also { dialog -> dialog.setOnDismissListener {
                shadeSetupDialog = null
            } }
            .show().also { styleNativeDialog(it) }
    }

    /** Wizard step 2: straight to the Accessibility list; the sheet carries the instructions. */
    internal fun openAccessibilitySettings() {
        try {
            returningFromShadeSettings = true
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            returningFromShadeSettings = false
            showShadeSetup()
        }
    }

    /** Wizard step 3: enable the overlay when the service is up, otherwise go to system navigation settings. */
    internal fun enableGesturesFromWizard() {
        if (SystemShadeAccessibilityService.isConnected() && (gestureModeEnabled.value ||
                checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)) {
            SystemShadeAccessibilityService.enableNavigationFromSetup(); refreshSetupChecks(); return
        }
        // HyperOS hosts 系统导航方式 in the system launcher package; AOSP keeps it in Settings.
        for (candidate in listOf(
                Intent().setClassName("com.miui.home", "com.miui.home.recents.settings.NavigationBarTypeActivity"),
                Intent().setClassName("com.android.settings", "com.android.settings.Settings\$NavigationModeSettingsActivity"),
                Intent(Settings.ACTION_SETTINGS))) {
            try { startActivity(candidate); return } catch (_: Exception) { }
        }
        IosBanner.show(getString(R.string.system_settings_unavailable), long = true)
    }

    private fun configureUsbCover(incoming: Intent) {
        val token = incoming.getStringExtra("duo_usb_cover_token") ?: return
        incoming.removeExtra("duo_usb_cover_token")
        if (!token.matches(Regex("[a-f0-9]{64}"))) return
        val store = getSharedPreferences("usb_cover_pairing", 0)
        val previous = store.getString("token", null)
        // A local USB provisioning command can initialize the pairing. Replacing an
        // established pairing requires the explicit reset button in settings.
        if (previous == null || previous == token) store.edit().putString("token", token).apply()
    }

    private fun finishFirstRun() {
        setupExperience.finish()
        showFirstRun.value = false
        panelOrientation.setStandbyAllowed(corePreferences.standbyEnabled)
        StandbyMonitorService.sync(this)
    }

    internal fun reopenSetup() {
        panelOrientation.dismissStandby()
        refreshSetupChecks()
        showFirstRun.value = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) NavigationProgressBus.homeFocused(android.os.SystemClock.uptimeMillis())
        UnlockArrival.onHomeFocusChanged(this, hasFocus)
        // StandBy keeps the bars hidden itself; showing Home's status bar here put it back over the clock.
        if (hasFocus && !panelOrientation.standbyReady.value) setStatusMode(model.state.value.verticalStatus)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        outState.putBoolean(SHADE_DIALOG_VISIBLE, shadeSetupDialog?.isShowing == true && !returningFromShadeSettings)
        outState.putBoolean(SHADE_SETTINGS_PENDING, returningFromShadeSettings)
        outState.putBoolean("duo.setup_visible", showFirstRun.value)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureUsbCover(intent)
        panelOrientation.noteUserActivity()
        if (intent.getStringExtra("duo_destination") == "search") { panelOrientation.dismissStandby(); searchRequests.intValue++ }
        else if (intent.getStringExtra("duo_destination") == "standby") panelOrientation.enterStandby()
        else if (intent.getStringExtra("duo_destination") == "settings" || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            panelOrientation.dismissStandby()
            settingsRequests.intValue++
        }
        else if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getStringExtra("duo_destination") == "home") {
            // A launcher sheet (Settings, Dock, pins) is a dialog window holding focus: Home is still in front.
            val inFront = hasWindowFocus() ||
                IosBanner.panels > 0 && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            homeIntentFromApp = !(inFront && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0)
            homeRequests.intValue++
        }
        intent.removeExtra("duo_destination")
    }

    @Deprecated("Widget configuration uses the platform host request-code API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun launchApp(app: AppEntry, bounds: android.graphics.Rect? = null) {
        // A locked app (iOS "要求面容 ID") opens only after the owner proves themselves; the zoom follows.
        if (AppPrivacy.needsAuthentication(app.id)) {
            val held = bounds?.let { android.graphics.Rect(it) }
            AppPrivacy.authenticate(this, uxText("打开“${app.label}”", "Open “${app.label}”")) { ok ->
                if (ok && !isFinishing) { AppPrivacy.grantLaunch(app.id); launchApp(app, held) }
            }
            return
        }
        // A copy: the tile keeps rewriting its Rect (and may be clipped away while Spotlight closes).
        val source = bounds?.takeUnless { it.isEmpty }?.let { android.graphics.Rect(it) }
        // Opened from a widget (armed just now): the card starts as the widget and the app returns into it.
        val widget = WidgetLaunchTiles.take(app.id)
        if (LauncherMotion.reduced || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            // Reduce Motion (iOS): no zoom; the app's launch screen dissolves in over Home and the
            // app replaces it in place (Android's own transition would slide it in sideways).
            startDissolve(app, source); return
        }
        val screen = if (source != null) screenBounds(source) else null
        if (screen != null && SystemShadeAccessibilityService.canAnimateLaunch()) {
            // iOS zoom drawn by the navigation overlay above everything.
            val icon = ZoomBounds(screen.left.toFloat(), screen.top.toFloat(), screen.right.toFloat(), screen.bottom.toFloat())
            // 1) Home exactly as it is, icon included: the overlay's card starts as that very icon.
            HomeSnapshotStore.capture { home ->
                val accepted = home != null && !isFinishing && SystemShadeAccessibilityService.animateLaunch(icon, app, home, widget) { started ->
                    if (!started) { startApp(app, source, zoom = true, quiet = false); return@animateLaunch }
                    // 2) The card is on screen now: hide the real icon (never earlier, so it never
                    //    blinks out) and copy Home without it — what the app opens over, and what a
                    //    card flying back into this icon will land on.
                    AppOpenZoomController.hideIcon(app.id, icon)
                    // The app starts as soon as the copy is queued, not when its pixels come back:
                    // the readback never delays the launch while the card is in flight.
                    HomeSnapshotStore.capture(afterFrames = 2, hiddenAppId = app.id,
                        onRequested = { startApp(app, source, zoom = true, quiet = true) }) { empty ->
                        empty?.let(SystemShadeAccessibilityService::updateLaunchBackdrop)
                    }
                }
                if (!accepted) startApp(app, source, zoom = true, quiet = false)
            }
            return
        }
        startApp(app, source, zoom = source != null, quiet = false)
    }

    /** Reduce Motion launch: a full-screen launch-screen card fades in, then the app replaces it. */
    private fun startDissolve(app: AppEntry, source: android.graphics.Rect?) {
        if (app.isPersonal && SplashColors.forPackage(app.packageName) == null) SplashColors.color(this, app.component)
        // Home at rest, copied before the card covers it: the backdrop of the next swipe home.
        HomeSnapshotStore.capture()
        val decor = window.decorView
        val full = ZoomBounds(0f, 0f, decor.width.toFloat(), decor.height.toFloat())
        val from = source?.let { ZoomBounds(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) } ?: full
        AppOpenZoomController.beginHandover(from, app.icon, android.os.SystemClock.uptimeMillis(), app.id, dissolve = true) {
            if (!isFinishing && !isDestroyed) launchNow(app, source, quiet = false, noAnimation = true, zoom = true)
        }
        decor.postDelayed({ AppOpenZoomController.handover() }, HANDOVER_TIMEOUT)
    }

    private fun startApp(app: AppEntry, bounds: android.graphics.Rect?, zoom: Boolean, quiet: Boolean) {
        val source = bounds?.takeUnless { it.isEmpty }
        if (zoom && source != null && !quiet) {
            // No overlay: Android 14+ ignores a launcher's cross-task launch animation and slides the
            // app in sideways. Draw the whole iOS zoom here and start the app, without any system
            // animation, once its launch-screen card covers the screen (≈ 0.27 s).
            if (app.isPersonal && SplashColors.forPackage(app.packageName) == null) SplashColors.color(this, app.component)
            // Home at rest, copied before the card covers it: the backdrop of the next swipe home.
            HomeSnapshotStore.capture()
            val pending = android.graphics.Rect(source)
            AppOpenZoomController.beginHandover(ZoomBounds(source.left.toFloat(), source.top.toFloat(), source.right.toFloat(),
                source.bottom.toFloat()), app.icon, android.os.SystemClock.uptimeMillis(), app.id,
                art = WidgetLaunchTiles.artFor(app.id)?.art) {
                if (!isFinishing && !isDestroyed) launchNow(app, pending, quiet = false, noAnimation = true, zoom = true)
            }
            // Safety net: a zoom that never draws (window hidden) must not swallow the launch.
            window.decorView.postDelayed({ AppOpenZoomController.handover() }, HANDOVER_TIMEOUT)
            return
        }
        if (zoom && source != null) AppOpenZoomController.begin(ZoomBounds(source.left.toFloat(), source.top.toFloat(),
            source.right.toFloat(), source.bottom.toFloat()), app.icon, android.os.SystemClock.uptimeMillis(), app.id,
            art = WidgetLaunchTiles.artFor(app.id)?.art)
        launchNow(app, bounds, quiet, noAnimation = false, zoom = zoom)
    }

    private fun launchNow(app: AppEntry, bounds: android.graphics.Rect?, quiet: Boolean, noAnimation: Boolean, zoom: Boolean) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            val options = if (quiet) android.app.ActivityOptions.makeCustomAnimation(this, R.anim.nav_hold, R.anim.nav_hold).toBundle()
                else if (noAnimation) null else launchOptions(bounds, app)
            if ((noAnimation || quiet) && user == android.os.Process.myUserHandle()) {
                // A card (ours, or the overlay's) already shows the app full size: the window replaces
                // it in place. Android 14+ would otherwise slide it in sideways under the card.
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .apply { sourceBounds = screenBounds(bounds) })
            // Same contract as the personal branch above: whenever a card already covers the screen
            // the window must replace it in place, so the clone intent carries NO_ANIMATION too.
            // Custom animation options alone are not enough — HyperOS ignores them when the app's
            // task is only brought to the front, and slides the window in sideways instead.
            } else if (!(app.isClone && startXSpaceClone(app.component, user, screenBounds(bounds), options,
                    noAnimation = noAnimation || quiet)))
                getSystemService(LauncherApps::class.java).startMainActivity(app.component, user, screenBounds(bounds), options)
            recentApps.record(app)
            // By app id (component + profile) with its tile: a dual-app copy returns into its own icon.
            NavigationProgressBus.noteLaunched(app.id, screenBounds(bounds)?.let {
                ZoomBounds(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) })
            NavigationProgressBus.launchedPackage = app.packageName
        } catch (_: Exception) {
            // Nothing is going to cover us: drop the zoom instead of leaving Home faded out.
            WidgetLaunchTiles.drop(app.id)
            if (zoom) AppOpenZoomController.cancel()
            if (quiet) SystemShadeAccessibilityService.cancelLaunch()
            IosBanner.show(getString(R.string.app_unavailable, app.label)); model.refresh()
        }
    }

    private fun screenBounds(bounds: android.graphics.Rect?): android.graphics.Rect? = bounds?.takeUnless { it.isEmpty }?.let {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        android.graphics.Rect(it).apply { offset(location[0], location[1]) }
    }
    private fun launchOptions(bounds: android.graphics.Rect?, app: AppEntry? = null): Bundle? {
        if (LauncherMotion.reduced || !android.animation.ValueAnimator.areAnimatorsEnabled())
            return android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_fade_in, R.anim.app_fade_out).toBundle()
        val source = bounds?.takeUnless { it.isEmpty }
        // Scale-up grows the real window out of the icon's bounds while Compose zooms the icon
        // and Home itself (see AppOpenZoomOverlay), so the hand-off reads as one continuous zoom.
        return if (source != null) android.app.ActivityOptions.makeScaleUpAnimation(window.decorView,
            source.left, source.top, source.width(), source.height()).toBundle()
        else android.app.ActivityOptions.makeCustomAnimation(this, R.anim.app_open, R.anim.home_exit).toBundle()
    }
    internal fun showRestoreSystemHome() {
        // Recheck the actual system role, not a potentially stale Compose snapshot.
        if (!getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)) {
            updateDefaultHome()
            return
        }
        nativeDialogBuilder(this)
            .setTitle(R.string.restore_system_home).setMessage(R.string.restore_home_confirm)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                // The user chooses Home in system UI. Never disable or clear either launcher.
                openSafeSystemSettings(Settings.ACTION_HOME_SETTINGS)
            }
            .setNeutralButton(R.string.navigation_system) { _, _ -> openSafeSystemSettings(Settings.ACTION_SETTINGS) }
            .setNegativeButton(R.string.cancel_action, null).show().also(::styleNativeDialog)
    }

    internal fun showWirelessSetup() {
        if (!SystemShadeAccessibilityService.showWirelessSetup()) showShadeSetup()
    }

    internal fun showNavigationSetup() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.provider.Settings.Global.getInt(contentResolver, "force_fsg_nav_bar", 0) != 1) {
            if (!SystemShadeAccessibilityService.showWirelessSetup()) showShadeSetup()
            return
        }
        // One menu, not an introduction followed by the same service recovery menu.
        if (!SystemShadeAccessibilityService.showNavigationRecovery()) showShadeSetup()
    }

    internal fun showPermissionGuide() {
        if (permissionGuideDialog?.isShowing == true) return
        permissionGuideDialog = nativeDialogBuilder(this)
            .setTitle(R.string.setup_permissions_title)
            .setMessage(getString(R.string.setup_restricted_help) + "\n\n" +
                getString(R.string.setup_permissions_help) + "\n\n" + getString(R.string.navigation_system_permission_help))
            .setNeutralButton(R.string.setup_app_details) { _, _ -> openPermissionAppDetails() }
            .setPositiveButton(R.string.close_menu, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { permissionGuideDialog = null }
                dialog.show(); styleNativeDialog(dialog)
            }
    }

    private fun openPermissionAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName"))) }
        catch (_: android.content.ActivityNotFoundException) {
            IosBanner.show(getString(R.string.system_settings_unavailable), long = true)
        }
    }

    private fun openSafeSystemSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) {
                IosBanner.show(getString(R.string.system_settings_unavailable), long = true)
            }
        }
    }

    private fun makeDefault() {
        // Samsung may immediately cancel a role request; its Home settings is reliable.
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_HOME)) startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun updateDefaultHome() {
        defaultHome.value = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun setStatusMode(vertical: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vertical) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
        // Launcher-only immersive navigation: no persistent gesture pill on Home, while
        // Android can still reveal transient navigation from an intentional edge swipe.
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun previewWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DuneWallpaperService::class.java)))
        } catch (_: android.content.ActivityNotFoundException) {
            IosBanner.show(getString(R.string.wallpaper_unavailable), long = true)
        }
    }

    private fun appInfo(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, user, null, null)
        } catch (_: Exception) {
            IosBanner.show(getString(R.string.app_unavailable, app.label))
            model.refresh()
        }
    }

    private companion object {
        /** Longest a launcher-drawn launch zoom may hold the app back (it normally hands over at ≈ 0.27 s). */
        const val HANDOVER_TIMEOUT = 450L
        /** Resume refreshes wait this long while an overlay card is still flying into Home. */
        const val RESUME_REFRESH_DELAY = 600L
        const val SHADE_DIALOG_VISIBLE = "duo.shade.dialog_visible"
        const val SHADE_SETTINGS_PENDING = "duo.shade.settings_pending"
    }
}
