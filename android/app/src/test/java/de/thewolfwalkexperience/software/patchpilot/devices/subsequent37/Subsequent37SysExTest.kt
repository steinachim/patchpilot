// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every expected request is the reference implementation's output, written out as hex. */
class Subsequent37SysExTest {

    private fun hex(text: String) = text.hexToBytes()

    @Test
    fun `the short requests are 13 command bytes, as the reference sends them`() {
        assertArrayEquals(hex("f0 04 0c 32 00 00 00 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.ping())
        assertArrayEquals(hex("f0 04 0c 06 03 00 00 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.bulkDumpRequest())
        assertArrayEquals(hex("f0 04 0c 06 04 00 00 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.settingsRequest())
        assertArrayEquals(hex("f0 04 0c 06 00 00 00 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.settingsPair())
        assertArrayEquals(hex("f0 04 0c 06 01 00 01 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.readProgram(1))
        assertArrayEquals(hex("f0 04 0c 06 01 01 7f 00 00 00 00 00 00 00 00 00 f7"), Subsequent37SysEx.readProgram(255))
        assertArrayEquals(hex("f0 7e 7f 06 01 f7"), Subsequent37SysEx.IDENTITY_REQUEST)
    }

    /** The four headers the vendor editor was captured writing: a rename, both halves of a swap, an upload. */
    @Test
    fun `a write is addressed with the program number split into 7-bit halves`() {
        val payload = Subsequent37Fixtures.unpackBlock(0)
        for ((number, address) in listOf(0 to "00 00", 143 to "01 0f", 144 to "01 10", 153 to "01 19", 255 to "01 7f")) {
            val message = Subsequent37SysEx.writeProgram(number, payload)
            assertArrayEquals(
                "program $number",
                hex("f0 04 0c 05 01 $address 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 01"),
                message.copyOfRange(0, 23),
            )
            assertEquals(756, message.size)
        }
    }

    /** The load-bearing one: the editor's own rename, reproduced byte for byte from the stored program. */
    @Test
    fun `writing the stored program back is the editor's own message`() {
        val payload = Subsequent37Fixtures.unpackBlock(0)
        assertArrayEquals(Subsequent37Fixtures.WRITE_RENAME_BACK, Subsequent37SysEx.writeProgram(0, payload))
    }

    @Test
    fun `a write closes the checksum and refuses what nothing on the instrument would`() {
        val broken = Subsequent37Fixtures.unpackBlock(2).also { it[100] = (it[100] + 1).toByte() }
        val sent = Subsequent37SysEx.programOf(Subsequent37SysEx.writeProgram(2, broken))!!
        assertTrue(Subsequent37SysEx.checksumCloses(sent))
        runCatching { Subsequent37SysEx.writeProgram(256, broken) }.let { assertTrue(it.isFailure) }
        runCatching { Subsequent37SysEx.writeProgram(0, ByteArray(487)) }.let { assertTrue(it.isFailure) }
    }

    /** A captured selection: bank 16 / slot 13 on channel 4, sent as CC32 1 and Program Change 0x7C. */
    @Test
    fun `a selection is bank select and Program Change on the given channel`() {
        assertArrayEquals(hex("b3 00 00 b3 20 01 c3 7c"), Subsequent37SysEx.select(channel = 3, number = 252))
        assertArrayEquals(hex("b0 00 00 b0 20 00 c0 06"), Subsequent37SysEx.select(channel = 0, number = 6))
    }

    @Test
    fun `the stored reply is matched on command and address, never on the address alone`() {
        val (buffer, stored) = Subsequent37Fixtures.READ_P001
        assertTrue(Subsequent37SysEx.isStoredProgramReply(stored, 1))
        assertFalse(Subsequent37SysEx.isStoredProgramReply(stored, 0))
        // The edit buffer ahead of it is addressed 00 00, whatever is selected.
        assertEquals(0, Subsequent37SysEx.addressOf(buffer))
        assertFalse(Subsequent37SysEx.isStoredProgramReply(buffer, 0))
        assertTrue(Subsequent37SysEx.isStoredProgramReply(Subsequent37Fixtures.READ_P255[1], 255))
    }

    @Test
    fun `a single-program message yields its program only whole and with a closing checksum`() {
        val stored = Subsequent37Fixtures.READ_P001[1]
        assertArrayEquals(Subsequent37Fixtures.unpackBlock(1), Subsequent37SysEx.programOf(stored))
        assertArrayEquals(Subsequent37Fixtures.unpackBlock(0), Subsequent37SysEx.programOf(Subsequent37Fixtures.WRITE_RENAME_BACK))
        assertNull(Subsequent37SysEx.programOf(stored.copyOfRange(0, 755)))
        val flipped = stored.copyOf().also { it[100] = (it[100].toInt() xor 0x01).toByte() }
        assertNull(Subsequent37SysEx.programOf(flipped))
        assertNull(Subsequent37SysEx.programOf(Subsequent37Fixtures.SETTINGS_IN01_OUT16))
    }

    @Test
    fun `the Identity Reply carries firmware 1_2_0`() {
        assertEquals("1.2.0", Subsequent37SysEx.decodeFirmware(Subsequent37Fixtures.IDENTITY_REPLY))
        assertNull(Subsequent37SysEx.decodeFirmware(hex("f0 7e 7f 06 02 43 00 41 35 06 06 00 00 7f f7")))
    }

    @Test
    fun `every program in the dump checksums, and packing reproduces its records`() {
        for (n in 0 until 256) {
            val payload = Subsequent37Fixtures.unpackBlock(n)
            assertTrue("program $n", Subsequent37SysEx.checksumCloses(payload))
            assertArrayEquals("program $n", Subsequent37Fixtures.packedBlock(n), Subsequent37SysEx.pack(payload))
        }
    }

    @Test
    fun `a record with a wrong marker or value bits does not unpack`() {
        assertNull(Subsequent37SysEx.unpack(hex("30 00 00"), 0, 1))
        assertNull(Subsequent37SysEx.unpack(hex("40 40 00"), 0, 1))
        assertNull(Subsequent37SysEx.unpack(hex("40 00"), 0, 1))
    }

    /** What the app sends must never be a reply shape or an unexplained command. */
    @Test
    fun `no request is one of the messages never to send`() {
        val forbidden = listOf(
            listOf(0x01, 0x07), listOf(0x02, 0x07), listOf(0x04, 0x07), listOf(0x04, 0x08),
            listOf(0x07, 0x02), listOf(0x35), listOf(0x1D), listOf(0x12), listOf(0x13), listOf(0x18),
        )
        val sent = listOf(
            Subsequent37SysEx.ping(), Subsequent37SysEx.bulkDumpRequest(), Subsequent37SysEx.settingsRequest(),
            Subsequent37SysEx.settingsPair(), Subsequent37SysEx.readProgram(7),
            Subsequent37SysEx.writeProgram(7, Subsequent37Fixtures.unpackBlock(7)),
        )
        for (message in sent) {
            for (command in forbidden) {
                assertFalse(Subsequent37SysEx.isCommand(message, *command.toIntArray()))
            }
        }
    }
}
