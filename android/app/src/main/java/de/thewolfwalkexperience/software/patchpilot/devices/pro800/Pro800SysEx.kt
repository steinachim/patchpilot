// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Message construction and validation for the Behringer Pro-800's SysEx protocol.
 *
 * Every message is `F0 00 20 32 00 01 24 00 <type> [params] F7` - SysEx start, the three-byte
 * Behringer manufacturer id, the three-byte Pro-800 product id, the device number, then the
 * type. The "reference implementation" the comments in this package compare against is the
 * pro800_manager_plugin project (<https://github.com/steinachim/pro800_manager_plugin>), whose
 * `docs/Pro800SysExMessages.md` and `Pro800ProgramConstants.h` document the messages and the
 * record layout.
 */
object Pro800SysEx {

    val HEADER = byteArrayOf(
        0xF0.toByte(),
        0x00, 0x20, 0x32, // Behringer
        0x00, 0x01, 0x24, // Pro-800
        0x00, // device number: 00 by default, settable with type 0x00 (never sent); replies carry the instrument's
    )

    const val SYSEX_END = 0xF7.toByte()

    /**
     * Status: the answer to most commands, and to a write. Two parameter bytes, and the code is
     * the second (offset [STATUS_CODE_INDEX]): 0 on success, 1 on failure; offset 9 is a constant
     * 0. A write answers `01 00 00`; a read of an out-of-range address answers `01 00 01`.
     */
    const val TYPE_STATUS = 0x01
    const val STATUS_CODE_INDEX = 0x0A

    /** The only code seen meaning success; anything else is a refusal. */
    const val STATUS_OK = 0

    /** Request device name; answered by [TYPE_DEVICE_NAME_REPLY] carrying ASCII "PRO-800". */
    const val TYPE_DEVICE_NAME = 0x06
    const val TYPE_DEVICE_NAME_REPLY = 0x07

    /**
     * Request firmware version, with a `0x00` parameter; answered by [TYPE_FIRMWARE_REPLY], whose
     * three version bytes start at [FIRMWARE_VERSION_INDEX] because index 9 echoes the parameter.
     */
    const val TYPE_FIRMWARE = 0x08
    const val TYPE_FIRMWARE_REPLY = 0x09
    const val FIRMWARE_VERSION_INDEX = 0x0A

    /** Request a program dump: params are the program number's LSB then MSB. */
    const val TYPE_REQUEST_DUMP = 0x77

    /** A program dump. Also the *write* command, with the data appended. */
    const val TYPE_DUMP = 0x78

    // ---- Hazards: named so nobody rediscovers them by sending them; never sent, except 0x32
    // with parameter 0x00, the documented preset reload ([reloadPreset]). ----

    /** **Factory reset. No confirmation from the instrument, and no undo.** */
    const val TYPE_FACTORY_RESET = 0x7D

    /**
     * Reboots the instrument into its bootloader at parameter [BOOTLOADER_PARAM]: the display
     * reads `boot`, the panel stops responding and USB re-enumerates; only a power cycle returns
     * it, with presets and firmware intact. Every other parameter from `0x00` to `0x3F`, plus
     * `0x40`, `0x60` and `0x7F`, answers a plain OK status and does nothing, which is what makes
     * the type dangerous to probe.
     */
    const val TYPE_UNKNOWN_03 = 0x03
    const val BOOTLOADER_PARAM = 0x30

    /**
     * Reset mode. Parameter `0x00` recalls the preset the settings block points at, discarding
     * unsaved panel edits ([reloadPreset], [Pro800Instrument.select]). Every non-zero parameter
     * puts the synth into a state where it displays 8888 and stops responding to its own
     * controls; `0x32 0x00` restores it.
     */
    const val TYPE_RESET_MODE = 0x32

    /**
     * Writes both MIDI channel fields; its parameter is a flag, not a value. `0x00` sets RX and
     * TX to DIP-switch mode; anything else writes `MIDI RX Channel` = 249, out of range, which
     * makes the instrument deaf to all channel-voice MIDI. Recovered by an ordinary settings
     * write.
     */
    const val TYPE_CHANNEL_WRITE = 0x0E

    /**
     * Writes preset name bytes directly, without the record-length bookkeeping `0x77` reports
     * from. Sent with no payload it blanks the name of every occupied preset, persistently,
     * recoverable only by a factory reset; sent correctly it stores at most 14 characters and
     * reads back inconsistently with the display. [Pro800Editor] renames through a `0x77`/`0x78`
     * round trip instead, as Behringer's own editor does.
     */
    const val TYPE_SET_NAME = 0x50

    /** Programs occupy 0..399; the settings block lives at 510 (`7E 03` as LSB/MSB) and never appears in a browsable index. */
    const val PROGRAM_COUNT = 400
    const val SETTINGS_ADDRESS = 510

    /** Offset of the type byte within a whole message, and of the two address bytes after it. */
    const val TYPE_INDEX = 8
    const val ADDRESS_LSB_INDEX = 9
    const val ADDRESS_MSB_INDEX = 10

    /** First byte of a dump's payload, i.e. just past the header, type and address. */
    const val DATA_START_INDEX = 11

    fun request(type: Int, vararg params: Int): ByteArray =
        HEADER + byteArrayOf(type.toByte()) + params.map { it.toByte() }.toByteArray() + SYSEX_END

    /**
     * A write: the same `0x78` type as a dump, with the data appended. An empty [encodedPayload]
     * sets the slot back to uninitialized, after which it answers reads with [isEmptyReply]'s bare
     * `F0 F7`. Sent fire-and-forget and verified by read-back (see [Pro800Editor]).
     */
    fun writeDump(programNumber: Int, encodedPayload: ByteArray): ByteArray =
        HEADER +
            byteArrayOf(
                TYPE_DUMP.toByte(),
                (programNumber and 0x7F).toByte(),
                ((programNumber shr 7) and 0x7F).toByte(),
            ) +
            encodedPayload +
            SYSEX_END

    /** The settings block's own dump request - the same 0x77 mechanism, at [SETTINGS_ADDRESS]. */
    fun requestSettings(): ByteArray = requestDump(SETTINGS_ADDRESS)

    /** A write of the settings block at [SETTINGS_ADDRESS] - named separately so the one legitimate write to 510 is greppable. */
    fun writeSettings(encodedPayload: ByteArray): ByteArray = writeDump(SETTINGS_ADDRESS, encodedPayload)

    /**
     * Recalls the preset the settings block points at: [TYPE_RESET_MODE] with the one parameter
     * that is not a hazard. A settings write alone moves the pointer while the voice engine keeps
     * playing the previous preset. Answered by a status.
     */
    fun reloadPreset(): ByteArray = request(TYPE_RESET_MODE, 0x00)

    /** A dump request for one program number, split into the LSB/MSB pair the protocol takes. */
    fun requestDump(programNumber: Int): ByteArray =
        request(TYPE_REQUEST_DUMP, programNumber and 0x7F, (programNumber shr 7) and 0x7F)

    /** Is this one of ours at all? On a shared MIDI bus other devices' SysEx arrives too. */
    fun isOurs(message: ByteArray): Boolean =
        message.size > TYPE_INDEX &&
            message.last() == SYSEX_END &&
            HEADER.indices.all { message[it] == HEADER[it] }

    fun typeOf(message: ByteArray): Int? =
        if (isOurs(message)) message[TYPE_INDEX].toInt() and 0xFF else null

    /** The program number a dump reply is *for*, recovered from its own echoed address bytes. */
    fun addressOf(message: ByteArray): Int? {
        if (!isOurs(message) || message.size <= ADDRESS_MSB_INDEX) return null
        val lsb = message[ADDRESS_LSB_INDEX].toInt() and 0x7F
        val msb = message[ADDRESS_MSB_INDEX].toInt() and 0x7F
        return (msb shl 7) or lsb
    }

    /** The status code a `0x01` reply carries (offset [STATUS_CODE_INDEX]; offset 9 is a constant 0), or null if this is not one. */
    fun statusCodeOf(message: ByteArray): Int? =
        if (typeOf(message) == TYPE_STATUS && message.size > STATUS_CODE_INDEX) {
            message[STATUS_CODE_INDEX].toInt() and 0x7F
        } else {
            null
        }

    /** True for a status reply saying the instrument accepted whatever it was asked to do. */
    fun isStatusOk(message: ByteArray): Boolean = statusCodeOf(message) == STATUS_OK

    /** True for a status reply saying it refused. */
    fun isStatusFailure(message: ByteArray): Boolean =
        statusCodeOf(message)?.let { it != STATUS_OK } == true

    /**
     * A bare `F0 F7`, the instrument's answer for an address holding nothing. It carries no header
     * ([isOurs] rejects it) and no echoed address, so it can only be accepted as the answer to
     * whatever is in flight, which is safe because exchanges are serialized.
     */
    fun isEmptyReply(message: ByteArray): Boolean =
        message.size == 2 && message[0] == HEADER[0] && message[1] == SYSEX_END

    /** The raw, still 7-bit-encoded payload of a dump: everything between the address and `F7`. */
    fun dumpPayload(message: ByteArray): ByteArray {
        require(typeOf(message) == TYPE_DUMP) { "not a dump message" }
        if (message.size <= DATA_START_INDEX + 1) return ByteArray(0)
        return message.copyOfRange(DATA_START_INDEX, message.size - 1)
    }

    /** ASCII payload of a device-name reply, NULs stripped - "PRO-800" on a Pro-800. */
    fun deviceName(message: ByteArray): String? {
        if (typeOf(message) != TYPE_DEVICE_NAME_REPLY) return null
        val bytes = message.copyOfRange(TYPE_INDEX + 1, message.size - 1)
        return String(bytes, Charsets.US_ASCII).trim('\u0000').trim()
    }

    /** The dotted firmware version from a reply's three bytes at [FIRMWARE_VERSION_INDEX]. */
    fun firmwareVersion(message: ByteArray): String? {
        if (typeOf(message) != TYPE_FIRMWARE_REPLY) return null
        if (message.size < FIRMWARE_VERSION_INDEX + 3) return null
        return (0 until 3)
            .map { message[FIRMWARE_VERSION_INDEX + it].toInt() and 0x7F }
            .joinToString(".")
    }

    /** The request, with the parameter byte the instrument expects. */
    fun requestFirmware(): ByteArray = request(TYPE_FIRMWARE, 0x00)
}
