package de.thewolfwalkexperience.software.patchpilot.transport

import android.media.midi.MidiDevice
import android.media.midi.MidiInputPort
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [MidiTransport] over `android.media.midi`.
 *
 * The platform handles USB-MIDI enumeration and unpacks the class's 32-bit event packets (cable
 * numbers, code index numbers) into a plain MIDI byte stream, which is most of why this is the
 * default rather than a hand-written USB-MIDI driver. It also brings BLE-MIDI and other
 * apps' virtual ports along for free, later, at no cost now.
 *
 * Naming: this class is Android-specific but [MidiTransport] is not, which is what lets a
 * [UsbMidiBulkTransport] replace it without anything above the transport layer noticing.
 *
 * [inputPort] is where *we* write (Android names ports from the device's point of view, so the
 * device's input is our output) and [outputPort] is where the device's replies arrive.
 */
class AndroidMidiTransport(
    private val device: MidiDevice,
    private val inputPort: MidiInputPort,
    private val outputPort: MidiOutputPort,
) : MidiTransport {

    /**
     * DROP_OLDEST rather than suspending: this is fed from [MidiReceiver.onSend], a platform
     * callback that must return promptly. Blocking it to wait for a slow collector would stall
     * the MIDI service's own thread.
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
            // Clamped rather than trusted. The framework supplies and validates these, so this is
            // belt-and-braces for the app's own USB link - but `android.media.midi` also carries
            // virtual ports published by *other apps on the phone*, which makes this the one
            // inbound path that is not our own connection. An out-of-range pair would throw inside
            // a platform callback thread, which is a bad place to throw.
            val start = offset.coerceIn(0, data.size)
            val end = (offset + count).coerceIn(start, data.size)
            if (end <= start) return
            // Copied out of the platform's buffer, which it is free to reuse the moment this
            // returns - handing the array straight on would race the next callback.
            _incoming.tryEmit(data.copyOfRange(start, end))
        }
    }

    init {
        outputPort.connect(receiver)
    }

    override fun send(bytes: ByteArray) {
        inputPort.send(bytes, 0, bytes.size)
    }

    /** Closes in reverse order of acquisition, and never lets an early failure skip the rest -
     * a leaked open MidiDevice keeps the port unavailable to every other app on the phone. */
    override fun close() {
        runCatching { outputPort.disconnect(receiver) }
        runCatching { outputPort.close() }
        runCatching { inputPort.close() }
        runCatching { device.close() }
    }
}
