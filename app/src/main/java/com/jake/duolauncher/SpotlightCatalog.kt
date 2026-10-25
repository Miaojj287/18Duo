package com.jake.duolauncher

import java.net.URLEncoder

/**
 * Settings pages Spotlight may open. Every entry only *opens* a public Settings screen
 * (or one of 18Duo's own sheets); none toggles anything, so a mistaken tap is harmless.
 * Actions are the public `android.settings.*` strings; unknown ones fall back to Settings.
 */
internal data class SettingsLink(
    val id: String,
    val action: String,
    val zh: String,
    val en: String,
    val keywords: List<String> = emptyList(),
) {
    val title: String get() = uxText(zh, en)
    val opensLauncherSheet: Boolean get() = action.startsWith(DUO_ACTION_PREFIX)
}

internal const val DUO_ACTION_PREFIX = "duo:"

internal val SETTINGS_LINKS: List<SettingsLink> = listOf(
    SettingsLink("wifi", "android.settings.WIFI_SETTINGS", "无线局域网", "Wi-Fi", listOf("wifi", "wlan", "无线", "网络", "network")),
    SettingsLink("bluetooth", "android.settings.BLUETOOTH_SETTINGS", "蓝牙", "Bluetooth", listOf("bluetooth", "耳机", "headphones")),
    SettingsLink("cellular", "android.settings.DATA_ROAMING_SETTINGS", "蜂窝网络", "Cellular", listOf("移动网络", "数据", "sim", "mobile data", "5g")),
    SettingsLink("airplane", "android.settings.AIRPLANE_MODE_SETTINGS", "飞行模式", "Airplane Mode", listOf("airplane", "flight", "飞行")),
    SettingsLink("notifications", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "通知", "Notifications", listOf("notification", "提醒", "推送")),
    SettingsLink("focus", "android.settings.ZEN_MODE_PRIORITY_SETTINGS", "勿扰模式", "Do Not Disturb", listOf("focus", "专注", "dnd", "免打扰")),
    SettingsLink("sound", "android.settings.SOUND_SETTINGS", "声音与触感", "Sounds & Haptics", listOf("sound", "ringtone", "铃声", "音量", "volume", "振动", "vibration")),
    SettingsLink("display", "android.settings.DISPLAY_SETTINGS", "显示与亮度", "Display & Brightness", listOf("display", "brightness", "亮度", "深色", "dark mode", "字体", "font")),
    SettingsLink("night", "android.settings.NIGHT_DISPLAY_SETTINGS", "夜览", "Night Shift", listOf("护眼", "night", "eye", "色温")),
    SettingsLink("battery", "android.intent.action.POWER_USAGE_SUMMARY", "电池", "Battery", listOf("battery", "电量", "耗电", "power")),
    SettingsLink("battery-saver", "android.settings.BATTERY_SAVER_SETTINGS", "低电量模式", "Low Power Mode", listOf("省电", "battery saver", "low power")),
    SettingsLink("privacy", "android.settings.PRIVACY_SETTINGS", "隐私与安全性", "Privacy & Security", listOf("privacy", "隐私", "权限", "permission")),
    SettingsLink("security", "android.settings.SECURITY_SETTINGS", "面容与密码", "Face ID & Passcode", listOf("密码", "password", "passcode", "锁屏", "lock", "指纹", "fingerprint", "face")),
    SettingsLink("location", "android.settings.LOCATION_SOURCE_SETTINGS", "定位服务", "Location Services", listOf("location", "gps", "位置", "定位")),
    SettingsLink("storage", "android.settings.INTERNAL_STORAGE_SETTINGS", "存储空间", "Storage", listOf("storage", "存储", "空间", "清理")),
    SettingsLink("apps", "android.settings.MANAGE_APPLICATIONS_SETTINGS", "App", "Apps", listOf("应用", "apps", "applications", "应用管理")),
    SettingsLink("default-apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "默认 App", "Default Apps", listOf("默认", "default", "浏览器", "browser")),
    SettingsLink("home-app", "android.settings.HOME_SETTINGS", "默认桌面", "Default Home App", listOf("桌面", "launcher", "home", "主屏幕")),
    SettingsLink("datetime", "android.settings.DATE_SETTINGS", "日期与时间", "Date & Time", listOf("时间", "日期", "time", "date", "时区", "timezone")),
    SettingsLink("language", "android.settings.LOCALE_SETTINGS", "语言与地区", "Language & Region", listOf("language", "语言", "地区", "region")),
    SettingsLink("keyboard", "android.settings.INPUT_METHOD_SETTINGS", "键盘", "Keyboard", listOf("keyboard", "输入法", "input")),
    SettingsLink("accessibility", "android.settings.ACCESSIBILITY_SETTINGS", "辅助功能", "Accessibility", listOf("accessibility", "无障碍", "辅助")),
    SettingsLink("nfc", "android.settings.NFC_SETTINGS", "NFC", "NFC", listOf("nfc", "刷卡", "门卡")),
    SettingsLink("vpn", "android.settings.VPN_SETTINGS", "VPN", "VPN", listOf("vpn", "代理", "proxy")),
    SettingsLink("cast", "android.settings.CAST_SETTINGS", "投屏", "Screen Mirroring", listOf("cast", "mirror", "镜像", "投射")),
    SettingsLink("data-usage", "android.settings.DATA_USAGE_SETTINGS", "流量使用", "Data Usage", listOf("流量", "data usage")),
    SettingsLink("accounts", "android.settings.SYNC_SETTINGS", "账户", "Accounts", listOf("account", "账号", "同步", "sync")),
    SettingsLink("about", "android.settings.DEVICE_INFO_SETTINGS", "关于本机", "About", listOf("about", "关于", "型号", "版本", "version")),
    SettingsLink("developer", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "开发者选项", "Developer Options", listOf("developer", "开发者", "调试", "adb")),
    SettingsLink("duo-settings", "${DUO_ACTION_PREFIX}settings", "18Duo 桌面设置", "18Duo Home Settings", listOf("18duo", "桌面设置", "主屏幕", "home screen", "布局", "layout")),
    SettingsLink("duo-wallpaper", "${DUO_ACTION_PREFIX}wallpaper", "墙纸", "Wallpaper", listOf("wallpaper", "壁纸", "背景", "background")),
    SettingsLink("duo-widgets", "${DUO_ACTION_PREFIX}widgets", "添加小组件", "Add Widget", listOf("widget", "小组件", "组件")),
)

/** Best score over the localized title, the other-language title and the keywords. */
internal fun settingsLinkScore(query: String, link: SettingsLink, keyOf: (String) -> SearchKey): Int {
    val title = matchScore(query, keyOf(link.title))
    val other = matchScore(query, keyOf(if (link.title == link.zh) link.en else link.zh))
    // A keyword hit is a weaker signal than the page's own name.
    val keyword = link.keywords.maxOfOrNull { matchScore(query, keyOf(it)) }?.let { (it - 60).coerceAtLeast(0) } ?: 0
    return maxOf(title, other - 20, keyword)
}

/**
 * Settings results need a deliberate query: one Latin letter would match half the list.
 * A single Han character is a whole word and is allowed.
 */
internal fun settingsQueryAllowed(query: String): Boolean {
    val trimmed = query.trim()
    return trimmed.length >= 2 || (trimmed.isNotEmpty() && containsHan(trimmed))
}

/** Fallback when no browser accepts ACTION_WEB_SEARCH. Bing answers both inside and outside mainland China. */
internal fun webSearchUrl(query: String): String =
    "https://www.bing.com/search?q=" + URLEncoder.encode(query.trim(), "UTF-8")

/** Spotlight's result groups, in the order iOS shows them. */
internal enum class SpotlightSection { TOP_HIT, APPS, SHORTCUTS, SETTINGS, WEB }

internal data class SpotlightPlan(
    val topHitAppId: String?,
    val appIds: List<String>,
    val settings: List<SettingsLink>,
)

/**
 * Pure ranking for one query: apps (pinyin-aware, recently used first on ties) and settings
 * pages. The Top Hit is the best app when its match is at least a prefix/pinyin match, as on
 * iOS where an infix hit is never promoted.
 */
internal fun planSpotlight(
    query: String,
    apps: List<Pair<String, SearchKey>>,
    recentIds: List<String>,
    keyOf: (String) -> SearchKey,
    appLimit: Int = 8,
    settingsLimit: Int = 4,
): SpotlightPlan {
    if (query.isBlank()) return SpotlightPlan(null, emptyList(), emptyList())
    val rank = recentIds.withIndex().associate { it.value to it.index }
    val ranked = rankMatches(query, apps, { it.second }, { rank[it.first] })
    val top = ranked.firstOrNull()?.takeIf { it.score >= SearchScore.PINYIN_PREFIX }?.item?.first
    val settings = if (!settingsQueryAllowed(query)) emptyList() else SETTINGS_LINKS
        .map { it to settingsLinkScore(query, it, keyOf) }
        .filter { it.second >= SearchScore.PINYIN_PREFIX }
        .sortedByDescending { it.second }
        .map { it.first }.take(settingsLimit)
    return SpotlightPlan(top, ranked.map { it.item.first }.filter { it != top }.take(appLimit), settings)
}
