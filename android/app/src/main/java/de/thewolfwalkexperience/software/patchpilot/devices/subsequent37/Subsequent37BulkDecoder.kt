// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

/**
 * Programs out of a bulk dump (`01 07`) as its bytes arrive, in any chunking: block `i` is
 * program `i`, at offset `23 + 796 × i`, and its 732 packed bytes can be checked on their own
 * (record markers, checksum) without waiting for the rest.
 *
 * A dump that lost bytes in transit still ends in `F7`, with every block after the loss shifted.
 * So decoding stops at the first block that fails, and nothing after it is ever returned, even
 * a block that happens to pass.
 */
class Subsequent37BulkDecoder {

    private var buffer = ByteArray(0)
    private var size = 0

    /** How many bytes of the message have been fed. */
    val received: Int get() = size

    /** The next block to decode; also how many have been returned. */
    var decoded: Int = 0
        private set

    /** The first block that failed, or null while none has. */
    var brokenAt: Int? = null
        private set

    /** Whether the bytes fed so far start like a bulk dump; false until five have arrived. */
    val headerSeen: Boolean
        get() = size >= 5 && Subsequent37SysEx.isCommand(buffer.copyOf(5), *Subsequent37SysEx.REPLY_BULK_DUMP)

    /** Appends the next bytes of the message, `F0` first, and returns the programs they completed. */
    fun feed(chunk: ByteArray): List<Subsequent37Program> {
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size, INITIAL_CAPACITY))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
        return decodeAvailable()
    }

    /**
     * What is wrong with the message once it has ended at [total] bytes, `F7` included, or null
     * where it is a whole dump of [Subsequent37SysEx.PROGRAM_COUNT] programs. Call after feeding it all.
     */
    fun problemAtEnd(total: Int = size): String? = when {
        !headerSeen -> "the reply is not a bulk dump"

        brokenAt != null -> "program $brokenAt arrived damaged"

        (total - Subsequent37SysEx.BODY_OFFSET - 1) % Subsequent37SysEx.BULK_STRIDE != 0 ->
            "the reply is $total bytes, which is not a whole number of programs"

        decoded < Subsequent37SysEx.PROGRAM_COUNT ->
            "the reply holds $decoded programs, not ${Subsequent37SysEx.PROGRAM_COUNT}"

        else -> null
    }

    private fun decodeAvailable(): List<Subsequent37Program> {
        if (brokenAt != null || size < 5) return emptyList()
        if (!headerSeen) {
            brokenAt = decoded
            return emptyList()
        }
        val out = mutableListOf<Subsequent37Program>()
        while (decoded < Subsequent37SysEx.PROGRAM_COUNT) {
            val at = Subsequent37SysEx.BODY_OFFSET + decoded * Subsequent37SysEx.BULK_STRIDE
            if (at + Subsequent37SysEx.PACKED_PROGRAM_BYTES > size) break
            val payload = Subsequent37SysEx.unpack(buffer, at, Subsequent37SysEx.PAYLOAD_BYTES / 2)
            if (payload == null || !Subsequent37SysEx.checksumCloses(payload)) {
                brokenAt = decoded
                break
            }
            out += Subsequent37Program(payload)
            decoded++
        }
        return out
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
