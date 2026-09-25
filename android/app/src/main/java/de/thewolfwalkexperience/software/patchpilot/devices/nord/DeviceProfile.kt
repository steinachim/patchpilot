// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.serialization.Serializable

/**
 * The per-instrument constants [NordDevice] needs, one instance per entry in
 * devices/nord_devices.json. Field-for-field the schema's `$defs/device` shape, so a profile
 * serializes straight back into a paste-able catalog entry (see `NordInstrument.buildReport`).
 * [programCategoryIds] indexes the catalog's family-level `programCategories` master list - see
 * [DeviceCatalog] and [NordCategories].
 */
@Serializable
data class DeviceProfile(
    val id: String,
    val name: String,
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
    /** Firmware version codes this profile has been verified against. Empty means "skip the
     * firmware check" - see [NordDevice.validateFirmwareVersion] - used for [unknown], not for a
     * real supported instrument. */
    val supportedFirmwareVersions: Set<Int> = emptySet(),
    /** Which ids of the master programCategories list in devices/nord_devices.json this
     * instrument offers - an index into it, not a redefinition of it.
     * Null (always true for [unknown]) means "this instrument's subset has not been
     * established", which is what leaves such a device without a [NordTagger]. */
    val programCategoryIds: List<Int>? = null,
    /** Ids from [programCategoryIds] this instrument displays under a name other than the master
     * list's, id -> name. Both current instruments relabel 23/24 as EPiano1/EPiano2. */
    val programCategoryNameOverrides: Map<String, String>? = null,
) {
    companion object {
        /** The [id] [unknown] stamps on a device the catalog does not recognise; `InstrumentViewModel.isUnknownDevice` compares against it. */
        const val UNKNOWN_ID = "unknown"

        /**
         * Synthesizes a profile for a Clavia USB device the catalog does not list - the "at your
         * own risk" path. The bank bounds are guesses generous enough not to reject a real Nord.
         */
        fun unknown(name: String, vendorId: Int, productId: Int) = DeviceProfile(
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

/** The parsed contents of devices/nord_devices.json - see [InstrumentRegistry]. */
@Serializable
data class DeviceCatalog(
    val schemaVersion: Int,
    val devices: List<DeviceProfile>,
    /**
     * The master category table, `id -> name`, all 54 entries: one list shared across the Nord
     * line, of which each instrument names a subset via [DeviceProfile.programCategoryIds]. Not
     * injective (ids 2 and 37 are both `Wind`), so `name -> id` is defined only within one
     * instrument's subset - [NordCategories] builds that direction.
     */
    val programCategories: Map<String, String> = emptyMap(),
)
