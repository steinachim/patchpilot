// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

// The routes. Internal rather than private: NavHost finds a start destination's serializer by
// reflection, which cannot reach a private class.
@Serializable
internal data object ConnectRoute

@Serializable
internal data object ProgramsRoute

@Serializable
internal data object DebugRoute

@Serializable
internal data object SettingsRoute

@Serializable
internal data object LicensesRoute

/** One license text; [asset] is its file under `assets/licenses/`. */
@Serializable
internal data class LicenseTextRoute(val asset: String)

/** Whether this destination shows a connected session and has no UI for anything else. */
private fun NavDestination.isSessionRoute(): Boolean = hasRoute<ProgramsRoute>() || hasRoute<DebugRoute>()

/**
 * Whether a session screen has anything to show for [this] state, or should hand off to
 * ConnectScreen. Exhaustive with no `else`, so a new [ConnectionState] is a compile error until
 * it says which side it falls on.
 *
 * `Searching` and `Opening` stay alongside `Connected`, because `forceReconnect()` passes through
 * them on a successful resume and leaving during that dip would bounce to ConnectScreen and
 * straight back. `Disconnected` stays too: it is the moment before the ViewModel's first attempt,
 * which a session screen restored after process death starts in. Everything else is a choice or a
 * message only ConnectScreen renders.
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
    navController: NavHostController,
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

    fun guardedNavigate(route: Any, builder: NavOptionsBuilder.() -> Unit = {}) {
        if (!currentEntryIsResumed()) return
        navController.navigate(route, builder)
    }
    fun guardedPopBackStack() {
        if (!currentEntryIsResumed()) return
        navController.popBackStack()
    }

    // Returns to ConnectRoute from a session route, a no-op if that has already happened: a
    // session that cycles through several unrenderable states re-runs the effect below for each,
    // and once the first call has left ProgramsRoute, `popUpTo<ProgramsRoute>` matches nothing and
    // `navigate` would push a second ConnectRoute. From DebugRoute, the pop takes ProgramsRoute
    // with it.
    fun returnToConnect() {
        if (navController.currentDestination?.hasRoute<ConnectRoute>() == true) return
        guardedNavigate(ConnectRoute) { popUpTo<ProgramsRoute> { inclusive = true } }
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
    val session by viewModel.state.collectAsStateWithLifecycle()
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(session, currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        if (!entry.destination.isSessionRoute()) return@LaunchedEffect
        if (session.rendersOnSessionScreens()) return@LaunchedEffect
        entry.lifecycle.withResumed { returnToConnect() }
    }

    // Hands ConnectScreen off to the preset list once a session is up. Waits for RESUMED for the
    // same reason: an instrument plugged in while the phone is locked connects while ConnectScreen
    // is not resumed, and a dropped navigate is never re-run.
    LaunchedEffect(session, currentEntry) {
        val entry = currentEntry ?: return@LaunchedEffect
        if (!entry.destination.hasRoute<ConnectRoute>() || session !is ConnectionState.Connected) return@LaunchedEffect
        entry.lifecycle.withResumed {
            guardedNavigate(ProgramsRoute) { popUpTo<ConnectRoute> { inclusive = true } }
        }
    }

    NavHost(navController = navController, startDestination = ConnectRoute, modifier = modifier) {
        composable<ConnectRoute> {
            ConnectScreen(viewModel, onOpenSettings = { guardedNavigate(SettingsRoute) })
        }
        composable<ProgramsRoute> {
            ProgramsScreen(
                viewModel,
                // `disconnect()` leaves Connected at once and ends on the picker, which this
                // screen cannot render, so the hand-off above also returns to ConnectScreen if
                // this navigate is dropped mid-transition.
                //
                // `returnToConnect` pops to ProgramsRoute, never to 0: under route-based
                // navigation the graph's own id is 0, and popping that leaves the host with no
                // destination to render.
                onBack = {
                    viewModel.connection.disconnect()
                    returnToConnect()
                },
                onOpenDebugMenu = { guardedNavigate(DebugRoute) },
                onOpenSettings = { guardedNavigate(SettingsRoute) },
            )
        }
        composable<DebugRoute> {
            DebugScreen(viewModel, onBack = { guardedPopBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(
                viewModel.preferences,
                onOpenLicenses = { guardedNavigate(LicensesRoute) },
                onBack = { guardedPopBackStack() },
            )
        }
        composable<LicensesRoute> {
            OpenSourceLicensesScreen(
                onOpenLicense = { asset -> guardedNavigate(LicenseTextRoute(asset)) },
                onBack = { guardedPopBackStack() },
            )
        }
        composable<LicenseTextRoute> { backStackEntry ->
            LicenseTextScreen(backStackEntry.toRoute<LicenseTextRoute>().asset, onBack = { guardedPopBackStack() })
        }
    }
}
