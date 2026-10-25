package com.jake.duolauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Owner round 3, item 2: in jiggle mode, a long press on a row of the "编辑" menu (添加小组件 / 自定义 /
 * 编辑墙纸) was taken as a long press on the app icon under the menu (Home's root lift detector saw
 * every touch). Menus, their scrims and the other overlays now own every touch inside them; a press
 * held on a row behaves like a UIMenu row (highlight, run on release). Each test fills Home page 1
 * with apps so an icon really lies under the menu, holds a finger still well past the long-press
 * timeout, and checks that no icon is lifted (no drag ghost) and the layout is untouched.
 */
@RunWith(AndroidJUnit4::class)
class OverlayTouchPassthroughTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[LauncherModel::class.java]
    private val hold = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 700L

    @Before fun home() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("launcher-root").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithTag("first-run-setup").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        if (compose.onAllNodesWithTag("settings-close").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("settings-close").performClick()
        compose.waitUntil(10_000) { !model.state.value.loading }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val rootOrigin get() = bounds("launcher-root").topLeft
    private fun lifted() = exists("drag-ghost") || exists("folder-drag-ghost")

    /**
     * Apps in every cell of the page the "编辑" menu opens over: Home page 1 on the cover and in inner
     * portrait, the Today page (left half) when unfolded, so whatever the menu covers is an app icon.
     */
    private fun fillFirstPage(): HomeLayout {
        val apps = model.state.value.apps.filter { it.available }.map { it.id }.distinct()
        val cells = List(HOME_CELLS) { apps.getOrNull(it) }
        val decor = compose.activity.window.decorView
        val spread = decor.width > decor.height && compose.activity.panelOrientation.panel.value != FoldPanel.COVER
        val layout = if (spread) HomeLayout(emptyList(), List(4) { null }, leadingSlots = cells)
            else HomeLayout(cells, List(4) { null })
        compose.runOnIdle { model.restoreLayout(layout) }
        compose.waitForIdle()
        return model.state.value.layout
    }

    private fun placedApps(): List<String> = model.state.value.layout.let { it.slots + it.leadingSlots }.filterNotNull()

    /** The app whose icon lies under [point] (root px), if any. */
    private fun appUnder(point: Offset): String? = placedApps().firstOrNull { id ->
        compose.onAllNodesWithTag("home-app-$id", useUnmergedTree = true).fetchSemanticsNodes()
            .any { it.boundsInRoot.contains(point) }
    }

    private fun openJiggleEditMenu() {
        compose.onNodeWithTag("home-page-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.waitUntil(5_000) { exists("editor-settings") }
        compose.onNodeWithTag("editor-settings").performClick()
        compose.waitUntil(5_000) { exists("jiggle-menu") }
        compose.waitForIdle()
    }

    private var fingerDown = false

    /** Puts a finger down at [point] (root px) and keeps it still past the long-press timeout. */
    private fun pressAndHold(point: Offset) {
        compose.onNodeWithTag("launcher-root").performTouchInput { down(point - rootOrigin) }
        fingerDown = true
        compose.mainClock.advanceTimeBy(hold)
        compose.waitForIdle()
    }

    private fun release(at: Offset? = null) {
        compose.onNodeWithTag("launcher-root").performTouchInput {
            if (at != null) moveTo(at - rootOrigin)
            up()
        }
        fingerDown = false
        compose.waitForIdle()
    }

    private fun closeSheetsAndEditing() {
        // A failed assertion can leave the finger down; lift it so the next test starts clean.
        if (fingerDown) runCatching { release() }
        fingerDown = false
        if (exists("drag-ghost")) compose.waitForIdle()
        if (exists("settings-close")) compose.onNodeWithTag("settings-close").performClick()
        compose.waitForIdle()
        if (exists("editor-done")) compose.onNodeWithTag("editor-done").performClick()
        compose.waitForIdle()
    }

    @Test fun longPressOnJiggleMenuRowRunsTheRowAndNeverLiftsTheIconBeneath() {
        val before = model.state.value.layout
        try {
            val layout = fillFirstPage()
            openJiggleEditMenu()
            val row = bounds("jiggle-menu-wallpaper").center
            assertTrue("an app icon lies under the menu row", appUnder(row) != null)
            pressAndHold(row)
            assertTrue("held menu row must not lift the icon beneath", !lifted())
            Thread.sleep(300)
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
                java.io.File(compose.activity.getExternalFilesDir(null), "overlay-held-menu-row.png"))
            // A little finger jitter while held is still the row, not a drag of the icon.
            compose.onNodeWithTag("launcher-root").performTouchInput { moveBy(Offset(0f, 3f)) }
            compose.mainClock.advanceTimeBy(300)
            assertTrue(!lifted())
            release()
            // iOS: releasing on a row runs it, however long it was held.
            compose.waitUntil(5_000) { exists("settings-page") }
            compose.runOnIdle { assertEquals(layout, model.state.value.layout) }
        } finally {
            closeSheetsAndEditing()
            compose.runOnIdle { model.restoreLayout(before) }
        }
    }

    @Test fun longPressOnMenuScrimClosesTheMenuAndNeverLiftsTheIconBeneath() {
        val before = model.state.value.layout
        try {
            val layout = fillFirstPage()
            openJiggleEditMenu()
            val menu = bounds("jiggle-menu")
            val target = placedApps().map { id -> id to bounds("home-app-$id") }
                .firstOrNull { (_, icon) -> !icon.overlaps(menu) && icon.top > menu.bottom + 8f }
            checkNotNull(target) { "an icon outside the menu" }
            pressAndHold(target.second.center)
            assertTrue("held scrim must not lift ${target.first}", !lifted())
            release()
            compose.waitUntil(5_000) { !exists("jiggle-menu") }
            assertTrue("still editing", exists("editor-title"))
            compose.runOnIdle { assertEquals(layout, model.state.value.layout) }
        } finally {
            closeSheetsAndEditing()
            compose.runOnIdle { model.restoreLayout(before) }
        }
    }

    @Test fun slidingAcrossMenuRowsRunsOnlyTheRowUnderTheLiftedFinger() {
        val before = model.state.value.layout
        try {
            fillFirstPage()
            openJiggleEditMenu()
            val widgets = bounds("jiggle-menu-widgets").center
            val settingsRow = bounds("jiggle-menu-settings").center
            pressAndHold(widgets)
            compose.onNodeWithTag("launcher-root").performTouchInput { moveTo(settingsRow - rootOrigin, 120) }
            compose.mainClock.advanceTimeBy(200)
            assertTrue(!lifted())
            release()
            // Settings ran; Add Widget did not open the gallery.
            compose.waitUntil(5_000) { exists("settings-page") }
            assertTrue(!exists("widget-catalog-list"))
            // Lifting outside the platter runs nothing: reopen and slide off before releasing.
            closeSheetsAndEditing()
            openJiggleEditMenu()
            val menu = bounds("jiggle-menu")
            pressAndHold(bounds("jiggle-menu-settings").center)
            release(at = Offset(menu.center.x, menu.bottom + 120f))
            assertTrue("menu stays open", exists("jiggle-menu"))
            assertTrue(!exists("settings-page"))
        } finally {
            closeSheetsAndEditing()
            compose.runOnIdle { model.restoreLayout(before) }
        }
    }

    @Test fun longPressOnFolderChildMenuRowNeverLiftsAnotherChild() {
        val before = model.state.value.layout
        try {
            val apps = model.state.value.apps.filter { it.available }.take(9).map { it.id }
            val folderId = newFolderId()
            compose.runOnIdle {
                model.restoreLayout(HomeLayout(listOf(folderId), List(4) { null },
                    folders = listOf(FolderEntry(folderId, "Tools", apps))))
            }
            compose.onNodeWithTag("home-folder-$folderId", useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { exists("folder-child-${apps[0]}") }
            compose.waitForIdle()
            pressAndHold(bounds("folder-child-${apps[0]}").center)
            release()
            compose.waitUntil(5_000) { exists("folder-child-menu") }
            compose.waitForIdle()
            val layout = model.state.value.layout
            val row = bounds("folder-child-menu-edit-home").center
            pressAndHold(row)
            assertTrue("held folder menu row must not lift a folder app", !lifted())
            release()
            // The row ran: the folder closed and Home is jiggling; the folder is unchanged.
            compose.waitUntil(5_000) { exists("editor-title") }
            compose.runOnIdle { assertEquals(layout, model.state.value.layout) }
        } finally {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            closeSheetsAndEditing()
            compose.runOnIdle { model.restoreLayout(before) }
        }
    }
}
