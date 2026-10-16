// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import kotlinx.serialization.Serializable

/**
 * The per-instrument constants [NordDevice] needs, one instance per entry in
 * devices/nord_devices.json. Field-for-field the schema's `$defs/device` shape, so a profile
 * serializes straight back into a paste-able catalog entry (see `NordReporter.buildReport`).
 * [programCategoryIds] indexes the catalog's family-level `programCategories` master list - see
 * [NordCatalog] and [NordCategories].
 */
@Serializable
data class NordProfile(
    val id: String,
    val name: String,
    /** The trailing part of [name] that tells this model from its siblings, e.g. "2 EX"; see [InstrumentDescriptor.variant]. */
    val variant: String? = null,
    val vendorId: Int,
    val productId: Int,
    val maxBankLetter: Char,
    val maxGroup: Int,
    val slotsPerGroup: Int,
    /** How many characters the instrument's display holds. Nothing in this app reads it; the schema declares it. */
    val maxDisplayTextLen: Int,
    /**
     * The longest program name the instrument stores (measured: an oversized `SET_NAME` is
     * accepted with status 0 and silently truncated). Its own field rather than
     * [maxDisplayTextLen], which is a different limit that coincides on every instrument
     * measured; defaults to it where a catalog entry omits this.
     */
    val maxProgramNameLen: Int = maxDisplayTextLen,
    /**
     * Firmware version codes this profile has been verified against. Empty means "raise no
     * firmware advisory" - see [NordDevice.validateFirmwareVersion] - used for [unknown], not for
     * a real supported instrument.
     */
    val supportedFirmwareVersions: Set<Int> = emptySet(),
    /**
     * Which ids of the master programCategories list in devices/nord_devices.json this
     * instrument offers - an index into it, not a redefinition of it.
     * Null (always true for [unknown]) means "this instrument's subset has not been
     * established", which is what leaves such a device without a [NordTagger].
     */
    val programCategoryIds: List<Int>? = null,
    /**
     * Ids from [programCategoryIds] this instrument displays under a name other than the master
     * list's, id -> name: the Grand relabels 23/24 as EPiano1/EPiano2, the Stage 4 ten ids.
     */
    val programCategoryNameOverrides: Map<String, String>? = null,
    /**
     * How a Program preset becomes Bank Select and Program Change; null where the instrument's
     * manual does not say, which leaves Info without MIDI values. See [MidiProgramSelect].
     */
    val midiProgramSelect: MidiProgramSelect? = null,
) {
    companion object {
        /** The [id] [unknown] stamps on a device the catalog does not recognise - see `InstrumentIdentity.isGuessed`. */
        const val UNKNOWN_ID = InstrumentIdentity.UNKNOWN_DESCRIPTOR_ID

        /**
         * Synthesizes a profile for a Clavia USB device the catalog does not list - the "at your
         * own risk" path. The bank bounds are guesses generous enough not to reject a real Nord.
         */
        fun unknown(name: String, vendorId: Int, productId: Int) = NordProfile(
            id = UNKNOWN_ID,
            name = name,
            vendorId = vendorId,
            productId = productId,
            maxBankLetter = 'Z',
            maxGroup = 100,
            slotsPerGroup = 5,
            maxDisplayTextLen = 16,
            supportedFirmwareVersions = emptySet(),
        )
    }
}

/**
 * The Program banks' Bank Select / Program Change layout. Taken from the manuals; not measured on
 * an instrument. A panel bank holds `N = maxGroup x slotsPerGroup` presets and [banksPerLsb]
 * consecutive banks share one LSB value, the Program Change counting through all of them:
 * `LSB = bank / banksPerLsb`, `PC = (bank % banksPerLsb) x N + slot`.
 *
 * [msb] is the Program content type's Bank Select MSB. The Program Change is the 0-based wire
 * value; three manuals number theirs from 1 (assumption: that is the panel's numbering, not the wire's).
 */
@Serializable
data class MidiProgramSelect(val msb: Int, val banksPerLsb: Int) {
    init {
        require(banksPerLsb >= 1) { "banksPerLsb must be positive, was $banksPerLsb" }
    }
}

/** The parsed contents of devices/nord_devices.json - see [InstrumentRegistry]. */
@Serializable
data class NordCatalog(
    val schemaVersion: Int,
    val devices: List<NordProfile>,
    /**
     * The master category table, `id -> name`, all 54 entries: one list shared across the Nord
     * line, of which each instrument names a subset via [NordProfile.programCategoryIds]. Not
     * injective (ids 2 and 37 are both `Wind`), so `name -> id` is defined only within one
     * instrument's subset - [NordCategories] builds that direction.
     */
    val programCategories: Map<String, String> = emptyMap(),
)
