package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandUpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun migrationPreservesAllTypesAndDestinationEditsExactlyOnce() {
        val legacyName = "brand-upgrade-test-legacy"
        val currentName = "brand-upgrade-test-current"
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val current = context.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        try {
            legacy.edit().clear().putBoolean("enabled", true).putInt("count", 7)
                .putLong("time", 1234567890123L).putFloat("scale", 1.25f)
                .putString("adb_key", "synthetic-test-key").putStringSet("panels", setOf("inner", "cover"))
                .putString("edited", "old").commit()
            current.edit().clear().putString("edited", "new").commit()
            migrateBrandPreferenceStore(context, legacyName, currentName)
            assertTrue(current.getBoolean("enabled", false))
            assertEquals(7, current.getInt("count", 0))
            assertEquals(1234567890123L, current.getLong("time", 0))
            assertEquals(1.25f, current.getFloat("scale", 0f), 0f)
            assertEquals("synthetic-test-key", current.getString("adb_key", null))
            assertEquals(setOf("inner", "cover"), current.getStringSet("panels", null))
            assertEquals("new", current.getString("edited", null))
            current.edit().remove("adb_key").commit()
            legacy.edit().putInt("count", 99).commit()
            migrateBrandPreferenceStore(context, legacyName, currentName)
            assertFalse(current.contains("adb_key"))
            assertEquals(7, current.getInt("count", 0))
            assertEquals("synthetic-test-key", legacy.getString("adb_key", null))
        } finally {
            legacy.edit().clear().commit()
            current.edit().clear().commit()
        }
    }

    @Test fun labelsAndSystemIconsUse18DuoAcrossLanguages() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals("18Duo", context.packageManager.getApplicationLabel(info).toString())
        assertEquals("com.jake.duolauncher.core", context.packageName)
        for (language in listOf("en", "zh", "zh-CN")) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(language))
            val localized = context.createConfigurationContext(config)
            for (id in listOf(R.string.app_name, R.string.core_about, R.string.shade_service_label,
                R.string.setup_app_details, R.string.wireless_bubble, R.string.wallpaper_service_name)) {
                val text = localized.getString(id)
                assertTrue("$language: $text", text.contains("18Duo"))
                assertFalse(text.contains("miduo", ignoreCase = true))
            }
        }
        val icon = context.packageManager.getApplicationIcon(info)
        assertTrue(icon is AdaptiveIconDrawable)
        val output = java.io.File(context.getExternalFilesDir(null), "18Duo-icon.png")
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bitmap))
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 1000)
    }
}
