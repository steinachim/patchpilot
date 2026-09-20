// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class Status { PASS, FAIL, SKIPPED }

/**
 * Why [RegressionTester] asks before testing against a real preset.
 *
 * The first two put rename, move and swap on real data, because there is no sandbox copy at all.
 * [NO_SECOND_FREE_SLOT] is narrower: the copy exists, but swapping it needs a second occupied
 * slot, and with only one free slot the only candidate is the real preset it was copied from.
 */
enum class OccupiedSlotReason { NO_FREE_SLOT, CANNOT_COPY, NO_SECOND_FREE_SLOT }

/** One test's outcome. [detail] says what actually happened, in the report's own words. */
data class RegressionResult(val name: String, val status: Status, val detail: String)

data class RegressionReport(val results: List<RegressionResult>, val summary: String) {

    /** The report as shareable plain text: unlike a device report, this is read by a person. */
    fun asText(): String = buildString {
        appendLine(summary)
        appendLine()
        results.forEach { result ->
            appendLine("${result.status.name.padEnd(7)} ${result.name}")
            appendLine("        ${result.detail}")
        }
    }
}

/**
 * Exercises every feature the app enables for the connected instrument, without destroying
 * anything, and says what worked.
 *
 * In `core/` because it tests the facets rather than a family: an operation the instrument does
 * not declare is skipped and said to be skipped, so one engine covers every family.
 *
 * Non-destructive by construction. Every mutating test runs against a copy the test made
 * ([EditOp.COPY] into a free slot) and deletes again - the swap included, which swaps the copy
 * with a second copy rather than with the preset it came from, so a process killed between the
 * swap and the swap-back displaces only scratch data. Where no sandbox can exist (no free slot,
 * or a family without copy), or the swap has no second free slot, the tests concerned run
 * against real data only after [onConfirmRealSlotMutation] says the user agreed, and each
 * reverts itself immediately. Delete is never run against real data.
 *
 * The two `suspend (...) -> Boolean` callbacks ask the user a question mid-run without this
 * class knowing a UI exists. One run per instance.
 */
class RegressionTester(
    private val instrument: Instrument,
    private val allSlots: () -> List<SlotAddress>,
    private val occupiedSlots: () -> Set<SlotAddress>,
    /** Asks whether the instrument really shows what it was just told to load. */
    private val onConfirmSelect: suspend (shownOnDevice: String) -> Boolean,
    /** Asks whether rename/move/swap may run against a slot holding real data, and revert. */
    private val onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
    /** Keeps the app's own cached listing in step with what the tests changed. */
    private val refreshEdited: suspend (List<SlotAddress>) -> Unit,
) {

    suspend fun run(progress: (String) -> Unit): RegressionReport {
        val results = facetInventory() +
            selectorChecks(progress) +
            transferChecks(progress) +
            reportChecks(progress) +
            editorChecks(progress)
        return RegressionReport(results, summarize(results))
    }

    // ---- Capability inventory ----

    /** What this instrument declares before anything is tried, so a later `SKIPPED` has something to point back at. */
    private fun facetInventory(): List<RegressionResult> = listOf(
        "Loads presets" to instrument.selector,
        "Edits presets" to instrument.editor,
        "Reads and writes preset data" to instrument.transfer,
        "Describes itself" to instrument.report,
        "Categorises and favorites presets" to instrument.tagger,
    ).map { (what, facet) ->
        if (facet != null) {
            RegressionResult("Offers: $what", Status.PASS, "declared by this instrument")
        } else {
            RegressionResult("Offers: $what", Status.SKIPPED, "not offered by this instrument")
        }
    }

    // ---- Read-only checks ----

    private suspend fun selectorChecks(progress: (String) -> Unit): List<RegressionResult> {
        val selector = instrument.selector ?: return emptyList()
        val occupied = occupiedInOrder()
        val target = occupied.firstOrNull() ?: return listOf(skip(SELECT, NOTHING_STORED))
        // Selects something else first, from the far end of the instrument's range, so the check
        // on the target below cannot be answered "yes" just because the target already happened to
        // be on the display before this test ran.
        val decoy = occupied.lastOrNull { it != target }
        return listOf(
            step(SELECT, progress) {
                // No family reports what is on its display, so the only witness to a preset
                // having loaded is the person holding it - for the decoy as much as for the
                // target: an unconfirmed decoy select is not a baseline.
                if (decoy != null) {
                    val decoySlot = instrument.browser.refresh(decoy)
                    selector.select(decoy)
                    val decoyShown = decoySlot.name?.let { "\"$it\" in ${decoySlot.displayId}" } ?: decoySlot.displayId
                    check(onConfirmSelect(decoyShown)) {
                        "The instrument did not show $decoyShown after it was told to load it " +
                            "(checked first, to rule out the next preset already being on the display)."
                    }
                }
                val slot = instrument.browser.refresh(target)
                selector.select(target)
                // The slot ID is part of the question, not just the name: two presets can share a
                // name, and only the address says which one was meant.
                val shown = slot.name?.let { "\"$it\" in ${slot.displayId}" } ?: slot.displayId
                check(onConfirmSelect(shown)) {
                    "The instrument did not show $shown after it was told to load it."
                }
                "${selector.confirmationFor(slot.displayId)} Confirmed on the instrument."
            },
        )
    }

    private suspend fun transferChecks(progress: (String) -> Unit): List<RegressionResult> {
        val transfer = instrument.transfer ?: return emptyList()
        val target = occupiedInOrder().firstOrNull() ?: return listOf(skip(READ_DATA, NOTHING_STORED))
        return listOf(
            // Read only. Writing a blob back needs a slot to write it to, and this runs before
            // any sandbox slot exists.
            step(READ_DATA, progress) {
                val blob = transfer.read(target)
                check(blob.isNotEmpty()) { "Reading ${display(target)} returned no data." }
                "Read ${blob.size} bytes from ${display(target)}."
            },
        )
    }

    private suspend fun reportChecks(progress: (String) -> Unit): List<RegressionResult> {
        val report = instrument.report ?: return emptyList()
        return listOf(
            step(BUILD_REPORT, progress) {
                val built = report.buildReport { step -> progress("$BUILD_REPORT: $step") }
                check(built.json.isNotBlank()) { "The device report came back empty." }
                // A read that could not complete is a finding, not a failure of this step: the
                // report is built to survive exactly that, and says what it could not read.
                if (built.isComplete) {
                    "Built ${built.json.length} characters of JSON."
                } else {
                    "Built ${built.json.length} characters of JSON; ${built.failures.size} of its " +
                        "reads failed (${built.failures.keys.joinToString()})."
                }
            },
        )
    }

    // ---- Mutating checks ----

    private suspend fun editorChecks(progress: (String) -> Unit): List<RegressionResult> {
        val editor = instrument.editor ?: return emptyList()
        val occupied = occupiedInOrder()
        if (occupied.isEmpty()) {
            return listOf(RENAME, MOVE, SWAP, COPY, DELETE).map { skip(it, NOTHING_STORED) }
        }
        val free = allSlots().filterNot { it in occupiedSlots() }
        return if (EditOp.COPY in editor.supported && free.isNotEmpty()) {
            sandboxChecks(editor, occupied, free, progress)
        } else {
            occupiedSlotChecks(editor, occupied, free, progress)
        }
    }

    /**
     * The safe path: make a copy, put the copy through every edit, delete the copy.
     *
     * Only the copies and the free slots they are moved through are written. The swap needs two
     * occupied slots, so it makes a second copy to swap the first with, and swaps back so the
     * cleanup finds each copy where it left it. Real data is touched only by that swap when there
     * is no room for the second copy, and only after the user has said so.
     */
    private suspend fun sandboxChecks(
        editor: PresetEditor,
        occupied: List<SlotAddress>,
        free: List<SlotAddress>,
        progress: (String) -> Unit,
    ): List<RegressionResult> {
        val results = mutableListOf<RegressionResult>()
        val source = occupied.first()
        var sandbox = free.first()
        var sandboxName: String? = null

        results += step(COPY, progress) {
            val copyName = editor.copyProgram(source, sandbox)
            refreshEdited(listOf(sandbox))
            check(instrument.browser.refresh(sandbox).name == copyName) {
                "${display(sandbox)} does not hold \"$copyName\" after the copy."
            }
            check(instrument.browser.refresh(source).name != null) {
                "${display(source)} was emptied by a copy, which must leave its source alone."
            }
            sandboxName = copyName
            "Copied ${display(source)} to ${display(sandbox)}; the instrument named it \"$copyName\"."
        }
        val copyName = sandboxName
            ?: return results + listOf(RENAME, MOVE, SWAP, DELETE).map { skip(it, NO_SANDBOX) }

        results += when {
            EditOp.RENAME !in editor.supported -> skip(RENAME, UNSUPPORTED)
            else -> step(RENAME, progress) {
                val newName = testName(editor)
                editor.rename(sandbox, newName)
                refreshEdited(listOf(sandbox))
                check(instrument.browser.refresh(sandbox).name == newName) {
                    "${display(sandbox)} still does not read back as \"$newName\"."
                }
                sandboxName = newName
                "Renamed the copy in ${display(sandbox)} from \"$copyName\" to \"$newName\"."
            }
        }

        val moveTarget = free.getOrNull(1)
        results += when {
            EditOp.MOVE !in editor.supported -> skip(MOVE, UNSUPPORTED)
            moveTarget == null -> skip(MOVE, "only one free slot, so there is nowhere to move the copy")
            else -> step(MOVE, progress) {
                val name = sandboxName
                val from = sandbox
                editor.move(from, moveTarget)
                refreshEdited(listOf(from, moveTarget))
                check(instrument.browser.refresh(moveTarget).name == name) {
                    "${display(moveTarget)} does not hold \"$name\" after the move."
                }
                check(instrument.browser.refresh(from).name == null) {
                    "${display(from)} is still occupied after moving out of it."
                }
                sandbox = moveTarget
                "Moved the copy from ${display(from)} to ${display(moveTarget)}."
            }
        }

        // Every free slot but the one the copy sits in is still empty: free[0] again after a
        // move, free[1] when there was no move. Null when only one slot was free to begin with.
        val partnerSlot = free.firstOrNull { it != sandbox }
        // The second copy, once made - a slot the cleanup has to know about as much as the first.
        var partnerName: String? = null
        results += when {
            EditOp.SWAP !in editor.supported -> skip(SWAP, UNSUPPORTED)
            partnerSlot != null -> step(SWAP, progress) {
                val name = sandboxName
                val copyName = editor.copyProgram(source, partnerSlot)
                refreshEdited(listOf(partnerSlot))
                check(instrument.browser.refresh(partnerSlot).name == copyName) {
                    "${display(partnerSlot)} does not hold \"$copyName\" after the second copy."
                }
                partnerName = copyName
                swapAndBack(editor, sandbox, name, partnerSlot, copyName)
                "Copied ${display(source)} to ${display(partnerSlot)} as \"$copyName\", swapped " +
                    "it with the copy in ${display(sandbox)} and back."
            }
            // No room for a second copy, so the only slot to swap with holds real data: the same
            // question the no-sandbox path asks, narrowed to this one test.
            !onConfirmRealSlotMutation(OccupiedSlotReason.NO_SECOND_FREE_SLOT) -> skip(SWAP, DECLINED)
            else -> step(SWAP, progress) {
                val name = sandboxName
                val sourceName = instrument.browser.refresh(source).name
                swapAndBack(editor, sandbox, name, source, sourceName)
                "Swapped the copy in ${display(sandbox)} with ${display(source)} - real data " +
                    "(only one free slot) - and back."
            }
        }

        val copies = listOfNotNull(
            sandbox to sandboxName,
            partnerSlot?.let { slot -> partnerName?.let { slot to it } },
        )
        results += cleanUp(editor, copies, progress)
        return results
    }

    /**
     * Swaps [a] and [b], verifies both, waits, swaps them back and verifies both again - so a
     * swap is tested in both directions and leaves each preset where it started.
     */
    private suspend fun swapAndBack(
        editor: PresetEditor,
        a: SlotAddress,
        aName: String?,
        b: SlotAddress,
        bName: String?,
    ) {
        editor.swap(a, b)
        refreshEdited(listOf(a, b))
        check(instrument.browser.refresh(b).name == aName) {
            "${display(b)} does not hold \"$aName\" after the swap."
        }
        check(instrument.browser.refresh(a).name == bName) {
            "${display(a)} does not hold \"$bName\" after the swap."
        }
        delay(STEP_PAUSE_MS)
        editor.swap(a, b)
        refreshEdited(listOf(a, b))
        check(instrument.browser.refresh(a).name == aName) {
            "${display(a)} does not hold \"$aName\" again after swapping back."
        }
        check(instrument.browser.refresh(b).name == bName) {
            "${display(b)} does not hold \"$bName\" again after swapping back."
        }
    }

    /**
     * Deletes the run's copies: the last test, and what makes the run leave nothing behind.
     *
     * Checks what is in each slot before erasing it. After a step that failed part-way (a swap
     * that did not swap back) the address held here may hold a real preset, so a slot is erased
     * only if it reads back as one of the run's copies - either name, since an interrupted swap
     * leaves the two copies exchanged - and anything else is left alone and said so.
     */
    private suspend fun cleanUp(
        editor: PresetEditor,
        copies: List<Pair<SlotAddress, String?>>,
        progress: (String) -> Unit,
    ): RegressionResult {
        val slots = copies.map { it.first }
        if (EditOp.DELETE !in editor.supported) {
            return skip(DELETE, "$UNSUPPORTED - the copies are left in ${slots.joinToString { display(it) }}")
        }
        val copyNames = copies.mapNotNull { it.second }.toSet()
        val (ours, theirs) = slots.partition { instrument.browser.refresh(it).name in copyNames }
        val leftAlone = theirs.joinToString { display(it) }
        if (ours.isEmpty()) {
            return skip(
                DELETE,
                "$leftAlone no longer holds a copy, so nothing was deleted - " +
                    "check the instrument by hand before running this again",
            )
        }
        return step(DELETE, progress) {
            ours.forEach { slot ->
                editor.delete(slot)
                refreshEdited(listOf(slot))
                check(instrument.browser.refresh(slot).name == null) {
                    "${display(slot)} is still occupied after deleting the copy."
                }
            }
            val deleted = (if (ours.size == 1) "the copy from " else "the copies from ") +
                ours.joinToString { display(it) }
            if (theirs.isEmpty()) {
                "Deleted $deleted; the instrument is as it was."
            } else {
                "Deleted $deleted. $leftAlone no longer holds a copy and was left alone - " +
                    "check the instrument by hand before running this again."
            }
        }
    }

    /**
     * The path with no sandbox to work in - either nothing is free or this family cannot copy.
     *
     * Each test runs against a real preset and undoes itself immediately, after one question
     * covering all three (the risk is the same one). Delete is not offered here; it has no revert.
     */
    private suspend fun occupiedSlotChecks(
        editor: PresetEditor,
        occupied: List<SlotAddress>,
        free: List<SlotAddress>,
        progress: (String) -> Unit,
    ): List<RegressionResult> {
        val (reason, why) = if (EditOp.COPY in editor.supported) {
            OccupiedSlotReason.NO_FREE_SLOT to "no free slot to copy into"
        } else {
            OccupiedSlotReason.CANNOT_COPY to "this instrument cannot copy a preset"
        }
        val copyResult = skip(COPY, why)
        val deleteResult = skip(DELETE, "$NO_SANDBOX - delete is never run against a stored preset")

        val mutating = listOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP).filter { it in editor.supported }
        if (mutating.isEmpty()) {
            return listOf(copyResult, deleteResult) +
                listOf(RENAME, MOVE, SWAP).map { skip(it, UNSUPPORTED) }
        }
        if (!onConfirmRealSlotMutation(reason)) {
            return listOf(copyResult, deleteResult) +
                listOf(RENAME, MOVE, SWAP).map { skip(it, DECLINED) }
        }

        val target = occupied.first()
        val results = mutableListOf(copyResult, deleteResult)

        results += when {
            EditOp.RENAME !in editor.supported -> skip(RENAME, UNSUPPORTED)
            else -> step(RENAME, progress) {
                val original = instrument.browser.refresh(target).name
                checkNotNull(original) { "${display(target)} holds no preset to rename." }
                val newName = testName(editor)
                editor.rename(target, newName)
                refreshEdited(listOf(target))
                check(instrument.browser.refresh(target).name == newName) {
                    "${display(target)} does not read back as \"$newName\"."
                }
                delay(STEP_PAUSE_MS)
                editor.rename(target, original)
                refreshEdited(listOf(target))
                check(instrument.browser.refresh(target).name == original) {
                    "${display(target)} was NOT renamed back to \"$original\" - fix this by hand."
                }
                "Renamed ${display(target)} - real data ($why) - and back to \"$original\"."
            }
        }

        val moveTarget = free.firstOrNull()
        results += when {
            EditOp.MOVE !in editor.supported -> skip(MOVE, UNSUPPORTED)
            moveTarget == null -> skip(MOVE, "no free slot to move into")
            else -> step(MOVE, progress) {
                val name = instrument.browser.refresh(target).name
                editor.move(target, moveTarget)
                refreshEdited(listOf(target, moveTarget))
                check(instrument.browser.refresh(moveTarget).name == name) {
                    "${display(moveTarget)} does not hold \"$name\" after the move."
                }
                delay(STEP_PAUSE_MS)
                editor.move(moveTarget, target)
                refreshEdited(listOf(target, moveTarget))
                check(instrument.browser.refresh(target).name == name) {
                    "\"$name\" was NOT moved back to ${display(target)} - fix this by hand."
                }
                "Moved ${display(target)} - real data ($why) - to ${display(moveTarget)} and back."
            }
        }

        val partner = occupied.getOrNull(1)
        results += when {
            EditOp.SWAP !in editor.supported -> skip(SWAP, UNSUPPORTED)
            partner == null -> skip(SWAP, "only one preset is stored, so there is nothing to swap with")
            else -> step(SWAP, progress) {
                val targetName = instrument.browser.refresh(target).name
                val partnerName = instrument.browser.refresh(partner).name
                editor.swap(target, partner)
                refreshEdited(listOf(target, partner))
                check(instrument.browser.refresh(target).name == partnerName) {
                    "${display(target)} does not hold \"$partnerName\" after the swap."
                }
                delay(STEP_PAUSE_MS)
                editor.swap(target, partner)
                refreshEdited(listOf(target, partner))
                check(instrument.browser.refresh(target).name == targetName) {
                    "${display(target)} was NOT swapped back to \"$targetName\" - fix this by hand."
                }
                "Swapped ${display(target)} with ${display(partner)} - real data ($why) - and back."
            }
        }
        return results
    }

    // ---- Plumbing ----

    /**
     * Runs one test, recording whatever went wrong rather than ending the run: a failing test is
     * a finding, and the tests after it still say something. Not `runCatching`, which would
     * swallow [CancellationException].
     *
     * Pauses after every step, so the instrument's display does not flip presets and names in a
     * burst too fast for whoever is standing at it.
     */
    private suspend fun step(
        name: String,
        progress: (String) -> Unit,
        block: suspend () -> String,
    ): RegressionResult {
        progress(name)
        val result = try {
            RegressionResult(name, Status.PASS, block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException.BlockedByDeviceState) {
            // The one place a remedy is applied without asking: this is a debug run the user
            // launched, which already writes presets, and a suite reported as FAIL because the
            // instrument was in Performance mode tells nobody anything. The step's detail says
            // what was changed.
            applyRemedyAndRetry(name, e, block)
        } catch (e: Exception) {
            RegressionResult(name, Status.FAIL, e.message ?: e.toString())
        }
        delay(STEP_PAUSE_MS)
        return result
    }

    /**
     * Applies a [InstrumentException.BlockedByDeviceState]'s remedy and runs the step again, once.
     * A second failure is reported with the remedy named.
     */
    private suspend fun applyRemedyAndRetry(
        name: String,
        blocked: InstrumentException.BlockedByDeviceState,
        block: suspend () -> String,
    ): RegressionResult {
        val fix = blocked.remedy
            ?: return RegressionResult(name, Status.FAIL, blocked.message ?: blocked.toString())
        val what = blocked.remedyLabel ?: "the instrument's state"
        return try {
            fix()
            remediesApplied += what
            RegressionResult(name, Status.PASS, "${block()}  [$what, to get past: ${blocked.message}]")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RegressionResult(name, Status.FAIL, "$what did not clear it: ${e.message ?: e}")
        }
    }

    /** Anything this run changed about the instrument's state, for the summary to disclose. */
    private val remediesApplied = linkedSetOf<String>()

    private fun skip(name: String, reason: String) = RegressionResult(name, Status.SKIPPED, reason)

    /** Occupied addresses in device order, which [occupiedSlots] alone does not carry. */
    private fun occupiedInOrder(): List<SlotAddress> {
        val occupied = occupiedSlots()
        return allSlots().filter { it in occupied }
    }

    private fun display(address: SlotAddress): String = instrument.layout.format.format(address)

    /** Capped to what the instrument will actually store, so a rename is not verified against a
     * name the instrument silently truncated (see [PresetEditor.maxNameLength]). */
    private fun testName(editor: PresetEditor): String =
        editor.maxNameLength?.let { TEST_NAME.take(it) } ?: TEST_NAME

    private fun summarize(results: List<RegressionResult>): String {
        val passed = results.count { it.status == Status.PASS }
        val failed = results.count { it.status == Status.FAIL }
        val skipped = results.count { it.status == Status.SKIPPED }
        val summary = "${instrument.identity.name}: $passed passed, $failed failed, $skipped skipped"
        // In the headline: the run left the instrument in a state the user did not put it in.
        return if (remediesApplied.isEmpty()) summary
        else "$summary. Changed on the instrument to run: ${remediesApplied.joinToString(", ")}"
    }

    private companion object {
        const val SELECT = "Load a preset"
        const val READ_DATA = "Read a preset's data"
        const val BUILD_REPORT = "Build a device report"
        const val COPY = "Copy a preset"
        const val RENAME = "Rename a preset"
        const val MOVE = "Move a preset"
        const val SWAP = "Swap two presets"
        const val DELETE = "Delete a preset"

        const val UNSUPPORTED = "not supported by this instrument"
        const val NOTHING_STORED = "the instrument holds no presets to test against"
        const val NO_SANDBOX = "no sandbox copy to test against"
        const val DECLINED = "declined by the user"

        /** Between steps, so the instrument's display does not flip presets in a rapid, silent burst. */
        const val STEP_PAUSE_MS = 500L

        const val TEST_NAME = "PatchPilot Test"
    }
}
