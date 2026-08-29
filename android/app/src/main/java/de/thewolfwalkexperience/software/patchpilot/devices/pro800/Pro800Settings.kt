package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * The instrument's global settings block, decoded.
 *
 * Stored at [Pro800SysEx.SETTINGS_ADDRESS] and fetched with the same `0x77` request a preset uses,
 * with the same 7-bit overflow encoding - so nothing new is needed to read it.
 *
 * Only the one field the app actually acts on is modelled. Offsets come from
 * `Pro800SettingsConstants.h` (`{10, 1, "MIDI RX Channel"}`), on the same rule as the program
 * table: the header for offsets, the markdown for meaning.
 */
class Pro800Settings(private val dense: ByteArray) {

    /**
     * The raw `MIDI RX Channel` setting: 0 = ALL, 1 = the rear dip switches, 2..17 = channel 1..16,
     * 18 = OFF.
     */
    val midiRxChannelSetting: Int?
        get() = if (dense.size > RX_CHANNEL_DENSE) {
            Pro800ProgramCodec.readValue(dense, RX_CHANNEL_DENSE, 1)
        } else {
            null
        }

    /**
     * The channel to *send* on, 0-based for the wire, or null where the instrument will not accept
     * program changes on any channel we can determine.
     *
     * - **ALL** - anything is heard; channel 0 is as good as any.
     * - **A fixed channel** - the setting is offset by two, so a front-panel "3" reads as 5 here
     *   and means wire channel 2.
     * - **Dip switches** - the instrument knows, and does not say. Null, so the caller falls back
     *   to whatever is configured.
     * - **OFF** - no channel will work, and saying so beats a button that silently does nothing.
     */
    val sendChannel: Int?
        get() = when (val setting = midiRxChannelSetting) {
            null -> null
            RX_ALL -> 0
            RX_DIP_SWITCHES -> null
            RX_OFF -> null
            else -> (setting - RX_CHANNEL_OFFSET).takeIf { it in 0..15 }
        }

    /** True where the instrument is set to ignore incoming MIDI entirely. */
    val midiReceiveDisabled: Boolean get() = midiRxChannelSetting == RX_OFF

    /** How to describe the setting to a user, e.g. in a "selection will not work" message. */
    val midiRxDescription: String
        get() = when (val setting = midiRxChannelSetting) {
            null -> "unknown"
            RX_ALL -> "ALL"
            RX_DIP_SWITCHES -> "set by the rear dip switches"
            RX_OFF -> "OFF"
            else -> "channel ${setting - RX_CHANNEL_OFFSET + 1}"
        }

    companion object {
        /** Raw offset 10 in the settings record. */
        val RX_CHANNEL_DENSE = Pro800ProgramCodec.denseIndexOf(10)

        const val RX_ALL = 0
        const val RX_DIP_SWITCHES = 1
        const val RX_OFF = 18

        /** Settings value 2 is channel 1, which is wire channel 0. */
        const val RX_CHANNEL_OFFSET = 2

        fun fromEncoded(encodedPayload: ByteArray) = Pro800Settings(Pro800ProgramCodec.decode(encodedPayload))
    }
}
