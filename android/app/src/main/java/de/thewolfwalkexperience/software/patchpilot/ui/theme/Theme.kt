// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

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
 * [AppTheme.Default] is stock Material following the system light/dark setting, with dynamic
 * colour on API 31 and later. [AppTheme.Steampunk] is a fixed dark look that does not follow the
 * system setting, built from [SteampunkColorScheme], [steampunkTypography] and [SteampunkShapes].
 * [LocalThemeStyle] is provided alongside for the things a token swap cannot produce, so screens
 * never branch on the raw [AppTheme].
 *
 * `MaterialTheme` and [content] are called from exactly one call site, never one per branch of a
 * theme `when`: [content] is `PatchPilotApp`'s `NavHost`, and two branches would give it two
 * positions in the composition, so a theme switch would tear the subtree down and reset
 * `rememberNavController()`'s back stack to the start destination.
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
    // The status bar follows the app's theme, not the system's: `enableEdgeToEdge()` picks the bar
    // icon colours from the system light/dark setting, which is right for [AppTheme.Default] but
    // would put dark icons over [AppTheme.Steampunk]'s near-black surface. Derived from the theme,
    // so a future theme gets this right by declaring what it is.
    val darkBars = when (appTheme) {
        AppTheme.Default -> darkTheme
        AppTheme.Steampunk -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // A SideEffect, not a LaunchedEffect: a one-line write to window state that has to land
        // after every successful composition, the one a theme change causes included.
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
