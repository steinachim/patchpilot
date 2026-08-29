package de.thewolfwalkexperience.software.patchpilot.transport

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "UsbMidiBulkTransport"

/**
 * [MidiTransport] over a raw [UsbBulkTransport], packing the USB-MIDI class's 32-bit event packets
 * by hand.
 *
 * [AndroidMidiTransport] is the default because the platform does this work for us. It can only do
 * it for a device that declares a **MIDIStreaming interface**, which is what `MidiManager` matches
 * on - and a Yamaha Motif XS does not. Its single interface is class `0xFF` (vendor-specific), so
 * no class driver binds it, `MidiManager` never enumerates it, and the whole `android.media.midi`
 * path is unavailable for that instrument: the descriptors declare no MIDIStreaming interface,
 * macOS CoreMIDI exposes no port for the device, and no interface driver is bound to it in the IO
 * registry.
 *
 * What the descriptors *don't* say is that the interface nonetheless speaks ordinary USB-MIDI:
 * 4-byte event packets, high nibble the cable number, low nibble the Code Index Number. A
 * Universal Device Inquiry packed this way and written to the bulk OUT endpoint gets back an
 * identical reply. So the instrument is reachable; it just needs the packing the platform would
 * otherwise have done.
 *
 * This is the swap [MidiTransport]'s own documentation anticipated: nothing above the
 * transport layer changes, because `SysExExchange` and the families above it were always written
 * against the interface rather than against `android.media.midi`.
 *
 * @param cable which USB-MIDI cable to stamp on outgoing packets and accept on incoming ones.
 *   Part of the address rather than a detail of opening the link. Of the sixteen possible cables
 *   on a Motif XS, **0 and 3 both work completely** - each answers an inquiry and returns voice and
 *   drum dumps on the cable the request went out on - cables 4-7 accept a request but answer on 3
 *   (so a transport pinned to 4 would send fine and hear nothing), and 1, 2 and 8-15 are silent.
 *
 *   Three is the default because it is what the Motif XS Editor used, which makes it the
 *   best-tested path through the firmware; it is a convention, not a constraint. Cable 0 carrying
 *   only Active Sensing is a fact about the editor's own choice, not a constraint the instrument
 *   itself imposes. Active Sensing on cable 0 does not disturb a dump there either: [SysExFramer]
 *   drops realtime bytes, so a 12.6 kB drum kit reassembles intact.
 */
class UsbMidiBulkTransport(
    private val bulk: UsbBulkTransport,
    private val cable: Int,
    scope: CoroutineScope,
    private val readBufferSize: Int = DEFAULT_READ_BUFFER,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : MidiTransport {

    /**
     * DROP_OLDEST and a **deep** buffer: the reader loop below must keep draining the endpoint
     * whatever the collector is doing, and a reader that blocks mid-dump loses the rest of it.
     *
     * **Sized against the largest message and the longest stall, not against a typical one.**
     * One element is emitted per 64-byte USB transfer, each carrying only ~6 MIDI bytes, so a
     * 1.9 kB voice is ~320 elements and a 12.6 kB drum kit ~2,100 - already four times the 512
     * this used to hold. The buffer only actually fills when the collector falls behind, and the
     * thing that makes it fall behind is not slow code: a **148 ms GC pause** during a live scan,
     * at the ~473 us the instrument leaves between transfers, is ~310 elements of backlog on its
     * own.
     *
     * When it does overflow, DROP_OLDEST discards the *front* of a message in flight. The framer
     * then sees a truncated stream, drops it, and the read times out - which is exactly the
     * "2 slots could not be read" that scattered itself across 416-voice scans and succeeded on
     * the retry every time. Retries hid it; they did not fix it.
     *
     * 8192 covers the worst message plus a stall several times longer than any yet seen. The cost
     * is bounded and small: elements are ~6-byte arrays, so a full buffer is a couple of hundred
     * kilobytes, and it is only ever *reached* in the pathological case this exists to survive.
     *
     * **Honest about what this did and did not fix.** Truncated dumps kept arriving after this
     * change at the same rate as before it; what stopped them costing a slot was making
     * well-formedness part of the read's matcher, so the retry could act on them (see
     * `MotifXsInstrument.readSlot`). This buffer is defensible on the arithmetic above and was
     * *not* measured to reduce the corruption rate. If this ever needs re-justifying, that is the
     * experiment: put it back to 512 and count damaged replies.
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
                // **Never rethrown.** This used to be `if (isActive) throw it`, which handed the
                // exception to the scope - and a SupervisorJob does not consume one, it only spares
                // the siblings. With no CoroutineExceptionHandler installed anywhere it reached the
                // platform's default handler, so an endpoint erroring mid-session took the whole
                // process down. A dead endpoint is a session outcome, not a programming error.
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
                // The only suspension point on the idle path, and it has to be here. A read that
                // returns nothing normally cost [readTimeoutMs] inside the driver, but nothing in
                // this loop guarantees that: a disconnected endpoint fails immediately, and without
                // this the loop spins as fast as the dispatcher allows, starving every other
                // coroutine on it - including whoever is waiting on [incoming].
                //
                // `delay` rather than `yield` because yield reschedules without idling, which is no
                // help at all when a failing endpoint returns instantly. It costs nothing on the
                // normal path: reaching here means the driver already waited [readTimeoutMs] and
                // came back with nothing, so one more millisecond is noise next to the 20 it just
                // spent - and a read carrying data never gets here at all.
                delay(IDLE_BACKOFF_MS)
                continue
            }
            val bytes = unpackEvents(raw, cable)
            if (bytes.isNotEmpty()) _incoming.tryEmit(bytes)
        }
    }

    /**
     * Sends one complete SysEx message.
     *
     * Complete rather than arbitrary bytes because that is all any family above this layer sends,
     * and because a partial message has no correct packing: the final packet's Code Index Number
     * states how many bytes it carries, which cannot be known until the `F7` is in hand. A
     * transport that silently packed a fragment would produce a stream no receiver can reassemble.
     */
    override fun send(bytes: ByteArray) {
        require(bytes.size >= 2 && bytes.first() == 0xF0.toByte() && bytes.last() == 0xF7.toByte()) {
            "UsbMidiBulkTransport sends complete F0..F7 messages; got ${bytes.size} bytes " +
                "starting ${bytes.firstOrNull()?.toInt()?.and(0xFF)?.toString(16)}"
        }
        bulk.bulkWrite(packSysEx(bytes, cable))
    }

    override fun close() {
        reader.cancel()
        bulk.close()
    }

    companion object {
        /**
         * One USB transfer per read, matching the endpoint's `wMaxPacketSize`.
         *
         * **Deliberately not larger, and this was measured.** A Motif XS always sends full
         * 64-byte transfers - padded, with only ~6 bytes of each carrying MIDI - so a bulk read
         * never sees the short packet that would terminate it early. Ask for more and the *last*
         * read of every dump blocks until its timeout expires: at 4096/100 ms that cost +84 ms on
         * a 158 ms voice and +100 ms on every drum kit, which across a 416-voice index is over a
         * minute of waiting for data that had already arrived.
         *
         * At 64 the read returns as each transfer lands and the timeout stops mattering -
         * measured identical at 1 ms, 5 ms and 50 ms. The cost is more round trips (~2,100 for a
         * drum kit), which is nothing next to the ~473 us the instrument takes between transfers.
         */
        const val DEFAULT_READ_BUFFER = 64

        /**
         * Short, because a timeout here is the normal idle case rather than an error - the loop
         * simply asks again. Long enough that an idle instrument does not spin the CPU.
         *
         * With [DEFAULT_READ_BUFFER] at one packet this no longer affects throughput at all; it
         * only sets how often an idle transport wakes up.
         */
        const val DEFAULT_READ_TIMEOUT_MS = 20

        /**
         * How long the reader idles after a read that brought nothing back.
         *
         * Small next to the [DEFAULT_READ_TIMEOUT_MS] the driver has already spent by the time a
         * read comes back empty, and enough that a driver returning instantly - which is what a
         * disconnected endpoint does - cannot turn this loop into a busy-spin.
         */
        private const val IDLE_BACKOFF_MS = 1L

        /** Longer than [IDLE_BACKOFF_MS]: a *failing* read is not the normal idle case, and there
         * is no point retrying one at full speed. */
        private const val FAILURE_BACKOFF_MS = 20L

        /**
         * Consecutive read failures before the reader concludes the endpoint is gone and stops.
         *
         * Stopping rather than throwing is what keeps this off the crash path (see the reader's own
         * comment). Nothing above needs telling: with the reader stopped, whatever operation is in
         * flight stops receiving and ends in the timeout [SysExExchange] already raises and the UI
         * already reports.
         */
        private const val MAX_CONSECUTIVE_FAILURES = 20

        /**
         * MIDI bytes carried by each Code Index Number; -1 where the CIN is reserved.
         *
         * The two that matter for SysEx are 4 (starts or continues, always three bytes) and 5/6/7
         * (ends with one, two or three). The rest are here so a channel message sharing the
         * endpoint is skipped by the right width rather than desynchronising the scan.
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
         * Pulls [cable]'s MIDI bytes out of a bulk transfer, dropping every other cable's.
         *
         * Filtering here rather than upstream because the cables are separate MIDI streams that
         * merely share an endpoint. Merging them is the same class of mistake as merging the two
         * endpoints: on a Motif XS it would splice 590 Active Sensing bytes from cable 0 into the
         * middle of cable 3's dumps.
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
