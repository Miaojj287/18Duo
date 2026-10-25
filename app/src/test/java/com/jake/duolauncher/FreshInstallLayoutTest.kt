package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class FreshInstallLayoutTest {
    private fun app(pkg: String, work: Boolean = false) = Phase22AppCandidate("$pkg/Main", pkg, pkg, work)

    @Test fun `Duo dock uses real phone browser messages music in that order`() {
        val pkgs = listOf("com.android.contacts", "com.android.browser", "com.android.mms", "com.miui.player")
        assertEquals(pkgs.map { "$it/Main" }, freshInstallDock((pkgs + "com.android.chrome" + "com.spotify.music").map { app(it) }))
    }
    @Test fun `missing dock apps stay empty and work apps are excluded`() {
        assertEquals(List<String?>(4) { null }, freshInstallDock(listOf(app("com.unknown.app"), app("com.miui.player", true))))
    }
    @Test fun `fresh home uses installed apps without seeded categories or duplicate shortcuts`() {
        val apps = listOf("com.android.contacts", "com.android.mms", "com.android.browser", "com.android.camera",
            "com.miui.gallery", "com.android.settings", "com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.miui.calculator", "com.miui.notes", "com.taobao.taobao", "com.jingdong.app.mall",
            "com.unknown.app", "com.spotify.music").map { app(it) }
        val dock = freshInstallDock(apps)
        val base = HomeLayout(emptyList(), dock, DEFAULT_WIDGET_PLACEMENTS)
        val result = phase22AppLayout(base, apps, freshInstall = true)
        assertEquals(dock, result.dock)
        assertEquals(base.widgetPlacements, result.widgetPlacements)
        assertTrue(result.folders.isEmpty())
        assertTrue(result.slots.take(HOME_CELLS).contains("com.tencent.mm/Main"))
        assertTrue(result.slots.drop(HOME_CELLS).filterNotNull().all(::isFolderId))
        val placed = result.slots.filterNotNull().filterNot(::isFolderId) + result.folders.flatMap { it.appIds } + dock.filterNotNull()
        assertEquals(placed.distinct(), placed)
        assertTrue(placed.all { id -> apps.any { it.id == id } })
        assertTrue(placed.contains("com.unknown.app/Main"))
        assertEquals(result, phase22AppLayout(result, apps, freshInstall = true))
    }
    @Test fun `language does not create demonstration folders`() {
        val layout = phase22AppLayout(HomeLayout(emptyList(), List(4) { null }),
            listOf(app("com.miui.calculator"), app("com.miui.notes")), freshInstall = true, english = true)
        assertTrue(layout.folders.isEmpty())
        assertEquals(2, layout.slots.filterNotNull().size)
    }
    @Test fun `Duo artwork default separates app image and widget footprint`() {
        val cover = homeGeometry(425f, 622f, LayoutPreset(), true)
        val inner = homeGeometry(860f, 608f, LayoutPreset(), true)
        // The same physical 54 dp icon on both panels (12.7 %W cover, 6.24 %W unfolded).
        assertEquals(54f, cover.iconSize, .5f)
        assertEquals(cover.iconSize, inner.iconSize, 1f)
        assertEquals(cover.iconSize, homeIconArtworkSize(cover.iconSize), 0f)
        assertEquals(4f * .189f * 425f, cover.gridWidth, .01f)
        assertTrue(cover.referenceCardSize!! > cover.gridWidth / 4f + cover.iconSize)
    }
    @Test fun `revision3 migration converts old artwork to nominal size exactly once`() {
        assertEquals(68f, upgradeArtworkDefault(LayoutPreset(iconSize = 44f), 0).iconSize, 0f)
        assertEquals(68f, upgradeArtworkDefault(LayoutPreset(iconSize = 46f), 1).iconSize, 0f)
        assertEquals(62f, upgradeArtworkDefault(LayoutPreset(iconSize = 54f), 2).iconSize, 0f)
        for (value in listOf(48f, 54f, 56f, 68f)) {
            val expected = if (value == 56f) 68f else value
            assertEquals(expected, upgradeArtworkDefault(LayoutPreset(iconSize = value), 3).iconSize, 0f)
            assertEquals(value, upgradeArtworkDefault(LayoutPreset(iconSize = value), 4).iconSize, 0f)
        }
    }
}
