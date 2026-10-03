// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AxeFx3EditorTest {

    private fun at(number: Int) = SlotAddress(number / 128, number % 128)

    private suspend fun TestScope.connected(fake: FakeAxeFx3, config: AxeFx3Config = AxeFx3Config()) =
        axeFx3(fake, config).apply { connect() }

    private suspend inline fun <reified T : Throwable> thrownBy(crossinline block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    /** The commands sent from [from] on, for checking one edit's sequence after connect. */
    private fun FakeAxeFx3.commandsSince(from: Int) = commands().drop(from)

    private val write = listOf("77") + List(16) { "78" } + listOf("79")

    @Test
    fun `declares every edit, rename as the instrument's own and the rest emulated`() = runTest {
        val editor = connected(FakeAxeFx3()).editor
        assertEquals(EditOp.entries.toSet(), editor.supported)
        assertEquals(31, editor.maxNameLength)
        assertFalse(editor.isEmulated(EditOp.RENAME))
        listOf(EditOp.COPY, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE).forEach { assertTrue(editor.isEmulated(it)) }
    }

    @Test
    fun `rename loads the preset, sets the name, stores it, reads it back and loads the old one again`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(10, "Old")
        val instrument = connected(fake)
        val start = fake.requests.size

        instrument.editor.rename(at(10), "New Name  ")

        assertEquals("New Name", fake.nameAt(10))
        assertEquals(listOf("0d", "0d", "01/27", "01/28", "01/26", "0d", "01/27"), fake.commandsSince(start))
        assertTrue(fake.requests.any { it.contentEquals(AxeFx3SysEx.setName("New Name")) })
        assertEquals(0, fake.selected)
    }

    @Test
    fun `a name the instrument cannot store, or would read as empty, is refused before anything is sent`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(10, "Old")
        val instrument = connected(fake)
        val start = fake.requests.size
        for (name in listOf("", "   ", "Café", "x".repeat(32), "<EMPTY>")) {
            thrownBy<IllegalArgumentException> { instrument.editor.rename(at(10), name) }
        }
        assertEquals(start, fake.requests.size)
        assertEquals("Old", fake.nameAt(10))
    }

    @Test
    fun `renaming an empty slot is refused, and an unchanged name sends nothing to store`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(10, "Same")
        val instrument = connected(fake)
        thrownBy<InstrumentException.NotSupported> { instrument.editor.rename(at(11), "Name") }
        val start = fake.requests.size
        instrument.editor.rename(at(10), "Same")
        assertFalse(fake.commandsSince(start).contains("01/26"))
    }

    @Test
    fun `a rename that does not stick is reported`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(10, "Old")
        val instrument = connected(fake)
        fake.ignoreStores = true
        thrownBy<InstrumentException.ProtocolDesync> { instrument.editor.rename(at(10), "New") }
    }

    @Test
    fun `copy reads the source, writes and stores the destination, reads it back, and loads the old preset again`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        val instrument = connected(fake)
        val start = fake.requests.size

        assertEquals("Lead", instrument.editor.copyProgram(at(1), at(2)))

        assertEquals("Lead", fake.nameAt(1))
        assertEquals("Lead", fake.nameAt(2))
        assertEquals(
            listOf("0d", "0d", "0d", "03", "01/27") + write + listOf("01/26", "03", "0d", "01/27"),
            fake.commandsSince(start),
        )
    }

    @Test
    fun `an unnamed preset is copied under the name the browser gives it`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "")
        assertEquals(AxeFx3Instrument.UNNAMED, connected(fake).editor.copyProgram(at(1), at(2)))
    }

    @Test
    fun `copying onto itself, from an empty slot or onto an occupied one is refused before anything is selected`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        fake.put(2, "Pad")
        val instrument = connected(fake)
        val start = fake.requests.size
        thrownBy<InstrumentException.NotSupported> { instrument.editor.copyProgram(at(1), at(1)) }
        thrownBy<InstrumentException.NotSupported> { instrument.editor.copyProgram(at(3), at(4)) }
        thrownBy<InstrumentException.NotSupported> { instrument.editor.copyProgram(at(1), at(2)) }
        assertFalse(fake.commandsSince(start).contains("01/27"))
        assertEquals("Pad", fake.nameAt(2))
    }

    @Test
    fun `a copy the instrument acknowledges but does not store is caught by the read-back`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        val instrument = connected(fake)
        fake.ignoreStores = true
        thrownBy<InstrumentException.ProtocolDesync> { instrument.editor.copyProgram(at(1), at(2)) }
    }

    @Test
    fun `a rejected write stores nothing and reloads the target's stored preset`() = runTest {
        val fake = FakeAxeFx3().apply { endStatusOf = { 0x13 } }
        fake.put(1, "Lead")
        val instrument = connected(fake)
        val start = fake.requests.size
        thrownBy<InstrumentException.DeviceRejected> { instrument.editor.copyProgram(at(1), at(2)) }
        val sent = fake.commandsSince(start)
        assertFalse(sent.contains("01/26"))
        // After the rejected end: the target loaded again, then the previously loaded preset.
        assertEquals(listOf("79", "01/27", "01/27"), sent.takeLast(3))
        assertEquals(AxeFx3SysEx.EMPTY_NAME, fake.nameAt(2))
    }

    @Test
    fun `move writes the destination first and empties the source last`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(3, "Pad")
        val instrument = connected(fake)
        val start = fake.requests.size

        instrument.editor.move(at(3), at(4))

        assertEquals("Pad", fake.nameAt(4))
        assertEquals(AxeFx3SysEx.EMPTY_NAME, fake.nameAt(3))
        val stores = fake.requests.drop(start).filter { it[5].toInt() == 0x01 && it[6].toInt() == 0x26 }
        assertEquals(listOf(AxeFx3SysEx.store(4), AxeFx3SysEx.store(3)).map { it.toList() }, stores.map { it.toList() })
    }

    @Test
    fun `a move whose source cannot be emptied says the preset is in both slots`() = runTest {
        val fake = FakeAxeFx3().apply { endStatusOf = { if (it == 2) 0x13 else 0 } }
        fake.put(3, "Pad")
        val instrument = connected(fake)
        val error = thrownBy<InstrumentException.ProtocolDesync> { instrument.editor.move(at(3), at(4)) }
        assertTrue(error.message!!.contains("both slots"))
        assertEquals("Pad", fake.nameAt(3))
        assertEquals("Pad", fake.nameAt(4))
    }

    @Test
    fun `swap reads both presets before writing either`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(5, "A")
        fake.put(6, "B")
        val instrument = connected(fake)
        val start = fake.requests.size

        instrument.editor.swap(at(5), at(6))

        assertEquals("B", fake.nameAt(5))
        assertEquals("A", fake.nameAt(6))
        val sent = fake.commandsSince(start)
        assertEquals(2, sent.subList(0, sent.indexOf("01/27")).count { it == "03" })
    }

    @Test
    fun `an empty slot swaps with a full one`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(7, "C")
        val instrument = connected(fake)
        instrument.editor.swap(at(7), at(8))
        assertEquals(AxeFx3SysEx.EMPTY_NAME, fake.nameAt(7))
        assertEquals("C", fake.nameAt(8))

        val start = fake.requests.size
        instrument.editor.swap(at(20), at(21))
        assertFalse(fake.commandsSince(start).contains("77"))
    }

    @Test
    fun `a swap whose second write fails puts the first slot back`() = runTest {
        val fake = FakeAxeFx3().apply { endStatusOf = { if (it == 2) 0x13 else 0 } }
        fake.put(5, "A")
        fake.put(6, "B")
        val instrument = connected(fake)
        thrownBy<InstrumentException.DeviceRejected> { instrument.editor.swap(at(5), at(6)) }
        assertEquals("A", fake.nameAt(5))
        assertEquals("B", fake.nameAt(6))
    }

    @Test
    fun `delete writes the empty preset, and an empty slot is left alone`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(9, "X")
        val instrument = connected(fake)
        instrument.editor.delete(at(9))
        assertEquals(AxeFx3SysEx.EMPTY_NAME, fake.nameAt(9))
        assertTrue(fake.requests.windowed(16).any { window ->
            window.zip(AxeFx3DumpImage.parse(AxeFx3Fixtures.EMPTY_PRESET_IMAGE).chunks).all { (a, b) -> a.contentEquals(b) }
        })

        val start = fake.requests.size
        instrument.editor.delete(at(9))
        assertFalse(fake.commandsSince(start).contains("01/27"))
    }

    /** Abandoned between chunks, the loaded preset's edit buffer would be left half-written. */
    @Test
    fun `cancelling during a transfer still completes the write and its store`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        val instrument = connected(fake)
        var chunks = 0
        val job = launch { instrument.editor.copyProgram(at(1), at(2)) }
        fake.onRequest = { request -> if (request[5].toInt() == 0x78 && ++chunks == 3) job.cancel() }
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(16, chunks)
        assertTrue(fake.commands().contains("01/26"))
        assertEquals("Lead", fake.nameAt(2))
    }

    @Test
    fun `the loaded preset follows a move and a swap, and is left alone after deleting it`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(3, "Pad")
        fake.put(5, "A")
        fake.put(6, "B")
        fake.put(9, "X")
        val instrument = connected(fake)

        instrument.select(at(3))
        instrument.editor.move(at(3), at(4))
        assertEquals(4, fake.selected)

        instrument.select(at(5))
        instrument.editor.swap(at(5), at(6))
        assertEquals(6, fake.selected)

        instrument.select(at(9))
        val start = fake.requests.size
        instrument.editor.delete(at(9))
        assertEquals("0d", fake.commandsSince(start).last())
        assertEquals(9, fake.selected)
    }

    @Test
    fun `nothing is restored when restoring is off or the unit cannot say what is loaded`() = runTest {
        val off = FakeAxeFx3()
        off.put(1, "Lead")
        connected(off, AxeFx3Config(restoreLoadedPresetAfterEdit = false)).editor.copyProgram(at(1), at(2))
        assertEquals(2, off.selected)

        val noEcho = FakeAxeFx3().apply { loadedQueryEchoesNumber = false }
        noEcho.put(1, "Lead")
        connected(noEcho).editor.copyProgram(at(1), at(2))
        assertEquals(2, noEcho.selected)
    }

    @Test
    fun `a restore that cannot ask what is loaded does not fail the edit`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        val instrument = connected(fake)
        fake.loadedQuerySilent = true
        assertEquals("Lead", instrument.editor.copyProgram(at(1), at(2)))
        assertEquals(2, fake.selected)
    }
}
