// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.ui.theme.BarAction
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle

private const val TAG = "ProgramsScreen"

/** Material's medium width breakpoint - see `badgesFit` in [ProgramsScreen]. */
private const val BADGE_MIN_WIDTH_DP = 600

/**
 * An operation the instrument's state blocked, and what the user can do about it. [apply] and
 * [retry] are held, so the dialog renders whatever the family said without knowing which
 * operation raised it.
 */
internal data class BlockedOperation(
    val message: String,
    val detail: String?,
    val actionLabel: String,
    val apply: suspend () -> Unit,
    val retry: () -> Unit,
)

/**
 * Puts a failed edit on the screen and in the log, and returns the text for the screen: `logcat`
 * holds the whole conversation with the instrument, and without the log line nothing in it names
 * the operation that failed. The user-facing string is left as the instrument layer wrote it.
 */
internal fun reportFailure(what: String, error: Throwable, fallback: String): String {
    Log.w(TAG, "$what failed", error)
    return error.message ?: fallback
}

/**
 * The preset browser. Tapping a preset loads it; long-pressing its drag handle and dropping it
 * elsewhere relocates it, an occupied row being a swap and an empty slot a move
 * ([ProgramsController.onSwapDropped] picks between them from the loaded index). The list and
 * its gesture are [ProgramListBody].
 *
 * Three things follow what the instrument declares: the tag actions and the Share button appear
 * only where those facets exist; the drag handle and Rename only for edits it supports; and drag
 * is disabled until the index is whole, since a drop into a region that has not loaded has no
 * defined target.
 */
@Composable
fun ProgramsScreen(
    viewModel: InstrumentViewModel,
    onBack: () -> Unit,
    onOpenDebugMenu: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = LocalThemeStyle.current

    // Every instrument operation this screen can start, and the state each one owns - see
    // [ProgramsController]. What stays below is the state that only describes the view.
    val ops = viewModel.programs

    // Whether a row has room for its category badges beside the preset id: Material's medium
    // width breakpoint, a phone in landscape or a tablet. From the configuration rather than
    // measured per row, so every row agrees.
    val badgesFit = LocalConfiguration.current.screenWidthDp >= BADGE_MIN_WIDTH_DP

    val resources = LocalResources.current
    var showEmptySlots by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val reportShare = rememberDeviceReportShare(viewModel) { sharedAs, failedReads ->
        if (failedReads == 0) {
            ops.statusMessage = resources.getString(R.string.programs_shared_as, sharedAs)
        } else {
            // Through the failure channel, which does not time out: the report is shared all the
            // same, but whoever sent it needs to know, and a confirmation that fades would not say.
            ops.operationError = resources.getString(R.string.report_shared_incomplete, failedReads)
        }
    }
    val debugTaps = remember { DebugTapCounter() }
    // Captured at screen level rather than inside a dialog: it is needed *as* the dialog is being
    // disposed, by which point a controller resolved inside it is already going away.
    val keyboardController = LocalSoftwareKeyboardController.current
    // Pull-to-refresh, the gesture half of "the listing is cached, how do I make it re-read?";
    // the app bar's button is the discoverable half. `PullToRefreshBox` hoists the flag to the
    // caller, so this screen owns it.
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // Drag-to-reorder - see ProgramListDragState.
    val drag = rememberProgramListDragState(listState)

    // What the session offers, derived once per connection state rather than re-read here on
    // every recomposition; empty while disconnected, so recomposing through a reconnect is safe.
    // The hand-off to ConnectScreen is PatchPilotApp's.
    val session by viewModel.state.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val supportedEdits = capabilities.supportedEdits
    val canRelocate = EditOp.MOVE in supportedEdits || EditOp.SWAP in supportedEdits
    // Which listing is on screen.
    val browseScope by viewModel.scope.collectAsState()
    // Null where the instrument has no categories at all, which is what hides both row actions.
    val tagger = capabilities.tagger
    // Non-null where the instrument is usable but not vouched for. The connect screen gates on
    // this once; the banner below keeps it visible for the session.
    val advisory = capabilities.advisory

    // A Snackbar rather than a Toast: a Toast outlives the screen that raised it, cannot host an
    // action, and sits outside the app's accessibility tree.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ops.statusMessage) {
        val message = ops.statusMessage ?: return@LaunchedEffect
        // Cleared after showing: `showSnackbar` suspends until dismissal, and clearing first
        // would change this effect's key and cancel the call about to render it.
        snackbarHostState.showSnackbar(message, withDismissAction = true)
        ops.statusMessage = null
    }

    // Failures use the same Snackbar and do not time out: some report that the instrument's own
    // state is uncertain ("the instrument did not confirm the commit"), and a message saying
    // somebody's data may be in an unknown state must not disappear on a timer. One tap on the
    // dismiss action clears it.
    LaunchedEffect(ops.operationError, reportShare.error) {
        val message = ops.operationError ?: reportShare.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        ops.operationError = null
        reportShare.clearError()
    }

    // Rows render as they arrive rather than behind a spinner, on an instrument whose index costs
    // one round trip per slot. Collected in the ViewModel, so a rotation does not restart a
    // 93-second scan.
    val index by viewModel.listings.index.collectAsState()

    // Announces "this screen needs a listing"; the ViewModel decides whether that means work.
    // Keyed on Unit, not the session: restarting a reconnect's listing is the ViewModel's job
    // (it starts one whenever a session begins), and a rotation must leave a running scan be.
    LaunchedEffect(Unit) { viewModel.listings.start() }
    /*
     * Editing is off while the listing is still arriving, and again while an edit is running.
     * A drag dropped onto a region that has not loaded has no defined target, rename and delete
     * would be composed against a filling index, and a second tap during an edit would queue a
     * second edit behind the first. The gate covers dragging, the overflow menu and the device
     * report alike.
     *
     * Three things stay live, because none is composed against the index: the bank rail, which
     * changes nothing on the instrument; the filter and the empty-slot toggle, which only narrow
     * what is shown; and tapping a row to select it, which stores nothing and lands between two
     * dumps rather than inside one, since `SysExExchange` serializes requests.
     */
    val editsEnabled = index.complete && ops.busy == null

    // Which run this gesture started, so the effect below can tell it from any other; the
    // spinner stops when the scan completes rather than on a timer.
    var pendingRefresh by remember { mutableStateOf<Int?>(null) }
    // Keyed on the generation, not only on `complete`: a fast listing (demo mode, a Nord, a cache
    // hit) resets and completes inside one recomposition, so Compose never observes the
    // intermediate `false` and the spinner would hang on exactly those instruments.
    LaunchedEffect(index.generation, index.complete, index.error) {
        if (!isRefreshing) return@LaunchedEffect
        if (index.generation != pendingRefresh) return@LaunchedEffect
        if (index.complete || index.error != null) isRefreshing = false
    }
    // Shared by the pull gesture and the app bar's button, so both give the same feedback and
    // obey the same rule ([refreshAllowed]). Guards re-entry itself rather than trusting
    // `enabled`, which only reflects the last recomposition, and which the pull gesture does not
    // have at all: `isRefreshing` is local Compose state, so the second of two quick taps sees the
    // first's write in the same snapshot.
    fun startRefresh() {
        if (isRefreshing || !refreshAllowed(index, editRunning = ops.busy != null)) return
        isRefreshing = true
        pendingRefresh = viewModel.listings.refresh()
    }
    val programs = index.slots

    // Built from the layout, once per session and scope rather than on every listing update: a
    // session is published with its layout settled. Up to 1,633 rows.
    val scopeSlots = remember(capabilities, browseScope) { viewModel.listings.allSlots(browseScope) }
    // Everything the list needs, derived in one pure pass - see [buildProgramListing], which
    // holds the occupancy rules and is testable without Compose.
    val unreadable = remember(index.failures) { index.unreadable }
    val listing = remember(programs, unreadable, showEmptySlots, searchText, ops.picking, scopeSlots) {
        buildProgramListing(
            reported = programs,
            allSlots = scopeSlots,
            unreadable = unreadable,
            // Only the user listing has empty slots to show, and hiding the checkbox in the other
            // two does not reset it: left ticked, it would render the favorites listing empty,
            // since that scope has no address space.
            showEmptySlots = showEmptySlots && browseScope == PresetScope.USER,
            picking = ops.picking,
            searchText = searchText,
        )
    }
    val visibleRows = listing.rows
    // The user listing's free slots, whatever is on screen: a copy can only be written to a
    // writable slot, so the destination pool belongs to the user scope. From `listing` it would
    // be empty in the factory scope, where every row holds a voice.
    val userIndex by viewModel.listings.userIndex.collectAsState()
    val userSlots = remember(capabilities) { viewModel.listings.allSlots(PresetScope.USER) }
    val copyDestinations =
        remember(userIndex, userSlots) { freeSlots(userIndex.slots, userSlots, userIndex.unreadable) }
    // Hidden rather than disabled where there is nowhere to copy to. Gated on the user listing
    // being complete rather than on `editsEnabled`, which describes whichever listing is
    // displayed - and the factory one completes the moment it is opened, before the destinations
    // are known.
    val canCopy = EditOp.COPY in supportedEdits && userIndex.complete && copyDestinations.isNotEmpty()
    val rules = RowRules(
        canRename = EditOp.RENAME in supportedEdits,
        canDelete = EditOp.DELETE in supportedEdits,
        canRelocate = canRelocate,
        canCopy = canCopy,
        canSelect = capabilities.canSelect,
        // Per-row rather than per-instrument, because the favorites listing mixes the two: a
        // favorited factory voice and a user voice sit in one list, and only one can be renamed.
        readOnlyBanks = capabilities.readOnlyBanks,
        hasCategories = tagger != null,
        categoryCount = tagger?.assignmentCount ?: 1,
        browseScope = browseScope,
        editsEnabled = editsEnabled,
    )

    // While a row is dragged near an edge of the viewport, keep scrolling so targets outside the
    // visible range can be reached. Keyed on draggedIndex, so ending the drag cancels the loop.
    LaunchedEffect(drag.draggedIndex) {
        if (drag.draggedIndex == null) return@LaunchedEffect
        drag.autoScroll { i -> visibleRows.getOrNull(i) is ProgramRow.ProgramEntry }
    }

    // The system-back half of `backEnabled` below: disabling the arrow does nothing for the
    // gesture or the hardware key, and with `enableOnBackInvokedCallback` set a predictive-back
    // swipe would animate this screen away mid-write. Enabled only while an edit is running, so
    // predictive back is not intercepted the rest of the time.
    BackHandler(enabled = ops.busy != null) {
        // Empty: refusing the gesture is the behaviour, and the progress line says what is running.
    }

    PatchPilotScaffold(
        // The instrument's own name, not the word "Presets". This is the only screen that shows
        // which instrument the app is talking to, which matters when more than one is plugged in
        // at once. Falls back only while disconnecting.
        title = capabilities.instrumentName ?: stringResource(R.string.programs_title_fallback),
        // Five taps on the instrument name opens the debug menu: undiscoverable by design, since
        // everything behind it is for whoever is developing against an instrument. `pointerInput`
        // rather than `clickable`, which would add a click semantics node and have TalkBack
        // announce the title as a button that appears to do nothing, one tap of five being no
        // activation at all.
        titleModifier = Modifier.pointerInput(Unit) {
            detectTapGestures { if (debugTaps.tap()) onOpenDebugMenu() }
        },
        // Back closes the session and returns to the connect screen's picker.
        onBack = onBack,
        // Off while an edit is in flight, which `busy` only ever is - never the index scan, which
        // leaves back available for the whole 93 seconds it can take. Not the safety itself
        // (launchEdit and exchangeAfterAll are), but what tells the user why nothing happened.
        backEnabled = ops.busy == null,
        snackbarHostState = snackbarHostState,
        // The discoverable half of "re-read from the instrument", the pull gesture being the
        // invisible one - which matters on a screen whose rows may have come from memory.
        actions = {
            // In demo mode too, where somebody exploring the app is most likely to be.
            IconButton(
                // Same as the pull gesture: drop the cache, then re-read from the instrument.
                onClick = ::startRefresh,
                enabled = refreshAllowed(index, editRunning = ops.busy != null),
            ) {
                // Composed only while refreshing: an infinite transition keeps the frame clock
                // ticking for as long as it is in the composition, whether or not its value is
                // read.
                val rotation = if (isRefreshing) {
                    rememberInfiniteTransition(label = "refresh-spin").animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
                        label = "angle",
                    )
                } else {
                    null
                }
                theme.ActionIcon(
                    BarAction.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh),
                    modifier = Modifier.graphicsLayer { rotationZ = rotation?.value ?: 0f },
                )
            }
            IconButton(onClick = onOpenSettings) {
                theme.ActionIcon(BarAction.Settings, stringResource(R.string.cd_settings))
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ProgramListHeader(
                scopes = capabilities.browsingScopes,
                browseScope = browseScope,
                onSelectScope = viewModel::setScope,
                advisory = advisory,
                ops = ops,
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                showEmptySlots = showEmptySlots,
                onShowEmptySlotsChange = { showEmptySlots = it },
                index = index,
            )

            // Bound to a local because `index` is a delegated property and cannot smart-cast.
            val scanError = index.error
            when {
                // This screen stays up through a resume's Disconnected/Searching/Opening dip (see
                // PatchPilotApp's session-lost effect), so the reconnect shows as the wait it is
                // rather than leaving the old rows under a "Not Connected" title.
                session !is ConnectionState.Connected -> ConnectingWait()

                scanError != null ->
                    Text(stringResource(R.string.programs_error, scanError), color = MaterialTheme.colorScheme.error)

                // The theme's own indicator, the same one ConnectScreen shows while it opens a
                // device, and centred like the empty states below - this Column has no horizontal
                // alignment of its own. No label during a refresh: the session is up, and the
                // spinning refresh button already says what is running.
                index.slots.isEmpty() && index.loading -> ConnectingWait(showLabel = !isRefreshing)

                else -> ProgramListBody(
                    listing = listing,
                    index = index,
                    rules = rules,
                    ops = ops,
                    drag = drag,
                    listState = listState,
                    isRefreshing = isRefreshing,
                    onRefresh = ::startRefresh,
                    searchText = searchText,
                    onClearFilter = { searchText = "" },
                    badgesFit = badgesFit,
                    virtualBanks = capabilities.virtualBankLabels,
                    // Drawn in the rail instead of the full label, which does not fit at the rail's width.
                    bankRailLabels = capabilities.bankRailLabels,
                    canSetFavorite = viewModel::canSetFavorite,
                    canSetCategories = viewModel::canSetCategories,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            // For a device the catalog does not recognise, or one on firmware nobody has tested this
            // app against: in both cases its owner is the one person who can send back what the app
            // read off it. For a known instrument the report lives in the debug menu instead.
            if (capabilities.hasReport && (capabilities.isUnknownDevice || advisory != null)) {
                TextButton(
                    onClick = { ops.pendingShare = reportShare.dialog() },
                    // Off while a report or a listing is running: the report walks every item, and
                    // two long reads would share one serialized bus.
                    enabled = !reportShare.running && editsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(reportShare.label)
                }
            }
        }
    }

    val notice by viewModel.pendingNotice.collectAsState()
    notice?.let { shown ->
        InstrumentNoticeDialog(shown, onDismiss = { hide -> viewModel.dismissNotice(shown, hide) })
    }

    ops.pendingShare?.let { pending ->
        ShareFilenameDialog(pending, onDismiss = { ops.pendingShare = null })
    }

    ops.deleteTarget?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            emulated = EditOp.DELETE in capabilities.emulatedEdits,
            onConfirm = { ops.onDeleteConfirmed(target) },
            onDismiss = { ops.deleteTarget = null },
        )
    }

    ops.blocked?.let { pending ->
        BlockedOperationDialog(
            pending = pending,
            onApply = { ops.onBlockedRemedyConfirmed(pending) },
            onDismiss = { ops.blocked = null },
        )
    }

    ops.renameTarget?.let { target ->
        HideKeyboardOnDismiss(keyboardController)
        RenameDialog(
            target = target,
            text = ops.renameText,
            onTextChange = { ops.renameText = it },
            maxNameLength = capabilities.maxNameLength,
            nameProblem = viewModel::presetNameProblem,
            onConfirm = { ops.onRenameConfirmed(target, ops.renameText) },
            onDismiss = { ops.renameTarget = null },
        )
    }

    // Guarded on `tagger` as well as on their target, since a disconnect between the read and
    // the dialog opening would leave a dialog with no facet to save through.
    tagger?.let { facet ->
        ops.favoriteTarget?.let { target ->
            SetFavoriteDialog(
                target = target.slot,
                tags = target.tags,
                taxonomy = facet.taxonomy,
                assignmentCount = facet.assignmentCount,
                onConfirm = { under -> ops.onFavoriteConfirmed(target.slot, under) },
                onDismiss = { ops.favoriteTarget = null },
            )
        }

        ops.categoriesTarget?.let { target ->
            SetCategoriesDialog(
                target = target.slot,
                tags = target.tags,
                taxonomy = facet.taxonomy,
                assignmentCount = facet.assignmentCount,
                allowsUnassigned = facet.allowsUnassigned,
                onConfirm = { picked -> ops.onCategoriesConfirmed(target.slot, picked) },
                onDismiss = { ops.categoriesTarget = null },
            )
        }
    }
}

/**
 * Counts taps on the instrument name, and says when five have landed close enough together to
 * have been meant - the window matters as much as the count, or five taps spread over an
 * afternoon would eventually open the screen. Plain state in the composition: a half-finished
 * gesture is not worth surviving anything.
 */
private class DebugTapCounter {
    private var taps: List<Long> = emptyList()

    /** True when this tap completed the gesture, which also resets the count. */
    fun tap(): Boolean {
        // Monotonic: a wall-clock change cannot open the menu or swallow a tap.
        val now = SystemClock.uptimeMillis()
        taps = taps.filter { now - it <= WINDOW_MS } + now
        if (taps.size < TAPS_TO_OPEN) return false
        taps = emptyList()
        return true
    }

    private companion object {
        const val TAPS_TO_OPEN = 5
        const val WINDOW_MS = 3_000L
    }
}

/**
 * The wait shown in place of the list: the theme's own indicator over "Connecting…", as
 * ConnectScreen shows while it searches. Used for the first read after a connect and for the
 * reconnect dip a resume goes through, which are the same wait from the user's side; without
 * [showLabel] for a refresh, which re-reads over a session that is already up.
 */
@Composable
private fun ConnectingWait(showLabel: Boolean = true) {
    val theme = LocalThemeStyle.current
    CenteredMessage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            theme.ProgressIndicator()
            if (showLabel) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.programs_reconnecting))
            }
        }
    }
}

/**
 * Dismisses the on-screen keyboard when the dialog that owns this leaves composition - tied to
 * disposal rather than to each button, since a dialog with a text field has three ways out.
 */
@Composable
private fun HideKeyboardOnDismiss(keyboardController: SoftwareKeyboardController?) {
    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }
}
