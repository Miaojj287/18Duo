package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Round 18 (owner recording 2026-10-22 12:20): HyperOS restarts 18Duo's process after an update or
 * when it has sat in the background for long. The frozen Home the navigation overlay draws behind a
 * card lived only in memory, so the first swipe home after a restart flew over bare wallpaper, and the
 * card had no icon to aim at. The last frame of Home per panel size, with its icon positions, is kept
 * in no-backup storage and read back when the process starts.
 */
internal object HomeSnapshotDisk {
    private const val DIR = "home-snapshots"
    // Round 22: background priority; the WebP encode runs while the launch/return animation is drawing.
    private val io = Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "duo-home-snapshot")
    }
    private val restored = AtomicBoolean(false)
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        if (dir == null) dir = File(context.noBackupFilesDir, DIR)
        if (!restored.compareAndSet(false, true)) return
        io.execute { runCatching { restore() }.onFailure { Log.w("DuoNavigation", "home snapshot restore failed", it) } }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Newest frame per size not yet written; a launch copies Home two or three times within a second. */
    private val pending = HashMap<String, HomeSnapshot>()
    private val flush = Runnable {
        val frames = pending.values.toList(); pending.clear()
        frames.forEach(::encode)
    }

    /**
     * Main thread: [snapshot] is the newest Home of its size. Round 22: the WebP encode (tens of ms of CPU)
     * used to start at once, in the middle of the launch zoom; now the newest frame per size is written
     * [WRITE_DELAY_MS] after the last copy, when the animation is over.
     */
    fun write(snapshot: HomeSnapshot) {
        if (dir == null) return
        pending[baseName(snapshot.windowWidth, snapshot.windowHeight)] = snapshot
        main.removeCallbacks(flush)
        main.postDelayed(flush, WRITE_DELAY_MS)
    }

    private fun encode(snapshot: HomeSnapshot) {
        val target = dir ?: return
        io.execute {
            runCatching {
                if (snapshot.bitmap.isRecycled) return@runCatching
                target.mkdirs()
                val base = baseName(snapshot.windowWidth, snapshot.windowHeight)
                val tmp = File(target, "$base.tmp")
                tmp.outputStream().use { snapshot.bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it) }
                File(target, "$base.json").writeText(encodeHomeSnapshotMeta(snapshot.windowWidth, snapshot.windowHeight, snapshot.icons))
                if (!tmp.renameTo(File(target, "$base.webp"))) tmp.delete()
            }
        }
    }

    private fun restore() {
        val target = dir ?: return
        val images = target.listFiles { f -> f.name.endsWith(".webp") }?.sortedByDescending { it.lastModified() } ?: return
        val frames = images.mapNotNull { image ->
            val meta = decodeHomeSnapshotMeta(File(target, image.name.removeSuffix(".webp") + ".json").takeIf { it.exists() }?.readText()
                ?: return@mapNotNull null) ?: return@mapNotNull null
            val bitmap = BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: return@mapNotNull null
            // capturedAt 0: any frame this process captures is newer.
            HomeSnapshot(bitmap, meta.width, meta.height, capturedAt = 0L, icons = meta.icons)
        }
        HomeSnapshotStore.restore(frames)
        Log.i("DuoNavigation", "home snapshots restored: ${frames.joinToString { "${it.windowWidth}x${it.windowHeight}/${it.icons?.entries?.size ?: 0} icons" }}")
    }

    private fun baseName(w: Int, h: Int) = "home_${w}x$h"
    private const val WRITE_DELAY_MS = 2_500L
}

internal class HomeSnapshotMeta(val width: Int, val height: Int, val icons: HomeIconLayout?)

/** The size and icon layout of a stored Home frame (pure JSON, unit-tested). */
internal fun encodeHomeSnapshotMeta(width: Int, height: Int, icons: HomeIconLayout?): String = JSONObject()
    .put("v", 1).put("w", width).put("h", height)
    .apply {
        if (icons != null) put("icons", JSONObject()
            .put("w", icons.width.toDouble()).put("h", icons.height.toDouble())
            .put("front", JSONArray(icons.front.map { it.name }))
            .put("entries", JSONArray(icons.entries.map { e ->
                JSONObject().put("id", e.appId).put("kind", e.kind.name).put("layer", e.layer.name)
                    .put("b", JSONArray(listOf(e.bounds.left, e.bounds.top, e.bounds.right, e.bounds.bottom).map(Float::toDouble)))
            })))
    }.toString()

internal fun decodeHomeSnapshotMeta(text: String): HomeSnapshotMeta? = runCatching {
    val j = JSONObject(text)
    if (j.getInt("v") != 1) return null
    val w = j.getInt("w"); val h = j.getInt("h")
    if (w <= 0 || h <= 0) return null
    val icons = j.optJSONObject("icons")?.let { o ->
        val front = o.getJSONArray("front").let { a -> List(a.length()) { IconLayer.valueOf(a.getString(it)) } }.toSet()
        val entries = o.getJSONArray("entries").let { a ->
            List(a.length()) { i ->
                val e = a.getJSONObject(i)
                val b = e.getJSONArray("b")
                HomeIconEntry(e.getString("id"), ZoomBounds(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(),
                    b.getDouble(2).toFloat(), b.getDouble(3).toFloat()), HomeIconKind.valueOf(e.getString("kind")),
                    IconLayer.valueOf(e.getString("layer")))
            }
        }
        HomeIconLayout(o.getDouble("w").toFloat(), o.getDouble("h").toFloat(), entries, 0L, front)
    }
    HomeSnapshotMeta(w, h, icons)
}.getOrNull()
