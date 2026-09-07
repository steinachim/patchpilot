package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **A factory voice is never dumped.** Not for a listing, not for a badge, not for a tag read, and
 * not for the refresh after an edit.
 *
 * The factory listing is free - 1,217 rows and no round trips - because every one of them comes
 * out of the shipped table. That is a property of the whole class rather than of one method, and
 * it is easy to lose one call site at a time: adding categories put three new paths in front of a
 * factory address, and the refresh after a favorite write was reachable with one for the first
 * time, since favoriting is the first edit that can target a read-only bank at all.
 *
 * So the device here **refuses** to answer a `0C` request aimed at a read-only bank, exactly as if
 * it did not exist. A path that regresses times out instead of quietly costing seven minutes on
 * real hardware.
 */
class MotifXsFactoryReadsTest {

    /** The real main order, so `NoAsg` sits at 16 the way the wire has it. */
    private val encoding = MotifXsCategoryEncoding(
        mainByValue = listOf(
            "Piano", "Keys", "Organ", "Guitar", "Bass", "String", "Brass", "SaxWW",
            "SynLd", "Pads", "SyCmp", "CPerc", "Dr/Pc", "S.EFX", "M.EFX", "Ethnic",
            MotifXsCategoryEncoding.NO_ASSIGNMENT,
        ),
        subByValuePerMain = mapOf(
            "Piano" to listOf("APno", "Layer", "Modrn", "Vintg", "Arp"),
            "Keys" to listOf("EP", "FM", "Clavi", "Synth", "Arp"),
        ),
    )

    private val config = MotifXsConfig(
        categoryEncoding = encoding,
        banks = listOf(
            MotifXsBank(
                label = "PRE1", slotCount = 4, addressHi = 0x0C, addressMid = 0x00,
                displayLabel = "PRE1", shortLabel = "P1",
                readOnly = true, indexByDefault = false, selectLsb = 0x00,
            ),
            MotifXsBank(
                label = "USR1", slotCount = 4, addressHi = 0x0C, addressMid = 0x0A,
                displayLabel = "USER 1", shortLabel = "U1",
                readOnly = false, indexByDefault = true, selectLsb = 0x08,
            ),
        ),
    )

    private val factoryVoices = MotifXsFactoryVoices(
        banks = listOf(
            MotifXsFactoryBank(
                label = "PRE1",
                slotCount = 4,
                voices = listOf(
                    MotifXsFactoryVoice(
                        1, "Full Concert Grand",
                        listOf(MotifXsFactoryCategory("Piano", "APno")),
                    ),
                    MotifXsFactoryVoice(
                        2, "Ballad Key",
                        listOf(
                            MotifXsFactoryCategory("Piano", "Layer"),
                            MotifXsFactoryCategory("Keys", "EP"),
                        ),
                    ),
                    // The drum-bank case: named, with no categories published anywhere.
                    MotifXsFactoryVoice(3, "Special SFXs", null),
                    MotifXsFactoryVoice(4, "Glasgow", listOf(MotifXsFactoryCategory("Piano"))),
                ),
            ),
        ),
    )

    /** Every `0C` request aimed at a read-only bank is a test failure, recorded and refused. */
    private val forbidden = mutableListOf<Triple<Int, Int, Int>>()

    private fun instrument(scope: kotlinx.coroutines.CoroutineScope): MotifXsInstrument {
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request)
            when {
                MotifXsSysEx.typeOf(request) != MotifXsSysEx.TYPE_DUMP_REQUEST -> emptyList()

                address?.first == MotifXsSysEx.FAVORITES_ADDRESS_HI ->
                    listOf(favoritesDump(address.second, byteArrayOf(0, 2, 0, 0)))

                // The read-only bank's own address mid. Silence, and remembered.
                address?.first == 0x0C && address.second == 0x00 -> {
                    forbidden += address
                    emptyList()
                }

                address?.first == 0x0C -> listOf(
                    voiceDump(MotifXsFixtures.namedVoice, address.second, address.third),
                )

                else -> emptyList()
            }
        }
        return MotifXsInstrument(
            // Short, because a regression here shows up as a timeout and there is no reason to
            // wait out a realistic one to see it.
            SysExExchange(transport, scope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            MotifXsBlanks(normal = ByteArray(8), drum = ByteArray(8)),
            factoryVoices = factoryVoices,
        )
    }

    private fun List<IndexUpdate>.slots(): List<PresetSlot> =
        filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

    @Test
    fun `the factory listing names and badges every row without a single dump`() = runTest {
        val motif = instrument(backgroundScope)
        val rows = motif.browser.index(PresetScope.FACTORY).toList().slots()

        assertEquals(4, rows.size)
        assertEquals("Full Concert Grand", rows[0].name)
        assertEquals(listOf("Piano / APno"), rows[0].badges)
        assertEquals(listOf("Piano / Layer", "Keys / EP"), rows[1].badges)
        // No published categories for this one, so no badge - not a guessed one, and not a dump.
        assertEquals(emptyList<String>(), rows[2].badges)
        // A main with no sub reads as the main alone.
        assertEquals(listOf("Piano"), rows[3].badges)
        assertTrue("the factory listing must ask the instrument nothing: $forbidden", forbidden.isEmpty())
    }

    /** The favorites listing mixes both banks; only the user side may cost a dump. */
    @Test
    fun `a favorited factory voice is named from the table`() = runTest {
        val motif = instrument(backgroundScope)
        val rows = motif.browser.index(PresetScope.FAVORITES).toList().slots()

        val factoryRow = rows.single { it.address.bank == 0 }
        assertEquals("Ballad Key", factoryRow.name)
        assertEquals(listOf("Piano / Layer", "Keys / EP"), factoryRow.badges)
        assertTrue(forbidden.isEmpty())
    }

    /** Reading a factory voice's tags is a table lookup plus the bank's mark table. */
    @Test
    fun `reading a factory voice's tags dumps nothing`() = runTest {
        val motif = instrument(backgroundScope)
        val tags = motif.tagger!!.read(SlotAddress(0, 1))

        assertEquals(setOf(0), tags.favoriteUnder)
        assertEquals(2, tags.assigned.size)
        assertTrue(forbidden.isEmpty())
    }

    /**
     * **The path this feature made reachable.** `refresh` used to dump unconditionally, and no
     * edit could target a read-only bank - until favoriting one could.
     */
    @Test
    fun `refreshing a factory row after a favorite rebuilds it from the table`() = runTest {
        val motif = instrument(backgroundScope)
        val row = motif.browser.refresh(SlotAddress(0, 0))

        assertEquals("Full Concert Grand", row.name)
        assertEquals(listOf("Piano / APno"), row.badges)
        assertTrue("refresh must not dump a factory voice: $forbidden", forbidden.isEmpty())
    }

    /** A user row still comes off the wire, so the guard above is not passing by doing nothing. */
    @Test
    fun `a user row is still read from the instrument`() = runTest {
        val motif = instrument(backgroundScope)
        assertEquals("TWE2 Wolf Walk", motif.browser.refresh(SlotAddress(1, 0)).name)
    }

    private fun voiceDump(source: ByteArray, mid: Int, lo: Int): ByteArray {
        val out = source.copyOf()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX] = 0x0C
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 1] = mid.toByte()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 2] = lo.toByte()
        out[out.size - 2] = MotifXsSysEx.checksumOf(out).toByte()
        return out
    }

    private fun favoritesDump(mid: Int, marks: ByteArray): ByteArray {
        val dump = MotifXsSysEx.bulkDump(0, MotifXsSysEx.FAVORITES_ADDRESS_HI, mid, 0, marks)
        dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
        dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
        return dump
    }
}
