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

/** A row plus the tags read for it - see [ProgramsController.openTagDialog] on why they are read before the dialog opens. */
internal data class TagTarget(val slot: PresetSlot, val tags: PresetTags)

/**
 * Everything ProgramsScreen does, as opposed to everything it draws, so the operation logic can
 * be read and tested without standing up a composition.
 *
 * What lives here is the state an instrument operation owns: what is running, what failed, what
 * the instrument refused, and which dialog the answer comes from. State that is about the view -
 * the search text, the "show empty slots" tick, the refresh spinner, the scroll position - stays
 * in the composable.
 *
 * Held by `remember`, so it lives as long as the screen; the edits themselves outlive it (see
 * [InstrumentViewModel.launchEdit]).
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
     * An operation the instrument's current state blocked, with the fix the family offered and
     * the retry. Separate from [operationError] because this one is answerable.
     */
    var blocked by mutableStateOf<BlockedOperation?>(null)

    // ---- Which dialog is open, and on what ----

    var renameTarget by mutableStateOf<PresetSlot?>(null)
    var renameText by mutableStateOf("")
    var deleteTarget by mutableStateOf<PresetSlot?>(null)

    /** The two tag dialogs, both carrying the tags as read: what a dialog offers depends on what the preset is filed under. */
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

    /** Non-null [pickSource] is "picking a destination is in progress"; there is no second flag to disagree with it. */
    val picking: Boolean get() = pickSource != null

    /**
     * Runs one instrument operation, owning the busy line, the error path and the
     * blocked-by-state dialog for it.
     *
     * @param reloadAfter re-reads the listing once the operation succeeds. False where nothing
     *   was stored - a selection, or a read that only opened a dialog.
     * @param retry re-runs the whole operation, which is what makes a [BlockedOperation]
     *   answerable. Null where there is nothing sensible to retry.
     * @param showProgress whether to put a progress line above the list. False for selection,
     *   which is fast enough that the line would push every row down and let them spring back on
     *   every tap; the operation is still tracked in [busy].
     * @param block returns the line the snackbar shows, or null where there is nothing to report.
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
        // Not the screen's `rememberCoroutineScope()`: an edit outlives the screen - see
        // [InstrumentViewModel.launchEdit]. Writing the result back into this object's state
        // after the screen is gone is harmless, since nothing retains it.
        viewModel.launchEdit {
            operationError = null
            statusMessage = null
            try {
                statusMessage = block()
                if (reloadAfter) viewModel.reloadIndex()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstrumentException.BlockedByDeviceState) {
                // Answerable rather than reportable: the family knows a fix, and it changes what
                // the instrument is playing, so the user decides.
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
        // The confirmation comes from the instrument (see PresetSelector.confirmationFor).
        // Nothing is stored, so nothing is re-listed.
        runEdit(
            busyLabel = strings.get(R.string.programs_busy_selecting, slot.displayId),
            reloadAfter = false,
            retry = { onProgramTapped(slot) },
            showProgress = false,
        ) { viewModel.selectProgram(slot) }
    }

    fun onBlockedRemedyConfirmed(pending: BlockedOperation) {
        // `pending.retry()` is the blocked edit, so it must not become abandonable just because
        // it arrived through the remedy dialog.
        viewModel.launchEdit {
            blocked = null
            operationError = null
            try {
                pending.apply()
                // Only after the fix reports success: the remedy verifies itself, so a silent
                // no-op would show the user the same dialog twice.
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
        // Dismissed before the work starts: an edit takes seconds, and a dialog left standing
        // over it hides the progress line.
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
     * Reads a preset's tags, then opens the dialog that needs them - read before the dialog opens,
     * since a dialog that fills in gives the user a moment where every box is unchecked, which a
     * tap would then write.
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
     * Picking a copy destination has one way in and one way out. A copy's destination is always a
     * user slot, so starting one from the factory listing moves the browser there, and where it
     * came from is remembered so cancelling returns to it. Both exits go through [endPicking].
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
     * Leaves destination-picking, going back to where it started - except after a copy, which
     * stays in the user banks where the new voice is. A cancelled pick has made nothing, so it
     * does go back.
     */
    fun endPicking(returnToPreviousScope: Boolean = true) {
        pickSource = null
        if (returnToPreviousScope) scopeBeforePicking?.let { viewModel.setScope(it) }
        scopeBeforePicking = null
    }

    /**
     * Resolves a destination pick, as whichever operation [beginPicking] was started for.
     *
     * @param destinationIsEmpty decides move-vs-swap for [PickIntent.MOVE], as a drop does.
     *   Ignored for a copy, which only offers empty destinations.
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
            // The picker closes only once the operation has landed; on failure it stays open, so
            // the error shows against the change that was about to be made.
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
     * @param occupied which addresses hold a preset, from the listing rather than re-derived,
     *   since the drop was aimed at the rows the user can see.
     */
    fun onSwapDropped(source: PresetSlot, target: PresetSlot, occupied: Set<SlotAddress>) {
        if (source.address == target.address) return
        val targetWasEmpty = target.address !in occupied
        // onDragStart already blocks a drag from an empty row; this is a re-check. An empty target
        // is valid, and targetWasEmpty picks move over swap.
        if (source.address !in occupied) return
        // No confirmation, even for an emulated operation: the safety lives in the operation
        // itself - read-back verification, destructive step last, rollback on a half-done swap.
        runRelocation(source, target, targetWasEmpty)
    }
}

/** Keyed on both collaborators, so a new session or configuration does not inherit a half-finished operation's dialog state. */
@Composable
internal fun rememberProgramsController(viewModel: InstrumentViewModel): ProgramsController {
    val resources = LocalResources.current
    return remember(viewModel, resources) {
        ProgramsController(viewModel) { resId, args -> resources.getString(resId, *args) }
    }
}
