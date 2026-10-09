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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
 * @param onUsbDetached a USB device was unplugged, the connected one or any other, with its
 *   device handle, the one a cache key is built from. Runs first, before any teardown.
 * @param onLeavingSession the user is leaving the session ([disconnect]): cancel work that only
 *   reads from it, such as a device report, so the teardown does not wait it out. Runs before the
 *   teardown asks for the operation mutex.
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
    private val onUsbDetached: (handle: String) -> Unit = {},
    private val onLeavingSession: () -> Unit = {},
    private val demo: () -> Instrument = { DemoInstrument() },
) {
    /** The device a session was opened on, which [forceReconnect] reopens. */
    private sealed interface SessionOrigin {
        /** A catalog match, found again by a scan of its bus. */
        data class Recognised(val candidate: Candidate) : SessionOrigin

        /** A device opened on a guess, reopened through [lastConfirmedUnknownDevice]. */
        data object Guessed : SessionOrigin
    }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /**
     * The session's instrument, or null; set and cleared under [instrumentMutex], except by
     * [release], which clears it at once and closes it once the mutex is free.
     */
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
     * [reconnectRememberedUnknownDeviceOrOffer] can restore it without the warning screen: on a
     * [forceReconnect] of a session opened this way, and when [connect] finds nothing in the
     * catalog. Cleared by [disconnect] and by a real detach, not by [teardownCurrentInstrument],
     * which [forceReconnect] runs before reconnecting.
     */
    private var lastConfirmedUnknownDevice: AttachedUsbDevice? = null

    /**
     * Where the session's [instrument] came from, so [forceReconnect] reopens that device rather
     * than whichever one a scan finds first. Null in demo mode and with no session; set and
     * cleared with [instrument].
     */
    private var sessionOrigin: SessionOrigin? = null

    /**
     * The advisory the user accepted, as the instrument's `stableKey` and the advisory text, so a
     * [forceReconnect] that raises the same advisory again does not ask again. Cleared by
     * [disconnect] and by a real detach.
     */
    private var acceptedAdvisory: Pair<String, String>? = null

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

    /** A USB device was plugged in while an attempt scanned or opened; see [rescanAfterAttach]. */
    private var attachedDuringAttempt = false

    /** Whether the latest scan forced the picker, so [rescanAfterAttach] repeats it as it ran. */
    private var lastScanForcedPicker = false

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
     * Reacts to a physical USB detach in whichever of four shapes the session is in: closes an
     * active session using that device ([ConnectionState.DeviceLost]); fails a connect attempt
     * waiting on it ([ConnectionState.Opening], most often on a permission dialog that can now
     * never arrive); leaves [ConnectionState.UnknownDeviceWarning] for a device that is gone; or
     * drops the matching row from [ConnectionState.DeviceSelection].
     *
     * Ignores everything else: every device Android has seen fires this broadcast on unplug.
     */
    private fun handleUsbDetach(device: AttachedUsbDevice) {
        // The device's cached listings go with it, connected or not: its contents may change
        // before it is next seen, and Android hands a replug - or another unit of the same model
        // in the same port - the same USB device path, so the cache key does not notice.
        onUsbDetached(device.handle)
        val detachedKey = device.physicalKey
        if (detachedKey == connectedPhysicalKey) {
            val name = (state.value as? ConnectionState.Connected)?.instrument?.identity?.name
                ?: (state.value as? ConnectionState.AdvisoryWarning)?.instrument?.identity?.name
                ?: device.label
            // A connect attempt that overlaps this (a forceReconnect() from onResume) must not
            // overwrite DeviceLost with a stale Connected or Error.
            connectJob?.cancel()
            lastConfirmedUnknownDevice = null
            acceptedAdvisory = null
            scope.launch {
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
        // Back to the picker, whose row for this device the filter below drops.
        val warning = state.value as? ConnectionState.UnknownDeviceWarning
        if (warning?.device?.physicalKey == detachedKey) offerUnknownDeviceOrFail()
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
     * device that went away. One plugged in while an attempt scans or opens is remembered and
     * scanned for once that attempt ends with nothing usable ([rescanAfterAttach]). An
     * established session is left alone.
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

            is ConnectionState.DeviceSelection -> launchConnect { performConnect(forcePicker = true) }

            is ConnectionState.DeviceLost -> if (device.physicalKey == s.physicalKey) connect()

            ConnectionState.Searching,
            is ConnectionState.Opening,
            -> attachedDuringAttempt = true

            is ConnectionState.Connected,
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
            runAttempt(failureMessage, block)
            rescanAfterAttach()
        }
    }

    /**
     * Runs [block] in place of whatever attempt is in flight: cancels that one and starts [block]
     * once it has finished, so the two never interleave. For [disconnect], which the
     * single-flight guard in [launchConnect] would otherwise drop during a [forceReconnect].
     */
    private fun replaceConnect(block: suspend () -> Unit) {
        val previous = connectJob
        previous?.cancel()
        connectJob = scope.launch {
            previous?.join()
            runAttempt(failureMessage = null, block)
            rescanAfterAttach()
        }
    }

    /**
     * Scans again where a USB device was plugged in while the attempt scanned or opened, and the
     * attempt ended with nothing usable: the device may have enumerated after the scan's USB
     * phase. Runs in the attempt's own job, which the single-flight guard would otherwise turn a
     * new attempt away from.
     */
    private suspend fun rescanAfterAttach() {
        while (attachedDuringAttempt) {
            attachedDuringAttempt = false
            when (state.value) {
                is ConnectionState.NothingFound,
                is ConnectionState.DeviceSelection,
                is ConnectionState.Error,
                is ConnectionState.PermissionDenied,
                is ConnectionState.NeedsManualSetting,
                ConnectionState.Disconnected,
                -> runAttempt(failureMessage = null) { performConnect(lastScanForcedPicker) }

                else -> return
            }
        }
    }

    /** The catch every attempt shares: a failure lands on a screen, cancellation does not. */
    private suspend fun runAttempt(failureMessage: String?, block: suspend () -> Unit) {
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

    /**
     * Scans every bus, then connects. The results are deduplicated by physical device: a Nord
     * exposes a class-compliant USB-MIDI interface alongside the vendor one, and USB goes first,
     * so it is claimed through the interface that can browse presets.
     */
    fun connect() {
        launchConnect { performConnect(forcePicker = false) }
    }

    /**
     * The scan-then-connect body of every attempt with no particular device in mind, run inside
     * that attempt's own job.
     *
     * [forcePicker] shows the picker where auto-connect would otherwise take the first instrument
     * found: after the user backed out of a session ([disconnect]), and while the picker is up.
     *
     * The MIDI scan is skipped where auto-connect already has its answer from USB alone:
     * `MidiDiscovery.scan` opens every port and waits up to 600 ms per port for a probe reply,
     * and [mergeCandidates] prefers a USB candidate for the same physical device anyway. A
     * MIDI-only family (the Pro-800, the Axe-Fx III, the Subsequent 37) is only ever found by
     * running that scan, so it runs whenever the picker is forced, auto-connect is off, or USB
     * found nothing recognised.
     */
    private suspend fun performConnect(forcePicker: Boolean) {
        lastScanForcedPicker = forcePicker
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
        connectAndPublish(built, SessionOrigin.Recognised(candidate), candidate.physicalKey, candidate.deviceHandle())
    }

    /**
     * Reopens the device a session ran on, found again by a scan of its own bus: by its handle, or
     * by model where only one unit of it is attached. Where it is gone, offers the picker rather
     * than taking whatever else is attached, so a resume never switches instruments.
     */
    private suspend fun reopen(previous: Candidate) {
        _state.value = ConnectionState.Searching
        val sameModel = discoveryFor(previous).scan(catalog())
            .filter { it.physicalKey == previous.physicalKey && it.descriptor.id == previous.descriptor.id }
        val again = sameModel.firstOrNull { it.deviceHandle() == previous.deviceHandle() }
            ?: sameModel.singleOrNull()
        if (again != null) connectCandidate(again) else performConnect(forcePicker = true)
    }

    /**
     * Connects [built] and makes it the session - the tail every connect path shares. A failure
     * before it becomes the session closes it, so a MIDI port or USB interface is never left
     * claimed by a session that does not exist. That includes an attempt cancelled while it waits
     * for the mutex, which a [disconnect] replacing it does.
     *
     * Behind the advisory gate nothing is read yet: the listing starts once the user accepts
     * ([confirmAdvisory]), and until then the instrument stays free for the gate's device report.
     *
     * Expects no session: every path that can run with one tears it down first.
     */
    private suspend fun connectAndPublish(
        built: Instrument,
        origin: SessionOrigin?,
        physicalKey: String?,
        handle: String?,
    ) {
        closingOnFailure({ closeQuietly(built) }) {
            built.connect()
            instrumentMutex.withLock {
                instrument = built
                sessionOrigin = origin
                connectedPhysicalKey = physicalKey
                deviceHandle = handle
            }
        }
        val published = connectedOrAdvisory(built)
        _state.value = published
        if (published is ConnectionState.Connected) onSessionStarted()
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

    /**
     * User picked a device from [ConnectionState.DeviceSelection] - show the "unsupported, at
     * your own risk" warning before touching it.
     */
    fun selectUnknownDevice(device: AttachedUsbDevice) {
        if (!mayGuessAt(device, UnknownDevicePolicy.refusalFor(device.label))) return
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

    /**
     * Backs out of [ConnectionState.UnknownDeviceWarning] back to the device picker (or the
     * plain error state, if USB devices were unplugged in the meantime).
     */
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
        connectAndPublish(built, SessionOrigin.Guessed, device.physicalKey, device.handle)
        lastConfirmedUnknownDevice = device
    }

    /**
     * Connects to a fictitious instrument instead of real hardware ([DemoInstrument]): no USB
     * permission, discovery or hardware I/O, landing in the same [ConnectionState.Connected].
     */
    fun connectDemo() {
        launchConnect(failureMessage = strings.get(R.string.connect_failed_demo)) {
            // Offered on the advisory gate, where a real instrument is already the session: it is
            // closed here, so its port is not left claimed.
            teardownCurrentInstrument()
            // No bus and no device handle: nothing to detach, and nothing for the cache to key on.
            connectAndPublish(demo(), origin = null, physicalKey = null, handle = null)
        }
    }

    /**
     * Whether returning to the foreground should rebuild the session: asked of the instrument,
     * which asks its transport (`Transport.rebuildOnResume`), so a USB session is rebuilt and a
     * MIDI or demo one is not. That includes one waiting on the advisory gate.
     *
     * True while nothing is connected, which is how an instrument plugged in while the app was
     * away gets picked up. False where a rescan would undo something:
     * - [ConnectionState.DeviceLost], which exists so nothing reconnects until the user asks.
     * - [ConnectionState.Opening] and [ConnectionState.PermissionDenied]: dismissing the
     *   permission dialog is itself the resume, and a rescan would put it straight back up.
     * - [ConnectionState.DeviceSelection] and [ConnectionState.UnknownDeviceWarning]: a choice the
     *   user has not made yet, which auto-connect would make for them. An attach or a detach
     *   while away still updates them.
     */
    val shouldRebuildOnResume: Boolean
        get() = when (val s = state.value) {
            is ConnectionState.Connected -> s.instrument.rebuildOnResume

            is ConnectionState.AdvisoryWarning -> s.instrument.rebuildOnResume

            is ConnectionState.DeviceLost,
            is ConnectionState.Opening,
            is ConnectionState.PermissionDenied,
            is ConnectionState.DeviceSelection,
            is ConnectionState.UnknownDeviceWarning,
            -> false

            ConnectionState.Disconnected,
            ConnectionState.Searching,
            is ConnectionState.Error,
            is ConnectionState.NeedsManualSetting,
            is ConnectionState.NothingFound,
            -> true
        }

    /**
     * [ConnectionState.Connected], or the advisory gate where the instrument raised an advisory
     * the user has not already accepted for it.
     */
    private fun connectedOrAdvisory(built: Instrument): ConnectionState {
        val advisory = built.advisory ?: return ConnectionState.Connected(built)
        if (acceptedAdvisory == (built.identity.stableKey to advisory)) return ConnectionState.Connected(built)
        return ConnectionState.AdvisoryWarning(built, advisory)
    }

    /** User accepted the advisory - carries on with the instrument already connected. */
    fun confirmAdvisory() {
        val pending = state.value as? ConnectionState.AdvisoryWarning ?: return
        acceptedAdvisory = pending.instrument.identity.stableKey to pending.message
        _state.value = ConnectionState.Connected(pending.instrument)
        onSessionStarted()
    }

    /**
     * The user backed out of the session, from the preset screen or the advisory gate: closes it
     * and offers the picker, even for a lone instrument, since they asked to choose.
     *
     * Publishes [ConnectionState.Searching] at once. The teardown waits for whatever holds the
     * operation mutex, and a session left showing as connected meanwhile would send the connect
     * screen straight back to the presets. Replaces any attempt in flight, a [forceReconnect]
     * included, so the session that attempt would have published never lands.
     */
    fun disconnect() {
        lastConfirmedUnknownDevice = null
        acceptedAdvisory = null
        onLeavingSession()
        _state.value = ConnectionState.Searching
        replaceConnect {
            teardownCurrentInstrument()
            performConnect(forcePicker = true)
        }
    }

    /**
     * Tears down whatever connection exists and reconnects from scratch, on a return to the
     * foreground: unrelated USB bus activity while backgrounded can leave the endpoints erroring
     * on every subsequent transfer, with no way to detect or repair that specifically.
     *
     * Reopens the session's own device ([reopen]), not whichever one a scan finds first; with no
     * session, it scans as [connect] does. Runs through [launchConnect], so this job lands in
     * [connectJob] like every other attempt - the single-flight guard, the error handling and
     * [handleUsbDetach]'s cancel all depend on it.
     */
    fun forceReconnect() {
        launchConnect {
            // Read before the teardown clears it.
            val origin = sessionOrigin
            teardownCurrentInstrument()
            when (origin) {
                null -> performConnect(forcePicker = false)
                is SessionOrigin.Recognised -> reopen(origin.candidate)
                SessionOrigin.Guessed -> reconnectRememberedUnknownDeviceOrOffer()
            }
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
     *
     * In [GlobalScope] on purpose: the owner's scope is already cancelled, and this one coroutine
     * has to outlive it. It ends once the close is done.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun release() {
        val target = instrument ?: return
        instrument = null
        GlobalScope.launch(Dispatchers.Main.immediate) {
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
            sessionOrigin = null
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
