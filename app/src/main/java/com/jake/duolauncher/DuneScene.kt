package com.jake.duolauncher

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs

/**
 * Core.35 (round 19): the iPhone Duo live dunes, ported from apple.com/iphone-duo's own
 * WallpaperRenderer ("Foldable design" viewer). Apple renders one private three.js scene (sky,
 * stars, hills, two dune meshes) with one camera into a 2670 × 1878 target; the cover shows a
 * centred crop of the same target. Hinge.position h (opening angle / 180) drives a spring
 * ([DuneFoldSpring], module x3GhYO92U1trXLEtspQS) whose value `fold` moves the camera, the two
 * dune anchors and every material's `uFoldProgress`.
 *
 * The camera and dune anchors are not re-derived: their matrices were sampled from the site's
 * WallpaperCamera / duneAnchors at f = 0, .01 … 1 (assets/dunes/camera.bin, evidence/r19/web
 * dump.json) and are interpolated here.
 */
internal object DuneFoldMath {
    /** Apple FOLD_DEAD_ZONE: the first 11 % of the opening does not move the dunes. */
    const val FOLD_DEAD_ZONE = .11f
    /** Apple's render target; the cover and any other window show a centred crop of it. */
    const val SCENE_WIDTH = 2670
    const val SCENE_HEIGHT = 1878
    const val SCENE_ASPECT = SCENE_WIDTH.toFloat() / SCENE_HEIGHT
    /** Hinge angles the panels report at their physical endpoints (as [GlassProjectionMath]):
     * closed jitters up to 3°, flat reads 172–179°. Apple's slider spans 0–180°. */
    const val CLOSED_DEGREES = 3f
    const val FLAT_DEGREES = 172f

    fun foldTarget(hinge: Float): Float =
        if (!hinge.isFinite()) 1f else ((hinge.coerceIn(0f, 1f) - FOLD_DEAD_ZONE) / (1f - FOLD_DEAD_ZONE)).coerceIn(0f, 1f)

    /** Apple's Hinge.position for a sensor angle. */
    fun hingePosition(degrees: Float): Float =
        if (!degrees.isFinite()) Float.NaN else ((degrees - CLOSED_DEGREES) / (FLAT_DEGREES - CLOSED_DEGREES)).coerceIn(0f, 1f)

    /** Cover-crop of the scene to [aspect] (width / height): scales the projection's x or y row,
     * which keeps Apple's off-centre shift centred on the crop. Column-major, in place. */
    fun cropProjection(projection: FloatArray, aspect: Float) {
        if (!aspect.isFinite() || aspect <= 0f) return
        if (aspect < SCENE_ASPECT) {
            val s = SCENE_ASPECT / aspect
            for (c in 0..3) projection[c * 4] *= s
        } else {
            val s = aspect / SCENE_ASPECT
            for (c in 0..3) projection[c * 4 + 1] *= s
        }
    }
}

/** Apple's Spring: ω = 2π / (3/7 s), damping 2.6ω, a fixed 1/30 s step, the error clamped to ±1,
 * settled (snapped to the target) once both the velocity and the step fall under .001. */
internal class DuneFoldSpring {
    private val omega = 2.0 * PI / (3.0 / 7.0)
    private val stiffness = omega * omega
    private val damping = 2.6 * omega
    var velocity = 0.0
        private set

    /** One Apple step from [value] toward [target]; the second value is Apple's needsMoreFrames. */
    fun update(value: Float, target: Float): Pair<Float, Boolean> {
        if (!value.isFinite() || !target.isFinite() || !velocity.isFinite()) {
            velocity = 0.0
            val v = if (target.isFinite()) target else if (value.isFinite()) value else 0f
            return v.coerceIn(0f, 1f) to false
        }
        val error = (value - target).toDouble().coerceIn(-1.0, 1.0)
        velocity += (-stiffness * error - damping * velocity) * STEP_SECONDS
        val next = (value + velocity * STEP_SECONDS).coerceIn(0.0, 1.0).toFloat()
        if (abs(velocity) < .001 && abs(next - value) < .001f) {
            velocity = 0.0
            return target.coerceIn(0f, 1f) to false
        }
        return next to true
    }

    fun reset() { velocity = 0.0 }

    companion object {
        const val STEP_SECONDS = 1.0 / 30.0
        /** The site steps once per animation frame: at the usual 60 Hz, 1/30 s of spring per 1/60 s. */
        const val REAL_SECONDS_PER_STEP = 1.0 / 60.0
    }
}

/** Apple's WallpaperState in real time: one spring step per 1/60 s, whatever the display rate. */
internal class DuneFoldMotion(initial: Float) {
    private val spring = DuneFoldSpring()
    private var pending = 0.0
    var value = initial.coerceIn(0f, 1f)
        private set
    var target = value
        private set
    val moving: Boolean get() = value != target || spring.velocity != 0.0

    fun snap(to: Float) { target = to.coerceIn(0f, 1f); value = target; spring.reset(); pending = 0.0 }

    fun retarget(to: Float) { target = to.coerceIn(0f, 1f) }

    /** Advances by [seconds] of real time; true if the value changed. A long stall catches up at
     * most a quarter second (15 steps), never a burst of hundreds. */
    fun advance(seconds: Double): Boolean {
        if (!moving) { pending = 0.0; return false }
        pending = (pending + seconds.coerceAtLeast(0.0)).coerceAtMost(.25)
        var changed = false
        while (pending >= DuneFoldSpring.REAL_SECONDS_PER_STEP - 1e-6) {
            pending -= DuneFoldSpring.REAL_SECONDS_PER_STEP
            val (next, more) = spring.update(value, target)
            if (next != value) changed = true
            value = next
            if (!more) { pending = 0.0; break }
        }
        return changed
    }

    /** Called when motion starts so the first frame already steps. */
    fun primeStep() { if (pending < DuneFoldSpring.REAL_SECONDS_PER_STEP) pending = DuneFoldSpring.REAL_SECONDS_PER_STEP }
}

/** The site's camera and dune anchors, sampled at f = i / (count − 1) (assets/dunes/camera.bin). */
internal class DuneCameraTrack(private val samples: Array<FloatArray>) {
    init { require(samples.size >= 2 && samples.all { it.size == SAMPLE_FLOATS }) }

    /** Fills [out] (64 floats: camera world, projection, close anchor, far anchor; column major). */
    fun sample(fold: Float, out: FloatArray) {
        val f = (if (fold.isFinite()) fold else 1f).coerceIn(0f, 1f) * (samples.size - 1)
        val i = f.toInt().coerceAtMost(samples.size - 2)
        val t = f - i
        val a = samples[i]; val b = samples[i + 1]
        for (k in 0 until SAMPLE_FLOATS) out[k] = a[k] + (b[k] - a[k]) * t
    }

    companion object {
        const val SAMPLE_FLOATS = 64
        const val WORLD = 0
        const val PROJECTION = 16
        const val CLOSE = 32
        const val FAR = 48

        fun parse(bytes: ByteArray): DuneCameraTrack {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            require(count in 2..10_000)
            return DuneCameraTrack(Array(count) {
                buffer.float // f, implied by the index
                FloatArray(SAMPLE_FLOATS) { buffer.float }
            })
        }
    }
}

/** Column-major 4 × 4 helpers for the renderer (no android.opengl.Matrix, so they unit-test). */
internal object DuneMatrix {
    fun multiply(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int, out: FloatArray, outOffset: Int = 0) {
        for (c in 0..3) for (r in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[aOffset + k * 4 + r] * b[bOffset + c * 4 + k]
            out[outOffset + c * 4 + r] = s
        }
    }

    /** General 4 × 4 inverse (three.js Matrix4.invert); a singular matrix gives zeros. */
    fun invert(m: FloatArray, offset: Int, out: FloatArray) {
        val n11 = m[offset]; val n21 = m[offset + 1]; val n31 = m[offset + 2]; val n41 = m[offset + 3]
        val n12 = m[offset + 4]; val n22 = m[offset + 5]; val n32 = m[offset + 6]; val n42 = m[offset + 7]
        val n13 = m[offset + 8]; val n23 = m[offset + 9]; val n33 = m[offset + 10]; val n43 = m[offset + 11]
        val n14 = m[offset + 12]; val n24 = m[offset + 13]; val n34 = m[offset + 14]; val n44 = m[offset + 15]
        val t11 = n23 * n34 * n42 - n24 * n33 * n42 + n24 * n32 * n43 - n22 * n34 * n43 - n23 * n32 * n44 + n22 * n33 * n44
        val t12 = n14 * n33 * n42 - n13 * n34 * n42 - n14 * n32 * n43 + n12 * n34 * n43 + n13 * n32 * n44 - n12 * n33 * n44
        val t13 = n13 * n24 * n42 - n14 * n23 * n42 + n14 * n22 * n43 - n12 * n24 * n43 - n13 * n22 * n44 + n12 * n23 * n44
        val t14 = n14 * n23 * n32 - n13 * n24 * n32 - n14 * n22 * n33 + n12 * n24 * n33 + n13 * n22 * n34 - n12 * n23 * n34
        val det = n11 * t11 + n21 * t12 + n31 * t13 + n41 * t14
        if (det == 0f) { out.fill(0f, 0, 16); return }
        val d = 1f / det
        out[0] = t11 * d
        out[1] = (n24 * n33 * n41 - n23 * n34 * n41 - n24 * n31 * n43 + n21 * n34 * n43 + n23 * n31 * n44 - n21 * n33 * n44) * d
        out[2] = (n22 * n34 * n41 - n24 * n32 * n41 + n24 * n31 * n42 - n21 * n34 * n42 - n22 * n31 * n44 + n21 * n32 * n44) * d
        out[3] = (n23 * n32 * n41 - n22 * n33 * n41 - n23 * n31 * n42 + n21 * n33 * n42 + n22 * n31 * n43 - n21 * n32 * n43) * d
        out[4] = t12 * d
        out[5] = (n13 * n34 * n41 - n14 * n33 * n41 + n14 * n31 * n43 - n11 * n34 * n43 - n13 * n31 * n44 + n11 * n33 * n44) * d
        out[6] = (n14 * n32 * n41 - n12 * n34 * n41 - n14 * n31 * n42 + n11 * n34 * n42 + n12 * n31 * n44 - n11 * n32 * n44) * d
        out[7] = (n12 * n33 * n41 - n13 * n32 * n41 + n13 * n31 * n42 - n11 * n33 * n42 - n12 * n31 * n43 + n11 * n32 * n43) * d
        out[8] = t13 * d
        out[9] = (n14 * n23 * n41 - n13 * n24 * n41 - n14 * n21 * n43 + n11 * n24 * n43 + n13 * n21 * n44 - n11 * n23 * n44) * d
        out[10] = (n12 * n24 * n41 - n14 * n22 * n41 + n14 * n21 * n42 - n11 * n24 * n42 - n12 * n21 * n44 + n11 * n22 * n44) * d
        out[11] = (n13 * n22 * n41 - n12 * n23 * n41 - n13 * n21 * n42 + n11 * n23 * n42 + n12 * n21 * n43 - n11 * n22 * n43) * d
        out[12] = t14 * d
        out[13] = (n13 * n24 * n31 - n14 * n23 * n31 + n14 * n21 * n33 - n11 * n24 * n33 - n13 * n21 * n34 + n11 * n23 * n34) * d
        out[14] = (n14 * n22 * n31 - n12 * n24 * n31 - n14 * n21 * n32 + n11 * n24 * n32 + n12 * n21 * n34 - n11 * n22 * n34) * d
        out[15] = (n12 * n23 * n31 - n13 * n22 * n31 + n13 * n21 * n32 - n11 * n23 * n32 - n12 * n21 * n33 + n11 * n22 * n33) * d
    }

    /** three.js normalMatrix: the inverse transpose of the upper 3 × 3 of [m]. */
    fun normalMatrix(m: FloatArray, out: FloatArray) {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]
        val b01 = a22 * a11 - a12 * a21
        val b11 = -a22 * a10 + a12 * a20
        val b21 = a21 * a10 - a11 * a20
        val det = a00 * b01 + a01 * b11 + a02 * b21
        val inv = if (det != 0f) 1f / det else 0f
        // inverse (column major), then transposed into out
        val i00 = b01 * inv; val i01 = (-a22 * a01 + a02 * a21) * inv; val i02 = (a12 * a01 - a02 * a11) * inv
        val i10 = b11 * inv; val i11 = (a22 * a00 - a02 * a20) * inv; val i12 = (-a12 * a00 + a02 * a10) * inv
        val i20 = b21 * inv; val i21 = (-a21 * a00 + a01 * a20) * inv; val i22 = (a11 * a00 - a01 * a10) * inv
        out[0] = i00; out[1] = i10; out[2] = i20
        out[3] = i01; out[4] = i11; out[5] = i21
        out[6] = i02; out[7] = i12; out[8] = i22
    }
}
