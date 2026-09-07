package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope

/**
 * The preset browser. Tapping a preset loads it. Long-pressing a preset's drag handle and dropping
 * it elsewhere relocates it: onto an occupied row that's a two-way swap, onto an empty slot a
 * one-way move. [ProgramsController.onSwapDropped] picks between them from the loaded index, since an instrument with
 * native operations rejects either call at the wrong kind of destination. The drag gesture is
 * detected on a Box wrapping the whole LazyColumn - not on the individual row - and hit-tests
 * the touch position against the row it landed in (via listState.layoutInfo) plus a left-edge
 * width band approximating the handle glyph's column. A per-row pointerInput was tried first but
 * its coroutine gets cancelled the moment the dragged row scrolls out of the composed range (e.g.
 * via auto-scroll), aborting the drag; a gesture owned by the always-composed wrapper survives
 * that.
 *
 * The dragged row itself is left in place (dimmed) rather than translated to follow the finger:
 * LazyColumn decides what to compose from each item's untranslated layout slot, so a
 * graphicsLayer translation that was tried first kept dragging a row whose content had already
 * been torn down once that slot scrolled off the (pre-translation) viewport, leaving an empty box.
 * Instead a separate "ghost" ListItem, positioned via pointerY (the finger's own position, tracked
 * independently of any row), is drawn on top as the visual stand-in for whichever preset is being
 * dragged.
 *
 * Rows alternate shading for readability, overridden by an accent color for the dragged row and
 * its current drop target - set via ListItem's own `colors` param, since an external
 * `Modifier.background()` sits underneath ListItem's own container paint and isn't visible.
 *
 * Bank caption rows (see ProgramRow) are interleaved into the same LazyColumn as the preset rows,
 * so draggedIndex/dropTargetIndex are indices into visibleRows (which include headers), not into
 * the preset list. That also means the row under a given screen position can no longer be found by
 * dividing a pixel offset by a single sampled row height - headers and preset rows aren't the same
 * height - so onDragStart/onDrag/the auto-scroll effect all resolve a position to a row via
 * hitRowInfo (a direct lookup against listState.layoutInfo.visibleItemsInfo) instead.
 * [ProgramsController.onSwapDropped] takes the two rows' slots rather than indices, since that's what the
 * instrument-facing call actually needs and stays valid independent of row vs. preset index space.
 *
 * **Three things here are driven by what the connected instrument declares**, not by
 * assumption: the Categories, Show text and Share buttons appear only where those facets exist;
 * the drag handle and the Rename button appear only for edits the instrument supports; and the
 * list renders while an index is still arriving, with drag disabled until it is whole, because a
 * drop into a region that has not loaded yet has no defined target.
 */

private const val TAG = "ProgramsScreen"

/**
 * The measurements the preset list and its floating drag ghost have to agree on.
 *
 * **Named because two of them were asserted in prose.** The ghost's end padding carried a comment
 * reading "Matches the list's own end padding", and the handle box's size was repeated between the
 * row and the ghost - a constant asked for by name in both cases. The ghost is meant to sit exactly
 * over the row it was lifted from, so these genuinely are one value each rather than two that
 * happen to coincide.
 */
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
 * An operation the instrument's state blocked, and what the user can do about it.
 *
 * [apply] and [retry] are held rather than re-derived so the dialog does not need to know which
 * operation raised it - it renders whatever the family said and calls back. Adding a second
 * blockable operation means catching the exception there too, not touching this.
 */
internal data class BlockedOperation(
    val message: String,
    val detail: String?,
    val actionLabel: String,
    val apply: suspend () -> Unit,
    val retry: () -> Unit,
)

/**
 * Puts a failed edit on the screen **and** in the log, and returns the text for the screen.
 *
 * The logging half is not decoration. A move or swap failure on a Motif XS can otherwise leave no
 * record anywhere but a sentence in a snackbar the user has already dismissed - `logcat` holds the
 * whole conversation with the instrument, and without this nothing in it names the operation that
 * failed. Without a logged record, the only way to learn what went wrong is asking the user to
 * read the error back off the screen.
 *
 * The user-facing string is left exactly as the instrument layer wrote it; those messages are
 * written to be read by whoever is holding the phone, and rephrasing them here would put the
 * wording two files away from the condition that produces it.
 */
internal fun reportFailure(what: String, error: Throwable, fallback: String): String {
    Log.w(TAG, "$what failed", error)
    return error.message ?: fallback
}

/**
 * Whether this screen has anything to show for [this] state, or should hand off to ConnectScreen.
 *
 * **Exhaustive on purpose, with no `else`.** [ConnectionState] is a sealed class specifically so
 * that adding a case here is a compile error until this function says which side it falls on -
 * the alternative is a state falling through to whichever behaviour an `else` happened to pick,
 * which is exactly how this screen ended up rendering a disconnected session under its own stale
 * title and gear icons (2026-08-28) rather than a clear "not connected".
 *
 * `Connected` obviously stays. `Disconnected`/`Searching`/`Opening` also stay: `forceReconnect()`
 * passes through them on a normal, successful resume, and leaving *during* that dip would bounce
 * to ConnectScreen and straight back for a reconnect that was working the whole time. Everything
 * else - an error, nothing found, a device picker, an unknown-device or advisory warning - is a
 * choice or a message only ConnectScreen's `when` renders; this screen has never had UI for any
 * of them, and sitting on one silently is the bug this function exists to end.
 */
private fun ConnectionState.rendersOnProgramsScreen(): Boolean = when (this) {
    is ConnectionState.Connected,
    ConnectionState.Disconnected,
    ConnectionState.Searching,
    is ConnectionState.Opening,
    -> true
    is ConnectionState.Error,
    is ConnectionState.NeedsManualSetting,
    is ConnectionState.NothingFound,
    is ConnectionState.DeviceSelection,
    is ConnectionState.UnknownDeviceWarning,
    is ConnectionState.AdvisoryWarning,
    is ConnectionState.DeviceLost,
    -> false
}

// `PullToRefreshBox` is still marked experimental in Material3 1.4. Opted in once for the whole
// screen rather than at each use: the alternative is annotations on expressions scattered through
// a long composable.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(
    viewModel: InstrumentViewModel,
    onBack: () -> Unit,
    onSessionLost: () -> Unit,
    onOpenDebugMenu: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = LocalThemeStyle.current

    // Every instrument operation this screen can start, and the state each one owns - see
    // [ProgramsController]. What stays below is the state that only describes the view.
    val ops = rememberProgramsController(viewModel)

    var showEmptySlots by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // Reads the report and opens the share sheet; `progress` is non-null while it runs, because
    // the probe walks every item on the instrument and is slow enough to need saying so.
    val sharer = rememberReportSharer(viewModel)
    val debugTaps = remember { DebugTapCounter() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Captured at screen level rather than inside a dialog: it is needed *as* the dialog is being
    // disposed, by which point a controller resolved inside it is already going away.
    val keyboardController = LocalSoftwareKeyboardController.current
    // Pull-to-refresh. A Material pattern rather than an iOS import: it is in the Material spec
    // and ships in Compose Material3. The gesture is the discoverable half of the answer to
    // "the listing is cached, how do I make it re-read?"; the overflow item is the other half,
    // because a gesture nobody performs is a feature nobody has.
    //
    // `PullToRefreshBox` hoists the refreshing flag to the caller, so this screen owns it. The
    // `PullToRefreshContainer` it replaces kept that state inside itself, which is why a refresh
    // used to be started by an effect watching `pullState.isRefreshing` rather than by the
    // gesture's own callback - a round trip through state the current API makes unnecessary.
    var isRefreshing by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    // Drag-to-reorder: four pieces of state, a hit-test and an auto-scroll loop, all of which
    // only ever change together - see ProgramListDragState.
    val drag = rememberProgramListDragState(listState)

    // What the connected instrument actually offers. Read once per composition rather than
    // at every call site, so a screen never asks a facet a question it has already been told the
    // answer to.
    //
    // **Keyed on the session, not on a counter this screen bumps by hand.** These six changed
    // exactly when the connected instrument changed, but nothing about a plain getter tells
    // Compose that - so a `refreshTrigger` was incremented at five call sites to force a re-read,
    // and correctness depended on every future mutation remembering to bump it. One already did
    // not: the refresh button bumped it for a re-read that changes none of these, while
    // `disconnect()` changes all of them and bumped nothing. `state` is a StateFlow Compose
    // already observes, and the accessors behind these are null-safe (see `connected()`), so the
    // recompose-while-disconnected crash that made the counter look necessary cannot happen.
    val session by viewModel.state.collectAsState()
    // Hands off to ConnectScreen the moment a reconnect settles somewhere this screen cannot
    // render - see [rendersOnProgramsScreen]. Keyed on the session itself, not on `Unit`: a
    // `forceReconnect()` that dips through Disconnected/Searching and back to Connected must not
    // trigger this, only one that settles on something else.
    LaunchedEffect(session) {
        if (!session.rendersOnProgramsScreen()) onSessionLost()
    }
    val supportedEdits = remember(session) { viewModel.supportedEdits }
    val canRename = EditOp.RENAME in supportedEdits
    val canDelete = EditOp.DELETE in supportedEdits
    val canRelocate = EditOp.MOVE in supportedEdits || EditOp.SWAP in supportedEdits
    val canCopyOp = EditOp.COPY in supportedEdits
    val canSelect = remember(session) { viewModel.canSelect }
    // Which listing is on screen, and which the instrument offers at all. A family with one scope
    // shows no selector, so nothing about the Nord, Pro-800 or demo screens changes.
    val browseScope by viewModel.scope.collectAsState()
    val browsingScopes = remember(session) { viewModel.browsingScopes() }
    // Banks the instrument refuses writes to. Per-row rather than per-instrument, because the
    // favorites listing mixes the two: a favorited factory voice and a favorited user voice sit
    // in the same list, and only one of them can be renamed.
    val readOnlyBanks = remember(session) { viewModel.readOnlyBanks() }
    // Null where the instrument has no categories at all, which is what hides both row actions.
    val tagger = remember(session) { viewModel.tagger }

    val hasReport = remember(session) { viewModel.hasReport }
    val isUnknownDevice = remember(session) { viewModel.isUnknownDevice }
    // Non-null where the instrument is usable but not vouched for - today, untested firmware.
    // The connect screen gates on this once; this keeps it visible, because a warning accepted
    // before the list appeared is a warning forgotten by the time anything is renamed.
    val advisory = remember(session) { viewModel.advisory }
    // Null where the instrument declares no limit. Where it does, the rename field enforces it -
    // an instrument that silently keeps the first N characters should not be the thing that tells
    // the user their name was too long.
    val maxNameLength = remember(session) { viewModel.maxPresetNameLength }

    // Brief confirmations (selected/swapped/renamed/deleted) surface as a Snackbar.
    //
    // This used to be a `Toast`, which is a *system* overlay: it outlives the screen that raised
    // it, cannot host an action, and sits outside the app's own accessibility tree. A Snackbar
    // belongs to this Scaffold, which is both the convention and the prerequisite for ever
    // offering "Undo" on a delete.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ops.statusMessage) {
        val message = ops.statusMessage ?: return@LaunchedEffect
        // Clear *after* showing, never before. `showSnackbar` suspends until the snackbar is
        // dismissed, and clearing first changes this effect's key - which cancels the very call
        // that was about to render it. The Toast this replaced hid the mistake, because
        // `Toast.show()` does not suspend and had already completed by then.
        snackbarHostState.showSnackbar(message, withDismissAction = true)
        ops.statusMessage = null
    }

    // Failures use the same Snackbar, and **do not time out**.
    //
    // They used to be a red line wedged under the filter row, which pushed the list down, stayed
    // until the next operation cleared it, and looked nothing like the confirmations for the same
    // actions. A refusal - "cannot put a drum kit into a slot that holds a normal voice" - is the
    // same kind of feedback as "Swapped A:01 <-> A:02" and belongs in the same place.
    //
    // Indefinite rather than the confirmations' brief show, because these are not all refusals.
    // Some report that the *instrument's* state is uncertain - "the instrument did not confirm
    // the commit, so the writes may or may not have been stored" - and a message telling somebody
    // their data might be in an unknown state must not disappear on a timer while they are looking
    // at the keyboard. One tap on the dismiss action clears it.
    LaunchedEffect(ops.operationError, sharer.error) {
        val message = ops.operationError ?: sharer.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        ops.operationError = null
    }

    // Rows render as they arrive, unlike the loading/error/data trio this used to use - the
    // difference between usable and unusable on an instrument whose index costs one round trip
    // per slot.
    // Collected in the ViewModel, not here. A `produceState` in this composable tied the scan to
    // the composition, so rotating the phone mid-listing cancelled it and started again - 93
    // seconds on a Motif XS. It only looked correct after completion because a finished index is
    // served from cache (docs/ARCHITECTURE.md, "Caching").
    val index by viewModel.index.collectAsState()

    // Announces "this screen needs a listing"; the ViewModel decides whether that means work.
    //
    // **Keyed on Unit, not on the session**, even though the session is collected above for the
    // facet-shape reads. Restarting a reconnect's listing is the ViewModel's job - it calls
    // startIndex() itself when a session begins - and a rotation must leave a scan already
    // running alone.
    //
    // Collecting the session here was once a defect in its own right: the screen recomposed
    // *while disconnected* and the accessors it reads threw on the first call needing an
    // instrument. They answer null now (see `InstrumentViewModel.connected()`), which is what
    // makes the collection above safe - but the listing still must not be keyed on it.
    LaunchedEffect(Unit) { viewModel.startIndex() }
    /*
     * Editing is off while the listing is still arriving.
     *
     * Not merely defensive. A drag dropped onto a region that has not loaded yet has no defined
     * target, and rename and delete would be composed against an index that is still filling -
     * on a Motif XS that window is about 93 seconds, which is long enough to be used rather than
     * long enough to be noticed. Dragging was already gated on this; the overflow menu and the
     * device report were not, which meant the two *destructive* operations were the ones still
     * reachable mid-scan.
     *
     * Three things deliberately stay live, because none of them is composed against the index:
     *
     * - **The bank rail.** Jumping between banks during a long load is what makes it bearable,
     *   and it changes nothing on the instrument.
     * - **The filter and the empty-slot toggle**, which only ever narrow what is already shown.
     * - **Tapping a row to select it.** It stores nothing, and `SysExExchange` serializes
     *   requests, so a selection issued mid-scan lands *between* two dumps rather than in the
     *   middle of one - about 160 ms on a Motif XS, not a hang. Auditioning presets while the
     *   rest of the bank loads is a feature, not a race.
     */
    // ...and off again while one is running. An edit is seconds of round trips on this
    // instrument, and until the progress line existed there was nothing to tell a user that
    // their tap had registered - so the natural response was to tap again, queueing a second
    // edit behind the first. `SysExExchange` serialises them, so the result was correct and
    // baffling: two copies, several seconds apart, from one apparent gesture.
    //
    // Selection is deliberately *not* gated on this. It stores nothing, it is the fastest thing
    // here, and auditioning one preset while another edit finishes is reasonable.
    val editsEnabled = index.complete && ops.busy == null

    // The gesture drops the cached listing and re-runs the scan; the scan reaching Complete is
    // what stops the spinner. Driving the spinner off `index.complete` rather than off a timer
    // means it reflects the instrument finishing rather than an animation finishing - which on a
    // Motif XS is a 93-second difference.
    // Which run *this* gesture started, so the effect below can tell it from any other.
    var pendingRefresh by remember { mutableStateOf<Int?>(null) }
    // Keyed on the **generation**, not only on `complete`.
    //
    // Keying on `complete` alone looks right and does not work: a fast listing - demo mode, a
    // Nord, a cache hit - resets and completes inside one recomposition, so Compose never
    // observes the intermediate `false`, the key never changes, this never re-runs, and the
    // spinner hangs forever on exactly the instruments where the refresh was instant.
    //
    // `index.generation` changes on every re-read whatever its duration, so comparing it against
    // the trigger we just bumped answers "has *my* refresh finished?" rather than "is something
    // finished?".
    LaunchedEffect(index.generation, index.complete, index.error) {
        if (!isRefreshing) return@LaunchedEffect
        if (index.generation != pendingRefresh) return@LaunchedEffect
        if (index.complete || index.error != null) isRefreshing = false
    }
    // Shared by the pull gesture and the app bar's refresh button, so both give the same
    // `isRefreshing` feedback rather than only the gesture's own indicator moving - the button
    // used to just call `viewModel.refreshIndex()` directly, dropping the cache with no visible
    // sign anything had happened.
    //
    // **Guards against re-entry itself**, rather than trusting the button's own `enabled` state
    // to have caught up: `enabled` only reflects the last recomposition, so two taps arriving
    // before Compose re-renders between them both reach this function regardless. `isRefreshing`
    // is a local Compose state var, though, so - unlike navigation completion, which lives in
    // NavController's own state and genuinely needs a recomposition to observe - a synchronous
    // check-then-set here is enough: the second call sees the first's write immediately, in the
    // same snapshot, with no recomposition required in between.
    fun startRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        pendingRefresh = viewModel.refreshIndex()
    }
    val programs = index.slots

    // Everything the list needs, derived in one pure pass - see [buildProgramListing], which is
    // where the rules about how the two families report occupancy now live, and where they can
    // finally be tested without running Compose.
    // `scope` is a key even though it is not an argument: `viewModel.allSlots(scope)` is a plain
    // call, so nothing else here would tell Compose the address space had changed underneath it.
    val listing = remember(programs, showEmptySlots, searchText, ops.pickingCopy, browseScope) {
        buildProgramListing(
            reported = programs,
            allSlots = viewModel.allSlots(browseScope),
            // Only the user listing has empty slots to show, and the checkbox is hidden in the
            // other two - but hiding a control does not reset it. Left ticked from the user
            // listing, it would make the favorites one render *nothing*: that scope has no
            // address space, so "show every address, occupied or not" is an empty set.
            showEmptySlots = showEmptySlots && browseScope == PresetScope.USER,
            pickingCopy = ops.pickingCopy,
            searchText = searchText,
        )
    }
    val visibleRows = listing.rows
    val occupied = listing.occupied
    // Drawn in the rail instead of the full label, which does not fit at the rail's width.
    val bankRailLabels = remember(index) { viewModel.bankRailLabels() }
    // **The user listing's free slots, whatever is on screen.** A copy can only ever be written to
    // a writable slot, so the destination pool is a property of the user scope - not of the
    // listing being displayed. Taking it from `listing` instead is wrong in the factory scope,
    // where every one of the 1,217 rows holds a voice: `freeSlots` would be empty and the Copy
    // item would be hidden on exactly the rows the feature exists for.
    val userIndex by viewModel.userIndex.collectAsState()
    val copyDestinations = remember(userIndex, session) {
        freeSlots(userIndex.slots, viewModel.allSlots(PresetScope.USER))
    }
    // Hidden rather than shown-disabled where there is nowhere to copy to: the row menu has no
    // other disabled-but-visible items, and entering picking mode only to find no valid target
    // is not worth offering.
    //
    // Gated on the *user* listing being complete rather than on `editsEnabled`, which describes
    // whichever listing is displayed - and the factory one completes the moment it is opened,
    // which would offer a copy before the destinations were known.
    val canCopy = canCopyOp && userIndex.complete && copyDestinations.isNotEmpty()

    // While a row is dragged near an edge of the viewport, keep scrolling so targets outside the
    // visible range can be reached. Keyed on draggedIndex, so onDragEnd/onDragCancel setting it
    // back to null cancels the loop.
    LaunchedEffect(drag.draggedIndex) {
        if (drag.draggedIndex == null) return@LaunchedEffect
        drag.autoScroll { i -> visibleRows.getOrNull(i) is ProgramRow.ProgramEntry }
    }

    // Lets an instrument's owner send back everything the app can read off it, for turning into
    // a catalog entry. Read-only, but slow: the probe walks every item, so this reports progress
    // and blocks the button while it runs.

    // The system-back half of `backEnabled` below. Disabling the arrow does nothing for the
    // gesture or the hardware key, and with `enableOnBackInvokedCallback` set in the manifest a
    // predictive-back swipe would otherwise animate this screen away mid-write. Enabled only
    // while an edit is running, so it is inert - and predictive back is not intercepted at all -
    // the rest of the time.
    BackHandler(enabled = ops.busy != null) {
        // Deliberately empty: refusing the gesture *is* the behaviour. The progress line already
        // says what is running, so there is nothing further to tell the user here.
    }

    PatchPilotScaffold(
        // The instrument's own name, not the word "Presets". This is the only screen that shows
        // which instrument the app is talking to, which matters when more than one is plugged in
        // at once. Falls back only while disconnecting.
        title = viewModel.instrumentName ?: stringResource(R.string.programs_title_fallback),
        // Five taps on the instrument name opens the debug menu. Deliberately undiscoverable -
        // no ripple, no content description, nothing that reads as a control - since everything
        // behind it is for whoever is developing against an instrument, not for whoever is
        // playing one. Local state: it means nothing outside this composition and there is
        // nothing to restore if the process dies mid-gesture.
        titleModifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { if (debugTaps.tap()) onOpenDebugMenu() },
        ),
        // Back returns to the connect screen, which re-scans on arrival - the way to pick up an
        // instrument that was plugged in after the app started, or to swap between two.
        onBack = onBack,
        // Off while an edit is in flight. `busy` is only ever a discrete edit - seconds - and
        // never the index scan, which reports through `index.progress` and leaves back available
        // for the whole 93 seconds it can take. So this greys the arrow out for a moment during a
        // rename or a copy, not for the long wait somebody might genuinely want out of.
        //
        // Belt and braces rather than the safety itself: launchEdit already keeps the write alive
        // across navigation, and exchangeAfterAll already refuses to stop mid-sequence. What this
        // adds is telling the user *why* nothing happened when they tapped, instead of appearing
        // to leave while a write is still running against the instrument.
        backEnabled = ops.busy == null,
        snackbarHostState = snackbarHostState,
        // The discoverable half of "re-read from the instrument". The pull gesture is the fast
        // half, and invisible to anyone not already expecting it - which on a screen whose rows
        // may have come from memory rather than from the wire is not good enough on its own.
        actions = {
            // Offered in demo mode too. It was hidden there on the reasoning that a fake
            // instrument has nothing to re-read - which is true and beside the point: the pull
            // gesture works in demo mode, so hiding the button made the two ways of doing the
            // same thing disagree, and left the only *discoverable* one missing on the one
            // configuration somebody exploring the app is most likely to be in.
            IconButton(
                // Same as the pull gesture: drop the cache, then re-read from the instrument.
                onClick = ::startRefresh,
                enabled = index.complete || index.error != null,
            ) {
                val rotation by rememberInfiniteTransition(label = "refresh-spin").animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
                    label = "angle",
                )
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.cd_refresh),
                    modifier = Modifier.graphicsLayer { rotationZ = if (isRefreshing) rotation else 0f },
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
            }
        },
    ) { innerPadding ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(horizontal = 16.dp),
    ) {
        // Only where there is a choice to make. An instrument with one listing renders exactly
        // what it rendered before this existed.
        //
        // Disabled rather than hidden while an edit runs or a copy destination is being picked:
        // both are states the user is *in the middle of*, and a control that vanishes and comes
        // back shifts everything below it - in a list they may be reading at the time.
        if (browsingScopes.size > 1) {
            ScopeSelector(
                scopes = browsingScopes,
                selected = browseScope,
                // **Off during an edit, not during a listing.** The three scopes would otherwise
                // be gated on something none of them shares: a factory listing costs no round
                // trips at all, and a favorites read interleaves with a running scan rather than
                // fighting it - `SysExExchange` locks one request/reply pair at a time, so the
                // two alternate, no reply can reach the wrong collector, and the scan pays about
                // fifteen extra round trips out of its four hundred. Switching away does not
                // abandon a scan either; each scope keeps its own job. Gating on `editsEnabled`
                // bought nothing and left every tab dead for the 93 seconds after a connect.
                //
                // An edit is different, and not because of the bus: `runEdit` reloads whichever
                // scope is current when it finishes, so switching underneath it reloads the wrong
                // one. Picking a copy destination owns the list until it resolves.
                enabled = ops.busy == null && !ops.pickingCopy,
                onSelect = viewModel::setScope,
            )
        }
        // Stays for the whole session rather than being dismissible. The connect screen already
        // asked once and the user said continue; the point of this is that the reason is still on
        // screen when a rename goes wrong an hour later, which a dismissed banner would not be.
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
        // Picking a copy destination happens in the list itself rather than behind a separate
        // dialog: occupied rows below grey out and stop responding to taps, empty rows show
        // regardless of the filter or "show empty slots" (see visiblePrograms/bankLabels above),
        // and tapping one starts the copy immediately - see [ProgramsController.onCopyConfirmed]. This banner is the
        // one persistent sign that mode is active, and its Cancel is the only way out besides
        // picking a target; there is deliberately no dialog stacked on top of another.
        ops.copySource?.let { source ->
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
                        stringResource(R.string.programs_copy_title, source.name ?: source.displayId),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { ops.endPicking() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        // Hidden rather than disabled while picking a copy destination: neither has any effect on
        // the list in that mode (see visiblePrograms/bankLabels), and the banner above already
        // says what mode this is - a filter and a checkbox that visibly do nothing would only
        // repeat that with dead controls.
        if (!ops.pickingCopy) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.programs_filter_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // One control, not two nodes: the label is part of the target and TalkBack reads it
            // as a single checkbox rather than a box and an unrelated string.
            //
            // **Only in the user listing, where an empty slot is a thing that exists.** Every
            // factory slot holds a voice, so the box would be a no-op there; and the favorites
            // listing has no address space of its own to have gaps in, so ticking it would replace
            // eleven favorites with nothing at all.
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
                // onCheckedChange = null makes the box non-interactive on its own - the Row above
                // owns the click - but it also drops the padding a standalone Checkbox brings, so
                // the gap has to be put back explicitly.
                Checkbox(checked = showEmptySlots, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.programs_show_empty))
            }
        }
        index.failures.takeIf { it.isNotEmpty() }?.let { failures ->
            // Individual unreadable slots are a finding, not a failure of the whole scan - say so
            // without throwing away the rows that did arrive.
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
                // Indeterminate: the instrument reports no progress through an edit, and a bar
                // that invented one would be claiming to know something the app cannot see.
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

        // Bound to a local because `index` is now a delegated property and cannot smart-cast.
        val scanError = index.error
        when {
            scanError != null ->
                Text(stringResource(R.string.programs_error, scanError), color = MaterialTheme.colorScheme.error)
            index.slots.isEmpty() && index.loading -> CircularProgressIndicator()
            else -> PullToRefreshBox(
                isRefreshing = isRefreshing,
                // The gesture starts the work directly now, rather than flipping a flag an effect
                // then noticed. Dropping the cache and re-reading is exactly what the app bar's
                // refresh button does, so the two ways in agree by construction.
                onRefresh = ::startRefresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(visibleRows, canRelocate, index.complete, ops.pickingCopy, browseScope) {
                        // Dragging is off while the index is still arriving: a drop into a region
                        // that has not loaded yet has no defined target, and off entirely on an
                        // instrument that cannot relocate presets at all. Also off while picking a
                        // copy destination - the two gestures would otherwise fight over the same
                        // rows, and a copy pick is resolved by a tap, not a drag.
                        if (!canRelocate || !index.complete || ops.pickingCopy) return@pointerInput
                        // Reordering is a user-listing gesture. A read-only bank refuses the write,
                        // and the favorites listing is not an address space - its rows are the
                        // marked voices across every bank, so "the row below" is not a
                        // destination and dropping onto it would mean nothing.
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
                // A filter that matches nothing used to render an empty grey area with no
                // explanation and no way out but clearing the field by hand.
                if (visibleRows.isEmpty() && index.complete) {
                    CenteredMessage {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (searchText.isBlank()) {
                                    // "This instrument reports no presets" would be a wrong and
                                    // slightly alarming thing to say about an instrument full of
                                    // them that simply has none marked.
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
                                // The app cannot set these, so an empty list is a dead end unless
                                // it says where they are actually set.
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
                                // While picking, an empty slot is the only thing a tap can do
                                // anything with - including one that was already empty before
                                // picking started, not just ones "Show empty slots" would add.
                                val isCopyTarget = ops.pickingCopy && isEmpty
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
                                        // Badges are short family-supplied strings (a category, a
                                        // preset version) rendered uniformly, so a family can add
                                        // one without this screen learning what it means.
                                        //
                                        // **Pushed to the trailing edge**, so a column of them
                                        // lines up down the list instead of starting wherever the
                                        // id happens to end. The badge text takes the remaining
                                        // width rather than a spacer taking it, which is what lets
                                        // a long one shrink and ellipsize instead of shoving the
                                        // id off the row.
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(program.displayId)
                                            if (program.badges.isNotEmpty()) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = program.badges.joinToString("  "),
                                                    modifier = Modifier.weight(1f),
                                                    textAlign = TextAlign.End,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
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
                                        // Offered in every listing, favorites included: a
                                        // favorited voice is as good a thing to duplicate as any
                                        // other, and picking a destination already moves to the
                                        // user banks, so a read-only source needs no special case.
                                        val rowCopy = canCopy
                                        // Asked of the facet, not derived from `rowWritable`,
                                        // because the two disagree: a Motif XS can favorite a
                                        // factory voice but not re-categorise one.
                                        val rowFavorite = viewModel.canSetFavorite(program.address)
                                        val rowCategories =
                                            viewModel.canSetCategories(program.address)
                                        // Hidden outright while picking, not merely disabled: none
                                        // of these applies to *any* row until the pick is resolved
                                        // or cancelled, including the row being copied from.
                                        if (ops.pickingCopy || isEmpty ||
                                            (!rowRename && !rowDelete && !rowCopy &&
                                                !rowFavorite && !rowCategories)
                                        ) {
                                            null
                                        } else {
                                            {
                                            // An overflow menu rather than two always-visible
                                            // buttons. In a 128-row bank those were 256 controls
                                            // competing with the preset names, and a destructive
                                            // action sat permanently a few dp from a benign one
                                            // in a scrolling list.
                                            RowActionsMenu(
                                                program = program,
                                                canRename = rowRename,
                                                canCopy = rowCopy,
                                                canDelete = rowDelete,
                                                canSetFavorite = rowFavorite,
                                                canSetCategories = rowCategories,
                                                categoryCount = tagger?.assignmentCount ?: 1,
                                                enabled = editsEnabled,
                                                onRename = {
                                                    ops.renameTarget = program
                                                    ops.renameText = program.name.orEmpty()
                                                },
                                                onCopy = { ops.beginPicking(program) },
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
                                    // Always reserves the handle's 32.dp column, even for an empty row with
                                    // no glyph in it - otherwise its headline text would sit flush left
                                    // instead of aligned with the other rows' text.
                                    leadingContent = {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(ProgramListMetrics.handleSize)
                                                .let { theme.slotBezel(it, occupied = !isEmpty) },
                                        ) {
                                            if (!isEmpty && canRelocate && !ops.pickingCopy && browseScope == PresetScope.USER) {
                                                // The glyph is decorative; the *row* is what a
                                                // screen reader should describe, so the handle
                                                // carries the instruction and nothing else does.
                                                //
                                                // Dimmed while the listing is still arriving,
                                                // because dragging is already inert then - and an
                                                // affordance that looks live and does nothing is
                                                // worse than one that says it is unavailable.
                                                // Resolved out here, not inside `semantics {}`:
                                                // that lambda is not a composable scope, so
                                                // `stringResource` cannot be called from it.
                                                val handleDescription = if (editsEnabled) {
                                                    stringResource(
                                                        R.string.cd_reorder,
                                                        program.name ?: program.displayId,
                                                    )
                                                } else {
                                                    stringResource(R.string.cd_reorder_unavailable)
                                                }
                                                Text(
                                                    theme.dragHandleGlyph,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = if (editsEnabled) {
                                                        LocalContentColor.current
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                            .copy(alpha = ProgramListMetrics.DISABLED_ALPHA)
                                                    },
                                                    modifier = Modifier.semantics {
                                                        contentDescription = handleDescription
                                                    },
                                                )
                                            }
                                        }
                                    },
                                    // Dimmed in place rather than translated - the floating ghost below is
                                    // what visually follows the finger, since this row's own composable
                                    // gets torn down by LazyColumn once its untranslated layout slot (which
                                    // a graphicsLayer translation doesn't move) scrolls out of the viewport.
                                    // Empty slots (no preset stored there, shown only when "Show empty
                                    // slots" is checked) get the same dimming treatment to read as
                                    // greyed-out/non-interactive - as does every occupied row, including
                                    // the copy source itself, while picking a destination: nothing about
                                    // it is a valid tap target until the pick is resolved or cancelled.
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .let { theme.rowPanel(it, dragged = isDragged, dropTarget = isDropTarget) }
                                        .alpha(
                                            when {
                                                isDragged -> ProgramListMetrics.DRAGGED_ALPHA
                                                ops.pickingCopy && !isCopyTarget ->
                                                    ProgramListMetrics.DISABLED_ALPHA
                                                isEmpty && !ops.pickingCopy ->
                                                    ProgramListMetrics.EMPTY_SLOT_ALPHA
                                                else -> 1f
                                            },
                                        )
                                        .let { m ->
                                            when {
                                                isCopyTarget -> {
                                                    val description = stringResource(
                                                        R.string.cd_copy_destination, program.displayId,
                                                    )
                                                    // Gated while an edit runs: the picker stays
                                                    // open across the copy it started, so without
                                                    // this a second destination could be tapped
                                                    // and queued behind the first.
                                                    m.clickable(enabled = ops.busy == null) {
                                                        ops.copySource?.let { ops.onCopyConfirmed(it, program) }
                                                    }.semantics { contentDescription = description }
                                                }
                                                // Occupied and not the source: inert while picking,
                                                // same reasoning as the hidden overflow menu above.
                                                ops.pickingCopy -> m
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
                                    // The floating copy that follows the finger mid-drag: purely
                                    // visual, and explicitly hidden from accessibility so it does
                                    // not read as a second control.
                                    Text(
                                        theme.dragHandleGlyph,
                                        style = MaterialTheme.typography.titleMedium,
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
                            // A plain jump rather than animateScrollToItem: that animates through every
                            // intermediate row, and with header rows breaking the list's average-item-size
                            // estimate (used to gauge how far to scroll), a long jump - e.g. bank A to bank
                            // I - would visibly overshoot/correct instead of landing in one smooth motion.
                            // It also sidesteps successive drag-scrub jumps interrupting each other's
                            // animation mid-flight, which was a second source of the same jerkiness.
                            listing.bankHeaderIndex[letter]?.let { row -> scope.launch { listState.scrollToItem(row) } }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }

        // For a device the catalog does not recognise, **or one running firmware nobody has
        // tested this app against** - in both cases its owner is the one person who can send back
        // what the app managed to read off it, and in both cases that report is the most useful
        // thing they can do. For a known instrument on known firmware the report says nothing
        // nobody has already written down, so it lives in the debug menu instead (five taps on
        // the title) rather than on the screen people use.
        if (hasReport && (isUnknownDevice || advisory != null)) {
            val shareReportTitle = stringResource(R.string.programs_share_report)
            val jsonSuffix = stringResource(R.string.programs_json_suffix)
            TextButton(
                onClick = {
                    ops.pendingShare = PendingShare(
                        title = shareReportTitle,
                        // Supplied by the instrument's own reporter: the two families read
                        // entirely different things, and this text used to describe Nord
                        // storage areas to someone holding a Pro-800.
                        description = viewModel.reportDescription,
                        suffix = jsonSuffix,
                        initialStem = viewModel.suggestedReportFilename(),
                        onConfirm = { stem ->
                            sharer.share(stem) { shared ->
                                ops.statusMessage = context.getString(R.string.programs_shared_as, shared)
                            }
                        },
                    )
                },
                // Off while a report is already running, and off while a listing is: the report
                // walks every item on the instrument, so starting one mid-scan puts two long
                // reads on the same serialized bus.
                enabled = !sharer.isRunning && editsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(sharer.progress ?: shareReportTitle)
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

    // Both are guarded on `tagger` as well as on their target, because a disconnect between the
    // read and the dialog opening would otherwise leave a dialog with no facet to save through.
    tagger?.let { facet ->
        ops.favoriteTarget?.let { target ->
            SetFavoriteDialog(
                target = target.slot,
                tags = target.tags,
                taxonomy = facet.taxonomy,
                assignmentCount = facet.assignmentCount,
                onConfirm = { under -> ops.onFavoriteConfirmed(target.slot, under) },
                onDismiss = { ops.favoriteTarget = null },
                // The way out of "this preset has no categories, so it cannot be a favorite" -
                // offered only where they can actually be set, which is not a factory bank.
                onSetCategories = if (facet.canSetCategories(target.slot.address)) {
                    {
                        ops.favoriteTarget = null
                        ops.categoriesTarget = target
                    }
                } else {
                    null
                },
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
 * Counts taps on the instrument name, and says when five of them have landed close enough
 * together to have been meant.
 *
 * Plain state in the composition rather than in the ViewModel: it is transient, it means nothing
 * to any other screen, and a half-finished gesture is not worth surviving anything. The window
 * matters as much as the count - without it, five taps spread over an afternoon of ordinary use
 * would eventually open a screen the user never asked for.
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
 * Dismisses the on-screen keyboard when the dialog that owns this leaves composition.
 *
 * Tied to disposal rather than to each button, because a dialog with a text field has three ways
 * out - confirm, cancel, and tapping outside it - and the keyboard was left standing by all of
 * them. Doing it here means a new dismissal path cannot forget.
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
 * **Segmented buttons rather than tabs.** Tabs say "the same kind of thing, paged"; these three
 * differ in what can be *done* to a row, not merely in which rows are shown, and a segmented
 * control reads as choosing a mode rather than turning a page. It also carries `Role.RadioButton`
 * and a selected state on each segment for free, so a screen reader announces "Factory, selected,
 * 2 of 3" without any semantics written here.
 *
 * The caption is not decoration. Two of these listings would otherwise misrepresent themselves:
 * the factory names are transcribed from Yamaha's Data List rather than read off the instrument,
 * and the favorite marks can be read but not written, so somebody will look for a way to star a
 * voice and needs to be told where that lives instead of hunting for it.
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

/** The segment's label. Kept out of [PresetScope] itself: strings live in `strings.xml`, and the
 * enum is in `core`, which knows nothing about resources. */
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
 * Per-row Rename / Copy / Delete, behind one overflow button.
 *
 * Replaces two always-visible `TextButton`s. Beyond the visual weight, the old arrangement put a
 * destructive action permanently within a few dp of a benign one in a scrolling list, which left
 * the confirmation dialog doing work the layout should have been doing. Copy sits between the
 * two: unlike Delete it gets no confirmation of its own, since it is exactly as safe as Rename -
 * choosing a destination in the list itself (`copySource`/`pickingCopy` in [ProgramsController]) is
 * the only decision it needs from the user.
 */
@Composable
private fun RowActionsMenu(
    program: PresetSlot,
    canRename: Boolean,
    canCopy: Boolean,
    canDelete: Boolean,
    canSetFavorite: Boolean,
    canSetCategories: Boolean,
    /** How many categories this instrument's presets hold - one on a Nord, two on a Motif XS.
     * Decides whether the menu offers "Set category" or "Set categories". */
    categoryCount: Int,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onSetFavorite: () -> Unit,
    onSetCategories: () -> Unit,
    /** False while the listing is still arriving - see `editsEnabled` in ProgramsScreen. */
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // The button is disabled rather than hidden. A control that vanishes and reappears as a
        // list loads is harder to read than one that is visibly not yet available, and the reason
        // is already on screen: the progress row above says which slot is being read.
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(
                Icons.Default.MoreVert,
                // Named for the row it belongs to: in a list of 128, "More options" alone tells a
                // screen-reader user nothing about which preset they are on.
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
            // Before Delete: these are ordinary edits, and the destructive item stays last.
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
 * Right-edge fast-scroll index (a la iOS Contacts): tapping or dragging across a bank letter
 * jumps the list to that bank's caption row. Drag uses detectDragGestures rather than the
 * long-press variant the row-reorder gesture above uses - an index jump should react to the
 * very first touch, not a held one. It lives in its own pointerInput scope on this narrow
 * column, so it only competes with the list-wide drag-to-reorder detector where their hit areas
 * actually overlap (this column's own width) - and there it always wins, since a touch that
 * starts here is already past dragHandleZoneWidthPx, which makes the reorder detector's own
 * onDragStart a no-op regardless of gesture-arbitration timing.
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
            // Inset from the edge and from top and bottom, so the rail reads as something
            // floating *over* the list rather than as the list's own right-hand margin. It used
            // to be a full-bleed strip painted `surface` - the same colour as everything behind
            // it - which made an opaque 48.dp column that hid the rows under it look like
            // nothing at all.
            .padding(vertical = 12.dp, horizontal = 4.dp)
            // Not the 20.dp this used to be: that is why the Motif XS needed a per-bank
            // `shortLabel` - "PREDR" wrapped to three stacked lines. At the rail width a label
            // like "USER DR" fits without abbreviating.
            .width(ProgramListMetrics.railWidth)
            // A container tone rather than `surface`, so it separates itself in both light and
            // dark without a border. Clipped before the background, or the ripple from the label
            // taps below would paint square corners over the rounded ones.
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .let { theme.railDecoration(it) }
            // **After the decoration, before the measurement.** The rivets are painted over the
            // whole plate, so they keep their positions; everything below divides only what is
            // left between them. Putting it here rather than on the Column's outer padding is
            // what keeps dragging and tapping in agreement: `onSizeChanged` and `pointerInput`
            // both sit after it, so the height `jumpToY` divides is the same box the weighted
            // cells fill, and a y position means the same thing to both.
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
            // An equal weighted share of the rail each, and the **whole cell** is the target -
            // not just the glyph plus 3.dp. With `SpaceEvenly` and a wrap-height label, most of
            // the rail was dead space between letters, so a tap that looked on-target did
            // nothing.
            //
            // It also makes the two ways of using the rail agree. `jumpToY` maps a y position by
            // dividing the rail's height into `banks.size` equal bands, which is exactly what
            // these weights now produce - so dragging and tapping resolve the same letter at the
            // same place. Before, the drag bands and the tap targets were subtly different.
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
                    // An index is secondary to what it indexes; `onSurfaceVariant` says so
                    // without making the labels hard to read against the container behind them.
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
