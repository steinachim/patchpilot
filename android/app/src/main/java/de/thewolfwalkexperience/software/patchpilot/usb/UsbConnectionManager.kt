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

/** Declared in AndroidManifest.xml with protectionLevel="signature", so only this app (or
 * another one signed with the same key) can hold it. Required of the sender in every
 * [Context.registerReceiver] call below, on every API level - not just RECEIVER_NOT_EXPORTED's
 * API 33+ equivalent - because a dynamically-registered receiver with no broadcastPermission is
 * implicitly exported on API 26-32 (this app's minSdk): any other app on the phone could
 * otherwise send de.thewolfwalkexperience.software.patchpilot.USB_PERMISSION with a spoofed
 * EXTRA_PERMISSION_GRANTED and force requestPermission()'s coroutine to resume with a fake
 * result. The PendingIntent this app hands to UsbManager.requestPermission() still satisfies the
 * check below: firing it broadcasts under this app's own identity, which holds this permission
 * like any app signed with the matching key would. */
private const val USB_PERMISSION_CALLBACK_PERMISSION =
    "de.thewolfwalkexperience.software.patchpilot.permission.USB_PERMISSION_CALLBACK"

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
                    unregister()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver, filter, USB_PERMISSION_CALLBACK_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                // No RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED overload exists below API 33; the
                // broadcastPermission argument above is this branch's actual protection.
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter, USB_PERMISSION_CALLBACK_PERMISSION, null)
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
     * **Needs no signature-level protection**, unlike [requestPermission]'s receiver.
     * `ACTION_USB_DEVICE_DETACHED` is a system broadcast the framework's own USB host stack sends
     * on a real unplug - not one this app triggers itself via a `PendingIntent` another app could
     * impersonate - so there is nothing here for a spoofed broadcast to fake convincingly enough
     * to matter: the caller re-checks the reported device against the session actually in use
     * rather than trusting the broadcast's mere arrival.
     */
    fun deviceDetachEvents(): Flow<UsbDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }
                if (device != null) trySend(device)
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

/** Human-readable label for a [UsbDevice] of unknown make - used by the device-selection/warning
 * screens and as the name in [de.thewolfwalkexperience.software.patchpilot.devices.nord.DeviceProfile.unknown].
 * Falls back to the vendor/product ids since `productName` isn't guaranteed by the USB spec. */
fun UsbDevice.displayLabel(): String {
    // Sanitized because this lands next to the "continue at your own risk" gate - see
    // [sanitizeDeviceText], which the MIDI path shares.
    val label = sanitizeDeviceText(productName) ?: "USB device"
    return "$label (0x%04X:0x%04X)".format(vendorId, productId)
}
