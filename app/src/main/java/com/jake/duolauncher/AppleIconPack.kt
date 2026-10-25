package com.jake.duolauncher

import android.content.ComponentName
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.UserHandle
import androidx.core.graphics.drawable.toBitmap

/** Apps whose original artwork should always be shown, even if the MTZ has a matching entry. */
private val originalIconPackages = setOf(
    "com.hpbr.bosszhipin",                // BOSS直聘
    "com.tencent.tmgp.sgame",             // 王者荣耀
    "com.tencent.lolm",                   // 英雄联盟手游
    "com.larus.nova",                     // 豆包
    "com.bytedance.android.doubaoime",   // 豆包输入法
)

/** Resolve a theme entry without guessing from the app label or matching another package's prefix. */
internal fun appleIconKey(packageName: String, className: String, available: Set<String>): String? {
    if (packageName in originalIconPackages) return null
    val qualifiedClass = when {
        className.startsWith(".") -> packageName + className
        '.' in className -> className
        className.isNotEmpty() -> "$packageName.$className"
        else -> ""
    }
    val activityKey = qualifiedClass.takeIf { it.startsWith("$packageName.") }.orEmpty()
    return sequenceOf(activityKey, "$packageName.${qualifiedClass.substringAfterLast('.')}", packageName)
        .filter(String::isNotBlank).distinct().firstOrNull(available::contains)
}

/** The supplied MTZ's app artwork is already masked and shaded; decode it as-is. */
internal class AppleIconPack(private val assets: AssetManager) {
    private val available: Set<String> by lazy {
        runCatching {
            assets.open("apple_icons/index.txt").bufferedReader().use { reader ->
                reader.readLines().filter(String::isNotBlank).toHashSet()
            }
        }.getOrDefault(emptySet())
    }

    fun iconFor(component: ComponentName): Bitmap? {
        // iOS draws the Calendar icon with today's date (r21); the MTZ's is a fixed page.
        if (component.packageName in LIVE_CALENDAR_PACKAGES)
            return calendarIconBitmap(java.time.LocalDate.now(), java.util.Locale.getDefault().language == "zh")
        val key = appleIconKey(component.packageName, component.className, available) ?: return null
        return runCatching {
            assets.open("apple_icons/$key.webp").use { stream ->
                BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })?.takeIf { it.width == ICON_BITMAP_PX && it.height == ICON_BITMAP_PX }
            }
        }.getOrNull()
    }

    /** Keep the icon shown by Home and by the navigation overlay identical, including profile badges. */
    fun iconFor(component: ComponentName, context: Context, badgeUser: UserHandle?): Bitmap? {
        val artwork = iconFor(component) ?: return null
        if (badgeUser == null) return artwork
        return runCatching {
            context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, artwork), badgeUser)
                .toBitmap(ICON_BITMAP_PX, ICON_BITMAP_PX, Bitmap.Config.ARGB_8888)
        }.getOrDefault(artwork)
    }
}
