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
 * device_filter.xml granted it on attach), and claims an interface. Family-agnostic:
 * [UsbHostDiscovery] matches the devices against the catalog, which supplies the endpoints.
 */
class UsbConnectionManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Every USB device Android currently sees, recognised or not. */
    fun findAllDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    /**
     * Asks the user for permission to open [device], unless it is already granted.
     *
     * The result is read from [UsbManager.hasPermission], not from the broadcast's extras: below
     * API 33 a dynamically registered receiver is exported, so any app could send
     * `ACTION_USB_PERMISSION` with a spoofed `EXTRA_PERMISSION_GRANTED`. (A broadcast permission
     * on the receiver does not help, since the system fires the `PendingIntent` under this app's
     * own uid, and a package holds only the permissions it requests.) A spoofed broadcast can at
     * worst resume this with "denied" while the real dialog is still up, after which a retry
     * finds the permission granted.
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
                // No RECEIVER_NOT_EXPORTED overload below API 33 - see the method's doc.
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { receiver.unregister() }

            usbManager.requestPermission(device, pendingIntent)
        }
    }

    /** Opens the device and claims interface 0 (every device this app opens so far), returning a ready-to-use transport. */
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
     * Emits every USB device Android reports as physically detached while collected. The caller
     * compares the device against the one its session uses; like [requestPermission]'s receiver
     * this one is exported below API 33, so a spoofed detach costs at most a reconnect.
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

/** Human-readable label for a [UsbDevice] of unknown make, sanitized because it lands next to the
 * "continue at your own risk" gate. `productName` is not guaranteed by the USB spec. */
fun UsbDevice.displayLabel(): String {
    val label = sanitizeDeviceText(productName) ?: "USB device"
    return "$label (0x%04X:0x%04X)".format(vendorId, productId)
}
