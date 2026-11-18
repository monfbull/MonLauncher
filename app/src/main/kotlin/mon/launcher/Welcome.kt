package mon.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Whether the welcome tips have been shown. */
class WelcomeStore(context: Context) {
    private val prefs = context.getSharedPreferences("welcome", Context.MODE_PRIVATE)

    val seen: Boolean get() = prefs.getBoolean(SEEN, false)

    fun markSeen() = prefs.edit().putBoolean(SEEN, true).apply()

    private companion object {
        const val SEEN = "seen"
    }
}

/** First-run tips: the main remote controls and where things are. Shown once; reopened from About. */
@Composable
fun WelcomePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hasIconPack = remember { IconPacks(context).installed().isNotEmpty() }
    GlassPanel("Welcome to MonLauncher", width = 500.dp, onDismiss = onDismiss) {
        val done = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFocus { done } }
        Tip("↓", "See all your apps")
        Tip("Hold OK", "Add an app to the dock, move it, or hide it")
        Tip("↑", "Open settings")
        PanelNote(
            "Hidden apps are to the left of your app list: press ← on the first app in the bottom row.",
            Modifier.padding(top = 6.dp),
        )
        if (!hasIconPack) {
            PanelNote("For logos made for TV, install the free Projectivy Icon Pack (Settings → Icon pack).", Modifier.padding(top = 6.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            PanelButton("Got it", done, onSelect = onDismiss)
        }
    }
}

@Composable
private fun Tip(key: String, text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp)) {
            BasicText(
                key,
                Modifier
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                style = PanelText.value,
            )
        }
        BasicText(text, style = PanelText.row)
    }
}
