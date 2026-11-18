package mon.launcher.core

data class InstalledApp(val packageName: String, val label: String)

data class SavedState(
    val dock: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Last app opened from the dock. Pressing Home focuses it. */
    val lastLaunched: String? = null,
    /** Last app opened, and where from. Coming back from it focuses that tile. */
    val lastOpened: String? = null,
    val lastOpenedFrom: Place? = null,
)

/** Logo image names by package name. [custom] wins over [pack]. */
class LogoCatalog(
    private val custom: Map<String, String> = emptyMap(),
    private val pack: Map<String, String> = emptyMap(),
) {
    fun logoFor(app: InstalledApp): Logo {
        val asset = custom[app.packageName] ?: pack[app.packageName]
        return if (asset != null) Logo.Asset(asset) else Logo.AppIcon
    }
}

sealed interface Logo {
    data class Asset(val name: String) : Logo
    /** The app's own banner or icon. */
    data object AppIcon : Logo
}

enum class Place { Dock, Grid, HiddenList }

/** Menu actions. The system handles [AppInfo] and [Uninstall]. */
enum class Action { AddToDock, RemoveFromDock, Move, Hide, Unhide, AppInfo, Uninstall }

/** Nothing is saved until [Home.withDockOrder] is called. */
data class DockMove(val packageName: String, val order: List<String>) {
    private val index get() = order.indexOf(packageName)
    val canMoveLeft: Boolean get() = index > 0
    val canMoveRight: Boolean get() = index in 0 until order.lastIndex

    /** Moves one place left (-1) or right (+1). Does nothing at the ends. */
    fun step(by: Int): DockMove {
        val i = index
        val j = i + by
        if (i < 0 || j !in order.indices) return this
        return copy(order = order.toMutableList().apply { this[i] = this[j]; this[j] = packageName })
    }
}

sealed interface Focus {
    data class Dock(val packageName: String) : Focus
    data class Grid(val packageName: String) : Focus
    data class Hidden(val packageName: String) : Focus
    data object GridStart : Focus
}

data class Change(val home: Home, val focus: Focus)

data class Tile(val packageName: String, val label: String, val logo: Logo)

/** Launcher state and the rules that change it. No UI code. */
class Home private constructor(
    private val installed: Map<String, InstalledApp>,
    val saved: SavedState,
    private val logos: LogoCatalog,
) {
    val dock: List<Tile> = saved.dock.map { tile(installed.getValue(it)) }

    private val alphabetical = installed.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    val grid: List<Tile> = alphabetical.filter { it.packageName !in saved.hidden }.map(::tile)

    val hiddenApps: List<Tile> = alphabetical.filter { it.packageName in saved.hidden }.map(::tile)

    val showHiddenEntry: Boolean get() = hiddenApps.isNotEmpty()

    /** Drops apps that are no longer installed. Never adds apps to the dock. */
    fun reconcile(installed: List<InstalledApp>, logos: LogoCatalog = this.logos): Home = create(installed, saved, logos)

    /** Focus when Home is pressed while the launcher is open. */
    val homeFocus: Focus
        get() = (saved.lastLaunched?.takeIf { it in saved.dock } ?: saved.dock.firstOrNull())
            ?.let { Focus.Dock(it) } ?: Focus.GridStart

    /** Focus when coming back from an app: the tile it was opened from, else [homeFocus]. */
    val returnFocus: Focus
        get() {
            val app = saved.lastOpened ?: return homeFocus
            return when (saved.lastOpenedFrom) {
                Place.Dock -> Focus.Dock(app).takeIf { app in saved.dock }
                Place.Grid -> Focus.Grid(app).takeIf { grid.any { it.packageName == app } }
                Place.HiddenList -> Focus.Hidden(app).takeIf { app in saved.hidden }
                null -> null
            } ?: homeFocus
        }

    fun launched(packageName: String, place: Place): Home {
        val opened = saved.copy(lastOpened = packageName, lastOpenedFrom = place)
        return Home(installed, if (place == Place.Dock) opened.copy(lastLaunched = packageName) else opened, logos)
    }

    /** In display order. */
    fun menu(packageName: String, place: Place): List<Action> = launcherActions(packageName, place) + manageActions

    private val manageActions = listOf(Action.AppInfo, Action.Uninstall)

    private fun launcherActions(packageName: String, place: Place): List<Action> = when (place) {
        Place.Grid -> listOf(
            if (packageName in saved.dock) Action.RemoveFromDock else Action.AddToDock,
            Action.Hide,
        )
        Place.Dock -> listOfNotNull(Action.Move.takeIf { saved.dock.size > 1 }, Action.RemoveFromDock)
        Place.HiddenList -> listOf(Action.Unhide)
    }

    fun perform(action: Action, packageName: String, place: Place): Change {
        val dock = saved.dock
        return when (action) {
            Action.AddToDock -> Change(with(dock = dock + packageName), Focus.Grid(packageName))
            Action.RemoveFromDock -> {
                val newDock = dock - packageName
                val focus = if (place == Place.Dock) dockNeighbour(packageName) else Focus.Grid(packageName)
                Change(with(dock = newDock), focus)
            }
            // The UI runs the move. See [startMove].
            Action.Move -> Change(this, Focus.Dock(packageName))
            Action.Hide -> Change(with(dock = dock - packageName, hidden = saved.hidden + packageName), gridNeighbour(packageName))
            Action.Unhide -> Change(with(hidden = saved.hidden - packageName), Focus.Grid(packageName))
            Action.AppInfo, Action.Uninstall -> Change(this, if (place == Place.Dock) Focus.Dock(packageName) else Focus.Grid(packageName))
        }
    }

    /** Next app, else previous app, else the grid start. */
    private fun gridNeighbour(packageName: String): Focus {
        val i = grid.indexOfFirst { it.packageName == packageName }
        val neighbour = grid.getOrNull(i + 1) ?: grid.getOrNull(i - 1)
        return neighbour?.let { Focus.Grid(it.packageName) } ?: Focus.GridStart
    }

    fun startMove(packageName: String) = DockMove(packageName, saved.dock)

    /** Does nothing unless [order] has exactly the current dock apps. */
    fun withDockOrder(order: List<String>): Home =
        if (order.sorted() == saved.dock.sorted()) with(dock = order) else this

    /** Next app, else previous app, else the grid start. */
    private fun dockNeighbour(packageName: String): Focus {
        val i = saved.dock.indexOf(packageName)
        val neighbour = saved.dock.getOrNull(i + 1) ?: saved.dock.getOrNull(i - 1)
        return neighbour?.let { Focus.Dock(it) } ?: Focus.GridStart
    }

    private fun with(dock: List<String> = saved.dock, hidden: Set<String> = saved.hidden) =
        Home(installed, saved.copy(dock = dock, hidden = hidden), logos)

    private fun tile(app: InstalledApp) = Tile(app.packageName, app.label, logos.logoFor(app))

    companion object {
        const val FIRST_RUN_DOCK_SIZE = 6

        /**
         * [saved] is null on first run.
         * Then the dock gets the installed [defaultDock] apps, then the first apps A to Z.
         * Up to [FIRST_RUN_DOCK_SIZE] apps in total.
         */
        fun start(
            installed: List<InstalledApp>,
            saved: SavedState?,
            defaultDock: List<String>,
            logos: LogoCatalog,
        ): Home {
            val state = saved ?: SavedState(dock = firstRunDock(installed, defaultDock))
            return create(installed, state, logos)
        }

        private fun firstRunDock(installed: List<InstalledApp>, preferred: List<String>): List<String> {
            val packages = installed.map { it.packageName }.toSet()
            val picked = preferred.filter { it in packages }.distinct().take(FIRST_RUN_DOCK_SIZE)
            val rest = installed.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .map { it.packageName }
                .filter { it !in picked }
            return picked + rest.take(FIRST_RUN_DOCK_SIZE - picked.size)
        }

        private fun create(installed: List<InstalledApp>, saved: SavedState, logos: LogoCatalog): Home {
            val byPkg = installed.associateBy { it.packageName }
            val clean = SavedState(
                dock = saved.dock.filter { it in byPkg },
                hidden = saved.hidden.filterTo(LinkedHashSet()) { it in byPkg },
                lastLaunched = saved.lastLaunched?.takeIf { it in byPkg },
                lastOpened = saved.lastOpened?.takeIf { it in byPkg },
                lastOpenedFrom = saved.lastOpenedFrom.takeIf { saved.lastOpened in byPkg },
            )
            return Home(byPkg, clean, logos)
        }
    }
}
