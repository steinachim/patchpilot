// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Setting a program's category tag: sub-opcode 51/52, and the facet on top of it.
 *
 * Asserts on the **bytes sent**, not just on the operation returning - `ReplayTransport` records
 * every request as a parsed message and already refuses a reply whose sub-opcode is not the
 * request's plus one, so what a test still has to pin is the payload and the surrounding
 * lock/unlock bracket.
 */
class NordTaggerTest {

    /** A Nord Grand with its real 21-category subset, transcribed from `nord_devices.json`. */
    private val profile = NordFixtures.GRAND_PROFILE.copy(
        programCategoryIds = listOf(0, 1, 27, 30, 4, 5, 21, 28, 6, 7, 8, 10, 11, 12, 22, 13, 2, 14, 17, 23, 24),
        programCategoryNameOverrides = mapOf("23" to "EPiano1", "24" to "EPiano2"),
    )

    private val master = mapOf(
        "0" to "Acoustic", "1" to "Bass", "2" to "Wind", "4" to "Fantasy", "5" to "FX",
        "6" to "Lead", "7" to "Organ", "8" to "Pad", "10" to "Guitar/Plucked", "11" to "String",
        "12" to "Synth", "13" to "Vocal", "14" to "User", "17" to "None", "21" to "Grand",
        "22" to "Upright", "23" to "EPiano", "24" to "Wurl", "27" to "Clavinet",
        "28" to "Harpsichord", "30" to "Arpeggio",
    )

    private val categories = NordCategories.resolve(profile, master)!!

    private fun uint32BE(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun readUInt32BE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    /** The sub-opcode 1 reply, with `Program` at index 1 - what getProgramCategoryIndex reads. */
    private fun rootList(): ByteArray {
        val names = listOf("Piano", "Program", "Settings")
        var out = uint32BE(0) + byteArrayOf(names.size.toByte())
        for (name in names) {
            out += uint32BE(name.length) + name.toByteArray(Charsets.US_ASCII) + ByteArray(29)
        }
        return out
    }

    // ---- The wire ----

    @Test
    fun `setPresetCategory sends sub-op 51 with bank, item and id`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to rootList(), // ROOT_CATEGORY_LIST, via getProgramCategoryIndex
                5 to ByteArray(0), // SELECT_CATEGORY - locks the instrument
                52 to (uint32BE(0) + uint32BE(1) + uint32BE(11)), // SET_CATEGORY echo
                48 to (uint32BE(0) + uint32BE(1) + uint32BE(11)), // the display re-select
                7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
            ),
        )
        val device = NordFixtures.device(transport, profile)
        device.setPresetCategory(bank = 1, item = 11, categoryId = 22)

        val sent = transport.sentRequests.first {
            it.subOp == NordDevice.FileTransferSubOp.SET_CATEGORY.code
        }
        assertEquals("three 4-byte words and nothing else", 12, sent.payload.size)
        assertEquals(1, readUInt32BE(sent.payload, 0))
        assertEquals(11, readUInt32BE(sent.payload, 4))
        assertEquals(22, readUInt32BE(sent.payload, 8))
    }

    /**
     * The write sits inside the lock, and the unlock is what lets the instrument be played again -
     * so it has to be the last thing sent whatever happens in between.
     */
    @Test
    fun `the write is bracketed by the category select and the unlock`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                52 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
                48 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
                7 to ByteArray(0),
            ),
        )
        val device = NordFixtures.device(transport, profile)
        device.setPresetCategory(0, 0, 21)

        val ops = transport.sentRequests.map { it.subOp }
        val select = ops.indexOf(NordDevice.FileTransferSubOp.SELECT_CATEGORY.code)
        val set = ops.indexOf(NordDevice.FileTransferSubOp.SET_CATEGORY.code)
        val reselect = ops.indexOf(NordDevice.FileTransferSubOp.SELECT_PRESET.code)
        assertTrue("select before set", select in 0 until set)
        assertTrue("display re-select after the write", set < reselect)
        assertEquals(
            NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code,
            transport.sentRequests.last().subOp,
        )
    }

    /** A mismatched echo means the instrument acted on a different slot - and it still unlocks. */
    @Test
    fun `a mismatched echo fails the write but still unlocks`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                52 to (uint32BE(0) + uint32BE(1) + uint32BE(12)), // wrong item echoed back
                7 to ByteArray(0),
            ),
        )
        val device = NordFixtures.device(transport, profile)
        try {
            device.setPresetCategory(1, 11, 22)
            fail("a mismatched echo should be refused")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(
            NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code,
            transport.sentRequests.last().subOp,
        )
    }

    @Test
    fun `a non-zero status fails the write`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                52 to (uint32BE(2) + uint32BE(1) + uint32BE(11)), // status 2 = refused
                7 to ByteArray(0),
            ),
        )
        val device = NordFixtures.device(transport, profile)
        try {
            device.setPresetCategory(1, 11, 22)
            fail("a non-zero status should be refused")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    // ---- The facet ----

    @Test
    fun `the facet declares one flat assignment, no favorites and no clearing`() {
        val tagger = NordTagger(NordFixtures.device(ReplayTransport(emptyList()), profile), categories)
        assertEquals(1, tagger.assignmentCount)
        assertNull("a Nord has no favorites", tagger.favorites)
        assertTrue("no sub-categories", tagger.taxonomy.isFlat)
        assertFalse("None is a category here, not the absence of one", tagger.allowsUnassigned)
        assertTrue(tagger.canSetCategories(SlotAddress(0, 0)))
        assertFalse(tagger.canSetFavorite(SlotAddress(0, 0)))
    }

    /** There is no id meaning "no category", so clearing is refused rather than guessed at. */
    @Test
    fun `clearing a category is refused`() = runTest {
        val tagger = NordTagger(NordFixtures.device(ReplayTransport(emptyList()), profile), categories)
        try {
            tagger.setCategories(SlotAddress(0, 0), listOf(null))
            fail("a Nord cannot store an absent category")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `an index outside the taxonomy is refused`() = runTest {
        val tagger = NordTagger(NordFixtures.device(ReplayTransport(emptyList()), profile), categories)
        try {
            tagger.setCategories(SlotAddress(0, 0), listOf(CategoryRef(99, null)))
            fail("index 99 names no category")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * The facet writes the **wire id**, not the taxonomy index it was handed.
     *
     * This is the mistake that would go wrong silently: the ids are sparse, so an index and an id
     * are different numbers, and writing the index would tag the program with somebody else's
     * category and report success.
     */
    @Test
    fun `the facet writes the wire id rather than the taxonomy index`() = runTest {
        val grand = categories.refOf(21)!!
        assertEquals("Grand", categories.taxonomy.label(grand))
        assertTrue("index and id must differ, or this proves nothing", grand.main != 21)

        val transport = ReplayTransport(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                52 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
                48 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
                7 to ByteArray(0),
                // The read-back verification walks the program list, which re-asks for the
                // category index first - getProgramCategoryIndex() queries every time.
                1 to rootList(), // ROOT_CATEGORY_LIST
                3 to childList(banks = 1), // GET_CATEGORY_CHILD, bounds the walk
                5 to ByteArray(0), // SELECT_CATEGORY
                9 to (uint32BE(0) + uint32BE(1)), // GET_ITEM_COUNT -> one item
                33 to (uint32BE(0) + uint32BE(0) + uint32BE(0)), // CURSOR_NEXT_ITEM -> item 0
                31 to itemRecord(bank = 0, item = 0, categoryId = 21, name = "White Grand"),
                7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
            ),
        )
        val device = NordFixtures.device(transport, profile)
        NordTagger(device, categories).setCategories(SlotAddress(0, 0), listOf(grand))

        val sent = transport.sentRequests.first {
            it.subOp == NordDevice.FileTransferSubOp.SET_CATEGORY.code
        }
        assertEquals(21, readUInt32BE(sent.payload, 8))
    }

    /**
     * A sub-opcode 2/3 reply listing [banks] banks of 25 slots - what bounds the item walk.
     *
     * `status(4) | categoryIndex(4) | childCount(1) | [ nameLen(4) | name | capacity(4) ]*`
     */
    private fun childList(banks: Int): ByteArray {
        var out = uint32BE(0) + uint32BE(1) + byteArrayOf(banks.toByte())
        repeat(banks) {
            val name = "Bank".toByteArray(Charsets.US_ASCII)
            out += uint32BE(name.size) + name + uint32BE(25)
        }
        return out
    }

    /**
     * A minimal `ngp` item record: the fields the parser reads, at the offsets it reads them.
     *
     * The content tag is spelled out as bytes rather than as a string, because it is NUL-padded -
     * `6e 67 70 00` - and a NUL inside a source literal is invisible to anyone reading it.
     */
    private fun itemRecord(bank: Int, item: Int, categoryId: Int, name: String): ByteArray {
        val text = name.toByteArray(Charsets.US_ASCII)
        val ngp = byteArrayOf(0x6E, 0x67, 0x70, 0x00)
        return uint32BE(0) + uint32BE(bank) + uint32BE(item) + uint32BE(0) +
            ngp + uint32BE(101) + uint32BE(0) + uint32BE(categoryId) +
            uint32BE(text.size) + text + ByteArray(12)
    }
}
