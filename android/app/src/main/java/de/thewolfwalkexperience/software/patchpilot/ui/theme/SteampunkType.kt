// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import de.thewolfwalkexperience.software.patchpilot.R

/**
 * Cinzel ships as a single variable font (weight axis only). Declaring the two instances the
 * type scale below actually uses as separate [Font] entries - each tagged with the [FontWeight]
 * a `TextStyle` would ask for - is what lets a plain `fontWeight = FontWeight.Bold` resolve to
 * the real Bold instance instead of a synthetic (faux) one.
 */
@OptIn(ExperimentalTextApi::class)
private val CinzelFamily = FontFamily(
    Font(
        R.font.cinzel,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.cinzel,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val ShareTechMonoFamily = FontFamily(Font(R.font.share_tech_mono, weight = FontWeight.Normal))

/**
 * Cinzel across the roles the app's headings and titles actually resolve to - `TopAppBar`'s
 * title, `ListItem`'s `headlineContent` (patch names), dialog titles and empty-state headings
 * all read `titleLarge`/`titleMedium`/`headlineSmall` by default, so this reaches every one of
 * them with no per-composable change. Share Tech Mono across `body*`/`label*` gives IDs, badges,
 * button labels and captions a mechanical-counter feel - `ListItem`'s
 * `supportingContent` (the "A:01  v2" line) is `bodyMedium`.
 *
 * Sizes, line heights and letter spacing are left exactly as [base] (Material's defaults, or
 * whatever the dynamic-color base already resolved) - only the family and weight change, so the
 * type scale still reads as one system rather than two unrelated ones stapled together.
 */
fun steampunkTypography(base: Typography): Typography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Bold),
    titleSmall = base.titleSmall.copy(fontFamily = CinzelFamily, fontWeight = FontWeight.Normal),
    bodyLarge = base.bodyLarge.copy(fontFamily = ShareTechMonoFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = ShareTechMonoFamily),
    bodySmall = base.bodySmall.copy(fontFamily = ShareTechMonoFamily),
    labelLarge = base.labelLarge.copy(fontFamily = ShareTechMonoFamily),
    labelMedium = base.labelMedium.copy(fontFamily = ShareTechMonoFamily),
    labelSmall = base.labelSmall.copy(fontFamily = ShareTechMonoFamily),
)
