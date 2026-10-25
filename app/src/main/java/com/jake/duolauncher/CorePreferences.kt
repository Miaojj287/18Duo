package com.jake.duolauncher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Local preferences for the core launcher features. */
internal class CorePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("duo18_core_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val APPLE_ICONS_KEY = "apple_icons_enabled"
        /** iPhone Duo shows Apple's icons out of the box (r21); the switch in 桌面与图标 still turns them off. */
        private const val APPLE_ICONS_DEFAULT = true

        fun appleIconsEnabled(context: Context): Boolean = context.applicationContext
            .getSharedPreferences("duo18_core_preferences", Context.MODE_PRIVATE)
            .getBoolean(APPLE_ICONS_KEY, APPLE_ICONS_DEFAULT)
    }

    init {
        // Re-arm only after the user explicitly enables the revised power-safe path.
        if (preferences.getInt("standby_power_policy", 0) < 2) {
            preferences.edit().putBoolean("standby_enabled", false)
                .putInt("standby_power_policy", 2).commit()
        }
    }

    var standbyEnabled by mutableStateOf(preferences.getBoolean("standby_enabled", false))
        private set
    var standbyScene by mutableStateOf(StandbyScene.entries.firstOrNull {
        it.name == preferences.getString("standby_scene", null)
    } ?: StandbyScene.LOCKED_AND_HOME)
        private set
    var standbyAngles by mutableStateOf(StandbyAngles.normalized(
        preferences.getInt("standby_min_angle", 10), preferences.getInt("standby_max_angle", 80)))
        private set
    var standbyNightMode by mutableStateOf(StandbyNightMode.fromKey(preferences.getString("standby_night_mode", null)))
        private set
    var foldAnimationEnabled by mutableStateOf(preferences.getBoolean("fold_animation_enabled", true))
        private set
    var appleIconsEnabled by mutableStateOf(preferences.getBoolean(APPLE_ICONS_KEY, APPLE_ICONS_DEFAULT))
        private set

    var widgetPageMode by mutableStateOf(preferences.getString("widget_page_mode", "AUTO") ?: "AUTO")
        private set
    // iPhone Duo's Dock holds exactly four apps; the recent-apps add-on is iPad-only, so it is opt-in.
    var showRecentApps by mutableStateOf(preferences.getBoolean("show_recent_apps", false))
        private set
    var reduceMotion by mutableStateOf(preferences.getBoolean("reduce_motion", false))
        private set

    /** iOS 26 Home Screen ▸ Customize: Automatic (follows Light/Dark), Light, Dark or Clear widgets. */
    var widgetStyle by mutableStateOf(WidgetStyle.fromKey(preferences.getString("widget_style", null)))
        private set

    fun widgetPageVisible(hasContent: Boolean) = todayViewVisible(widgetPageMode, hasContent)
    fun chooseWidgetStyle(style: WidgetStyle) {
        widgetStyle = style
        preferences.edit().putString("widget_style", style.key).apply()
    }
    fun setWidgetPage(visible: Boolean) {
        widgetPageMode = if (visible) "SHOW" else "HIDE"
        preferences.edit().putString("widget_page_mode", widgetPageMode).apply()
    }
    fun setRecentApps(visible: Boolean) {
        showRecentApps = visible
        preferences.edit().putBoolean("show_recent_apps", visible).apply()
    }
    fun setMotionReduction(reduced: Boolean) {
        LauncherMotion.reduced = reduced
        reduceMotion = reduced
        preferences.edit().putBoolean("reduce_motion", reduced).apply()
    }

    fun refresh() {
        appleIconsEnabled = preferences.getBoolean(APPLE_ICONS_KEY, APPLE_ICONS_DEFAULT)
        standbyEnabled = preferences.getBoolean("standby_enabled", false)
        standbyScene = StandbyScene.entries.firstOrNull {
            it.name == preferences.getString("standby_scene", null)
        } ?: StandbyScene.LOCKED_AND_HOME
        standbyAngles = StandbyAngles.normalized(preferences.getInt("standby_min_angle", 10),
            preferences.getInt("standby_max_angle", 80))
        standbyNightMode = StandbyNightMode.fromKey(preferences.getString("standby_night_mode", null))
    }

    fun setStandby(enabled: Boolean) {
        if (preferences.edit().putBoolean("standby_enabled", enabled).commit()) standbyEnabled = enabled
    }

    fun chooseStandbyScene(scene: StandbyScene) {
        if (preferences.edit().putString("standby_scene", scene.name).commit()) standbyScene = scene
    }

    fun setStandbyAngles(minimum: Int, maximum: Int) {
        val next = StandbyAngles.normalized(minimum, maximum)
        if (preferences.edit().putInt("standby_min_angle", next.minimum)
                .putInt("standby_max_angle", next.maximum).commit()) standbyAngles = next
    }

    fun chooseStandbyNightMode(mode: StandbyNightMode) {
        if (preferences.edit().putString("standby_night_mode", mode.name).commit()) standbyNightMode = mode
    }

    fun setFoldAnimation(enabled: Boolean) {
        if (preferences.edit().putBoolean("fold_animation_enabled", enabled).commit()) foldAnimationEnabled = enabled
    }

    fun setAppleIcons(enabled: Boolean): Boolean {
        if (!preferences.edit().putBoolean(APPLE_ICONS_KEY, enabled).commit()) return false
        appleIconsEnabled = enabled
        return true
    }
}
