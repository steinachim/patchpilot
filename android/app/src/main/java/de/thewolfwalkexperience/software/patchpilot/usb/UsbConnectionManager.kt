// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import de.thewolfwalkexperience.software.patchpilot.transport.AndroidUsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import de.thewolfwalkexperience.software.patchpilot.core.sanitizeDeviceText

private const val ACTION_USB_PERMISSION = "de.thewolfwalkexperience.software.patchpilot.USB_PERMISSION"
/** Interface 0 on every device this app opens so far - Nord and Motif XS alike. */
private const val DEVICE_INTERFACE_INDEX = 0

/**
 * Lists what is attached over USB, drives the Android runtime permission flow (needed unless
 * device_filter.xml auto-granted it on attach), and claims an interface.
 *
 * Family-agnostic: [UsbHostDiscovery] matches whatever this returns against the catalog, and the
 * endpoint addresses come from the matched descriptor.
 *
 * No kernel-driver-detach step is needed here - that is a desktop-libusb concern that does not
 * apply to Android's own USB host stack.
 */
class UsbConnectionManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Every USB device Android currently sees, recognised or not. [UsbHostDiscovery] matches
     * these against the catalog; the connect screen also offers the leftovers as the "pick a
     * device to try anyway" rows. */
    fun findAllDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    /**
     * Asks the user for permission to open [device], unless it is already granted.
     *
     * **The result is read from [UsbManager.hasPermission], not from the broadcast's extras.**
     * The receiver below is dynamically registered, which on API 26-32 (this app's minSdk) means
     * it is exported: any app on the phone can send `ACTION_USB_PERMISSION` with a spoofed
     * `EXTRA_PERMISSION_GRANTED`. Guarding the receiver with a broadcast permission does not
     * work here, because the system fires the `PendingIntent` under this app's own uid, and a
     * package holds only the permissions it *requests* - so a receiver that demands one this
     * app merely defines never receives the real result either. Asking the framework whether
     * permission is held makes a spoofed broadcast worthless: it cannot grant anything, and
     * at worst it resumes this with "denied" while the real dialog is still up, after which a
     * retry finds the permission granted. On API 33+ the receiver is additionally not exported.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { cont ->
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags,
            )

            val receiver = object : BroadcastReceiver() {
                private val unregistered = AtomicBoolean(false)

                /** Once only: the reply and a cancellation can both try, and the second would throw. */
                fun unregister() {
                    if (unregistered.compareAndSet(false, true)) context.unregisterReceiver(this)
                }

                override fun onReceive(receiverContext: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    // A result for some other device - two requests in flight, or a broadcast
                    // that is not the system's - is not this request's answer.
                    val answered = intent.usbDevice()
                    if (answered != null && answered.deviceName != device.deviceName) return
                    unregister()
                    if (cont.isActive) cont.resume(usbManager.hasPermission(device))
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // No RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED overload exists below API 33. The
                // receiver is exported there, which is why the result is verified with
                // hasPermission() rather than read from the intent - see the method's doc.
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { receiver.unregister() }

            usbManager.requestPermission(device, pendingIntent)
        }
    }

    /**
     * Opens the device and claims its vendor interface, returning a ready-to-use transport.
     *
     * Interface 0 for every device this app opens so far, Nord and Motif XS alike; the endpoint
     * addresses differ, which is why they are arguments.
     */
    fun openTransport(
        device: UsbDevice,
        endpointOut: Int = AndroidUsbBulkTransport.EP_OUT_ADDRESS,
        endpointIn: Int = AndroidUsbBulkTransport.EP_IN_ADDRESS,
    ): UsbBulkTransport {
        val usbInterface: UsbInterface = device.getInterface(DEVICE_INTERFACE_INDEX)
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: error("Failed to open USB device ${device.deviceName}")
        return AndroidUsbBulkTransport(
            connection, usbInterface, endpointOutAddress = endpointOut, endpointInAddress = endpointIn,
        )
    }

    /**
     * Emits every USB device Android reports as physically detached while collected - lets a
     * caller tell "the instrument this session is using just went away" from "some unrelated USB
     * event happened on the bus" by comparing the emitted device against whatever it is using.
     *
     * Like [requestPermission]'s receiver, this one is exported below API 33, and for the same
     * reason nothing here trusts a broadcast's mere arrival: the caller re-checks the reported
     * device against the session actually in use, so a spoofed detach for a device that is
     * still attached costs at most a reconnect.
     */
    fun deviceDetachEvents(): Flow<UsbDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                intent.usbDevice()?.let { trySend(it) }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

/** The [UsbDevice] a USB broadcast is about, or null where it carries none. */
private fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
    }

/** Human-readable label for a [UsbDevice] of unknown make - used by the device-selection/warning
 * screens and as the name in [de.thewolfwalkexperience.software.patchpilot.devices.nord.DeviceProfile.unknown].
 * Falls back to the vendor/product ids since `productName` isn't guaranteed by the USB spec. */
fun UsbDevice.displayLabel(): String {
    // Sanitized because this lands next to the "continue at your own risk" gate - see
    // [sanitizeDeviceText], which the MIDI path shares.
    val label = sanitizeDeviceText(productName) ?: "USB device"
    return "$label (0x%04X:0x%04X)".format(vendorId, productId)
}
