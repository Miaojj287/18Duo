package com.jake.duolauncher

/** Installed app identity used by the deterministic core desktop preset. */
data class Phase22AppCandidate(val id: String, val packageName: String, val label: String, val isWork: Boolean = false)

/**
 * Apps that can fill one place, in order of preference. An entry is a package, or `package/SimpleClass` when a
 * package has several launcher activities (MIUI's Phone and Contacts are both `com.android.contacts`).
 */
private data class AppChoice(val packages: List<String>)
private fun choice(vararg packages: String) = AppChoice(packages.toList())
private fun Phase22AppCandidate.matches(entry: String): Boolean {
    val pkg = entry.substringBefore('/')
    if (packageName != pkg) return false
    if ('/' !in entry) return true
    val component = parseProfileAppId(id)?.component ?: return false
    return component.substringAfter('/').substringAfterLast('.') == entry.substringAfter('/')
}
private fun AppChoice.resolve(apps: List<Phase22AppCandidate>, used: Set<String>): Phase22AppCandidate? =
    packages.firstNotNullOfOrNull { name -> apps.firstOrNull { it.matches(name) && it.id !in used } }

/** Preserves the Duo geometry and real widgets while choosing apps from this device only. */
fun phase22AppLayout(current: HomeLayout, candidates: List<Phase22AppCandidate>, freshInstall: Boolean = false, english: Boolean = false): HomeLayout {
    val apps = candidates.filterNot { it.isWork }.distinctBy { it.id }
    val leadingFolderIds = current.leadingSlots.filterNotNull().filter(::isFolderId).toSet()
    val retainedFolders = current.folders.filter { it.id in leadingFolderIds }
    val used = (current.dock.filterNotNull() + current.leadingSlots.filterNotNull() + retainedFolders.flatMap { it.appIds }).toMutableSet()
    val priority = listOf(
        choice("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d"),
        choice("com.android.settings"),
        choice("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage"),
        choice("com.android.fileexplorer", "com.mi.android.globalFileexplorer", "com.sec.android.app.myfiles"),
    ).mapNotNull { it.resolve(apps, used)?.also { app -> used += app.id } }
    val remaining = apps.filter { it.id !in used }.sortedWith(compareBy({ it.label.lowercase() }, { it.id }))
    val items = (priority + remaining).iterator()
    val blocked = current.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    val pageCapacity = HOME_CELLS * maxOf(1, (priority.size + remaining.size + blocked.size + HOME_CELLS - 1) / HOME_CELLS)
    val slots = MutableList<String?>(pageCapacity) { null }
    for (cell in slots.indices) if (cell !in blocked && items.hasNext()) slots[cell] = items.next().id
    return current.copy(slots = slots.dropLastWhile { it == null }, folders = retainedFolders)
}

fun freshInstallDock(candidates: List<Phase22AppCandidate>): List<String?> {
    val apps = candidates.filterNot { it.isWork }
    val used = mutableSetOf<String>()
    return listOf(
        choice("com.android.contacts/TwelveKeyDialer", "com.android.dialer", "com.google.android.dialer",
            "com.samsung.android.dialer", "com.android.contacts"),
        choice("com.android.browser", "com.mi.globalbrowser", "com.android.chrome", "com.sec.android.app.sbrowser"),
        choice("com.android.mms", "com.android.messaging", "com.google.android.apps.messaging", "com.samsung.android.messaging"),
        choice("com.miui.player", "com.android.music", "com.apple.android.music", "com.spotify.music", "com.tencent.qqmusic", "com.netease.cloudmusic"),
    ).map { it.resolve(apps, used)?.id?.also(used::add) }
}

// =======================================================================================
// iPhone Duo's own Home Screen (round 21, the owner's screenshots of a real iPhone Duo:
// evidence/r21/ref/4.webp and 5.webp), resolved to this device's apps. Apple's order is kept;
// an Apple app with no counterpart here is skipped (no hole), so later apps move up.
//   Page 1: [Weather S][Calendar S] / FaceTime Calendar Photos Camera / Mail Notes Clock Maps /
//           TV App Store Games Health / Wallet Settings
//   Page 2: Weather Stocks Find My Home / Podcasts Books Fitness Watch / Reminders Files Preview
//           [Utilities] / Contacts iTunes Store Translate Freeform / Journal Tips Apple Store
//           [Creativity] / Sports
//   Today View (1.webp): Calendar M / Notes S, Reminders S / Maps M
// Every other app follows from page 3, alphabetically.
// =======================================================================================

/** One Home cell of iPhone Duo's layout: an app, or a folder made of whichever of its apps exist. */
private sealed interface DuoCell
private class DuoApp(val choice: AppChoice) : DuoCell
private class DuoFolder(val zh: String, val en: String, val apps: List<AppChoice>) : DuoCell
private fun app(vararg packages: String) = DuoApp(choice(*packages))

private val DUO_PAGE_1: List<DuoCell> = listOf(
    app("com.google.android.apps.tachyon"),                                               // FaceTime
    app("com.android.calendar", "com.google.android.calendar", "com.samsung.android.calendar"),
    app("com.miui.gallery", "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d"),
    app("com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera2"),
    app("com.android.email", "com.google.android.gm", "com.samsung.android.email.provider", "com.tencent.androidqqmail"),
    app("com.miui.notes", "com.google.android.keep", "com.samsung.android.app.notes"),
    app("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage"),
    app("com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map", "com.google.android.apps.maps"),
    app("com.miui.video", "com.google.android.videos"),                                   // TV
    app("com.xiaomi.market", "com.android.vending", "com.huawei.appmarket", "com.heytap.market"), // App Store
    app("com.xiaomi.gamecenter", "com.nearme.gamecenter", "com.google.android.play.games"), // Games
    app("com.mi.health", "com.google.android.apps.fitness", "com.sec.android.app.shealth", "com.huawei.health"),
    app("com.mipay.wallet", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay"),
    app("com.android.settings"),
)

private val DUO_PAGE_2: List<DuoCell> = listOf(
    app("com.miui.weather2", "com.google.android.apps.weather", "com.sec.android.daemonapp"),
    app("com.xiaomi.finddevice", "com.google.android.apps.adm"),                          // Find My
    app("com.xiaomi.smarthome", "com.google.android.apps.chromecast.app"),                 // Home
    app("com.duokan.reader", "com.google.android.apps.books"),                             // Books
    app("com.xiaomi.hm.health"),                                                           // Fitness
    app("com.xiaomi.wearable"),                                                            // Watch
    app("com.android.fileexplorer", "com.mi.android.globalFileexplorer", "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles", "com.google.android.documentsui"),                  // Files
    DuoFolder("实用工具", "Utilities", listOf(
        choice("com.android.soundrecorder", "com.miui.soundrecorder"), choice("com.miui.calculator", "com.google.android.calculator"),
        choice("com.miui.compass"), choice("com.xiaomi.scanner"), choice("com.miui.securitymanager", "com.miui.securitycenter"),
        choice("com.android.providers.downloads.ui"), choice("com.miui.huanji"), choice("com.duokan.phone.remotecontroller"),
        choice("com.miui.virtualsim"))),
    app("com.android.contacts/PeopleActivity", "com.google.android.contacts", "com.samsung.android.app.contacts"),
    app("com.miui.themestore", "com.android.thememanager"),                                // iTunes Store
    app("com.google.android.apps.translate"),                                              // Translate
    app("com.miui.miservice"),                                                             // Tips
    app("com.xiaomi.shop"),                                                                // Apple Store
)

/** A default 18Duo widget: its registry key and where it goes on its page. */
internal data class DuoWidgetSpot(val key: String, val page: Int, val column: Int, val row: Int, val spanX: Int, val spanY: Int)

internal val DUO_DEFAULT_WIDGETS = listOf(
    DuoWidgetSpot("native:calendar:medium", -1, 0, 0, 4, 2),
    DuoWidgetSpot("native:notes:small", -1, 0, 2, 2, 2),
    DuoWidgetSpot("native:reminders:small", -1, 2, 2, 2, 2),
    DuoWidgetSpot("native:maps:medium", -1, 0, 4, 4, 2),
    DuoWidgetSpot("native:weather:small", 0, 0, 0, 2, 2),
    DuoWidgetSpot("native:calendar:small", 0, 2, 0, 2, 2),
)

/**
 * Adds iPhone Duo's default widgets ([DUO_DEFAULT_WIDGETS]) where their page has room: Today View only when
 * it holds nothing yet, a Home page spot only when no widget covers it. Existing widgets never move.
 */
internal fun withDuoDefaultWidgets(current: HomeLayout, widgetId: (String) -> Int? = ::parseNativeWidgetKey): HomeLayout {
    val todayEmpty = current.widgetPlacements.none { it.page == -1 } && current.leadingSlots.all { it == null }
    var slot = (current.widgetPlacements.maxOfOrNull { it.slot } ?: -1) + 1
    val placements = current.widgetPlacements.toMutableList()
    for (spot in DUO_DEFAULT_WIDGETS) {
        if (spot.page == -1 && !todayEmpty) continue
        val id = widgetId(spot.key) ?: continue
        val placement = WidgetPlacement(slot, id, spot.page, spot.column, spot.row, spot.spanX, spot.spanY)
        if (placements.any { it.page == placement.page && it.column < placement.column + placement.spanX &&
                placement.column < it.column + it.spanX && it.row < placement.row + placement.spanY &&
                placement.row < it.row + it.spanY }) continue
        placements += placement
        slot++
    }
    return current.copy(widgetPlacements = placements)
}

/**
 * iPhone Duo's Home Screen for this device (see the table above): widgets first ([withDuoDefaultWidgets]),
 * then pages 1 and 2 in Apple's order around them, folders included, then every other app from page 3.
 * The Dock ([freshInstallDock]) and the unfolded-only Today shortcuts in [current] are kept.
 */
fun duoHomeLayout(current: HomeLayout, candidates: List<Phase22AppCandidate>, english: Boolean = false,
    widgetId: (String) -> Int? = ::parseNativeWidgetKey): HomeLayout {
    val apps = candidates.filterNot { it.isWork }.distinctBy { it.id }
    val leadingFolderIds = current.leadingSlots.filterNotNull().filter(::isFolderId).toSet()
    val retainedFolders = current.folders.filter { it.id in leadingFolderIds }
    val used = (current.dock.filterNotNull() + current.leadingSlots.filterNotNull() + retainedFolders.flatMap { it.appIds }).toMutableSet()
    val withWidgets = withDuoDefaultWidgets(current.copy(folders = retainedFolders), widgetId)
    val blocked = withWidgets.widgetPlacements.filter { it.page >= 0 }.flatMapTo(mutableSetOf()) { it.coveredIndices() }
    val folders = retainedFolders.toMutableList()
    fun resolve(cells: List<DuoCell>): List<String> = cells.mapNotNull { cell -> when (cell) {
        is DuoApp -> cell.choice.resolve(apps, used)?.id?.also(used::add)
        is DuoFolder -> {
            val members = cell.apps.mapNotNull { it.resolve(apps, used)?.id?.also(used::add) }
            when (members.size) {
                0 -> null
                1 -> members.single()
                else -> FolderEntry(newFolderId(), if (english) cell.en else cell.zh, members).also(folders::add).id
            }
        }
    } }
    val page1 = resolve(DUO_PAGE_1)
    val page2 = resolve(DUO_PAGE_2)
    val rest = apps.filter { it.id !in used }.sortedWith(compareBy({ it.label.lowercase() }, { it.id })).map { it.id }
    val slots = mutableListOf<String?>()
    // Writes [ids] from [start] around widget cells; returns the cell after the last one written.
    fun fill(ids: List<String>, start: Int): Int {
        var cell = start
        for (id in ids) {
            while (cell in blocked) cell++
            while (slots.size <= cell) slots.add(null)
            slots[cell] = id
            cell++
        }
        return cell
    }
    fun nextPage(cell: Int) = (cell + HOME_CELLS - 1) / HOME_CELLS * HOME_CELLS
    var cell = fill(page1, 0)
    cell = fill(page2, if (page2.isEmpty()) cell else maxOf(nextPage(cell), HOME_CELLS).takeIf { page1.isNotEmpty() } ?: 0)
    fill(rest, if (rest.isEmpty() || cell == 0) cell else nextPage(cell))
    return withWidgets.copy(slots = slots.dropLastWhile { it == null }, folders = folders)
}
