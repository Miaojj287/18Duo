package com.jake.duolauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.Executor

/**
 * File naming and housekeeping for persisted app snapshots (pure, JVM-tested). One app in one
 * profile keeps at most one frame per panel shape: `<key>_<p|l>.webp` plus a tiny `.meta` text
 * file, the key Base64url-encoded so any package name is a safe file name and can be read back
 * (to delete an uninstalled app's frames).
 */
internal object SnapshotFiles {
    const val DIR = "app-snapshots"
    /** On disk: every card of a full switcher in both shapes. */
    const val MAX_FILES = 32
    const val WEBP_QUALITY = 82

    fun baseName(key: String, landscape: Boolean): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray()) + if (landscape) "_l" else "_p"

    /** The key and shape a file belongs to, or null for anything that is not one of ours. */
    fun parse(fileName: String): Pair<String, Boolean>? {
        val base = fileName.substringBeforeLast('.')
        val shape = base.substringAfterLast('_', "")
        if (shape != "p" && shape != "l") return null
        val key = runCatching { String(Base64.getUrlDecoder().decode(base.substringBeforeLast('_'))) }.getOrNull() ?: return null
        return key.takeIf { '#' in it }?.let { it to (shape == "l") }
    }

    fun meta(sourceWidth: Int, sourceHeight: Int, bottomLuma: Float, capturedAt: Long): String =
        "v1\n$sourceWidth\n$sourceHeight\n$bottomLuma\n$capturedAt\n"

    /** sourceWidth, sourceHeight, bottomLuma, capturedAt — or null for a damaged or foreign file. */
    fun parseMeta(text: String): Meta? {
        val lines = text.trim().lines()
        if (lines.size < 5 || lines[0] != "v1") return null
        val w = lines[1].toIntOrNull() ?: return null
        val h = lines[2].toIntOrNull() ?: return null
        val luma = lines[3].toFloatOrNull() ?: return null
        val at = lines[4].toLongOrNull() ?: return null
        return if (w > 0 && h > 0) Meta(w, h, luma, at) else null
    }

    data class Meta(val sourceWidth: Int, val sourceHeight: Int, val bottomLuma: Float, val capturedAt: Long)

    /** Names of the oldest images to delete so that at most [max] remain ([files]: name → last modified). */
    fun prune(files: Map<String, Long>, max: Int = MAX_FILES): List<String> =
        files.entries.sortedByDescending { it.value }.drop(max).map { it.key }
}

/**
 * App snapshots in the app's no-backup storage (`Context.noBackupFilesDir`, credential-encrypted,
 * never included in a backup or a device transfer). Reads and writes run on [io], never on the
 * stage or main thread; [write] encodes WebP off the caller's thread.
 */
internal class SnapshotDisk(root: File, private val io: Executor) {
    private val dir = File(root, SnapshotFiles.DIR)

    private fun image(key: String, landscape: Boolean) = File(dir, SnapshotFiles.baseName(key, landscape) + ".webp")
    private fun meta(key: String, landscape: Boolean) = File(dir, SnapshotFiles.baseName(key, landscape) + ".meta")

    /** Encodes [snapshot] (a software or hardware bitmap) as WebP on [io]; replaces the app's older frame of that shape. */
    fun write(key: String, snapshot: AppSnapshot) = submit {
        val bitmap = snapshot.bitmap
        if (bitmap.isRecycled) return@submit
        dir.mkdirs()
        val landscape = snapshot.landscape
        val tmp = File(dir, SnapshotFiles.baseName(key, landscape) + ".tmp")
        val source = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return@submit else bitmap
        try {
            tmp.outputStream().use { source.compress(Bitmap.CompressFormat.WEBP_LOSSY, SnapshotFiles.WEBP_QUALITY, it) }
        } finally { if (source !== bitmap) source.recycle() }
        meta(key, landscape).writeText(SnapshotFiles.meta(snapshot.sourceWidth, snapshot.sourceHeight, snapshot.bottomLuma, snapshot.capturedAt))
        if (!tmp.renameTo(image(key, landscape))) tmp.delete()
        prune()
    }

    /** Decodes [key]'s frame of one shape (a hardware bitmap: no app heap), or null. Blocking: [io] only. */
    fun read(key: String, landscape: Boolean): AppSnapshot? = runCatching {
        val file = image(key, landscape)
        if (!file.exists()) return null
        val info = SnapshotFiles.parseMeta(meta(key, landscape).readText()) ?: return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        AppSnapshot(bitmap, info.sourceWidth, info.sourceHeight, info.bottomLuma, info.capturedAt, key)
    }.getOrNull()

    /** Keys and shapes on disk, most recently written first. */
    fun stored(): List<Pair<String, Boolean>> = runCatching {
        (dir.listFiles { f -> f.name.endsWith(".webp") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { SnapshotFiles.parse(it.name) }
    }.getOrDefault(emptyList())

    fun delete(key: String) = submit { for (l in listOf(false, true)) { image(key, l).delete(); meta(key, l).delete() } }

    fun deletePackage(packageName: String) = submit {
        (dir.listFiles() ?: emptyArray()).forEach { f ->
            if (SnapshotFiles.parse(f.name)?.first?.startsWith("$packageName#") == true) f.delete()
        }
    }

    /** The navigation service was turned off: no snapshot outlives it. Runs at once if [io] is gone. */
    fun deleteAll() {
        val wipe = { runCatching { dir.listFiles()?.forEach { it.delete() } }; Unit }
        try { io.execute(wipe) } catch (_: java.util.concurrent.RejectedExecutionException) { wipe() }
    }

    private fun prune() {
        val files = (dir.listFiles { f -> f.name.endsWith(".webp") } ?: return).associate { it.name to it.lastModified() }
        for (name in SnapshotFiles.prune(files)) {
            File(dir, name).delete()
            File(dir, name.substringBeforeLast('.') + ".meta").delete()
        }
    }

    private fun submit(block: () -> Unit) {
        try { io.execute { runCatching(block) } } catch (_: java.util.concurrent.RejectedExecutionException) { /* stopped */ }
    }
}
