// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.usb

import android.hardware.usb.UsbDevice
import de.thewolfwalkexperience.software.patchpilot.discovery.physicalKey
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A USB device as connecting sees it. An interface rather than the platform's [UsbDevice], whose
 * constructor is hidden, so the connection logic can run in a unit test.
 */
interface AttachedUsbDevice {
    /** The model, by vendor and product id: what a detach is matched on, and stable across a replug. */
    val physicalKey: String

    /** The handle the platform gave this enumeration, which a replug changes - see `CacheKey`. */
    val handle: String

    /** What a picker row and the unknown-device warning show. */
    val label: String

    val vendorId: Int
    val productId: Int
}

/** [AttachedUsbDevice] over the platform's [UsbDevice]. */
data class AndroidUsbDevice(val device: UsbDevice) : AttachedUsbDevice {
    override val physicalKey: String get() = device.physicalKey()
    override val handle: String get() = "usb:${device.deviceName}"
    override val label: String get() = device.displayLabel()
    override val vendorId: Int get() = device.vendorId
    override val productId: Int get() = device.productId
}

/** What connecting needs of the USB host bus: [AndroidUsbHost] in the app, a fake in a test. */
interface UsbHost {
    fun attachedDevices(): List<AttachedUsbDevice>

    /** Asks the user for access to [device]; true where it was granted, now or before. */
    suspend fun requestPermission(device: AttachedUsbDevice): Boolean

    /** Opens [device] for a bulk vendor protocol. */
    fun openBulk(device: AttachedUsbDevice): UsbBulkTransport

    /** Every device reported physically detached, for as long as this is collected. */
    fun detachEvents(): Flow<AttachedUsbDevice>
}

/** [UsbHost] over [UsbConnectionManager]. Hands out and accepts only [AndroidUsbDevice]s. */
class AndroidUsbHost(private val manager: UsbConnectionManager) : UsbHost {
    override fun attachedDevices(): List<AttachedUsbDevice> = manager.findAllDevices().map(::AndroidUsbDevice)

    override suspend fun requestPermission(device: AttachedUsbDevice): Boolean =
        manager.requestPermission(device.platform())

    override fun openBulk(device: AttachedUsbDevice): UsbBulkTransport = manager.openTransport(device.platform())

    override fun detachEvents(): Flow<AttachedUsbDevice> = manager.deviceDetachEvents().map(::AndroidUsbDevice)

    private fun AttachedUsbDevice.platform(): UsbDevice =
        (this as? AndroidUsbDevice)?.device ?: error("$label was not found on this USB host")
}
