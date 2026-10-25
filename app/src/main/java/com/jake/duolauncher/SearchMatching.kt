package com.jake.duolauncher

import java.text.Normalizer
import java.util.Locale

/**
 * Pure, JVM-testable matching used by Spotlight, the App Library search and its A–Z index.
 *
 * A name is indexed as a sequence of "syllables": Latin words as they are and every Han
 * character as its Hanyu Pinyin syllable (`微信` → `wei`,`xin`; `QQ音乐` → `qq`,`yin`,`yue`).
 * A query matches when it can be split into prefixes of consecutive syllables, so the
 * initials (`wx`), the full spelling (`weixin`) and any mix (`wxin`, `weix`) all find 微信 —
 * the way Chinese iPhone users search. Romanisation is injected: on the device it is ICU's
 * `Han-Latin` transliterator (Android API 29+, no extra dependency); tests pass a table.
 */
internal fun interface Romanizer {
    /** Returns [text] with Han characters replaced by space-separated pinyin (tone marks allowed). */
    fun romanize(text: String): String
}

internal data class SearchKey(
    val label: String,
    /** Lower-cased, accent-free label used for literal matches. */
    val folded: String,
    /** Literal words of the label (split on spaces, punctuation and camelCase). */
    val words: List<String>,
    /** Latin words and pinyin syllables in reading order. */
    val syllables: List<String>,
) {
    val initials: String get() = syllables.joinToString("") { it.take(1) }
    val compact: String get() = syllables.joinToString("")
    /** First letter used by the App Library's A–Z index; `#` for digits and symbols. */
    val indexLetter: Char get() = (syllables.firstOrNull()?.firstOrNull() ?: folded.firstOrNull())
        ?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
    /** Pinyin-aware sort key: Chinese names interleave with Latin ones as on iOS. */
    val sortKey: String get() = syllables.joinToString(" ").ifEmpty { folded }
}

/** Lower-case and strip diacritics (pinyin tone marks, é → e). */
internal fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase(Locale.ROOT)

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=[0-9])|(?<=[0-9])(?=[A-Za-z])")

internal fun containsHan(text: String): Boolean = text.any {
    Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN
}

internal fun searchKey(label: String, romanizer: Romanizer?): SearchKey {
    val folded = foldForSearch(label).trim()
    val words = label.split(NON_WORD).flatMap { it.split(CAMEL_BOUNDARY) }
        .map(::foldForSearch).filter(String::isNotBlank)
    val romanized = if (romanizer != null && containsHan(label)) runCatching { romanizer.romanize(label) }.getOrNull() else null
    val syllables = if (romanized != null) foldForSearch(romanized).split(NON_WORD).filter(String::isNotBlank)
        // Without a romanizer a Han string still matches literally; each character is its own unit.
        else words.flatMap { word -> if (containsHan(word)) word.map(Char::toString) else listOf(word) }
    return SearchKey(label, folded, words, syllables)
}

/** Match quality; 0 means no match. The bands are ordered like iOS: exact, prefix, word, pinyin, substring. */
internal object SearchScore {
    const val EXACT = 1000
    const val PREFIX = 900
    const val WORD_PREFIX = 800
    const val PINYIN_FULL = 770
    const val PINYIN_INITIALS = 760
    const val PINYIN_PREFIX = 720
    const val PINYIN_INNER = 600
    const val SUBSTRING = 500
}

internal fun matchScore(query: String, key: SearchKey): Int {
    val q = foldForSearch(query).trim()
    if (q.isEmpty()) return 0
    if (key.folded == q) return SearchScore.EXACT
    if (key.folded.startsWith(q)) return SearchScore.PREFIX
    if (key.words.any { it.startsWith(q) }) return SearchScore.WORD_PREFIX
    val queryWords = q.split(NON_WORD).filter(String::isNotBlank)
    if (queryWords.size > 1 && wordsInOrder(queryWords, key.words)) return SearchScore.WORD_PREFIX - 10
    val compactQuery = queryWords.joinToString("")
    if (compactQuery.isNotEmpty() && key.syllables.isNotEmpty()) {
        if (compactQuery == key.compact) return SearchScore.PINYIN_FULL
        if (key.syllables.size > 1 && compactQuery == key.initials) return SearchScore.PINYIN_INITIALS
        if (prefixesOfSyllables(compactQuery, key.syllables, 0)) return SearchScore.PINYIN_PREFIX
        if ((1 until key.syllables.size).any { prefixesOfSyllables(compactQuery, key.syllables, it) })
            return SearchScore.PINYIN_INNER
    }
    if (key.folded.contains(q)) return SearchScore.SUBSTRING
    return 0
}

/** Every query word must prefix a distinct later label word ("goo ma" → "Google Maps"). */
private fun wordsInOrder(query: List<String>, words: List<String>): Boolean {
    var next = 0
    for (part in query) {
        while (next < words.size && !words[next].startsWith(part)) next++
        if (next == words.size) return false
        next++
    }
    return true
}

/**
 * True when [query] is a concatenation of non-empty prefixes of consecutive syllables
 * starting at [start]. The last piece may end inside its syllable ("weix" = wei + x).
 */
internal fun prefixesOfSyllables(query: String, syllables: List<String>, start: Int): Boolean {
    fun match(position: Int, syllable: Int): Boolean {
        if (position == query.length) return true
        if (syllable >= syllables.size) return false
        val unit = syllables[syllable]
        var length = 0
        while (length < unit.length && position + length < query.length && unit[length] == query[position + length]) length++
        // Prefer the longest piece; fall back to shorter ones so "xian" can split as xi + an.
        for (take in length downTo 1) if (match(position + take, syllable + 1)) return true
        return false
    }
    return query.isNotEmpty() && match(0, start)
}

internal data class RankedMatch<T>(val item: T, val score: Int)

/**
 * Orders matches by score, then by how recently the item was used ([recency]: 0 = most recent,
 * null = never), then shorter names first so "微信" beats "微信读书" for "wx".
 */
internal fun <T> rankMatches(
    query: String,
    items: List<T>,
    key: (T) -> SearchKey,
    recency: (T) -> Int? = { null },
): List<RankedMatch<T>> = items.mapNotNull { item ->
    matchScore(query, key(item)).takeIf { it > 0 }?.let { RankedMatch(item, it) }
}.sortedWith(compareByDescending<RankedMatch<T>> { it.score }
    .thenBy { recency(it.item) ?: Int.MAX_VALUE }
    .thenBy { key(it.item).label.length }
    .thenBy { key(it.item).sortKey })

/** Most-recent-first list of submitted searches, case-insensitively unique. */
internal fun pushRecentSearch(history: List<String>, query: String, limit: Int = 8): List<String> {
    val clean = query.trim().replace(Regex("\\s+"), " ")
    if (clean.isEmpty()) return history.take(limit)
    return (listOf(clean) + history.filterNot { it.equals(clean, ignoreCase = true) }).take(limit)
}

/** Groups items under A–Z/# headers in pinyin-aware order, `#` last like iOS. */
internal fun <T> alphabeticalSections(items: List<T>, key: (T) -> SearchKey): List<Pair<Char, List<T>>> =
    items.sortedWith(compareBy<T>({ if (key(it).indexLetter == '#') 1 else 0 }, { key(it).sortKey }, { key(it).label }))
        .groupBy { key(it).indexLetter }.toList()
