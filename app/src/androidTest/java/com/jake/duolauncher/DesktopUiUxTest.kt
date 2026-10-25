package com.jake.duolauncher

import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopUiUxTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[LauncherModel::class.java]

    @Before fun home() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-root").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("first-run-setup").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        if (compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("settings-close").performClick()
        compose.waitUntil(10_000) { !model.state.value.loading }
    }
    private fun settings() {
        compose.onNodeWithTag("home-page-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("editor-settings").performClick(); compose.onNodeWithTag("jiggle-menu-settings").performClick()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(350)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            java.io.File(compose.activity.getExternalFilesDir(null), "uiux-$name.png"))
    }

    @Test fun settingsNavigationAndLabelsArePersistent() {
        val labels = model.state.value.labels
        try {
            settings(); screenshot("settings")
            compose.onNodeWithTag("settings-layout").performScrollTo().performClick()
            screenshot("layout")
            compose.onNodeWithTag("label-switch").performClick()
            compose.runOnIdle { assertEquals(!labels, model.state.value.labels) }
            compose.onNodeWithTag("settings-more-back").performClick()
            compose.onNodeWithTag("settings-layout").performClick()
            compose.runOnIdle { assertEquals(!labels, model.state.value.labels) }
            compose.onNodeWithTag("settings-close").performClick()
            compose.onNodeWithTag("settings-page").assertDoesNotExist()
        } finally { compose.runOnIdle { model.setLabels(labels) } }
    }

    @Test fun ordinaryDesktopIsFixedAndBodySwipeNeverOpensShade() {
        val before = model.state.value.layout
        try {
            val app = model.state.value.apps.first { it.available }
            compose.runOnIdle { model.restoreLayout(HomeLayout(listOf(app.id), List(4) { null })) }
            compose.onNodeWithTag("home-scroll-0", useUnmergedTree = true).assert(
                SemanticsMatcher.keyNotDefined(SemanticsProperties.VerticalScrollAxisRange))
            val iconBefore = compose.onNodeWithTag("home-app-${app.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                swipe(Offset(centerX, height * .55f), Offset(centerX, height * .80f), 320)
            }
            val iconAfter = compose.onNodeWithTag("home-app-${app.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(iconBefore.top, iconAfter.top, .5f)
            assertEquals("com.jake.duolauncher.core", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).currentPackageName)
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
        } finally { compose.runOnIdle { model.restoreLayout(before) } }
    }

    /** iOS: a downward swipe on Home opens Spotlight. Close it before checking what lies beneath. */
    private fun closeSpotlightIfOpen() {
        compose.waitForIdle()
        // The first Back may only hide the keyboard Spotlight raises.
        repeat(3) {
            if (compose.onAllNodesWithTag("spotlight").fetchSemanticsNodes().isEmpty()) return
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            Thread.sleep(600)
            compose.waitForIdle()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("spotlight").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun slowSwipeStartingOnIconDoesNotReorderOrOpenAppMenu() {
        val before = model.state.value.layout
        try {
            val apps = model.state.value.apps.filter { it.available }.take(3)
            val layout = HomeLayout(apps.map { it.id }, List(4) { null })
            compose.runOnIdle { model.restoreLayout(layout) }
            val page = compose.onNodeWithTag("home-page-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val icon = compose.onNodeWithTag("home-app-${apps[0].id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                val start = Offset(icon.center.x, icon.top + 20f) - page.topLeft
                swipe(start, Offset(start.x, minOf(height - 4f, start.y + height * .5f)), 950)
            }
            compose.runOnIdle { assertEquals(layout, model.state.value.layout) }
            compose.onNodeWithTag("app-context-overlay").assertDoesNotExist()
            closeSpotlightIfOpen()
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            assertEquals("com.jake.duolauncher.core", UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).currentPackageName)
            // Intentional, stationary long presses still expose the icon's actions.
            compose.onNodeWithTag("home-app-${apps[0].id}", useUnmergedTree = true).performTouchInput {
                longClick(Offset(centerX, 20f), 700)
            }
            compose.onNodeWithTag("app-context-overlay").assertExists()
        } finally { compose.runOnIdle { model.restoreLayout(before) } }
    }

    @Test fun slowBlankSwipesNeverOpenEditorButStationaryPressStillWorks() {
        val before = model.state.value.layout
        try {
            val app = model.state.value.apps.first { it.available }
            compose.runOnIdle { model.restoreLayout(HomeLayout(listOf(app.id), List(4) { null })) }
            // Stay in a large blank region long enough to pass the 300ms phone timeout.
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                swipe(Offset(width * .4f, height * .79f), Offset(width * .4f, height * .91f), 950)
            }
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            closeSpotlightIfOpen()
            compose.onNodeWithTag("home-cell-9", useUnmergedTree = true).performTouchInput {
                swipe(Offset(centerX, height * .15f), Offset(centerX, height * .85f), 950)
            }
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            closeSpotlightIfOpen()
            compose.onNodeWithTag("home-options-margin-0", useUnmergedTree = true).performTouchInput {
                swipe(Offset(centerX, height * .65f), Offset(centerX, height * .80f), 950)
            }
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            closeSpotlightIfOpen()
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                longClick(Offset(width * .4f, 12f), HOME_OPTIONS_HOLD_MS + 200)
            }
            compose.onNodeWithTag("editor-title").assertExists()
        } finally { compose.runOnIdle { model.restoreLayout(before) } }
    }

    @Test fun blankTapDoesNothingButStationaryHoldsOpenEditor() {
        val before = model.state.value.layout
        try {
            val app = model.state.value.apps.first { it.available }
            compose.runOnIdle { model.restoreLayout(HomeLayout(listOf(app.id), List(4) { null })) }
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                click(Offset(width * .4f, 12f))
            }
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true).performTouchInput {
                longClick(Offset(width * .4f, 12f), HOME_OPTIONS_HOLD_MS + 200)
            }
            compose.onNodeWithTag("editor-title").assertExists()
            compose.onNodeWithTag("editor-done").performClick()
            compose.onNodeWithTag("editor-title").assertDoesNotExist()
            compose.onNodeWithTag("home-cell-9", useUnmergedTree = true).performTouchInput {
                longClick(center, HOME_OPTIONS_HOLD_MS + 200)
            }
            compose.onNodeWithTag("editor-title").assertExists()
            screenshot("editor")
        } finally { compose.runOnIdle { model.restoreLayout(before) } }
    }

    @Test fun legacySixRowBackupMigratesAllShortcutsAndNewBackupRoundTrips() {
        val apps = model.state.value.apps.filter { it.available }.take(4)
        assertEquals(4, apps.size)
        val snapshot = model.state.value.copy(homeSlots = listOf(apps[0].id), leadingSlots = List(HOME_CELLS) { null },
            dock = List(4) { null }, widgetPlacements = emptyList(), widgetRestores = emptyList(), folders = emptyList())
        val raw = JSONObject(encodeLayoutBackup(snapshot, emptyList(), "grid-qa"))
        // v2 (pre-Core.12) backups already used six rows: they now import cell for cell.
        val legacySlots = JSONArray(List<String?>(LEGACY_HOME_CELLS * 2) { if (it == 0) apps[0].id else if (it == 23) apps[1].id else null })
        val legacyLeading = JSONArray(List<String?>(LEGACY_HOME_CELLS) { if (it == 23) apps[2].id else null })
        raw.put("version", 2).put("homeSlots", legacySlots).put("leadingSlots", legacyLeading)
        val imported = decodeLayoutBackup(raw.toString(), snapshot.apps, snapshot.profiles, "grid-qa")
        assertEquals(apps.take(3).map { it.id }.toSet(), (imported.layout.slots + imported.layout.leadingSlots).filterNotNull().toSet())
        assertEquals(apps[1].id, imported.layout.slots[23])
        assertEquals(apps[2].id, imported.layout.leadingSlots[23])
        assertEquals(HOME_CELLS, imported.layout.leadingSlots.size)
        assertEquals(3, imported.appCount)
        // v3 (Core.12–16) backups used five rows: every shortcut keeps its page, row and column.
        val fiveRow = JSONObject(encodeLayoutBackup(snapshot, emptyList(), "grid-qa")).put("version", 3)
            .put("homeSlots", JSONArray(List<String?>(FIVE_ROW_HOME_CELLS * 2) { when (it) { 0 -> apps[0].id; 19 -> apps[1].id; 20 -> apps[3].id; else -> null } }))
            .put("leadingSlots", JSONArray(List<String?>(FIVE_ROW_HOME_CELLS) { if (it == 19) apps[2].id else null }))
        val fromFive = decodeLayoutBackup(fiveRow.toString(), snapshot.apps, snapshot.profiles, "grid-qa").layout
        assertEquals(apps[0].id, fromFive.slots[0])
        assertEquals(apps[1].id, fromFive.slots[19])
        assertEquals(apps[3].id, fromFive.slots[HOME_CELLS])
        assertEquals(apps[2].id, fromFive.leadingSlots[19])
        assertEquals(HOME_CELLS, fromFive.leadingSlots.size)
        val migrated = snapshot.copy(homeSlots = imported.layout.slots, leadingSlots = imported.layout.leadingSlots)
        val encoded = encodeLayoutBackup(migrated, emptyList(), "grid-qa")
        assertEquals(LAYOUT_BACKUP_VERSION, JSONObject(encoded).getInt("version"))
        assertEquals(4, LAYOUT_BACKUP_VERSION)
        assertEquals(imported.layout, decodeLayoutBackup(encoded, snapshot.apps, snapshot.profiles, "grid-qa").layout)
    }

    @Test fun recentBarUsesTwoRealAppsAndNoDuplicatePinnedApp() {
        val before = model.state.value.layout
        val recentBefore = compose.activity.getSharedPreferences("recent_apps", 0).getString("history", "")
        val showing = compose.activity.corePreferences.showRecentApps
        try {
            val apps = model.state.value.apps.filter { it.available }.take(3)
            compose.runOnIdle {
                model.restoreLayout(HomeLayout(listOf(apps[1].id, apps[2].id), listOf(apps[0].id, null, null, null)))
                compose.activity.corePreferences.setRecentApps(true)
                apps.forEach(compose.activity.recentApps::record)
            }
            compose.onNodeWithTag("recent-app-${apps[1].id}").assertExists()
            compose.onNodeWithTag("recent-app-${apps[2].id}").assertExists()
            compose.onNodeWithTag("recent-app-${apps[0].id}").assertDoesNotExist()
            screenshot("dock")
        } finally {
            compose.runOnIdle {
                model.restoreLayout(before)
                compose.activity.corePreferences.setRecentApps(showing)
                compose.activity.getSharedPreferences("recent_apps", 0).edit().putString("history", recentBefore).commit()
            }
        }
    }

    /** Review code P2-2: a name typed into an open folder survives closing it with Home. */
    @Test fun folderRenameTypedBeforeHomeIsKept() {
        val before = model.state.value.layout
        try {
            val apps = model.state.value.apps.filter { it.available }.take(2)
            val folderId = newFolderId()
            compose.runOnIdle {
                model.restoreLayout(HomeLayout(listOf(folderId), List(4) { null },
                    folders = listOf(FolderEntry(folderId, "Tools", apps.map { it.id }))))
            }
            compose.onNodeWithTag("home-folder-$folderId", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("folder-rename").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("folder-rename").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("folder-name").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("folder-name").performTextReplacement("Renamed")
            // The Home button as the running launcher receives it: onNewIntent(CATEGORY_HOME), called
            // directly; the scenario's own intent is put back (ActivityScenario tracks it by intent).
            compose.runOnUiThread {
                val original = compose.activity.intent
                MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                    .apply { isAccessible = true }
                    .invoke(compose.activity, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                compose.activity.intent = original
            }
            compose.waitUntil(5_000) { model.state.value.layout.folder(folderId)?.title == "Renamed" }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("folder-panel").fetchSemanticsNodes().isEmpty() }
        } finally { compose.runOnIdle { model.restoreLayout(before) } }
    }
    /** True once the leading Today page is the left-most page on screen (the first spread). */
    private fun todayOnScreen(): Boolean = compose.onAllNodesWithTag("home-page--1", useUnmergedTree = true)
        .fetchSemanticsNodes().any { it.boundsInRoot.width > 0f && it.boundsInRoot.left >= -1f }

    /** Back to the first spread from whatever page an earlier test left Home on. */
    private fun showFirstSpread() {
        repeat(model.state.value.homePages + 3) {
            if (!todayOnScreen()) { compose.onNodeWithTag("launcher-root").performTouchInput { swipeRight() }; compose.waitForIdle() }
        }
        assertTrue("Home returns to the first spread", todayOnScreen())
    }

    /** Average colour of a 9×9 patch at ([fx], [fy]) inside [bounds] (root px). */
    private fun patch(image: PixelMap, bounds: Rect, fx: Float, fy: Float): FloatArray {
        val cx = (bounds.left + bounds.width * fx).toInt(); val cy = (bounds.top + bounds.height * fy).toInt()
        val sum = FloatArray(3)
        for (dy in -4..4) for (dx in -4..4) {
            val c = image[cx + dx, cy + dy]
            sum[0] += c.red; sum[1] += c.green; sum[2] += c.blue
        }
        return FloatArray(3) { sum[it] / 81f }
    }

    /**
     * Final verification R2: the widget lifted with its context menu shows its own opaque card,
     * not the wallpaper through a dimmed copy. The preview is a pixel copy taken while the finger
     * is still down, so a press highlight on the widget would be baked into it. (The test clock
     * drives the long-press timeout, so the press is injected through Compose, not UiAutomator.)
     */
    @Test fun liftedWidgetPreviewKeepsItsOpaqueCard() {
        val before = model.state.value.layout
        val calendar = nativeWidgetId(NativeWidgetKind.CALENDAR, NativeWidgetSize.SMALL)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var fingerDown = false
        try {
            compose.runOnIdle {
                model.restoreLayout(HomeLayout(emptyList(), before.dock,
                    widgetPlacements = listOf(WidgetPlacement(slot = 0, id = calendar, page = 0, column = 0, row = 0, spanX = 2, spanY = 2))))
            }
            compose.waitForIdle()
            showFirstSpread()
            val onScreen = {
                compose.onAllNodesWithTag("widget-slot-0").fetchSemanticsNodes().any { it.boundsInRoot.width > 0f && it.boundsInRoot.left >= 0f }
            }
            repeat(2) { if (!onScreen()) { compose.onNodeWithTag("launcher-root").performTouchInput { swipeLeft() }; compose.waitForIdle() } }
            Thread.sleep(400)
            compose.waitForIdle()
            val resting = compose.onNodeWithTag("widget-slot-0").fetchSemanticsNode().boundsInRoot
            // The calendar's empty right-middle: pure card in every tone except Clear.
            val card = patch(compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap(), resting, .78f, .5f)
            // A finger held still on the widget past the long-press timeout lifts it and opens its
            // menu; it stays down while the preview is copied and the menu springs open (as on a phone).
            val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("launcher-root").performTouchInput { down(resting.center - root.topLeft) }
            fingerDown = true
            compose.mainClock.advanceTimeBy(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150L)
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("widget-context-overlay-lifted").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.advanceTimeBy(900)
            compose.waitForIdle()
            val lifted = compose.onNodeWithTag("widget-context-overlay-lifted").fetchSemanticsNode().boundsInRoot
            val preview = patch(compose.onNodeWithTag("launcher-root").captureToImage().toPixelMap(), lifted, .78f, .5f)
            screenshot("widget-menu-preview")
            val difference = (0 until 3).maxOf { kotlin.math.abs(card[it] - preview[it]) }
            android.util.Log.i("18DuoWidgetPreviewQA", "card=${card.toList()} preview=${preview.toList()} resting=$resting lifted=$lifted")
            assertTrue("Lifted preview must show the widget's own card (${card.toList()}), got ${preview.toList()}", difference < .06f)
        } finally {
            if (fingerDown) compose.onNodeWithTag("launcher-root").performTouchInput { up() }
            compose.waitForIdle()
            device.pressBack()
            compose.waitForIdle()
            compose.runOnIdle { model.restoreLayout(before) }
        }
    }

    /** iPhone Duo's Today View has no search field (Apple videos); Spotlight stays on the Search button. */
    @Test fun todayViewHasNoSearchFieldOnDuo() {
        showFirstSpread()
        compose.onNodeWithTag("today-search", useUnmergedTree = true).assertDoesNotExist()
    }
}
