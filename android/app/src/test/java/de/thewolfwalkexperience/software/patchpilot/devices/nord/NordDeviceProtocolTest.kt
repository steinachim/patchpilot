// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFixtures.hex
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Protocol-level tests: exercises NordDevice methods against ReplayTransport instead of real
 * hardware, using both real fixture bytes from actual instruments (NordFixtures) and small
 * synthetic response sequences for operations the fixture subset doesn't cover end-to-end.
 */
class NordDeviceProtocolTest {

    private fun buildRootCategoryListPayload(names: List<String>, trailerLen: Int): ByteArray {
        val out = mutableListOf<Byte>()
        repeat(4) { out += 0.toByte() } // unused leading 4 bytes (real devices vary these)
        out += names.size.toByte()
        for (name in names) {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            out += uint32BE(nameBytes.size).toList()
            out += nameBytes.toList()
            out += List(trailerLen) { 0.toByte() }
        }
        return out.toByteArray()
    }

    private fun uint32BE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    /**
     * A sub-opcode 2/3 (GET_CATEGORY_CHILD) reply payload for [children], each a
     * name and a slot capacity - the layout NordDevice.parseCategoryChildren reads:
     * status(4) | categoryIndex(4) | childCount(1) | [ nameLen(4) | name | capacity(4) ]*
     */
    private fun childListPayload(children: List<Pair<String, Int>>): ByteArray {
        val out = mutableListOf<Byte>()
        out += uint32BE(0).toList() // status
        out += uint32BE(1).toList() // category index (echoed, unused here)
        out += children.size.toByte()
        for ((name, capacity) in children) {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            out += uint32BE(nameBytes.size).toList()
            out += nameBytes.toList()
            out += uint32BE(capacity).toList()
        }
        return out.toByteArray()
    }

    // ---- Firmware gate + device-info protocol version table ----

    /**
     * The device-info payload for an instrument reporting
     * PROTOCOL_FILE_TRANSFER at [protocolVersionFileTransfer].
     */
    private fun deviceInfoPayload(protocolVersionFileTransfer: Int): ByteArray =
        byteArrayOf(5, 6, 1, 7, 0, 10, 2, 12, protocolVersionFileTransfer.toByte(), 13, 0)

    @Test
    fun `connect rejects unsupported firmware version`() = runTest {
        val device = NordFixtures.device(ReplayTransport(emptyList(), firmwareVersion = 999), NordFixtures.GRAND_PROFILE)
        try {
            device.connect()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        // The firmware gate runs before anything is sent, so nothing was.
    }

    @Test
    fun `connect sends only the device-info query and reads protocolVersionFileTransfer from it`() = runTest {
        val transport = ReplayTransport(listOf(3 to deviceInfoPayload(10)), firmwareVersion = 168)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.connect()
        assertEquals(168, device.firmwareVersion)
        assertEquals("1.68", device.formatFirmwareVersion(device.firmwareVersion))
        // Exactly one handshake message: the device-info query, and nothing else.
        assertEquals(1, transport.sentRequests.size)
        assertEquals(NordDevice.CtrlSubOp.DEVICE_INFO_QUERY.code, transport.sentRequests[0].subOp)
        assertEquals(10, device.protocolVersionFileTransfer)
    }

    /**
     * A transport whose IN endpoint holds leftovers: [queued] whole messages are served before
     * anything the request under test is answered with, the way a device answers after a
     * session that died mid-reply. Every request is answered with [reply], protocol id and
     * sub-opcode taken from the request unless [answerSubOp] says otherwise. Records whether
     * [drainInput] was called, and how many requests went out.
     */
    private class LeftoverTransport(
        private val queued: List<ByteArray>,
        private val reply: ByteArray,
        private val answerSubOp: ((Int) -> Int) = { it + 1 },
        private val firmwareVersion: Int = 168,
    ) : UsbBulkTransport {
        private val pending = ArrayDeque(queued)
        private var last: NordMessage? = null
        var drained = false
        var requests = 0

        override fun drainInput() {
            drained = true
        }

        override fun bulkWrite(data: ByteArray) {
            last = parseMessage(data)
            requests++
        }

        override fun bulkRead(bufferSize: Int): ByteArray {
            pending.removeFirstOrNull()?.let { return it }
            val msg = checkNotNull(last) { "read with nothing pending" }
            return buildMessage(msg.protocolId, msg.protocolVersion, answerSubOp(msg.subOp), reply)
        }

        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int) =
            byteArrayOf((firmwareVersion and 0xFF).toByte(), ((firmwareVersion shr 8) and 0xFF).toByte())

        override val rebuildOnResume = true
        override fun close() {}
    }

    /** The tail of a content-database reply a killed session never read - what X9 left queued. */
    private fun staleFileTransferReply(): ByteArray =
        buildMessage(NordDevice.PROTOCOL_FILE_TRANSFER, 10, 31, ByteArray(390))

    @Test
    fun `connect drains the endpoint before its first request`() = runTest {
        val transport = LeftoverTransport(emptyList(), deviceInfoPayload(10))
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.connect()
        assertTrue("drainInput() was never called", transport.drained)
        assertEquals(1, transport.requests)
    }

    /**
     * What a process killed mid-read leaves behind: the rest of a reply the device was still
     * sending, which the next session's first read receives as the answer to the device-info
     * query. It wears the wrong protocol id, so it is discarded and the real reply read next;
     * connect() succeeds, with no request repeated.
     */
    @Test
    fun `connect discards a stale reply left over from an earlier session`() = runTest {
        val transport = LeftoverTransport(
            listOf(staleFileTransferReply(), staleFileTransferReply()),
            deviceInfoPayload(10),
        )
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.connect()
        assertEquals(10, device.protocolVersionFileTransfer)
        assertEquals(1, transport.requests)
    }

    /**
     * Stale replies are bounded: an instrument whose every reply answers some other request is
     * out of step, not merely behind, and is refused with both sides named rather than read
     * from until a reply happens to fit.
     */
    @Test
    fun `a reply that never matches its request fails after a bounded number of reads`() = runTest {
        val transport = LeftoverTransport(emptyList(), deviceInfoPayload(10), answerSubOp = { it + 2 })
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        val exc = assertThrows(IllegalStateException::class.java) { runBlocking { device.connect() } }
        val cause = generateSequence<Throwable>(exc) { it.cause }.first { "out of step" in it.message.orEmpty() }
        assertTrue(cause.message, cause.message!!.contains("sub-op=2 with a reply for protocol=7 sub-op=4"))
        // MAX_STALE_REPLIES are discarded; the one after that is the refusal.
        assertTrue(cause.message, cause.message!!.contains("${NordDevice.MAX_STALE_REPLIES + 1} times"))
        // The reseat hint is on the wrapping message: this is the first message of the session.
        assertTrue(exc.message, exc.message!!.contains("unplug the USB cable"))
    }

    @Test
    fun `connect takes the protocol version from the instrument, whatever the profile is`() = runTest {
        // No profile declares a version any more: a Grand profile against a table saying 8
        // gets 8, because the instrument is the only authority on what it speaks.
        val transport = ReplayTransport(listOf(3 to deviceInfoPayload(8)), firmwareVersion = 168)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.connect()
        assertEquals(8, device.protocolVersionFileTransfer)
    }

    /**
     * There is no declared value to fall back to, and that is deliberate.
     *
     * A catalog value could only ever agree with what was read (adding nothing) or disagree with
     * it - in which case trusting it means parsing the instrument's responses against the wrong
     * layout, on the protocol that carries the writes. The query is the first bulk message of every
     * session and needs no prerequisite, so an instrument that will not answer it is one nothing
     * else here would work against either.
     */
    @Test
    fun `a version that cannot be read aborts instead of falling back`() = runTest {
        // An unparseable device-info reply: the count doesn't account for the payload.
        for (profile in listOf(
            NordFixtures.GRAND_PROFILE,
            DeviceProfile.unknown("Nord Something", 0x0FFC, 0x00FF),
        )) {
            val transport = ReplayTransport(listOf(3 to byteArrayOf(9, 6, 1)), firmwareVersion = 168)
            val device = NordFixtures.device(transport, profile)
            try {
                device.connect()
                fail("expected IllegalStateException for ${profile.name}")
            } catch (exc: IllegalStateException) {
                assertTrue(
                    exc.message!!.contains("Couldn't read the file-transfer protocol version"),
                )
            }
        }
    }

    @Test
    fun `parseProtocolVersions decodes both instruments' protocol version tables`() {
        val grand = NordDevice.parseProtocolVersions(parseMessage(NordFixtures.GRAND_DEVICE_INFO_RESPONSE).payload)
        val stage2 = NordDevice.parseProtocolVersions(parseMessage(NordFixtures.STAGE2EX_DEVICE_INFO_RESPONSE).payload)

        // 6 -> PROTOCOL_VERSION_UI, 7 -> PROTOCOL_VERSION_CTRL, 12 -> protocolVersionFileTransfer, plus two commands
        // the instruments advertise and this app never uses.
        assertEquals(mapOf(6 to 1, 7 to 0, 10 to 2, 12 to 10, 13 to 0), grand)
        assertEquals(mapOf(6 to 1, 7 to 0, 10 to 2, 12 to 8, 13 to 0), stage2)
    }

    @Test
    fun `parseProtocolVersions rejects a payload the count field does not account for`() {
        // The count accounting for the payload exactly is what makes this a decoded
        // structure rather than a guess - so a payload it doesn't account for is
        // rejected, not partially parsed.
        assertThrows(IllegalArgumentException::class.java) {
            NordDevice.parseProtocolVersions(byteArrayOf(2, 6, 1)) // claims 2 pairs, carries 1
        }
        assertThrows(IllegalArgumentException::class.java) {
            NordDevice.parseProtocolVersions(ByteArray(0))
        }
    }

    // ---- Parsing against fixture bytes ----

    @Test
    fun `parseRootCategoryList decodes a Nord Grand's root category list`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        val payload = parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload
        val categories = device.parseRootCategoryList(payload)
        assertEquals(9, categories.size)
        assertEquals("Piano (Native)", categories[0])
        assertEquals("Program", categories[6])
    }

    @Test
    fun `a Native category's 0xFFFE capacity parses instead of failing the reply`() {
        // A Nord Stage 2 EX reports 0xFFFE for every "(Native)" category - a documented
        // sentinel, not corruption. Vetting it at parse
        // time threw the whole child list away, which cost three of that instrument's ten
        // categories their bank counts and showed up as three failures in a device report.
        val children = NordDevice.parseCategoryChildren(childListPayload(listOf("Bank 1" to 65534)))

        assertEquals(1, children.size)
        assertEquals("reported as the instrument gave it, not rejected", 65534, children[0].capacity)
    }

    @Test
    fun `an implausible capacity is still refused as an addressing bound`() = runTest {
        // The plausibility bound sits where the value becomes an addressing bound, not in the
        // parser: deriveBankLayout feeds InstrumentViewModel.allSlots.
        val root = parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload
        val device = NordFixtures.device(
            ReplayTransport(listOf(1 to root, 3 to childListPayload(listOf("Bank 1" to 9_999_999)))),
            NordFixtures.GRAND_PROFILE,
        )
        try {
            device.deriveBankLayout()
            fail("expected an implausible slots-per-bank to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("not a usable addressing bound"))
        }
    }

    @Test
    fun `an ordinary capacity still derives a bank layout`() = runTest {
        val root = parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload
        val device = NordFixtures.device(
            ReplayTransport(listOf(1 to root, 3 to childListPayload(List(16) { "Bank" to 25 }))),
            NordFixtures.GRAND_PROFILE,
        )
        val layout = device.deriveBankLayout()!!

        assertEquals(16, layout.bankCount)
        assertEquals(25, layout.slotsPerBank)
    }

    @Test
    fun `parseRootCategories reads each area's allocation unit from its trailer`() {
        // The first word of a category's trailer is its storage area's allocation unit in
        // bytes. These are both instruments' own root lists, and the figures are what the
        // devices state.
        val grand = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
            .parseRootCategories(parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload)
            .associate { it.name to it.unitBytes }
        assertEquals(130816, grand["Piano"])
        assertEquals(130816, grand["Piano Pedal"])
        assertEquals(65408, grand["Samp Lib"])

        val stage2 = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.STAGE2EX_PROFILE)
            .parseRootCategories(parseMessage(NordFixtures.STAGE2EX_ROOT_LIST_RESPONSE).payload)
            .associate { it.name to it.unitBytes }
        assertEquals(261632, stage2["Piano"])
        assertEquals(196596, stage2["Samp Lib"])
        // A byte-counted area reports 1, so it needs no special case.
        assertEquals(1, stage2["Program"])
        assertEquals(1, stage2["Settings"])
    }

    @Test
    fun `parseRootCategoryList decodes a Nord Stage 2 EX's root category list`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.STAGE2EX_PROFILE)
        val payload = parseMessage(NordFixtures.STAGE2EX_ROOT_LIST_RESPONSE).payload
        val categories = device.parseRootCategoryList(payload)
        // One more category than the Grand, behind a one-byte-shorter trailer.
        assertEquals(10, categories.size)
        assertEquals("Piano (Native)", categories[0])
        assertEquals("Program", categories[6])
        assertEquals("Synth", categories[7])
    }

    /**
     * The trailer length isn't announced anywhere in the handshake, but the root category list
     * response pins it down on its own: of every candidate length, only the real one walks the
     * payload and lands exactly on the end.
     */
    @Test
    fun `detectRootCategoryTrailerLen recovers the expected value from fixture data`() {
        assertEquals(
            29,
            NordDevice.detectRootCategoryTrailerLen(parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload),
        )
        assertEquals(
            28,
            NordDevice.detectRootCategoryTrailerLen(parseMessage(NordFixtures.STAGE2EX_ROOT_LIST_RESPONSE).payload),
        )
    }

    // ---- Protocol version rules ----
    //
    // The file-transfer protocol version decides two wire formats: how many flag bytes the
    // root-category trailer carries, and whether an item record ends with a content id. Both rules
    // are asserted at every version in the accepted range - including the tiers no instrument in
    // this repository sits in.

    /**
     * A file-transfer version outside 3-10 is refused, not warned about.
     *
     * Every response layout this app knows was decoded from that range, and a revision outside it
     * can differ in ways nothing here would detect - including on the writes that move, rename and
     * delete presets.
     */
    @Test
    fun `a file-transfer version outside the known range is refused`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        for (bad in listOf(0, 2, 11, 99)) {
            val failure = assertThrows(UnsupportedProtocolVersionException::class.java) {
                device.checkProtocolVersions(
                    mapOf(
                        NordDevice.PROTOCOL_UI to 1,
                        NordDevice.PROTOCOL_CTRL to 0,
                        NordDevice.PROTOCOL_FILE_TRANSFER to bad,
                    ),
                )
            }
            assertTrue("names version $bad", failure.message!!.contains("$bad"))
            assertTrue(failure.message!!.contains("3-10"))
        }
    }

    @Test
    fun `every version in the supported range is accepted`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        for (good in NordDevice.MIN_FILE_TRANSFER_VERSION..NordDevice.MAX_KNOWN_FILE_TRANSFER_VERSION) {
            device.checkProtocolVersions(
                mapOf(
                    NordDevice.PROTOCOL_UI to 1,
                    NordDevice.PROTOCOL_CTRL to 0,
                    NordDevice.PROTOCOL_FILE_TRANSFER to good,
                ),
            )
        }
    }

    /**
     * A UI protocol this app doesn't know is skipped, not guessed at.
     *
     * Sub-op 0/1 locks the instrument's display and inhibits playing until 2/3 releases it, so
     * sending a lock over a layout that may have moved is the one thing worth avoiding.
     * Everything the UI protocol does here is cosmetic, so nothing else changes - which is why
     * the capability query reports itself skipped rather than the connection failing.
     */
    @Test
    fun `an unexpected UI version stops the app speaking that protocol`() = runTest {
        val transport = ReplayTransport(emptyList())
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.checkProtocolVersions(
            mapOf(
                NordDevice.PROTOCOL_UI to 2, // not the 1 this app knows
                NordDevice.PROTOCOL_CTRL to 0,
                NordDevice.PROTOCOL_FILE_TRANSFER to 10,
            ),
        )
        val failure = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { device.capabilityQueryPayload() }
        }
        assertTrue(failure.message!!.contains("skipped"))
        assertTrue("nothing went on the wire", transport.sentRequests.isEmpty())
    }

    /** Protocol 6 at version 1 and protocol 7 at version 0, on both known instruments. */
    @Test
    fun `both instruments report the UI and Ctrl versions this app expects`() {
        for (response in listOf(NordFixtures.GRAND_DEVICE_INFO_RESPONSE, NordFixtures.STAGE2EX_DEVICE_INFO_RESPONSE)) {
            val table = NordDevice.parseProtocolVersions(parseMessage(response).payload)
            assertEquals(NordDevice.EXPECTED_PROTOCOL_VERSION_UI, table[NordDevice.PROTOCOL_UI])
            assertEquals(NordDevice.EXPECTED_PROTOCOL_VERSION_CTRL, table[NordDevice.PROTOCOL_CTRL])
        }
    }

    @Test
    fun `the trailer length follows the protocol version at every supported version`() {
        // 10 flags below 5, + erase and deps from 5, + one more from 10.
        assertEquals(26, NordDevice.rootCategoryTrailerLenForVersion(3))
        assertEquals(26, NordDevice.rootCategoryTrailerLenForVersion(4))
        for (version in 5..9) {
            assertEquals("version $version", 28, NordDevice.rootCategoryTrailerLenForVersion(version))
        }
        assertEquals(29, NordDevice.rootCategoryTrailerLenForVersion(10))
    }

    @Test
    fun `both instruments land on their expected trailer lengths`() {
        assertEquals(29, NordDevice.rootCategoryTrailerLenForVersion(10)) // Nord Grand
        assertEquals(28, NordDevice.rootCategoryTrailerLenForVersion(8))  // Nord Stage 2 EX
    }

    /**
     * A later revision could add a fifteenth flag byte exactly as version 10 added the fourteenth.
     * Answering 29 for it would parse every entry after the first into garbage, so the rule
     * declines and the caller falls back to the response's own arithmetic, which adapts.
     */
    @Test
    fun `the version rule declines above the known range`() {
        for (version in listOf(11, 12, 99, null)) {
            assertEquals("version $version", null, NordDevice.rootCategoryTrailerLenForVersion(version))
        }
    }

    @Test
    fun `the content id is a version 10 field, not a Nord Grand field`() {
        for (version in listOf(3, 4, 5, 8, 9)) {
            assertEquals("version $version", false, NordDevice.itemRecordHasContentId(version))
        }
        assertEquals(true, NordDevice.itemRecordHasContentId(10))
        assertEquals(null, NordDevice.itemRecordHasContentId(11))
        assertEquals(null, NordDevice.itemRecordHasContentId(null))
    }

    @Test
    fun `an unknown device parses a real root list with nothing declared`() {
        // The case detection helps most: nothing declared, no guesses, still correct. No profile
        // declares a trailer length any more, so this is the only way it can work at all.
        val device = NordFixtures.device(ReplayTransport(emptyList()), DeviceProfile.unknown("Nord Something", 0x0FFC, 0x00FF))
        val categories = device.parseRootCategoryList(parseMessage(NordFixtures.STAGE2EX_ROOT_LIST_RESPONSE).payload)
        assertEquals(10, categories.size)
        assertEquals("Program", categories[6])
    }

    @Test
    fun `a zero-category root list is the empty list, and a corrupt one throws`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)

        // Detection can't pin a trailer length down here - every candidate trivially "fits" a
        // list of no categories - but the walk never consumes one either, so there is nothing
        // to guess and nothing to fail over.
        assertEquals(emptyList<String>(), device.parseRootCategoryList(ByteArray(5)))

        // A list that claims categories it doesn't carry is a different matter: no candidate
        // length walks it, and no declared value could have rescued it.
        val truncated = ByteArray(9).also { it[4] = 2 }
        assertThrows(IllegalStateException::class.java) { device.parseRootCategoryList(truncated) }
    }

    @Test
    fun `parseRootCategoryList follows the response over the profile's own instrument`() {
        // A Stage 2 EX profile (28-byte trailers on its own hardware) handed a Nord Grand's
        // response (29) still parses it correctly: nothing but the response decides the layout.
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.STAGE2EX_PROFILE)
        val categories = device.parseRootCategoryList(parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload)
        assertEquals(9, categories.size)
        assertEquals("Program", categories[6])
    }

    @Test
    fun `parseItemName decodes real ngp item record`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        val payload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        assertEquals("White Grand", device.parseItemName(payload))

        val named = device.collectItemNames(listOf(payload))
        assertEquals(1, named.size)
        assertEquals("A:1:1", named[0].presetId)
        assertEquals("White Grand", named[0].name)
    }

    // ---- Preset id parsing ----

    @Test
    fun `parsePresetId and formatPresetId round-trip`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        val bankItem = device.parsePresetId("B:2:2")
        assertEquals(NordDevice.BankItem(1, 6), bankItem)
        assertEquals("B:2:2", device.formatPresetId(bankItem.bank, bankItem.item))
    }

    @Test
    fun `parsePresetId rejects out-of-range bank`() {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        assertThrows(IllegalArgumentException::class.java) { device.parsePresetId("Z:1:1") }
    }

    // ---- fetchCategoryItems cursor walk ----
    // SELECT_CATEGORY locks the instrument; every case below ends with a sub-op 6/7
    // UNLOCK_CATEGORY_SELECTION response, since fetchCategoryItems() always unlocks again in a
    // `finally` block, success or failure.

    @Test
    fun `fetchCategoryItems walks a two-item bank via the cursor and unlocks afterward`() = runTest {
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val responses = listOf(
            3 to childListPayload(listOf("Bank 1" to 25)), // GET_CATEGORY_CHILD -> bounds the walk
            5 to ByteArray(0), // SELECT_CATEGORY -> 5
            9 to (uint32BE(0) + uint32BE(2)), // GET_ITEM_COUNT -> total_count=2 at offset 4
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(0)), // CURSOR_NEXT_ITEM -> item 0
            31 to itemPayload, // FETCH_ITEM -> real ngp record fixture
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(1)), // CURSOR_NEXT_ITEM -> item 1
            31 to itemPayload, // FETCH_ITEM
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        val items = device.fetchCategoryItems(6)
        assertEquals(2, items.size)
        items.forEach { assertEquals("White Grand", device.parseItemName(it)) }
        assertEquals(NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code, transport.sentRequests.last().subOp)
    }

    @Test
    fun `fetchCategoryItems advances bank on exhaustion`() = runTest {
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val responses = listOf(
            3 to childListPayload(List(2) { "Bank" to 25 }), // two banks
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(1)), // total_count=1
            33 to (uint32BE(1) + uint32BE(0) + uint32BE(0)), // bank 0 exhausted (bool flag set)
            33 to (uint32BE(0) + uint32BE(1) + uint32BE(0)), // bank 1, item 0
            31 to itemPayload,
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val items = device.fetchCategoryItems(6)
        assertEquals(1, items.size)
    }

    @Test
    fun `fetchCategoryItems walks the cursor even when the item count reads zero`() = runTest {
        // The Nord Stage 2 EX reports an item count of 0 for its Live and Settings
        // categories while both hold items. Using the count as the loop condition would
        // return nothing.
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val responses = listOf(
            3 to childListPayload(List(2) { "Bank" to 25 }), // two banks
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(0)), // total_count = 0, which is a lie
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(0)), // bank 0, item 0
            31 to itemPayload,
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(1)), // bank 0, item 1
            31 to itemPayload,
            33 to (uint32BE(1) + uint32BE(0) + uint32BE(1)), // bank 0 exhausted
            33 to (uint32BE(1) + uint32BE(1) + uint32BE(0)), // bank 1 empty -> ends the walk
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        val items = device.fetchCategoryItems(8)
        assertEquals(2, items.size)
        assertEquals(NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code, transport.sentRequests.last().subOp)
    }

    @Test
    fun `fetchCategoryItems reports a non-zero count the cursor cannot deliver`() = runTest {
        // Every bank reports itself exhausted, so the cursor yields nothing at all while
        // the count claims 3. Only a count of *zero* is treated as uninformative.
        //
        // Running out of banks with the count unsatisfied now consults the category's
        // child list once, so that reply is part of the expected traffic: it reports the
        // same 16 banks the walk already covered, which raises nothing and leaves the
        // disagreement to be reported as before.
        val exhausted = uint32BE(1) + uint32BE(0) + uint32BE(0)
        val responses = listOf(
            3 to childListPayload(List(16) { "Bank" to 25 }), // the Grand's 16 banks, A-P
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(3)), // claims 3 items
        ) + List(16) { 33 to exhausted } + // all empty
            listOf(7 to ByteArray(0)) // UNLOCK_CATEGORY_SELECTION -> unlock
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        try {
            device.fetchCategoryItems(6)
            fail("expected a count/cursor disagreement to be reported")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("reported 3 items"))
        }
    }

    @Test
    fun `fetchCategoryItems walks a category holding more banks than there are bank letters`() = runTest {
        // The bank walk takes its bound from the category's own child list, not from the
        // instrument's program bank letters - those describe `Program` and nothing else.
        // A Nord Stage 2 EX addresses programs in banks A-D, but its `Piano` category has
        // six children (Grand, Upright, EPiano1, EPiano2, Clavinet, Harps). Bounded at
        // four it returned 21 of the 25 items the count field promised; on the
        // instrument, banks 4 and 5 hold the missing 1 and 3.
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val perBank = listOf(5, 5, 9, 2, 1, 3) // the real distribution: 21 in A-D, 4 beyond
        val responses = mutableListOf<Pair<Int, ByteArray>>(
            3 to childListPayload(
                listOf("Grand" to 15, "Upright" to 15, "EPiano1" to 15, "EPiano2" to 15, "Clavinet" to 15, "Harps" to 15),
            ),
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(perBank.sum())), // count = 25
        )
        for (bank in perBank.indices) {
            for (item in 0 until perBank[bank]) {
                responses += 33 to (uint32BE(0) + uint32BE(bank) + uint32BE(item))
                responses += 31 to itemPayload
            }
            responses += 33 to (uint32BE(1) + uint32BE(bank) + uint32BE(0)) // bank exhausted
        }
        responses += 7 to ByteArray(0) // UNLOCK_CATEGORY_SELECTION

        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.STAGE2EX_PROFILE)
        assertEquals(25, device.fetchCategoryItems(1).size)
        assertEquals(
            "the child list is read once per category, then cached",
            1,
            transport.sentRequests.count { it.subOp == NordDevice.FileTransferSubOp.GET_CATEGORY_CHILD.code },
        )
    }

    @Test
    fun `fetchCategoryItems caches a category's bank count across walks`() = runTest {
        // The bound costs one round trip per category. A child list is a fixed firmware
        // table, so a second walk of the same category must reuse it rather than ask again.
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val oneWalk = listOf(
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(1)),
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
            31 to itemPayload,
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
        )
        val responses = listOf(3 to childListPayload(listOf("Bank 1" to 100))) + oneWalk + oneWalk

        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.STAGE2EX_PROFILE)
        assertEquals(1, device.fetchCategoryItems(6).size)
        assertEquals(1, device.fetchCategoryItems(6).size)
        assertEquals(
            "cached after the first walk",
            1,
            transport.sentRequests.count { it.subOp == NordDevice.FileTransferSubOp.GET_CATEGORY_CHILD.code },
        )
    }

    @Test
    fun `fetchCategoryItems does not probe a one-bank category past its only bank`() = runTest {
        // The gain the bound buys beyond correctness: a category reporting one child is
        // walked as one bank. Before, `Live` and `Settings` - one bank each, and an item
        // count of 0 - were only stopped by *finding* bank 1 empty, which cost a cursor
        // request to a bank the instrument does not have.
        val itemPayload = parseMessage(NordFixtures.NGP_RECORD_RESPONSE).payload
        val responses = listOf(
            3 to childListPayload(listOf("Live" to 5)),
            5 to ByteArray(0), // SELECT_CATEGORY
            9 to (uint32BE(0) + uint32BE(0)), // the count reads 0 while the category holds items
            33 to (uint32BE(0) + uint32BE(0) + uint32BE(0)),
            31 to itemPayload,
            33 to (uint32BE(1) + uint32BE(0) + uint32BE(0)), // bank 0 exhausted
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.STAGE2EX_PROFILE)
        assertEquals(1, device.fetchCategoryItems(8).size)
        // Every cursor request names bank 0; bank 1 is never asked about.
        val banksAsked = transport.sentRequests
            .filter { it.subOp == NordDevice.FileTransferSubOp.CURSOR_NEXT_ITEM.code }
            .map { readUInt32BE(it.payload, 0) }
        assertEquals(setOf(0), banksAsked.toSet())
    }

    // ---- selectPreset / moveProgram ----
    // Neither sends UiSubOp.ENTER_STATUS_MODE first - only
    // SELECT_CATEGORY locks the instrument, so only the trailing unlock (sub-op 6/7) is needed.

    @Test
    fun `selectPreset succeeds when the device echoes the request`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList, // ROOT_CATEGORY_LIST (via getProgramCategoryIndex)
            5 to ByteArray(0), // SELECT_CATEGORY
            48 to (uint32BE(0) + uint32BE(1) + uint32BE(6)), // SELECT_PRESET echo: flag=0 bank=1 item=6
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        device.selectPreset(1, 6) // no exception == success
    }

    @Test
    fun `selectPreset throws when the echoed response does not match, but still unlocks`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            48 to (uint32BE(0) + uint32BE(1) + uint32BE(7)), // wrong echoed item
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock, even on failure
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        try {
            device.selectPreset(1, 6)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code, transport.sentRequests.last().subOp)
    }

    @Test
    fun `swapPrograms succeeds on status zero`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0), // SELECT_CATEGORY
            27 to uint32BE(0), // SWAP_PROGRAMS status=0
            48 to (uint32BE(0) + uint32BE(0) + uint32BE(0)), // reloadPresetForDisplay(src): SELECT_PRESET echo
            48 to (uint32BE(0) + uint32BE(0) + uint32BE(3)), // reloadPresetForDisplay(dst): SELECT_PRESET echo
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.swapPrograms(0, 0, 0, 3) // no exception == success
        assertEquals(NordDevice.FileTransferSubOp.SWAP_PROGRAMS.code, transport.sentRequests[2].subOp)
    }

    /**
     * Delete is sub-opcode 20/21, a device primitive rather than a
     * composed erase - nothing is read first and no blank payload is written.
     */
    @Test
    fun `deleteProgram uses sub-op 20 and accepts the echoed request`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0), // SELECT_CATEGORY
            21 to (uint32BE(0) + uint32BE(8) + uint32BE(12)), // status=0 + echo of (bank 8, item 12)
            48 to (uint32BE(0) + uint32BE(8) + uint32BE(12)), // reloadPresetForDisplay
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.deleteProgram(8, 12) // no exception == success

        val delete = transport.sentRequests[2]
        assertEquals(NordDevice.FileTransferSubOp.DELETE_ITEM.code, delete.subOp)
        assertTrue((uint32BE(8) + uint32BE(12)).contentEquals(delete.payload))
    }

    /**
     * A mismatched echo is refused.
     *
     * On a delete this is not pedantry: the echo is the only evidence the instrument emptied the
     * slot that was asked for rather than a different one, and the difference is losing the right
     * preset or the wrong one.
     */
    @Test
    fun `deleteProgram rejects an echo for a different slot`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            21 to (uint32BE(0) + uint32BE(8) + uint32BE(13)), // status 0, but item 13 not 12
            7 to ByteArray(0), // unlock still runs
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        try {
            device.deleteProgram(8, 12)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    /** A non-zero status is a refusal, whatever the echo says. */
    @Test
    fun `deleteProgram rejects a non-zero status`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            21 to (uint32BE(9) + uint32BE(8) + uint32BE(12)),
            7 to ByteArray(0),
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        try {
            device.deleteProgram(8, 12)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    /**
     * The move-to-an-empty-slot counterpart: sub-opcode 24, whose
     * reply carries a 16-byte echo of the request after the status word (where 26/27's carries
     * the status word alone).
     */
    @Test
    fun `moveProgram uses sub-op 24 and accepts the echoed request`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0), // SELECT_CATEGORY
            // MOVE_PROGRAM status=0 + echo of (srcBank=7, srcItem=24, dstBank=8, dstItem=0)
            25 to (uint32BE(0) + uint32BE(7) + uint32BE(24) + uint32BE(8) + uint32BE(0)),
            48 to (uint32BE(0) + uint32BE(7) + uint32BE(24)), // reloadPresetForDisplay(src)
            48 to (uint32BE(0) + uint32BE(8) + uint32BE(0)), // reloadPresetForDisplay(dst)
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.moveProgram(7, 24, 8, 0) // H:5:5 -> empty I:1:1; no exception == success
        val move = transport.sentRequests[2]
        assertEquals(NordDevice.FileTransferSubOp.MOVE_PROGRAM.code, move.subOp)
        assertTrue((uint32BE(7) + uint32BE(24) + uint32BE(8) + uint32BE(0)).contentEquals(move.payload))
    }

    @Test
    fun `moveProgram rejects a mismatched echo`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0), // SELECT_CATEGORY
            // status=0 but the echoed destination is not the one that was asked for
            25 to (uint32BE(0) + uint32BE(7) + uint32BE(24) + uint32BE(9) + uint32BE(0)),
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock still runs
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        try {
            device.moveProgram(7, 24, 8, 0)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    /**
     * Copy is sub-opcode 22/23: [moveProgram]'s four-field
     * payload and reply shape, but the source stays put and the instrument names the copy - so the
     * destination's item record is read back inside the same lock and its name returned.
     */
    @Test
    fun `copyProgram uses sub-op 22 and returns the name the instrument chose`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0), // SELECT_CATEGORY
            // COPY_PROGRAM status=0 + echo of (srcBank=7, srcItem=23, dstBank=8, dstItem=12)
            23 to (uint32BE(0) + uint32BE(7) + uint32BE(23) + uint32BE(8) + uint32BE(12)),
            31 to itemRecordPayload(8, 12, "Synth Strings 2"), // FETCH_ITEM: the auto-assigned name
            48 to (uint32BE(0) + uint32BE(8) + uint32BE(12)), // reloadPresetForDisplay(dst)
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        assertEquals("Synth Strings 2", device.copyProgram(7, 23, 8, 12))

        val copy = transport.sentRequests[2]
        assertEquals(NordDevice.FileTransferSubOp.COPY_PROGRAM.code, copy.subOp)
        assertTrue((uint32BE(7) + uint32BE(23) + uint32BE(8) + uint32BE(12)).contentEquals(copy.payload))
    }

    /**
     * Status 4 is the instrument's "file exists", and an occupied destination is the one
     * copy failure a caller can act on - so it is named rather than reported as a bare number.
     */
    @Test
    fun `copyProgram refuses an occupied destination, but still unlocks`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            23 to (uint32BE(4) + uint32BE(7) + uint32BE(23) + uint32BE(8) + uint32BE(12)),
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock, even on failure
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        try {
            device.copyProgram(7, 23, 8, 12)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("occupied"))
        }
        assertEquals(NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code, transport.sentRequests.last().subOp)
    }

    @Test
    fun `copyProgram rejects a mismatched echo`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            // status=0 but the echoed destination is not the one that was asked for
            23 to (uint32BE(0) + uint32BE(7) + uint32BE(23) + uint32BE(9) + uint32BE(12)),
            7 to ByteArray(0),
        )
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        try {
            device.copyProgram(7, 23, 8, 12)
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    /**
     * A sub-opcode 30/31 item record carrying [name]. Only the fields NordDevice.parseItemName
     * reads are filled: the length prefix at offset 32 and the name from 36.
     */
    private fun itemRecordPayload(bank: Int, item: Int, name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val record = ByteArray(36)
        uint32BE(bank).copyInto(record, 4)
        uint32BE(item).copyInto(record, 8)
        uint32BE(nameBytes.size).copyInto(record, 32)
        return record + nameBytes
    }

    @Test
    fun `renamePreset succeeds when the device echoes the request`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList, // ROOT_CATEGORY_LIST (via getProgramCategoryIndex)
            5 to ByteArray(0), // SELECT_CATEGORY
            29 to (uint32BE(0) + uint32BE(1) + uint32BE(11)), // SET_NAME echo: flag=0 bank=1 item=11
            48 to (uint32BE(0) + uint32BE(1) + uint32BE(11)), // reloadPresetForDisplay: SELECT_PRESET echo
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        device.renamePreset(1, 11, "Black Upright") // no exception == success

        // SET_NAME's payload is bank(4) + item(4) + nameLen(4) + name
        val sent = transport.sentRequests.first { it.subOp == NordDevice.FileTransferSubOp.SET_NAME.code }
        assertEquals(1, readUInt32BE(sent.payload, 0))
        assertEquals(11, readUInt32BE(sent.payload, 4))
        assertEquals(13, readUInt32BE(sent.payload, 8))
        assertEquals("Black Upright", String(sent.payload, 12, 13, Charsets.US_ASCII))
    }

    @Test
    fun `renamePreset throws when the echoed response does not match, but still unlocks`() = runTest {
        val rootList = buildRootCategoryListPayload(listOf("Piano", "Program", "Settings"), trailerLen = 29)
        val responses = listOf(
            1 to rootList,
            5 to ByteArray(0),
            29 to (uint32BE(0) + uint32BE(1) + uint32BE(12)), // wrong echoed item
            7 to ByteArray(0), // UNLOCK_CATEGORY_SELECTION -> unlock, even on failure
        )
        val transport = ReplayTransport(responses)
        val device = NordFixtures.device(transport, NordFixtures.GRAND_PROFILE)
        try {
            device.renamePreset(1, 11, "Black Upright")
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(NordDevice.FileTransferSubOp.UNLOCK_CATEGORY_SELECTION.code, transport.sentRequests.last().subOp)
    }

    @Test
    fun `renamePreset rejects a blank name`() = runTest {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        try {
            device.renamePreset(0, 0, "")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `renamePreset rejects a non-ASCII name`() = runTest {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        try {
            device.renamePreset(0, 0, "Flügel")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun readUInt32BE(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    @Test
    fun `getCategoryIndexByName finds Program in a Nord Grand's root category list`() = runTest {
        val payload = parseMessage(NordFixtures.GRAND_ROOT_LIST_RESPONSE).payload
        val responses = listOf(1 to payload)
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        assertEquals(6, device.getProgramCategoryIndex())
    }

    @Test
    fun `hex helper decodes as expected`() {
        assertTrue(hex("00ff").contentEquals(byteArrayOf(0x00, 0xFF.toByte())))
    }

    // ---- Reply framing ----

    /**
     * A transport whose bulk reads hand back exactly the byte runs given - the point being that
     * a bulk read carries *bytes*, with no promise of landing on a message boundary either way.
     */
    private class ChunkedTransport(chunks: List<ByteArray>) : UsbBulkTransport {
        private val chunks = chunks.toMutableList()
        override fun bulkWrite(data: ByteArray) {}
        override fun bulkRead(bufferSize: Int): ByteArray {
            check(chunks.isNotEmpty()) { "read past the end of the scripted chunks" }
            return chunks.removeAt(0)
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int) =
            ByteArray(length)

        override val rebuildOnResume = true
        override fun close() {}
    }

    private fun chunkedDevice(vararg chunks: ByteArray) =
        NordFixtures.device(ChunkedTransport(chunks.toList()), NordFixtures.GRAND_PROFILE)

    @Test
    fun `one whole message per read is the ordinary case`() = runTest {
        val built = buildMessage(12, 10, 1, "payload!".toByteArray())
        val msg = chunkedDevice(built).readReply()
        assertEquals(1, msg.subOp)
        assertEquals("payload!", String(msg.payload))
    }

    /**
     * Needed on this side regardless of what the instrument does: READ_BUFSIZE is 8 KB here and
     * a READ_ITEM_DATA reply can be 32 KB, so a large reply always spans reads.
     */
    @Test
    fun `a message split across reads is reassembled`() = runTest {
        val built = buildMessage(12, 10, 1, ByteArray(400) { 'x'.code.toByte() })
        val device = chunkedDevice(
            built.copyOfRange(0, 64),
            built.copyOfRange(64, 200),
            built.copyOfRange(200, built.size),
        )
        val msg = device.readReply()
        assertEquals(1, msg.subOp)
        assertEquals(400, msg.payload.size)
    }

    @Test
    fun `a surplus message is kept for the next read`() = runTest {
        val first = buildMessage(12, 10, 1, "first".toByteArray())
        val second = buildMessage(12, 10, 9, "second".toByteArray())
        // One read carrying both; the scripted chunks running out is what proves the second
        // was served from the buffer rather than from the endpoint.
        val device = chunkedDevice(first + second)

        assertEquals("first", String(device.readReply().payload))
        assertEquals("second", String(device.readReply().payload))
    }

    /**
     * Answers every read with a zero-length transfer, after an optional first chunk.
     *
     * A ZLP is a legal USB reply carrying no bytes, so `bulkRead`'s `read >= 0` guard passes it
     * through as an empty array, and an unbounded reader would hang on it rather than fail.
     */
    private class EmptyReadTransport(private val first: ByteArray? = null) : UsbBulkTransport {
        private var sentFirst = false
        override fun bulkWrite(data: ByteArray) {}
        override fun bulkRead(bufferSize: Int): ByteArray {
            if (first != null && !sentFirst) {
                sentFirst = true
                return first
            }
            return ByteArray(0)
        }
        override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, length: Int) =
            ByteArray(length)

        override val rebuildOnResume = true
        override fun close() {}
    }

    /**
     * A device answering only with zero-length transfers makes no progress, and [readReply] used to
     * wait on that forever - in a loop with no suspension point, so neither cancellation nor a
     * surrounding timeout could end it. One IO thread stranded per reconnect.
     */
    @Test
    fun `a device that only returns empty reads is given up on`() = runTest {
        val device = NordFixtures.device(EmptyReadTransport(), NordFixtures.GRAND_PROFILE)
        val caught = assertThrows(IllegalStateException::class.java) {
            runBlocking { device.readReply() }
        }
        assertTrue(caught.message!!, caught.message!!.contains("empty replies in a row"))
    }

    /**
     * The same hazard reached the other way: a valid declared length arrives, so the loop is
     * legitimately waiting for the rest of a message the device then never sends.
     */
    @Test
    fun `a truncated message followed by empty reads is given up on`() = runTest {
        val built = buildMessage(12, 10, 1, ByteArray(400) { 'x'.code.toByte() })
        val device = NordFixtures.device(
            EmptyReadTransport(first = built.copyOfRange(0, 64)),
            NordFixtures.GRAND_PROFILE,
        )
        val caught = assertThrows(IllegalStateException::class.java) {
            runBlocking { device.readReply() }
        }
        assertTrue(caught.message!!, caught.message!!.contains("empty replies in a row"))
    }

    // ---- Item names ----

    /** Builds a minimal sub-opcode 31 item record carrying [name] in its name field. */
    private fun itemRecord(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(36 + bytes.size)
        payload[32] = (bytes.size ushr 24).toByte()
        payload[33] = (bytes.size ushr 16).toByte()
        payload[34] = (bytes.size ushr 8).toByte()
        payload[35] = bytes.size.toByte()
        bytes.copyInto(payload, 36)
        return payload
    }

    /**
     * The name's *length* was checked; its *contents* were not. A newline renders as two rows in
     * the browser the user picks delete targets from, and NUL or ESC reaches the shared report and
     * logcat. The equivalent rule was already enforced for category names and USB product strings.
     */
    @Test
    fun `control characters in an item name are stripped`() = runTest {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        assertEquals("GrandPiano", device.parseItemName(itemRecord("Grand\u0000Pi\nano")))
    }

    /** An ordinary name is untouched - the filter must not eat legitimate punctuation or spaces. */
    @Test
    fun `an ordinary item name survives sanitising`() = runTest {
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)
        assertEquals("Grand Piano 1 (v2)", device.parseItemName(itemRecord("Grand Piano 1 (v2)")))
    }

    /**
     * A garbage length must not become an unbounded wait, one timeout at a time.
     */
    @Test
    fun `an unusable declared length is refused rather than awaited`() = runTest {
        val device = chunkedDevice(hex("ffffffff") + ByteArray(32))
        val caught = assertThrows(IllegalStateException::class.java) { runBlocking { device.readReply() } }
        assertTrue(caught.message!!, caught.message!!.contains("unusable length"))
    }
}
