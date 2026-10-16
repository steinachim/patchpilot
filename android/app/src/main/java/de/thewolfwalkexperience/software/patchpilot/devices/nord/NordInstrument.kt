// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.transport.UsbReadTimeoutException
import de.thewolfwalkexperience.software.patchpilot.transport.UsbTransferException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "NordInstrument"

/**
 * Presents a [NordDevice] as an [Instrument]: an adapter with no protocol logic, converting
 * between [SlotAddress] and the (bank, item) pair `NordDevice` speaks.
 */
class NordInstrument(
    private val device: NordDevice,
    /**
     * The catalog's family-level category master list, `id -> name` (see
     * [NordCatalog.programCategories]). Empty on the unknown-device path and in tests, which
     * gives an instrument without categories.
     */
    programCategories: Map<String, String> = emptyMap(),
    /** The catalog's [NordCatalog.contentVersionScales]; empty means every tag is scaled by 100. */
    private val contentVersionScales: Map<String, Int> = emptyMap(),
    private val bus: Bus = Bus.USB,
) : Instrument, PresetBrowser, PresetSelector, PresetEditor {

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
            format = device.addressFormat(),
        )

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

    override val report: DeviceReporter = NordReporter(device, deviceLock) { identity }

    /** Null: the item-data read/write sub-opcodes are not implemented (see `NordDevice.READ_BUFSIZE`). */
    override val transfer: PresetTransfer? = null

    override val rebuildOnResume: Boolean get() = device.rebuildOnResume

    override val advisory: String? get() = device.firmwareAdvisory

    /**
     * Every edit re-selects the programs it touched so the display shows the change, which on a
     * Grand loads them: the panel switches to the edited program, and unsaved changes to the one
     * loaded are lost (measured). No query says which program is loaded, so it cannot be put back.
     */
    override val notices: List<InstrumentNotice> = listOf(
        InstrumentNotice(
            id = EDITS_LOAD_PROGRAMS_NOTICE,
            title = "Edits load programs",
            message = "Every change Patch Pilot makes on the Nord (rename, category, copy, move, swap " +
                "or delete) loads the program it changes, so the display shows the result. Unsaved " +
                "changes to the program you are playing are lost, so store them on the instrument first.",
        ),
    )

    /**
     * The handshake, then, for a device opened on a guess ([NordProfile.unknown]), the bank
     * bounds it reports in place of the guessed ones - see [NordDevice.applyDerivedBankLayout].
     * Best-effort: a device with no `Program` category is what that path exists to survive, and
     * the guessed bounds still work, just generously.
     */
    override suspend fun connect() {
        exclusive("connect", "connecting") { device.connect() }
        if (device.profile.id != NordProfile.UNKNOWN_ID) return
        try {
            deviceLock.withLock { device.applyDerivedBankLayout() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't derive a bank layout for ${device.name}", e)
        }
    }

    override fun close() = device.close()

    // ---- PresetBrowser ----

    /**
     * One batch and done: the Nord lists its programs in a single walk. [scope] is ignored; this
     * family declares only [PresetScope.USER], since reading the factory content is not
     * implemented.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
        val items = exclusive("list presets", "listing presets") {
            device.collectItemNames(device.fetchCategoryItems(device.getProgramCategoryIndex()))
        }
        emit(IndexUpdate.Slots(items.map { it.toPresetSlot() }))
        emit(IndexUpdate.Complete)
    }

    /** Re-reads one slot by its address - see [NordDevice.readProgram]. */
    override suspend fun refresh(address: SlotAddress): PresetSlot =
        exclusive("re-read a preset", "re-reading a preset") { device.readProgram(address.bank, address.slot) }
            ?.toPresetSlot()
            ?: emptySlot(address)

    /** The program's content version, save date, data size and content id, from its item record. */
    override suspend fun details(address: SlotAddress): List<InfoRow> {
        val record = exclusive("read preset details", "reading preset details") {
            device.readProgramRecord(address.bank, address.slot)
        } ?: return emptyList()
        return NordItemInfo.rows(record, contentVersionScales, device.itemContentId(record))
    }

    private fun NordDevice.NamedItem.toPresetSlot(): PresetSlot {
        val address = SlotAddress(bank, item)
        val format = device.addressFormat()
        return PresetSlot(
            address = address,
            displayId = format.format(address),
            bankLabel = format.bankLabel(bank),
            name = name,
            // Empty for an id this model does not name, which its own display shows as `No Cat`.
            badges = listOfNotNull(categoryId?.let { categories?.nameOf(it) }),
        )
    }

    private fun emptySlot(address: SlotAddress) = PresetSlot(
        address = address,
        displayId = device.formatPresetId(address.bank, address.slot),
        bankLabel = device.addressFormat().bankLabel(address.bank),
        name = null,
    )

    // ---- PresetSelector ----

    override suspend fun select(address: SlotAddress) =
        exclusive("load a preset", "loading a preset") { device.selectPreset(address.bank, address.slot) }

    /**
     * The values a controller sends to load [address]'s Program, from the profile's
     * [NordProfile.midiProgramSelect]; null where the catalog has none or the result does not
     * fit 7 bits. Not what [select] sends, which is the vendor protocol.
     */
    override fun midiSelection(address: SlotAddress): MidiSelection? {
        val select = device.profile.midiProgramSelect ?: return null
        val perBank = device.maxGroup * device.slotsPerGroup
        val lsb = address.bank / select.banksPerLsb
        val program = address.bank % select.banksPerLsb * perBank + address.slot
        if (select.msb !in 0..127 || lsb !in 0..127 || program !in 0..127) return null
        return MidiSelection(bankMsb = select.msb, bankLsb = lsb, program = program)
    }

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

    /**
     * The name is checked outside [exclusive], whose failure mapping would read the refusal as an
     * unparseable reply. The slot is read first, as every family does: an unchanged name writes
     * nothing, and a slot emptied on the panel is reported as such rather than as a status code.
     */
    override suspend fun rename(address: SlotAddress, newName: String) {
        nameProblem(newName)?.let { throw IllegalArgumentException(it) }
        exclusive("rename a preset", "renaming a preset") {
            val current = device.readProgram(address.bank, address.slot)
                ?: throw InstrumentException.SlotStateChanged.empty(layout.format.format(address))
            if (current.name == newName) return@exclusive
            device.renamePreset(address.bank, address.slot, newName)
        }
    }

    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        if (from == to) return
        exclusive("move a preset", "moving a preset") {
            intoEmpty(to) { device.moveProgram(from.bank, from.slot, to.bank, to.slot) }
        }
    }

    /** Both slots must hold a preset; an empty one answers status 1, which does not say which. */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        if (a == b) return
        exclusive("swap two presets", "swapping two presets") { swapPrograms(a, b) }
    }

    private suspend fun swapPrograms(a: SlotAddress, b: SlotAddress) {
        try {
            device.swapPrograms(a.bank, a.slot, b.bank, b.slot)
        } catch (e: NordStatusException) {
            if (e.status != NordDevice.STATUS_NOT_FOUND) throw e
            throw InstrumentException.SlotStateChanged(
                "${layout.format.format(a)} or ${layout.format.format(b)} is empty, though the list " +
                    "shows a preset in both.",
                e,
            )
        }
    }

    /**
     * Sub-opcode 20/21: the instrument empties the slot itself. Frees no space until its own
     * reclaim runs. Status 1 (not found, measured on a Grand) is a slot emptied on the panel
     * since the list was read.
     */
    override suspend fun delete(address: SlotAddress) = exclusive("delete a preset", "deleting a preset") {
        try {
            device.deleteProgram(address.bank, address.slot)
        } catch (e: NordStatusException) {
            if (e.status != NordDevice.STATUS_NOT_FOUND) throw e
            throw InstrumentException.SlotStateChanged.empty(layout.format.format(address), e)
        }
    }

    /**
     * Sub-opcode 22/23: the instrument duplicates the record and names the copy itself. The same
     * slot twice is checked outside [exclusive], whose failure mapping would read the refusal as
     * an unparseable reply.
     */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        require(src != dst) { "A preset cannot be copied onto itself." }
        return exclusive("copy a preset", "copying a preset") {
            intoEmpty(dst) { device.copyProgram(src.bank, src.slot, dst.bank, dst.slot) }
        }
    }

    /**
     * Status 4 ("file exists") from [block] as [InstrumentException.SlotStateChanged]: the app
     * never offers an occupied destination, so [destination] was filled since the list was read.
     * Measured for a copy; *assumed* for a move, whose refusal code is unrecorded. Any other code
     * stays a plain refusal.
     */
    private suspend fun <T> intoEmpty(destination: SlotAddress, block: suspend () -> T): T = try {
        block()
    } catch (e: NordStatusException) {
        if (e.status != NordDevice.STATUS_FILE_EXISTS) throw e
        throw InstrumentException.SlotStateChanged.occupied(layout.format.format(destination), e)
    }

    /** [block] under [deviceLock], with its failures translated by [mapNordFailure]. */
    private suspend fun <T> exclusive(action: String, during: String, block: suspend () -> T): T =
        deviceLock.withLock { mapNordFailure(action, during, block) }

    companion object {
        const val FAMILY = "nord"

        /** The id a hidden "edits load programs" notice is remembered by. */
        const val EDITS_LOAD_PROGRAMS_NOTICE = "nord.edits_load_programs"
    }
}

/**
 * Translates a Nord failure into the [InstrumentException] the screens act on.
 *
 * The classification is by type, not by message:
 *
 *  - [NordStatusException] - the instrument answered and said no. Its code travels verbatim.
 *  - [UnsupportedProtocolVersionException] - answered clearly, in a protocol nobody has profiled.
 *  - [NordProtocolException] - answered with something this app cannot make sense of: a reply
 *    out of step with the request, an echo naming another slot, a walk that does not add up, a
 *    read-back that contradicts the write.
 *  - `IllegalArgumentException` - a reply this app cannot parse: a CRC mismatch, a short
 *    payload, an implausible field.
 *  - [UsbReadTimeoutException] - no reply within the read timeout: the instrument was silent,
 *    and a retry may well work.
 *  - [UsbTransferException] - the transport's own ("USB bulk write failed", a dead endpoint): the
 *    link is gone, which a reconnect can fix.
 *  - any other `IllegalStateException` - a state this code did not expect, which is a bug rather
 *    than anything the connection did.
 *
 * The operation is named twice, in the user's terms rather than "SET_NAME", because the sentences
 * need two forms: [action] completes "The instrument refused to …" ("rename a preset"), and
 * [during] completes "… did not answer while …" ("renaming a preset"). Shared with [NordTagger],
 * whose writes go through the same wire and deserve the same translation.
 */
internal suspend fun <T> mapNordFailure(action: String, during: String, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: NordStatusException) {
    throw InstrumentException.DeviceRejected(action, e.status, cause = e)
} catch (e: UnsupportedProtocolVersionException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unsupported protocol version", e)
} catch (e: NordProtocolException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unexpected reply while $during", e)
} catch (e: IllegalArgumentException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unreadable reply while $during", e)
} catch (e: UsbReadTimeoutException) {
    throw InstrumentException.Timeout(during, e)
} catch (e: UsbTransferException) {
    throw InstrumentException.TransportLost(e.message ?: "The connection failed while $during", e)
} catch (e: IllegalStateException) {
    throw InstrumentException.ProtocolDesync(e.message ?: "Unexpected state while $during", e)
}
