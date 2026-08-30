package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsConfig
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.resolvedConfig
import de.thewolfwalkexperience.software.patchpilot.devices.pro800.Pro800Config
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shipped catalog files, decoded by the same serializers the app uses.
 *
 * **These read the catalog files in `devices/` from the repository, not a copy.** A catalog is the one part of a
 * family that no other test touches: the JVM tests build their instruments from hand-made configs,
 * and the assets the app actually loads are generated at build time. So a typo in a catalog is
 * invisible until a phone silently fails to list the instrument - which is a poor place to learn
 * it, and an especially poor one for a family that cannot be tested on hardware at all.
 *
 * The Nord catalog has its own native shape and its own tests; only the generic-shaped ones are
 * checked here.
 */
class CatalogParsesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val catalogDir: File by lazy {
        val path = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        File(path).also { assertTrue("Catalog directory $it does not exist", it.isDirectory) }
    }

    private fun catalog(name: String): FamilyCatalog =
        json.decodeFromString(FamilyCatalog.serializer(), catalogDir.resolve(name).readText())

    /** The XS6 entry specifically - the family's other tests below are shape checks shared by
     * XS6/7/8, and pin their assertions to the model that was actually captured. */
    private fun motifXs6(): InstrumentDescriptor =
        catalog("yamaha_motif_xs.json").devices.single { it.id == "yamaha_motif_xs6" }

    /** XS6's resolved config - banks included, whichever of the device entry or the catalog's
     * shared familyConfig they actually came from. See [resolvedConfig]. */
    private fun motifXs6Config(): MotifXsConfig {
        val catalog = catalog("yamaha_motif_xs.json")
        val descriptor = catalog.devices.single { it.id == "yamaha_motif_xs6" }
        return resolvedConfig(catalog.familyConfig, descriptor.familyConfig, json)
    }

    @Test
    fun `the Pro-800 catalog decodes into its family config`() {
        val catalog = catalog("behringer_pro800.json")
        assertEquals("pro800", catalog.family)
        val descriptor = catalog.devices.single()
        assertTrue(descriptor.match is DeviceMatch.MidiIdentity)
        val config = json.decodeFromJsonElement(Pro800Config.serializer(), descriptor.familyConfig)
        assertEquals(4, config.bankCount)
        assertEquals(100, config.slotsPerBank)
    }

    @Test
    fun `the Motif XS catalog decodes into its family config`() {
        val catalog = catalog("yamaha_motif_xs.json")
        assertEquals("motifxs", catalog.family)
        val config = motifXs6Config()

        // The four **user** banks. The instrument has eleven factory banks too, fully mapped, but
        // they are deliberately not in the app's catalog yet: nothing calls
        // MotifXsInstrument.indexBank, so listing them made the browser show
        // 1,217 rows of "--- EMPTY ---" for voices that are really there. Better absent than
        // present and wrong. Restore them together with the UI that fetches them on demand.
        assertEquals(listOf("USR1", "USR2", "USR3", "DRUM"), config.banks.map { it.label })
        assertTrue(config.banks.all { it.addressHi == 0x0C })
        assertEquals(416, config.banks.sumOf { it.slotCount })

        val bySize = config.banks.associate { it.label to it.slotCount }
        assertEquals(128, bySize["USR1"])
        assertEquals(32, bySize["DRUM"])   // USER DR: groups A-B only

        val byAddress = config.banks.associate { it.label to it.addressMid }
        assertEquals(0x0A, byAddress["USR1"])
        assertEquals(0x0B, byAddress["USR2"])
        assertEquals(0x0C, byAddress["USR3"])
        assertEquals(0x28, byAddress["DRUM"])
    }

    /**
     * Three labels per bank, because three places need three different widths.
     *
     * `label` is the internal shorthand used to address a bank;
     * `displayLabel` is what the instrument's own panel says, and what the browser's headers and
     * row ids show; `shortLabel` is what fits the index rail, where `USER DR` wraps to
     * three unreadable stacked lines at the narrower width this used to be.
     */
    @Test
    fun `each bank carries a panel label and a rail label`() {
        val config = motifXs6Config()
        assertEquals(
            listOf("USER 1", "USER 2", "USER 3", "USER DR"),
            config.banks.map { it.displayLabel },
        )
        assertEquals(listOf("U1", "U2", "U3", "U DR"), config.banks.map { it.shortLabel })
        // "USER DR" -> "U DR", not "UD": no rule that yields one yields the other, which is why
        // the rail label is data rather than derived.
        assertTrue(config.banks.all { it.shortLabel.length <= 4 })
    }

    /**
     * Every catalogued bank is walked, because only the user banks are catalogued.
     *
     * The flag still exists and [MotifXsInstrument.indexBank] still works - both are covered in
     * MotifXsInstrumentTest against a config that sets them - so restoring the factory banks is
     * a catalog edit plus the UI that opens them, not a rewrite.
     */
    @Test
    fun `every catalogued bank is indexed by default`() {
        val config = motifXs6Config()
        assertTrue(config.banks.all { it.indexByDefault })
        assertTrue(config.banks.none { it.readOnly })
    }

    /**
     * The Motif XS is found on the **USB host bus**, not through `MidiManager`.
     *
     * It used to be a `midiIdentity` match on port 3, on the assumption that a class-compliant
     * host would expose one port per cable. The instrument declares no MIDIStreaming interface at
     * all - one vendor-specific interface, class 0xFF - so `MidiManager` never enumerates it and
     * there is no port to match. The bulk endpoints do carry ordinary USB-MIDI event packets,
     * which is why the family still speaks SysEx over them.
     *
     * The cable is asserted because losing it in an edit is easy and diagnosing that is not.
     * Cable 0 works as well as cable 3 on this instrument, so a fallback to 0
     * would not fail loudly - but cables 4-7 answer on 3 rather than on themselves, and 1, 2 and
     * 8-15 are silent, so most wrong values produce an instrument that connects and browses
     * nothing. Three is what the vendor's own editor used.
     */
    @Test
    fun `the Motif XS is matched on the USB bus, on cable 3`() {
        val match = motifXs6().match as DeviceMatch.Usb
        assertEquals(0x0499, match.vendorId)
        assertEquals(0x1042, match.productId)
        assertEquals(3, match.midiCable)
        // Bulk OUT is 0x01 here, not the Nord vendor interface's 0x03.
        assertEquals(0x01, match.endpointOut)
        assertEquals(0x82, match.endpointIn)
    }

    /**
     * The XS7 and XS8 sit at the two product ids adjacent to the confirmed XS6 - `0x1043`/`0x1044`
     * next to `0x1042` - per Yamaha's own Windows USB-MIDI driver package (structurally identical
     * `yum1043.inf`/`yum1044.inf` beside the confirmed `yum1042.inf`) and its precedent of
     * assigning sequential product ids by keybed size across the two prior Motif generations. The
     * shared "MOTIF XS6/7/8 MIDI Implementation Chart" documents an identical SysEx protocol for
     * all three - they differ only in the Identity Reply's family member code, which this app does
     * not use to distinguish models (see docs/PROTOCOLS.md) - so the catalog carries the same bank
     * map and endpoints as the XS6, changing only id, name, and USB product id.
     */
    @Test
    fun `the XS7 and XS8 are matched on the product ids adjacent to the confirmed XS6`() {
        val catalog = catalog("yamaha_motif_xs.json")
        val devices = catalog.devices
        assertEquals(
            listOf("yamaha_motif_xs6", "yamaha_motif_xs7", "yamaha_motif_xs8"),
            devices.map { it.id },
        )

        val byId = devices.associateBy { it.id }
        val xs7 = byId.getValue("yamaha_motif_xs7").match as DeviceMatch.Usb
        val xs8 = byId.getValue("yamaha_motif_xs8").match as DeviceMatch.Usb
        assertEquals(0x0499, xs7.vendorId)
        assertEquals(0x1043, xs7.productId)
        assertEquals(0x0499, xs8.vendorId)
        assertEquals(0x1044, xs8.productId)

        // Everything but the model-identifying fields should be the XS6's, since the protocol is
        // shared across the family.
        val xs6 = motifXs6()
        for (id in listOf("yamaha_motif_xs7", "yamaha_motif_xs8")) {
            val sibling = byId.getValue(id)
            val siblingMatch = sibling.match as DeviceMatch.Usb
            val xs6Match = xs6.match as DeviceMatch.Usb
            assertEquals(xs6Match.midiCable, siblingMatch.midiCable)
            assertEquals(xs6Match.endpointOut, siblingMatch.endpointOut)
            assertEquals(xs6Match.endpointIn, siblingMatch.endpointIn)

            val siblingConfig = resolvedConfig(catalog.familyConfig, sibling.familyConfig, json)
            val xs6Config = resolvedConfig(catalog.familyConfig, xs6.familyConfig, json)
            assertEquals(xs6Config.banks, siblingConfig.banks)
            assertEquals(xs6Config.deviceNumber, siblingConfig.deviceNumber)
        }
    }

    /** A probe must be a read-only enquiry. Both families use a universal or documented identity
     * query for exactly the reason in `MidiDiscovery`'s class doc. */
    @Test
    fun `every MIDI probe is a single well-formed SysEx message`() {
        for (name in listOf("behringer_pro800.json", "yamaha_motif_xs.json")) {
            for (descriptor in catalog(name).devices) {
                val match = descriptor.match as? DeviceMatch.MidiIdentity ?: continue
                val probe = match.probe
                assertEquals("$name: probe must start with F0", 0xF0.toByte(), probe.first())
                assertEquals("$name: probe must end with F7", 0xF7.toByte(), probe.last())
                assertEquals("$name: probe must be one message", 1, probe.count { it == 0xF7.toByte() })
            }
        }
    }
}
