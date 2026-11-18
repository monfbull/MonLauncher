package mon.launcher

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

private val THUMB = DpSize(188.dp, 106.dp)
private const val COLUMNS = 4
/** Space under each thumbnail for the "Current" label. */
private val CAPTION = 32.dp

private val titleStyle = TextStyle(color = Color.White, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
private val sectionStyle = TextStyle(color = Color.White.copy(alpha = 0.7f), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)
private val noteStyle = PanelText.note

/**
 * Settings → Wallpaper: pick from the library or Download, upload from a phone, or long-press to
 * save a copy or delete. [current] is the file name in use; [onChanged] is called when files are added or removed.
 */
@Composable
fun WallpaperManager(
    library: WallpaperLibrary,
    current: String?,
    backdrop: ImageBitmap?,
    onPick: (File) -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val lists by produceState<Pair<List<File>, List<File>>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { library.list() to library.inDownloads() }
    }
    var uploading by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    val requesters = remember { mutableStateMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    // Focus the current wallpaper when the list first appears.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(lists) {
        val (mine, _) = lists ?: return@LaunchedEffect
        if (placed) return@LaunchedEffect
        placed = true
        awaitFocus { requester(mine.firstOrNull { it.name == current }?.path ?: UPLOAD_KEY) }
    }

    val backSounds = LocalSounds.current
    Dialog(onDismissRequest = { backSounds?.backed(); onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NoSystemDim()
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
        Box(Modifier.fillMaxSize().arrowTicks().graphicsLayer { alpha = appear.value }.background(Color.Black)) {
            backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))

            // Wait for the list before showing focusable items, so focus starts on the current wallpaper.
            val (mine, downloads) = lists ?: return@Box
            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUMNS),
                contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 36.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = (1f - appear.value) * 28.dp.toPx() },
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        BasicText("Wallpaper", style = titleStyle)
                        BasicText(
                            "Pick one to use it. Hold OK on one of yours to save a copy or delete it.",
                            Modifier.padding(top = 4.dp),
                            style = noteStyle,
                        )
                    }
                }
                item(key = UPLOAD_KEY) {
                    TextTile(
                        "Upload from phone\nor computer",
                        THUMB,
                        onClick = { uploading = true },
                        requester = requester(UPLOAD_KEY),
                    )
                }
                section("Your wallpapers")
                if (mine.isEmpty()) note("None yet. Upload one, or pick one from Downloads below.")
                items(mine, key = { it.path }) { file ->
                    Thumbnail(
                        file = file,
                        library = library,
                        current = file.name == current,
                        requester = requester(file.path),
                        onClick = { onPick(file) },
                        onLongPress = { menuFor = file },
                    )
                }
                if (downloads.isNotEmpty()) {
                    section("From Downloads")
                    items(downloads, key = { "dl:" + it.path }) { file ->
                        Thumbnail(
                            file = file,
                            library = library,
                            current = false,
                            requester = requester("dl:" + file.path),
                            onClick = onClick@{
                                if (busy) return@onClick
                                busy = true
                                scope.launch {
                                    val copy = withContext(Dispatchers.IO) { runCatching { library.import(file) }.getOrNull() }
                                    busy = false
                                    if (copy != null) {
                                        onPick(copy)
                                        onChanged()
                                        refresh++
                                        awaitFocus { requester(copy.path) }
                                    }
                                }
                            },
                            onLongPress = {},
                        )
                    }
                }
            }
        }

        if (uploading) {
            UploadPanel(
                library = library,
                onUploaded = { file, apply ->
                    scope.launch {
                        uploading = false
                        refresh++
                        onChanged()
                        if (apply) onPick(file)
                        awaitFocus { requester(file.path) }
                    }
                },
                onDismiss = { uploading = false },
            )
        }

        menuFor?.let { file ->
            ChoiceMenu(
                title = file.name,
                choices = listOf("Save a copy to Download", "Delete from the TV", "Cancel"),
                onDismiss = { menuFor = null },
                onPick = { choice ->
                    menuFor = null
                    if (choice == 0) {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { library.exportToDownloads(file) }
                            val message = when (result) {
                                WallpaperLibrary.Export.Saved -> "Saved to Download"
                                WallpaperLibrary.Export.AlreadyThere -> "Already in Download"
                                WallpaperLibrary.Export.Failed -> "Couldn't save to Download"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (choice == 1) {
                        scope.launch {
                            val gone = withContext(Dispatchers.IO) { library.delete(file) }
                            if (gone) {
                                onChanged()
                                refresh++
                                awaitFocus { requester(UPLOAD_KEY) }
                            }
                        }
                    }
                },
            )
        }
    }
}

private const val UPLOAD_KEY = "upload"

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.section(title: String) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        BasicText(title, Modifier.padding(top = 10.dp), style = sectionStyle)
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.note(text: String) {
    item(span = { GridItemSpan(maxLineSpan) }) { BasicText(text, style = noteStyle) }
}

@Composable
private fun Thumbnail(
    file: File,
    library: WallpaperLibrary,
    current: Boolean,
    requester: FocusRequester,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, file) { value = library.thumbnail(file) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PhotoTile(image, file.name, THUMB, requester, onClick, onLongPress)
        // The current wallpaper is labelled below the thumbnail rather than marked on it, so the label
        // isn't confused with focus.
        Row(
            Modifier.height(CAPTION).padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (current) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                BasicText("Current", style = PanelText.note.copy(color = Color.White, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** Renders [text] as a QR code with a white quiet zone. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier.clip(RoundedCornerShape(TILE_CORNER)).background(Color.White)) {
        val quiet = 2 // quiet zone, in modules
        val cells = matrix.width + quiet * 2
        val cell = size.minDimension / cells
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) {
                drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Runs the upload server while shown. It stops after one upload or when the panel closes. */
@Composable
private fun UploadPanel(
    library: WallpaperLibrary,
    onUploaded: (File, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val wallpaper = LocalWallpaper.current
    val server = remember {
        UploadServer(
            library,
            // The heavy blur is tiny, so it only carries colour.
            backdrop = { wallpaper?.heavy?.let { png -> ByteArrayOutputStream().also { png.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() } },
            onUploaded = onUploaded,
        )
    }
    var address by remember { mutableStateOf<String?>(null) }
    DisposableEffect(server) {
        server.start()
        address = server.address
        onDispose { server.stop() }
    }
    LaunchedEffect(Unit) {
        delay(LAN_PAGE_MINUTES * 60_000L)
        onDismiss()
    }
    LanAddressPanel(
        title = "Upload a wallpaper",
        address = address,
        instructions = "Scan with a phone camera, or on any phone or computer on the same Wi-Fi open:",
        footer = "Waiting for a photo… This closes by itself once it arrives. Back cancels.",
        onDismiss = onDismiss,
    )
}
