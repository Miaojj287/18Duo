package com.jake.duolauncher

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
 * Round 3 evidence and checks for the Dock (owner item 8) in whatever posture the QA emulator is
 * set to (`wm size`): four Dock apps, with and without the two recent apps. A side-column Dock is
 * centred on the window height; the inner-portrait Dock is a compact floating glass hugging its icons.
 * Round 4: centred with the recent apps too, never over the status block or Search; on the rotated
 * cover the recents overflow (DuoHomeLayout `SideDockSpec.fit`). Screenshots go to
 * Android/data/<pkg>/files/dock-evidence/<posture>-*.png, plus one of the jiggle "编辑" menu; the
 * measured glass is logged under the tag DockEvidence.
 */
@RunWith(AndroidJUnit4::class)
class DockEvidenceTest {
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

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val root = compose.activity.window.decorView
        val posture = when {
            root.width > root.height -> "landscape"
            compose.activity.panelOrientation.panel.value == FoldPanel.COVER -> "cover"
            else -> "inner-portrait"
        }
        val dir = java.io.File(compose.activity.getExternalFilesDir(null), "dock-evidence").apply { mkdirs() }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(java.io.File(dir, "$posture-$name.png"))
    }

    private fun report(name: String, window: androidx.compose.ui.geometry.Rect, glass: androidx.compose.ui.geometry.Rect) {
        val d = compose.activity.resources.displayMetrics.density
        android.util.Log.i("DockEvidence", "%s window %.0fx%.0f px glass y %.0f-%.0f x %.0f-%.0f (%.1fx%.1f dp) centre y %.1f px = %.4f H, offset %.2f dp"
            .format(name, window.width, window.height, glass.top, glass.bottom, glass.left, glass.right,
                glass.width / d, glass.height / d, glass.center.y, glass.center.y / window.height,
                (glass.center.y - window.center.y) / d))
    }

    /** The side Dock never reaches the time + ring block or the Search button. */
    private fun assertClearOfColumn(glass: androidx.compose.ui.geometry.Rect) {
        compose.onAllNodesWithTag("status-rail", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let {
            assertTrue("Dock top ${glass.top} below the status block ${it.bottom}", glass.top >= it.bottom)
        }
        compose.onAllNodesWithTag("search-visual", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let {
            assertTrue("Dock bottom ${glass.bottom} above Search ${it.top}", glass.bottom <= it.top)
        }
    }

    @Test fun dockIsCentredOrFloating() {
        val before = model.state.value.layout
        val showing = compose.activity.corePreferences.showRecentApps
        val recentBefore = compose.activity.getSharedPreferences("recent_apps", 0).getString("history", "")
        try {
            val apps = model.state.value.apps.filter { it.available }
            val dock = apps.take(4).map { it.id }
            compose.runOnIdle {
                compose.activity.corePreferences.setRecentApps(false)
                model.restoreLayout(HomeLayout(apps.drop(4).take(12).map { it.id }, dock))
            }
            compose.waitForIdle()
            val window = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
            val glass = compose.onNodeWithTag("dock", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val side = glass.height > glass.width
            report("recents-off", window, glass)
            if (side) {
                assertEquals("side Dock centred on the panel", window.center.y, glass.center.y, 3f)
                assertClearOfColumn(glass)
            }
            else {
                assertEquals("floating Dock centred horizontally", window.center.x, glass.center.x, 2f)
                assertTrue("compact, not a full-width strip", glass.width < window.width * .6f)
            }
            shot("dock")
            compose.onNodeWithTag("home-page-0", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("editor-settings").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("jiggle-menu").fetchSemanticsNodes().isNotEmpty() }
            shot("jiggle-edit-menu")
            // While the menu is open a tap anywhere (Done included) only closes the menu, as on iOS.
            compose.onNodeWithTag("editor-done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("jiggle-menu").fetchSemanticsNodes().isEmpty() }
            assertTrue("still editing", compose.onAllNodesWithTag("editor-done").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("editor-done").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("editor-done").fetchSemanticsNodes().isEmpty() }
            compose.runOnIdle {
                compose.activity.corePreferences.setRecentApps(true)
                apps.drop(4).take(3).forEach(compose.activity.recentApps::record)
            }
            val rotatedCover = compose.activity.panelOrientation.panel.value == FoldPanel.COVER && window.width > window.height
            if (rotatedCover) {
                // 425 dp tall: not even the four apps fit centred at Apple's size, so the recents overflow.
                compose.waitForIdle(); Thread.sleep(800); compose.waitForIdle()
                assertTrue("recents overflow on the rotated cover",
                    compose.onAllNodesWithTag("recent-dock", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                val group = compose.onNodeWithTag("dock", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                report("recents-on", window, group)
                assertEquals(glass, group)
                shot("dock-recents")
                return
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("recent-dock", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val group = compose.onNodeWithTag("dock", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            report("recents-on", window, group)
            if (side) {
                assertEquals("the whole group is centred", window.center.y, group.center.y, 3f)
                assertClearOfColumn(group)
            } else assertEquals(window.center.x, group.center.x, 2f)
            shot("dock-recents")
            // Re-ranking the same recent apps (what the resume refresh does) moves nothing; a newly
            // used app replaces only the one that dropped out, in that slot (no swap, no blink).
            val recent = apps.drop(4).take(3).map { it.id }  // recorded oldest → newest: shown [r2, r1]
            fun slotOf(id: String) = compose.onAllNodesWithTag("recent-app-$id", useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            val r1 = slotOf(recent[1]); val r2 = slotOf(recent[2])
            assertTrue(r1 != null && r2 != null)
            compose.runOnIdle { compose.activity.recentApps.record(apps[5]) }
            compose.mainClock.advanceTimeBy(RECENT_DOCK_SETTLE_MS + 200)
            compose.waitForIdle()
            assertEquals(r1, slotOf(recent[1])); assertEquals(r2, slotOf(recent[2]))
            compose.runOnIdle { compose.activity.recentApps.record(apps[4]) }
            compose.mainClock.advanceTimeBy(RECENT_DOCK_SETTLE_MS + 200)
            compose.waitForIdle()
            assertEquals("newcomer takes the freed slot", r2, slotOf(recent[0]))
            assertEquals(r1, slotOf(recent[1]))
        } finally {
            if (compose.onAllNodesWithTag("editor-done").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("editor-done").performClick()
            compose.runOnIdle {
                compose.activity.corePreferences.setRecentApps(showing)
                compose.activity.getSharedPreferences("recent_apps", 0).edit().putString("history", recentBefore).commit()
                model.restoreLayout(before)
            }
        }
    }
}
