package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index fold, which used to live inside a `produceState` in ProgramsScreen.
 *
 * Testable at all only because it moved: the collector now runs in the ViewModel so a
 * configuration change does not restart a 93-second listing, and pulling the fold out to get
 * there turned three rules that were previously only assertable by hand into unit tests.
 */
class PresetIndexStateTest {

    private fun slot(bank: Int, index: Int) = PresetSlot(
        address = SlotAddress(bank, index),
        displayId = "USER 1 - A:0$index",
        bankLabel = "USER 1",
        name = "Voice $index",
    )

    @Test
    fun `slots accumulate in arrival order`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 1), slot(0, 2))))
            .plus(IndexUpdate.Slots(listOf(slot(0, 3))))
        assertEquals(listOf("Voice 1", "Voice 2", "Voice 3"), state.slots.map { it.name })
        assertTrue("still loading until Complete", state.loading)
    }

    /**
     * A single unreadable address must not lose the rest of the index.
     *
     * The rule the whole streaming design rests on - one bad slot out of 400 is a finding, not a
     * failure - and the live Motif XS scan hits it regularly ("2 slots could not be read").
     */
    @Test
    fun `a failed address is recorded without becoming an error`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 1))))
            .plus(IndexUpdate.Failed(SlotAddress(0, 9), "timed out"))
            .plus(IndexUpdate.Complete)

        assertEquals(1, state.failures.size)
        assertEquals(1, state.slots.size)
        assertNull("a failed slot is not a failed scan", state.error)
        assertTrue(state.complete)
        assertFalse(state.loading)
    }

    /** Progress is transient: it is for the bar, and Complete clears it. */
    @Test
    fun `completing clears the progress line`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Progress(done = 3, total = 128, label = "Reading A03"))
        assertEquals(3, state.progress?.done)

        assertNull(state.plus(IndexUpdate.Complete).progress)
    }

    /** A fresh run keeps its generation so a caller can tell its own refresh from any other. */
    @Test
    fun `generation survives the fold`() {
        val state = PresetIndexState(generation = 7).plus(IndexUpdate.Complete)
        assertEquals(7, state.generation)
    }
}
