package mon.launcher

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Wallpapers stored in app storage, plus images in Download that can be imported.
 * Using app storage means deleting needs no extra permission; uninstalling removes them.
 */
class WallpaperLibrary(private val context: Context) {
    val folder = File(context.filesDir, "wallpapers")
    // Deprecated for scoped storage, but this reads files directly with the photo permission, so
    // the real path is what's needed. Unlike a hardcoded /sdcard/Download, it's correct on every device.
    @Suppress("DEPRECATION")
    private val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private val prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
    private val thumbDir = File(context.cacheDir, "wallpaper-thumbs")
    private val thumbs = LruCache<String, ImageBitmap>(48)

    /** On first run, adds the bundled default wallpaper so the library isn't empty. */
    fun seedDefault() {
        if (prefs.getBoolean(SEEDED, false)) return
        if (list().isEmpty()) {
            runCatching {
                folder.mkdirs()
                context.resources.openRawResource(R.raw.default_wallpaper).use { input ->
                    File(folder, DEFAULT_NAME).outputStream().use { input.copyTo(it) }
                }
            }.onFailure { logProblem("Couldn't add the default wallpaper", it) }
        }
        prefs.edit().putBoolean(SEEDED, true).apply()
    }

    /** Library images, newest first. */
    fun list(): List<File> = images(folder, depth = 1).sortedByDescending { it.lastModified() }

    /** Images in Download (and its subfolders) not already in the library. */
    fun inDownloads(): List<File> {
        val have = list().map { it.name to it.length() }.toSet()
        return images(downloads, depth = 3)
            .filter { (it.name to it.length()) !in have }
            .sortedByDescending { it.lastModified() }
    }

    /** Copies an image into the library under its own name, made safe and unique. */
    fun import(file: File): File = file.inputStream().use { save(file.name, it) }

    /**
     * Saves [data] to the library under a safe, unique version of [name]. Throws, and keeps nothing,
     * if the result isn't a readable image.
     */
    fun save(name: String, data: InputStream, limitBytes: Long = MAX_BYTES): File {
        folder.mkdirs()
        val target = uniqueName(safeName(name))
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = data.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limitBytes) throw IllegalArgumentException("Image is larger than ${limitBytes / 1_000_000} MB")
                    out.write(buffer, 0, n)
                }
            }
            if (!isImage(target)) throw IllegalArgumentException("Not an image the TV can read")
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Result of [exportToDownloads]. */
    enum class Export { Saved, AlreadyThere, Failed }

    /**
     * Saves a copy of a library image to the shared Download folder, so it survives uninstalling
     * the launcher. Does nothing if an identical copy (same name and size) is already there.
     */
    fun exportToDownloads(file: File): Export {
        if (File(downloads, file.name).let { it.exists() && it.length() == file.length() }) return Export.AlreadyThere
        return if (copyToDownloads(file)) Export.Saved else Export.Failed
    }

    private fun copyToDownloads(file: File): Boolean = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(file))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("couldn't open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }.onFailure { logProblem("Couldn't save ${file.name} to Download", it) }.getOrDefault(false)

    private fun mimeType(file: File) = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** Deletes a library image. Returns true if it no longer exists. */
    fun delete(file: File): Boolean = file.parentFile == folder && (file.delete() || !file.exists())

    /** A downscaled preview, cached on disk and in memory. */
    suspend fun thumbnail(file: File): ImageBitmap? = try {
        loadThumbnail(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logProblem("Couldn't make a thumbnail for ${file.name}", e)
        null
    }

    private suspend fun loadThumbnail(file: File): ImageBitmap? {
        val key = wallpaperVersion(file)
        thumbs.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cached = File(thumbDir, Integer.toHexString(key.hashCode()) + ".jpg")
            val bitmap = (if (cached.exists()) BitmapFactory.decodeFile(cached.path) else null)
                ?: decodeSmall(file)?.also { small ->
                    runCatching {
                        thumbDir.mkdirs()
                        cached.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    }
                }
            bitmap?.asImageBitmap()?.also { it.prepareToDraw(); thumbs.put(key, it) }
        }
    }

    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val height = THUMB_WIDTH * decoded.height / decoded.width
        return if (decoded.width > THUMB_WIDTH) Bitmap.createScaledBitmap(decoded, THUMB_WIDTH, height, true) else decoded
    }

    private fun images(dir: File, depth: Int): List<File> {
        if (depth <= 0) return emptyList()
        return dir.listFiles().orEmpty().flatMap {
            when {
                it.isDirectory && !it.name.startsWith(".") -> images(it, depth - 1)
                it.isFile && it.extension.lowercase() in EXTENSIONS -> listOf(it)
                else -> emptyList()
            }
        }
    }

    private fun isImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.')
        val ext = base.substringAfterLast('.', "").lowercase()
        return when {
            base.isEmpty() -> "wallpaper.jpg"
            ext in EXTENSIONS -> base
            else -> "$base.jpg"
        }
    }

    private fun uniqueName(name: String): File {
        var file = File(folder, name)
        var n = 2
        while (file.exists()) {
            file = File(folder, "${name.substringBeforeLast('.')}-$n.${name.substringAfterLast('.')}")
            n++
        }
        return file
    }

    private companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val THUMB_WIDTH = 480
        const val MAX_BYTES = 60_000_000L
        const val SEEDED = "seeded_default"
        const val DEFAULT_NAME = "default.jpg"
    }
}
