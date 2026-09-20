// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.CancellationException

/** What an edit currently in flight is doing, or null when nothing is. */
internal data class BusyOperation(val label: String, val showProgress: Boolean)

/**
 * A row plus the tags read for it, which is what a tag dialog opens on.
 *
 * The tags travel with the row rather than being re-read by the dialog: see
 * [ProgramsController.openTagDialog] on why they are read before it opens.
 */
internal data class TagTarget(val slot: PresetSlot, val tags: PresetTags)

/**
 * Everything ProgramsScreen *does*, as opposed to everything it draws.
 *
 * **Why this is not a set of `remember { mutableStateOf(...) }` calls and local functions in the
 * composable.** Separated, the operation logic can be read - and changed - without scrolling
 * through layout, and it can be tested without standing up a whole composition.
 *
 * **State that is genuinely about the view stays in the composable.** The search box's text, the
 * "show empty slots" tick, the pull-to-refresh spinner and the list's scroll position describe
 * what is on screen and nothing else; they are not operations and they do not belong here. What
 * lives here is the state an *instrument operation* owns: what is running, what failed, what the
 * instrument refused, and which dialog the answer is going to come from.
 *
 * Held by `remember`, so it lives exactly as long as the screen does - one exception being the
 * edits themselves, which [InstrumentViewModel.launchEdit] deliberately outlives it.
 */
@Stable
internal class ProgramsController(
    private val viewModel: ProgramsOperations,
    private val strings: StringResolver,
) {

    // ---- What an operation is doing, and what came of it ----

    /** The line the snackbar shows after an operation succeeds. */
    var statusMessage by mutableStateOf<String?>(null)

    /** The line shown when one fails. Cleared at the start of the next attempt, not on dismissal. */
    var operationError by mutableStateOf<String?>(null)

    /**
     * The operation in flight, or null - where more than one is, the one with a progress line.
     *
     * **These are seconds, not milliseconds, and the instrument gives nothing away.** A copy is a
     * whole-voice read, a write, a commit and a read-back; on a drum kit that is ~12.6 kB each way
     * and takes several seconds. The screen gates every other edit, the scope selector and the
     * back arrow on this, and [onPickConfirmed] refuses a second destination while it is set.
     * Selection alone stays open: it stores nothing, and auditioning a preset while an edit
     * finishes is reasonable - which is why this is derived from every operation in flight
     * rather than being one slot the last one to start overwrites: a selection that finishes
     * while a delete is still running must not take the delete's line, and its gates, with it.
     *
     * **Registered synchronously by [runEdit], before the edit is launched.** A gate that only
     * took effect once the coroutine had started would let two taps in the same frame through.
     */
    val busy: BusyOperation?
        get() = inFlight.firstOrNull { it.showProgress } ?: inFlight.firstOrNull()

    private val inFlight = mutableStateListOf<BusyOperation>()

    /**
     * An operation the instrument's *current state* blocked, together with the fix the family
     * offered and the action to retry once it is applied. Kept separate from [operationError]
     * because this one is answerable: the user gets a choice, not a report.
     */
    var blocked by mutableStateOf<BlockedOperation?>(null)

    // ---- Which dialog is open, and on what ----

    var renameTarget by mutableStateOf<PresetSlot?>(null)
    var renameText by mutableStateOf("")
    var deleteTarget by mutableStateOf<PresetSlot?>(null)

    /**
     * The two tag dialogs. Both carry the tags as read, not just the row: what the dialog offers
     * depends on what the preset is already filed under, so it cannot open until that is known.
     */
    var favoriteTarget by mutableStateOf<TagTarget?>(null)
    var categoriesTarget by mutableStateOf<TagTarget?>(null)

    /** Share-report dialog: null while closed, otherwise what is about to be shared. */
    var pendingShare by mutableStateOf<PendingShare?>(null)

    // ---- Destination picking, for both copy and move ----

    /** The row a "Copy to…" or "Move to…" tap started from, and which of the two it was. */
    var pickSource by mutableStateOf<PresetSlot?>(null)
        private set
    var pickIntent by mutableStateOf(PickIntent.COPY)
        private set

    private var scopeBeforePicking by mutableStateOf<PresetScope?>(null)

    /**
     * Non-null [pickSource] doubles as "picking a destination is in progress" - there is no second
     * flag that can disagree with it.
     */
    val picking: Boolean get() = pickSource != null

    /**
     * Runs one instrument operation, owning the busy line, the error path and the blocked-by-state
     * dialog for it.
     *
     * @param reloadAfter re-reads the listing once the operation succeeds. False where nothing was
     *   stored - a selection, or a read that only opened a dialog.
     * @param retry re-runs the whole operation, and is what makes a [BlockedOperation] answerable.
     *   Null where there is nothing sensible to retry.
     * @param showProgress whether to put a progress line above the list while this runs. False for
     *   selection, which is the one operation fast enough that the line is pure cost: it appears
     *   and disappears within a couple of hundred milliseconds, and because it sits above the list
     *   it pushes every row down and lets them spring back on every single tap. The operation is
     *   still tracked in [busy]; it just does not move the thing the user is aiming at.
     * @param block returns the line the snackbar shows, or null where there is nothing to report -
     *   an operation whose whole result is a dialog that just opened, say.
     */
    fun runEdit(
        busyLabel: String,
        reloadAfter: Boolean = true,
        retry: (() -> Unit)? = null,
        showProgress: Boolean = true,
        block: suspend () -> String?,
    ) {
        val what = busyLabel.trimEnd('…', ' ')
        val failed = strings.get(R.string.programs_operation_failed, what)
        // Before the launch, not inside it - see [busy].
        val mine = BusyOperation(busyLabel, showProgress)
        inFlight += mine
        // **Not the screen's `rememberCoroutineScope()`.** An edit outlives the screen
        // deliberately - see [InstrumentViewModel.launchEdit] for why cancelling one mid-write is
        // the one thing that can leave an instrument stuck. Writing the result back into this
        // object's state after the screen is gone is harmless; nothing retains it.
        viewModel.launchEdit {
            operationError = null
            statusMessage = null
            try {
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
                            ?: strings.get(R.string.programs_blocked_action_default),
                        apply = fix,
                        retry = retry,
                    )
                }
            } catch (e: Exception) {
                operationError = reportFailure(what, e, failed)
            } finally {
                // In a finally, always: a failure that left the bar running would claim the app
                // was still working on something it had given up on.
                inFlight.remove(mine)
            }
        }
    }

    fun onProgramTapped(slot: PresetSlot) {
        // The confirmation comes from the instrument, not from here: one that echoes the address
        // back can honestly say "Selected", one that is sent a fire-and-forget message cannot
        // (see PresetSelector.confirmationFor). Nothing is stored, so nothing is re-listed.
        runEdit(
            busyLabel = strings.get(R.string.programs_busy_selecting, slot.displayId),
            reloadAfter = false,
            retry = { onProgramTapped(slot) },
            showProgress = false,
        ) { viewModel.selectProgram(slot) }
    }

    fun onBlockedRemedyConfirmed(pending: BlockedOperation) {
        // Same reasoning as runEdit: `pending.retry()` is the edit that was blocked, and it must
        // not become abandonable just because it arrived via the remedy dialog.
        viewModel.launchEdit {
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
                val what = strings.get(R.string.programs_busy_applying_fix).trimEnd('…', ' ')
                operationError =
                    reportFailure(what, e, strings.get(R.string.programs_operation_failed, what))
            }
        }
    }

    fun onDeleteConfirmed(slot: PresetSlot) {
        // Dismissed *before* the work starts, not after it finishes. An edit is seconds on this
        // instrument, and a dialog left standing over them hides the progress line.
        deleteTarget = null
        runEdit(strings.get(R.string.programs_busy_deleting, slot.displayId)) {
            viewModel.deleteProgram(slot)
        }
    }

    fun onRenameConfirmed(slot: PresetSlot, newName: String) {
        renameTarget = null
        runEdit(strings.get(R.string.programs_busy_renaming, slot.displayId)) {
            viewModel.renameProgram(slot, newName)
        }
    }

    /**
     * Reads a preset's tags, then opens the dialog that needs them.
     *
     * **Read before the dialog opens, not inside it.** A dialog that appears empty and fills in
     * gives the user a moment where every box is unchecked, which is indistinguishable from "not
     * a favorite" - and a tap in that moment writes that. Reading first costs at most one small
     * dump, and [runEdit] already owns the busy line and the error path.
     */
    fun openTagDialog(slot: PresetSlot, forFavorite: Boolean) {
        runEdit(
            strings.get(R.string.programs_busy_reading_tags, slot.displayId),
            reloadAfter = false,
            showProgress = false,
        ) {
            val tags = viewModel.presetTags(slot)
            val target = TagTarget(slot, tags)
            if (forFavorite) favoriteTarget = target else categoriesTarget = target
            // Nothing to report: the dialog that just opened is the result.
            null
        }
    }

    fun onFavoriteConfirmed(slot: PresetSlot, under: Set<Int>) {
        favoriteTarget = null
        runEdit(strings.get(R.string.programs_busy_favoriting, slot.displayId)) {
            viewModel.setFavorite(slot, under)
        }
    }

    fun onCategoriesConfirmed(slot: PresetSlot, categories: List<CategoryRef?>) {
        categoriesTarget = null
        runEdit(strings.get(R.string.programs_busy_categorising, slot.displayId)) {
            viewModel.setCategories(slot, categories)
        }
    }

    /*
     * Picking a copy destination has one way in and one way out, deliberately.
     *
     * A copy's destination is always a *user* slot, so starting one from the factory listing has
     * to move the browser there - picking in a list of read-only rows would offer targets the
     * instrument refuses. Where it came from is remembered so cancelling, or finishing, puts the
     * user back in the listing they were browsing rather than stranding them in the user banks.
     *
     * Both exits go through [endPicking], which is what keeps them from drifting apart once there
     * is more to undo than one field.
     */
    fun beginPicking(source: PresetSlot, intent: PickIntent = PickIntent.COPY) {
        val browseScope = viewModel.scope.value
        if (browseScope != PresetScope.USER) {
            scopeBeforePicking = browseScope
            viewModel.setScope(PresetScope.USER)
        }
        pickIntent = intent
        pickSource = source
    }

    /**
     * Leaves destination-picking, going back to where it started - or staying put after a copy.
     *
     * **A finished copy deliberately does not return to the factory listing.** The new voice is in
     * the user banks and that is what the user just made; bouncing back to the read-only list they
     * launched from hides the result of the action and leaves them to find their way to it. A
     * cancelled pick has made nothing, so it does go back.
     */
    fun endPicking(returnToPreviousScope: Boolean = true) {
        pickSource = null
        if (returnToPreviousScope) scopeBeforePicking?.let { viewModel.setScope(it) }
        scopeBeforePicking = null
    }

    /**
     * Resolves a destination pick, as whichever operation [beginPicking] was started for.
     *
     * @param destinationIsEmpty decides move-vs-swap for [PickIntent.MOVE], the same way a drop
     *   does - an instrument with a separate one-way move refuses the two-way swap onto an empty
     *   slot. Ignored for a copy, which only ever offers empty destinations.
     */
    fun onPickConfirmed(source: PresetSlot, destination: PresetSlot, destinationIsEmpty: Boolean) {
        // The picker stays open across the operation it started (see below), so a second row
        // tapped before the first copy has landed would queue a second copy behind it. The row's
        // own `enabled` flag lags a recomposition behind; this does not.
        if (busy != null) return
        val label = when (pickIntent) {
            PickIntent.COPY -> strings.get(
                R.string.programs_busy_copying, source.displayId, destination.displayId,
            )
            PickIntent.MOVE -> strings.get(
                if (destinationIsEmpty) R.string.programs_busy_moving else R.string.programs_busy_swapping,
                source.displayId,
                destination.displayId,
            )
        }
        val intent = pickIntent
        runEdit(label) {
            // The picker closes only once the operation has actually landed. On failure it stays
            // open, same reasoning as delete: show what went wrong against the change that was
            // about to be made rather than dismissing first.
            when (intent) {
                PickIntent.COPY -> viewModel.copyProgram(source, destination)
                PickIntent.MOVE -> viewModel.moveProgram(source, destination, destinationIsEmpty)
            }.also { endPicking(returnToPreviousScope = false) }
        }
    }

    fun runRelocation(source: PresetSlot, target: PresetSlot, targetWasEmpty: Boolean) {
        val label = if (targetWasEmpty) R.string.programs_busy_moving else R.string.programs_busy_swapping
        runEdit(strings.get(label, source.displayId, target.displayId)) {
            viewModel.moveProgram(source, target, targetWasEmpty)
        }
    }

    /**
     * @param occupied which addresses currently hold a preset - the listing's own view of it,
     *   passed in rather than re-derived, since the drop was aimed at the rows the user can see.
     */
    fun onSwapDropped(source: PresetSlot, target: PresetSlot, occupied: Set<SlotAddress>) {
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
}

/**
 * Keyed on both collaborators, so a new session or a new configuration builds a new controller
 * rather than carrying a half-finished operation's dialog state into one.
 */
@Composable
internal fun rememberProgramsController(viewModel: InstrumentViewModel): ProgramsController {
    val resources = LocalResources.current
    return remember(viewModel, resources) {
        ProgramsController(viewModel) { resId, args -> resources.getString(resId, *args) }
    }
}
