package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Message construction and validation for the Behringer Pro-800's SysEx protocol.
 *
 * Every message is `F0 00 20 32 00 01 24 00 <type> [params] F7` - SysEx start, the three-byte
 * Behringer manufacturer id, the three-byte Pro-800 product id, a CPU id, then the type. See
 * `docs/Pro800SysExMessages.md` in the pro800_manager_plugin repository.
 */
object Pro800SysEx {

    val HEADER = byteArrayOf(
        0xF0.toByte(),
        0x00, 0x20, 0x32, // Behringer
        0x00, 0x01, 0x24, // Pro-800
        0x00, // CPU id
    )

    const val SYSEX_END = 0xF7.toByte()

    /**
     * Status: the answer to most commands, and to a write.
     *
     * **Two parameter bytes, and the code is the second one** - offset [STATUS_CODE_INDEX], 0 on
     * success and 1 on failure; offset 9 is a constant 0 across every status reply. Reading the
     * code from 9 makes every status look like a success, which is the same off-by-one the
     * firmware reply has (see [FIRMWARE_VERSION_INDEX]). A write answers `01 00 00`; a read of
     * an out-of-range address answers `01 00 01`.
     */
    const val TYPE_STATUS = 0x01
    const val STATUS_CODE_INDEX = 0x0A

    /** The only code seen meaning success; anything else is a refusal. */
    const val STATUS_OK = 0

    /** Request device name; answered by [TYPE_DEVICE_NAME_REPLY] carrying ASCII "PRO-800". */
    const val TYPE_DEVICE_NAME = 0x06
    const val TYPE_DEVICE_NAME_REPLY = 0x07

    /**
     * Request firmware version; answered by [TYPE_FIRMWARE_REPLY].
     *
     * **The request carries a `0x00` parameter** (`VersionMessage::request()` sends
     * `{REQUEST_ID, 0x00}`), and the reply's three version bytes start at message index
     * [FIRMWARE_VERSION_INDEX] - one past where the payload of every other reply begins, because
     * index 9 echoes that parameter. Reading from 9 yields a version one byte out of step - a
     * version like 1.4.6 decoded from the wrong offset comes out as nonsense.
     */
    const val TYPE_FIRMWARE = 0x08
    const val TYPE_FIRMWARE_REPLY = 0x09
    const val FIRMWARE_VERSION_INDEX = 0x0A

    /** Request a program dump: params are the program number's LSB then MSB. */
    const val TYPE_REQUEST_DUMP = 0x77

    /** A program dump. Also the *write* command, with the data appended. */
    const val TYPE_DUMP = 0x78

    /**
     * **Destructive, and named here on purpose.**
     *
     * `0x7D` is a factory reset with no confirmation and no undo; `0x32` with a non-zero parameter
     * puts the synth into a state where it displays 8888 and stops responding properly to its own
     * controls. Neither has a UI path, and any raw-message tool must demand a typed confirmation
     * before sending either.
     *
     * Naming them here rather than leaving them as unlabelled numbers is deliberate: an
     * undocumented destructive command is easy to send by accident precisely because nothing marks
     * it as dangerous. Naming both, documenting what they do, and gating both behind a typed
     * confirmation is what keeps that from happening.
     */
    const val TYPE_FACTORY_RESET = 0x7D
    const val TYPE_DEBUG_MODE = 0x32

    /**
     * Programs occupy 0..399; the settings block lives at 510 (`7E 03` as LSB/MSB) and must never
     * appear in a browsable index - a row that corrupts global settings when written to is not a
     * preset.
     */
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
     * A write: the same `0x78` type as a dump, with the data appended.
     *
     * An **empty** [encodedPayload] is how a slot is set back to uninitialized - the protocol
     * notes say the data "may be empty -> set to 'uninitialized'", and a slot written that way
     * answers subsequent reads with [isEmptyReply]'s bare `F0 F7`.
     *
     * The reference implementation sends this fire-and-forget with a 20 ms pause between writes
     * and never inspects a reply, so this app does not depend on one either - it verifies by
     * reading the address back, which is a stronger check than a status byte anyway (see
     * [Pro800Editor]).
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

    /** A dump request for one program number, split into the LSB/MSB pair the protocol takes. */
    fun requestDump(programNumber: Int): ByteArray =
        request(TYPE_REQUEST_DUMP, programNumber and 0x7F, (programNumber shr 7) and 0x7F)

    /**
     * Is this one of ours at all?
     *
     * Checked on every inbound message before anything else looks at it, because on a shared MIDI
     * bus other devices' SysEx *will* arrive, and a foreign message that happens to be the right
     * length is otherwise indistinguishable from a reply.
     */
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

    /**
     * The status code a `0x01` reply carries, or null if this is not one.
     *
     * **Read from [STATUS_CODE_INDEX] (offset 10), not offset 9.** A write answers `01 00 00`; a
     * read of an out-of-range address answers `01 00 01` - offset 9 is a constant `00` in both, so
     * reading the code from there reports **every** status as a success, including every
     * rejection. It had no callers yet, which is the only reason it never mattered.
     */
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
     * A bare `F0 F7` - a SysEx message with no body at all.
     *
     * **This is how the instrument says "nothing is stored at that address" (design 11.4).**
     * Every dump request for an address holding nothing comes back as exactly these two bytes,
     * while populated ones answer with dumps of *varying* length - so a dump means a preset
     * exists, and its length says nothing. It carries no
     * manufacturer header, so [isOurs] rejects it, and it carries no echoed address, so it cannot
     * be matched to the request that prompted it - it can only be accepted as "the answer to
     * whatever is currently in flight", which is safe here because exchanges are serialized.
     *
     * Treating it as a timeout instead is expensive rather than merely wrong: it cost two full
     * two-second waits per address, which on an instrument with three empty banks is around
     * twenty minutes of a scan spent waiting for a reply that had already arrived.
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

    // ---- Channel-voice messages (not SysEx) ----

    /**
     * Bank select (CC 0) then program change - how a preset is loaded (section 7.6).
     *
     * Two plain channel-voice messages rather than SysEx, and exactly the (bank, slot)
     * decomposition an address already carries, so there is no arithmetic here at all. **Nothing
     * answers either message**, which is why they go out through `tell()` rather than `exchange()`.
     *
     * The bank select is always sent, even when the bank has not changed: it costs one three-byte
     * message, and the alternative - tracking the instrument's current bank host-side - is wrong
     * the moment the user touches the front panel, which they will, since the point of selecting a
     * preset is to then play it.
     */
    fun bankSelect(channel: Int, bank: Int): ByteArray =
        byteArrayOf((0xB0 or (channel and 0x0F)).toByte(), 0x00, (bank and 0x7F).toByte())

    fun programChange(channel: Int, slot: Int): ByteArray =
        byteArrayOf((0xC0 or (channel and 0x0F)).toByte(), (slot and 0x7F).toByte())
}
