package com.jake.duolauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationIconThemeTest {
    @Test fun returnFlightUsesTheSameArtworkAsHomeAndInvalidatesWhenToggled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.getSystemService(LauncherApps::class.java)
            .getActivityList("com.android.settings", Process.myUserHandle()).first()
        val id = info.componentName.flattenToString()
        val themePrefs = context.getSharedPreferences("duo18_core_preferences", Context.MODE_PRIVATE)
        val historyPrefs = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
        val hadTheme = themePrefs.contains("apple_icons_enabled")
        val oldTheme = themePrefs.getBoolean("apple_icons_enabled", false)
        val hadHistory = historyPrefs.contains("history")
        val oldHistory = historyPrefs.getString("history", null)
        try {
            historyPrefs.edit().putString("history", id).commit()
            val source = RecentTaskSource(context)
            fun returnIcon() = source.load(launcherInFront = true).tasks.firstOrNull { it.id == id }?.icon

            themePrefs.edit().putBoolean("apple_icons_enabled", false).commit()
            val stock = returnIcon()
            assertNotNull("Settings must be a recent task", stock)

            themePrefs.edit().putBoolean("apple_icons_enabled", true).commit()
            val themed = returnIcon()
            val homeArtwork = AppleIconPack(context.assets).iconFor(info.componentName, context, null)
            assertNotNull("The theme must include Settings", homeArtwork)
            assertNotNull(themed)
            assertTrue("The return flight must match Home", themed!!.sameAs(homeArtwork!!))
            assertFalse("The cached system icon must be replaced", stock!!.sameAs(themed))

            themePrefs.edit().putBoolean("apple_icons_enabled", false).commit()
            assertTrue("Turning the theme off must restore the stock return icon", stock.sameAs(returnIcon()!!))
        } finally {
            themePrefs.edit().let { if (hadTheme) it.putBoolean("apple_icons_enabled", oldTheme) else it.remove("apple_icons_enabled") }.commit()
            historyPrefs.edit().let { if (hadHistory) it.putString("history", oldHistory) else it.remove("history") }.commit()
        }
    }
}
