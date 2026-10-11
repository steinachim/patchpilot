// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

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

    // ---- withSlot ----

    /**
     * A re-read row goes back **where it was**, not on the end.
     *
     * A rename re-reads the slot it changed; appending it would move the renamed voice to the
     * bottom of its bank, which looks exactly like a move nobody asked for.
     */
    @Test
    fun `withSlot puts the new slot in the old one's position`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 0), slot(0, 1), slot(0, 2))))

        val renamed = slot(0, 1).copy(name = "Renamed In Place")
        val after = state.withSlot(renamed, insert = true)

        assertEquals(3, after.slots.size)
        assertEquals("Renamed In Place", after.slots[1].name)
        assertEquals(listOf(0, 1, 2), after.slots.map { it.address.slot })
    }

    /**
     * A listing that is not an address space does not gain a row.
     *
     * Every retained listing is offered the re-read row, because the same address can be in more
     * than one - a favorited user voice is in both the user listing and the favorites one. A
     * favorites listing must not gain a voice that is not marked.
     */
    @Test
    fun `a listing that may not grow is left alone by an address it does not hold`() {
        val state = PresetIndexState().plus(IndexUpdate.Slots(listOf(slot(0, 0)))).plus(IndexUpdate.Complete)

        val after = state.withSlot(slot(1, 5).copy(name = "Somewhere Else"), insert = false)

        assertEquals(state, after)
    }

    /**
     * A copy into an empty Nord slot writes an address the listing never had, since a Nord lists
     * only what it holds. The row lands in device order, so it shows under its own bank header.
     */
    @Test
    fun `the user listing gains an absent address in device order`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 0), slot(0, 2), slot(1, 0))))
            .plus(IndexUpdate.Complete)

        val after = state.withSlot(slot(0, 1).copy(name = "The Copy"), insert = true)

        assertEquals(
            listOf(SlotAddress(0, 0), SlotAddress(0, 1), SlotAddress(0, 2), SlotAddress(1, 0)),
            after.slots.map { it.address },
        )
    }

    /** A scan still arriving lists the address itself; inserting it now would list it twice. */
    @Test
    fun `a listing still arriving does not gain a row`() {
        val state = PresetIndexState().plus(IndexUpdate.Slots(listOf(slot(0, 0))))

        assertEquals(state, state.withSlot(slot(0, 1), insert = true))
    }

    @Test
    fun `an absent address that reads empty adds nothing`() {
        val state = PresetIndexState().plus(IndexUpdate.Slots(listOf(slot(0, 0)))).plus(IndexUpdate.Complete)

        assertEquals(state, state.withSlot(slot(0, 1).copy(name = null), insert = true))
    }

    /** A slot the listing could not read is offered as neither free nor occupied until it is re-read. */
    @Test
    fun `a re-read address no longer counts as unreadable`() {
        val state = PresetIndexState()
            .plus(IndexUpdate.Slots(listOf(slot(0, 0))))
            .plus(IndexUpdate.Failed(SlotAddress(0, 1), "timed out"))
            .plus(IndexUpdate.Complete)
        assertEquals(setOf(SlotAddress(0, 1)), state.unreadable)

        val after = state.withSlot(slot(0, 1).copy(name = null), insert = true)

        assertTrue(after.unreadable.isEmpty())
    }
}
