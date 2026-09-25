// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.GroupedBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.slugifyDeviceId
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException

private const val TAG = "NordInstrument"

/** The report writer's Json. encodeDefaults, so a catalog entry spells out every field the recipient has to fill in. */
private val REPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Presents a [NordDevice] as an [Instrument]: an adapter with no protocol logic, converting
 * between [SlotAddress] and the (bank, item) pair `NordDevice` speaks.
 */
class NordInstrument(
    private val device: NordDevice,
    /**
     * The catalog's family-level category master list, `id -> name` (see
     * [DeviceCatalog.programCategories]). Empty on the unknown-device path and in tests, which
     * gives an instrument without categories.
     */
    programCategories: Map<String, String> = emptyMap(),
    private val bus: Bus = Bus.USB,
) : Instrument, PresetBrowser, PresetSelector, PresetEditor, DeviceReporter {

    /** This model's categories, resolved once; [toPresetSlot] needs them for every row. */
    private val categories: NordCategories? = NordCategories.resolve(device.profile, programCategories)

    /**
     * Serialises every operation against [device]: the protocol allows one outstanding request,
     * every operation is a sequence of them inside a category lock, and the ViewModel launches
     * listings and edits independently. Shared with [NordTagger].
     */
    private val deviceLock = Mutex()

    /** Rebuilt from [NordDevice.profile] on every read: `applyDerivedBankLayout()` replaces an unknown device's guessed bounds after construction. */
    override val layout: SlotLayout
        get() = SlotLayout.uniform(
            bankCount = device.maxBankLetter - 'A' + 1,
            slotsPerBank = device.maxGroup * device.slotsPerGroup,
            format = addressFormat(),
        )

    private fun addressFormat(): AddressFormat =
        GroupedBankAddressFormat(groupsPerBank = device.maxGroup, slotsPerGroup = device.slotsPerGroup)

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = device.profile.id,
            family = FAMILY,
            name = device.name,
            firmwareVersion = device.formatFirmwareVersion(device.firmwareVersion),
            bus = bus,
            // No serial number is available over this protocol, so this names the model rather
            // than the unit; the listing cache adds the session's USB device path to tell two
            // units apart (see CacheKey.physicalDevice).
            stableKey = "nord:${device.vendorId}:${device.productId}:${device.profile.id}",
        )

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val editor: PresetEditor get() = this
    /** Categories, where the catalog says which ones this model offers; null where [NordCategories.resolve] declines. */
    override val tagger: PresetTagger? = categories?.let { NordTagger(device, it, deviceLock) }

    override val report: DeviceReporter get() = this

    /** Null: the item-data read/write sub-opcodes are not implemented (see `NordDevice.READ_BUFSIZE`). */
    override val transfer: PresetTransfer? = null

    override val rebuildOnResume: Boolean get() = device.rebuildOnResume

    override val advisory: String? get() = device.firmwareAdvisory

    override suspend fun connect() = exclusive("connecting") { device.connect() }

    override fun close() = device.close()

    /** Replaces an unrecognized device's guessed bank bounds with the ones it reports - see [NordDevice.applyDerivedBankLayout]. */
    suspend fun deriveBankLayout() {
        deviceLock.withLock { device.applyDerivedBankLayout() }
    }

    // ---- PresetBrowser ----

    /**
     * One batch and done: the Nord lists its programs in a single walk. [scope] is ignored; this
     * family declares only [PresetScope.USER], since reading the factory content is not
     * implemented.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
        val items = exclusive("listing presets") {
            device.collectItemNames(device.fetchCategoryItems(device.getProgramCategoryIndex()))
        }
        emit(IndexUpdate.Slots(items.map { it.toPresetSlot() }))
        emit(IndexUpdate.Complete)
    }

    /**
     * Re-reads one slot by walking the program list, because the per-item fetch needs a category
     * selection this adapter does not hold open. One walk, only after an edit.
     */
    override suspend fun refresh(address: SlotAddress): PresetSlot {
        val items = exclusive("re-reading a preset") {
            device.collectItemNames(device.fetchCategoryItems(device.getProgramCategoryIndex()))
        }
        val displayId = device.formatPresetId(address.bank, address.slot)
        return items.firstOrNull { it.presetId == displayId }?.toPresetSlot()
            ?: emptySlot(address)
    }

    private fun NordDevice.NamedItem.toPresetSlot(): PresetSlot {
        val parsed = device.parsePresetId(presetId)
        return PresetSlot(
            address = SlotAddress(parsed.bank, parsed.item),
            displayId = presetId,
            bankLabel = addressFormat().bankLabel(parsed.bank),
            name = name,
            // Empty for an id this model does not name, which its own display shows as `No Cat`.
            badges = listOfNotNull(categoryId?.let { categories?.nameOf(it) }),
        )
    }

    private fun emptySlot(address: SlotAddress) = PresetSlot(
        address = address,
        displayId = device.formatPresetId(address.bank, address.slot),
        bankLabel = addressFormat().bankLabel(address.bank),
        name = null,
    )

    // ---- PresetSelector ----

    override suspend fun select(address: SlotAddress) =
        exclusive("load a preset") { device.selectPreset(address.bank, address.slot) }


    // ---- PresetEditor ----

    override val supported = setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    /** All five are single device commands here, with the instrument's own atomicity. */
    override fun isEmulated(op: EditOp) = false

    /**
     * What the instrument keeps of a new name (measured: an oversized `SET_NAME` is accepted
     * with status 0 and silently truncated). From the catalog's own field rather than the
     * display width; the two coincide on every instrument measured.
     */
    override val maxNameLength: Int get() = device.profile.maxProgramNameLen

    override suspend fun rename(address: SlotAddress, newName: String) =
        exclusive("rename a preset") { device.renamePreset(address.bank, address.slot, newName) }

    override suspend fun move(from: SlotAddress, to: SlotAddress) =
        exclusive("move a preset") { device.moveProgram(from.bank, from.slot, to.bank, to.slot) }

    override suspend fun swap(a: SlotAddress, b: SlotAddress) =
        exclusive("swap two presets") { device.swapPrograms(a.bank, a.slot, b.bank, b.slot) }

    /** Sub-opcode 20/21: the instrument empties the slot itself. Frees no space until its own reclaim runs. */
    override suspend fun delete(address: SlotAddress) =
        exclusive("delete a preset") { device.deleteProgram(address.bank, address.slot) }

    /** Sub-opcode 22/23: the instrument duplicates the record and names the copy itself. */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String =
        exclusive("copy a preset") { device.copyProgram(src.bank, src.slot, dst.bank, dst.slot) }

    // ---- DeviceReporter ----

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

        val report = DeviceReport(
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

    /** [block] under [deviceLock], with its failures translated by [mapNordFailure]. */
    private suspend fun <T> exclusive(what: String, block: suspend () -> T): T =
        deviceLock.withLock { mapNordFailure(what, block) }

    companion object {
        const val FAMILY = "nord"
    }
}

/**
 * Plain-language meaning for the one status code the protocol names,
 * [NordDevice.STATUS_FILE_EXISTS]; null for the rest, which keep the generic "refused (status
 * N)". The app never offers an occupied destination, so status 4 means the listing and the
 * instrument disagree - a preset stored from the front panel since the last read - and the
 * wording says what to do about it.
 */
private fun nordStatusExplanation(what: String, status: Int): String? = when (status) {
    NordDevice.STATUS_FILE_EXISTS ->
        "Couldn't $what: the instrument says that slot already holds one, though the listing " +
            "shows it as empty. Refresh the listing."
    else -> null
}

/**
 * Translates a Nord failure into the [InstrumentException] the screens act on.
 *
 * The classification is by type, not by message:
 *
 *  - [NordStatusException] - the instrument answered and said no. Its code travels verbatim.
 *  - [UnsupportedProtocolVersionException] - answered clearly, in a protocol nobody has profiled.
 *  - [NordProtocolException] - answered with something this app cannot make sense of: a reply
 *    out of step with the request, a walk that does not add up, a read-back that contradicts
 *    the write.
 *  - `IllegalArgumentException` - a reply this app cannot parse: a CRC mismatch, a short
 *    payload, an implausible field.
 *  - any other `IllegalStateException` - the transport's own ("USB bulk write failed", a dead
 *    endpoint): the link is gone, which is the one case a retry can actually fix.
 *
 * [what] is the operation in the user's terms, so [InstrumentException.NotSupported]'s and
 * [InstrumentException.DeviceRejected]'s sentences read correctly: "rename presets", not
 * "SET_NAME". Shared with [NordTagger], whose writes go through the same wire and deserve the
 * same translation.
 */
internal suspend fun <T> mapNordFailure(what: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: NordStatusException) {
    throw InstrumentException.DeviceRejected(what, e.status, nordStatusExplanation(what, e.status))
} catch (e: UnsupportedProtocolVersionException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unsupported protocol version", e)
} catch (e: NordProtocolException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unexpected reply while $what", e)
} catch (e: IllegalArgumentException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unreadable reply while $what", e)
} catch (e: IllegalStateException) {
    throw InstrumentException.TransportLost(e.message ?: "The connection failed while $what", e)
}
