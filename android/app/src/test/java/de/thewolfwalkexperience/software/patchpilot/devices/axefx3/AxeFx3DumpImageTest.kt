// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AxeFx3DumpImageTest {

    @Test
    fun `the shipped delete image is the editor's recorded transfer`() {
        val image = AxeFx3DumpImage.parse(AxeFx3Fixtures.EMPTY_PRESET_IMAGE)
        assertEquals(49_323, image.raw.size)
        assertEquals(16, image.chunks.size)
        assertTrue(image.chunks.all { it.size == AxeFx3SysEx.DUMP_CHUNK_MESSAGE_BYTES })
        assertEquals(listOf(0x79, 0, 0, 0), image.end.copyOfRange(5, 9).map { it.toInt() and 0xFF })
        // Almost all fill: 18,432 bytes of 0xFF among the 43,008 (PROTOCOL_AxeFX3.md §8.3).
        assertEquals(43_008, image.decodedData.size)
        assertEquals(18_432, image.decodedData.count { it == 0xFF.toByte() })
    }

    @Test
    fun `a captured image parses and keeps its raw bytes`() {
        val image = AxeFx3DumpImage.parse(AxeFx3Fixtures.NEVER_USED_IMAGE)
        assertArrayEquals(AxeFx3Fixtures.NEVER_USED_IMAGE, image.raw)
        assertArrayEquals(image.raw, (image.chunks + image.end).fold(ByteArray(0)) { a, m -> a + m })
    }

    @Test
    fun `anything but 16 well-formed chunks and an end is refused`() {
        val messages = FakeAxeFx3.split(AxeFx3Fixtures.NEVER_USED_IMAGE)
        fun join(list: List<ByteArray>) = list.fold(ByteArray(0)) { a, m -> a + m }
        val badChecksum = messages.toMutableList().also {
            it[2] = it[2].copyOf().apply { this[size - 2] = (this[size - 2].toInt() xor 1).toByte() }
        }
        listOf(
            join(messages.drop(1)),
            join(messages.dropLast(1)),
            join(messages + messages.last()),
            join(badChecksum),
            join(listOf(messages[0]) + messages),
            AxeFx3Fixtures.NEVER_USED_IMAGE.copyOf(AxeFx3Fixtures.NEVER_USED_IMAGE.size - 1),
            ByteArray(43_008),
        ).forEach { raw -> assertThrows(IllegalArgumentException::class.java) { AxeFx3DumpImage.parse(raw) } }
    }

    @Test
    fun `a damaged read is a protocol desync, which the reader may retry`() {
        val messages = FakeAxeFx3.split(AxeFx3Fixtures.NEVER_USED_IMAGE)
        assertThrows(InstrumentException.ProtocolDesync::class.java) {
            AxeFx3DumpImage.fromReadReplies(messages.drop(1))
        }
        val shortChunk = messages.toMutableList().also { it[4] = it[4].copyOf(100).also { m -> m[99] = 0xF7.toByte() } }
        assertThrows(InstrumentException.ProtocolDesync::class.java) {
            AxeFx3DumpImage.fromReadReplies(shortChunk)
        }
    }

    /** The bytes a store rewrites are ignored; any other difference is a different preset. */
    @Test
    fun `stored data compares equal across the header bytes a store rewrites`() {
        val original = AxeFx3DumpImage.parse(AxeFx3Fixtures.NEVER_USED_IMAGE)
        // Packed wire byte k carries dense bits 7k..7k+6, and decoded byte n is dense byte n + 2:
        // wire byte 8 is decoded byte 5, one the store rewrites.
        val rewritten = AxeFx3DumpImage.parse(FakeAxeFx3.mutate(original.raw, chunk = 0, at = 5 + 8))
        assertFalse(original.raw.contentEquals(rewritten.raw))
        assertTrue(original.sameStoredDataAs(rewritten))

        // Wire byte 5 is decoded bytes 2 and 3, which it does not.
        val different = AxeFx3DumpImage.parse(FakeAxeFx3.mutate(original.raw, chunk = 0, at = 5 + 5, xor = 0x7F))
        assertFalse(original.sameStoredDataAs(different))
        assertFalse(original.sameStoredDataAs(AxeFx3DumpImage.parse(FakeAxeFx3.presetImage(7))))
    }

    /** Padding bits are not data: two images differing only there hold the same preset. */
    @Test
    fun `padding bits are not part of the stored data`() {
        val image = AxeFx3DumpImage.parse(FakeAxeFx3.presetImage(3))
        val lastDataByte = AxeFx3SysEx.DUMP_CHUNK_MESSAGE_BYTES - 3
        val cleared = AxeFx3DumpImage.parse(FakeAxeFx3.mutate(image.raw, chunk = 0, at = lastDataByte, xor = 0x05))
        assertFalse(image.raw.contentEquals(cleared.raw))
        assertArrayEquals(image.decodedData, cleared.decodedData)
    }
}
