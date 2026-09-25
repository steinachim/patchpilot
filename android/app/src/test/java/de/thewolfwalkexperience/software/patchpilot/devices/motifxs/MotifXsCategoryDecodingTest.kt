// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.catalog.FamilyCatalog
import kotlinx.serialization.json.Json
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category decoder against recorded dumps.
 *
 * **These are recordings, and the expectations are not the decoder's own arithmetic.** Every
 * figure pair asserted below is anchored to a second channel: the instrument's own `.X0E` backup
 * of the same voices lists the identical `<cat1>:<cat2>:` pair for each of them, and its `0x18`-
 * `0x1B` Common-block bytes read by the documented path agree with the `main * 16 + sub` reading
 * on every voice measured. The label names come from the shipped encoding table, which was
 * measured on the instrument's own panel.
 */
class MotifXsCategoryDecodingTest {

    // The real shipped files, the same way MotifXsFactoryVoicesTest reads them - so a category
    // this test relies on cannot be one only a test fixture has.
    private val catalogDir: File by lazy {
        File(
            System.getProperty("deviceCatalogDir")
                ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        )
    }

    /** The encoding lives in the device catalog: it describes the instrument, not the voice list. */
    private val encoding: MotifXsCategoryEncoding by lazy {
        val family = JSON.decodeFromString(
            FamilyCatalog.serializer(),
            catalogDir.resolve("yamaha_motif_xs.json").readText(),
        )
        val descriptor = family.devices.first { it.id == "yamaha_motif_xs6" }
        requireNotNull(
            resolvedConfig(family.familyConfig, descriptor.familyConfig, JSON).categoryEncoding,
        ) { "the device catalog carries no category encoding" }
    }

    private val taxonomy get() = encoding.taxonomy

    private fun labelsOf(dump: ByteArray): List<String> =
        MotifXsVoice.categoriesOf(MotifXsSysEx.dumpPayload(dump)).orEmpty()
            .mapNotNull { figure -> figure?.let { MotifXsCategories.refOf(it, taxonomy) } }
            .mapNotNull { taxonomy.label(it) }

    /**
     * Two assignments, each with a sub-category: `TWE2 Actor On Ledge` reads `145:226:`, and the
     * instrument's `.X0E` export lists the same pair.
     *
     * The strongest single case available offline: two assignments pin the second figure as a
     * whole second pair rather than as a sub-category of the first.
     */
    @Test
    fun `two assignments decode as two main and sub pairs`() {
        assertEquals(
            listOf("Pads / Warm", "M.EFX / Sweep"),
            labelsOf(MotifXsFixtures.twoAssignmentVoice),
        )
    }

    /** Two more two-assignment voices, at two-digit figure widths: `83:144:` and `83:146:`. */
    @Test
    fun `two-digit figures decode like three-digit ones`() {
        assertEquals(
            listOf("String / Synth", "Pads / Analg"),
            labelsOf(MotifXsFixtures.fullWidthNameVoice),
        )
        assertEquals(
            listOf("S.EFX / SciFi"),
            labelsOf(MotifXsFixtures.longNameVoice),
        )
    }

    /** `TWE2 Ashes` reads `81:256:` - one assignment, the second slot unassigned. */
    @Test
    fun `a single-assignment voice decodes to one category`() {
        assertEquals(listOf("String / Ensem"), labelsOf(MotifXsFixtures.shortPrefixVoice))
        val figures = MotifXsVoice.categoriesOf(MotifXsSysEx.dumpPayload(MotifXsFixtures.shortPrefixVoice))
        assertEquals(listOf(81, null), figures)
    }

    /**
     * An initialized drum kit reads `192:192:`, and 192 is `Dr/Pc` with sub 0.
     *
     * This is the blank the app writes over a deleted drum slot, so what it decodes to is also
     * what a deleted slot's row would show if empty slots ever carried badges.
     */
    @Test
    fun `an initialized drum kit decodes to Dr-Pc Drums twice`() {
        assertEquals(listOf("Dr/Pc / Drums", "Dr/Pc / Drums"), labelsOf(MotifXsFixtures.drumVoice))
    }

    /** `256:256:` is `NoAsg` twice - a voice filed under nothing, which cannot be favorited. */
    @Test
    fun `an unassigned voice decodes to no categories`() {
        assertEquals(emptyList<String>(), labelsOf(MotifXsFixtures.namedVoice))
        val figures = MotifXsVoice.categoriesOf(
            MotifXsSysEx.dumpPayload(MotifXsFixtures.namedVoice),
        )
        assertEquals(listOf(null, null), figures)
    }

    /**
     * `0:0:` is the narrowest figure pair there is, and **both halves are real assignments**.
     *
     * Zero is `Piano / APno`, not an empty field - "unassigned" is 256, and this voice reads it
     * twice. Worth pinning precisely because it looks like an absence: a decoder that treated a
     * falsy figure as "no category" would agree with every other fixture here and get this one
     * wrong, and the row would silently lose a badge the instrument shows.
     */
    @Test
    fun `figure zero is Piano, not an empty field`() {
        assertEquals(
            listOf("Piano / APno", "Piano / APno"),
            labelsOf(MotifXsFixtures.zeroPrefixVoice),
        )
    }

    /** A slot with no name still answers, and its figures decode rather than throwing. */
    @Test
    fun `an empty slot yields figures without a name`() {
        val payload = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice)
        assertNull(MotifXsVoice.nameOf(payload))
        assertNotNull(MotifXsVoice.categoriesOf(payload))
    }

    /**
     * **The rule a fixed sentinel gets wrong.** "No sub-category" is one past the main's last sub,
     * which is 5 for fourteen mains and 4 for `Bass` and `Dr/Pc` - the two with only four subs.
     * No factory voice has a `Bass` or `Dr/Pc` assignment without a sub, so a hard-coded 5 decodes
     * every shipped voice correctly and still mis-reads what the panel writes.
     */
    @Test
    fun `the no-sub value is one past the main's last sub, not a fixed five`() {
        val bass = taxonomy.mains.indexOfFirst { it.name == "Bass" }
        val pads = taxonomy.mains.indexOfFirst { it.name == "Pads" }
        assertEquals(4, taxonomy.mains[bass].subs.size)
        assertEquals(5, taxonomy.mains[pads].subs.size)

        val bassNoSub = bass * MotifXsCategories.SUBS_PER_MAIN + 4
        assertEquals(CategoryRef(bass, null), MotifXsCategories.refOf(bassNoSub, taxonomy))
        // The same byte in a five-sub main is a real sub-category, not an absence.
        val padsFour = pads * MotifXsCategories.SUBS_PER_MAIN + 4
        assertEquals(CategoryRef(pads, 4), MotifXsCategories.refOf(padsFour, taxonomy))
    }

    /** An unassigned slot writes `(16, 0)`; the instrument zeroes the sub and a read-back checks it. */
    @Test
    fun `an unassigned assignment writes a zero sub, not the no-sub sentinel`() {
        assertEquals(16 to 0, MotifXsCategories.bytesOf(null, taxonomy))
        val bass = taxonomy.mains.indexOfFirst { it.name == "Bass" }
        assertEquals(bass to 4, MotifXsCategories.bytesOf(CategoryRef(bass, null), taxonomy))
        assertEquals(bass to 2, MotifXsCategories.bytesOf(CategoryRef(bass, 2), taxonomy))
    }

    /**
     * `Brass` indexes as `Solo, BrsEn, Orche` on the instrument and prints as `Orche, Solo, BrsEn`
     * in Yamaha's voice list. Pinned because reading the order off the list looks right and is
     * wrong, and nothing else in the build would notice it being "corrected".
     */
    @Test
    fun `the measured sub-category order is kept, not the printed one`() {
        val brass = taxonomy.mains.first { it.name == "Brass" }
        assertEquals(listOf("Solo", "BrsEn", "Orche", "Synth", "Arp"), brass.subs)
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
