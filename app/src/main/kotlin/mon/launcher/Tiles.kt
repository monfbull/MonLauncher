package mon.launcher

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mon.launcher.core.Tile

private val tileShape = RoundedCornerShape(TILE_CORNER)

/** Focus within this time of a tile appearing is initial focus and isn't animated. */
private const val INITIAL_FOCUS_MS = 600L

/** An app tile showing its logo, icon or banner. */
@Composable
fun AppTile(
    tile: Tile,
    size: DpSize,
    images: TileImages,
    requester: FocusRequester,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image by produceState(images.peek(tile), tile) { value = images.get(tile) }
    FocusableTile(size, requester, onClick, onLongPress, modifier, opensApp = true) {
        TileContent(tile, image, size)
    }
}

@Composable
private fun TileContent(tile: Tile, art: TileArt?, size: DpSize) {
    art ?: return
    if (art.fill) {
        Image(art.image, tile.label, Modifier.fillMaxSize().clip(tileShape), contentScale = ContentScale.Crop)
        return
    }
    art.image.let {
        Image(
            it,
            tile.label,
            Modifier.fillMaxSize().padding(horizontal = size.width * 0.15f, vertical = size.height * 0.2f),
            contentScale = ContentScale.Fit,
        )
    }
}

/** A tile showing only text. */
@Composable
fun TextTile(
    text: String,
    size: DpSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    requester: FocusRequester = remember { FocusRequester() },
) {
    FocusableTile(size, requester, onClick, onLongPress = {}, modifier) {
        BasicText(
            text,
            style = TextStyle(color = Color.White, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, textAlign = TextAlign.Center),
        )
    }
}

/** A photo filling the tile, e.g. a wallpaper thumbnail. [image] is null while loading. */
@Composable
fun PhotoTile(
    image: ImageBitmap?,
    description: String,
    size: DpSize,
    requester: FocusRequester,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableTile(
        size, requester, onClick, onLongPress, modifier,
        surface = { lit -> tileSurface(lit, TILE_CORNER, rimBright = 0.7f) },
    ) {
        image?.let { Image(it, description, Modifier.fillMaxSize().clip(tileShape), contentScale = ContentScale.Crop) }
    }
}

/** The settings button in the top-left corner. */
@Composable
fun SettingsButton(
    backdrop: ImageBitmap?,
    screen: IntSize,
    requester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val corner = SETTINGS_BUTTON.width / 2
    FocusableTile(
        SETTINGS_BUTTON, requester, { onClick() }, onLongPress = {}, modifier,
        corner = corner,
        surface = { lit -> buttonGlass(backdrop, screen, lit, corner) },
    ) {
        Image(painterResource(R.drawable.ic_settings), "Settings", Modifier.size(26.dp), alpha = 0.9f)
    }
}

/**
 * Focusable tile: scales up and lifts when focused, dips while OK is held, and opens the long-press
 * menu when OK is held long enough.
 */
@Composable
private fun FocusableTile(
    size: DpSize,
    requester: FocusRequester,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Dp = TILE_CORNER,
    opensApp: Boolean = false,
    /** Background drawing, given the focus amount (0..1). */
    surface: Modifier.(lit: Float) -> Modifier = { lit -> tileSurface(lit, corner) },
    content: @Composable BoxScope.() -> Unit,
) {
    val sounds = LocalSounds.current
    var focused by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    // Initial focus when a screen opens snaps instead of animating.
    val shownAt = remember { SystemClock.uptimeMillis() }
    val litAnim = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        val target = if (focused) 1f else 0f
        if (focused && SystemClock.uptimeMillis() - shownAt < INITIAL_FOCUS_MS) litAnim.snapTo(target)
        else litAnim.animateTo(target, tween(180))
    }
    val lit = litAnim.value
    val dip by animateFloatAsState(if (pressing && !longPressed) 0.93f else 1f, tween(110), label = "tileDip")

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val scale = (1f + 0.06f * lit) * dip
                scaleX = scale
                scaleY = scale
            }
            .surface(lit)
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !focused) sounds?.focusMoved()
                focused = it.isFocused
                if (!it.isFocused) {
                    pressing = false
                    longPressed = false
                }
            }
            .onKeyEvent { event ->
                if (!event.isSelect) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            pressing = true
                            longPressed = false
                        } else if (event.nativeKeyEvent.isLongPress && pressing && !longPressed) {
                            longPressed = true
                            onLongPress()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (pressing && !longPressed) {
                            if (opensApp) sounds?.opened() else sounds?.clicked()
                            onClick()
                        }
                        pressing = false
                        longPressed = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
