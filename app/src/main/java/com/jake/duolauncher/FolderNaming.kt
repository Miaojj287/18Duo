package com.jake.duolauncher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps

/**
 * iOS names a new folder after the App Store category of its apps ("工具", "社交", "创意" …)
 * instead of a generic "文件夹". When the two apps disagree, the app that was dropped onto (it
 * stays in place and becomes the folder) decides; "其他" and the App Library's smart groups never
 * name a folder, and with nothing better the generic name remains.
 */
internal fun suggestedFolderCategory(stays: LibraryCategory?, joins: LibraryCategory?): LibraryCategory? {
    fun LibraryCategory?.names() = this != null && !smart && this != LibraryCategory.OTHER
    return when {
        stays.names() -> stays
        joins.names() -> joins
        else -> null
    }
}

internal fun suggestedFolderTitle(stays: LibraryCategory?, joins: LibraryCategory?): String =
    suggestedFolderCategory(stays, joins)?.title ?: uxText("文件夹", "Folder")

/** The App Library category of [app] (same rules as the App Library page). */
internal fun libraryCategoryOf(context: Context, app: AppEntry): LibraryCategory {
    val info = runCatching {
        context.getSystemService(LauncherApps::class.java).getApplicationInfo(app.packageName, 0, app.user)
    }.getOrNull()
    val system = info != null && info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    return categorizeApp(app.packageName, info?.category ?: AndroidAppCategory.UNDEFINED, system)
}
