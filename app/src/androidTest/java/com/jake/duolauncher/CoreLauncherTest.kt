package com.jake.duolauncher

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import android.appwidget.AppWidgetManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreLauncherTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun dismissSetup() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-root").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("first-run-setup").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        if (compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("settings-close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("persistent-settings").assertDoesNotExist()
    }

    private fun openHomeSettings() {
        val page = compose.onAllNodes(androidx.compose.ui.test.hasTestTag("home-page-0") or
            androidx.compose.ui.test.hasTestTag("home-page--1"), useUnmergedTree = true)
            .fetchSemanticsNodes().first { it.boundsInRoot.width > 0 }
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher("visible home page") { it.id == page.id },
            useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("editor-settings").performClick(); compose.onNodeWithTag("jiggle-menu-settings").performClick()
    }

    @Test fun standbyIsOneSwitchThatNeverOpensTheClockOnTheInnerScreen() {
        val prefs = compose.activity.corePreferences
        val before = prefs.standbyEnabled
        try {
            openHomeSettings()
            compose.onNodeWithTag("settings-standby").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(!before, CorePreferences(compose.activity).standbyEnabled)
                assertFalse(StandbyMonitorService.clockVisible.value)
                assertFalse(compose.activity.panelOrientation.automaticStandby)
            }
            compose.onNodeWithTag("standby-scene-MANUAL_ONLY").assertDoesNotExist()
        } finally {
            compose.runOnIdle { prefs.setStandby(before) }
        }
    }

    @Test fun persistentSettingsAndAnimationPreferenceAreUsable() {
        openHomeSettings()
        val original = compose.activity.corePreferences.foldAnimationEnabled
        compose.onNodeWithTag("settings-fold-animation").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(!original, compose.activity.corePreferences.foldAnimationEnabled) }
        compose.onNodeWithTag("settings-fold-animation").performClick()
        compose.runOnIdle { assertEquals(original, compose.activity.corePreferences.foldAnimationEnabled) }
    }

    @Test fun appleIconSwitchReplacesAndRestoresLauncherArtwork() {
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        val prefs = compose.activity.corePreferences
        val before = prefs.appleIconsEnabled
        val themed = AppleIconPack(compose.activity.assets).iconFor(
            ComponentName("com.android.settings", "com.android.settings.Settings"))
        assertNotNull("The bundled pack must include Settings", themed)
        fun settingsIcon() = model.state.value.apps.firstOrNull {
            it.packageName == "com.android.settings" && it.isPersonal && it.available
        }?.icon
        try {
            compose.runOnIdle { prefs.setAppleIcons(false); model.refresh() }
            compose.waitUntil(15_000) { settingsIcon()?.sameAs(themed!!) == false }
            openHomeSettings()
            compose.onNodeWithTag("customize-more").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("settings-layout").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("settings-layout").performScrollTo().performClick()
            compose.onNodeWithTag("apple-icons-switch").performScrollTo().performClick()
            compose.waitUntil(15_000) { prefs.appleIconsEnabled && settingsIcon()?.sameAs(themed!!) == true }
            compose.onNodeWithTag("apple-icons-switch").performClick()
            compose.waitUntil(15_000) { !prefs.appleIconsEnabled && settingsIcon()?.sameAs(themed!!) == false }
        } finally {
            compose.runOnIdle { prefs.setAppleIcons(before); model.refresh() }
        }
    }

    @Test fun appLibrarySearchShowsAnInstalledApp() {
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.loading }
        // Use the same paging gesture as the desktop. The Search destination
        // deliberately opens the system search provider when one is installed.
        repeat(model.state.value.homePages + 1) {
            if (compose.onAllNodesWithTag("library-page").fetchSemanticsNodes().none { node ->
                node.boundsInRoot.width > 0 && node.boundsInRoot.left >= 0
            }) {
                compose.onNodeWithTag("launcher-root").performTouchInput { swipeLeft() }
                compose.waitForIdle()
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-page").fetchSemanticsNodes().isNotEmpty() }
        val label = compose.activity.packageManager.getApplicationLabel(
            compose.activity.packageManager.getApplicationInfo("com.android.settings", 0)).toString()
        compose.onNodeWithTag("library-search").performTextInput(label)
        compose.onAllNodes(hasText(label)).onFirst().assertExists()
    }

    @Test fun setupCanBeReopenedAfterDismissal() {
        openHomeSettings()
        compose.onNodeWithTag("setup-entry").performScrollTo().performClick()
        compose.onNodeWithTag("reopen-setup").performScrollTo().performClick()
        compose.onNodeWithTag("first-run-setup").assertExists()
        compose.onNodeWithTag("setup-explore").performClick()
        compose.onNodeWithTag("persistent-settings").assertDoesNotExist()
    }

    @Test fun widgetCatalogContainsOnlyInstalledProviders() {
        compose.onAllNodesWithTag("reference-clock").assertCountEquals(0)
        compose.onAllNodesWithTag("reference-month-calendar").assertCountEquals(0)
        openHomeSettings()
        compose.onNodeWithTag("settings-entry-${R.string.editor_widgets}").performScrollTo().performClick()
        compose.onNodeWithTag("widget-catalog-search").performTextInput("Search")
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag("widget-provider-com.android.quicksearchbox/com.android.quicksearchbox.SearchWidgetProvider").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithTag("widget-builtin-$WEATHER_WIDGET").assertCountEquals(0)
        compose.onAllNodesWithTag("widget-builtin-$BATTERY_WIDGET").assertCountEquals(0)
    }

    @Test fun dockFollowsPanelRulesInBothOrientations() {
        try {
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                compose.runOnIdle { compose.activity.requestedOrientation = orientation }
                compose.waitUntil(6_000) {
                    val bounds = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
                    val cover = compose.activity.panelOrientation.panel.value == FoldPanel.COVER
                    val side = desktopSideDock(cover, orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                    if (side) bounds.height > bounds.width else bounds.width > bounds.height
                }
            }
        } finally { compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR } }
    }

    @Test fun denseDesktopFitsBothOrientationsAndRespectsCamera() {
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.loading }
        val saved = model.state.value.layout
        val apps = model.state.value.apps.take(HOME_CELLS * 2)
        val folders = (apps.size until HOME_CELLS * 2).map { index ->
            FolderEntry(newFolderId(), "应用 ${index + 1}", listOf(apps[index % apps.size].id))
        }
        // Rotate the DISPLAY (user-rotation lock) and let Home follow it. App orientation requests are not
        // dependable here: Android 16 ignores them on >= 600 dp panels (the inner screen) for targetSdk 36,
        // `ignoreOrientationRequest` displays letterbox instead, and the inner panel is naturally landscape
        // (portrait is ROTATION_90/270 there, not ROTATION_0). The layout checks below are unchanged.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun setting(name: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("settings get system $name")).bufferedReader().use { it.readText().trim() }
        val autoRotate = setting("accelerometer_rotation") == "1"
        val userRotation = setting("user_rotation").toIntOrNull() ?: android.view.Surface.ROTATION_0
        val naturalLandscape = compose.runOnIdle {
            val bounds = compose.activity.windowManager.maximumWindowMetrics.bounds
            val rotation = compose.activity.display!!.rotation
            (bounds.width() > bounds.height()) == (rotation == android.view.Surface.ROTATION_0 ||
                rotation == android.view.Surface.ROTATION_180)
        }
        // Portrait, then both landscapes (the camera ends up on opposite sides), as before.
        val rotations = if (naturalLandscape) listOf(android.view.Surface.ROTATION_270,
            android.view.Surface.ROTATION_180, android.view.Surface.ROTATION_0)
            else listOf(android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_270, android.view.Surface.ROTATION_90)
        try {
            compose.runOnIdle { model.restoreLayout(HomeLayout(
                slots = apps.map { it.id } + folders.map { it.id },
                dock = (0 until 4).map { apps[it % apps.size].id },
                widgetPlacements = emptyList(), folders = folders)) }
            for (rotation in rotations) {
                val landscape = naturalLandscape == (rotation == android.view.Surface.ROTATION_0 ||
                    rotation == android.view.Surface.ROTATION_180)
                val orientation = when {
                    !landscape -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    rotation == (if (naturalLandscape) android.view.Surface.ROTATION_180 else android.view.Surface.ROTATION_270) ->
                        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                compose.runOnIdle { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER }
                assertTrue("Rotation $rotation", automation.setRotation(rotation))
                compose.waitUntil(8_000) {
                    val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
                    (root.width > root.height) == landscape && compose.activity.display?.rotation == rotation
                }
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(600)
                val cover = compose.activity.panelOrientation.panel.value == FoldPanel.COVER
                val side = desktopSideDock(cover, landscape)
                val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
                assertEquals(side, dock.height > dock.width)
                val density = compose.activity.resources.displayMetrics.density
                if (!side) {
                    val rootWidth = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot.width
                    assertTrue("Dock stays within horizontal safe margins", dock.width <= rootWidth - 24f * density)
                    assertTrue("Pinned Dock retains usable width", dock.width / density >= 200f)
                }
                val rail = compose.onNodeWithTag("status-rail").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("status-connectivity-ring", useUnmergedTree = true).assertIsDisplayed()
                val cutouts = compose.activity.windowManager.currentWindowMetrics.windowInsets.displayCutout?.boundingRects.orEmpty()
                android.util.Log.i("18DuoLayoutQA", "cover=$cover orientation=$orientation dock=$dock status=$rail cameras=$cutouts")
                cutouts.filter { !it.isEmpty }.forEach { cutout ->
                    assertFalse("Status must avoid camera $cutout", android.graphics.RectF.intersects(
                        android.graphics.RectF(rail.left, rail.top, rail.right, rail.bottom), android.graphics.RectF(cutout)))
                    assertFalse("Dock must avoid camera $cutout", android.graphics.RectF.intersects(
                        android.graphics.RectF(dock.left, dock.top, dock.right, dock.bottom), android.graphics.RectF(cutout)))
                }
                if (side && cutouts.none { it.right > dock.left && it.left < dock.right &&
                    kotlin.math.abs(it.exactCenterY() - compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot.center.y) < dock.height / 2f + density * 12f }) {
                    val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
                    // Round 3: the Dock (with any recent apps) is centred on the panel height, clamped
                    // between status and Search (rotated cover, or a long Dock with recents).
                    val recents = compose.onAllNodes(SemanticsMatcher("recent app") {
                        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("recent-app-") == true
                    }, useUnmergedTree = true).fetchSemanticsNodes().size
                    val frame = duoHomeFrame(duoHomePose(cover, root.width / density, root.height / density),
                        root.width / density, root.height / density, dockExtraSlots = recents)
                    assertEquals("Dock sits at the Duo Dock height", root.top + frame.dock.box.centerY * density,
                        dock.center.y, density * 2f)
                }
                for (index in 0 until HOME_CELLS) {
                    compose.onNodeWithTag("home-cell-$index", useUnmergedTree = true).assertIsDisplayed()
                    val bounds = compose.onNodeWithTag("home-cell-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    assertTrue("Cell $index retains a touchable row", bounds.height / density >= 48f)
                    if (cover && landscape) assertTrue("Landscape labels get a wider column", bounds.width / density >= 62f)
                    val id = model.state.value.homeSlots[index]!!
                    val tag = if (isFolderId(id)) "home-folder-$id" else "home-app-$id"
                    compose.onNodeWithTag(tag).assertIsDisplayed()
                    val tile = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                    assertTrue("Tile $index keeps its icon and label", tile.height / density >= 48f)
                    if (cover && landscape) {
                        assertFalse("Tile $index must avoid status", tile.overlaps(rail))
                        val search = compose.onNodeWithTag("search").fetchSemanticsNode().boundsInRoot
                        assertFalse("Tile $index must avoid search", tile.overlaps(search))
                    }
                    cutouts.filter { !it.isEmpty }.forEach { cutout ->
                        assertFalse("Tile $index must avoid camera", android.graphics.RectF.intersects(
                            android.graphics.RectF(tile.left, tile.top, tile.right, tile.bottom), android.graphics.RectF(cutout)))
                    }
                }
                assertEquals("Rotating preserves every saved app, folder and cell", apps.map { it.id } + folders.map { it.id },
                    model.state.value.layout.slots)
                compose.onNodeWithTag("home-page-dot-0").assertIsDisplayed()
                compose.onNodeWithTag("home-page-dot-1").assertIsDisplayed()
                if (cover && landscape) {
                    val first = compose.onNodeWithTag("home-cell-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val sixth = compose.onNodeWithTag("home-cell-5", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val nextRow = compose.onNodeWithTag("home-cell-6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val last = compose.onNodeWithTag("home-cell-${HOME_CELLS - 1}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val dot = compose.onNodeWithTag("home-page-dot-0").fetchSemanticsNode().boundsInRoot
                    assertEquals("Six cells share the first row (24 cells as six by four)", first.top, sixth.top, 1f)
                    assertTrue("Seventh cell starts the second row", nextRow.top > first.top)
                    assertEquals("Four evenly spaced rows", 3f * (nextRow.top - first.top), last.top - first.top, 2f)
                    assertTrue("Top whitespace is compact", first.top / density <= 32f)
                    assertTrue("Last row stays above the page dots", (dot.top - last.bottom) / density in 8f..32f)
                }
                compose.onNodeWithTag("persistent-settings").assertDoesNotExist()
                val label = "${if (cover) "cover" else "inner"}-${when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> "reverse-landscape"
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> "landscape"
                    else -> "portrait"
                }}"
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(compose.activity.getExternalFilesDir(null), "core10-$label.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                if (!cover && landscape) {
                    compose.onNodeWithTag("launcher-root").performTouchInput { swipeLeft() }
                    compose.waitForIdle()
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(compose.activity.getExternalFilesDir(null), "core10-$label-spread.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    compose.onNodeWithTag("launcher-root").performTouchInput { swipeRight() }
                    compose.waitForIdle()
                }
            }
            openHomeSettings()
            compose.onNodeWithTag("settings-close").performClick()
            compose.onNodeWithTag("editor-settings").assertDoesNotExist()
        } finally {
            automation.setRotation(if (autoRotate) android.app.UiAutomation.ROTATION_UNFREEZE else userRotation)
            compose.runOnIdle { model.restoreLayout(saved); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
        }
    }

    @Test fun manualStandbyHasAVisibleEntryAndExit() {
        compose.runOnIdle { compose.activity.panelOrientation.enterStandby() }
        compose.waitUntil(6_000) { compose.onAllNodesWithTag("standby-root").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("standby-exit").performClick()
        compose.waitUntil(6_000) { compose.onAllNodesWithTag("launcher-root").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun touchRequestsFastestRefreshAndReleasesAtRest() {
        val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = android.os.SystemClock.uptimeMillis()
        val x = root.left + root.width * .2f
        val y = root.top + root.height * .8f
        // Compose injects directly into its root view, bypassing Activity dispatch.
        // Use Android's input dispatcher to exercise a real screen touch instead.
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 1, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            assertTrue(instrumentation.uiAutomation.injectInputEvent(down, true))
            instrumentation.runOnMainSync {
                // HIGH category is satisfied by 90 Hz; the boost names the fastest mode of this resolution.
                val display = compose.activity.display!!
                val current = display.mode
                val fastest = display.supportedModes.filter {
                    it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
                }.maxByOrNull { it.refreshRate }!!
                assertEquals(fastest.modeId, compose.activity.window.attributes.preferredDisplayModeId)
                if (android.os.Build.VERSION.SDK_INT >= 35)
                    assertEquals(fastest.refreshRate, compose.activity.window.decorView.requestedFrameRate, .5f)
            }
        } finally {
            instrumentation.uiAutomation.injectInputEvent(up, true)
            down.recycle()
            up.recycle()
        }
        // At rest the system's adaptive policy owns the panel again.
        compose.waitUntil(4_000) { compose.activity.window.attributes.preferredDisplayModeId == 0 }
        if (android.os.Build.VERSION.SDK_INT >= 35) instrumentation.runOnMainSync {
            assertTrue(compose.activity.window.decorView.requestedFrameRate.isNaN())
        }
    }

    @Test fun realClockWidgetClickOpensItsProviderApplication() {
        val activity = compose.activity
        val model = ViewModelProvider(activity)[LauncherModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.loading }
        val field = MainActivity::class.java.getDeclaredField("widgets").apply { isAccessible = true }
        val widgets = field.get(activity) as WidgetController
        val provider = AppWidgetManager.getInstance(activity).installedProviders.first {
            it.provider.className == "com.android.alarmclock.AnalogAppWidgetProvider"
        }
        val slot = model.nextWidgetSlot()
        val candidate = (0 until HOME_CELLS).firstNotNullOf { index ->
            widgetCandidate(model.state.value.layout, slot, index, 2, 2)
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            compose.runOnIdle { widgets.add(candidate, provider) }
            // Android owns the binding confirmation; use its visible Allow control.
            val allow = device.wait(Until.findObject(By.res("android:id/button1")), 4_000)
            if (allow == null) {
                device.dumpWindowHierarchy(java.io.File(activity.getExternalFilesDir(null), "widget-bind-window.xml"))
                device.takeScreenshot(java.io.File(activity.getExternalFilesDir(null), "widget-bind-window.png"))
            }
            allow?.click()
            compose.waitUntil(8_000) { model.placement(slot)?.id?.let { it > 0 } == true }
            val placement = model.placement(slot)!!
            assertEquals(provider.provider, widgets.manager.getAppWidgetInfo(placement.id).provider)
            compose.onNodeWithTag("widget-slot-$slot").assertIsDisplayed()
            val frame = compose.onNodeWithTag("widget-slot-$slot").fetchSemanticsNode().boundsInRoot
            device.click(frame.center.x.toInt(), frame.center.y.toInt())
            assertTrue("A real widget PendingIntent opens its own Clock app",
                device.wait(Until.hasObject(By.pkg("com.android.deskclock")), 6_000))
        } finally {
            device.pressBack()
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.runOnIdle { widgets.remove(slot) }
        }
    }

    /** True once the leading Today page is the left-most page on screen (the first spread). */
    private fun todayPageOnScreen(): Boolean = compose.onAllNodesWithTag("home-page--1", useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.boundsInRoot.width > 0f && it.boundsInRoot.left >= -1f }

    @Test fun swipingAppArtworkCanCrossTheReservedDockColumn() {
        compose.runOnIdle { compose.activity.requestedOrientation = if (compose.activity.panelOrientation.panel.value == FoldPanel.COVER)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(6_000) {
            val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
            dock.height > dock.width
        }
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.loading }
        val saved = model.state.value.layout
        val app = model.state.value.apps.first { it.packageName == "com.android.settings" }
        // Own layout, independent of earlier tests: Home 1 empty, Settings alone in Home 2's last
        // column (beside the Dock), no widgets; Today View shown. Round 4: second row, so the sampled
        // pixels pass between the status block (time + ring, drawn above the pages) and the Dock
        // rather than under the Wi-Fi glyph once the leaving page catches up at a steady 1.3×.
        val todayHidden = compose.activity.corePreferences.widgetPageMode == "HIDE"
        compose.runOnIdle {
            if (todayHidden) compose.activity.corePreferences.setWidgetPage(true)
            model.restoreLayout(HomeLayout(slots = List(HOME_CELLS + 8) { index -> if (index == HOME_CELLS + 7) app.id else null },
                dock = saved.dock, widgetPlacements = emptyList()))
        }
        try {
            compose.waitForIdle()
            // Whatever page an earlier run left Home on: back to the first spread, then forward until
            // Home 2's icon is fully on screen (cover: Today → 1 → 2; unfolded: [Today|1] → [1|2]).
            repeat(model.state.value.homePages + 3) {
                if (!todayPageOnScreen()) { compose.onNodeWithTag("launcher-root").performTouchInput { swipeRight() }; compose.waitForIdle() }
            }
            assertTrue("Home returns to the first spread", todayPageOnScreen())
            val iconOnScreen = {
                compose.onAllNodesWithTag("home-app-${app.id}").fetchSemanticsNodes().any { node ->
                    val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
                    node.boundsInRoot.width > 0f && node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= dock.left
                }
            }
            repeat(3) {
                if (!iconOnScreen()) { compose.onNodeWithTag("launcher-root").performTouchInput { swipeLeft() }; compose.waitForIdle() }
            }
            val node = compose.onNodeWithTag("home-app-${app.id}")
            val before = node.fetchSemanticsNode().boundsInRoot
            node.assertIsDisplayed()
            val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
            val iconWidth = 48f * compose.activity.resources.displayMetrics.density
            val sampleY = before.top + iconWidth * .45f
            assertTrue("Use the row above the Dock so its glass cannot hide the artwork", sampleY < dock.top)
            val sampleX = before.center.x
            val original = compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap()
            val travel = dock.left + iconWidth * .25f - sampleX
            assertTrue(travel > 0f && sampleX + travel < root.right - 4f)
            compose.onNodeWithTag("launcher-root").performTouchInput {
                down(Offset(root.width * .25f, root.height * .8f))
                repeat(8) { moveBy(Offset((travel + viewConfiguration.touchSlop) / 8f, 0f), delayMillis = 16) }
            }
            try {
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                val moved = node.fetchSemanticsNode().boundsInRoot
                // Semantics bounds clip at the physical screen edge, so their centre
                // changes when the tile is partly offscreen. Preserve its true origin.
                val shiftedX = sampleX + moved.left - before.left
                android.util.Log.i("18DuoScrollQA", "before=$before moved=$moved dock=$dock travel=$travel")
                assertTrue("The icon must move into the reserved side column", shiftedX > dock.left)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let {
                    val out = java.io.File(compose.activity.getExternalFilesDir(null), "scroll-crossing.png")
                    out.outputStream().use { stream -> it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
                }
                val frame = compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap()
                var matching = 0
                var samples = 0
                for (dy in -8..8) for (dx in -8..8) {
                    val old = original[(sampleX.toInt() + dx), (sampleY.toInt() + dy)]
                    val actual = frame[(shiftedX.toInt() + dx), (sampleY.toInt() + dy)]
                    if (kotlin.math.abs(old.red - actual.red) + kotlin.math.abs(old.green - actual.green) +
                        kotlin.math.abs(old.blue - actual.blue) < .12f) matching++
                    samples++
                }
                assertTrue("Artwork must remain painted beyond the Dock layout boundary", matching > samples * .7f)
            } finally {
                compose.onNodeWithTag("launcher-root").performTouchInput { up() }
                compose.waitForIdle()
            }
        } finally {
            compose.runOnIdle {
                model.restoreLayout(saved); if (todayHidden) compose.activity.corePreferences.setWidgetPage(false)
                compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }
    }

    @Test fun dockGlassSamplesMovingAppArtwork() {
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        compose.waitUntil(10_000) { !model.state.value.loading }
        val saved = model.state.value.layout
        val app = model.state.value.apps.first { it.packageName == "com.android.settings" }
        try {
            compose.runOnIdle {
                compose.activity.requestedOrientation = if (compose.activity.panelOrientation.panel.value == FoldPanel.COVER)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                model.restoreLayout(HomeLayout(slots = List(HOME_CELLS + 12) { index ->
                    if (index == HOME_CELLS + 11) app.id else null
                }, dock = saved.dock, widgetPlacements = emptyList()))
            }
            compose.waitUntil(8_000) {
                val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
                dock.height > dock.width
            }
            compose.onNodeWithTag("launcher-root").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            val artwork = compose.onNodeWithTag("home-app-${app.id}").fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("dock").fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
            val density = compose.activity.resources.displayMetrics.density
            val sampleY = artwork.top + 20f * density
            assertTrue("Moving icon crosses the glass", sampleY > dock.top + 8f * density && sampleY < dock.bottom - 8f * density)
            val before = compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap()
            val travel = dock.left + 8f * density - artwork.center.x
            compose.onNodeWithTag("launcher-root").performTouchInput {
                down(Offset(root.width * .25f, root.height * .8f))
                repeat(8) { moveBy(Offset((travel + viewConfiguration.touchSlop) / 8f, 0f), delayMillis = 16) }
            }
            try {
                compose.mainClock.advanceTimeBy(100)
                compose.waitForIdle()
                val after = compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap()
                var difference = 0f
                var samples = 0
                // The strip inside the glass edge is outside the stationary Dock
                // icons. Only the sampled moving backdrop should change its pixels.
                for (y in (sampleY - 5f * density).toInt()..(sampleY + 5f * density).toInt())
                    for (x in (dock.left + 2f * density).toInt()..(dock.left + 6f * density).toInt()) {
                        val old = before[x, y]; val current = after[x, y]
                        difference += kotlin.math.abs(old.red - current.red) + kotlin.math.abs(old.green - current.green) +
                            kotlin.math.abs(old.blue - current.blue)
                        samples++
                    }
                android.util.Log.i("18DuoGlassQA", "backdrop pixel change=${difference / samples} dock=$dock artwork=$artwork sampleY=$sampleY")
                assertTrue("Dock glass must respond to the passing app, not only wallpaper", difference / samples > .025f)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(compose.activity.getExternalFilesDir(null), "core9-glass-crossing.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            } finally {
                compose.onNodeWithTag("launcher-root").performTouchInput { up() }
                compose.waitForIdle()
            }
        } finally {
            compose.runOnIdle { model.restoreLayout(saved); compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
        }
    }
}
