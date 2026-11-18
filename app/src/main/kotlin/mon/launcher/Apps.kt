package mon.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toBitmap
import mon.launcher.core.InstalledApp
import mon.launcher.core.LogoCatalog
import mon.launcher.core.Place
import mon.launcher.core.SavedState

/**
 * Preferred dock apps on first run, in order. Installed ones are used; the rest of the six places
 * are filled from the app list.
 */
val DEFAULT_DOCK = listOf(
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
    "com.wbd.stream",
    "com.hulu.livingroomplus",
    "com.cbs.ott",
    "com.peacocktv.peacockandroid",
    "com.plexapp.android",
    "com.spotify.tv.android",
)

/** Queries and launches installed apps. */
class AppSource(private val context: Context) {
    private val pm = context.packageManager

    fun installed(): List<InstalledApp> {
        val categories = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        val byPackage = LinkedHashMap<String, InstalledApp>()
        for (category in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || pkg in byPackage) continue
                byPackage[pkg] = InstalledApp(packageName = pkg, label = info.loadLabel(pm).toString())
            }
        }
        return byPackage.values.toList()
    }

    fun launch(packageName: String) {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return
        // The app may have been disabled or removed since the list was built.
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { logProblem("Couldn't launch $packageName", it) }
    }

    /** Opens the system App info screen for [packageName]. */
    fun openAppInfo(packageName: String) = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
    )

    /** Starts the system uninstall flow, which asks for confirmation. */
    fun uninstall(packageName: String) = start(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))

    /** Starts a system screen in a new task so Back returns to the launcher. */
    private fun start(intent: Intent) {
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }.onFailure { logProblem("Couldn't open system screen ${intent.action}", it) }
    }

    /**
     * The app's icon at high density, trimmed to its content. For adaptive icons only the foreground
     * layer is used, so the tile's own background shows behind it.
     */
    fun iconArtwork(packageName: String, transparentOnly: Boolean = false): Bitmap? = safely {
        val info = pm.getApplicationInfo(packageName, 0)
        val drawable = runCatching {
            pm.getResourcesForApplication(info).getDrawableForDensity(info.icon, DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull() ?: pm.getApplicationIcon(info)
        // Legacy icons include their own background.
        if (transparentOnly && drawable !is AdaptiveIconDrawable) return@safely null
        val art = (drawable as? AdaptiveIconDrawable)?.foreground ?: drawable
        trimTransparent(art.toBitmap(ICON_SIZE, ICON_SIZE))
    }

    /** The app's TV banner (android:banner), or null if it has none. */
    fun banner(packageName: String): Bitmap? = safely {
        val component = (pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName))?.component
        val drawable = component?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() }
            ?: pm.getApplicationBanner(packageName)
            ?: return@safely null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: BANNER_WIDTH
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: BANNER_WIDTH * 9 / 16
        drawable.toBitmap(width, height)
    }

    private fun <T> safely(block: () -> T?): T? = try {
        block()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

private const val ICON_SIZE = 432
private const val BANNER_WIDTH = 640

/** Crops transparent and near-transparent margins. */
fun trimTransparent(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    var left = w; var top = h; var right = -1; var bottom = -1
    for (y in 0 until h) for (x in 0 until w) {
        if ((pixels[y * w + x] ushr 24) > 12) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return src
    return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
}

/** Persists [SavedState] in shared preferences. */
class StateStore(context: Context) {
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(): SavedState? {
        if (!prefs.contains(DOCK)) return null
        return SavedState(
            dock = prefs.getString(DOCK, "").orEmpty().split('\n').filter { it.isNotEmpty() },
            hidden = prefs.getStringSet(HIDDEN, emptySet()).orEmpty().toSet(),
            lastLaunched = prefs.getString(LAST, null),
            lastOpened = prefs.getString(OPENED, null),
            lastOpenedFrom = prefs.getString(OPENED_FROM, null)?.let { name -> Place.entries.find { it.name == name } },
        )
    }

    fun save(state: SavedState) {
        prefs.edit()
            .putString(DOCK, state.dock.joinToString("\n"))
            .putStringSet(HIDDEN, state.hidden)
            .putString(LAST, state.lastLaunched)
            .putString(OPENED, state.lastOpened)
            .putString(OPENED_FROM, state.lastOpenedFrom?.name)
            .apply()
    }

    /** Records that an app was launched from the launcher. */
    fun markOpened() = prefs.edit().putLong(OPENED_AT, System.currentTimeMillis()).putBoolean(RETURN_PENDING, true).apply()

    /**
     * True until the launcher is shown again after a launch; the next return then restores focus
     * to the launched app's tile.
     */
    fun returnPending(): Boolean = prefs.getBoolean(RETURN_PENDING, false)

    fun clearReturnPending() {
        if (returnPending()) prefs.edit().putBoolean(RETURN_PENDING, false).apply()
    }

    /** Time of the last launch from the launcher, or 0. */
    fun openedAt(): Long = prefs.getLong(OPENED_AT, 0L)

    /** Whether an app was launched since the last boot. */
    fun openedSinceBoot(): Boolean {
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return openedAt() > bootedAt
    }

    private companion object {
        const val DOCK = "dock"
        const val HIDDEN = "hidden"
        const val LAST = "last"
        const val OPENED = "opened"
        const val OPENED_FROM = "opened_from"
        const val OPENED_AT = "opened_at"
        const val RETURN_PENDING = "return_pending"
    }
}
