// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every builder against the bytes Axe-Edit III actually sent, and every decoder against real replies. */
class AxeFx3SysExTest {

    @Test
    fun `requests match the captured ones byte for byte`() {
        assertArrayEquals(AxeFx3Fixtures.SELECT_356, AxeFx3SysEx.select(356))
        assertArrayEquals(AxeFx3Fixtures.SELECT_464, AxeFx3SysEx.select(464))
        assertArrayEquals(AxeFx3Fixtures.STORE_509, AxeFx3SysEx.store(509))
        assertArrayEquals(AxeFx3Fixtures.STORE_510, AxeFx3SysEx.store(510))
        assertArrayEquals(AxeFx3Fixtures.STORE_511, AxeFx3SysEx.store(511))
        assertArrayEquals(AxeFx3Fixtures.STORE_464, AxeFx3SysEx.store(464))
        assertArrayEquals(AxeFx3Fixtures.DUMP_READ_REQUEST_511, AxeFx3SysEx.dumpReadRequest(511))
        assertArrayEquals(AxeFx3Fixtures.DUMP_READ_REQUEST_509, AxeFx3SysEx.dumpReadRequest(509))
        assertArrayEquals(AxeFx3Fixtures.DUMP_WRITE_BEGIN, AxeFx3SysEx.dumpWriteBegin())
        assertArrayEquals(AxeFx3Fixtures.FIRMWARE_REQUEST, AxeFx3SysEx.firmwareRequest())
        assertArrayEquals(AxeFx3Fixtures.CAPABILITIES_REQUEST, AxeFx3SysEx.capabilitiesRequest())
        assertArrayEquals(AxeFx3Fixtures.WHO_IS_THERE, AxeFx3Device.WHO_IS_THERE)
    }

    /** The packing, its two-byte artifact and the reserved bytes, all pinned by two real renames. */
    @Test
    fun `a rename request matches the captured one byte for byte`() {
        assertArrayEquals(AxeFx3Fixtures.RENAME_TO_WOLFWALK, AxeFx3SysEx.setName("Key Reamp Wolfwalk"))
        assertArrayEquals(AxeFx3Fixtures.RENAME_TO_WOLFWALK_123, AxeFx3SysEx.setName("Key Reamp Wolfwalk123"))
    }

    @Test
    fun `the checksum closes every captured message`() {
        listOf(
            AxeFx3Fixtures.SELECT_ACK, AxeFx3Fixtures.STORE_ACK, AxeFx3Fixtures.DUMP_READ_ACK_511,
            AxeFx3Fixtures.DUMP_END_511, AxeFx3Fixtures.BEGIN_ACK, AxeFx3Fixtures.CHUNK_ACK,
            AxeFx3Fixtures.END_ACK, AxeFx3Fixtures.FIRMWARE_REPLY, AxeFx3Fixtures.CAPABILITIES_REPLY_MARK_I,
            AxeFx3Fixtures.WHO_IS_THERE_REPLY, AxeFx3Fixtures.REAL_CHUNK, AxeFx3Fixtures.RENAME_TO_WOLFWALK,
        ).forEach { assertTrue(it.joinToString(" ") { b -> "%02x".format(b) }, AxeFx3SysEx.isOurs(it)) }
    }

    /** The real chunk's last wire byte carries padding bits, so only the data round-trips. */
    @Test
    fun `packing inverts unpacking on a real chunk, padding aside`() {
        val chunk = AxeFx3Fixtures.REAL_CHUNK
        val wire = chunk.copyOfRange(5, chunk.size - 2)
        val dense = AxeFx3SysEx.unpack7(wire)
        assertEquals(2690, dense.size)
        assertEquals(listOf(0xF0, 0x00), dense.take(2).map { it.toInt() and 0xFF })
        val repacked = AxeFx3SysEx.pack7(dense)
        assertArrayEquals(wire.copyOf(wire.size - 1), repacked.copyOf(repacked.size - 1))
        assertEquals(AxeFx3SysEx.DUMP_CHUNK_DATA_BYTES, AxeFx3SysEx.decodeChunk(chunk).size)
    }

    @Test
    fun `the firmware reply decodes to its version and build stamp`() {
        val firmware = AxeFx3SysEx.decodeFirmware(AxeFx3Fixtures.FIRMWARE_REPLY)!!
        assertEquals("32.06", firmware.version)
        assertEquals("Jun 25 2026 10:11:56", firmware.build)
    }

    @Test
    fun `the model record of a Mark I says 512 presets`() {
        val values = AxeFx3SysEx.decodeCapabilities(AxeFx3Fixtures.CAPABILITIES_REPLY_MARK_I)
        assertEquals(listOf(336, 2048, 2048, 16, 512), values)
        assertEquals(512, AxeFx3SysEx.presetCountOf(values))
    }

    /** A fifth value of exactly 1024. The Mark II reply is built from the Mark I one. */
    @Test
    fun `a fifth value of 1024 is a Mark II, and anything else a Mark I`() {
        val markII =
            FakeAxeFx3.message(AxeFx3SysEx.CMD_CAPABILITIES, 0x50, 0x02, 0x00, 0x10, 0x00, 0x10, 0x10, 0x00, 0x00, 0x08)
        assertEquals(1024, AxeFx3SysEx.presetCountOf(AxeFx3SysEx.decodeCapabilities(markII)))
        assertEquals(512, AxeFx3SysEx.presetCountOf(emptyList()))
        assertEquals(512, AxeFx3SysEx.presetCountOf(listOf(336, 2048, 2048, 16, 2048)))
        // An acknowledgement in place of the reply carries no values.
        val ack = FakeAxeFx3.stepAck(AxeFx3SysEx.CMD_CAPABILITIES, 0)
        assertTrue(AxeFx3SysEx.isCapabilitiesReply(ack))
        assertEquals(emptyList<Int>(), AxeFx3SysEx.decodeCapabilities(ack))
    }

    @Test
    fun `a name is cut at its terminator, where a delete leaves an old name's tail`() {
        val field = "<EMPTY>".toByteArray() + byteArrayOf(0) + "p Wolfwalk".toByteArray() + ByteArray(14)
        assertEquals("<EMPTY>", AxeFx3SysEx.nameFromField(field))
        assertTrue(AxeFx3SysEx.hasStaleTail(field))
        val clean = "Bassguy".toByteArray() + ByteArray(25)
        assertEquals("Bassguy", AxeFx3SysEx.nameFromField(clean))
        assertFalse(AxeFx3SysEx.hasStaleTail(clean))
        assertEquals("", AxeFx3SysEx.nameFromField(ByteArray(32)))
        assertEquals("x".repeat(32), AxeFx3SysEx.nameFromField("x".repeat(32).toByteArray()))
    }

    @Test
    fun `a name reply is matched on the number it echoes`() {
        val reply = FakeAxeFx3.message(AxeFx3SysEx.CMD_QUERY_NAME, 0x7F, 0x03, *IntArray(32) { 0x41 })
        assertEquals(511, AxeFx3SysEx.nameReplyNumber(reply))
        assertEquals("A".repeat(32), AxeFx3SysEx.nameFromField(AxeFx3SysEx.nameField(reply)!!))
        assertNull(AxeFx3SysEx.nameReplyNumber(AxeFx3Fixtures.SELECT_ACK))
    }

    @Test
    fun `a dump read is matched on the number its acknowledgement echoes`() {
        assertTrue(AxeFx3SysEx.isDumpReadAck(AxeFx3Fixtures.DUMP_READ_ACK_511, 511))
        assertFalse(AxeFx3SysEx.isDumpReadAck(AxeFx3Fixtures.DUMP_READ_ACK_511, 509))
        assertTrue(AxeFx3SysEx.isDumpReadAck(AxeFx3Fixtures.DUMP_READ_ACK_509, 509))
    }

    @Test
    fun `step and editor acknowledgements are told apart, with their status`() {
        assertTrue(AxeFx3SysEx.isStepAck(AxeFx3Fixtures.BEGIN_ACK, AxeFx3SysEx.CMD_DUMP_BEGIN))
        assertTrue(AxeFx3SysEx.isStepAck(AxeFx3Fixtures.CHUNK_ACK, AxeFx3SysEx.CMD_DUMP_CHUNK))
        assertTrue(AxeFx3SysEx.isStepAck(AxeFx3Fixtures.END_ACK, AxeFx3SysEx.CMD_DUMP_END))
        assertFalse(AxeFx3SysEx.isStepAck(AxeFx3Fixtures.CHUNK_ACK, AxeFx3SysEx.CMD_DUMP_END))
        assertEquals(0, AxeFx3SysEx.stepAckStatus(AxeFx3Fixtures.END_ACK))
        assertEquals(0x13, AxeFx3SysEx.stepAckStatus(FakeAxeFx3.stepAck(AxeFx3SysEx.CMD_DUMP_END, 0x13)))
        assertTrue(AxeFx3SysEx.isEditorAck(AxeFx3Fixtures.SELECT_ACK, AxeFx3SysEx.SUB_SELECT))
        assertTrue(AxeFx3SysEx.isEditorAck(AxeFx3Fixtures.STORE_ACK, AxeFx3SysEx.SUB_STORE))
        assertFalse(AxeFx3SysEx.isEditorAck(AxeFx3Fixtures.STORE_ACK, AxeFx3SysEx.SUB_SELECT))
    }
}
