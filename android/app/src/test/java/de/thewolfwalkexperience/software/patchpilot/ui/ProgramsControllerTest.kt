// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preset screen's operation rules.
 *
 * Every one of these is a rule the screen's own comments state as deliberate - a delete dialog
 * dismissed before the work rather than after it, a copy picker that closes only on success, a
 * blocked operation becoming answerable only when the family offers both a remedy and a retry.
 * They were unreachable without standing up a whole composition, so none of them was covered;
 * [ProgramsController] taking [ProgramsOperations] and [StringResolver] rather than a ViewModel
 * and a Context is what makes them ordinary functions to call.
 */
class ProgramsControllerTest {

    private fun slot(bank: Int, index: Int, name: String? = "Warm Brass") = PresetSlot(
        address = SlotAddress(bank, index),
        displayId = "A$index",
        bankLabel = "A",
        name = name,
    )

    /**
     * Records what was asked of the instrument, and answers however a test needs.
     *
     * [launchEdit] runs on the test's own scope, so an edit completes within `runTest` rather than
     * outliving it - the production implementation deliberately outlives the *screen*, which is a
     * different lifetime and not one this test is about.
     */
    private class FakeOperations(
        private val testScope: CoroutineScope,
        initialScope: PresetScope = PresetScope.USER,
    ) : ProgramsOperations {
        val calls = mutableListOf<String>()
        var reloads = 0
        var tagsToReturn = PresetTags(categories = emptyList())
        var failWith: Throwable? = null

        private val _scope = MutableStateFlow(initialScope)
        override val scope: StateFlow<PresetScope> = _scope

        override fun setScope(scope: PresetScope) {
            calls += "setScope($scope)"
            _scope.value = scope
        }

        override fun launchEdit(block: suspend () -> Unit): Job = testScope.launch { block() }

        override fun reloadIndex(): Int? {
            reloads++
            return null
        }

        private fun <T> answer(call: String, value: T): T {
            calls += call
            failWith?.let { throw it }
            return value
        }

        override suspend fun selectProgram(slot: PresetSlot) = answer("select(${slot.displayId})", "Selected")
        override suspend fun renameProgram(slot: PresetSlot, newName: String) =
            answer("rename(${slot.displayId},$newName)", "Renamed")
        override suspend fun deleteProgram(slot: PresetSlot) = answer("delete(${slot.displayId})", "Deleted")
        override suspend fun copyProgram(source: PresetSlot, destination: PresetSlot) =
            answer("copy(${source.displayId}->${destination.displayId})", "Copied")
        override suspend fun moveProgram(source: PresetSlot, target: PresetSlot, targetIsEmpty: Boolean) =
            answer("move(${source.displayId}->${target.displayId},empty=$targetIsEmpty)", "Moved")
        override suspend fun presetTags(slot: PresetSlot) = answer("tags(${slot.displayId})", tagsToReturn)
        override suspend fun setFavorite(slot: PresetSlot, under: Set<Int>) =
            answer("favorite(${slot.displayId},$under)", "Favorited")
        override suspend fun setCategories(slot: PresetSlot, categories: List<CategoryRef?>) =
            answer("categories(${slot.displayId})", "Categorised")
    }

    /** Resource ids are opaque here; the label only has to be stable and distinguishable. */
    private fun TestScope.controller(
        ops: FakeOperations = FakeOperations(this),
    ): Pair<ProgramsController, FakeOperations> =
        ProgramsController(ops) { resId, args -> "res$resId(${args.joinToString(",")})" } to ops

    // ---- The busy line ----

    @Test
    fun `an edit clears its busy line when it finishes`() = runTest {
        val (controller, ops) = controller()
        controller.onDeleteConfirmed(slot(0, 1))
        testScheduler.advanceUntilIdle()

        assertNull("the bar must not be left running after the edit returns", controller.busy)
        assertEquals(listOf("delete(A1)"), ops.calls)
    }

    @Test
    fun `a failed edit clears its busy line too, and reports`() = runTest {
        val (controller, ops) = controller()
        ops.failWith = InstrumentException.Timeout("deleting")
        controller.onDeleteConfirmed(slot(0, 1))
        testScheduler.advanceUntilIdle()

        assertNull("a failure that left the bar running would claim work still in progress", controller.busy)
        assertNotNull("the failure has to reach the screen", controller.operationError)
    }

    // ---- Dialog dismissal, which the screen's comments call out as deliberate ----

    @Test
    fun `delete dismisses its dialog before the work starts, not after`() = runTest {
        val (controller, _) = controller()
        controller.deleteTarget = slot(0, 1)
        controller.onDeleteConfirmed(slot(0, 1))

        // Before advancing: the edit has not run yet, and the dialog is already gone.
        assertNull("a dialog left standing over an edit hides the progress line", controller.deleteTarget)
    }

    @Test
    fun `rename dismisses its dialog before the work starts`() = runTest {
        val (controller, _) = controller()
        controller.renameTarget = slot(0, 1)
        controller.onRenameConfirmed(slot(0, 1), "New Name")

        assertNull(controller.renameTarget)
    }

    // ---- Reloading, or deliberately not ----

    @Test
    fun `selecting a preset does not re-read the listing`() = runTest {
        val (controller, ops) = controller()
        controller.onProgramTapped(slot(0, 3))
        testScheduler.advanceUntilIdle()

        assertEquals("selection stores nothing, so re-listing would be a round trip per tap", 0, ops.reloads)
    }

    @Test
    fun `an edit that stores something re-reads the listing`() = runTest {
        val (controller, ops) = controller()
        controller.onRenameConfirmed(slot(0, 1), "New Name")
        testScheduler.advanceUntilIdle()

        assertEquals(1, ops.reloads)
    }

    // ---- Blocked-by-device-state, which is answerable rather than merely reportable ----

    @Test
    fun `a blocked operation offering a remedy becomes a question, not an error`() = runTest {
        val (controller, ops) = controller()
        ops.failWith = InstrumentException.BlockedByDeviceState(
            message = "The instrument is in Performance mode.",
            remedy = { },
            remedyLabel = "Switch to Voice mode",
            remedyDetail = "One message fixes it.",
        )
        controller.onProgramTapped(slot(0, 3))
        testScheduler.advanceUntilIdle()

        assertNotNull("a remedy plus a retry is answerable, so it must open the dialog", controller.blocked)
        assertNull("and must not also be reported as a plain failure", controller.operationError)
    }

    @Test
    fun `a blocked operation with no retry falls back to a plain error`() = runTest {
        val (controller, ops) = controller()
        ops.failWith = InstrumentException.BlockedByDeviceState(
            message = "The instrument is in Performance mode.",
            remedy = { },
            remedyLabel = "Switch to Voice mode",
            remedyDetail = null,
        )
        // onDeleteConfirmed passes no `retry`, so there is nothing to offer.
        controller.onDeleteConfirmed(slot(0, 1))
        testScheduler.advanceUntilIdle()

        assertNull("with nothing to retry, a dialog would be a dead end", controller.blocked)
        assertNotNull(controller.operationError)
    }

    // ---- Copy destination picking ----

    @Test
    fun `picking a copy destination from a factory listing moves the browser to the user banks`() = runTest {
        val ops = FakeOperations(this, initialScope = PresetScope.FACTORY)
        val (controller, _) = controller(ops)

        controller.beginPicking(slot(0, 1))
        assertTrue(controller.picking)
        assertEquals("a copy's destination is always a user slot", PresetScope.USER, ops.scope.value)

        // Cancelling has made nothing, so it goes back where it came from.
        controller.endPicking()
        assertFalse(controller.picking)
        assertEquals(PresetScope.FACTORY, ops.scope.value)
    }

    @Test
    fun `a finished copy stays in the user banks rather than bouncing back`() = runTest {
        val ops = FakeOperations(this, initialScope = PresetScope.FACTORY)
        val (controller, _) = controller(ops)

        controller.beginPicking(slot(0, 1))
        controller.onPickConfirmed(slot(0, 1), slot(1, 7), destinationIsEmpty = true)
        testScheduler.advanceUntilIdle()

        assertFalse(controller.picking)
        assertEquals(
            "the new voice is in the user banks, and that is what the user just made",
            PresetScope.USER,
            ops.scope.value,
        )
    }

    @Test
    fun `a failed copy leaves the picker open`() = runTest {
        val ops = FakeOperations(this)
        val (controller, _) = controller(ops)
        controller.beginPicking(slot(0, 1))
        ops.failWith = InstrumentException.Timeout("copying")

        controller.onPickConfirmed(slot(0, 1), slot(1, 7), destinationIsEmpty = true)
        testScheduler.advanceUntilIdle()

        assertTrue("show what went wrong against the copy that was about to be made", controller.picking)
    }

    // ---- "Move to…", the reachable equivalent of the drag gesture ----

    @Test
    fun `a move pick onto an empty slot moves, and onto an occupied one swaps`() = runTest {
        val (controller, ops) = controller()

        controller.beginPicking(slot(0, 1), PickIntent.MOVE)
        controller.onPickConfirmed(slot(0, 1), slot(0, 2), destinationIsEmpty = true)
        testScheduler.advanceUntilIdle()

        controller.beginPicking(slot(0, 1), PickIntent.MOVE)
        controller.onPickConfirmed(slot(0, 1), slot(0, 3), destinationIsEmpty = false)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("move(A1->A2,empty=true)", "move(A1->A3,empty=false)"),
            ops.calls,
        )
    }

    /**
     * The whole point of the menu path: it has to reach the same operation the gesture does, not a
     * lesser one. A move started from the menu and a move started from a drop both end in
     * `moveProgram` with the same empty/occupied decision.
     */
    @Test
    fun `a menu move and a dropped move issue the same call`() = runTest {
        val (viaMenu, menuOps) = controller()
        viaMenu.beginPicking(slot(0, 1), PickIntent.MOVE)
        viaMenu.onPickConfirmed(slot(0, 1), slot(0, 3), destinationIsEmpty = false)
        testScheduler.advanceUntilIdle()

        val (viaDrag, dragOps) = controller()
        viaDrag.onSwapDropped(slot(0, 1), slot(0, 3), setOf(SlotAddress(0, 1), SlotAddress(0, 3)))
        testScheduler.advanceUntilIdle()

        assertEquals(menuOps.calls, dragOps.calls)
    }

    @Test
    fun `a pick remembers which operation it was started for`() = runTest {
        val (controller, ops) = controller()

        controller.beginPicking(slot(0, 1), PickIntent.MOVE)
        assertEquals(PickIntent.MOVE, controller.pickIntent)
        controller.endPicking()

        controller.beginPicking(slot(0, 1), PickIntent.COPY)
        controller.onPickConfirmed(slot(0, 1), slot(1, 7), destinationIsEmpty = true)
        testScheduler.advanceUntilIdle()

        assertEquals("a copy must not become a move", listOf("copy(A1->A7)"), ops.calls)
    }

    // ---- Drag-to-reorder picks move or swap from what is occupied ----

    @Test
    fun `dropping onto an empty row moves, and onto an occupied one swaps`() = runTest {
        val (controller, ops) = controller()
        val source = slot(0, 1)
        val emptyTarget = slot(0, 2)
        val fullTarget = slot(0, 3)
        val occupied = setOf(source.address, fullTarget.address)

        controller.onSwapDropped(source, emptyTarget, occupied)
        testScheduler.advanceUntilIdle()
        controller.onSwapDropped(source, fullTarget, occupied)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("move(A1->A2,empty=true)", "move(A1->A3,empty=false)"),
            ops.calls,
        )
    }

    @Test
    fun `dropping a row onto itself, or dragging an empty one, does nothing`() = runTest {
        val (controller, ops) = controller()
        val source = slot(0, 1)
        val empty = slot(0, 9, name = null)

        controller.onSwapDropped(source, source, setOf(source.address))
        controller.onSwapDropped(empty, source, setOf(source.address))
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<String>(), ops.calls)
    }

    // ---- Tags are read before the dialog opens ----

    @Test
    fun `opening a tag dialog reads the tags first, so it never appears empty`() = runTest {
        val (controller, ops) = controller()
        controller.openTagDialog(slot(0, 1), forFavorite = true)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("tags(A1)"), ops.calls)
        assertNotNull("the dialog opens on tags already read", controller.favoriteTarget)
        assertNull(controller.categoriesTarget)
        assertEquals(
            "a read that only opened a dialog has nothing to announce",
            null,
            controller.statusMessage,
        )
    }

    @Test
    fun `a failed tag read opens no dialog at all`() = runTest {
        val (controller, ops) = controller()
        ops.failWith = InstrumentException.Timeout("reading tags")

        controller.openTagDialog(slot(0, 1), forFavorite = false)
        testScheduler.advanceUntilIdle()

        assertNull(controller.categoriesTarget)
        assertNotNull(controller.operationError)
    }
}
