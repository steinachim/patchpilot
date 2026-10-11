// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.printableAscii

/**
 * The Axe-Fx III's SysEx messages: builders, matchers and decoders, with no I/O.
 * `docs/PROTOCOLS.md` has the wire format.
 *
 * Every message is `F0 00 01 74 10 cc … cs F7`: Fractal's manufacturer id, model `10` (the
 * Axe-Fx III; assumed to be the same on a Mark II, which is untested), a command byte, its
 * payload, and an XOR checksum.
 *
 * Deliberately absent: an encoder for preset dump chunks. A dump's end-of-transfer marker is a
 * checksum over the chunks' wire bytes, padding bits included, whose algorithm is unknown, and
 * the instrument rejects a re-encoded dump while still acknowledging every step. A dump is only
 * ever replayed exactly as the instrument sent it ([AxeFx3DumpImage]).
 */
object AxeFx3SysEx {

    private const val SYSEX_START = 0xF0
    private const val SYSEX_END = 0xF7

    /** Fractal Audio's manufacturer id, then the Axe-Fx III's model byte. */
    val HEADER = byteArrayOf(0x00, 0x01, 0x74, 0x10)

    const val CMD_EDITOR = 0x01
    const val CMD_DUMP_READ = 0x03
    const val CMD_FIRMWARE = 0x08
    const val CMD_QUERY_NAME = 0x0D
    const val CMD_CAPABILITIES = 0x47
    const val CMD_STEP_ACK = 0x64
    const val CMD_DUMP_BEGIN = 0x77
    const val CMD_DUMP_CHUNK = 0x78
    const val CMD_DUMP_END = 0x79

    /** Sub-functions of [CMD_EDITOR], the editor's private channel. */
    const val SUB_STORE = 0x26
    const val SUB_SELECT = 0x27
    const val SUB_SET_NAME = 0x28

    const val NAME_FIELD_LENGTH = 32

    /** What a slot with no preset reports as its name, deleted and never-used slots alike. */
    const val EMPTY_NAME = "<EMPTY>"

    const val PRESET_COUNT_MARK_I = 512
    const val PRESET_COUNT_MARK_II = 1024

    const val DUMP_CHUNK_COUNT = 16
    const val DUMP_CHUNK_DATA_BYTES = 2688

    /** A whole `0x78` chunk message on the wire: 2,690 dense bytes packed into 3,075, plus framing. */
    const val DUMP_CHUNK_MESSAGE_BYTES = 3082

    /** A whole `0x79` end-of-transfer message: three marker bytes plus framing. */
    const val DUMP_END_MESSAGE_BYTES = 11

    /** The decoded bytes the instrument rewrites in every preset it stores, whatever it was sent. */
    val STORE_REWRITTEN_OFFSETS: Set<Int> = setOf(0, 1, 5, 6, 7, 8)

    /**
     * The value standing for "whichever preset is loaded" in a `0x0D` query, and in the dump
     * write's begin message for "the edit buffer".
     */
    private const val CURRENT = 0x7F

    /**
     * The two dense bytes a packed `cc`/`sub` pair leaves at the start of its decoded stream. Not
     * `cc` and `sub` themselves: their 14 bits do not end on a byte boundary, so building a packed
     * message means packing this artifact plus the payload, never `cc, sub, payload`. Measured off
     * real captures; the rename requests in `AxeFx3SysExTest` pin it.
     */
    private val SET_NAME_ARTIFACT = byteArrayOf(0x02, 0xA0.toByte())

    /**
     * The 12 bytes between the artifact and the name in every rename request captured from
     * Axe-Edit III. Not decoded; replayed verbatim, which renames stored live on two different
     * presets. Assumed to be the same for every preset.
     */
    private val SET_NAME_RESERVED = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10, 0)

    // ---- Framing ----

    /** XOR of everything from `F0` through the last data byte, masked to a legal data byte. */
    fun checksum(bytes: ByteArray, end: Int = bytes.size): Int {
        var x = 0
        for (i in 0 until end) x = x xor (bytes[i].toInt() and 0xFF)
        return x and 0x7F
    }

    /** A message whose payload is already 7-bit clean. */
    fun buildRaw(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = byteArrayOf(SYSEX_START.toByte()) + HEADER + byteArrayOf(cmd.toByte()) + payload
        return body + byteArrayOf(checksum(body).toByte(), SYSEX_END.toByte())
    }

    /**
     * Fractal's packing: the low seven bits of every wire byte form one bitstream, most
     * significant bit first, cut into bytes; a partial byte at the end is padding.
     */
    fun unpack7(wire: ByteArray, from: Int = 0, to: Int = wire.size): ByteArray {
        val out = java.io.ByteArrayOutputStream((to - from) * 7 / 8)
        var acc = 0
        var bits = 0
        for (i in from until to) {
            acc = (acc shl 7) or (wire[i].toInt() and 0x7F)
            bits += 7
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xFF)
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    /** The inverse of [unpack7], zero-padded to whole seven-bit groups. */
    fun pack7(dense: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(dense.size * 8 / 7 + 1)
        var acc = 0
        var bits = 0
        for (b in dense) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 7) {
                bits -= 7
                out.write((acc shr bits) and 0x7F)
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out.write((acc shl (7 - bits)) and 0x7F)
        return out.toByteArray()
    }

    /** Whether [message] is a whole, checksummed Axe-Fx III message. */
    fun isOurs(message: ByteArray): Boolean =
        message.size >= 8 &&
            (message[0].toInt() and 0xFF) == SYSEX_START &&
            (message.last().toInt() and 0xFF) == SYSEX_END &&
            HEADER.indices.all { message[1 + it] == HEADER[it] } &&
            checksum(message, message.size - 2) == (message[message.size - 2].toInt() and 0xFF)

    /** The command byte, or null for anything that is not ours. */
    fun commandOf(message: ByteArray): Int? =
        if (isOurs(message)) message[5].toInt() and 0xFF else null

    /** Payload byte [index] (after the command byte), or null where the message is shorter. */
    private fun payloadByte(message: ByteArray, index: Int): Int? =
        message.getOrNull(6 + index)?.takeIf { 6 + index < message.size - 2 }?.toInt()?.and(0xFF)

    private fun numberLsFirst(number: Int) = byteArrayOf((number and 0x7F).toByte(), (number shr 7).toByte())

    // ---- Identity ----

    fun firmwareRequest(): ByteArray = buildRaw(CMD_FIRMWARE)

    /** `"32.06"` and the build stamp from a `0x08` reply. */
    data class Firmware(val version: String, val build: String)

    fun decodeFirmware(reply: ByteArray): Firmware? {
        if (commandOf(reply) != CMD_FIRMWARE) return null
        val payload = reply.copyOfRange(6, reply.size - 2)
        if (payload.size < 2) return null
        val build = if (payload.size > 7) {
            String(payload.copyOfRange(7, payload.size), Charsets.US_ASCII).substringBefore('\u0000')
        } else {
            ""
        }
        return Firmware("${payload[0]}.${payload[1].toString().padStart(2, '0')}", build)
    }

    /** The editor's third startup message; the reply says which model this is. */
    fun capabilitiesRequest(): ByteArray = buildRaw(CMD_CAPABILITIES)

    /** A `0x47` reply, or a `64 47` acknowledgement in its place, which carries no record. */
    fun isCapabilitiesReply(message: ByteArray): Boolean = when (commandOf(message)) {
        CMD_CAPABILITIES -> true
        CMD_STEP_ACK -> payloadByte(message, 0) == CMD_CAPABILITIES
        else -> false
    }

    /**
     * The 14-bit values of a `0x47` reply, two bytes each, low seven bits first. Five on firmware
     * 32.06 (336, 2048, 2048, 16, 512 on a Mark I); an acknowledgement carries none. The fifth is
     * assumed to be the preset count; only the Mark I's 512 has been seen.
     */
    fun decodeCapabilities(reply: ByteArray): List<Int> {
        if (commandOf(reply) != CMD_CAPABILITIES) return emptyList()
        val payload = reply.copyOfRange(6, reply.size - 2)
        return (0 until payload.size / 2).map { i ->
            (payload[2 * i].toInt() and 0x7F) or ((payload[2 * i + 1].toInt() and 0x7F) shl 7)
        }
    }

    /**
     * A fifth value of exactly 1024 is a Mark II, anything else, a missing one included, a Mark I,
     * so a unit that cannot be classified is never offered slots it may not have. Assumed, since no
     * Mark II has been seen: a Mark II reports 1024, and a Mark II Turbo reports as a Mark II.
     */
    fun presetCountOf(capabilities: List<Int>): Int =
        if (capabilities.getOrNull(4) == PRESET_COUNT_MARK_II) PRESET_COUNT_MARK_II else PRESET_COUNT_MARK_I

    // ---- Names ----

    /** The public, plain-ASCII name query for preset [number]. */
    fun queryName(number: Int): ByteArray = buildRaw(CMD_QUERY_NAME, numberLsFirst(number))

    /** The same query for whichever preset is loaded; the reply carries its number. */
    fun queryLoaded(): ByteArray = buildRaw(CMD_QUERY_NAME, byteArrayOf(CURRENT.toByte(), CURRENT.toByte()))

    /** The preset number a `0x0D` reply echoes, or null for anything else. */
    fun nameReplyNumber(reply: ByteArray): Int? {
        if (commandOf(reply) != CMD_QUERY_NAME) return null
        val lo = payloadByte(reply, 0) ?: return null
        val hi = payloadByte(reply, 1) ?: return null
        return lo or (hi shl 7)
    }

    /** The raw 32-byte name field of a `0x0D` reply. */
    fun nameField(reply: ByteArray): ByteArray? {
        if (commandOf(reply) != CMD_QUERY_NAME) return null
        val start = 8
        if (reply.size - 2 < start + NAME_FIELD_LENGTH) return null
        return reply.copyOfRange(start, start + NAME_FIELD_LENGTH)
    }

    /**
     * A name field as text. It is a C string in a fixed buffer: whatever follows the first NUL is
     * left over from an earlier name (a delete writes `<EMPTY>` and a NUL over only the first
     * eight bytes), so it is cut there. Control bytes before it are dropped, as every family
     * drops them from a name it shows.
     */
    fun nameFromField(field: ByteArray): String {
        val end = field.indexOf(0).let { if (it < 0) field.size else it }
        return printableAscii(String(field, 0, end, Charsets.US_ASCII)).trimEnd()
    }

    /** Whether bytes other than NUL follow the name's terminator, which a delete leaves behind. */
    fun hasStaleTail(field: ByteArray): Boolean {
        val end = field.indexOf(0)
        return end >= 0 && (end + 1 until field.size).any { field[it].toInt() != 0 }
    }

    // ---- The editor channel: select, rename, store ----

    /** The 5-zeros / number / 7-zeros shape shared by select and store. */
    private fun slotMessage(sub: Int, number: Int): ByteArray =
        buildRaw(CMD_EDITOR, byteArrayOf(sub.toByte()) + ByteArray(5) + numberLsFirst(number) + ByteArray(7))

    fun select(number: Int): ByteArray = slotMessage(SUB_SELECT, number)

    /** Writes the edit buffer into slot [number]. A rename or a dump write changes nothing stored until this. */
    fun store(number: Int): ByteArray = slotMessage(SUB_STORE, number)

    /**
     * Renames whatever preset is loaded; the request carries no preset number. Space-padded to the
     * field, as Axe-Edit III sends it. [name] must already be printable ASCII.
     */
    fun setName(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= NAME_FIELD_LENGTH) { "a name is at most $NAME_FIELD_LENGTH bytes" }
        val field = bytes + ByteArray(NAME_FIELD_LENGTH - bytes.size) { 0x20 }
        val wire = pack7(SET_NAME_ARTIFACT + SET_NAME_RESERVED + field)
        val body = byteArrayOf(SYSEX_START.toByte()) + HEADER + wire
        return body + byteArrayOf(checksum(body).toByte(), SYSEX_END.toByte())
    }

    /** The acknowledgement of editor sub-function [sub]; it carries no preset number. */
    fun isEditorAck(message: ByteArray, sub: Int): Boolean =
        commandOf(message) == CMD_EDITOR && payloadByte(message, 0) == sub

    // ---- Preset dumps ----

    /** Asks for preset [number]'s dump. The number goes high byte first here, unlike everywhere else. */
    fun dumpReadRequest(number: Int): ByteArray =
        buildRaw(CMD_DUMP_READ, byteArrayOf((number shr 7).toByte(), (number and 0x7F).toByte(), 0))

    /** The acknowledgement that opens a dump read, echoing the requested number. */
    fun isDumpReadAck(message: ByteArray, number: Int): Boolean =
        commandOf(message) == CMD_DUMP_BEGIN &&
            payloadByte(message, 0) == (number shr 7) &&
            payloadByte(message, 1) == (number and 0x7F)

    /** Opens a dump write into the loaded preset's edit buffer; `7F 7F` is "the edit buffer". */
    fun dumpWriteBegin(): ByteArray =
        buildRaw(CMD_DUMP_BEGIN, byteArrayOf(CURRENT.toByte(), CURRENT.toByte(), 0x00, 0x00, 0x01))

    /** Whether [message] acknowledges dump step [step] (`0x77`, `0x78` or `0x79`). */
    fun isStepAck(message: ByteArray, step: Int): Boolean =
        commandOf(message) == CMD_STEP_ACK && payloadByte(message, 0) == step

    /** A step acknowledgement's status: 0 is success. A rejected dump is still acknowledged. */
    fun stepAckStatus(message: ByteArray): Int = payloadByte(message, 1) ?: -1

    /** The [DUMP_CHUNK_DATA_BYTES] of preset data inside one `0x78` chunk message. */
    fun decodeChunk(message: ByteArray): ByteArray =
        unpack7(message, 5, message.size - 2).copyOfRange(2, 2 + DUMP_CHUNK_DATA_BYTES)
}
