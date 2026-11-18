package mon.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * UI sounds, played through a SoundPool for low latency.
 *
 * Focus ticks only play after an arrow press, so programmatic focus changes are silent.
 */
class Sounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val tick = pool.load(context, R.raw.tick, 1)
    private val click = pool.load(context, R.raw.click, 1)
    private val open = pool.load(context, R.raw.open, 1)
    private val back = pool.load(context, R.raw.back, 1)
    private var lastArrow = 0L

    /** Mute and volume from [LauncherSettings]. */
    var settings = LauncherSettings()

    /** Volume as 0..1 on a squared curve, so steps sound even. */
    private val gain: Float
        get() = if (!settings.soundsOn) 0f else (settings.soundVolume / LauncherSettings.MAX_VOLUME.toFloat()).let { it * it }

    /** Call on each arrow key press, before focus moves. */
    fun arrowPressed() {
        lastArrow = SystemClock.uptimeMillis()
    }

    /** Plays the tick if focus moved because of a recent arrow press. */
    fun focusMoved() {
        // Long enough to cover the grid opening, which focuses its first tile a few frames later.
        if (SystemClock.uptimeMillis() - lastArrow < 400) {
            lastArrow = 0L
            play(tick)
        }
    }

    /** OK on buttons, menu items and settings. */
    fun clicked() = play(click, priority = 1)

    /** OK on an app tile. */
    fun opened() = play(open, priority = 1)

    /** Back closed a panel or overlay, or cancelled a move. */
    fun backed() = play(back, priority = 1)

    /** A dock app moved one place. */
    fun stepped() = play(tick)

    /** Plays a tick at the current volume. */
    fun preview() = play(tick)

    private fun play(sound: Int, priority: Int = 0) {
        val gain = gain
        if (gain > 0f) pool.play(sound, gain, gain, priority, 0, 1f)
    }

    fun release() = pool.release()
}

/**
 * Reports arrow presses to [LocalSounds]. Needed at the root of each dialog, since dialogs are
 * separate windows and their key events don't reach the activity.
 */
fun Modifier.arrowTicks(): Modifier = composed {
    val sounds = LocalSounds.current
    onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key in ARROWS) sounds?.arrowPressed()
        false
    }
}

private val ARROWS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)

/** Null in previews and tests. */
val LocalSounds = staticCompositionLocalOf<Sounds?> { null }
