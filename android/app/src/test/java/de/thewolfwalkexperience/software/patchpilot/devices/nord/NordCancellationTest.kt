package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
 * These are the regression tests for two places where that had happened. There were none before -
 * `main` had ~30 hand-written `CancellationException` rethrow guards and not one test that any of
 * them was there, which is exactly why the two missing ones went unnoticed.
 */
class NordCancellationTest {

    /**
     * A transport that cancels the caller's job on the first write, then refuses to be read.
     *
     * Models the real timing that exposed both bugs: the request goes out, and the user leaves
     * before the reply comes back. Cancelling from inside `bulkWrite` makes that deterministic -
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
     * `connect()` reads the file-transfer protocol version first, and wrapped *any* exception from
     * that read in an `IllegalStateException` explaining that the instrument could not be talked
     * to. Cancelling the connect therefore put an error screen - "Couldn't read the file-transfer
     * protocol version" - in front of a user whose only crime was backing out of it.
     */
    @Test
    fun `cancelling a connect surfaces as cancellation, not as a protocol failure`() = runTest {
        // 168 is GRAND_PROFILE's one supported firmware, so validateFirmwareVersion passes and the
        // run reaches the protocol-version read this test is actually about.
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
            "cancelling a connect must stay a cancellation, but it surfaced as ${caught?.let { it::class.simpleName }}: ${caught?.message}",
            caught is CancellationException,
        )
    }

    /**
     * The sibling bug, and the more insidious of the two: this one did not report anything at all.
     * A cancelled child-list read was logged as "could not be read" and answered `null`, which the
     * bank walk treats as "unknown, carry on with the item count" - so a scan the app had already
     * decided to tear down kept issuing requests against a closed instrument.
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
}
