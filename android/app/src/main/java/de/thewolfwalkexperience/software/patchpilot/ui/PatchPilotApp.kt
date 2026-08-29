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
 * Two screens: find an instrument, then browse it. Plus a third nobody finds by accident - the
 * debug menu, which has no button anywhere and opens only on five taps of the instrument name.
 *
 * There were five. `CategoriesScreen`, `CategoryItemsScreen` and `ShowTextScreen` were reachable
 * only from two buttons in the Presets app bar left over from an earlier version of the debug
 * menu, and all three were removed with them - there is no point keeping screens nothing can
 * open. The underlying instrument commands - listing categories and showing text - are
 * unaffected; this app simply does not expose UI for them.
 *
 * The demo-mode banner went at the same time. It existed to make sure a fake session could never
 * be mistaken for a real one, which the app bar now does better: it shows the connected
 * instrument's own name, and in demo mode that name says so.
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
    // destination hasn't actually finished becoming the foreground screen yet.
    //
    // **Confirmed root cause of a rapid-tap navigation fault, 2026-08-28**, with a fully
    // deterministic, scriptable reproduction: spamming taps on a route's back arrow (or any
    // other nav-triggering icon) raced NavController's own back-stack transition closely enough
    // that NavHost stopped composing anything at all - the window stayed up, `MainActivity`
    // stayed resumed, nothing crashed, and `uiautomator dump` showed an empty `ComposeView` with
    // no content whatsoever. Reproduced identically on a real Pixel 6 and the emulator, both from
    // genuine rapid tapping and from concurrent `adb shell input tap` injection. This is the same
    // shape the 2026-08-23 sighting described - "no nav destination wanted the back press" - just
    // with a trigger that took until now to isolate.
    //
    // **Two earlier attempts at this guard did not hold, both confirmed by re-running the exact
    // same reproduction against them:**
    // 1. A `Boolean` keyed off [androidx.navigation.compose.currentBackStackEntryAsState] instead
    //    of a timer - Compose's back-stack state updates as soon as `navigate()` mutates it,
    //    which is *before* the destination-swap transition animation has actually finished
    //    playing, so a second `navigate()` fired into that still-animating window still broke it.
    // 2. A flat 500ms cooldown - safely longer than Compose Navigation's default ~300ms
    //    crossfade, and it still reproduced with two taps 700ms apart. `ProgramsScreen` is heavy
    //    enough (a `LazyColumn`, drag-to-reorder state, the index collector's own effects) that
    //    however long it actually takes to settle is not a constant this file can guess at - two
    //    seconds between taps was reliably safe in testing, which only says the true number is
    //    somewhere in between and screen-dependent.
    //
    // What finally held: the destination's own [NavBackStackEntry.getLifecycle], which Navigation
    // Compose itself only advances to [Lifecycle.State.RESUMED] once that destination has
    // actually become the active, settled top of the stack - not a duration this file has to
    // guess, the same signal the library's own transition machinery uses internally.
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
    // `programs`, `popUpTo(ROUTE_PROGRAMS)` matches nothing and `navigate` just pushes a second
    // `connect` on top of the first - confirmed on-device 2026-08-28, a back stack that had
    // accumulated four of them after a long session with several reconnect failures. Checking
    // the current destination first, rather than trusting which composable's callback fired, is
    // what makes repeated calls harmless instead of cumulative. `guardedNavigate`'s own lifecycle
    // check on top of that is what stops a *single* burst of taps from re-entering `navigate()`
    // while the transition is still playing, which is the sharper and more common way to hit the
    // same class of bug.
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
                // no content in it, recoverable only by force-stopping the app. That was one
                // candidate shape for the 2026-08-23 fault; the confirmed one, found 2026-08-28,
                // is `guardedNavigate`'s doc comment above.
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
