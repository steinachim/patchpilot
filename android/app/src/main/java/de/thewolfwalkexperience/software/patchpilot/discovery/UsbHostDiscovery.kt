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
 * Finds instruments on the USB host bus by vendor/product id. The runtime permission prompt
 * happens in [open], not in [scan], so a scan never raises a dialog for a device the user did
 * not ask for.
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
        // Null on the "unrecognized, continue at your own risk" path, where the default endpoints
        // are the only guess available.
        val match = candidate.descriptor?.match as? DeviceMatch.Usb
        return if (match == null) {
            connectionManager.openTransport(device)
        } else {
            connectionManager.openTransport(device, match.endpointOut, match.endpointIn)
        }
    }
}

/** The key [MidiDiscovery] derives from a port's backing `UsbDevice` too, so both buses agree. */
internal fun UsbDevice.physicalKey(): String = "usb:$vendorId:$productId"
