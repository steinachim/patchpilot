// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies buildMessage()/parseMessage()/the CRC against exact byte sequences from real USB
 * traffic.
 */
class NordMessageTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

    // Bare PROTOCOL_CTRL/PROTOCOL_VERSION_CTRL/DEVICE_INFO_QUERY request
    @Test
    fun `build handshake info query matches the wire format`() {
        val built = buildMessage(protocolId = 7, protocolVersion = 0, subOp = 2)
        assertArrayEquals(hex("000000120000000700000000000000026791"), built)
    }

    // The device-info reply for a Nord Grand
    @Test
    fun `parse handshake info response matches the wire format`() {
        val data = hex("0000001d00000007000000000000000305060107000a020c0a0d001c14")
        val msg = parseMessage(data)
        assertEquals(7, msg.protocolId)
        assertEquals(0, msg.protocolVersion)
        assertEquals(3, msg.subOp)
        assertEquals(data.size - MESSAGE_HEADER_LEN - MESSAGE_CRC_LEN, msg.payload.size)
    }

    // PROTOCOL_FILE_TRANSFER at PROTOCOL_VERSION_FILE_TRANSFER (Grand), SELECT_PRESET,
    // bank=1 item=6 ("B:2:2")
    @Test
    fun `build select preset request matches the wire format`() {
        val payload = hex("0000000100000006")
        val built = buildMessage(protocolId = 12, protocolVersion = 10, subOp = 47, payload = payload)
        assertArrayEquals(hex("0000001a0000000c0000000a0000002f0000000100000006a5c7"), built)
    }

    // SWAP_PROGRAMS(0,0 -> 0,3); A:1:4 is occupied, so it's sub-op 26 rather than
    // MOVE_PROGRAM's 24.
    @Test
    fun `build swap programs request matches the wire format`() {
        // src_bank=0, src_item=0, dst_bank=0, dst_item=3 (A:1:1 -> A:1:4)
        val payload = hex("00000000") + hex("00000000") + hex("00000000") + hex("00000003")
        val built = buildMessage(protocolId = 12, protocolVersion = 10, subOp = 26, payload = payload)
        assertArrayEquals(
            hex("000000220000000c0000000a0000001a000000000000000000000000000000031880"),
            built,
        )
    }

    @Test
    fun `parse throws on CRC mismatch`() {
        val data = hex("0000001d00000007000000000000000305060107000a020c0a0d001c14").clone()
        data[data.size - 1] = (data[data.size - 1] + 1).toByte() // corrupt CRC
        assertThrows(IllegalArgumentException::class.java) { parseMessage(data) }
    }

    /**
     * Trailing bytes belong to whatever comes next, not to this message's checksum. Reading the
     * last two bytes of the *buffer* as the CRC is right only when the buffer holds exactly one
     * whole message.
     */
    @Test
    fun `parse is delimited by the declared length, not the buffer size`() {
        val data = hex("0000001d00000007000000000000000305060107000a020c0a0d001c14")
        val msg = parseMessage(data + hex("deadbeef"))
        assertEquals(7, msg.protocolId)
        assertEquals(3, msg.subOp)
        assertEquals(data.size - MESSAGE_HEADER_LEN - MESSAGE_CRC_LEN, msg.payload.size)
    }

    /** The complement: fewer bytes than declared is not a CRC error. */
    @Test
    fun `parse refuses a truncated message`() {
        val data = hex("0000001d00000007000000000000000305060107000a020c0a0d001c14")
        val caught = assertThrows(IllegalArgumentException::class.java) {
            parseMessage(data.copyOfRange(0, data.size - 4))
        }
        assertEquals(true, caught.message!!.contains("only"))
    }
}
