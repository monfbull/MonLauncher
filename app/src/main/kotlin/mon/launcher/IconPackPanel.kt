package mon.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp

/** Settings → Icon pack. Without the Projectivy pack installed, shows where to get it. */
@Composable
fun IconPackPanel(iconPacks: IconPacks, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val packs = remember { iconPacks.installed() }
    val hasProjectivy = packs.any { it.packageName == IconPacks.PROJECTIVY_PACK }
    GlassPanel("Icon pack", width = if (hasProjectivy) 440.dp else 680.dp, onDismiss = onDismiss) {
        val requesters = remember(packs) { List(packs.size + 1) { FocusRequester() } }
        val selected = packs.indexOfFirst { it.packageName == current } + 1 // index 0 is App icons
        LaunchedEffect(Unit) { awaitFocus { requesters[selected] } }
        Choice("App icons", current == null, requesters[0]) { onPick(null) }
        packs.forEachIndexed { i, pack ->
            Choice(pack.label, pack.packageName == current, requesters[i + 1]) { onPick(pack.packageName) }
        }
        if (!hasProjectivy) {
            Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(IconPacks.PROJECTIVY_PACK_PAGE, Modifier.size(150.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    PanelNote("For logos made for TV, get the free Projectivy Icon Pack:")
                    PanelNote(IconPacks.PROJECTIVY_PACK_PAGE, Modifier.padding(vertical = 4.dp), style = PanelText.row)
                    PanelNote("Once it's installed, it shows up here.")
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, chosen: Boolean, requester: FocusRequester, onSelect: () -> Unit) {
    PanelRow(requester, onSelect = onSelect) {
        BasicText(label, style = PanelText.row)
        if (chosen) BasicText("✓", style = PanelText.value)
    }
}
