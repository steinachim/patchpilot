// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport

/**
 * Test-only UsbBulkTransport that serves a fixed list of (expectedSubOp,
 * responsePayload) pairs in order instead of talking to real hardware -
 * Kotlin equivalent of the Python repo's tests/fixtures.py ReplayLink.
 * Asserts that each request's sub-opcode N is immediately followed, in the
 * fixture stream, by a response for sub-opcode N+1, catching requests sent
 * out of order.
 */
class ReplayTransport(
    responses: List<Pair<Int, ByteArray>>,
    private val firmwareVersion: Int = 0,
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
        check(index < responses.size) {
            "ReplayTransport exhausted after $index responses, but a request for sub-op=$pendingSubOp still expects a reply"
        }
        val (expectedSubOp, payload) = responses[index]
        index++
        check(expectedSubOp == pendingSubOp + 1) {
            "step $index: sent sub-op=$pendingSubOp, but the next recorded response " +
                "in the fixture is for sub-op=$expectedSubOp"
        }
        return buildMessage(lastProtocolId, lastProtocolVersion, expectedSubOp, payload)
    }

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int): ByteArray =
        byteArrayOf((firmwareVersion and 0xFF).toByte(), ((firmwareVersion shr 8) and 0xFF).toByte())

    override val rebuildOnResume = true

    override fun close() {}
}
