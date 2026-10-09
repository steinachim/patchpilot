// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxeFx3DeviceTest {

    private fun TestScope.device(fake: FakeAxeFx3) = AxeFx3Device(SysExExchange(fake.transport, backgroundScope))

    @Test
    fun `a dump read returns the preset exactly as it was sent`() = runTest {
        val fake = FakeAxeFx3()
        val image = fake.put(5, "Lead")
        val read = device(fake).readDump(5)
        assertArrayEquals(image, read.raw)
        assertEquals(listOf("03"), fake.commands())
    }

    @Test
    fun `an end marker left from an earlier read is not taken for this one`() = runTest {
        val fake = FakeAxeFx3().apply { strayBeforeReadAck = true }
        val image = fake.put(5, "Lead")
        assertArrayEquals(image, device(fake).readDump(5).raw)
    }

    @Test
    fun `a damaged or incomplete read is read again once`() = runTest {
        for (fault in FakeAxeFx3.ReadFault.entries) {
            val fake = FakeAxeFx3().apply { readFault = fault }
            val image = fake.put(5, "Lead")
            assertArrayEquals(fault.name, image, device(fake).readDump(5).raw)
            assertEquals(fault.name, listOf("03", "03"), fake.commands())
        }
    }

    @Test
    fun `a read damaged twice fails`() = runTest {
        val fake = FakeAxeFx3().apply {
            readFault = FakeAxeFx3.ReadFault.BAD_CHECKSUM
            readFaultCount = 2
        }
        fake.put(5, "Lead")
        thrownBy<InstrumentException.ProtocolDesync> { device(fake).readDump(5) }
    }

    @Test
    fun `a write replays the begin, the 16 chunks verbatim and the end, in order`() = runTest {
        val fake = FakeAxeFx3()
        val image = AxeFx3DumpImage.parse(fake.put(5, "Lead"))
        device(fake).writeEditBuffer(image)
        assertEquals(18, fake.requests.size)
        assertArrayEquals(AxeFx3Fixtures.DUMP_WRITE_BEGIN, fake.requests[0])
        image.chunks.forEachIndexed { i, chunk -> assertArrayEquals(chunk, fake.requests[1 + i]) }
        assertArrayEquals(image.end, fake.requests[17])
    }

    /** A re-sent chunk would land in the transfer twice. */
    @Test
    fun `a chunk whose acknowledgement is lost is sent once, and nothing after it`() = runTest {
        val fake = FakeAxeFx3().apply { silentChunk = 3 }
        val image = AxeFx3DumpImage.parse(fake.put(5, "Lead"))
        thrownBy<InstrumentException.Timeout> { device(fake).writeEditBuffer(image) }
        assertEquals(listOf("77", "78", "78", "78"), fake.commands())
    }

    @Test
    fun `a rejected step stops the transfer, and a rejected end says nothing was stored`() = runTest {
        val fake = FakeAxeFx3().apply { chunkStatus = { if (it == 2) 0x05 else 0 } }
        val image = AxeFx3DumpImage.parse(fake.put(5, "Lead"))
        val rejected = thrownBy<InstrumentException.DeviceRejected> { device(fake).writeEditBuffer(image) }
        assertEquals(0x05, rejected.status)
        assertEquals(listOf("77", "78", "78"), fake.commands())

        val atEnd = FakeAxeFx3().apply { endStatusOf = { 0x13 } }
        val end = thrownBy<InstrumentException.DeviceRejected> {
            device(atEnd).writeEditBuffer(AxeFx3DumpImage.parse(atEnd.put(5, "Lead")))
        }
        assertEquals(0x13, end.status)
        assertTrue(end.message!!.contains("stored nothing"))
    }

    /** The instrument's own contract, which the fake enforces: a dump it never sent is refused. */
    @Test
    fun `an image the instrument never sent is rejected at the end`() = runTest {
        val fake = FakeAxeFx3()
        val foreign = AxeFx3DumpImage.parse(FakeAxeFx3.presetImage(99))
        val rejected = thrownBy<InstrumentException.DeviceRejected> { device(fake).writeEditBuffer(foreign) }
        assertEquals(0x13, rejected.status)
    }

    @Test
    fun `an unacknowledged store is not sent again, and is no error by itself`() = runTest {
        val fake = FakeAxeFx3().apply { silentStores = true }
        device(fake).store(5)
        assertEquals(listOf("01/26"), fake.commands())
    }

    @Test
    fun `the loaded preset is the number the reply echoes, or null without one`() = runTest {
        val fake = FakeAxeFx3()
        val device = device(fake)
        device.select(300)
        assertEquals(300, device.loadedPreset(512))
        fake.loadedQueryEchoesNumber = false
        assertNull(device.loadedPreset(512))
    }

    @Test
    fun `a dump delivered three bytes at a time is still assembled`() = runTest {
        val fake = FakeAxeFx3(bufferCapacity = 20_000, pieceSize = 3)
        val image = fake.put(5, "Lead")
        assertArrayEquals(image, device(fake).readDump(5).raw)
    }

    @Test
    fun `names are matched on their echo and cut at the terminator`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(42, "Bassguy")
        val device = device(fake)
        assertEquals("Bassguy", device.name(42))
        assertEquals(AxeFx3SysEx.EMPTY_NAME, device.name(43))
    }
}
