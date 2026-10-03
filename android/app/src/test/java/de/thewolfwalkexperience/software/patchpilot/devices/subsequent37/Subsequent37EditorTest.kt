// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Subsequent37EditorTest {

    private fun address(number: Int) = Subsequent37AddressFormat.addressOf(number)

    private suspend fun TestScope.connected(fake: FakeSubsequent37 = FakeSubsequent37(backgroundScope)) =
        subsequent37(fake).apply { connect() }

    private suspend fun refused(block: suspend () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (expected: InstrumentException.NotSupported) {
        } catch (expected: IllegalArgumentException) {
        }
    }

    private val blankPacked get() = Subsequent37SysEx.pack(Subsequent37Fixtures.BLANK)

    @Test
    fun `every edit is offered, and every one is emulated`() = runTest {
        val editor = connected().editor
        assertEquals(EditOp.entries.toSet(), editor.supported)
        assertTrue(EditOp.entries.all { editor.isEmulated(it) })
        assertEquals(24, editor.maxNameLength)
    }

    /** The vendor editor's own rename of program 0, reproduced byte for byte. */
    @Test
    fun `a rename sends exactly the editor's message`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        connected(fake).editor.rename(address(0), "Preset 000  Test")
        assertEquals(1, fake.writes.size)
        assertArrayEquals(Subsequent37Fixtures.WRITE_RENAME, fake.writes.single())
        assertEquals("Preset 000  Test", fake.programAt(0).name)
    }

    @Test
    fun `a rename refuses before anything is written`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        val empty = Subsequent37Fixtures.emptyPrograms(1).single()
        refused { editor.rename(address(1), "x".repeat(25)) }
        refused { editor.rename(address(1), "Café") }
        refused { editor.rename(address(1), "   ") }
        refused { editor.rename(address(1), "Init Preset") }
        refused { editor.rename(address(empty), "Something") }
        assertTrue(fake.writes.isEmpty())
        // Renaming to the name it already has writes nothing.
        editor.rename(address(1), "Preset 001")
        assertTrue(fake.writes.isEmpty())
    }

    /** A write may land after the first read-back; one stale read is not a failure. */
    @Test
    fun `a read-back that still shows the old program is read again`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { staleReadsAfterWrite = 1 }
        connected(fake).editor.rename(address(1), "Renamed")
        assertEquals("Renamed", fake.programAt(1).name)
    }

    @Test
    fun `a write that never lands is reported after three read-backs`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { ignoreWritesTo = setOf(1) }
        val instrument = connected(fake)
        val readsBefore = fake.requests.count { Subsequent37SysEx.isCommand(it, 0x06, 0x01) }
        try {
            instrument.editor.rename(address(1), "Renamed")
            fail("an unstored write was reported as done")
        } catch (expected: InstrumentException.ProtocolDesync) {
        }
        // One read before the write, three after it.
        assertEquals(4, fake.requests.count { Subsequent37SysEx.isCommand(it, 0x06, 0x01) } - readsBefore)
    }

    @Test
    fun `a copy writes the source's bytes to an empty slot and returns its name`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        val target = Subsequent37Fixtures.emptyPrograms(1).single()
        assertEquals("Preset 002", editor.copyProgram(address(2), address(target)))
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[target])
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[2])
    }

    @Test
    fun `a copy refuses an occupied destination, an empty source and itself`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        val empty = Subsequent37Fixtures.emptyPrograms(2)
        refused { editor.copyProgram(address(2), address(3)) }
        refused { editor.copyProgram(address(empty[0]), address(empty[1])) }
        refused { editor.copyProgram(address(2), address(2)) }
        assertTrue(fake.writes.isEmpty())
    }

    @Test
    fun `a move writes the destination first and the panel's Initialize over the source last`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        val target = Subsequent37Fixtures.emptyPrograms(1).single()
        editor.move(address(2), address(target))
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[target])
        assertArrayEquals(blankPacked, fake.packed[2])
        assertEquals(listOf(target, 2), fake.writes.map { Subsequent37SysEx.addressOf(it) })
    }

    @Test
    fun `a move whose erase fails says the preset is now in both slots`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { ignoreWritesTo = setOf(2) }
        val editor = connected(fake).editor
        val target = Subsequent37Fixtures.emptyPrograms(1).single()
        try {
            editor.move(address(2), address(target))
            fail("a half-done move was reported as done")
        } catch (expected: InstrumentException.ProtocolDesync) {
            assertTrue(expected.message!!.contains("in both slots"))
        }
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[target])
    }

    @Test
    fun `a swap exchanges two programs, an empty side included`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        val empty = Subsequent37Fixtures.emptyPrograms(1).single()
        editor.swap(address(2), address(3))
        assertArrayEquals(Subsequent37Fixtures.packedBlock(3), fake.packed[2])
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[3])

        editor.swap(address(4), address(empty))
        assertArrayEquals(Subsequent37Fixtures.packedBlock(empty), fake.packed[4])
        assertArrayEquals(Subsequent37Fixtures.packedBlock(4), fake.packed[empty])
    }

    @Test
    fun `a swap whose second write fails puts the first slot back`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { ignoreWritesTo = setOf(2) }
        val editor = connected(fake).editor
        try {
            editor.swap(address(2), address(3))
            fail("a half-done swap was reported as done")
        } catch (expected: InstrumentException.ProtocolDesync) {
        }
        assertArrayEquals(Subsequent37Fixtures.packedBlock(3), fake.packed[3])
        assertArrayEquals(Subsequent37Fixtures.packedBlock(2), fake.packed[2])
    }

    @Test
    fun `a delete writes the panel's Initialize, and an empty slot is left alone`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val editor = connected(fake).editor
        editor.delete(address(2))
        assertArrayEquals(blankPacked, fake.packed[2])
        editor.delete(address(Subsequent37Fixtures.emptyPrograms(1).single()))
        assertEquals(1, fake.writes.size)
    }
}
