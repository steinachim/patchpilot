// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.REPORT_JSON
import de.thewolfwalkexperience.software.patchpilot.core.ReportIdentity
import de.thewolfwalkexperience.software.patchpilot.core.slugifyDeviceId
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * Everything the app can read off a Nord without changing anything on it, as JSON: the device
 * info and capability replies, the categories and their child lists, the bank layout they imply,
 * and each storage area's measured allocation unit, plus a catalog entry built from them
 * ([NordReport]).
 *
 * Runs under the instrument's own [deviceLock], as every Nord operation does: a category walk
 * must not interleave with a listing.
 */
internal class NordReporter(
    private val device: NordDevice,
    private val deviceLock: Mutex,
    private val identity: () -> InstrumentIdentity,
) : DeviceReporter {

    override fun suggestedFilename(): String = slugifyDeviceId(device.profile.name)

    override val description =
        "Reads everything this app can get off the instrument - categories, storage figures and " +
            "each area's measured allocation unit - and shares it as JSON. Nothing is written to " +
            "the instrument, but measuring reads every item, so it takes a while."

    /**
     * Everything the app can read off the instrument without changing anything on it, as JSON.
     * Every call is a query; the only side effect is the per-category SELECT_CATEGORY lock, which
     * each call releases. Slow: the storage calibration walks every item of every category at
     * two round trips each, so [progress] reports what it is doing. No probe is fatal - see
     * [Probes].
     */
    override suspend fun buildReport(progress: ((String) -> Unit)?): DeviceReportResult = deviceLock.withLock {
        val d = device
        val probes = Probes()

        progress?.invoke("Reading device info...")
        val commandTargets = probes.probe("deviceInfo", emptyMap()) { d.getProtocolVersions() }
        val capabilityHex = probes.probe("capabilityQuery", "(query failed)") {
            d.capabilityQueryPayload().toHex()
        }

        progress?.invoke("Reading categories...")
        val rootPayload = probes.probe("rootCategoryList", ByteArray(0)) { d.rootCategoryListPayload() }
        val categoryNames = probes.probe("rootCategoryListParse", emptyList()) {
            if (rootPayload.isEmpty()) emptyList() else d.parseRootCategoryList(rootPayload)
        }
        val childPayloads = categoryNames.mapIndexed { index, name ->
            name to probes.probe("categoryChild[$name]", null) { d.categoryChildPayload(index) }
        }
        val childHex = childPayloads.associate { (name, bytes) ->
            name to (bytes?.toHex() ?: "(query failed)")
        }
        val childLists = childPayloads.mapNotNull { (name, bytes) ->
            val parsed = bytes?.let {
                probes.probe("categoryChildParse[$name]", null) { NordDevice.parseCategoryChildren(it) }
            }
            parsed?.let { children ->
                name to children.map { CategoryChildReport(it.name, it.capacity) }
            }
        }.toMap()

        val bankLayout = probes.probe("bankLayout", null) { d.deriveBankLayout() }

        val areas = probes.probe("storage", emptyList()) {
            d.calibrateStorageUnits(
                progress = { name, _ -> progress?.invoke("Measuring '$name'...") },
                onFailure = { name, e -> probes.record("storage[$name]", e.message ?: e.toString()) },
            )
        }

        progress?.invoke("Building report...")
        // Each area's allocation unit as the instrument reports it in its root-list trailer.
        val reportedUnits = probes.probe("storageUnits", emptyMap<Int, Int>()) {
            d.parseRootCategories(rootPayload)
                .mapIndexedNotNull { i, c -> c.unitBytes?.let { unit -> i to unit } }
                .toMap()
        }

        val entry = d.profile.copy(
            id = slugifyDeviceId(d.profile.name),
            supportedFirmwareVersions = setOf(d.firmwareVersion),
            // From the Program category's own child list; the grouping of a bank's slots is not
            // announced, so maxGroup is 1 - see NordDevice.applyDerivedBankLayout.
            maxBankLetter = bankLayout?.maxBankLetter ?: d.profile.maxBankLetter,
            maxGroup = if (bankLayout != null) 1 else d.profile.maxGroup,
            slotsPerGroup = bankLayout?.slotsPerBank ?: d.profile.slotsPerGroup,
        )

        val report = NordReport(
            note = "Read-only report generated from the connected instrument. Nothing was " +
                "written; each category was locked only while it was read.",
            identity = ReportIdentity.of(identity()),
            catalogEntry = entry,
            probe = DeviceProbe(
                failures = probes.failures,
                firmwareVersion = d.formatFirmwareVersion(d.firmwareVersion),
                usbVendorId = String.format(Locale.ROOT, "0x%04X", d.vendorId),
                usbProductId = String.format(Locale.ROOT, "0x%04X", d.productId),
                profileName = d.profile.name,
                commandTargets = commandTargets.mapKeys { it.key.toString() },
                capabilityQueryHex = capabilityHex,
                rootCategories = categoryNames,
                rootCategoryListHex = rootPayload.toHex(),
                rootCategoryTrailerLen = NordDevice.detectRootCategoryTrailerLen(rootPayload),
                categoryChildren = childLists,
                categoryChildHex = childHex,
                derivedBankCount = bankLayout?.bankCount,
                derivedSlotsPerBank = bankLayout?.slotsPerBank,
                storageAreas = areas.map { area ->
                    StorageAreaReport(
                        names = area.names,
                        itemCounts = area.names.zip(area.itemCounts).toMap(),
                        free = area.space.free,
                        used = area.space.used,
                        reclaimable = area.space.reclaimable,
                        unitCode = area.space.unitCode,
                        countedInBytes = area.space.countedInBytes,
                        recordCount = area.recordCount,
                        totalBytes = area.totalBytes,
                        reportedUnitBytes = reportedUnits[area.indexes.first()],
                        fittedUnitBytes = area.unitBytes.takeIf { area.solved },
                        residual = area.residual,
                        reportedResidual = area.reportedResidual,
                        candidateRange = area.candidateRange?.let { "${it.first}-${it.second}" },
                        note = area.reason,
                    )
                },
            ),
        )

        DeviceReportResult(REPORT_JSON.encodeToString(report), probes.failures)
    }
}
