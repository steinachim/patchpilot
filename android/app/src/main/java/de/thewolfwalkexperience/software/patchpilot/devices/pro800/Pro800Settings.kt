// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * The instrument's global settings block, decoded.
 *
 * Stored at [Pro800SysEx.SETTINGS_ADDRESS] and fetched with the same `0x77` request a preset uses,
 * with the same 7-bit overflow encoding - so nothing new is needed to read it.
 *
 * Only the fields the app actually acts on are modelled. Offsets come from
 * `Pro800SettingsConstants.h` (`{10, 1, "MIDI RX Channel"}`), on the same rule as the program
 * table: the header for offsets, the markdown for meaning.
 *
 * **This block is also where a preset is selected.** Offsets 6 and 23 are the instrument's own
 * selection pointer, and writing them is how [Pro800Instrument.select] works - see [withSelection].
 */
class Pro800Settings(private val dense: ByteArray) {

    /**
     * The raw `MIDI RX Channel` setting: 0 = ALL, 1 = the rear dip switches, 2..17 = channel 1..16,
     * 18 = OFF.
     *
     * **Nothing depends on this.** Selection is pure SysEx and needs no channel at all, so this is
     * read purely as a property of the instrument worth reporting in the device report.
     */
    val midiRxChannelSetting: Int?
        get() = if (dense.size > RX_CHANNEL_DENSE) {
            Pro800ProgramCodec.readValue(dense, RX_CHANNEL_DENSE, 1)
        } else {
            null
        }

    /**
     * The **flat** program number the instrument is pointed at, 0..399 - not the slot within a bank.
     *
     * Two bytes at raw offset 6. The firmware takes the slot digits from this modulo 100 and the
     * bank letter from [currentBank] separately, so a pointer at C60 reads 260 here, not 60. The
     * field is not validated by the instrument: `--set-setting` on the reference tool has left it
     * holding 560 across a power cycle while the display came up on D60.
     */
    val currentPresetNumber: Int?
        get() = if (dense.size > CURRENT_PRESET_DENSE + 1) {
            Pro800ProgramCodec.readValue(dense, CURRENT_PRESET_DENSE, 2)
        } else {
            null
        }

    /** The bank the instrument is pointed at, 0..3 for A..D. One byte at raw offset 23. */
    val currentBank: Int?
        get() = if (dense.size > CURRENT_BANK_DENSE) {
            Pro800ProgramCodec.readValue(dense, CURRENT_BANK_DENSE, 1)
        } else {
            null
        }

    /** How to describe the MIDI receive setting to a user, e.g. in a device report. */
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

        /** Raw offset 6, two bytes: the flat program number the instrument is pointed at. */
        val CURRENT_PRESET_DENSE = Pro800ProgramCodec.denseIndexOf(6)

        /** Raw offset 23, one byte: the bank, 0..3. */
        val CURRENT_BANK_DENSE = Pro800ProgramCodec.denseIndexOf(23)

        const val RX_ALL = 0
        const val RX_DIP_SWITCHES = 1
        const val RX_OFF = 18

        /** Settings value 2 is channel 1, which is wire channel 0. */
        const val RX_CHANNEL_OFFSET = 2

        fun fromEncoded(encodedPayload: ByteArray) = Pro800Settings(Pro800ProgramCodec.decode(encodedPayload))

        /**
         * A copy of a settings block's encoded payload with the selection pointer moved to
         * [programNumber] (flat, 0..399) in [bank].
         *
         * **Both fields, in one payload, on purpose.** They do not cross-validate each other: only
         * `Current Bank` actually selects the bank, `Current Preset Number` alone moves just the
         * slot digits, and a mismatched pair is silently accepted and survives a power cycle rather
         * than being rejected or normalized. Writing both in a single `0x78` never leaves the pair
         * inconsistent, not even transiently.
         *
         * Patched byte-by-byte rather than re-encoded - see [Pro800ProgramCodec.patchValue] for why
         * that distinction matters on this particular record.
         */
        fun withSelection(encodedPayload: ByteArray, programNumber: Int, bank: Int): ByteArray {
            val withPreset =
                Pro800ProgramCodec.patchValue(encodedPayload, CURRENT_PRESET_DENSE, 2, programNumber)
            return Pro800ProgramCodec.patchValue(withPreset, CURRENT_BANK_DENSE, 1, bank)
        }
    }
}
