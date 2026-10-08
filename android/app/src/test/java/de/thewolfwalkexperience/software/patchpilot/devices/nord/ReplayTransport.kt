// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport

/**
 * Test-only UsbBulkTransport that serves a fixed list of (expectedSubOp, responsePayload) pairs
 * in order instead of talking to real hardware. Asserts that each request's sub-opcode N is
 * immediately followed, in the fixture stream, by a response for sub-opcode N+1, catching
 * requests sent out of order.
 *
 * A request the script does not answer throws [AssertionError] rather than the
 * `IllegalStateException` the device raises itself: no production `catch` swallows it, and a test
 * expecting a device failure cannot mistake a script out of step for one. [assertAllConsumed]
 * checks the other direction, that every scripted reply was asked for.
 *
 * @param thenSilent the instrument stops answering after the last reply: a further read fails as
 *   the USB transport's does, with an `IllegalStateException` the code under test may handle.
 */
internal class ReplayTransport(
    responses: List<Pair<Int, ByteArray>>,
    private val firmwareVersion: Int = 0,
    private val thenSilent: Boolean = false,
) : UsbBulkTransport {
    private val responses = responses.toList()
    private var index = 0
    private var lastProtocolId = 0
    private var lastProtocolVersion = 0
    private var pendingSubOp = -1

    val sentRequests = mutableListOf<NordMessage>()

    override fun bulkWrite(data: ByteArray) {
        val msg = parseMessage(data)
        sentRequests += msg
        lastProtocolId = msg.protocolId
        lastProtocolVersion = msg.protocolVersion
        pendingSubOp = msg.subOp
    }

    override fun bulkRead(bufferSize: Int): ByteArray {
        if (index >= responses.size && thenSilent) error("USB bulk read failed (result=-1)")
        if (index >= responses.size) {
            throw AssertionError(
                "ReplayTransport exhausted after $index responses, but a request for sub-op=$pendingSubOp " +
                    "still expects a reply",
            )
        }
        val (expectedSubOp, payload) = responses[index]
        index++
        if (expectedSubOp != pendingSubOp + 1) {
            throw AssertionError(
                "step $index: sent sub-op=$pendingSubOp, but the next recorded response " +
                    "in the fixture is for sub-op=$expectedSubOp",
            )
        }
        return buildMessage(lastProtocolId, lastProtocolVersion, expectedSubOp, payload)
    }

    /** Fails unless every scripted reply was read: a request the code under test skipped leaves one unread. */
    fun assertAllConsumed() {
        if (index < responses.size) {
            throw AssertionError(
                "${responses.size - index} of ${responses.size} scripted replies were never asked for; " +
                    "the first is for sub-op=${responses[index].first}",
            )
        }
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray =
        byteArrayOf((firmwareVersion and 0xFF).toByte(), ((firmwareVersion shr 8) and 0xFF).toByte())

    override val rebuildOnResume = true

    override fun close() {}
}
