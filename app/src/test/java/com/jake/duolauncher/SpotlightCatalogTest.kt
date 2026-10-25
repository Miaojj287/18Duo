package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class SpotlightCatalogTest {
    private fun key(label: String) = searchKey(label, FakePinyin)
    private val apps = listOf("微信", "微信读书", "设置", "高德地图", "Google Maps", "Gmail").map { it to key(it) }

    @Test fun topHitNeedsAPrefixOrPinyinMatch() {
        val plan = planSpotlight("wx", apps, emptyList(), ::key)
        assertEquals("微信", plan.topHitAppId)
        assertEquals(listOf("微信读书"), plan.appIds)
        // "ail" only matches inside "Gmail": listed, never promoted to Top Hit.
        val infix = planSpotlight("ail", apps, emptyList(), ::key)
        assertNull(infix.topHitAppId)
        assertEquals(listOf("Gmail"), infix.appIds)
        assertEquals(SpotlightPlan(null, emptyList(), emptyList()), planSpotlight("  ", apps, emptyList(), ::key))
    }

    @Test fun recentAppsBreakTiesForTheTopHit() {
        val plan = planSpotlight("g", apps, listOf("Gmail"), ::key)
        assertEquals("Gmail", plan.topHitAppId)
    }

    @Test fun settingsPagesMatchChineseEnglishAndPinyin() {
        assertEquals("bluetooth", planSpotlight("蓝牙", apps, emptyList(), ::key).settings.first().id)
        assertEquals("bluetooth", planSpotlight("lanya", apps, emptyList(), ::key).settings.first().id)
        assertEquals("bluetooth", planSpotlight("blue", apps, emptyList(), ::key).settings.first().id)
        assertEquals("wifi", planSpotlight("wifi", apps, emptyList(), ::key).settings.first().id)
        assertTrue(planSpotlight("飞行", apps, emptyList(), ::key).settings.any { it.id == "airplane" })
        // One Latin letter is too vague for Settings; one Han character is a word.
        assertTrue(planSpotlight("b", apps, emptyList(), ::key).settings.isEmpty())
        assertTrue(settingsQueryAllowed("电"))
        assertFalse(settingsQueryAllowed("b"))
    }

    @Test fun settingsLinksOnlyOpenPagesNeverToggle() {
        assertEquals(SETTINGS_LINKS.size, SETTINGS_LINKS.map { it.id }.toSet().size)
        SETTINGS_LINKS.forEach { link ->
            assertTrue(link.id, link.action.startsWith("android.settings.") || link.action.startsWith("android.intent.action.") ||
                link.action.startsWith(DUO_ACTION_PREFIX))
            assertTrue(link.zh.isNotBlank() && link.en.isNotBlank())
        }
        assertTrue(SETTINGS_LINKS.first { it.id == "duo-settings" }.opensLauncherSheet)
        assertFalse(SETTINGS_LINKS.first { it.id == "wifi" }.opensLauncherSheet)
    }

    @Test fun webFallbackEncodesTheQuery() {
        assertEquals("https://www.bing.com/search?q=%E5%BE%AE%E4%BF%A1+%E6%89%AB%E4%B8%80%E6%89%AB", webSearchUrl(" 微信 扫一扫 "))
        assertEquals("https://www.bing.com/search?q=a%26b%3Dc", webSearchUrl("a&b=c"))
    }
}
