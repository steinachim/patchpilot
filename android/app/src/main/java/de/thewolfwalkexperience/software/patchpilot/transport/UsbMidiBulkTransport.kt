// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UsbMidiBulkTransport"

/**
 * [MidiTransport] over a raw [UsbBulkTransport], packing the USB-MIDI class's 32-bit event packets
 * by hand.
 *
 * For a device with no MIDIStreaming interface, which `MidiManager` never enumerates: a Yamaha
 * Motif XS has a single vendor-specific interface that nonetheless speaks ordinary USB-MIDI
 * (4-byte event packets, high nibble the cable number, low nibble the Code Index Number).
 * `SysExExchange` and the families above it are written against [MidiTransport], so nothing
 * above this layer changes.
 *
 * @param cable which USB-MIDI cable to stamp on outgoing packets and accept on incoming ones. On
 *   a Motif XS cables 0 and 3 both carry the full protocol, cables 4-7 accept a request but answer
 *   on 3, and 1, 2 and 8-15 are silent. Three is what the vendor's editor uses. The instrument
 *   sends Active Sensing on cable 0, which [SysExFramer] would drop as a realtime byte anyway.
 */
class UsbMidiBulkTransport(
    private val bulk: UsbBulkTransport,
    private val cable: Int,
    scope: CoroutineScope,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : MidiTransport {

    /**
     * DROP_OLDEST and a deep buffer: the reader loop must keep draining the endpoint whatever the
     * collector is doing, and a reader that blocks mid-dump loses the rest of it.
     *
     * Sized against the largest message and the longest stall. One element is emitted per
     * 64-byte USB transfer carrying ~6 MIDI bytes, so a 1.9 kB voice is ~320 elements and a
     * 12.6 kB drum kit ~2,100; a 148 ms GC pause (measured) at the ~473 us between transfers is
     * ~310 elements of backlog. On overflow DROP_OLDEST discards the front of a message in
     * flight, the framer drops the truncated stream and the read is retried (see
     * `MotifXsInstrument.readSlot`). A full buffer of ~6-byte arrays is a couple of hundred
     * kilobytes. The size follows from this arithmetic; it was not measured against the
     * corruption rate.
     */
    private val _incoming = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 8192,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    /** USB host mode, so the same reasoning as [AndroidUsbBulkTransport]: rebuild on resume. */
    override val rebuildOnResume = true

    private val reader: Job = scope.launch {
        var consecutiveFailures = 0
        while (isActive) {
            val raw = try {
                bulk.bulkReadOrEmpty(readBufferSize, readTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never rethrown: a dead endpoint is a session outcome, not something for the
                // scope's exception handler (see transportScope).
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w(TAG, "Giving up on the IN endpoint after $consecutiveFailures " +
                        "consecutive failures; this session is over.", e)
                    return@launch
                }
                delay(FAILURE_BACKOFF_MS)
                continue
            }
            consecutiveFailures = 0
            if (raw.isEmpty()) {
                // The idle path's suspension point: a transport that returns instantly (a fake)
                // would otherwise spin this loop and starve every other coroutine on the
                // dispatcher. A real read has already waited out [readTimeoutMs], so one more
                // millisecond costs nothing.
                delay(IDLE_BACKOFF_MS)
                continue
            }
            val bytes = unpackEvents(raw, cable)
            if (bytes.isNotEmpty()) _incoming.tryEmit(bytes)
        }
    }

    /**
     * Sends one complete SysEx message. A partial message has no correct packing: the final
     * packet's Code Index Number states how many bytes it carries, which needs the `F7` in hand.
     */
    override suspend fun send(bytes: ByteArray) {
        require(bytes.size >= 2 && bytes.first() == 0xF0.toByte() && bytes.last() == 0xF7.toByte()) {
            "UsbMidiBulkTransport sends complete F0..F7 messages; got ${bytes.size} bytes " +
                "starting ${bytes.firstOrNull()?.toInt()?.and(0xFF)?.toString(16)}"
        }
        // A bulk write blocks until the instrument has taken the data, and the caller is
        // normally on the main dispatcher.
        val packed = packSysEx(bytes, cable)
        withContext(Dispatchers.IO) { bulk.bulkWrite(packed) }
    }

    /**
     * Stops the reader, then closes the bulk transport once the reader has actually stopped: it
     * may be inside `bulkReadOrEmpty` for up to [readTimeoutMs] more, and closing the connection
     * under a transfer in flight is a use-after-close in the platform's USB host code.
     * `invokeOnCompletion` runs at once if the reader has already given up.
     */
    override fun close() {
        reader.cancel()
        reader.invokeOnCompletion { bulk.close() }
    }

    companion object {
        /**
         * One USB transfer per read, matching the endpoint's `wMaxPacketSize`. A Motif XS always
         * sends full 64-byte transfers, so a larger request never sees the short packet that would
         * end it early and the last read of every dump waits out its timeout instead (measured:
         * +84 ms on a 158 ms voice at 4096/100 ms). At 64 the read returns as each transfer
         * lands, and the timeout stops mattering.
         */
        const val DEFAULT_READ_BUFFER = 64

        /**
         * Short, because a timeout here is the normal idle case. With [DEFAULT_READ_BUFFER] at one
         * packet it only sets how often an idle transport wakes up.
         */
        const val DEFAULT_READ_TIMEOUT_MS = 20

        /** How long the reader idles after an empty read, so a driver returning instantly cannot
         * turn the loop into a busy-spin. */
        private const val IDLE_BACKOFF_MS = 1L

        /** Longer than [IDLE_BACKOFF_MS]: a failing read is not the normal idle case. */
        private const val FAILURE_BACKOFF_MS = 20L

        /**
         * Consecutive read failures before the reader concludes the endpoint is gone and stops.
         * With the reader stopped, whatever operation is in flight ends in the timeout
         * [SysExExchange] raises.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 20

        /**
         * MIDI bytes carried by each Code Index Number; -1 where the CIN is reserved. SysEx uses
         * 4 (start/continue, three bytes) and 5/6/7 (end with one, two or three); the rest let a
         * channel message sharing the endpoint be skipped by the right width.
         */
        private val CIN_LENGTHS = intArrayOf(
            -1, -1, 2, 3, 3, 1, 2, 3, 3, 3, 3, 3, 2, 2, 3, 1,
        )

        /** Wraps one complete SysEx message into USB-MIDI event packets on [cable]. */
        fun packSysEx(message: ByteArray, cable: Int): ByteArray {
            val prefix = (cable shl 4)
            val out = ByteArray(((message.size + 2) / 3) * 4)
            var read = 0
            var write = 0
            while (message.size - read > 3) {
                out[write] = (prefix or 0x4).toByte()
                out[write + 1] = message[read]
                out[write + 2] = message[read + 1]
                out[write + 3] = message[read + 2]
                read += 3
                write += 4
            }
            val remaining = message.size - read
            // The final packet's CIN states how many bytes it carries: 0x5/0x6/0x7 for 1/2/3.
            out[write] = (prefix or (0x4 + remaining)).toByte()
            for (i in 0 until remaining) out[write + 1 + i] = message[read + i]
            return out
        }

        /**
         * Pulls [cable]'s MIDI bytes out of a bulk transfer, dropping every other cable's: the
         * cables are separate MIDI streams that share an endpoint, and merging them would splice
         * cable 0's Active Sensing into cable 3's dumps.
         */
        fun unpackEvents(payload: ByteArray, cable: Int): ByteArray {
            val out = ByteArray(payload.size)
            var write = 0
            var i = 0
            while (i + 3 < payload.size) {
                val header = payload[i].toInt() and 0xFF
                if (header != 0) {
                    val length = CIN_LENGTHS[header and 0x0F]
                    if (length > 0 && (header shr 4) == cable) {
                        for (j in 0 until length) out[write++] = payload[i + 1 + j]
                    }
                }
                i += 4
            }
            return out.copyOf(write)
        }
    }
}
