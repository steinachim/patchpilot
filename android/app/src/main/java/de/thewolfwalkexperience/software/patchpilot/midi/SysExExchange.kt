// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.midi

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.BuildConfig
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "SysExExchange"

/**
 * Turns a MIDI byte stream into request/response, with one outstanding request at a time: a
 * Pro-800 reply carries no request id, so a `0x78` is matched to a `0x77` only by being the next
 * to arrive. The [Mutex] makes that explicit.
 *
 * **Every awaiting exchange subscribes to [messages] before it sends**, with `UNDISPATCHED` so
 * the subscription is real before the request leaves. The flow has `replay = 0`, so a reply
 * arriving with no subscriber is gone and the exchange would wait out its whole timeout.
 *
 * @param scope the collector's lifetime. Ownership passes to this object: [close] cancels the
 *   whole scope, since both families construct one solely to hand it here.
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
     * DROP_OLDEST, so a device that floods the bus cannot stall the collector. Sized for a whole
     * answer: [exchangeSequence] collects many messages, DROP_OLDEST discards from the front,
     * and a Motif XS drum voice answers a documented read with 83 messages. A unit test pins
     * the capacity.
     */
    private val messages = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )


    init {
        // UNDISPATCHED so the collector subscribes to [transport] during construction; the
        // transport's flow has replay = 0 too.
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { chunk ->
                framer.feed(chunk).forEach {
                    // A message arriving with no exchange in flight - a fire-and-forget write's
                    // status reply - is otherwise unobservable. Quiet during a scan, where every
                    // dump has a subscriber.
                    if (BuildConfig.DEBUG && messages.subscriptionCount.value == 0) {
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
     * [matches] is the caller's, because a message type alone is not enough: after a timeout, a
     * late reply to the previous request is still the right type. A dump matcher checks the
     * echoed address too.
     *
     * Retries [retries] times, since on a stream transport a single dropped message is normal.
     * Each retry starts after [drainStale], so a late reply to the previous attempt cannot
     * satisfy this one.
     */
    suspend fun exchange(
        request: ByteArray,
        what: String,
        timeout: Duration = defaultTimeout,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = lock.withLock {
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "retrying '$what' (attempt ${attempt + 1})")
                drainStale()
            }
            // No framer.reset() here: the collector coroutine feeds the framer, so a reset from
            // this one would race its buffer, and SysEx is self-delimiting anyway.
            val reply = withTimeoutOrNull(timeout) {
                coroutineScope {
                    // Subscribed before the send - see the class doc.
                    val awaited = async(start = CoroutineStart.UNDISPATCHED) {
                        messages.first { message ->
                            matches(message).also { matched ->
                                // A rejected reply and silence are indistinguishable from a
                                // timeout alone.
                                if (!matched && BuildConfig.DEBUG) {
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
        }
        throw InstrumentException.Timeout(what)
    }

    /**
     * Sends one request and collects every reply until [done] matches, or the timeout expires -
     * for a device that answers one request with a sequence, such as a Motif XS voice read at its
     * Bulk Header address (26 messages bracketed by a header and a footer).
     *
     * The timeout bounds the whole sequence, not each message. A sequence that never sends its
     * terminator ends in [InstrumentException.Timeout] with whatever arrived discarded: a partial
     * block sequence is an unusable voice, not a partial one.
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
     * satisfying [matches] - for a write acknowledged only at the end, such as a Motif XS's
     * header, blocks and footer. The lock is held across the whole run so no unrelated message
     * lands inside a sequence the instrument treats as one transaction.
     *
     * [gap] paces the sends, matching the vendor editor's own spacing.
     *
     * There is no safe place to stop part way: a Motif XS left mid-sequence sits on "receiving
     * midi bulk data" until it is completed or power-cycled. The send loop therefore runs under
     * [NonCancellable]; cancellation is observed at the wait for the acknowledgement, by which
     * time the instrument has the whole sequence. Every check a caller wants belongs before the
     * call.
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
                            if (!matched && BuildConfig.DEBUG) {
                                Log.d(TAG, "'$what': ignoring ${message.size}B reply ${message.prefixHex()}")
                            }
                        }
                    }
                }
                // `delay(gap)` is a cancellation point on every iteration - see the doc above.
                withContext(NonCancellable) {
                    messages.forEachIndexed { index, message ->
                        transport.send(message)
                        if (gap > Duration.ZERO && index < messages.size - 1) delay(gap)
                    }
                }
                awaited.await()
            }
        }
        reply ?: throw InstrumentException.Timeout(what)
    }

    /**
     * Fire-and-forget: a message whose reply, if any, the caller does not wait for (a Pro-800
     * write, verified by read-back; a Motif XS mode change, confirmed by polling). Takes the same
     * lock, so it lands between two dumps of a running scan rather than inside one.
     */
    suspend fun tell(request: ByteArray) = lock.withLock {
        transport.send(request)
    }

    /**
     * Ends the session: stops draining the transport and closes it. A MIDI port that is never
     * closed stays claimed, and the next scan cannot open the device to probe it.
     */
    fun close() {
        // The whole scope, not just [collector]: on a USB-backed session the reader loop inside
        // UsbMidiBulkTransport is the scope's other child.
        scope.cancel()
        transport.close()
    }

    /**
     * Absorbs, for a short window, whatever is still arriving from the previous attempt, so a
     * late reply to it is not the first thing the retry's matcher sees. With `replay = 0` this is
     * a window in time, not a drain of stored messages.
     */
    private suspend fun drainStale() {
        withTimeoutOrNull(DRAIN_WINDOW) {
            messages.collect { /* discard */ }
        }
    }

    private companion object {
        val DRAIN_WINDOW = 50.milliseconds
    }
}

/** Enough of a message to identify it (header, type, address) without dumping a whole 210-byte
 * program into the log. */
private const val PREFIX_BYTES = 12

private fun ByteArray.prefixHex(): String =
    take(PREFIX_BYTES).joinToString(" ") { "%02x".format(it) } + if (size > PREFIX_BYTES) " ..." else ""
