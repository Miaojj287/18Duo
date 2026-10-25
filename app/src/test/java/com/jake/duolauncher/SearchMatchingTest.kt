package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

/** Table romanizer standing in for ICU Han-Latin (same output shape: toned, space separated). */
internal val FakePinyin = Romanizer { text ->
    val table = mapOf('微' to "wēi", '信' to "xìn", '读' to "dú", '书' to "shū", '西' to "xī", '安' to "ān",
        '音' to "yīn", '乐' to "yuè", '蓝' to "lán", '牙' to "yá", '支' to "zhī", '付' to "fù", '宝' to "bǎo",
        '设' to "shè", '置' to "zhì", '网' to "wǎng", '易' to "yì", '云' to "yún", '淘' to "táo",
        '高' to "gāo", '德' to "dé", '地' to "dì", '图' to "tú", '相' to "xiàng", '机' to "jī", '飞' to "fēi",
        '行' to "xíng", '模' to "mó", '式' to "shì", '电' to "diàn", '池' to "chí", '钟' to "zhōng", '时' to "shí")
    buildString {
        text.forEach { c -> table[c]?.let { append(' ').append(it).append(' ') } ?: append(c) }
    }.trim().replace(Regex("\\s+"), " ")
}

private fun key(label: String) = searchKey(label, FakePinyin)

class SearchMatchingTest {
    @Test fun pinyinInitialsFullAndMixedSpellingsFindChineseNames() {
        val weixin = key("微信")
        assertEquals(listOf("wei", "xin"), weixin.syllables)
        assertEquals(SearchScore.PINYIN_INITIALS, matchScore("wx", weixin))
        assertEquals(SearchScore.PINYIN_FULL, matchScore("weixin", weixin))
        assertEquals(SearchScore.PINYIN_PREFIX, matchScore("wxin", weixin))
        assertEquals(SearchScore.PINYIN_PREFIX, matchScore("weix", weixin))
        assertEquals(SearchScore.PINYIN_PREFIX, matchScore("w", weixin))
        assertEquals(SearchScore.PINYIN_INNER, matchScore("xin", weixin))
        assertEquals(0, matchScore("wz", weixin))
        assertEquals(SearchScore.PREFIX, matchScore("微", weixin))
        assertEquals(SearchScore.EXACT, matchScore("微信", weixin))
    }

    @Test fun mixedLatinAndHanLabelsSplitIntoUnits() {
        val music = key("QQ音乐")
        assertEquals(listOf("qq", "yin", "yue"), music.syllables)
        assertTrue(matchScore("qqyy", music) >= SearchScore.PINYIN_PREFIX)
        assertTrue(matchScore("qqyinyue", music) >= SearchScore.PINYIN_PREFIX)
        assertEquals(SearchScore.PINYIN_INNER, matchScore("yinyue", music))
    }

    @Test fun ambiguousSyllableBoundariesBacktrack() {
        // "xian" is not a prefix of "xi"; it must split as xi + an.
        assertEquals(SearchScore.PINYIN_FULL, matchScore("xian", key("西安")))
        assertTrue(prefixesOfSyllables("xan", listOf("xi", "an"), 0))
        assertFalse(prefixesOfSyllables("xn", listOf("xi", "an"), 0))
    }

    @Test fun latinWordsMatchByPrefixAndAcrossWords() {
        val maps = key("Google Maps")
        assertEquals(SearchScore.PREFIX, matchScore("goo", maps))
        assertEquals(SearchScore.WORD_PREFIX, matchScore("maps", maps))
        assertEquals(SearchScore.WORD_PREFIX - 10, matchScore("goo ma", maps))
        assertEquals(SearchScore.SUBSTRING, matchScore("ogle", maps))
        assertEquals(SearchScore.WORD_PREFIX, matchScore("tube", key("YouTube")))
    }

    @Test fun accentsAndCaseAreIgnored() {
        assertEquals(SearchScore.EXACT, matchScore("CAFE", key("Café")))
        assertEquals("wei xin", foldForSearch("wēi xìn"))
    }

    @Test fun withoutRomanizerHanStillMatchesLiterally() {
        val plain = searchKey("微信", null)
        assertEquals(SearchScore.PREFIX, matchScore("微", plain))
        assertEquals(0, matchScore("wx", plain))
    }

    @Test fun rankingPrefersScoreThenRecencyThenShorterName() {
        val labels = listOf("微信读书", "微信", "网易云音乐")
        val ranked = rankMatches("wx", labels, ::key).map { it.item }
        assertEquals(listOf("微信", "微信读书"), ranked)
        // Equal scores ("wei" prefixes both): recently used wins, otherwise the shorter name.
        assertEquals("微信", rankMatches("wei", labels, ::key).first().item)
        val recentFirst = rankMatches("wei", labels, ::key) { if (it == "微信读书") 0 else null }.map { it.item }
        assertEquals("微信读书", recentFirst.first())
        val maps = rankMatches("ma", listOf("Gmail", "Maps", "Google Maps"), ::key).map { it.item }
        assertEquals(listOf("Maps", "Google Maps", "Gmail"), maps)
    }

    @Test fun indexLettersFollowPinyinAndPutSymbolsLast() {
        assertEquals('W', key("微信").indexLetter)
        assertEquals('Z', key("支付宝").indexLetter)
        assertEquals('#', key("12306").indexLetter)
        val sections = alphabeticalSections(listOf("支付宝", "Bilibili", "12306", "Alipay", "微信"), ::key)
        assertEquals(listOf('A', 'B', 'W', 'Z', '#'), sections.map { it.first })
    }

    @Test fun recentSearchesAreUniqueNewestFirstAndBounded() {
        var history = emptyList<String>()
        history = pushRecentSearch(history, "wx")
        history = pushRecentSearch(history, "  maps ")
        history = pushRecentSearch(history, "WX")
        assertEquals(listOf("WX", "maps"), history)
        assertEquals(history, pushRecentSearch(history, "   "))
        val long = (1..20).fold(emptyList<String>()) { acc, i -> pushRecentSearch(acc, "q$i") }
        assertEquals(8, long.size)
        assertEquals("q20", long.first())
    }
}
