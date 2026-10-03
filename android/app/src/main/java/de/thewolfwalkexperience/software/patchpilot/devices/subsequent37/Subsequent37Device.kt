// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val TAG = "Subsequent37Device"

/**
 * One Subsequent 37 request at a time, over [exchange]: the wire operations and nothing composed
 * from them. Takes no lock of its own; [Subsequent37Instrument] holds one around every sequence.
 *
 * The instrument sends at about 1,435 bytes per second whatever is asked, so every timeout here
 * follows from the size of the reply: a settings block (828 B) takes about 0.6 s, a read's two
 * replies (2 × 756 B) about 1.1 s, a bulk dump (203,800 B) about 142 s.
 */
class Subsequent37Device(
    private val exchange: SysExExchange,
    /** The pause between write read-backs; a parameter so the tests need not wait it out. */
    private val verifyPause: Duration = VERIFY_PAUSE,
) {

    val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    /** Every byte received this session; see [SysExExchange.receivedBytes]. */
    val receivedBytes: Long get() = exchange.receivedBytes

    fun pending(offset: Int): SysExExchange.Pending = exchange.pending(offset)

    suspend fun ping(): ByteArray =
        exchange.exchange(Subsequent37SysEx.ping(), "answering a ping") { Subsequent37SysEx.isPingReply(it) }

    suspend fun identityReply(): ByteArray =
        exchange.exchange(Subsequent37SysEx.IDENTITY_REQUEST, "reading the firmware version") {
            Subsequent37SysEx.isIdentityReply(it)
        }

    /**
     * The settings block, raw. `06 04` draws it and `06 00` is sent with it, as the editor
     * does; the pair is also what makes the instrument answer a program read. A short block
     * does not match and is asked for again.
     */
    suspend fun settingsReply(): ByteArray = attempt(READ_ATTEMPTS, "reading the settings") {
        exchange.exchangeAfterAll(
            listOf(Subsequent37SysEx.settingsRequest(), Subsequent37SysEx.settingsPair()),
            "reading the settings",
            SETTINGS_TIMEOUT,
        ) { Subsequent37SysEx.isSettingsReply(it) }
    }

    suspend fun settings(): Subsequent37Settings =
        Subsequent37Settings.parse(settingsReply())
            ?: throw InstrumentException.ProtocolDesync("The Subsequent 37's settings block could not be decoded.")

    /**
     * Stored program [number] as the `02 07` message that carries it, read with `06 01`: about
     * 1.7 s with the settings pair sent first, nothing selected, nothing heard. A damaged reply
     * does not match and is asked for again; `06 01` is a read, so asking twice is harmless.
     */
    suspend fun readStoredMessage(number: Int): ByteArray =
        attempt(READ_ATTEMPTS, "reading program $number") {
            settingsReply()
            exchange.exchange(Subsequent37SysEx.readProgram(number), "reading program $number", READ_TIMEOUT, retries = 0) {
                Subsequent37SysEx.isStoredProgramReply(it, number) && Subsequent37SysEx.programOf(it) != null
            }
        }

    suspend fun readProgram(number: Int): Subsequent37Program =
        Subsequent37Program(Subsequent37SysEx.programOf(readStoredMessage(number))!!)

    /**
     * Writes [program] to [number] and reads it back until the slot holds exactly the bytes
     * sent. Nothing acknowledges a write, so the read-back is the only proof.
     *
     * Up to [VERIFY_ATTEMPTS] reads, [verifyPause] apart: how long a write takes to land is not
     * measured, and a read taken too soon may still show the old program, so one mismatch is
     * not believed. A mismatch is reported only where every read that arrived disagreed; where
     * none arrived the outcome is unknown, which is a [InstrumentException.Timeout].
     */
    suspend fun writeVerified(number: Int, program: Subsequent37Program, displayId: String) {
        // Built first: the checksum closed on the way out is what the read-back is checked against.
        val message = Subsequent37SysEx.writeProgram(number, program.payload)
        val sent = Subsequent37Program(Subsequent37SysEx.programOf(message)!!)
        exchange.tell(message)
        var mismatched = false
        for (attempt in 1..VERIFY_ATTEMPTS) {
            if (attempt > 1) delay(verifyPause)
            val stored = try {
                readProgram(number)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InstrumentException.Timeout) {
                Log.w(TAG, "Read-back $attempt of $displayId went unanswered")
                continue
            }
            if (stored.sameBytesAs(sent)) return
            mismatched = true
            Log.w(TAG, "Read-back $attempt of $displayId differs from what was written")
        }
        if (mismatched) {
            throw InstrumentException.ProtocolDesync(
                "Wrote $displayId, but reading it back gave different data. The preset may not " +
                    "have been stored.",
            )
        }
        throw InstrumentException.Timeout("confirming what was written to $displayId")
    }

    /**
     * Every program, as one message of about 203,800 bytes that takes about 142 seconds. Sent
     * once: a repeat sent while the first is still arriving would only start another one after it.
     */
    suspend fun bulkDump(): ByteArray =
        exchange.exchange(Subsequent37SysEx.bulkDumpRequest(), "sending its preset list", BULK_DUMP_TIMEOUT, retries = 0) {
            Subsequent37SysEx.isBulkDumpReply(it)
        }

    /** Selects [number] on MIDI [channel], 0..15, and waits for the `36` acknowledgement. */
    suspend fun select(channel: Int, number: Int, displayId: String) {
        exchange.exchange(Subsequent37SysEx.select(channel, number), "loading $displayId", SELECT_TIMEOUT) {
            Subsequent37SysEx.isSelectAck(it, number)
        }
    }

    /** [block], repeated on a timeout up to [times] in all. */
    private suspend fun <T> attempt(times: Int, what: String, block: suspend () -> T): T {
        var last: InstrumentException.Timeout? = null
        repeat(times) { index ->
            try {
                return block()
            } catch (e: InstrumentException.Timeout) {
                last = e
                Log.w(TAG, "'$what' went unanswered (attempt ${index + 1} of $times)")
            }
        }
        throw last ?: InstrumentException.Timeout(what)
    }

    companion object {
        /**
         * Read-backs per write, and the pause between them. **Assumption:** guesses, not measured;
         * every write so far was confirmed by the first read.
         */
        const val VERIFY_ATTEMPTS = 3
        val VERIFY_PAUSE = 1.seconds

        const val READ_ATTEMPTS = 2

        val SETTINGS_TIMEOUT = 3.seconds
        val READ_TIMEOUT = 4.seconds
        val SELECT_TIMEOUT = 1.seconds

        /** The 142-second dump, with room for a slow start. */
        val BULK_DUMP_TIMEOUT = 200.seconds
    }
}
