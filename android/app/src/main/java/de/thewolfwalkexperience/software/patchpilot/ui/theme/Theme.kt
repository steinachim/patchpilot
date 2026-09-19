package de.thewolfwalkexperience.software.patchpilot.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Wraps [content] in the chosen [AppTheme].
 *
 * [AppTheme.Default] is stock Material: it follows the system light/dark setting (minSdk 26
 * predates the "force dark" APIs, so this is the only reliable signal), with dynamic
 * (wallpaper-derived) color on API 31+ and a static scheme below that. Anyone who never opens
 * Settings sees exactly that, unconditionally.
 *
 * [AppTheme.Steampunk] is a single committed dark look - like a real WinAmp skin, it does not
 * follow the system setting - built from [SteampunkColorScheme]/[steampunkTypography]/
 * [SteampunkShapes]. [LocalThemeStyle] is provided alongside it, holding [appTheme]'s [ThemeStyle]
 * ([AppTheme.style]), for the handful of things a `MaterialTheme` token swap cannot produce (the
 * connect-screen gauge, the slot bezel, the device rows, the drag handle glyph) - screens read
 * [LocalThemeStyle.current] rather than branching on the raw [AppTheme] themselves.
 *
 * **`MaterialTheme`/[content] are called from exactly one call site**, never one per branch of a
 * theme `when`. [content] is `PatchPilotApp`'s `NavHost`, and calling it from two different
 * branches gives it two different positions in the composition's slot table - switching themes
 * then reads as "the old position's subtree went away, a new one appeared", which tears the whole
 * subtree down and rebuilds it - and with it `rememberNavController()` and its back stack, which
 * would reset to the start destination on every theme change. Computing the values first and
 * calling `MaterialTheme`/[content] once, after the `when`s, keeps `content` at one stable
 * position, so a theme switch only ever changes what `MaterialTheme` resolves to.
 */
@Composable
fun PatchPilotTheme(
    appTheme: AppTheme = AppTheme.Default,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.Default -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
        AppTheme.Steampunk -> SteampunkColorScheme
    }
    val typography = when (appTheme) {
        AppTheme.Default -> MaterialTheme.typography
        AppTheme.Steampunk -> steampunkTypography(MaterialTheme.typography)
    }
    val shapes = when (appTheme) {
        AppTheme.Default -> MaterialTheme.shapes
        AppTheme.Steampunk -> SteampunkShapes
    }
    // **The status bar has to follow the *app's* theme, not the system's.**
    // `enableEdgeToEdge()` in MainActivity opts into drawing behind the bars and, left to itself,
    // picks the bar icon colours from the system light/dark setting. That is right for
    // [AppTheme.Default], which follows the same setting - but [AppTheme.Steampunk] is
    // deliberately always dark, so with the system in light mode the platform drew dark icons over
    // this theme's near-black surface and the clock, battery and back gesture hint disappeared.
    //
    // Derived from the theme rather than from `darkTheme` alone, so a future theme gets this right
    // by declaring what it is instead of by remembering to touch MainActivity.
    val darkBars = when (appTheme) {
        AppTheme.Default -> darkTheme
        AppTheme.Steampunk -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // A SideEffect, not a LaunchedEffect: this is a one-line write to window state that has to
        // land after every successful composition, including the one a theme change causes.
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkBars
                    isAppearanceLightNavigationBars = !darkBars
                }
            }
        }
    }
    CompositionLocalProvider(LocalThemeStyle provides appTheme.style) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
    }
}
