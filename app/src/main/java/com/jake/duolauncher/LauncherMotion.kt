package com.jake.duolauncher

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Shared motion language. Every spring is named after its SwiftUI/UIKit counterpart and documented
 * as (response, damping fraction); [IosSpring] converts to Compose stiffness = (2π / response)².
 *
 * [reduced] is snapshot state, so composables and draw blocks that read it update when either the
 * in-app switch or the system "Remove animations" (animator duration scale 0) changes. Under
 * Reduce Motion iOS replaces zooms and slides with dissolves: geometry specs snap, while
 * [dissolveIn]/[dissolveOut] keep animating opacity (the system scale 0 still makes them instant,
 * because Compose scales every animation by the animator duration scale).
 */
internal object LauncherMotion {
    private val inAppReduced = mutableStateOf(false)
    private val systemAnimationsOff = mutableStateOf(false)

    /** In-app Reduce Motion or system animations off. Setting it changes only the in-app switch. */
    var reduced: Boolean
        get() = inAppReduced.value || systemAnimationsOff.value
        set(value) { inAppReduced.value = value }

    /** System "Remove animations": even dissolves are instant. */
    val animationsOff: Boolean get() = systemAnimationsOff.value

    private var observer: ContentObserver? = null

    /** Follows Settings.Global.ANIMATOR_DURATION_SCALE for the process lifetime (idempotent). */
    fun attachSystemSettings(context: Context) {
        val resolver = context.applicationContext.contentResolver
        fun read() = runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        systemAnimationsOff.value = read()
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemAnimationsOff.value = read() }
        }.also { o ->
            runCatching {
                resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, o)
            }
        }
    }

    // ---------------------------------------------------------------- named iOS springs

    /** SwiftUI `.smooth` (the default `withAnimation` since iOS 17): response 0.5 s, no bounce. */
    val smoothSpring = IosSpring(.5f, 1f)
    /** SwiftUI `.snappy`: response 0.5 s, bounce 0.15. */
    val snappySpring = IosSpring(.5f, .85f)
    /** SwiftUI `.bouncy`: response 0.5 s, bounce 0.3. */
    val bouncySpring = IosSpring(.5f, .7f)
    /** SwiftUI `.interactiveSpring`: response 0.15 s, damping 0.86 — content chasing a finger. */
    val interactiveSpring = IosSpring(.15f, .86f)
    /**
     * Home paging (UIScrollView paging settle, rubber-band return, programmatic page moves):
     * critically damped, response 0.40 s → 95 % in 0.30 s, done in ≈0.42 s. Compose's default
     * spring (stiffness 1500 ≈ response 0.16 s) was far stiffer than iOS.
     */
    val pageSpring = IosSpring(.40f, 1f)
    /** Reduce Motion paging: the same shape, less time in motion. */
    val reducedPageSpring = IosSpring(.28f, 1f)
    /** Opacity cross-dissolve that replaces zooms/slides under Reduce Motion (≈0.2 s). */
    val dissolveSpring = IosSpring(.26f, 1f)
    private val dissolveOutSpring = IosSpring(.2f, 1f)

    val page: IosSpring get() = if (reduced) reducedPageSpring else pageSpring

    fun <T> springSpec(spring: IosSpring, visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = spring.dampingFraction, stiffness = spring.stiffness, visibilityThreshold = visibilityThreshold)

    /** Snaps under Reduce Motion; otherwise the named spring. */
    fun <T> motion(spring: IosSpring, visibilityThreshold: T? = null): AnimationSpec<T> =
        if (reduced) snap() else springSpec(spring, visibilityThreshold)

    val smooth: AnimationSpec<Float> get() = motion(smoothSpring, .001f)
    val snappy: AnimationSpec<Float> get() = motion(snappySpring, .001f)
    val bouncy: AnimationSpec<Float> get() = motion(bouncySpring, .001f)

    /** Opacity in: animates even under Reduce Motion (it is the replacement for motion). */
    val dissolveIn: FiniteAnimationSpec<Float> get() = springSpec(dissolveSpring, .002f)
    val dissolveOut: FiniteAnimationSpec<Float> get() = springSpec(dissolveOutSpring, .002f)

    // ------------------------------------------------- Home direct-manipulation springs
    // Tuned by the edit/layout workstreams; kept numerically identical, now with their iOS names.

    /** Icons making room during a drag (response 0.28 s, bounce 0.18). */
    private val placementIos = IosSpring.fromStiffness(520f, .82f)
    /** Press sink / lift (response 0.25 s, critically damped). */
    private val liftIos = IosSpring.fromStiffness(620f, 1f)
    /** Size/position settles (response 0.29 s, critically damped). */
    private val settleIos = IosSpring.fromStiffness(480f, 1f)

    val placement: AnimationSpec<IntOffset> get() = motion(placementIos, IntOffset(1, 1))
    val lift: AnimationSpec<Float> get() = motion(liftIos, .002f)
    val settle: AnimationSpec<Float> get() = motion(settleIos, .002f)
    val size: AnimationSpec<Dp> get() = motion(settleIos, .5.dp)

    // ------------------------------------------------------------ the rest of the vocabulary
    // Round 11 (goal doc `uiux-core17-r11-motion-goal.md`): every spring the launcher plays is
    // named here, so one kind of motion has one feel everywhere. Callers keep their own Reduce
    // Motion branching (a zoom dissolves, a slide snaps) exactly as before; these are the specs.

    /**
     * Something growing out of the tile that was tapped and back into it (an open folder, an
     * App Library category): response 0.42 s with a hint of bounce, closing 0.36 s critically
     * damped. One pair for both, so the two "zoom out of a tile" gestures feel identical.
     */
    val zoomOpenSpring = IosSpring(.42f, .86f)
    val zoomCloseSpring = IosSpring(.36f, 1f)
    val zoomOpen: SpringSpec<Float> get() = springSpec(zoomOpenSpring, .001f)
    val zoomClose: SpringSpec<Float> get() = springSpec(zoomCloseSpring, .001f)

    /** Context menu springing out of the lifted item (0.31 s, bounce 0.2) and back (0.21 s). */
    val menuInSpring = IosSpring.fromStiffness(420f, .80f)
    val menuOutSpring = IosSpring.fromStiffness(900f, 1f)
    val menuIn: SpringSpec<Float> get() = springSpec(menuInSpring, .001f)
    val menuOut: SpringSpec<Float> get() = springSpec(menuOutSpring, .001f)

    /**
     * Sheets and panels (settings, widget gallery, Dock picker): presented on SwiftUI's default
     * `.smooth` (0.5 s, no bounce); dismissed in 0.36 s; a released pull that stays springs back
     * with a touch of bounce (0.31 s / 0.86) carrying the finger's velocity.
     */
    val sheetPresentSpring = smoothSpring
    val sheetDismissSpring = IosSpring.fromStiffness(300f, 1f)
    val sheetReturnSpring = IosSpring.fromStiffness(420f, .86f)
    val sheetPresent: SpringSpec<Float> get() = springSpec(sheetPresentSpring, .001f)
    val sheetDismiss: SpringSpec<Float> get() = springSpec(sheetDismissSpring, 1f)
    val sheetReturn: SpringSpec<Float> get() = springSpec(sheetReturnSpring, .5f)
    /** UINavigationController push/pop inside a sheet: critically damped, 0.36 s. */
    val navigationPushSpring = IosSpring.fromStiffness(300f, 1f)
    val navigationPush: SpringSpec<IntOffset> get() = springSpec(navigationPushSpring, IntOffset(1, 1))

    /** UIAlertController: 1.08 → 1 with a light settle (0.28 s / 0.86), out in 0.21 s. */
    val alertInSpring = IosSpring.fromStiffness(520f, .86f)
    val alertOutSpring = IosSpring.fromStiffness(900f, 1f)
    val alertIn: SpringSpec<Float> get() = springSpec(alertInSpring, .001f)
    val alertOut: SpringSpec<Float> get() = springSpec(alertOutSpring, .001f)

    /** The glass banner dropping from the top (0.32 s, bounce 0.22) and leaving (0.28 s). */
    val bannerInSpring = IosSpring.fromStiffness(380f, .78f)
    val bannerOutSpring = IosSpring.fromStiffness(500f, 1f)
    val bannerIn: SpringSpec<Float> get() = springSpec(bannerInSpring, .001f)
    val bannerOut: SpringSpec<Float> get() = springSpec(bannerOutSpring, .001f)

    /** UISwitch knob travel (0.28 s, bounce 0.26) and the stretch while it is held (0.21 s). */
    val switchKnobSpring = IosSpring.fromStiffness(520f, .74f)
    val switchPressSpring = IosSpring.fromStiffness(900f, .9f)
    val switchKnob: SpringSpec<Float> get() = springSpec(switchKnobSpring, .001f)
    val switchPress: SpringSpec<Float> get() = springSpec(switchPressSpring, .001f)
    /** Disclosure chevron turning (0.28 s / 0.86). */
    val disclosureSpring = IosSpring.fromStiffness(500f, .86f)
    val disclosure: SpringSpec<Float> get() = springSpec(disclosureSpring, .1f)
    /** Setup-wizard segment switch (0.44 s critically damped). */
    val wizardSegmentSpring = IosSpring.fromStiffness(200f, 1f)
    val wizardSegment: SpringSpec<Float> get() = springSpec(wizardSegmentSpring, .001f)

    /** iOS 26 glass control swelling under the finger (0.31 s, bounce 0.38) and relaxing. */
    val glassPressSpring = IosSpring.fromStiffness(420f, .62f)
    val glassPress: SpringSpec<Float> get() = springSpec(glassPressSpring, .001f)

    /** Jiggle strength 0 → 1 when editing starts/ends (0.32 s critically damped). */
    val editPresenceSpring = IosSpring.fromStiffness(380f, 1f)
    val editPresence: SpringSpec<Float> get() = springSpec(editPresenceSpring, .002f)

    /** Spotlight presence settling after a pull or the Search button (0.35 s, bounce 0.08). */
    val spotlightSettleSpring = IosSpring.fromStiffness(320f, .92f)
    val spotlightSettle: SpringSpec<Float> get() = springSpec(spotlightSettleSpring, .001f)
}
