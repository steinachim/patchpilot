package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.zIndex
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListItemInfo
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The preset browser. Tapping a preset loads it. Long-pressing a preset's drag handle and dropping
 * it elsewhere relocates it: onto an occupied row that's a two-way swap, onto an empty slot a
 * one-way move. onSwapDropped picks between them from the loaded index, since an instrument with
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
 * onSwapDropped takes the two rows' slots rather than indices, since that's what the
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
private fun reportFailure(what: String, error: Throwable, fallback: String): String {
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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }

    // What an edit currently in flight is doing, or null when nothing is.
    //
    // **These are seconds, not milliseconds, and the instrument gives nothing away.** A copy is a
    // whole-voice read, a write, a commit and a read-back; on a drum kit that is ~12.6 kB each way
    // and takes several seconds, during which the app looked exactly as it did before the tap.
    // The user could not tell that the destination had registered, let alone that anything was
    // happening - so the natural response is to tap again.
    var busy by remember { mutableStateOf<String?>(null) }
    var showEmptySlots by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    // An operation the instrument's *current state* blocked, together with the fix the family
    // offered and the action to retry once it is applied. Kept separate from `operationError`
    // because this one is answerable: the user gets a choice, not a report.
    var blocked by remember { mutableStateOf<BlockedOperation?>(null) }
    var renameTarget by remember { mutableStateOf<PresetSlot?>(null) }
    var deleteTarget by remember { mutableStateOf<PresetSlot?>(null) }
    // The row a "Copy to…" tap started from - non-null while the destination picker is open.
    var copySource by remember { mutableStateOf<PresetSlot?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Share-report dialog: null while closed, otherwise what is about to be shared.
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
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
    // Non-null copySource doubles as "picking a copy destination is in progress" - there is no
    // separate boolean to let drift out of sync with it.
    val pickingCopy = copySource != null
    val canSelect = remember(session) { viewModel.canSelect }

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

    // A setting the instrument cannot report and the app must not guess - currently only a
    // Pro-800 in DIP-switch mode, asking which MIDI channel it listens on. Shown here rather than
    // on the connect screen because that one navigates away the instant a connection lands.
    val setupQuestion by viewModel.setupQuestion.collectAsState()

    // Brief confirmations (selected/swapped/renamed/deleted) surface as a Snackbar.
    //
    // This used to be a `Toast`, which is a *system* overlay: it outlives the screen that raised
    // it, cannot host an action, and sits outside the app's own accessibility tree. A Snackbar
    // belongs to this Scaffold, which is both the convention and the prerequisite for ever
    // offering "Undo" on a delete.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        // Clear *after* showing, never before. `showSnackbar` suspends until the snackbar is
        // dismissed, and clearing first changes this effect's key - which cancels the very call
        // that was about to render it. The Toast this replaced hid the mistake, because
        // `Toast.show()` does not suspend and had already completed by then.
        snackbarHostState.showSnackbar(message, withDismissAction = true)
        statusMessage = null
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
    LaunchedEffect(operationError, sharer.error) {
        val message = operationError ?: sharer.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Indefinite,
        )
        operationError = null
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
    val editsEnabled = index.complete && busy == null

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
    val listing = remember(programs, showEmptySlots, searchText, pickingCopy) {
        buildProgramListing(
            reported = programs,
            allSlots = viewModel.allSlots(),
            showEmptySlots = showEmptySlots,
            pickingCopy = pickingCopy,
            searchText = searchText,
        )
    }
    val visibleRows = listing.rows
    val occupied = listing.occupied
    // Drawn in the rail instead of the full label, which does not fit at the rail's width.
    val bankRailLabels = remember(index) { viewModel.bankRailLabels() }
    // Hidden rather than shown-disabled where there is nowhere to copy to: the row menu has no
    // other disabled-but-visible items, and entering picking mode only to find no valid target
    // is not worth offering.
    val canCopy = canCopyOp && listing.freeSlots.isNotEmpty()

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
    /**
     * Runs one instrument operation with this screen's bookkeeping around it.
     *
     * **Five copies of this shape used to sit here**, one per operation, differing only in the verb
     * and the call - and they had already drifted: selection skipped the reload (correctly, since
     * it stores nothing) while copy cleared its picker inside the `try`, so a failed copy left the
     * picker open and a successful one closed it.
     *
     * [busyLabel] is both what the progress line shows and, with its ellipsis trimmed, the phrase
     * the failure message and the log line are built from - so the two can no longer disagree about
     * capitalisation the way "Selecting A:1:1…" and "selecting A:1:1" did.
     *
     * @param reloadAfter false for an operation that changes nothing stored. Selection is the only
     *   one, and re-listing after it would be a round trip per tap for no new information.
     * @param retry re-runs this operation after the user accepts a family's remedy. Non-null makes
     *   the operation *answerable* when the instrument's state blocks it: a Motif XS in Performance
     *   mode ignores a voice selection silently, and one documented message fixes it. Only selection
     *   passed one before, because the catch clause lived in its copy of this block; every operation
     *   can offer it now.
     */
    fun runEdit(
        busyLabel: String,
        reloadAfter: Boolean = true,
        retry: (() -> Unit)? = null,
        block: suspend () -> String,
    ) {
        val what = busyLabel.trimEnd('…', ' ')
        val failed = context.getString(R.string.programs_operation_failed, what)
        scope.launch {
            operationError = null
            statusMessage = null
            try {
                busy = busyLabel
                statusMessage = block()
                if (reloadAfter) viewModel.reloadIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstrumentException.BlockedByDeviceState) {
                // Answerable rather than merely reportable: the family knows a fix and the user
                // decides whether to take it. That fix changes what the instrument is playing,
                // so it is never applied without asking.
                val fix = e.remedy
                if (fix == null || retry == null) {
                    operationError = reportFailure(what, e, failed)
                } else {
                    blocked = BlockedOperation(
                        message = e.message.orEmpty(),
                        detail = e.remedyDetail,
                        actionLabel = e.remedyLabel
                            ?: context.getString(R.string.programs_blocked_action_default),
                        apply = fix,
                        retry = retry,
                    )
                }
            } catch (e: Exception) {
                operationError = reportFailure(what, e, failed)
            } finally {
                // In a finally, always: a failure that left the bar running would claim the app
                // was still working on something it had given up on.
                busy = null
            }
        }
    }

    fun onProgramTapped(slot: PresetSlot) {
        // The confirmation comes from the instrument, not from here: one that echoes the address
        // back can honestly say "Selected", one that is sent a fire-and-forget message cannot
        // (see PresetSelector.confirmationFor). Nothing is stored, so nothing is re-listed.
        runEdit(
            busyLabel = context.getString(R.string.programs_busy_selecting, slot.displayId),
            reloadAfter = false,
            retry = { onProgramTapped(slot) },
        ) { viewModel.selectProgram(slot) }
    }

    fun onBlockedRemedyConfirmed(pending: BlockedOperation) {
        scope.launch {
            blocked = null
            operationError = null
            try {
                pending.apply()
                // Retry only after the fix reports success. The remedy verifies itself - a mode
                // change draws no reply of its own - so a silent no-op here would otherwise show
                // the user the same dialog twice with no explanation.
                pending.retry()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val what = context.getString(R.string.programs_busy_applying_fix).trimEnd('…', ' ')
                operationError =
                    reportFailure(what, e, context.getString(R.string.programs_operation_failed, what))
            }
        }
    }

    fun onDeleteConfirmed(slot: PresetSlot) {
        // Dismissed *before* the work starts, not after it finishes. An edit is seconds on this
        // instrument, and a dialog left standing over them hides the progress line.
        deleteTarget = null
        runEdit(context.getString(R.string.programs_busy_deleting, slot.displayId)) {
            viewModel.deleteProgram(slot)
        }
    }

    fun onRenameConfirmed(slot: PresetSlot, newName: String) {
        renameTarget = null
        runEdit(context.getString(R.string.programs_busy_renaming, slot.displayId)) {
            viewModel.renameProgram(slot, newName)
        }
    }

    fun onCopyConfirmed(source: PresetSlot, destination: PresetSlot) {
        runEdit(
            context.getString(
                R.string.programs_busy_copying, source.displayId, destination.displayId,
            ),
        ) {
            // The picker closes only once the copy has actually landed. On failure it stays open,
            // same reasoning as delete: show what went wrong against the copy that was about to be
            // made rather than dismissing first.
            viewModel.copyProgram(source, destination).also { copySource = null }
        }
    }

    fun runRelocation(source: PresetSlot, target: PresetSlot, targetWasEmpty: Boolean) {
        val label = if (targetWasEmpty) R.string.programs_busy_moving else R.string.programs_busy_swapping
        runEdit(context.getString(label, source.displayId, target.displayId)) {
            viewModel.moveProgram(source, target, targetWasEmpty)
        }
    }

    fun onSwapDropped(source: PresetSlot, target: PresetSlot) {
        if (source.address == target.address) return
        val targetWasEmpty = target.address !in occupied
        // A source has nothing to move - onDragStart already blocks starting a drag from an
        // empty row, this is just a defensive re-check. An empty *target*, on the other hand, is
        // a valid drop target; targetWasEmpty picks the operation, since an instrument with a
        // separate one-way move rejects the two-way swap there.
        if (source.address !in occupied) return
        // No confirmation step, even where the operation is emulated rather than a single device
        // command: composed move and swap were verified on real hardware, and a dialog in front of
        // every drag is friction the reliability does not justify. The safety that mattered lives
        // in the operation itself - read-back verification, destructive step last, rollback on a
        // half-completed swap - not in asking first.
        runRelocation(source, target, targetWasEmpty)
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
        // and tapping one starts the copy immediately - see onCopyConfirmed. This banner is the
        // one persistent sign that mode is active, and its Cancel is the only way out besides
        // picking a target; there is deliberately no dialog stacked on top of another.
        copySource?.let { source ->
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
                    TextButton(onClick = { copySource = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        // Hidden rather than disabled while picking a copy destination: neither has any effect on
        // the list in that mode (see visiblePrograms/bankLabels), and the banner above already
        // says what mode this is - a filter and a checkbox that visibly do nothing would only
        // repeat that with dead controls.
        if (!pickingCopy) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                label = { Text(stringResource(R.string.programs_filter_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // One control, not two nodes: the label is part of the target and TalkBack reads it
            // as a single checkbox rather than a box and an unrelated string.
            Row(
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
        busy?.let { what ->
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
                    .pointerInput(visibleRows, canRelocate, index.complete, pickingCopy) {
                        // Dragging is off while the index is still arriving: a drop into a region
                        // that has not loaded yet has no defined target, and off entirely on an
                        // instrument that cannot relocate presets at all. Also off while picking a
                        // copy destination - the two gestures would otherwise fight over the same
                        // rows, and a copy pick is resolved by a tap, not a drag.
                        if (!canRelocate || !index.complete || pickingCopy) return@pointerInput
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
                                    onSwapDropped(source.item, target.item)
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
                                    stringResource(R.string.programs_no_presets)
                                } else {
                                    stringResource(R.string.programs_no_matches, searchText)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (searchText.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { searchText = "" }) { Text(stringResource(R.string.action_clear_filter)) }
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
                                val isCopyTarget = pickingCopy && isEmpty
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
                                        // Badges are short family-supplied strings (a preset
                                        // version, say) rendered uniformly, so a family can add
                                        // one without this screen learning what it means.
                                        Text((listOf(program.displayId) + program.badges).joinToString("  "))
                                    },
                                    colors = ListItemDefaults.colors(containerColor = rowColor),
                                    // Hidden outright while picking, not merely disabled: none of
                                    // rename/copy/delete applies to *any* row until the pick is
                                    // resolved or cancelled, including the row being copied from.
                                    trailingContent = if (pickingCopy || isEmpty ||
                                        (!canRename && !canDelete && !canCopy)
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
                                                canRename = canRename,
                                                canCopy = canCopy,
                                                canDelete = canDelete,
                                                enabled = editsEnabled,
                                                onRename = {
                                                    renameTarget = program
                                                    renameText = program.name.orEmpty()
                                                },
                                                onCopy = { copySource = program },
                                                onDelete = { deleteTarget = program },
                                            )
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
                                            if (!isEmpty && canRelocate && !pickingCopy) {
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
                                                pickingCopy && !isCopyTarget ->
                                                    ProgramListMetrics.DISABLED_ALPHA
                                                isEmpty && !pickingCopy ->
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
                                                    m.clickable(enabled = busy == null) {
                                                        copySource?.let { onCopyConfirmed(it, program) }
                                                    }.semantics { contentDescription = description }
                                                }
                                                // Occupied and not the source: inert while picking,
                                                // same reasoning as the hidden overflow menu above.
                                                pickingCopy -> m
                                                isEmpty || !canSelect -> m
                                                else -> m.clickable { onProgramTapped(program) }
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
                    pendingShare = PendingShare(
                        title = shareReportTitle,
                        // Supplied by the instrument's own reporter: the two families read
                        // entirely different things, and this text used to describe Nord
                        // storage areas to someone holding a Pro-800.
                        description = viewModel.reportDescription,
                        suffix = jsonSuffix,
                        initialStem = viewModel.suggestedReportFilename(),
                        onConfirm = { stem ->
                            sharer.share(stem) { shared ->
                                statusMessage = context.getString(R.string.programs_shared_as, shared)
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

    setupQuestion?.let { question ->
        SetupQuestionDialog(question = question, onAnswer = { viewModel.answerSetup(it) })
    }

    pendingShare?.let { pending ->
        ShareFilenameDialog(pending, onDismiss = { pendingShare = null })
    }

    deleteTarget?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            emulated = viewModel.isEmulatedEdit(EditOp.DELETE),
            onConfirm = { onDeleteConfirmed(target) },
            onDismiss = { deleteTarget = null },
        )
    }

    blocked?.let { pending ->
        BlockedOperationDialog(
            pending = pending,
            onApply = { onBlockedRemedyConfirmed(pending) },
            onDismiss = { blocked = null },
        )
    }

    renameTarget?.let { target ->
        HideKeyboardOnDismiss(keyboardController)
        RenameDialog(
            target = target,
            text = renameText,
            onTextChange = { renameText = it },
            maxNameLength = maxNameLength,
            onConfirm = { onRenameConfirmed(target, renameText) },
            onDismiss = { renameTarget = null },
        )
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
 * Per-row Rename / Copy / Delete, behind one overflow button.
 *
 * Replaces two always-visible `TextButton`s. Beyond the visual weight, the old arrangement put a
 * destructive action permanently within a few dp of a benign one in a scrolling list, which left
 * the confirmation dialog doing work the layout should have been doing. Copy sits between the
 * two: unlike Delete it gets no confirmation of its own, since it is exactly as safe as Rename -
 * choosing a destination in the list itself (`copySource`/`pickingCopy` in `ProgramsScreen`) is
 * the only decision it needs from the user.
 */
@Composable
private fun RowActionsMenu(
    program: PresetSlot,
    canRename: Boolean,
    canCopy: Boolean,
    canDelete: Boolean,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
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
