// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import android.hardware.usb.UsbDevice
import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.usb.UsbConnectionManager
import de.thewolfwalkexperience.software.patchpilot.usb.displayLabel
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * Finds instruments on the USB host bus by vendor/product id.
 *
 * No probing is needed or wanted here: a vendor id and product id *are* the identity, and unlike a
 * MIDI port there is nothing to ask. The runtime permission prompt happens in [open], not in
 * [scan], so a scan never interrupts the user with a dialog for a device they did not ask for.
 */
class UsbHostDiscovery(private val connectionManager: UsbConnectionManager) : DeviceDiscovery {

    override val bus = Bus.USB

    override suspend fun scan(catalog: List<InstrumentDescriptor>): List<Candidate> {
        val usbMatches = catalog.mapNotNull { descriptor ->
            (descriptor.match as? DeviceMatch.Usb)?.let { it to descriptor }
        }
        return connectionManager.findAllDevices().mapNotNull { device ->
            val descriptor = usbMatches.firstOrNull { (match, _) ->
                match.vendorId == device.vendorId && match.productId == device.productId
            }?.second ?: return@mapNotNull null
            Candidate(
                descriptor = descriptor,
                displayName = descriptor.name,
                bus = bus,
                physicalKey = device.physicalKey(),
                handle = device,
            )
        }
    }

    override suspend fun open(candidate: Candidate): Transport {
        val device = candidate.handle as? UsbDevice
            ?: error("Not a USB candidate: ${candidate.displayName}")
        if (!connectionManager.requestPermission(device)) {
            throw SecurityException("USB permission was denied for ${device.displayLabel()}.")
        }
        // Null for the "unrecognized, continue at your own risk" path, and a non-USB match is not
        // reachable from [scan] - either way the defaults are the only endpoints we could guess,
        // and guessing is what that path is for.
        val match = candidate.descriptor?.match as? DeviceMatch.Usb
        return if (match == null) {
            connectionManager.openTransport(device)
        } else {
            connectionManager.openTransport(device, match.endpointOut, match.endpointIn)
        }
    }
}

/**
 * Matches the key [MidiDiscovery] derives from a port's backing `UsbDevice`, so the same
 * instrument found on both buses collapses to one candidate.
 */
internal fun UsbDevice.physicalKey(): String = "usb:$vendorId:$productId"
