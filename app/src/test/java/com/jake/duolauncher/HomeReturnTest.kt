package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 6 return-to-origin: every launch surface, the Home press on Home, dual apps, posture changes. */
class HomeReturnTest {
    private val cover = PagerPosture(spread = false, leading = true)
    private val coverNoToday = PagerPosture(spread = false, leading = false)
    private val inner = PagerPosture(spread = true, leading = true)
    // Three Home pages: cover = Today 0, Home 1..3, Library 4 (5 pages); inner = Today 0, [P1|P2] 1, [P2|P3] 2, Library 3.
    private val coverPages = 5
    private val innerPages = 4

    private fun origin(page: OriginPage, physical: Int, posture: PagerPosture, folder: String? = null, category: String? = null,
        search: Boolean = false, spotlight: Boolean = false) =
        LaunchOrigin("com.x/.Main", page, physical, posture.spread, posture.leading, folder, category, search, spotlight,
            launchedAt = 1_000L, armed = true)

    @Test fun `page kinds on the cover and unfolded`() {
        assertEquals(OriginPage.TODAY, originPageOf(0, cover, coverPages))
        assertEquals(OriginPage.HOME, originPageOf(1, cover, coverPages))
        assertEquals(OriginPage.HOME, originPageOf(3, cover, coverPages))
        assertEquals(OriginPage.LIBRARY, originPageOf(4, cover, coverPages))
        assertEquals(OriginPage.TODAY, originPageOf(0, inner, innerPages))
        assertEquals(OriginPage.HOME, originPageOf(2, inner, innerPages))
        assertEquals(OriginPage.LIBRARY, originPageOf(3, inner, innerPages))
        // Today hidden: page 0 is Home 1.
        assertEquals(OriginPage.HOME, originPageOf(0, coverNoToday, 4))
    }

    @Test fun `surfaces name the innermost place`() {
        assertEquals(LaunchSurface.HOME, origin(OriginPage.HOME, 2, cover).surface)
        assertEquals(LaunchSurface.TODAY, origin(OriginPage.TODAY, 0, cover).surface)
        assertEquals(LaunchSurface.FOLDER, origin(OriginPage.HOME, 2, cover, folder = "folder:1").surface)
        assertEquals(LaunchSurface.FOLDER, origin(OriginPage.TODAY, 0, inner, folder = "folder:1").surface)
        assertEquals(LaunchSurface.LIBRARY, origin(OriginPage.LIBRARY, 4, cover).surface)
        assertEquals(LaunchSurface.LIBRARY_CATEGORY, origin(OriginPage.LIBRARY, 4, cover, category = "SOCIAL").surface)
        assertEquals(LaunchSurface.LIBRARY_SEARCH, origin(OriginPage.LIBRARY, 3, inner, search = true).surface)
        assertEquals(LaunchSurface.SPOTLIGHT, origin(OriginPage.HOME, 1, cover, spotlight = true).surface)
    }

    @Test fun `a Home that never moved stays put`() {
        for ((o, current) in listOf(
            origin(OriginPage.HOME, 3, cover) to 3, origin(OriginPage.TODAY, 0, cover) to 0,
            origin(OriginPage.LIBRARY, 4, cover) to 4, origin(OriginPage.TODAY, 0, inner) to 0,
            origin(OriginPage.HOME, 2, inner) to 2, origin(OriginPage.LIBRARY, 3, inner) to 3,
        )) {
            val posture = if (o.spread) inner else cover
            assertNull(o.toString(), originRestorePage(o, posture, if (o.spread) innerPages else coverPages, o.spread, current))
        }
    }

    @Test fun `a recreated unfolded window comes back to Today View and the App Library`() {
        // Process death: the unfolded pager is rebuilt on [P1 | P2] (page 1), never on the Today overlay.
        assertEquals(0, originRestorePage(origin(OriginPage.TODAY, 0, inner), inner, innerPages, true, currentPage = 1))
        assertEquals(3, originRestorePage(origin(OriginPage.LIBRARY, 3, inner), inner, innerPages, true, currentPage = 1))
        assertEquals(2, originRestorePage(origin(OriginPage.HOME, 2, inner), inner, innerPages, true, currentPage = 1))
        assertEquals(0, originRestorePage(origin(OriginPage.TODAY, 0, cover), cover, coverPages, false, currentPage = 1))
    }

    @Test fun `the App Library is the last page even when the page count changed`() {
        assertEquals(5, originRestorePage(origin(OriginPage.LIBRARY, 4, cover), cover, 6, false, currentPage = 1))
        // A Home page that is gone resolves to the last Home page, never to the library.
        assertEquals(3, originRestorePage(origin(OriginPage.HOME, 6, cover), cover, coverPages, false, currentPage = 1))
        // Unfolded, a Home origin never lands on the Today overlay.
        assertEquals(1, originRestorePage(origin(OriginPage.HOME, 0, inner), inner, innerPages, true, currentPage = 2))
    }

    @Test fun `a fold or unfold in between keeps the posture hand-off's page`() {
        assertNull(originRestorePage(origin(OriginPage.TODAY, 0, cover), inner, innerPages, true, currentPage = 1))
        assertNull(originRestorePage(origin(OriginPage.LIBRARY, 3, inner), cover, coverPages, false, currentPage = 4))
        assertNull(originRestorePage(origin(OriginPage.HOME, 2, cover), coverNoToday, 4, false, currentPage = 1))
    }

    @Test fun `Today hidden since the launch resolves to the first Home page`() {
        val o = LaunchOrigin("a/.B", OriginPage.TODAY, 0, spread = false, leading = false, armed = true)
        assertEquals(0, originRestorePage(o, coverNoToday, 4, false, currentPage = 2))
    }

    @Test fun `first Home page per posture`() {
        assertEquals(1, firstHomePhysicalPage(cover, todayOverlay = false, pageCount = coverPages))
        assertEquals(0, firstHomePhysicalPage(coverNoToday, todayOverlay = false, pageCount = 4))
        assertEquals(1, firstHomePhysicalPage(inner, todayOverlay = true, pageCount = innerPages))
        assertEquals(0, firstHomePhysicalPage(PagerPosture(spread = true, leading = false), todayOverlay = false, pageCount = 3))
        assertEquals(0, firstHomePhysicalPage(inner, todayOverlay = true, pageCount = 1))
    }

    @Test fun `Home press - back from an app keeps everything, on Home it closes then goes to page one`() {
        assertEquals(HomeRequestAction.KEEP, homeRequestAction(returningFromApp = true, anythingOpen = true, physicalPage = 4, firstHomePage = 1))
        assertEquals(HomeRequestAction.KEEP, homeRequestAction(true, false, 0, 1))
        assertEquals(HomeRequestAction.CLOSE, homeRequestAction(false, true, 3, 1))
        assertEquals(HomeRequestAction.CLOSE, homeRequestAction(false, true, 1, 1))
        assertEquals(HomeRequestAction.FIRST_PAGE, homeRequestAction(false, false, 3, 1))
        assertEquals(HomeRequestAction.FIRST_PAGE, homeRequestAction(false, false, 0, 1)) // Today View
        assertEquals(HomeRequestAction.FIRST_PAGE, homeRequestAction(false, false, 4, 1)) // App Library
        assertEquals(HomeRequestAction.NONE, homeRequestAction(false, false, 1, 1))
    }

    @Test fun `only a launch that took Home away arms its origin`() {
        assertTrue(originArms(1_000L, 1_300L))
        assertTrue(originArms(1_000L, 1_000L + ORIGIN_ARM_WINDOW_MS))
        assertFalse(originArms(1_000L, 1_001L + ORIGIN_ARM_WINDOW_MS))
        assertFalse(originArms(1_000L, 900L))
    }

    @Test fun `origins survive the saved instance state`() {
        val all = listOf(origin(OriginPage.HOME, 2, cover), origin(OriginPage.LIBRARY, 3, inner, category = "GAMES", search = true),
            origin(OriginPage.TODAY, 0, inner, folder = "folder:abc", spotlight = true).copy(armed = false))
        for (o in all) assertEquals(o, LaunchOrigin.fromList(o.toList()))
        assertNull(LaunchOrigin.fromList(listOf("x")))
    }

    @Test fun `the layer in front is the one the card may land in`() {
        val home = frontIconLayers(spotlight = false, sheet = false, folder = false, category = false, todayOverlayIn = false, fullLibraryIn = false)
        assertTrue(IconLayer.HOME in home && IconLayer.DOCK in home && IconLayer.LIBRARY in home)
        assertFalse(IconLayer.FOLDER in home || IconLayer.TODAY_OVERLAY in home)
        assertEquals(setOf(IconLayer.FOLDER), frontIconLayers(false, false, folder = true, category = false, todayOverlayIn = true, fullLibraryIn = false))
        assertEquals(setOf(IconLayer.LIBRARY_CATEGORY), frontIconLayers(false, false, false, category = true, todayOverlayIn = false, fullLibraryIn = true))
        assertEquals(setOf(IconLayer.LIBRARY), frontIconLayers(false, false, false, false, false, fullLibraryIn = true))
        assertEquals(setOf(IconLayer.TODAY_OVERLAY), frontIconLayers(false, false, false, false, todayOverlayIn = true, fullLibraryIn = false))
        assertEquals(setOf(IconLayer.SPOTLIGHT), frontIconLayers(spotlight = true, sheet = false, folder = true, category = false, todayOverlayIn = false, fullLibraryIn = false))
        assertEquals(emptySet<IconLayer>(), frontIconLayers(true, sheet = true, folder = false, category = false, todayOverlayIn = false, fullLibraryIn = false))
    }

    private fun at(x: Float, y: Float, size: Float = 148f) = ZoomBounds(x, y, x + size, y + size)

    @Test fun `the card returns into the folder, library or Today icon in front, not the Home copy under it`() {
        val id = "com.x/.Main"
        val grid = HomeIconEntry(id, at(100f, 400f), HomeIconKind.GRID, IconLayer.HOME)
        val folder = HomeIconEntry(id, at(500f, 700f), HomeIconKind.GRID, IconLayer.FOLDER)
        val library = HomeIconEntry(id, at(300f, 900f), HomeIconKind.GRID, IconLayer.LIBRARY)
        val category = HomeIconEntry(id, at(320f, 1000f), HomeIconKind.GRID, IconLayer.LIBRARY_CATEGORY)
        val today = HomeIconEntry(id, at(120f, 300f), HomeIconKind.GRID, IconLayer.TODAY_OVERLAY)
        val entries = listOf(grid, folder, library, category, today)
        fun land(front: Set<IconLayer>) = HomeIconLayout(1168f, 1712f, entries, 0L, front).find(id, "com.x", 1168f, 1712f, launchedFrom = null)
        assertEquals(folder, land(setOf(IconLayer.FOLDER)))
        assertEquals(library, land(setOf(IconLayer.LIBRARY)))
        assertEquals(category, land(setOf(IconLayer.LIBRARY_CATEGORY)))
        assertEquals(today, land(setOf(IconLayer.TODAY_OVERLAY)))
        assertEquals(grid, land(setOf(IconLayer.HOME, IconLayer.DOCK, IconLayer.LIBRARY)))
        assertNull(land(setOf(IconLayer.SPOTLIGHT)))
    }

    @Test fun `the launching tile wins over the kind order`() {
        val id = "com.x/.Main"
        val grid = HomeIconEntry(id, at(100f, 400f), HomeIconKind.GRID)
        val dock = HomeIconEntry(id, at(1000f, 900f, 120f), HomeIconKind.DOCK, IconLayer.DOCK)
        val recent = HomeIconEntry(id, at(1000f, 1100f, 120f), HomeIconKind.RECENT, IconLayer.DOCK)
        val entries = listOf(grid, dock, recent)
        assertEquals(grid, pickHomeIcon(entries, id, "com.x", 1168f, 1712f))
        assertEquals(dock, pickHomeIcon(entries, id, "com.x", 1168f, 1712f, launchedFrom = at(1000f, 900f, 120f)))
        assertEquals(recent, pickHomeIcon(entries, id, "com.x", 1168f, 1712f, launchedFrom = at(1010f, 1110f, 100f)))
        // A tile that is not there any more (the posture changed): the usual order.
        assertEquals(grid, pickHomeIcon(entries, id, "com.x", 1168f, 1712f, launchedFrom = at(600f, 100f)))
    }

    // ------------------------------------------------------------------ dual apps (XSpace user 999)

    private val wechat = "com.tencent.mm/.ui.LauncherUI"
    private val clone = "duo-profile:v1:15:$wechat"

    @Test fun `a dual-app copy and its parent are told apart by app id`() {
        val parentIcon = HomeIconEntry(wechat, at(100f, 400f), HomeIconKind.GRID)
        val cloneIcon = HomeIconEntry(clone, at(300f, 400f), HomeIconKind.GRID)
        val both = listOf(parentIcon, cloneIcon)
        assertEquals(cloneIcon, pickHomeIcon(both, clone, "com.tencent.mm", 1168f, 1712f))
        assertEquals(parentIcon, pickHomeIcon(both, wechat, "com.tencent.mm", 1168f, 1712f))
        // The clone's icon is on another page: the card never lands in the parent's.
        assertNull(pickHomeIcon(listOf(parentIcon), clone, "com.tencent.mm", 1168f, 1712f))
        assertNull(pickHomeIcon(listOf(cloneIcon), wechat, "com.tencent.mm", 1168f, 1712f))
    }

    @Test fun `launched app ids carry package and profile`() {
        val launched = LaunchedApp.of(clone, null, 5L)!!
        assertEquals("com.tencent.mm", launched.packageName)
        assertEquals(15L, launched.userSerial)
        assertNull(LaunchedApp.of(wechat, null, 5L)!!.userSerial)
        assertNull(LaunchedApp.of("", null, 5L))
    }

    @Test fun `the copy in front - without usage access the last launch decides`() {
        val personal = 0L
        val cloneLaunch = LaunchedApp.of(clone, null, 5L)
        val parentLaunch = LaunchedApp.of(wechat, null, 5L)
        // The real phone: no usage access, WeChat in user 0 and XSpace (serial 15).
        assertEquals(15L, foregroundProfileSerial("com.tencent.mm", personal, null, cloneLaunch, listOf(15L)))
        assertEquals(personal, foregroundProfileSerial("com.tencent.mm", personal, null, parentLaunch, listOf(15L)))
        assertEquals(personal, foregroundProfileSerial("com.tencent.mm", personal, null, null, listOf(15L)))
        // The last launch was another app: nothing says which copy, so ours.
        assertEquals(personal, foregroundProfileSerial("com.tencent.mm", personal, null, LaunchedApp.of("com.y/.A", null, 1L), listOf(15L)))
        // Only one copy installed.
        assertEquals(personal, foregroundProfileSerial("com.tencent.mm", personal, null, cloneLaunch, emptyList()))
    }

    @Test fun `the copy in front - usage events win over the last launch`() {
        val cloneLaunch = LaunchedApp.of(clone, null, 5L)
        val parentLaunch = LaunchedApp.of(wechat, null, 5L)
        // Our copy is resumed: it is ours, whatever was launched.
        assertEquals(0L, foregroundProfileSerial("com.tencent.mm", 0L, true, cloneLaunch, listOf(15L)))
        // Ours is not resumed: another profile's — the launched one when it names one.
        assertEquals(15L, foregroundProfileSerial("com.tencent.mm", 0L, false, parentLaunch, listOf(15L)))
        assertEquals(16L, foregroundProfileSerial("com.tencent.mm", 0L, false, LaunchedApp.of("duo-profile:v1:16:$wechat", null, 1L), listOf(15L, 16L)))
        assertEquals(15L, foregroundProfileSerial("com.tencent.mm", 0L, false, null, listOf(15L, 16L)))
    }
}
