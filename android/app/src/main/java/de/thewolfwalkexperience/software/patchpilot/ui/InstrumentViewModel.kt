// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentRegistry
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.core.OccupiedSlotReason
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.RegressionTester
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.stemFor
import de.thewolfwalkexperience.software.patchpilot.discovery.MidiDiscovery
import de.thewolfwalkexperience.software.patchpilot.discovery.UsbHostDiscovery
import de.thewolfwalkexperience.software.patchpilot.usb.AndroidUsbDevice
import de.thewolfwalkexperience.software.patchpilot.usb.AndroidUsbHost
import de.thewolfwalkexperience.software.patchpilot.usb.UsbConnectionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app's one session, for its lifetime: [connection] holds the connected [Instrument],
 * [listings] its preset listings, and this class the operations the screens drive on top of
 * them. Nothing here knows what family is connected: every action goes through a facet, and a
 * null facet is an operation the screens do not offer, so adding a family adds no `when`.
 */
class InstrumentViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application), ProgramsOperations {

    /**
     * Every stored setting, owned here so a write outlives the screen that made it. Settings and
     * `MainActivity` read it directly; a scan reads it fresh, so a change takes effect on the next
     * connect attempt.
     */
    val preferences = AppPreferences(application, viewModelScope)

    private val strings = StringResolver { id, args -> getApplication<Application>().getString(id, *args) }

    internal val connection: ConnectionCoordinator = run {
        val usb = UsbConnectionManager(application)
        ConnectionCoordinator(
            scope = viewModelScope,
            usb = AndroidUsbHost(usb),
            // Priority order: USB host first, since a Nord's MIDI interface cannot manage presets.
            discoveries = listOf(UsbHostDiscovery(usb), MidiDiscovery(application)),
            catalog = { InstrumentRegistry.allDescriptors(application) },
            create = { descriptor, transport -> InstrumentRegistry.create(application, descriptor, transport) },
            createForUnknown = { family, transport, device ->
                InstrumentRegistry.createForUnknown(family, transport, device.label, device.vendorId, device.productId)
            },
            autoConnect = { preferences.autoConnectToFirstFound.first() },
            strings = strings,
            onSessionStarted = { listings.start() },
            onSessionEnding = { listings.cancel() },
            onDeviceLost = { listings.invalidateUserCache() },
        )
    }

    internal val listings = PresetListings(
        scope = viewModelScope,
        instrument = { connection.instrument },
        deviceHandle = { connection.deviceHandle },
        strings = strings,
    )

    val state: StateFlow<ConnectionState> get() = connection.state

    /** What the session offers, for the screens - see [SessionCapabilities]. */
    val capabilities: StateFlow<SessionCapabilities> = connection.state
        .map { SessionCapabilities.of(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionCapabilities())

    /** Notice ids already shown since the app started - see [pendingNotice]. */
    private val noticesShownThisRun = mutableSetOf<String>()

    private val _pendingNotice = MutableStateFlow<InstrumentNotice?>(null)

    /** The [Instrument.notices] entry the preset screen should show now, or null. */
    val pendingNotice: StateFlow<InstrumentNotice?> = _pendingNotice.asStateFlow()

    /** The device report, held here so a minutes-long read survives the screen being torn down - see [DeviceReportRunner]. */
    internal val deviceReportRunner = DeviceReportRunner(
        scope = viewModelScope,
        savedState = savedStateHandle,
        build = { progress -> buildDeviceReport(progress) },
        identity = { suggestedReportFilename() to reportDescription },
    )

    /** The debug menu's regression run, held here so it survives the debug screen being torn down - see [RegressionRunner]. */
    internal val regressionRunner = RegressionRunner(
        scope = viewModelScope,
        savedState = savedStateHandle,
        run = ::runRegressionTest,
        filenameStem = { filenameStem },
    )

    init {
        connection.start()
        // Offered once the session is usable, so a notice never sits on top of the advisory gate.
        viewModelScope.launch {
            state.collect { s ->
                if (s is ConnectionState.Connected) offerNotice(s.instrument) else _pendingNotice.value = null
            }
        }
    }

    private suspend fun offerNotice(instrument: Instrument) {
        // An unreadable store reads as nothing hidden, so a notice shows once more rather than never.
        val hidden = preferences.hiddenNotices.first()
        _pendingNotice.value = pendingNotice(instrument.notices, hidden, noticesShownThisRun)
    }

    /** The user closed [notice]; [hideForever] is the dialog's "Don't show this again". */
    fun dismissNotice(notice: InstrumentNotice, hideForever: Boolean) {
        noticesShownThisRun += notice.id
        _pendingNotice.value = null
        viewModelScope.launch {
            // Stored before the next notice is chosen, which reads the hidden set back.
            if (hideForever) preferences.hideNotice(notice.id).join()
            (state.value as? ConnectionState.Connected)?.let { offerNotice(it.instrument) }
        }
    }

    /** A USB device was plugged in, delivered by `MainActivity` - see [ConnectionCoordinator.onUsbDeviceAttached]. */
    fun onUsbDeviceAttached(device: UsbDevice) = connection.onUsbDeviceAttached(AndroidUsbDevice(device))

    /**
     * Stops a running scan when the app backgrounds, called from `MainActivity.onPause` (not from
     * a rotation, which the activity filters out). A no-op where the session will not be rebuilt
     * on the way back; where it will, the resume discards the scan anyway, and on a Motif XS its
     * display would sit on "dump in progress" for up to 93 s with the phone locked.
     */
    fun cancelScanOnBackground() {
        if (connection.shouldRebuildOnResume) listings.cancel()
    }

    /**
     * Runs an edit on [viewModelScope] rather than on the caller's composition scope, so
     * navigating away cannot abandon it mid-write: a `rememberCoroutineScope()` dies with the
     * screen, and a Motif XS left mid-sequence sits on "receiving midi bulk data" until it is
     * completed or power-cycled. `SysExExchange.exchangeAfterAll` is the other half of that
     * guarantee.
     */
    override fun launchEdit(block: suspend () -> Unit): Job = viewModelScope.launch { block() }

    /** Releases the connection when the ViewModel goes away with the app - see [ConnectionCoordinator.release]. */
    override fun onCleared() = connection.release()

    // ---- The listing, for ProgramsController ----

    override val scope: StateFlow<PresetScope> get() = listings.browseScope

    override fun setScope(scope: PresetScope) = listings.setScope(scope)

    override fun reloadIndex(): Int? = listings.reload()

    // ---- Actions, each routed through a facet ----

    override suspend fun selectProgram(slot: PresetSlot): String = connection.exclusive { instrument ->
        val selector = requireFacet(instrument.selector, "load a preset")
        selector.select(slot.address)
        selector.confirmationFor(slot.displayId)
    }

    /**
     * Relocates a preset, picking the operation that matches the destination: an occupied target
     * is a swap, an empty one a move. An instrument with native operations rejects either call at
     * the wrong destination, so [targetIsEmpty] comes from the caller's own view of the listing.
     */
    override suspend fun moveProgram(
        source: PresetSlot,
        target: PresetSlot,
        targetIsEmpty: Boolean,
    ): String = connection.exclusive { instrument ->
        val editor = requireFacet(instrument.editor, "rearrange presets")
        // Both ends either way: a move empties the source as surely as it fills the destination.
        if (targetIsEmpty) {
            editor.move(source.address, target.address)
            listings.refreshEdited(source.address, target.address)
            str(R.string.result_moved, source.displayId, target.displayId)
        } else {
            editor.swap(source.address, target.address)
            listings.refreshEdited(source.address, target.address)
            str(R.string.result_swapped, source.displayId, target.displayId)
        }
    }

    override suspend fun renameProgram(slot: PresetSlot, newName: String): String = connection.exclusive { instrument ->
        requireFacet(instrument.editor, "rename presets").rename(slot.address, newName)
        listings.refreshEdited(slot.address)
        str(R.string.result_renamed, slot.displayId, newName)
    }

    /** What [slot] is filed under and whether it is a favorite, for the two tag dialogs. */
    override suspend fun presetTags(slot: PresetSlot): PresetTags = connection.exclusive { instrument ->
        requireFacet(instrument.tagger, "categorise presets").read(slot.address)
    }

    /**
     * Files [slot] under the categories named by [under], or removes the favorite mark. Drops the
     * favorites listing rather than patching the row in: favoriting adds a voice to a listing it
     * was absent from, and `PresetIndexState.replacing` is a no-op for an absent address.
     */
    override suspend fun setFavorite(slot: PresetSlot, under: Set<Int>): String = connection.exclusive { instrument ->
        requireFacet(instrument.tagger, "favorite presets").setFavorite(slot.address, under)
        listings.refreshEdited(slot.address)
        listings.dropFavorites()
        if (under.isEmpty()) {
            str(R.string.result_favorite_cleared, slot.displayId)
        } else {
            str(R.string.result_favorite_set, slot.displayId)
        }
    }

    /** Replaces [slot]'s category assignments, nulls included. */
    override suspend fun setCategories(slot: PresetSlot, categories: List<CategoryRef?>): String =
        connection.exclusive { instrument ->
            val tagger = requireFacet(instrument.tagger, "categorise presets")
            tagger.setCategories(slot.address, categories)
            listings.refreshEdited(slot.address)
            // A favorite is filed under the voice's own categories, so changing one moves where
            // the instrument lists it - the same reason setFavorite drops that listing.
            listings.dropFavorites()
            val shown = categories.mapNotNull { ref -> ref?.let { tagger.taxonomy.label(it) } }
            if (shown.isEmpty()) {
                str(R.string.result_categories_cleared, slot.displayId)
            } else {
                str(R.string.result_categories_set, slot.displayId, shown.joinToString(", "))
            }
        }

    /**
     * Duplicates [source] into the empty slot [destination]. Only [destination] needs re-reading,
     * and the name in the message is the instrument's: a Nord appends a disambiguating suffix.
     */
    override suspend fun copyProgram(source: PresetSlot, destination: PresetSlot): String = connection.exclusive { instrument ->
        val copiedName = requireFacet(instrument.editor, "copy presets")
            .copyProgram(source.address, destination.address)
        listings.refreshEdited(destination.address)
        str(R.string.result_copied, source.displayId, destination.displayId, copiedName)
    }

    /**
     * Erases one preset - destructive and not undoable from this app. The confirmation lives in
     * the screen. How it is erased varies by family: a Nord empties the slot with one command,
     * while every other family writes an empty or initialised preset over it
     * (`PresetEditor.isEmulated(EditOp.DELETE)`).
     */
    override suspend fun deleteProgram(slot: PresetSlot): String = connection.exclusive { instrument ->
        requireFacet(instrument.editor, "delete presets").delete(slot.address)
        listings.refreshEdited(slot.address)
        str(R.string.result_deleted, slot.displayId)
    }

    /** Asked of the facet rather than derived from the read-only banks: on a Motif XS a factory voice can be favorited but not re-categorised. */
    fun canSetCategories(address: SlotAddress): Boolean =
        connected()?.tagger?.canSetCategories(address) == true

    fun canSetFavorite(address: SlotAddress): Boolean {
        val tagger = connected()?.tagger ?: return false
        return tagger.favorites != null && tagger.canSetFavorite(address)
    }

    /** Why [name] cannot be stored on the connected instrument, or null - see `PresetEditor.nameProblem`. */
    fun presetNameProblem(name: String): String? = connected()?.editor?.nameProblem(name)

    // ---- Reports and the debug menu ----

    /** A filename stem for the shared report, e.g. "Nord Grand" -> "nord_grand". */
    fun suggestedReportFilename(): String =
        requireFacet(current().report, "describe itself").suggestedFilename()

    /**
     * A filename stem for anything shared about this instrument, facet or no facet:
     * [suggestedReportFilename] requires the reporter, and the regression test is offered for
     * every family, including one whose `report` is null.
     */
    val filenameStem: String
        get() = stemFor(current())

    /** What the share dialog says the report will do - the connected instrument's own wording. */
    val reportDescription: String
        get() = requireFacet(current().report, "describe itself").description

    suspend fun buildDeviceReport(progress: ((String) -> Unit)? = null): DeviceReportResult =
        connection.exclusive { instrument ->
            requireFacet(instrument.report, "describe itself").buildReport(progress)
        }

    /**
     * Runs [RegressionTester] against the connected instrument - the debug menu's second entry.
     * A wrapper: the engine is in `core/` because it belongs to the facets, and the confirmation
     * callbacks come from the screen, the only layer that can ask the user.
     *
     * The operation mutex is held for the whole run, callbacks included, so a resume mid-test
     * waits out a pending dialog rather than closing the instrument under it.
     */
    suspend fun runRegressionTest(
        onConfirmSelect: suspend (String) -> Boolean,
        onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
        progress: (String) -> Unit,
    ): RegressionReport = connection.exclusive { instrument ->
        progress(str(R.string.listing_reading_presets))
        // Read once, before anything is written: which slots are free is what the run is planned
        // around, and re-answering it halfway through would plan against the test's own edits.
        val occupied = listings.occupiedSlots()
        RegressionTester(
            instrument = instrument,
            // The writable addresses: the tester writes to the first free one it is handed, and
            // given the whole layout that would be PRE1's first slot.
            allSlots = { listings.allSlots(PresetScope.USER).map { it.address } },
            occupiedSlots = { occupied },
            onConfirmSelect = onConfirmSelect,
            onConfirmRealSlotMutation = onConfirmRealSlotMutation,
            refreshEdited = { addresses -> listings.refreshEdited(*addresses.toTypedArray()) },
        ).run(progress)
    }

    /** Checks one bank's shipped factory names against the instrument - see `FactoryNameCheck`. */
    suspend fun verifyFactoryNames(bank: Int, progress: (String) -> Unit): List<String> =
        connection.exclusive { instrument ->
            val check = instrument.factoryNames
                ?: throw InstrumentException.NotSupported("verify factory voice names")
            check.verify(bank, progress)
        }

    private fun str(@StringRes id: Int, vararg args: Any): String = strings.get(id, *args)

    /**
     * The instrument as the published state has it, or null - what every property a screen reads
     * during composition goes through, since those are re-read across the reconnect a resume
     * causes. [current] stays strict for everything a user action invokes.
     */
    private fun connected(): Instrument? = (state.value as? ConnectionState.Connected)?.instrument

    private fun current(): Instrument = connection.instrument ?: error("Not connected to an instrument")

    /** Unwraps a facet the caller has already decided is present; reaching the error is a UI gating bug. */
    private fun <T : Any> requireFacet(facet: T?, what: String): T =
        facet ?: error("This instrument cannot $what.")
}
