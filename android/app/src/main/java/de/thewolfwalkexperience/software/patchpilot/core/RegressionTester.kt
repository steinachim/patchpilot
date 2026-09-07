package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class Status { PASS, FAIL, SKIPPED }

/** Why [RegressionTester] fell back to testing rename/move/swap against a real preset. */
enum class OccupiedSlotReason { NO_FREE_SLOT, CANNOT_COPY }

/** One test's outcome. [detail] says what actually happened, in the report's own words. */
data class RegressionResult(val name: String, val status: Status, val detail: String)

data class RegressionReport(val results: List<RegressionResult>, val summary: String) {

    /**
     * The report as shareable text.
     *
     * Plain text rather than the JSON a [DeviceReporter] produces, because the two have different
     * readers: a device report is pasted into `devices/nord_devices.json` by whoever adds a
     * catalog entry, while this is read by the person who just ran it - on the phone, and then in
     * whatever they send it to.
     */
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
 * **In `core/` because it tests the facets rather than a family.** Nothing here knows what is
 * plugged in: an operation the instrument does not declare is skipped and said to be skipped,
 * which is the same rule the screens follow, and is what makes one engine cover all
 * three families.
 *
 * **Non-destructive by construction, not by care.** Every mutating test runs against a copy the
 * test made ([EditOp.COPY] into a free slot) and deletes again, so no slot holding real data is
 * ever written. Where that sandbox cannot exist - no free slot, or a family without copy - the
 * mutating tests are only offered against real data after [onConfirmRealSlotMutation] says the
 * user has been told and agreed, and each one reverts itself immediately. Delete is never run
 * against real data at all, whatever the answer.
 *
 * The two `suspend (...) -> Boolean` callbacks are how this asks the user a question mid-run
 * without knowing that a UI exists, let alone that it is Compose.
 *
 * One run per instance.
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

    /**
     * What this instrument declares before anything is tried, so the report describes the
     * instrument as well as the run - and so a later `SKIPPED` has something to point back at.
     */
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
                // The protocol layer verifies whatever it can - a Nord checks the address the
                // instrument echoes back - but no family reports what is actually on its display,
                // so the only witness to a preset having really loaded is the person holding it.
                // That is exactly as true of the decoy as of the target: a decoy select nobody
                // confirms is not a baseline, it is an assumption - if it silently failed, the
                // display could still be showing whatever it was showing before, target included.
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
                val json = report.buildReport { step -> progress("$BUILD_REPORT: $step") }
                check(json.isNotBlank()) { "The device report came back empty." }
                "Built ${json.length} characters of JSON."
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
     * Only the copy and the free slots it is moved through are ever written. The one moment a slot
     * holding real data is touched is the swap, which is why the swap is immediately swapped back
     * and verified in the same test.
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

        results += when {
            EditOp.SWAP !in editor.supported -> skip(SWAP, UNSUPPORTED)
            else -> step(SWAP, progress) {
                val name = sandboxName
                val partnerName = instrument.browser.refresh(source).name
                editor.swap(sandbox, source)
                refreshEdited(listOf(sandbox, source))
                check(instrument.browser.refresh(source).name == name) {
                    "${display(source)} does not hold \"$name\" after the swap."
                }
                check(instrument.browser.refresh(sandbox).name == partnerName) {
                    "${display(sandbox)} does not hold \"$partnerName\" after the swap."
                }
                delay(STEP_PAUSE_MS)
                editor.swap(sandbox, source)
                refreshEdited(listOf(sandbox, source))
                check(instrument.browser.refresh(sandbox).name == name) {
                    "${display(sandbox)} does not hold \"$name\" again after swapping back."
                }
                check(instrument.browser.refresh(source).name == partnerName) {
                    "${display(source)} does not hold \"$partnerName\" again after swapping back."
                }
                "Swapped the copy in ${display(sandbox)} with ${display(source)} and back."
            }
        }

        results += cleanUp(editor, sandbox, sandboxName, progress)
        return results
    }

    /**
     * Deletes the sandbox copy, which is both the last test and the thing that makes the run
     * leave nothing behind.
     *
     * **Checks what is in the slot before erasing it.** If an earlier step failed part-way - a
     * swap that did not swap back, say - the address held here is no longer the copy's, and
     * deleting it would destroy exactly the real preset this whole path exists to protect. So a
     * slot that does not read back as the copy is left alone and said so.
     */
    private suspend fun cleanUp(
        editor: PresetEditor,
        sandbox: SlotAddress,
        sandboxName: String?,
        progress: (String) -> Unit,
    ): RegressionResult {
        if (EditOp.DELETE !in editor.supported) {
            return skip(DELETE, "$UNSUPPORTED - the copy is left in ${display(sandbox)}")
        }
        if (instrument.browser.refresh(sandbox).name != sandboxName) {
            return skip(
                DELETE,
                "${display(sandbox)} no longer holds the copy, so nothing was deleted - " +
                    "check the instrument by hand before running this again",
            )
        }
        return step(DELETE, progress) {
            editor.delete(sandbox)
            refreshEdited(listOf(sandbox))
            check(instrument.browser.refresh(sandbox).name == null) {
                "${display(sandbox)} is still occupied after deleting the copy."
            }
            "Deleted the copy from ${display(sandbox)}; the instrument is as it was."
        }
    }

    /**
     * The path with no sandbox to work in - either nothing is free or this family cannot copy.
     *
     * Each test runs against a real preset and undoes itself immediately, so it needs the user to
     * have agreed first: **one question covering all three**, not three prompts, since the risk is
     * the same one and answering it three times is how a warning stops being read. Delete is not
     * offered at all here - there is no revert for it.
     */
    private suspend fun occupiedSlotChecks(
        editor: PresetEditor,
        occupied: List<SlotAddress>,
        free: List<SlotAddress>,
        progress: (String) -> Unit,
    ): List<RegressionResult> {
        val reason = if (EditOp.COPY in editor.supported) {
            OccupiedSlotReason.NO_FREE_SLOT
        } else {
            OccupiedSlotReason.CANNOT_COPY
        }
        val why = when (reason) {
            OccupiedSlotReason.NO_FREE_SLOT -> "no free slot to copy into"
            OccupiedSlotReason.CANNOT_COPY -> "this instrument cannot copy a preset"
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
     * Runs one test, recording whatever went wrong rather than ending the run.
     *
     * A failing test is a *finding*, and the tests after it usually still say something worth
     * knowing - which is the same rule `NordInstrument.buildReport`'s probes follow, for the same
     * reason. Not `runCatching`, which swallows [CancellationException] and would leave a run the
     * user backed out of still writing to the instrument.
     *
     * **Pauses after every step**, success or failure. Back to back, these steps make the
     * instrument's own display flip presets and names in a fast, uncommented burst - the pause is
     * for whoever is standing at the instrument, not for the protocol.
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
            // **Take the offered fix, here and only here.** Everywhere else in the app a remedy
            // is put to the user first, because changing the instrument's mode changes what a
            // player is hearing. This is a debug run they launched deliberately, which already
            // has permission to write presets and revert them - refusing it over a mode switch
            // would be straining at a gnat, and a whole suite reported as FAIL because the
            // instrument was in Performance mode tells nobody anything.
            //
            // It still **says** what it did, in the step's own detail, so the report never
            // implies the instrument came back the way it was found.
            applyRemedyAndRetry(name, e, block)
        } catch (e: Exception) {
            RegressionResult(name, Status.FAIL, e.message ?: e.toString())
        }
        delay(STEP_PAUSE_MS)
        return result
    }

    /**
     * Applies a [InstrumentException.BlockedByDeviceState]'s remedy and runs the step again.
     *
     * One attempt only. If the operation is still blocked after its own stated fix, that is a
     * finding rather than something to keep hammering at - and the second failure is reported
     * with the remedy named, so the report says "we tried this and it did not help".
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
        // Disclosed in the headline, not buried in a step: the run left the instrument in a state
        // the user did not put it in, and they may be about to walk back to it and wonder.
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
