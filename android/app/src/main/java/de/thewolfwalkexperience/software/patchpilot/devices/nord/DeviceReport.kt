// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.serialization.Serializable

/**
 * Everything this app can read off a connected Nord without changing anything on it.
 *
 *  - [catalogEntry] is a [DeviceProfile] in the schema's `$defs/device` shape, meant to be lifted
 *    straight into `devices/nord_devices.json`, filled in with what the wire can settle: USB ids,
 *    firmware version, and the bank count and capacity the `Program` category reports. It cannot
 *    fill the grouping of a bank's slots (nothing announces it) or `programCategoryIds` (the wire
 *    carries only numeric ids).
 *  - [probe] is the evidence behind it, plus the replies nothing here decodes, as raw hex.
 *
 * Every field comes from a query; the only side effect is the per-category SELECT_CATEGORY lock.
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
     * Probes that failed, keyed by what was being read (`"storage"`, `"categoryChild[Piano]"`,
     * ...) with the error as the value. Empty on a clean run; see `core.Probes`.
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
     * The device-info reply's protocol version table, `protocol id -> version`. Where
     * `NordDevice.protocolVersionFileTransfer` is read from; protocols 10 and 13 appear here and
     * are not otherwise used. The JSON key stays `commandTargets`: renaming it would change the
     * report format.
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
     * Derived from the response ([NordDevice.detectRootCategoryTrailerLen]), not from the version
     * rule: a report describes an instrument whose version this build may never have seen, and
     * what the response implies is the evidence for extending the version table.
     */
    val rootCategoryTrailerLen: Int?,
    /**
     * Each root category's child list (sub-opcode 2/3), decoded: banks for most categories,
     * named piano types for `Piano`, each with its slot capacity. See
     * [NordDevice.parseCategoryChildren] for the layout and how firmly it is established.
     */
    val categoryChildren: Map<String, List<CategoryChildReport>>,
    /** The same replies as raw hex, in case the parse is wrong for this instrument. */
    val categoryChildHex: Map<String, String>,
    /** What the `Program` category's child list says about addressing: bank count and slots per bank. Null if unreadable. */
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
    /** Each category's own item count, keyed by name; these differ within an area where the storage figures do not. */
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
    /** The allocation unit the instrument reports for this area, from its root-list trailer. */
    val reportedUnitBytes: Int? = null,
    /**
     * The unit this area's own records fit, as a cross-check on [reportedUnitBytes]: the
     * roundest value in [candidateRange]. What matters is the reported figure falling inside the
     * range, not the two agreeing.
     */
    val fittedUnitBytes: Int? = null,
    /** `used` minus the sum the fitted unit produces - per-item overhead the records do not show. */
    val residual: Int? = null,
    /** The same overhead at [reportedUnitBytes] instead of [fittedUnitBytes]. */
    val reportedResidual: Int? = null,
    /** Unit sizes that fit as well as the reported one, as "lo-hi" bytes. */
    val candidateRange: String? = null,
    /** Always set: says what was found, or why nothing was. */
    val note: String,
)
