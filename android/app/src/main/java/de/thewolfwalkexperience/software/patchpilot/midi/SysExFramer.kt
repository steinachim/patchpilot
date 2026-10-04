// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.midi

/**
 * Reassembles complete SysEx messages out of a MIDI byte stream that respects no message
 * boundaries: one `F0 ... F7` message may span any number of reads and split at any byte.
 *
 * Not thread-safe: [SysExExchange] owns one of these, feeds it from a single collector and
 * synchronises on it for [pendingFrom].
 */
class SysExFramer(private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES) {

    /** The message being assembled is `buffer[0 until size]`; the array only grows. */
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var inMessage = false

    /** Messages that were dropped for being over-long - a symptom worth surfacing, not hiding;
     * [SysExExchange] logs each one. */
    var droppedMessages: Int = 0
        private set

    /**
     * How many messages have started, one per `F0`. A reader of [pendingFrom] compares it across
     * calls: a change means the message it was following was abandoned and another began, so its
     * offsets no longer apply.
     */
    var messageStarts: Long = 0
        private set

    /** How many bytes of the message still being assembled have arrived; 0 where none is. */
    val pendingSize: Int get() = if (inMessage) size else 0

    /**
     * The message still being assembled, from [offset] to what has arrived so far, `F0` included
     * at offset 0. Empty where no message is in progress or nothing past [offset] has arrived.
     *
     * For a message slow enough to be read while it arrives: a 200 KB dump at 1.4 KB/s. Copies only
     * the bytes from [offset], so a reader that advances its offset copies each byte once.
     */
    fun pendingFrom(offset: Int): ByteArray {
        if (!inMessage || offset >= size) return ByteArray(0)
        return buffer.copyOfRange(offset, size)
    }

    /**
     * Feeds raw inbound bytes and returns whatever complete SysEx messages they completed.
     *
     * Each returned message includes its own `F0` and `F7`, since that is what every parser above
     * expects to validate against.
     */
    fun feed(chunk: ByteArray): List<ByteArray> {
        val completed = mutableListOf<ByteArray>()
        for (byte in chunk) {
            val value = byte.toInt() and 0xFF
            when {
                // System realtime (F8-FF) may be interleaved inside a SysEx message and is not
                // part of it; clock (F8) arrives constantly while a DAW is running.
                value >= 0xF8 -> Unit

                value == SYSEX_START -> {
                    // A second F0 before an F7 means the first message was abandoned. Keep the
                    // new one rather than the truncated old one.
                    size = 0
                    inMessage = true
                    messageStarts++
                    append(byte)
                }

                !inMessage -> Unit // channel-voice traffic between messages is not ours

                value == SYSEX_END -> {
                    append(byte)
                    completed += buffer.copyOf(size)
                    size = 0
                    inMessage = false
                }

                // Any other status byte aborts the message: a device that never sends F7 must
                // not have its fragment delivered as a whole message.
                value >= 0x80 -> {
                    size = 0
                    inMessage = false
                }

                else -> {
                    append(byte)
                    // Bounds a device that never terminates; resync at the next F0.
                    if (size > maxMessageBytes) {
                        droppedMessages++
                        size = 0
                        inMessage = false
                    }
                }
            }
        }
        return completed
    }

    private fun append(byte: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = byte
    }

    companion object {
        const val SYSEX_START = 0xF0
        const val SYSEX_END = 0xF7

        /** Generous for a Pro-800 program dump (210 bytes); the Motif XS, Axe-Fx III and
         * Subsequent 37 families pass their own. Not a protocol limit. */
        const val DEFAULT_MAX_MESSAGE_BYTES = 4096

        private const val INITIAL_CAPACITY = 256
    }
}
