package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The factory and favorites listings, against a scripted device.
 *
 * A small instrument on purpose - one read-only bank of four and one writable bank of four - since
 * what is under test is which addresses are asked for and where each name comes from, neither of
 * which gets truer at 1,633 slots.
 */
class MotifXsScopesTest {

    private val config = MotifXsConfig(
        banks = listOf(
            // Mirrors the real catalog's shape: a read-only bank that is not indexed on connect,
            // and a writable one that is.
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
                    MotifXsFactoryVoice(1, "Full Concert Grand"),
                    MotifXsFactoryVoice(2, "Rock Grand Piano"),
                    MotifXsFactoryVoice(3, "Suitcase Rhodes"),
                    MotifXsFactoryVoice(4, "Glasgow"),
                ),
            ),
        ),
    )

    private val blanks
        get() = MotifXsBlanks(
            normal = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
            drum = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
        )

    /** A voice dump re-addressed to [mid]/[lo] and re-checksummed - the fake's stored voices. */
    private fun voiceDump(source: ByteArray, mid: Int, lo: Int): ByteArray {
        val out = source.copyOf()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX] = 0x0C
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 1] = mid.toByte()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 2] = lo.toByte()
        out[out.size - 2] = MotifXsSysEx.checksumOf(out).toByte()
        return out
    }

    /** One bank's marks, framed the way the device sends them. */
    private fun favoritesDump(mid: Int, marks: ByteArray): ByteArray {
        val dump = MotifXsSysEx.bulkDump(0, MotifXsSysEx.FAVORITES_ADDRESS_HI, mid, 0, marks)
        dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
        dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
        return dump
    }

    private fun instrument(
        scope: CoroutineScope,
        favorites: Map<Int, ByteArray> = emptyMap(),
        damaged: Set<Int> = emptySet(),
        names: MotifXsFactoryVoices = factoryVoices,
    ): Pair<MotifXsInstrument, FakeMidiTransport> {
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request)
            when {
                MotifXsSysEx.typeOf(request) != MotifXsSysEx.TYPE_DUMP_REQUEST -> emptyList()

                address?.first == MotifXsSysEx.FAVORITES_ADDRESS_HI -> {
                    val mid = address.second
                    when {
                        // Truncated mid-message: the declared count promises more than arrives,
                        // which is what a dropped packet looks like and what the retry is for.
                        mid in damaged -> listOf(
                            favoritesDump(mid, favorites[mid] ?: ByteArray(4))
                                .copyOfRange(0, 11) + MotifXsSysEx.SYSEX_END,
                        )
                        favorites.containsKey(mid) -> listOf(favoritesDump(mid, favorites.getValue(mid)))
                        else -> emptyList()
                    }
                }

                address?.first == 0x0C -> listOf(
                    voiceDump(MotifXsFixtures.namedVoice, address.second, address.third),
                )

                else -> emptyList()
            }
        }
        return MotifXsInstrument(
            // A short timeout and no retry: two of the tests below deliberately make the fake go
            // silent or answer damage, and each of those otherwise costs the full budget times
            // the retry count in real time.
            SysExExchange(transport, scope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            blanks,
            factoryVoices = names,
        ) to transport
    }

    private fun List<IndexUpdate>.slots(): List<PresetSlot> =
        filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

    private fun FakeMidiTransport.dumpRequests(): List<Triple<Int, Int, Int>> =
        sent.filter { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST }
            .mapNotNull { MotifXsSysEx.addressOf(it) }

    // ---- Which scopes are offered ----

    @Test
    fun `an instrument with factory banks and a name table offers all three listings`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        assertEquals(
            listOf(PresetScope.USER, PresetScope.FACTORY, PresetScope.FAVORITES),
            motif.browser.scopes,
        )
    }

    /**
     * No names, no factory tab.
     *
     * The listing would still *work* - 1,217 correctly addressed rows - but every one of them
     * would be unnamed, which is a worse answer than not offering it. This is the case a missing
     * or unreadable asset lands in, and it must degrade rather than fail.
     */
    @Test
    fun `an instrument with no name table does not offer the factory listing`() = runTest {
        val (motif, _) = instrument(backgroundScope, names = MotifXsFactoryVoices())
        assertEquals(listOf(PresetScope.USER, PresetScope.FAVORITES), motif.browser.scopes)
    }

    // ---- The factory listing ----

    @Test
    fun `the factory listing names every read-only slot without asking the instrument`() = runTest {
        val (motif, transport) = instrument(backgroundScope)

        val updates = motif.browser.index(PresetScope.FACTORY).toList()
        val slots = updates.slots()

        assertEquals(4, slots.size)
        assertEquals(listOf("Full Concert Grand", "Rock Grand Piano", "Suitcase Rhodes", "Glasgow"),
            slots.map { it.name })
        // The whole point: it costs nothing. A real one is 1,217 voices and 7.5 minutes.
        assertTrue("the factory listing must not touch the wire", transport.sent.isEmpty())
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `the factory listing excludes the writable banks`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val slots = motif.browser.index(PresetScope.FACTORY).toList().slots()
        assertTrue(slots.all { it.bankLabel == "PRE1" })
        assertTrue(slots.all { it.address.bank == 0 })
    }

    /**
     * A gap in the table is a row without a name, not a row that is missing.
     *
     * Skipping it would shift every row after it up by one, so a single missing entry would put
     * the wrong name against every subsequent slot - silently, and for the rest of the bank.
     */
    @Test
    fun `a slot the table has no entry for is listed unnamed`() = runTest {
        val sparse = MotifXsFactoryVoices(
            banks = listOf(
                MotifXsFactoryBank(
                    label = "PRE1", slotCount = 4,
                    voices = listOf(MotifXsFactoryVoice(1, "Full Concert Grand"), MotifXsFactoryVoice(4, "Glasgow")),
                ),
            ),
        )
        val (motif, _) = instrument(backgroundScope, names = sparse)
        val slots = motif.browser.index(PresetScope.FACTORY).toList().slots()
        assertEquals(4, slots.size)
        assertEquals(listOf("Full Concert Grand", null, null, "Glasgow"), slots.map { it.name })
    }

    // ---- The favorites listing ----

    @Test
    fun `the favorites listing asks each catalogued bank exactly once`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope,
            favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)),
        )

        motif.browser.index(PresetScope.FAVORITES).toList()

        val favoriteRequests = transport.dumpRequests()
            .filter { it.first == MotifXsSysEx.FAVORITES_ADDRESS_HI }
        assertEquals(listOf(0x00, 0x0A), favoriteRequests.map { it.second })
        assertTrue("the low byte is always zero", favoriteRequests.all { it.third == 0 })
    }

    /**
     * **Only catalogued middle bytes are ever asked for.**
     *
     * An unmapped address is not merely refused - it puts an *Illegal Bulk Data* message on the
     * instrument's own screen, in front of the user. So the walk has to be driven by the bank
     * table rather than by a range: `0x08` is a permanent hole between PRE8 and GM, and a sweep
     * would find it once per listing.
     */
    @Test
    fun `the favorites listing never asks for an address the catalog does not carry`() = runTest {
        val (motif, transport) = instrument(backgroundScope, favorites = mapOf(0x00 to ByteArray(4)))

        motif.browser.index(PresetScope.FAVORITES).toList()

        val catalogued = config.banks.map { it.addressMid }.toSet()
        val asked = transport.dumpRequests()
            .filter { it.first == MotifXsSysEx.FAVORITES_ADDRESS_HI }
            .map { it.second }
        assertTrue("asked for $asked, catalog has $catalogued", asked.all { it in catalogued })
    }

    @Test
    fun `a factory favorite is named from the table and a user favorite is read from the device`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope,
            favorites = mapOf(
                // PRE1 slot 3 (0-based 2) and USER 1 slot 1 (0-based 0).
                0x00 to byteArrayOf(0, 0, 2, 0),
                0x0A to byteArrayOf(2, 0, 0, 0),
            ),
        )

        val slots = motif.browser.index(PresetScope.FAVORITES).toList().slots()

        assertEquals(2, slots.size)
        assertEquals("Suitcase Rhodes", slots[0].name)
        assertEquals("PRE1 - A:03", slots[0].displayId)
        assertEquals("TWE2 Wolf Walk", slots[1].name)
        assertEquals("USER 1 - A:01", slots[1].displayId)

        // Exactly one voice dump, for the user bank's mark. The factory one cost nothing.
        val voiceReads = transport.dumpRequests().filter { it.first == 0x0C }
        assertEquals(listOf(Triple(0x0C, 0x0A, 0)), voiceReads)
    }

    /**
     * 1, 2 and 3 all mean "marked"; only 0 means "not".
     *
     * Which of the three is which is genuinely unknown - they are a small enum whose meaning has
     * never been decoded - so nothing branches on the value and nothing shows it. Pinning that
     * here stops someone later "fixing" the check to `== 1`.
     */
    @Test
    fun `every non-zero marker counts as favorited`() = runTest {
        val (motif, _) = instrument(backgroundScope, favorites = mapOf(0x00 to byteArrayOf(1, 2, 3, 0)))

        val slots = motif.browser.index(PresetScope.FAVORITES).toList().slots()

        assertEquals(listOf("PRE1 - A:01", "PRE1 - A:02", "PRE1 - A:03"), slots.map { it.displayId })
    }

    @Test
    fun `a bank with nothing marked contributes no rows`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)),
        )
        val updates = motif.browser.index(PresetScope.FAVORITES).toList()
        assertTrue(updates.slots().isEmpty())
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * A short payload does not index past its end.
     *
     * The bound is the smaller of the reply and the bank's slot count, so a truncated-but-valid
     * reply loses the marks it did not carry rather than reading off the end of the array.
     */
    @Test
    fun `a payload shorter than the bank stops at what arrived`() = runTest {
        val (motif, _) = instrument(backgroundScope, favorites = mapOf(0x00 to byteArrayOf(0, 2)))
        val slots = motif.browser.index(PresetScope.FAVORITES).toList().slots()
        assertEquals(listOf("PRE1 - A:02"), slots.map { it.displayId })
    }

    /**
     * One bank's failure costs only that bank.
     *
     * The same rule the user listing applies per slot: losing every other bank's marks because one
     * reply arrived damaged would be absurd, and the listing still completes.
     */
    @Test
    fun `a bank whose marks arrive damaged is reported and the rest still land`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            favorites = mapOf(0x0A to byteArrayOf(2, 0, 0, 0)),
            damaged = setOf(0x00),
        )

        val updates = motif.browser.index(PresetScope.FAVORITES).toList()

        val failures = updates.filterIsInstance<IndexUpdate.Failed>()
        assertEquals(1, failures.size)
        assertEquals(0, failures.single().address.bank)
        assertTrue(failures.single().reason.contains("damaged"))
        assertEquals(listOf("USER 1 - A:01"), updates.slots().map { it.displayId })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `a bank that answers nothing at all is reported without stopping the walk`() = runTest {
        // 0x00 is absent from the map, so the fake stays silent for it.
        val (motif, _) = instrument(backgroundScope, favorites = mapOf(0x0A to byteArrayOf(0, 2, 0, 0)))

        val updates = motif.browser.index(PresetScope.FAVORITES).toList()

        assertEquals(1, updates.filterIsInstance<IndexUpdate.Failed>().size)
        assertEquals(listOf("USER 1 - A:02"), updates.slots().map { it.displayId })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    // ---- The user listing is unchanged ----

    @Test
    fun `the user listing still walks only the banks marked to be indexed`() = runTest {
        val (motif, transport) = instrument(backgroundScope)

        val slots = motif.browser.index(PresetScope.USER).toList().slots()

        assertEquals(4, slots.size)
        assertTrue(slots.all { it.bankLabel == "USER 1" })
        assertTrue(
            "the read-only bank must not be walked on connect",
            transport.dumpRequests().none { it.second == 0x00 },
        )
    }

    @Test
    fun `the layout marks the read-only banks and the address space still covers both`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        assertEquals(listOf(true, false), motif.layout.banks.map { it.readOnly })
        assertEquals(8, motif.layout.slotCount)
    }

    // ---- The name table itself ----

    @Test
    fun `an out-of-range slot number in the table is ignored rather than throwing`() {
        val table = MotifXsFactoryVoices(
            banks = listOf(
                MotifXsFactoryBank(
                    label = "PRE1", slotCount = 2,
                    voices = listOf(
                        MotifXsFactoryVoice(1, "Full Concert Grand"),
                        MotifXsFactoryVoice(0, "impossible"),
                        MotifXsFactoryVoice(99, "also impossible"),
                    ),
                ),
            ),
        )
        assertEquals("Full Concert Grand", table.name("PRE1", 0))
        assertNull(table.name("PRE1", 1))
        assertNull("a slot beyond the bank has no name", table.name("PRE1", 99))
        assertNull("an unknown bank has no names", table.name("NOPE", 0))
        assertFalse(table.isEmpty)
    }
}
