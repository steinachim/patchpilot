// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.midi

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.BuildConfig
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 *   whole scope, since every family constructs one solely to hand it here.
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

    /**
     * DROP_OLDEST, so a device that floods the bus cannot stall the collector. Sized for a whole
     * answer: [exchangeSequence] collects many messages, DROP_OLDEST discards from the front,
     * and a Motif XS drum voice answers a documented read with 83 messages.
     */
    private val messages = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Written by the collector alone; read from any coroutine. */
    @Volatile
    private var received = 0L

    /**
     * Every byte [transport] has delivered, SysEx or not. Next to [pending], it tells a reader
     * waiting on a slow message whether the device is sending something else (bytes arrive while
     * nothing is pending) or nothing at all.
     */
    val receivedBytes: Long get() = received

    /** The message still being assembled, as [pending] reports it. */
    class Pending(
        /** [SysExFramer.messageStarts] at the time of the call: a change means a new message. */
        val start: Long,
        /** How much of it has arrived; 0 where no message is in progress. */
        val size: Int,
        /** Its bytes from the offset asked for to [size]. */
        val bytes: ByteArray,
    )

    /**
     * What has arrived of the message still being assembled, from [offset] on - for a reply
     * slow enough to be decoded while it arrives, whose [exchange] is still waiting. A family
     * reads from the offset it has consumed, so each byte is copied once.
     */
    fun pending(offset: Int): Pending = synchronized(framer) {
        Pending(framer.messageStarts, framer.pendingSize, framer.pendingFrom(offset))
    }

    init {
        // UNDISPATCHED so the collector subscribes to [transport] during construction; the
        // transport's flow has replay = 0 too. [close] ends it by cancelling the scope.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { chunk ->
                received += chunk.size
                // Synchronised against [pending]; nothing in feed suspends.
                val (completed, dropped) = synchronized(framer) {
                    val droppedBefore = framer.droppedMessages
                    framer.feed(chunk) to framer.droppedMessages - droppedBefore
                }
                if (dropped > 0) {
                    Log.w(
                        TAG,
                        "dropped $dropped over-long SysEx message(s), " +
                            "${framer.droppedMessages} this session",
                    )
                }
                completed.forEach {
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
     * satisfy this one. A retry re-sends [request], so a step that must not reach the instrument
     * twice (one chunk of a multi-message write, say) passes `retries = 0`.
     */
    suspend fun exchange(
        request: ByteArray,
        what: String,
        timeout: Duration = defaultTimeout,
        retries: Int = this.retries,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = lock.withLock {
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "retrying '$what' (attempt ${attempt + 1})")
                drainStale()
            }
            // The framer is not cleared between attempts: the collector coroutine feeds it, so
            // clearing it from this one would race its buffer, and SysEx is self-delimiting
            // anyway.
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
                    sendOrLost(request, what)
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
                sendOrLost(request, what)
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
                        sendOrLost(message, what)
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
        sendOrLost(request, "sending a message")
    }

    /**
     * [MidiTransport.send], with a failed send raised as [InstrumentException.TransportLost], as
     * the Nord's own mapping does for its transport.
     */
    private suspend fun sendOrLost(message: ByteArray, what: String) {
        try {
            transport.send(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException) {
            throw e
        } catch (e: Exception) {
            throw InstrumentException.TransportLost(e.message ?: "The connection failed while $what", e)
        }
    }

    /**
     * Ends the session: stops draining the transport and closes it. A MIDI port that is never
     * closed stays claimed, and the next scan cannot open the device to probe it.
     */
    fun close() {
        // The whole scope, not just the collector: on a USB-backed session the reader loop inside
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
    copyOf(minOf(size, PREFIX_BYTES)).toHex(" ") + if (size > PREFIX_BYTES) " ..." else ""
