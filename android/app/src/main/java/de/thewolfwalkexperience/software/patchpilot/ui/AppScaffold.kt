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
 * Written because the app had none. Each screen used to open with a bare `Column` whose first row
 * was a `TextButton("← Back")` beside a `titleLarge` `Text`, which diverges from the platform in
 * more ways than it looks:
 *
 * - The back affordance was a text glyph rather than the system arrow, and `←` does not mirror in
 *   a right-to-left locale. [Icons.AutoMirrored.Filled.ArrowBack] does.
 * - The title's position moved with the width of whatever sat beside it, so headings shifted
 *   between screens.
 * - Nothing scrolled *under* anything, so the app never showed the elevation cue Android uses to
 *   say "there is more above this".
 * - `Scaffold` is also what applies window insets, which is what makes edge-to-edge work at all
 *   (see `MainActivity`) rather than something each screen has to remember.
 *
 * @param onBack null on a root screen, which is what decides whether a back arrow is drawn - the
 *   arrow's presence should follow from the navigation graph rather than from a flag somebody has
 *   to keep in step with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchPilotScaffold(
    title: String,
    modifier: Modifier = Modifier,
    /** Applied to the title `Text` itself - what the hidden debug gesture hangs off. */
    titleModifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
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
                        IconButton(onClick = onBack) {
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
 * Exists because the app had three different ways of saying nothing-to-show, one of which was a
 * `CircularProgressIndicator` as a plain `Column` child, which renders in the top-left corner
 * rather than anywhere a user would look for it.
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
