// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.catalog.supportedModelLines
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.discovery.Candidate
import de.thewolfwalkexperience.software.patchpilot.usb.AttachedUsbDevice

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
    data class Unknown(val device: AttachedUsbDevice) : PickerEntry {
        override val label: String get() = device.label
    }
}

/** Mirrors the states a connection attempt moves through, made explicit for the UI. */
sealed class ConnectionState {
    /** Before the ViewModel's first connect attempt; every later attempt starts in [Searching]. */
    data object Disconnected : ConnectionState()
    data object Searching : ConnectionState()

    /** Opening the device, which on the USB bus may mean waiting for the permission dialog. [bus] keeps the screen from claiming "USB" for a MIDI port. */
    data class Opening(val displayName: String, val bus: Bus) : ConnectionState()
    data class Error(val message: String) : ConnectionState()

    /**
     * The user answered the system's USB permission dialog with "Deny". Its own state rather than
     * an [Error], because closing that dialog is itself a resume, and a resume with nothing
     * connected rescans ([ConnectionCoordinator.shouldRebuildOnResume]) - which would put the
     * dialog straight back up. Only the Retry button asks again.
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

    /**
     * Nothing is attached at all. [supportedNames] is read from the catalog, so a new supported
     * model needs no screen change; it holds one line per model, variants joined (see
     * [supportedModelLines]).
     */
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
    data class UnknownDeviceWarning(val device: AttachedUsbDevice) : ConnectionState()

    /**
     * Connected, and the instrument raised an [Instrument.advisory]: shown before the user starts
     * using it, with the choice to continue or disconnect. Raised after the handshake, unlike
     * [UnknownDeviceWarning], since only the device can answer it; the instrument is carried here
     * so continuing costs no second connect.
     */
    data class AdvisoryWarning(val instrument: Instrument, val message: String) : ConnectionState()

    /**
     * The instrument this session was using was physically unplugged. Its own state rather than a
     * rescan, which would latch onto a different instrument that happens to still be attached;
     * for the same reason it does not rebuild on resume. Nothing reconnects until the user asks,
     * with one exception: the same instrument being plugged back in, which
     * [ConnectionCoordinator.onUsbDeviceAttached] matches on [physicalKey].
     */
    data class DeviceLost(val instrumentName: String, val physicalKey: String?) : ConnectionState()
}
