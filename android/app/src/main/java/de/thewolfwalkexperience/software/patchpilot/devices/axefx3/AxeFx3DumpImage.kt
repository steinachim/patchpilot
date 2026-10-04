// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.devices.axefx3.AxeFx3SysEx.CMD_DUMP_CHUNK
import de.thewolfwalkexperience.software.patchpilot.devices.axefx3.AxeFx3SysEx.CMD_DUMP_END
import de.thewolfwalkexperience.software.patchpilot.devices.axefx3.AxeFx3SysEx.DUMP_CHUNK_COUNT
import de.thewolfwalkexperience.software.patchpilot.devices.axefx3.AxeFx3SysEx.DUMP_CHUNK_MESSAGE_BYTES
import de.thewolfwalkexperience.software.patchpilot.devices.axefx3.AxeFx3SysEx.DUMP_END_MESSAGE_BYTES

/**
 * One preset as the instrument sent it: 16 `0x78` chunk messages and the `0x79` end-of-transfer
 * message, back to back, exactly as they arrived. This is also the `.syx` form [AxeFx3Instrument]
 * hands out through its transfer facet.
 *
 * The raw messages rather than the 43,008 decoded bytes, because a write must replay them
 * verbatim: the end marker checksums the chunks' wire bytes, including padding bits that are not
 * always zero, and the instrument stores nothing from a dump whose marker does not match. The
 * constructor is private and nothing here encodes, so an image can only come from the instrument
 * (or from a file it once sent).
 */
class AxeFx3DumpImage private constructor(
    val raw: ByteArray,
    val chunks: List<ByteArray>,
    val end: ByteArray,
) {
    /** The preset's data, for comparing two images. Never written back. */
    val decodedData: ByteArray by lazy {
        val out = java.io.ByteArrayOutputStream(DUMP_CHUNK_COUNT * AxeFx3SysEx.DUMP_CHUNK_DATA_BYTES)
        chunks.forEach { out.write(AxeFx3SysEx.decodeChunk(it)) }
        out.toByteArray()
    }

    /**
     * Whether [other] holds the same preset. The instrument rewrites a few header bytes in every
     * preset it stores ([AxeFx3SysEx.STORE_REWRITTEN_OFFSETS]), so a read-back never equals what
     * was written byte for byte.
     */
    fun sameStoredDataAs(other: AxeFx3DumpImage): Boolean {
        val a = decodedData
        val b = other.decodedData
        if (a.size != b.size) return false
        return a.indices.all { it in AxeFx3SysEx.STORE_REWRITTEN_OFFSETS || a[it] == b[it] }
    }

    companion object {
        /**
         * An image from its raw bytes, e.g. a shipped blank or a file. Throws
         * [IllegalArgumentException] for anything that is not exactly 16 well-formed chunk
         * messages and one end message.
         */
        fun parse(raw: ByteArray): AxeFx3DumpImage {
            val messages = split(raw)
            require(messages.size == DUMP_CHUNK_COUNT + 1) {
                "An Axe-Fx III preset is ${DUMP_CHUNK_COUNT + 1} SysEx messages; this has ${messages.size}."
            }
            validate(messages)?.let { throw IllegalArgumentException(it) }
            return AxeFx3DumpImage(raw.copyOf(), messages.dropLast(1), messages.last())
        }

        /**
         * An image from what a dump read collected after its acknowledgement: 16 chunks, then
         * the end message. Anything else means a message was lost or damaged on the way, which
         * is a [InstrumentException.ProtocolDesync] the caller may answer by reading again.
         */
        fun fromReadReplies(messages: List<ByteArray>): AxeFx3DumpImage {
            if (messages.size != DUMP_CHUNK_COUNT + 1) {
                throw InstrumentException.ProtocolDesync(
                    "The preset arrived as ${messages.size} messages instead of ${DUMP_CHUNK_COUNT + 1}.",
                )
            }
            validate(messages)?.let { throw InstrumentException.ProtocolDesync("The preset arrived damaged: $it") }
            val raw = java.io.ByteArrayOutputStream(
                DUMP_CHUNK_COUNT * DUMP_CHUNK_MESSAGE_BYTES + DUMP_END_MESSAGE_BYTES,
            )
            messages.forEach { raw.write(it) }
            return AxeFx3DumpImage(raw.toByteArray(), messages.dropLast(1), messages.last())
        }

        /** What is wrong with [messages] as 16 chunks plus an end, or null where nothing is. */
        private fun validate(messages: List<ByteArray>): String? {
            messages.forEachIndexed { index, message ->
                val isEnd = index == DUMP_CHUNK_COUNT
                val wantCommand = if (isEnd) CMD_DUMP_END else CMD_DUMP_CHUNK
                val wantSize = if (isEnd) DUMP_END_MESSAGE_BYTES else DUMP_CHUNK_MESSAGE_BYTES
                val what = if (isEnd) "the end marker" else "chunk ${index + 1}"
                when {
                    !AxeFx3SysEx.isOurs(message) -> return "$what is not a valid Axe-Fx III message"
                    AxeFx3SysEx.commandOf(message) != wantCommand -> return "$what has the wrong command"
                    message.size != wantSize -> return "$what is ${message.size} bytes, not $wantSize"
                    !isEnd && message[6].toInt() != 0 -> return "$what has an unexpected sub-command"
                }
            }
            return null
        }

        /** Raw bytes into SysEx messages; a dump's data bytes are 7-bit, so `F7` only ends one. */
        private fun split(raw: ByteArray): List<ByteArray> {
            val messages = mutableListOf<ByteArray>()
            var start = 0
            for (i in raw.indices) {
                if (raw[i] == 0xF7.toByte()) {
                    messages += raw.copyOfRange(start, i + 1)
                    start = i + 1
                }
            }
            require(start == raw.size) { "The data ends in the middle of a SysEx message." }
            return messages
        }
    }
}
