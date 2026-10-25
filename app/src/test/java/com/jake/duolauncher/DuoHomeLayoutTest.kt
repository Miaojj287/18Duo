package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iPhone Duo's own Home Screen resolved to a Xiaomi's apps (r21, evidence/r21/ref/4.webp and 5.webp). */
class DuoHomeLayoutTest {
    private fun app(component: String, work: Boolean = false) =
        Phase22AppCandidate(component, component.substringBefore('/'), component.substringAfterLast('.'), work)

    // The owner's phone: both Phone and Contacts live in com.android.contacts.
    private val dialer = app("com.android.contacts/com.android.contacts.activities.TwelveKeyDialer")
    private val contacts = app("com.android.contacts/com.android.contacts.activities.PeopleActivity")
    private val xiaomi = listOf(dialer, contacts) + listOf(
        "com.android.browser/.launch.SplashActivity", "com.android.mms/.ui.MmsTabActivity", "com.miui.player/.ui.MusicBrowserActivity",
        "com.android.calendar/.homepage.AllInOneActivity", "com.miui.gallery/.MainActivity", "com.android.camera/.Camera",
        "com.android.email/com.wps.multiwindow.main.HomeActivity", "com.miui.notes/.Notes", "com.android.deskclock/.DeskClockTabActivity",
        "com.autonavi.minimap/com.autonavi.map.activity.SplashActivity", "com.miui.video/.Launcher1", "com.xiaomi.market/.ui.MarketTabActivity",
        "com.xiaomi.gamecenter/.ui.MainTabActivity", "com.mi.health/.Splash", "com.mipay.wallet/.Finance", "com.android.settings/.MainSettings",
        "com.miui.weather2/.ActivityWeatherMain", "com.xiaomi.smarthome/.SmartHomeMainActivity", "com.duokan.reader/.DkReaderActivity",
        "com.android.fileexplorer/.FileExplorerTabActivity", "com.miui.calculator/.cal.CalculatorActivity", "com.miui.compass/.CompassActivity",
        "com.android.soundrecorder/.StartActivity", "com.miui.themestore/.Tabs", "com.miui.miservice/.main.MainActivity",
        "com.xiaomi.shop/.activity.MainTabActivity", "com.tencent.mm/.ui.LauncherUI", "com.eg.android.AlipayGphone/.AlipayLogin",
    ).map(::app)
    private val ids = mapOf("native:calendar:medium" to -109, "native:notes:small" to -184, "native:reminders:small" to -187,
        "native:maps:medium" to -191, "native:weather:small" to -112, "native:calendar:small" to -108)

    private fun layout(apps: List<Phase22AppCandidate> = xiaomi, base: HomeLayout = HomeLayout(emptyList(), freshInstallDock(apps))) =
        duoHomeLayout(base, apps, widgetId = ids::get)

    @Test fun `the Dock is Phone, Safari, Messages, Music and picks the dialer over Contacts`() {
        assertEquals(listOf(dialer.id, "com.android.browser/.launch.SplashActivity", "com.android.mms/.ui.MmsTabActivity",
            "com.miui.player/.ui.MusicBrowserActivity"), freshInstallDock(xiaomi))
    }

    @Test fun `page 1 follows Apple's order below the Weather and Calendar widgets`() {
        val result = layout()
        // Rows 1–2 are the two small widgets; apps start on row 3 (cell 8). FaceTime has no counterpart.
        assertTrue(result.slots.take(8).all { it == null })
        assertEquals(listOf("calendar", "gallery", "camera", "email", "notes", "deskclock", "minimap", "video", "market",
            "gamecenter", "health", "wallet", "settings"),
            result.slots.subList(8, HOME_CELLS).filterNotNull().map { it.substringBefore('/').substringAfterLast('.') })
        val page1Widgets = result.widgetPlacements.filter { it.page == 0 }.sortedBy { it.column }
        assertEquals(listOf(-112, -108), page1Widgets.map { it.id })
        assertTrue(page1Widgets.all { it.row == 0 && it.spanX == 2 && it.spanY == 2 })
    }

    @Test fun `page 2 holds Weather, Home, Books, Files, the Utilities folder and Contacts in order`() {
        val result = layout()
        val page2 = result.slots.subList(HOME_CELLS, minOf(result.slots.size, 2 * HOME_CELLS)).filterNotNull()
        val folder = result.folders.single()
        assertEquals("实用工具", folder.title)
        assertEquals(listOf("com.android.soundrecorder", "com.miui.calculator", "com.miui.compass"),
            folder.appIds.map { it.substringBefore('/') })
        assertEquals(listOf("com.miui.weather2", "com.xiaomi.smarthome", "com.duokan.reader", "com.android.fileexplorer",
            folder.id, contacts.id, "com.miui.themestore", "com.miui.miservice", "com.xiaomi.shop"),
            page2.map { if (isFolderId(it) || it == contacts.id) it else it.substringBefore('/') })
    }

    @Test fun `everything else starts on page 3 and every app appears exactly once`() {
        val result = layout()
        assertEquals(listOf("com.eg.android.AlipayGphone/.AlipayLogin", "com.tencent.mm/.ui.LauncherUI"),
            result.slots.drop(2 * HOME_CELLS).filterNotNull())
        val placed = result.slots.filterNotNull().filterNot(::isFolderId) + result.folders.flatMap { it.appIds } +
            result.dock.filterNotNull()
        assertEquals(placed.distinct(), placed)
        assertEquals(xiaomi.map { it.id }.toSet(), placed.toSet())
    }

    @Test fun `Today View gets Calendar, Notes, Reminders and Maps only while it is empty`() {
        val today = layout().widgetPlacements.filter { it.page == -1 }.sortedWith(compareBy({ it.row }, { it.column }))
        assertEquals(listOf(-109, -184, -187, -191), today.map { it.id })
        assertEquals(listOf(4 to 2, 2 to 2, 2 to 2, 4 to 2), today.map { it.spanX to it.spanY })
        val mine = WidgetPlacement(7, 1234, -1, 0, 0, 2, 2)
        val kept = layout(base = HomeLayout(emptyList(), List(4) { null }, listOf(mine)))
        assertEquals(listOf(mine), kept.widgetPlacements.filter { it.page == -1 })
        // Slots never collide with existing ones.
        assertEquals(kept.widgetPlacements.map { it.slot }.distinct(), kept.widgetPlacements.map { it.slot })
    }

    @Test fun `a widget already on page 1 keeps its place and the apps flow around it`() {
        val medium = WidgetPlacement(0, 4321, 0, 0, 0, 4, 2)
        val result = layout(base = HomeLayout(emptyList(), freshInstallDock(xiaomi), listOf(medium)))
        assertEquals(listOf(medium), result.widgetPlacements.filter { it.page == 0 })
        assertTrue(result.slots.take(8).all { it == null })
        assertEquals("com.android.calendar", result.slots[8]?.substringBefore('/'))
    }

    @Test fun `a widget the registry does not know is skipped and a single folder app stays loose`() {
        val result = duoHomeLayout(HomeLayout(emptyList(), List(4) { null }),
            listOf(app("com.miui.calculator/.Calc"), app("com.miui.weather2/.Main")), widgetId = { null })
        assertTrue(result.widgetPlacements.isEmpty())
        assertTrue(result.folders.isEmpty())
        // Nothing for page 1, so page 2's apps start at the first cell instead of leaving an empty page.
        assertEquals(listOf("com.miui.weather2/.Main", "com.miui.calculator/.Calc"), result.slots.filterNotNull())
        assertEquals(0, result.slots.indexOf("com.miui.weather2/.Main"))
        assertNull(result.folders.firstOrNull())
    }

    @Test fun `work apps never land on Home`() {
        val result = layout(xiaomi + app("com.tencent.wework/.Launch", work = true))
        assertTrue(result.slots.none { it == "com.tencent.wework/.Launch" })
    }
}
