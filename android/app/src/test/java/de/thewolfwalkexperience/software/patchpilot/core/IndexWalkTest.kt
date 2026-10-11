// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk most families list with: a failed slot is recorded and skipped, but an instrument that
 * has gone silent, or a link that is gone, ends it rather than costing a timeout per slot.
 */
class IndexWalkTest {

    private val layout = SlotLayout.uniform(
        bankCount = 1,
        slotsPerBank = 10,
        format = FlatBankAddressFormat(slotDigits = 2),
    )

    /** Walks every address; [answer] says what reading one does. Returns what was read, in order. */
    private fun walk(read: MutableList<Int>, answer: (Int) -> Throwable?): Flow<IndexUpdate> = indexWalk(
        addresses = layout.allAddresses().asIterable(),
        total = layout.slotCount,
        batchSize = 4,
        layout = layout,
    ) { address, displayId ->
        read += address.slot
        answer(address.slot)?.let { throw it }
        PresetSlot(address, displayId, "A", "Preset ${address.slot}")
    }

    private suspend fun Flow<IndexUpdate>.collectAll(): Pair<List<IndexUpdate>, Throwable?> {
        val updates = mutableListOf<IndexUpdate>()
        var failure: Throwable? = null
        catch { failure = it }.collect { updates += it }
        return updates to failure
    }

    private fun timeout() = InstrumentException.Timeout("reading a preset")

    @Test
    fun `three timeouts in a row end the walk and keep the rows already read`() = runTest {
        val read = mutableListOf<Int>()
        val (updates, failure) = walk(read) { if (it >= 5) timeout() else null }.collectAll()

        assertTrue(failure is InstrumentException.Timeout)
        assertEquals("nothing after the third silent slot is read", listOf(0, 1, 2, 3, 4, 5, 6, 7), read)
        assertEquals(
            (0..4).toList(),
            updates.filterIsInstance<IndexUpdate.Slots>().flatMap {
                it.slots
            }.map { it.address.slot },
        )
        assertEquals(3, updates.count { it is IndexUpdate.Failed })
    }

    @Test
    fun `a timeout between answers does not end the walk`() = runTest {
        val read = mutableListOf<Int>()
        val (updates, failure) = walk(read) { if (it == 2 || it == 3 || it == 6) timeout() else null }.collectAll()

        assertEquals(null, failure)
        assertEquals((0..9).toList(), read)
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /** A damaged reply is an instrument still talking, however many come in a row. */
    @Test
    fun `failures other than timeouts do not end the walk`() = runTest {
        val read = mutableListOf<Int>()
        val (updates, failure) =
            walk(read) { if (it in 2..7) InstrumentException.ProtocolDesync("damaged") else null }.collectAll()

        assertEquals(null, failure)
        assertEquals(6, updates.count { it is IndexUpdate.Failed })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `a lost link ends the walk at once`() = runTest {
        val read = mutableListOf<Int>()
        val (_, failure) = walk(read) {
            if (it ==
                3
            ) {
                InstrumentException.TransportLost("unplugged")
            } else {
                null
            }
        }.collectAll()

        assertTrue(failure is InstrumentException.TransportLost)
        assertEquals(listOf(0, 1, 2, 3), read)
    }
}
