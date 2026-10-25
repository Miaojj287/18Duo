package com.jake.duolauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Code review P1-2: leaving StandBy must neither replay Home's requests nor reset Home's state. */
class StandbyHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeSurvivesStandbyAndRequestsNeverReplay() {
        val surface = mutableStateOf(LauncherSurface.HOME)
        val settingsRequests = mutableIntStateOf(0)
        var settingsOpened = 0
        var veiled = 0
        compose.setContent {
            StandbySurfaceHost(surface.value, onVeiled = { veiled++ },
                standby = { active -> Box(Modifier.fillMaxSize().testTag(if (active) "fake-standby" else "fake-face-veil")) },
                home = {
                    // Like LauncherScreen: a request-keyed effect, saveable and plain remembered state.
                    val request = settingsRequests.intValue
                    LaunchedEffect(request) { if (request > 0) settingsOpened++ }
                    var page by rememberSaveable { mutableIntStateOf(0) }
                    var draft by remember { mutableStateOf("") }
                    Box(Modifier.fillMaxSize().testTag("fake-home").clickable { page++; draft += "x" }) {
                        Text("$page/$draft", Modifier.testTag("fake-home-state"))
                    }
                })
            ConsumeLauncherRequests(settingsRequests)
        }
        compose.runOnIdle { settingsRequests.intValue++ }
        compose.waitUntil(2_000) { settingsRequests.intValue == 0 }
        compose.runOnIdle { assertEquals(1, settingsOpened) }
        compose.onNodeWithTag("fake-home").performClick()
        compose.onNodeWithTag("fake-home").performClick()
        compose.onNodeWithTag("fake-home-state", useUnmergedTree = true).assertTextEquals("2/xx")

        // Enter: Home stays drawn while the black veil rises; the controller hears once it is on screen (S1).
        compose.runOnIdle { surface.value = LauncherSurface.ENTERING }
        compose.waitUntil(3_000) { veiled == 1 }
        compose.onNodeWithTag("standby-veil").assertIsDisplayed()
        compose.onNodeWithTag("fake-standby").assertDoesNotExist()
        for (away in listOf(LauncherSurface.ROTATING, LauncherSurface.STANDBY)) {
            compose.runOnIdle { surface.value = away }
            // Parked: not drawn, not touchable, not in the accessibility tree.
            compose.onNodeWithTag("fake-home").assertDoesNotExist()
        }
        compose.onNodeWithTag("fake-standby").assertIsDisplayed()
        // Exit: the face stays up (inactive) while the window settles, then dissolves off Home.
        compose.runOnIdle { surface.value = LauncherSurface.EXITING }
        compose.onNodeWithTag("fake-face-veil").assertIsDisplayed()
        compose.onNodeWithTag("fake-home").assertDoesNotExist()
        compose.runOnIdle { surface.value = LauncherSurface.HOME }
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("fake-face-veil").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("standby-veil").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, veiled) }
        compose.onNodeWithTag("fake-home-state", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("2/xx")
        compose.runOnIdle {
            assertEquals("Settings must not reopen on StandBy exit", 1, settingsOpened)
            assertEquals(0, settingsRequests.intValue)
        }
        // A new request is still delivered once.
        compose.runOnIdle { settingsRequests.intValue++ }
        compose.waitUntil(2_000) { settingsRequests.intValue == 0 }
        compose.runOnIdle { assertEquals(2, settingsOpened) }
    }
}
