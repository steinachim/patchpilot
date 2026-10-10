// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.catalog.UsbIds
import de.thewolfwalkexperience.software.patchpilot.core.sanitizeDeviceText
import de.thewolfwalkexperience.software.patchpilot.transport.AndroidMidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "MidiPorts"

/**
 * One MIDI device as discovery sees it. An interface rather than the platform's [MidiDeviceInfo],
 * which a unit test cannot build.
 */
interface MidiPort {
    /** What a log line calls it. */
    val label: String

    /** The physical device behind it, spelled as a USB device's own key so the two buses deduplicate. */
    val physicalKey: String

    /** Which enumeration this is: stable across a resume; whether a replug changes it is unmeasured, so it is no replug detector - see `CacheKey.physicalDevice`. */
    val handle: String

    /** The backing USB device's ids, where the platform exposes one. */
    val usbIds: UsbIds?

    /** Whether another app provides this port (a `MidiDeviceService`) rather than hardware behind USB or Bluetooth. */
    val virtual: Boolean
}

/** What discovery needs of the MIDI bus: [AndroidMidiPorts] in the app, a fake in a test. */
interface MidiPorts {
    fun list(): List<MidiPort>

    /**
     * Opens input and output port [portIndex] of [port] as one transport, or the first pair where
     * the device has fewer. Throws where the device cannot be opened.
     */
    suspend fun open(port: MidiPort, portIndex: Int): MidiTransport
}

/** [MidiPorts] over the platform's [MidiManager]: nothing to list on a phone without MIDI support. */
class AndroidMidiPorts(context: Context) : MidiPorts {

    private val manager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val handler = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION") // getDevicesForTransport is API 33+; minSdk here is 26.
    override fun list(): List<MidiPort> = manager?.devices.orEmpty().map(::AndroidMidiPort)

    override suspend fun open(port: MidiPort, portIndex: Int): MidiTransport {
        val manager = manager ?: error("This phone has no MIDI support.")
        val info = (port as? AndroidMidiPort)?.info ?: error("${port.label} is not a port on this phone")
        val device = openDevice(manager, info)
        // Where the device has fewer ports than the catalog expects, fall back to the first: the
        // wrong port answers nothing, which is visible, while refusing to open leaves no session.
        val inPorts = info.inputPortCount
        val outPorts = info.outputPortCount
        val index = if (portIndex < minOf(inPorts, outPorts)) {
            portIndex
        } else {
            Log.w(TAG, "${port.label} has $inPorts/$outPorts ports; falling back from $portIndex to 0")
            FIRST_PORT
        }
        val inputPort = device.openInputPort(index)
            ?: run {
                device.close()
                error("${port.label} has no input port $index to send on.")
            }
        val outputPort = device.openOutputPort(index)
            ?: run {
                inputPort.close()
                device.close()
                error("${port.label} has no output port $index to listen on.")
            }
        return AndroidMidiTransport(device, inputPort, outputPort)
    }

    /**
     * A device the framework refuses to open is reported as an exception so the caller can skip
     * the port. A device that arrives after the caller was cancelled is closed here.
     */
    private suspend fun openDevice(manager: MidiManager, info: MidiDeviceInfo): MidiDevice =
        suspendCancellableCoroutine { continuation ->
            manager.openDevice(
                info,
                { device ->
                    if (device == null) {
                        continuation.resumeWithException(IllegalStateException("Couldn't open ${info.label()}."))
                    } else {
                        continuation.resume(device) { _, opened, _ -> opened.close() }
                    }
                },
                handler,
            )
        }

    private companion object {
        /** The port to fall back to when a descriptor names one the device does not have. */
        const val FIRST_PORT = 0
    }
}

/** [MidiPort] over the platform's [MidiDeviceInfo]. */
private class AndroidMidiPort(val info: MidiDeviceInfo) : MidiPort {
    override val label: String get() = info.label()
    override val physicalKey: String get() = info.usbDevice()?.physicalKey() ?: "midi:${info.id}"
    override val handle: String get() = "midi:${info.id}"
    override val usbIds: UsbIds? get() = info.usbDevice()?.let { UsbIds(it.vendorId, it.productId) }
    override val virtual: Boolean get() = info.type == MidiDeviceInfo.TYPE_VIRTUAL
}

/**
 * The `UsbDevice` behind a MIDI port, where the platform exposes one. `Bundle.get` is
 * deprecated from API 33; minSdk is 26.
 */
@Suppress("DEPRECATION")
private fun MidiDeviceInfo.usbDevice(): UsbDevice? = properties?.get(MidiDeviceInfo.PROPERTY_USB_DEVICE) as? UsbDevice

private fun MidiDeviceInfo.label(): String {
    val name = properties?.getString(MidiDeviceInfo.PROPERTY_NAME)
        ?: properties?.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
    return sanitizeDeviceText(name) ?: "MIDI device $id"
}
