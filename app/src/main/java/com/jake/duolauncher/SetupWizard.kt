package com.jake.duolauncher

/** The three essential steps and the wallpaper, in the order the wizard walks through them. */
internal enum class SetupStep { DEFAULT_HOME, NAVIGATION_SERVICE, FULLSCREEN_GESTURES, WALLPAPER }

internal enum class SetupStepStatus { DONE, CURRENT, UPCOMING }

/**
 * Pure view of the wizard. `current` is the first incomplete step so that returning from system
 * Settings always lands on the next thing to do; `complete` triggers the success state.
 */
internal data class SetupWizardState(
    val defaultHome: Boolean,
    val navigationService: Boolean,
    val gestureMode: Boolean,
    /** A wallpaper was applied in the wizard (or a photo was already chosen in Settings). */
    val wallpaper: Boolean,
) {
    val current: SetupStep? = SetupStep.entries.firstOrNull { !done(it) }
    val complete: Boolean get() = current == null
    val completedCount: Int get() = SetupStep.entries.count { done(it) }
    fun done(step: SetupStep): Boolean = when (step) {
        SetupStep.DEFAULT_HOME -> defaultHome
        SetupStep.NAVIGATION_SERVICE -> navigationService
        SetupStep.FULLSCREEN_GESTURES -> gestureMode
        SetupStep.WALLPAPER -> wallpaper
    }
    fun status(step: SetupStep): SetupStepStatus = setupStepStatus(this, step)
}

/** Done steps collapse; the first incomplete one expands; later ones wait even if already satisfied. */
internal fun setupStepStatus(state: SetupWizardState, step: SetupStep): SetupStepStatus = when {
    state.done(step) -> SetupStepStatus.DONE
    state.current == step -> SetupStepStatus.CURRENT
    else -> SetupStepStatus.UPCOMING
}
