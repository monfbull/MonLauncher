package mon.launcher

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** How long the phone pages stay available, in minutes. */
const val LAN_PAGE_MINUTES = 10

@Composable
fun DiagnosticsPanel(onShowWelcome: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var crashCount by remember { mutableIntStateOf(CrashLog.reports(context).size) }
    var sharing by remember { mutableStateOf(false) }
    // Same width as the Settings panel it opens over.
    GlassPanel("About & diagnostics", width = 440.dp, onDismiss = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFocus { first } }
        InfoRow("Version", appVersionName(context))
        InfoRow("Device", deviceName())
        InfoRow("Android", Build.VERSION.RELEASE)
        InfoRow("Crash reports", if (crashCount == 0) "None" else "$crashCount")
        PanelRow(first, onSelect = { sharing = true }) {
            BasicText("Send diagnostics", style = PanelText.row)
            BasicText("›", style = PanelText.value)
        }
        PanelRow(remember { FocusRequester() }, onSelect = onShowWelcome) {
            BasicText("Show welcome tips", style = PanelText.row)
        }
        if (crashCount > 0) {
            PanelRow(remember { FocusRequester() }, onSelect = {
                CrashLog.clear(context)
                crashCount = 0
                first.requestFocus() // this row is removed, so move focus
            }) {
                BasicText("Clear crash reports", style = PanelText.row)
            }
        }
    }
    if (sharing) DiagnosticsSharePanel(onDismiss = { sharing = false })
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = PanelText.row)
        BasicText(value, Modifier.padding(start = 16.dp), style = PanelText.value, maxLines = 1)
    }
}

/** Serves the report on the local network while shown. */
@Composable
fun DiagnosticsSharePanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val server = remember {
        LanServer { request ->
            when {
                request.method == "GET" && request.path == "/" ->
                    LanServer.Response(200, "text/html; charset=utf-8", reportPage(DiagnosticsReport.build(context)).toByteArray())
                request.method == "GET" && request.path == "/report.txt" ->
                    LanServer.Response(
                        200, "text/plain; charset=utf-8", DiagnosticsReport.build(context).toByteArray(),
                        mapOf("Content-Disposition" to "attachment; filename=\"monlauncher-report.txt\""),
                    )
                else -> LanServer.Response.text(404, "Not found")
            }
        }
    }
    var address by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(true) }
    DisposableEffect(server) {
        started = server.start()
        address = server.address
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) {
        delay(LAN_PAGE_MINUTES * 60_000L)
        onDismiss()
    }
    LanAddressPanel(
        title = "Send diagnostics",
        address = address,
        failed = !started,
        instructions = "Scan with a phone camera, or on any phone or computer on the same Wi-Fi open:",
        footer = "Then download the report and send it to the developer. Back closes this page.",
        onDismiss = onDismiss,
    )
}

/** [failed] means the server couldn't start. */
@Composable
fun LanAddressPanel(
    title: String,
    address: String?,
    failed: Boolean,
    instructions: String,
    footer: String,
    onDismiss: () -> Unit,
) {
    GlassPanel(title, width = 680.dp, onDismiss = onDismiss) {
        if (address != null) {
            Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(address, Modifier.size(196.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    PanelNote(instructions)
                    PanelNote(address, Modifier.padding(vertical = 6.dp), style = PanelText.heading.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium))
                    PanelNote(footer)
                }
            }
        } else if (failed) {
            PanelNote(
                "The page couldn't be started. Another app may be using port ${LanServer.PORT}. Close this and try again.",
                Modifier.padding(vertical = 4.dp),
            )
        } else {
            PanelNote("The TV isn't connected to a network, so this can't be opened on a phone.", Modifier.padding(vertical = 4.dp))
        }
    }
}

fun deviceName(): String =
    if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "${Build.MANUFACTURER} ${Build.MODEL}"

private fun reportPage(report: String): String {
    val escaped = report.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MonLauncher diagnostics</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 20px 16px; background: #16161a; color: #fff; font: 16px/1.45 system-ui, sans-serif; }
  a.button { display: inline-block; padding: 12px 18px; border-radius: 12px; background: #fff; color: #16161a; font-weight: 600; text-decoration: none; }
  p { color: rgba(255,255,255,.65); }
  pre { margin-top: 18px; padding: 14px; border-radius: 12px; background: rgba(255,255,255,.07); font-size: 12px; white-space: pre-wrap; word-break: break-word; }
</style></head>
<body>
  <h1>MonLauncher diagnostics</h1>
  <p>Download the report and send it to the developer along with what happened.</p>
  <a class="button" href="report.txt" download>Download report</a>
  <pre>$escaped</pre>
</body></html>
"""
}

/**
 * Shown instead of the home screen after a crash loop.
 * Skips the wallpaper, logos and app list, the most likely causes of a crash on start.
 */
@Composable
fun RecoveryScreen(onTryAgain: () -> Unit, onReset: () -> Unit) {
    val context = LocalContext.current
    var sharing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF16161A)))
    GlassPanel(null, width = 620.dp, onDismiss = {}, backSound = false) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { awaitFocus { first } }
        PanelNote("MonLauncher stopped several times in a row", style = PanelText.heading)
        PanelNote("Sending diagnostics to the developer helps get this fixed.", Modifier.padding(bottom = 8.dp))
        PanelRow(first, onSelect = onTryAgain) { BasicText("Try again", style = PanelText.row) }
        PanelRow(remember { FocusRequester() }, onSelect = { sharing = true }) {
            BasicText("Send diagnostics to a phone", style = PanelText.row)
            BasicText("›", style = PanelText.value)
        }
        PanelRow(remember { FocusRequester() }, onSelect = onReset) {
            Column {
                BasicText("Reset MonLauncher", style = PanelText.row)
                BasicText("Forgets the dock, hidden apps and settings. Wallpaper photos are kept.", style = PanelText.note)
            }
        }
        PanelRow(remember { FocusRequester() }, onSelect = {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }) { BasicText("Open Android settings", style = PanelText.row) }
    }
    if (sharing) DiagnosticsSharePanel(onDismiss = { sharing = false })
}
