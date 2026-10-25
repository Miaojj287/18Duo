package com.jake.duolauncher

import android.content.Context
import android.content.SharedPreferences

/** Migrate the two branded stores before any launcher component reads them. */
internal fun migrateBrandPreferences(context: Context) {
    migrateBrandPreferenceStore(context, "miduo_core_preferences", "duo18_core_preferences")
    migrateBrandPreferenceStore(context, "miduo_local_adb", "duo18_local_adb")
}

internal fun migrateBrandPreferenceStore(context: Context, legacyName: String, currentName: String) {
    val current = context.getSharedPreferences(currentName, Context.MODE_PRIVATE)
    val marker = "brand18_migration_complete"
    if (current.getBoolean(marker, false)) return
    val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
    val editor = current.edit()
    // Existing destination values win. Keep the old store for rollback and never log ADB keys.
    for ((key, value) in legacy.all) {
        if (key == marker || current.contains(key)) continue
        editor.putPreferenceValue(key, value)
    }
    check(editor.putBoolean(marker, true).commit()) { "18Duo preference migration could not be saved" }
}

private fun SharedPreferences.Editor.putPreferenceValue(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        null -> remove(key)
        else -> error("Unsupported stored preference type")
    }
}
