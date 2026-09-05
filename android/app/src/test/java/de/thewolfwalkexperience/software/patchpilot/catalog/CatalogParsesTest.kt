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
        val config = json.decodeFromJsonElement(Pro800Config.serializer(), catalog.familyConfig)
        assertEquals(4, config.bankCount)
        assertEquals(100, config.slotsPerBank)
    }

    @Test
    fun `the Motif XS catalog decodes into its family config`() {
        val catalog = catalog("yamaha_motif_xs.json")
        assertEquals("motifxs", catalog.family)
        val config = motifXs6Config()

        // All fifteen banks, **in the instrument's own address order**. The order is asserted
        // rather than the set, because the browser's bank rail follows this list: it is the order
        // headers appear in and the order dragging the rail scrubs through.
        assertEquals(
            listOf(
                "PRE1", "PRE2", "PRE3", "PRE4", "PRE5", "PRE6", "PRE7", "PRE8", "GM",
                "USR1", "USR2", "USR3", "PREDR", "GMDR", "DRUM",
            ),
            config.banks.map { it.label },
        )
        assertTrue(config.banks.all { it.addressHi == 0x0C })
        assertEquals(1633, config.banks.sumOf { it.slotCount })
        assertEquals(416, config.banks.filterNot { it.readOnly }.sumOf { it.slotCount })
        assertEquals(1217, config.banks.filter { it.readOnly }.sumOf { it.slotCount })

        val bySize = config.banks.associate { it.label to it.slotCount }
        assertEquals(128, bySize["USR1"])
        assertEquals(32, bySize["DRUM"])    // USER DR: groups A-B only
        assertEquals(64, bySize["PREDR"])   // PRE DR: groups A-D
        assertEquals(1, bySize["GMDR"])     // GM DR is a single kit

        val byAddress = config.banks.associate { it.label to it.addressMid }
        assertEquals(0x00, byAddress["PRE1"])
        assertEquals(0x07, byAddress["PRE8"])
        // GM sits at 0x09, not 0x08 - see the hole test below.
        assertEquals(0x09, byAddress["GM"])
        assertEquals(0x0A, byAddress["USR1"])
        assertEquals(0x0B, byAddress["USR2"])
        assertEquals(0x0C, byAddress["USR3"])
        assertEquals(0x20, byAddress["PREDR"])
        assertEquals(0x21, byAddress["GMDR"])
        assertEquals(0x28, byAddress["DRUM"])
    }

    /**
     * **`0x08` is a hole, and asking about it is not free.**
     *
     * There is no bank between PRE8 (`0x07`) and GM (`0x09`). An unmapped middle byte is not
     * merely refused: the instrument puts an *Illegal Bulk Data* message on its own screen, in
     * front of the user, once per attempt. The favorites listing sends one request per catalogued
     * bank, so a bank added here with a wrong middle byte becomes an error message on the
     * instrument rather than a wrong row in the app.
     */
    @Test
    fun `no bank claims the unmapped middle byte between PRE8 and GM`() {
        assertTrue(motifXs6Config().banks.none { it.addressMid == 0x08 })
    }

    /**
     * The bank-select MSB is not a constant, and the LSB alone does not identify a bank.
     *
     * PRE1, GM and GM DR all select on LSB `0x00` and are told apart **only** by this byte. An
     * unrecognised pair is *ignored* rather than refused, so a wrong value here does not fail - it
     * loads somebody else's voice and reports success, which is the one failure no amount of
     * software checking downstream can catch.
     */
    @Test
    fun `only GM and GM DR override the bank-select MSB`() {
        val config = motifXs6Config()
        assertTrue("every bank needs a confirmed select LSB", config.banks.all { it.selectLsb != null })
        val byMsb = config.banks.associate { it.label to it.selectMsb }
        assertEquals(0x00, byMsb["GM"])
        assertEquals(0x7F, byMsb["GMDR"])
        assertTrue(
            config.banks.filterNot { it.label == "GM" || it.label == "GMDR" }
                .all { it.selectMsb == 0x3F },
        )
        // The three that collide on the LSB, kept together so the reason stays visible.
        val byLsb = config.banks.associate { it.label to it.selectLsb }
        assertEquals(0x00, byLsb["PRE1"])
        assertEquals(0x00, byLsb["GM"])
        assertEquals(0x00, byLsb["GMDR"])
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
            listOf(
                "PRE1", "PRE2", "PRE3", "PRE4", "PRE5", "PRE6", "PRE7", "PRE8", "GM",
                "USER 1", "USER 2", "USER 3", "PRE DR", "GM DR", "USER DR",
            ),
            config.banks.map { it.displayLabel },
        )
        assertEquals(
            listOf("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "GM",
                "U1", "U2", "U3", "P DR", "G DR", "U DR"),
            config.banks.map { it.shortLabel },
        )
        // "USER DR" -> "U DR", not "UD": no rule that yields one yields the other, which is why
        // the rail label is data rather than derived.
        assertTrue(config.banks.all { it.shortLabel.length <= 4 })
    }

    /**
     * Exactly the writable banks are walked on connect; the read-only ones never are.
     *
     * The two flags have to agree, and the cost of them not agreeing is asymmetric. A read-only
     * bank left indexing by default adds about seven and a half minutes to every connect, reading
     * 1,217 voices whose names ship with the app. A writable bank left out of the index simply
     * does not appear.
     */
    @Test
    fun `only the writable banks are indexed by default`() {
        val config = motifXs6Config()
        assertTrue(config.banks.all { it.indexByDefault == !it.readOnly })
        assertEquals(4, config.banks.count { it.indexByDefault })
        assertEquals(11, config.banks.count { it.readOnly })
    }

    /**
     * The drum banks are the three at or above `0x20`, and nothing else.
     *
     * Derived from the address rather than configured, so this is really a test that the addresses
     * above put the drum banks where the derivation expects. It matters because a drum kit and a
     * normal voice are different objects of very different sizes, and the *instrument* accepts a
     * payload of the wrong kind without complaint - `requireSameKind` is the only thing that
     * refuses it, and it reads this flag.
     */
    @Test
    fun `only the drum banks are marked as such`() {
        val config = motifXs6Config()
        assertEquals(
            listOf("PREDR", "GMDR", "DRUM"),
            config.banks.filter { it.isDrum }.map { it.label },
        )
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
