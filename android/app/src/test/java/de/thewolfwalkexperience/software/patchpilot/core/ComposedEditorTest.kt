// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.causedBy
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ComposedEditor]'s own rules, on an in-memory instrument rather than through a family: the
 * Pro-800, the Axe-Fx III and the Subsequent 37 inherit every one of them, so they are pinned once
 * here.
 */
class ComposedEditorTest {

    /** Slots as names, null for empty; a write fails where [failWrite] says so, after it has landed or before. */
    private class MemoryEditor(vararg names: String?) : ComposedEditor<String>() {
        val slots = names.toMutableList()
        val writes = mutableListOf<Int>()
        var failWrite: (slot: Int, attempt: Int) -> Exception? = { _, _ -> null }

        /** Holds every write until completed, so a test can cancel one in flight. */
        var holdWrites: CompletableDeferred<Unit>? = null

        /** Whether a failing write still stores its value first, as a write whose read-back fails does. */
        var failAfterStoring = false

        override fun displayIdOf(address: SlotAddress) = "#${address.slot}"
        override suspend fun peek(address: SlotAddress) = slots[address.slot] ?: EMPTY
        override fun isEmpty(preset: String) = preset == EMPTY
        override fun isNamed(preset: String, name: String) = preset == name
        override fun shownName(preset: String) = preset

        override suspend fun writeVerified(address: SlotAddress, preset: String) {
            holdWrites?.await()
            val slot = address.slot
            writes += slot
            val failure = failWrite(slot, writes.count { it == slot })
            if (failure != null && !failAfterStoring) throw failure
            slots[slot] = preset.takeUnless { it == EMPTY }
            if (failure != null) throw failure
        }

        override suspend fun erase(address: SlotAddress) = writeVerified(address, EMPTY)

        override suspend fun storeName(address: SlotAddress, preset: String, name: String) =
            writeVerified(address, name)
    }

    private fun at(slot: Int) = SlotAddress(0, slot)

    @Test
    fun `a rename to the name already stored writes nothing`() = runTest {
        val editor = MemoryEditor("Pad")
        editor.rename(at(0), "Pad")
        assertEquals(emptyList<Int>(), editor.writes)
    }

    @Test
    fun `an edit on a slot not as the list showed it is refused before anything is written`() = runTest {
        val editor = MemoryEditor("Pad", "Lead", null)
        thrownBy<InstrumentException.SlotStateChanged> { editor.copyProgram(at(0), at(1)) }
        thrownBy<InstrumentException.SlotStateChanged> { editor.move(at(2), at(1)) }
        thrownBy<InstrumentException.SlotStateChanged> { editor.rename(at(2), "New") }
        assertEquals(emptyList<Int>(), editor.writes)
    }

    /** The destination is written first, so the preset is in both slots rather than in neither. */
    @Test
    fun `a move whose erase fails is partly applied, and keeps why`() = runTest {
        val refusal = InstrumentException.DeviceRejected("store a preset", status = 3)
        val editor = MemoryEditor("Pad", null).apply { failWrite = { slot, _ -> refusal.takeIf { slot == 0 } } }

        val failure = thrownBy<InstrumentException.PartlyApplied> { editor.move(at(0), at(1)) }

        assertTrue("why the erase failed is kept", failure.causedBy { it === refusal })
        assertTrue(failure.message!!, failure.message!!.contains("in both slots"))
        assertEquals(listOf("Pad", "Pad"), editor.slots)
    }

    @Test
    fun `a swap whose second write fails puts the first slot back`() = runTest {
        val editor = MemoryEditor("Pad", "Lead").apply {
            failWrite = { slot, _ -> InstrumentException.Timeout("storing").takeIf { slot == 0 } }
        }
        thrownBy<InstrumentException.Timeout> { editor.swap(at(0), at(1)) }
        assertEquals(listOf("Pad", "Lead"), editor.slots)
    }

    /** A write can land and still fail its read-back; the slot it overwrote is restored. */
    @Test
    fun `a swap whose first write lands but fails its read-back puts that slot back`() = runTest {
        val editor = MemoryEditor("Pad", "Lead").apply {
            failAfterStoring = true
            failWrite =
                { slot, attempt -> InstrumentException.Timeout("confirming").takeIf { slot == 1 && attempt == 1 } }
        }
        thrownBy<InstrumentException.Timeout> { editor.swap(at(0), at(1)) }
        assertEquals(listOf("Pad", "Lead"), editor.slots)
    }

    @Test
    fun `swapping two empty slots and deleting an empty one write nothing`() = runTest {
        val editor = MemoryEditor(null, null)
        editor.swap(at(0), at(1))
        editor.delete(at(0))
        assertEquals(emptyList<Int>(), editor.writes)
    }

    /** Cancelled between its two writes, a move finishes rather than leaving the preset in both slots. */
    @Test
    fun `a cancelled move finishes`() = runTest {
        val hold = CompletableDeferred<Unit>()
        val editor = MemoryEditor("Pad", null).apply { holdWrites = hold }
        val move = launch { editor.move(at(0), at(1)) }
        runCurrent()

        move.cancel()
        hold.complete(Unit)
        move.join()

        assertTrue(move.isCancelled)
        assertNull(editor.slots[0])
        assertEquals("Pad", editor.slots[1])
    }

    private companion object {
        const val EMPTY = "<empty>"
    }
}
