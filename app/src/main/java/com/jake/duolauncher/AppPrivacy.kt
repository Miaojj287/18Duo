package com.jake.duolauncher

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * iOS 18+ "Require Face ID" / "Hide and Require Face ID" (round 10, owner: "给它补上一个隐藏应用程序的功能").
 *
 * - **Locked** apps open only after the device owner authenticates (biometrics or the screen-lock PIN).
 * - **Hidden** apps are locked too, leave Home, the Dock, folders and Spotlight, and are listed only in the
 *   "已隐藏" folder at the bottom of the App Library, which shows them after authenticating ([revealed])
 *   until the library is left.
 *
 * Ids are [AppEntry.id] (component + profile), so a dual-app copy is hidden on its own. Persisted in its own
 * preferences file: layout backups never carry it, and an app that is uninstalled simply drops out of view.
 */
internal object AppPrivacy {
    private const val PREFS = "duo18_app_privacy"
    private const val LOCKED = "locked"
    private const val HIDDEN = "hidden"

    var locked by mutableStateOf(emptySet<String>())
        private set
    var hidden by mutableStateOf(emptySet<String>())
        private set
    /** The Hidden folder was unlocked in this App Library visit ([relock] closes it again). */
    var revealed by mutableStateOf(false)
        private set
    private var loaded = false
    /** One launch of this id was just authenticated (or came from the unlocked Hidden folder). */
    private var grant: String? = null

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        hidden = prefs.getStringSet(HIDDEN, emptySet()).orEmpty().toSet()
        // A hidden app is always locked, even if the file was edited by hand.
        locked = prefs.getStringSet(LOCKED, emptySet()).orEmpty().toSet() + hidden
        loaded = true
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(LOCKED, locked).putStringSet(HIDDEN, hidden).apply()
    }

    fun isLocked(id: String) = id in locked
    fun isHidden(id: String) = id in hidden

    fun lock(context: Context, id: String) { locked = locked + id; save(context) }
    fun hide(context: Context, id: String) { locked = locked + id; hidden = hidden + id; save(context) }
    /** "不要求…" / "添加到主屏幕" on a hidden app: back in its category, opens without authenticating. */
    fun release(context: Context, id: String) { locked = locked - id; hidden = hidden - id; save(context) }

    fun reveal() { revealed = true }
    fun relock() { revealed = false }

    /** Lets the next [needsAuthentication] check for [id] pass once (an authenticated or revealed launch). */
    fun grantLaunch(id: String) { grant = id }

    /** True when opening [id] must ask for authentication first. Consumes a pending [grantLaunch]. */
    fun needsAuthentication(id: String): Boolean {
        if (id !in locked) return false
        if (grant == id) { grant = null; return false }
        return !(id in hidden && revealed)
    }

    /**
     * iOS cannot hide the apps that ship with it, but on a Xiaomi nearly every app counts as a system app
     * (preinstalled, or updated over a preinstalled copy), so that rule would leave almost nothing to hide
     * (owner, round 12). Everything but 18Duo itself can be hidden; a launcher hiding it removes nothing.
     */
    fun canHide(context: Context, app: AppEntry): Boolean = app.packageName != context.packageName

    fun canLock(context: Context, app: AppEntry): Boolean = app.packageName != context.packageName &&
        app.packageName !in UNLOCKABLE_PACKAGES

    /** iOS keeps Settings, Camera, Clock, Calculator, Contacts, Maps, Find My and Shortcuts lockable-free. */
    private val UNLOCKABLE_PACKAGES = setOf(
        "com.android.settings", "com.android.camera", "com.google.android.GoogleCamera", "com.android.deskclock",
        "com.google.android.deskclock", "com.miui.calculator", "com.google.android.calculator",
        "com.android.calculator2", "com.android.contacts", "com.google.android.contacts",
    )

    /** The phone's own way of proving the owner, named as iOS names Face ID / Touch ID / Passcode. */
    enum class Method { FINGERPRINT, FACE, PASSCODE }

    fun method(context: Context): Method {
        val biometrics = runCatching {
            context.getSystemService(BiometricManager::class.java)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
        val pm = context.packageManager
        return when {
            biometrics && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> Method.FINGERPRINT
            biometrics && pm.hasSystemFeature(PackageManager.FEATURE_FACE) -> Method.FACE
            biometrics -> Method.FINGERPRINT
            else -> Method.PASSCODE
        }
    }

    fun methodName(context: Context): String = when (method(context)) {
        Method.FINGERPRINT -> uxText("指纹", "Fingerprint")
        Method.FACE -> uxText("面部识别", "Face Unlock")
        Method.PASSCODE -> uxText("密码", "Passcode")
    }

    /** "要求指纹" — the context-menu item and the alert's first button. */
    fun requireLabel(context: Context): String = uxText("要求${methodName(context)}", "Require ${methodName(context)}")
    fun hideLabel(context: Context): String =
        uxText("隐藏并要求${methodName(context)}", "Hide and Require ${methodName(context)}")
    fun releaseLabel(context: Context): String =
        uxText("不要求${methodName(context)}", "Don’t Require ${methodName(context)}")

    /** Without a screen lock there is nothing to authenticate with (iOS needs a passcode too). */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun openSecuritySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * The system authentication sheet (biometrics, falling back to the screen-lock PIN/pattern/password).
     * [onResult] runs on the main thread: true only after a successful authentication.
     */
    fun authenticate(context: Context, title: String, subtitle: String? = null, onResult: (Boolean) -> Unit) {
        if (!deviceSecure(context)) { onResult(false); return }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
        var done = false
        fun finish(ok: Boolean) { if (!done) { done = true; onResult(ok) } }
        runCatching {
            prompt.authenticate(CancellationSignal(), context.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = finish(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = finish(false)
            })
        }.onFailure { android.util.Log.w("AppPrivacy", "authentication unavailable", it); finish(false) }
    }
}

/** What Spotlight, Home suggestions and the App Library's categories may show. */
internal fun List<AppEntry>.withoutHidden(): List<AppEntry> {
    val hidden = AppPrivacy.hidden
    return if (hidden.isEmpty()) this else filter { it.id !in hidden }
}
