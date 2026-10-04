// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.hardware.usb.UsbDevice
import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.UnknownDevicePolicy
import de.thewolfwalkexperience.software.patchpilot.catalog.supportedModelLines
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.discovery.Candidate
import de.thewolfwalkexperience.software.patchpilot.discovery.DeviceDiscovery
import de.thewolfwalkexperience.software.patchpilot.discovery.MidiPort
import de.thewolfwalkexperience.software.patchpilot.discovery.mergeCandidates
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.usb.AttachedUsbDevice
import de.thewolfwalkexperience.software.patchpilot.usb.UsbHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ConnectionCoordinator"

/**
 * The connection state machine: scanning, opening, the unknown-device and advisory gates, USB
 * attach and detach, and teardown. Holds the one connected [Instrument] and the mutex every
 * operation on it takes ([exclusive]).
 *
 * The buses, the catalog and the instrument factories come in as parameters, so this runs in a
 * unit test against fakes. What the rest of the session does about a session starting or ending
 * arrives through the three hooks.
 *
 * @param createForUnknown builds an instrument on the protocol of the family
 *   [UnknownDevicePolicy.familyFor] named, for a device the catalog does not list.
 * @param onSessionStarted a session became usable: start its listing.
 * @param onSessionEnding a session is about to be closed: stop whatever reads from it, and return
 *   once that has finished its cleanup. Runs under the operation mutex, right before the close.
 * @param onDeviceLost the connected device was unplugged. Runs before the teardown, which clears
 *   the [deviceHandle] a cache key is built from.
 */
internal class ConnectionCoordinator(
    private val scope: CoroutineScope,
    private val usb: UsbHost,
    private val discoveries: List<DeviceDiscovery>,
    private val catalog: () -> List<InstrumentDescriptor>,
    private val create: (InstrumentDescriptor, Transport) -> Instrument,
    private val createForUnknown: (familyId: String, UsbBulkTransport, AttachedUsbDevice) -> Instrument,
    private val autoConnect: suspend () -> Boolean,
    private val strings: StringResolver,
    private val onSessionStarted: () -> Unit = {},
    private val onSessionEnding: suspend () -> Unit = {},
    private val onDeviceLost: () -> Unit = {},
    private val demo: () -> Instrument = { DemoInstrument() },
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** The session's instrument, or null; set and cleared under [instrumentMutex]. */
    var instrument: Instrument? = null
        private set

    /**
     * The physical key of whatever [instrument] is connected to, or null (nothing connected, or a
     * bus with no backing USB device). Captured at connect time, so a later detach can be matched
     * to this session's device - see [handleUsbDetach].
     */
    private var connectedPhysicalKey: String? = null

    /**
     * The handle of the device this session runs over - a USB device path or a MIDI device id -
     * which tells one unit of a model from another for the listing cache (`CacheKey.physicalDevice`).
     * It does not tell a replug from a resume, which is why a detach drops the listing itself.
     */
    var deviceHandle: String? = null
        private set

    /**
     * The physical key of whatever device a connect attempt is opening, or null. Held only for
     * the open step, by [whileOpening], so [handleUsbDetach] can fail an attempt whose device is
     * unplugged before it finishes.
     */
    private var openingPhysicalKey: String? = null

    /**
     * The device behind the most recent accepted [confirmUnknownDevice], so
     * [reconnectRememberedUnknownDeviceOrOffer] can restore it without the warning screen when
     * [connect] finds nothing in the catalog - most often a [forceReconnect] on resume. Cleared by
     * [disconnect] and by a real detach, not by [teardownCurrentInstrument], which
     * [forceReconnect] runs before reconnecting.
     */
    private var lastConfirmedUnknownDevice: AttachedUsbDevice? = null

    /**
     * Serialises every read-then-write against [instrument] with [forceReconnect]'s teardown, so
     * a resume that rebuilds the session waits for an edit in flight rather than closing the
     * transport under it. Held for the whole body of each operation, since the race is between
     * the write and the re-read that confirms it.
     *
     * The index scan is not routed through this: [onSessionEnding] cancels it instead, rather than
     * making a resume wait out a 93-second Motif XS listing, and waits only for its cleanup.
     */
    private val instrumentMutex = Mutex()

    private var connectJob: Job? = null

    // Set by disconnect(showPicker = true) - the user backed out of a session on purpose and
    // asked to choose again, so the next connect() must not silently reconnect a lone device.
    private var forcePickerOnNextConnect = false

    /**
     * Starts listening for USB detaches; called once. Guarded, unlike this class's other
     * fire-and-forget launches: the scope installs no CoroutineExceptionHandler, and registering a
     * receiver can fail for reasons outside this app's control. Losing detach detection is a small
     * regression; crashing at startup is not.
     */
    fun start() {
        scope.launch {
            try {
                usb.detachEvents().collect { device -> handleUsbDetach(device) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Couldn't listen for USB detach events; a physical unplug will only be noticed when the next operation fails",
                    e,
                )
            }
        }
    }

    /** Runs [block] on the connected instrument under the operation mutex; throws where nothing is connected. */
    suspend fun <T> exclusive(block: suspend (Instrument) -> T): T =
        instrumentMutex.withLock { block(instrument ?: error("Not connected to an instrument")) }

    /**
     * Reacts to a physical USB detach in whichever of three shapes the session is in: closes an
     * active session using that device ([ConnectionState.DeviceLost]); fails a connect attempt
     * waiting on it ([ConnectionState.Opening], most often on a permission dialog that can now
     * never arrive); or drops the matching row from [ConnectionState.DeviceSelection].
     *
     * Ignores everything else: every device Android has seen fires this broadcast on unplug.
     */
    private fun handleUsbDetach(device: AttachedUsbDevice) {
        val detachedKey = device.physicalKey
        if (detachedKey == connectedPhysicalKey) {
            val name = (state.value as? ConnectionState.Connected)?.instrument?.identity?.name
                ?: (state.value as? ConnectionState.AdvisoryWarning)?.instrument?.identity?.name
                ?: device.label
            // A connect attempt that overlaps this (a forceReconnect() from onResume) must not
            // overwrite DeviceLost with a stale Connected or Error.
            connectJob?.cancel()
            lastConfirmedUnknownDevice = null
            scope.launch {
                // The device's cached listing goes with it: once the instrument has left the bus
                // its contents may change before it is next seen, and Android hands a replug the
                // same USB device path, so the cache key does not notice.
                onDeviceLost()
                teardownCurrentInstrument()
                _state.value = ConnectionState.DeviceLost(name, detachedKey)
            }
            return
        }
        if (detachedKey == openingPhysicalKey) {
            openingPhysicalKey = null
            connectJob?.cancel()
            _state.value = ConnectionState.Error(strings.get(R.string.connect_opening_device_unplugged, device.label))
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
    fun onUsbDeviceAttached(device: AttachedUsbDevice) {
        val s = state.value
        Log.i(
            TAG,
            "USB attach of ${device.label} while $s; attached devices: " +
                usb.attachedDevices().joinToString { it.label },
        )
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

            is ConnectionState.DeviceLost -> if (device.physicalKey == s.physicalKey) connect()

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
        connectJob = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstrumentException.NeedsManualSetting) {
                // Not an error line: the user has something to go and do, and the screen has to
                // show them what. See ConnectionState.NeedsManualSetting.
                _state.value = ConnectionState.NeedsManualSetting(
                    message = e.message ?: strings.get(R.string.connect_failed),
                    steps = e.steps,
                    alsoCheck = e.alsoCheck,
                )
            } catch (e: Exception) {
                _state.value =
                    ConnectionState.Error(e.message ?: failureMessage ?: strings.get(R.string.connect_failed))
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
        val catalog = catalog()
        val autoConnect = autoConnect()

        val usbCandidates = discoveries.filter { it.bus == Bus.USB }.flatMap { it.scan(catalog) }
        if (autoConnect && !forcePicker && usbCandidates.isNotEmpty()) {
            connectCandidate(usbCandidates.first())
            return
        }

        val otherScans = discoveries.filterNot { it.bus == Bus.USB }.map { it.scan(catalog) }
        val known = mergeCandidates(listOf(usbCandidates) + otherScans)
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
        val transport = whileOpening(candidate.physicalKey) {
            _state.value = ConnectionState.Opening(candidate.displayName, candidate.bus)
            try {
                discoveryFor(candidate).open(candidate)
            } catch (e: SecurityException) {
                _state.value = ConnectionState.PermissionDenied(candidate.displayName)
                return
            }
        }
        val built = closingOnFailure(transport::close) { create(candidate.descriptor, transport) }
        connectAndPublish(built, candidate.physicalKey, candidate.deviceHandle())
    }

    /**
     * Connects [built] and makes it the session - the tail every connect path shares. A failure
     * before it becomes the session closes it, so a MIDI port or USB interface is never left
     * claimed by a session that does not exist.
     */
    private suspend fun connectAndPublish(built: Instrument, physicalKey: String?, handle: String?) {
        closingOnFailure({ closeQuietly(built) }) { built.connect() }
        instrumentMutex.withLock {
            instrument = built
            connectedPhysicalKey = physicalKey
            deviceHandle = handle
        }
        _state.value = connectedOrAdvisory(built)
        onSessionStarted()
    }

    /**
     * Runs the open step of a connect attempt with [key] as [openingPhysicalKey], and clears it
     * however [block] ends - opened, refused, failed or cancelled - unless [handleUsbDetach] has
     * already. A key left behind would make a later unplug of that device fail whatever session
     * is current by then.
     */
    private inline fun <T> whileOpening(key: String, block: () -> T): T {
        openingPhysicalKey = key
        try {
            return block()
        } finally {
            if (openingPhysicalKey == key) openingPhysicalKey = null
        }
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
            usb.attachedDevices()
                .filter { it.physicalKey !in claimed }
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
            usb.attachedDevices().any { it.physicalKey == remembered.physicalKey }
        if (remembered == null || !stillAttached) {
            lastConfirmedUnknownDevice = null
            offerUnknownDeviceOrFail()
            return
        }
        connectUnknownDevice(remembered)
    }

    /** No supported instrument was found: offers a device picker if anything else is attached, otherwise "nothing found". */
    private fun offerUnknownDeviceOrFail() {
        if (usb.attachedDevices().isEmpty()) {
            // Every instrument the app can recognize, on both buses, since both were scanned.
            _state.value = ConnectionState.NothingFound(supportedModelLines(catalog()))
        } else {
            _state.value = ConnectionState.DeviceSelection(pickerEntries(emptyList()))
        }
    }

    /** User picked a device from [ConnectionState.DeviceSelection] - show the "unsupported, at
     * your own risk" warning before touching it. */
    fun selectUnknownDevice(device: AttachedUsbDevice) {
        val refusal = "${device.label} is not a Clavia device, so there is no protocol " +
            "to try on it. Only an unrecognised Nord can be opened this way - everything else the " +
            "app supports is matched by its exact USB ids."
        if (!mayGuessAt(device, refusal)) return
        _state.value = ConnectionState.UnknownDeviceWarning(device)
    }

    /**
     * Whether [device] may be opened on a guess - see [UnknownDevicePolicy], which is where the
     * rule lives. Where not, returns the user to the picker with [refusal] as the reason.
     */
    private fun mayGuessAt(device: AttachedUsbDevice, refusal: String): Boolean {
        if (UnknownDevicePolicy.familyFor(catalog(), device.vendorId) != null) return true
        _state.value = ConnectionState.DeviceSelection(entries = pickerEntries(emptyList()), message = refusal)
        return false
    }

    /** Backs out of [ConnectionState.UnknownDeviceWarning] back to the device picker (or the
     * plain error state, if USB devices were unplugged in the meantime). */
    fun cancelUnknownDeviceSelection() {
        offerUnknownDeviceOrFail()
    }

    /**
     * User accepted the warning in [ConnectionState.UnknownDeviceWarning]: proceeds as [connect]
     * does, but builds on the guessed family rather than a catalog entry.
     */
    fun confirmUnknownDevice(device: AttachedUsbDevice) {
        // Checked again here: this is the call that claims the interface and starts sending a
        // vendor protocol, whatever route got here.
        if (!mayGuessAt(device, "${device.label} cannot be opened this way.")) return
        launchConnect { connectUnknownDevice(device) }
    }

    /**
     * Opens a device the catalog does not know on the protocol [UnknownDevicePolicy] allows for
     * its vendor: the path for [confirmUnknownDevice] and
     * [reconnectRememberedUnknownDeviceOrOffer]. A catalog match goes through [connectCandidate].
     */
    private suspend fun connectUnknownDevice(device: AttachedUsbDevice) {
        val familyId = UnknownDevicePolicy.familyFor(catalog(), device.vendorId)
            ?: error("${device.label} cannot be opened this way.")
        val transport = whileOpening(device.physicalKey) {
            _state.value = ConnectionState.Opening(device.label, Bus.USB)
            if (!usb.requestPermission(device)) {
                _state.value = ConnectionState.PermissionDenied(device.label)
                return
            }
            usb.openBulk(device)
        }
        val built = closingOnFailure(transport::close) { createForUnknown(familyId, transport, device) }
        connectAndPublish(built, device.physicalKey, device.handle)
        lastConfirmedUnknownDevice = device
    }

    /**
     * Connects to a fictitious instrument instead of real hardware ([DemoInstrument]): no USB
     * permission, discovery or hardware I/O, landing in the same [ConnectionState.Connected].
     */
    fun connectDemo() {
        launchConnect(failureMessage = strings.get(R.string.connect_failed_demo)) {
            // No bus and no device handle: nothing to detach, and nothing for the cache to key on.
            connectAndPublish(demo(), physicalKey = null, handle = null)
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
        onSessionStarted()
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

    fun disconnect(showPicker: Boolean = false) {
        forcePickerOnNextConnect = showPicker
        lastConfirmedUnknownDevice = null
        scope.launch {
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
     * Releases the connection when its owner goes away with the app.
     *
     * **Never under an edit still in flight.** The owner's scope is cancelled before this runs,
     * but a write's `NonCancellable` section (a Motif XS block sequence, its write-plus-commit)
     * keeps going, holding the operation mutex until it is done; closing the transport under it
     * would fail the write part way - the one thing those sections exist to prevent. A
     * cancelled listing's cleanup (a Nord's category unlock) is in the same position. So the
     * close runs from a coroutine of its own, through the same mutex and after [onSessionEnding]:
     * at once where nothing is in flight, otherwise once the edit and the cleanup are done.
     * Nothing here sends protocol messages; every operation cleans up whatever lock it caused.
     */
    fun release() {
        val target = instrument ?: return
        instrument = null
        CoroutineScope(Dispatchers.Main.immediate).launch {
            instrumentMutex.withLock {
                onSessionEnding()
                closeQuietly(target)
            }
        }
    }

    private suspend fun teardownCurrentInstrument() {
        // Waits for an edit, report or regression run in flight - see [instrumentMutex].
        instrumentMutex.withLock {
            // Stop the listing and let its cleanup finish before closing what it reads from: a
            // scan runs in the owner's scope, so it would otherwise fail every remaining slot
            // against a shut handle, and a Nord's would leave its category lock set. Inside the
            // mutex rather than before it, so a listing started while an edit finished is stopped too.
            onSessionEnding()
            closeQuietly(instrument)
            instrument = null
            connectedPhysicalKey = null
            deviceHandle = null
        }
    }

    private fun closeQuietly(target: Instrument?) {
        try {
            target?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Closing the connection did not complete cleanly", e)
        }
    }
}

/** The physical device a row refers to, in the same form a detach reports. */
private fun PickerEntry.physicalKey(): String = when (this) {
    is PickerEntry.Known -> candidate.physicalKey
    is PickerEntry.Unknown -> device.physicalKey
}

/** The handle the platform assigned this device when it enumerated - see `CacheKey.physicalDevice`. */
private fun Candidate.deviceHandle(): String = when (val h = handle) {
    is UsbDevice -> "usb:${h.deviceName}"
    is MidiPort -> h.handle
    else -> "handle:${System.identityHashCode(h)}"
}
