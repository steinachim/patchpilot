package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsConfig
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
