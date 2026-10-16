// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * The version, save date, size and content id an item record gives Info. Records are built field
 * by field rather than taken from a capture, so each figure is stated where it is asserted.
 */
class NordItemInfoTest {

    private fun u32(value: Long) = ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }

    /**
     * A status-0 record laid out as §3.5 of the protocol notes: bank, item, data size, tag,
     * content version, timestamp, category, name, then (protocol 10) the two attribute words, the
     * content id and a CRC-16.
     */
    private fun record(
        tag: String = "ngp\u0000",
        version: Long = 101,
        timestamp: Long = 0,
        size: Long = 90,
        name: String = "Upright",
        contentId: Long? = 0xDEADBEEF,
    ): ByteArray {
        val text = name.toByteArray(Charsets.US_ASCII)
        val head = u32(0) + u32(1) + u32(11) + u32(size) + tag.toByteArray(Charsets.US_ASCII) +
            u32(version) + u32(timestamp) + u32(21) + u32(text.size.toLong()) + text
        return if (contentId == null) head + ByteArray(8 + 2) else head + ByteArray(8) + u32(contentId) + ByteArray(2)
    }

    private fun rows(record: ByteArray, scales: Map<String, Int> = emptyMap(), contentId: Int? = null) =
        NordItemInfo.rows(record, scales, contentId)

    @Test
    fun `a Grand program shows its version over 100, its size and its content id`() {
        val record = record(version = 101, size = 90)
        assertEquals(
            listOf(
                InfoRow("Version", "1.01"),
                InfoRow("Saved", "Factory content"),
                InfoRow("Data size", "90 bytes"),
                InfoRow("Content ID", "DEADBEEF"),
            ),
            rows(record, contentId = NordDevice.parseItemContentId(record)),
        )
    }

    /** The Stage 2 EX's tags are unscaled: 7 is 7.00. */
    @Test
    fun `a tag the catalog lists is scaled by its own divisor`() {
        val record = record(tag = "ns2p", version = 7, size = 521, contentId = null)
        val shown = rows(record, scales = mapOf("ns2p" to 1))

        assertEquals(InfoRow("Version", "7.00"), shown.first())
        assertTrue("no content id where the record has none", shown.none { it.label == "Content ID" })
    }

    @Test
    fun `a version with no value is left out, and so is a timestamp with none`() {
        val shown = rows(record(version = 0xFFFFFFFF, timestamp = 0xFFFFFFFF))
        assertEquals(listOf(InfoRow("Data size", "90 bytes")), shown)
    }

    /** 1,300,000,000 s is 2011-03-13 in UTC. */
    @Test
    fun `a timestamp is a UTC date`() {
        val shown = rows(record(timestamp = 1_300_000_000)).first { it.label == "Saved" }
        assertEquals("2011-03-13", shown.value)
    }

    @Test
    fun `versions are padded to the width of their scale`() {
        assertEquals("3.12", NordItemInfo.formatVersion(312, 100))
        assertEquals("1.00", NordItemInfo.formatVersion(100, 100))
        assertEquals("2.05", NordItemInfo.formatVersion(205, 100))
        assertEquals("6.00", NordItemInfo.formatVersion(6, 1))
    }

    @Test
    fun `the record's tag has its padding dropped`() {
        assertEquals("ngp", NordDevice.parseItemTag(record()))
        assertEquals("ns2p", NordDevice.parseItemTag(record(tag = "ns2p")))
    }

    @Test
    fun `the shipped catalog scales the Stage 2 EX tags by 1 and nothing else`() {
        val dir = System.getProperty("deviceCatalogDir") ?: error("deviceCatalogDir is not set")
        val catalog = catalogJson.decodeFromString(
            NordCatalog.serializer(),
            File(dir).resolve("nord_devices.json").readText(),
        )
        assertEquals(mapOf("ns2l" to 1, "ns2p" to 1, "ns2s" to 1, "ns2y" to 1), catalog.contentVersionScales)
    }

    // ---- Reading the record through the instrument ----

    private val programRootList get() =
        NordFixtures.rootListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)

    @Test
    fun `details fetch the slot's record once, inside the Program lock`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to programRootList,
                5 to ByteArray(0),
                31 to record(version = 112, timestamp = 1_300_000_000),
                7 to ByteArray(0),
            ),
        )
        val instrument = NordInstrument(NordFixtures.device(transport, NordFixtures.GRAND_PROFILE))

        val shown = instrument.browser.details(SlotAddress(1, 11))

        assertEquals(
            listOf("Version" to "1.12", "Saved" to "2011-03-13", "Data size" to "90 bytes", "Content ID" to "DEADBEEF"),
            shown.map { it.label to it.value },
        )
        assertEquals(1, transport.sentRequests.count { it.subOp == NordDevice.FileTransferSubOp.FETCH_ITEM.code })
    }

    @Test
    fun `an empty slot has no details`() = runTest {
        val transport = ReplayTransport(
            listOf(
                1 to programRootList,
                5 to ByteArray(0),
                31 to NordFixtures.emptyItemRecord(1, 11),
                7 to ByteArray(0),
            ),
        )
        val instrument = NordInstrument(NordFixtures.device(transport, NordFixtures.GRAND_PROFILE))

        assertTrue(instrument.browser.details(SlotAddress(1, 11)).isEmpty())
    }
}
