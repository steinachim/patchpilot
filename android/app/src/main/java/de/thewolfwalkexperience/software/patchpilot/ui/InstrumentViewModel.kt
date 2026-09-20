// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.app.Application
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDeviceInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.cache.CacheKey
import de.thewolfwalkexperience.software.patchpilot.cache.CachingBrowser
import de.thewolfwalkexperience.software.patchpilot.cache.PresetIndexCache
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.OccupiedSlotReason
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.stemFor
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.RegressionTester
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentRegistry
import de.thewolfwalkexperience.software.patchpilot.catalog.UnknownDevicePolicy
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.discovery.Candidate
import de.thewolfwalkexperience.software.patchpilot.discovery.physicalKey
import de.thewolfwalkexperience.software.patchpilot.discovery.DeviceDiscovery
import de.thewolfwalkexperience.software.patchpilot.discovery.MidiDiscovery
import de.thewolfwalkexperience.software.patchpilot.discovery.UsbHostDiscovery
import de.thewolfwalkexperience.software.patchpilot.discovery.mergeCandidates
import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsInstrument
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DeviceProfile
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument
import de.thewolfwalkexperience.software.patchpilot.usb.UsbConnectionManager
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.usb.displayLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import androidx.annotation.StringRes
import de.thewolfwalkexperience.software.patchpilot.R

private const val TAG = "InstrumentViewModel"

/** Sanity cap for [InstrumentViewModel.allSlots]'s per-bank loop bound - see its own comment. */
private const val MAX_ITEMS_PER_BANK = 10_000

/**
 * One row in the device picker.
 *
 * Two kinds rather than one list of `UsbDevice`, because a recognised instrument is not
 * necessarily a USB one - a Pro-800 is matched on the MIDI bus and has no `UsbDevice` to offer -
 * and because the two are opened by completely different routes: a [Known] goes through the
 * registry with its own descriptor, an [Unknown] through the guess that
 * `UnknownDevicePolicy` gates.
 */
sealed interface PickerEntry {
    /** What the row says. */
    val label: String

    /** An instrument the catalog matched, on whichever bus found it. */
    data class Known(val candidate: Candidate) : PickerEntry {
        override val label: String get() = candidate.displayName
    }

    /** An attached USB device nothing in the catalog matched. */
    data class Unknown(val device: UsbDevice) : PickerEntry {
        override val label: String get() = device.displayLabel()
    }
}

/** The physical device a row refers to, in the same form [UsbConnectionManager.deviceDetachEvents]
 * reports a detach in - what [InstrumentViewModel.handleUsbDetach] matches a picker row against. */
private fun PickerEntry.physicalKey(): String = when (this) {
    is PickerEntry.Known -> candidate.physicalKey
    is PickerEntry.Unknown -> device.physicalKey()
}

/**
 * The handle the platform assigned this device when it enumerated - see [CacheKey.physicalDevice].
 *
 * Unlike [physicalKey], which is the model (vendor and product id), this changes on a replug.
 */
private fun UsbDevice.deviceHandle(): String = "usb:$deviceName"

private fun Candidate.deviceHandle(): String = when (val h = handle) {
    is UsbDevice -> h.deviceHandle()
    is MidiDeviceInfo -> "midi:${h.id}"
    else -> "handle:${System.identityHashCode(h)}"
}

/** Mirrors the states a connection attempt moves through, made explicit for the UI. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Searching : ConnectionState()
    /** Opening the device, which on the USB bus means waiting for the permission dialog.
     * [bus] is carried so the screen does not claim "USB" while a MIDI port is opening -
     * which is exactly the case when connecting to a Pro-800. */
    data class Opening(val displayName: String, val bus: Bus) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    /**
     * The user answered the system's USB permission dialog with "Deny".
     *
     * Not an [Error], because of what happens right after that dialog closes: closing it resumes
     * `MainActivity`, and a resume with nothing connected is a rescan
     * ([InstrumentViewModel.shouldRebuildOnResume]). Landing on `Error` there meant the rescan
     * immediately asked again, and denying was answered with the same dialog, without end. This
     * state is the one a resume leaves alone; only the Retry button asks again.
     */
    data class PermissionDenied(val displayName: String) : ConnectionState()

    /**
     * The instrument is attached but not listening, and the fix is at its own front panel.
     *
     * Distinct from [Error] because the user is expected to *go and change something* before
     * retrying, which needs the button sequence spelled out rather than a single red line. The
     * screen shows [steps] as a list and offers a retry, which rescans and reconnects from
     * scratch - the instrument may well have re-enumerated when the setting changed.
     */
    data class NeedsManualSetting(
        val message: String,
        val steps: List<String>,
        val alsoCheck: String?,
    ) : ConnectionState()
    data class Connected(val instrument: Instrument) : ConnectionState()

    /** Nothing is attached at all - not an error, just the expected state before a supported
     * instrument is plugged in. [supportedNames] is read from the instrument catalog (D-series
     * JSON configs), never a literal list here, so a new supported model needs no screen change. */
    data class NothingFound(val supportedNames: List<String>) : ConnectionState()

    /**
     * Everything attached, recognised or not, with the recognised ones first.
     *
     * **Deliberately unfiltered**, and [message] is why that works. A device missing from this
     * list is indistinguishable from a device that is not plugged in, so hiding the ones the app
     * cannot use would send somebody to check their cable for a fault that does not exist.
     * Everything is listed, everything stays selectable, and picking something unusable explains
     * itself here rather than silently doing nothing.
     */
    data class DeviceSelection(
        val entries: List<PickerEntry>,
        /** Set after a refused pick, so the picker can say why without leaving the screen. */
        val message: String? = null,
    ) : ConnectionState()

    /** The user picked a device in [DeviceSelection]; asks them to confirm before continuing,
     * since this device is unrecognized and unsupported. */
    data class UnknownDeviceWarning(val device: UsbDevice) : ConnectionState()

    /**
     * Connected, and the instrument raised an [Instrument.advisory] - shown before the user starts
     * using it, with the choice to continue or disconnect.
     *
     * **After the handshake, unlike [UnknownDeviceWarning].** That one is decided from USB ids
     * before anything is opened; this can only be known by asking the device, so by the time it is
     * raised the session already exists. Nothing has been written, though, so "continue or back
     * out" is still a real choice - and the instrument is carried here so continuing costs no
     * second connect.
     */
    data class AdvisoryWarning(val instrument: Instrument, val message: String) : ConnectionState()

    /**
     * The instrument this session was using was physically unplugged.
     *
     * A dedicated state rather than folding back into [Disconnected]: ConnectScreen auto-connects
     * from [Disconnected] on its own, which would silently latch onto a different instrument that
     * happens to still be attached - the opposite of what someone who watched their own instrument
     * go dark wants. Landing here instead means nothing reconnects until they explicitly ask for a
     * new search - with one exception, which is the one they do want: the instrument that went
     * dark being plugged back in. [physicalKey] is what [InstrumentViewModel.onUsbDeviceAttached]
     * matches a later attach against; null for a session with no backing USB device.
     */
    data class DeviceLost(val instrumentName: String, val physicalKey: String?) : ConnectionState()
}

/**
 * Holds the single connected [Instrument] for the app's lifetime and exposes the operations the
 * screens drive.
 *
 * **Nothing here knows what family is connected.** Every action below goes through a facet, and a
 * facet that is null is an operation the screens do not offer at all, so adding a family adds no
 * `when` here.
 *
 * There is no content-database "session" to open/close: each operation that locks an instrument
 * cleans up after itself, which is what keeps the instrument's own display/play state usable
 * between actions without this class wrapping every call in an open/close pair.
 */
class InstrumentViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application), ProgramsOperations {
    private val connectionManager = UsbConnectionManager(application)

    /** Settings screen reads and writes this directly; [performConnect] reads it fresh on every
     * scan, so a change takes effect on the next connect attempt without restarting the app. */
    val connectionPreferences = ConnectionPreferences(application)

    /**
     * The device report, read and held here so a minutes-long read survives the screen that
     * started it being torn down by a configuration change - see [DeviceReportRunner].
     */
    internal val deviceReportRunner = DeviceReportRunner(
        scope = viewModelScope,
        savedState = savedStateHandle,
        build = { progress -> buildDeviceReport(progress) },
        identity = { suggestedReportFilename() to reportDescription },
    )

    /**
     * The debug menu's regression run, held here so it survives the debug screen being torn down
     * by a configuration change - see [RegressionRunner].
     */
    internal val regressionRunner = RegressionRunner(
        scope = viewModelScope,
        savedState = savedStateHandle,
        run = ::runRegressionTest,
        filenameStem = { filenameStem },
    )

    /**
     * The buses to look on, in priority order.
     *
     * USB host first: a Nord is visible on both (its MIDI interface is real but useless for
     * preset management), and the first bus to claim a physical device wins in
     * [mergeCandidates].
     */
    private val discoveries: List<DeviceDiscovery> = listOf(
        UsbHostDiscovery(connectionManager),
        MidiDiscovery(application),
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var instrument: Instrument? = null

    /**
     * [Candidate.physicalKey]/[UsbDevice.physicalKey] of whatever [instrument] is currently
     * connected to, or null (nothing connected, or connected over a bus with no backing
     * `UsbDevice` at all - demo mode). Captured at connect time so a later physical detach can be
     * matched to *this* session's device rather than to whatever else Android happens to report
     * unplugging elsewhere on the bus - see [handleUsbDetach].
     */
    private var connectedPhysicalKey: String? = null

    /**
     * The handle of the device this session runs over - a USB device path, or a MIDI device id -
     * which is what tells one unit of a model from another for the listing cache; see
     * [CacheKey.physicalDevice]. Null while nothing is connected, and in demo mode.
     */
    private var connectedDeviceHandle: String? = null


    /**
     * [Candidate.physicalKey]/[UsbDevice.physicalKey] of whatever device a connect attempt is
     * currently mid-flight on - i.e. whatever [ConnectionState.Opening] is waiting for - or null.
     * Set right before entering that state and cleared on every way out of it, so
     * [handleUsbDetach] can fail an attempt fast if the device it is opening (most often waiting
     * on a permission dialog that will now never come) is unplugged before it finishes.
     */
    private var openingPhysicalKey: String? = null

    /**
     * The [UsbDevice] behind the most recent successful [confirmUnknownDevice] (or silent
     * reconnect of one) - remembered so [reconnectRememberedUnknownDeviceOrOffer] can restore it
     * without the warning screen the next time [connect] finds nothing in the catalog, most
     * commonly a [forceReconnect] on resume. Cleared by an explicit [disconnect] and by a real
     * physical detach of this device in [handleUsbDetach] - not by [teardownCurrentInstrument]
     * itself, since [forceReconnect] needs it to survive its own teardown-then-[connect].
     */
    private var lastConfirmedUnknownDevice: UsbDevice? = null

    /**
     * Serialises every read-then-write against [instrument] with [forceReconnect]'s teardown.
     *
     * Without this, a rename/move/delete/copy/regression-test/report already in flight when the
     * app backgrounds and comes back keeps running against its own captured [Instrument]
     * reference - `forceReconnect` doesn't cancel it the way [cancelIndex] cancels a scan - and
     * only discovers the session is gone on its trailing re-read, throwing "Not connected to an
     * instrument" out from under an edit that may have already landed. Held for the *whole* body
     * of each such operation, not just its `current()` read, since the race is between the write
     * and the re-read that confirms it, not just the read alone.
     *
     * The index scan is deliberately not routed through this: [cancelIndex] already handles it,
     * and cancelling it is cheaper and more honest than making a resume wait out a 93-second
     * Motif XS listing before it can reconnect.
     */
    private val instrumentMutex = Mutex()

    private var connectJob: Job? = null

    // Set by disconnect(showPicker = true) - the user backed out of a session on purpose and
    // asked to choose again, so the next connect() must not silently reconnect a lone device.
    private var forcePickerOnNextConnect = false

    init {
        // Lives for the ViewModel's whole lifetime, same as the rest of its state - cancelled by
        // viewModelScope on onCleared() like everything else here, with no separate teardown to
        // remember.
        //
        // Guarded, unlike most of this class's fire-and-forget launches: viewModelScope installs
        // no CoroutineExceptionHandler, and this is the one place here whose very first suspension
        // point is a registerReceiver() call that can fail for reasons outside this app's control
        // (a restrictive OEM build, say). Losing detach detection is a small regression; taking
        // the whole process down at startup over it is not - the same reasoning that already
        // wraps every USB reader loop elsewhere (see UsbMidiBulkTransport).
        viewModelScope.launch {
            try {
                connectionManager.deviceDetachEvents().collect { device -> handleUsbDetach(device) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't listen for USB detach events; a physical unplug will only be noticed when the next operation fails", e)
            }
        }
    }

    /**
     * Runs an edit on [viewModelScope] rather than on the caller's composition scope.
     *
     * **The scope an edit runs in decides whether navigating away can abandon it mid-write.** A
     * `rememberCoroutineScope()` in ProgramsScreen is cancelled the moment that screen leaves the
     * composition, which would cancel a write with it - and on a Motif XS a write is a header, up
     * to 81 blocks and a footer, with the instrument sitting on *receiving midi bulk data* until
     * it is completed or power-cycled. Tying an edit's lifetime to the instrument's instead of to
     * the screen's is what makes backing out safe rather than merely discouraged.
     *
     * `SysExExchange.exchangeAfterAll` refuses to be interrupted mid-sequence in any case; this is
     * the other half of that guarantee, and the half that also keeps a slow single-message write
     * from being dropped halfway.
     */
    override fun launchEdit(block: suspend () -> Unit): Job = viewModelScope.launch { block() }

    /**
     * Reacts to a physical USB detach, in whichever of three shapes the session is currently in:
     * closes an active session if the device that went away is the one it is actually using
     * ([ConnectionState.DeviceLost]); fails a connect attempt still waiting on that same device
     * ([ConnectionState.Opening], most often stuck on a permission dialog that can now never
     * arrive) rather than leaving it hung forever; or, sitting on
     * [ConnectionState.DeviceSelection], drops the matching row so it cannot be tapped into
     * either of the above.
     *
     * **Ignores everything else.** Every device Android has ever seen fires this same broadcast on
     * unplug - a USB keyboard, a charger-only cable's far end, another recognised instrument still
     * sitting unselected in the picker - and none of those should so much as flicker whatever this
     * session is doing.
     */
    private fun handleUsbDetach(device: UsbDevice) {
        val detachedKey = device.physicalKey()
        if (detachedKey == connectedPhysicalKey) {
            val name = connected()?.identity?.name
                ?: (state.value as? ConnectionState.AdvisoryWarning)?.instrument?.identity?.name
                ?: device.displayLabel()
            // Whatever connect attempt might be mid-flight (unlikely - connectedPhysicalKey is
            // only set once one has already succeeded - but forceReconnect() from onResume could
            // overlap this) must not be allowed to overwrite DeviceLost with a stale
            // Connected/Error right after it is set below.
            connectJob?.cancel()
            lastConfirmedUnknownDevice = null
            viewModelScope.launch {
                teardownCurrentInstrument()
                _state.value = ConnectionState.DeviceLost(name, detachedKey)
            }
            return
        }
        if (detachedKey == openingPhysicalKey) {
            openingPhysicalKey = null
            connectJob?.cancel()
            _state.value = ConnectionState.Error(str(R.string.connect_opening_device_unplugged, device.displayLabel()))
            return
        }
        val picker = state.value as? ConnectionState.DeviceSelection ?: return
        val remaining = picker.entries.filterNot { it.physicalKey() == detachedKey }
        if (remaining.size != picker.entries.size) {
            _state.value = picker.copy(entries = remaining)
        }
    }

    /**
     * Reacts to a USB device being plugged in, delivered by `MainActivity.onNewIntent` from the
     * manifest's `USB_DEVICE_ATTACHED` filter.
     *
     * **Only where the session is waiting for a device.** Every state that ConnectScreen shows as
     * "nothing usable yet" rescans, since the thing just plugged in is exactly what it was waiting
     * for; the picker rescans without auto-picking, so a list the user is looking at gains a row
     * rather than vanishing into a connect; and [ConnectionState.DeviceLost] reconnects only when
     * the device that comes back is the one that went away - any other instrument still waits for
     * the user's own "Retry search", as that state's doc comment promises. A session already in
     * flight or established is left alone: [handleUsbDetach] is what ends one.
     *
     * Permission is never asked on this path. Android grants it to the app it launches for the
     * attach before the intent arrives, so a rescan here opens the device without a dialog.
     */
    fun onUsbDeviceAttached(device: UsbDevice) {
        val attachedKey = device.physicalKey()
        val s = state.value
        Log.i(TAG, "USB attach of ${device.displayLabel()} while $s; attached devices: " +
            connectionManager.findAllDevices().joinToString { it.displayLabel() })
        when (s) {
            is ConnectionState.NothingFound,
            is ConnectionState.Error,
            is ConnectionState.PermissionDenied,
            is ConnectionState.NeedsManualSetting,
            ConnectionState.Disconnected,
            -> connect()
            is ConnectionState.DeviceSelection -> {
                forcePickerOnNextConnect = true
                connect()
            }
            is ConnectionState.DeviceLost -> if (attachedKey == s.physicalKey) connect()
            is ConnectionState.Connected,
            ConnectionState.Searching,
            is ConnectionState.Opening,
            is ConnectionState.UnknownDeviceWarning,
            is ConnectionState.AdvisoryWarning,
            -> Unit
        }
    }

    /**
     * Runs one connection attempt, unless another is already in flight.
     *
     * The single guard, launch and catch pair for the four entry points: auto-connect, a picked
     * row, an unrecognised device and demo mode.
     *
     * `CancellationException` is rethrown rather than caught, so backing out of a connect does not
     * land on the error screen; that is the reason this is not `runCatching`.
     */
    private fun launchConnect(failureMessage: String? = null, block: suspend () -> Unit) {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstrumentException.NeedsManualSetting) {
                // Not an error line: the user has something to go and do, and the screen has to
                // show them what. See ConnectionState.NeedsManualSetting.
                _state.value = ConnectionState.NeedsManualSetting(
                    message = e.message ?: str(R.string.connect_failed),
                    steps = e.steps,
                    alsoCheck = e.alsoCheck,
                )
            } catch (e: Exception) {
                _state.value =
                    ConnectionState.Error(e.message ?: failureMessage ?: str(R.string.connect_failed))
            }
        }
    }

    /**
     * Scans every bus, then connects.
     *
     * **Both buses are scanned, and the results deduplicated by physical device.** That is
     * not theoretical: a Nord exposes a class-compliant USB-MIDI interface alongside the vendor
     * interface this app uses, so it turns up on the MIDI scan too. USB goes first, so a Nord is
     * claimed through the interface that can actually browse presets, and its MIDI twin is
     * dropped rather than offered as a second, useless entry.
     */
    fun connect() {
        launchConnect { performConnect(consumeForcePicker()) }
    }

    /** Reads and clears [forcePickerOnNextConnect] in one step, so every caller that folds it
     * into a connect attempt - [connect] and [forceReconnect] alike - consumes it the same way. */
    private fun consumeForcePicker(): Boolean =
        forcePickerOnNextConnect.also { forcePickerOnNextConnect = false }

    /**
     * The scan-then-connect body [connect] and [forceReconnect] share - factored out so
     * [forceReconnect] can run it inside its own [launchConnect] job (see that function's doc
     * comment) instead of through a second, untracked one.
     *
     * **The MIDI scan is skipped where auto-connect already has its answer from USB alone.**
     * `MidiDiscovery.scan` is the slow one - it opens every port and waits up to 600ms per port
     * for a probe reply, which is what made a Nord's launch feel slower once MIDI-only families
     * arrived - and [mergeCandidates] always prefers a USB candidate over a MIDI one for the same
     * physical device in any case, so a MIDI scan could never change what gets connected here.
     * Skipping it needs a picker of everything on both buses (`forcePicker`), or auto-connect
     * itself turned off, or USB not finding anything recognised on its own - a MIDI-only family
     * (the Pro-800) is only ever found by actually running that scan.
     */
    private suspend fun performConnect(forcePicker: Boolean) {
        _state.value = ConnectionState.Searching
        val catalog = InstrumentRegistry.allDescriptors(getApplication())
        val autoConnect = connectionPreferences.autoConnectToFirstFound.first()

        val usbDiscovery = discoveries.first { it.bus == Bus.USB }
        val usbCandidates = usbDiscovery.scan(catalog)
        val usbKnown = usbCandidates.filter { it.descriptor != null }
        if (autoConnect && !forcePicker && usbKnown.isNotEmpty()) {
            connectCandidate(usbKnown.first())
            return
        }

        val otherScans = discoveries.filterNot { it.bus == Bus.USB }.map { it.scan(catalog) }
        val candidates = mergeCandidates(listOf(usbCandidates) + otherScans)
        val known = candidates.filter { it.descriptor != null }
        when {
            // Nothing recognised: reconnect a device already accepted through the "at your own
            // risk" gate this session if it is still attached, otherwise offer whatever else is
            // attached.
            known.isEmpty() -> reconnectRememberedUnknownDeviceOrOffer()
            // Auto-connect is on and nobody asked to be shown the picker anyway: take the first
            // one the scan found. Reached only when USB alone did not already resolve it above -
            // so everything here is a MIDI-only family, or the picker was forced.
            autoConnect && !forcePicker -> connectCandidate(known.first())
            // Auto-connect is off, or the user backed out and asked to choose again: let them
            // pick rather than taking whichever the scan happened to return first.
            else -> _state.value = ConnectionState.DeviceSelection(pickerEntries(known))
        }
    }

    /** Opens a recognized candidate on whichever bus found it and builds its family's instrument. */
    private suspend fun connectCandidate(candidate: Candidate) {
        val descriptor = requireNotNull(candidate.descriptor) { "candidate is not a known instrument" }
        openingPhysicalKey = candidate.physicalKey
        _state.value = ConnectionState.Opening(candidate.displayName, candidate.bus)
        val transport = try {
            discoveryFor(candidate).open(candidate)
        } catch (e: SecurityException) {
            openingPhysicalKey = null
            _state.value = ConnectionState.PermissionDenied(candidate.displayName)
            return
        }
        openingPhysicalKey = null
        val built = closingOnFailure(transport::close) {
            InstrumentRegistry.create(getApplication(), descriptor, transport)
        }
        closingOnFailure({ closeQuietly(built) }) { built.connect() }
        instrumentMutex.withLock {
            instrument = built
            connectedPhysicalKey = candidate.physicalKey
            connectedDeviceHandle = candidate.deviceHandle()
        }
        _state.value = connectedOrAdvisory(built)
        startIndex()
    }

    /**
     * Runs [block], releasing what [close] names if it throws - including on cancellation.
     *
     * A transport opened for a connect attempt is owned by nobody until the instrument built on
     * it is published as the session, so a failing handshake (a timeout, a Motif XS routed away
     * from USB, backing out of the attempt) has to release it here: a MIDI port left open cannot
     * be probed by the next scan, and a claimed USB interface outlives the attempt.
     */
    private inline fun <T> closingOnFailure(close: () -> Unit, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        try {
            close()
        } catch (closeFailure: Exception) {
            e.addSuppressed(closeFailure)
        }
        throw e
    }

    private fun discoveryFor(candidate: Candidate): DeviceDiscovery =
        discoveries.first { it.bus == candidate.bus }

    /**
     * Every attached device as picker rows, **recognised ones first**.
     *
     * Deduplicated by [Candidate.physicalKey], which both buses derive the same way from the
     * backing `UsbDevice` - so a recognised instrument does not also appear as an unrecognised
     * USB device one row further down.
     */
    private fun pickerEntries(known: List<Candidate>): List<PickerEntry> {
        val claimed = known.map { it.physicalKey }.toSet()
        return known.map { PickerEntry.Known(it) } +
            connectionManager.findAllDevices()
                .filter { it.physicalKey() !in claimed }
                .map { PickerEntry.Unknown(it) }
    }

    /**
     * Routes a picked row to whichever path can open it.
     *
     * A recognised instrument connects through the registry with its own descriptor, exactly as
     * an auto-connect would - it is not a "try it anyway", and must not be routed through the
     * guess that `UnknownDevicePolicy` gates.
     */
    fun selectEntry(entry: PickerEntry) {
        when (entry) {
            is PickerEntry.Known -> launchConnect { connectCandidate(entry.candidate) }
            is PickerEntry.Unknown -> selectUnknownDevice(entry.device)
        }
    }

    /** Nothing in the catalog matched - reconnects [lastConfirmedUnknownDevice] straight through
     * [connectAndFinish] if it is still among the attached devices, without showing the warning
     * again: the user already accepted it once this session. Otherwise falls through to
     * [offerUnknownDeviceOrFail].
     *
     * This is what lets [forceReconnect] restore an unrecognised device silently - e.g. returning
     * from the share sheet after generating its device report - instead of bouncing back to the
     * picker/warning and reading as "disconnected" the way a cataloged device's single-candidate
     * auto-reconnect already does not.
     */
    private suspend fun reconnectRememberedUnknownDeviceOrOffer() {
        val remembered = lastConfirmedUnknownDevice
        val stillAttached = remembered != null &&
            connectionManager.findAllDevices().any { it.physicalKey() == remembered.physicalKey() }
        if (remembered == null || !stillAttached) {
            lastConfirmedUnknownDevice = null
            offerUnknownDeviceOrFail()
            return
        }
        connectAndFinish(remembered, remembered.displayLabel()) { transport ->
            val profile =
                DeviceProfile.unknown(remembered.displayLabel(), remembered.vendorId, remembered.productId)
            NordInstrument(NordDevice(transport, profile))
        }
    }

    /** No supported instrument was found - offers a device picker if anything else is plugged
     * in over USB, otherwise falls back to the plain "nothing found" error. */
    private fun offerUnknownDeviceOrFail() {
        val allDevices = connectionManager.findAllDevices()
        if (allDevices.isEmpty()) {
            // Every instrument the app can recognize, on both buses: both were scanned by the
            // time this runs, so naming only the USB-matched ones would claim a narrower search
            // than actually happened.
            val names = InstrumentRegistry.allDescriptors(getApplication()).map { it.name }.sorted()
            _state.value = ConnectionState.NothingFound(names)
        } else {
            _state.value = ConnectionState.DeviceSelection(pickerEntries(emptyList()))
        }
    }

    /** User picked a device from [ConnectionState.DeviceSelection] - show the "unsupported, at
     * your own risk" warning before touching it. */
    fun selectUnknownDevice(usbDevice: UsbDevice) {
        if (usbDevice.vendorId !in unknownCapableVendorIds()) {
            _state.value = ConnectionState.DeviceSelection(
                entries = pickerEntries(emptyList()),
                message = "${usbDevice.displayLabel()} is not a Clavia device, so there is no " +
                    "protocol to try on it. Only an unrecognised Nord can be opened this way - " +
                    "everything else the app supports is matched by its exact USB ids.",
            )
            return
        }
        _state.value = ConnectionState.UnknownDeviceWarning(usbDevice)
    }

    /**
     * Vendor ids for which guessing at an unrecognised device is defensible - **Nord only**.
     *
     * Read from the catalog rather than written as a constant, so adding a Nord model needs
     * no code change here, and so this cannot drift from the ids the registry actually matches.
     *
     * **Nord and nothing else, and the asymmetry is structural.** Clavia's vendor id covers
     * several models this project has verified share one protocol, so "another Clavia device
     * probably speaks this too" is an inference with evidence behind it. Yamaha's and Behringer's
     * cover exactly one instrument each, and a Motif XS is no evidence about Yamaha synths in
     * general - its address map has holes (`0x08`) and detached banks (USER DR at `0x28`). A
     * family is guessed from a vendor id only where two members have already agreed.
     *
     * Fails closed: if the catalog cannot be read this is empty and every device is refused, which
     * is the safe direction for a path whose whole job is to avoid talking to strangers.
     */
    private fun unknownCapableVendorIds(): Set<Int> =
        UnknownDevicePolicy.vendorIdsAllowingAGuess(InstrumentRegistry.allDescriptors(getApplication()))

    /** Backs out of [ConnectionState.UnknownDeviceWarning] back to the device picker (or the
     * plain error state, if USB devices were unplugged in the meantime). */
    fun cancelUnknownDeviceSelection() {
        offerUnknownDeviceOrFail()
    }

    /** User accepted the warning in [ConnectionState.UnknownDeviceWarning] - proceeds exactly
     * like [connect] does for a recognized instrument, but builds from a [DeviceProfile.unknown]
     * instead of looking the USB ids up in [InstrumentRegistry]. */
    fun confirmUnknownDevice(usbDevice: UsbDevice) {
        // Checked again here, not only in selectUnknownDevice. This is the call that claims the
        // interface and starts sending a vendor protocol, so it is the one that must not be
        // reachable with the wrong device - whatever route got here.
        if (usbDevice.vendorId !in unknownCapableVendorIds()) {
            _state.value = ConnectionState.DeviceSelection(
                entries = pickerEntries(emptyList()),
                message = "${usbDevice.displayLabel()} cannot be opened this way.",
            )
            return
        }
        launchConnect {
            connectAndFinish(usbDevice, usbDevice.displayLabel()) { transport ->
                val profile = DeviceProfile.unknown(usbDevice.displayLabel(), usbDevice.vendorId, usbDevice.productId)
                NordInstrument(NordDevice(transport, profile))
            }
        }
    }

    /** Shared permission -> open -> connect tail for both [connect] and [confirmUnknownDevice] -
     * [displayName] is only used for the USB-permission-denied error message. */
    private suspend fun connectAndFinish(
        usbDevice: UsbDevice,
        displayName: String,
        factory: (UsbBulkTransport) -> NordInstrument,
    ) {
        openingPhysicalKey = usbDevice.physicalKey()
        _state.value = ConnectionState.Opening(displayName, Bus.USB)
        if (!connectionManager.requestPermission(usbDevice)) {
            openingPhysicalKey = null
            _state.value = ConnectionState.PermissionDenied(displayName)
            return
        }
        openingPhysicalKey = null

        val transport = connectionManager.openTransport(usbDevice)
        val built = closingOnFailure(transport::close) { factory(transport) }
        closingOnFailure({ closeQuietly(built) }) {
            built.connect()
            // An unrecognized device starts on DeviceProfile.unknown()'s deliberately generous
            // bank bounds ('Z', 100 groups), which are guesses rather than this instrument's. Its
            // own Program category announces the real ones, so ask - best-effort, since a device
            // that has no Program category at all is exactly the sort this path exists to
            // survive. A catalog device keeps its configured bounds untouched.
            built.applyDerivedBankLayoutIfUnknown(displayName)
        }
        instrumentMutex.withLock {
            instrument = built
            connectedPhysicalKey = usbDevice.physicalKey()
            connectedDeviceHandle = usbDevice.deviceHandle()
        }
        lastConfirmedUnknownDevice = usbDevice
        _state.value = connectedOrAdvisory(built)
        startIndex()
    }

    private suspend fun NordInstrument.applyDerivedBankLayoutIfUnknown(displayName: String) {
        if (identity.descriptorId != DeviceProfile.UNKNOWN_ID) return
        try {
            deriveBankLayout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best-effort: the guessed bounds still work, just generously. Not runCatching,
            // which would swallow a cancellation and leave a connect the user backed out of
            // still querying the instrument.
            Log.w(TAG, "Couldn't derive a bank layout for $displayName", e)
        }
    }

    /**
     * Connects to a fictitious instrument instead of real hardware - see [DemoInstrument]. No USB
     * permission, discovery or hardware I/O happens on this path, and it lands in exactly the same
     * [ConnectionState.Connected] every other screen already knows how to handle.
     */
    fun connectDemo() {
        launchConnect(failureMessage = str(R.string.connect_failed_demo)) {
            val demo = DemoInstrument()
            demo.connect()
            instrumentMutex.withLock {
                instrument = demo
                connectedPhysicalKey = null
                connectedDeviceHandle = null
            }
            _state.value = ConnectionState.Connected(demo)
            startIndex()
        }
    }

    /**
     * Whether returning to the foreground should rebuild the session.
     *
     * Asks the connected instrument, which asks its transport (`Transport.rebuildOnResume`) - so
     * a USB session is rebuilt and a MIDI one is left alone. Demo mode answers false through the
     * same route rather than through a special case here.
     *
     * True while nothing is connected: that resume is a rescan, and it is how an instrument
     * plugged in while the app was away gets picked up.
     *
     * False on [ConnectionState.DeviceLost]: that state exists specifically so nothing reconnects
     * until the warning is acted on, and a resume-triggered rescan would defeat it exactly as
     * surely as ConnectScreen's own auto-connect would (see that state's own doc comment).
     *
     * False on [ConnectionState.Opening]: that state most often means a connect attempt is
     * sitting on `UsbConnectionManager.requestPermission()`'s system dialog, and dismissing that
     * dialog is itself a resume. Rebuilding then would tear down the session the dialog's own
     * permission grant is about to finish and orphan the original attempt's coroutine; the
     * attempt already in flight is left to finish or fail on its own instead.
     *
     * False on [ConnectionState.PermissionDenied], for the other order of the same two events:
     * when the denial's broadcast is handled *before* the resume, the attempt has already landed
     * here by the time this is asked, and a rescan now would put the dialog straight back up.
     */
    val shouldRebuildOnResume: Boolean
        get() = when (val s = state.value) {
            is ConnectionState.Connected -> s.instrument.rebuildOnResume
            is ConnectionState.DeviceLost -> false
            is ConnectionState.Opening -> false
            is ConnectionState.PermissionDenied -> false
            else -> true
        }

    /** [ConnectionState.Connected], or the advisory gate where the instrument raised one. */
    private fun connectedOrAdvisory(built: Instrument): ConnectionState =
        built.advisory
            ?.let { ConnectionState.AdvisoryWarning(built, it) }
            ?: ConnectionState.Connected(built)

    /** User accepted the advisory - carries on with the instrument already connected. */
    fun confirmAdvisory() {
        val pending = state.value as? ConnectionState.AdvisoryWarning ?: return
        _state.value = ConnectionState.Connected(pending.instrument)
        startIndex()
    }

    /** User declined the advisory - closes the session rather than leaving it half-entered. */
    fun declineAdvisory() = disconnect(showPicker = false)

    /**
     * The connected instrument's advisory, or null. Drives the banner that keeps the warning
     * visible after [confirmAdvisory] - a gate seen once at connect is a gate forgotten by the
     * time anything is edited.
     */
    val advisory: String?
        get() = (state.value as? ConnectionState.Connected)?.instrument?.advisory

    fun disconnect(showPicker: Boolean = false) {
        forcePickerOnNextConnect = showPicker
        lastConfirmedUnknownDevice = null
        viewModelScope.launch {
            teardownCurrentInstrument()
            _state.value = ConnectionState.Disconnected
        }
    }

    /**
     * Tears down whatever connection exists (best-effort - it may already be unusable) and
     * reconnects from scratch. Meant to be called whenever the app returns to the foreground:
     * unrelated USB bus activity while backgrounded (e.g. a USB keyboard being unplugged and
     * replugged) has been observed to leave the connection's endpoints erroring on every
     * subsequent transfer, with no clean way to detect or repair that specifically - a full
     * disconnect/reconnect is the safe default instead.
     *
     * Runs through [launchConnect] rather than a bare `viewModelScope.launch`, so this job lands
     * in [connectJob] like every other connect attempt: the single-flight guard, the error
     * handling and [handleUsbDetach]'s ability to cancel it all depend on [connectJob] actually
     * being whatever is currently running.
     */
    fun forceReconnect() {
        launchConnect {
            teardownCurrentInstrument()
            _state.value = ConnectionState.Disconnected
            performConnect(consumeForcePicker())
        }
    }

    override fun onCleared() {
        // best-effort - viewModelScope is already cancelled by the time onCleared runs, so this
        // can't reuse it. Nothing here sends protocol messages: every operation below already
        // cleans up whatever lock it caused before returning, so by the time onCleared runs
        // there's nothing left to undo except releasing the raw connection.
        closeQuietly(instrument)
    }

    private suspend fun teardownCurrentInstrument() {
        // **Stop the listing before closing what it is reading from.** A scan outlives this call
        // otherwise - it runs in viewModelScope, not in the composition - and every remaining slot
        // then fails instantly against a handle that is already shut, none of them retried.
        // MainActivity.onResume() calls forceReconnect(), so backgrounding the app mid-scan is
        // enough to get here.
        cancelIndex()
        // Waits for any edit/report/regression-test already in flight to finish on its own terms
        // before the instrument it is using is closed out from under it - see [instrumentMutex].
        instrumentMutex.withLock {
            closeQuietly(instrument)
            instrument = null
            connectedPhysicalKey = null
            connectedDeviceHandle = null
        }
    }

    /** Drops the listing and whatever is producing it, so nothing survives into the next session. */
    private fun cancelIndex() {
        indexJobs.values.forEach { it.cancel() }
        indexJobs.clear()
        indexedInstrument = null
        // **Deliberately does not publish a fresh PresetIndexState.** Emitting one recomposes
        // ProgramsScreen at the exact moment there is no instrument - and that screen derives the
        // bank rail and its placeholder rows from `index`, both of which need one. [launchIndex]
        // resets the state when the next scan starts, by which point there is something to
        // describe.
    }

    private fun closeQuietly(target: Instrument?) {
        try {
            target?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing the connection did not complete cleanly", e)
        }
    }

    // ---- Actions, each routed through a facet ----

    /**
     * The program index as it arrives.
     *
     * Exposed as the raw [Flow] rather than collected into a list here, because on a Pro-800 it
     * is 400 sequential dumps and the screen has to render what has landed so far. On a Nord
     * it is one batch, and the same collector handles it without noticing.
     */
    fun programIndex(scope: PresetScope): Flow<IndexUpdate> = browser().index(scope)

    /**
     * The connected instrument's browser, wrapped so a listing can come from memory.
     *
     * Built per call rather than held: [CachingBrowser] carries no state of its own - all of it
     * lives in [indexCache], which is a field of this ViewModel and therefore outlives the
     * instrument object. That is the entire point. `MainActivity.onResume` calls [forceReconnect]
     * on every return to the foreground, which throws the instrument away and builds a new one,
     * so a cache owned by the instrument would be discarded exactly when it was needed.
     *
     * Demo mode is not wrapped. Its library is in memory already, so there is nothing to save,
     * and a cache surviving a demo session would be a fiction outliving its own fiction.
     */
    private fun browser(): PresetBrowser {
        val instrument = current()
        val key = cacheKey(instrument) ?: return instrument.browser
        return CachingBrowser(instrument.browser, indexCache, key)
    }

    /**
     * The cache key for [instrument]'s listing, or null where nothing is cached: demo mode,
     * whose library is in memory already, and a session with no device handle to key on.
     */
    private fun cacheKey(instrument: Instrument): CacheKey? {
        if (instrument is DemoInstrument) return null
        return CacheKey.of(instrument, connectedDeviceHandle ?: return null)
    }

    // ---- The preset index, collected here rather than in the composition ----
    //
    // A scan in the composition's coroutine scope would be cancelled and restarted by a rotation,
    // which on a Motif XS is 93 seconds thrown away. A ViewModel survives configuration changes,
    // so the scan does too.

    // Each scope keeps its own state, rather than one state re-collected on every switch. Two
    // things need that, and neither is optional:
    //
    // - "Copy to..." has to know which user slots are free *while the factory listing is on
    //   screen*, because a factory voice can only be copied into a user slot.
    // - An edit made from the favorites listing changes a row the user listing also holds, and
    //   the user listing has to be corrected without being re-read.
    private val _indexes = MutableStateFlow<Map<PresetScope, PresetIndexState>>(emptyMap())

    private val _scope = MutableStateFlow(PresetScope.USER)
    override val scope: StateFlow<PresetScope> = _scope.asStateFlow()

    /**
     * The listing for whichever scope is selected - what ProgramsScreen collects, unchanged.
     *
     * `Eagerly` rather than `WhileSubscribed`: the screen's collector is what it is, but the scan
     * driving this is launched from here and outlives the composition on purpose (see below), so a
     * state that reset itself when the screen went away would undo exactly that.
     */
    val index: StateFlow<PresetIndexState> = combine(_scope, _indexes) { scope, byScope ->
        byScope[scope] ?: PresetIndexState()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PresetIndexState())

    /**
     * The user listing specifically, whatever scope is selected.
     *
     * A flow rather than a getter, because the questions that cross scopes are asked *while
     * another scope is displayed* - "can this factory voice be copied, and to where?" - and a
     * plain read would be answered once and never revisited. Browsing the factory list while the
     * user scan is still running would then leave Copy hidden until something unrelated
     * recomposed the screen.
     */
    val userIndex: StateFlow<PresetIndexState> = _indexes
        .map { it[PresetScope.USER] ?: PresetIndexState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PresetIndexState())

    // **One job per scope, so that switching never throws a listing away.** A user scan is 93
    // seconds; cancelling it because somebody looked at the factory list for a moment and then
    // came back would restart it from nothing. Two listings running at once is safe and does not
    // starve either: `SysExExchange` serialises one request/reply at a time rather than holding
    // the bus for a whole flow, so a favorites read started mid-scan interleaves with it instead
    // of queueing behind it, and each reply is matched on its own echoed address.
    private val indexJobs = mutableMapOf<PresetScope, Job>()
    private var indexGeneration = 0

    /** Which instrument [_indexes] describes, so a *different* one still triggers a fresh scan. */
    private var indexedInstrument: String? = null

    /**
     * The last instrument actually browsed, **surviving a teardown** - which [indexedInstrument]
     * deliberately does not.
     *
     * These two look redundant and are not. `cancelIndex()` clears [indexedInstrument] on every
     * disconnect, and `MainActivity.onResume` force-reconnects on every return to the foreground,
     * so "is this the instrument we were just browsing?" cannot be asked of it: after switching
     * apps and coming back, the answer is always no. Keyed on that, the browser would drop out of
     * the factory or favorites listing every time the user glanced at something else - the same
     * churn `PresetIndexCache` exists to avoid, reintroduced one layer up.
     */
    private var lastBrowsedInstrument: String? = null

    private fun updateIndex(scope: PresetScope, transform: (PresetIndexState) -> PresetIndexState) {
        _indexes.update { byScope ->
            byScope + (scope to transform(byScope[scope] ?: PresetIndexState()))
        }
    }

    /** True where [scope]'s listing has finished or failed, so re-running it would buy nothing. */
    private fun isSettled(scope: PresetScope): Boolean =
        _indexes.value[scope]?.let { it.complete || it.error != null } == true

    /**
     * Switches which listing the browser shows, starting it if it has not been read yet.
     *
     * A scope that already finished is shown as it stands and costs nothing, which is the point of
     * keeping all three; one still in flight keeps filling.
     */
    override fun setScope(scope: PresetScope) {
        if (_scope.value == scope) return
        _scope.value = scope
        val key = instrument?.identity?.stableKey ?: return
        if (isSettled(scope) || indexJobs[scope]?.isActive == true) return
        launchIndex(key, scope)
    }


    /**
     * Starts the listing unless one is already running or finished for this instrument.
     *
     * Called from two places, and idempotent so that both are safe: ProgramsScreen on first
     * composition, and this class itself whenever a session begins. The second is what covers a
     * reconnect - the screen cannot notice one without collecting the session into its
     * composition, which would recompose it while disconnected.
     */
    fun startIndex() {
        val key = instrument?.identity?.stableKey ?: return
        // A different instrument invalidates all three listings, not just the one on screen, and
        // returns the browser to the user presets - which is where "the default is the user
        // presets" actually gets enforced. Deliberately keyed on the *instrument* rather than on
        // each session: `MainActivity.onResume` rebuilds the session on every return to the
        // foreground, so resetting per session would drop the user out of the factory or favorites
        // listing every time they switched apps.
        if (key != lastBrowsedInstrument) {
            _indexes.value = emptyMap()
            _scope.value = PresetScope.USER
        }
        lastBrowsedInstrument = key
        val scope = _scope.value
        if (key == indexedInstrument && (indexJobs[scope]?.isActive == true || isSettled(scope))) return
        launchIndex(key, scope)
    }

    /**
     * Re-collects the current listing, **keeping** the cache.
     *
     * What an edit needs: `refreshEdited` has already written the changed addresses through to
     * the cached index, so this re-reads from memory rather than from the instrument.
     */
    override fun reloadIndex(): Int? {
        val key = instrument?.identity?.stableKey ?: return null
        return launchIndex(key, _scope.value)
    }

    /**
     * Drops the cache and re-reads from the instrument - the pull-to-refresh gesture.
     *
     * Returns the generation it started, or null if nothing is connected - so a caller can tell
     * *its* refresh finishing from something else finishing; see [PresetIndexState.generation] for
     * why `complete` alone cannot.
     */
    fun refreshIndex(): Int? {
        invalidateIndex()
        return reloadIndex()
    }

    private fun launchIndex(key: String, scope: PresetScope): Int {
        indexJobs.remove(scope)?.cancel()
        indexedInstrument = key
        // One counter across all scopes. The screen compares the generation it started against the
        // one that finished, and a per-scope counter would let two scopes issue the same number -
        // at which point a refresh of one would look, to the screen, like its own refresh landing.
        val generation = ++indexGeneration
        updateIndex(scope) { PresetIndexState(generation = generation) }
        indexJobs[scope] = viewModelScope.launch {
            try {
                programIndex(scope).collect { update -> updateIndex(scope) { it.plus(update) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Listing $scope failed", e)
                updateIndex(scope) {
                    it.copy(error = e.message ?: str(R.string.listing_unknown_error), progress = null)
                }
            }
        }
        return generation
    }

    /**
     * Throws away the cached listing so the next collection re-reads the instrument.
     *
     * What the pull-to-refresh gesture and the "Re-read from instrument" menu item both call.
     *
     * **Only the user listing is cached** (see [CachingBrowser]), so this is a no-op for the other
     * two - and has to be. Pulling to refresh the favorites listing, which is exactly when someone
     * has just marked something on the panel, would otherwise drop the cached user listing as a
     * side effect and cost 93 seconds the next time they switched back to it.
     */
    fun invalidateIndex() {
        if (_scope.value != PresetScope.USER) return
        invalidateUserCache()
    }

    /**
     * Drops the cached user listing regardless of which scope is on screen.
     *
     * Separate from [invalidateIndex] because the two callers differ in what they know: the
     * gesture means "re-read what I am looking at", while a failed re-read after an edit means
     * "the cached user listing is now untrustworthy" - which is true whichever listing the edit
     * was made from.
     */
    private fun invalidateUserCache() {
        val instrument = instrument ?: return
        cacheKey(instrument)?.let { indexCache.invalidate(it) }
    }

    /**
     * Re-reads the slots an edit touched, so the cached listing matches the instrument.
     *
     * **Every address the operation touched, not just the obvious one.** A move and a swap each
     * change two slots, and refreshing only the destination would leave the source showing the
     * voice it no longer holds - which is indistinguishable, to the user, from the write having
     * silently failed.
     *
     * If a re-read fails, the whole cached index is dropped rather than left holding a value
     * nothing has confirmed. That costs a full listing on the next collection and is the right
     * trade: the alternative is showing a stale row with no way for anyone to know it is stale.
     *
     * **The re-read slot is also written into every listing already holding that address**, not
     * only into the cache. A favorited user voice sits in the user listing and the favorites one
     * at once; renaming it from either has to correct both, and the cache write-through only
     * reaches whichever is collected next. A listing that has already finished is not re-collected
     * - that is the point of keeping it - so without this it would keep showing the old name.
     */
    private suspend fun refreshEdited(vararg addresses: SlotAddress) {
        val browser = browser()
        for (address in addresses) {
            try {
                val slot = browser.refresh(address)
                _indexes.update { byScope -> byScope.mapValues { (_, state) -> state.replacing(slot) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't re-read $address after an edit; dropping the cached index", e)
                invalidateUserCache()
                return
            }
        }
    }

    /**
     * Every address within the connected instrument's bank/slot range, whether or not a preset is
     * actually stored there - used by ProgramsScreen to show the gaps a device that only reports
     * occupied slots leaves behind.
     */
    fun allSlots(scope: PresetScope = PresetScope.USER): List<PresetSlot> {
        val inst = instrument ?: return emptyList()
        val layout = inst.layout
        // A favorites listing has no address space of its own: it is a sparse set drawn from every
        // bank, so there is no address at which one is "missing". Returning the whole instrument
        // here would make "show empty slots" offer 1,633 placeholder rows for a list of eleven.
        if (scope == PresetScope.FAVORITES) return emptyList()
        val wantReadOnly = scope == PresetScope.FACTORY
        // Belt-and-suspenders on top of the protocol layer's own bound on a derived device's slot
        // count: a bank's capacity ultimately traces back to a value a connected instrument
        // reported, and this is the loop bound that value feeds into. Clamp rather than trust it
        // so one bad field can't turn into an attempt to materialize millions of strings.
        return layout.banks.flatMapIndexed { bank, spec ->
            if (spec.readOnly != wantReadOnly) return@flatMapIndexed emptyList()
            (0 until spec.slotCount.coerceIn(0, MAX_ITEMS_PER_BANK)).map { slot ->
                val address = SlotAddress(bank, slot)
                PresetSlot(
                    address = address,
                    displayId = layout.format.format(address),
                    bankLabel = spec.label,
                    name = null,
                )
            }
        }
    }

    /**
     * Bank indices the instrument refuses writes to, for the per-row gating of edits.
     *
     * Read off the layout rather than tracked beside it, so a bank cannot be read-only for the
     * purposes of [allSlots] and writable for the purposes of the Rename item.
     */
    fun readOnlyBanks(): Set<Int> =
        instrument?.layout?.banks?.withIndex()
            ?.filter { it.value.readOnly }?.map { it.index }?.toSet()
            ?: emptySet()

    /** The listings the connected instrument offers; a single entry means no selector is shown. */
    fun browsingScopes(): List<PresetScope> = connected()?.browser?.scopes ?: listOf(PresetScope.USER)

    /** The connected instrument's tagging facet, or null where it has none. */
    val tagger: PresetTagger? get() = connected()?.tagger

    /**
     * Whether these two row actions should be offered for [address].
     *
     * Asked of the facet rather than derived from [readOnlyBanks], because on a Motif XS the two
     * disagree: a factory voice can be favorited but not re-categorised.
     */
    fun canSetCategories(address: SlotAddress): Boolean =
        tagger?.canSetCategories(address) == true

    fun canSetFavorite(address: SlotAddress): Boolean =
        tagger?.favorites != null && tagger?.canSetFavorite(address) == true

    /**
     * Bank label -> what the index rail should draw for it.
     *
     * Keyed by the same label the rows carry, so the rail can render something narrow while
     * still jumping by the value the header index is keyed on.
     *
     * **Returns empty rather than throwing when nothing is connected**, as [allSlots] does and for
     * the same reason: both are read *during composition* by ProgramsScreen, which recomposes
     * across the reconnect `MainActivity.onResume()` causes. Throwing there takes the process down
     * while a recoverable reconnect is in flight. [current] stays strict for everything a user
     * action invokes, where "not connected" really is a bug.
     */
    fun bankRailLabels(): Map<String, String> =
        instrument?.layout?.banks?.associate { it.label to it.shortLabel } ?: emptyMap()

    override suspend fun selectProgram(slot: PresetSlot): String = instrumentMutex.withLock {
        val selector = requireFacet(current().selector, "load a preset")
        selector.select(slot.address)
        selector.confirmationFor(slot.displayId)
    }

    /**
     * Relocates a preset, picking the operation that matches the destination: an occupied target
     * is a two-way swap, an empty one a one-way move. An instrument with native operations rejects
     * either call used at the wrong kind of destination, so [targetIsEmpty] has to come from the
     * caller's own view of what is stored where - ProgramsScreen knows it from the index it has.
     */
    override suspend fun moveProgram(
        source: PresetSlot,
        target: PresetSlot,
        targetIsEmpty: Boolean,
    ): String = instrumentMutex.withLock {
        val editor = requireFacet(current().editor, "rearrange presets")
        if (targetIsEmpty) {
            editor.move(source.address, target.address)
            // Both ends: a move empties the source as surely as it fills the destination.
            refreshEdited(source.address, target.address)
            return@withLock str(R.string.result_moved, source.displayId, target.displayId)
        }
        editor.swap(source.address, target.address)
        refreshEdited(source.address, target.address)
        str(R.string.result_swapped, source.displayId, target.displayId)
    }

    override suspend fun renameProgram(slot: PresetSlot, newName: String): String = instrumentMutex.withLock {
        requireFacet(current().editor, "rename presets").rename(slot.address, newName)
        refreshEdited(slot.address)
        str(R.string.result_renamed, slot.displayId, newName)
    }

    /** What [slot] is filed under and whether it is a favorite, for the two tag dialogs. */
    override suspend fun presetTags(slot: PresetSlot): PresetTags = instrumentMutex.withLock {
        requireFacet(current().tagger, "categorise presets").read(slot.address)
    }

    /**
     * Files [slot] under the categories named by [under], or removes the favorite mark.
     *
     * **Drops the favorites listing rather than patching the row into it.** `refreshEdited` writes
     * a re-read slot into every listing that *already holds* that address, which is exactly right
     * for a rename and wrong here: favoriting a voice adds it to a listing it was absent from, and
     * `PresetIndexState.replacing` is a no-op for an absent address rather than an insertion.
     */
    override suspend fun setFavorite(slot: PresetSlot, under: Set<Int>): String = instrumentMutex.withLock {
        requireFacet(current().tagger, "favorite presets").setFavorite(slot.address, under)
        refreshEdited(slot.address)
        dropFavoritesListing()
        if (under.isEmpty()) str(R.string.result_favorite_cleared, slot.displayId)
        else str(R.string.result_favorite_set, slot.displayId)
    }

    /**
     * Forgets the favorites listing, and stops a scan still filling it.
     *
     * The job goes with the state: a scan left running would keep writing rows read *before* the
     * edit into a fresh state, and [setScope] would then show that half-stale listing as settled
     * rather than re-reading it. The next visit to the favorites listing starts a new scan.
     */
    private fun dropFavoritesListing() {
        indexJobs.remove(PresetScope.FAVORITES)?.cancel()
        _indexes.update { it - PresetScope.FAVORITES }
    }

    /** Replaces [slot]'s category assignments, nulls included. */
    override suspend fun setCategories(slot: PresetSlot, categories: List<CategoryRef?>): String =
        instrumentMutex.withLock {
            val tagger = requireFacet(current().tagger, "categorise presets")
            tagger.setCategories(slot.address, categories)
            refreshEdited(slot.address)
            // A favorite is filed under the voice's own categories, so changing one moves where
            // the instrument lists it - the same reason setFavorite drops that listing.
            dropFavoritesListing()
            val shown = categories.mapNotNull { ref -> ref?.let { tagger.taxonomy.label(it) } }
            if (shown.isEmpty()) str(R.string.result_categories_cleared, slot.displayId)
            else str(R.string.result_categories_set, slot.displayId, shown.joinToString(", "))
        }

    /**
     * Duplicates [source] into the empty slot [destination], leaving [source] untouched.
     *
     * Only [destination] needs re-reading: unlike [moveProgram], nothing about the source
     * changes on the instrument. The name in the returned message is the *instrument's*, not
     * [source]'s - a Nord appends a disambiguating suffix, so the only way to know what actually
     * got stored is what the editor's own `copyProgram` read back.
     */
    override suspend fun copyProgram(source: PresetSlot, destination: PresetSlot): String = instrumentMutex.withLock {
        val copiedName = requireFacet(current().editor, "copy presets")
            .copyProgram(source.address, destination.address)
        refreshEdited(destination.address)
        str(R.string.result_copied, source.displayId, destination.displayId, copiedName)
    }

    /**
     * Erases one preset. **Destructive and not undoable from this app.**
     *
     * The confirmation lives in the screen rather than here, so that a caller cannot get a
     * silent delete by using the view model directly - but nothing here can enforce that, which
     * is why the wording of the dialog matters as much as this method does.
     *
     * How much is destroyed varies by family and the message says so: a Nord empties the slot
     * with one command and can still reclaim it until its own cleanup runs, while a Pro-800 and
     * a Motif XS overwrite the slot with an initialised preset, which nothing can undo.
     */
    override suspend fun deleteProgram(slot: PresetSlot): String = instrumentMutex.withLock {
        requireFacet(current().editor, "delete presets").delete(slot.address)
        refreshEdited(slot.address)
        str(R.string.result_deleted, slot.displayId)
    }

    /** A filename stem for the shared report, e.g. "Nord Grand" -> "nord_grand". */
    fun suggestedReportFilename(): String =
        requireFacet(current().report, "describe itself").suggestedFilename()

    /**
     * A filename stem for **anything** shared about this instrument, facet or no facet.
     *
     * [suggestedReportFilename] belongs to the [DeviceReporter] and requires it. The regression
     * test does not: it is a debug action offered for every family, including one whose `report`
     * is null. A null facet means "cannot do this at all", so anything outside that facet has to
     * derive its own name.
     */
    val filenameStem: String
        get() = stemFor(current())

    /** What the share dialog says the report will do - the connected instrument's own wording. */
    val reportDescription: String
        get() = requireFacet(current().report, "describe itself").description

    suspend fun buildDeviceReport(progress: ((String) -> Unit)? = null): DeviceReportResult =
        instrumentMutex.withLock {
            requireFacet(current().report, "describe itself").buildReport(progress)
        }

    /**
     * Runs [RegressionTester] against the connected instrument - the debug menu's second entry.
     *
     * A wrapper and nothing more: the engine is in `core/` because it belongs to the facets rather
     * than to a screen or to this ViewModel, and everything it needs is state this class already
     * holds. The two confirmation callbacks are passed straight through from the screen, which is
     * the only layer that can put a question in front of the user.
     *
     * [instrumentMutex] is held for the whole run, confirmation callbacks included - so a resume
     * mid-test waits out a pending "confirm this real-slot mutation" dialog rather than closing
     * the instrument under it. A background/foreground cycle must not be able to answer that
     * question by accident.
     */
    suspend fun runRegressionTest(
        onConfirmSelect: suspend (String) -> Boolean,
        onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
        progress: (String) -> Unit,
    ): RegressionReport = instrumentMutex.withLock {
        progress(str(R.string.listing_reading_presets))
        // Read once, before anything is written, and hand the tester that snapshot: which slots
        // are free is the question the whole run is planned around, and re-answering it halfway
        // through - after the test's own copy has filled one - would plan against its own edits.
        val occupied = occupiedSlots()
        RegressionTester(
            instrument = current(),
            // The **writable** addresses, explicitly. The tester picks its sandbox slot as the
            // first free address it is handed and then writes to it; given the whole layout it
            // would pick PRE1's first slot, which the instrument refuses, and the run would fail
            // on its own setup.
            allSlots = { allSlots(PresetScope.USER).map { it.address } },
            occupiedSlots = { occupied },
            onConfirmSelect = onConfirmSelect,
            onConfirmRealSlotMutation = onConfirmRealSlotMutation,
            refreshEdited = { addresses -> refreshEdited(*addresses.toTypedArray()) },
        ).run(progress)
    }

    /**
     * Whether this instrument can have its shipped factory names checked against itself.
     *
     * Motif XS only, and only where there are read-only banks to check - which is why it asks the
     * instrument rather than the family name.
     */
    val canVerifyFactoryNames: Boolean
        get() = connected() is MotifXsInstrument &&
            connected()?.layout?.banks?.any { it.readOnly } == true

    /** The read-only banks, as (bank index, display label), for the debug screen's picker. */
    fun factoryBanks(): List<Pair<Int, String>> =
        connected()?.layout?.banks?.withIndex()
            ?.filter { it.value.readOnly }
            ?.map { it.index to it.value.label }
            ?: emptyList()

    /**
     * Reads one factory bank off the instrument and compares it to the names the app ships.
     *
     * **The only way to check the shipped table against real hardware.** Those 1,217 names are
     * transcribed from the manufacturer's data list rather than read from any instrument, so a
     * wrong one is invisible: it renders exactly like a right one. Reading a bank costs about two
     * minutes and is not something to do on every connect, which is the whole reason the table
     * exists - but doing it once, deliberately, is what turns "probably correct" into "checked".
     *
     * Read-only throughout: it issues dump requests and writes nothing.
     */
    suspend fun verifyFactoryNames(
        bank: Int,
        progress: (String) -> Unit,
    ): List<String> = instrumentMutex.withLock {
        val instrument = current() as? MotifXsInstrument
            ?: throw InstrumentException.NotSupported("verify factory voice names")
        val spec = instrument.layout.banks.getOrNull(bank)
            ?: error("No bank $bank")
        require(spec.readOnly) { "${spec.label} is not a factory bank." }

        val shipped = _indexes.value[PresetScope.FACTORY]?.slots
            ?: instrument.browser.index(PresetScope.FACTORY).toList()
                .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        val expected = shipped.filter { it.address.bank == bank }.associateBy { it.address.slot }

        val mismatches = mutableListOf<String>()
        instrument.indexBank(bank).collect { update ->
            when (update) {
                is IndexUpdate.Progress -> progress(update.label)
                is IndexUpdate.Failed ->
                    mismatches += "${update.address.slot + 1}: unreadable (${update.reason})"
                is IndexUpdate.Slots -> update.slots.forEach { read ->
                    val shippedName = expected[read.address.slot]?.name
                    if (read.name != shippedName) {
                        mismatches += "${read.displayId}: instrument says " +
                            "\"${read.name ?: "(empty)"}\", app ships \"${shippedName ?: "(none)"}\""
                    }
                }
                IndexUpdate.Complete -> Unit
            }
        }
        mismatches
    }

    /**
     * Which addresses actually hold a preset, read through the same cached browser the preset
     * screen uses - the debug screen has no listing of its own to ask.
     */
    private suspend fun occupiedSlots(): Set<SlotAddress> =
        browser().index()
            .filterIsInstance<IndexUpdate.Slots>()
            .toList()
            .flatMap { it.slots }
            .filterNot { it.isEmpty }
            .map { it.address }
            .toSet()
    /** Which edit operations the connected instrument offers at all - drives the drag handle and
     * the Rename button. */
    val supportedEdits: Set<EditOp> get() = connected()?.editor?.supported ?: emptySet()

    /** True where an edit is composed host-side from reads and writes rather than being one
     * device command. The delete dialog words its warning differently for the two: a composed
     * erase overwrites the slot with an initialised preset, a native one empties it. */
    fun isEmulatedEdit(op: EditOp): Boolean = connected()?.editor?.isEmulated(op) ?: false

    /** The longest preset name the connected instrument will store, or null if unlimited/unknown -
     * used to cap the rename field rather than letting the instrument truncate silently. */
    val maxPresetNameLength: Int?
        get() = connected()?.editor?.maxNameLength

    /**
     * Whether tapping a preset can actually load it.
     *
     * A family may browse and nothing else. Without this the row would still be clickable and
     * answer with "This instrument cannot load a preset" - an error message standing in for a UI
     * decision.
     */
    val canSelect: Boolean get() = connected()?.selector != null

    /**
     * The connected instrument's own name, for the app bar title.
     *
     * Read off the state rather than through [current] so it cannot throw while the screen is
     * being torn down mid-disconnect - a title is not worth a crash.
     */
    val instrumentName: String?
        get() = (state.value as? ConnectionState.Connected)?.instrument?.identity?.name

    /**
     * Whether the connected instrument can describe itself.
     *
     * Read off [connectedOrPending] rather than [connected], because the connect screen offers the
     * report from the untested-firmware gate, where the session is
     * [ConnectionState.AdvisoryWarning] and not yet [ConnectionState.Connected].
     */
    val hasReport: Boolean get() = connectedOrPending()?.report != null

    /**
     * True once connected to something the catalog does not recognise - see [DeviceProfile.unknown]
     * and [confirmUnknownDevice].
     *
     * What the preset screen's "Share device details" button is gated on. Read off the identity
     * rather than off a Nord profile, since nothing above the instrument layer knows what family
     * is connected.
     */
    val isUnknownDevice: Boolean get() = connected()?.identity?.descriptorId == DeviceProfile.UNKNOWN_ID


    /**
     * Preset listings, kept for as long as this ViewModel lives.
     *
     * A field here rather than anywhere nearer the hardware because of what it has to survive:
     * [forceReconnect] runs on every return to the foreground and replaces [instrument]
     * wholesale. Without it a Motif XS re-reads all 416 user voices - about 93 seconds - every
     * time the user switches apps and comes back.
     *
     * Dies with the process, deliberately. Persistence was considered and declined; the reasoning
     * is in `docs/ARCHITECTURE.md`, under "Caching".
     */
    private val indexCache = PresetIndexCache()

    /** Resolves one of this app's own strings. The instrument layer's messages are deliberately
     * not resources - see the header of `res/values/strings.xml`. */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /**
     * The instrument as the *published state* has it, or null.
     *
     * **What every property a screen reads during composition goes through.** Those are re-read
     * across the reconnect `MainActivity.onResume()` causes, so a throwing accessor there takes the
     * process down while a perfectly recoverable reconnect is in flight - which is exactly what
     * `advisory` and `instrumentName` already read off the state to avoid.
     *
     * [current] stays strict for everything a user action invokes, where "not connected" is a bug.
     */
    private fun connected(): Instrument? = (state.value as? ConnectionState.Connected)?.instrument

    /**
     * [connected], or the instrument held behind the advisory gate: the session exists and its
     * facets work there too, the user just has not said "continue" yet.
     */
    private fun connectedOrPending(): Instrument? = when (val s = state.value) {
        is ConnectionState.Connected -> s.instrument
        is ConnectionState.AdvisoryWarning -> s.instrument
        else -> null
    }

    private fun current(): Instrument = instrument ?: error("Not connected to an instrument")

    /**
     * Unwraps a facet the caller has already decided is present.
     *
     * Reaching this error means a screen offered an action the connected instrument declared it
     * cannot do - a UI gating bug, not a device problem, which is why it reads as one.
     */
    private fun <T : Any> requireFacet(facet: T?, what: String): T =
        facet ?: error("This instrument cannot $what.")
}
