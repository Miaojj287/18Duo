package com.jake.duolauncher

import android.view.Surface
import kotlin.math.*

/** Core.35 (round 19): the screen "Wipe" of apple.com/iphone-duo's Foldable design viewer, ported
 * term for term from its BlurPass (module ATTVpAM55GNrzjS1YEVV), its screen composite
 * (3tLaB6Cb935Y3Fsgxqd2) and the per-screen uniforms the viewer animates against Hinge.position h
 * (opening angle / 180; evidence/r19/web/wipe-log.json):
 *
 *  - inner: wipeAmount = clamp(1.19916·(1 − h)), blurBounds [.45, 1], shadeBounds [.5, 1],
 *    brightness piecewise linear through (0, .15) (1/3, .25) (1, 1), plus the "extra inner shadow on
 *    the left"; d = 1 − x with x = 0 at the free edge of the tilting leaf and .5 at the hinge;
 *  - cover: wipeAmount = tent(h)/2 (largest at 90°), blurBounds [0, .9], shadeBounds [0, 1],
 *    brightness 1; d = distance from the hinge edge.
 *
 * Content stays where it is on the glass (owner, round 7): the viewer's camera-dependent "window"
 * lookup and its .23 offset are not used, only the blur, the shade and the light.
 *
 * The pyramid-variance helpers ([levelVariance], [levelMix]) and the angle follower keep the
 * GlassProjection (MIT) lineage; see assets/licenses/GlassProjection-MIT.txt. */
internal object GlassProjectionMath {
    /** Round 20 pyramid: a half-resolution box (H), a full-resolution Gaussian (F), a half-resolution
     * Gaussian (G0) and nine more Gaussian halvings from G0, reaching the widest blur of either panel. Core.35.1 had ten levels from the half-resolution box with nothing between
     * σ 1.2 and 7 px, and modelled each level 4× too narrow (its blur came out ≈ 2× Apple's). */
    const val LEVELS = 12
    /** Variance (level texels²) one pass of [GLASS_GAUSSIAN_SHADER] adds. */
    const val PYRAMID_VARIANCE = 2.854f
    /** lhasa reports 172–179° while flat and merely tilted or touched; nothing moves above this. */
    const val INNER_FLAT_DEGREES = 172f
    /** A closed cover jitters by a degree or two around the contact endpoint. */
    const val COVER_CLOSED_DEGREES = 3f
    /** Apple Tech Talk 111464: the wallpaper zooms with the hinge; 1.08 at a perpendicular leaf.
     * Core.35: photo wallpapers only; the default dunes move with Apple's own camera ([DuneLive]). */
    const val WALLPAPER_ZOOM = .08f
    /** Below this the projection is bit-identical to the native frame and is skipped. */
    const val MIN_WIPE = .0005f

    /** Apple's variant file (QUYwYucFDmDJOZo.lsd): inner wipeFactor −1.19916, offset 1.19916. */
    const val INNER_WIPE_FACTOR = 1.1991588f
    /** BlurPass runs on a render target at .6 of the screen texture: 1602 texels across the inner
     * screen's long side, 775 across the cover's width. The blur is measured in those texels. */
    const val INNER_TEXELS = 1602f
    const val COVER_TEXELS = 775f
    /** Round 20, frame by frame against apple.com's own pipeline on the emulator's frames (blur scale
     * swept, evidence/r20/calib): the inner Wipe matches at Apple's formula, the cover's blur is √2
     * wider than the formula gives (diff 5.6–6.0 → 4.5–5.1 /255 in its blurred part at 30–60°). */
    const val COVER_BLUR_GAIN = 1.4142135f

    /** Screen pixels per BlurPass texel across the fold for a window [foldLengthPx] long. */
    fun texelPx(foldLengthPx: Float, inner: Boolean): Float =
        if (inner) foldLengthPx / INNER_TEXELS else foldLengthPx / COVER_TEXELS * COVER_BLUR_GAIN
    /** Apple's mip LOD at the widest blur: 8·sqrt(1/.75). */
    val MAX_LOD = 8f * sqrt(1f / .75f)

    private fun clampAngle(degrees: Float): Float = if (degrees.isFinite()) degrees.coerceIn(0f, 180f) else Float.NaN

    /** Tilt of the inner left leaf away from the flat plane, 0 at rest, clamped at perpendicular. */
    fun innerTilt(hingeDegrees: Float): Float {
        val angle = clampAngle(hingeDegrees)
        if (angle.isNaN()) return 0f
        return (INNER_FLAT_DEGREES - angle).coerceIn(0f, 90f)
    }

    /** Tilt of the cover away from the viewer who faced it closed: it turns away as the device opens. */
    fun coverTilt(hingeDegrees: Float): Float {
        val angle = clampAngle(hingeDegrees)
        if (angle.isNaN()) return 0f
        return (angle - COVER_CLOSED_DEGREES).coerceIn(0f, 90f)
    }

    fun wallpaperZoom(tiltDegrees: Float): Float =
        1f + WALLPAPER_ZOOM * sin(Math.toRadians(tiltDegrees.coerceIn(0f, 90f).toDouble())).toFloat()

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun wipeAmount(hinge: Float, inner: Boolean): Float {
        if (!hinge.isFinite()) return 0f
        val h = hinge.coerceIn(0f, 1f)
        return if (inner) (INNER_WIPE_FACTOR * (1f - h)).coerceIn(0f, 1f)
        else (1f - 2f * abs(h - .5f)).coerceIn(0f, 1f) / 2f
    }

    /** The screen's emissive brightness keyframes (cover: always 1). */
    fun brightness(hinge: Float, inner: Boolean): Float {
        if (!inner || !hinge.isFinite()) return 1f
        val h = hinge.coerceIn(0f, 1f)
        return if (h <= 1f / 3f) .15f + .3f * h else .25f + 1.125f * (h - 1f / 3f)
    }

    /** `smoothstep(.1, 1, brightness)`, the composite's light factor. */
    fun light(hinge: Float, inner: Boolean): Float = smoothstep(.1f, 1f, brightness(hinge, inner))

    fun blurBounds(inner: Boolean): Pair<Float, Float> = if (inner) .45f to 1f else 0f to .9f
    fun shadeBounds(inner: Boolean): Pair<Float, Float> = if (inner) .5f to 1f else 0f to 1f

    /** The composite's "edges" weight: the extra inner shadow on the left (inner screen only). */
    fun edgeAmount(hinge: Float, inner: Boolean): Float = if (inner) smoothstep(0f, .55f, wipeAmount(hinge, true)) else 0f

    /** BlurPass blurArea at distance [d] from the wipe position. */
    fun blurArea(d: Float, amount: Float, inner: Boolean): Float {
        val (b0, b1) = blurBounds(inner)
        val a = ((d - b0) / (b1 - b0) * amount * 2.5f).coerceIn(0f, 1f)
        return sqrt(a / .75f)
    }

    /** Variance (texels²) of a cubic B-spline fetch from box-filtered mip [lod]: (5·4^L − 1) / 12. */
    fun mipVariance(lod: Float): Float = (5f * 4f.pow(lod) - 1f) / 12f

    /** Gaussian sigma in screen pixels for Apple's two BlurPass runs at [lod] (fractional LODs mix
     * the floor and ceil mips; level 0's own B-spline footprint is not counted as blur). */
    fun blurSigmaPx(lod: Float, texelPx: Float): Float {
        val l = lod.coerceIn(0f, MAX_LOD)
        val lo = floor(l)
        val v = mipVariance(lo) + (mipVariance(lo + 1f) - mipVariance(lo)) * (l - lo) - mipVariance(0f)
        return sqrt(max(0f, 2f * v)) * texelPx
    }

    /** Each BlurPass multiplies by this shade, so the screen gets its square. */
    fun passShade(area: Float): Float = .25f + .75f * smoothstep(1.3f, .9f, area)

    /** The composite's wipe darkening at distance [d]. */
    fun wipeShade(d: Float, amount: Float, inner: Boolean): Float {
        val (s0, s1) = shadeBounds(inner)
        return 1f + (smoothstep(s1, s0, d) - 1f) * (amount * 2.75f).coerceIn(0f, 1f)
    }

    /** Light leaving a screen point at distance [d] (inner: from the far edge of the held half). */
    fun pointLight(d: Float, hinge: Float, inner: Boolean): Float {
        val amount = wipeAmount(hinge, inner)
        val shade = passShade(blurArea(d, amount, inner))
        val edges = 1f + (smoothstep(.05f, .5f, 1f - d) - 1f) * edgeAmount(hinge, inner)
        return shade * shade * light(hinge, inner) * wipeShade(d, amount, inner) * edges
    }

    /** Core.35.1: the viewer's window lookup. Hinge.position as the opening angle the leaf turns by. */
    fun hingeDegrees(hinge: Float): Float = 180f * (if (hinge.isFinite()) hinge.coerceIn(0f, 1f) else 1f)

    /** The viewer's camera slides from the screen centre (open) to the held half's centre (closed). */
    fun cameraX(hinge: Float): Float = 4f * (1f - (if (hinge.isFinite()) hinge.coerceIn(0f, 1f) else 1f))

    /** Cover only (transitionToCameraRest): the window lookup fades back to the plain screen as the
     * cover opens past 45 % and at its closed endpoint. The inner screen always looks through. */
    fun restMix(hinge: Float, inner: Boolean): Float {
        if (inner || !hinge.isFinite()) return if (inner) 0f else 1f
        val h = hinge.coerceIn(0f, 1f)
        val tent = (1f - 2f * abs(h - .5f)).coerceIn(0f, 1f)
        return if (tent > .01f) smoothstep(.45f, 1f, h) else 1f
    }

    /** Cover only: the viewer shifts the cover's lookup by wipeAmount · .23. */
    fun contentOffset(hinge: Float, inner: Boolean): Float = if (inner) 0f else .23f * wipeAmount(hinge, false)

    /** Weight of the native frame against Apple's ~2 % inset lookup scale: 1 at the resting endpoint,
     * 0 once the fold is under way (inner below 162°, cover above 18°). */
    fun identityMix(hinge: Float, inner: Boolean): Float {
        if (!hinge.isFinite()) return 1f
        val h = hinge.coerceIn(0f, 1f)
        return if (inner) smoothstep(.9f, 1f, h) else 1f - smoothstep(0f, .1f, h)
    }

    /** Whether the Wipe changes any pixel at [hinge]; at rest the native frame is drawn untouched. */
    fun active(hinge: Float, inner: Boolean): Boolean =
        hinge.isFinite() && wipeAmount(hinge, inner) > MIN_WIPE

    fun followAngle(current: Float,target: Float,elapsedMs: Long,inner: Boolean,responsiveness: Float): Float {
        if(!target.isFinite())return current
        if(!current.isFinite())return target
        val onset=if(inner)180f-target else target
        val visible=smooth((onset-2f)/2f)*(1f-smooth((onset-9f)/5f))
        var constant=12f+12f*(1f-smooth((onset-10f)/4f))+48f*visible
        constant+=(12f-constant)*responsiveness.coerceIn(0f,1f)
        return current+(target-current)*(1f-exp(-max(0L,elapsedMs)/constant))
    }
    private fun smooth(value: Float): Float { val t=value.coerceIn(0f,1f); return t*t*(3f-2f*t) }
    /** Impulse variance (window px²) of pyramid level [level] as the projection fetches it (cubic
     * B-spline), measured on the renderer's own chain (evidence/r20/tools/blur_sim2.py): H 4/3,
     * F 3.19, G0 12.75, the next 63.41, then each halving ×4 plus the box and spline residue. */
    fun levelVariance(level: Int): Float {
        val l = level.coerceIn(0, LEVELS - 1)
        if (l < LEVEL_TABLE.size) return LEVEL_TABLE[l]
        var v = LEVEL_TABLE.last()
        repeat(l - LEVEL_TABLE.size + 1) { v = 4f * v + LEVEL_GROWTH }
        return v
    }
    private val LEVEL_TABLE = floatArrayOf(4f / 3f, 3.187f, 12.75f, 63.41f)
    private const val LEVEL_GROWTH = 12.42f
    val levelVariances: FloatArray get() = FloatArray(LEVELS) { levelVariance(it) }
    /** [sharp]: weight of the pyramid against the native pixels (below level 0's own variance). */
    data class LevelMix(val low: Int, val high: Int, val weight: Float, val sharp: Float = 0f)
    fun levelMix(sigma: Float): LevelMix {
        val variance = if (sigma.isFinite()) max(0f, sigma).pow(2) else 0f
        val v0 = levelVariance(0)
        val pyramid = (variance / v0).coerceIn(0f, 1f)
        val v = max(variance, v0)
        var low = 0
        for (i in 1 until LEVELS) if (v >= levelVariance(i)) low = i
        val high = min(low + 1, LEVELS - 1)
        val weight = ((v - levelVariance(low)) / max(levelVariance(high) - levelVariance(low), .0001f)).coerceIn(0f, 1f)
        return LevelMix(low, high, weight, 1f - pyramid)
    }
}

/** The window edge that carries the physical hinge. */
internal enum class HingeEdge { LEFT, BOTTOM, RIGHT, TOP }

/**
 * Round 12: the cover's hinge edge under every window rotation. The hinge runs along the cover's
 * long side and sits on its LEFT in the natural portrait window (ROTATION_0, round 7's choice).
 * `Display.getRotation()` is the rotation of the drawn graphics, i.e. the opposite of the device's
 * physical turn: ROTATION_90 means the device was turned 90° counter-clockwise, which carries its
 * natural left edge to the bottom; ROTATION_180 to the right; ROTATION_270 (turned clockwise) to
 * the top. The fold effect therefore pivots on the physical hinge whatever rotation the window
 * inherited (the round 12 feedback: "开合动态效果应该永远作用于远离铰链的一侧").
 */
internal fun coverHingeEdge(rotation: Int): HingeEdge = when (rotation) {
    Surface.ROTATION_90 -> HingeEdge.BOTTOM
    Surface.ROTATION_180 -> HingeEdge.RIGHT
    Surface.ROTATION_270 -> HingeEdge.TOP
    else -> HingeEdge.LEFT
}

/** The inner panel's hinge axis per rotation on lhasa, whose native inner mode is 1672 × 2364
 * (natural portrait, hinge horizontal through the centre; dumpsys display 2026-10-20): vertical
 * at ROTATION_90/270 (the owner's everyday ROTATION_270 "book"), horizontal at 0/180. The window
 * always covers the whole panel, so this is the same statement as "the hinge bisects the long
 * side": [FoldLeafGeometry.forWindow] reads it off the window's aspect, which also holds on an
 * emulator whose natural orientation differs. */
internal fun innerHingeVertical(rotation: Int): Boolean =
    rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

/** Which part of the window is the tilting leaf and where its hinge is, in window pixels.
 * Inner landscape (hinge vertical): the left half, hinge on its right (the right half holds the
 * Home page, as in Apple's video). Inner portrait (hinge horizontal): the top half, hinge below it
 * (the bottom half is the one held). Both are in the user's frame — "left" and "top" as the user
 * sees the upright window — so turning the device 180° makes the other physical leaf the tilting
 * one; the held half stays the right/bottom one in the hand. Cover: the whole window, hinge on
 * the edge [coverHingeEdge] names for the window's rotation. Core.35: Apple's Wipe covers the whole
 * window on both panels (its inner blur starts just inside the held half); the leaf only orients
 * the distance d the Wipe is measured in. */
internal data class FoldLeafGeometry(val hingeX: Float, val hingeY: Float, val dirX: Float, val dirY: Float, val lengthPx: Float) {
    /** Screen length across the fold: both leaves on the inner panel, the whole cover. */
    fun foldLengthPx(inner: Boolean): Float = if (inner) 2f * lengthPx else lengthPx
    /** The window edge the hinge lies on (cover) or the half that tilts, as a direction name. */
    val hingeEdge: HingeEdge get() = when {
        dirX > 0f -> HingeEdge.LEFT
        dirX < 0f -> HingeEdge.RIGHT
        dirY > 0f -> HingeEdge.TOP
        else -> HingeEdge.BOTTOM
    }
    companion object {
        fun forWindow(widthPx: Float, heightPx: Float, inner: Boolean, rotation: Int = Surface.ROTATION_0): FoldLeafGeometry = when {
            !inner -> when (coverHingeEdge(rotation)) {
                HingeEdge.LEFT -> FoldLeafGeometry(0f, 0f, 1f, 0f, widthPx)
                HingeEdge.BOTTOM -> FoldLeafGeometry(0f, heightPx, 0f, -1f, heightPx)
                HingeEdge.RIGHT -> FoldLeafGeometry(widthPx, 0f, -1f, 0f, widthPx)
                HingeEdge.TOP -> FoldLeafGeometry(0f, 0f, 0f, 1f, heightPx)
            }
            widthPx >= heightPx -> FoldLeafGeometry(widthPx / 2f, 0f, -1f, 0f, widthPx / 2f)
            else -> FoldLeafGeometry(0f, heightPx / 2f, 0f, -1f, heightPx / 2f)
        }
    }
}

/** Upstream AdaptiveAngleFollow: fast deliberate runs shorten lag, alternating 1-degree
 * jitter does not. Frame-time based and clamped interpolation; never predicts a future pose. */
internal class GlassAngleFollow {
    private var frameAt=0L; private var changeAt=0L; private var runAt=0L; private var boostAt=0L
    private var target=Float.NaN; private var runDistance=0f; private var boost=0f; private var direction=0
    fun reset(now: Long,value: Float) {
        frameAt=now; changeAt=now; runAt=now; boostAt=now; target=value; runDistance=0f; boost=0f; direction=0
    }
    fun update(now: Long,current: Float,next: Float,inner: Boolean): Float {
        if(!next.isFinite())return current
        if(!target.isFinite()){reset(now,next); return next}
        val elapsed=max(0L,now-frameAt); val delta=next-target
        var strength=boost*max(0f,1f-(now-boostAt)/120f)
        if(delta!=0f) {
            val sign=if(delta>0f)1 else -1
            if(sign!=direction || now-changeAt>120L || now-runAt>120L){runAt=changeAt;runDistance=0f}
            runDistance+=abs(delta);direction=sign
            val speed=runDistance*1000f/max(16L,now-runAt)
            var measured=if(runDistance>=3f)((speed-30f)/90f).coerceIn(0f,1f) else 0f
            if(abs(delta)>=4f)measured=1f
            strength=max(strength,measured);boost=strength;boostAt=now;target=next;changeAt=now
        }
        frameAt=now
        return GlassProjectionMath.followAngle(current,next,elapsed,inner,strength)
    }
}
