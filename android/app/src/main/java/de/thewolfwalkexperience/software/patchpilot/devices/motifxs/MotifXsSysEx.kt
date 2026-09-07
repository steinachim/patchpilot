package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * Message construction and validation for the Yamaha Motif XS SysEx protocol.
 *
 * Standard Yamaha SysEx framing: `F0 43 <type><device> 7F <model> ... F7`. Covers both the
 * undocumented bulk-dump extension the vendor editor uses for browsing and editing voices, and
 * Yamaha's own documented parameter and bulk-dump messages (mode, selection, the Bulk
 * Header/Footer rename path). Some behaviors here are best-effort and may not generalize to
 * firmware revisions or instrument modes this implementation has not exercised.
 */
object MotifXsSysEx {

    const val SYSEX_START = 0xF0.toByte()
    const val SYSEX_END = 0xF7.toByte()

    /** Yamaha. */
    const val MANUFACTURER = 0x43

    /**
     * Bank bytes at or above this are drum banks: `20` PRE DR, `21` GM DR, `28` USER DR.
     *
     * Here rather than at the top level of `MotifXsFamily.kt`, where it was the only protocol
     * constant in the file that defines the catalog shape - and the only one anywhere in this
     * family outside this object.
     */
    const val FIRST_DRUM_BANK = 0x20

    /**
     * Two model ids exist, split by **who is speaking**: everything the host sends carries
     * [MODEL_HOST], everything the instrument sends carries [MODEL_DEVICE]. Which one to send is
     * all this app needs to know; why the two values differ is not otherwise relevant here.
     */
    const val MODEL_HI = 0x7F
    const val MODEL_HOST = 0x03
    const val MODEL_DEVICE = 0x0B

    /** Message type, in the high nibble of byte 2; the low nibble is the device number. */
    const val TYPE_BULK_DUMP = 0x00
    const val TYPE_PARAM_CHANGE = 0x10
    const val TYPE_DUMP_REQUEST = 0x20
    const val TYPE_PARAM_REQUEST = 0x30

    /**
     * Host -> device "parameter set", used only for selecting a voice.
     *
     * Selection uses this type where other parameter writes use [TYPE_PARAM_CHANGE]. Both carry
     * [MODEL_HOST] and an address, and the device answers both with [TYPE_PARAM_CHANGE].
     */
    const val TYPE_SELECT = 0x40

    /** Device -> host acknowledgement of a bulk dump the host sent: `F0 43 60 02 F7`. */
    const val TYPE_ACK = 0x60

    const val TYPE_INDEX = 2

    /** A bulk dump's byte count, and the address that follows it. */
    const val COUNT_HI_INDEX = 5
    const val BULK_ADDRESS_INDEX = 7

    /**
     * Where the address sits in everything that is *not* a bulk dump.
     *
     * **Two bytes earlier, because only a dump carries a byte count.** A request is
     * `F0 43 2n 7F 03 <addr> F7` and a dump is `F0 43 0n 7F 0B <count> <addr> <data> <sum> F7`, so
     * reading a request at the dump's offset picks up its last address byte and its `F7`. That is
     * not hypothetical - it is what [addressOf] did until a test fake asked a request what address
     * it was for and got null, which then failed every read for the right reason by accident.
     */
    const val REQUEST_ADDRESS_INDEX = 5

    /** The shortest message worth parsing: `F0 43 2n 7F 03 <addr> F7`. */
    private const val MIN_MESSAGE_SIZE = 9

    /**
     * A bulk dump's fixed overhead: `F0 43 0n 7F 0B` (5) + count (2) + address (3) + checksum (1)
     * + `F7` (1). The declared byte count covers the **data only**, so a whole message is
     * `count + 12` bytes.
     */
    const val BULK_OVERHEAD = 12

    /** `F0 43 2n 7F 03 <addr> F7` - nine bytes, no payload. */
    fun requestDump(device: Int, addressHi: Int, addressMid: Int, addressLo: Int): ByteArray =
        byteArrayOf(
            SYSEX_START,
            MANUFACTURER.toByte(),
            (TYPE_DUMP_REQUEST or (device and 0x0F)).toByte(),
            MODEL_HI.toByte(),
            MODEL_HOST.toByte(),
            (addressHi and 0x7F).toByte(),
            (addressMid and 0x7F).toByte(),
            (addressLo and 0x7F).toByte(),
            SYSEX_END,
        )

    /**
     * The favorited-voice marks: a second address family parallel to the `0C` voice extension,
     * reusing its bank byte at a different high byte.
     *
     * One small dump per bank at `71 mm 00`, where `mm` is the bank's own [MotifXsBank.addressMid]
     * - the same selector as a voice dump. The payload is **one raw byte per slot**, indexed by
     * zero-based slot: `0` for unmarked, and `1`, `2` or `3` for marked - see the note below on
     * what each of those three means.
     *
     * **Not MSB-packed.** Every other payload this app reads from a `0C` address is packed 7 bits
     * per byte and has to be unpacked; this one is not, and running the unpacker over it produces
     * plausible-looking nonsense rather than an error.
     *
     * **Never sweep the middle byte.** An unmapped address is not merely refused - it puts an
     * *Illegal Bulk Data* message on the instrument's own screen, in front of the user, once per
     * attempt. `mm = 0x08` is a permanent hole between PRE8 and GM. Only ever pass a value that
     * came out of the catalog's bank table.
     *
     * **The mark values are decoded**, and they are not a boolean or a bitmask. The byte names
     * which of the voice's *own two category assignments* the instrument's browser files the
     * favorite under: `0` not favorited, `1` both, `2` Category 1 only, `3` Category 2 only.
     * Anything else is stored verbatim and lists the voice under neither - which is what rules
     * the bitmask reading out, since a third bit produced no third listing.
     *
     * **Writable, and confirmed on hardware** - see [writeFavorites] for the four rules that
     * differ from the `0C` path.
     */
    const val FAVORITES_ADDRESS_HI = 0x71

    /** `F0 43 2n 7F 03 71 mm 00 F7` - see [FAVORITES_ADDRESS_HI], especially the sweep hazard. */
    fun requestFavorites(device: Int, bankByte: Int): ByteArray =
        requestDump(device, FAVORITES_ADDRESS_HI, bankByte, 0)

    /**
     * A whole bank's favorite marks, written back to `71 mm 00`.
     *
     * **[table] is the entire bank's table, not one slot.** Read it, change one byte, send it
     * back, so the length declared is always the instrument's own. A wrong-length write at a
     * neighbouring address family once left this instrument ignoring MIDI until it was power
     * cycled, and nothing about a favorite is worth risking that to save a round trip.
     *
     * Four things here are the opposite of the stored-voice path, all measured on hardware:
     *
     * - **No store marker.** This reaches non-volatile storage by itself; an uncommitted write
     *   survived a cold power cycle. Sending [storeMarker] anyway is not merely redundant, it
     *   commits every unrelated pending write too.
     * - **It applies on a delay.** An immediate read-back returns the *old* table, so a caller
     *   that verifies straight away reports a working write as a no-op. Poll instead.
     * - **The read-only-bank rule does not apply.** PRE1 is read-only and its favorites table
     *   accepts a write and acknowledges it. That rule belongs to `0C`.
     * - **Out-of-range values are kept, not clamped.** Writing `4` is accepted, survives a power
     *   cycle, and lists the voice nowhere - so a caller must refuse anything outside `0..3`
     *   rather than letting the instrument sort it out.
     *
     * Acknowledged with [isAck] like any other bulk dump.
     */
    fun writeFavorites(device: Int, bankByte: Int, table: ByteArray): ByteArray =
        bulkDump(device, FAVORITES_ADDRESS_HI, bankByte, 0, table)

    /** Address a voice selection writes to, one byte per message. */
    const val SELECT_ADDRESS_HI = 0x65
    const val SELECT_ADDRESS_MID = 0x00

    /** Address-low bytes of the three sets: MSB, LSB, then the program. */
    const val SELECT_BANK_MSB = 0x00
    const val SELECT_BANK_LSB = 0x01
    const val SELECT_PROGRAM = 0x02

    /**
     * The bank-select MSB for a **normal** voice bank - a default, not a constant.
     *
     * It holds for every normal user or preset bank, but **GM answers only at `0x00` and GM DR
     * only at `0x7F`**, so it is not a fixed value across the whole instrument.
     *
     * The consequence for a caller: PRE1, GM and GM DR all use bank-select LSB `0x00` and are
     * told apart *only* by this byte, so selecting on the LSB alone silently picks a different
     * bank - and an unrecognised pair is ignored rather than refused, leaving the program change
     * to land in whatever bank was already current. Pass [MotifXsBank.selectMsb].
     */
    const val BANK_MSB = 0x3F

    /**
     * The three parameter sets that select a voice, in the order the editor sends them.
     *
     * [program] is the **flat zero-based slot**, so the panel's group and position fall straight
     * out of it - group `program / 16`, position `program % 16 + 1`. Whether the instrument
     * tolerates fewer messages, or a different order, is untested: all three are sent, in order.
     */
    fun selectVoice(
        device: Int,
        bankSelectLsb: Int,
        program: Int,
        bankSelectMsb: Int = BANK_MSB,
    ): List<ByteArray> {
        require(program in 0..127) { "program $program outside 0..127" }
        return listOf(bankSelectMsb, bankSelectLsb, program).mapIndexed { index, value ->
            byteArrayOf(
                SYSEX_START, MANUFACTURER.toByte(), (TYPE_SELECT or (device and 0x0F)).toByte(),
                MODEL_HI.toByte(), MODEL_HOST.toByte(),
                SELECT_ADDRESS_HI.toByte(), SELECT_ADDRESS_MID.toByte(),
                index.toByte(), (value and 0x7F).toByte(), SYSEX_END,
            )
        }
    }

    /** True for the device's echo of a selection - the only confirmation a selection gets. */
    fun isSelectEcho(message: ByteArray): Boolean =
        message.size >= 9 && isOurs(message) &&
            typeOf(message) == TYPE_PARAM_CHANGE &&
            (message[5].toInt() and 0xFF) == SELECT_ADDRESS_HI &&
            (message[6].toInt() and 0xFF) == SELECT_ADDRESS_MID

    /**
     * True for the acknowledgement a bulk dump draws back (`F0 43 60 02 F7`).
     *
     * **This means "accepted", and its absence means "refused".** The `0x02` payload byte carries
     * no information of its own; the signal is not in the payload byte at all - it is in whether
     * an acknowledgement arrives.
     *
     * A write to a read-only bank, a dump with a broken checksum, and a dump whose declared count
     * disagrees with its length are **all answered with silence**, and none of the three is
     * buffered.
     *
     * Accepted is still not the same as stored - see [storeMarker].
     */
    fun isAck(message: ByteArray): Boolean =
        message.size == 5 && message[0] == SYSEX_START && message[4] == SYSEX_END &&
            (message[1].toInt() and 0xFF) == MANUFACTURER &&
            (message[2].toInt() and 0xF0) == TYPE_ACK

    /**
     * A bulk dump addressed to [addressHi]/[addressMid]/[addressLo] carrying [payload].
     *
     * The byte count and checksum are computed here so a caller cannot get them wrong; the
     * payload is passed through untouched, because the only payload this app has any business
     * writing is one it read back off the same instrument.
     */
    fun bulkDump(device: Int, addressHi: Int, addressMid: Int, addressLo: Int, payload: ByteArray): ByteArray {
        // The counted region is the payload **only** - the three address bytes are outside it.
        // A 1,903-byte dump declares 0x0E 0x63 = 1,891, which is its payload length, and
        // 1,891 + BULK_OVERHEAD = 1,903.
        val count = payload.size
        val out = ByteArray(payload.size + BULK_OVERHEAD)
        out[0] = SYSEX_START
        out[1] = MANUFACTURER.toByte()
        out[2] = (TYPE_BULK_DUMP or (device and 0x0F)).toByte()
        out[3] = MODEL_HI.toByte()
        out[4] = MODEL_HOST.toByte()
        out[5] = ((count shr 7) and 0x7F).toByte()
        out[6] = (count and 0x7F).toByte()
        out[7] = addressHi.toByte()
        out[8] = addressMid.toByte()
        out[9] = addressLo.toByte()
        payload.copyInto(out, 10)
        out[out.size - 2] = checksumOf(out).toByte()
        out[out.size - 1] = SYSEX_END
        return out
    }

    /**
     * The store marker: `11 00 00` as a zero-payload bulk dump. **This is the commit.**
     *
     * It is not a formality. A bulk dump written to a stored-voice address is *acknowledged and
     * not applied* - read the slot straight back and it still holds what it held before. The
     * marker is what writes the pending dumps to flash, and until one arrives the instrument
     * holds them.
     *
     * Two consequences that shape everything above:
     *
     * - **It commits everything outstanding, not just the write before it.** Writes left pending
     *   by an abandoned operation are still there even if the instrument is reconnected to a
     *   different host, and a single marker commits all of them. So a write that is abandoned is
     *   not discarded; it is armed, and the next operation's marker fires it.
     * - **It is idempotent.** The vendor editor sends two. The first is acknowledged in ~160 ms
     *   (a flash write), the second in ~12 ms, and one is enough - three writes followed by one
     *   marker committed all three. This sends one.
     */
    fun storeMarker(device: Int): ByteArray = bulkDump(device, STORE_HI, STORE_MID, STORE_LO, ByteArray(0))

    const val STORE_HI = 0x11
    const val STORE_MID = 0x00
    const val STORE_LO = 0x00

    // ---- The documented protocol (Yamaha's Data List) ----
    //
    // Everything above this line is the *undocumented extension* the vendor's editor drives -
    // `0C mm nn`, `4n`, `11 00 00`, none of which appear in Yamaha's Data List. Everything below
    // is published, and the two are separate protocols that happen to share a framing.

    /** `F0 43 3n 7F 03 <addr> F7` - the parameter counterpart of [requestDump]. */
    fun requestParam(device: Int, addressHi: Int, addressMid: Int, addressLo: Int): ByteArray =
        byteArrayOf(
            SYSEX_START,
            MANUFACTURER.toByte(),
            (TYPE_PARAM_REQUEST or (device and 0x0F)).toByte(),
            MODEL_HI.toByte(),
            MODEL_HOST.toByte(),
            (addressHi and 0x7F).toByte(),
            (addressMid and 0x7F).toByte(),
            (addressLo and 0x7F).toByte(),
            SYSEX_END,
        )

    /**
     * The instrument's current mode, readable with a parameter request at `0A 00 01`.
     *
     * **This is what makes a selection safe to attempt.** The undocumented `4n` selection works in
     * Voice mode *only*: in Performance and Song mode the instrument does not acknowledge it and
     * changes nothing. Since the echo repeats what was sent rather than what happened, a client
     * with no way to read the mode cannot tell the difference.
     */
    const val MODE_ADDRESS_HI = 0x0A
    const val MODE_ADDRESS_MID = 0x00
    const val MODE_ADDRESS_LO = 0x01

    fun requestMode(device: Int): ByteArray =
        requestParam(device, MODE_ADDRESS_HI, MODE_ADDRESS_MID, MODE_ADDRESS_LO)

    /** The value a mode reply carries, or null if [message] is not one. */
    fun modeOf(message: ByteArray): MotifXsMode? {
        if (typeOf(message) != TYPE_PARAM_CHANGE) return null
        if (addressOf(message) != Triple(MODE_ADDRESS_HI, MODE_ADDRESS_MID, MODE_ADDRESS_LO)) return null
        val at = REQUEST_ADDRESS_INDEX + 3
        if (message.size <= at + 1) return null
        return MotifXsMode.of(message[at].toInt() and 0x7F)
    }

    /**
     * Switches the instrument's mode - `F0 43 1n 7F 03 0A 00 01 dd F7`.
     *
     * Documented, and a Parameter Change is the only way to switch between Performance, Song,
     * Pattern, and Voice mode - there is no other route.
     *
     * **This changes what the player sees and hears**, so nothing in this app sends it without
     * asking first - see `InstrumentException.BlockedByDeviceState`.
     */
    fun setMode(device: Int, mode: MotifXsMode): ByteArray =
        byteArrayOf(
            SYSEX_START,
            MANUFACTURER.toByte(),
            (TYPE_PARAM_CHANGE or (device and 0x0F)).toByte(),
            MODEL_HI.toByte(),
            MODEL_HOST.toByte(),
            MODE_ADDRESS_HI.toByte(),
            MODE_ADDRESS_MID.toByte(),
            MODE_ADDRESS_LO.toByte(),
            (mode.value and 0x7F).toByte(),
            SYSEX_END,
        )

    /** The Normal Voice edit buffer's name bytes: `40 00 00`..`40 00 13`. */
    fun requestEditBufferNameByte(device: Int, offset: Int): ByteArray =
        requestParam(device, COMMON_HI, COMMON_MID, offset)

    /**
     * Bulk Header and Bulk Footer - Yamaha's own way to address one stored voice.
     *
     * `mm` is the same bank byte the extension's `0C` uses and `nn` the slot, so a voice is
     * transferred as **header -> a fixed sequence of parameter blocks -> footer**. The footer is
     * what saves to Flash ROM: a write survives a power cycle.
     */
    const val BULK_HEADER_HI = 0x0E
    const val BULK_FOOTER_HI = 0x0F

    fun bulkHeader(device: Int, bankByte: Int, slot: Int): ByteArray =
        bulkDump(device, BULK_HEADER_HI, bankByte, slot, ByteArray(0))

    fun bulkFooter(device: Int, bankByte: Int, slot: Int): ByteArray =
        bulkDump(device, BULK_FOOTER_HI, bankByte, slot, ByteArray(0))

    /** A dump request at the Bulk Header address: reads the **stored** voice, block by block. */
    fun requestStoredVoice(device: Int, bankByte: Int, slot: Int): ByteArray =
        requestDump(device, BULK_HEADER_HI, bankByte, slot)

    fun isBulkFooter(message: ByteArray): Boolean =
        typeOf(message) == TYPE_BULK_DUMP && addressOf(message)?.first == BULK_FOOTER_HI

    /**
     * Normal Voice Common1 - the block whose first 20 bytes are the voice name.
     *
     * **Fixed width, one byte per character, NUL padded**, and none of the `0C` serialisation's
     * variable-length, doubled-name, undecoded-trailer trouble applies here: that trouble is a
     * property of the `0C` encoding, not of the instrument's parameter model.
     */
    const val COMMON_HI = 0x40
    const val COMMON_MID = 0x00
    const val COMMON_LO = 0x00

    /**
     * Drum Common1 - the same field at a different address.
     *
     * A Drum Voice's sequence is a different shape entirely: eight `46 xx` Common blocks (note
     * `46 08`, not the Normal Voice's `46 06`) and **73** `47 ee` elements against eight `41`/`42`
     * pairs, matching the Data List's `ee : 0 - 72`.
     *
     * **What does not differ is the name**: the first 20 bytes of Common1, fixed width, NUL
     * padded, in both. `46 00 00` is 74 bytes where `40 00 00` is 82, and nothing about renaming
     * depends on that.
     */
    const val DRUM_COMMON_HI = 0x46

    /**
     * Where a voice's category assignments sit in its Common block: `main1, sub1, main2, sub2`.
     *
     * From Yamaha's own Data List (`MIDI_Data_Table_en.xls`, VOICE NORMAL rows 26-29), which names
     * them `Voice Category 1 (Main)` through `Voice Category 2 (Sub)`. The *values* those bytes
     * take are another matter: Yamaha documents the main half and says only "Refer to Category
     * List" for the sub half, and that list is in no released file - see the shipped
     * `categoryEncoding`, which was measured on hardware.
     */
    const val CATEGORY_OFFSET = 0x18
    const val CATEGORY_LENGTH = 4

    fun isCommonBlock(message: ByteArray): Boolean = addressOf(message).let {
        it == Triple(COMMON_HI, COMMON_MID, COMMON_LO) ||
            it == Triple(DRUM_COMMON_HI, COMMON_MID, COMMON_LO)
    }

    /**
     * Re-frames a dump the instrument sent as one the host may send.
     *
     * **Never echo a block back verbatim.** The instrument sends model [MODEL_DEVICE] and the host
     * must send [MODEL_HOST]; the checksum covers that byte, so a message copied unchanged is
     * wrong twice. Rebuilding from the address and payload is the only safe conversion.
     */
    fun rebuildForHost(device: Int, message: ByteArray): ByteArray {
        val (hi, mid, lo) = addressOf(message)
            ?: throw IllegalArgumentException("message carries no address; cannot rebuild it")
        return bulkDump(device, hi, mid, lo, dumpPayload(message))
    }

    /**
     * The Universal Device Inquiry - read-only and idempotent.
     *
     * **Addressed to device `00`, not to the `7F` broadcast.** `00` is what the Motif XS Editor
     * sends and what this instrument answers; whether it also answers a broadcast inquiry is
     * untested, and a request that goes unanswered means the instrument is never found at all.
     * The reply comes back addressed `7F`, which is the device saying "to everyone".
     */
    fun identityRequest(): ByteArray =
        byteArrayOf(SYSEX_START, 0x7E, 0x00, 0x06, 0x01, SYSEX_END)

    fun isOurs(message: ByteArray): Boolean =
        message.size >= MIN_MESSAGE_SIZE &&
            message.first() == SYSEX_START &&
            message.last() == SYSEX_END &&
            message[1].toInt() == MANUFACTURER

    fun typeOf(message: ByteArray): Int? =
        if (isOurs(message)) message[TYPE_INDEX].toInt() and 0xF0 else null

    /**
     * The (hi, mid, lo) address a message is *for* - a dump's echoed address, or the one a request
     * is asking about.
     *
     * The offset is chosen from the message type rather than fixed, because the two shapes differ
     * (see [REQUEST_ADDRESS_INDEX]). Correlating a reply to a request means comparing these two,
     * so a single function that reads both correctly is the point.
     */
    fun addressOf(message: ByteArray): Triple<Int, Int, Int>? {
        val type = typeOf(message) ?: return null
        val at = if (type == TYPE_BULK_DUMP) BULK_ADDRESS_INDEX else REQUEST_ADDRESS_INDEX
        if (message.size <= at + 2) return null
        return Triple(
            message[at].toInt() and 0x7F,
            message[at + 1].toInt() and 0x7F,
            message[at + 2].toInt() and 0x7F,
        )
    }

    /** The declared data byte count: a 14-bit value split across two 7-bit bytes. */
    fun declaredCount(message: ByteArray): Int? {
        if (message.size <= COUNT_HI_INDEX + 1) return null
        return ((message[COUNT_HI_INDEX].toInt() and 0x7F) shl 7) or
            (message[COUNT_HI_INDEX + 1].toInt() and 0x7F)
    }

    /**
     * Is this a structurally sound bulk dump?
     *
     * Checks the declared length *and* the checksum. A reader that trusts a length field will
     * decode whatever follows it, and this app cannot see what it is decoding, so both checks are
     * made unconditionally; both are cheap.
     */
    fun isWellFormedBulkDump(message: ByteArray): Boolean {
        if (typeOf(message) != TYPE_BULK_DUMP) return false
        val count = declaredCount(message) ?: return false
        if (message.size != count + BULK_OVERHEAD) return false
        return checksumOf(message) == (message[message.size - 2].toInt() and 0x7F)
    }

    /** Yamaha's checksum: two's complement of the sum from the count byte to the last data byte. */
    fun checksumOf(message: ByteArray): Int {
        var sum = 0
        for (i in COUNT_HI_INDEX until message.size - 2) sum += message[i].toInt() and 0x7F
        return (-sum) and 0x7F
    }

    /** A bulk dump's payload: everything after the address, before the checksum. */
    fun dumpPayload(message: ByteArray): ByteArray {
        val start = BULK_ADDRESS_INDEX + 3
        val end = message.size - 2
        return if (end <= start) ByteArray(0) else message.copyOfRange(start, end)
    }
}
