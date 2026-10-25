package com.jake.duolauncher

import androidx.compose.runtime.staticCompositionLocalOf

/*
 * Return to origin (round 6, owner: "应该哪里过去的就能返回哪里"). iOS never resets Home behind an app:
 * whatever you opened it from — a Home page, the Dock, an open folder, Today View, the App Library (a
 * category, its search), Spotlight — is exactly where you are when you come back, by the Home gesture,
 * the Home button or Back, and the app shrinks into the very tile it grew out of. Only a Home press
 * while Home is ALREADY in front navigates: it closes what is open, and with nothing open goes to the
 * first Home page.
 *
 * Everything here is pure (JVM-tested in HomeReturnTest); LauncherScreen, MainActivity, LaunchBounds
 * and RecentApps apply it.
 */

/** Which page kind Home showed when it opened an app. */
internal enum class OriginPage { TODAY, HOME, LIBRARY }

/** The innermost place an app was opened from (what the return lands on). */
internal enum class LaunchSurface { HOME, TODAY, FOLDER, LIBRARY, LIBRARY_CATEGORY, LIBRARY_SEARCH, SPOTLIGHT }

/**
 * Where Home was when it opened [appId] (the launcher app id: component + profile, so a dual-app copy
 * is never confused with its parent). [physicalPage] is the native pager's page in the posture
 * ([spread], [leading]) it was recorded in; [launchedAt] is uptime. [armed]: Home really went away for
 * this launch (paused soon after it), so the next resume is its return.
 */
internal data class LaunchOrigin(
    val appId: String,
    val page: OriginPage,
    val physicalPage: Int,
    val spread: Boolean,
    val leading: Boolean,
    val folderId: String? = null,
    val category: String? = null,
    val librarySearch: Boolean = false,
    val spotlight: Boolean = false,
    val launchedAt: Long = 0L,
    val armed: Boolean = false,
) {
    val surface: LaunchSurface get() = when {
        spotlight -> LaunchSurface.SPOTLIGHT
        folderId != null -> LaunchSurface.FOLDER
        page == OriginPage.LIBRARY && category != null -> LaunchSurface.LIBRARY_CATEGORY
        page == OriginPage.LIBRARY && librarySearch -> LaunchSurface.LIBRARY_SEARCH
        page == OriginPage.LIBRARY -> LaunchSurface.LIBRARY
        page == OriginPage.TODAY -> LaunchSurface.TODAY
        else -> LaunchSurface.HOME
    }

    /** Flat form for the saved instance state (process death between launch and return). */
    fun toList(): List<Any> = listOf(appId, page.name, physicalPage, spread, leading, folderId.orEmpty(), category.orEmpty(),
        librarySearch, spotlight, launchedAt, armed)

    companion object {
        fun fromList(values: List<Any?>): LaunchOrigin? = runCatching {
            LaunchOrigin(
                appId = values[0] as String, page = OriginPage.valueOf(values[1] as String),
                physicalPage = (values[2] as Number).toInt(), spread = values[3] as Boolean, leading = values[4] as Boolean,
                folderId = (values[5] as String).ifEmpty { null }, category = (values[6] as String).ifEmpty { null },
                librarySearch = values[7] as Boolean, spotlight = values[8] as Boolean,
                launchedAt = (values[9] as Number).toLong(), armed = values[10] as Boolean,
            )
        }.getOrNull()
    }
}

/** The page kind of [physicalPage] (Today = page 0 when shown, the App Library = the pager's last page). */
internal fun originPageOf(physicalPage: Int, posture: PagerPosture, pageCount: Int): OriginPage = when {
    posture.leading && physicalPage <= 0 -> OriginPage.TODAY
    pageCount > 1 && physicalPage >= pageCount - 1 -> OriginPage.LIBRARY
    else -> OriginPage.HOME
}

/** The physical page of the first Home page: the sharp [P1 | P2] spread unfolded, Home 1 on the cover. */
internal fun firstHomePhysicalPage(posture: PagerPosture, todayOverlay: Boolean, pageCount: Int): Int =
    (if (todayOverlay) 1 else posture.firstHome).coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/** How long a launch may take to actually take Home away (overlay zoom, slow cold start). */
internal const val ORIGIN_ARM_WINDOW_MS = 4_000L

/** Home paused [pausedAt] for the launch recorded at [launchedAt] (both uptime): that launch took it away. */
internal fun originArms(launchedAt: Long, pausedAt: Long): Boolean = pausedAt >= launchedAt && pausedAt - launchedAt <= ORIGIN_ARM_WINDOW_MS

/**
 * The page Home must show when it comes back from the app [origin] opened, or null to leave the pager
 * where it is. Home normally never moved (null: nothing to do); it differs when the window was
 * recreated meanwhile (process death: an unfolded pager is rebuilt on [P1 | P2], never on the Today
 * overlay) or the posture changed. A fold or unfold in between is the user moving to the other panel:
 * the pager's own posture hand-off already shows the same place there (PagerPostureHandoff), so it is
 * kept rather than forced back onto a page of the other posture.
 */
internal fun originRestorePage(origin: LaunchOrigin, posture: PagerPosture, pageCount: Int, todayOverlay: Boolean,
    currentPage: Int): Int? {
    if (origin.spread != posture.spread || origin.leading != posture.leading || pageCount <= 0) return null
    val last = pageCount - 1
    val first = firstHomePhysicalPage(posture, todayOverlay, pageCount)
    val page = when (origin.page) {
        OriginPage.LIBRARY -> last
        OriginPage.TODAY -> if (posture.leading) 0 else first
        // A Home page never resolves to Today or the App Library (pages may have gone meanwhile).
        OriginPage.HOME -> origin.physicalPage.coerceIn(minOf(first, last), maxOf(first, last - 1).coerceAtMost(last))
    }
    return page.takeIf { it != currentPage }
}

/** What a Home intent does (see [homeRequestAction]). */
internal enum class HomeRequestAction {
    /** Back from an app: everything stays where it was (only drags and menus end). */
    KEEP,
    /** Home was in front with something open: close it (folder, Search, a library category or search, editing, sheets). */
    CLOSE,
    /** Home was in front with nothing open, away from the first Home page: go there. */
    FIRST_PAGE,
    /** Already on the first Home page with nothing open. */
    NONE,
}

internal fun homeRequestAction(returningFromApp: Boolean, anythingOpen: Boolean, physicalPage: Int, firstHomePage: Int): HomeRequestAction =
    when {
        returningFromApp -> HomeRequestAction.KEEP
        anythingOpen -> HomeRequestAction.CLOSE
        physicalPage != firstHomePage -> HomeRequestAction.FIRST_PAGE
        else -> HomeRequestAction.NONE
    }

// ------------------------------------------------------------------ return flight target

/**
 * Which presentation layer a Home icon belongs to. The return flight only aims at icons of the layer(s)
 * in front ([frontIconLayers]): the Home grid stays laid out (and on screen) under an open folder, the
 * Today overlay, the full-screen App Library and Spotlight, but it is not what the user is looking at.
 */
internal enum class IconLayer { HOME, DOCK, TODAY_OVERLAY, LIBRARY, LIBRARY_CATEGORY, FOLDER, SPOTLIGHT }

/** Provided around a surface whose icons register with `homeIconTarget` (default: the Home pages). */
internal val LocalHomeIconLayer = staticCompositionLocalOf { IconLayer.HOME }

internal val ALL_ICON_LAYERS: Set<IconLayer> = IconLayer.values().toSet()

/**
 * The layers a card flying home may land in. [spotlight]: Search is open or reopens on the return (its
 * results are not targets — Spotlight hides while the app opens — so the card dissolves into Home's
 * centre and Search comes back). [sheet]: a sheet covers Home (likewise). [todayOverlayIn]: unfolded
 * Today column fully in. [fullLibraryIn]: unfolded full-screen App Library in (the Dock is hidden too).
 */
internal fun frontIconLayers(
    spotlight: Boolean, sheet: Boolean, folder: Boolean, category: Boolean,
    todayOverlayIn: Boolean, fullLibraryIn: Boolean,
): Set<IconLayer> = when {
    sheet -> emptySet()
    spotlight -> setOf(IconLayer.SPOTLIGHT)
    folder -> setOf(IconLayer.FOLDER)
    category -> setOf(IconLayer.LIBRARY_CATEGORY)
    fullLibraryIn -> setOf(IconLayer.LIBRARY)
    // The Dock is blurred with Home under the unfolded Today column (iPadOS 5.webp).
    todayOverlayIn -> setOf(IconLayer.TODAY_OVERLAY)
    else -> setOf(IconLayer.HOME, IconLayer.DOCK, IconLayer.LIBRARY)
}

// ------------------------------------------------------------------ the app Home just opened

/**
 * The app Home (or the switcher) opened last, by launcher app id. [userSerial] null = the personal
 * profile; [tile] = the launching tile in screen px (null: no tile, e.g. a switcher card); [at] uptime.
 */
internal data class LaunchedApp(val appId: String, val packageName: String, val userSerial: Long?, val tile: ZoomBounds?, val at: Long) {
    companion object {
        fun of(appId: String, tile: ZoomBounds?, at: Long): LaunchedApp? {
            val identity = parseProfileAppId(appId) ?: return null
            val pkg = identity.component.substringBefore('/').takeIf(String::isNotBlank) ?: return null
            return LaunchedApp(appId, pkg, identity.userSerial, tile, at)
        }
    }
}

/** Profile serial of a launcher app id (null: personal). */
internal fun appIdProfileSerial(appId: String): Long? = parseProfileAppId(appId)?.userSerial

/**
 * Which profile's copy of [foregroundPackage] is in front (a dual-app WeChat and its parent share the
 * package name, and window events name only the package).
 * - [personalResumed]: usage events of our user (null without usage access): true = our copy is the
 *   resumed one; false = it is not, so the window belongs to another profile.
 * - [lastLaunch]: what Home or the switcher started last; it names the exact copy, so without usage
 *   events it is the best evidence (before round 6 the personal copy always won: the clone's card flew
 *   into the parent's icon).
 * - [otherSerials]: other profiles that have the package installed.
 */
internal fun foregroundProfileSerial(
    foregroundPackage: String,
    personalSerial: Long,
    personalResumed: Boolean?,
    lastLaunch: LaunchedApp?,
    otherSerials: List<Long>,
): Long {
    if (personalResumed == true || otherSerials.isEmpty()) return personalSerial
    val launched = lastLaunch?.takeIf { it.packageName == foregroundPackage }
    if (launched != null) {
        val serial = launched.userSerial ?: personalSerial
        // Usage says our copy is not resumed: the launch of our copy is not what is in front.
        if (serial in otherSerials || (serial == personalSerial && personalResumed == null)) return serial
    }
    return if (personalResumed == false) otherSerials.first() else personalSerial
}
