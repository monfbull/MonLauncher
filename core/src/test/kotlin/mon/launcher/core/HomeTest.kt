package mon.launcher.core

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeTest {
    /** App-management options offered at the end of every menu. */
    private val manage = listOf(Action.AppInfo, Action.Uninstall)

    private val defaults = listOf("kodi", "jellyfin", "youtube", "plex", "netflix", "hbo")

    private fun app(pkg: String, label: String = pkg) = InstalledApp(pkg, label)

    @Test
    fun `first run seeds dock with default apps in order, skipping ones not installed, then fills from the app list`() {
        val installed = listOf(app("hbo"), app("youtube"), app("kodi"), app("netflix"), app("vlc", "VLC"), app("a", "Arte"))

        val home = Home.start(installed, saved = null, defaultDock = defaults, logos = LogoCatalog())

        assertEquals(listOf("kodi", "youtube", "netflix", "hbo", "a", "vlc"), home.dock.map { it.packageName })
    }

    @Test
    fun `first run dock holds at most six apps`() {
        val installed = (defaults + listOf("x", "y")).map { app(it) }

        val home = Home.start(installed, saved = null, defaultDock = defaults, logos = LogoCatalog())

        assertEquals(defaults.take(6), home.dock.map { it.packageName })
    }

    @Test
    fun `a saved dock is used as is, even when short`() {
        val installed = listOf(app("youtube"), app("netflix"), app("vlc"))

        val home = Home.start(installed, SavedState(dock = listOf("vlc")), defaults, LogoCatalog())

        assertEquals(listOf("vlc"), home.dock.map { it.packageName })
    }

    @Test
    fun `grid lists every app alphabetically ignoring case`() {
        val installed = listOf(app("a", "netflix"), app("b", "Plex"), app("c", "YouTube"), app("d", "kodi"))

        val home = Home.start(installed, saved = SavedState(), defaultDock = defaults, logos = LogoCatalog())

        assertEquals(listOf("kodi", "netflix", "Plex", "YouTube"), home.grid.map { it.label })
    }

    @Test
    fun `hidden apps leave the grid and the hidden-apps entry appears only when something is hidden`() {
        val installed = listOf(app("a", "Arc"), app("b", "Browser"), app("c", "Chrome"))

        val none = Home.start(installed, SavedState(), defaults, LogoCatalog())
        val some = Home.start(installed, SavedState(hidden = setOf("b")), defaults, LogoCatalog())

        assertEquals(false, none.showHiddenEntry)
        assertEquals(listOf("Arc", "Chrome"), some.grid.map { it.label })
        assertEquals(true, some.showHiddenEntry)
        assertEquals(listOf("Browser"), some.hiddenApps.map { it.label })
    }

    @Test
    fun `logo is custom override, then pack logo, then the app's own icon`() {
        val logos = LogoCatalog(
            custom = mapOf("jellyfin" to "custom/jellyfin"),
            pack = mapOf("jellyfin" to "pack/jellyfin_old", "netflix" to "pack/netflix"),
        )
        val installed = listOf(app("jellyfin", "Jellyfin"), app("netflix", "Netflix"), app("vlc", "VLC"))

        val home = Home.start(installed, SavedState(), defaults, logos)
        val logoOf = home.grid.associate { it.packageName to it.logo }

        assertEquals(Logo.Asset("custom/jellyfin"), logoOf["jellyfin"])
        assertEquals(Logo.Asset("pack/netflix"), logoOf["netflix"])
        assertEquals(Logo.AppIcon, logoOf["vlc"])
    }

    private fun homeWithDock(vararg dock: String, extra: List<String> = listOf("vlc")) =
        Home.start((dock.toList() + extra).map { app(it) }, SavedState(dock = dock.toList()), defaults, LogoCatalog())

    @Test
    fun `grid menu offers add or remove depending on whether the app is in the dock`() {
        val home = homeWithDock("netflix")

        assertEquals(listOf(Action.AddToDock, Action.Hide) + manage, home.menu("vlc", Place.Grid))
        assertEquals(listOf(Action.RemoveFromDock, Action.Hide) + manage, home.menu("netflix", Place.Grid))
    }

    @Test
    fun `dock menu offers move only when there's another app to move past`() {
        val home = homeWithDock("a", "b", "c")

        assertEquals(listOf(Action.Move, Action.RemoveFromDock) + manage, home.menu("a", Place.Dock))
        assertEquals(listOf(Action.Move, Action.RemoveFromDock) + manage, home.menu("c", Place.Dock))
        assertEquals(listOf(Action.RemoveFromDock) + manage, homeWithDock("solo").menu("solo", Place.Dock))
    }

    @Test
    fun `hidden apps list offers unhide`() {
        val home = Home.start(listOf(app("x")), SavedState(hidden = setOf("x")), defaults, LogoCatalog())

        assertEquals(listOf(Action.Unhide) + manage, home.menu("x", Place.HiddenList))
    }

    @Test
    fun `adding to the dock puts the app at the end and keeps focus on it in the grid`() {
        val change = homeWithDock("a", "b").perform(Action.AddToDock, "vlc", Place.Grid)

        assertEquals(listOf("a", "b", "vlc"), change.home.dock.map { it.packageName })
        assertEquals(Focus.Grid("vlc"), change.focus)
    }

    @Test
    fun `removing from the dock moves focus to the next app, or the previous one at the end`() {
        val home = homeWithDock("a", "b", "c")

        val middle = home.perform(Action.RemoveFromDock, "b", Place.Dock)
        val last = home.perform(Action.RemoveFromDock, "c", Place.Dock)

        assertEquals(listOf("a", "c"), middle.home.dock.map { it.packageName })
        assertEquals(Focus.Dock("c"), middle.focus)
        assertEquals(Focus.Dock("b"), last.focus)
    }

    @Test
    fun `removing the only dock app sends focus to the grid`() {
        val change = homeWithDock("solo").perform(Action.RemoveFromDock, "solo", Place.Dock)

        assertEquals(emptyList(), change.home.dock)
        assertEquals(Focus.GridStart, change.focus)
    }

    @Test
    fun `removing from the dock via the grid keeps focus on the grid app`() {
        val change = homeWithDock("a", "b").perform(Action.RemoveFromDock, "a", Place.Grid)

        assertEquals(listOf("b"), change.home.dock.map { it.packageName })
        assertEquals(Focus.Grid("a"), change.focus)
    }

    @Test
    fun `each step swaps the moving app with its neighbour`() {
        val move = homeWithDock("a", "b", "c").startMove("b")

        assertEquals(listOf("b", "a", "c"), move.step(-1).order)
        assertEquals(listOf("a", "c", "b"), move.step(+1).order)
        assertEquals(listOf("b", "c", "a"), homeWithDock("a", "b", "c").startMove("a").step(1).step(1).order)
    }

    @Test
    fun `a move only offers the directions it can go, and stops at the ends`() {
        val move = homeWithDock("a", "b").startMove("a")

        assertEquals(false, move.canMoveLeft)
        assertEquals(true, move.canMoveRight)
        assertEquals(move, move.step(-1))
        assertEquals(true, move.step(1).canMoveLeft)
        assertEquals(false, move.step(1).canMoveRight)
    }

    @Test
    fun `nothing changes until a move is committed`() {
        val home = homeWithDock("a", "b", "c")
        val move = home.startMove("a").step(1).step(1)

        assertEquals(listOf("a", "b", "c"), home.dock.map { it.packageName })
        assertEquals(listOf("b", "c", "a"), home.withDockOrder(move.order).dock.map { it.packageName })
        assertEquals(Change(home, Focus.Dock("a")).focus, home.perform(Action.Move, "a", Place.Dock).focus)
    }

    @Test
    fun `a committed order that no longer matches the dock is ignored`() {
        val home = homeWithDock("a", "b", "c")

        assertEquals(listOf("a", "b", "c"), home.withDockOrder(listOf("c", "a")).dock.map { it.packageName })
        assertEquals(listOf("a", "b", "c"), home.withDockOrder(listOf("c", "a", "vlc")).dock.map { it.packageName })
    }

    @Test
    fun `hiding an app removes it from the dock and grid and focuses the next grid app`() {
        val installed = listOf(app("a", "Alpha"), app("b", "Beta"), app("c", "Gamma"))
        val home = Home.start(installed, SavedState(dock = listOf("b", "c")), defaults, LogoCatalog())

        val change = home.perform(Action.Hide, "b", Place.Grid)

        assertEquals(listOf("c"), change.home.dock.map { it.packageName })
        assertEquals(listOf("Alpha", "Gamma"), change.home.grid.map { it.label })
        assertEquals(listOf("Beta"), change.home.hiddenApps.map { it.label })
        assertEquals(Focus.Grid("c"), change.focus)
    }

    @Test
    fun `unhiding brings the app back to the grid without adding it to the dock`() {
        val installed = listOf(app("a", "Alpha"), app("b", "Beta"))
        val home = Home.start(installed, SavedState(hidden = setOf("b")), defaults, LogoCatalog())

        val change = home.perform(Action.Unhide, "b", Place.HiddenList)

        assertEquals(listOf("Alpha", "Beta"), change.home.grid.map { it.label })
        assertEquals(false, change.home.showHiddenEntry)
        assertEquals(emptyList(), change.home.dock)
        assertEquals(Focus.Grid("b"), change.focus)
    }

    @Test
    fun `home lands on the app last launched from the dock`() {
        val home = homeWithDock("a", "b", "c").launched("b", Place.Dock).launched("vlc", Place.Grid)

        assertEquals(Focus.Dock("b"), home.homeFocus)
    }

    @Test
    fun `coming back from an app returns to the tile it was opened from`() {
        val fromGrid = homeWithDock("a", "b").launched("b", Place.Dock).launched("vlc", Place.Grid)
        val fromDock = homeWithDock("a", "b").launched("vlc", Place.Grid).launched("b", Place.Dock)

        assertEquals(Focus.Grid("vlc"), fromGrid.returnFocus)
        assertEquals(Focus.Dock("b"), fromDock.returnFocus)
        // Pressing Home on the launcher itself still goes to the dock.
        assertEquals(Focus.Dock("b"), fromGrid.homeFocus)
    }

    @Test
    fun `coming back to an app opened from the hidden list returns to the hidden list`() {
        val home = homeWithDock("a").perform(Action.Hide, "vlc", Place.Grid).home.launched("vlc", Place.HiddenList)

        assertEquals(Focus.Hidden("vlc"), home.returnFocus)
    }

    @Test
    fun `coming back falls back to the dock when the opened app has moved or gone`() {
        val hiddenSince = homeWithDock("a").launched("vlc", Place.Grid).perform(Action.Hide, "vlc", Place.Grid).home
        val uninstalled = homeWithDock("a").launched("vlc", Place.Grid).reconcile(listOf(app("a")))

        assertEquals(Focus.Dock("a"), hiddenSince.returnFocus)
        assertEquals(Focus.Dock("a"), uninstalled.returnFocus)
        assertEquals(null, uninstalled.saved.lastOpened)
    }

    @Test
    fun `home falls back to the first dock app, or the grid when the dock is empty`() {
        val neverLaunched = homeWithDock("a", "b")
        val launchedThenRemoved = homeWithDock("a", "b").launched("b", Place.Dock)
            .perform(Action.RemoveFromDock, "b", Place.Dock).home

        assertEquals(Focus.Dock("a"), neverLaunched.homeFocus)
        assertEquals(Focus.Dock("a"), launchedThenRemoved.homeFocus)
        assertEquals(Focus.GridStart, homeWithDock().homeFocus)
    }

    @Test
    fun `uninstalled apps drop out of the dock, grid and hidden list`() {
        val home = Home.start(
            listOf(app("a"), app("b"), app("h")),
            SavedState(dock = listOf("a", "b"), hidden = setOf("h")),
            defaults, LogoCatalog(),
        )

        val after = home.reconcile(listOf(app("a")))

        assertEquals(listOf("a"), after.dock.map { it.packageName })
        assertEquals(listOf("a"), after.grid.map { it.packageName })
        assertEquals(false, after.showHiddenEntry)
        assertEquals(SavedState(dock = listOf("a")), after.saved)
    }

    @Test
    fun `new installs appear in the grid but never in the dock, even when reinstalled`() {
        val home = homeWithDock("a", "b", extra = emptyList())

        val reinstalled = home.reconcile(listOf(app("a"))).reconcile(listOf(app("a"), app("b"), app("new")))

        assertEquals(listOf("a"), reinstalled.dock.map { it.packageName })
        assertEquals(listOf("a", "b", "new"), reinstalled.grid.map { it.packageName })
    }

    @Test
    fun `saved state mentioning uninstalled apps is cleaned up at start`() {
        val home = Home.start(listOf(app("a")), SavedState(dock = listOf("gone", "a"), lastLaunched = "gone"), defaults, LogoCatalog())

        assertEquals(SavedState(dock = listOf("a")), home.saved)
        assertEquals(listOf(Action.RemoveFromDock) + manage, home.menu("a", Place.Dock))
    }

    @Test
    fun `app info and uninstall leave the launcher state and focus untouched`() {
        val home = homeWithDock("a", "b")

        val info = home.perform(Action.AppInfo, "b", Place.Dock)
        val uninstall = home.perform(Action.Uninstall, "vlc", Place.Grid)

        assertEquals(home.saved, info.home.saved)
        assertEquals(Focus.Dock("b"), info.focus)
        assertEquals(home.saved, uninstall.home.saved)
        assertEquals(Focus.Grid("vlc"), uninstall.focus)
    }

    @Test
    fun `switching icon packs changes the logos and nothing else`() {
        val installed = listOf(app("netflix"), app("vlc"))
        val home = Home.start(installed, SavedState(dock = listOf("vlc", "netflix")), defaults, LogoCatalog())

        val withPack = home.reconcile(installed, LogoCatalog(pack = mapOf("netflix" to "netflix_logo")))

        assertEquals(Logo.Asset("netflix_logo"), withPack.dock.single { it.packageName == "netflix" }.logo)
        assertEquals(Logo.AppIcon, withPack.dock.single { it.packageName == "vlc" }.logo)
        assertEquals(home.saved, withPack.saved)
    }
}
