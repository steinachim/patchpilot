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
 * The index fold, kept pure so the collector can run in the ViewModel and so its three rules can
 * be asserted here rather than only by hand.
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

    // ---- replacing ----

    /**
     * A re-read row goes back **where it was**, not on the end.
     *
     * A rename re-reads the slot it changed; appending it would move the renamed voice to the
     * bottom of its bank, which looks exactly like a move nobody asked for.
     */
    @Test
    fun `replacing puts the new slot in the old one's position`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 0), slot(0, 1), slot(0, 2))))

        val renamed = slot(0, 1).copy(name = "Renamed In Place")
        val after = state.replacing(renamed)

        assertEquals(3, after.slots.size)
        assertEquals("Renamed In Place", after.slots[1].name)
        assertEquals(listOf(0, 1, 2), after.slots.map { it.address.slot })
    }

    /**
     * A listing that does not hold the address is left alone.
     *
     * Every retained listing is offered the re-read row, because the same address can be in more
     * than one - a favorited user voice is in both the user listing and the favorites one. The
     * ones that do not hold it must not gain a row: a listing is what a scan found, and inserting
     * into it would be inventing an entry. That is exactly the favorites case, where a voice that
     * is not marked genuinely does not belong.
     */
    @Test
    fun `replacing an address the listing does not hold changes nothing`() {
        val state = PresetIndexState().plus(IndexUpdate.Slots(listOf(slot(0, 0))))

        val after = state.replacing(slot(1, 5).copy(name = "Somewhere Else"))

        assertEquals(state, after)
        assertEquals(1, after.slots.size)
    }

    @Test
    fun `replacing an empty listing changes nothing`() {
        assertEquals(PresetIndexState(), PresetIndexState().replacing(slot(0, 0)))
    }
}
