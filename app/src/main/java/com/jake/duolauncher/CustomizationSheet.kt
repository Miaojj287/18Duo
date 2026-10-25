package com.jake.duolauncher

/**
 * Settings sub-page the open sheet remembers (`sheet == "settings"` / `"settings:wallpaper"`).
 * The Core.16 "Customize" sheet that used to live here was replaced by [SimpleLauncherSettings]
 * and had no callers left; its stale "Search button opens Google" switch was bound to the
 * legacy layout flag `googleSearch` instead of [CorePreferences.searchOpensGoogle], so it went too.
 */
internal enum class CustomizationPage { OVERVIEW, WALLPAPER, HOME, GESTURES, BACKUP, HELP }
