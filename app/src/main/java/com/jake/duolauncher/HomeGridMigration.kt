package com.jake.duolauncher

/**
 * Grid history of the persisted Home layout (four columns throughout):
 * - state schemas ≤ 8 and portable backups v1/v2: six rows per page (24 cells);
 * - state schema 9 and backup v3 (Core.12–Core.16): five rows per page (20 cells);
 * - state schema 10 and backup v4 (Core.17): six rows again, matching the iPhone Duo
 *   (two widget rows + four icon rows). Six-row data therefore loads unchanged.
 */
internal const val LEGACY_GRID_ROWS = 6
internal const val LEGACY_HOME_CELLS = GRID_COLUMNS * LEGACY_GRID_ROWS
internal const val FIVE_ROW_GRID_ROWS = 5
internal const val FIVE_ROW_HOME_CELLS = GRID_COLUMNS * FIVE_ROW_GRID_ROWS
internal const val FIVE_ROW_STATE_SCHEMA = 9
internal const val HOME_STATE_SCHEMA = 10
internal const val FIVE_ROW_BACKUP_VERSION = 3

/** Rows per page used by a persisted state [schema]. */
internal fun persistedGridRows(schema: Int): Int = if (schema == FIVE_ROW_STATE_SCHEMA) FIVE_ROW_GRID_ROWS else GRID_ROWS

/** Rows per page used by a portable backup [version]. */
internal fun backupGridRows(version: Int): Int = if (version == FIVE_ROW_BACKUP_VERSION) FIVE_ROW_GRID_ROWS else GRID_ROWS

internal fun widgetOverlapsShortcuts(placement: WidgetPlacement, slots: List<String?>,
    leading: List<String?>, rows: Int = GRID_ROWS): Boolean {
    val cells = rows * GRID_COLUMNS
    return (placement.row until placement.row + placement.spanY).any { row ->
        (placement.column until placement.column + placement.spanX).any { column ->
            val local = row * GRID_COLUMNS + column
            if (placement.page == -1) leading.getOrNull(local) != null
            else row < rows && slots.getOrNull(placement.page * cells + local) != null
        }
    }
}

/**
 * Five-row (20-cell) addresses → six-row (24-cell) addresses. Every app, folder, widget and Dock
 * entry keeps its page, row and column; each page simply gains an empty sixth row, so nothing
 * reflows across pages and no widget binding or folder identity changes. A legacy "retained"
 * overflow card that sat just below the five-row grid stays just below the six-row grid.
 */
internal fun migrateFiveRowHome(layout: HomeLayout): HomeLayout {
    val slots = mutableListOf<String?>()
    layout.slots.forEachIndexed { index, id ->
        if (id == null) return@forEachIndexed
        val target = (index / FIVE_ROW_HOME_CELLS) * HOME_CELLS + index % FIVE_ROW_HOME_CELLS
        while (slots.size <= target) slots.add(null)
        slots[target] = id
    }
    val placements = layout.widgetPlacements.map { placement ->
        val retainedOverflow = placement.page > 0 && placement.row == FIVE_ROW_GRID_ROWS &&
            placement.column == 0 && placement.spanX == GRID_COLUMNS && placement.spanY == 4
        if (retainedOverflow) placement.copy(row = GRID_ROWS) else placement
    }
    return layout.copy(slots = slots.dropLastWhile { it == null },
        leadingSlots = List(HOME_CELLS) { layout.leadingSlots.getOrNull(it) },
        widgetPlacements = placements)
}
