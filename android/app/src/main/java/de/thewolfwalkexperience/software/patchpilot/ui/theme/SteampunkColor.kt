// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The "Brass & Aether" palette: brass primary,
 * copper secondary, verdigris tertiary (also what the connect screen's "connected" jewel and
 * device-row status read as), a rust error role, warm near-black surfaces, parchment text.
 *
 * Dark-only, deliberately - like a real WinAmp skin, this is a single committed look rather than
 * something that flips with the system's light/dark setting. [AppTheme.Default] is what still
 * follows the system.
 */
private val Brass = Color(0xFFB8863A)
private val BrassLight = Color(0xFFD9AB5C)
private val BrassDark = Color(0xFF6A4C1E)

/**
 * The boundary tones, held apart from [BrassDark]/[CopperDark] because they answer to a contrast
 * rule those do not.
 *
 * [BrassMuted] is `outline`, which draws the visible edge of *interactive* controls - an
 * `OutlinedTextField`, an `OutlinedButton`, a `SegmentedButton`. WCAG 1.4.11 wants 3:1 for that,
 * and this clears it against the lightest panel it can land on (3.6:1 on `PanelHigh`). It stays
 * well below [Brass], so it still reads as an edge rather than as the accent colour.
 *
 * [CopperMuted] is `outlineVariant`, the decorative-divider role. That one is *not* held to 3:1 -
 * Material's own baseline ships it at 1.6:1 light and 2.0:1 dark, because a separator that carries
 * no information is exempt - so this matches Material's dark level rather than inventing a
 * stricter rule for one theme.
 */
private val BrassMuted = Color(0xFF9A7130)
private val CopperMuted = Color(0xFF6E472A)
private val Copper = Color(0xFFA85F36)
private val CopperDark = Color(0xFF4A2C1A)
private val Verdigris = Color(0xFF6F9A83)
private val VerdigrisDark = Color(0xFF23362E)
private val Rust = Color(0xFFC97A5C)
private val RustDark = Color(0xFF4A241A)
private val Ink = Color(0xFF130D09)
private val Surface = Color(0xFF1B140D)
private val PanelRaised = Color(0xFF241A12)
private val PanelHigh = Color(0xFF2D2015)
private val Parchment = Color(0xFFEAD9B8)
private val ParchmentDim = Color(0xFFC2AD87)

val SteampunkColorScheme: ColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = Ink,
    primaryContainer = BrassDark,
    onPrimaryContainer = Parchment,
    inversePrimary = BrassDark,
    secondary = Copper,
    onSecondary = Ink,
    secondaryContainer = CopperDark,
    onSecondaryContainer = Parchment,
    tertiary = Verdigris,
    onTertiary = Ink,
    tertiaryContainer = VerdigrisDark,
    onTertiaryContainer = Parchment,
    error = Rust,
    onError = Ink,
    errorContainer = RustDark,
    onErrorContainer = Parchment,
    background = Ink,
    onBackground = Parchment,
    surface = Surface,
    onSurface = Parchment,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = ParchmentDim,
    surfaceContainer = PanelRaised,
    surfaceContainerHigh = PanelHigh,
    surfaceContainerHighest = PanelHigh,
    surfaceContainerLow = Surface,
    surfaceContainerLowest = Ink,
    outline = BrassMuted,
    outlineVariant = CopperMuted,
    inverseSurface = Parchment,
    inverseOnSurface = Ink,
    scrim = Ink,
)

/** Exposed for the bespoke Steampunk composables (gauge ticks, borders) that draw outside the
 *  `ColorScheme` roles above. */
object SteampunkAccents {
    val brassLight = BrassLight
    val brassDark = BrassDark
    val verdigris = Verdigris
    val rust = Rust
    val parchment = Parchment
    val parchmentDim = ParchmentDim
}
