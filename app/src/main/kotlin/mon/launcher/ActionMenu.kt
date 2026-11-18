package mon.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import mon.launcher.core.Action

/** Long-press menu for an app. */
@Composable
fun ActionMenu(title: String, actions: List<Action>, onDismiss: () -> Unit, onPick: (Action) -> Unit) {
    ChoiceMenu(title, actions.map { it.label() }, onDismiss) { onPick(actions[it]) }
}

/** A menu of [choices]; [onPick] receives the chosen index. */
@Composable
fun ChoiceMenu(title: String, choices: List<String>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    GlassPanel(title, width = 320.dp, onDismiss = onDismiss) {
        val requesters = remember(choices) { choices.map { FocusRequester() } }
        LaunchedEffect(Unit) { awaitFocus { requesters.firstOrNull() } }
        choices.forEachIndexed { i, choice ->
            PanelChoice(choice, requesters[i]) { onPick(i) }
        }
    }
}

private fun Action.label() = when (this) {
    Action.AddToDock -> "Add to dock"
    Action.RemoveFromDock -> "Remove from dock"
    Action.Move -> "Move"
    Action.Hide -> "Hide"
    Action.Unhide -> "Unhide"
    Action.AppInfo -> "App info"
    Action.Uninstall -> "Uninstall"
}
