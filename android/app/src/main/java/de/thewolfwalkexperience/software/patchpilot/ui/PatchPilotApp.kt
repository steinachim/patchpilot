// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import de.thewolfwalkexperience.software.patchpilot.ui.theme.ThemePreferences

private const val ROUTE_CONNECT = "connect"
private const val ROUTE_PROGRAMS = "programs"
private const val ROUTE_DEBUG = "debug"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LICENSES = "licenses"
private const val ROUTE_LICENSE_TEXT = "licenses/{asset}"
private const val ARG_LICENSE_ASSET = "asset"

/** The routes that show a connected session and have no UI for anything else. */
private val SESSION_ROUTES = setOf(ROUTE_PROGRAMS, ROUTE_DEBUG)

/**
 * Whether a session screen has anything to show for [this] state, or should hand off to
 * ConnectScreen. Exhaustive with no `else`, so a new [ConnectionState] is a compile error until
 * it says which side it falls on.
 *
 * `Disconnected`, `Searching` and `Opening` stay alongside `Connected`, because
 * `forceReconnect()` passes through them on a successful resume and leaving during that dip
 * would bounce to ConnectScreen and straight back. Everything else is a choice or a message only
 * ConnectScreen renders.
 */
private fun ConnectionState.rendersOnSessionScreens(): Boolean = when (this) {
    is ConnectionState.Connected,
    ConnectionState.Disconnected,
    ConnectionState.Searching,
    is ConnectionState.Opening,
    -> true
    is ConnectionState.Error,
    is ConnectionState.PermissionDenied,
    is ConnectionState.NeedsManualSetting,
    is ConnectionState.NothingFound,
    is ConnectionState.DeviceSelection,
    is ConnectionState.UnknownDeviceWarning,
    is ConnectionState.AdvisoryWarning,
    is ConnectionState.DeviceLost,
    -> false
}

/**
 * Two screens: find an instrument, then browse it. Plus settings, the licence screens, and a
 * debug menu nobody finds by accident - it has no button anywhere and opens only on five taps of
 * the instrument name.
 *
 * There is no demo-mode banner: the app bar shows the connected instrument's own name, and in
 * demo mode that name says so.
 */
@Composable
fun PatchPilotApp(
    viewModel: InstrumentViewModel,
    themePreferences: ThemePreferences,
    navController: NavHostController,
) {
    PatchPilotNavHost(navController, viewModel, themePreferences)
}

@Composable
private fun PatchPilotNavHost(
    navController: NavHostController,
    viewModel: InstrumentViewModel,
    themePreferences: ThemePreferences,
    modifier: Modifier = Modifier,
) {
    // Guards every navigate()/popBackStack() below against firing while the current destination
    // has not finished becoming the foreground screen: a second navigate() into a still-animating
    // transition leaves NavHost composing nothing at all, recoverable only by force-stopping the
    // app. The entry's own lifecycle is the reliable signal - Navigation Compose advances it to
    // RESUMED only once the destination is the settled top of the stack - where
    // currentBackStackEntryAsState updates earlier and a fixed cooldown depends on the screen.
    fun currentEntryIsResumed(): Boolean =
        navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

    fun guardedNavigate(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {
        if (!currentEntryIsResumed()) return
        navController.navigate(route, builder)
    }
    fun guardedPopBackStack() {
        if (!currentEntryIsResumed()) return
        navController.popBackStack()
    }
    // Returns to ROUTE_CONNECT from a session route, a no-op if that has already happened: a
    // session that cycles through several unrenderable states re-runs the effect below for each,
    // and once the first call has left `programs`, `popUpTo(ROUTE_PROGRAMS)` matches nothing and
    // `navigate` would push a second `connect`. From `debug`, the pop takes `programs` with it.
    fun returnToConnect() {
        if (navController.currentDestination?.route == ROUTE_CONNECT) return
        guardedNavigate(ROUTE_CONNECT) { popUpTo(ROUTE_PROGRAMS) { inclusive = true } }
    }

    // Hands a session screen off to ConnectScreen once the session settles somewhere it cannot
    // render (see [rendersOnSessionScreens]). Owned here rather than by each screen, so the debug
    // screen is covered as well as the preset list.
    //
    // Waits for the entry to be RESUMED rather than trying once: `guardedNavigate` drops a
    // navigate whose entry is not resumed, and a session is lost at exactly such moments - under
    // a system dialog, while backgrounded, or in the first frame of a pop back from the debug or
    // settings screen. Nothing would re-run a dropped hand-off, so the effect suspends on
    // `withResumed` instead. Keyed on the session, so a `forceReconnect()` dip back to Connected
    // never fires it.
    val session by viewModel.state.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(session, currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        if (entry.destination.route !in SESSION_ROUTES) return@LaunchedEffect
        if (session.rendersOnSessionScreens()) return@LaunchedEffect
        entry.lifecycle.withResumed { returnToConnect() }
    }

    NavHost(navController = navController, startDestination = ROUTE_CONNECT, modifier = modifier) {
        composable(ROUTE_CONNECT) {
            ConnectScreen(
                viewModel,
                onConnected = {
                    guardedNavigate(ROUTE_PROGRAMS) { popUpTo(ROUTE_CONNECT) { inclusive = true } }
                },
                onOpenSettings = { guardedNavigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_PROGRAMS) {
            ProgramsScreen(
                viewModel,
                // Disconnect first, then navigate: ConnectScreen starts a scan only when it
                // finds the session already Disconnected. showPicker = true because the user
                // asked for the picker, so the rescan must not silently reconnect the same lone
                // device and skip straight back here.
                //
                // `returnToConnect` pops to ROUTE_PROGRAMS, never to 0: under route-based
                // navigation the graph's own id is 0, and popping that leaves the host with no
                // destination to render.
                onBack = {
                    viewModel.disconnect(showPicker = true)
                    returnToConnect()
                },
                onOpenDebugMenu = { guardedNavigate(ROUTE_DEBUG) },
                onOpenSettings = { guardedNavigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_DEBUG) {
            DebugScreen(viewModel, onBack = { guardedPopBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                themePreferences,
                viewModel.connectionPreferences,
                onOpenLicenses = { guardedNavigate(ROUTE_LICENSES) },
                onBack = { guardedPopBackStack() },
            )
        }
        composable(ROUTE_LICENSES) {
            OpenSourceLicensesScreen(
                onOpenLicense = { asset -> guardedNavigate("$ROUTE_LICENSES/$asset") },
                onBack = { guardedPopBackStack() },
            )
        }
        composable(
            ROUTE_LICENSE_TEXT,
            arguments = listOf(navArgument(ARG_LICENSE_ASSET) { type = NavType.StringType }),
        ) { backStackEntry ->
            val asset = backStackEntry.arguments?.getString(ARG_LICENSE_ASSET).orEmpty()
            LicenseTextScreen(asset, onBack = { guardedPopBackStack() })
        }
    }
}
