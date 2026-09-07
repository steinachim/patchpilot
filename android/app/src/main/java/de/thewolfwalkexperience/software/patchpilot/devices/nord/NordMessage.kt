package de.thewolfwalkexperience.software.patchpilot.devices.nord

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Message framing for the Nord vendor USB protocol:
 * `[4B total length][4B protocol ID][4B protocol version][4B sub-opcode][payload][2B CRC-16/CCITT-FALSE]`,
 * all big-endian, CRC over everything before it.
 *
 * The two middle words carry which protocol the message belongs to (6 = UI, 7 = Ctrl, 12 = file
 * transfer) and the version that protocol was negotiated at - the same names [NordMessage]'s own
 * fields and docs/PROTOCOLS.md use for them.
 */
const val MESSAGE_HEADER_LEN = 16
const val MESSAGE_CRC_LEN = 2

data class NordMessage(
    val protocolId: Int,
    val protocolVersion: Int,
    val subOp: Int,
    val payload: ByteArray,
)

fun buildMessage(
    protocolId: Int,
    protocolVersion: Int,
    subOp: Int,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val totalLen = MESSAGE_HEADER_LEN + payload.size + MESSAGE_CRC_LEN
    val body = ByteBuffer.allocate(MESSAGE_HEADER_LEN + payload.size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(totalLen)
        .putInt(protocolId)
        .putInt(protocolVersion)
        .putInt(subOp)
        .put(payload)
        .array()
    val crc = crc16CcittFalse(body)
    return ByteBuffer.allocate(body.size + MESSAGE_CRC_LEN)
        .order(ByteOrder.BIG_ENDIAN)
        .put(body)
        .putShort(crc.toShort())
        .array()
}

/**
 * Decodes one message from the front of [data].
 *
 * **The leading length field delimits the message, not `data.size`.** Reading the last two bytes
 * of whatever the buffer holds as the checksum and everything before them as the payload is
 * correct only when the buffer holds exactly one whole message - something a bulk pipe never
 * promises. A read can come back holding, say, 93 bytes of a declared 408 with a stale reply from
 * an earlier exchange stuck on the end; testing the stale message's checksum against the first 91
 * bytes of a different one then reports the mismatch against the wrong sub-opcode. Bytes past the
 * declared length are ignored here; [NordDevice.readReply] is what keeps them for the next call.
 */
fun parseMessage(data: ByteArray): NordMessage {
    require(data.size >= MESSAGE_HEADER_LEN + MESSAGE_CRC_LEN) {
        "message too short (${data.size} bytes): ${data.joinToString("") { "%02x".format(it) }}"
    }
    val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    val totalLen = buf.int
    val protocolId = buf.int
    val protocolVersion = buf.int
    val subOp = buf.int
    require(totalLen >= MESSAGE_HEADER_LEN + MESSAGE_CRC_LEN) {
        "message declares an impossible length of $totalLen bytes " +
            "(protocol=$protocolId version=$protocolVersion sub-op=$subOp)"
    }
    require(data.size >= totalLen) {
        "message declares $totalLen bytes but only ${data.size} are available " +
            "(protocol=$protocolId version=$protocolVersion sub-op=$subOp)"
    }
    val payload = data.copyOfRange(MESSAGE_HEADER_LEN, totalLen - MESSAGE_CRC_LEN)

    val crcBytes = data.copyOfRange(totalLen - MESSAGE_CRC_LEN, totalLen)
    val expectedCrc = ((crcBytes[0].toInt() and 0xFF) shl 8) or (crcBytes[1].toInt() and 0xFF)
    val actualCrc = crc16CcittFalse(data, 0, totalLen - MESSAGE_CRC_LEN)
    require(expectedCrc == actualCrc) {
        "CRC mismatch in response: expected 0x%04x, computed 0x%04x " +
            "(protocol=%d version=%d sub-op=%d)"
            .format(expectedCrc, actualCrc, protocolId, protocolVersion, subOp)
    }
    return NordMessage(protocolId, protocolVersion, subOp, payload)
}
