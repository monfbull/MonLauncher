package mon.launcher

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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

/** The permission that lets the launcher read images in Download. It was renamed in Android 13. */
val photoPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_IMAGES
    else android.Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * Wallpapers live in app storage, so deleting needs no extra permission.
 * Uninstalling the launcher removes them.
 */
class WallpaperLibrary(private val context: Context) {
    val folder = File(context.filesDir, "wallpapers")
    // Deprecated, but reading files directly with the photo permission needs the real path.
    // A hardcoded /sdcard/Download is wrong on some devices.
    @Suppress("DEPRECATION")
    private val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    private val prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE)
    private val thumbDir = File(context.cacheDir, "wallpaper-thumbs")
    private val thumbs = LruCache<String, ImageBitmap>(48)

    /** Only runs once, so a deleted default doesn't come back. */
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

    /** Newest first. */
    fun list(): List<File> = images(folder, depth = 1).sortedByDescending { it.lastModified() }

    /** Skips images already in the library (same name and size). */
    fun inDownloads(): List<File> {
        val have = list().map { it.name to it.length() }.toSet()
        return images(downloads, depth = 3)
            .filter { (it.name to it.length()) !in have }
            .sortedByDescending { it.lastModified() }
    }

    fun import(file: File): File = file.inputStream().use { save(file.name, it) }

    /** Throws and keeps nothing if [data] is over [limitBytes] or isn't a readable image. */
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

    enum class Export { Saved, AlreadyThere, Failed }

    /** A copy in Download survives uninstalling the launcher. */
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

    /** Only deletes files in the library folder. Returns true if the file is gone. */
    fun delete(file: File): Boolean = file.parentFile == folder && (file.delete() || !file.exists())

    /** Cached on disk and in memory. */
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
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, THUMB_WIDTH, 1)
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Crop tall images to a center square, so a very tall image doesn't make a huge thumbnail.
        // Thumbnails are shown at 16:9, so nothing visible is lost.
        val source = if (decoded.height > decoded.width) {
            Bitmap.createBitmap(decoded, 0, (decoded.height - decoded.width) / 2, decoded.width, decoded.width)
        } else {
            decoded
        }
        val height = (THUMB_WIDTH.toLong() * source.height / source.width).toInt().coerceAtLeast(1)
        return if (source.width > THUMB_WIDTH) Bitmap.createScaledBitmap(source, THUMB_WIDTH, height, true) else source
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
