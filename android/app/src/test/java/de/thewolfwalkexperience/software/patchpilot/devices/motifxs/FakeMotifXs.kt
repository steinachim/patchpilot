// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport

/**
 * Re-addresses a sample dump to [hi]/[mid]/[lo] and re-checksums it, so the fake device can
 * answer six addresses from three recordings. Only the address bytes change; every other byte
 * is still what the instrument sent.
 */
internal fun dumpAt(source: ByteArray, hi: Int, mid: Int, lo: Int): ByteArray {
    val out = source.copyOf()
    out[MotifXsSysEx.BULK_ADDRESS_INDEX] = hi.toByte()
    out[MotifXsSysEx.BULK_ADDRESS_INDEX + 1] = mid.toByte()
    out[MotifXsSysEx.BULK_ADDRESS_INDEX + 2] = lo.toByte()
    out[out.size - 2] = MotifXsSysEx.checksumOf(out).toByte()
    return out
}

/**
 * [payload] framed as the *device* sends a bulk dump: the instrument speaks model `7F 0B` where
 * the host speaks `7F 03`, so a fake that echoed the host's bytes back would hide any confusion
 * between the two.
 */
internal fun deviceDump(hi: Int, mid: Int, lo: Int, payload: ByteArray): ByteArray {
    val dump = MotifXsSysEx.bulkDump(0, hi, mid, lo, payload)
    dump[4] = MotifXsSysEx.MODEL_DEVICE.toByte()
    dump[dump.size - 2] = MotifXsSysEx.checksumOf(dump).toByte()
    return dump
}

/** Every address-hi a documented sequence uses: brackets, then Normal or Drum blocks. */
internal val DOCUMENTED_ADDRESS_HI = setOf(
    MotifXsSysEx.BULK_HEADER_HI,
    MotifXsSysEx.BULK_FOOTER_HI,
    0x40,
    0x41,
    0x42, // Normal Voice: Common, element groups 1 and 2
    0x46,
    0x47, // Drum Voice: Common, element group
)

/**
 * A fake that **remembers what is written to it**.
 *
 * A stateless one cannot test a write at all: the editor verifies every write by reading the
 * slot straight back, so a fake that always replies with its canned fixture makes a correct
 * write look like a failed one. Modelling the store is the minimum needed for the read-back
 * to mean anything - and it is also what makes a *wrong* address or payload fail the test,
 * which a fake that ignored writes could never do.
 */
internal class FakeMotifXsSlots(private val readOnly: Set<Int> = emptySet()) {
    private val written = mutableMapOf<Triple<Int, Int, Int>, ByteArray>()
    private val pending = mutableMapOf<Triple<Int, Int, Int>, ByteArray>()

    /** Applies a commit but leaves it unacknowledged, so whether it took cannot be told. */
    var silentCommit = false

    /** Stores every committed write with one data bit flipped, so a read-back disagrees. */
    var misstore = false

    /**
     * Answers a host bulk dump the way the instrument does: a write is acknowledged and
     * *held*, and only `11 00 00` stores it. Writes wait in [pending] until a marker arrives,
     * so a code path that forgets to commit fails here rather than only on real hardware.
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
            pending.forEach { (where, payload) ->
                written[where] =
                    if (misstore) {
                        payload.copyOf().also {
                            it[it.size / 2] =
                                (it[it.size / 2].toInt() xor 0x01).toByte()
                        }
                    } else {
                        payload
                    }
            }
            pending.clear()
            return if (silentCommit) emptyList() else listOf(ACK)
        }
        if (!MotifXsSysEx.isWellFormedBulkDump(request)) return emptyList()
        if (address.second in readOnly) return emptyList()
        pending[address] = MotifXsSysEx.dumpPayload(request)
        return listOf(ACK)
    }

    fun read(address: Triple<Int, Int, Int>, default: ByteArray?): ByteArray? {
        val payload = written[address] ?: return default
        return deviceDump(address.first, address.second, address.third, payload)
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
        if (drum) {
            return buildList {
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

    /** An 82-byte Common block whose first 20 bytes are the name, NUL padded. */
    private fun commonPayload(name: String?, size: Int = 82): ByteArray =
        ByteArray(size).also { out ->
            val text = name.orEmpty()
            for (i in 0 until 20) out[i] = if (i < text.length) text[i].code.toByte() else 0
            for (i in 20 until out.size) out[i] = (i and 0x3F).toByte()
        }

    companion object {
        /** `F0 43 60 02 F7` - the only acknowledgement observed: "accepted", with silence meaning "refused". */
        val ACK = byteArrayOf(0xF0.toByte(), 0x43, 0x60, 0x02, 0xF7.toByte())
    }
}

/**
 * A scripted Motif XS around [FakeMotifXsSlots]: it answers the identity inquiry, voice and
 * documented reads, writes, mode reads and changes, selections and the edit buffer's name, as the
 * instrument does. The constructor's parameters are the knobs a test turns.
 */
internal class FakeMotifXs(
    readOnlyBanks: Set<Int> = emptySet(),
    /** What the instrument answers at `0A 00 01`; null means it will not say. */
    mode: MotifXsMode? = MotifXsMode.VOICE,
    /** Whether a documented mode change actually takes, or is ignored. */
    private val modeIsSettable: Boolean = false,
    /**
     * How many mode *reads* still report the old mode after the change is sent.
     *
     * The instrument does not switch instantly - it tears down a Performance and loads a
     * voice - so a client that reads the mode straight back sees the old value. Zero means
     * instant, which is the one thing a real instrument never is.
     */
    private val modeSettlesAfterReads: Int = 0,
    /** The voice the edit buffer starts out holding, i.e. what the panel shows. */
    loadedVoiceName: String? = null,
    /** Answer the documented read with a Drum Voice's shape rather than a Normal Voice's. */
    private val drumShape: Boolean = false,
    /**
     * Whether the Universal Device Inquiry is answered. False models an instrument
     * routing MIDI somewhere other than USB - it enumerates and opens, then says nothing.
     */
    private val answersIdentity: Boolean = true,
    /** Sees every request before the device answers it - where a test cancels mid-operation. */
    private val onRequest: (ByteArray) -> Unit = {},
    /** See [FakeMotifXsSlots.silentCommit]. */
    silentCommit: Boolean = false,
    /** See [FakeMotifXsSlots.misstore]. */
    misstore: Boolean = false,
    /** Echoes the program set with the next program's number, as if another selection had landed. */
    private val echoesOtherProgram: Boolean = false,
    /** The bank a selection's LSB names, as its dump address-mid byte: USER 1 and USER 2 by default. */
    private val bankMidForLsb: Map<Int, Int> = mapOf(0x08 to 0x0A, 0x09 to 0x0B),
) {
    val slots = FakeMotifXsSlots(readOnlyBanks).also {
        it.silentCommit = silentCommit
        it.misstore = misstore
    }
    private var currentMode = mode
    private var pendingMode: MotifXsMode? = null
    private var readsBeforeSettled = 0

    /** The bank the last selection's LSB named; a program change loads from it. */
    private var selectedBankMid = 0x0A

    // What the instrument's own panel is showing: the voice in the Normal Voice edit buffer.
    // A selection loads one; a documented write to flash does **not** touch it, which is the
    // whole reason a rename can leave the panel showing a stale name.
    private var loadedVoice: String? = loadedVoiceName
    val transport = FakeMidiTransport { request ->
        onRequest(request)
        val address = MotifXsSysEx.addressOf(request)
        when {
            request.size > 4 && request[1].toInt() == 0x7E ->
                if (answersIdentity) listOf(MotifXsFixtures.identityReply) else emptyList()

            // A host bulk dump on the documented path: header, block or footer.
            MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP &&
                address != null && address.first in DOCUMENTED_ADDRESS_HI ->
                slots.writeDocumented(request)

            MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_BULK_DUMP ->
                slots.write(request)

            // a write draws no dump back, only an acknowledgement

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

            // A selection is echoed back a set at a time, in Voice mode only; outside it the
            // instrument ignores the selection (see `selection is refused outside voice mode`).
            // An instrument that does not say its mode is taken to be in Voice mode.
            MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_SELECT -> {
                if (currentMode != null && currentMode != MotifXsMode.VOICE) return@FakeMidiTransport emptyList()
                if ((request[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_BANK_LSB) {
                    selectedBankMid = bankMidForLsb[request[8].toInt() and 0x7F] ?: selectedBankMid
                }
                // The program set is the one that loads the voice into the edit buffer.
                if ((request[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_PROGRAM) {
                    val slot = request[8].toInt() and 0x7F
                    loadedVoice = slots.read(
                        Triple(0x0C, selectedBankMid, slot),
                        defaultVoiceAt(0x0C, selectedBankMid, slot),
                    )?.let { MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(it)) }
                }
                val echo = selectEcho(request)
                if (echoesOtherProgram && (request[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_PROGRAM) {
                    echo[8] = ((echo[8].toInt() + 1) and 0x7F).toByte()
                }
                listOf(echo)
            }

            // The edit buffer's name, one byte per request - what the panel is showing.
            MotifXsSysEx.typeOf(request) == MotifXsSysEx.TYPE_PARAM_REQUEST &&
                address != null && address.first == MotifXsSysEx.COMMON_HI &&
                address.second == MotifXsSysEx.COMMON_MID -> {
                val name = loadedVoice
                // Silent when nothing is loaded, which is also how Performance mode behaves.
                if (name == null) emptyList() else listOf(editBufferNameByte(name, address.third))
            }

            else -> listOfNotNull(
                address?.let { slots.read(it, defaultVoiceAt(it.first, it.second, it.third)) },
            )
        }
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
    at += MotifXsVoice.NAME_TRAILER_BYTES // left as NUL: stops the printable run cleanly
    name.forEach { dense[at++] = it.code.toByte() }
    return byteArrayOf(0, 0) + packMsb(dense)
}

/** The inverse of [MotifXsVoice.unpack]: one high-bit byte carrying the next seven. */
internal fun packMsb(dense: ByteArray): ByteArray {
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
