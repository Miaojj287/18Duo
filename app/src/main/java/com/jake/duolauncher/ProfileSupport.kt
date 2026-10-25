package com.jake.duolauncher

import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/** Persisted app identity. Personal-profile IDs intentionally retain the legacy component string. */
data class ProfileAppIdentity(val component: String, val userSerial: Long?)

/**
 * How a profile's apps are presented, following the system's own
 * `UserProperties.getShowInLauncher()` contract.
 *
 * [CLONE] covers dual-app / parallel-app profiles (Xiaomi XSpace, and the AOSP clone profile other
 * OEMs use): the system asks launchers to show them beside their parent app, not in their own tab.
 * [HIDDEN] covers private space and any profile the system says not to show.
 */
enum class ProfileKind { PERSONAL, CLONE, WORK, HIDDEN }

data class AppProfile(
    val userSerial: Long,
    val label: String,
    val isPersonal: Boolean,
    val isWork: Boolean,
    val quiet: Boolean,
    val unlocked: Boolean,
    val available: Boolean,
    val isClone: Boolean = false,
)

private const val PROFILE_APP_PREFIX = "duo-profile:v1:"

fun profileAppId(component: String, userSerial: Long, personalSerial: Long): String {
    require(component.isNotBlank())
    require(userSerial >= 0)
    return if (userSerial == personalSerial) component else "$PROFILE_APP_PREFIX$userSerial:$component"
}

fun parseProfileAppId(id: String): ProfileAppIdentity? {
    if (!id.startsWith(PROFILE_APP_PREFIX)) return id.takeIf(String::isNotBlank)?.let { ProfileAppIdentity(it, null) }
    val separator = id.indexOf(':', PROFILE_APP_PREFIX.length)
    if (separator <= PROFILE_APP_PREFIX.length || separator == id.lastIndex) return null
    val serialText = id.substring(PROFILE_APP_PREFIX.length, separator)
    if (serialText.any { !it.isDigit() }) return null
    val serial = serialText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return ProfileAppIdentity(id.substring(separator + 1), serial)
}

/** A successful associated-profile enumeration is authoritative even when a profile is quiet or locked. */
fun removedAssociatedProfileSerials(cachedWorkSerials: Set<Long>, associatedSerials: Set<Long>): Set<Long> =
    cachedWorkSerials - associatedSerials

fun removedAppIds(
    savedIds: Collection<String>,
    availableIds: Set<String>,
    authoritativeProfiles: Set<Long>,
    temporarilyUnavailable: Set<Pair<Long, String>>,
    confirmedRemoved: Set<Pair<Long, String>>,
    personalSerial: Long,
    definitivelyRemovedProfiles: Set<Long> = emptySet(),
): Set<String> = savedIds.filterTo(mutableSetOf()) { id ->
    if (isReservedFolderId(id)) return@filterTo false
    val identity = parseProfileAppId(id) ?: return@filterTo false
    val serial = identity.userSerial ?: personalSerial
    val packageName = identity.component.substringBefore('/').takeIf(String::isNotBlank) ?: return@filterTo false
    serial in definitivelyRemovedProfiles || serial to packageName in confirmedRemoved ||
        (serial in authoritativeProfiles && id !in availableIds && serial to packageName !in temporarilyUnavailable)
}

/**
 * Classifies a profile from the user type [LauncherApps.getLauncherUserInfo] reports (API 35+).
 *
 * A null type means the platform cannot tell us (API < 35, or an OEM that refuses the query); those
 * profiles stay visible in the Work tab, which is what every build before Core.19 did with every
 * associated profile. Private space never reaches this call: the system only hands hidden profiles
 * to a default launcher holding ACCESS_HIDDEN_PROFILES, which 18Duo does not request.
 */
fun launcherProfileKind(userType: String?): ProfileKind = when {
    userType == UserManager.USER_TYPE_PROFILE_CLONE -> ProfileKind.CLONE
    userType == UserManager.USER_TYPE_PROFILE_PRIVATE -> ProfileKind.HIDDEN
    userType == UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.WORK
    // OEM dual-app profiles that do not use the AOSP clone type (Xiaomi XSpace reports the AOSP one,
    // other vendors ship their own): treat anything self-described as a clone as a clone.
    userType != null && CLONE_TYPE_HINTS.any { userType.contains(it, ignoreCase = true) } -> ProfileKind.CLONE
    else -> ProfileKind.WORK
}

private val CLONE_TYPE_HINTS = listOf("clone", "dual", "xspace", "parallel", "twin")

/** The profile kind of [user] as this launcher should present it. */
fun profileKind(
    launcherApps: LauncherApps,
    user: UserHandle,
    personal: UserHandle = Process.myUserHandle(),
): ProfileKind {
    if (user == personal) return ProfileKind.PERSONAL
    val userType = if (Build.VERSION.SDK_INT >= 35)
        runCatching { launcherApps.getLauncherUserInfo(user)?.userType }.getOrNull() else null
    return launcherProfileKind(userType)
}

/** Managed (work) profiles only: clone profiles never own widgets or a separate library tab. */
fun isSupportedWorkProfile(launcherApps: LauncherApps, user: UserHandle): Boolean =
    profileKind(launcherApps, user) == ProfileKind.WORK

/** "Clone", "Clone 2", … for the rare device that exposes more than one dual-app profile. */
fun cloneProfileLabel(cloneWord: String, index: Int): String =
    if (index <= 0) cloneWord else "$cloneWord ${index + 1}"

/**
 * Xiaomi HyperOS ("XSpace") refuses a cross-profile start from any launcher without FLAG_SYSTEM:
 * XSpaceManagerServiceImpl.checkXSpaceControl replaces the intent with its own chooser, which then
 * follows the per-app "打开方式" setting and usually opens the parent copy ("Direct to 0" in
 * logcat). These are the two markers that chooser puts on the intent it hands back: the first makes
 * the framework treat the choice as already made, the second is the user whose copy to resolve.
 * No other platform reads them, so they are inert everywhere else.
 */
const val XSPACE_EXTRA_USER_SELECTED = "android.intent.extra.xspace_userid_selected"
const val XSPACE_EXTRA_CACHED_USER_ID = "android.intent.extra.xspace_cached_uid"
const val XSPACE_PACKAGE = "com.miui.securitycore"

/**
 * The profile's user id (999 for XSpace), which [UserHandle] only exposes through its text form:
 * `getIdentifier()` is not public API. [hash] is `UserHandle.hashCode()`, which AOSP defines as the
 * same id and which backs the text up.
 */
fun parseProfileUserId(text: String, hash: Int): Int {
    val digits = text.filter(Char::isDigit)
    val parsed = digits.takeIf { it.isNotEmpty() && it.length <= 9 }?.toIntOrNull() ?: -1
    return when {
        parsed >= 0 && parsed == hash -> parsed
        parsed >= 0 && hash < 0 -> parsed
        hash >= 0 -> hash
        else -> parsed
    }
}

fun profileUserId(user: UserHandle): Int = parseProfileUserId(user.toString(), user.hashCode())

private var xspacePresent: Boolean? = null

/** Whether this phone is one of the Xiaomi builds whose dual apps live in the XSpace profile. */
fun hasXSpace(context: android.content.Context): Boolean = xspacePresent ?: runCatching {
    context.packageManager.getPackageInfo(XSPACE_PACKAGE, 0) != null
}.getOrDefault(false).also { xspacePresent = it }

/**
 * Starts a dual-app copy through HyperOS's own XSpace route (see [XSPACE_EXTRA_USER_SELECTED]).
 * Returns false when this is not an XSpace device or the start was refused; the caller then uses
 * `LauncherApps.startMainActivity`, which is what every other phone's clone profile needs.
 */
fun android.content.Context.startXSpaceClone(
    component: android.content.ComponentName,
    user: UserHandle,
    sourceBounds: android.graphics.Rect?,
    options: android.os.Bundle?,
    noAnimation: Boolean = false,
): Boolean {
    val userId = profileUserId(user).takeIf { it > 0 } ?: return false
    if (!hasXSpace(this)) return false
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
            android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            (if (noAnimation) android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION else 0))
        .putExtra(XSPACE_EXTRA_USER_SELECTED, true)
        .putExtra(XSPACE_EXTRA_CACHED_USER_ID, userId)
    intent.sourceBounds = sourceBounds
    return runCatching { startActivity(intent, options); true }
        .onFailure { android.util.Log.i("18Duo", "XSpace launch refused for user $userId: $it") }
        .getOrDefault(false)
}

/**
 * Whether "删除 App" can be offered for [app]: a personal-profile app that the system lets the user
 * uninstall (not a system app, unless it is an updated one whose update can be removed). Dual-app and
 * work copies belong to another profile, whose uninstall a launcher cannot start.
 */
internal fun canUninstall(context: android.content.Context, app: AppEntry): Boolean {
    if (!app.isPersonal || app.packageName.isBlank() || app.packageName == context.packageName) return false
    val info = runCatching { context.packageManager.getApplicationInfo(app.packageName, 0) }.getOrNull() ?: return false
    val system = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    val updated = info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
    return !system || updated
}

/** Opens the system's uninstall confirmation for [app] (needs REQUEST_DELETE_PACKAGES). */
internal fun requestUninstall(context: android.content.Context, app: AppEntry) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE,
            android.net.Uri.fromParts("package", app.packageName, null))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { android.util.Log.i("18Duo", "Uninstall of ${app.packageName} refused: $it") }
}
