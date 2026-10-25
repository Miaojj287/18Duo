package com.jake.duolauncher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import java.util.WeakHashMap

/**
 * Round 22 (owner: "整个项目经常会很卡掉帧"). Compose treats accessibility as on whenever *any* service is
 * enabled, and then rebuilds, sorts and diffs the whole semantics tree of Home on every change — about a
 * quarter of Home's main thread in a phone profile. The only service usually on is 18Duo's own navigation
 * service, which cannot read windows (canRetrieveWindowContent="false"), so all of that work had no reader.
 *
 * This hands each AndroidComposeView's accessibility delegate the list of enabled services that *can*
 * retrieve window content (TalkBack, Select to Speak, Switch Access, ...). With one of those on, Compose
 * behaves exactly as before; with none, it skips the semantics work. Compose re-reads the system list
 * when accessibility or touch exploration changes; we re-apply the filter right after it.
 * The two fields are kept by name in proguard-rules.pro; if they are ever missing, nothing changes.
 */
internal object ComposeAccessibilityGate {
    private const val TAG = "DuoA11yGate"
    private val main = Handler(Looper.getMainLooper())
    private val delegateField by lazy {
        runCatching {
            Class.forName("androidx.compose.ui.platform.AndroidComposeView")
                .getDeclaredField("composeAccessibilityDelegate").apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "compose delegate field unavailable", it) }.getOrNull()
    }
    /** Scroll observers registered while the delegate still thought it was on; they keep rebuilding the tree. */
    private val scrollScopesField by lazy {
        runCatching {
            Class.forName("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat")
                .getDeclaredField("scrollObservationScopes").apply { isAccessible = true }
        }.getOrNull()
    }
    private val servicesField by lazy {
        runCatching {
            Class.forName("androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat")
                .getDeclaredField("enabledServices").apply { isAccessible = true }
        }.onFailure { Log.w(TAG, "compose services field unavailable", it) }.getOrNull()
    }
    /** Delegates we gate (weak: they go with their views). */
    private val delegates = WeakHashMap<Any, Unit>()
    private var manager: AccessibilityManager? = null
    private val reapply = Runnable { applyAll() }
    private val stateListener = AccessibilityManager.AccessibilityStateChangeListener { schedule() }
    private val touchListener = AccessibilityManager.TouchExplorationStateChangeListener { schedule() }
    private val servicesListener: Any? = if (Build.VERSION.SDK_INT >= 33)
        AccessibilityManager.AccessibilityServicesStateChangeListener { schedule() } else null

    /** Services that can read the semantics tree Compose would build. */
    internal fun readers(services: List<AccessibilityServiceInfo>): List<AccessibilityServiceInfo> =
        services.filter { it.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0 }

    /** Gates every AndroidComposeView under [root] (main thread). Cheap; call after setContent and on resume. */
    fun install(root: View) {
        val field = delegateField ?: return
        if (servicesField == null) return
        if (manager == null) {
            val am = root.context.applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            manager = am
            am.addAccessibilityStateChangeListener(stateListener)
            am.addTouchExplorationStateChangeListener(touchListener)
            if (Build.VERSION.SDK_INT >= 33)
                am.addAccessibilityServicesStateChangeListener(servicesListener as AccessibilityManager.AccessibilityServicesStateChangeListener)
        }
        collect(root, field)
        applyAll()
    }

    private fun collect(view: View, field: java.lang.reflect.Field) {
        if (field.declaringClass.isInstance(view)) runCatching { field.get(view) }.getOrNull()?.let { delegates[it] = Unit }
        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), field)
    }

    /** Runs after the listeners of the same dispatch, Compose's own (which reset the list) included. */
    private fun schedule() { main.removeCallbacks(reapply); main.post(reapply) }

    private fun applyAll() {
        val am = manager ?: return
        val field = servicesField ?: return
        val readers = readers(runCatching { am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) }
            .getOrDefault(emptyList()))
        for (delegate in delegates.keys.toList()) runCatching {
            field.set(delegate, readers)
            // Scopes from before the gate stay subscribed to every scroll (Home's pager, Today, the library) and
            // rebuild the whole semantics map each time; an emptied list makes them invalid, so they return at once.
            // Compose registers new ones only while it is on again.
            if (readers.isEmpty()) (scrollScopesField?.get(delegate) as? MutableList<*>)?.clear()
        }.onFailure { Log.w(TAG, "could not gate compose accessibility", it) }
    }
}
