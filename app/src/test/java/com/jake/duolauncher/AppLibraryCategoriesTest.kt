package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class AppLibraryCategoriesTest {
    @Test fun knownPackagesWinOverDeclaredCategory() {
        assertEquals(LibraryCategory.SOCIAL, categorizeApp("com.tencent.mm", AndroidAppCategory.UNDEFINED, false))
        assertEquals(LibraryCategory.PRODUCTIVITY, categorizeApp("com.eg.android.AlipayGphone", AndroidAppCategory.UNDEFINED, false))
        assertEquals(LibraryCategory.ENTERTAINMENT, categorizeApp("tv.danmaku.bili", AndroidAppCategory.SOCIAL, false))
        assertEquals(LibraryCategory.UTILITIES, categorizeApp("com.android.settings", AndroidAppCategory.UNDEFINED, true))
        assertEquals(LibraryCategory.UTILITIES, categorizeApp("com.android.deskclock", AndroidAppCategory.UNDEFINED, true))
    }

    @Test fun androidCategoriesMapToIosFolders() {
        assertEquals(LibraryCategory.GAMES, categorizeApp("a.b", AndroidAppCategory.GAME, false))
        assertEquals(LibraryCategory.ENTERTAINMENT, categorizeApp("a.b", AndroidAppCategory.AUDIO, false))
        assertEquals(LibraryCategory.ENTERTAINMENT, categorizeApp("a.b", AndroidAppCategory.VIDEO, false))
        assertEquals(LibraryCategory.CREATIVITY, categorizeApp("a.b", AndroidAppCategory.IMAGE, false))
        assertEquals(LibraryCategory.SOCIAL, categorizeApp("a.b", AndroidAppCategory.SOCIAL, false))
        assertEquals(LibraryCategory.INFORMATION, categorizeApp("a.b", AndroidAppCategory.NEWS, false))
        assertEquals(LibraryCategory.TRAVEL, categorizeApp("a.b", AndroidAppCategory.MAPS, false))
        assertEquals(LibraryCategory.PRODUCTIVITY, categorizeApp("a.b", AndroidAppCategory.PRODUCTIVITY, false))
        assertEquals(LibraryCategory.UTILITIES, categorizeApp("a.b", AndroidAppCategory.ACCESSIBILITY, false))
    }

    @Test fun undefinedCategoriesFallBackToPackageWordsThenSystemFlag() {
        assertEquals(LibraryCategory.GAMES, categorizeApp("com.tencent.tmgp.sgame", AndroidAppCategory.UNDEFINED, false))
        assertEquals(LibraryCategory.PRODUCTIVITY, categorizeApp("com.example.mybank", AndroidAppCategory.UNDEFINED, false))
        assertEquals(LibraryCategory.CREATIVITY, categorizeApp("com.vendor.camera", AndroidAppCategory.UNDEFINED, false))
        assertEquals(LibraryCategory.UTILITIES, categorizeApp("com.vendor.xyz", AndroidAppCategory.UNDEFINED, true))
        assertEquals(LibraryCategory.OTHER, categorizeApp("com.vendor.xyz", AndroidAppCategory.UNDEFINED, false))
        // "schedule" contains "edu" but is a productivity word, which is checked first.
        assertEquals(LibraryCategory.PRODUCTIVITY, categorizeApp("com.vendor.schedule", AndroidAppCategory.UNDEFINED, false))
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day

    private fun app(id: String, category: LibraryCategory, installed: Long = 0L, system: Boolean = false) =
        LibraryApp(id, id, category, installed, system)

    @Test fun smartFoldersComeFirstAndRespectRecencyAndInstallWindow() {
        val apps = listOf(
            app("wechat", LibraryCategory.SOCIAL, now - 300 * day),
            app("new1", LibraryCategory.OTHER, now - 2 * day),
            app("new2", LibraryCategory.GAMES, now - 1 * day),
            app("old", LibraryCategory.OTHER, now - 90 * day),
            app("sysNew", LibraryCategory.UTILITIES, now - 1 * day, system = true),
        )
        val folders = buildLibraryFolders(apps, recentIds = listOf("ghost", "wechat", "new1"), now = now)
        assertEquals(LibraryCategory.SUGGESTIONS, folders[0].category)
        assertEquals(listOf("wechat", "new1"), folders[0].appIds)
        assertEquals(LibraryCategory.RECENTLY_ADDED, folders[1].category)
        assertEquals(listOf("new2", "new1"), folders[1].appIds)
        // Categories with recently used apps lead; the rest keep the iOS order.
        assertEquals(listOf(LibraryCategory.SOCIAL, LibraryCategory.OTHER, LibraryCategory.GAMES, LibraryCategory.UTILITIES),
            folders.drop(2).map { it.category })
        assertEquals(listOf("new1", "old"), folders.first { it.category == LibraryCategory.OTHER }.appIds)
    }

    @Test fun noRecentsAndNoInstallsMeansNoSmartFolders() {
        val folders = buildLibraryFolders(listOf(app("b", LibraryCategory.OTHER), app("a", LibraryCategory.OTHER)), emptyList(), now)
        assertEquals(1, folders.size)
        assertEquals(listOf("a", "b"), folders.single().appIds)
        assertTrue(buildLibraryFolders(emptyList(), listOf("x"), now).isEmpty())
    }

    @Test fun categoryFoldersShowThreeLargeIconsAndAClusterLikeIos() {
        val five = LibraryFolder(LibraryCategory.SOCIAL, listOf("1", "2", "3", "4", "5"))
        assertEquals(listOf("1", "2", "3"), five.largeIds)
        assertEquals(listOf("4", "5"), five.clusterIds)
        val eight = LibraryFolder(LibraryCategory.SOCIAL, (1..8).map(Int::toString))
        assertEquals(listOf("4", "5", "6", "7"), eight.clusterIds)
        val four = LibraryFolder(LibraryCategory.SOCIAL, listOf("1", "2", "3", "4"))
        assertEquals(4, four.largeIds.size)
        assertTrue(four.clusterIds.isEmpty())
        val suggestions = LibraryFolder(LibraryCategory.SUGGESTIONS, (1..8).map(Int::toString))
        assertEquals(4, suggestions.largeIds.size)
        assertTrue(suggestions.clusterIds.isEmpty())
    }
}
