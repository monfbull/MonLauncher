package mon.launcher

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Inter (SIL Open Font License). */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Inter Display, for large text. */
val InterDisplay = FontFamily(Font(R.font.inter_display_light, FontWeight.Light))

val DOCK_TILE = DpSize(124.dp, 72.dp)
val GRID_TILE = DpSize(150.dp, 88.dp)
val TILE_CORNER = 12.dp
val SETTINGS_BUTTON = DpSize(52.dp, 52.dp)
/** Padding inside the dock; leaves room for a focused tile to scale up. */
val DOCK_PADDING = 14.dp

/** Tile corner radius plus the dock padding, so the corners are concentric. */
val DOCK_CORNER = TILE_CORNER + DOCK_PADDING
const val GRID_COLUMNS = 5

/** True for the remote's OK/select keys. */
val KeyEvent.isSelect: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
