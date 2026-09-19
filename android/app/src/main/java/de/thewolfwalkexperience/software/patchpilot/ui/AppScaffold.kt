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
 * The frame every screen sits in: a Material `Scaffold` with a real `TopAppBar`.
 *
 * One shared frame rather than a per-screen header row, because the platform's own does several
 * things a hand-rolled one does not:
 *
 * - The back affordance is the system arrow, [Icons.AutoMirrored.Filled.ArrowBack], which mirrors
 *   in a right-to-left locale where a `←` glyph does not.
 * - The title sits in the same place on every screen rather than moving with whatever is beside
 *   it.
 * - Content scrolls *under* the bar, which is the elevation cue Android uses to say "there is
 *   more above this".
 * - `Scaffold` is also what applies window insets, which is what makes edge-to-edge work at all
 *   (see `MainActivity`) rather than something each screen has to remember.
 *
 * @param onBack null on a root screen, which is what decides whether a back arrow is drawn - the
 *   arrow's presence should follow from the navigation graph rather than from a flag somebody has
 *   to keep in step with it.
 * @param backEnabled false while leaving would interrupt something that cannot be interrupted -
 *   see ProgramsScreen's use of it. Deliberately greys the arrow out rather than hiding it: an
 *   arrow that vanishes for a few seconds reads as a layout bug, while a disabled one reads as
 *   "not now", which is what is actually meant. A screen with no such state leaves this true.
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
        // The frame decoration lives on the Scaffold's own outer bounds, not on the TopAppBar or
        // the scrollable body - one consistent placement rule shared with ConnectScreen's root
        // (see ThemeStyle.screenFrame's doc comment), rather than two different rivet treatments
        // that moved between screens.
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .let { theme.screenFrame(it) },
        topBar = {
            TopAppBar(
                title = {
                    // A preset name or a device name can be longer than the bar; ellipsis rather
                    // than wrapping, which is what every other Android app does here.
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
                    // **Deliberately not the Material default.** M3's `Snackbar` uses
                    // `inverseSurface`/`inverseOnSurface`, which in a dark theme means a near-white
                    // slab - by design, so it contrasts with the app rather than blending in. In a
                    // dark room, next to an instrument, that is a flashbulb every time a preset is
                    // selected. These colours keep it legible and on-theme instead.
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
 * A centred one-line state - loading, empty, or "nothing matched".
 *
 * One composable for every nothing-to-show state, so they all centre the same way; a progress
 * indicator dropped in as a plain `Column` child renders in the top-left corner rather than
 * anywhere a user would look for it.
 */
@Composable
fun CenteredMessage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/** The muted styling an empty slot gets, in place of the old `--- EMPTY ---` text. */
@Composable
fun EmptySlotText(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.programs_slot_empty),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
