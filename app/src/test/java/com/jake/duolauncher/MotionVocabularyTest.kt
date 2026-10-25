package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11 goal (`docs/uiux-core17-r11-motion-goal.md`): one named spring per kind of motion,
 * each written as a SwiftUI (response, damping) pair, and an icon press that darkens instead of
 * scaling.
 */
class MotionVocabularyTest {

    private val named: Map<String, IosSpring> = mapOf(
        "zoomOpen" to LauncherMotion.zoomOpenSpring, "zoomClose" to LauncherMotion.zoomCloseSpring,
        "menuIn" to LauncherMotion.menuInSpring, "menuOut" to LauncherMotion.menuOutSpring,
        "sheetPresent" to LauncherMotion.sheetPresentSpring, "sheetDismiss" to LauncherMotion.sheetDismissSpring,
        "sheetReturn" to LauncherMotion.sheetReturnSpring, "navigationPush" to LauncherMotion.navigationPushSpring,
        "alertIn" to LauncherMotion.alertInSpring, "alertOut" to LauncherMotion.alertOutSpring,
        "bannerIn" to LauncherMotion.bannerInSpring, "bannerOut" to LauncherMotion.bannerOutSpring,
        "switchKnob" to LauncherMotion.switchKnobSpring, "switchPress" to LauncherMotion.switchPressSpring,
        "disclosure" to LauncherMotion.disclosureSpring, "wizardSegment" to LauncherMotion.wizardSegmentSpring,
        "glassPress" to LauncherMotion.glassPressSpring, "editPresence" to LauncherMotion.editPresenceSpring,
        "spotlightSettle" to LauncherMotion.spotlightSettleSpring,
    )

    @Test fun `every named spring is an iOS-range spring, never a tween`() {
        named.forEach { (name, spring) ->
            // UIKit/SwiftUI interface springs live between .interactiveSpring (0.15 s) and .smooth (0.5 s).
            assertTrue("$name response ${spring.response}", spring.response in .15f..0.5f)
            // Bounce at most 0.4 (SwiftUI .bouncy is 0.3): nothing wobbles like Material.
            assertTrue("$name damping ${spring.dampingFraction}", spring.dampingFraction in .6f..1f)
        }
    }

    @Test fun `zooms out of a tapped tile share one pair of springs`() {
        assertEquals(.42f, LauncherMotion.zoomOpenSpring.response, 1e-6f)
        assertEquals(.86f, LauncherMotion.zoomOpenSpring.dampingFraction, 1e-6f)
        assertEquals(.36f, LauncherMotion.zoomCloseSpring.response, 1e-6f)
        assertEquals(1f, LauncherMotion.zoomCloseSpring.dampingFraction, 0f)
        // The App Library category (round 10, owner-accepted) and the Home folder are the same zoom.
        assertSame(LauncherMotion.zoomOpenSpring, LIBRARY_CATEGORY_OPEN)
        assertSame(LauncherMotion.zoomCloseSpring, LIBRARY_CATEGORY_CLOSE)
        // Closing is quicker and never overshoots the tile.
        assertTrue(LauncherMotion.zoomCloseSpring.response < LauncherMotion.zoomOpenSpring.response)
    }

    @Test fun `sheets present on SwiftUI smooth and leave a little faster`() {
        assertSame(LauncherMotion.smoothSpring, LauncherMotion.sheetPresentSpring)
        assertEquals(.5f, LauncherMotion.sheetPresentSpring.response, 1e-6f)
        assertEquals(1f, LauncherMotion.sheetPresentSpring.dampingFraction, 0f)
        assertTrue(LauncherMotion.sheetDismissSpring.response < LauncherMotion.sheetPresentSpring.response)
        assertEquals(1f, LauncherMotion.sheetDismissSpring.dampingFraction, 0f)
        // The push/pop inside a sheet and its dismissal are the same critically damped 0.36 s.
        assertEquals(LauncherMotion.sheetDismissSpring.response, LauncherMotion.navigationPushSpring.response, 1e-6f)
        // A released pull that stays carries a touch of bounce back to rest.
        assertTrue(LauncherMotion.sheetReturnSpring.dampingFraction < 1f)
    }

    @Test fun `menus, alerts and glass presses keep their tuned numbers under their new names`() {
        assertEquals(420f, LauncherMotion.menuInSpring.stiffness, .5f)
        assertEquals(.80f, LauncherMotion.menuInSpring.dampingFraction, 1e-6f)
        assertEquals(900f, LauncherMotion.menuOutSpring.stiffness, .5f)
        assertEquals(520f, LauncherMotion.alertInSpring.stiffness, .5f)
        assertEquals(420f, LauncherMotion.glassPressSpring.stiffness, .5f)
        assertEquals(.62f, LauncherMotion.glassPressSpring.dampingFraction, 1e-6f)
        assertEquals(520f, LauncherMotion.switchKnobSpring.stiffness, .5f)
        assertEquals(380f, LauncherMotion.editPresenceSpring.stiffness, .5f)
        assertEquals(320f, LauncherMotion.spotlightSettleSpring.stiffness, .5f)
    }

    @Test fun `an icon press darkens the artwork by a third and never beyond`() {
        assertEquals(0f, iconHighlightAlpha(0f), 0f)
        assertEquals(ICON_HIGHLIGHT_ALPHA, iconHighlightAlpha(1f), 1e-6f)
        assertEquals(ICON_HIGHLIGHT_ALPHA / 2f, iconHighlightAlpha(.5f), 1e-6f)
        // A spring that overshoots, or a bad value, cannot push the highlight past its design.
        assertEquals(ICON_HIGHLIGHT_ALPHA, iconHighlightAlpha(1.3f), 1e-6f)
        assertEquals(0f, iconHighlightAlpha(-.2f), 0f)
        assertEquals(0f, iconHighlightAlpha(Float.NaN), 0f)
        assertTrue(ICON_HIGHLIGHT_ALPHA in .25f..0.4f)
    }

    @Test fun `a centred form sheet rises from below the bottom edge and lands without scale or fade`() {
        val sheet = 900f // 90 % of a 1000 px window, centred: its top is 50 px down, the bottom edge 950 px away.
        assertTrue(SheetPullMath.formSheetTranslation(0f, sheet) >= 950f)
        assertEquals(0f, SheetPullMath.formSheetTranslation(1f, sheet), 0f)
        assertEquals(SheetPullMath.formSheetTranslation(0f, sheet) / 2f, SheetPullMath.formSheetTranslation(.5f, sheet), 1e-3f)
        // A spring overshoot past 1 or a value below 0 never pulls the sheet above its rest or below the start.
        assertEquals(0f, SheetPullMath.formSheetTranslation(1.1f, sheet), 0f)
        assertEquals(SheetPullMath.formSheetTranslation(0f, sheet), SheetPullMath.formSheetTranslation(-.2f, sheet), 0f)
    }
}
