// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancellation is a contract, not an error.
 *
 * Every long operation here runs in a coroutine the user can walk away from - backing out of a
 * connect attempt, leaving the browser mid-scan - and the whole app is written on the assumption
 * that doing so is silent. A `catch (e: Exception)` on a suspending call breaks that assumption
 * without looking like it does, because `CancellationException` *is* an `Exception`: the operation
 * reports a failure that never happened, or worse, carries on against a scope that is already gone.
 *
 * `main` has about 30 hand-written `CancellationException` rethrow guards; these tests cover the
 * two whose absence is hardest to notice, since a missing one fails nothing.
 */
class NordCancellationTest {

    /**
     * A transport that cancels the caller's job on the first write, then refuses to be read.
     *
     * Models the timing that matters: the request goes out, and the user leaves before the reply
     * comes back. Cancelling from inside `bulkWrite` makes that deterministic -
     * by the time [NordDevice.readReply] runs its `ensureActive()` the job is already gone - and
     * the throwing `bulkRead` proves the test never silently took the ordinary path instead.
     */
    private class CancelOnWriteTransport(
        private val firmwareVersion: Int,
    ) : UsbBulkTransport {
        /** Set after `launch`, which is why the job starts lazily. */
        var job: Job? = null

        override fun bulkWrite(data: ByteArray) {
            job?.cancel()
        }

        override fun bulkRead(bufferSize: Int): ByteArray =
            throw AssertionError("the read should never be reached: the job was cancelled during the write")

        override fun controlTransfer(
            requestType: Int,
            request: Int,
            value: Int,
            index: Int,
            length: Int,
        ): ByteArray = byteArrayOf(
            (firmwareVersion and 0xFF).toByte(),
            ((firmwareVersion shr 8) and 0xFF).toByte(),
        )

        override val rebuildOnResume = true

        override fun close() {}
    }

    /**
     * `connect()` reads the file-transfer protocol version first, and wraps a failure of that read
     * in an `IllegalStateException` explaining that the instrument could not be talked to. A
     * cancellation must pass through unwrapped, or backing out of a connect would show an error
     * screen - "Couldn't read the file-transfer protocol version".
     */
    @Test
    fun `cancelling a connect surfaces as cancellation, not as a protocol failure`() = runTest {
        // 168 is GRAND_PROFILE's tested firmware, so nothing but the protocol-version read this
        // test is about stands in the way.
        val transport = CancelOnWriteTransport(firmwareVersion = 168)
        val device = NordFixtures.device(transport)

        var caught: Throwable? = null
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                device.connect()
            } catch (t: Throwable) {
                caught = t
            }
        }
        transport.job = job
        job.start()
        job.join()

        assertTrue(
            "cancelling a connect must stay a cancellation, but it surfaced as ${caught?.let {
                it::class.simpleName
            }}: ${caught?.message}",
            caught is CancellationException,
        )
    }

    /**
     * The quieter of the two: a cancelled child-list read taken as "could not be read" would
     * answer `null`, which the bank walk treats as "unknown, carry on with the item count" - so a
     * scan the app had already decided to tear down would keep issuing requests against a closed
     * instrument.
     */
    @Test
    fun `cancelling a bank-count walk stops it rather than reporting an unknown count`() = runTest {
        val transport = CancelOnWriteTransport(firmwareVersion = 168)
        val device = NordFixtures.device(transport)

        var caught: Throwable? = null
        var returned: Int? = null
        val job = launch(start = CoroutineStart.LAZY) {
            try {
                returned = device.categoryBankCount(categoryIndex = 0)
            } catch (t: Throwable) {
                caught = t
            }
        }
        transport.job = job
        job.start()
        job.join()

        assertNull("a cancelled walk must not answer with a bank count at all", returned)
        assertTrue(
            "a cancelled walk must propagate the cancellation, but it caught ${caught?.let { it::class.simpleName }}",
            caught is CancellationException,
        )
    }

    /**
     * [ReplayTransport], but the caller's job is cancelled the moment a request for [cancelOn]
     * goes out - the user leaving mid-operation, at the worst moment.
     */
    private class CancelMidOperation(
        private val replay: ReplayTransport,
        private val cancelOn: Int,
    ) : UsbBulkTransport by replay {
        var job: Job? = null

        override fun bulkWrite(data: ByteArray) {
            replay.bulkWrite(data)
            if (parseMessage(data).subOp == cancelOn) job?.cancel()
        }
    }

    /**
     * **A cancelled operation still unlocks the instrument.** The unlock runs under
     * `NonCancellable`: without it, leaving mid-rename would leave the category lock set and the
     * front panel unresponsive until the session ended.
     */
    @Test
    fun `a rename cancelled mid-write still unlocks the instrument`() = runTest {
        val replay = ReplayTransport(
            listOf(
                1 to rootListPayload(listOf("Piano", "Program", "Settings")),
                5 to ByteArray(0), // SELECT_CATEGORY
                // No SET_NAME reply: the job is cancelled as the request goes out, before the read.
                7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION, sent anyway
            ),
        )
        val transport = CancelMidOperation(replay, cancelOn = NordDevice.FileTransferSubOp.SET_NAME.code)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)

        val job = launch(start = CoroutineStart.LAZY) { device.renamePreset(1, 11, "Black Upright") }
        transport.job = job
        job.start()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(
            NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code,
            replay.sentRequests.last().subOp,
        )
        replay.assertAllConsumed()
    }

    /** A sub-opcode 0/1 root category list carrying [names], each with a 29-byte trailer (protocol version 10). */
    private fun rootListPayload(names: List<String>): ByteArray {
        val out = mutableListOf<Byte>()
        repeat(4) { out += 0.toByte() }
        out += names.size.toByte()
        for (name in names) {
            val bytes = name.toByteArray(Charsets.US_ASCII)
            out +=
                listOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                )
            out += bytes.toList()
            out += List(29) { 0.toByte() }
        }
        return out.toByteArray()
    }
}
