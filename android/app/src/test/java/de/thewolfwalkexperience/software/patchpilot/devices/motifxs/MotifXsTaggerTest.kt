package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CoroutineScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Categories and favorites, against a scripted device.
 *
 * Two banks, one read-only and one writable, because the whole point of this facet is that the two
 * capabilities **disagree** about a factory bank: its categories are inside the voice and cannot be
 * rewritten, its favorite mark is in a separate table the instrument lets a host write.
 *
 * The device fake here is deliberately smaller than `MotifXsInstrumentTest`'s: it answers a `0C`
 * voice dump, a documented `0E` read/write, and the `71` favorites table, which is everything this
 * facet touches and nothing else.
 */
class MotifXsTaggerTest {

    private val config get() = MotifXsConfig(
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

    /**
     * The real main-category order, with subs filled in for only the three mains used below.
     *
     * **The indices have to be the real ones.** `NoAsg` is main 16 because the wire says so - an
     * unassigned slot reads as the figure 256, which is `16 * 16` - so a cut-down table that put
     * it at index 3 would be testing a format the instrument does not have.
     *
     * `Bass` is one of the two four-sub mains, where "no sub-category" is 4 rather than 5. That is
     * the case a fixed sentinel gets wrong, and no factory voice exercises it.
     */
    private val mainNames = listOf(
        "Piano", "Keys", "Organ", "Guitar", "Bass", "String", "Brass", "SaxWW",
        "SynLd", "Pads", "SyCmp", "CPerc", "Dr/Pc", "S.EFX", "M.EFX", "Ethnic",
        MotifXsCategoryEncoding.NO_ASSIGNMENT,
    )

    private val subsByMain = mapOf(
        "Piano" to listOf("APno", "Layer", "Modrn", "Vintg", "Arp"),
        "Bass" to listOf("ABass", "EBass", "SynBs", "Arp"),
        "Pads" to listOf("Analg", "Warm", "Brite", "Choir", "Arp"),
    )

    private val encoding = MotifXsCategoryEncoding(
        mainByValue = mainNames,
        subByValuePerMain = subsByMain,
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
                            MotifXsFactoryCategory("Pads", "Warm"),
                        ),
                    ),
                    // A factory drum-style gap: named, with no categories published at all.
                    MotifXsFactoryVoice(3, "Special SFXs", null),
                    MotifXsFactoryVoice(4, "Glasgow", emptyList()),
                ),
            ),
        ),
    )

    private val taxonomy = CategoryTaxonomy(
        mainNames.map { MainCategory(it, subsByMain[it].orEmpty()) },
    )

    private val piano = mainNames.indexOf("Piano")
    private val bass = mainNames.indexOf("Bass")
    private val pads = mainNames.indexOf("Pads")
    private val noAsg = mainNames.indexOf(MotifXsCategoryEncoding.NO_ASSIGNMENT)

    // ---- The device ----

    /**
     * A scripted Motif XS holding one user voice and one favorites table per bank.
     *
     * Voice categories live in the `0C` payload's leading figure pair, exactly as the real
     * instrument serialises them, so a category write has to come back out through the same
     * decoder the browser uses rather than through a field this fake invented.
     */
    private inner class Device(
        var userFigures: Pair<Int, Int> = 256 to 256,
        favorites: Map<Int, ByteArray> = emptyMap(),
        /** How many read-backs still report the old table after a favorites write is acked. */
        private val favoriteSettlesAfterReads: Int = 0,
    ) {
        val marks = favorites.mapValues { it.value.copyOf() }.toMutableMap()
        private var stale = 0
        private var staleTable: ByteArray? = null
        private var inbound: MutableList<ByteArray>? = null
        private var inboundTarget: Pair<Int, Int>? = null

        /**
         * What the `0C` read answers: the figure pair, the name, then the name's own repeat.
         *
         * The repeat six bytes past the end is not decoration - it is what the name decoder
         * corroborates a printable run against, and without it this voice reads as empty.
         */
        private fun voicePayload(): ByteArray {
            val name = "Scratch Voice".toByteArray(Charsets.ISO_8859_1)
            val prefix = "${userFigures.first}:${userFigures.second}:".toByteArray(Charsets.ISO_8859_1)
            val dense = prefix + name + ByteArray(6) + name
            return byteArrayOf(0, 0) + pack(dense)
        }

        fun respond(request: ByteArray): List<ByteArray> {
            val address = MotifXsSysEx.addressOf(request)
            val type = MotifXsSysEx.typeOf(request)

            if (type == MotifXsSysEx.TYPE_DUMP_REQUEST && address != null) {
                val (hi, mid, lo) = address
                return when (hi) {
                    0x0C -> listOf(deviceDump(0x0C, mid, lo, voicePayload()))
                    MotifXsSysEx.FAVORITES_ADDRESS_HI -> {
                        val table = if (stale > 0) {
                            stale--
                            staleTable ?: marks.getValue(mid)
                        } else {
                            marks.getValue(mid)
                        }
                        listOf(deviceDump(hi, mid, 0, table))
                    }
                    MotifXsSysEx.BULK_HEADER_HI -> documentedRead(mid, lo)
                    else -> emptyList()
                }
            }

            if (type != MotifXsSysEx.TYPE_BULK_DUMP || address == null) return emptyList()
            val (hi, mid, lo) = address
            if (!MotifXsSysEx.isWellFormedBulkDump(request)) return emptyList()

            return when (hi) {
                MotifXsSysEx.FAVORITES_ADDRESS_HI -> {
                    // Whole-table write. Held stale for a beat, the way the instrument applies it.
                    staleTable = marks[mid]?.copyOf()
                    stale = favoriteSettlesAfterReads
                    marks[mid] = MotifXsSysEx.dumpPayload(request)
                    listOf(ACK)
                }
                MotifXsSysEx.BULK_HEADER_HI -> {
                    inbound = mutableListOf()
                    inboundTarget = mid to lo
                    emptyList()
                }
                MotifXsSysEx.BULK_FOOTER_HI -> {
                    val blocks = inbound ?: return emptyList()
                    inbound = null
                    if (inboundTarget != mid to lo) return emptyList()
                    // The footer is the commit: apply the Common block's category bytes to the
                    // voice, so they read back out through `0C` like the real thing.
                    blocks.firstOrNull { MotifXsSysEx.isCommonBlock(it) }?.let { common ->
                        val payload = MotifXsSysEx.dumpPayload(common)
                        val o = MotifXsSysEx.CATEGORY_OFFSET
                        userFigures = (payload[o] * 16 + payload[o + 1]) to
                            (payload[o + 2] * 16 + payload[o + 3])
                    }
                    listOf(ACK)
                }
                else -> {
                    inbound?.add(request)
                    emptyList()
                }
            }
        }

        /** Header, a Common block carrying the current category bytes, filler, footer. */
        private fun documentedRead(mid: Int, lo: Int): List<ByteArray> = buildList {
            add(deviceDump(MotifXsSysEx.BULK_HEADER_HI, mid, lo, ByteArray(0)))
            val common = ByteArray(82) { (it and 0x3F).toByte() }
            val o = MotifXsSysEx.CATEGORY_OFFSET
            common[o] = (userFigures.first / 16).toByte()
            common[o + 1] = (userFigures.first % 16).toByte()
            common[o + 2] = (userFigures.second / 16).toByte()
            common[o + 3] = (userFigures.second % 16).toByte()
            add(deviceDump(0x40, 0x00, 0x00, common))
            for (m in listOf(0x01, 0x02, 0x03)) {
                add(deviceDump(0x40, m, 0x00, ByteArray(37) { it.toByte() }))
            }
            add(deviceDump(MotifXsSysEx.BULK_FOOTER_HI, mid, lo, ByteArray(0)))
        }
    }

    private fun instrument(
        scope: CoroutineScope,
        device: Device,
    ): Triple<MotifXsInstrument, MotifXsTagger, FakeMidiTransport> {
        val transport = FakeMidiTransport { device.respond(it) }
        val motif = MotifXsInstrument(
            SysExExchange(transport, scope, defaultTimeout = 50.milliseconds, retries = 0),
            config,
            MotifXsBlanks(normal = ByteArray(8), drum = ByteArray(8)),
            factoryVoices = factoryVoices,
        )
        return Triple(motif, motif.tagger as MotifXsTagger, transport)
    }

    private fun FakeMidiTransport.favoriteWrites() = sent.filter {
        MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
            MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.FAVORITES_ADDRESS_HI
    }

    private fun FakeMidiTransport.storeMarkers() = sent.filter {
        MotifXsSysEx.addressOf(it) ==
            Triple(MotifXsSysEx.STORE_HI, MotifXsSysEx.STORE_MID, MotifXsSysEx.STORE_LO)
    }

    private val userSlot = SlotAddress(1, 0)
    private val factorySlot = SlotAddress(0, 1)

    // ---- What the facet declares ----

    @Test
    fun `a factory voice can be favorited but not re-categorised`() = runTest {
        val (_, tagger, _) = instrument(backgroundScope, Device())
        assertTrue("a factory favorite is user data the instrument accepts",
            tagger.canSetFavorite(factorySlot))
        assertFalse("a factory voice's own bytes are not rewritable",
            tagger.canSetCategories(factorySlot))
        assertTrue(tagger.canSetFavorite(userSlot))
        assertTrue(tagger.canSetCategories(userSlot))
    }

    /**
     * No encoding in the **device catalog**, no facet.
     *
     * Keyed on the catalog rather than on the factory voice names, which is the point of the
     * encoding living there: an instrument whose user banks are full and whose shipped name table
     * is missing can still categorise, because the format is the same either way.
     */
    @Test
    fun `an instrument whose catalog carries no encoding offers no tagger`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config.copy(categoryEncoding = null),
            MotifXsBlanks(normal = ByteArray(8), drum = ByteArray(8)),
            factoryVoices = factoryVoices,
        )
        assertNull(motif.tagger)
    }

    /** And the converse: no shipped names is not a reason to withhold it. */
    @Test
    fun `an instrument with no factory name table still tags`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            MotifXsBlanks(normal = ByteArray(8), drum = ByteArray(8)),
            factoryVoices = MotifXsFactoryVoices(),
        )
        assertNotNull(motif.tagger)
    }

    // ---- Reading ----

    /**
     * **A factory voice is never dumped.** Its categories come from the shipped table, which is
     * what keeps the factory listing free - so the only `0C` request this makes is none at all.
     */
    @Test
    fun `reading a factory voice's tags asks the table, not the instrument`() = runTest {
        val device = Device(favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)))
        val (_, tagger, transport) = instrument(backgroundScope, device)

        val tags = tagger.read(factorySlot)
        assertEquals(
            listOf(CategoryRef(piano, 1), CategoryRef(pads, 1)),
            tags.categories,
        )
        assertTrue(
            "no voice dump should have been requested for a factory slot",
            transport.sent.none { MotifXsSysEx.addressOf(it)?.first == 0x0C },
        )
    }

    /** A user voice's come out of the `0C` payload the browser already reads. */
    @Test
    fun `reading a user voice's tags decodes the cheap dump`() = runTest {
        val device = Device(
            userFigures = (bass * 16 + 1) to (pads * 16 + 2),
            favorites = mapOf(0x0A to ByteArray(4)),
        )
        val (_, tagger, _) = instrument(backgroundScope, device)
        assertEquals(
            listOf(CategoryRef(bass, 1), CategoryRef(pads, 2)),
            tagger.read(userSlot).categories,
        )
    }

    /**
     * The mark byte is not a boolean and not a bitmask: `1` is *both*, `2` and `3` pick one.
     *
     * Driven from the device's own table rather than through a writer, so the decode is checked
     * independently of the encode - the two agreeing with each other proves neither.
     */
    @Test
    fun `the mark byte names which categories the favorite is filed under`() = runTest {
        val table = byteArrayOf(0, 1, 2, 3)
        val device = Device(favorites = mapOf(0x00 to table, 0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)

        assertEquals(emptySet<Int>(), tagger.read(SlotAddress(0, 0)).favoriteUnder)
        assertEquals(setOf(0, 1), tagger.read(SlotAddress(0, 1)).favoriteUnder)
        assertEquals(setOf(0), tagger.read(SlotAddress(0, 2)).favoriteUnder)
        assertEquals(setOf(1), tagger.read(SlotAddress(0, 3)).favoriteUnder)
    }

    /**
     * **A player can mark a favorite on the front panel, mid-session.**
     *
     * Nothing tells the app when that happens, so the cached mark table has to be dropped
     * whenever the app re-reads the instrument - which is what a listing is, and what
     * pull-to-refresh triggers. Without this the dialog goes on showing what was true when it
     * first looked, and a save writes that stale state straight back.
     */
    @Test
    fun `a listing drops the cached marks so a panel change is picked up`() = runTest {
        val device = Device(favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)))
        val (motif, tagger, _) = instrument(backgroundScope, device)

        assertEquals(emptySet<Int>(), tagger.read(factorySlot).favoriteUnder)

        // The player marks it on the instrument itself. Nothing informs the app.
        device.marks[0x00] = byteArrayOf(0, 2, 0, 0)
        assertEquals(
            "still cached, which is correct until something re-reads",
            emptySet<Int>(), tagger.read(factorySlot).favoriteUnder,
        )

        motif.browser.index(PresetScope.FAVORITES).toList()
        assertEquals(setOf(0), tagger.read(factorySlot).favoriteUnder)
    }

    // ---- Writing a favorite ----

    /**
     * The write carries the **whole** bank table with one byte changed, and no store marker.
     *
     * Both halves are hazards rather than preferences: a wrong-length write at a neighbouring
     * address family has been observed to hang the instrument's MIDI handling until a power
     * cycle, and `11 00 00` here would commit every unrelated pending write too.
     */
    @Test
    fun `a favorite write sends the whole table and no store marker`() = runTest {
        val device = Device(
            userFigures = (piano * 16 + 1) to (pads * 16 + 2),
            favorites = mapOf(0x0A to byteArrayOf(0, 0, 0, 0)),
        )
        val (_, tagger, transport) = instrument(backgroundScope, device)

        tagger.setFavorite(userSlot, setOf(1))

        val writes = transport.favoriteWrites()
        assertEquals(1, writes.size)
        assertEquals(
            Triple(MotifXsSysEx.FAVORITES_ADDRESS_HI, 0x0A, 0),
            MotifXsSysEx.addressOf(writes.single()),
        )
        // Four bytes out for a four-slot bank: the instrument's own length, not a synthesised one.
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), MotifXsSysEx.dumpPayload(writes.single()))
        assertTrue(transport.storeMarkers().isEmpty())
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), device.marks.getValue(0x0A))
    }

    /** A factory bank takes one too - the read-only rule belongs to the voice path, not this one. */
    @Test
    fun `a factory voice can actually be favorited`() = runTest {
        val device = Device(favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)
        tagger.setFavorite(factorySlot, setOf(0, 1))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), device.marks.getValue(0x00))
    }

    /** Clearing every box is a value of its own, not a skipped write. */
    @Test
    fun `clearing a favorite writes zero`() = runTest {
        val device = Device(favorites = mapOf(0x00 to byteArrayOf(0, 2, 0, 0), 0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)
        tagger.setFavorite(factorySlot, emptySet())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), device.marks.getValue(0x00))
    }

    /**
     * **An immediate read-back races the write.** The instrument acknowledges at once and applies
     * a moment later, so a verifier that trusts the first read reports a working write as a no-op.
     * This device reports the old table twice before applying, and the write must still succeed.
     */
    @Test
    fun `a favorite write polls rather than trusting the first read-back`() = runTest {
        val device = Device(
            favorites = mapOf(0x00 to ByteArray(4), 0x0A to ByteArray(4)),
            favoriteSettlesAfterReads = 2,
        )
        val (_, tagger, _) = instrument(backgroundScope, device)
        tagger.setFavorite(factorySlot, setOf(0))
        assertArrayEquals(byteArrayOf(0, 2, 0, 0), device.marks.getValue(0x00))
    }

    /**
     * **A voice with no categories can still be favorited**, and the mark written is the one the
     * instrument's own front panel writes.
     *
     * Measured on hardware: marking a category-less USER voice via Category Search -> FAVORITE
     * stores `2` and the voice then appears in the instrument's FAVORITE bank, so such a mark
     * does not "list the voice nowhere". It matters at scale rather than as a corner case: 43 of
     * the 128 voices in the USR1 bank measured carry no category at all.
     */
    @Test
    fun `a voice with no categories can still be favorited`() = runTest {
        // 256:256 - no assignments at all.
        val device = Device(favorites = mapOf(0x0A to ByteArray(4)))
        val (_, tagger, transport) = instrument(backgroundScope, device)

        tagger.setFavorite(userSlot, setOf(0))

        assertEquals(
            "the mark must be the 2 the front panel writes, not a refusal",
            2,
            device.marks.getValue(0x0A)[userSlot.slot].toInt(),
        )
        assertTrue("the write must actually go out", transport.favoriteWrites().isNotEmpty())
    }

    /** An index outside the instrument's two assignment slots is a caller bug, not a value. */
    @Test
    fun `a favorite refuses an assignment slot the format does not have`() = runTest {
        val device = Device(favorites = mapOf(0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)
        assertNotNull(runCatching { tagger.setFavorite(userSlot, setOf(2)) }.exceptionOrNull())
    }

    // ---- Writing categories ----

    /**
     * Categories go out by the documented path - header, blocks, footer - and read back through
     * `0C`, which is the path the browser itself lists with.
     */
    @Test
    fun `setting categories writes the documented sequence and reads back`() = runTest {
        val device = Device(favorites = mapOf(0x0A to ByteArray(4)))
        val (_, tagger, transport) = instrument(backgroundScope, device)

        tagger.setCategories(userSlot, listOf(CategoryRef(bass, 2), CategoryRef(pads, null)))

        val documented = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first in
                setOf(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.BULK_FOOTER_HI, 0x40)
        }
        assertEquals(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.addressOf(documented.first())!!.first)
        assertEquals(MotifXsSysEx.BULK_FOOTER_HI, MotifXsSysEx.addressOf(documented.last())!!.first)
        // The footer is this path's commit; a store marker belongs to `0C`.
        assertTrue(transport.storeMarkers().isEmpty())
        // Every block goes out in host form - echoing the device's own bytes back is wrong in the
        // model byte and in the checksum covering it.
        assertTrue(documented.all { it[4].toInt() == MotifXsSysEx.MODEL_HOST })

        assertEquals(
            listOf(CategoryRef(bass, 2), CategoryRef(pads, null)),
            tagger.read(userSlot).categories,
        )
    }

    /**
     * **"No sub-category" is one past the main's last sub**, so `Bass` writes 4 where a five-sub
     * main writes 5. A fixed sentinel decodes every shipped factory voice correctly and still gets
     * this wrong, because no factory voice has a `Bass` assignment without a sub.
     */
    @Test
    fun `a four-sub main writes its own no-sub value`() = runTest {
        val device = Device(favorites = mapOf(0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)

        tagger.setCategories(userSlot, listOf(CategoryRef(bass, null), null))
        assertEquals((bass * 16 + 4) to (noAsg * 16 + 0), device.userFigures)
    }

    /** An unassigned slot stores `(16, 0)`: with no main the sub is meaningless and gets zeroed. */
    @Test
    fun `an unassigned category writes a zero sub`() = runTest {
        val device = Device(
            userFigures = (piano * 16 + 1) to (pads * 16 + 2),
            favorites = mapOf(0x0A to ByteArray(4)),
        )
        val (_, tagger, _) = instrument(backgroundScope, device)

        tagger.setCategories(userSlot, listOf(CategoryRef(piano, 0), null))
        assertEquals((piano * 16 + 0) to (noAsg * 16 + 0), device.userFigures)
        assertEquals(listOf(CategoryRef(piano, 0), null), tagger.read(userSlot).categories)
    }

    /** Inside the voice, so a factory bank is refused before a single byte goes out. */
    @Test
    fun `setting categories refuses a read-only bank`() = runTest {
        val device = Device(favorites = mapOf(0x00 to ByteArray(4)))
        val (_, tagger, transport) = instrument(backgroundScope, device)

        val failure = runCatching {
            tagger.setCategories(factorySlot, listOf(CategoryRef(piano, 0), null))
        }.exceptionOrNull()
        assertNotNull("a factory bank should be refused", failure)
        assertTrue(
            "nothing should have gone out",
            transport.sent.none { MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.BULK_HEADER_HI },
        )
    }

    /** A sub-category the main does not have is a caller bug, refused before the write. */
    @Test
    fun `setting categories refuses a sub the main does not have`() = runTest {
        val device = Device(favorites = mapOf(0x0A to ByteArray(4)))
        val (_, tagger, _) = instrument(backgroundScope, device)
        assertNotNull(
            runCatching {
                // Bass has four subs, so index 4 is its no-sub value and not a sub at all.
                tagger.setCategories(userSlot, listOf(CategoryRef(bass, 4), null))
            }.exceptionOrNull(),
        )
    }

    private companion object {
        val ACK = byteArrayOf(0xF0.toByte(), 0x43, 0x60, 0x02, 0xF7.toByte())

        fun deviceDump(hi: Int, mid: Int, lo: Int, payload: ByteArray): ByteArray {
            val dump = MotifXsSysEx.bulkDump(0, hi, mid, lo, payload)
            dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
            dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
            return dump
        }

        /** Yamaha MSB packing, the inverse of [MotifXsVoice.unpack]. */
        fun pack(dense: ByteArray): ByteArray {
            val out = ArrayList<Byte>(dense.size + dense.size / 7 + 1)
            var i = 0
            while (i < dense.size) {
                val group = dense.copyOfRange(i, minOf(i + 7, dense.size))
                var msb = 0
                group.forEachIndexed { j, b -> if (b.toInt() and 0x80 != 0) msb = msb or (1 shl j) }
                out.add(msb.toByte())
                group.forEach { out.add((it.toInt() and 0x7F).toByte()) }
                i += 7
            }
            return out.toByteArray()
        }
    }
}
