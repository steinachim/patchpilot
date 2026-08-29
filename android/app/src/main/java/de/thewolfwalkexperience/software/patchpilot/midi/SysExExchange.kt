package de.thewolfwalkexperience.software.patchpilot.midi

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SysExExchange"

/**
 * Turns a MIDI byte stream into request/response, with **one outstanding request at a time**.
 *
 * That constraint is the protocol's, not a simplification: a Pro-800 reply carries no request id
 * and no sequence number, so a `0x78` is matched to a `0x77` only by being the next `0x78` to
 * arrive. Two requests in flight would be indistinguishable. The [Mutex] makes that explicit
 * rather than leaving it to whoever calls next.
 *
 * @param scope the collector's lifetime; cancelling it stops draining [transport]. **Ownership
 *   passes to this object**: [close] cancels the whole scope, not merely the collector it started.
 *   Both families construct one solely to hand it here, so there is no second stakeholder - and
 *   the alternative left the scope alive for the process's lifetime, resting on every child
 *   remembering to cancel itself.
 */
class SysExExchange(
    private val transport: MidiTransport,
    private val scope: CoroutineScope,
    private val framer: SysExFramer = SysExFramer(),
    private val defaultTimeout: Duration = 2.seconds,
    private val retries: Int = 1,
) {

    /** The transport's own resume policy - see [de.thewolfwalkexperience.software.patchpilot
     * .core.Instrument.rebuildOnResume], which is what actually consults it. */
    val rebuildOnResume: Boolean get() = transport.rebuildOnResume
    private val lock = Mutex()

    /** The collector draining [transport]; cancelled by [close] so the session actually ends. */
    private var collector: kotlinx.coroutines.Job? = null

    /**
     * Replay of 0 with a generous buffer: an exchange subscribes *before* it sends, so it cannot
     * miss its own reply, and DROP_OLDEST means a device that floods the bus cannot make this
     * suspend and stall the collector.
     *
     * **Sized for a whole answer, not for one message.** [exchangeSequence] collects a reply that
     * is many messages long, and DROP_OLDEST discards from the *front* - so a buffer smaller than
     * the sequence silently eats its opening, which is where the header and the Common block are.
     * A Motif XS drum voice answers a documented read with **83** messages against a normal
     * voice's 26; at the old capacity of 64 the drum read lost its first nineteen and reported
     * "carried 0 Common blocks". A unit test catches this reliably, because the sequence length
     * is a fixed property of the instrument rather than a timing fluke.
     */
    private val messages = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Complete SysEx messages nobody was waiting for - CC echo, spontaneous status, other
     * devices on a shared bus. Exposed rather than dropped, since that traffic is a feature
     * (a knob being turned) as often as it is noise. */
    val unsolicited: SharedFlow<ByteArray> = messages.asSharedFlow()

    init {
        // UNDISPATCHED so the collector subscribes to [transport] *during construction*, not
        // whenever the dispatcher gets around to it.
        //
        // This is not a tidiness point. Both this flow and the transports' own use replay = 0, and
        // a SharedFlow with no subscriber discards what is emitted to it - so a reply arriving in
        // the window between constructing this and the collector actually starting is gone, and
        // the exchange that wanted it waits out its whole timeout before retrying. Found by the
        // Pro-800 index test, which asked for ten dumps and watched eleven go out.
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { chunk ->
                framer.feed(chunk).forEach {
                    // **Log what arrives with nobody waiting for it.** [exchange] logs a reply it
                    // rejects, but only while it is running; a message arriving when no exchange is
                    // in flight has no subscriber, and with replay = 0 it is emitted to nobody and
                    // gone. That is exactly where a fire-and-forget write's status reply lands, so
                    // without this the instrument's own account of a refusal is unobservable - as a
                    // hardware run showed, eight writes producing not one line about their status.
                    //
                    // Gated on there being no subscriber, which is what makes it quiet: during a
                    // 400-slot scan every dump has one, so this logs none of them.
                    if (messages.subscriptionCount.value == 0) {
                        Log.d(TAG, "unsolicited ${it.size}B ${it.prefixHex()}")
                    }
                    messages.emit(it)
                }
            }
        }
    }

    /**
     * Sends [request] and returns the first inbound message satisfying [matches].
     *
     * [matches] is supplied by the caller rather than being "same message type", because a type
     * alone is not enough: after a timeout, a late reply to the *previous* request is still the
     * right type, and returning it would hand back the wrong preset - which on a write path is
     * data loss. A dump matcher checks the echoed address too.
     *
     * Retries [retries] times before giving up, since on a stream transport a single dropped
     * message is normal and making a 400-slot scan fail on the first hiccup would be unusable.
     * Each attempt starts from a clean framer and a drained buffer, so a late reply to the
     * previous attempt cannot satisfy this one.
     */
    suspend fun exchange(
        request: ByteArray,
        what: String,
        timeout: Duration = defaultTimeout,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = lock.withLock {
        var lastError: Throwable? = null
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "retrying '$what' (attempt ${attempt + 1})")
                drainStale()
            }
            // There is deliberately no framer.reset() here. It was called at this point, from
            // *this* coroutine, while the collector coroutine feeds the same framer - a plain data
            // race on its buffer, which could truncate a reply that was mid-arrival and turn it
            // into the timeout the reset was meant to help with. It also bought nothing: SysEx is
            // self-delimiting, and the framer already abandons a partial message when the next F0
            // arrives. Suspected in a live scan that timed out on scattered slots and succeeded on
            // the retry every time.
            val reply = withTimeoutOrNull(timeout) {
                coroutineScope {
                    // Subscribe *before* sending, or a reply that arrives in between is emitted
                    // to nobody and then waited for until the timeout. UNDISPATCHED is what makes
                    // that guarantee real rather than likely: it runs this body immediately, on
                    // this thread, up to its first suspension - which is `first` having actually
                    // subscribed. A plain `async` merely schedules it, and on a fast device the
                    // send wins that race often enough to matter.
                    val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                        messages.first { message ->
                            matches(message).also { matched ->
                                // A reply that arrives and is rejected is a completely different
                                // problem from silence, and the two are indistinguishable from a
                                // timeout alone. Logging the first bytes of a non-match is what
                                // separates "the instrument said something we did not expect"
                                // from "the instrument said nothing".
                                if (!matched) {
                                    Log.d(TAG, "'$what': ignoring ${message.size}B reply ${message.prefixHex()}")
                                }
                            }
                        }
                    }
                    transport.send(request)
                    awaited.await()
                }
            }
            if (reply != null) return@withLock reply
            lastError = InstrumentException.Timeout(what)
        }
        throw lastError ?: InstrumentException.Timeout(what)
    }

    /**
     * Sends one request and collects **every** reply until [done] matches, or the timeout expires.
     *
     * For a device that answers a single request with a *sequence*. A Motif XS asked for a voice at
     * its documented Bulk Header address replies with 26 separate messages bracketed by a header
     * and a footer, and [exchange] returns the first one - which is the header, carrying no data at
     * all.
     *
     * The timeout bounds the **whole** sequence rather than each message, since what a caller
     * knows is how long the operation should take, not how the device chooses to chunk it. A
     * sequence that never sends its terminator therefore ends in [InstrumentException.Timeout]
     * with whatever arrived discarded - deliberately, because a partial block sequence is not a
     * partial voice, it is an unusable one.
     */
    suspend fun exchangeSequence(
        request: ByteArray,
        what: String,
        timeout: Duration = defaultTimeout,
        accept: (ByteArray) -> Boolean = { true },
        done: (ByteArray) -> Boolean,
    ): List<ByteArray> = lock.withLock {
        val collected = ArrayList<ByteArray>()
        val finished = withTimeoutOrNull(timeout) {
            coroutineScope {
                // Subscribed before the send, for the reason [exchange] documents at length: with
                // replay = 0 a reply arriving in the gap is emitted to nobody.
                val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                    messages.first { message ->
                        if (accept(message)) collected.add(message)
                        done(message)
                    }
                }
                transport.send(request)
                awaited.await()
            }
        }
        if (finished == null) {
            Log.w(TAG, "'$what': sequence never terminated (${collected.size} messages in)")
            throw InstrumentException.Timeout(what)
        }
        collected
    }

    /**
     * Sends every message in [messages] under a single lock, then waits for the first reply
     * satisfying [matches].
     *
     * For a write that is acknowledged **only at the end**. On a Motif XS the documented write is
     * a header, 24 blocks and a footer, and the instrument answers nothing at all until the footer
     * - so sending the blocks through [tell] and then waiting would be correct in outcome and
     * wrong in one important way: [tell] takes the lock per message, which lets an unrelated
     * operation land in the middle of a sequence the instrument is treating as one transaction.
     * Holding it across the whole run is the point of this method rather than a detail of it.
     *
     * [gap] paces the sends, matching the vendor editor's own timing: it spaces its blocks a few
     * USB frames apart while the instrument is writing flash at the end of the sequence, and
     * there is no reason to assume sending all 26 as fast as the bus allows would be safe.
     *
     * **There is no safe place to stop part way.** A Motif XS left mid-sequence sits on
     * *receiving midi bulk data* until it is completed or power-cycled, so this sends the whole
     * list and every check a caller wants belongs before the call.
     */
    suspend fun exchangeAfterAll(
        messages: List<ByteArray>,
        what: String,
        timeout: Duration = defaultTimeout,
        gap: Duration = Duration.ZERO,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = lock.withLock {
        require(messages.isNotEmpty()) { "exchangeAfterAll needs at least one message to send" }
        val reply = withTimeoutOrNull(timeout) {
            coroutineScope {
                val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                    this@SysExExchange.messages.first { message ->
                        matches(message).also { matched ->
                            if (!matched) {
                                Log.d(TAG, "'$what': ignoring ${message.size}B reply ${message.prefixHex()}")
                            }
                        }
                    }
                }
                messages.forEachIndexed { index, message ->
                    transport.send(message)
                    if (gap > Duration.ZERO && index < messages.size - 1) delay(gap)
                }
                awaited.await()
            }
        }
        reply ?: throw InstrumentException.Timeout(what)
    }

    /**
     * Fire-and-forget: a message the instrument does not answer at all.
     *
     * Selecting a preset on a Pro-800 is exactly this - a bank select and a program change, plain
     * channel-voice MIDI with no status reply, no echo, nothing to correlate. It still takes
     * the same lock, so a select issued while a 400-preset scan is running lands *between* two
     * dumps rather than in the middle of one.
     */
    suspend fun tell(request: ByteArray) = lock.withLock {
        transport.send(request)
    }

    /**
     * Ends the session: stops draining the transport and closes it.
     *
     * **A MIDI port that is never closed stays claimed.** Android hands one out per device, so
     * leaking it does not merely waste a handle - the next connection attempt cannot open the
     * device to probe it, and the app reports finding no instrument at all while the instrument is
     * plugged in and working. That is exactly what happened on hardware once the app had been
     * backgrounded: `Pro800Instrument.close()` did nothing, so nothing ever released the port.
     */
    fun close() {
        // The whole scope, not just [collector]. On a USB-backed session the reader loop inside
        // UsbMidiBulkTransport is the scope's other child, and cancelling only what this class
        // started left the guarantee resting on every sibling remembering to cancel itself - one
        // new child away from a coroutine that outlives the instrument and reads a closed handle.
        // transport.close() still runs below, since cancelling a coroutine does not release a
        // USB interface or a MIDI port.
        scope.cancel()
        transport.close()
    }

    /**
     * Swallows whatever is already buffered, so a retry starts clean.
     *
     * The MIDI counterpart of clearing `NordDevice.readBuffer` before a new request: on that bus a
     * stale reply left in the buffer got its checksum tested against a different message's bytes,
     * and the failure was reported against the wrong sub-opcode.
     */
    private suspend fun drainStale() {
        withTimeoutOrNull(DRAIN_WINDOW) {
            messages.collect { /* discard */ }
        }
    }

    private companion object {
        val DRAIN_WINDOW = 50.milliseconds

        /** Enough of a message to identify it (header, type, address) without dumping a whole
         * 210-byte program into the log. */
        const val PREFIX_BYTES = 12
    }
}

private fun ByteArray.prefixHex(): String =
    take(12).joinToString(" ") { "%02x".format(it) } + if (size > 12) " ..." else ""
