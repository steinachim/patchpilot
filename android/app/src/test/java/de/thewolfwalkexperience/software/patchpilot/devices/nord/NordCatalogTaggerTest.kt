package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The **shipped** catalog produces a working tagger for every Nord this app profiles.
 *
 * `NordTaggerTest` drives the facet against a transcribed profile, which proves the code; this
 * proves the *data*, against the real `devices/nord_devices.json`. The two halves live in
 * different places in that file - each model's `programCategoryIds` and the family-level
 * `programCategories` master list - and nothing else would notice them drifting apart, which is
 * exactly the failure this catches: `NordCategories.resolve` is all-or-nothing, so one id missing
 * a name silently costs the whole feature rather than one dropdown row.
 */
class NordCatalogTaggerTest {

    private val catalog: DeviceCatalog by lazy {
        val dir = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        Json { ignoreUnknownKeys = true }.decodeFromString(
            DeviceCatalog.serializer(),
            File(dir).resolve("nord_devices.json").readText(),
        )
    }

    @Test
    fun `every profiled model resolves a complete taxonomy from the shipped catalog`() {
        assertTrue("the catalog should profile at least one Nord", catalog.devices.isNotEmpty())
        for (profile in catalog.devices) {
            val categories = NordCategories.resolve(profile, catalog.programCategories)
            assertNotNull("${profile.id} should resolve its categories", categories)
            assertEquals(
                "${profile.id} should name every id it declares",
                profile.programCategoryIds!!.size,
                categories!!.taxonomy.mains.size,
            )
            assertTrue("${profile.id} is flat", categories.taxonomy.isFlat)
        }
    }

    /** What the browser screen actually asks the facet, on a device built the way the app builds it. */
    @Test
    fun `the facet a profiled model exposes is flat, single and favorite-free`() {
        val profile = catalog.devices.first { it.id == "nord_grand" }
        val instrument = NordInstrument(
            NordFixtures.device(ReplayTransport(emptyList()), profile),
            catalog.programCategories,
        )
        val tagger = assertNotNull(instrument.tagger).let { instrument.tagger!! }

        assertEquals(1, tagger.assignmentCount)
        assertNull("a Nord has no favorites", tagger.favorites as FavoriteModel?)
        assertFalse("None is a category, not the absence of one", tagger.allowsUnassigned)
        assertEquals(21, tagger.taxonomy.mains.size)
        assertEquals(NordCategories.NONE, tagger.taxonomy.mains.last().name)
    }

    /** Without the master list there is no facet at all, rather than a partly-named one. */
    @Test
    fun `a device built without the catalog's master list gets no tagger`() {
        val profile = catalog.devices.first { it.id == "nord_grand" }
        val instrument = NordInstrument(NordFixtures.device(ReplayTransport(emptyList()), profile))
        assertNull(instrument.tagger)
    }
}
