package mon.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mon.launcher.core.Action
import mon.launcher.core.DockMove
import mon.launcher.core.Focus
import mon.launcher.core.Home
import mon.launcher.core.Place
import mon.launcher.core.Tile
import java.io.File

private object HomeMotion {
    private val settle = FastOutSlowInEasing
    private val leave = FastOutLinearInEasing

    val backdropIn = fadeIn(tween(340, easing = LinearOutSlowInEasing))
    val backdropOut = fadeOut(tween(450, easing = LinearOutSlowInEasing))

    val tilesIn = fadeIn(tween(300, delayMillis = 50)) + slideInVertically(tween(370, delayMillis = 50, easing = settle)) { it / 14 }
    val tilesOut = fadeOut(tween(240, easing = leave)) + slideOutVertically(tween(290, easing = leave)) { it / 14 }

    val dockIn = fadeIn(tween(300, delayMillis = 160)) + slideInVertically(tween(380, delayMillis = 160, easing = settle)) { it / 2 }
    val dockOut = fadeOut(tween(180, easing = leave)) + slideOutVertically(tween(220, easing = leave)) { it / 2 }

    val chromeIn = fadeIn(tween(300, delayMillis = 160))

    val pan = tween<IntOffset>(460, easing = settle)
    val panFade = tween<Float>(360, easing = LinearOutSlowInEasing)
    val chromeOut = fadeOut(tween(180, easing = leave))
}

/** Padding that keeps a tile's shadow from being clipped while it fades. */
private val SHADOW_ROOM = 32.dp

private enum class Overlay {
    None, Grid, Hidden;

    val place get() = if (this == Hidden) Place.HiddenList else Place.Grid
}

private fun Home.tilesFor(overlay: Overlay) = if (overlay == Overlay.Hidden) hiddenApps else grid

/** First tile of the last row. Pressing Left on it opens the hidden apps page, if any apps are hidden. */
private fun secretIndex(tiles: List<Tile>): Int? =
    if (tiles.isEmpty()) null else (tiles.size - 1) / GRID_COLUMNS * GRID_COLUMNS

private data class MenuTarget(val tile: Tile, val place: Place)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    home: Home?,
    wallpaper: WallpaperImages?,
    images: TileImages,
    focusRequest: FocusRequest?,
    homePresses: Int,
    onFocusHandled: () -> Unit,
    onLaunch: (String, Place) -> Unit,
    onAction: (Action, String, Place) -> Unit,
    settings: LauncherSettings,
    onSettingsChange: (LauncherSettings) -> Unit,
    wallpapers: WallpaperLibrary,
    onPickWallpaper: (File) -> Unit,
    onWallpapersChanged: () -> Unit,
    onReorderDock: (List<String>) -> Unit,
    showWelcome: Boolean,
    onShowWelcome: () -> Unit,
    onWelcomeDone: () -> Unit,
) {
    var overlay by remember { mutableStateOf(Overlay.None) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showWallpapers by remember { mutableStateOf(false) }
    // Set when leaving the hidden page, so the grid can refocus the tile that opened it.
    var leftHidden by remember { mutableStateOf(false) }
    val settingsFocus = remember { FocusRequester() }
    // Last focused dock app, used when moving Down from the settings button.
    var lastDockApp by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val dockFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    val gridFocus = remember { mutableStateMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val hiddenState = rememberLazyGridState()
    val dockState = rememberLazyListState()
    val scrollMargin = with(LocalDensity.current) { 24.dp.toPx() }

    // On the first frame with data, open the focus target's overlay without animating.
    var placed by remember { mutableStateOf(false) }
    if (home != null && !placed) {
        placed = true
        overlay = when (focusRequest?.target) {
            is Focus.Grid, Focus.GridStart -> Overlay.Grid
            is Focus.Hidden -> Overlay.Hidden
            else -> Overlay.None
        }
    }

    // Back does nothing on the home screen. Overlays add their own handlers.
    BackHandler {}

    // Dock move mode: Left/Right reorder, OK saves, Back or Home cancels.
    val sounds = LocalSounds.current
    var moving by remember { mutableStateOf<DockMove?>(null) }
    BackHandler(enabled = moving != null) {
        sounds?.backed()
        moving = null
    }

    LaunchedEffect(homePresses) {
        if (homePresses > 0) {
            menu = null
            moving = null
            showSettings = false
            showWallpapers = false
            overlay = Overlay.None
        }
    }

    LaunchedEffect(focusRequest, home != null) {
        val target = focusRequest?.target ?: return@LaunchedEffect
        val current = home ?: return@LaunchedEffect
        when (target) {
            is Focus.Dock -> {
                overlay = Overlay.None
                dockState.reveal(current.dock.indexOfFirst { it.packageName == target.packageName })
                awaitFocus { dockFocus[target.packageName] }
            }
            is Focus.Grid -> {
                if (overlay == Overlay.None || current.hiddenApps.none { it.packageName == target.packageName }) {
                    overlay = Overlay.Grid
                }
                val state = if (overlay == Overlay.Hidden) hiddenState else gridState
                state.reveal(current.tilesFor(overlay).indexOfFirst { it.packageName == target.packageName })
                awaitFocus { gridFocus[target.packageName] }
            }
            is Focus.Hidden -> {
                overlay = Overlay.Hidden
                hiddenState.reveal(current.hiddenApps.indexOfFirst { it.packageName == target.packageName })
                awaitFocus { gridFocus[target.packageName] }
            }
            Focus.GridStart -> {
                overlay = Overlay.Grid
                gridState.scrollToItem(0)
                awaitFocus { current.grid.firstOrNull()?.let { gridFocus[it.packageName] } }
            }
        }
        onFocusHandled()
    }

    // When the overlay changes, focus its first tile (or the tile that opened the hidden page), or return to the dock.
    LaunchedEffect(overlay) {
        val current = home ?: return@LaunchedEffect
        if (focusRequest != null) return@LaunchedEffect
        if (overlay == Overlay.None) {
            val app = lastDockApp?.takeIf { app -> current.dock.any { it.packageName == app } }
                ?: (current.homeFocus as? Focus.Dock)?.packageName
            // With an empty dock, focus the settings button (Down from there opens the grid).
            awaitFocus { if (app != null) dockFocus[app] else settingsFocus }
        } else if (overlay == Overlay.Grid && leftHidden) {
            leftHidden = false
            val secret = secretIndex(current.grid) ?: 0
            gridState.reveal(secret)
            awaitFocus { current.grid.getOrNull(secret)?.let { gridFocus[it.packageName] } }
        } else {
            (if (overlay == Overlay.Hidden) hiddenState else gridState).scrollToItem(0)
            awaitFocus { current.tilesFor(overlay).firstOrNull()?.let { gridFocus[it.packageName] } }
        }
    }

    LaunchedEffect(moving?.order) {
        val move = moving ?: return@LaunchedEffect
        dockState.reveal(move.order.indexOf(move.packageName))
    }

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides remember(scrollMargin) { MinimalScroll(scrollMargin) },
        LocalWallpaper provides wallpaper,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { screen = it },
        ) {
            Crossfade(wallpaper?.sharp, animationSpec = tween(600), label = "wallpaper") { sharp ->
                sharp?.let { Image(it.asImage(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }

            Clock(Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 52.dp))

            AnimatedVisibility(
                visible = home != null && overlay == Overlay.None,
                // Offset by SHADOW_ROOM and padded back so the fade layer has room for the shadow.
                modifier = Modifier.align(Alignment.TopStart)
                    .padding(top = 44.dp - SHADOW_ROOM, start = 52.dp - SHADOW_ROOM),
                enter = HomeMotion.chromeIn,
                exit = HomeMotion.chromeOut,
            ) {
                SettingsButton(
                    backdrop = wallpaper?.glass?.asImage(),
                    screen = screen,
                    requester = settingsFocus,
                    onClick = { showSettings = true },
                    modifier = Modifier.onPreviewKeyEvent {
                        val current = home
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown && current != null) {
                            val target = lastDockApp?.takeIf { app -> current.dock.any { t -> t.packageName == app } }
                                ?: (current.homeFocus as? Focus.Dock)?.packageName
                            // Empty dock: Down opens the grid.
                            if (target != null) dockFocus[target]?.requestFocus() else overlay = Overlay.Grid
                            true
                        } else {
                            false
                        }
                    }.padding(SHADOW_ROOM),
                )
            }

            if (home != null) {
                AnimatedVisibility(
                    visible = overlay == Overlay.None,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = HomeMotion.dockIn,
                    exit = HomeMotion.dockOut,
                ) {
                    Dock(
                        tiles = moving?.let { move ->
                            val byPackage = home.dock.associateBy { it.packageName }
                            move.order.mapNotNull { byPackage[it] }
                        } ?: home.dock,
                        move = moving,
                        onMoveStep = { by ->
                            val move = moving
                            if (move != null) {
                                val next = move.step(by)
                                if (next != move) sounds?.stepped()
                                moving = next
                            }
                        },
                        onMoveDone = {
                            moving?.let { onReorderDock(it.order) }
                            sounds?.clicked()
                            moving = null
                        },
                        backdrop = wallpaper?.glass?.asImage(),
                        screen = screen,
                        state = dockState,
                        images = images,
                        requesters = dockFocus,
                        onOpenGrid = { overlay = Overlay.Grid },
                        onOpenSettingsButton = { settingsFocus.requestFocus() },
                        onFocused = { lastDockApp = it.packageName },
                        onLaunch = { onLaunch(it.packageName, Place.Dock) },
                        onMenu = { menu = MenuTarget(it, Place.Dock) },
                    )
                }

                AnimatedVisibility(
                    visible = overlay != Overlay.None,
                    enter = EnterTransition.None,
                    exit = ExitTransition.None,
                ) {
                    // Keep the last page visible while the overlay fades out.
                    var page by remember { mutableStateOf(Overlay.Grid) }
                    if (overlay != Overlay.None) page = overlay
                    AppsOverlay(
                        page = page,
                        grid = home.grid,
                        hidden = home.hiddenApps,
                        secretIndex = if (home.showHiddenEntry) secretIndex(home.grid) else null,
                        backdrop = wallpaper?.heavy?.asImage(),
                        gridState = gridState,
                        hiddenState = hiddenState,
                        images = images,
                        requesters = gridFocus,
                        onCloseGrid = { overlay = Overlay.None },
                        onOpenHidden = { overlay = Overlay.Hidden },
                        onLeaveHidden = {
                            leftHidden = true
                            overlay = Overlay.Grid
                        },
                        onLaunch = { onLaunch(it.packageName, overlay.place) },
                        onMenu = { menu = MenuTarget(it, overlay.place) },
                    )
                }
            }

            if (showSettings) {
                SettingsPanel(
                    settings,
                    onSettingsChange,
                    // Shown on top of Settings so closing it returns there.
                    onOpenWallpapers = { showWallpapers = true },
                    onShowWelcome = {
                        showSettings = false
                        onShowWelcome()
                    },
                    onDismiss = { showSettings = false },
                )
            }

            if (showWelcome && home != null) WelcomePanel(onDismiss = onWelcomeDone)

            if (showWallpapers) {
                WallpaperManager(
                    library = wallpapers,
                    // The version string starts with the file path.
                    current = wallpaper?.version?.substringBefore(':')?.let { File(it).name },
                    backdrop = wallpaper?.heavy?.asImage(),
                    onPick = onPickWallpaper,
                    onChanged = onWallpapersChanged,
                    onDismiss = { showWallpapers = false },
                )
            }

            menu?.let { target ->
                ActionMenu(
                    title = target.tile.label,
                    actions = home?.menu(target.tile.packageName, target.place).orEmpty(),
                    onDismiss = { menu = null },
                    onPick = { action ->
                        menu = null
                        if (action == Action.Move) {
                            moving = home?.startMove(target.tile.packageName)
                        } else {
                            onAction(action, target.tile.packageName, target.place)
                        }
                    },
                )
            }
        }
    }
}

/** Scrolls only when the focused item is cut off, and only far enough to show it plus [margin]. */
@OptIn(ExperimentalFoundationApi::class)
private class MinimalScroll(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start < 0 -> start
            end > containerSize -> end - containerSize
            else -> 0f
        }
    }
}

/** Retries for up to 30 frames, until the requester is attached. */
suspend fun awaitFocus(find: () -> FocusRequester?) {
    repeat(30) {
        val requester = find()
        if (requester != null && runCatching { requester.requestFocus() }.isSuccess) return
        delay(16)
    }
}

private suspend fun LazyListState.reveal(index: Int) {
    if (index >= 0 && layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}

private suspend fun LazyGridState.reveal(index: Int) {
    if (index >= 0 && layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
}

@Composable
private fun Dock(
    tiles: List<Tile>,
    move: DockMove?,
    onMoveStep: (Int) -> Unit,
    onMoveDone: () -> Unit,
    backdrop: ImageBitmap?,
    screen: IntSize,
    state: LazyListState,
    images: TileImages,
    requesters: MutableMap<String, FocusRequester>,
    onOpenGrid: () -> Unit,
    onOpenSettingsButton: () -> Unit,
    onFocused: (Tile) -> Unit,
    onLaunch: (Tile) -> Unit,
    onMenu: (Tile) -> Unit,
) {
    // Ignore the OK release left over from picking Move in the menu.
    var movePressed by remember { mutableStateOf(false) }
    LaunchedEffect(move == null) { movePressed = false }
    Box(
        Modifier
            .padding(bottom = 34.dp)
            .widthIn(max = 920.dp)
            .dockGlass(backdrop, screen, corner = DOCK_CORNER)
            .clip(RoundedCornerShape(DOCK_CORNER))
            .onPreviewKeyEvent {
                if (move != null) {
                    // In move mode the dock consumes all navigation keys.
                    when {
                        it.isSelect -> {
                            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) movePressed = true
                            if (it.type == KeyEventType.KeyUp && movePressed) {
                                movePressed = false
                                onMoveDone()
                            }
                        }
                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft -> onMoveStep(-1)
                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight -> onMoveStep(1)
                        it.key == Key.DirectionUp || it.key == Key.DirectionDown -> Unit
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> onOpenGrid()
                    Key.DirectionUp -> onOpenSettingsButton()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        contentAlignment = Alignment.Center,
    ) {
        LazyRow(
            state = state,
            modifier = Modifier.fadeScrolledEdges(state),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(horizontal = DOCK_PADDING, vertical = DOCK_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(tiles, key = { _, t -> t.packageName }) { index, tile ->
                val isMoving = move?.packageName == tile.packageName
                val dim by animateFloatAsState(if (move != null && !isMoving) 0.35f else 1f, tween(220), label = "dockDim")
                val lift by animateFloatAsState(if (isMoving) 1.08f else 1f, tween(220, easing = FastOutSlowInEasing), label = "dockLift")
                Box(
                    Modifier
                        .animateItem(placementSpec = tween(260, easing = FastOutSlowInEasing))
                        .graphicsLayer {
                            alpha = dim
                            scaleX = lift
                            scaleY = lift
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AppTile(
                        tile = tile,
                        size = DOCK_TILE,
                        images = images,
                        requester = requesters.requesterFor(tile.packageName),
                        onClick = { onLaunch(tile) },
                        onLongPress = { onMenu(tile) },
                        modifier = Modifier
                            .onFocusChanged { if (it.hasFocus) onFocused(tile) }
                            // Stop focus at the dock's ends so Left doesn't jump to the settings button.
                            .then(if (index == 0) Modifier.opensOn(Key.DirectionLeft) {} else Modifier)
                            .then(if (index == tiles.lastIndex) Modifier.opensOn(Key.DirectionRight) {} else Modifier),
                    )
                    MoveArrows(left = isMoving && move?.canMoveLeft == true, right = isMoving && move?.canMoveRight == true)
                }
            }
        }
    }
}

/**
 * The hidden apps page sits left of the grid.
 * Left on the first tile of the last row opens it.
 * Right at the end of a row returns from the hidden page, and Back returns from either page.
 */
@Composable
private fun AnimatedVisibilityScope.AppsOverlay(
    page: Overlay,
    grid: List<Tile>,
    hidden: List<Tile>,
    /** Null if no apps are hidden. */
    secretIndex: Int?,
    backdrop: ImageBitmap?,
    gridState: LazyGridState,
    hiddenState: LazyGridState,
    images: TileImages,
    requesters: MutableMap<String, FocusRequester>,
    onCloseGrid: () -> Unit,
    onOpenHidden: () -> Unit,
    onLeaveHidden: () -> Unit,
    onLaunch: (Tile) -> Unit,
    onMenu: (Tile) -> Unit,
) {
    val sounds = LocalSounds.current
    BackHandler {
        sounds?.backed()
        if (page == Overlay.Hidden) onLeaveHidden() else onCloseGrid()
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().animateEnterExit(HomeMotion.backdropIn, HomeMotion.backdropOut)) {
            backdrop?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
        }
        Box(Modifier.fillMaxSize().animateEnterExit(HomeMotion.tilesIn, HomeMotion.tilesOut)) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val toHidden = targetState == Overlay.Hidden
                    (slideInHorizontally(HomeMotion.pan) { w -> if (toHidden) -w else w } + fadeIn(HomeMotion.panFade)) togetherWith
                        (slideOutHorizontally(HomeMotion.pan) { w -> if (toHidden) w else -w } + fadeOut(HomeMotion.panFade))
                },
                label = "appsPage",
            ) { shown ->
                if (shown == Overlay.Hidden) {
                    AppsPage("Hidden apps", hidden, hiddenState, images, requesters, onLaunch, onMenu) { index ->
                        // Right at the end of a row returns to the grid.
                        if (index % GRID_COLUMNS == GRID_COLUMNS - 1 || index == hidden.lastIndex) {
                            Modifier.opensOn(Key.DirectionRight, onLeaveHidden)
                        } else {
                            Modifier
                        }
                    }
                } else {
                    AppsPage(null, grid, gridState, images, requesters, onLaunch, onMenu) { index ->
                        Modifier
                            .closeOnUp(index, onCloseGrid)
                            .then(if (index == secretIndex) Modifier.opensOn(Key.DirectionLeft, onOpenHidden) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsPage(
    title: String?,
    tiles: List<Tile>,
    state: LazyGridState,
    images: TileImages,
    requesters: MutableMap<String, FocusRequester>,
    onLaunch: (Tile) -> Unit,
    onMenu: (Tile) -> Unit,
    tileModifier: (index: Int) -> Modifier,
) {
    Column(Modifier.fillMaxSize()) {
        if (title != null) {
            BasicText(
                title,
                Modifier.padding(start = 64.dp, top = 36.dp, bottom = 4.dp),
                style = TextStyle(color = Color.White, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            state = state,
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = if (title == null) 52.dp else 20.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(tiles, key = { _, t -> t.packageName }) { index, tile ->
                Box(contentAlignment = Alignment.Center) {
                    AppTile(
                        tile = tile,
                        size = GRID_TILE,
                        images = images,
                        requester = requesters.requesterFor(tile.packageName),
                        onClick = { onLaunch(tile) },
                        onLongPress = { onMenu(tile) },
                        modifier = tileModifier(index),
                    )
                }
            }
        }
    }
}

/** Fades the dock's edges when more apps are scrolled out of view. */
private fun Modifier.fadeScrolledEdges(state: LazyListState) =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = 44.dp.toPx() / size.width
            val left = if (state.canScrollBackward) Color.Transparent else Color.Black
            val right = if (state.canScrollForward) Color.Transparent else Color.Black
            drawRect(
                Brush.horizontalGradient(0f to left, fade to Color.Black, 1f - fade to Color.Black, 1f to right),
                blendMode = BlendMode.DstIn,
            )
        }

@Composable
private fun MutableMap<String, FocusRequester>.requesterFor(packageName: String): FocusRequester =
    remember(packageName) { getOrPut(packageName) { FocusRequester() } }

/** Runs [action] on [key] instead of moving focus. */
private fun Modifier.opensOn(key: Key, action: () -> Unit) = onPreviewKeyEvent {
    if (it.key != key) return@onPreviewKeyEvent false
    if (it.type == KeyEventType.KeyDown) action()
    true
}

private fun Modifier.closeOnUp(index: Int, onClose: () -> Unit) = onPreviewKeyEvent {
    if (index < GRID_COLUMNS && it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp) {
        onClose()
        true
    } else {
        false
    }
}

private val imageCache = java.util.WeakHashMap<android.graphics.Bitmap, ImageBitmap>()

fun android.graphics.Bitmap.asImage(): ImageBitmap = imageCache.getOrPut(this) { asImageBitmap() }

/** Shows an arrow for each direction the app can still move. */
@Composable
private fun MoveArrows(left: Boolean, right: Boolean) {
    val leftAlpha by animateFloatAsState(if (left) 1f else 0f, tween(180), label = "arrowLeft")
    val rightAlpha by animateFloatAsState(if (right) 1f else 0f, tween(180), label = "arrowRight")
    Box(Modifier.size(DOCK_TILE)) {
        Chevron(pointsLeft = true, Modifier.align(Alignment.CenterStart).offset(x = (-5).dp).graphicsLayer { alpha = leftAlpha })
        Chevron(pointsLeft = false, Modifier.align(Alignment.CenterEnd).offset(x = 5.dp).graphicsLayer { alpha = rightAlpha })
    }
}

@Composable
private fun Chevron(pointsLeft: Boolean, modifier: Modifier) {
    // The path and paint are built once per size, not on every frame of the fade.
    Spacer(
        modifier.size(width = 11.dp, height = 24.dp).drawWithCache {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val tipX = if (pointsLeft) inset else size.width - inset
            val armX = if (pointsLeft) size.width - inset else inset
            val path = android.graphics.Path().apply {
                moveTo(armX, inset)
                lineTo(tipX, size.height / 2)
                lineTo(armX, size.height - inset)
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                color = android.graphics.Color.argb(240, 255, 255, 255)
                setShadowLayer(4.dp.toPx(), 0f, 1.dp.toPx(), android.graphics.Color.argb(110, 0, 0, 0))
            }
            onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawPath(path, paint) } }
        },
    )
}
