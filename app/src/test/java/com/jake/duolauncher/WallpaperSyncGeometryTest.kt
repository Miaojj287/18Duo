package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WallpaperSyncGeometryTest {
    private val bundledW = 2364
    private val bundledH = 1712

    @Test fun lhasaAndItsPanelSizesSyncBothPanels() {
        val both = listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL)
        assertEquals(both, duoPanelSizes(PanelPixels(1080, 2400), lhasa = true))
        // QA emulator with `wm size` set to either panel, in either orientation.
        assertEquals(both, duoPanelSizes(XIAOMI_COVER_PANEL, lhasa = false))
        assertEquals(both, duoPanelSizes(XIAOMI_INNER_PANEL.rotated(), lhasa = false))
        assertEquals(listOf(PanelPixels(1080, 2400)), duoPanelSizes(PanelPixels(1080, 2400), lhasa = false))
    }

    @Test fun perPanelCropsAreHomesCrops() {
        val panels = listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL)
        val plan = systemWallpaperPlan(bundledW, bundledH, bundled = true, panels)
        // Exactly DuneWallpaper's crop for each panel: the dunes are right-anchored.
        panels.forEach { panel ->
            assertEquals(wallpaperSourceCrop(bundledW, bundledH, panel.width, panel.height, rightAnchored = true), plan.crops[panel])
        }
        val cover = plan.crops.getValue(XIAOMI_COVER_PANEL)
        // The cover shows the right half of the inner picture at the same scale (iPhone Duo window model).
        assertEquals(bundledW, cover.x + cover.width)
        assertEquals(bundledH, cover.height)
        assertEquals(1168, cover.width)
        assertEquals(plan.crops.getValue(XIAOMI_INNER_PANEL), plan.legacyHint)
    }

    @Test fun legacyHintIsExactForWidePhotosOnBothPanels() {
        // A centred landscape photo wider than the inner panel (e.g. 4:3 camera shot rotated, 2048×1152).
        val w = 2048; val h = 1152
        val plan = systemWallpaperPlan(w, h, bundled = false, listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL))
        for (panel in listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL)) {
            val shown = legacyPanelView(plan.legacyHint, panel)
            val home = wallpaperSourceCrop(w, h, panel.width, panel.height, rightAnchored = false)
            assertTrue("$panel: $shown vs $home", abs(shown.x - home.x) <= 1 && abs(shown.width - home.width) <= 2 &&
                abs(shown.y - home.y) <= 1 && abs(shown.height - home.height) <= 2)
        }
    }

    @Test fun legacyHintCannotPlaceRightAnchoredDunesOnTheCover() {
        // Documented trade-off: one crop hint shows the centre of the inner picture on the cover.
        val plan = systemWallpaperPlan(bundledW, bundledH, bundled = true, listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL))
        val shown = legacyPanelView(plan.legacyHint, XIAOMI_COVER_PANEL)
        assertNotEquals(plan.crops.getValue(XIAOMI_COVER_PANEL).x, shown.x)
        assertEquals(legacyPanelView(plan.legacyHint, XIAOMI_INNER_PANEL), plan.legacyHint)
    }

    @Test fun legacyViewKeepsAspect() {
        val hint = WallpaperSourceCrop(10, 20, 1414, 1000)
        val portrait = legacyPanelView(hint, PanelPixels(1000, 2000))
        assertEquals(1000, portrait.height); assertEquals(500, portrait.width); assertEquals(10 + 457, portrait.x)
        val wide = legacyPanelView(hint, PanelPixels(3000, 1000))
        assertEquals(1414, wide.width); assertEquals(471, wide.height); assertEquals(20 + 264, wide.y)
    }

    @Test fun signatureChangesWithImageAndPanels() {
        val a = wallpaperSyncSignature("op-1", 100, 50, listOf(XIAOMI_COVER_PANEL))
        assertEquals(a, wallpaperSyncSignature("op-1", 100, 50, listOf(XIAOMI_COVER_PANEL)))
        assertNotEquals(a, wallpaperSyncSignature("op-2", 100, 50, listOf(XIAOMI_COVER_PANEL)))
        assertNotEquals(a, wallpaperSyncSignature("op-1", 100, 50, listOf(XIAOMI_COVER_PANEL, XIAOMI_INNER_PANEL)))
        assertTrue(wallpaperSyncSignature(null, 1, 1, listOf(XIAOMI_INNER_PANEL)).startsWith("bundled-dunes"))
    }
}
