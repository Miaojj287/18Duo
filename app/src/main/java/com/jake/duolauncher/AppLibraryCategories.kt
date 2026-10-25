package com.jake.duolauncher

/**
 * App Library categories (iOS 14+ naming as shown on a Chinese iPhone). The two smart
 * folders come first; real categories are ordered by how recently their apps were used.
 */
internal enum class LibraryCategory(val zh: String, val en: String) {
    SUGGESTIONS("建议", "Suggestions"),
    RECENTLY_ADDED("最近添加", "Recently Added"),
    SOCIAL("社交", "Social"),
    ENTERTAINMENT("娱乐", "Entertainment"),
    PRODUCTIVITY("效率与财务", "Productivity & Finance"),
    CREATIVITY("创意", "Creativity"),
    INFORMATION("信息与阅读", "Information & Reading"),
    SHOPPING("购物与美食", "Shopping & Food"),
    TRAVEL("旅行", "Travel"),
    HEALTH("健康与健身", "Health & Fitness"),
    EDUCATION("教育", "Education"),
    GAMES("游戏", "Games"),
    UTILITIES("工具", "Utilities"),
    OTHER("其他", "Other"),
    /** iOS 18+ "已隐藏": the locked folder of hidden apps (AppPrivacy), always last, never a categorisation result. */
    HIDDEN("已隐藏", "Hidden");

    val title: String get() = uxText(zh, en)
    val smart: Boolean get() = this == SUGGESTIONS || this == RECENTLY_ADDED
}

/** Values of `ApplicationInfo.category` (stable public API constants, inlined for JVM tests). */
internal object AndroidAppCategory {
    const val UNDEFINED = -1
    const val GAME = 0
    const val AUDIO = 1
    const val VIDEO = 2
    const val IMAGE = 3
    const val SOCIAL = 4
    const val NEWS = 5
    const val MAPS = 6
    const val PRODUCTIVITY = 7
    const val ACCESSIBILITY = 8
}

/**
 * Popular apps whose manifests leave `android:appCategory` undefined (most Chinese apps do),
 * plus system apps whose declared category disagrees with where iOS files the equivalent.
 */
private val KNOWN_PACKAGES: Map<String, LibraryCategory> = buildMap {
    fun put(category: LibraryCategory, vararg packages: String) = packages.forEach { put(it, category) }
    put(LibraryCategory.SOCIAL, "com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim", "com.sina.weibo",
        "com.xingin.xhs", "com.zhihu.android", "com.tencent.wework", "com.alibaba.android.rimet", "com.ss.android.lark",
        "org.telegram.messenger", "com.whatsapp", "com.facebook.katana", "com.instagram.android", "com.twitter.android",
        "com.discord", "jp.naver.line.android", "com.skype.raider", "com.android.mms", "com.google.android.apps.messaging",
        "com.android.contacts", "com.android.dialer", "com.google.android.dialer", "com.google.android.contacts",
        "com.reddit.frontpage", "com.snapchat.android", "com.tencent.qqlite", "com.soulapp.android", "com.p1.mobile.putong")
    put(LibraryCategory.ENTERTAINMENT, "com.ss.android.ugc.aweme", "com.smile.gifmaker", "com.kuaishou.nebula",
        "tv.danmaku.bili", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone", "com.hunantv.imgo.activity",
        "com.netease.cloudmusic", "com.tencent.qqmusic", "com.kugou.android", "cn.kuwo.player", "com.ximalaya.ting.android",
        "com.google.android.youtube", "com.netflix.mediaclient", "com.spotify.music", "com.miui.player", "com.miui.video",
        "com.android.music", "com.google.android.apps.youtube.music", "com.duowan.kiwi", "air.tv.douyu.android",
        "com.ss.android.ugc.aweme.lite", "com.ss.android.article.video")
    put(LibraryCategory.PRODUCTIVITY, "com.eg.android.AlipayGphone", "com.android.email", "com.google.android.gm",
        "com.microsoft.office.outlook", "com.microsoft.office.word", "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint", "com.microsoft.teams", "cn.wps.moffice_eng", "com.tencent.docs",
        "com.android.calendar", "com.google.android.calendar", "com.miui.notes", "com.google.android.keep",
        "com.evernote", "com.notion.id", "com.icbc", "com.chinamworld.main", "cmb.pb", "com.chinamworld.bocmbci",
        "com.android.bankabc", "com.unionpay", "com.tencent.meeting", "us.zoom.videomeetings", "com.google.android.apps.docs",
        "com.baidu.netdisk",
        "com.google.android.apps.walletnfcrel", "com.mipay.wallet", "com.miui.cloudservice")
    put(LibraryCategory.CREATIVITY, "com.miui.gallery", "com.android.camera", "com.google.android.GoogleCamera",
        "com.google.android.apps.photos", "com.meitu.meiyancamera", "com.mt.mtxx.mtxx", "com.lemon.lv", "com.xt.retouch",
        "com.adobe.lrmobile", "com.adobe.psmobile", "com.android.soundrecorder", "com.miui.screenrecorder",
        "com.android.gallery3d", "com.picsart.studio", "com.canva.editor")
    put(LibraryCategory.INFORMATION, "com.ss.android.article.news", "com.tencent.news", "com.netease.newsreader.activity",
        "com.sohu.newsclient", "com.google.android.apps.magazines", "com.tencent.weread", "com.dragon.read",
        "com.qq.reader", "com.amazon.kindlefc", "com.miui.weather2", "com.google.android.apps.weather",
        "com.douban.frodo", "com.android.browser", "com.android.chrome", "com.UCMobile", "com.quark.browser",
        "org.mozilla.firefox", "com.microsoft.emmx", "com.baidu.searchbox", "com.google.android.googlequicksearchbox",
        "com.android.quicksearchbox", "com.miui.newhome", "com.tencent.mtt")
    put(LibraryCategory.SHOPPING, "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall",
        "com.xunmeng.pinduoduo", "com.sankuai.meituan", "me.ele", "com.dianping.v1", "com.sankuai.meituan.takeoutnew",
        "com.taobao.idlefish", "com.achievo.vipshop", "com.amazon.mShop.android.shopping", "com.xiaomi.shop",
        "com.wudaokou.hippo", "com.taobao.litetao", "com.ss.android.ugc.aweme.mall")
    put(LibraryCategory.TRAVEL, "com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map",
        "com.google.android.apps.maps", "ctrip.android.view", "com.MobileTicket", "com.sdu.didi.psnger",
        "com.qunar", "com.taobao.trip", "com.umetrip.android.msky.app", "com.ubercab", "com.airbnb.android",
        "com.mfw.roadbook", "com.jingyao.easybike", "com.tongcheng.android")
    put(LibraryCategory.HEALTH, "com.mi.health", "com.xiaomi.hm.health", "com.huami.watch.hmwatchmanager",
        "com.gotokeep.keep", "com.google.android.apps.fitness", "com.codoon.gps", "com.yuedong.sport",
        "com.dxy.android.doctor")
    put(LibraryCategory.EDUCATION, "com.youdao.dict", "com.youdao.translate", "com.duolingo", "com.baicizhan.main",
        "com.google.android.apps.translate", "com.zuoyebang.knowledge", "com.fenbi.android.solar",
        "com.jiongji.andriod.card", "com.tencent.edu", "com.chaoxing.mobile", "com.coursera.app")
    put(LibraryCategory.UTILITIES, "com.android.settings", "com.android.vending", "com.xiaomi.market",
        "com.android.fileexplorer", "com.google.android.apps.nbu.files", "com.mi.android.globalFileexplorer",
        "com.miui.securitycenter", "com.miui.compass", "com.android.thememanager", "com.miui.backup",
        "com.xiaomi.scanner", "com.miui.huanji", "com.android.stk", "com.miui.cleanmaster", "com.miui.miservice",
        "com.xiaomi.smarthome", "com.android.documentsui", "com.google.android.apps.authenticator2",
        "com.miui.screenshot", "com.miui.remotecontroller", "com.miui.virtualsim", "com.android.providers.downloads.ui",
        "com.android.deskclock", "com.google.android.deskclock", "com.miui.calculator", "com.google.android.calculator",
        "com.android.calculator2")
}

/** Package-name keywords for apps not in the table, checked in this order. */
private val PACKAGE_KEYWORDS: List<Pair<LibraryCategory, List<String>>> = listOf(
    LibraryCategory.GAMES to listOf("game", "tencent.tmgp", "netease.g", "mihoyo", "hoyoverse", "supercell", "miniclip"),
    LibraryCategory.SOCIAL to listOf("chat", "messag", "messenger", "social", "contacts", "dialer", "mms"),
    LibraryCategory.ENTERTAINMENT to listOf("music", "video", "player", "movie", "live", "radio", "podcast", ".tv", "tv."),
    LibraryCategory.PRODUCTIVITY to listOf("bank", "pay", "wallet", "finance", "stock", "office", "docs", "mail",
        "calendar", "schedule", "note", "task", "todo", "meeting", "cloud"),
    LibraryCategory.CREATIVITY to listOf("camera", "photo", "gallery", "editor", "draw", "paint", "recorder"),
    LibraryCategory.SHOPPING to listOf("shop", "mall", "store.buy", "food", "takeout", "deliver"),
    LibraryCategory.TRAVEL to listOf("map", "travel", "trip", "flight", "hotel", "railway", "metro", "taxi"),
    LibraryCategory.HEALTH to listOf("health", "fitness", "sport", "workout", "medic"),
    LibraryCategory.INFORMATION to listOf("news", "read", "book", "weather", "browser", "search"),
    LibraryCategory.EDUCATION to listOf("edu", "learn", "dict", "translat", "study", "school", "course"),
    LibraryCategory.UTILITIES to listOf("file", "tool", "scan", "manager", "clean", "settings", "launcher", "compass", "vpn", "clock", "calculator"),
)

internal fun categorizeApp(packageName: String, androidCategory: Int, isSystemApp: Boolean): LibraryCategory {
    KNOWN_PACKAGES[packageName]?.let { return it }
    when (androidCategory) {
        AndroidAppCategory.GAME -> return LibraryCategory.GAMES
        AndroidAppCategory.AUDIO, AndroidAppCategory.VIDEO -> return LibraryCategory.ENTERTAINMENT
        AndroidAppCategory.IMAGE -> return LibraryCategory.CREATIVITY
        AndroidAppCategory.SOCIAL -> return LibraryCategory.SOCIAL
        AndroidAppCategory.NEWS -> return LibraryCategory.INFORMATION
        AndroidAppCategory.MAPS -> return LibraryCategory.TRAVEL
        AndroidAppCategory.PRODUCTIVITY -> return LibraryCategory.PRODUCTIVITY
        AndroidAppCategory.ACCESSIBILITY -> return LibraryCategory.UTILITIES
    }
    val lower = packageName.lowercase()
    PACKAGE_KEYWORDS.firstOrNull { (_, words) -> words.any(lower::contains) }?.let { return it.first }
    return if (isSystemApp) LibraryCategory.UTILITIES else LibraryCategory.OTHER
}

internal data class LibraryApp(
    val id: String,
    val label: String,
    val category: LibraryCategory,
    /** First install time in ms; 0 when unknown. */
    val installedAt: Long = 0L,
    val systemApp: Boolean = false,
)

internal data class LibraryFolder(val category: LibraryCategory, val appIds: List<String>) {
    /** iOS: smart folders show four large icons; categories show three plus a 2×2 cluster when larger. */
    val largeIds: List<String> get() = if (category.smart || appIds.size <= 4) appIds.take(4) else appIds.take(3)
    val clusterIds: List<String> get() = if (category.smart || appIds.size <= 4) emptyList() else appIds.drop(3).take(4)
}

internal const val LIBRARY_RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Builds the App Library: 建议 (recently used), 最近添加 (newest user installs within 30 days),
 * then each non-empty category. Categories containing recently used apps come first (iOS
 * orders folders by use); the rest keep the enum order. Inside a category, recently used apps
 * lead and the remainder is alphabetical by [sortKey].
 */
internal fun buildLibraryFolders(
    apps: List<LibraryApp>,
    recentIds: List<String>,
    now: Long,
    sortKey: (LibraryApp) -> String = { it.label.lowercase() },
): List<LibraryFolder> {
    if (apps.isEmpty()) return emptyList()
    val byId = apps.associateBy(LibraryApp::id)
    val rank = recentIds.withIndex().filter { it.value in byId }.associate { it.value to it.index }
    fun recency(app: LibraryApp) = rank[app.id] ?: Int.MAX_VALUE
    val folders = mutableListOf<LibraryFolder>()
    val suggestions = recentIds.distinct().filter { it in byId }.take(8)
    if (suggestions.isNotEmpty()) folders += LibraryFolder(LibraryCategory.SUGGESTIONS, suggestions)
    val recentInstalls = apps.filter { !it.systemApp && it.installedAt > 0 && now - it.installedAt in 0..LIBRARY_RECENT_WINDOW_MS }
        .sortedByDescending(LibraryApp::installedAt).map(LibraryApp::id).take(8)
    if (recentInstalls.isNotEmpty()) folders += LibraryFolder(LibraryCategory.RECENTLY_ADDED, recentInstalls)
    val groups = apps.groupBy(LibraryApp::category).filterKeys { !it.smart }
    groups.entries.sortedWith(compareBy({ entry -> entry.value.minOf(::recency) }, { it.key.ordinal }))
        .forEach { (category, members) ->
            folders += LibraryFolder(category, members.sortedWith(compareBy(::recency, sortKey)).map(LibraryApp::id))
        }
    return folders
}
