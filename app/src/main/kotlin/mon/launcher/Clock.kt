package mon.launcher

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    // Rebuilt every minute to pick up changes to the time format, locale, or time zone.
    val formats = remember(now) {
        val locale = Locale.getDefault()
        val is24Hour = DateFormat.is24HourFormat(context)
        Triple(
            SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", locale),
            if (is24Hour) null else SimpleDateFormat("a", locale),
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale),
        )
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 2f), 16f)
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                formats.first.format(now),
                style = TextStyle(
                    color = Color.White,
                    fontFamily = InterDisplay,
                    fontWeight = FontWeight.Light,
                    fontSize = 66.sp,
                    letterSpacing = (-1.5).sp,
                    shadow = shadow,
                ),
            )
            formats.second?.let { amPm ->
                BasicText(
                    amPm.format(now),
                    Modifier.padding(start = 6.dp, bottom = 13.dp),
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.85f),
                        fontFamily = Inter,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        shadow = shadow,
                    ),
                )
            }
        }
        BasicText(
            formats.third.format(now),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.9f),
                fontFamily = Inter,
                fontWeight = FontWeight.Medium,
                fontSize = 17.sp,
                letterSpacing = 0.2.sp,
                shadow = shadow,
            ),
        )
    }
}
