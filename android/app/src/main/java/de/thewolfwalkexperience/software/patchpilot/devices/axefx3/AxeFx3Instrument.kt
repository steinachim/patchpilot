// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.core.LinearAddressFormat
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
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import de.thewolfwalkexperience.software.patchpilot.core.UNTESTED_ADVISORY_CLOSING
import de.thewolfwalkexperience.software.patchpilot.core.indexWalk
import de.thewolfwalkexperience.software.patchpilot.core.queryOrNull
import de.thewolfwalkexperience.software.patchpilot.core.untestedFirmwareWarning
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AxeFx3Instrument"

/**
 * The Fractal Audio Axe-Fx III over class-compliant USB-MIDI SysEx: the Mark I as measured, and
 * the Mark II on the assumption that it differs only in its preset count (untested).
 * Browsing, selection and the raw preset transfer live here; [AxeFx3Editor] composes the edits.
 *
 * **Every write goes through the edit buffer.** A rename or a dump write changes the loaded
 * preset, and only a store makes it permanent, so writing preset N means loading N first, which
 * the user hears. [operation] remembers which preset was loaded and loads it again afterwards.
 *
 * **One lock for every sequence.** Each public method holds [lock] for its whole sequence, so a
 * listing's name query can never land between an edit's select and its store. The exchange's own
 * lock covers one request at a time, and listings run outside the app's operation mutex. Internal
 * helpers that send anything take no lock and may only be called while it is held.
 */
class AxeFx3Instrument(
    private val exchange: SysExExchange,
    private val config: AxeFx3Config,
    /** The editor's recorded delete; see `AxeFx3Family`. */
    internal val emptyPreset: AxeFx3DumpImage,
    /** The catalog entry's id and name; defaulted so the JVM tests can build this with no catalog. */
    private val descriptorId: String = "fractal_axefx3",
    private val catalogName: String = "Fractal Audio Axe-Fx III",
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : Instrument, PresetBrowser, PresetSelector, PresetTransfer {

    internal val device = AxeFx3Device(exchange)
    internal val lock = Mutex()

    /** Mark I until [connect] has asked; 1024 where the unit reports a Mark II (assumed, untested). */
    var presetCount: Int = AxeFx3SysEx.PRESET_COUNT_MARK_I
        private set

    private var format = LinearAddressFormat(presetCount, config.presetsPerBank)

    /** One run of preset numbers, split into virtual banks for the index rail; rebuilt by [connect]. */
    override var layout: SlotLayout = format.layout()
        private set

    private var firmware: String = UNKNOWN_FIRMWARE

    /** "Mark I" or "Mark II", the names Axe-Edit III shows; null until [connect]. */
    var model: String? = null
        private set

    /** The decoded `0x47` values, for the device report. */
    internal var capabilities: List<Int> = emptyList()
        private set

    /**
     * Whether the instrument answered "which preset is loaded" with a number at connect. Restoring
     * the loaded preset after an edit, and checking a selection, both depend on it.
     */
    internal var loadedQueryWorks: Boolean = false
        private set

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = descriptorId,
            family = FAMILY,
            name = model?.let { "$catalogName $it" } ?: catalogName,
            firmwareVersion = firmware,
            bus = Bus.MIDI,
            stableKey = "axefx3:$descriptorId",
        )

    override val rebuildOnResume: Boolean get() = device.rebuildOnResume

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val transfer: PresetTransfer get() = this
    override val editor: PresetEditor = AxeFx3Editor(this)

    /** An Axe-Fx III preset has no category and no favorite mark. */
    override val tagger: PresetTagger? = null

    override val report: DeviceReporter = AxeFx3Reporter(this, timeSource)

    /** Set by [connect] for an untested firmware or model; see [Instrument.advisory]. */
    override var advisory: String? = null
        private set

    override val notices: List<InstrumentNotice>
        get() {
            val afterwards = if (restoresLoadedPreset) {
                "and then loads the preset that was loaded before"
            } else {
                "and leaves it loaded"
            }
            return listOf(
                InstrumentNotice(
                    id = EDITS_LOAD_PRESETS_NOTICE,
                    title = "Edits load presets",
                    message = "Every change Patch Pilot makes on the Axe-Fx III (rename, copy, " +
                        "move, swap or delete) loads the preset it writes, $afterwards. Unsaved " +
                        "changes to the loaded preset are lost, so save them on the unit first.",
                ),
            )
        }

    private val restoresLoadedPreset: Boolean
        get() = config.restoreLoadedPresetAfterEdit && loadedQueryWorks

    /**
     * Firmware, then the model, which decides the layout, then whether "which preset is loaded"
     * is answered. None of them stops a session: a unit that does not say it is a Mark II is
     * treated as a Mark I, so it is never offered slots it may not have.
     */
    override suspend fun connect() {
        lock.withLock { connectLocked() }
    }

    private suspend fun connectLocked() {
        val firmwareRead = queryOrNull(TAG) { AxeFx3SysEx.decodeFirmware(device.firmwareReply())?.version }
        capabilities = queryOrNull(TAG) { AxeFx3SysEx.decodeCapabilities(device.capabilitiesReply()) }.orEmpty()
        // Either answer is enough to go on; neither means every later request would go unanswered
        // too, so there is no session to offer.
        if (firmwareRead == null && capabilities.isEmpty()) {
            throw InstrumentException.Timeout(
                "connecting",
                explanation = "The Axe-Fx III answered neither its firmware version nor its " +
                    "capabilities, so nothing can be read from it. Check that it is switched on and " +
                    "connected by USB, then retry.",
            )
        }
        firmware = firmwareRead ?: UNKNOWN_FIRMWARE
        presetCount = AxeFx3SysEx.presetCountOf(capabilities)
        model = if (presetCount == AxeFx3SysEx.PRESET_COUNT_MARK_II) "Mark II" else "Mark I"
        format = LinearAddressFormat(presetCount, config.presetsPerBank)
        layout = format.layout()
        loadedQueryWorks = loadedPresetOrNull() != null
        advisory = advisoryText()
        Log.i(
            TAG,
            "${identity.name}, firmware $firmware, $presetCount presets, loaded-preset query works: $loadedQueryWorks",
        )
    }

    /** Warns on a firmware or a model this app has not been tested against. */
    private fun advisoryText(): String? {
        val warnings = buildList {
            untestedFirmwareWarning(identity.name, firmware, config.supportedFirmwareVersions.sorted())?.let(::add)
            if (config.testedPresetCounts.isNotEmpty() && presetCount !in config.testedPresetCounts) {
                add(
                    "This app has not been tested with an Axe-Fx III ${model ?: ""} " +
                        "($presetCount presets). Presets above " +
                        "${config.testedPresetCounts.max() - 1} in particular have never been " +
                        "read or written by it.",
                )
            }
        }
        if (warnings.isEmpty()) return null
        return (warnings + UNTESTED_ADVISORY_CLOSING).joinToString(" ").also { Log.w(TAG, it) }
    }

    /** Releases the MIDI port - see [SysExExchange.close]. */
    override fun close() = exchange.close()

    // ---- PresetBrowser ----

    /**
     * One name query per preset: the public, plain-ASCII `0x0D`, about a millisecond each.
     * [scope] is ignored; every preset is writable, so only [PresetScope.USER] is declared.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> {
        val walked = layout
        return indexWalk(
            addresses = walked.allAddresses().asIterable(),
            total = walked.slotCount,
            batchSize = BATCH_SIZE,
            layout = walked,
        ) { address, displayId -> lock.withLock { readSlot(address, displayId) } }
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot =
        lock.withLock { readSlot(address, format.format(address)) }

    private suspend fun readSlot(address: SlotAddress, displayId: String): PresetSlot = PresetSlot(
        address = address,
        displayId = displayId,
        bankLabel = layout.banks[address.bank].label,
        name = displayName(device.name(numberOf(address))),
    )

    /** A name as a row shows it: null for an empty slot, [UNNAMED_PRESET] for a preset without one. */
    internal fun displayName(name: String): String? = when {
        name == AxeFx3SysEx.EMPTY_NAME -> null
        name.isBlank() -> UNNAMED_PRESET
        else -> name
    }

    // ---- PresetSelector ----

    /** Whether the last [select] was confirmed by the loaded-preset query; see [confirmationFor]. */
    private var selectionConfirmed = false

    /**
     * The editor channel's select, which is acknowledged. Where the instrument says which preset
     * is loaded, that is checked too.
     */
    override suspend fun select(address: SlotAddress): Unit = lock.withLock {
        val number = numberOf(address)
        selectionConfirmed = false
        device.select(number)
        if (!loadedQueryWorks) return@withLock
        var loaded = loadedPresetOrNull()
        // A late answer to an earlier name query has the same shape as this one, so a mismatch
        // is asked once more before it counts: the first ask has taken the stray reply.
        if (loaded != null && loaded != number) loaded = loadedPresetOrNull()
        if (loaded != null && loaded != number) {
            throw InstrumentException.ProtocolDesync(
                "Asked the Axe-Fx III to load ${displayIdOf(number)}, but it reports " +
                    "${displayIdOf(loaded)} as loaded.",
            )
        }
        selectionConfirmed = loaded == number
    }

    /**
     * CC 0 carries the bank, the Program Change the preset within it. Assumption: taken from
     * community reports and matching [numberOf]; not confirmed against the vendor's table. Not
     * used by [select], which goes over SysEx.
     */
    override fun midiSelection(address: SlotAddress): MidiSelection {
        numberOf(address)
        return MidiSelection(bankMsb = address.bank, bankLsb = null, program = address.slot)
    }

    /** "Selected" only where the instrument named the preset it loaded; the acknowledgement does not. */
    override fun confirmationFor(displayId: String): String =
        if (selectionConfirmed) super.confirmationFor(displayId) else "Asked the instrument to load $displayId."

    // ---- PresetTransfer ----

    /** One preset as the raw SysEx the instrument sends; see [AxeFx3DumpImage]. */
    override suspend fun read(address: SlotAddress): ByteArray =
        lock.withLock { device.readDump(numberOf(address)).raw }

    // ---- Shared by the editor and the reporter; see the class doc on locking ----

    /** The instrument's own preset number for [address], refusing one this unit does not have. */
    internal fun numberOf(address: SlotAddress): Int = format.numberOf(address)

    internal fun displayIdOf(number: Int): String = format.format(format.addressOf(number))

    /**
     * Runs one edit under [lock], loading the preset that was loaded before once it is done.
     *
     * @param follow where that preset is once the edit is done, so the same sound stays loaded:
     *   a move takes it to its destination, a swap to the other slot.
     */
    internal suspend fun <T> operation(follow: (Int) -> Int = { it }, block: suspend () -> T): T =
        lock.withLock {
            val loaded = if (restoresLoadedPreset) loadedPresetOrNull() else null
            val selectionsBefore = device.selections
            try {
                block()
            } finally {
                // Only where the edit loaded something: one refused before any write leaves the
                // instrument as it was.
                val target = loaded?.let(follow)
                if (target != null && device.selections != selectionsBefore && target != device.lastSelected) {
                    withContext(NonCancellable) { selectQuietly(target) }
                }
            }
        }

    /**
     * Loads [image] into [number] and stores it. Not cancellable once started: abandoned between
     * chunks, the loaded preset's edit buffer would be left half-written, and that is what the
     * rig would be playing. A failed transfer re-selects [number], which reloads its stored
     * preset, and stores nothing.
     */
    internal suspend fun writeUnit(number: Int, image: AxeFx3DumpImage) = withContext(NonCancellable) {
        device.select(number)
        try {
            device.writeEditBuffer(image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            selectQuietly(number)
            throw e
        }
        device.store(number)
    }

    /**
     * Loads [number], names it [name], stores it and reads the name back. A failure anywhere,
     * the read-back included, re-selects [number], as [writeUnit] does: the edit buffer is what
     * the rig plays, and it must not keep a name that was never stored.
     */
    internal suspend fun renameUnit(number: Int, name: String) = withContext(NonCancellable) {
        device.select(number)
        try {
            device.setName(name)
            device.store(number)
            val stored = device.name(number)
            if (stored != name) {
                throw InstrumentException.ProtocolDesync(
                    "${displayIdOf(number)} was saved, but the Axe-Fx III stored its name as " +
                        "'$stored' rather than '$name'. It may not accept every character.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            selectQuietly(number)
            throw e
        }
    }

    /**
     * [writeUnit], then a read-back that must hold the same preset - or, where the instrument
     * converted it on the way in, a stable one.
     *
     * A preset the instrument holds in an older form, a factory preset never saved since, comes
     * back converted from the first store: thousands of bytes differ (measured on a Mark I with
     * firmware 32.06, preset 0 written to 384). The converted form is what the instrument keeps:
     * stored once more, it reads back unchanged. So a difference is followed by one more write,
     * of the instrument's own version, which must read back the same; anything that still
     * changes is not a conversion and is an error. The end of the transfer is acknowledged only
     * for data whose checksum holds, so the first write itself cannot have been damaged.
     */
    internal suspend fun writeVerified(number: Int, image: AxeFx3DumpImage) {
        writeUnit(number, image)
        // Decoding both images is a few milliseconds of bit shuffling, cheap enough for any thread.
        val stored = device.readDump(number)
        val differences = image.differencesFrom(stored) ?: return
        writeUnit(number, stored)
        stored.differencesFrom(device.readDump(number))?.let { stillChanging ->
            throw InstrumentException.ProtocolDesync(
                "Wrote ${displayIdOf(number)}, but reading it back gave different data ($differences), " +
                    "and storing that again changed it further ($stillChanging). The preset may not " +
                    "have been stored.",
            )
        }
        Log.i(TAG, "${displayIdOf(number)} was converted by the instrument when stored: $differences")
    }

    /**
     * Empties [number] the way Axe-Edit III does, by writing [emptyPreset], which the instrument
     * takes as an erase. Its data is not stored, so only the name proves it.
     */
    internal suspend fun erase(number: Int) {
        writeUnit(number, emptyPreset)
        val name = device.name(number)
        if (name != AxeFx3SysEx.EMPTY_NAME) {
            throw InstrumentException.ProtocolDesync(
                "Asked the Axe-Fx III to empty ${displayIdOf(number)}, but it still reports " +
                    "'$name' there.",
            )
        }
    }

    internal suspend fun loadedPresetOrNull(): Int? = queryOrNull(TAG) { device.loadedPreset(presetCount) }

    /** A select whose failure is logged rather than thrown, for restores and clean-up. */
    private suspend fun selectQuietly(number: Int) {
        try {
            device.select(number)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load ${displayIdOf(number)} again", e)
        }
    }

    companion object {
        const val FAMILY = "axefx3"

        /** The id a hidden "edits load presets" notice is remembered by. */
        const val EDITS_LOAD_PRESETS_NOTICE = "axefx3.edits_load_presets"

        /** Name queries are fast; a batch of 32 keeps rows arriving without a recomposition per query. */
        private const val BATCH_SIZE = 32
    }
}
