// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * Which mode the instrument is in, read from `0A 00 01`. Selection is gated on it: the `4n` voice
 * selection works in [VOICE] only, and in [PERFORMANCE] and [SONG] the instrument does not
 * acknowledge it and nothing changes, silently - the echo repeats what was sent. [PATTERN] and
 * [MASTER] are presumed gated the same way, untested.
 */
enum class MotifXsMode(val value: Int, val label: String) {
    VOICE(0, "Voice"),
    PERFORMANCE(1, "Performance"),
    PATTERN(2, "Pattern"),
    SONG(3, "Song"),
    MASTER(4, "Master"),
    ;

    /** True where a voice selection is known or presumed to be ignored. */
    val blocksVoiceSelection: Boolean get() = this != VOICE

    companion object {
        fun of(value: Int): MotifXsMode? = entries.firstOrNull { it.value == value }
    }
}
