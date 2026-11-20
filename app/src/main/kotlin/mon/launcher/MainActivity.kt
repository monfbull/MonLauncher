package mon.launcher

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mon.launcher.core.Action
import mon.launcher.core.Focus
import mon.launcher.core.Home
import mon.launcher.core.Place
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var apps: AppSource
    private lateinit var store: StateStore
    private lateinit var images: TileImages
    private lateinit var iconPacks: IconPacks
    private lateinit var sounds: Sounds
    private lateinit var settingsStore: SettingsStore
    private lateinit var wallpapers: WallpaperLibrary

    private var home by mutableStateOf<Home?>(null)
    private var wallpaper by mutableStateOf<WallpaperImages?>(null)
    private var focusRequest by mutableStateOf<FocusRequest?>(null)
    private var homePresses by mutableIntStateOf(0)
    /** True while another app or screen covers the launcher. */
    private var leftForApp = false
    private var settings by mutableStateOf(LauncherSettings())
    private lateinit var welcome: WelcomeStore
    private var showWelcome by mutableStateOf(false)
    private var wallpaperLoad: Job? = null
    /** Stops the same wallpaper from loading twice. */
    private var wallpaperLoading: String? = null
    /** When true, the lateinit fields are not set. */
    private var recovering = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashLog.inCrashLoop(this)) {
            recovering = true
            setContent {
                RecoveryScreen(
                    onTryAgain = { CrashLog.acknowledge(this); recreate() },
                    onReset = { CrashLog.acknowledge(this); resetLauncher(); recreate() },
                )
            }
            return
        }
        apps = AppSource(this)
        store = StateStore(this)
        iconPacks = IconPacks(this)
        images = TileImages(apps, iconPacks)
        sounds = Sounds(this)
        settingsStore = SettingsStore(this)
        wallpapers = WallpaperLibrary(this)
        settings = settingsStore.load()
        welcome = WelcomeStore(this)
        showWelcome = !welcome.seen
        sounds.settings = settings

        setContent {
            CompositionLocalProvider(LocalSounds provides sounds) {
                HomeScreen(
                    home = home,
                    wallpaper = wallpaper,
                    images = images,
                    focusRequest = focusRequest,
                    homePresses = homePresses,
                    onFocusHandled = { focusRequest = null },
                    onLaunch = ::launch,
                    onAction = ::perform,
                    settings = settings,
                    onSettingsChange = ::changeSettings,
                    wallpapers = wallpapers,
                    onPickWallpaper = { file ->
                        changeSettings(settings.copy(wallpaper = file.name))
                        refreshWallpaper()
                    },
                    onWallpapersChanged = ::refreshWallpaper,
                    onReorderDock = { order -> home?.let { update(it.withDockOrder(order)) } },
                    showWelcome = showWelcome,
                    onShowWelcome = { showWelcome = true },
                    onWelcomeDone = {
                        welcome.markSeen()
                        showWelcome = false
                    },
                )
            }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })

        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 1)
        }

        lifecycleScope.launch {
            val pack = iconPacks.effective(settings.iconPack)
            images.pack = pack
            val catalog = withContext(Dispatchers.IO) { iconPacks.catalog(pack) }
            val installed = withContext(Dispatchers.IO) { apps.installed() }
            val started = Home.start(installed, store.load(), DEFAULT_DOCK, catalog)
            store.save(started.saved)
            home = started
            // If the process died while an app was open, focus that app's tile.
            // After a reboot or a launcher crash, focus the dock.
            val crashedSinceLaunch = CrashLog.lastCrashAt(this@MainActivity) > store.openedAt()
            val resume = store.openedSinceBoot() && store.returnPending() && !crashedSinceLaunch
            focusRequest = FocusRequest(if (resume) started.returnFocus else started.homeFocus)
            images.preload(started.dock + started.grid + started.hiddenApps)
        }
    }

    override fun onResume() {
        super.onResume()
        if (recovering) return
        leftForApp = false
        store.clearReturnPending()
        refreshApps()
        refreshWallpaper()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!recovering) refreshWallpaper()
    }

    /**
     * Home pressed.
     * Coming back from an app opened here focuses its tile.
     * Coming back from anything else keeps focus.
     * Pressing Home on the launcher closes overlays and focuses the dock.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val current = home ?: return
        if (leftForApp) {
            // onResume (which clears the pending return) runs after this.
            if (store.returnPending()) focusRequest = FocusRequest(current.returnFocus)
        } else {
            homePresses++
            focusRequest = FocusRequest(current.homeFocus)
        }
    }

    override fun onStop() {
        super.onStop()
        leftForApp = true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!recovering && event.action == KeyEvent.ACTION_DOWN && event.keyCode in ARROW_KEYS) sounds.arrowPressed()
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (!recovering) {
            sounds.release()
            unregisterReceiver(packageReceiver)
        }
        super.onDestroy()
    }

    /** Resets dock, hidden apps, settings and caches. Wallpaper photos are kept. */
    private fun resetLauncher() {
        listOf("home", "settings").forEach { getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
        File(filesDir, "wallpaper").deleteRecursively()
        cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    private fun refreshApps() {
        if (home == null) return
        lifecycleScope.launch {
            val pack = iconPacks.effective(settings.iconPack)
            val (installed, catalog) = withContext(Dispatchers.IO) { apps.installed() to iconPacks.catalog(pack) }
            images.pack = pack
            // Reconcile against the current state so changes made during the query aren't lost.
            home?.let { update(it.reconcile(installed, catalog)) }
        }
    }

    private fun refreshWallpaper() {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { chosenWallpaper() }
            val version = file?.let { withContext(Dispatchers.IO) { wallpaperVersion(it) } }
            if (version == wallpaper?.version || (version != null && version == wallpaperLoading)) return@launch
            wallpaperLoad?.cancel()
            wallpaperLoading = version
            wallpaperLoad = launch {
                val loaded = file?.let {
                    withContext(Dispatchers.Default) {
                        runCatching { Wallpaper.load(File(filesDir, "wallpaper"), it) }
                            .onFailure { e -> logProblem("Couldn't load wallpaper ${it.name}", e) }
                            .getOrNull()
                    }
                }
                if (file != null && loaded == null) logProblem("Wallpaper ${file.name} couldn't be shown")
                wallpaper = loaded
                wallpaperLoading = null
            }
        }
    }

    private fun chosenWallpaper(): File? {
        wallpapers.seedDefault()
        return settings.wallpaper?.let { File(wallpapers.folder, it) }?.takeIf { it.exists() }
            ?: wallpapers.list().firstOrNull()
    }

    private fun launch(packageName: String, place: Place) {
        home?.let { update(it.launched(packageName, place)) }
        store.markOpened()
        apps.launch(packageName)
    }

    private fun perform(action: Action, packageName: String, place: Place) {
        when (action) {
            Action.AppInfo -> apps.openAppInfo(packageName)
            Action.Uninstall -> apps.uninstall(packageName)
            else -> Unit
        }
        val change = home?.perform(action, packageName, place) ?: return
        update(change.home)
        focusRequest = FocusRequest(change.focus)
    }

    private fun changeSettings(next: LauncherSettings) {
        val packChanged = next.iconPack != settings.iconPack
        settings = next
        sounds.settings = next
        settingsStore.save(next)
        if (packChanged) refreshApps()
    }

    private fun update(next: Home) {
        home = next
        store.save(next.saved)
    }
}

private val ARROW_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

/** Not a data class, so requesting the same target twice still triggers. */
class FocusRequest(val target: Focus)
