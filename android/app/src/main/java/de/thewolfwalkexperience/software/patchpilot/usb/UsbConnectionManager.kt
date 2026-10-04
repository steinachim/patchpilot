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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
     * The answer's receiver is registered not-exported (see [registerNotExported]). The result is
     * read from [UsbManager.hasPermission] all the same, not from the broadcast's
     * `EXTRA_PERMISSION_GRANTED`, so it never rests on what a broadcast claims.
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
            registerNotExported(receiver, IntentFilter(ACTION_USB_PERMISSION))
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
     * compares the device against the one its session uses. Registered not-exported, like
     * [requestPermission]'s receiver; the detach is a protected broadcast only the system sends.
     */
    fun deviceDetachEvents(): Flow<UsbDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                intent.usbDevice()?.let { trySend(it) }
            }
        }
        registerNotExported(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /**
     * Registers [receiver] so that no other app can send to it. From API 33 the platform enforces
     * that itself. Below it, `ContextCompat` guards the receiver with a signature permission only
     * this app holds (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, merged in from androidx.core).
     *
     * Both broadcasts this class waits for still arrive there. The permission answer comes from a
     * `PendingIntent` this app created, which is sent under this app's uid and so holds the
     * permission; the detach comes from the system, whose uid passes every permission check.
     * Checked on API 26 and API 32 emulators: a broadcast through this app's own `PendingIntent`
     * arrives, one from another app (the adb shell) does not, and a privileged sender's detach
     * arrives, with root standing in for the system.
     */
    private fun registerNotExported(receiver: BroadcastReceiver, filter: IntentFilter) {
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

/** The [UsbDevice] a USB broadcast or attach intent is about, or null where it carries none. */
internal fun Intent.usbDevice(): UsbDevice? =
    IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

/** Human-readable label for a [UsbDevice] of unknown make, sanitized because it lands next to the
 * "continue at your own risk" gate. `productName` is not guaranteed by the USB spec. */
fun UsbDevice.displayLabel(): String {
    val label = sanitizeDeviceText(productName) ?: "USB device"
    return "$label (0x%04X:0x%04X)".format(vendorId, productId)
}
