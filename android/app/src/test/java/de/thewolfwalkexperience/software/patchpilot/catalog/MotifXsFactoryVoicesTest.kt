// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsConfig
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsCategories
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsCategoryEncoding
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsFactoryVoices
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsVoice
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.resolvedConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped factory voice-name table, read from `devices/` in the repository.
 *
 * These 1,217 names are the one thing in the app that is **transcribed rather than measured** -
 * they come from Yamaha's Data List spreadsheets, not from any instrument this project has talked
 * to. Nothing downstream can notice a wrong one: an unnamed row is visible, but a row with the
 * wrong name looks exactly like a right one. So what can be checked mechanically is checked here,
 * and the rest is a bench job (see the browser's own note that these are not read from the wire).
 *
 * The load-bearing test is [`the table and the catalog agree on every read-only bank`]: the table
 * and the bank list are two files that have to describe the same eleven banks, and nothing else
 * would notice them drifting apart.
 */
class MotifXsFactoryVoicesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val catalogDir: File by lazy {
        val path = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        File(path).also { assertTrue("Catalog directory $it does not exist", it.isDirectory) }
    }

    private val table: MotifXsFactoryVoices by lazy {
        json.decodeFromString(
            MotifXsFactoryVoices.serializer(),
            catalogDir.resolve("motifxs_factory_voices.json").readText(),
        )
    }

    /** The family's category encoding, resolved the way the app resolves it. */
    private fun encoding(): MotifXsCategoryEncoding? = motifXs6Config().categoryEncoding

    private fun motifXs6Config(): MotifXsConfig {
        val catalog = json.decodeFromString(
            FamilyCatalog.serializer(),
            catalogDir.resolve("yamaha_motif_xs.json").readText(),
        )
        val descriptor = catalog.devices.single { it.id == "yamaha_motif_xs6" }
        return resolvedConfig(catalog.familyConfig, descriptor.familyConfig, json)
    }

    @Test
    fun `the table carries every factory voice`() {
        assertEquals(11, table.banks.size)
        assertEquals(1217, table.banks.sumOf { it.voices.size })
    }

    /**
     * The category encoding is in the **device catalog**, not beside the voice names.
     *
     * It describes the instrument's format rather than the shipped list - a user voice is filed
     * under the same categories a factory one is - so it belongs to `yamaha_motif_xs.json`.
     *
     * `NoAsg` has to be main **16**, because an unassigned slot serialises as the figure 256 in a
     * voice dump and 256 is `16 * 16`. A table that listed fewer mains would decode every voice on
     * the instrument one category to the left.
     */
    @Test
    fun `the device catalog carries the category encoding`() {
        val encoding = requireNotNull(encoding())
        assertEquals(17, encoding.mainByValue.size)
        assertEquals(
            MotifXsCategoryEncoding.NO_ASSIGNMENT,
            encoding.mainByValue[MotifXsCategories.NO_ASSIGNMENT_MAIN],
        )
        assertEquals(17, encoding.taxonomy.mains.size)
    }

    /** Every model of this family inherits it; none of the three carries its own copy. */
    @Test
    fun `every Motif XS model resolves the same encoding`() {
        val catalog = json.decodeFromString(
            FamilyCatalog.serializer(),
            catalogDir.resolve("yamaha_motif_xs.json").readText(),
        )
        for (descriptor in catalog.devices) {
            val config = resolvedConfig(catalog.familyConfig, descriptor.familyConfig, json)
            assertEquals(
                "${descriptor.id} should resolve the family's category encoding",
                encoding(), config.categoryEncoding,
            )
        }
    }

    /**
     * **Two mains have four sub-categories, not five**, which is what makes "no sub-category" a
     * rule rather than a constant: it is one past the main's last sub, so 4 for these and 5 for
     * the other fourteen. No factory voice has a `Bass` or `Dr/Pc` assignment without a sub, so
     * nothing else in this build would notice the difference.
     */
    @Test
    fun `Bass and Dr Pc have four sub-categories where the rest have five`() {
        val taxonomy = requireNotNull(encoding()).taxonomy
        val short = taxonomy.mains.filter { it.subs.size == 4 }.map { it.name }
        assertEquals(listOf("Bass", "Dr/Pc"), short)
        val full = taxonomy.mains.filter { it.subs.isNotEmpty() && it.subs.size != 4 }
        assertTrue("every other main with subs has five", full.all { it.subs.size == 5 })
    }

    /**
     * The sub-category order is a hardware measurement, and Yamaha's own voice list disagrees
     * with it: `Brass` prints as `Orche, Solo, BrsEn` and indexes as `Solo, BrsEn, Orche`.
     * Pinned because "correcting" it to match the printed list looks right and is wrong.
     */
    @Test
    fun `the measured sub-category order is kept, not the printed one`() {
        val brass = requireNotNull(encoding()).taxonomy.mains.first { it.name == "Brass" }
        assertEquals(listOf("Solo", "BrsEn", "Orche", "Synth", "Arp"), brass.subs)
    }

    /**
     * Every factory voice carries a category, and every category names something the encoding
     * lists.
     *
     * The two drum banks are the ones worth stating: Yamaha publishes no drum categories at all,
     * so `PREDR` and `GMDR` were read off the instrument rather than transcribed from a voice
     * list. An empty bank here would mean that read was lost in a catalog re-sync.
     */
    @Test
    fun `every factory voice has a category, and every category resolves`() {
        val taxonomy = requireNotNull(encoding()).taxonomy
        val withoutCategories = table.banks.filter { bank ->
            bank.voices.any { table.categories(bank.label, it.slot - 1).isEmpty() }
        }.map { it.label }
        assertEquals(emptyList<String>(), withoutCategories)

        for (bank in table.banks) {
            for (voice in bank.voices) {
                val listed = voice.categories.orEmpty()
                val resolved = MotifXsCategories.refsOf(listed, encoding())
                assertEquals(
                    "${bank.label}:${voice.slot} ${voice.name} has an unresolvable category",
                    listed.size, resolved.size,
                )
                assertTrue(resolved.all { taxonomy.label(it) != null })
            }
        }
    }

    /**
     * **The two files have to describe the same banks, in the same order, at the same sizes.**
     *
     * A bank renamed in the catalog and not here would list as 128 unnamed rows; one whose slot
     * count grew here and not there would carry names nothing can reach. Neither shows up anywhere
     * else, because the lookup is by label and a miss is indistinguishable from an empty slot.
     */
    @Test
    fun `the table and the catalog agree on every read-only bank`() {
        val catalogBanks = motifXs6Config().banks.filter { it.readOnly }
        assertEquals(catalogBanks.map { it.label }, table.banks.map { it.label })
        assertEquals(catalogBanks.map { it.slotCount }, table.banks.map { it.slotCount })
    }

    /** Dense 1..slotCount in every bank: no gaps, no duplicates, nothing out of range. */
    @Test
    fun `every bank numbers its slots from one with no gaps`() {
        for (bank in table.banks) {
            assertEquals(
                "${bank.label} does not number 1..${bank.slotCount} exactly once",
                (1..bank.slotCount).toList(),
                bank.voices.map { it.slot }.sorted(),
            )
        }
    }

    /**
     * Every name is real and fits the instrument's own field.
     *
     * Twenty characters is `MotifXsVoice.NAME_LENGTH`, the width the instrument stores a voice name
     * in - so a longer entry here would be one the instrument could not be showing, which means the
     * transcription is wrong rather than the instrument being surprising.
     */
    @Test
    fun `every name is non-blank and fits the instrument's name field`() {
        val voices = table.banks.flatMap { bank -> bank.voices.map { bank.label to it } }
        assertTrue(voices.none { it.second.name.isBlank() })
        val tooLong = voices.filter { it.second.name.length > MotifXsVoice.NAME_LENGTH }
        assertTrue("names longer than the instrument's field: $tooLong", tooLong.isEmpty())
    }

    /**
     * One value pinned against something read off real hardware.
     *
     * `PRE1 - A:04` reads `Glasgow` on the instrument's own panel; it is the worked example the
     * protocol notes use for voice-name decoding. It is a single point of contact between this
     * transcribed table and a thing somebody actually saw, which is worth more than its size:
     * a table generated from the wrong spreadsheet, or off by one slot, fails here.
     */
    @Test
    fun `PRE1 slot 4 is the voice the protocol notes recorded from hardware`() {
        assertEquals("Glasgow", table.name("PRE1", 3))
    }

    /** The first and last of the two banks whose sizes are unusual, so an off-by-one shows up. */
    @Test
    fun `the drum banks are the documented sizes`() {
        val byLabel = table.banks.associateBy { it.label }
        assertEquals(64, byLabel.getValue("PREDR").slotCount)
        assertEquals(1, byLabel.getValue("GMDR").slotCount)
        assertTrue(table.name("PREDR", 63) != null)
        assertTrue(table.name("GMDR", 0) != null)
        assertTrue("GM DR holds exactly one kit", table.name("GMDR", 1) == null)
    }
}
