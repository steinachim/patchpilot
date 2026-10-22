// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection

/**
 * The Moog Subsequent 37's SysEx messages: `F0 04 0C <command…> F7`, Moog's manufacturer id then
 * a constant `0C`. Builders, matchers and the record packing every message body shares, with no
 * I/O.
 *
 * **Never sent.** The app sends only the requests built here and the `05 01` write. Not the
 * replies the instrument sends (`01 07`, `02 07`, `04 07`, `07 02`): a file of a `04 07` sent to
 * the instrument is stored at the selected slot, and one of all programs overwrites all 256. Not
 * `04 08`, the same shape. Not `35`, which is never answered, and not `1D`, `12 48 74`, `13` or
 * `18 7D`, whose effect is unknown (`1D` is probably a reset of the global settings).
 *
 * **Packing.** A message body starts at offset 23 (header, two command bytes, an 18-byte address
 * block) and is a run of 3-byte records, one 16-bit big-endian word each, split 4 + 6 + 6 bits.
 * A record's first byte is `0x40`–`0x4F`, the other two are below `0x40`.
 */
object Subsequent37SysEx {

    const val SYSEX_START = 0xF0
    const val SYSEX_END = 0xF7
    const val MANUFACTURER = 0x04
    const val MODEL = 0x0C

    /** 16 banks of 16 programs, numbered 0..255: `n = bank * 16 + slot`, both 0-based. */
    const val PROGRAM_COUNT = 256

    /** A program payload: 244 words. */
    const val PAYLOAD_BYTES = 488

    /** Where a message body starts. */
    const val BODY_OFFSET = 23

    const val RECORD_BYTES = 3
    private const val RECORD_MARKER = 0x40

    /** One program's records: 244 × 3. */
    const val PACKED_PROGRAM_BYTES = PAYLOAD_BYTES / 2 * RECORD_BYTES

    /** A single-program message (`02 07`, `04 07`, `04 08`, `05 01`): header, one program, `F7`. */
    const val SINGLE_PROGRAM_MESSAGE_BYTES = BODY_OFFSET + PACKED_PROGRAM_BYTES + 1

    /** A bulk dump carries one program per stride: its records and a 64-byte separator. */
    const val BULK_STRIDE = 796

    /** A bulk dump of all 256 programs: `24 + 796 × 256`. */
    const val FULL_BULK_DUMP_BYTES = BODY_OFFSET + 1 + BULK_STRIDE * PROGRAM_COUNT

    /** The settings block: header, 268 records, `F7`. A shorter one lost bytes in transit. */
    const val SETTINGS_WORDS = 268
    const val SETTINGS_MESSAGE_BYTES = BODY_OFFSET + SETTINGS_WORDS * RECORD_BYTES + 1

    /** Every short request is 13 command bytes, zero-padded. */
    private const val REQUEST_COMMAND_BYTES = 13

    // Commands, as their first two bytes.
    const val CMD_PING = 0x32
    const val CMD_SELECT_ACK = 0x36
    val REQUEST_SETTINGS = intArrayOf(0x06, 0x04)
    val REQUEST_SETTINGS_PAIR = intArrayOf(0x06, 0x00)
    val REQUEST_READ_PROGRAM = intArrayOf(0x06, 0x01)
    val REQUEST_BULK_DUMP = intArrayOf(0x06, 0x03)
    val WRITE_PROGRAM = intArrayOf(0x05, 0x01)
    val REPLY_SETTINGS = intArrayOf(0x07, 0x02)
    val REPLY_STORED_PROGRAM = intArrayOf(0x02, 0x07)
    val REPLY_EDIT_BUFFER = intArrayOf(0x04, 0x07)
    val REPLY_BULK_DUMP = intArrayOf(0x01, 0x07)

    /** The editor's own export of one program: the same shape as [REPLY_EDIT_BUFFER]. */
    val EXPORT_PROGRAM = intArrayOf(0x04, 0x08)

    /** The address block after a write's program number. */
    private val WRITE_ADDRESS_TAIL = ByteArray(15) + byteArrayOf(0x01)

    /** The standard MIDI Device Inquiry, broadcast: the only form the instrument answers. */
    val IDENTITY_REQUEST = bytes(0xF0, 0x7E, 0x7F, 0x06, 0x01, 0xF7)

    // ---- Requests ----

    /** The discovery probe: answered `F0 04 0C 32 7F 7F F7`, read-only and idempotent. */
    fun ping(): ByteArray = request(CMD_PING)

    /** Answered by the settings block. Sent with [settingsPair], which draws nothing. */
    fun settingsRequest(): ByteArray = request(*REQUEST_SETTINGS)

    fun settingsPair(): ByteArray = request(*REQUEST_SETTINGS_PAIR)

    /**
     * Reads stored program [number]: answered by the selected program's edit buffer (`04 07`),
     * then the program asked for (`02 07`, addressed). Selects nothing.
     */
    fun readProgram(number: Int): ByteArray {
        requireProgram(number)
        return request(*REQUEST_READ_PROGRAM, number shr 7, number and 0x7F)
    }

    /** Every program in one reply of 203,800 bytes, about 142 seconds long. */
    fun bulkDumpRequest(): ByteArray = request(*REQUEST_BULK_DUMP)

    /**
     * Writes [payload] to program [number], whole. Never acknowledged and never validated by the
     * instrument, so every guard is here; the checksum is closed on the way out.
     */
    fun writeProgram(number: Int, payload: ByteArray): ByteArray {
        requireProgram(number)
        require(payload.size == PAYLOAD_BYTES) { "A program is $PAYLOAD_BYTES bytes, not ${payload.size}." }
        return header(*WRITE_PROGRAM) +
            bytes(number shr 7, number and 0x7F) + WRITE_ADDRESS_TAIL +
            pack(withChecksum(payload)) + bytes(SYSEX_END)
    }

    /**
     * Bank select (CC0 = 0, CC32 = `n / 128`) and Program Change (`n % 128`) on [channel], 0..15:
     * one buffer of three channel messages. Answered `36 <cc32> <pc>`.
     */
    fun select(channel: Int, number: Int): ByteArray {
        require(channel in 0..15) { "MIDI channel $channel is outside 0..15." }
        val selection = midiSelection(number)
        return bytes(
            0xB0 or channel,
            0x00,
            requireNotNull(selection.bankMsb),
            0xB0 or channel,
            0x20,
            requireNotNull(selection.bankLsb),
            0xC0 or channel,
            selection.program,
        )
    }

    /** The values [select] sends for program [number]. */
    fun midiSelection(number: Int): MidiSelection {
        requireProgram(number)
        return MidiSelection(bankMsb = 0, bankLsb = number shr 7, program = number and 0x7F)
    }

    // ---- Matchers ----

    /**
     * Whether [message] is one of this instrument's, with the command [command] after the header.
     * Needs only the first `3 + command.size` bytes, so the start of a message still arriving can
     * be checked.
     */
    fun isCommand(message: ByteArray, vararg command: Int): Boolean {
        if (message.size < 3 + command.size) return false
        if (!hasHeader(message)) return false
        return command.indices.all { (message[3 + it].toInt() and 0xFF) == command[it] }
    }

    fun isPingReply(message: ByteArray): Boolean =
        message.size >= 6 && isCommand(message, CMD_PING) &&
            message[4].toInt() == 0x7F && message[5].toInt() == 0x7F

    fun isSettingsReply(message: ByteArray): Boolean =
        message.size == SETTINGS_MESSAGE_BYTES && isCommand(message, *REPLY_SETTINGS)

    /**
     * The `02 07` carrying stored program [number], matched on the command **and** the address: the
     * `04 07` ahead of it is addressed `00 00` whatever it holds, so the address alone would take
     * program 0's edit buffer for program 0.
     */
    fun isStoredProgramReply(message: ByteArray, number: Int): Boolean =
        isCommand(message, *REPLY_STORED_PROGRAM) && addressOf(message) == number

    fun isBulkDumpReply(message: ByteArray): Boolean = isCommand(message, *REPLY_BULK_DUMP)

    fun isSelectAck(message: ByteArray, number: Int): Boolean =
        message.size == 7 && isCommand(message, CMD_SELECT_ACK) &&
            message[4].toInt() == number shr 7 && message[5].toInt() == number and 0x7F

    /** The Identity Reply, from Moog: `F0 7E <id> 06 02 04 …`. */
    fun isIdentityReply(message: ByteArray): Boolean =
        message.size >= 15 &&
            (message[0].toInt() and 0xFF) == SYSEX_START &&
            message[1].toInt() == 0x7E && message[3].toInt() == 0x06 && message[4].toInt() == 0x02 &&
            message[5].toInt() == MANUFACTURER

    /**
     * The firmware version an Identity Reply carries, as `1.2.0`: the last three bytes of its
     * four-byte software revision (`00 01 02 00`). The first is left out: nothing says what it
     * means. Null for anything else.
     */
    fun decodeFirmware(reply: ByteArray): String? {
        if (!isIdentityReply(reply)) return null
        return (11..13).joinToString(".") { (reply[it].toInt() and 0x7F).toString() }
    }

    /** The program number in bytes 5–6 of a single-program message, `hi << 7 | lo`. */
    fun addressOf(message: ByteArray): Int? {
        if (message.size < 7) return null
        return ((message[5].toInt() and 0x7F) shl 7) or (message[6].toInt() and 0x7F)
    }

    /**
     * The program a single-program message carries - a `02 07` or `04 07` reply, the editor's
     * `04 08` export or a `05 01` write - or null where it is not one, or its records or checksum
     * are broken.
     */
    fun programOf(message: ByteArray): ByteArray? {
        if (message.size != SINGLE_PROGRAM_MESSAGE_BYTES) return null
        if ((message.last().toInt() and 0xFF) != SYSEX_END) return null
        val known = listOf(REPLY_STORED_PROGRAM, REPLY_EDIT_BUFFER, EXPORT_PROGRAM, WRITE_PROGRAM)
        if (known.none { isCommand(message, *it) }) return null
        val payload = unpack(message, BODY_OFFSET, PAYLOAD_BYTES / 2) ?: return null
        return payload.takeIf { checksumCloses(it) }
    }

    // ---- Records and checksum ----

    /**
     * [words] records from [offset] of [source], unpacked to `2 × words` bytes; null where a
     * record's marker or value bits are wrong, which is how a byte lost in transit shows.
     */
    fun unpack(source: ByteArray, offset: Int, words: Int): ByteArray? {
        if (offset < 0 || offset + words * RECORD_BYTES > source.size) return null
        val out = ByteArray(words * 2)
        for (i in 0 until words) {
            val at = offset + i * RECORD_BYTES
            val b0 = source[at].toInt() and 0xFF
            val b1 = source[at + 1].toInt() and 0xFF
            val b2 = source[at + 2].toInt() and 0xFF
            if (b0 and 0xF0 != RECORD_MARKER || b1 >= 0x40 || b2 >= 0x40) return null
            val word = ((b0 and 0x0F) shl 12) or (b1 shl 6) or b2
            out[i * 2] = (word shr 8).toByte()
            out[i * 2 + 1] = word.toByte()
        }
        return out
    }

    /** The records for an even-length [payload]. */
    fun pack(payload: ByteArray): ByteArray {
        require(payload.size % 2 == 0) { "A payload is whole 16-bit words." }
        val out = ByteArray(payload.size / 2 * RECORD_BYTES)
        for (i in 0 until payload.size / 2) {
            val word = ((payload[i * 2].toInt() and 0xFF) shl 8) or (payload[i * 2 + 1].toInt() and 0xFF)
            out[i * 3] = (RECORD_MARKER or (word shr 12)).toByte()
            out[i * 3 + 1] = ((word shr 6) and 0x3F).toByte()
            out[i * 3 + 2] = (word and 0x3F).toByte()
        }
        return out
    }

    /** A program's 244 big-endian words sum to zero, modulo 2^16: a plain sum, not a CRC. */
    fun checksumCloses(payload: ByteArray): Boolean =
        payload.size == PAYLOAD_BYTES && wordSum(payload, PAYLOAD_BYTES / 2) == 0

    /** [payload] with its last word set so that [checksumCloses]. */
    fun withChecksum(payload: ByteArray): ByteArray {
        require(payload.size == PAYLOAD_BYTES) { "A program is $PAYLOAD_BYTES bytes, not ${payload.size}." }
        val checksum = (0x10000 - wordSum(payload, PAYLOAD_BYTES / 2 - 1)) and 0xFFFF
        return payload.copyOf().also {
            it[PAYLOAD_BYTES - 2] = (checksum shr 8).toByte()
            it[PAYLOAD_BYTES - 1] = checksum.toByte()
        }
    }

    private fun wordSum(payload: ByteArray, words: Int): Int {
        var sum = 0
        for (i in 0 until words) {
            sum += ((payload[i * 2].toInt() and 0xFF) shl 8) or (payload[i * 2 + 1].toInt() and 0xFF)
        }
        return sum and 0xFFFF
    }

    // ---- Helpers ----

    private fun hasHeader(message: ByteArray): Boolean =
        (message[0].toInt() and 0xFF) == SYSEX_START &&
            message[1].toInt() == MANUFACTURER && message[2].toInt() == MODEL

    private fun header(vararg command: Int): ByteArray = bytes(SYSEX_START, MANUFACTURER, MODEL, *command)

    /** A short request: [command] zero-padded to 13 bytes. */
    private fun request(vararg command: Int): ByteArray =
        header(*command) + ByteArray(REQUEST_COMMAND_BYTES - command.size) + bytes(SYSEX_END)

    private fun requireProgram(number: Int) =
        require(number in 0 until PROGRAM_COUNT) { "Program $number is outside 0..${PROGRAM_COUNT - 1}." }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
