package com.jake.duolauncher

/** iOS widget families on the 4-column Home grid. */
internal val IOS_WIDGET_FAMILIES = listOf(WidgetSpan(2, 2), WidgetSpan(4, 2), WidgetSpan(4, 4))

/**
 * The size pages the gallery offers for one Android provider: the iOS families (small,
 * medium, large) the provider can actually take, given its minimum/maximum and resize axes.
 * A provider that fits none of them (a 4×1 search bar, a 1×1 toggle) keeps its own size.
 * `null` constraints mean the provider cannot fit this layout at all.
 */
internal fun widgetSizeVariants(constraints: WidgetSpanConstraints?): List<WidgetSpan> {
    if (constraints == null || !constraints.minimumFitsGrid) return emptyList()
    val preferred = constraints.preferred
    val families = IOS_WIDGET_FAMILIES.filter { span ->
        span.width in constraints.minimum.width..constraints.maximum.width &&
            span.height in constraints.minimum.height..constraints.maximum.height &&
            (constraints.canResizeHorizontally || span.width == preferred.width) &&
            (constraints.canResizeVertically || span.height == preferred.height)
    }
    return families.ifEmpty { listOf(preferred) }
}

/** The family name iOS prints under a size page. */
internal fun widgetFamilyName(span: WidgetSpan): String = when (span) {
    WidgetSpan(2, 2) -> uxText("小", "Small")
    WidgetSpan(4, 2) -> uxText("中", "Medium")
    WidgetSpan(4, 4) -> uxText("大", "Large")
    else -> "${span.width} × ${span.height}"
}

/**
 * Gallery "featured" previews (18Duo's own iOS widgets), laid out two small per row, medium full
 * width, in each spec's [IosWidgetSpec.featured] order: widgets that show live data lead; Weather
 * (sample preview only until it has a data source) comes later.
 */
internal fun featuredNativeWidgetIds(): List<Int> =
    iosWidgetSpecs.filter { it.featured != null }.sortedBy { it.featured }.map { it.id }

/**
 * Widget providers that are not apps a user would recognise (SystemUI's conversation widgets,
 * the framework itself) stay out of the gallery; iOS lists apps only. Placed widgets are unaffected.
 */
internal fun isHiddenWidgetPackage(packageName: String): Boolean =
    packageName == "android" || packageName.contains("systemui", ignoreCase = true)

private val NATIVE_KIND_PACKAGES = mapOf(
    NativeWidgetKind.CALENDAR to setOf("com.android.calendar", "com.google.android.calendar", "com.xiaomi.calendar",
        "com.samsung.android.calendar", "com.huawei.calendar", "com.coloros.calendar", "com.oneplus.calendar"),
    NativeWidgetKind.CLOCK to setOf("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage",
        "com.huawei.deskclock", "com.coloros.alarmclock", "com.oneplus.deskclock"),
    NativeWidgetKind.WEATHER to setOf("com.miui.weather2", "com.moji.mjweather", "com.google.android.apps.weather",
        "com.huawei.android.totemweather", "com.oneplus.weather", "com.sec.android.daemonapp", "com.coloros.weather2"),
    NativeWidgetKind.PHOTOS to setOf("com.miui.gallery", "com.google.android.apps.photos", "com.android.gallery3d",
        "com.sec.android.gallery3d", "com.huawei.photos", "com.coloros.gallery3d"),
    NativeWidgetKind.NOTES to NOTES_PACKAGES.toSet(),
    NativeWidgetKind.REMINDERS to setOf("com.google.android.apps.tasks"),
    NativeWidgetKind.MAPS to MAP_PACKAGES.toSet(),
)

private val NATIVE_KIND_NAMES = mapOf(
    NativeWidgetKind.CALENDAR to setOf("日历", "calendar"),
    NativeWidgetKind.CLOCK to setOf("时钟", "clock"),
    NativeWidgetKind.WEATHER to setOf("天气", "weather"),
    NativeWidgetKind.PHOTOS to setOf("照片", "相册", "图库", "photos", "gallery"),
    NativeWidgetKind.BATTERY to setOf("电池", "batteries", "battery"),
    NativeWidgetKind.NOTES to setOf("备忘录", "笔记", "notes"),
    NativeWidgetKind.REMINDERS to setOf("提醒事项", "reminders", "tasks"),
    NativeWidgetKind.MAPS to setOf("地图", "高德地图", "百度地图", "腾讯地图", "maps"),
)

/** 18Duo's widget kind that belongs to an installed app (by package, else by its name in zh/en). */
internal fun nativeKindForWidgetApp(packageName: String, appLabel: String): NativeWidgetKind? {
    NATIVE_KIND_PACKAGES.entries.firstOrNull { packageName in it.value }?.let { return it.key }
    val name = appLabel.trim().lowercase()
    return NATIVE_KIND_NAMES.entries.firstOrNull { name in it.value }?.key
}

/**
 * The gallery lists each app once (iOS): 18Duo's Calendar/Clock/Weather/Photos widgets join the
 * matching installed app's row instead of a second "日历"/"时钟" row. Returns package → kind for the
 * personal-profile apps that absorb a kind; each kind joins at most one app (the first listed).
 */
internal fun nativeKindOwners(apps: List<Pair<String, String>>, kinds: Collection<NativeWidgetKind>): Map<String, NativeWidgetKind> {
    val owners = linkedMapOf<String, NativeWidgetKind>()
    for ((packageName, label) in apps) {
        val kind = nativeKindForWidgetApp(packageName, label) ?: continue
        if (kind in kinds && kind !in owners.values && packageName !in owners) owners[packageName] = kind
    }
    return owners
}

/**
 * Packs featured previews into rows of [columns] Home-grid columns (small = 2, medium = 4),
 * keeping their order: a phone-width sheet gets the iOS two-small / one-medium rhythm, the
 * wide unfolded sheet fits a medium beside a small.
 */
internal fun featuredRows(ids: List<Int>, columns: Int = 4): List<List<Int>> {
    val rows = mutableListOf<MutableList<Int>>()
    var used = 0
    for (id in ids) {
        val width = (nativeWidgetSize(id) ?: NativeWidgetSize.SMALL).spanX
        if (rows.isEmpty() || used + width > columns.coerceAtLeast(width)) { rows += mutableListOf<Int>(); used = 0 }
        rows.last() += id; used += width
    }
    return rows
}

/** How many Home-grid columns of gallery previews fit in [widthDp] at natural size. */
internal fun featuredColumns(widthDp: Float, small: Float = 150f, gap: Float = 16f): Int =
    ((widthDp + gap) / ((small + gap) / 2f)).toInt().coerceAtLeast(2)

/**
 * Preview size in dp: a small (2×2) widget is [small] dp square and families keep the Home
 * grid's proportions, so a medium is two smalls plus one gap wide (true aspect, not stretched).
 */
internal fun galleryPreviewSize(span: WidgetSpan, small: Float = 150f, gap: Float = 16f): Pair<Float, Float> {
    val pitch = (small + gap) / 2f
    return (span.width * pitch - gap) to (span.height * pitch - gap)
}
