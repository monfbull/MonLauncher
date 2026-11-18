package mon.launcher

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mon.launcher.core.Logo
import mon.launcher.core.Tile

/** [fill] images (banners) cover the whole tile. Others get padding. */
class TileArt(val image: ImageBitmap, val fill: Boolean)

/**
 * With an icon pack: pack image, else icon foreground, else TV banner, else full icon.
 * Without a pack: TV banner, else full icon.
 */
class TileImages(private val apps: AppSource, private val iconPacks: IconPacks) {
    private val cache = LruCache<String, TileArt>(160)

    var pack: String? = null
        set(value) {
            if (value != field) cache.evictAll()
            field = value
        }

    /** Lets tiles draw on their first frame. */
    fun peek(tile: Tile): TileArt? = cache.get(key(tile))

    /** Call early, so opening the grid doesn't wait on disk reads. */
    suspend fun preload(tiles: List<Tile>) = tiles.forEach { get(it) }

    suspend fun get(tile: Tile): TileArt? {
        val key = key(tile)
        cache.get(key)?.let { return it }
        val art = withContext(Dispatchers.IO) {
            runCatching {
                val logo = (tile.logo as? Logo.Asset)?.let { asset -> pack?.let { iconPacks.load(it, asset.name) } }
                fun onCard(bitmap: android.graphics.Bitmap?) = bitmap?.let { TileArt(it.asImageBitmap(), fill = false) }
                fun banner() = apps.banner(tile.packageName)?.let { TileArt(it.asImageBitmap(), fill = true) }
                if (pack != null) {
                    onCard(logo) ?: onCard(apps.iconArtwork(tile.packageName, transparentOnly = true)) ?: banner()
                        ?: onCard(apps.iconArtwork(tile.packageName))
                } else {
                    banner() ?: onCard(apps.iconArtwork(tile.packageName))
                }
            }.onFailure { logProblem("Couldn't load tile picture for ${tile.packageName}", it) }.getOrNull()
        } ?: return null
        return art.also { it.image.prepareToDraw(); cache.put(key, it) }
    }

    private fun key(tile: Tile) = "$pack|${tile.packageName}|${tile.logo}"
}
