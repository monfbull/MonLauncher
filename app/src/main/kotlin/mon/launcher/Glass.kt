package mon.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Fill used when there's no wallpaper to blur. */
private val NO_WALLPAPER_GLASS = Color(0xFF2A2B31)

private val vivid = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.1f) })

/** Reused clip shape for [dockGlass]; see [shadowCard]. */
private val glassClip = Path()

/**
 * Frosted glass background: the blurred wallpaper aligned to the screen, darkened by [tint],
 * with a top sheen and a thin rim scaled by [rimStrength].
 */
fun Modifier.dockGlass(
    backdrop: ImageBitmap?,
    screen: IntSize,
    corner: Dp,
    rimStrength: Float = 1f,
    tint: Float = 0.18f,
): Modifier = composed {
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        val radius = CornerRadius(corner.toPx().coerceAtMost(size.height / 2))
        glassClip.reset()
        glassClip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius))
        clipPath(glassClip) {
            if (backdrop != null && screen != IntSize.Zero) {
                drawImage(
                    backdrop,
                    dstOffset = IntOffset(-origin.x.toInt(), -origin.y.toInt()),
                    dstSize = screen,
                    colorFilter = vivid,
                )
            } else {
                // Without a wallpaper the glass needs an opaque base, or content behind it shows through.
                drawRect(NO_WALLPAPER_GLASS)
            }
            drawRect(Color.Black.copy(alpha = tint))
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.35f to Color.Transparent))
        }
        val width = 1.dp.toPx()
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f * rimStrength), 1f to Color.White.copy(alpha = 0.10f * rimStrength)),
            topLeft = Offset(width / 2, width / 2),
            size = size.copy(width = size.width - width, height = size.height - width),
            cornerRadius = CornerRadius(radius.x - width / 2),
            style = Stroke(width),
        )
    }
}

// Slightly translucent so the dock shows through the tiles.
private val graphiteTop = Color(0xFF5C5C63).copy(alpha = 0.38f)
private val graphiteBottom = Color(0xFF3E3E44).copy(alpha = 0.38f)

/** Tile background. [lit] (0..1) adds the focus sheen and rim. */
fun Modifier.tileSurface(lit: Float, corner: Dp, rimBright: Float = 0.32f): Modifier = drawWithContent {
    val radius = CornerRadius(corner.toPx())
    shadowOutside(
        radius,
        blur = (10.dp + 14.dp * lit).toPx(),
        dropY = (3.dp + 7.dp * lit).toPx(),
        alpha = 0.32f + 0.18f * lit,
    )
    drawRoundRect(Brush.verticalGradient(0f to graphiteTop, 1f to graphiteBottom), cornerRadius = radius)
    drawContent()
    if (lit > 0f) {
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f * lit), 0.45f to Color.Transparent),
            cornerRadius = radius,
        )
        rim(radius, bright = rimBright * lit, dim = 0.10f * lit, width = 1.dp.toPx())
    }
}

// Shadow drawing objects, reused instead of allocated on every frame of every tile. Drawing only
// happens on the main thread, and the canvas records copies, so sharing them is safe.
private val shadowCard = Path()
private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

/** Blur filters by radius in whole pixels. Rounding keeps the cache small while focus animates. */
private val shadowBlurs = HashMap<Int, android.graphics.BlurMaskFilter>()

/** Shadow clipped to outside the card, so it doesn't show through translucent tiles. */
private fun DrawScope.shadowOutside(radius: CornerRadius, blur: Float, dropY: Float, alpha: Float) {
    shadowCard.reset()
    shadowCard.addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius))
    // A blur radius of 0 is invalid, so the smallest is 1 pixel.
    val blurPx = blur.roundToInt().coerceAtLeast(1)
    shadowPaint.color = android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0)
    shadowPaint.maskFilter = shadowBlurs.getOrPut(blurPx) {
        android.graphics.BlurMaskFilter(blurPx.toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    clipPath(shadowCard, ClipOp.Difference) {
        drawIntoCanvas {
            it.nativeCanvas.drawRoundRect(0f, dropY, size.width, size.height + dropY, radius.x, radius.y, shadowPaint)
        }
    }
}

/** Background that only appears when focused, e.g. panel rows. */
fun Modifier.focusSurface(lit: Float, corner: Dp): Modifier = drawWithContent {
    if (lit > 0f) {
        val radius = CornerRadius(corner.toPx())
        shadowOutside(radius, blur = (12.dp * lit).toPx(), dropY = (5.dp * lit).toPx(), alpha = 0.38f * lit)
        drawRoundRect(
            Brush.verticalGradient(0f to graphiteTop.copy(alpha = 0.75f * lit), 1f to graphiteBottom.copy(alpha = 0.75f * lit)),
            cornerRadius = radius,
        )
        drawContent()
        drawRoundRect(
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.08f * lit), 0.45f to Color.Transparent),
            cornerRadius = radius,
        )
        rim(radius, bright = 0.32f * lit, dim = 0.10f * lit, width = 1.dp.toPx())
    } else {
        drawContent()
    }
}

/** Glass background for a standalone button, e.g. settings. */
fun Modifier.buttonGlass(backdrop: ImageBitmap?, screen: IntSize, lit: Float, corner: Dp): Modifier =
    drawWithContent {
        val radius = CornerRadius(corner.toPx())
        if (lit > 0f) {
            shadowOutside(radius, blur = (14.dp * lit).toPx(), dropY = (7.dp * lit).toPx(), alpha = 0.45f * lit)
        }
        drawContent()
        if (lit > 0f) {
            drawRoundRect(Color.White.copy(alpha = 0.10f * lit), cornerRadius = radius)
            rim(radius, bright = 0.32f * lit, dim = 0.10f * lit, width = 1.dp.toPx())
        }
    }.dockGlass(backdrop, screen, corner, rimStrength = 1.6f) // brighter rim than the dock, as it sits alone on the wallpaper

/** Rim highlight, brightest at the top-left. */
private fun DrawScope.rim(radius: CornerRadius, bright: Float, dim: Float, width: Float) {
    val inset = width / 2
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = bright),
            0.5f to Color.White.copy(alpha = dim),
            1f to Color.White.copy(alpha = (bright + dim) / 2.5f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        topLeft = Offset(inset, inset),
        size = size.copy(width = size.width - width, height = size.height - width),
        cornerRadius = CornerRadius(radius.x - inset, radius.y - inset),
        style = Stroke(width),
    )
}
