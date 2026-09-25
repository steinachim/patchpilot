// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.ui.theme.BarAction
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope

private const val TAG = "ProgramsScreen"

/** Material's medium width breakpoint - see `badgesFit` in [ProgramsScreen]. */
private const val BADGE_MIN_WIDTH_DP = 600

/** The measurements the preset list and its floating drag ghost have to agree on, so the ghost sits over the row it was lifted from. */
private object ProgramListMetrics {
    /** Clears the bank rail (48.dp) plus its 4.dp inset, so no row slides underneath it. */
    val listEndPadding = 56.dp

    /** The drag handle's column, reserved even on rows with no glyph so headlines stay aligned. */
    val handleSize = 32.dp

    /** Material's minimum touch target, and the only way to jump banks. */
    val railWidth = 48.dp

    /** The dragged row, left in place and dimmed while the ghost follows the finger. */
    const val DRAGGED_ALPHA = 0.3f

    /** Material's disabled alpha - what a row that cannot be tapped right now reads as. */
    const val DISABLED_ALPHA = 0.38f

    /** An empty slot: legible, clearly not a preset. */
    const val EMPTY_SLOT_ALPHA = 0.5f
}

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

// PullToRefreshBox is marked experimental in Material3 1.4; opted in once for the whole screen.
/**
 * The preset browser. Tapping a preset loads it; long-pressing its drag handle and dropping it
 * elsewhere relocates it, an occupied row being a swap and an empty slot a move
 * ([ProgramsController.onSwapDropped] picks between them from the loaded index).
 *
 * The drag gesture is detected on a Box wrapping the whole LazyColumn rather than on a row, and
 * hit-tests the touch position against `listState.layoutInfo` plus a left-edge band for the
 * handle's column: a per-row `pointerInput` would be cancelled the moment the dragged row
 * scrolled out of the composed range. The dragged row stays in place, dimmed, and a separate
 * ghost ListItem positioned from the finger's own y follows the finger, since LazyColumn composes
 * from each item's untranslated layout slot and would tear the row down.
 *
 * Bank captions are interleaved into the same LazyColumn, so the drag indices are into
 * `visibleRows` rather than the preset list, and headers and rows are not the same height - which
 * is why a position resolves to a row through `hitRowInfo` rather than by dividing by a row
 * height. Row shading alternates, and the dragged row and its drop target are set through
 * ListItem's own `colors`, which an external `Modifier.background()` would sit beneath.
 *
 * Three things follow what the instrument declares: the tag actions and the Share button appear
 * only where those facets exist; the drag handle and Rename only for edits it supports; and drag
 * is disabled until the index is whole, since a drop into a region that has not loaded has no
 * defined target.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val ops = rememberProgramsController(viewModel)

    // Whether a row has room for its category badges beside the preset id: Material's medium
    // width breakpoint, a phone in landscape or a tablet. From the configuration rather than
    // measured per row, so every row agrees.
    val badgesFit = LocalConfiguration.current.screenWidthDp >= BADGE_MIN_WIDTH_DP

    val resources = LocalResources.current
    var showEmptySlots by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // Read in the ViewModel, so a rotation mid-read costs nothing (see DeviceReportRunner). The
    // stem the user typed is saved, so the share still happens if the screen was recreated.
    val reportRunner = viewModel.deviceReportRunner
    val reportState by reportRunner.state.collectAsState()
    val reportError by reportRunner.error.collectAsState()
    var pendingReportStem by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val shareReportTitle = stringResource(R.string.programs_share_report)
    val jsonSuffix = stringResource(R.string.programs_json_suffix)
    val readingLabel = stringResource(R.string.share_reading_device)
    LaunchedEffect(reportState, pendingReportStem) {
        val stem = pendingReportStem ?: return@LaunchedEffect
        val done = reportState as? DeviceReportState.Done ?: return@LaunchedEffect
        pendingReportStem = null
        reportRunner.dismiss()
        val shared = shareTextReport(context, "$stem$jsonSuffix", done.json, JSON_MIME_TYPE, shareReportTitle)
        if (done.isComplete) {
            ops.statusMessage = resources.getString(R.string.programs_shared_as, shared)
        } else {
            // Through the failure channel, which does not time out: the report is shared all the
            // same, but whoever sent it needs to know, and a confirmation that fades would not say.
            ops.operationError = resources.getString(R.string.report_shared_incomplete, done.failures.size)
        }
    }
    val debugTaps = remember { DebugTapCounter() }
    val scope = rememberCoroutineScope()
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

    // What the connected instrument offers, read once per composition and keyed on the session:
    // these change exactly when the instrument changes, and nothing about a plain getter tells
    // Compose that. The accessors are null-safe (see `connected()`), so recomposing while
    // disconnected is safe. The hand-off to ConnectScreen is PatchPilotNavHost's.
    val session by viewModel.state.collectAsState()
    val supportedEdits = remember(session) { viewModel.supportedEdits }
    val canRename = EditOp.RENAME in supportedEdits
    val canDelete = EditOp.DELETE in supportedEdits
    val canRelocate = EditOp.MOVE in supportedEdits || EditOp.SWAP in supportedEdits
    val canCopyOp = EditOp.COPY in supportedEdits
    val canSelect = remember(session) { viewModel.canSelect }
    // Which listing is on screen, and which the instrument offers; one scope shows no selector.
    val browseScope by viewModel.scope.collectAsState()
    val browsingScopes = remember(session) { viewModel.browsingScopes() }
    // Per-row rather than per-instrument, because the favorites listing mixes the two: a
    // favorited factory voice and a user voice sit in one list, and only one can be renamed.
    val readOnlyBanks = remember(session) { viewModel.readOnlyBanks() }
    // Null where the instrument has no categories at all, which is what hides both row actions.
    val tagger = remember(session) { viewModel.tagger }

    val hasReport = remember(session) { viewModel.hasReport }
    val isUnknownDevice = remember(session) { viewModel.isUnknownDevice }
    // Non-null where the instrument is usable but not vouched for. The connect screen gates on
    // this once; the banner below keeps it visible for the session.
    val advisory = remember(session) { viewModel.advisory }
    // Null where the instrument declares no limit; where it does, the rename field enforces it
    // rather than letting the instrument truncate silently.
    val maxNameLength = remember(session) { viewModel.maxPresetNameLength }

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
    LaunchedEffect(ops.operationError, reportError) {
        val message = ops.operationError ?: reportError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        ops.operationError = null
        reportRunner.clearError()
    }

    // Rows render as they arrive rather than behind a spinner, on an instrument whose index costs
    // one round trip per slot. Collected in the ViewModel, so a rotation does not restart a
    // 93-second scan.
    val index by viewModel.index.collectAsState()

    // Announces "this screen needs a listing"; the ViewModel decides whether that means work.
    // Keyed on Unit, not the session: restarting a reconnect's listing is the ViewModel's job
    // (it calls startIndex() when a session begins), and a rotation must leave a running scan be.
    LaunchedEffect(Unit) { viewModel.startIndex() }
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
    // Shared by the pull gesture and the app bar's button, so both give the same feedback.
    // Guards re-entry itself rather than trusting `enabled`, which only reflects the last
    // recomposition: `isRefreshing` is local Compose state, so the second of two quick taps sees
    // the first's write in the same snapshot. Not while an edit is running either, since the
    // edit's own reload follows it.
    fun startRefresh() {
        if (isRefreshing || ops.busy != null) return
        isRefreshing = true
        pendingRefresh = viewModel.refreshIndex()
    }
    val programs = index.slots

    // Everything the list needs, derived in one pure pass - see [buildProgramListing], which
    // holds the occupancy rules and is testable without Compose. `browseScope` is a key even
    // though it is not an argument, since `viewModel.allSlots(scope)` is a plain call.
    val listing = remember(programs, showEmptySlots, searchText, ops.picking, browseScope) {
        buildProgramListing(
            reported = programs,
            allSlots = viewModel.allSlots(browseScope),
            // Only the user listing has empty slots to show, and hiding the checkbox in the other
            // two does not reset it: left ticked, it would render the favorites listing empty,
            // since that scope has no address space.
            showEmptySlots = showEmptySlots && browseScope == PresetScope.USER,
            picking = ops.picking,
            searchText = searchText,
        )
    }
    val visibleRows = listing.rows
    val occupied = listing.occupied
    // Drawn in the rail instead of the full label, which does not fit at the rail's width.
    val bankRailLabels = remember(index) { viewModel.bankRailLabels() }
    // The user listing's free slots, whatever is on screen: a copy can only be written to a
    // writable slot, so the destination pool belongs to the user scope. From `listing` it would
    // be empty in the factory scope, where every row holds a voice.
    val userIndex by viewModel.userIndex.collectAsState()
    val copyDestinations = remember(userIndex, session) {
        freeSlots(userIndex.slots, viewModel.allSlots(PresetScope.USER))
    }
    // Hidden rather than disabled where there is nowhere to copy to. Gated on the user listing
    // being complete rather than on `editsEnabled`, which describes whichever listing is
    // displayed - and the factory one completes the moment it is opened, before the destinations
    // are known.
    val canCopy = canCopyOp && userIndex.complete && copyDestinations.isNotEmpty()

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
        title = viewModel.instrumentName ?: stringResource(R.string.programs_title_fallback),
        // Five taps on the instrument name opens the debug menu: undiscoverable by design, since
        // everything behind it is for whoever is developing against an instrument. `pointerInput`
        // rather than `clickable`, which would add a click semantics node and have TalkBack
        // announce the title as a button that appears to do nothing, one tap of five being no
        // activation at all.
        titleModifier = Modifier.pointerInput(Unit) {
            detectTapGestures { if (debugTaps.tap()) onOpenDebugMenu() }
        },
        // Back returns to the connect screen, which re-scans on arrival.
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
                enabled = (index.complete || index.error != null) && ops.busy == null,
            ) {
                val rotation by rememberInfiniteTransition(label = "refresh-spin").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
                    label = "angle",
                )
                theme.ActionIcon(
                    BarAction.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh),
                    modifier = Modifier.graphicsLayer { rotationZ = if (isRefreshing) rotation else 0f },
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
        // Only where there is a choice to make; an instrument with one listing shows no selector.
        //
        // Disabled rather than hidden while an edit runs or a copy destination is being picked:
        // both are states the user is *in the middle of*, and a control that vanishes and comes
        // back shifts everything below it - in a list they may be reading at the time.
        if (browsingScopes.size > 1) {
            ScopeSelector(
                scopes = browsingScopes,
                selected = browseScope,
                // Off during an edit, not during a listing: a factory listing costs no round
                // trips, a favorites read interleaves with a running scan (`SysExExchange` locks
                // one request/reply at a time), and each scope keeps its own job. An edit is
                // different - `runEdit` reloads whichever scope is current when it finishes - and
                // picking a copy destination owns the list until it resolves.
                enabled = ops.busy == null && !ops.picking,
                onSelect = viewModel::setScope,
            )
        }
        // Stays for the whole session: the reason has to still be on screen when a rename goes
        // wrong an hour after the connect screen asked.
        if (advisory != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    advisory,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        // Picking a destination happens in the list itself rather than behind a dialog: occupied
        // rows grey out, empty rows show regardless of the filter, and tapping one resolves the
        // pick (see [ProgramsController.onPickConfirmed]). This banner is the persistent sign
        // that the mode is active, and its Cancel the only way out besides picking a target.
        ops.pickSource?.let { source ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            when (ops.pickIntent) {
                                PickIntent.COPY -> R.string.programs_copy_title
                                PickIntent.MOVE -> R.string.programs_move_title
                            },
                            source.name ?: source.displayId,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ops.endPicking() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        // Hidden rather than disabled while picking: neither affects the list in that mode, and
        // the banner above already says what mode this is.
        if (!ops.picking) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.programs_filter_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // One control, not two nodes, so TalkBack reads it as a single checkbox. Only in the
            // user listing: every factory slot holds a voice, and the favorites listing has no
            // address space to have gaps in.
            if (browseScope == PresetScope.USER) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = showEmptySlots,
                        onValueChange = { showEmptySlots = it },
                        role = Role.Checkbox,
                    )
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onCheckedChange = null leaves the click to the Row above, and also drops the
                // padding a standalone Checkbox brings, hence the explicit Spacer.
                Checkbox(checked = showEmptySlots, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.programs_show_empty))
            }
        }
        index.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            // Unreadable slots are a finding rather than a failure of the whole scan.
            Text(
                pluralStringResource(
                    R.plurals.programs_unreadable_slots, failures.size, failures.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ops.busy?.takeIf { it.showProgress }?.let { (what, _) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(what, style = MaterialTheme.typography.labelMedium)
                // Indeterminate: the instrument reports no progress through an edit.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        index.progress?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(progress.label, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Bound to a local because `index` is a delegated property and cannot smart-cast.
        val scanError = index.error
        when {
            // This screen stays up through a resume's Disconnected/Searching/Opening dip (see
            // PatchPilotNavHost's session-lost effect), so the reconnect shows as the wait it is
            // rather than leaving the old rows under a "Not Connected" title.
            session !is ConnectionState.Connected -> ConnectingWait()
            scanError != null ->
                Text(stringResource(R.string.programs_error, scanError), color = MaterialTheme.colorScheme.error)
            // The theme's own indicator, the same one ConnectScreen shows while it opens a
            // device, and centred like the empty states below - this Column has no horizontal
            // alignment of its own.
            index.slots.isEmpty() && index.loading -> ConnectingWait()
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                // The same function the app bar's refresh button calls, so the two agree.
                onRefresh = ::startRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(visibleRows, canRelocate, index.complete, ops.picking, browseScope) {
                        // Off while the index is still arriving (a drop into a region that has not
                        // loaded has no target), on an instrument that cannot relocate presets,
                        // and while picking a destination, which is resolved by a tap.
                        if (!canRelocate || !index.complete || ops.picking) return@pointerInput
                        // A user-listing gesture: a read-only bank refuses the write, and the
                        // favorites listing is not an address space, so "the row below" is not a
                        // destination.
                        if (browseScope != PresetScope.USER) return@pointerInput
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                drag.begin(offset) { i ->
                                    val entry = visibleRows.getOrNull(i) as? ProgramRow.ProgramEntry
                                    entry != null && entry.item.address in occupied
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                drag.update(dragAmount.y) { i ->
                                    visibleRows.getOrNull(i) is ProgramRow.ProgramEntry
                                }
                            },
                            onDragEnd = {
                                val (from, to) = drag.end() ?: return@detectDragGesturesAfterLongPress
                                val source = visibleRows.getOrNull(from) as? ProgramRow.ProgramEntry
                                val target = visibleRows.getOrNull(to) as? ProgramRow.ProgramEntry
                                if (source != null && target != null) {
                                    ops.onSwapDropped(source.item, target.item, occupied)
                                }
                            },
                            onDragCancel = { drag.cancel() },
                        )
                    },
            ) {
                // A filter that matches nothing says so, and offers a way out.
                if (visibleRows.isEmpty() && index.complete) {
                    CenteredMessage {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (searchText.isBlank()) {
                                    // An instrument full of presets with none marked is not one
                                    // that "reports no presets".
                                    if (browseScope == PresetScope.FAVORITES) {
                                        stringResource(R.string.programs_no_favorites)
                                    } else {
                                        stringResource(R.string.programs_no_presets)
                                    }
                                } else {
                                    stringResource(R.string.programs_no_matches, searchText)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchText.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { searchText = "" }) { Text(stringResource(R.string.action_clear_filter)) }
                            } else if (browseScope == PresetScope.FAVORITES) {
                                // Says where a mark is set - in a row menu, or on the instrument
                                // - so an empty list is not a dead end.
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.programs_no_favorites_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = ProgramListMetrics.listEndPadding),
                ) {
                    itemsIndexed(
                        visibleRows,
                        key = { _, row ->
                            when (row) {
                                is ProgramRow.BankHeader -> "header:${row.bank}"
                                is ProgramRow.ProgramEntry -> "slot:${row.item.address.bank}:${row.item.address.slot}"
                            }
                        },
                    ) { rowIndex, row ->
                        when (row) {
                            is ProgramRow.BankHeader ->
                                theme.BankHeader(stringResource(R.string.programs_bank_header, row.bank))
                            is ProgramRow.ProgramEntry -> {
                                val program = row.item
                                val isEmpty = program.address !in occupied
                                val isDragged = drag.draggedIndex == rowIndex
                                val isDropTarget = drag.dropTargetIndex == rowIndex &&
                                    drag.draggedIndex != null && drag.draggedIndex != rowIndex
                                // Which rows a tap can act on depends on what is being picked
                                // for: a copy needs somewhere empty, a move can land anywhere but
                                // its own source.
                                val isPickTarget = ops.picking && when (ops.pickIntent) {
                                    PickIntent.COPY -> isEmpty
                                    PickIntent.MOVE -> program.address != ops.pickSource?.address
                                }
                                val rowColor = when {
                                    isDragged -> MaterialTheme.colorScheme.primaryContainer
                                    isDropTarget -> MaterialTheme.colorScheme.secondaryContainer
                                    row.programIndex % 2 == 0 -> MaterialTheme.colorScheme.surface
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                                ListItem(
                                    headlineContent = {
                                        if (program.name == null) EmptySlotText() else Text(program.name)
                                    },
                                    supportingContent = {
                                        // Badges are short family-supplied strings rendered
                                        // uniformly, pushed to the trailing edge so a column of
                                        // them lines up. The badge text takes the remaining width
                                        // rather than a spacer, so a long one ellipsizes instead
                                        // of shoving the id off the row.
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(program.displayId)
                                            // Only where the row is wide enough: two full labels
                                            // beside the id do not fit a portrait phone, and the
                                            // categories stay a row-menu question there.
                                            if (badgesFit) {
                                                if (program.badges.isNotEmpty()) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = program.badges.joinToString(", "),
                                                        modifier = Modifier.weight(1f),
                                                        textAlign = TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                } else if (tagger != null && program.name != null) {
                                                    // Said rather than left blank: an empty gap
                                                    // where every other row carries a category
                                                    // reads as "not loaded yet".
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(R.string.programs_no_category),
                                                        modifier = Modifier.weight(1f),
                                                        textAlign = TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    colors = ListItemDefaults.colors(containerColor = rowColor),
                                    // What this row in particular offers.
                                    //
                                    // A read-only bank refuses every write, so the row keeps only
                                    // the one action that does not need one - and Copy is a read
                                    // of *this* row into somewhere else, which is precisely the
                                    // point of browsing the factory voices at all.
                                    //
                                    // Relocating within the favorites listing is meaningless in a
                                    // different way: its rows are the marked voices in bank order,
                                    // not an address space, so there is nothing for a copy to
                                    // rearrange and no free slot in it to copy into.
                                    trailingContent = run {
                                        val rowWritable = program.address.bank !in readOnlyBanks
                                        val rowRename = canRename && rowWritable
                                        val rowDelete = canDelete && rowWritable &&
                                            browseScope == PresetScope.USER
                                        // In every listing, favorites included: picking a
                                        // destination already moves to the user banks, so a
                                        // read-only source needs no special case.
                                        val rowCopy = canCopy
                                        // The same conditions the drag gesture is enabled under,
                                        // so the accessible path is not the less capable one.
                                        val rowMove = canRelocate && rowWritable &&
                                            browseScope == PresetScope.USER
                                        // Asked of the facet rather than derived from
                                        // `rowWritable`: a Motif XS can favorite a factory voice
                                        // but not re-categorise one.
                                        val rowFavorite = viewModel.canSetFavorite(program.address)
                                        val rowCategories =
                                            viewModel.canSetCategories(program.address)
                                        // Hidden outright while picking: none of these applies to
                                        // any row until the pick resolves.
                                        if (ops.picking || isEmpty ||
                                            (!rowRename && !rowDelete && !rowCopy &&
                                                !rowFavorite && !rowCategories)
                                        ) {
                                            null
                                        } else {
                                            {
                                            RowActionsMenu(
                                                program = program,
                                                canRename = rowRename,
                                                canCopy = rowCopy,
                                                canMove = rowMove,
                                                canDelete = rowDelete,
                                                canSetFavorite = rowFavorite,
                                                canSetCategories = rowCategories,
                                                categoryCount = tagger?.assignmentCount ?: 1,
                                                enabled = editsEnabled,
                                                onRename = {
                                                    ops.renameTarget = program
                                                    ops.renameText = program.name.orEmpty()
                                                },
                                                onCopy = { ops.beginPicking(program, PickIntent.COPY) },
                                                onMove = { ops.beginPicking(program, PickIntent.MOVE) },
                                                onDelete = { ops.deleteTarget = program },
                                                onSetFavorite = {
                                                    ops.openTagDialog(program, forFavorite = true)
                                                },
                                                onSetCategories = {
                                                    ops.openTagDialog(program, forFavorite = false)
                                                },
                                            )
                                            }
                                        }
                                    },
                                    // Always reserves the handle's column, so an empty row's
                                    // headline stays aligned with the others.
                                    leadingContent = {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(ProgramListMetrics.handleSize),
                                        ) {
                                            if (!isEmpty && canRelocate && !ops.picking && browseScope == PresetScope.USER) {
                                                // The handle is decorative and the row is what a
                                                // screen reader describes, so the handle carries
                                                // the instruction. Resolved out here because
                                                // `semantics {}` is not a composable scope.
                                                val handleDescription = if (editsEnabled) {
                                                    stringResource(
                                                        R.string.cd_reorder,
                                                        program.name ?: program.displayId,
                                                    )
                                                } else {
                                                    stringResource(R.string.cd_reorder_unavailable)
                                                }
                                                theme.DragHandle(
                                                    enabled = editsEnabled,
                                                    modifier = Modifier.semantics {
                                                        contentDescription = handleDescription
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    // Dimmed in place rather than translated - the ghost below
                                    // follows the finger. Empty slots and, while picking, every
                                    // row that is not a valid target get the same treatment.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { theme.rowPanel(it, dragged = isDragged, dropTarget = isDropTarget) }
                                        .alpha(
                                            when {
                                                isDragged -> ProgramListMetrics.DRAGGED_ALPHA
                                                ops.picking && !isPickTarget ->
                                                    ProgramListMetrics.DISABLED_ALPHA
                                                isEmpty && !ops.picking ->
                                                    ProgramListMetrics.EMPTY_SLOT_ALPHA
                                                else -> 1f
                                            },
                                        )
                                        .let { m ->
                                            when {
                                                isPickTarget -> {
                                                    val description = stringResource(
                                                        when (ops.pickIntent) {
                                                            PickIntent.COPY -> R.string.cd_copy_destination
                                                            PickIntent.MOVE -> R.string.cd_move_destination
                                                        },
                                                        program.displayId,
                                                    )
                                                    // Gated while an edit runs: the picker stays
                                                    // open across the copy it started.
                                                    m.clickable(enabled = ops.busy == null) {
                                                        ops.pickSource?.let {
                                                            ops.onPickConfirmed(it, program, isEmpty)
                                                        }
                                                    }.semantics { contentDescription = description }
                                                }
                                                // Inert while picking, as the row menu is.
                                                ops.picking -> m
                                                isEmpty || !canSelect -> m
                                                else -> m.clickable { ops.onProgramTapped(program) }
                                            }
                                        },
                                )
                            }
                        }
                    }
                }

                drag.draggedIndex?.let { idx ->
                    (visibleRows.getOrNull(idx) as? ProgramRow.ProgramEntry)?.let { row ->
                        ListItem(
                            headlineContent = {
                                if (row.item.name == null) EmptySlotText() else Text(row.item.name)
                            },
                            supportingContent = { Text(row.item.displayId) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            leadingContent = {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(ProgramListMetrics.handleSize),
                                ) {
                                    // Purely visual, and hidden from accessibility so it does not
                                    // read as a second control.
                                    theme.DragHandle(
                                        enabled = true,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = ProgramListMetrics.listEndPadding)
                                .offset { IntOffset(0, drag.ghostOffsetY) }
                                .shadow(4.dp),
                        )
                    }
                }

                if (listing.bankLabels.size > 1) {
                    BankIndex(
                        banks = listing.bankLabels,
                        railLabel = { bankRailLabels[it] ?: it },
                        onJump = { letter ->
                            // A plain jump rather than animateScrollToItem, which animates through
                            // every intermediate row: header rows break the average-item-size
                            // estimate it scrolls by, so a long jump overshoots and corrects, and
                            // successive drag-scrub jumps interrupt each other's animation.
                            listing.bankHeaderIndex[letter]?.let { row -> scope.launch { listState.scrollToItem(row) } }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }

        // For a device the catalog does not recognise, or one on firmware nobody has tested this
        // app against: in both cases its owner is the one person who can send back what the app
        // read off it. For a known instrument the report lives in the debug menu instead.
        if (hasReport && (isUnknownDevice || advisory != null)) {
            TextButton(
                onClick = {
                    ops.pendingShare = PendingShare(
                        title = shareReportTitle,
                        // Supplied by the instrument's own reporter, since the families read
                        // different things.
                        description = viewModel.reportDescription,
                        suffix = jsonSuffix,
                        initialStem = viewModel.suggestedReportFilename(),
                        onConfirm = { stem ->
                            pendingReportStem = stem
                            reportRunner.start(readingLabel)
                        },
                    )
                },
                // Off while a report or a listing is running: the report walks every item, and
                // two long reads would share one serialized bus.
                enabled = reportState !is DeviceReportState.Running && editsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text((reportState as? DeviceReportState.Running)?.step ?: shareReportTitle)
            }
        }
    }
    }

    ops.pendingShare?.let { pending ->
        ShareFilenameDialog(pending, onDismiss = { ops.pendingShare = null })
    }

    ops.deleteTarget?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            emulated = viewModel.isEmulatedEdit(EditOp.DELETE),
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
            maxNameLength = maxNameLength,
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
        val now = System.currentTimeMillis()
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
 * reconnect dip a resume goes through, which are the same wait from the user's side.
 */
@Composable
private fun ConnectingWait() {
    val theme = LocalThemeStyle.current
    CenteredMessage {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            theme.ProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.programs_reconnecting))
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

/**
 * Which of the instrument's listings to show, plus a line saying what the current one is.
 *
 * Segmented buttons rather than tabs: these three differ in what can be done to a row, not only
 * in which rows are shown, and each segment carries `Role.RadioButton` and a selected state, so a
 * screen reader announces "Factory, selected, 2 of 3" without any semantics here.
 *
 * The caption keeps two of the listings from misrepresenting themselves: the factory names are
 * transcribed from Yamaha's Data List rather than read off the instrument, and the favorite marks
 * are the instrument's own, changed from a row menu rather than from the listing.
 */
@Composable
private fun ScopeSelector(
    scopes: List<PresetScope>,
    selected: PresetScope,
    enabled: Boolean,
    onSelect: (PresetScope) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            scopes.forEachIndexed { index, scope ->
                SegmentedButton(
                    selected = scope == selected,
                    onClick = { onSelect(scope) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = scopes.size),
                ) {
                    Text(stringResource(scope.labelRes()))
                }
            }
        }
        selected.noteRes()?.let { note ->
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The segment's label, kept out of [PresetScope], which is in `core` and knows nothing about resources. */
@StringRes
private fun PresetScope.labelRes(): Int = when (this) {
    PresetScope.USER -> R.string.programs_scope_user
    PresetScope.FACTORY -> R.string.programs_scope_factory
    PresetScope.FAVORITES -> R.string.programs_scope_favorites
}

/** What this listing has to admit about itself, or null where there is nothing to say. */
@StringRes
private fun PresetScope.noteRes(): Int? = when (this) {
    PresetScope.USER -> null
    PresetScope.FACTORY -> R.string.programs_scope_note_factory
    PresetScope.FAVORITES -> R.string.programs_scope_note_favorites
}

/**
 * Per-row Rename / Copy / Delete, behind one overflow button: in a 128-row bank always-visible
 * buttons would be hundreds of controls competing with the preset names, with a destructive
 * action permanently a few dp from a benign one. Copy gets no confirmation of its own, being as
 * safe as Rename; choosing a destination in the list is the only decision it needs.
 */
@Composable
private fun RowActionsMenu(
    program: PresetSlot,
    canRename: Boolean,
    canCopy: Boolean,
    canMove: Boolean,
    canDelete: Boolean,
    canSetFavorite: Boolean,
    canSetCategories: Boolean,
    /** How many categories this instrument's presets hold - one on a Nord, two on a Motif XS.
     * Decides whether the menu offers "Set category" or "Set categories". */
    categoryCount: Int,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSetFavorite: () -> Unit,
    onSetCategories: () -> Unit,
    /** False while the listing is still arriving - see `editsEnabled` in ProgramsScreen. */
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // Disabled rather than hidden: a control that vanishes and reappears as a list loads is
        // harder to read than one that is visibly not yet available, and the progress row above
        // says why.
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                Icons.Default.MoreVert,
                // Named for its row: in a list of 128, "More options" alone says nothing about
                // which preset a screen-reader user is on.
                contentDescription = stringResource(
                    R.string.cd_row_options, program.name ?: program.displayId,
                ),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (canRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    onClick = { expanded = false; onRename() },
                )
            }
            if (canCopy) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    onClick = { expanded = false; onCopy() },
                )
            }
            // The reachable half of drag-to-reorder: a long-press drag has no keyboard or
            // screen-reader equivalent, and this is the same destination-picking flow Copy uses.
            if (canMove) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move)) },
                    onClick = { expanded = false; onMove() },
                )
            }
            // Before Delete, which stays last.
            if (canSetFavorite) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_set_favorite)) },
                    onClick = { expanded = false; onSetFavorite() },
                )
            }
            if (canSetCategories) {
                DropdownMenuItem(
                    text = {
                        Text(pluralStringResource(R.plurals.action_set_categories, categoryCount))
                    },
                    onClick = { expanded = false; onSetCategories() },
                )
            }
            if (canDelete) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { expanded = false; onDelete() },
                )
            }
        }
    }
}

/**
 * Right-edge fast-scroll index: tapping or dragging across a bank letter jumps the list to that
 * bank's caption row. `detectDragGestures` rather than the long-press variant the row reorder
 * uses, since an index jump should react to the first touch. In its own `pointerInput` scope on
 * this narrow column, and a touch starting here is already past the reorder detector's handle
 * band, so that detector's `onDragStart` is a no-op regardless of arbitration timing.
 */
@Composable
private fun BankIndex(
    banks: List<String>,
    onJump: (String) -> Unit,
    modifier: Modifier = Modifier,
    railLabel: (String) -> String = { it },
) {
    var columnHeightPx by remember { mutableStateOf(0f) }
    val theme = LocalThemeStyle.current
    val pillShape = RoundedCornerShape(percent = 50)
    fun jumpToY(y: Float) {
        if (banks.isEmpty() || columnHeightPx <= 0f) return
        val index = ((y / columnHeightPx) * banks.size).toInt().coerceIn(0, banks.lastIndex)
        onJump(banks[index])
    }
    Column(
        modifier
            // Inset, so the rail reads as floating over the list rather than as its right-hand
            // margin.
            .padding(vertical = 12.dp, horizontal = 4.dp)
            // Wide enough for a label like "USER DR" without abbreviating.
            .width(ProgramListMetrics.railWidth)
            // A container tone rather than `surface`, so it separates itself in both light and
            // dark without a border. Clipped before the background, or the label taps' ripple
            // would paint square corners over the rounded ones.
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { theme.railDecoration(it) }
            // After the decoration, before the measurement: `onSizeChanged` and `pointerInput`
            // both sit after it, so the height `jumpToY` divides is the same box the weighted
            // cells fill and a y position means the same thing to both.
            .padding(vertical = theme.railEndInset)
            .onSizeChanged { columnHeightPx = it.height.toFloat() }
            .pointerInput(banks) {
                detectDragGestures(
                    onDragStart = { offset -> jumpToY(offset.y) },
                    onDrag = { change, _ -> change.consume(); jumpToY(change.position.y) },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        banks.forEach { letter ->
            // An equal weighted share each, with the whole cell as the target rather than the
            // glyph: `jumpToY` divides the rail's height into `banks.size` equal bands, which is
            // what these weights produce, so dragging and tapping resolve the same letter.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable { onJump(letter) },
            ) {
                Text(
                    railLabel(letter),
                    style = MaterialTheme.typography.labelSmall,
                    // An index is secondary to what it indexes.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
