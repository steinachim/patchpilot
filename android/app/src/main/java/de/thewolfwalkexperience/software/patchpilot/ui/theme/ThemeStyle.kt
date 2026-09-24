// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Everything a screen needs to draw itself differently per [AppTheme], behind one interface rather
 * than an `if (theme == AppTheme.Steampunk)` at each call site.
 *
 * Not where `ColorScheme`, `Typography` and `Shapes` live: those are `MaterialTheme` tokens,
 * centralised in [PatchPilotTheme]'s one `when (appTheme)`, and every stock Material component
 * picks them up. This interface is for the things a token swap cannot produce - a compass standing
 * in for a spinner, a brass frame, a brass drag handle.
 *
 * A third theme is one implementation of this (see [SteampunkThemeStyle]) plus a mapping in
 * [AppTheme.style]; no screen file changes, since they read [LocalThemeStyle.current].
 */
interface ThemeStyle {
    /**
     * The preset row's drag handle - decorative, since the row itself carries the a11y label.
     *
     * Drawn by the theme rather than described to it: a text glyph dims by taking a colour, a
     * bitmap that carries its own colour dims by alpha.
     *
     * @param enabled false while the listing is still arriving, when dragging does nothing.
     */
    @Composable
    fun DragHandle(enabled: Boolean, modifier: Modifier = Modifier)

    /** The screen-level frame/border decoration, if this theme draws one. */
    fun screenFrame(base: Modifier): Modifier

    /** The app-wide background texture, applied once at the root ([MainActivity]'s `Surface`). */
    fun screenTexture(base: Modifier): Modifier

    /** The panel treatment for one preset row, including drag/drop-target highlighting. */
    fun rowPanel(base: Modifier, dragged: Boolean, dropTarget: Boolean): Modifier

    /** The decoration for the bank fast-scroll rail (`BankIndex`). */
    fun railDecoration(base: Modifier): Modifier

    /** Stands in for a bare spinner while the connect screen is searching for/opening a device. */
    @Composable
    fun ProgressIndicator(modifier: Modifier = Modifier)

    /** One candidate in the connect screen's device picker. */
    @Composable
    fun DeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier = Modifier)

    /** A bank caption row in the preset list (e.g. "Bank A"). */
    @Composable
    fun BankHeader(text: String, modifier: Modifier = Modifier)

    /**
     * Space to keep clear at each end of the bank rail, for decoration drawn over it: zero unless
     * a theme paints into the rail's ends, which [railDecoration] does after the content is laid
     * out, so the labels would otherwise sit underneath. With the Motif XS's eleven factory banks
     * the first and last labels land on the decoration.
     */
    val railEndInset: Dp get() = 0.dp
}

/** Material's disabled alpha, for the pieces a theme dims for itself rather than by token. */
internal const val DISABLED_HANDLE_ALPHA = 0.38f

/** Plain Material: every function hands back [base] untouched or renders the stock component. */
object DefaultThemeStyle : ThemeStyle {
    override fun screenFrame(base: Modifier) = base
    override fun screenTexture(base: Modifier) = base
    override fun rowPanel(base: Modifier, dragged: Boolean, dropTarget: Boolean) = base
    override fun railDecoration(base: Modifier) = base

    @Composable
    override fun DragHandle(enabled: Boolean, modifier: Modifier) {
        Text(
            "⠿",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                LocalContentColor.current
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_HANDLE_ALPHA)
            },
            modifier = modifier,
        )
    }

    @Composable
    override fun ProgressIndicator(modifier: Modifier) {
        CircularProgressIndicator(modifier)
    }

    @Composable
    override fun DeviceRow(label: String, sublabel: String, onClick: () -> Unit, modifier: Modifier) {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    override fun BankHeader(text: String, modifier: Modifier) {
        // The dividers, not the background: alternating row shading makes the neighbouring rows
        // surfaceVariant half the time, the same colour the header uses.
        Column(modifier.fillMaxWidth()) {
            HorizontalDivider()
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            HorizontalDivider()
        }
    }
}

/** [ThemeStyle] for the currently active [AppTheme]. */
val AppTheme.style: ThemeStyle
    get() = when (this) {
        AppTheme.Default -> DefaultThemeStyle
        AppTheme.Steampunk -> SteampunkThemeStyle
    }

/**
 * The active [ThemeStyle], provided by [PatchPilotTheme]. Defaults to [DefaultThemeStyle], so a
 * composable previewed outside it degrades to the plain look rather than crashing.
 */
val LocalThemeStyle = compositionLocalOf<ThemeStyle> { DefaultThemeStyle }
