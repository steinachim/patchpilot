// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A program's category tag: decoding it out of the item record, and resolving ids to names.
 *
 * **The decode is checked against recordings with an independently known answer.** The `ngp`
 * fixture is `A:1:1` "White Grand"; the protocol notes capture a sub-opcode 51 write on that
 * *same* preset carrying category id 21 for "Grand". So the value asserted here comes from a
 * different message on a different day than the record it is read out of.
 */
class NordCategoryTest {

    private val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)

    /**
     * The item-record payload: the message with its **16-byte** header and 2-byte CRC removed.
     *
     * Four words of header - length, protocol id, protocol version, sub-opcode - and getting this
     * wrong is quiet rather than loud: a 12-byte slice shifts every field by one word, which reads
     * the data size where the tag belongs and so decodes as "this record has no category".
     */
    private fun record(message: ByteArray): ByteArray =
        message.copyOfRange(16, message.size - 2)

    // ---- Decoding ----

    @Test
    fun `a program record's category is the field before its name`() {
        val payload = record(NordFixtures.NGP_RECORD_RESPONSE)
        assertEquals("White Grand", device.parseItemName(payload))
        assertEquals(21, device.parseItemCategory(payload))
    }

    /**
     * **The tag gate, and why it is not paranoia.** Offset 28 is the record's *tag-specific*
     * field: on this sample record it is two 2-byte halves, `(9, 2)`, which read as one 4-byte
     * word is 589826. A decode that skipped the tag check would badge samples with that.
     */
    @Test
    fun `a sample record yields no program category`() {
        val payload = record(NordFixtures.NSMP_RECORD_RESPONSE)
        assertEquals("OrchStrings Legato_KH 3.0", device.parseItemName(payload))
        assertNull(device.parseItemCategory(payload))
    }

    @Test
    fun `a record too short to hold the field yields null rather than throwing`() {
        assertNull(device.parseItemCategory(ByteArray(20)))
    }

    @Test
    fun `collectItemNames carries the category alongside the name`() {
        val item = device.collectItemNames(listOf(record(NordFixtures.NGP_RECORD_RESPONSE))).single()
        assertEquals("A:1:1", item.presetId)
        assertEquals("White Grand", item.name)
        assertEquals(21, item.categoryId)
    }

    // ---- Resolving ----

    private val catalog: DeviceCatalog by lazy {
        // The real shipped catalog, so a name or id this test relies on cannot be one only a
        // fixture has.
        val dir = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        Json { ignoreUnknownKeys = true }.decodeFromString(
            DeviceCatalog.serializer(),
            File(dir).resolve("nord_devices.json").readText(),
        )
    }

    private fun resolved(id: String) =
        NordCategories.resolve(catalog.devices.first { it.id == id }, catalog.programCategories)

    @Test
    fun `the shipped master list is the full 54 entries`() {
        assertEquals(54, catalog.programCategories.size)
        assertEquals("Acoustic", catalog.programCategories["0"])
        assertEquals("None", catalog.programCategories["17"])
    }

    /** Both profiled instruments offer the same 21, and both relabel 23/24. */
    @Test
    fun `each model resolves its own subset, overrides applied`() {
        for (id in listOf("nord_grand", "nord_stage_2ex")) {
            val categories = requireNotNull(resolved(id)) { "$id should resolve its categories" }
            assertEquals("$id should offer 21 categories", 21, categories.taxonomy.mains.size)
            assertEquals("$id relabels 23", "EPiano1", categories.nameOf(23))
            assertEquals("$id relabels 24", "EPiano2", categories.nameOf(24))
            assertEquals("Grand", categories.nameOf(21))
        }
    }

    /**
     * Alphabetical with `None` last, which is the order the vendor's own editor lists categories
     * in - so the app and the vendor's tool show the same list the same way round.
     */
    @Test
    fun `categories are ordered alphabetically with None last`() {
        val names = resolved("nord_grand")!!.taxonomy.mains.map { it.name }
        assertEquals(NordCategories.NONE, names.last())
        val rest = names.dropLast(1)
        assertEquals(rest.sortedBy { it.lowercase() }, rest)
        assertEquals("Acoustic", names.first())
    }

    /**
     * **An index is not a wire id.** A Nord's ids are sparse, so the taxonomy's positions and the
     * bytes sub-opcode 51 carries are different numbers - this is the mapping that keeps them
     * apart, checked in both directions for every category the model offers.
     */
    @Test
    fun `index and wire id round-trip in both directions`() {
        val categories = resolved("nord_grand")!!
        val ids = catalog.devices.first { it.id == "nord_grand" }.programCategoryIds!!
        for (id in ids) {
            val ref = requireNotNull(categories.refOf(id)) { "id $id should resolve" }
            assertEquals("id $id should round-trip", id, categories.idOf(ref))
        }
        // And the two really do differ, or this test would pass on an identity mapping.
        assertTrue(
            "a Nord's ids are sparse, so some index must not equal its id",
            ids.indices.any { categories.idOf(CategoryRef(it, null)) != it },
        )
    }

    /** An id the model does not name is `No Cat` on the instrument, and no badge here. */
    @Test
    fun `an id outside the model's own set resolves to nothing`() {
        val categories = resolved("nord_grand")!!
        assertNull(categories.refOf(9))
        assertNull(categories.nameOf(9))
        assertNull(categories.refOf(53))
    }

    /** No declared ids, no facet - rather than one that can name nothing. */
    @Test
    fun `a profile with no declared ids resolves to null`() {
        assertNull(NordCategories.resolve(NordFixtures.GRAND_PROFILE, catalog.programCategories))
        assertNull(
            NordCategories.resolve(
                catalog.devices.first { it.id == "nord_grand" },
                emptyMap(),
            ),
        )
    }
}
