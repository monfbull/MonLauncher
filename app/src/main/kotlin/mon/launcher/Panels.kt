package mon.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Current wallpaper, for drawing panel glass. */
val LocalWallpaper = compositionLocalOf<WallpaperImages?> { null }

/**
 * Disables the system dialog dim, which appears before the content and causes a flash.
 * Panels draw their own dim. Call first thing inside a Dialog.
 */
@Composable
fun NoSystemDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0f) }
}

/** Text styles used by panels. */
object PanelText {
    val title = TextStyle(color = Color.White.copy(alpha = 0.65f), fontFamily = Inter, fontSize = 14.sp)
    val heading = TextStyle(color = Color.White, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
    val row = TextStyle(color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 17.sp)
    val value = row.copy(color = Color.White.copy(alpha = 0.7f))
    val note = TextStyle(color = Color.White.copy(alpha = 0.6f), fontFamily = Inter, fontSize = 15.sp)
}

/** Row corner radius, matching the tiles so rows sit concentric with the panel corners. */
private val ROW_CORNER = TILE_CORNER

/** Centered glass panel used for settings screens and menus. Back calls [onDismiss]. */
@Composable
fun GlassPanel(
    title: String?,
    width: Dp,
    onDismiss: () -> Unit,
    /** Play the back sound on dismiss. Off where Back does nothing. */
    backSound: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sounds = LocalSounds.current
    Dialog(onDismissRequest = { if (backSound) sounds?.backed(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Full-screen so the glass lines up with the wallpaper behind it.
        NoSystemDim()
        var screen by remember { mutableStateOf(IntSize.Zero) }
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        val backdrop = LocalWallpaper.current?.heavy?.asImage()

        Box(
            Modifier
                .fillMaxSize()
                .arrowTicks()
                .onSizeChanged { screen = it }
                .graphicsLayer { alpha = appear.value }
                .background(Color.Black.copy(alpha = 0.40f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(width)
                    .graphicsLayer {
                        val scale = 0.96f + 0.04f * appear.value
                        scaleX = scale
                        scaleY = scale
                    }
                    .dockGlass(backdrop, screen, DOCK_CORNER, rimStrength = 1.3f, tint = 0.34f)
                    .padding(DOCK_PADDING),
            ) {
                title?.let {
                    BasicText(
                        it,
                        Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                        style = PanelText.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                content()
            }
        }
    }
}

/** A focusable panel row. OK calls [onSelect]; Left/Right call [onAdjust] with -1/+1 if set. */
@Composable
fun PanelRow(
    requester: FocusRequester,
    onSelect: (() -> Unit)? = null,
    onAdjust: ((Int) -> Unit)? = null,
    clickSound: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val sounds = LocalSounds.current
    var focused by remember { mutableStateOf(false) }
    // Ignore a release whose press started elsewhere, e.g. the OK that opened the panel.
    var pressed by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "rowLit")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .graphicsLayer {
                val scale = 1f + 0.02f * lit
                scaleX = scale
                scaleY = scale
            }
            .focusSurface(lit, ROW_CORNER)
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !focused) sounds?.focusMoved()
                focused = it.isFocused
                if (!it.isFocused) pressed = false
            }
            .onKeyEvent {
                when {
                    it.key == Key.DirectionLeft || it.key == Key.DirectionRight -> {
                        if (it.type == KeyEventType.KeyDown) onAdjust?.invoke(if (it.key == Key.DirectionLeft) -1 else 1)
                        true
                    }
                    it.isSelect -> {
                        if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) pressed = true
                        if (it.type == KeyEventType.KeyUp && pressed) {
                            pressed = false
                            if (onSelect != null && clickSound) sounds?.clicked()
                            onSelect?.invoke()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A centered action button: light at rest, white with dark text when focused. */
@Composable
fun PanelButton(text: String, requester: FocusRequester, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val sounds = LocalSounds.current
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "buttonLit")
    Box(
        modifier
            .graphicsLayer {
                val scale = 1f + 0.04f * lit
                scaleX = scale
                scaleY = scale
            }
            .background(lerp(Color.White.copy(alpha = 0.14f), Color.White, lit), RoundedCornerShape(ROW_CORNER))
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !focused) sounds?.focusMoved()
                focused = it.isFocused
                if (!it.isFocused) pressed = false
            }
            .onKeyEvent {
                if (!it.isSelect) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) pressed = true
                if (it.type == KeyEventType.KeyUp && pressed) {
                    pressed = false
                    sounds?.clicked()
                    onSelect()
                }
                true
            }
            .focusable()
            .padding(horizontal = 36.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = PanelText.row.copy(color = lerp(Color.White, Color(0xFF16161A), lit)))
    }
}

/** A menu row with a single label. */
@Composable
fun PanelChoice(text: String, requester: FocusRequester, onSelect: () -> Unit) {
    PanelRow(requester, onSelect) { BasicText(text, style = PanelText.row) }
}

/** Body text inside a panel, aligned with row text. */
@Composable
fun PanelNote(text: String, modifier: Modifier = Modifier, style: TextStyle = PanelText.note) {
    BasicText(text, modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = style)
}
