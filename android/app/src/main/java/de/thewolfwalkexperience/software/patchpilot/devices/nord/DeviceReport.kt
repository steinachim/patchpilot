package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.serialization.Serializable

/**
 * Everything this app can read off a connected instrument without changing anything on it -
 * the payload behind ProgramsScreen's "Share device details" button.
 *
 * Two parts, because they serve different readers:
 *
 *  - [catalogEntry] is a [DeviceProfile], field-for-field devices/nord_devices.schema.json's
 *    `$defs/device` shape, meant to be lifted straight into that file's `devices` array. It is
 *    filled in with everything the wire can settle: the USB ids, the firmware version, and the
 *    bank count and capacity the `Program` category reports. What it cannot fill is the grouping
 *    of a bank's slots (nothing announces it) and `programCategoryIds`, since the wire only ever
 *    carries a category's numeric id - which ids an instrument offers, and what it calls them,
 *    has to be established from the names the instrument displays.
 *  - [probe] is the evidence behind it, plus the replies nothing in this repo decodes yet, kept
 *    as raw hex rather than a guessed parse. That is the half worth having when the question is
 *    "what does this instrument actually do", as opposed to "how do I add it to the catalog".
 *
 * **Read-only by construction.** Every field here comes from a query; nothing in the collection
 * path creates, writes, deletes, renames, moves or reclaims. It does briefly hold the
 * SELECT_CATEGORY lock per category, which the instrument shows as status-message mode,
 * and it is slow on a large instrument - the calibration walks every item.
 */
@Serializable
data class DeviceReport(
    val reportVersion: Int = 1,
    val catalogEntry: DeviceProfile,
    val probe: DeviceProbe,
)

@Serializable
data class DeviceProbe(
    /**
     * Probes that failed, keyed by what was being read (`"storage"`,
     * `"categoryChild[Piano]"`, ...) with the error as the value. Empty on a clean run.
     *
     * A report is generated *against an instrument nobody has profiled*, which is exactly where
     * an advanced probe is most likely to be unsupported, time out, or answer something this app
     * can't parse. So no probe below is allowed to be fatal: whatever fails is recorded here and
     * the rest of the report is collected and sent regardless. A partial report with this map
     * filled in is far more useful to whoever receives it than no report at all - the failures
     * are themselves a finding about the instrument.
     */
    val failures: Map<String, String> = emptyMap(),
    /** e.g. "1.68" - the scaled code lives in [DeviceProfile.supportedFirmwareVersions]. */
    val firmwareVersion: String,
    val usbVendorId: String,
    val usbProductId: String,
    /**
     * The name this session is driving the instrument under: its catalog entry's name for a
     * known device, and the USB product name plus ids ([UsbDevice.displayLabel]) for one the
     * catalog doesn't have.
     */
    val profileName: String,
    /**
     * The device-info reply's protocol version table, keyed by command. Where
     * [DeviceProfile.protocolVersion] comes from, and the rest of it has never been used for anything -
     * commands 10 and 13 appear here and are not otherwise known.
     */
    val commandTargets: Map<String, Int>,
    /** Raw capability-query reply (cmd 6, sub-op 4/5), undecoded here. */
    val capabilityQueryHex: String,
    val rootCategories: List<String>,
    /**
     * The raw sub-opcode 0/1 payload the names were parsed out of. Worth shipping because each
     * category's trailer is undecoded past its native/factory flag.
     */
    val rootCategoryListHex: String,
    /**
     * Recovered from the response above, not configured - see [NordDevice.detectRootCategoryTrailerLen].
     *
     * **Deliberately the derived value, not [NordDevice.rootCategoryTrailerLenForVersion]'s.** The
     * parse paths prefer the version rule, but a report exists to describe an instrument
     * nobody has profiled - very possibly one whose version this build has never seen. Reporting
     * the rule's answer would echo our own assumption back at whoever reads the report; reporting
     * what the response itself implies is the evidence needed to extend the known version table.
     * The raw payload is in [rootCategoryListHex] for the same reason.
     */
    val rootCategoryTrailerLen: Int?,
    /**
     * Each root category's child list (sub-opcode 2/3), decoded: banks for most categories,
     * named piano types for `Piano`, each with its slot capacity. See
     * [NordDevice.parseCategoryChildren] for the layout and how firmly it is established.
     */
    val categoryChildren: Map<String, List<CategoryChildReport>>,
    /**
     * The same replies as raw hex. Kept beside the parse rather than replaced by it: the layout
     * was recovered from one instrument, so a report from a different one is worth having in a
     * form that survives the parse being wrong.
     */
    val categoryChildHex: Map<String, String>,
    /**
     * What the `Program` category's child list says about addressing: how many banks, and how
     * many slots each holds. Null if it couldn't be read. This is the derivable part of
     * [DeviceProfile]'s bank bounds - the grouping of a bank's slots is not derivable at all,
     * see [NordDevice.applyDerivedBankLayout].
     */
    val derivedBankCount: Int? = null,
    val derivedSlotsPerBank: Int? = null,
    val storageAreas: List<StorageAreaReport>,
)

@Serializable
data class CategoryChildReport(val name: String, val capacity: Int)

/**
 * One storage area's figures and what its own item records say its allocation unit is.
 * [names] holds every root category the area is exposed under, since
 * `Samp Lib` and `Samp Lib (Native)` are one area under two names.
 */
@Serializable
data class StorageAreaReport(
    val names: List<String>,
    /**
     * Each category's own item count, keyed by name. Per category rather than per area because
     * the counts differ where the storage figures don't: the Nord Grand's shared
     * `Program`/`Live`/`Settings` area reports 202, 5 and 1 against one identical triple.
     */
    val itemCounts: Map<String, Int>,
    val free: Int,
    val used: Int,
    val reclaimable: Int,
    /** Sub-opcode 9's sixth field: a code, not a size. 0 means the area is counted in bytes. */
    val unitCode: Int,
    val countedInBytes: Boolean,
    /** Distinct item records the calibration actually pooled for this area. */
    val recordCount: Int,
    val totalBytes: Long,
    /**
     * The allocation unit **the instrument reports** for this area, from its root-list trailer.
     * This is the figure everything actually uses; it is not derived from
     * the sixth field and not configured anywhere.
     */
    val reportedUnitBytes: Int? = null,
    /**
     * The unit this area's own records fit, as a cross-check on [reportedUnitBytes] - or null
     * when they don't determine one. A fit only narrows the unit to [candidateRange] and then
     * names the roundest value in it, so these two agreeing exactly is not expected; the
     * reported figure falling inside the range is what matters.
     */
    val fittedUnitBytes: Int? = null,
    /** `used` minus the sum the solved unit produces - per-item overhead the records don't show. */
    val residual: Int? = null,
    /**
     * The same overhead at [reportedUnitBytes] instead of [fittedUnitBytes] - the figure that
     * matters, since the reported unit is the one in use. The two differ whenever the fit's
     * roundest candidate is not the reported value, which is the usual case.
     */
    val reportedResidual: Int? = null,
    /** Unit sizes that fit as well as the reported one, as "lo-hi" bytes. */
    val candidateRange: String? = null,
    /** Always set: says what was found, or why nothing was. */
    val note: String,
)
