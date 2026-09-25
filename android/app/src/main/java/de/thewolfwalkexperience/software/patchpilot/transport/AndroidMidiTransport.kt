// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import android.media.midi.MidiDevice
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

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
     * DROP_OLDEST rather than suspending: this is fed from [MidiReceiver.onSend], a platform
     * callback that must return promptly.
     */
    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    /** MIDI has explicit device add/remove callbacks, so there is no reason to rebuild a working
     * session just because the app was backgrounded. */
    override val rebuildOnResume = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            // Clamped rather than trusted: `android.media.midi` also carries virtual ports
            // published by other apps, and an out-of-range pair would throw on a platform
            // callback thread.
            val start = offset.coerceIn(0, data.size)
            val end = (offset + count).coerceIn(start, data.size)
            if (end <= start) return
            // Copied out of the platform's buffer, which it may reuse the moment this returns.
            _incoming.tryEmit(data.copyOfRange(start, end))
        }
    }

    init {
        outputPort.connect(receiver)
    }

    /** [MidiInputPort.send] writes to the MIDI service's socket, so it runs off the caller's thread. */
    override suspend fun send(bytes: ByteArray) {
        withContext(Dispatchers.IO) { inputPort.send(bytes, 0, bytes.size) }
    }

    /** Closes in reverse order of acquisition; an early failure must not skip the rest, since a
     * leaked MidiDevice keeps the port unavailable to every app on the phone. */
    override fun close() {
        runCatching { outputPort.disconnect(receiver) }
        runCatching { outputPort.close() }
        runCatching { inputPort.close() }
        runCatching { device.close() }
    }
}
