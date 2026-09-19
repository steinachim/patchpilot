// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.midi

/**
 * Reassembles complete SysEx messages out of a MIDI byte stream that respects no message
 * boundaries whatsoever.
 *
 * **A read is not a message.** Android's MIDI callback hands over whatever bytes have arrived, so
 * one `F0 ... F7` message may span any number of callbacks and split at any byte - including
 * between `F0` and the manufacturer id. Every rule below is a real MIDI hazard rather than
 * defensive padding, and the same four rules hold on the *other* bus: `NordDevice.readReply()`
 * reassembles across bulk reads, delimits by the reply's own length field, bounds that length,
 * and discards stale bytes before a new request.
 *
 * Not thread-safe: [SysExExchange] owns one of these and feeds it from a single collector.
 */
class SysExFramer(private val maxMessageBytes: Int = DEFAULT_MAX_MESSAGE_BYTES) {

    private var buffer = ArrayList<Byte>(256)
    private var inMessage = false

    /** Messages that were dropped for being over-long - a symptom worth surfacing, not hiding. */
    var droppedMessages: Int = 0
        private set

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
                // System realtime (F8-FF) may be interleaved *inside* a SysEx message and is not
                // part of it. Clock (F8) in particular arrives constantly if anything on the bus
                // is sending it, so treating one as a terminator would corrupt every dump taken
                // while a DAW was running.
                value >= 0xF8 -> Unit

                value == SYSEX_START -> {
                    // A second F0 before an F7 means the first message was abandoned. Keep the
                    // new one rather than the truncated old one.
                    buffer.clear()
                    inMessage = true
                    buffer.add(byte)
                }

                !inMessage -> Unit // channel-voice traffic between messages; see nonSysExBytes

                value == SYSEX_END -> {
                    buffer.add(byte)
                    completed += buffer.toByteArray()
                    buffer.clear()
                    inMessage = false
                }

                // Any other status byte aborts the message: this is what a device that never
                // sends F7 leaves behind, and delivering the fragment would hand a parser a
                // message that only looks whole.
                value >= 0x80 -> {
                    buffer.clear()
                    inMessage = false
                }

                else -> {
                    buffer.add(byte)
                    // Without this, a device that never terminates grows an unbounded buffer -
                    // one malfunctioning peripheral away from an OOM. Resync at the next F0.
                    if (buffer.size > maxMessageBytes) {
                        droppedMessages++
                        buffer.clear()
                        inMessage = false
                    }
                }
            }
        }
        return completed
    }

    /** Throws away any partial message. Called before a fresh exchange, so a half-arrived reply
     * from a timed-out one cannot be completed by the next reply's bytes. */
    fun reset() {
        buffer.clear()
        inMessage = false
    }

    companion object {
        const val SYSEX_START = 0xF0
        const val SYSEX_END = 0xF7

        /**
         * Generous for every message this app expects (a Pro-800 program dump is 210 bytes) while
         * still bounding a device that never terminates one. Not a protocol limit.
         */
        const val DEFAULT_MAX_MESSAGE_BYTES = 4096
    }
}
