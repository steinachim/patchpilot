package de.thewolfwalkexperience.software.patchpilot.transport

import kotlinx.coroutines.flow.Flow

/**
 * Bytes in, bytes out. Nothing here knows what a message is - that belongs to whichever family is
 * speaking over it.
 */
interface Transport {
    fun close()

    /**
     * Whether returning to the foreground should proactively rebuild the session.
     *
     * True for USB host, where unrelated bus activity while backgrounded (a USB keyboard being
     * unplugged and replugged, say) can leave the endpoints erroring on every subsequent
     * transfer, with no clean way to detect or repair that specifically. False for
     * MIDI, which has explicit device add/remove callbacks and no reason to tear down a working
     * session on every resume.
     */
    val rebuildOnResume: Boolean
}

/**
 * The vendor-protocol bus: paired bulk endpoints plus the control pipe.
 *
 * [controlTransfer] is generic on purpose. It used to be `controlReadFirmware(wLength)`, which is
 * not a transport concept at all - it is Nord vendor request 4, and it now lives in the Nord
 * device layer where the rest of that protocol's constants are.
 */
interface UsbBulkTransport : Transport {
    fun bulkWrite(data: ByteArray)
    fun bulkRead(bufferSize: Int): ByteArray

    /**
     * Reads up to [bufferSize] bytes, returning empty if nothing arrived within [timeoutMs].
     *
     * Separate from [bulkRead] because the two callers disagree about what a timeout *is*. The
     * Nord path reads only after asking for something, so silence is a fault. A MIDI IN endpoint
     * is polled continuously whether or not anything was asked for, so silence is the normal case
     * and throwing on it would turn an idle instrument into an error loop.
     *
     * The default delegates, which is right for a fake serving a scripted exchange.
     */
    fun bulkReadOrEmpty(bufferSize: Int, timeoutMs: Int): ByteArray = bulkRead(bufferSize)
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray
}

/**
 * A MIDI port pair, as a byte stream.
 *
 * [incoming] is deliberately **not** message-aligned: Android's MIDI callback makes no such
 * promise, and a type that claimed otherwise would be a lie the whole stack above would then
 * depend on. [SysExFramer][de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer] is what
 * turns it into messages.
 *
 * Nothing in this interface mentions Android, which is the point: the same contract is
 * implementable over `android.media.midi` (where the platform unpacks USB-MIDI event packets for
 * us) or directly over [UsbBulkTransport] by packing those 32-bit packets by hand. Swapping one
 * for the other touches no code above this line.
 */
interface MidiTransport : Transport {
    fun send(bytes: ByteArray)
    val incoming: Flow<ByteArray>
}
