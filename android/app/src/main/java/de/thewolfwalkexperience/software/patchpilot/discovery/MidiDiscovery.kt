// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import android.content.Context
import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.UsbIds
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MidiDiscovery"

/**
 * Finds instruments on the MIDI bus by asking each port what it is. Port names are never used as
 * identity (they vary by OS, hub and other apps); a descriptor's `usbHint` narrows which ports are
 * probed but never decides. Every probe is read-only and idempotent - see
 * [DeviceMatch.MidiIdentity].
 *
 * A silent port costs every descriptor's probe timeout, so two kinds of port are never probed: a
 * virtual one, which another app provides and none of the supported instruments is reached
 * through, and the MIDI interface of a device the catalog matches over USB (a Nord's), which the
 * USB scan claims through its vendor interface. A port whose USB ids are a descriptor's
 * `launchOnUsbAttach` goes first, with that descriptor's probe first, so an instrument plugged in
 * directly answers before anything behind a separate interface is asked.
 *
 * The bus comes in as [MidiPorts], so the probing runs in a unit test against fake ports.
 */
class MidiDiscovery(private val ports: MidiPorts) : DeviceDiscovery {

    constructor(context: Context) : this(AndroidMidiPorts(context))

    override val bus = Bus.MIDI

    override suspend fun scan(catalog: List<InstrumentDescriptor>): List<Candidate> {
        val midiDescriptors = catalog.filter { it.match is DeviceMatch.MidiIdentity }
        if (midiDescriptors.isEmpty()) return emptyList()

        val listed = ports.list()
        val claimedOverUsb = catalog.mapNotNull { descriptor ->
            (descriptor.match as? DeviceMatch.Usb)?.let { UsbIds(it.vendorId, it.productId) }
        }.toSet()
        val (virtual, hardware) = listed.partition { it.virtual }
        val (claimed, unclaimed) = hardware.partition { port -> port.usbIds?.let { it in claimedOverUsb } == true }
        // Stable, so ports keep the platform's order within each group.
        val probed = unclaimed.sortedByDescending { port -> midiDescriptors.any { it.launchesFor(port) } }
        Log.i(
            TAG,
            "Probing ${probed.size} of ${listed.size} MIDI ports; skipped ${virtual.size} virtual and " +
                "${claimed.size} claimed over USB",
        )

        return probed.mapNotNull { port ->
            // Probing costs a device open, so narrow first where a hint lets us.
            val plausible = midiDescriptors.filter { descriptor ->
                val hint = (descriptor.match as DeviceMatch.MidiIdentity).usbHint
                hint == null || port.usbIds == UsbIds(hint.vendorId, hint.productId)
            }.sortedByDescending { it.launchesFor(port) }
            if (plausible.isEmpty()) return@mapNotNull null

            val identified = identify(port, plausible) ?: return@mapNotNull null
            Candidate(
                descriptor = identified,
                displayName = identified.name,
                bus = bus,
                physicalKey = port.physicalKey,
                handle = port,
            )
        }
    }

    /**
     * Asks each plausible descriptor's probe on the port it names and returns the first that
     * answers as expected. Closes every port it opens.
     */
    private suspend fun identify(port: MidiPort, plausible: List<InstrumentDescriptor>): InstrumentDescriptor? {
        val byPort = plausible.groupBy { (it.match as DeviceMatch.MidiIdentity).portIndex }
        for ((portIndex, descriptors) in byPort) {
            val transport = try {
                ports.open(port, portIndex)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open ${port.label} port $portIndex to identify it", e)
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
                // Subscribed before the send, as SysExExchange does: the flow has no replay.
                val answered = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incoming
                        .mapNotNull { chunk ->
                            framer.feed(chunk).firstOrNull { answersProbe(it, match.probe, prefix) }
                        }
                        .first()
                }
                transport.send(match.probe)
                answered.await()
                true
            }
        } ?: false
    }

    override suspend fun open(candidate: Candidate): Transport {
        val port = candidate.handle as? MidiPort ?: error("Not a MIDI candidate: ${candidate.displayName}")
        // The same port the descriptor was identified on.
        val portIndex = (candidate.descriptor.match as? DeviceMatch.MidiIdentity)?.portIndex ?: 0
        return ports.open(port, portIndex)
    }

    private companion object {
        /**
         * Long enough for a device that is slow to answer, short enough that a port which is not
         * an instrument at all does not stall the connect screen.
         */
        const val PROBE_TIMEOUT_MS = 600L
    }
}

/** Whether [port] is this MIDI instrument plugged in directly: its USB ids are the descriptor's `launchOnUsbAttach`. */
private fun InstrumentDescriptor.launchesFor(port: MidiPort): Boolean {
    val ids = (match as? DeviceMatch.MidiIdentity)?.launchOnUsbAttach ?: return false
    return port.usbIds == ids
}

/**
 * Whether [message] answers [probe]: it begins with what the catalog declares as the reply, since
 * the interesting part (an ASCII name) follows that prefix, and it is not the probe itself. A
 * virtual or loopback port sends back whatever it is sent, and an Axe-Fx III's reply prefix is the
 * first six bytes of its own probe, so an echo would otherwise identify every such port as one.
 */
internal fun answersProbe(message: ByteArray, probe: ByteArray, prefix: ByteArray): Boolean =
    message.startsWith(prefix) && !message.contentEquals(probe)

/** Prefix comparison on raw bytes. */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
