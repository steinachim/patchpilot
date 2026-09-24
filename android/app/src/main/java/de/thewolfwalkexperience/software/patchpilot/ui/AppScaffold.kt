// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle

/**
 * The frame every screen sits in: a Material `Scaffold` with a real `TopAppBar`, which brings the
 * system back arrow (mirrored in a right-to-left locale, where a `←` glyph is not), a title in
 * the same place on every screen, content scrolling under the bar, and the window insets
 * edge-to-edge needs (see `MainActivity`).
 *
 * @param onBack null on a root screen, so the arrow's presence follows from the navigation graph.
 * @param backEnabled false while leaving would interrupt something that cannot be interrupted -
 *   see ProgramsScreen. Greys the arrow out rather than hiding it, since an arrow that vanishes
 *   for a few seconds reads as a layout bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchPilotScaffold(
    title: String,
    modifier: Modifier = Modifier,
    /** Applied to the title `Text` itself - what the hidden debug gesture hangs off. */
    titleModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    snackbarHostState: SnackbarHostState? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val theme = LocalThemeStyle.current
    Scaffold(
        // The frame decoration goes on the Scaffold's own outer bounds, not the TopAppBar or the
        // scrollable body - the same placement ConnectScreen's root uses (see
        // ThemeStyle.screenFrame).
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .let { theme.screenFrame(it) },
        topBar = {
            TopAppBar(
                title = {
                    // A preset or device name can be longer than the bar; ellipsis rather than
                    // wrapping.
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleModifier)
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, enabled = backEnabled) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                    }
                },
                actions = { actions() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState) { data ->
                    // Not the Material default: M3's `Snackbar` uses `inverseSurface`, a
                    // near-white slab in a dark theme, which next to an instrument in a dark room
                    // is a flashbulb every time a preset is selected.
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        actionColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        content = content,
    )
}

/**
 * A centred one-line state - loading, empty, or "nothing matched". One composable for every
 * nothing-to-show state, since a progress indicator dropped into a `Column` renders top-left.
 */
@Composable
fun CenteredMessage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** The muted styling an empty slot gets. */
@Composable
fun EmptySlotText(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.programs_slot_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
