package de.thewolfwalkexperience.software.patchpilot.discovery

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import de.thewolfwalkexperience.software.patchpilot.transport.AndroidMidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.sanitizeDeviceText
import android.hardware.usb.UsbDevice

private const val TAG = "MidiDiscovery"

/**
 * Finds instruments on the MIDI bus, and identifies them by **asking them what they are**.
 *
 * Port names are not used as identity: they vary by OS, by hub, and by whether another app renamed
 * the port. A device that answers a documented query in its own words is authoritative where a
 * name is a guess. A descriptor's `usbHint` narrows which ports are worth probing, but never
 * decides on its own.
 *
 * **Every probe here is read-only and idempotent.** For a Pro-800 that is SysEx `0x06`,
 * "request device name". Nothing from an undocumented range may ever be used for identification -
 * those have unknown side effects, and on this instrument one neighbour is an unconfirmed factory
 * reset.
 */
class MidiDiscovery(private val context: Context) : DeviceDiscovery {

    override val bus = Bus.MIDI

    private val midiManager: MidiManager? =
        context.getSystemService(Context.MIDI_SERVICE) as? MidiManager

    private val handler = Handler(Looper.getMainLooper())

    override suspend fun scan(catalog: List<InstrumentDescriptor>): List<Candidate> {
        val manager = midiManager ?: return emptyList()
        val midiDescriptors = catalog.filter { it.match is DeviceMatch.MidiIdentity }
        if (midiDescriptors.isEmpty()) return emptyList()

        @Suppress("DEPRECATION") // getDevicesForTransport is API 33+; minSdk here is 26.
        val ports = manager.devices.orEmpty()

        return ports.mapNotNull { info ->
            // Probing costs a device open, so narrow first where a hint lets us.
            val plausible = midiDescriptors.filter { descriptor ->
                val match = descriptor.match as DeviceMatch.MidiIdentity
                match.usbHint == null || info.matchesUsb(match.usbHint)
            }
            if (plausible.isEmpty()) return@mapNotNull null

            val identified = identify(manager, info, plausible)
            Candidate(
                descriptor = identified,
                displayName = identified?.name ?: info.label(),
                bus = bus,
                physicalKey = info.physicalKey(),
                handle = info,
            )
        }.filter { it.descriptor != null }
    }

    /** Asks each plausible descriptor's probe and returns the first that answers as expected.
     * Closes every port it opens - this is discovery, not a session.
     *
     * Descriptors are grouped by the port they name, because that is part of the question being
     * asked: an instrument that speaks on cable 3 is silent on port 0, so probing it there would
     * not identify it as itself - it would fail to identify it at all. */
    private suspend fun identify(
        manager: MidiManager,
        info: MidiDeviceInfo,
        plausible: List<InstrumentDescriptor>,
    ): InstrumentDescriptor? {
        val byPort = plausible.groupBy { (it.match as DeviceMatch.MidiIdentity).portIndex }
        for ((portIndex, descriptors) in byPort) {
            val transport = try {
                openTransport(manager, info, portIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${info.label()} port $portIndex to identify it", e)
                continue
            }
            try {
                for (descriptor in descriptors) {
                    val match = descriptor.match as DeviceMatch.MidiIdentity
                    if (probeMatches(transport, match)) return descriptor
                }
            } finally {
                transport.close()
            }
        }
        return null
    }

    private suspend fun probeMatches(transport: MidiTransport, match: DeviceMatch.MidiIdentity): Boolean {
        val framer = SysExFramer()
        val prefix = match.replyPrefix
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            coroutineScope {
                // Same ordering rule as SysExExchange: subscribe before sending, and UNDISPATCHED
                // to make that a guarantee rather than a likelihood. A device that answers
                // promptly would otherwise beat the collector to the punch and look silent.
                val answered = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incoming
                        .mapNotNull { chunk -> framer.feed(chunk).firstOrNull { it.startsWith(prefix) } }
                        .first()
                }
                transport.send(match.probe)
                answered.await()
                true
            }
        } ?: false
    }

    override suspend fun open(candidate: Candidate): Transport {
        val manager = midiManager ?: error("This phone has no MIDI support.")
        val info = candidate.handle as? MidiDeviceInfo
            ?: error("Not a MIDI candidate: ${candidate.displayName}")
        // The session opens the same port the descriptor was identified on. Anything else would
        // identify an instrument through one cable and then talk to it down another.
        val portIndex = (candidate.descriptor?.match as? DeviceMatch.MidiIdentity)?.portIndex ?: 0
        return openTransport(manager, info, portIndex)
    }

    private suspend fun openTransport(
        manager: MidiManager,
        info: MidiDeviceInfo,
        portIndex: Int,
    ): MidiTransport {
        val device = openDevice(manager, info)
        // A catalog port index is a best-effort claim about the instrument. Where the device
        // turns out to have fewer ports than the catalog expects, fall back to the first rather
        // than failing the connect outright: the wrong port is a device that answers nothing,
        // which is recoverable and visible, while refusing to open at all leaves the user with no
        // session and no way to try.
        val inPorts = info.inputPortCount
        val outPorts = info.outputPortCount
        val port = if (portIndex < minOf(inPorts, outPorts)) {
            portIndex
        } else {
            Log.w(TAG, "${info.label()} has $inPorts/$outPorts ports; falling back from $portIndex to 0")
            FIRST_PORT
        }
        val inputPort = device.openInputPort(port)
            ?: run { device.close(); error("${info.label()} has no input port $port to send on.") }
        val outputPort = device.openOutputPort(port)
            ?: run { inputPort.close(); device.close(); error("${info.label()} has no output port $port to listen on.") }
        return AndroidMidiTransport(device, inputPort, outputPort)
    }

    /**
     * A device the framework refuses to open is a failure of this port, reported as an exception
     * so the caller can skip the port; it is not a cancellation of the caller. A device that
     * arrives after the caller has already been cancelled is closed here, since nothing else
     * will ever hold it.
     */
    private suspend fun openDevice(manager: MidiManager, info: MidiDeviceInfo): MidiDevice =
        suspendCancellableCoroutine { continuation ->
            manager.openDevice(
                info,
                { device ->
                    if (device == null) {
                        continuation.resumeWithException(IllegalStateException("Couldn't open ${info.label()}."))
                    } else {
                        continuation.resume(device) { device.close() }
                    }
                },
                handler,
            )
        }

    private companion object {
        /** The port to fall back to when a descriptor names one the device does not have. */
        const val FIRST_PORT = 0

        /** Long enough for a device that is slow to answer, short enough that a port which is not
         * an instrument at all does not stall the connect screen. */
        const val PROBE_TIMEOUT_MS = 600L
    }
}

/** Prefix comparison on raw bytes - the reply must *begin* with what the catalog declares,
 * since the interesting part (an ASCII name) follows it. */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/**
 * The `UsbDevice` behind a MIDI port, where the platform exposes one.
 *
 * One accessor, which confines the deprecation to a single site: `Bundle.get(String)` is
 * deprecated in favour of the typed `getParcelable(String, Class)` from API 33, and this app's
 * minSdk is 26.
 */
@Suppress("DEPRECATION")
private fun MidiDeviceInfo.usbDevice(): UsbDevice? =
    properties?.get(MidiDeviceInfo.PROPERTY_USB_DEVICE) as? UsbDevice

/**
 * A stable-ish identifier for the physical device behind a MIDI port, for cross-bus dedupe.
 *
 * Delegates to [physicalKey] rather than formatting `"usb:$vendorId:$productId"` a second time.
 * The two spellings had to agree exactly or a Nord would appear twice in the picker - once
 * usable, once not - and that agreement was asserted only in a comment.
 */
private fun MidiDeviceInfo.physicalKey(): String = usbDevice()?.physicalKey() ?: "midi:$id"

private fun MidiDeviceInfo.matchesUsb(hint: DeviceMatch.Usb): Boolean {
    val usb = usbDevice() ?: return false
    return usb.vendorId == hint.vendorId && usb.productId == hint.productId
}

private fun MidiDeviceInfo.label(): String {
    val name = properties?.getString(MidiDeviceInfo.PROPERTY_NAME)
        ?: properties?.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
    // Sanitized exactly as a USB string descriptor is, and by the same function.
    return sanitizeDeviceText(name) ?: "MIDI device $id"
}


