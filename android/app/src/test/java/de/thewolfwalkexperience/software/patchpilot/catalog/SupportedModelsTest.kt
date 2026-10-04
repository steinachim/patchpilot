// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.nord.DeviceCatalog
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connect screen's supported-instrument list, with siblings of one model on one line. */
class SupportedModelsTest {

    private fun model(name: String, variant: String? = null, family: String = "f") =
        InstrumentDescriptor(
            id = name,
            family = family,
            name = name,
            variant = variant,
            match = DeviceMatch.Usb(vendorId = 1, productId = 1),
        )

    @Test
    fun `siblings share a line, the first in full and the rest by variant`() {
        assertEquals(
            listOf("Yamaha Motif XS6 / 7 / 8"),
            supportedModelLines(
                listOf(
                    model("Yamaha Motif XS8", "8"),
                    model("Yamaha Motif XS6", "6"),
                    model("Yamaha Motif XS7", "7"),
                ),
            ),
        )
    }

    @Test
    fun `a space before the variant does not split the group`() {
        assertEquals(
            listOf("Nord Stage 2 EX / 4"),
            supportedModelLines(listOf(model("Nord Stage 4", "4"), model("Nord Stage 2 EX", "2 EX"))),
        )
    }

    @Test
    fun `a model with no sibling shows its plain name`() {
        assertEquals(listOf("Nord Grand 2"), supportedModelLines(listOf(model("Nord Grand 2", "2"))))
    }

    @Test
    fun `models without a variant are never merged`() {
        assertEquals(
            listOf("Nord Grand", "Nord Grand 2"),
            supportedModelLines(listOf(model("Nord Grand 2", "2"), model("Nord Grand"))),
        )
    }

    /** A catalog mistake costs the grouping, not the line. */
    @Test
    fun `a variant the name does not end with leaves the model on its own line`() {
        assertEquals(
            listOf("Nord Stage 2 EX", "Nord Stage 4"),
            supportedModelLines(listOf(model("Nord Stage 2 EX", "2EX"), model("Nord Stage 4", "4"))),
        )
    }

    @Test
    fun `a variant that is the whole name leaves the model on its own line`() {
        assertEquals(
            listOf("Pro-800", "Pro-900"),
            supportedModelLines(listOf(model("Pro-800", "Pro-800"), model("Pro-900", "Pro-900"))),
        )
    }

    @Test
    fun `the same base in two families stays on two lines`() {
        assertEquals(
            listOf("Synth 1", "Synth 2"),
            supportedModelLines(listOf(model("Synth 1", "1", family = "a"), model("Synth 2", "2", family = "b"))),
        )
    }

    @Test
    fun `lines are sorted, grouped or not`() {
        assertEquals(
            listOf("Behringer Pro-800", "Nord Stage 2 EX / 4", "Yamaha Motif XS6 / 7"),
            supportedModelLines(
                listOf(
                    model("Yamaha Motif XS7", "7", family = "motifxs"),
                    model("Nord Stage 4", "4", family = "nord"),
                    model("Behringer Pro-800", family = "pro800"),
                    model("Yamaha Motif XS6", "6", family = "motifxs"),
                    model("Nord Stage 2 EX", "2 EX", family = "nord"),
                ),
            ),
        )
    }

    // The shipped catalogs, read from `devices/` as CatalogParsesTest does.

    private val json = Json { ignoreUnknownKeys = true }

    private val catalogDir: File by lazy {
        val path = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        File(path).also { assertTrue("Catalog directory $it does not exist", it.isDirectory) }
    }

    /** Every descriptor the app would load: each generic-shaped catalog's, and the Nord file's. */
    private fun shippedDescriptors(): List<InstrumentDescriptor> {
        val generic = catalogDir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".schema.json") }!!
            .map { it to json.parseToJsonElement(it.readText()).jsonObject }
            .filter { (_, root) -> "family" in root && "devices" in root }
            .flatMap { (file, _) -> json.decodeFromString(FamilyCatalog.serializer(), file.readText()).devices }
        val nord = json.decodeFromString(DeviceCatalog.serializer(), catalogDir.resolve("nord_devices.json").readText())
            .devices.map {
                InstrumentDescriptor(
                    id = it.id,
                    family = NordInstrument.FAMILY,
                    name = it.name,
                    variant = it.variant,
                    match = DeviceMatch.Usb(it.vendorId, it.productId),
                )
            }
        return generic + nord
    }

    @Test
    fun `every shipped variant is the trailing part of its name`() {
        shippedDescriptors().filter { it.variant != null }.forEach {
            val variant = it.variant!!
            assertTrue("${it.id}: '${it.name}' does not end with '$variant'", it.name.endsWith(variant))
            assertTrue("${it.id}: '$variant' is the whole name", it.name.removeSuffix(variant).isNotBlank())
        }
    }

    @Test
    fun `the shipped catalog groups the Motif XS and the Nord Stage`() {
        val lines = supportedModelLines(shippedDescriptors())
        assertTrue(lines.toString(), "Yamaha Motif XS6 / 7 / 8" in lines)
        assertTrue(lines.toString(), "Nord Stage 2 EX / 4" in lines)
    }
}
