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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * One row in the device picker. Two kinds, because a recognised instrument is not necessarily a
 * USB one (a Pro-800 is matched on the MIDI bus) and the two open by different routes: a [Known]
 * through the registry with its own descriptor, an [Unknown] through the guess
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
    /** Opening the device, which on the USB bus may mean waiting for the permission dialog. [bus] keeps the screen from claiming "USB" for a MIDI port. */
    data class Opening(val displayName: String, val bus: Bus) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    /**
     * The user answered the system's USB permission dialog with "Deny". Its own state rather than
     * an [Error], because closing that dialog is itself a resume, and a resume with nothing
     * connected rescans ([InstrumentViewModel.shouldRebuildOnResume]) - which would put the dialog
     * straight back up. Only the Retry button asks again.
     */
    data class PermissionDenied(val displayName: String) : ConnectionState()

    /**
     * The instrument is attached but not listening, and the fix is at its own front panel.
     * Distinct from [Error] because the user is expected to go and change something before
     * retrying, so the screen shows [steps] as a list; the retry rescans from scratch, since the
     * instrument may have re-enumerated.
     */
    data class NeedsManualSetting(
        val message: String,
        val steps: List<String>,
        val alsoCheck: String?,
    ) : ConnectionState()
    data class Connected(val instrument: Instrument) : ConnectionState()

    /** Nothing is attached at all. [supportedNames] is read from the catalog, so a new supported model needs no screen change. */
    data class NothingFound(val supportedNames: List<String>) : ConnectionState()

    /**
     * Everything attached, recognised or not, with the recognised ones first. Unfiltered: a device
     * missing from the list is indistinguishable from one that is not plugged in, so everything
     * stays selectable and picking something unusable explains itself through [message].
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
     * Connected, and the instrument raised an [Instrument.advisory]: shown before the user starts
     * using it, with the choice to continue or disconnect. Raised after the handshake, unlike
     * [UnknownDeviceWarning], since only the device can answer it; the instrument is carried here
     * so continuing costs no second connect.
     */
    data class AdvisoryWarning(val instrument: Instrument, val message: String) : ConnectionState()

    /**
     * The instrument this session was using was physically unplugged. Its own state rather than
     * [Disconnected], from which ConnectScreen auto-connects - which would latch onto a different
     * instrument that happens to still be attached. Nothing reconnects until the user asks, with
     * one exception: the same instrument being plugged back in, which
     * [InstrumentViewModel.onUsbDeviceAttached] matches on [physicalKey].
     */
    data class DeviceLost(val instrumentName: String, val physicalKey: String?) : ConnectionState()
}

/**
 * Holds the single connected [Instrument] for the app's lifetime and exposes the operations the
 * screens drive. Nothing here knows what family is connected: every action goes through a facet,
 * and a null facet is an operation the screens do not offer, so adding a family adds no `when`.
 */
class InstrumentViewModel(application: Application, savedStateHandle: SavedStateHandle) :
    AndroidViewModel(application), ProgramsOperations {
    private val connectionManager = UsbConnectionManager(application)

    /** Settings screen reads and writes this directly; [performConnect] reads it fresh on every
     * scan, so a change takes effect on the next connect attempt without restarting the app. */
    val connectionPreferences = ConnectionPreferences(application)

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

    /** The buses to look on, in priority order: USB host first, since a Nord's MIDI interface cannot manage presets. */
    private val discoveries: List<DeviceDiscovery> = listOf(
        UsbHostDiscovery(connectionManager),
        MidiDiscovery(application),
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var instrument: Instrument? = null

    /**
     * The physical key of whatever [instrument] is connected to, or null (nothing connected, or a
     * bus with no backing `UsbDevice`). Captured at connect time, so a later detach can be matched
     * to this session's device - see [handleUsbDetach].
     */
    private var connectedPhysicalKey: String? = null

    /**
     * The handle of the device this session runs over - a USB device path or a MIDI device id -
     * which tells one unit of a model from another for the listing cache ([CacheKey.physicalDevice]).
     * It does not tell a replug from a resume, which is why a detach drops the listing itself.
     */
    private var connectedDeviceHandle: String? = null


    /**
     * The physical key of whatever device a connect attempt is mid-flight on, or null. Set before
     * entering [ConnectionState.Opening] and cleared on every way out, so [handleUsbDetach] can
     * fail an attempt whose device is unplugged before it finishes.
     */
    private var openingPhysicalKey: String? = null

    /**
     * The [UsbDevice] behind the most recent accepted [confirmUnknownDevice], so
     * [reconnectRememberedUnknownDeviceOrOffer] can restore it without the warning screen when
     * [connect] finds nothing in the catalog - most often a [forceReconnect] on resume. Cleared by
     * [disconnect] and by a real detach, not by [teardownCurrentInstrument], which
     * [forceReconnect] runs before reconnecting.
     */
    private var lastConfirmedUnknownDevice: UsbDevice? = null

    /**
     * Serialises every read-then-write against [instrument] with [forceReconnect]'s teardown, so
     * a resume that rebuilds the session waits for an edit in flight rather than closing the
     * transport under it. Held for the whole body of each operation, since the race is between
     * the write and the re-read that confirms it.
     *
     * The index scan is not routed through this: [cancelIndex] cancels it instead, rather than
     * making a resume wait out a 93-second Motif XS listing.
     */
    private val instrumentMutex = Mutex()

    private var connectJob: Job? = null

    // Set by disconnect(showPicker = true) - the user backed out of a session on purpose and
    // asked to choose again, so the next connect() must not silently reconnect a lone device.
    private var forcePickerOnNextConnect = false

    init {
        // Guarded, unlike this class's other fire-and-forget launches: viewModelScope installs no
        // CoroutineExceptionHandler, and registerReceiver() can fail for reasons outside this
        // app's control. Losing detach detection is a small regression; crashing at startup is not.
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
     * Runs an edit on [viewModelScope] rather than on the caller's composition scope, so
     * navigating away cannot abandon it mid-write: a `rememberCoroutineScope()` dies with the
     * screen, and a Motif XS left mid-sequence sits on "receiving midi bulk data" until it is
     * completed or power-cycled. `SysExExchange.exchangeAfterAll` is the other half of that
     * guarantee.
     */
    override fun launchEdit(block: suspend () -> Unit): Job = viewModelScope.launch { block() }

    /**
     * Reacts to a physical USB detach in whichever of three shapes the session is in: closes an
     * active session using that device ([ConnectionState.DeviceLost]); fails a connect attempt
     * waiting on it ([ConnectionState.Opening], most often on a permission dialog that can now
     * never arrive); or drops the matching row from [ConnectionState.DeviceSelection].
     *
     * Ignores everything else: every device Android has seen fires this broadcast on unplug.
     */
    private fun handleUsbDetach(device: UsbDevice) {
        val detachedKey = device.physicalKey()
        if (detachedKey == connectedPhysicalKey) {
            val name = connected()?.identity?.name
                ?: (state.value as? ConnectionState.AdvisoryWarning)?.instrument?.identity?.name
                ?: device.displayLabel()
            // A connect attempt that overlaps this (a forceReconnect() from onResume) must not
            // overwrite DeviceLost with a stale Connected or Error.
            connectJob?.cancel()
            lastConfirmedUnknownDevice = null
            viewModelScope.launch {
                // The cached listing goes with the device: once the instrument has left the bus
                // its contents may change before it is next seen, and Android hands a replug the
                // same USB device path, so [CacheKey.physicalDevice] does not notice. Before the
                // teardown, which clears the handle the key is built from.
                invalidateUserCache()
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
     * Reacts to a USB device being plugged in, delivered by `MainActivity.onNewIntent`. Only where
     * the session is waiting for a device: every "nothing usable yet" state rescans, the picker
     * rescans without auto-picking, and [ConnectionState.DeviceLost] reconnects only for the
     * device that went away. A session in flight or established is left alone.
     *
     * Permission is never asked here: Android grants it to the app it launches for the attach.
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
     * Runs one connection attempt, unless another is in flight: the single guard, launch and
     * catch for auto-connect, a picked row, an unrecognised device and demo mode.
     * `CancellationException` is rethrown, so backing out does not land on the error screen.
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
     * Scans every bus, then connects. The results are deduplicated by physical device: a Nord
     * exposes a class-compliant USB-MIDI interface alongside the vendor one, and USB goes first,
     * so it is claimed through the interface that can browse presets.
     */
    fun connect() {
        launchConnect { performConnect(consumeForcePicker()) }
    }

    /** Reads and clears [forcePickerOnNextConnect] in one step, so every caller that folds it
     * into a connect attempt - [connect] and [forceReconnect] alike - consumes it the same way. */
    private fun consumeForcePicker(): Boolean =
        forcePickerOnNextConnect.also { forcePickerOnNextConnect = false }

    /**
     * The scan-then-connect body [connect] and [forceReconnect] share, so [forceReconnect] runs
     * it inside its own [launchConnect] job rather than a second, untracked one.
     *
     * The MIDI scan is skipped where auto-connect already has its answer from USB alone:
     * `MidiDiscovery.scan` opens every port and waits up to 600 ms per port for a probe reply,
     * and [mergeCandidates] prefers a USB candidate for the same physical device anyway. A
     * MIDI-only family (the Pro-800) is only ever found by running that scan, so it runs whenever
     * the picker is forced, auto-connect is off, or USB found nothing recognised.
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
     * Runs [block], releasing what [close] names if it throws, cancellation included: a transport
     * opened for a connect attempt is owned by nobody until the instrument built on it becomes
     * the session, and a MIDI port left open cannot be probed by the next scan.
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
     * Every attached device as picker rows, recognised ones first, deduplicated by
     * [Candidate.physicalKey] so a recognised instrument does not also appear as an unrecognised
     * one.
     */
    private fun pickerEntries(known: List<Candidate>): List<PickerEntry> {
        val claimed = known.map { it.physicalKey }.toSet()
        return known.map { PickerEntry.Known(it) } +
            connectionManager.findAllDevices()
                .filter { it.physicalKey() !in claimed }
                .map { PickerEntry.Unknown(it) }
    }

    /**
     * Routes a picked row to whichever path can open it: a recognised instrument goes through the
     * registry with its own descriptor, not through the guess `UnknownDevicePolicy` gates.
     */
    fun selectEntry(entry: PickerEntry) {
        when (entry) {
            is PickerEntry.Known -> launchConnect { connectCandidate(entry.candidate) }
            is PickerEntry.Unknown -> selectUnknownDevice(entry.device)
        }
    }

    /**
     * Nothing in the catalog matched: reconnects [lastConfirmedUnknownDevice] if it is still
     * attached, without the warning the user already accepted this session, so a
     * [forceReconnect] (returning from the share sheet, say) restores it silently. Otherwise
     * falls through to [offerUnknownDeviceOrFail].
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

    /** No supported instrument was found: offers a device picker if anything else is attached, otherwise "nothing found". */
    private fun offerUnknownDeviceOrFail() {
        val allDevices = connectionManager.findAllDevices()
        if (allDevices.isEmpty()) {
            // Every instrument the app can recognize, on both buses, since both were scanned.
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
     * Vendor ids for which guessing at an unrecognised device is defensible - see
     * [UnknownDevicePolicy], which is where the rule lives. Read from the catalog, so adding a
     * Nord model needs no change here; fails closed on an unreadable catalog.
     */
    private fun unknownCapableVendorIds(): Set<Int> =
        UnknownDevicePolicy.vendorIdsAllowingAGuess(InstrumentRegistry.allDescriptors(getApplication()))

    /** Backs out of [ConnectionState.UnknownDeviceWarning] back to the device picker (or the
     * plain error state, if USB devices were unplugged in the meantime). */
    fun cancelUnknownDeviceSelection() {
        offerUnknownDeviceOrFail()
    }

    /**
     * User accepted the warning in [ConnectionState.UnknownDeviceWarning]: proceeds as [connect]
     * does, but builds from a [DeviceProfile.unknown] rather than a catalog entry.
     */
    fun confirmUnknownDevice(usbDevice: UsbDevice) {
        // Checked again here: this is the call that claims the interface and starts sending a
        // vendor protocol, whatever route got here.
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
            // An unrecognized device starts on DeviceProfile.unknown()'s generous guessed bounds;
            // its own Program category announces the real ones. Best-effort, since a device with
            // no Program category is exactly what this path exists to survive.
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
            // Best-effort: the guessed bounds still work, just generously.
            Log.w(TAG, "Couldn't derive a bank layout for $displayName", e)
        }
    }

    /**
     * Connects to a fictitious instrument instead of real hardware ([DemoInstrument]): no USB
     * permission, discovery or hardware I/O, landing in the same [ConnectionState.Connected].
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
     * Whether returning to the foreground should rebuild the session: asked of the connected
     * instrument, which asks its transport (`Transport.rebuildOnResume`), so a USB session is
     * rebuilt and a MIDI or demo one is not.
     *
     * True while nothing is connected, which is how an instrument plugged in while the app was
     * away gets picked up. False on [ConnectionState.DeviceLost], which exists so nothing
     * reconnects until the user asks; and false on [ConnectionState.Opening] and
     * [ConnectionState.PermissionDenied], where dismissing the permission dialog is itself the
     * resume and a rescan would put it straight back up.
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

    /**
     * User declined the advisory: closes the session and offers the picker. Not a plain
     * [disconnect] - ConnectScreen scans only on its first composition and is already on screen,
     * so `Disconnected` would show a bare "Not connected". The picker is forced, since
     * auto-connect would reopen the same instrument and raise the same advisory.
     */
    fun declineAdvisory() {
        lastConfirmedUnknownDevice = null
        launchConnect {
            teardownCurrentInstrument()
            _state.value = ConnectionState.Disconnected
            performConnect(forcePicker = true)
        }
    }

    /** The connected instrument's advisory, or null: the banner that keeps the warning visible after [confirmAdvisory]. */
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
     * Tears down whatever connection exists and reconnects from scratch, on every return to the
     * foreground: unrelated USB bus activity while backgrounded can leave the endpoints erroring
     * on every subsequent transfer, with no way to detect or repair that specifically.
     *
     * Runs through [launchConnect], so this job lands in [connectJob] like every other attempt -
     * the single-flight guard, the error handling and [handleUsbDetach]'s cancel all depend on it.
     */
    fun forceReconnect() {
        launchConnect {
            teardownCurrentInstrument()
            _state.value = ConnectionState.Disconnected
            performConnect(consumeForcePicker())
        }
    }

    /**
     * Releases the connection when the ViewModel goes away with the app.
     *
     * **Never under an edit still in flight.** `viewModelScope` is cancelled before this runs,
     * but a write's `NonCancellable` section (a Motif XS block sequence, its write-plus-commit)
     * keeps going, holding [instrumentMutex] until it is done; closing the transport under it
     * would fail the write part way - the one thing those sections exist to prevent. So the
     * close is taken through the same mutex: at once when it is free, otherwise from a
     * coroutine of its own that waits for the edit to let go. Nothing here sends protocol
     * messages; every operation cleans up whatever lock it caused before returning.
     */
    override fun onCleared() {
        val target = instrument ?: return
        instrument = null
        if (instrumentMutex.tryLock()) {
            try {
                closeQuietly(target)
            } finally {
                instrumentMutex.unlock()
            }
            return
        }
        Log.i(TAG, "An edit is still in flight; the connection is released once it completes")
        CoroutineScope(Dispatchers.Main.immediate).launch {
            instrumentMutex.withLock { closeQuietly(target) }
        }
    }

    private suspend fun teardownCurrentInstrument() {
        // Stop the listing before closing what it reads from: a scan runs in viewModelScope, so
        // it would otherwise outlive this call and fail every remaining slot against a shut
        // handle.
        cancelIndex()
        // Waits for an edit, report or regression run in flight - see [instrumentMutex].
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
        // No fresh PresetIndexState: emitting one would recompose ProgramsScreen at the moment
        // there is no instrument to derive its rail and placeholder rows from. [launchIndex]
        // resets the state when the next scan starts.
    }

    /**
     * Stops a running scan when the app backgrounds, called from `MainActivity.onPause` (not from
     * a rotation, which the activity filters out). A no-op where [shouldRebuildOnResume] is
     * false, since nothing will be torn down on the way back; where it is true the resume
     * discards the scan anyway, and on a Motif XS its display would sit on "dump in progress" for
     * up to 93 s with the phone locked.
     */
    fun cancelScanOnBackground() {
        if (shouldRebuildOnResume) cancelIndex()
    }

    private fun closeQuietly(target: Instrument?) {
        try {
            target?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing the connection did not complete cleanly", e)
        }
    }

    // ---- Actions, each routed through a facet ----

    /** The program index as it arrives: a raw [Flow], since on a Pro-800 it is 400 sequential dumps. */
    fun programIndex(scope: PresetScope): Flow<IndexUpdate> = browser().index(scope)

    /**
     * The connected instrument's browser, wrapped so a listing can come from memory. Built per
     * call: [CachingBrowser] carries no state, and [indexCache] is a field of this ViewModel, so
     * it outlives the instrument a reconnect throws away. Demo mode is not wrapped - its library
     * is already in memory.
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

    // ---- The preset index, collected here rather than in the composition, so a rotation does
    // not restart a 93-second scan. ----
    //
    // Each scope keeps its own state: "Copy to..." has to know which user slots are free while
    // the factory listing is on screen, and an edit made from the favorites listing changes a row
    // the user listing also holds.
    private val _indexes = MutableStateFlow<Map<PresetScope, PresetIndexState>>(emptyMap())

    private val _scope = MutableStateFlow(PresetScope.USER)
    override val scope: StateFlow<PresetScope> = _scope.asStateFlow()

    /**
     * The listing for whichever scope is selected. `Eagerly` rather than `WhileSubscribed`: the
     * scan driving it outlives the composition, so a state that reset itself when the screen went
     * away would undo that.
     */
    val index: StateFlow<PresetIndexState> = combine(_scope, _indexes) { scope, byScope ->
        byScope[scope] ?: PresetIndexState()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PresetIndexState())

    /**
     * The user listing specifically, whatever scope is selected. A flow rather than a getter,
     * since "can this factory voice be copied, and to where?" is asked while another scope is
     * displayed and a plain read would be answered once.
     */
    val userIndex: StateFlow<PresetIndexState> = _indexes
        .map { it[PresetScope.USER] ?: PresetIndexState() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PresetIndexState())

    // One job per scope, so switching never throws a 93-second user scan away. Two listings at
    // once is safe: `SysExExchange` serialises one request/reply at a time, so a favorites read
    // started mid-scan interleaves with it and each reply is matched on its own echoed address.
    private val indexJobs = mutableMapOf<PresetScope, Job>()
    private var indexGeneration = 0

    /** Which instrument [_indexes] describes, so a *different* one still triggers a fresh scan. */
    private var indexedInstrument: String? = null

    /**
     * The last instrument actually browsed, surviving a teardown, which [indexedInstrument] does
     * not: `cancelIndex()` clears that one on every disconnect, and a resume force-reconnects, so
     * keyed on it the browser would drop out of the factory or favorites listing every time the
     * user switched apps.
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

    /** Switches which listing the browser shows, starting it if it has not been read yet; a finished scope costs nothing. */
    override fun setScope(scope: PresetScope) {
        if (_scope.value == scope) return
        _scope.value = scope
        val key = instrument?.identity?.stableKey ?: return
        if (isSettled(scope) || indexJobs[scope]?.isActive == true) return
        launchIndex(key, scope)
    }


    /**
     * Starts the listing unless one is running or finished for this instrument. Idempotent, since
     * both callers are safe: ProgramsScreen on first composition, and this class whenever a
     * session begins, which is what covers a reconnect.
     */
    fun startIndex() {
        val key = instrument?.identity?.stableKey ?: return
        // A different instrument invalidates all three listings and returns the browser to the
        // user presets. Keyed on the instrument rather than the session, which a resume rebuilds.
        if (key != lastBrowsedInstrument) {
            _indexes.value = emptyMap()
            _scope.value = PresetScope.USER
        }
        lastBrowsedInstrument = key
        val scope = _scope.value
        if (key == indexedInstrument && (indexJobs[scope]?.isActive == true || isSettled(scope))) return
        launchIndex(key, scope)
    }

    /** Re-collects the current listing, keeping the cache: `refreshEdited` has already written the changed addresses through. */
    override fun reloadIndex(): Int? {
        val key = instrument?.identity?.stableKey ?: return null
        return launchIndex(key, _scope.value)
    }

    /**
     * Drops the cache and re-reads from the instrument - the pull-to-refresh gesture. Returns the
     * generation it started, so a caller can tell its own refresh finishing from another's; see
     * [PresetIndexState.generation].
     */
    fun refreshIndex(): Int? {
        invalidateIndex()
        return reloadIndex()
    }

    private fun launchIndex(key: String, scope: PresetScope): Int {
        indexJobs.remove(scope)?.cancel()
        indexedInstrument = key
        // One counter across all scopes: a per-scope counter would let two scopes issue the same
        // number, and a refresh of one would look like the screen's own refresh landing.
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
     * Throws away the cached listing so the next collection re-reads the instrument - what
     * pull-to-refresh calls. Only the user listing is cached (see [CachingBrowser]), so this is a
     * no-op for the other two: refreshing the favorites listing must not cost 93 seconds the next
     * time the user listing is shown.
     */
    fun invalidateIndex() {
        if (_scope.value != PresetScope.USER) return
        invalidateUserCache()
    }

    /**
     * Drops the cached user listing whichever scope is on screen. Separate from [invalidateIndex]:
     * a failed re-read after an edit makes the cached user listing untrustworthy whichever
     * listing the edit was made from.
     */
    private fun invalidateUserCache() {
        val instrument = instrument ?: return
        cacheKey(instrument)?.let { indexCache.invalidate(it) }
    }

    /**
     * Re-reads the slots an edit touched, so the cached listing matches the instrument - every
     * address it touched, since a move and a swap each change two. A failed re-read drops the
     * whole cached index rather than leaving a row nothing has confirmed.
     *
     * The re-read slot is written into every listing already holding that address, not only into
     * the cache: a favorited user voice sits in two listings at once, and a finished listing is
     * never re-collected.
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
        // Clamped on top of the protocol layer's own bound: a bank's capacity traces back to a
        // value the instrument reported, and this is the loop it feeds.
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

    /** Bank indices the instrument refuses writes to, read off the layout so [allSlots] and the row menu cannot disagree. */
    fun readOnlyBanks(): Set<Int> =
        instrument?.layout?.banks?.withIndex()
            ?.filter { it.value.readOnly }?.map { it.index }?.toSet()
            ?: emptySet()

    /** The listings the connected instrument offers; a single entry means no selector is shown. */
    fun browsingScopes(): List<PresetScope> = connected()?.browser?.scopes ?: listOf(PresetScope.USER)

    /** The connected instrument's tagging facet, or null where it has none. */
    val tagger: PresetTagger? get() = connected()?.tagger

    /** Asked of the facet rather than derived from [readOnlyBanks]: on a Motif XS a factory voice can be favorited but not re-categorised. */
    fun canSetCategories(address: SlotAddress): Boolean =
        tagger?.canSetCategories(address) == true

    fun canSetFavorite(address: SlotAddress): Boolean =
        tagger?.favorites != null && tagger?.canSetFavorite(address) == true

    /**
     * Bank label -> what the index rail should draw for it, keyed by the label the rows carry so
     * the rail can render something narrow and still jump by the header's value. Empty rather
     * than throwing while nothing is connected, as [allSlots] is: both are read during
     * composition, across the reconnect a resume causes.
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
     * is a swap, an empty one a move. An instrument with native operations rejects either call at
     * the wrong destination, so [targetIsEmpty] comes from the caller's own view of the listing.
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
     * Files [slot] under the categories named by [under], or removes the favorite mark. Drops the
     * favorites listing rather than patching the row in: favoriting adds a voice to a listing it
     * was absent from, and `PresetIndexState.replacing` is a no-op for an absent address.
     */
    override suspend fun setFavorite(slot: PresetSlot, under: Set<Int>): String = instrumentMutex.withLock {
        requireFacet(current().tagger, "favorite presets").setFavorite(slot.address, under)
        refreshEdited(slot.address)
        dropFavoritesListing()
        if (under.isEmpty()) str(R.string.result_favorite_cleared, slot.displayId)
        else str(R.string.result_favorite_set, slot.displayId)
    }

    /**
     * Forgets the favorites listing and stops a scan still filling it: a scan left running would
     * write rows read before the edit into the fresh state, which [setScope] would then show as
     * settled.
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
     * Duplicates [source] into the empty slot [destination]. Only [destination] needs re-reading,
     * and the name in the message is the instrument's: a Nord appends a disambiguating suffix.
     */
    override suspend fun copyProgram(source: PresetSlot, destination: PresetSlot): String = instrumentMutex.withLock {
        val copiedName = requireFacet(current().editor, "copy presets")
            .copyProgram(source.address, destination.address)
        refreshEdited(destination.address)
        str(R.string.result_copied, source.displayId, destination.displayId, copiedName)
    }

    /**
     * Erases one preset - destructive and not undoable from this app. The confirmation lives in
     * the screen. How much is destroyed varies by family: a Nord empties the slot with one
     * command and can reclaim it until its own cleanup runs, while a Pro-800 and a Motif XS
     * overwrite it with an initialised preset.
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
     * A filename stem for anything shared about this instrument, facet or no facet:
     * [suggestedReportFilename] requires the [DeviceReporter], and the regression test is offered
     * for every family, including one whose `report` is null.
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
     * A wrapper: the engine is in `core/` because it belongs to the facets, and the confirmation
     * callbacks come from the screen, the only layer that can ask the user.
     *
     * [instrumentMutex] is held for the whole run, callbacks included, so a resume mid-test waits
     * out a pending dialog rather than closing the instrument under it.
     */
    suspend fun runRegressionTest(
        onConfirmSelect: suspend (String) -> Boolean,
        onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
        progress: (String) -> Unit,
    ): RegressionReport = instrumentMutex.withLock {
        progress(str(R.string.listing_reading_presets))
        // Read once, before anything is written: which slots are free is what the run is planned
        // around, and re-answering it halfway through would plan against the test's own edits.
        val occupied = occupiedSlots()
        RegressionTester(
            instrument = current(),
            // The writable addresses: the tester writes to the first free one it is handed, and
            // given the whole layout that would be PRE1's first slot.
            allSlots = { allSlots(PresetScope.USER).map { it.address } },
            occupiedSlots = { occupied },
            onConfirmSelect = onConfirmSelect,
            onConfirmRealSlotMutation = onConfirmRealSlotMutation,
            refreshEdited = { addresses -> refreshEdited(*addresses.toTypedArray()) },
        ).run(progress)
    }

    /** Whether this instrument can have its shipped factory names checked against itself: a Motif XS with read-only banks. */
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
     * Reads one factory bank off the instrument and compares it to the names the app ships - the
     * only way to check the shipped table against real hardware, since a wrong transcribed name
     * renders exactly like a right one. About two minutes per bank. Read-only: it issues dump
     * requests and writes nothing.
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

    /** Which addresses hold a preset, through the same cached browser the preset screen uses. */
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

    /** True where an edit is composed host-side rather than being one device command; the delete dialog words its warning from this. */
    fun isEmulatedEdit(op: EditOp): Boolean = connected()?.editor?.isEmulated(op) ?: false

    /** The longest preset name the connected instrument will store, or null if unlimited/unknown -
     * used to cap the rename field rather than letting the instrument truncate silently. */
    val maxPresetNameLength: Int?
        get() = connected()?.editor?.maxNameLength

    /** Whether tapping a preset can load it: a family may browse and nothing else, and the row should not be clickable there. */
    val canSelect: Boolean get() = connected()?.selector != null

    /** The connected instrument's own name, for the app bar title; read off the state so it cannot throw mid-disconnect. */
    val instrumentName: String?
        get() = (state.value as? ConnectionState.Connected)?.instrument?.identity?.name

    /**
     * Whether the connected instrument can describe itself. Read off [connectedOrPending], since
     * the connect screen offers the report from the firmware gate, where the session is
     * [ConnectionState.AdvisoryWarning].
     */
    val hasReport: Boolean get() = connectedOrPending()?.report != null

    /**
     * True once connected to something the catalog does not recognise - what the preset screen's
     * "Share device details" is gated on. Read off the identity, since nothing above the
     * instrument layer knows what family is connected.
     */
    val isUnknownDevice: Boolean get() = connected()?.identity?.descriptorId == DeviceProfile.UNKNOWN_ID


    /**
     * Preset listings, kept for as long as this ViewModel lives - a field here rather than nearer
     * the hardware because [forceReconnect] replaces [instrument] on every return to the
     * foreground, and a Motif XS would otherwise re-read 416 user voices (about 93 s) every time.
     * Not persisted - see `docs/ARCHITECTURE.md`, "Caching".
     */
    private val indexCache = PresetIndexCache()

    /** Resolves one of this app's own strings; the instrument layer's messages are not resources - see `res/values/strings.xml`. */
    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /**
     * The instrument as the published state has it, or null - what every property a screen reads
     * during composition goes through, since those are re-read across the reconnect a resume
     * causes. [current] stays strict for everything a user action invokes.
     */
    private fun connected(): Instrument? = (state.value as? ConnectionState.Connected)?.instrument

    /** [connected], or the instrument held behind the advisory gate, whose facets work too. */
    private fun connectedOrPending(): Instrument? = when (val s = state.value) {
        is ConnectionState.Connected -> s.instrument
        is ConnectionState.AdvisoryWarning -> s.instrument
        else -> null
    }

    private fun current(): Instrument = instrument ?: error("Not connected to an instrument")

    /** Unwraps a facet the caller has already decided is present; reaching the error is a UI gating bug. */
    private fun <T : Any> requireFacet(facet: T?, what: String): T =
        facet ?: error("This instrument cannot $what.")
}
