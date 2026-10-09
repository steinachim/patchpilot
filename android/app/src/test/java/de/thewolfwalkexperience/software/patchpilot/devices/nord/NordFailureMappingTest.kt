// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFixtures.emptyItemRecord
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFixtures.itemRecord
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFixtures.rootListPayload
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFixtures.uint32BE
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import de.thewolfwalkexperience.software.patchpilot.transport.UsbReadTimeoutException
import de.thewolfwalkexperience.software.patchpilot.transport.UsbTransferException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Nord adapter turns protocol failures into [InstrumentException]s.
 *
 * These tests pin the translation, which is behaviour no other test exercises: the protocol tests
 * drive `NordDevice` directly and never go through the adapter, and the screens branch on the
 * exception's type rather than on its message.
 */
class NordFailureMappingTest {

    private fun instrument(responses: List<Pair<Int, ByteArray>>) =
        NordInstrument(NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE))

    /** A non-zero status is the instrument answering and saying no - code carried verbatim. */
    @Test
    fun `a refused delete becomes DeviceRejected carrying the instrument's own status`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0), // SELECT_CATEGORY
                21 to (uint32BE(9) + uint32BE(8) + uint32BE(12)), // status 9
                7 to ByteArray(0), // unlock still runs
            ),
        )
        val failure = thrownBy<InstrumentException.DeviceRejected> { instrument.delete(SlotAddress(8, 12)) }
        assertEquals(9, failure.status)
        assertTrue(failure.message!!, failure.message!!.contains("refused to delete a preset"))
    }

    /**
     * An echo for a slot other than the one asked for is a desync, not a refusal: the instrument
     * acted, on another slot, so the user is told the list may be out of date rather than that
     * nothing happened.
     */
    @Test
    fun `an echo for the wrong slot becomes ProtocolDesync`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                21 to (uint32BE(0) + uint32BE(8) + uint32BE(13)), // status 0, wrong item
                7 to ByteArray(0),
            ),
        )
        val failure = thrownBy<InstrumentException.ProtocolDesync> { instrument.delete(SlotAddress(8, 12)) }
        assertTrue(failure.message!!, failure.message!!.contains("Refresh the list"))
    }

    /**
     * Status 4 ("file exists"; `docs/PROTOCOLS.md`, sub-opcode 22/23) means the list is out of
     * date, which is what the user is told. The code survives in the cause, for the log.
     */
    @Test
    fun `copying onto an occupied slot asks for a refresh`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                23 to uint32BE(NordDevice.STATUS_FILE_EXISTS),
                7 to ByteArray(0),
            ),
        )
        val failure =
            thrownBy<InstrumentException.SlotStateChanged> {
                instrument.copyProgram(SlotAddress(0, 0), SlotAddress(0, 1))
            }
        assertEquals(NordDevice.STATUS_FILE_EXISTS, (failure.cause as NordStatusException).status)
        assertTrue(failure.message!!, failure.message!!.contains("holds a preset"))
        assertTrue(failure.message!!, failure.message!!.contains("Refresh the list"))
    }

    /** As for a copy; status 4 for a move is assumed, not measured - see `NordInstrument.intoEmpty`. */
    @Test
    fun `moving onto an occupied slot asks for a refresh`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                25 to uint32BE(NordDevice.STATUS_FILE_EXISTS),
                7 to ByteArray(0),
            ),
        )
        val failure =
            thrownBy<InstrumentException.SlotStateChanged> { instrument.move(SlotAddress(0, 0), SlotAddress(0, 1)) }
        assertTrue(failure.message!!, failure.message!!.contains("holds a preset"))
    }

    /** Read before the write, as on every family: a slot deleted on the panel asks for a refresh. */
    @Test
    fun `renaming a slot emptied on the panel asks for a refresh`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                31 to emptyItemRecord(bank = 0, item = 0),
                7 to ByteArray(0),
            ),
        )
        val failure = thrownBy<InstrumentException.SlotStateChanged> { instrument.rename(SlotAddress(0, 0), "New") }
        assertTrue(failure.message!!, failure.message!!.contains("is empty"))
    }

    @Test
    fun `a rename to the name already stored writes nothing`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                31 to itemRecord(bank = 0, item = 0, categoryId = 21, name = "White Grand"),
                7 to ByteArray(0),
            ),
        )
        NordInstrument(NordFixtures.device(transport, NordFixtures.GRAND_PROFILE))
            .rename(SlotAddress(0, 0), "White Grand")
        assertTrue(transport.sentRequests.none { it.subOp == NordDevice.FileTransferSubOp.SET_NAME.code })
    }

    /** A swap needs two presets; status 1 says one slot is empty, though not which. */
    @Test
    fun `swapping with an empty slot asks for a refresh`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                27 to uint32BE(NordDevice.STATUS_NOT_FOUND),
                7 to ByteArray(0),
            ),
        )
        val failure =
            thrownBy<InstrumentException.SlotStateChanged> { instrument.swap(SlotAddress(0, 0), SlotAddress(0, 1)) }
        assertEquals(NordDevice.STATUS_NOT_FOUND, (failure.cause as NordStatusException).status)
        assertTrue(failure.message!!, failure.message!!.contains("Refresh the list"))
    }

    /**
     * The counterpart: a code with no documented meaning must not be given an invented one. The
     * generic wording is honest - the app has the instrument's answer and cannot interpret it.
     */
    @Test
    fun `a status the protocol does not name keeps the generic wording`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                21 to (uint32BE(9) + uint32BE(8) + uint32BE(12)),
                7 to ByteArray(0),
            ),
        )
        val failure = thrownBy<InstrumentException.DeviceRejected> { instrument.delete(SlotAddress(8, 12)) }
        assertNull(failure.explanation)
        assertTrue(failure.message!!.contains("status 9"))
    }

    /**
     * A reply out of step with the request - here, every reply answering some other sub-opcode -
     * is a desync too, not a lost link: the instrument is talking, just not to this session's
     * requests.
     */
    @Test
    fun `replies out of step with the requests become ProtocolDesync, not TransportLost`() = runTest {
        val instrument = NordInstrument(
            NordFixtures.device(OutOfStepTransport(), NordFixtures.GRAND_PROFILE),
        )
        thrownBy<InstrumentException.ProtocolDesync> { instrument.delete(SlotAddress(8, 12)) }
    }

    /** Answers every request with a well-formed reply to some other one. */
    private class OutOfStepTransport : de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport {
        override fun bulkWrite(data: ByteArray) = Unit
        override fun bulkRead(bufferSize: Int): ByteArray =
            buildMessage(NordDevice.PROTOCOL_FILE_TRANSFER, 10, 31, ByteArray(40))
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray =
            ByteArray(length)
        override fun close() = Unit
        override val rebuildOnResume = true
    }

    /** The transport's own failure is the one case a retry can fix, and keeps its own type. */
    @Test
    fun `a failed bulk transfer becomes TransportLost`() = runTest {
        val instrument = NordInstrument(
            NordFixtures.device(FailingTransport(), NordFixtures.GRAND_PROFILE),
        )
        thrownBy<InstrumentException.TransportLost> { instrument.delete(SlotAddress(8, 12)) }
    }

    /** What a detached device looks like to the protocol layer: every transfer fails at once. */
    private class FailingTransport : de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport {
        override fun bulkWrite(data: ByteArray) =
            throw UsbTransferException("USB bulk write failed: sent -1 of ${data.size} bytes")
        override fun bulkRead(
            bufferSize: Int,
        ): ByteArray = throw UsbTransferException("USB bulk read failed (result=-1)")
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray =
            throw UsbTransferException("USB control transfer failed")
        override fun close() = Unit
        override val rebuildOnResume = true
    }

    /** A read that waits out its timeout is a silent instrument, not a lost link: a retry may work. */
    @Test
    fun `a read that times out becomes Timeout`() = runTest {
        val instrument = NordInstrument(NordFixtures.device(SilentTransport(), NordFixtures.GRAND_PROFILE))
        val failure = thrownBy<InstrumentException.Timeout> { instrument.delete(SlotAddress(8, 12)) }
        // The -ing form: "did not answer while delete a preset" is what one label for both gave.
        assertTrue(failure.message!!, failure.message!!.contains("did not answer while deleting a preset"))
    }

    /** Takes every request, then never answers within the timeout. */
    private class SilentTransport : de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport {
        override fun bulkWrite(data: ByteArray) = Unit
        override fun bulkRead(bufferSize: Int): ByteArray = throw UsbReadTimeoutException("No reply within 3000 ms")
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray =
            ByteArray(length)
        override fun close() = Unit
        override val rebuildOnResume = true
    }

    /** A `check()` failing inside this app's own code is a bug, not a cable: it must not read as "connection lost". */
    @Test
    fun `an unexpected internal state becomes ProtocolDesync, not TransportLost`() = runTest {
        val failure =
            thrownBy<InstrumentException.ProtocolDesync> {
                mapNordFailure("delete a preset", "deleting a preset") { check(false) { "impossible state" } }
            }
        assertTrue(failure.message!!.contains("impossible state"))
    }

    /**
     * A reply this app cannot parse is a desync, not a refusal - the distinction the screens act
     * on, since one may be worth retrying and the other is a bug or an unprofiled device.
     */
    @Test
    fun `an unparseable reply becomes ProtocolDesync`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootListPayload(),
                5 to ByteArray(0),
                21 to uint32BE(0), // too short for the (status, bank, item) triple
                7 to ByteArray(0),
            ),
        )
        thrownBy<InstrumentException.ProtocolDesync> { instrument.delete(SlotAddress(8, 12)) }
    }
}
