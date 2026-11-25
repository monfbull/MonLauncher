package mon.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LauncherSettings(
    val soundsOn: Boolean = true,
    val soundVolume: Int = 8,
    /** Wallpaper file name. Null means use the newest wallpaper. */
    val wallpaper: String? = null,
    /** Icon pack package name. Empty means no pack. Null means never chosen. */
    val iconPack: String? = null,
) {
    companion object {
        const val MAX_VOLUME = 10
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = LauncherSettings(
        soundsOn = prefs.getBoolean(SOUNDS_ON, true),
        soundVolume = prefs.getInt(SOUND_VOLUME, LauncherSettings().soundVolume),
        wallpaper = prefs.getString(WALLPAPER, null),
        iconPack = if (prefs.contains(ICON_PACK)) prefs.getString(ICON_PACK, "") else null,
    )

    fun save(settings: LauncherSettings) {
        prefs.edit()
            .putBoolean(SOUNDS_ON, settings.soundsOn)
            .putInt(SOUND_VOLUME, settings.soundVolume)
            .putString(WALLPAPER, settings.wallpaper)
            .apply {
                if (settings.iconPack == null) remove(ICON_PACK) else putString(ICON_PACK, settings.iconPack)
            }
            .apply()
    }

    private companion object {
        const val SOUNDS_ON = "sounds_on"
        const val SOUND_VOLUME = "sound_volume"
        const val WALLPAPER = "wallpaper"
        const val ICON_PACK = "icon_pack"
    }
}

/** Opens the system settings app (TV settings on Android TV). */
fun openAndroidSettings(context: Context) {
    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { logProblem("Couldn't open Android settings", it) }
}

/** The settings panel. Changes apply immediately. */
@Composable
fun SettingsPanel(
    settings: LauncherSettings,
    onChange: (LauncherSettings) -> Unit,
    onOpenWallpapers: () -> Unit,
    onShowWelcome: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sounds = LocalSounds.current
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }
    var showIconPacks by remember { mutableStateOf(false) }
    val iconPacks = remember { IconPacks(context) }
    // Looked up off the main thread, and again only when the choice changes. Null until loaded.
    val iconPackName by produceState<String?>(null, settings.iconPack) {
        value = withContext(Dispatchers.IO) {
            iconPacks.effective(settings.iconPack)
                ?.let { pack -> iconPacks.installed().firstOrNull { it.packageName == pack }?.label }
                ?: "App icons"
        }
    }
    GlassPanel("Settings", width = 440.dp, onDismiss = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFocus { first } }
        // Most remotes have no button for this, and MonLauncher replaces the home screen that links to it.
        PanelRow(first, onSelect = { openAndroidSettings(context) }) {
            BasicText("Android settings", style = PanelText.row)
            BasicText("›", style = PanelText.value)
        }
        PanelRow(
            remember { FocusRequester() },
            onSelect = {
                val next = settings.copy(soundsOn = !settings.soundsOn)
                onChange(next)
                if (next.soundsOn) sounds?.clicked()
            },
            clickSound = false,
        ) {
            BasicText("Sound effects", style = PanelText.row)
            BasicText(if (settings.soundsOn) "On" else "Off", style = PanelText.value)
        }
        PanelRow(
            remember { FocusRequester() },
            onAdjust = { step ->
                val volume = (settings.soundVolume + step).coerceIn(0, LauncherSettings.MAX_VOLUME)
                if (volume != settings.soundVolume) {
                    onChange(settings.copy(soundVolume = volume))
                    sounds?.preview()
                }
            },
        ) {
            BasicText("Volume", style = PanelText.row)
            VolumeBar(settings.soundVolume, enabled = settings.soundsOn)
        }
        PanelRow(remember { FocusRequester() }, onSelect = onOpenWallpapers) {
            BasicText("Wallpaper", style = PanelText.row)
            BasicText("›", style = PanelText.value)
        }
        PanelRow(remember { FocusRequester() }, onSelect = { showIconPacks = true }) {
            BasicText("Icon pack", style = PanelText.row)
            BasicText("${iconPackName.orEmpty()}  ›", style = PanelText.value, maxLines = 1)
        }
        PanelRow(remember { FocusRequester() }, onSelect = { showDiagnostics = true }) {
            BasicText("About & diagnostics", style = PanelText.row)
            BasicText("${appVersionName(context)}  ›", style = PanelText.value)
        }
    }
    if (showDiagnostics) DiagnosticsPanel(onShowWelcome = onShowWelcome, onDismiss = { showDiagnostics = false })
    if (showIconPacks) {
        IconPackPanel(
            iconPacks = iconPacks,
            current = iconPacks.effective(settings.iconPack),
            onPick = { pack -> onChange(settings.copy(iconPack = pack ?: "")) },
            onDismiss = { showIconPacks = false },
        )
    }
}

@Composable
private fun VolumeBar(level: Int, enabled: Boolean) {
    val alpha = if (enabled) 1f else 0.35f
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("‹", style = PanelText.row.copy(color = Color.White.copy(alpha = 0.5f * alpha)))
        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(LauncherSettings.MAX_VOLUME) { i ->
                val lit = i < level
                Box(
                    Modifier
                        .width(9.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = (if (lit) 0.9f else 0.18f) * alpha)),
                )
            }
        }
        BasicText("›", style = PanelText.row.copy(color = Color.White.copy(alpha = 0.5f * alpha)))
    }
}
