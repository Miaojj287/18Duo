package com.jake.duolauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The App Library session: the search query, whether the search list is open (the field was focused, or
 * the grid was pulled down), and which category is expanded ([openCategory], round 6). Hoisted to
 * LauncherScreen so it outlives the library pane leaving composition and survives a launch-and-return
 * ([keepsLibrarySearchOnHome]); saved with the window like the old query string.
 */
@Stable
internal class LibrarySearchSession(query: String = "", open: Boolean = false, openCategory: String? = null,
    work: Boolean = false) {
    var query by mutableStateOf(query)
    var open by mutableStateOf(open)
    /**
     * [LibraryCategory.name] of the category expanded from its tile (iPadOS folder-style panel), or null.
     * Launching an app from it and returning keeps it open; [cancel] closes it.
     */
    var openCategory by mutableStateOf(openCategory)
    /** The Personal / Work tab of the library (only shown with a work profile); [cancel] keeps it. */
    var work by mutableStateOf(work)
    val searching: Boolean get() = open || query.isNotEmpty()
    /** True while the library shows anything beyond its resting grid (a search or an expanded category). */
    val active: Boolean get() = searching || openCategory != null
    /** Back to the resting App Library: no query, no search list, no expanded category. */
    fun cancel() { query = ""; open = false; openCategory = null }

    companion object {
        val Saver = listSaver<LibrarySearchSession, Any>(
            save = { listOf(it.query, it.open, it.openCategory.orEmpty(), it.work) },
            restore = { LibrarySearchSession(it[0] as String, it[1] as Boolean,
                (it.getOrNull(2) as? String)?.takeIf(String::isNotEmpty), it.getOrNull(3) as? Boolean ?: false) },
        )
    }
}

@Composable
internal fun rememberLibrarySearchSession(): LibrarySearchSession =
    rememberSaveable(saver = LibrarySearchSession.Saver) { LibrarySearchSession() }

/** The App Library switches to the iPadOS layout from this width (dp): the full unfolded inner screen. */
internal const val LIBRARY_WIDE_MIN_WIDTH = 600f

/**
 * iPadOS App Library geometry (dp) for a full-screen library of [width] × [height] dp, as fractions measured
 * on the owner's iPad photos (user-photos-r5, 2000×1379 px, landscape — the same 1.45 aspect as the
 * Xiaomi's 860×608 dp inner panel, so the fractions transfer directly):
 * - 8.webp, idle: a centred capsule field "App 资源库" x 36.2–63.8 %, y 8.2–13.9 %; four columns of
 *   square category platters, each 12.4 %W, spanning 19.5–80.5 %W (gaps 3.8 %W), first row at 21.2 %H,
 *   row pitch 24.6 %H, the category name under each.
 * - 4.webp, searching: the field widens to x 27.1–68.4 % with "取消" right of it (centre ≈ 71.5 %W); one
 *   centred glass list x 27.1–72.9 % from y 16.8 %H, 52 dp rows (8.6 %H), with an A–Z index on its right.
 */
internal data class WideLibraryFrame(
    val fieldLeft: Float, val fieldWidth: Float, val fieldTop: Float, val fieldHeight: Float,
    val searchFieldLeft: Float, val searchFieldWidth: Float, val cancelLeft: Float, val cancelWidth: Float,
    val gridLeft: Float, val gridTop: Float, val folderSide: Float, val columnGap: Float, val rowPitch: Float,
    val listLeft: Float, val listWidth: Float, val listTop: Float,
) {
    val columns: Int get() = WIDE_LIBRARY_COLUMNS
    val gridWidth: Float get() = folderSide * columns + columnGap * (columns - 1)
}

internal const val WIDE_LIBRARY_COLUMNS = 4

internal fun wideLibraryFrame(width: Float, height: Float, extraTop: Float = 0f): WideLibraryFrame {
    val fieldHeight = maxOf(36f, .057f * height)
    val fieldCentre = .1105f * height
    val fieldTop = fieldCentre - fieldHeight / 2f
    val side = .124f * width
    val gap = .0377f * width
    val gridWidth = side * WIDE_LIBRARY_COLUMNS + gap * (WIDE_LIBRARY_COLUMNS - 1)
    val listLeft = .271f * width
    val listRight = .729f * width
    val searchRight = .684f * width
    return WideLibraryFrame(
        fieldLeft = .362f * width, fieldWidth = .276f * width, fieldTop = fieldTop, fieldHeight = fieldHeight,
        searchFieldLeft = listLeft, searchFieldWidth = searchRight - listLeft,
        // "取消" sits in the space between the field and the list's right edge, plus a touch-size margin.
        cancelLeft = searchRight, cancelWidth = (listRight - searchRight) + 24f,
        gridLeft = (width - gridWidth) / 2f, gridTop = .212f * height + extraTop, folderSide = side,
        columnGap = gap, rowPitch = .2456f * height,
        listLeft = listLeft, listWidth = listRight - listLeft, listTop = .168f * height + extraTop,
    )
}

/**
 * The section index as iOS draws it when every letter does not fit (4.webp: "A • G • N • T • Z #"): evenly
 * spaced letters, always the first and the last, with a dot (null) wherever letters were skipped. Picking
 * still maps the touch over the FULL list of letters, so a dot jumps to the letters it stands for.
 */
internal fun compactSectionIndex(letters: List<Char>, slots: Int): List<Char?> {
    if (letters.size <= slots || letters.size <= 2) return letters
    // k letters and at most k − 1 dots must fit in the slots.
    val keep = ((slots + 1) / 2).coerceIn(2, letters.size)
    val picks = (0 until keep).map { i -> Math.round(i * (letters.size - 1) / (keep - 1).toFloat()) }.distinct()
    val out = ArrayList<Char?>()
    picks.forEachIndexed { i, index ->
        if (i > 0 && index - picks[i - 1] > 1) out += null
        out += letters[index]
    }
    return out
}
