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
 * The category decoder against recorded dumps, checked against the shipped factory catalog.
 *
 * **These are recordings, and the expectations are not the decoder's own arithmetic.** Every
 * assertion here is anchored either to a value the *instrument* produced, or to what Yamaha's own
 * voice list says about a factory voice of the same name - never to a figure this test computed
 * with the same formula the code uses, which would only prove the formula agrees with itself.
 *
 * The evidence that the `0C` figure pair really is the category pair: 205 of the user voices in
 * this project's full-sync capture share a name with a factory voice, and all 205 decode to
 * exactly the categories the catalog lists for it. Two of those voices are fixtures here.
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

    private val catalog: MotifXsFactoryVoices by lazy {
        JSON.decodeFromString(
            MotifXsFactoryVoices.serializer(),
            catalogDir.resolve("motifxs_factory_voices.json").readText(),
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

    /**
     * The categories the shipped table gives the factory voice of this name.
     *
     * **By name, not by slot.** A user voice is a copy of a factory one and keeps its categories,
     * but it does not keep its slot number - which is the mistake this helper exists to prevent.
     */
    private fun factoryLabelsOf(name: String): List<String> {
        val bank = catalog.banks.firstOrNull { bank ->
            bank.voices.any { it.name == name && it.categories != null }
        } ?: error("no factory voice named $name carries categories")
        val slot0 = bank.voices.first { it.name == name }.slot - 1
        return MotifXsCategories.refsOf(catalog.categories(bank.label, slot0), encoding)
            .mapNotNull { taxonomy.label(it) }
    }

    private fun labelsOf(dump: ByteArray): List<String> =
        MotifXsVoice.categoriesOf(MotifXsSysEx.dumpPayload(dump)).orEmpty()
            .mapNotNull { figure -> figure?.let { MotifXsCategories.refOf(it, taxonomy) } }
            .mapNotNull { taxonomy.label(it) }

    /**
     * `New Stab` is a copy of PRE7's, and a copy keeps its categories.
     *
     * The strongest single case available offline: it is the one fixture with **two** assignments,
     * so it pins the second figure as a whole second pair rather than as a sub-category of the
     * first - which is what the file-format notes had it recorded as.
     */
    @Test
    fun `a copied factory voice decodes to the factory voice's own categories`() {
        assertEquals(listOf("M.EFX / Hit", "Pads / Brite"), factoryLabelsOf("New Stab"))
        assertEquals(
            factoryLabelsOf("New Stab"),
            labelsOf(MotifXsFixtures.genuineTrailingBVoice),
        )
    }

    /** Two more copies, both with a second assignment, at three different figure widths. */
    @Test
    fun `more copied factory voices agree with the shipped table`() {
        assertEquals(listOf("Ethnic / Bowed", "SaxWW / Flute"), factoryLabelsOf("Kawala"))
        assertEquals(factoryLabelsOf("Kawala"), labelsOf(MotifXsFixtures.kawalaVoice))

        assertEquals(
            listOf("CPerc / Bell", "CPerc / PDrum"),
            factoryLabelsOf("Timpani/Bell/Glocken"),
        )
        assertEquals(
            factoryLabelsOf("Timpani/Bell/Glocken"),
            labelsOf(MotifXsFixtures.fullWidthNameVoice),
        )
    }

    /** `Dyno Straight MW+AS2`, a copy of a PRE1 voice with a single assignment. */
    @Test
    fun `a single-assignment voice decodes to one category`() {
        assertEquals(listOf("Keys / EP"), factoryLabelsOf("Dyno Straight MW+AS2"))
        assertEquals(
            factoryLabelsOf("Dyno Straight MW+AS2"),
            labelsOf(MotifXsFixtures.shortPrefixVoice),
        )
    }

    /**
     * A drum kit carries categories even though no published table lists them.
     *
     * `Power Standard Kit 1` reads `192:256:`, and 192 is `Dr/Pc` with sub 0. The catalog has
     * nothing to check this against - which is exactly the gap - so the assertion is against the
     * recorded bytes and the hardware-measured encoding, and it is why the drum banks show no
     * badge rather than a guessed one.
     */
    @Test
    fun `a drum kit decodes even though the shipped table lists none`() {
        assertEquals(listOf("Dr/Pc / Drums"), labelsOf(MotifXsFixtures.drumVoice))
        assertTrue(
            "the shipped table has no drum categories to check that against",
            catalog.categories("PREDR", slot0 = 0).isEmpty(),
        )
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
