package com.jake.duolauncher

/**
 * Where each Dock slot is drawn while the Dock rests, in slot pitches from its leading end.
 * iOS has no visible empty Dock positions: the apps that are there keep their order and sit
 * centred as one block (an iPhone Dock with three apps centres them). Empty slots get null —
 * nothing is drawn for them. The stored slot indices (and the user's data) are unchanged; only
 * the drawing position moves.
 */
internal fun dockRestingPositions(slots: List<String?>): List<Float?> {
    val count = slots.count { it != null }
    val start = (slots.size - count) / 2f
    var next = 0
    return slots.map { id -> if (id == null) null else start + next++ }
}
