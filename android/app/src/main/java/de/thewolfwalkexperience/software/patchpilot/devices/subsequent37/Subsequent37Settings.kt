// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

/**
 * The settings block (`07 02`, 828 bytes): 268 words of MIDI and global settings, with the
 * selected program in the header. Three words are named; the rest are carried for the device
 * report only.
 *
 * | Word | Contents |
 * |---|---|
 * | 2 | selected program, 0..255 |
 * | 30 | MIDI IN CHANNEL: 0..15 = channel 1..16, 16 = ALL |
 * | 31 | MIDI OUT CHANNEL: 0..15 = channel 1..16 |
 */
class Subsequent37Settings private constructor(val words: List<Int>) {

    val selectedProgram: Int get() = words[WORD_SELECTED_PROGRAM]

    /** 0..15, or [IN_CHANNEL_ALL]. */
    val inChannel: Int get() = words[WORD_IN_CHANNEL]

    val outChannel: Int get() = words[WORD_OUT_CHANNEL]

    /**
     * The channel a selection is sent on, 0..15: the IN channel, or channel 1 where it is ALL,
     * which listens on every channel.
     */
    val selectionChannel: Int get() = if (inChannel in 0..15) inChannel else 0

    /** The IN channel as the panel shows it: `1`..`16` or `ALL`. */
    val inChannelLabel: String get() = if (inChannel in 0..15) "${inChannel + 1}" else "ALL"

    companion object {
        const val IN_CHANNEL_ALL = 16
        private const val WORD_SELECTED_PROGRAM = 2
        private const val WORD_IN_CHANNEL = 30
        private const val WORD_OUT_CHANNEL = 31

        /** The settings in a whole `07 02` reply, or null for anything else, a short reply included. */
        fun parse(message: ByteArray): Subsequent37Settings? {
            if (!Subsequent37SysEx.isSettingsReply(message)) return null
            val body = Subsequent37SysEx.unpack(message, Subsequent37SysEx.BODY_OFFSET, Subsequent37SysEx.SETTINGS_WORDS) ?: return null
            return Subsequent37Settings(
                List(Subsequent37SysEx.SETTINGS_WORDS) { ((body[it * 2].toInt() and 0xFF) shl 8) or (body[it * 2 + 1].toInt() and 0xFF) },
            )
        }
    }
}
