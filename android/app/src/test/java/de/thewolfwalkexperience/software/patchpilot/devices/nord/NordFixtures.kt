// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes

/**
 * A subset of real request/response bytes from actual Nord instruments, transcribed verbatim.
 * Used to keep the decoding byte-for-byte compatible with real device output.
 */
object NordFixtures {

    /** A big-endian 32-bit word, as every Nord payload field is. */
    fun uint32BE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    /**
     * A root category list reply (sub-opcode 1): four unused bytes, the count, then each name
     * with its length and a [trailerLen]-byte trailer, which opens with that category's word from
     * [units] where one is given.
     */
    fun rootListPayload(
        names: List<String> = listOf("Piano", "Program", "Settings"),
        trailerLen: Int = 29,
        units: List<Int>? = null,
    ): ByteArray {
        val body = names.flatMapIndexed { i, name ->
            val bytes = name.toByteArray(Charsets.US_ASCII)
            val trailer = units?.getOrNull(i)?.let { uint32BE(it) + ByteArray(trailerLen - 4) } ?: ByteArray(trailerLen)
            (uint32BE(bytes.size) + bytes + trailer).toList()
        }
        return ByteArray(4) + names.size.toByte() + body
    }

    /**
     * A minimal `ngp` item record, as a fetch (30/31) of an occupied slot answers: the fields the
     * parser reads, at the offsets it reads them.
     *
     * The content tag is spelled out as bytes rather than as a string, because it is NUL-padded -
     * `6e 67 70 00` - and a NUL inside a source literal is invisible to anyone reading it.
     */
    fun itemRecord(bank: Int, item: Int, categoryId: Int, name: String): ByteArray {
        val text = name.toByteArray(Charsets.US_ASCII)
        val ngp = byteArrayOf(0x6E, 0x67, 0x70, 0x00)
        return uint32BE(0) + uint32BE(bank) + uint32BE(item) + uint32BE(0) +
            ngp + uint32BE(101) + uint32BE(0) + uint32BE(categoryId) +
            uint32BE(text.size) + text + ByteArray(12)
    }

    /** What a fetch (30/31) of an empty slot answers: status 1, not found. */
    fun emptyItemRecord(bank: Int, item: Int): ByteArray =
        uint32BE(NordDevice.STATUS_NOT_FOUND) + uint32BE(bank) + uint32BE(item)

    /**
     * The file-transfer protocol version each instrument reports. Not part of any profile -
     * the instrument is asked at connect time - so tests that bypass connect() state it here.
     */
    const val GRAND_PROTOCOL_VERSION = 10
    const val STAGE2EX_PROTOCOL_VERSION = 8

    /**
     * A [NordDevice] with the protocol version already set, as `connect()` would have left it.
     *
     * Tests drive individual operations against canned bytes rather than running `connect()`, but
     * every content-database request needs the version in its header, and no profile declares
     * one. This supplies the version the matching instrument reports.
     */
    fun device(
        transport: de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport,
        profile: NordProfile = GRAND_PROFILE,
    ): NordDevice = NordDevice(transport, profile).apply {
        detectedProtocolVersionFileTransfer = when (profile) {
            STAGE2EX_PROFILE -> STAGE2EX_PROTOCOL_VERSION
            else -> GRAND_PROTOCOL_VERSION
        }
    }

    // Transcribed from devices/nord_devices.json (repo root) rather than loaded from it, since
    // these tests run as plain JVM unit tests with no Android assets/Context available.
    val GRAND_PROFILE = NordProfile(
        id = "nord_grand",
        name = "Nord Grand",
        vendorId = 0x0FFC,
        productId = 0x002B,
        maxBankLetter = 'P',
        maxGroup = 5,
        slotsPerGroup = 5,
        maxDisplayTextLen = 16,
        supportedFirmwareVersions = setOf(168),
    )

    val STAGE2EX_PROFILE = NordProfile(
        id = "nord_stage_2ex",
        name = "Nord Stage 2 EX",
        vendorId = 0x0FFC,
        productId = 0x0021,
        maxBankLetter = 'D',
        maxGroup = 20,
        slotsPerGroup = 5,
        maxDisplayTextLen = 16,
        supportedFirmwareVersions = setOf(300),
    )

    // Nord Grand root category list response (sub-op 1). Contains "Program" at root
    // index 6.
    val GRAND_ROOT_LIST_RESPONSE =
        "000001980000000c0000000a0000000100000000090000000e5069616e6f20284e6174697665290001ff00000000c000000002000000a401010100000000000100010001000000055069616e6f0001ff00000000c000000002000000a4000101000101000001000100010000000e506564616c20284e6174697665290001ff00000000c000000002000000a4010101000000000001000100010000000b5069616e6f20506564616c0001ff00000000c000000002000000a4000101000000000001000100010000001153616d70204c696220284e6174697665290000ff80000000800000000200000068010101000000000001000100010000000853616d70204c69620000ff80000000800000000200000068000101000101000001000100010000000750726f6772616d000000fc00000024000000010000001000010101010101000001000101000000044c697665000000fc000000240000000100000010000101000000000100010000010000000853657474696e6773000000fc00000024000000010000001000010100000000010001000001c9ab"
            .hexToBytes()

    // ngp (Nord Grand piano/program) item record response (sub-op 31):
    // bank=0 item=0 "White Grand".
    val NGP_RECORD_RESPONSE =
        "0000004d0000000c0000000a0000001f0000000000000000000000000000005a6e6770000000006500000000000000150000000b5768697465204772616e64c185eab000000000ec2cdf019aa5"
            .hexToBytes()

    // nsmp (sample) item record response (sub-op 31): bank=0 item=0
    // "OrchStrings Legato_KH 3.0". Carries a content id in its tail, which
    // the Stage 2 EX's records do not - see NordDevice.parseItemContentId.
    val NSMP_RECORD_RESPONSE =
        "0000005b0000000c0000000a0000001f000000000000000000000000004e49406e736d700000012c59356fcd00090002000000194f726368537472696e6773204c656761746f5f4b4820332e30c185eab00000000068aeee7101ed"
            .hexToBytes()

    // Device-info responses (sub-op 3). The payload is a
    // protocol version table - a count, then that many
    // (protocol id, version) byte pairs - and the pair for command 12 is where
    // protocolVersionFileTransfer comes from: `0c 0a` = 10 on the Grand, `0c 08` = 8 on the
    // Stage 2 EX. See NordDevice.parseProtocolVersions.
    val GRAND_DEVICE_INFO_RESPONSE = "0000001d00000007000000000000000305060107000a020c0a0d001c14".hexToBytes()
    val STAGE2EX_DEVICE_INFO_RESPONSE = "0000001d00000007000000000000000305060107000a020c080d007274".hexToBytes()

    // Nord Stage 2 EX root category list response (sub-op 1): 10 categories
    // (the Grand has 9) behind a 28-byte per-category trailer (the Grand's
    // is 29).
    val STAGE2EX_ROOT_LIST_RESPONSE =
        "000001b40000000c0000000800000001000000000a0000000e5069616e6f20284e6174697665290003fe00000000c000000008000000a4010101000000000001000100000000055069616e6f0003fe00000000c000000008000000a40001010001010000010001000000000b5069616e6f20506564616c0003fe00000000c000000008000000a40001010000000000010001000000000e506564616c20284e6174697665290003fe00000000c000000008000000a40101010000000000010001000000001153616d70204c696220284e6174697665290002fff40000017d000000030000015c0101010000000000010001000000000853616d70204c69620002fff40000017d000000030000015c0001010001010000010001000000000750726f6772616d000000010000000000000001000000100001010101010101000100010000000553796e746800000001000000000000000100000010000101010101010100010001000000044c697665000000010000000000000001000000000001010000000000000100000000000853657474696e677300000001000000000000000100000000000101000000000000010000dc60"
            .hexToBytes()
}
