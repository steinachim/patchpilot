package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

/**
 * Which mode the instrument is in, read from `0A 00 01`.
 *
 * **This exists because selection is gated on it.** The undocumented `4n` voice selection the app
 * uses works in [VOICE] only; in [PERFORMANCE] and [SONG] the instrument does not acknowledge it
 * and nothing changes. Every one of those failures is silent - the echo repeats what was *sent*
 * rather than reporting what happened - so reading the mode first is the only way the app can
 * tell a player why nothing happened.
 *
 * [PATTERN] and [MASTER] are presumed gated the same way, untested; they are listed because the
 * Data List does, and treating an unverified mode as "probably fine" is the wrong default when a
 * wrong assumption here fails silently.
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
