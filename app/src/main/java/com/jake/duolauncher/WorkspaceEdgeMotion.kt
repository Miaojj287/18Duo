package com.jake.duolauncher

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Draw-phase values of the Home workspace while it pages. Both are snapshot floats read only in
 * graphics-layer blocks, so dragging, settling and bouncing never recompose Home.
 */
@Stable
internal class WorkspaceScrollEffects {
    /**
     * Rubber-band displacement in content-offset pixels (iOS `(1 − 1/(x·0.55/d + 1))·d` while the
     * finger pulls, the raw spring displacement while it bounces back). Positive = past the trailing
     * edge, so the content is drawn shifted left by this amount.
     */
    var overscroll by mutableFloatStateOf(0f)

    /** Workspace opacity; dips only while a Reduce Motion page jump cross-dissolves. */
    var alpha by mutableFloatStateOf(1f)

    fun rest() { overscroll = 0f; alpha = 1f }
}
