package mon.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.DisplayMetrics
import androidx.core.graphics.drawable.toBitmap
import mon.launcher.core.LogoCatalog
import org.xmlpull.v1.XmlPullParser

data class IconPack(val packageName: String, val label: String)

/** Reads installed icon packs. Each pack's appfilter.xml maps app activities to image names. Nothing is copied into the launcher. */
class IconPacks(private val context: Context) {
    private val pm = context.packageManager
    private var cached: Pair<String, LogoCatalog>? = null

    fun installed(): List<IconPack> =
        THEME_ACTIONS.flatMap { action -> pm.queryIntentActivities(Intent(action), 0) }
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { IconPack(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }

    fun isInstalled(packageName: String) = runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    /**
     * [choice] null means never chosen: use the Projectivy pack if installed.
     * Empty means no pack.
     */
    fun effective(choice: String?): String? = when {
        choice == null -> PROJECTIVY_PACK.takeIf(::isInstalled)
        choice.isEmpty() -> null
        else -> choice.takeIf(::isInstalled)
    }

    /** Cached until the pack is updated. */
    fun catalog(pack: String?): LogoCatalog {
        if (pack == null) return LogoCatalog()
        val version = runCatching { pm.getPackageInfo(pack, 0).lastUpdateTime }.getOrNull() ?: return LogoCatalog()
        val key = "$pack@$version"
        cached?.takeIf { it.first == key }?.let { return it.second }
        val catalog = runCatching { LogoCatalog(pack = readAppFilter(pack)) }
            .onFailure { logProblem("Couldn't read icon pack $pack", it) }
            .getOrDefault(LogoCatalog())
        cached = key to catalog
        return catalog
    }

    fun load(pack: String, name: String): Bitmap? {
        val res = pm.getResourcesForApplication(pack)
        val id = res.getIdentifier(name, "drawable", pack).takeIf { it != 0 } ?: return null
        val drawable = runCatching { res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
            ?: res.getDrawable(id, null)
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 512
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 512
        return trimTransparent(drawable.toBitmap(width, height))
    }

    /** If an app has several entries, use the one for its launch activity, else the first. */
    private fun readAppFilter(pack: String): Map<String, String> {
        val res = pm.getResourcesForApplication(pack)
        val id = res.getIdentifier("appfilter", "xml", pack)
        val byApp = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        if (id != 0) {
            val parser = res.getXml(id)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "item") continue
                val component = parser.getAttributeValue(null, "component") ?: continue
                val image = parser.getAttributeValue(null, "drawable") ?: continue
                val inside = component.substringAfter("ComponentInfo{", "").substringBefore("}")
                val app = inside.substringBefore('/').takeIf { it.isNotEmpty() } ?: continue
                byApp.getOrPut(app) { mutableListOf() } += inside.substringAfter('/', "") to image
            }
            parser.close()
        }
        return byApp.mapValues { (app, entries) ->
            val launches = (pm.getLeanbackLaunchIntentForPackage(app) ?: pm.getLaunchIntentForPackage(app))?.component?.className
            entries.firstOrNull { (activity, _) -> launches != null && (activity == launches || app + activity == launches) }?.second
                ?: entries.first().second
        }
    }

    companion object {
        const val PROJECTIVY_PACK = "dev.projectivy.blueprint.app"
        const val PROJECTIVY_PACK_PAGE = "https://github.com/SicMundus86/ProjectivyIconPack"

        /** Icon packs declare these intent actions so launchers can find them. */
        private val THEME_ACTIONS = listOf(
            "org.adw.launcher.THEMES",
            "com.novalauncher.THEME",
            "com.anddoes.launcher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "com.fede.launcher.THEME_ICONPACK",
        )
    }
}
