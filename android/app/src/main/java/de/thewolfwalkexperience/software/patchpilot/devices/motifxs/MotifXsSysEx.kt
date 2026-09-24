// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

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

    /** Bank bytes at or above this are drum banks: `20` PRE DR, `21` GM DR, `28` USER DR. */
    const val FIRST_DRUM_BANK = 0x20

    /** Two model ids, split by who is speaking: the host sends [MODEL_HOST], the instrument sends [MODEL_DEVICE]. */
    const val MODEL_HI = 0x7F
    const val MODEL_HOST = 0x03
    const val MODEL_DEVICE = 0x0B

    /** Message type, in the high nibble of byte 2; the low nibble is the device number. */
    const val TYPE_BULK_DUMP = 0x00
    const val TYPE_PARAM_CHANGE = 0x10
    const val TYPE_DUMP_REQUEST = 0x20
    const val TYPE_PARAM_REQUEST = 0x30

    /** Host -> device "parameter set", used only for selecting a voice; the device answers with [TYPE_PARAM_CHANGE]. */
    const val TYPE_SELECT = 0x40

    /** Device -> host acknowledgement of a bulk dump the host sent: `F0 43 60 02 F7`. */
    const val TYPE_ACK = 0x60

    const val TYPE_INDEX = 2

    /** A bulk dump's byte count, and the address that follows it. */
    const val COUNT_HI_INDEX = 5
    const val BULK_ADDRESS_INDEX = 7

    /**
     * Where the address sits in everything that is not a bulk dump: two bytes earlier, since only
     * a dump carries a byte count (`F0 43 2n 7F 03 <addr> F7` against
     * `F0 43 0n 7F 0B <count> <addr> <data> <sum> F7`).
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
     * one small dump per bank at `71 mm 00`, where `mm` is the bank's own
     * [MotifXsBank.addressMid]. The payload is one raw byte per slot, indexed by zero-based slot,
     * and is not MSB-packed - running the unpacker over it produces plausible nonsense rather
     * than an error.
     *
     * The mark is neither a boolean nor a bitmask: it names which of the voice's own two category
     * assignments the instrument's browser files the favorite under. `0` not favorited, `1` both,
     * `2` Category 1 only, `3` Category 2 only; anything else is stored verbatim and lists the
     * voice under neither.
     *
     * Never sweep the middle byte: an unmapped address puts an *Illegal Bulk Data* message on the
     * instrument's own screen, and `mm = 0x08` is a permanent hole between PRE8 and GM. Only pass
     * a value from the catalog's bank table. Writable - see [writeFavorites].
     */
    const val FAVORITES_ADDRESS_HI = 0x71

    /** `F0 43 2n 7F 03 71 mm 00 F7` - see [FAVORITES_ADDRESS_HI], especially the sweep hazard. */
    fun requestFavorites(device: Int, bankByte: Int): ByteArray =
        requestDump(device, FAVORITES_ADDRESS_HI, bankByte, 0)

    /**
     * A whole bank's favorite marks, written back to `71 mm 00`. [table] is the entire bank's
     * table: read it, change one byte, send it back, so the declared length is always the
     * instrument's own - an ill-formed write at a neighbouring address family has been observed
     * to leave this instrument ignoring MIDI until a power cycle.
     *
     * Four things differ from the stored-voice path, all measured:
     *
     * - No store marker: this reaches non-volatile storage by itself, and sending [storeMarker]
     *   anyway would commit every unrelated pending write too.
     * - It applies on a delay, so an immediate read-back returns the old table. Poll instead.
     * - The read-only-bank rule does not apply: PRE1's table accepts a write.
     * - Out-of-range values are kept, not clamped, so a caller must refuse anything outside 0..3.
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
     * The bank-select MSB for a normal voice bank - a default, not a constant: GM answers only at
     * `0x00` and GM DR only at `0x7F`. PRE1, GM and GM DR share bank-select LSB `0x00` and are
     * told apart only by this byte, and an unrecognised pair is ignored rather than refused, so
     * pass [MotifXsBank.selectMsb].
     */
    const val BANK_MSB = 0x3F

    /**
     * The three parameter sets that select a voice, in the order the vendor's editor sends them.
     * [program] is the flat zero-based slot. Whether fewer messages or another order would do is
     * untested.
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
     * True for the acknowledgement a bulk dump draws back (`F0 43 60 02 F7`). Its arrival means
     * "accepted" and its absence "refused"; the `0x02` payload byte carries no information. A
     * write to a read-only bank, a broken checksum and a count disagreeing with the length are
     * all answered with silence, and none is buffered. Accepted is not stored - see [storeMarker].
     */
    fun isAck(message: ByteArray): Boolean =
        message.size == 5 && message[0] == SYSEX_START && message[4] == SYSEX_END &&
            (message[1].toInt() and 0xFF) == MANUFACTURER &&
            (message[2].toInt() and 0xF0) == TYPE_ACK

    /**
     * A bulk dump addressed to [addressHi]/[addressMid]/[addressLo] carrying [payload]. The byte
     * count and checksum are computed here; the payload is passed through untouched.
     */
    fun bulkDump(device: Int, addressHi: Int, addressMid: Int, addressLo: Int, payload: ByteArray): ByteArray {
        // The counted region is the payload only: a 1,903-byte dump declares 1,891, its payload
        // length, and 1,891 + BULK_OVERHEAD = 1,903.
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
     * The store marker: `11 00 00` as a zero-payload bulk dump, and the commit. A bulk dump to a
     * stored-voice address is acknowledged and not applied; the marker writes the pending dumps
     * to flash.
     *
     * - It commits everything outstanding, not just the write before it - and pending writes
     *   survive being abandoned, even across a reconnect to a different host, so an abandoned
     *   write is armed rather than discarded.
     * - It is idempotent (the vendor editor sends two, acknowledged in ~160 ms and ~12 ms); one
     *   marker committed three writes, and this sends one.
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
     * The instrument's current mode, readable with a parameter request at `0A 00 01`. The `4n`
     * selection works in Voice mode only, and its echo repeats what was sent rather than what
     * happened, so reading the mode is the only way to tell.
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
     * Switches the instrument's mode - `F0 43 1n 7F 03 0A 00 01 dd F7`, the documented and only
     * route. It changes what the player hears, so nothing here sends it without asking first -
     * see `InstrumentException.BlockedByDeviceState`.
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
     * Bulk Header and Bulk Footer - Yamaha's own way to address one stored voice, at the same
     * bank byte `0C` uses. A voice transfers as header, a fixed sequence of parameter blocks,
     * footer; the footer is what saves to Flash ROM.
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
     * Normal Voice Common1 - the block whose first 20 bytes are the voice name: fixed width, one
     * byte per character, NUL padded, with none of the `0C` encoding's variable-length trouble.
     */
    const val COMMON_HI = 0x40
    const val COMMON_MID = 0x00
    const val COMMON_LO = 0x00

    /**
     * Drum Common1 - the same name field at a different address. A Drum Voice's sequence is a
     * different shape (eight `46 xx` Common blocks and 73 `47 ee` elements, matching the Data
     * List's `ee : 0 - 72`), but the first 20 bytes of Common1 are the name in both.
     */
    const val DRUM_COMMON_HI = 0x46

    /**
     * Where a voice's category assignments sit in its Common block: `main1, sub1, main2, sub2`,
     * from Yamaha's Data List (`MIDI_Data_Table_en.xls`, VOICE NORMAL rows 26-29). The values
     * those bytes take are only half documented - the sub half refers to a category list that is
     * in no released file - so the shipped `categoryEncoding` was measured on hardware.
     */
    const val CATEGORY_OFFSET = 0x18
    const val CATEGORY_LENGTH = 4

    fun isCommonBlock(message: ByteArray): Boolean = addressOf(message).let {
        it == Triple(COMMON_HI, COMMON_MID, COMMON_LO) ||
            it == Triple(DRUM_COMMON_HI, COMMON_MID, COMMON_LO)
    }

    /**
     * Re-frames a dump the instrument sent as one the host may send: the instrument sends model
     * [MODEL_DEVICE] and the host must send [MODEL_HOST], and the checksum covers that byte, so
     * a block echoed verbatim is wrong twice.
     */
    fun rebuildForHost(device: Int, message: ByteArray): ByteArray {
        val (hi, mid, lo) = addressOf(message)
            ?: throw IllegalArgumentException("message carries no address; cannot rebuild it")
        return bulkDump(device, hi, mid, lo, dumpPayload(message))
    }

    /**
     * The Universal Device Inquiry - read-only and idempotent, addressed to device `00`, which is
     * what the vendor's editor sends and this instrument answers. Whether it answers the `7F`
     * broadcast is untested. The reply comes back addressed `7F`.
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
     * The (hi, mid, lo) address a message is for - a dump's echoed address, or the one a request
     * asks about. The offset is chosen from the message type, since the two shapes differ (see
     * [REQUEST_ADDRESS_INDEX]).
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

    /** Is this a structurally sound bulk dump? Checks the declared length and the checksum, both cheap. */
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
