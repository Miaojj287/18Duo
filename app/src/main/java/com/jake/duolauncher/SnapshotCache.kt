package com.jake.duolauncher

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/** Which home indicator reads best over an app: iOS swaps a dark bar for a light one over dark content. */
internal enum class HomeBarStyle { DARK, LIGHT, ADAPTIVE }

/**
 * Privacy and memory rules for app snapshots (pure, JVM-tested). Round 8 (owner: most cards showed
 * "a big logo" instead of the app): iOS keeps every app's last frame, so 18Duo now does too:
 *  - captured when the user starts a bottom gesture over an app, and when an app has settled in
 *    front (then again at most every [AutoCapturePolicy.RECAPTURE_MS] while it stays there, never
 *    over the notification shade, a system dialog, the lock screen or Home);
 *  - downscaled so the long side is ≤ [MAX_LONG_SIDE] px, at most [MAX_ENTRIES] / [MAX_BYTES] in
 *    memory, and kept on this phone only in the app's no-backup storage ([SnapshotDisk]) so a
 *    lock or a restart of the process keeps them — never backed up, exported or sent;
 *  - an all-black capture means a FLAG_SECURE window (banking, passwords, DRM video): it is
 *    discarded, the app's older snapshot is deleted and the card falls back to the app's launch
 *    colour and icon;
 *  - deleted per app when the user swipes its card away or uninstalls it, and all of them when
 *    the navigation service is turned off. A capture still in flight when the screen turns off is
 *    refused (see [RevocableSnapshots]).
 */
internal object SnapshotPolicy {
    const val MAX_LONG_SIDE = 1280
    /** Every card of a full switcher (12 apps) plus a few of the other panel's shape. */
    const val MAX_ENTRIES = 16
    const val MAX_BYTES = 72L * 1024L * 1024L
    /** A snapshot from the other panel (cover ↔ inner) has the wrong shape and is not shown. */
    const val ASPECT_TOLERANCE = .04f
    const val BLANK_LUMA = .025f
    const val LIGHT_CONTENT = .62f
    const val DARK_CONTENT = .38f

    fun scaleFor(width: Int, height: Int): Float {
        val longSide = max(width, height)
        return if (longSide <= MAX_LONG_SIDE) 1f else MAX_LONG_SIDE.toFloat() / longSide
    }

    fun luma(argb: Int): Float {
        val a = (argb ushr 24 and 0xFF) / 255f
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return (.2126f * r + .7152f * g + .0722f * b) * a
    }

    fun averageLuma(samples: IntArray): Float =
        if (samples.isEmpty()) 0f else samples.fold(0f) { sum, it -> sum + luma(it) } / samples.size

    /** Secure windows come back black (or fully transparent); real content is never uniformly so. */
    fun isBlank(samples: IntArray): Boolean = samples.isNotEmpty() && samples.all { luma(it) <= BLANK_LUMA }

    fun fitsPanel(snapshotWidth: Int, snapshotHeight: Int, screenWidth: Float, screenHeight: Float): Boolean {
        if (snapshotWidth <= 0 || snapshotHeight <= 0 || screenWidth <= 0f || screenHeight <= 0f) return false
        val snap = snapshotWidth.toFloat() / snapshotHeight
        val screen = screenWidth / screenHeight
        return abs(snap - screen) / screen <= ASPECT_TOLERANCE
    }

    /**
     * Paints the home indicator out of a background capture: every row of [rect] (clamped to the
     * image) is replaced by its mirror image above the rect, so the app's own background — usually
     * a flat bar or a gradient there — continues through the 5 dp pill. [pixels] is row-major ARGB.
     */
    fun patchRows(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val l = left.coerceIn(0, width); val r = right.coerceIn(0, width)
        val t = top.coerceIn(0, height); val b = bottom.coerceIn(0, height)
        if (l >= r || t >= b || t == 0) return
        for (y in t until b) {
            val src = (t - 1 - (y - t)).coerceAtLeast(0)
            System.arraycopy(pixels, src * width + l, pixels, y * width + l, r - l)
        }
    }

    fun barStyle(bottomLuma: Float?): HomeBarStyle = when {
        bottomLuma == null -> HomeBarStyle.ADAPTIVE
        bottomLuma >= LIGHT_CONTENT -> HomeBarStyle.DARK
        bottomLuma <= DARK_CONTENT -> HomeBarStyle.LIGHT
        else -> HomeBarStyle.ADAPTIVE
    }
}

/** Access-ordered LRU bounded by entry count and bytes. Thread-safe; values are never recycled. */
internal class SnapshotLru<K, V>(
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val map = LinkedHashMap<K, V>(16, .75f, true)
    var bytes = 0L
        private set

    val size: Int @Synchronized get() = map.size

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
        val size = sizeOf(value)
        if (size > maxBytes) return
        map[key] = value
        bytes += size
        val iterator = map.entries.iterator()
        while ((map.size > maxEntries || bytes > maxBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            bytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }

    @Synchronized fun remove(key: K) { map.remove(key)?.let { bytes -= sizeOf(it) } }

    @Synchronized fun clear() { map.clear(); bytes = 0L }

    @Synchronized fun keys(): List<K> = map.keys.toList()
}

/**
 * Per-transition draw cache (bitmap → shader) for the navigation stage. Values hold their keys
 * strongly, so it is bounded and must be [clear]ed when the stage goes idle; a WeakHashMap here
 * pinned every screenshot ever drawn until the screen turned off (Core.17 review P1-6).
 */
internal class TransientDrawCache<K, V>(private val maxEntries: Int, private val create: (K) -> V) {
    private val map = HashMap<K, V>()
    val size: Int get() = map.size

    fun get(key: K): V {
        map[key]?.let { return it }
        // Far more entries than one frame draws means old transitions leaked in: start over.
        if (map.size >= maxEntries) map.clear()
        return create(key).also { map[key] = it }
    }

    fun clear() = map.clear()
}

/** One app's last frame as the user left it, in physical pixels of the panel it was taken on. */
internal class AppSnapshot(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val bottomLuma: Float,
    /** Wall-clock time of the capture (survives a restart, unlike uptime). */
    val capturedAt: Long = System.currentTimeMillis(),
    /** [snapshotKey] of the app and profile it was captured from; set when it is filed, checked on every read. */
    val owner: String? = null,
) {
    /** Taken on a landscape panel (the inner screen unfolded); cover and inner portrait share the other shape. */
    val landscape: Boolean get() = sourceWidth >= sourceHeight

    fun ownedBy(key: String) = AppSnapshot(bitmap, sourceWidth, sourceHeight, bottomLuma, capturedAt, key)
}

/** Store slot of one app's snapshot of one panel shape: an app keeps a cover and an inner-landscape frame. */
internal fun snapshotSlot(key: String, landscape: Boolean): String = key + if (landscape) "|l" else "|p"

/**
 * Snapshots that can be revoked as a whole (pure, JVM-tested). [clear] — screen off or lock —
 * starts a new [epoch]; a value captured in an older epoch (a screenshot that was still being
 * prepared when the screen went off) is refused, so a cleared store is never repopulated with
 * pixels from before the lock. A value is returned only for the key it was filed under ([ownerOf]),
 * so one app's pixels can never come back as another app's (or profile's) snapshot.
 */
internal class RevocableSnapshots<V>(private val lru: SnapshotLru<String, V>, private val ownerOf: (V) -> String?) {
    @Volatile var epoch = 0
        private set

    /** False (and nothing stored) when the store was cleared since [capturedInEpoch] or [value] belongs to another key. */
    @Synchronized fun put(key: String, value: V, capturedInEpoch: Int, alsoUnderLock: () -> Unit = {}): Boolean {
        if (capturedInEpoch != epoch || ownerOf(value) != key) return false
        lru.put(key, value)
        alsoUnderLock()
        return true
    }

    fun get(key: String): V? = lru.get(key)?.takeIf { ownerOf(it) == key }

    fun remove(key: String) = lru.remove(key)

    fun keys(): List<String> = lru.keys()

    /** Screen off: captures still in flight are refused, what is stored stays (iOS keeps snapshots across a lock). */
    @Synchronized fun revokeInFlight() { epoch++ }

    @Synchronized fun clear(alsoUnderLock: () -> Unit = {}) {
        epoch++
        lru.clear()
        alsoUnderLock()
    }
}

/** What became of one gesture-start screenshot. Only [Secure] says anything about the app. */
internal sealed interface PreparedCapture {
    /** [topColor]: the app's colour just below the status bar when asked for ([AppSnapshotStore.prepare] topBandY). */
    class Ready(val snapshot: AppSnapshot, val topColor: Int? = null) : PreparedCapture
    /** All black: a FLAG_SECURE window. Never stored, and the app's older snapshot is dropped. */
    data object Secure : PreparedCapture
    /** Copy or memory failure: the capture is simply not used; nothing is concluded. */
    data object Failed : PreparedCapture
}

/**
 * Snapshots keyed by app *and profile* ([snapshotKey]) and panel shape ([snapshotSlot]): a
 * work-profile or Dual Apps copy of an app is a different app and never shows the personal copy's
 * pixels. Memory is an LRU cache in front of [SnapshotDisk] (attached by the navigation service).
 */
internal object AppSnapshotStore {
    private val cache = RevocableSnapshots(SnapshotLru<String, AppSnapshot>(SnapshotPolicy.MAX_ENTRIES, SnapshotPolicy.MAX_BYTES) {
        // Hardware bitmaps (decoded from disk) report no heap size; count what they hold.
        it.bitmap.width.toLong() * it.bitmap.height * 4L
    }) { it.owner?.let { owner -> snapshotSlot(owner, it.landscape) } }
    /** Last known bottom-edge brightness per package, kept separately so the bar survives eviction. */
    private val bottomLuma = HashMap<String, Float>()
    @Volatile var disk: SnapshotDisk? = null

    /** Read when a capture starts; a capture is only filed in the epoch it was taken in (see [RevocableSnapshots]). */
    val epoch: Int get() = cache.epoch

    /** The task's snapshot of this panel's shape, else of the other shape (the card then shows it whole). */
    fun get(task: RecentTask?, landscape: Boolean): AppSnapshot? {
        task ?: return null
        val key = snapshotKey(task.packageName, task.user.hashCode())
        return cache.get(snapshotSlot(key, landscape)) ?: cache.get(snapshotSlot(key, !landscape))
    }

    fun get(task: RecentTask?): AppSnapshot? = get(task, landscape = false)

    /** Files [snapshot] as [key]'s; refused when the screen went off after [capturedInEpoch]. Writes it to disk ([persist]). */
    fun put(key: String, packageName: String, snapshot: AppSnapshot, capturedInEpoch: Int, persist: Boolean = true): Boolean {
        val owned = snapshot.ownedBy(key)
        // In memory as a hardware bitmap (GPU memory, not the launcher's heap); the software copy
        // only lives until it is written to disk.
        val held = if (owned.bitmap.config == Bitmap.Config.HARDWARE) owned else runCatching {
            owned.bitmap.copy(Bitmap.Config.HARDWARE, false)?.let {
                AppSnapshot(it, owned.sourceWidth, owned.sourceHeight, owned.bottomLuma, owned.capturedAt, key)
            }
        }.getOrNull() ?: owned
        val ok = cache.put(snapshotSlot(key, held.landscape), held, capturedInEpoch) {
            synchronized(bottomLuma) { bottomLuma[packageName] = snapshot.bottomLuma }
        }
        if (ok && persist) disk?.write(key, owned)
        return ok
    }

    /** A snapshot read back from disk: filed in memory without touching the disk again. */
    fun restore(key: String, packageName: String, snapshot: AppSnapshot): Boolean =
        put(key, packageName, snapshot, cache.epoch, persist = false)

    fun has(key: String, landscape: Boolean): Boolean = cache.get(snapshotSlot(key, landscape)) != null

    fun luma(packageName: String?): Float? = packageName?.let { synchronized(bottomLuma) { bottomLuma[it] } }

    /** Forgets every shape of [key]'s snapshot, in memory and on disk (card swiped away, secure screen). */
    fun remove(key: String) {
        cache.remove(snapshotSlot(key, false)); cache.remove(snapshotSlot(key, true))
        disk?.delete(key)
    }

    fun remove(task: RecentTask) = remove(snapshotKey(task.packageName, task.user.hashCode()))

    /** The app was uninstalled: forget it in every profile. */
    fun removePackage(packageName: String) {
        cache.keys().filter { it.startsWith("$packageName#") }.forEach { cache.remove(it) }
        synchronized(bottomLuma) { bottomLuma.remove(packageName) }
        disk?.deletePackage(packageName)
    }

    /** Screen off: a capture still being prepared is refused; the snapshots themselves stay. */
    fun revokeInFlight() = cache.revokeInFlight()

    /** Memory only (the service stopped); [SnapshotDisk.deleteAll] forgets them for good. */
    fun clear() = cache.clear { synchronized(bottomLuma) { bottomLuma.clear() } }

    /**
     * Downscales a software copy of a fresh capture and measures it. Must run off the main thread:
     * reading back a hardware buffer is not free. The full-size intermediate copy is freed at once.
     */
    fun prepare(capture: Bitmap, indicator: android.graphics.Rect? = null, topBandY: Int = -1): PreparedCapture {
        val mutable = indicator != null
        val software = (if (capture.config == Bitmap.Config.HARDWARE || mutable)
            runCatching { capture.copy(Bitmap.Config.ARGB_8888, mutable) }.getOrNull()
            else capture) ?: return PreparedCapture.Failed
        try {
            val w = software.width
            val h = software.height
            if (w <= 0 || h <= 0) return PreparedCapture.Failed
            val grid = IntArray(12 * 16)
            for (gy in 0 until 16) for (gx in 0 until 12)
                grid[gy * 12 + gx] = software.getPixel(((gx + .5f) * w / 12f).toInt(), ((gy + .5f) * h / 16f).toInt())
            if (SnapshotPolicy.isBlank(grid)) return PreparedCapture.Secure
            // A background capture contains our own home indicator: paint it out (iOS snapshots have none).
            if (indicator != null) {
                val l = indicator.left.coerceIn(0, w); val r = indicator.right.coerceIn(0, w)
                val t = indicator.top.coerceIn(0, h); val b = indicator.bottom.coerceIn(0, h)
                val above = (b - t).coerceAtMost(t)
                if (r > l && b > t && above > 0) {
                    val rows = IntArray((r - l) * (above + b - t))
                    software.getPixels(rows, 0, r - l, l, t - above, r - l, above + b - t)
                    SnapshotPolicy.patchRows(rows, r - l, above + b - t, 0, above, r - l, above + b - t)
                    software.setPixels(rows, (r - l) * above, r - l, l, t, r - l, b - t)
                }
            }
            // Brightness right behind the home indicator: bottom 4 % of the screen, middle third.
            val strip = IntArray(24)
            val y = (h * .975f).toInt().coerceIn(0, h - 1)
            for (i in strip.indices) strip[i] = software.getPixel((w / 3f + (i + .5f) * (w / 3f) / strip.size).toInt(), y)
            // The app's top edge right under the status bar, for the Apple status strip (StatusTint).
            val topColor = if (topBandY in 0 until h) {
                val band = IntArray(24)
                for (i in band.indices) band[i] = software.getPixel(((i + .5f) * w / band.size).toInt().coerceIn(0, w - 1), topBandY)
                statusBandColor(band)
            } else null
            val scale = SnapshotPolicy.scaleFor(w, h)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(software, (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1), true) else software
            return PreparedCapture.Ready(AppSnapshot(scaled, w, h, SnapshotPolicy.averageLuma(strip)), topColor)
        } catch (_: OutOfMemoryError) {
            return PreparedCapture.Failed
        } catch (_: RuntimeException) {
            return PreparedCapture.Failed
        } finally {
            // The ~16 MB full-size copy is only an intermediate (the stored one is downscaled).
            if (software !== capture && SnapshotPolicy.scaleFor(software.width, software.height) < 1f) software.recycle()
        }
    }
}

/** Median of each channel across a row of samples: a toolbar's colour, unmoved by a title or an icon on it. */
internal fun statusBandColor(samples: IntArray): Int {
    fun median(shift: Int) = samples.map { (it shr shift) and 0xFF }.sorted()[samples.size / 2]
    return (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
}
