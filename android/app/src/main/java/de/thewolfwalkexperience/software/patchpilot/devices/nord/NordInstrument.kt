package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
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
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException

private const val TAG = "NordInstrument"

/**
 * The report writer's Json, built once.
 *
 * encodeDefaults: kotlinx.serialization's default config omits a property entirely when its value
 * equals the declared default, which would drop nullable fields from the catalog entry. The schema
 * allows them to be absent, but a report is more useful spelling out every field the recipient has
 * to fill in than silently leaving holes.
 */
private val REPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Presents a [NordDevice] as an [Instrument].
 *
 * **This is an adapter and nothing else.** There is no protocol logic here - every method below
 * either delegates to [device] or converts between [SlotAddress] and the (bank, item) pair
 * `NordDevice` already speaks. Generalization had to be additive, so it was.
 */
class NordInstrument(
    private val device: NordDevice,
    /**
     * The catalog's family-level category master list, `id -> name`.
     *
     * Passed in rather than read off [DeviceProfile], because it belongs to the whole Nord line
     * rather than to one instrument - see [DeviceCatalog.programCategories]. Defaulted to empty so
     * a caller that has no catalog to hand (the unknown-device path, and the tests) still gets a
     * working instrument, just without categories.
     */
    programCategories: Map<String, String> = emptyMap(),
    private val bus: Bus = Bus.USB,
) : Instrument, PresetBrowser, PresetSelector, PresetEditor, DeviceReporter {

    /**
     * This model's categories, resolved once.
     *
     * Held rather than re-derived per row: [toPresetSlot] needs it for every one of them, and
     * resolving involves a sort.
     */
    private val categories: NordCategories? = NordCategories.resolve(device.profile, programCategories)

    /**
     * Rebuilt from [NordDevice.profile] on every read rather than captured once, because
     * `applyDerivedBankLayout()` replaces an unknown device's guessed bounds with the
     * instrument's own during [connect] - a layout captured in the constructor would be the guess
     * forever.
     */
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
            // No serial number is available over this protocol, so two identical models on one
            // phone would share a key. Acceptable: nothing keyed on it is destructive, and the
            // alternative (a USB device path) changes on every replug, which is worse.
            stableKey = "nord:${device.vendorId}:${device.productId}:${device.profile.id}",
        )

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val editor: PresetEditor get() = this
    /**
     * Categories, where the catalog says which ones this model offers.
     *
     * Null when [NordCategories.resolve] declines - an unrecognised device, or a family catalog
     * with no master name list - rather than a facet offering an empty list of categories and
     * refusing every write. Same rule the Motif XS applies to a catalog with no encoding.
     */
    override val tagger: PresetTagger? = categories?.let { NordTagger(device, it) }

    override val report: DeviceReporter get() = this

    /** Nothing about a Nord needs the user to fill it in: everything is read from the instrument
     * or fixed by the protocol. */

    /**
     * Null until the item data path is ported (see `NordDevice.READ_BUFSIZE`'s
     * own KDoc, which says what has to change first). The Pro-800 implements this facet on day
     * one; this asymmetry is exactly what nullable facets are for.
     */
    override val transfer: PresetTransfer? = null

    override val rebuildOnResume: Boolean get() = device.rebuildOnResume

    override val advisory: String? get() = device.firmwareAdvisory

    override suspend fun connect() = mapNordFailure("connecting") { device.connect() }

    override fun close() = device.close()

    /**
     * Replaces an unrecognized device's guessed bank bounds with the ones it reports for its own
     * `Program` category, which is what makes [layout] correct for a device with no catalog entry.
     * A catalog device keeps its configured bounds untouched - see
     * [NordDevice.applyDerivedBankLayout] for why the grouping cannot be derived and what it does
     * instead.
     */
    suspend fun deriveBankLayout() {
        device.applyDerivedBankLayout()
    }

    // ---- PresetBrowser ----

    /**
     * One batch and done. The Nord lists its programs device-side in a single walk, so there is
     * no meaningful progress to report and nothing to gain by chunking - the streaming shape
     * exists for the Pro-800, which needs 400 round trips to answer the same question.
     *
     * [scope] is ignored: this family declares only [PresetScope.USER], so it is the only value
     * that can arrive here. The instrument does have factory content, but reading it is not
     * implemented, and declaring a scope the browser cannot fill would put a tab on the screen
     * that leads nowhere.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
        val items = mapNordFailure("listing presets") {
            device.collectItemNames(device.fetchCategoryItems(device.getProgramCategoryIndex()))
        }
        emit(IndexUpdate.Slots(items.map { it.toPresetSlot() }))
        emit(IndexUpdate.Complete)
    }

    /**
     * Re-reads one slot by walking the program list and picking it out, because the protocol's
     * per-item fetch needs a category selection this adapter does not hold open. Cheap enough on
     * a Nord (one walk), and only called after an edit.
     */
    override suspend fun refresh(address: SlotAddress): PresetSlot {
        val items = mapNordFailure("re-reading a preset") {
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
            // Asked of the formatter, not recovered from the id with `substringBefore(':')`.
            // That shortcut is correct for a Nord's `A:1:1` and silently wrong for a flat `A00`,
            // which is the whole reason [PresetSlot.bankLabel] travels on the row - see
            // [de.thewolfwalkexperience.software.patchpilot.core.AddressFormat]. It was taken out
            // of ProgramsScreen and left standing here, ten lines above [emptySlot] doing it right.
            bankLabel = addressFormat().bankLabel(parsed.bank),
            name = name,
            // Free: the tag is in the item record this row was already built from. Empty for an
            // id this model does not name, which its own display shows as `No Cat`.
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
        mapNordFailure("load a preset") { device.selectPreset(address.bank, address.slot) }

    /** A fact, not a hope: sub-opcode 47/48 echoes the address back and `selectPreset` checks it. */
    // ---- PresetEditor ----

    override val supported = setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    /** All five are single device commands here, with the instrument's own atomicity. */
    override fun isEmulated(op: EditOp) = false

    /**
     * What the instrument will actually keep of a new name.
     *
     * **Measured, not assumed**: an oversized `SET_NAME` is accepted with
     * status 0 and the instrument stores exactly its display width, silently discarding the rest.
     * So the failure this prevents is not a refusal or a corruption - it is a rename that appears
     * to work and quietly is not what was typed. Capping the input is what makes the limit visible
     * before the user commits to it.
     *
     * Read from `devices/nord_devices.json` per device, and from its **own** field rather
     * than borrowing the display width - they are separate limits that happen to coincide on
     * every instrument measured so far.
     */
    override val maxNameLength: Int get() = device.profile.maxProgramNameLen

    override suspend fun rename(address: SlotAddress, newName: String) =
        mapNordFailure("rename a preset") { device.renamePreset(address.bank, address.slot, newName) }

    override suspend fun move(from: SlotAddress, to: SlotAddress) =
        mapNordFailure("move a preset") { device.moveProgram(from.bank, from.slot, to.bank, to.slot) }

    override suspend fun swap(a: SlotAddress, b: SlotAddress) =
        mapNordFailure("swap two presets") { device.swapPrograms(a.bank, a.slot, b.bank, b.slot) }

    /**
     * Sub-opcode 20/21 - the instrument empties the slot itself.
     *
     * Unlike the Pro-800's and the Motif XS's, this is not a composed erase: nothing is read
     * first and no blank payload is written, so there is no half-completed state to recover from.
     *
     * Note it **frees no space** until the instrument's own reclaim runs, which is
     * also what keeps it undoable until then - so a user who deletes to make room for a write may
     * still be told there is none.
     */
    override suspend fun delete(address: SlotAddress) =
        mapNordFailure("delete a preset") { device.deleteProgram(address.bank, address.slot) }

    /** Sub-opcode 22/23 - the instrument duplicates the
     * record and names the copy itself, which is why the name it chose comes back. */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String =
        mapNordFailure("copy a preset") { device.copyProgram(src.bank, src.slot, dst.bank, dst.slot) }

    // ---- DeviceReporter ----

    override fun suggestedFilename(): String = slugifyDeviceId(device.profile.name)

    override val description =
        "Reads everything this app can get off the instrument - categories, storage figures and " +
            "each area's measured allocation unit - and shares it as JSON. Nothing is written to " +
            "the instrument, but measuring reads every item, so it takes a while."

    /**
     * Everything the app can read off the connected instrument without changing anything on it,
     * as JSON. Moved here verbatim from `InstrumentViewModel`, which is where it lived while
     * there was only one family; the report's *shape* is Nord-specific (protocol version table, root
     * categories, storage areas), so it belongs to the Nord adapter and not to a shared screen.
     *
     * **Reads only.** Every call below is a query. The one side effect is that per-category
     * SELECT_CATEGORY briefly locks the instrument into status-message mode,
     * which each call releases again; nothing is created, written, deleted, renamed, moved or
     * reclaimed. It is *slow*, though: the storage calibration walks every item of every category
     * at two round trips each, so [progress] reports what it is doing.
     *
     * **No probe here is allowed to be fatal.** A report describes an instrument nobody has
     * profiled, which is precisely where an advanced query may be unsupported, time out, or answer
     * something this app can't parse - so every one of them runs through [probe], which records
     * the failure and carries on with a fallback. A report missing its storage figures but
     * carrying the categories, the child lists and the firmware version is worth far more to
     * whoever receives it than no report at all, and the failures are themselves a finding.
     */
    override suspend fun buildReport(progress: ((String) -> Unit)?): String {
        val d = device
        val failures = LinkedHashMap<String, String>()

        progress?.invoke("Reading device info...")
        val commandTargets = probe(failures, "deviceInfo", emptyMap()) { d.getProtocolVersions() }
        val capabilityHex = probe(failures, "capabilityQuery", "(query failed)") {
            d.capabilityQueryPayload().toHex()
        }

        progress?.invoke("Reading categories...")
        val rootPayload = probe(failures, "rootCategoryList", ByteArray(0)) { d.rootCategoryListPayload() }
        val categoryNames = probe(failures, "rootCategoryListParse", emptyList()) {
            if (rootPayload.isEmpty()) emptyList() else d.parseRootCategoryList(rootPayload)
        }
        val childPayloads = categoryNames.mapIndexed { index, name ->
            name to probe(failures, "categoryChild[$name]", null) { d.categoryChildPayload(index) }
        }
        val childHex = childPayloads.associate { (name, bytes) ->
            name to (bytes?.toHex() ?: "(query failed)")
        }
        val childLists = childPayloads.mapNotNull { (name, bytes) ->
            val parsed = bytes?.let {
                probe(failures, "categoryChildParse[$name]", null) { NordDevice.parseCategoryChildren(it) }
            }
            parsed?.let { children ->
                name to children.map { CategoryChildReport(it.name, it.capacity) }
            }
        }.toMap()

        val bankLayout = probe(failures, "bankLayout", null) { d.deriveBankLayout() }

        val areas = probe(failures, "storage", emptyList()) {
            d.calibrateStorageUnits(
                progress = { name, _ -> progress?.invoke("Measuring '$name'...") },
                onFailure = { name, e -> failures["storage[$name]"] = e.message ?: e.toString() },
            )
        }

        progress?.invoke("Building report...")
        // Each area's allocation unit as the instrument itself reports it - the first word of
        // that category's root-list trailer. Nothing derives or configures
        // this any more; the fitted value beside it in the report is a cross-check on it.
        val reportedUnits = probe(failures, "storageUnits", emptyMap<Int, Int>()) {
            d.parseRootCategories(rootPayload)
                .mapIndexedNotNull { i, c -> c.unitBytes?.let { unit -> i to unit } }
                .toMap()
        }

        val entry = d.profile.copy(
            id = slugifyDeviceId(d.profile.name),
            supportedFirmwareVersions = setOf(d.firmwareVersion),
            // Derived from the Program category's own child list rather than copied from the
            // profile, since a report exists to describe an instrument nobody has a profile for
            // yet. maxGroup is 1 and slotsPerGroup the whole bank because the grouping of a
            // bank's slots is the one part of this the instrument does not announce - see
            // NordDevice.applyDerivedBankLayout.
            maxBankLetter = bankLayout?.maxBankLetter ?: d.profile.maxBankLetter,
            maxGroup = if (bankLayout != null) 1 else d.profile.maxGroup,
            slotsPerGroup = bankLayout?.slotsPerBank ?: d.profile.slotsPerGroup,
            // Storage units are deliberately absent: the instrument states each area's own in
            // its root category list, so there is nothing for a catalog
            // entry to carry. The reported figure is in the storageAreas section below,
            // alongside what this instrument's records make of it.
        )

        val report = DeviceReport(
            catalogEntry = entry,
            probe = DeviceProbe(
                failures = failures,
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

        return REPORT_JSON.encodeToString(report)
    }

    /**
     * Runs one probe for [buildReport], recording a failure under [what] and carrying on with
     * [fallback] instead of losing the whole report.
     *
     * Not `runCatching`, which swallows [CancellationException] and would leave a cancelled report
     * running to completion.
     */
    private suspend fun <T> probe(
        failures: MutableMap<String, String>,
        what: String,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Device report: '$what' failed", e)
        failures[what] = e.message ?: e.toString()
        fallback
    }

    companion object {
        const val FAMILY = "nord"
    }
}

/**
 * Turns a display name like "USB device (0x1234:0x5678)" into a devices/nord_devices.json-style
 * machine id - runs of anything but a-z0-9 collapse to one underscore, since the schema's `id`
 * pattern (`^[a-z][a-z0-9_]*$`) allows nothing else. A leading digit (or a name that collapses to
 * nothing at all) gets an underscore-joined "device" prefix, since that pattern also requires
 * starting with a letter.
 */

/**
 * Translates a Nord failure into the [InstrumentException] the screens act on.
 *
 * **The Nord family was the last one throwing raw `IllegalStateException`s.** Pro-800 and Motif XS
 * adopted [InstrumentException] when they were written; this family predates it, so the sealed
 * hierarchy whose own doc says the UI must not match on message strings had exactly one family it
 * did not cover - the one most users have.
 *
 * The classification is by type, not by message:
 *
 *  - [NordStatusException] - the instrument answered and said no. Its code travels verbatim.
 *  - `IllegalArgumentException` - a reply this app cannot parse: a CRC
 *    mismatch, a short payload, a cursor that ran off the end.
 *  - [UnsupportedProtocolVersionException] - answered clearly, in a protocol nobody has profiled.
 *  - anything else, including the transport's own `IllegalStateException`s ("USB bulk write
 *    failed") - the link is gone, which is the one case a retry can actually fix.
 *
 * [what] is the operation in the user's terms, so [InstrumentException.NotSupported]'s and
 * [InstrumentException.DeviceRejected]'s sentences read correctly: "rename presets", not
 * "SET_NAME".
 */
/**
 * Plain-language meaning for the status codes this protocol's replies are known to use.
 *
 * Null for anything else, which leaves the generic "refused (status N)" - honest about the fact
 * that the app has the instrument's answer and cannot interpret it, rather than inventing a
 * reason. Only one code has a documented meaning; see [NordDevice.STATUS_FILE_EXISTS].
 *
 * **The wording names a contradiction, because that is the only way this can be seen.** Neither
 * copy nor move offers an occupied destination: the picker accepts empty rows only, and a drag
 * resolves move-vs-swap from the listing. So reaching status 4 means the listing and the
 * instrument disagree - a preset stored from the front panel since the last read - and the user
 * is looking at a row the app is showing them as empty. Saying only "that slot already holds a
 * preset" would read as the app contradicting itself; saying what to do about it does not.
 */
private fun nordStatusExplanation(what: String, status: Int): String? = when (status) {
    NordDevice.STATUS_FILE_EXISTS ->
        "Couldn't $what: the instrument says that slot already holds one, though the listing " +
            "shows it as empty. Pull down to re-read the instrument."
    else -> null
}

private suspend fun <T> mapNordFailure(what: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: NordStatusException) {
    throw InstrumentException.DeviceRejected(what, e.status, nordStatusExplanation(what, e.status))
} catch (e: UnsupportedProtocolVersionException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unsupported protocol version", e)
} catch (e: IllegalArgumentException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unreadable reply while $what", e)
} catch (e: IllegalStateException) {
    throw InstrumentException.TransportLost(e.message ?: "The connection failed while $what", e)
}
