package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/**
 * OPENING / RETURNING are played by Compose inside the launcher. GESTURE is a return whose card
 * the navigation overlay is flying into its icon: the launcher only mirrors the Home zoom from
 * [AppOpenZoomController.gestureProgress] and draws no icon of its own.
 */
internal enum class AppOpenDirection { OPENING, RETURNING, GESTURE }

/** Window-pixel bounds of the tapped icon; a plain value so the geometry stays JVM-testable. */
internal data class ZoomBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun scaled(factor: Float) = ZoomBounds(left * factor, top * factor, right * factor, bottom * factor)
    fun offset(dx: Float, dy: Float) = ZoomBounds(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float) =
            ZoomBounds(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }
}

/** One icon-to-fullscreen zoom. [icon] is null only in tests and for GESTURE returns. */
internal data class AppOpenZoom(
    val bounds: ZoomBounds,
    val icon: Bitmap?,
    val direction: AppOpenDirection,
    val startedAt: Long,
    /** Launcher app id, used to find the app's last snapshot for the return. */
    val appId: String? = null,
    /**
     * Without the navigation overlay Android would slide the app in sideways (cross-task launch
     * animations from a third-party launcher are ignored by shell transitions). So the launcher
     * draws the whole iOS zoom itself and starts the app — with no system animation — only once
     * its launch-screen card covers the screen ([AppOpenZoomController.handover]).
     */
    val handover: Boolean = false,
    /** Reduce Motion: the launch screen cross-fades in over Home (no zoom), and out again on return. */
    val dissolve: Boolean = false,
    /** Opened from a widget: its pixels, which the card is at the widget's end of the zoom (not the icon). */
    val art: Bitmap? = null,
    /**
     * Round 12: a GESTURE that began on Home itself (swipe up from Home): the page shrinks toward
     * its centre and blurs in place while the wallpaper stays full-bleed, so the overlay draws no
     * frozen copy of Home at all (nothing to hand over, nothing to flash).
     */
    val homeOrigin: Boolean = false,
)

internal data class AppOpenTransform(
    val iconLeft: Float,
    val iconTop: Float,
    val iconWidth: Float,
    val iconHeight: Float,
    val iconCornerRadius: Float,
    val iconAlpha: Float,
    val contentScale: Float,
    val contentAlpha: Float,
    val wallpaperScale: Float,
    /** 0 = icon in its cell, 1 = full screen. */
    val progress: Float = 0f,
    /** Round 12: blur of the live Home (0 … 1 of [SwitcherMotion.HOME_BLUR_DP]) during a Home swipe. */
    val homeBlur: Float = 0f,
)

/**
 * Pure geometry of the iOS "zoom from icon" hand-off. At progress 0 the icon sits in its grid cell;
 * at 1 it covers the screen while Home has zoomed toward the viewer and faded, so the real window
 * that Android reveals underneath reads as the continuation of the same zoom.
 */
internal object AppOpenMotion {
    const val OPEN_MILLIS = 350
    const val RETURN_MILLIS = 300
    /** If the launched activity never covered us, give up and settle Home again. */
    const val ABANDON_MILLIS = 1500L
    const val CONTENT_ZOOM = 1.12f
    const val WALLPAPER_ZOOM = 1.08f
    const val ICON_CORNER_FRACTION = .224f
    const val SCREEN_CORNER_RADIUS = 40f

    fun transform(progress: Float, bounds: ZoomBounds, screenWidth: Float, screenHeight: Float): AppOpenTransform {
        val p = progress.coerceIn(0f, 1f)
        val iconW = max(bounds.width, 1f)
        val iconH = max(bounds.height, 1f)
        val fullScale = max(screenWidth / iconW, screenHeight / iconH)
        val scale = 1f + (fullScale - 1f) * p
        val width = iconW * scale
        val height = iconH * scale
        val centerX = bounds.centerX + (screenWidth / 2f - bounds.centerX) * p
        val centerY = bounds.centerY + (screenHeight / 2f - bounds.centerY) * p
        // The icon stays opaque while it is still icon-sized, then dissolves into the real window.
        val iconAlpha = 1f - ((p - .55f) / .35f).coerceIn(0f, 1f)
        return AppOpenTransform(
            iconLeft = centerX - width / 2f,
            iconTop = centerY - height / 2f,
            iconWidth = width,
            iconHeight = height,
            iconCornerRadius = iconW * ICON_CORNER_FRACTION * scale.coerceAtMost(SCREEN_CORNER_RADIUS / (iconW * ICON_CORNER_FRACTION)),
            iconAlpha = iconAlpha,
            contentScale = 1f + (CONTENT_ZOOM - 1f) * p,
            contentAlpha = 1f - (p / .6f).coerceIn(0f, 1f),
            wallpaperScale = 1f + (WALLPAPER_ZOOM - 1f) * p,
            progress = p,
        )
    }

    /**
     * Home under a card the overlay flies back into its icon. [zoom] comes straight from the
     * overlay's backdrop so the frozen snapshot it draws and the live launcher it fades into
     * line up exactly: one uniform scale for wallpaper and icons, fully opaque.
     */
    fun gestureTransform(zoom: Float): AppOpenTransform = AppOpenTransform(
        iconLeft = 0f, iconTop = 0f, iconWidth = 0f, iconHeight = 0f, iconCornerRadius = 0f, iconAlpha = 0f,
        contentScale = zoom, contentAlpha = 1f, wallpaperScale = zoom, progress = 0f,
    )

    /**
     * Round 12, a swipe up on Home itself: the page (icons, widgets, Dock) shrinks toward its centre
     * under the finger and blurs in place ([blur] 0 … 1), the wallpaper stays full-bleed (iOS: the
     * icons gather toward the centre, the wallpaper only blurs). Pure, so the overlay's numbers and
     * the launcher's layer agree by construction.
     */
    fun homeGestureTransform(scale: Float, blur: Float): AppOpenTransform = AppOpenTransform(
        iconLeft = 0f, iconTop = 0f, iconWidth = 0f, iconHeight = 0f, iconCornerRadius = 0f, iconAlpha = 0f,
        contentScale = scale.coerceAtLeast(.01f), contentAlpha = 1f, wallpaperScale = 1f, progress = 0f,
        homeBlur = blur.coerceIn(0f, 1f),
    )

    /** Reduce Motion: a full-screen launch card fading in over an unmoved Home. */
    fun dissolveTransform(progress: Float, screenWidth: Float, screenHeight: Float): AppOpenTransform {
        val p = progress.coerceIn(0f, 1f)
        return AppOpenTransform(iconLeft = 0f, iconTop = 0f, iconWidth = screenWidth, iconHeight = screenHeight,
            iconCornerRadius = 0f, iconAlpha = 0f, contentScale = 1f, contentAlpha = 1f, wallpaperScale = 1f, progress = p)
    }

    /** The real icon only while the card is nearly icon-sized (no giant icon on screen). */
    fun fallbackIconMix(progress: Float): Float = 1f - smooth((progress - .08f) / .3f)

    /**
     * The launch-screen card is invisible near full screen, where Android's own window animation
     * plays; a [AppOpenZoom.handover] card stays opaque, the app's window replaces it in place.
     */
    fun fallbackCardAlpha(progress: Float, handover: Boolean = false): Float =
        if (handover) 1f else 1f - smooth((progress - .6f) / .4f)

    /** A handover zoom starts the app once its card covers this much of the way to full screen. */
    const val HANDOVER_PROGRESS = .94f

    /**
     * Centred icon of a launch-screen card. A card on its way to full screen ([fullness] → 1)
     * grows its icon to the size of Android's own splash icon (≈ 34 % of the panel's short side,
     * at most 160 dp: 144 dp on the cover), so the splash that replaces the card shows the icon
     * where it already was; smaller cards (switcher, mid-zoom) keep a modest icon.
     */
    fun launchCardIconSide(cardWidth: Float, cardHeight: Float, screenShortSide: Float, density: Float, fullness: Float): Float {
        val small = kotlin.math.min(kotlin.math.min(cardWidth, cardHeight) * .26f, 88f * density)
        val splash = kotlin.math.min(screenShortSide * .34f, 160f * density)
        val t = smooth((fullness - .7f) / .3f)
        return small + (splash - small) * t
    }

    private fun smooth(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /** Whether an OPENING zoom has waited too long for the launched window and should be abandoned. */
    fun abandoned(zoom: AppOpenZoom, now: Long): Boolean =
        zoom.direction == AppOpenDirection.OPENING && now - zoom.startedAt >= ABANDON_MILLIS
}

/**
 * Process-local hand-off between MainActivity (who launches) and LauncherScreen (who animates).
 * Thread-safe: the navigation overlay's stage thread drives GESTURE returns and may cancel an
 * opening zoom while the launcher's main thread begins, hands over and finishes others, so every
 * check-then-change below runs under one lock and the fields read without it are volatile.
 */
internal object AppOpenZoomController {
    private val lock = Any()
    private val _state = MutableStateFlow<AppOpenZoom?>(null)
    val state: StateFlow<AppOpenZoom?> get() = _state
    @Volatile private var covered = false
    /** The app start a [AppOpenZoom.handover] zoom is holding back until its card covers the screen. */
    private var pendingStart: (() -> Unit)? = null

    /**
     * App whose Home icon is hidden because its window is (or is about to be) the moving card:
     * iOS never shows an icon and its own zooming window at the same time. Snapshot state so the
     * icon's draw node invalidates by itself.
     */
    val hiddenIcon = mutableStateOf<String?>(null)

    /**
     * Home zoom of a GESTURE return, written by the overlay every frame. Snapshot state (not a
     * flow) so only the graphics layers that read it redraw — LauncherScreen never recomposes.
     */
    val gestureProgress = mutableFloatStateOf(1f)

    /** Round 12: blur of the live Home (0 … 1) under a Home-origin gesture, written by the overlay like [gestureProgress]. */
    val gestureBlur = mutableFloatStateOf(0f)

    /**
     * When the overlay last wrote or touched the gesture (System.nanoTime, JVM-safe). The launcher's
     * GESTURE watchdog ends a gesture the overlay has not touched for a while (a lost overlay must not
     * leave Home zoomed); an open switcher over the live Home keeps touching it (NavigationStage
     * heartbeat) without writing anything new.
     */
    @Volatile private var gestureTouchedNanos = 0L

    /**
     * The tile an overlay launch is zooming out of (screen px), known before [begin] sets [state]:
     * only that copy of the app hides, not its other copies (a recent-apps slot and a page icon).
     */
    @Volatile var hiddenTile: ZoomBounds? = null
        private set

    /** Caller holds [lock]. */
    private fun set(value: AppOpenZoom?) {
        _state.value = value
        hiddenTile = null
        hiddenIcon.value = value?.appId
    }

    /** Hides [appId]'s icon at [tile] before Home is frozen for an overlay launch; [begin] or [cancel] follow. */
    fun hideIcon(appId: String?, tile: ZoomBounds? = null) = synchronized(lock) {
        hiddenTile = tile
        hiddenIcon.value = appId
    }

    fun begin(bounds: ZoomBounds, icon: Bitmap?, now: Long, appId: String? = null, art: Bitmap? = null) = synchronized(lock) {
        covered = false
        set(AppOpenZoom(bounds, icon, AppOpenDirection.OPENING, now, appId, art = art))
    }

    /** Plays the launcher-drawn launch zoom and runs [start] when the card covers the screen. */
    fun beginHandover(bounds: ZoomBounds, icon: Bitmap?, now: Long, appId: String?, dissolve: Boolean = false,
        art: Bitmap? = null, start: () -> Unit) =
        synchronized(lock) {
            covered = false
            pendingStart = start
            set(AppOpenZoom(bounds, icon, AppOpenDirection.OPENING, now, appId, handover = true, dissolve = dissolve, art = art))
        }

    /** Starts the held-back app now (called by the zoom at [AppOpenMotion.HANDOVER_PROGRESS], or a timeout). */
    fun handover() {
        // Taken atomically (exactly one caller starts the app), run outside the lock.
        val start = synchronized(lock) { pendingStart.also { pendingStart = null } } ?: return
        start()
    }

    /** The launch threw before Android ever took over: nothing to zoom into. */
    fun cancel() = synchronized(lock) {
        covered = false
        pendingStart = null
        set(null)
    }

    /** Drops an OPENING zoom only — check and cancel in one step (the overlay launched another app). */
    fun cancelOpening() = synchronized(lock) {
        if (_state.value?.direction == AppOpenDirection.OPENING) { covered = false; pendingStart = null; set(null) }
    }

    fun launcherPaused() = synchronized(lock) { if (_state.value != null) covered = true }

    /** Resuming after a launch plays the reverse zoom; resuming without ever being covered drops it. */
    fun launcherResumed(now: Long) = synchronized(lock) {
        val current = _state.value ?: return@synchronized
        if (current.direction != AppOpenDirection.OPENING) return@synchronized
        set(if (covered) current.copy(direction = AppOpenDirection.RETURNING, startedAt = now) else null)
        covered = false
    }

    /** An OPENING zoom whose window never arrived turns into a return so Home settles again. */
    fun abandon(zoom: AppOpenZoom, now: Long) = synchronized(lock) {
        if (_state.value !== zoom || !AppOpenMotion.abandoned(zoom, now)) return@synchronized
        covered = false
        set(zoom.copy(direction = AppOpenDirection.RETURNING, startedAt = now))
    }

    fun finished(zoom: AppOpenZoom) = synchronized(lock) { if (_state.value === zoom) set(null) }

    /** The overlay takes over the return of [appId]: Home starts at [zoom] and follows [gestureZoom]. */
    fun beginGesture(zoom: Float, now: Long, appId: String? = null, homeOrigin: Boolean = false) = synchronized(lock) {
        covered = false
        pendingStart = null
        releasedIcon = null
        restoredIconDrawn = true
        gestureProgress.floatValue = zoom
        gestureBlur.floatValue = 0f
        gestureTouchedNanos = System.nanoTime()
        set(AppOpenZoom(ZoomBounds(0f, 0f, 0f, 0f), null, AppOpenDirection.GESTURE, now, appId, homeOrigin = homeOrigin))
    }

    /** A single float, written every frame by the stage: no invariant to guard. */
    fun gestureZoom(zoom: Float) { gestureProgress.floatValue = zoom; gestureTouchedNanos = System.nanoTime() }

    /** Round 12: scale and blur of the live Home under a Home-origin gesture, written every frame by the stage. */
    fun gestureHome(scale: Float, blur: Float) {
        gestureProgress.floatValue = scale
        gestureBlur.floatValue = blur
        gestureTouchedNanos = System.nanoTime()
    }

    /** The overlay is still holding this gesture (an open switcher at rest): keeps the watchdog off. */
    fun touchGesture() { gestureTouchedNanos = System.nanoTime() }

    /** Whether the overlay has written or touched the gesture within the last [withinMillis]. */
    fun gestureAlive(withinMillis: Long): Boolean = (System.nanoTime() - gestureTouchedNanos) / 1_000_000L < withinMillis

    /** The overlay re-aimed its card (Home relaid out under it): hide that icon instead, or none. */
    fun gestureIcon(appId: String?) = synchronized(lock) {
        val current = _state.value ?: return@synchronized
        if (current.direction != AppOpenDirection.GESTURE || current.appId == appId) return@synchronized
        set(current.copy(appId = appId))
    }

    fun endGesture() = synchronized(lock) {
        val current = _state.value
        if (current?.direction == AppOpenDirection.GESTURE) {
            // Recorded before the icon is shown again, so the draw that shows it is the one that counts.
            releasedIcon = current.appId
            restoredIconDrawn = current.appId == null
            set(null)
        }
        gestureProgress.floatValue = 1f
        gestureBlur.floatValue = 0f
    }

    /** The icon a gesture return just gave back to Home, until Home has drawn it again. */
    @Volatile var releasedIcon: String? = null
        private set
    /** Home has redrawn [releasedIcon] (written by its draw node): the overlay's card may go. */
    @Volatile var restoredIconDrawn = true
        private set

    /** Called by an icon's draw node when it draws [appIds] visibly (UI thread, every draw: lock-free when idle). */
    fun iconDrawn(appIds: List<String>) {
        if (releasedIcon == null) return
        synchronized(lock) {
            val released = releasedIcon ?: return
            if (hiddenIcon.value == null && released in appIds) { restoredIconDrawn = true; releasedIcon = null }
        }
    }
}
