package mon.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * [sharp] is 1920x1080.
 * [glass] is a light blur, for the dock and settings button.
 * [heavy] is a strong blur, for the grid, panels and upload page.
 */
class WallpaperImages(val sharp: Bitmap, val glass: Bitmap, val heavy: Bitmap, val version: String)

/** Changes when the file is replaced or edited. */
fun wallpaperVersion(file: File) = "${file.path}:${file.lastModified()}:${file.length()}"

/**
 * Six 1080p screens, about 50 MB.
 * Lets a 12 MP portrait photo decode at full size, so it stays sharp after cropping.
 */
private const val MAX_DECODE_PIXELS = 6L * 1920 * 1080

/**
 * Doubles the sample size while both sides stay at least [minWidth]x[minHeight].
 * Then keeps doubling until the image has at most [MAX_DECODE_PIXELS].
 * The second step is for very long, thin images (e.g. 60000x1000).
 */
internal fun sampleSize(width: Int, height: Int, minWidth: Int, minHeight: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= minWidth && height / (sample * 2) >= minHeight) sample *= 2
    // Rounded up, so a side that shrinks below one pixel is still counted as one.
    while (ceilDiv(width, sample) * ceilDiv(height, sample) > MAX_DECODE_PIXELS) sample *= 2
    return sample
}

private fun ceilDiv(a: Int, b: Int): Long = (a.toLong() + b - 1) / b

object Wallpaper {
    private const val WIDTH = 1920
    private const val HEIGHT = 1080

    /** Wallpapers kept in the cache. */
    private const val CACHED = 4

    /**
     * Returns null if [file] can't be read.
     * Results are cached under [cacheRoot]. Decoding and blurring a large photo takes seconds.
     */
    fun load(cacheRoot: File, file: File): WallpaperImages? {
        if (!file.canRead()) return null
        val version = wallpaperVersion(file)
        val dir = File(cacheRoot, Integer.toHexString(version.hashCode()))
        readCache(dir, version)?.let { dir.setLastModified(System.currentTimeMillis()); return it }
        val fresh = decode(file) ?: return null
        runCatching {
            writeCache(dir, version, fresh)
            prune(cacheRoot)
        }
        return fresh.toImages(version)
    }

    private fun prune(cacheRoot: File) {
        cacheRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedByDescending { it.lastModified() }
            .drop(CACHED)
            .forEach { it.deleteRecursively() }
    }

    private class Processed(val sharp: Bitmap, val glassSmall: Bitmap, val heavySmall: Bitmap) {
        fun toImages(version: String) = WallpaperImages(
            sharp = sharp,
            glass = glassSmall,
            heavy = heavySmall,
            version = version,
        )
    }

    private fun readCache(dir: File, version: String): WallpaperImages? = runCatching {
        if (File(dir, "stamp").readText() != version) return null
        Processed(
            sharp = BitmapFactory.decodeFile(File(dir, "sharp.jpg").path) ?: return null,
            glassSmall = BitmapFactory.decodeFile(File(dir, "glass.png").path) ?: return null,
            heavySmall = BitmapFactory.decodeFile(File(dir, "heavy.png").path) ?: return null,
        ).toImages(version)
    }.getOrNull()

    private fun writeCache(dir: File, stamp: String, images: Processed) {
        dir.mkdirs()
        File(dir, "stamp").delete()
        File(dir, "sharp.jpg").outputStream().use { images.sharp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        File(dir, "glass.png").outputStream().use { images.glassSmall.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "heavy.png").outputStream().use { images.heavySmall.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "stamp").writeText(stamp) // written last, so a partial cache is never read
    }

    private fun decode(file: File): Processed? {
        val width = WIDTH
        val height = HEIGHT
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val sharp = centerCrop(decoded, width, height)
        return Processed(
            sharp = sharp,
            glassSmall = smallBlur(sharp, shrink = 2, radius = 3),
            heavySmall = smallBlur(sharp, shrink = 24, radius = 4),
        )
    }

    /** Kept small: drawing it stretched blurs it more. */
    private fun smallBlur(src: Bitmap, shrink: Int, radius: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(src, src.width / shrink, src.height / shrink, true)
        return boxBlur(boxBlur(small, radius), radius)
    }

    private fun centerCrop(src: Bitmap, width: Int, height: Int): Bitmap {
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        // At least 1 pixel: very thin images can round down to 0.
        val w = (width / scale).toInt().coerceIn(1, src.width)
        val h = (height / scale).toInt().coerceIn(1, src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
        return Bitmap.createScaledBitmap(cropped, width, height, true)
    }

    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val tmp = IntArray(w * h)
        pass(pixels, tmp, w, h, radius, horizontal = true)
        pass(tmp, pixels, w, h, radius, horizontal = false)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var red = 0; var green = 0; var blue = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    red += (p shr 16) and 0xFF; green += (p shr 8) and 0xFF; blue += p and 0xFF; n++
                }
                val out = (0xFF shl 24) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
            }
        }
    }
}
