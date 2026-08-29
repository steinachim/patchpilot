package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.serialization.Serializable

/**
 * The per-instrument constants [NordDevice] needs, one instance per entry in
 * devices/nord_devices.json (repo root) - the source of truth this is parsed from (see
 * [InstrumentRegistry]). One JSON entry per instrument, data rather than a subclass.
 * Field-for-field, this is exactly devices/nord_devices.schema.json's
 * `$defs/device` shape - deliberately so a [DeviceProfile] can be serialized straight back into a
 * paste-able catalog entry (see NordInstrument.buildReport(), which is what a user shares) with
 * no field mapping.
 * [programCategoryIds] is otherwise unused - nothing in this app reads preset category
 * tags yet.
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
    /**
     * How many characters the instrument's display holds.
     *
     * **Nothing in this app reads it any more** - the Show Text screen and the `DisplayText`
     * facet were removed. It stays because this class mirrors `devices/nord_devices.json`,
     * whose schema still declares the field. Deleting it here would put the two out of step
     * to save nothing.
     */
    val maxDisplayTextLen: Int,
    /**
     * The longest program name the instrument actually stores.
     *
     * **Measured, not guessed.** An oversized `SET_NAME` is accepted with status 0 and the
     * instrument silently keeps this many characters - so the failure it prevents is a rename
     * that reports success and leaves the preset called something else.
     *
     * Its own field rather than a reuse of [maxDisplayTextLen], even though both are 16 on every
     * instrument measured so far. They are different limits that happen to coincide, and a Nord
     * with a wider display than name field would have made that reuse wrong in a way nothing
     * would have reported. Optional in the catalog, defaulting to [maxDisplayTextLen] so an
     * entry written before the field existed still loads - a fallback that is a guess where the
     * field is a measurement.
     */
    val maxProgramNameLen: Int = maxDisplayTextLen,
    /** Firmware version codes this profile has been verified against. Empty means "skip the
     * firmware check" - see [NordDevice.validateFirmwareVersion] - used for [unknown], not for a
     * real supported instrument. */
    val supportedFirmwareVersions: Set<Int> = emptySet(),
    /** Which ids of the master programCategories list in devices/nord_devices.json this
     * instrument offers - an index into it, not a redefinition of it.
     * Null (always true for [unknown]) means "this instrument's subset has not been
     * established". See the class doc for why this otherwise-unread field is modeled at all. */
    val programCategoryIds: List<Int>? = null,
    /** Ids from [programCategoryIds] this instrument displays under a name other than the master
     * list's, id -> name. Both current instruments relabel 23/24 as EPiano1/EPiano2. */
    val programCategoryNameOverrides: Map<String, String>? = null,
) {
    companion object {
        /**
         * The [id] [unknown] stamps on a device the catalog does not recognise.
         *
         * Named because two callers in another package compare against it to decide whether to
         * derive a bank layout and whether to offer the device report - see
         * `InstrumentViewModel.isUnknownDevice`. It was the string `"unknown"` at all three sites,
         * one of them the producer, while [DEMO_ID] beside it was already a constant.
         */
        const val UNKNOWN_ID = "unknown"

        /**
         * Synthesizes a profile for a USB device the user picked despite it not being in
         * devices/nord_devices.json - see InstrumentViewModel.confirmUnknownDevice() and
         * ConnectScreen's "unsupported, at your own risk" flow. The
         * bank-layout bounds are guesses generous enough not to reject a real Nord instrument
         * this app simply doesn't have a profile for yet, not the measured values of a real
         * catalog entry.
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
)
