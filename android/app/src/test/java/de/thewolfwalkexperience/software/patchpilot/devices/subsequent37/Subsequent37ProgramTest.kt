// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Subsequent37ProgramTest {

    private fun program(number: Int) = Subsequent37Program(Subsequent37Fixtures.unpackBlock(number))

    @Test
    fun `names, emptiness and categories read straight off the dump`() {
        assertEquals("Preset 000", program(0).name)
        assertEquals(0x0F, program(0).category) // Evolving
        assertEquals("Preset 001", program(1).name)
        assertEquals(0x0C, program(1).category) // Sync
        val empty = (0 until 256).count { program(it).isEmpty }
        assertEquals(Subsequent37Fixtures.EMPTY_COUNT, empty)
        assertTrue(Subsequent37Program(Subsequent37Fixtures.BLANK).isEmpty)
        assertEquals(Subsequent37Categories.UNSET, Subsequent37Program(Subsequent37Fixtures.BLANK).category)
    }

    /** The vendor editor's rename appended `Test` after the first 12-character line and changed nothing else. */
    @Test
    fun `a rename is the editor's own, byte for byte`() {
        val renamed = program(0).withName("Preset 000  Test")
        assertArrayEquals(Subsequent37SysEx.programOf(Subsequent37Fixtures.WRITE_RENAME), renamed.payload)
        assertEquals("Preset 000  Test", renamed.name)
    }

    @Test
    fun `a name the field cannot hold is refused rather than cut`() {
        for (bad in listOf("x".repeat(25), "Café", "tab\there")) {
            try {
                program(0).withName(bad)
                fail("'$bad' was accepted")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals("x".repeat(24), program(0).withName("x".repeat(24)).name)
        assertEquals("BRASH B@SS ~^[]", program(0).withName("BRASH B@SS ~^[]").name)
    }

    @Test
    fun `a category change moves byte 50 and the checksum and nothing else`() {
        val before = program(1).payload
        val after = program(1).withCategory(0x11).payload
        val moved = before.indices.filter { before[it] != after[it] }
        assertEquals(50, moved.first())
        assertTrue(moved.drop(1).all { it >= 486 })
        assertEquals(0x11, after[50].toInt())
        assertTrue(Subsequent37SysEx.checksumCloses(after))
        assertEquals(Subsequent37Categories.UNSET, program(1).withCategory(Subsequent37Categories.UNSET).category)
        assertFalse(runCatching { program(1).withCategory(22) }.isSuccess)
    }

    @Test
    fun `categories run in the panel's order and count from its end`() {
        assertEquals(22, Subsequent37Categories.PANEL_ORDER.size)
        assertEquals("User", Subsequent37Categories.PANEL_ORDER.first())
        assertEquals("Misc", Subsequent37Categories.PANEL_ORDER.last())
        assertEquals("Vox", Subsequent37Categories.nameOf(0x11))
        assertEquals("Misc", Subsequent37Categories.nameOf(0x00))
        assertEquals("User", Subsequent37Categories.nameOf(21))
        assertNull(Subsequent37Categories.nameOf(Subsequent37Categories.UNSET))
        assertNull(Subsequent37Categories.nameOf(0x40))
        for (index in Subsequent37Categories.PANEL_ORDER.indices) {
            assertEquals(index, Subsequent37Categories.panelIndexOf(Subsequent37Categories.valueOf(index)))
        }
    }

    // ---- The bulk-dump decoder ----

    private fun decodeIn(
        pieces: Int,
        dump: ByteArray = Subsequent37Fixtures.BULK_DUMP,
    ): Pair<List<Subsequent37Program>, Subsequent37BulkDecoder> {
        val decoder = Subsequent37BulkDecoder()
        val out = mutableListOf<Subsequent37Program>()
        var at = 0
        while (at < dump.size) {
            val end = minOf(at + pieces, dump.size)
            out += decoder.feed(dump.copyOfRange(at, end))
            at = end
        }
        return out to decoder
    }

    @Test
    fun `the whole dump decodes the same in any chunking`() {
        for (piece in listOf(1, 7, 1_435, 203_800)) {
            val (programs, decoder) = decodeIn(piece)
            assertEquals("pieces of $piece", 256, programs.size)
            assertNull(decoder.problemAtEnd())
            for (n in 0 until 256) assertArrayEquals(Subsequent37Fixtures.unpackBlock(n), programs[n].payload)
        }
    }

    /** A block is whole once its 732 packed bytes are in: the separator after it is not needed. */
    @Test
    fun `a program is returned as soon as its own records are in`() {
        val decoder = Subsequent37BulkDecoder()
        val dump = Subsequent37Fixtures.BULK_DUMP
        assertTrue(decoder.feed(dump.copyOfRange(0, 23 + 731)).isEmpty())
        assertEquals(1, decoder.feed(dump.copyOfRange(23 + 731, 23 + 732)).size)
    }

    /** A dump that lost bytes still ends in F7; nothing past the loss may be trusted, even a block that happens to pass. */
    @Test
    fun `decoding stops at the first damaged block`() {
        val damaged = Subsequent37Fixtures.BULK_DUMP.copyOf()
        damaged[23 + 5 * 796 + 100] = (damaged[23 + 5 * 796 + 100].toInt() xor 0x01).toByte()
        val (programs, decoder) = decodeIn(1_435, damaged)
        assertEquals(5, programs.size)
        assertEquals(5, decoder.brokenAt)
        assertEquals("program 5 arrived damaged", decoder.problemAtEnd())
    }

    @Test
    fun `a dump with bytes missing decodes up to the loss`() {
        val dump = Subsequent37Fixtures.BULK_DUMP
        // The measured shape of a loss: bytes vanish mid-stream, the message still ends in F7.
        val lossy = dump.copyOfRange(0, 5_399) + dump.copyOfRange(5_399 + 173, dump.size)
        val (programs, decoder) = decodeIn(1_435, lossy)
        assertEquals(6, programs.size)
        assertEquals(6, decoder.brokenAt)
    }

    @Test
    fun `something that is not a bulk dump decodes nothing`() {
        val (programs, decoder) = decodeIn(100, Subsequent37Fixtures.READ_P001[1])
        assertTrue(programs.isEmpty())
        assertEquals("the reply is not a bulk dump", decoder.problemAtEnd())
    }

    // ---- Settings ----

    @Test
    fun `the settings block names the selected program and both channels`() {
        val first = Subsequent37Settings.parse(Subsequent37Fixtures.SETTINGS_IN01_OUT16)!!
        assertEquals(153, first.selectedProgram)
        assertEquals(0, first.inChannel)
        assertEquals(15, first.outChannel)
        assertEquals(0, first.selectionChannel)
        assertEquals("1", first.inChannelLabel)
        assertEquals(268, first.words.size)

        val second = Subsequent37Settings.parse(Subsequent37Fixtures.SETTINGS_IN03_OUT14)!!
        assertEquals(2, second.selectionChannel)
        assertEquals(13, second.outChannel)
        assertEquals("3", second.inChannelLabel)
    }

    /** Once in 102 replies the block arrived 12 bytes short; it must not be decoded. */
    @Test
    fun `a short settings block is discarded`() {
        val whole = Subsequent37Fixtures.SETTINGS_IN01_OUT16
        assertNull(Subsequent37Settings.parse(whole.copyOfRange(0, 815) + byteArrayOf(0xF7.toByte())))
    }

    @Test
    fun `an IN channel of ALL selects on channel 1`() {
        val all = settingsWithInChannel(Subsequent37Settings.IN_CHANNEL_ALL)
        assertEquals(0, all.selectionChannel)
        assertEquals("ALL", all.inChannelLabel)
    }

    companion object {
        /** The captured block with word 30 changed, re-packed test-side. */
        fun settingsMessageWithInChannel(channel: Int): ByteArray {
            val message = Subsequent37Fixtures.SETTINGS_IN01_OUT16.copyOf()
            val at = 23 + 30 * 3
            byteArrayOf(0x40, (channel shr 6).toByte(), (channel and 0x3F).toByte()).copyInto(message, at)
            return message
        }

        fun settingsWithInChannel(channel: Int): Subsequent37Settings =
            Subsequent37Settings.parse(settingsMessageWithInChannel(channel))!!
    }
}
