package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * The Motif XS browser end to end against a scripted device that replays sample dumps.
 *
 * The instrument is deliberately tiny here - two banks of three - because the shape under test is
 * the walk, not its length. The real one is 416 voices and over a minute, which is why the walk is
 * a flow in the first place.
 */
class MotifXsInstrumentTest {

    private val config = MotifXsConfig(
        banks = listOf(
            // selectLsb is the real instrument's: USER 1 selects with 0x08 and dumps at 0x0A,
            // which is exactly why bank-select LSB is not derivable from the dump address.
            MotifXsBank(label = "USR1", slotCount = 3, addressHi = 0x0C, addressMid = 0x0A,
                displayLabel = "USER 1", selectLsb = 0x08),
            MotifXsBank(label = "USR2", slotCount = 3, addressHi = 0x0C, addressMid = 0x0B,
                displayLabel = "USER 2", selectLsb = 0x09),
        ),
    )

    /** A stand-in for the app's shipped blanks - real sample data, not fabricated bytes. */
    private val testBlanks
        get() = MotifXsBlanks(
            normal = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
            drum = MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
        )

    /**
     * Re-addresses a sample dump to [hi]/[mid]/[lo] and re-checksums it, so the fake device can
     * answer six addresses from three recordings. Only the address bytes change; every other byte
     * is still what the instrument sent.
     */
    private fun dumpAt(source: ByteArray, hi: Int, mid: Int, lo: Int): ByteArray {
        val out = source.copyOf()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX] = hi.toByte()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 1] = mid.toByte()
        out[MotifXsSysEx.BULK_ADDRESS_INDEX + 2] = lo.toByte()
        out[out.size - 2] = MotifXsSysEx.checksumOf(out).toByte()
        return out
    }

    /**
     * Slot 0 named, slot 1 empty, slot 2 long-named, in every **voice** bank.
     *
     * **Refuses anything but `0x0C`, and that guard is load-bearing.** Without it this answered a
     * favorites request at `71 mm 00` with a well-formed 1,919-byte voice dump echoing the right
     * address - which passes every check a favorites read makes, and would have been decoded as
     * about nineteen hundred marks. The fake would have been the thing under test.
     */
    private fun defaultVoiceAt(hi: Int, mid: Int, lo: Int): ByteArray? {
        if (hi != 0x0C) return null
        val source = when (lo) {
            0 -> MotifXsFixtures.namedVoice
            1 -> MotifXsFixtures.emptyVoice
            2 -> MotifXsFixtures.longNameVoice
            else -> return null
        }
        return dumpAt(source, hi, mid, lo)
    }

    /**
     * One bank's favorite marks, framed as the device sends them.
     *
     * Synthesized rather than recorded, unlike everything in [MotifXsFixtures]. That is honest
     * here: these payloads carry no instrument-specific encoding to get wrong - they are one plain
     * byte per slot - so there is nothing a recording would pin down that a constructed array does
     * not. The *framing* still goes through [MotifXsSysEx.bulkDump] and is re-stamped as
     * device-originated, because that part is real.
     */
    private fun favoritesDump(mid: Int, marks: ByteArray): ByteArray {
        val dump = MotifXsSysEx.bulkDump(0, MotifXsSysEx.FAVORITES_ADDRESS_HI, mid, 0, marks)
        dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
        dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
        return dump
    }

    /**
     * A fake that **remembers what is written to it**.
     *
     * A stateless one cannot test a write at all: the editor verifies every write by reading the
     * slot straight back, so a fake that always replies with its canned fixture makes a correct
     * write look like a failed one. Modelling the store is the minimum needed for the read-back
     * to mean anything - and it is also what makes a *wrong* address or payload fail the test,
     * which a fake that ignored writes could never do.
     */
    private class FakeSlots(private val readOnly: Set<Int> = emptySet()) {
        private val written = mutableMapOf<Triple<Int, Int, Int>, ByteArray>()
        private val pending = mutableMapOf<Triple<Int, Int, Int>, ByteArray>()

        /**
         * Answers a host bulk dump the way the instrument does, which is **not** the way this
         * fake used to.
         *
         * It applied every write immediately and acknowledged nothing. Both halves were wrong: a
         * write is acknowledged and *held*, and only `11 00 00` stores it. The fake now holds
         * writes in [pending] until a marker arrives, so a code path that forgets to commit fails
         * here rather than only on real hardware.
         *
         * A refusal is silence - no acknowledgement and nothing buffered - for a write to a
         * read-only bank or a dump that fails its own checksum.
         *
         * @return what the device sends back: an acknowledgement, or nothing at all.
         */
        fun write(request: ByteArray): List<ByteArray> {
            val address = MotifXsSysEx.addressOf(request) ?: return emptyList()
            if (address == Triple(MotifXsSysEx.STORE_HI, MotifXsSysEx.STORE_MID, MotifXsSysEx.STORE_LO)) {
                // Commits everything outstanding, not merely the write before it.
                written.putAll(pending)
                pending.clear()
                return listOf(ACK)
            }
            if (!MotifXsSysEx.isWellFormedBulkDump(request)) return emptyList()
            if (address.second in readOnly) return emptyList()
            pending[address] = MotifXsSysEx.dumpPayload(request)
            return listOf(ACK)
        }

        fun read(address: Triple<Int, Int, Int>, default: ByteArray?): ByteArray? {
            val payload = written[address] ?: return default
            // Rebuilt as the *device* would send it: the instrument speaks model 7F 0B where the
            // host speaks 7F 03, so a fake that echoed the host's bytes back would hide any
            // confusion between the two.
            val dump = MotifXsSysEx.bulkDump(0, address.first, address.second, address.third, payload)
            dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
            dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
            return dump
        }

        // ---- The documented path (Bulk Header/Footer, Yamaha's Data List) ----
        //
        // Modelled separately from the `0C` store above because it *is* separate: different
        // addresses, different framing, and a different commit. The two are linked at exactly one
        // point, which is the point that matters - a documented write changes the name a `0C` read
        // reports, because on the instrument they are two views of one voice.

        /** Names set by a documented write, keyed (bank byte, slot). */
        private val renamed = mutableMapOf<Pair<Int, Int>, String>()

        /** Blocks accumulated between a header and its footer. */
        private var inbound: MutableList<ByteArray>? = null
        private var inboundTarget: Pair<Int, Int>? = null

        /** The 26-message sequence a dump request at `0E mm nn` draws back. */
        fun readDocumented(
            bankByte: Int,
            slot: Int,
            currentName: String?,
            drum: Boolean = false,
        ): List<ByteArray> {
            val name = renamed[bankByte to slot] ?: currentName
            if (drum) return buildList {
                // On the instrument: eight 46 xx Common blocks - note 46 08, not the Normal
                // Voice's 46 06 - and 73 elements at 47 ee 00, ee running 0..72.
                add(deviceDump(MotifXsSysEx.BULK_HEADER_HI, bankByte, slot, ByteArray(0)))
                add(deviceDump(0x46, 0x00, 0x00, commonPayload(name, size = 74)))
                for (mid in listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x08, 0x30)) {
                    add(deviceDump(0x46, mid, 0x00, ByteArray(37) { it.toByte() }))
                }
                for (element in 0..72) {
                    add(deviceDump(0x47, element, 0x00, ByteArray(47) { it.toByte() }))
                }
                add(deviceDump(MotifXsSysEx.BULK_FOOTER_HI, bankByte, slot, ByteArray(0)))
            }
            return buildList {
                add(deviceDump(MotifXsSysEx.BULK_HEADER_HI, bankByte, slot, ByteArray(0)))
                add(deviceDump(0x40, 0x00, 0x00, commonPayload(name)))
                // The seven other Common blocks and eight element pairs. Their contents are
                // filler - nothing in the app reads them, it only sends them back - but their
                // *addresses and count* are exactly the instrument's, because that is what the
                // rename checks before it writes anything.
                for (mid in listOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x30)) {
                    add(deviceDump(0x40, mid, 0x00, ByteArray(37) { it.toByte() }))
                }
                for (element in 0 until 8) {
                    add(deviceDump(0x41, element, 0x00, ByteArray(95) { it.toByte() }))
                    add(deviceDump(0x42, element, 0x00, ByteArray(70) { it.toByte() }))
                }
                add(deviceDump(MotifXsSysEx.BULK_FOOTER_HI, bankByte, slot, ByteArray(0)))
            }
        }

        /**
         * A host message on the documented path.
         *
         * **Acknowledges nothing until the footer**, which is the instrument's real behaviour and
         * the reason this fake is worth having: a client that waits for an acknowledgement after
         * the header hangs here exactly as it would on the real instrument.
         */
        fun writeDocumented(request: ByteArray): List<ByteArray> {
            val (hi, mid, lo) = MotifXsSysEx.addressOf(request) ?: return emptyList()
            when (hi) {
                MotifXsSysEx.BULK_HEADER_HI -> {
                    inbound = mutableListOf()
                    inboundTarget = mid to lo
                    return emptyList()
                }
                MotifXsSysEx.BULK_FOOTER_HI -> {
                    val blocks = inbound ?: return emptyList()
                    val target = inboundTarget
                    inbound = null
                    inboundTarget = null
                    // The footer must close the sequence its header opened.
                    if (target != (mid to lo)) return emptyList()
                    // No expected count: a Normal Voice is 24 blocks and a Drum Voice 81, and
                    // the instrument does not check either - it takes what it is given between
                    // a header and a footer.
                    if (blocks.isEmpty()) return emptyList()
                    val common = blocks.firstOrNull { MotifXsSysEx.isCommonBlock(it) }
                        ?: return emptyList()
                    val payload = MotifXsSysEx.dumpPayload(common)
                    val name = String(payload, 0, 20, Charsets.ISO_8859_1)
                        .takeWhile { it.code != 0 }
                    renamed[mid to lo] = name
                    // A documented write is what a `0C` read reports afterwards. Anything else
                    // would let a rename pass here while showing the old name in the browser.
                    written[Triple(0x0C, mid, lo)] = syntheticVoicePayload(name)
                    return listOf(ACK)
                }
                else -> {
                    inbound?.add(request)
                    return emptyList()
                }
            }
        }

        private fun deviceDump(hi: Int, mid: Int, lo: Int, payload: ByteArray): ByteArray {
            val dump = MotifXsSysEx.bulkDump(0, hi, mid, lo, payload)
            dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
            dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
            return dump
        }

        /** An 82-byte Common block whose first 20 bytes are the name, NUL padded. */
        private fun commonPayload(name: String?, size: Int = 82): ByteArray =
            ByteArray(size).also { out ->
                val text = name.orEmpty()
                for (i in 0 until 20) out[i] = if (i < text.length) text[i].code.toByte() else 0
                for (i in 20 until out.size) out[i] = (i and 0x3F).toByte()
            }

        private companion object {
            /** `F0 43 60 02 F7` - the only acknowledgement ever observed, now known to mean
             * "accepted", with silence meaning "refused". */
            val ACK = byteArrayOf(0xF0.toByte(), 0x43, 0x60, 0x02, 0xF7.toByte())
        }
    }

    private fun instrument(
        scope: CoroutineScope,
        withConfig: MotifXsConfig = config,
        readOnlyBanks: Set<Int> = emptySet(),
        /** What the instrument answers at `0A 00 01`; null means it will not say. */
        mode: MotifXsMode? = MotifXsMode.VOICE,
        /** Whether a documented mode change actually takes, or is ignored. */
        modeIsSettable: Boolean = false,
        /**
         * How many mode *reads* still report the old mode after the change is sent.
         *
         * The instrument does not switch instantly - it tears down a Performance and loads a
         * voice - so a client that reads the mode straight back sees the old value. Zero means
         * instant, which is the one thing a real instrument never is.
         */
        modeSettlesAfterReads: Int = 0,
        /** The voice the edit buffer starts out holding, i.e. what the panel shows. */
        loadedVoiceName: String? = null,
        /** Answer the documented read with a Drum Voice's shape rather than a Normal Voice's. */
        drumShape: Boolean = false,
        /** Whether the Universal Device Inquiry is answered. False models an instrument
         * routing MIDI somewhere other than USB - it enumerates and opens, then says nothing. */
        answersIdentity: Boolean = true,
        /**
         * Favorite marks per bank **address-mid byte**, one raw byte per slot.
         *
         * A bank absent from this map answers nothing at all, which is what a real instrument does
         * for an address it does not recognise - so a test that expects a bank to be skipped and a
         * test that expects it to be read cannot be confused with one another.
         */
        favorites: Map<Int, ByteArray> = emptyMap(),
        /** Marks whose reply is corrupted on every attempt, to exercise the retry and the
         * per-bank failure isolation. */
        damagedFavorites: Set<Int> = emptySet(),
        /** The names the factory listing draws on; empty means no factory scope is offered. */
        factoryVoices: MotifXsFactoryVoices = MotifXsFactoryVoices(),
    ): Pair<MotifXsInstrument, FakeMidiTransport> {
        val slots = FakeSlots(readOnlyBanks)
        var currentMode = mode
        var pendingMode: MotifXsMode? = null
        var readsBeforeSettled = 0
        // What the instrument's own panel is showing: the voice in the Normal Voice edit buffer.
        // A selection loads one; a documented write to flash does **not** touch it, which is the
        // whole reason a rename can leave the panel showing a stale name.
        var loadedVoice: String? = loadedVoiceName
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request)
            when {
                request.size > 4 && request[1].toInt() == 0x7E ->
                    if (answersIdentity) listOf(MotifXsFixtures.identityReply) else emptyList()

                // A host bulk dump on the documented path: header, block or footer.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP &&
                    address != null && address.first in DOCUMENTED_ADDRESS_HI ->
                    slots.writeDocumented(request)

                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP ->
                    slots.write(request)   // a write draws no dump back, only an acknowledgement

                // A dump request at the Bulk Header address reads the whole voice, block by block.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_DUMP_REQUEST &&
                    address?.first == MotifXsSysEx.BULK_HEADER_HI -> {
                    val current = slots.read(
                        Triple(0x0C, address.second, address.third),
                        defaultVoiceAt(0x0C, address.second, address.third),
                    )
                    slots.readDocumented(
                        address.second,
                        address.third,
                        current?.let { MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(it)) },
                        drumShape,
                    )
                }

                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_PARAM_REQUEST &&
                    address == Triple(
                        MotifXsSysEx.MODE_ADDRESS_HI,
                        MotifXsSysEx.MODE_ADDRESS_MID,
                        MotifXsSysEx.MODE_ADDRESS_LO,
                    ) -> {
                    // A pending switch lands only once the instrument has had time to make it.
                    if (pendingMode != null) {
                        if (readsBeforeSettled <= 0) {
                            currentMode = pendingMode
                            pendingMode = null
                        } else {
                            readsBeforeSettled--
                        }
                    }
                    currentMode?.let { listOf(modeReply(it)) } ?: emptyList()
                }

                // The documented mode change. It draws **no reply of its own**, which is why the
                // instrument has to be asked afterwards what mode it ended up in.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_PARAM_CHANGE &&
                    address == Triple(
                        MotifXsSysEx.MODE_ADDRESS_HI,
                        MotifXsSysEx.MODE_ADDRESS_MID,
                        MotifXsSysEx.MODE_ADDRESS_LO,
                    ) -> {
                    if (modeIsSettable) {
                        pendingMode = MotifXsMode.of(request[8].toInt() and 0x7F)
                        readsBeforeSettled = modeSettlesAfterReads
                    }
                    emptyList()
                }

                // A selection is echoed back a set at a time - and the echo repeats what was
                // *sent* rather than reporting what happened, which is the whole reason the mode
                // has to be read separately.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_SELECT -> {
                    // The program set is the one that loads the voice into the edit buffer.
                    if ((request[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_PROGRAM) {
                        val slot = request[8].toInt() and 0x7F
                        loadedVoice = slots.read(
                            Triple(0x0C, 0x0A, slot), defaultVoiceAt(0x0C, 0x0A, slot),
                        )?.let { MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(it)) }
                    }
                    listOf(selectEcho(request))
                }

                // The edit buffer's name, one byte per request - what the panel is showing.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_PARAM_REQUEST &&
                    address != null && address.first == MotifXsSysEx.COMMON_HI &&
                    address.second == MotifXsSysEx.COMMON_MID -> {
                    val name = loadedVoice
                    // Silent when nothing is loaded, which is also how Performance mode behaves.
                    if (name == null) emptyList() else listOf(editBufferNameByte(name, address.third))
                }

                // The favorite marks. Ahead of the catch-all below, which would otherwise hand
                // back a voice dump - see defaultVoiceAt.
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_DUMP_REQUEST &&
                    address?.first == MotifXsSysEx.FAVORITES_ADDRESS_HI -> {
                    val mid = address.second
                    when {
                        mid in damagedFavorites -> listOf(
                            // Truncated after the address: the count says more is coming and the
                            // message ends anyway, which is what a dropped packet looks like.
                            favoritesDump(mid, favorites[mid] ?: ByteArray(0)).copyOfRange(0, 11)
                                .plus(MotifXsSysEx.SYSEX_END),
                        )
                        favorites.containsKey(mid) -> listOf(favoritesDump(mid, favorites.getValue(mid)))
                        else -> emptyList()
                    }
                }

                else -> listOfNotNull(
                    address?.let { slots.read(it, defaultVoiceAt(it.first, it.second, it.third)) }
                )
            }
        }
        return MotifXsInstrument(
            SysExExchange(transport, scope), withConfig, testBlanks, factoryVoices = factoryVoices,
        ) to transport
    }

    /** One byte of the edit buffer's name, NUL padded, as a `1n` parameter change. */
    private fun editBufferNameByte(name: String, offset: Int): ByteArray = byteArrayOf(
        MotifXsSysEx.SYSEX_START, MotifXsSysEx.MANUFACTURER.toByte(),
        MotifXsSysEx.TYPE_PARAM_CHANGE.toByte(),
        MotifXsSysEx.MODEL_HI.toByte(), MotifXsSysEx.MODEL_DEVICE.toByte(),
        MotifXsSysEx.COMMON_HI.toByte(), MotifXsSysEx.COMMON_MID.toByte(), offset.toByte(),
        (if (offset < name.length) name[offset].code else 0).toByte(),
        MotifXsSysEx.SYSEX_END,
    )

    /** The device's echo of one selection set: the same address and value, sent back as `1n`. */
    private fun selectEcho(request: ByteArray): ByteArray = byteArrayOf(
        MotifXsSysEx.SYSEX_START, MotifXsSysEx.MANUFACTURER.toByte(),
        MotifXsSysEx.TYPE_PARAM_CHANGE.toByte(),
        MotifXsSysEx.MODEL_HI.toByte(), MotifXsSysEx.MODEL_DEVICE.toByte(),
        request[5], request[6], request[7], request[8], MotifXsSysEx.SYSEX_END,
    )

    /** `F0 43 10 7F 0B 0A 00 01 <mode> F7` - what the instrument answers a mode request with. */
    private fun modeReply(mode: MotifXsMode): ByteArray = byteArrayOf(
        MotifXsSysEx.SYSEX_START, MotifXsSysEx.MANUFACTURER.toByte(),
        MotifXsSysEx.TYPE_PARAM_CHANGE.toByte(),
        MotifXsSysEx.MODEL_HI.toByte(), MotifXsSysEx.MODEL_DEVICE.toByte(),
        MotifXsSysEx.MODE_ADDRESS_HI.toByte(), MotifXsSysEx.MODE_ADDRESS_MID.toByte(),
        MotifXsSysEx.MODE_ADDRESS_LO.toByte(),
        mode.value.toByte(), MotifXsSysEx.SYSEX_END,
    )

    private companion object {
        /** Every address-hi a documented sequence uses: brackets, then Normal or Drum blocks. */
        val DOCUMENTED_ADDRESS_HI = setOf(
            MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.BULK_FOOTER_HI,
            0x40, 0x41, 0x42,   // Normal Voice: Common, element groups 1 and 2
            0x46, 0x47,         // Drum Voice: Common, element group
        )
    }

    @Test
    fun `the index walks every address and names the occupied ones`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val updates = motif.browser.index().toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(6, slots.size)
        assertEquals("TWE2 Wolf Walk", slots.first { it.displayId == "USER 1 - A:01" }.name)
        assertNull(slots.first { it.displayId == "USER 1 - A:02" }.name)
        assertEquals("TWE2 Dreadnought 2.0", slots.first { it.displayId == "USER 2 - A:03" }.name)
        assertEquals(6, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * A dump that arrives truncated is **retried**, not reported as a permanent failure.
     *
     * This is the defect a live 416-voice scan kept showing: a handful of slots read "arrived
     * damaged" with *zero* retries logged against them. The well-formedness check sat after the
     * exchange returned, so a corrupted dump satisfied the matcher, became "the reply", and the
     * retry machinery never saw a fault that is transient by nature.
     */
    @Test
    fun `a damaged dump is retried rather than failing the slot`() = runTest {
        var attempts = 0
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request) ?: return@FakeMidiTransport emptyList()
            val good = dumpAt(MotifXsFixtures.namedVoice, address.first, address.second, address.third)
            if (address.third == 0 && attempts++ == 0) {
                // Truncated in flight: the framer still emits F0..F7, so this *looks* like a
                // reply and is exactly what a dropped USB chunk produces.
                listOf(good.copyOfRange(0, good.size - 40) + 0xF7.toByte())
            } else {
                listOf(good)
            }
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 200.milliseconds, retries = 2),
            MotifXsConfig(
                banks = listOf(
                    MotifXsBank(label = "USR1", slotCount = 1, addressHi = 0x0C, addressMid = 0x0A,
                        displayLabel = "USER 1", selectLsb = 0x08),
                ),
            ),
            testBlanks,
        )
        val updates = motif.browser.index().toList()

        assertTrue("a damaged dump must not fail the slot", updates.filterIsInstance<IndexUpdate.Failed>().isEmpty())
        assertEquals(
            "TWE2 Wolf Walk",
            updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.single().name,
        )
        assertTrue("the damaged reply should have forced a second attempt", attempts >= 2)
    }

    @Test
    fun `every row carries its own bank label`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val slots = motif.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        // The panel's label, not the catalog shorthand: the header above these rows would
        // otherwise read "Bank USR1" over ids reading "USER 1 - A:01".
        assertEquals("USER 1", slots.first { it.displayId == "USER 1 - A:01" }.bankLabel)
        assertEquals("USER 2", slots.first { it.displayId == "USER 2 - A:01" }.bankLabel)
    }

    /**
     * A dump for the wrong address must not be accepted for this one.
     *
     * Not a hypothetical: at ~155 ms a voice, a late reply to the previous request is ordinary
     * traffic, and taking it would put one voice's name on another's row - a wrong answer that
     * looks entirely plausible in the list.
     */
    @Test
    fun `a reply for a different address is not accepted`() = runTest {
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request) ?: return@FakeMidiTransport emptyList()
            // Always answers with slot 0's dump, whatever was asked for.
            listOf(dumpAt(MotifXsFixtures.namedVoice, address.first, address.second, 0))
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )
        val updates = motif.browser.index().toList()
        val named = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        val failed = updates.filterIsInstance<IndexUpdate.Failed>()

        // Only the two slot-0 addresses are satisfied; the other four time out rather than
        // silently inheriting slot 0's name.
        assertEquals(2, named.size)
        assertEquals(4, failed.size)
        assertTrue(named.all { it.name == "TWE2 Wolf Walk" })
    }

    /** A damaged dump costs its own voice and nothing else - a large scan is likely to hit at
     * least one corrupted dump along the way, so a walk that gives up on the first one would
     * never finish. */
    @Test
    fun `a damaged dump fails one slot and the walk continues`() = runTest {
        val transport = FakeMidiTransport { request ->
            val (hi, mid, lo) = MotifXsSysEx.addressOf(request)
                ?: return@FakeMidiTransport emptyList()
            val dump = dumpAt(MotifXsFixtures.namedVoice, hi, mid, lo)
            if (lo == 1) dump[600] = (dump[600] + 1).toByte() // one flipped byte in slot 1
            listOf(dump)
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )
        val updates = motif.browser.index().toList()

        assertEquals(2, updates.filterIsInstance<IndexUpdate.Failed>().size)
        assertEquals(4, updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.size)
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    @Test
    fun `connect reads the firmware version out of the inquiry reply`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        motif.connect()
        assertEquals("Yamaha Motif XS", motif.identity.name)
        // USB, not MIDI: this instrument is matched by USB ids and has no MIDI port at all.
        // This assertion used to enshrine the hardcoded "MIDI" the identity reported.
        assertEquals(Bus.USB, motif.identity.bus)
        assertEquals("6.0.0.127", motif.identity.firmwareVersion)
    }

    /**
     * A transport that answers nothing at all is refused, not accepted with a blank firmware.
     *
     * **This used to assert the opposite**, on the reasoning that an unanswered inquiry is a
     * cosmetic loss and the voices are what the user came for. That holds for the inquiry alone -
     * see `an unanswered identity inquiry alone still connects` - but not for an instrument
     * answering nothing, which has no voices to offer either: every operation would time out on
     * its own, and none of them could explain why.
     */
    @Test
    fun `connect refuses an instrument that answers nothing at all`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0),
            config,
            testBlanks,
        )
        assertThrows(InstrumentException.NeedsManualSetting::class.java) {
            runBlocking { motif.connect() }
        }
    }

    /** Read-only for now, and the nulls say so - see MotifXsInstrument's class doc. */
    @Test
    fun `only the facets something has actually observed are offered`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        // Select, move and swap are all supported.
        assertNotNull(motif.selector)
        assertNotNull(motif.editor)
        // Four of these are one stored-slot `0C` write plus a `11 00 00` commit. RENAME is the
        // fifth and is not built on that path at all - it uses Yamaha's documented Bulk
        // Header/Footer sequence.
        assertEquals(
            setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY),
            motif.editor!!.supported,
        )
        // Still nothing anywhere about these.
        assertNull(motif.transfer)
        assertNull(motif.report)
    }

    // ---- Connecting to an instrument that is not listening on USB ----

    /**
     * **Total silence is refused, with the fix spelled out.**
     *
     * A Motif XS routes MIDI to one destination - DIN, USB or mLAN - and set to any but USB it
     * still enumerates, still opens, and then ignores everything. The session used to be built
     * anyway, so the user got a working-looking app in which each operation timed out separately
     * and none could say why.
     */
    @Test
    fun `an instrument answering nothing is refused, with the setting to change`() = runTest {
        val (motif, _) = instrument(backgroundScope, mode = null, answersIdentity = false)
        try {
            motif.connect()
            throw AssertionError("a silent instrument was accepted as a working session")
        } catch (expected: InstrumentException.NeedsManualSetting) {
            assertTrue(expected.message!!.contains("MIDI In/Out"))
            // The button sequence has to be usable while standing at the instrument.
            assertTrue(expected.steps.any { it.contains("UTILITY") })
            assertTrue(expected.steps.any { it.contains("[F5]") && it.contains("[SF2]") })
            assertTrue(expected.steps.any { it.contains("USB") })
            // Silence has other causes, and the app is inferring from an absence.
            assertTrue(expected.alsoCheck!!.contains("TO HOST"))
        }
    }

    /**
     * **One silent probe is not enough to refuse a session.**
     *
     * The identity reply is cosmetic and allowed to be missing on an instrument that otherwise
     * works, so the refusal above requires the Yamaha-specific mode request to be unanswered too.
     * Gating on the identity inquiry alone would lock out a working instrument.
     */
    @Test
    fun `an unanswered identity inquiry alone still connects`() = runTest {
        val (motif, _) = instrument(backgroundScope, mode = MotifXsMode.VOICE, answersIdentity = false)
        motif.connect()
        assertEquals(MotifXsInstrument.UNKNOWN_FIRMWARE, motif.identity.firmwareVersion)
    }

    // ---- Mode gating ----

    /**
     * A selection outside Voice mode is refused, and the message says what to press.
     *
     * In Performance and Song mode the three parameter sets draw **no echo at all** and change
     * nothing - not the Performance, not any part's voice assignment. Before this check the app
     * waited out its timeout and reported that the instrument had not answered, which is true and
     * tells a player nothing.
     */
    @Test
    fun `selection is refused outside voice mode`() = runTest {
        for (mode in listOf(MotifXsMode.PERFORMANCE, MotifXsMode.SONG, MotifXsMode.MASTER)) {
            val (motif, transport) = instrument(backgroundScope, mode = mode)
            // Called directly rather than through `runBlocking`, which would block the test
            // scheduler and stop the fake's mode reply from ever being delivered - the check
            // would then "pass" by timing out, which is the failure it exists to replace.
            val failure = try {
                motif.selector!!.select(SlotAddress(0, 0))
                null
            } catch (e: InstrumentException.BlockedByDeviceState) {
                e
            }
            assertNotNull("$mode should refuse a selection", failure)
            assertTrue(
                "the message should name the mode, was: ${failure!!.message}",
                failure.message!!.contains(mode.label),
            )
            // A fix is offered rather than only an obstacle reported.
            assertNotNull("a remedy should be offered", failure.remedy)
            assertNotNull(failure.remedyLabel)
            assertNotNull(failure.remedyDetail)
            // Refused before the selection goes out, not after it is ignored - and the mode is
            // *not* changed by merely being asked for one.
            assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
            assertTrue(
                "offering a remedy must not already have applied it",
                transport.sent.none { MotifXsSysEx.modeOf(it) != null },
            )
        }

    }

    /**
     * Taking the remedy sends the documented mode change, and then the selection works.
     *
     * The fake flips to Voice mode when it receives the parameter change, which is what lets the
     * retry be tested rather than only the send.
     */
    @Test
    fun `the offered remedy switches the instrument to voice mode`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope, mode = MotifXsMode.PERFORMANCE, modeIsSettable = true,
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull(blocked)

        blocked!!.remedy!!.invoke()
        val sentModeChange = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_PARAM_CHANGE &&
                MotifXsSysEx.addressOf(it) == Triple(0x0A, 0x00, 0x01)
        }
        assertEquals(MotifXsMode.VOICE.value, sentModeChange[8].toInt())

        // And the operation the block interrupted now succeeds.
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * A switch that takes a moment is **waited for**, not declared failed.
     *
     * Reading the mode immediately after sending the change would see the *old* value, since
     * changing mode is not instant, and would wrongly report the switch as having failed even
     * though it goes on to succeed.
     */
    @Test
    fun `a mode change that takes time still succeeds`() = runTest {
        val (motif, _) = instrument(
            backgroundScope,
            mode = MotifXsMode.PERFORMANCE,
            modeIsSettable = true,
            // Three reads still report Performance before the switch shows up.
            modeSettlesAfterReads = 3,
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull(blocked)

        // Must not throw: the instrument gets there, just not on the first read.
        blocked!!.remedy!!.invoke()
        // And the operation it was blocking now works.
        motif.selector!!.select(SlotAddress(0, 0))
    }

    /** A switch the instrument ignores is reported, not assumed. */
    @Test
    fun `a mode change that does not take is reported`() = runTest {
        val (motif, _) = instrument(
            backgroundScope, mode = MotifXsMode.PERFORMANCE, modeIsSettable = false,
        )
        val blocked = try {
            motif.selector!!.select(SlotAddress(0, 0))
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        val second = try {
            blocked!!.remedy!!.invoke()
            null
        } catch (e: InstrumentException.BlockedByDeviceState) {
            e
        }
        assertNotNull("a stuck mode should be reported, not assumed fixed", second)
        assertTrue(
            "the message should say it was still in the old mode, was: ${second!!.message}",
            second.message!!.contains("still in"),
        )
    }

    @Test
    fun `selection proceeds in voice mode`() = runTest {
        val (motif, transport) = instrument(backgroundScope, mode = MotifXsMode.VOICE)
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * An instrument that will not report its mode is not blocked from selecting.
     *
     * The check exists to explain a silent failure, not to invent a new one. An older firmware
     * that ignores the mode request must still be usable, so a missing answer falls through to
     * the selection - which then behaves exactly as it did before this check existed.
     */
    @Test
    fun `an unreadable mode does not block a selection`() = runTest {
        val (motif, transport) = instrument(backgroundScope, mode = null)
        motif.selector!!.select(SlotAddress(0, 0))
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT })
    }

    /**
     * Rename goes out as a documented sequence and the new name reads back.
     *
     * The vendor editor's own rename mechanism reaches only the edit buffer, not the stored slot.
     * Yamaha's own documented write path stores the name directly and survives a power cycle,
     * which is the path this method uses instead.
     */
    @Test
    fun `rename writes a documented sequence and the new name reads back`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")

        val documented = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first in DOCUMENTED_ADDRESS_HI
        }
        // Header, 24 blocks, footer - and nothing else on this path.
        assertEquals(26, documented.size)
        assertEquals(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.addressOf(documented.first())!!.first)
        assertEquals(MotifXsSysEx.BULK_FOOTER_HI, MotifXsSysEx.addressOf(documented.last())!!.first)

        // The header and footer carry the destination; the blocks do not.
        assertEquals(Triple(MotifXsSysEx.BULK_HEADER_HI, 0x0A, 0), MotifXsSysEx.addressOf(documented.first()))
        assertEquals(Triple(MotifXsSysEx.BULK_FOOTER_HI, 0x0A, 0), MotifXsSysEx.addressOf(documented.last()))

        // Every block goes out in *host* form. Echoing the instrument's own bytes back would be
        // wrong in the model byte and in the checksum that covers it.
        assertTrue(documented.all { it[4].toInt() == MotifXsSysEx.MODEL_HOST })
        assertTrue(documented.all { MotifXsSysEx.isWellFormedBulkDump(it) })

        // No store marker: the footer is this path's commit.
        assertTrue(
            "the documented path needs no 11 00 00",
            transport.sent.none {
                MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI
            },
        )
        assertEquals("Renamed In Place", motif.browser.refresh(SlotAddress(0, 0)).name)
    }

    /**
     * Renaming the voice the panel is showing reloads it, so the panel stops showing the old name.
     *
     * The documented write stores to Flash and does **not** touch the edit buffer, so without
     * this the instrument goes on displaying the previous name until the player reselects.
     */
    @Test
    fun `renaming the loaded voice reloads it so the panel updates`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope, loadedVoiceName = "TWE2 Wolf Walk",
        )
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")

        // A selection went out *after* the write, to reload the slot that was just renamed.
        val selects = transport.sent.filter { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT }
        assertEquals(3, selects.size)
        val lastWrite = transport.sent.indexOfLast { MotifXsSysEx.isBulkFooter(it) }
        val firstSelect = transport.sent.indexOfFirst { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT }
        assertTrue("the reload must follow the write", firstSelect > lastWrite)
    }

    /**
     * Renaming a voice that is *not* loaded leaves the player where they are.
     *
     * The reload is a fix for a stale display, not a licence to change what the instrument is
     * playing. Getting this wrong would yank a player onto a voice they never chose.
     */
    @Test
    fun `renaming an unloaded voice does not reload anything`() = runTest {
        val (motif, transport) = instrument(
            backgroundScope, loadedVoiceName = "Something Else",
        )
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed In Place")
        assertTrue(
            "no selection should be sent for a voice the panel is not showing",
            transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_SELECT },
        )
    }

    /** Only the Common block changes; the other 23 are returned exactly as they arrived. */
    @Test
    fun `rename alters the name bytes and nothing else`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.rename(SlotAddress(0, 0), "Short")

        val common = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP && MotifXsSysEx.isCommonBlock(it)
        }
        val payload = MotifXsSysEx.dumpPayload(common)
        assertEquals("Short", String(payload, 0, 5, Charsets.ISO_8859_1))
        // NUL padded, not space padded - what the instrument does, and what revision C0 of the
        // Data List permits where B0 does not.
        assertTrue((5 until 20).all { payload[it].toInt() == 0 })
        // Everything past the name is the filler the fake sent, untouched.
        assertTrue((20 until payload.size).all { payload[it].toInt() == (it and 0x3F) })
    }

    /** A name the field cannot hold is refused before anything is sent. */
    @Test
    fun `rename refuses a name the instrument cannot store`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val editor = motif.editor!!
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { editor.rename(SlotAddress(0, 0), "x".repeat(21)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { editor.rename(SlotAddress(0, 0), "   ") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { editor.rename(SlotAddress(0, 0), "Grüße") }
        }
        // Nothing left the building: there is no safe place to abort once a header has gone out,
        // so every one of these has to fail before the first byte.
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * An empty slot is refused - it would otherwise succeed and leave a named, empty voice.
     *
     * The fixture's slot 1 reads empty in both banks. This is the same care `copyProgram` takes
     * over an empty *source*, and it matters more here because the documented read answers for an
     * empty slot exactly as it does for a full one: there is no natural failure to rely on.
     */
    @Test
    fun `rename refuses an empty slot`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val failure = try {
            motif.editor!!.rename(SlotAddress(0, 1), "Not A Voice")
            null
        } catch (e: InstrumentException.NotSupported) {
            e
        }
        assertNotNull("an empty slot should be refused", failure)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * A **drum** voice renames too, and its sequence is a different shape entirely.
     *
     * Eight `46 xx` Common blocks and 73 `47 ee` elements, against a Normal Voice's eight
     * `40 xx` plus eight `41`/`42` pairs - 81 blocks against 24. Requiring the Normal Voice shape
     * would refuse every drum row after reading it, which is safe and useless. The name is the
     * first 20 bytes of `46 00 00` exactly as it is of `40 00 00`.
     */
    @Test
    fun `a drum voice renames, despite a completely different block sequence`() = runTest {
        val drumConfig = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "DRUM", slotCount = 1, addressHi = 0x0C, addressMid = 0x28,
                    displayLabel = "USER DR", selectLsb = 0x28),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, withConfig = drumConfig, drumShape = true)
        motif.editor!!.rename(SlotAddress(0, 0), "Renamed Kit")

        val sent = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first in DOCUMENTED_ADDRESS_HI
        }
        // Header, 81 blocks, footer - the instrument's shape, not a number this app knows.
        assertEquals(83, sent.size)
        assertEquals(MotifXsSysEx.BULK_HEADER_HI, MotifXsSysEx.addressOf(sent.first())!!.first)
        assertEquals(MotifXsSysEx.BULK_FOOTER_HI, MotifXsSysEx.addressOf(sent.last())!!.first)

        // The drum Common went back to 46 00 00, not to a Normal Voice's 40 00 00.
        val common = sent.single { MotifXsSysEx.isCommonBlock(it) }
        assertEquals(
            Triple(MotifXsSysEx.DRUM_COMMON_HI, 0x00, 0x00),
            MotifXsSysEx.addressOf(common),
        )
        assertEquals("Renamed Kit", String(MotifXsSysEx.dumpPayload(common), 0, 11, Charsets.ISO_8859_1))
    }

    /**
     * Copy, move and swap refuse to cross the drum boundary - because nothing else will.
     *
     * A drum kit and a normal voice are different objects, ~12.6 kB against ~1.9 kB with entirely
     * different block sequences, and the instrument **acknowledges a payload of the wrong kind
     * without complaint**: a normal voice offered to `0C 28 00` is acknowledged, and a drum kit
     * to `0C 0A 7F` likewise, without either being committed. Since the acknowledgement carries no
     * warning, the app cannot discover this by trying, and the rule has to live in the app.
     */
    @Test
    fun `copy, move and swap refuse to cross the drum boundary`() = runTest {
        val mixed = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "USR1", slotCount = 3, addressHi = 0x0C, addressMid = 0x0A,
                    displayLabel = "USER 1", selectLsb = 0x08),
                MotifXsBank(label = "DRUM", slotCount = 3, addressHi = 0x0C, addressMid = 0x28,
                    displayLabel = "USER DR", selectLsb = 0x28),
            ),
        )
        assertFalse("USR1 is not a drum bank", mixed.banks[0].isDrum)
        assertTrue("USER DR is", mixed.banks[1].isDrum)

        val normal = SlotAddress(0, 0)
        val drum = SlotAddress(1, 1)   // slot 1 reads empty in the fixture, so copy has a target
        for (op in listOf<Pair<String, suspend (PresetEditor) -> Unit>>(
            "copy" to { e -> e.copyProgram(normal, drum) },
            "move" to { e -> e.move(normal, drum) },
            "swap" to { e -> e.swap(normal, drum) },
        )) {
            val (name, run) = op
            val (motif, transport) = instrument(backgroundScope, withConfig = mixed)
            val failure = try {
                run(motif.editor!!)
                null
            } catch (e: InstrumentException.NotSupported) {
                e
            }
            assertNotNull("$name across the drum boundary should be refused", failure)
            // Refused before anything is offered to the instrument, which matters here more than
            // usual: an accepted-but-uncommitted write stays armed until something commits it.
            assertTrue(
                "$name must send nothing",
                transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP },
            )
        }
    }

    /** Same-kind edits are unaffected. */
    @Test
    fun `copy within a bank still works`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val name = motif.editor!!.copyProgram(SlotAddress(0, 0), SlotAddress(0, 1))
        assertEquals("TWE2 Wolf Walk", name)
    }

    /**
     * Delete picks the blank matching the bank's kind - the drum blank for a drum bank, not the
     * normal-voice one - and works even in a fully populated bank, such as a USER DR with all 32
     * kits occupied and nothing empty to fall back on.
     */
    @Test
    fun `delete writes the drum blank in a drum bank`() = runTest {
        val fullBank = MotifXsConfig(
            banks = listOf(
                // Every slot named, and none of it matters: there is no fallback to avoid.
                MotifXsBank(label = "DRUM", slotCount = 2, addressHi = 0x0C, addressMid = 0x28,
                    displayLabel = "USER DR", selectLsb = 0x28),
            ),
        )
        val shipped = MotifXsBlanks(normal = ByteArray(64) { 1 }, drum = ByteArray(64) { 2 })
        val slots = FakeSlots()
        val transport = FakeMidiTransport { request ->
            val address = MotifXsSysEx.addressOf(request)
            when {
                MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP -> slots.write(request)
                // Both slots always read as a named voice - no empty one anywhere.
                else -> listOfNotNull(address?.let {
                    slots.read(it, dumpAt(MotifXsFixtures.namedVoice, it.first, it.second, it.third))
                })
            }
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope), fullBank, shipped,
        )
        motif.editor!!.delete(SlotAddress(0, 0))

        // The drum blank went out, not the normal one, and not a payload scavenged from a slot.
        val written = transport.sent.single {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(it)?.first == 0x0C
        }
        assertTrue(
            "the shipped drum blank should have been written",
            MotifXsSysEx.dumpPayload(written).contentEquals(shipped.drum),
        )
    }

    /** A factory bank is refused, as it is for every other edit. */
    @Test
    fun `rename refuses a read-only bank`() = runTest {
        val readOnly = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "PRE1", slotCount = 3, addressHi = 0x0C, addressMid = 0x00,
                    displayLabel = "PRE 1", readOnly = true),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, withConfig = readOnly)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { motif.editor!!.rename(SlotAddress(0, 0), "Nope") }
        }
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Delete erases the stored slot, and does it the way move clears its source. */
    @Test
    fun `delete writes an initialised voice over the slot`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.delete(SlotAddress(0, 0))

        val dumps = transport.sent.filter {
            MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP
        }
        val marker = Triple(MotifXsSysEx.STORE_HI, MotifXsSysEx.STORE_MID, MotifXsSysEx.STORE_LO)
        val writes = dumps.filter { MotifXsSysEx.addressOf(it) != marker }

        assertEquals(1, writes.size)
        // Written to the slot asked for...
        assertEquals(Triple(0x0C, 0x0A, 0), MotifXsSysEx.addressOf(writes.single()))
        // ...carrying the shipped blank for a normal voice, not a payload scavenged from a slot.
        assertTrue(
            MotifXsSysEx.dumpPayload(writes.single()).contentEquals(testBlanks.normal),
        )
        // ...and committed - without this the instrument acknowledges the write and keeps
        // holding the old voice.
        assertEquals(marker, MotifXsSysEx.addressOf(dumps.last()))
    }

    /**
     * An uncommitted write is not visible, and that is the instrument's behaviour rather than the
     * fake's convenience.
     *
     * Worth its own test because the failure it guards is silent: an edit could acknowledge every
     * write and report success while changing nothing on the instrument if the commit were
     * missing. This is the test that would catch that.
     */
    @Test
    fun `a write is not stored until the marker commits it`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val before = motif.browser.refresh(SlotAddress(0, 0)).name
        assertNotNull(before)

        // The write on its own - exactly what the app used to send - changes nothing.
        transport.send(
            MotifXsSysEx.bulkDump(
                0, 0x0C, 0x0A, 0,
                MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice),
            )
        )
        assertEquals(before, motif.browser.refresh(SlotAddress(0, 0)).name)

        // The marker is what applies it.
        transport.send(MotifXsSysEx.storeMarker(0))
        assertNull(motif.browser.refresh(SlotAddress(0, 0)).name)
    }

    /**
     * Swap really exchanges the two slots' contents.
     *
     * Asserted by reading both back afterwards rather than by counting messages: the point of a
     * swap is the end state, and a test that checked only "four writes happened" would pass on an
     * implementation that wrote each voice back over itself.
     */
    @Test
    fun `swap exchanges the two slots`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val a = SlotAddress(0, 0)   // named
        val b = SlotAddress(0, 2)   // long-named
        val before = motif.browser.refresh(a).name to motif.browser.refresh(b).name
        assertNotEquals(before.first, before.second)

        motif.editor!!.swap(a, b)

        assertEquals(before.second, motif.browser.refresh(a).name)
        assertEquals(before.first, motif.browser.refresh(b).name)
    }

    /**
     * Move puts the voice at the destination **and clears the source**.
     *
     * The second half is the destructive one, and it is what makes move and delete the same
     * operation underneath - see the editor's own note on why delete is offered.
     */
    @Test
    fun `move carries the voice across and empties the slot it came from`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val from = SlotAddress(0, 0)   // named
        val to = SlotAddress(1, 0)     // named, in the other bank - will be overwritten
        val moving = motif.browser.refresh(from).name
        assertNotNull(moving)

        motif.editor!!.move(from, to)

        assertEquals(moving, motif.browser.refresh(to).name)
        assertNull("the source slot must read empty after a move", motif.browser.refresh(from).name)
    }

    /**
     * Copy duplicates the voice and **leaves the source alone** - the one thing that separates it
     * from move, which is otherwise the same write and the same commit.
     *
     * This works in both directions, within a bank and across two, and the destination matches
     * the source byte for byte each time.
     */
    @Test
    fun `copy duplicates the voice and leaves the source where it was`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val from = SlotAddress(0, 0)   // named
        val to = SlotAddress(1, 1)     // empty, in the other bank
        val source = motif.browser.refresh(from).name
        assertNotNull(source)

        val stored = motif.editor!!.copyProgram(from, to)

        assertEquals(source, stored)
        assertEquals(source, motif.browser.refresh(to).name)
        assertEquals("the source must survive a copy", source, motif.browser.refresh(from).name)
    }

    /**
     * An occupied destination is refused **before anything is written**.
     *
     * Nothing at the other end enforces this: a well-formed dump aimed at an occupied writable
     * slot is simply accepted, so the instrument would overwrite it without complaint. The
     * contract says "into the empty slot dst", and this is the only thing keeping it.
     */
    @Test
    fun `copy onto an occupied slot is refused without writing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        // Not `runBlocking` inside `assertThrows`: this refusal has to read both slots first, and
        // blocking the test thread stops the fake device's replies from ever being delivered - the
        // call then fails on a timeout, which would pass an `assertThrows` for the wrong reason.
        val refusal = runCatching {
            motif.editor!!.copyProgram(SlotAddress(0, 0), SlotAddress(1, 0))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.NotSupported)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Copying an empty slot is refused too - there is nothing to duplicate. */
    @Test
    fun `copy of an empty slot is refused without writing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        val refusal = runCatching {
            motif.editor!!.copyProgram(SlotAddress(0, 1), SlotAddress(1, 1))
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $refusal", refusal is InstrumentException.NotSupported)
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** Moving or swapping a slot onto itself is a no-op, not two writes. */
    @Test
    fun `an operation onto the same slot writes nothing`() = runTest {
        val (motif, transport) = instrument(backgroundScope)
        motif.editor!!.move(SlotAddress(0, 0), SlotAddress(0, 0))
        motif.editor!!.swap(SlotAddress(0, 1), SlotAddress(0, 1))
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /** A factory bank is refused before anything is sent, not after a failed write. */
    @Test
    fun `writing a read-only bank is refused before it is attempted`() = runTest {
        val withFactory = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "USR1", slotCount = 3, addressHi = 0x0C, addressMid = 0x0A,
                    displayLabel = "USER 1", selectLsb = 0x08),
                MotifXsBank(label = "PRE1", slotCount = 3, addressHi = 0x0C, addressMid = 0x00,
                    displayLabel = "PRE1", readOnly = true, indexByDefault = false),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, withFactory)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { motif.editor!!.swap(SlotAddress(0, 0), SlotAddress(1, 0)) }
        }
        assertTrue(transport.sent.none { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP })
    }

    /**
     * The two sources of browser rows must agree on a bank's label.
     *
     * `index()` builds rows from dumps; the view model builds placeholder rows for unread slots
     * from `SlotLayout`'s [BankSpec]s. The browser groups by `bankLabel`, so if those two ever
     * disagree - as they did the moment the layout started carrying panel labels while
     * `readSlot` still carried catalog shorthand - every bank grows a second header and its rows
     * split across both.
     */
    @Test
    fun `indexed rows and layout banks label a bank the same way`() = runTest {
        val (motif, _) = instrument(backgroundScope)
        val slots = motif.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertTrue(slots.isNotEmpty())
        for (slot in slots) {
            assertEquals(motif.layout.banks[slot.address.bank].label, slot.bankLabel)
        }
    }

    /**
     * A full index skips the factory banks.
     *
     * Walking all 15 banks costs about 7.5 minutes against 93 s for the user banks, and the
     * factory ones are read-only and never change. The saving is the point of the flag, so the
     * test asserts the *requests*, not just the rows: a bank that is skipped must cost nothing.
     */
    @Test
    fun `a full index walks only the banks marked indexByDefault`() = runTest {
        val scoped = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "USR1", slotCount = 3, addressHi = 0x0C, addressMid = 0x0A,
                    displayLabel = "USER 1"),
                MotifXsBank(label = "PRE1", slotCount = 3, addressHi = 0x0C, addressMid = 0x00,
                    displayLabel = "PRE1", readOnly = true, indexByDefault = false),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, scoped)
        val updates = motif.browser.index().toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(3, slots.size)
        assertTrue(slots.all { it.bankLabel == "USER 1" })
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /** ...and the skipped bank is still reachable, which is what makes skipping it acceptable. */
    @Test
    fun `a factory bank can be indexed on demand`() = runTest {
        val scoped = MotifXsConfig(
            banks = listOf(
                MotifXsBank(label = "USR1", slotCount = 3, addressHi = 0x0C, addressMid = 0x0A,
                    displayLabel = "USER 1"),
                MotifXsBank(label = "PRE1", slotCount = 3, addressHi = 0x0C, addressMid = 0x00,
                    displayLabel = "PRE1", readOnly = true, indexByDefault = false),
            ),
        )
        val (motif, transport) = instrument(backgroundScope, scoped)
        val updates = (motif.browser as MotifXsInstrument).indexBank(1).toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

        assertEquals(3, slots.size)
        assertTrue(slots.all { it.bankLabel == "PRE1" })
        assertEquals(3, transport.sent.count { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_DUMP_REQUEST })
        assertTrue(updates.last() is IndexUpdate.Complete)
    }

    /**
     * The two worked examples are how the instrument's own display renders these addresses:
     * `DRUM:031` shows as `USER DR - B:15`, and `USR3:053` as `USER 3 - D:05`.
     */
    @Test
    fun `addresses render as the instrument displays them, in lettered groups of sixteen`() {
        val format = MotifXsAddressFormat(
            listOf("USR1", "USR2", "USR3", "DRUM"),
            listOf("USER 1", "USER 2", "USER 3", "USER DR"),
        )
        assertEquals("USER DR - B:15", format.format(SlotAddress(3, 30)))
        assertEquals("USER 3 - D:05", format.format(SlotAddress(2, 52)))
        assertEquals("USER 1 - A:01", format.format(SlotAddress(0, 0)))
        assertEquals("USER 3 - H:16", format.format(SlotAddress(2, 127)))
    }

    @Test
    fun `the displayed form round-trips, and the flat form still parses`() {
        val format = MotifXsAddressFormat(
            listOf("USR1", "USR2", "USR3", "DRUM"),
            listOf("USER 1", "USER 2", "USER 3", "USER DR"),
        )
        for (address in listOf(SlotAddress(3, 30), SlotAddress(2, 52), SlotAddress(0, 0))) {
            assertEquals(address, format.parse(format.format(address)))
        }
        // The flat form is a plain shorthand still worth supporting, so it has to keep working.
        assertEquals(SlotAddress(3, 0), format.parse("DRUM:001"))
        assertEquals(SlotAddress(1, 44), format.parse("USR2:045"))
        assertEquals(SlotAddress(2, 52), format.parse("USR3:053"))
    }

    /**
     * **A write and its commit are one unit, and cancelling must not split them.**
     *
     * `commit()` applies everything offered since the last commit, so an acknowledged-but-
     * uncommitted write is not discarded when its coroutine dies - it is armed, and some later,
     * unrelated edit's commit applies it. `NonCancellable` on the send loop inside
     * `exchangeAfterAll` does not cover this: delete, copy, move and swap do not use that path,
     * they issue plain exchanges and then commit separately.
     *
     * Cancellation is delivered from inside the transport, the moment the write goes out, which
     * puts it exactly in the window this is about rather than relying on timing.
     */
    @Test
    fun `a cancelled delete still commits the write it already sent`() = runTest {
        val sent = mutableListOf<ByteArray>()
        var job: Job? = null
        val transport = FakeMidiTransport { request ->
            sent += request
            val address = MotifXsSysEx.addressOf(request)
            // The write has just been accepted; the commit has not gone out yet.
            if (address != null && address.first == 0x0C) job?.cancel()
            // `F0 43 60 02 F7` - the same acknowledgement FakeSlots serves, restated here
            // because that one is private to it.
            listOf(byteArrayOf(0xF0.toByte(), 0x43, 0x60, 0x02, 0xF7.toByte()))
        }
        val motif = MotifXsInstrument(
            SysExExchange(transport, backgroundScope, defaultTimeout = 200.milliseconds),
            MotifXsConfig(
                banks = listOf(
                    MotifXsBank(label = "USR1", slotCount = 1, addressHi = 0x0C, addressMid = 0x0A,
                        displayLabel = "USER 1", selectLsb = 0x08),
                ),
            ),
            testBlanks,
        )

        job = launch(start = CoroutineStart.LAZY) {
            runCatching { motif.editor.delete(SlotAddress(0, 0)) }
        }
        job!!.start()
        job!!.join()

        val committed = sent.any { MotifXsSysEx.addressOf(it)?.first == MotifXsSysEx.STORE_HI }
        assertTrue(
            "an accepted write left uncommitted is armed, not discarded - a later unrelated " +
                "commit would apply it",
            committed,
        )
    }
}

/**
 * A `0C` payload that decodes to [name] - the inverse of what [MotifXsVoice.nameOf] reads.
 *
 * Synthesized rather than taken from a fixture because the fake has to answer a `0C` read *after*
 * a documented rename, and no fixture exists for a name chosen by a test. It reproduces the real
 * serialisation's three features that the reader depends on: the leading figure pair, the name,
 * and the repeat six bytes past its end.
 */
private fun syntheticVoicePayload(name: String): ByteArray {
    val dense = ByteArray(160)
    var at = 0
    "0:0:".forEach { dense[at++] = it.code.toByte() }
    name.forEach { dense[at++] = it.code.toByte() }
    at += MotifXsVoice.NAME_TRAILER_BYTES     // left as NUL: stops the printable run cleanly
    name.forEach { dense[at++] = it.code.toByte() }
    return byteArrayOf(0, 0) + packMsb(dense)
}

/** The inverse of [MotifXsVoice.unpack]: one high-bit byte carrying the next seven. */
private fun packMsb(dense: ByteArray): ByteArray {
    val out = ArrayList<Byte>(dense.size * 8 / 7 + 8)
    var i = 0
    while (i < dense.size) {
        var msb = 0
        val chunk = ArrayList<Byte>(7)
        for (j in 0 until 7) {
            if (i + j >= dense.size) break
            val b = dense[i + j].toInt() and 0xFF
            if (b and 0x80 != 0) msb = msb or (1 shl j)
            chunk.add((b and 0x7F).toByte())
        }
        out.add(msb.toByte())
        out.addAll(chunk)
        i += 7
    }
    return out.toByteArray()

}
