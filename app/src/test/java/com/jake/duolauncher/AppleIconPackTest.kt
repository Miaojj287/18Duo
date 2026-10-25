package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleIconPackTest {
    private val entries = setOf("com.example.app", "com.example.app.MainActivity", "com.example.other")

    @Test fun activity_artwork_takes_priority_over_package_artwork() {
        assertEquals("com.example.app.MainActivity",
            appleIconKey("com.example.app", "com.example.app.MainActivity", entries))
    }

    @Test fun package_artwork_covers_other_activities() {
        assertEquals("com.example.app", appleIconKey("com.example.app", "com.example.app.Settings", entries))
        assertEquals("com.example.app.MainActivity", appleIconKey("com.example.app", ".MainActivity", entries))
    }

    @Test fun missing_package_does_not_match_another_package_prefix() {
        assertNull(appleIconKey("com.example", "com.example.MainActivity", entries))
    }

    @Test fun another_packages_activity_class_does_not_take_its_icon() {
        assertEquals("com.example.app", appleIconKey("com.example.app", "com.example.other", entries))
    }

    @Test fun five_apps_keep_original_icons_even_when_theme_has_activity_artwork() {
        val originalPackages = listOf(
            "com.hpbr.bosszhipin",
            "com.tencent.tmgp.sgame",
            "com.tencent.lolm",
            "com.larus.nova",
            "com.bytedance.android.doubaoime",
        )
        originalPackages.forEach { packageName ->
            val activity = "$packageName.MainActivity"
            assertNull(appleIconKey(packageName, activity, setOf(packageName, activity)))
        }
    }

    @Test fun similar_package_names_still_use_their_theme_icons() {
        assertEquals("com.tencent.tmgp.sgamece", appleIconKey(
            "com.tencent.tmgp.sgamece", "com.tencent.tmgp.sgamece.MainActivity",
            setOf("com.tencent.tmgp.sgamece")))
    }
}
