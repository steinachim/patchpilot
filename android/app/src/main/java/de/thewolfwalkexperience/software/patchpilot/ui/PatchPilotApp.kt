// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import de.thewolfwalkexperience.software.patchpilot.ui.theme.ThemePreferences

private const val ROUTE_CONNECT = "connect"
private const val ROUTE_PROGRAMS = "programs"
private const val ROUTE_DEBUG = "debug"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LICENSES = "licenses"
private const val ROUTE_LICENSE_TEXT = "licenses/{asset}"
private const val ARG_LICENSE_ASSET = "asset"

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
    // Guards every navigate()/popBackStack() call below against firing while the *current*
    // destination has not finished becoming the foreground screen.
    //
    // Rapid taps on a back arrow (or any other navigation-triggering icon) race NavController's
    // own back-stack transition; a second `navigate()` fired into a still-animating transition
    // leaves NavHost composing nothing at all - a live window with an empty `ComposeView`,
    // recoverable only by force-stopping the app. Neither `currentBackStackEntryAsState` (which
    // updates before the transition animation has finished) nor a fixed cooldown (the settle
    // time depends on how heavy the screen is) is a reliable guard. The destination's own
    // [NavBackStackEntry.getLifecycle] is: Navigation Compose advances it to
    // [Lifecycle.State.RESUMED] only once the destination is the active, settled top of the
    // stack, which is the same signal the library's own transition machinery uses.
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
    // Returns to ROUTE_CONNECT from ROUTE_PROGRAMS, a no-op if that has already happened.
    //
    // Both callers ([onBack][ProgramsScreen] and `onSessionLost`) assume the current destination
    // is still `programs` when they fire, which a reactive effect cannot guarantee:
    // `onSessionLost` is driven by `LaunchedEffect(session)`, and a session that cycles through
    // more than one unrenderable state in quick succession - a failed reconnect retried, for
    // instance - re-runs that effect for each one. Once the first call has already left
    // `programs`, `popUpTo(ROUTE_PROGRAMS)` matches nothing and `navigate` would push a second
    // `connect` on top of the first. Checking the current destination first is what makes
    // repeated calls harmless instead of cumulative; `guardedNavigate`'s lifecycle check on top
    // of that stops a single burst of taps from re-entering `navigate()` mid-transition.
    fun returnToConnect() {
        if (navController.currentDestination?.route == ROUTE_CONNECT) return
        guardedNavigate(ROUTE_CONNECT) { popUpTo(ROUTE_PROGRAMS) { inclusive = true } }
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
                // Disconnect first, then navigate: ConnectScreen only starts a scan when it finds
                // the session already Disconnected, so going back without this would show the
                // previous instrument's connected state and re-scan nothing.
                // showPicker = true: the user asked to be shown the picker, so even if the same
                // lone device is still the only thing attached, the rescan must not silently
                // reconnect it and skip straight back to ProgramsScreen.
                //
                // **`popUpTo(ROUTE_PROGRAMS)`, not `popUpTo(0)`.** Under route-based navigation
                // the graph's own id is 0, so `popUpTo(0)` pops the graph *itself* and leaves the
                // host with an empty back stack and no destination to render - a live window with
                // no content in it, recoverable only by force-stopping the app.
                onBack = {
                    viewModel.disconnect(showPicker = true)
                    returnToConnect()
                },
                // A reconnect that lands on a state only ConnectScreen knows how to show
                // (NothingFound, an error, a picker, an advisory) - not the user backing out, so
                // no disconnect() first: the session is already however forceReconnect() left it,
                // and ConnectScreen's own `when` renders it correctly on arrival. Same popUpTo
                // shape as [onBack] above, for the same reason.
                onSessionLost = { returnToConnect() },
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
