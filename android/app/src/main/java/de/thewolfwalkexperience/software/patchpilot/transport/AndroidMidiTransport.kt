// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import android.media.midi.MidiDevice
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

private const val TAG = "AndroidMidiTransport"

/**
 * [MidiTransport] over `android.media.midi`, which handles USB-MIDI enumeration and unpacks the
 * class's event packets into a plain MIDI byte stream.
 *
 * [inputPort] is where we write (Android names ports from the device's point of view) and
 * [outputPort] is where the device's replies arrive.
 */
class AndroidMidiTransport(
    private val device: MidiDevice,
    private val inputPort: MidiInputPort,
    private val outputPort: MidiOutputPort,
) : MidiTransport {

    /**
     * DROP_OLDEST rather than suspending: this is fed from [MidiReceiveQueue]'s consumer, which
     * must not wait on a slow collector either.
     *
     * Sized for the fastest large reply any family reads, an Axe-Fx III preset dump: 49 KB at
     * about 105 bytes per millisecond. Assumed, not measured on this path: a platform that hands
     * over one USB-MIDI event (three data bytes) per callback makes that ~16,000 elements, so a
     * 150 ms collector stall (as measured on the Motif XS path) is a backlog of about 5,000.
     * Dropping any of them corrupts the dump; the family's validation catches that, but only by
     * reading the whole preset again. The buffer grows only as far as a real backlog takes it.
     *
     * The Subsequent 37's 203,800-byte dump is larger, but at about 1.4 bytes per millisecond it
     * builds a far smaller backlog.
     */
    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 8192,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    /** A MIDI port keeps working across a stay in the background - see [Transport.rebuildOnResume]. */
    override val rebuildOnResume = false

    private val scope = transportScope("MIDI port")

    private val queue = MidiReceiveQueue(scope) { _incoming.tryEmit(it) }

    private var readerBoosted = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (!readerBoosted) {
                readerBoosted = true
                raiseReaderPriority()
            }
            queue.push(data, offset, count)
        }
    }

    /**
     * Called on the platform's reader thread, once: a thread at normal priority is descheduled
     * for long enough, on a busy phone, to overrun the service's small socket during a dump. A
     * refusal is only logged; the queue alone is still worth having.
     */
    private fun raiseReaderPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't raise the MIDI reader thread's priority", e)
        }
    }

    init {
        outputPort.connect(receiver)
    }

    /** [MidiInputPort.send] writes to the MIDI service's socket, so it runs off the caller's thread. */
    override suspend fun send(bytes: ByteArray) {
        withContext(Dispatchers.IO) { inputPort.send(bytes, 0, bytes.size) }
    }

    /**
     * Closes in reverse order of acquisition; an early failure must not skip the rest, since a
     * leaked MidiDevice keeps the port unavailable to every app on the phone.
     */
    override fun close() {
        runCatching { outputPort.disconnect(receiver) }
        scope.cancel()
        runCatching { outputPort.close() }
        runCatching { inputPort.close() }
        runCatching { device.close() }
    }
}
