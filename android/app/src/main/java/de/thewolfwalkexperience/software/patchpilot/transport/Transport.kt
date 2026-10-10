// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

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
     * transfer, with no clean way to detect or repair that specifically. False for MIDI: a port
     * opened through Android's MIDI service keeps working across a stay in the background
     * (measured on a Pro-800), so there is no reason to tear a working session down on every
     * resume. The app registers no MIDI device callback; a USB-backed MIDI device's unplug is
     * caught by the USB detach broadcast.
     */
    val rebuildOnResume: Boolean
}

/**
 * The vendor-protocol bus: paired bulk endpoints plus the control pipe.
 *
 * [controlTransfer] is generic on purpose: which request reads what (Nord vendor request 4 reads
 * the firmware version) is a property of a protocol, and lives in that device layer.
 */
interface UsbBulkTransport : Transport {
    fun bulkWrite(data: ByteArray)
    fun bulkRead(bufferSize: Int): ByteArray

    /**
     * Reads up to [bufferSize] bytes, returning empty if nothing arrived within [timeoutMs].
     *
     * Separate from [bulkRead] because the callers disagree about what a timeout is: the Nord
     * path reads only after a request, so silence is a fault, while a USB-MIDI endpoint is polled
     * continuously, so silence is the normal case. The default delegates, which suits a fake.
     */
    fun bulkReadOrEmpty(bufferSize: Int, timeoutMs: Int): ByteArray = bulkRead(bufferSize)
    fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray

    /**
     * Discards whatever the device has already queued on the IN endpoint: a session killed
     * mid-reply leaves the rest of that reply on the device, where the next session's first read
     * would receive it. Called once at the start of a session by a protocol that answers exactly
     * one reply per request; a continuously polled USB-MIDI transport has nothing to drain. The
     * default does nothing.
     */
    fun drainInput() {}
}

/**
 * A MIDI port pair, as a byte stream.
 *
 * [incoming] is not message-aligned, because Android's MIDI callback makes no such promise;
 * [SysExFramer][de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer] turns it into
 * messages. Implemented over `android.media.midi` and directly over a [UsbBulkTransport];
 * nothing above this interface can tell which.
 */
interface MidiTransport : Transport {
    /**
     * Writes one message to the port. Suspending, so a blocking USB bulk write can be moved off
     * the caller's thread.
     */
    suspend fun send(bytes: ByteArray)
    val incoming: Flow<ByteArray>

    /**
     * Whether the instrument is reached through another MIDI interface, over DIN cables at 31.25
     * kbit/s, rather than on its own USB connection. A request and a reply then take time that
     * a USB timeout leaves out: a 3 kB message about two seconds, a preset dump half a minute.
     */
    val slowLink: Boolean get() = false

    /**
     * Called after a request went unanswered, before it is asked again: reopens whatever the
     * platform may have dropped meanwhile. Android's MIDI service drops an app's receiver for good
     * when its socket to the app overflows, and nothing says so: the port just stays silent. A
     * transport with nothing to reopen does nothing, and one that has been fine loses nothing.
     */
    suspend fun recover() {}
}
