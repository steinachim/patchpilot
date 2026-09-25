// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Storage-unit calibration, held to the same figures as five hand-measured real areas, plus the
 * one that could not be measured. Item sizes for the real areas aren't recorded anywhere - only
 * the totals are - so these reconstruct each area's *shape* (item count, size range, and the
 * documented residual between the sum and the reported `used`) and require the solver to land on
 * the unit that was measured.
 */
class StorageCalibrationTest {

    private fun sizes(count: Int, low: Int, high: Int, seed: Int): List<Int> {
        val rng = Random(seed)
        return List(count) { rng.nextInt(low, high) }
    }

    private fun check(sizes: List<Int>, unit: Int, overhead: Int) {
        val used = (NordDevice.storageUnitsAt(sizes, unit) + overhead).toInt()
        val solved = NordDevice.solveStorageUnit(sizes, used)
        assertEquals(solved.reason, unit, solved.unitBytes)
        assertEquals(overhead, solved.residual)
        assertTrue("$unit outside ${solved.candidateRange}", solved.candidateRange!!.first <= unit)
        assertTrue("$unit outside ${solved.candidateRange}", solved.candidateRange!!.second >= unit)
    }

    @Test
    fun `recovers the Grand's 64 KB sample unit`() {
        // 235 records, used 8,164, sum short by 21.
        check(sizes(235, 200_000, 5_000_000, seed = 1), 64 * 1024, 21)
    }

    @Test
    fun `recovers the Grand's 128 KB piano unit`() {
        // 38 records, used 15,427, sum short by 33.
        check(sizes(38, 20_000_000, 90_000_000, seed = 2), 128 * 1024, 33)
    }

    @Test
    fun `recovers the Stage 2 EX's 192 KB sample unit`() {
        // The area whose real unit is neither the sixth field's product nor the Grand's, and the
        // one that made this a per-area measurement at all.
        check(sizes(202, 100_000, 3_000_000, seed = 3), 192 * 1024, 1)
    }

    @Test
    fun `recovers the Stage 2 EX's 256 KB piano unit`() {
        check(sizes(25, 5_000_000, 60_000_000, seed = 4), 256 * 1024, 5)
    }

    @Test
    fun `recovers a unit from a single record`() {
        // The Stage 2 EX's npdl area: one 2,285,000-byte item occupying exactly 9 units, which
        // 256 KB satisfies and 192 KB does not.
        check(listOf(2_285_000), 256 * 1024, 0)
    }

    @Test
    fun `refuses the Grand's slot-counted Program area`() {
        // 202 programs of 90 bytes plus the live sets and settings sharing their area, against a
        // used of 219. One unit per item, so no block size is determinable - and a
        // fitted answer here would be worse than none.
        val sizes = List(202) { 90 } + List(5) { 400 } + listOf(200)
        val solved = NordDevice.solveStorageUnit(sizes, 219)

        assertNull(solved.unitBytes)
        assertNull(solved.residual)
        assertTrue(solved.reason, solved.reason.contains("slot count"))
    }

    @Test
    fun `the wrong candidates miss by far more than the tolerance`() {
        // What makes the fit trustworthy: neighbouring candidates are off by tens of percent
        // where the accepted residual is under one.
        val sizes = sizes(235, 200_000, 5_000_000, seed = 1)
        val used = NordDevice.storageUnitsAt(sizes, 64 * 1024)
        for (candidate in listOf(32 * 1024, 128 * 1024, 192 * 1024)) {
            val error = kotlin.math.abs(used - NordDevice.storageUnitsAt(sizes, candidate)).toDouble() / used
            assertTrue("$candidate is too close to call", error > 0.25)
        }
    }

    @Test
    fun `reports no answer rather than a bad one`() {
        assertNull(NordDevice.solveStorageUnit(emptyList(), 100).unitBytes)
        assertNull(NordDevice.solveStorageUnit(listOf(1000), 0).unitBytes)
        assertTrue(
            NordDevice.solveStorageUnit(List(10) { 1000 }, 4).reason.contains("fewer units than items"),
        )
        // 10 items of 1,000 bytes total 10 units or 20, never 15.
        assertTrue(
            NordDevice.solveStorageUnit(List(10) { 1000 }, 15).reason.contains("no unit size reproduces"),
        )
    }

    @Test
    fun `rounds to the unit a real instrument would use`() {
        assertEquals(65536, NordDevice.roundestInRange(65_000, 66_000))
        assertEquals(196608, NordDevice.roundestInRange(195_000, 198_000))
        assertEquals(262144, NordDevice.roundestInRange(261_000, 264_000))
        // Nothing round in range: the lower edge stands.
        assertEquals(1001, NordDevice.roundestInRange(1001, 1001))
    }

    // ---- Item identity: the defect the first live run exposed ----

    private fun itemRecord(bank: Int, item: Int, size: Int, tag: String = "nsmp", name: String = "x"): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(36 + nameBytes.size)
        fun put(offset: Int, value: Int) {
            out[offset] = (value ushr 24).toByte()
            out[offset + 1] = (value ushr 16).toByte()
            out[offset + 2] = (value ushr 8).toByte()
            out[offset + 3] = value.toByte()
        }
        put(4, bank)
        put(8, item)
        put(12, size)
        tag.toByteArray(Charsets.US_ASCII).copyInto(out, 16)
        put(32, nameBytes.size)
        nameBytes.copyInto(out, 36)
        return out
    }

    @Test
    fun `item identity ignores the address and follows the content`() {
        // On a Nord Grand, two Samp Lib views list the
        // same 235 samples at *different* (bank, item), so an address-keyed merge saw 258 items,
        // inflated the byte total, and solved Samp Lib to 68,608 bytes - outside the
        // 65,036.8 <= u < 67,746.7 bracket a delete had already pinned.
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)

        val here = itemRecord(0, 3, 952_640, name = "Vibes_DV mono 3.1")
        val there = itemRecord(0, 235, 952_640, name = "Vibes_DV mono 3.1")
        assertEquals(device.itemIdentity(here), device.itemIdentity(there))

        val other = itemRecord(0, 3, 563_136, name = "Victorini Accordion 2_PS 3.0")
        assertTrue(device.itemIdentity(here) != device.itemIdentity(other))

        // ...and a record carrying a content id keys on that instead.
        val real = parseMessage(NordFixtures.NSMP_RECORD_RESPONSE).payload
        assertNotNull(NordDevice.parseItemContentId(real))
        assertEquals(NordDevice.parseItemContentId(real), device.itemIdentity(real))
    }

    // ---- The category child list (sub-opcode 2/3) ----

    /**
     * Sample sub-opcode 2/3 replies representative of a Nord Grand's category child lists.
     */
    private object GrandChildLists {
        const val PROGRAM = "0000000000000006100000000642616e6b2041000000190000000642616e6b2042000000190000000642616e6b2043000000190000000642616e6b2044000000190000000642616e6b2045000000190000000642616e6b2046000000190000000642616e6b2047000000190000000642616e6b2048000000190000000642616e6b2049000000190000000642616e6b204a000000190000000642616e6b204b000000190000000642616e6b204c000000190000000642616e6b204d000000190000000642616e6b204e000000190000000642616e6b204f000000190000000642616e6b205000000019"
        const val PIANO = "000000000000000106000000054772616e640000001400000007557072696768740000001400000008456c6563747269630000001400000004436c617600000014000000074469676974616c00000014000000044d69736300000014"
        const val LIVE = "0000000000000007010000000642616e6b203100000005"
        const val SETTINGS = "0000000000000008010000000642616e6b203100000001"
    }

    @Test
    fun `the Program child list gives the Grand's real bank layout`() {
        val children = NordDevice.parseCategoryChildren(NordFixtures.hex(GrandChildLists.PROGRAM))

        assertEquals(16, children.size)
        assertEquals("Bank A", children.first().name)
        assertEquals("Bank P", children.last().name)
        assertTrue(children.all { it.capacity == 25 })
        // 16 banks x 25 = the Grand's 400 program slots, and 'P' is exactly the maxBankLetter
        // its catalog entry declares.
        assertEquals(400, children.sumOf { it.capacity })
    }

    @Test
    fun `a category's children are not always banks`() {
        // Piano lists named piano *types*, not banks - which is why the decode reads a
        // length-prefixed name rather than assuming "Bank X".
        val children = NordDevice.parseCategoryChildren(NordFixtures.hex(GrandChildLists.PIANO))

        assertEquals(
            listOf("Grand", "Upright", "Electric", "Clav", "Digital", "Misc"),
            children.map { it.name },
        )
        assertTrue(children.all { it.capacity == 20 })
    }

    @Test
    fun `the trailing per-child word tracks the category's own item count`() {
        // The evidence for reading it as a slot capacity: on this instrument Live holds 5 items
        // and Settings 1, and those are exactly the figures. (The Stage 2 EX's figures don't
        // line up under that reading; on a Grand they do.)
        assertEquals(5, NordDevice.parseCategoryChildren(NordFixtures.hex(GrandChildLists.LIVE)).single().capacity)
        assertEquals(1, NordDevice.parseCategoryChildren(NordFixtures.hex(GrandChildLists.SETTINGS)).single().capacity)
    }

    @Test
    fun `a child list that doesn't walk cleanly throws rather than guessing`() {
        // A wrong parse here would hand deriveBankLayout a wrong addressing range, so the
        // leftover-bytes check is load-bearing rather than defensive.
        val truncated = NordFixtures.hex(GrandChildLists.LIVE).copyOfRange(0, 20)
        assertThrows(IllegalArgumentException::class.java) {
            NordDevice.parseCategoryChildren(truncated)
        }
        val trailingJunk = NordFixtures.hex(GrandChildLists.LIVE) + byteArrayOf(0, 0)
        assertThrows(IllegalArgumentException::class.java) {
            NordDevice.parseCategoryChildren(trailingJunk)
        }
    }

    @Test
    fun `deriving a bank layout leaves the grouping alone`() = runTest {
        // Sixteen banks of 25 is the Grand's layout, but nothing on the wire says those 25 are
        // 5 groups of 5 - so the derived profile says one group of 25 rather than inventing it.
        val rootList = rootListPayload(listOf("Program"))
        // An unknown device as it stands *after* connect(): guessed bank bounds, but a protocol version
        // already read off the instrument. Without one, every request below would fail before it
        // was sent.
        val device = NordFixtures.device(
            ReplayTransport(listOf(1 to rootList, 3 to NordFixtures.hex(GrandChildLists.PROGRAM))),
            DeviceProfile.unknown("Nord Something", 0x0FFC, 0x00FF),
        ).apply { detectedProtocolVersionFileTransfer = NordFixtures.GRAND_PROTOCOL_VERSION }

        val layout = device.applyDerivedBankLayout()!!

        assertEquals(16, layout.bankCount)
        assertEquals(25, layout.slotsPerBank)
        assertEquals('P', device.profile.maxBankLetter)
        assertEquals(1, device.profile.maxGroup)
        assertEquals(25, device.profile.slotsPerGroup)
        // ...and the whole bank stays addressable under that convention.
        assertEquals("A:1:1", device.formatPresetId(0, 0))
        assertEquals("A:1:25", device.formatPresetId(0, 24))
        assertEquals("P:1:25", device.formatPresetId(15, 24))
    }

    @Test
    fun `a catalog device keeps its configured bank bounds`() = runTest {
        // The rule the whole derivation is scoped by: nord_devices.json is the configuration,
        // and a known instrument's bank/group layout comes from there, not from derivation.
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)

        assertEquals('P', device.profile.maxBankLetter)
        assertEquals(5, device.profile.maxGroup)
        assertEquals(5, device.profile.slotsPerGroup)
        assertEquals("A:5:5", device.formatPresetId(0, 24))
    }

    // ---- The area walk, end to end over a replayed instrument ----

    /**
     * A sub-opcode 0/1 payload listing [names]. [units], if given, is one allocation unit per
     * name to write into the first word of its trailer.
     * Left out, every trailer is zeros, i.e. "states no unit".
     */
    private fun rootListPayload(
        names: List<String>,
        trailerLen: Int = 29,
        units: List<Int>? = null,
    ): ByteArray {
        val body = names.flatMapIndexed { i, name ->
            val bytes = name.toByteArray(Charsets.US_ASCII)
            val unit = units?.getOrNull(i)
            val trailer = if (unit == null) {
                List(trailerLen) { 0.toByte() }
            } else {
                listOf(unit ushr 24, unit ushr 16, unit ushr 8, unit).map { it.toByte() } +
                    List(trailerLen - 4) { 0.toByte() }
            }
            listOf(0, 0, 0, bytes.size).map { it.toByte() } + bytes.toList() + trailer
        }
        return (List(4) { 0.toByte() } + names.size.toByte() + body).toByteArray()
    }

    private fun countPayload(itemCount: Int, free: Int, used: Int, reclaimable: Int, unitCode: Int): ByteArray {
        val out = ByteArray(24)
        listOf(0, itemCount, free, used, reclaimable, unitCode).forEachIndexed { i, value ->
            out[i * 4] = (value ushr 24).toByte()
            out[i * 4 + 1] = (value ushr 16).toByte()
            out[i * 4 + 2] = (value ushr 8).toByte()
            out[i * 4 + 3] = value.toByte()
        }
        return out
    }

    /**
     * A sub-opcode 3 reply describing a single bank - what categoryBankCount() reads
     * to bound a walk. Every category these fixtures cover reports one child, as the
     * real instruments do for everything except `Program` and `Piano`.
     */
    private fun singleBankChildList(): ByteArray {
        val name = "Bank 1".toByteArray(Charsets.US_ASCII)
        val zero = List(4) { 0.toByte() }
        return (
            zero +                                            // status
                zero +                                        // category index
                1.toByte() +                                  // one child
                listOf(0, 0, 0, name.size).map { it.toByte() } +
                name.toList() +
                listOf(0, 0, 0, 100).map { it.toByte() }      // slot capacity
            ).toByteArray()
    }

    private fun walkResponses(records: List<ByteArray>, count: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>(
            3 to singleBankChildList(), // bounds the walk
            5 to ByteArray(0),
            9 to count,
        )
        for (record in records) {
            val bank = record[7].toInt()
            val item = record[11].toInt()
            val cursor = ByteArray(12)
            cursor[7] = bank.toByte()
            cursor[11] = item.toByte()
            out += 33 to cursor
            out += 31 to record
        }
        out += 7 to ByteArray(4)
        return out
    }

    // ---- Resilience: one probe failing must not cost the rest ----

    @Test
    fun `a category whose storage query fails does not abandon the others`() = runTest {
        // The case a report exists for: an instrument that answers sub-opcode 8/9 differently,
        // or not at all. One category refusing is no reason to lose the eight that answered.
        val sizes = listOf(200_000, 1_600_000, 5_000_000)
        val unit = 64 * 1024
        val used = NordDevice.storageUnitsAt(sizes, unit).toInt()
        val records = sizes.mapIndexed { i, size -> itemRecord(0, i, size, name = "sample$i") }
        val count = countPayload(3, 100, used, 0, 4)

        val responses = mutableListOf<Pair<Int, ByteArray>>(
            1 to rootListPayload(listOf("Broken", "Wave Pool")),
        )
        // "Broken" answers the item-count query with a non-zero status...
        responses += listOf(5 to ByteArray(0), 9 to countPayload(0, 0, 0, 0, 0).also { it[3] = 7 },
                            7 to ByteArray(4))
        responses += listOf(5 to ByteArray(0), 9 to count, 7 to ByteArray(4))
        responses += walkResponses(records, count)

        val failed = mutableListOf<String>()
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val areas = device.calibrateStorageUnits(onFailure = { name, _ -> failed += name })

        assertEquals(listOf("Broken"), failed)
        assertEquals(1, areas.size)
        assertEquals(listOf("Wave Pool"), areas.single().names)
        assertEquals(unit, areas.single().unitBytes)
    }

    /**
     * The other way a walk can fail: not the storage query, but the item walk after it - a cable
     * pulled halfway through "Measuring 'Program'...". The area still lands in the report with its
     * figures zeroed and a note saying why; what this checks is that the failure *also* reaches
     * the callback, and through it the report's top-level failure map. A note alone let such a
     * report read as complete to anyone who did not open every storage area.
     */
    @Test
    fun `a category whose item walk fails is recorded as a failure, not only as a note`() = runTest {
        val count = countPayload(3, 100, 5, 0, 4)
        val responses = mutableListOf<Pair<Int, ByteArray>>(
            1 to rootListPayload(listOf("Wave Pool")),
            5 to ByteArray(0), 9 to count, 7 to ByteArray(4),
            // The walk: child list, select, count, first cursor step - and then the instrument
            // goes silent, which the replay transport reports by running out of replies.
            3 to singleBankChildList(),
            5 to ByteArray(0),
            9 to count,
        )

        val failed = mutableListOf<String>()
        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val areas = device.calibrateStorageUnits(onFailure = { name, _ -> failed += name })

        assertEquals(listOf("Wave Pool"), failed)
        val area = areas.single()
        assertEquals(0, area.recordCount)
        assertTrue(area.reason, area.reason.startsWith("couldn't walk 'Wave Pool'"))
    }

    @Test
    fun `copies in a shared area are counted once each, not merged`() = runTest {
        // Two categories sharing an area are the same item set only if they list the *whole*
        // same set. Merging item by item instead loses real storage, and a Nord Grand shows it:
        // every Live slot holds a copy of the program last loaded into it, so all five are
        // byte-identical to five programs. A per-item merge dropped them - 201 items counted
        // where the instrument holds 206, and 16 units of overhead reported where the true
        // figure is 11.
        val unit = 1024
        val shared = listOf(1000, 2000, 3000)   // what Program holds
        val copies = listOf(1000, 2000)         // Live, byte-identical to two of them
        val used = NordDevice.storageUnitsAt(shared + copies, unit).toInt()
        assertEquals("1+2+3 for the programs, 1+2 for the copies", 9, used)

        val programCount = countPayload(shared.size, 100, used, 0, 2)
        val liveCount = countPayload(copies.size, 100, used, 0, 2)
        val programRecords = shared.mapIndexed { i, size -> itemRecord(0, i, size, "ngp", "p$i") }
        // Same tag, name and size - so the same identity, which is the point.
        val liveRecords = copies.mapIndexed { i, size -> itemRecord(1, i, size, "ngp", "p$i") }

        val responses = mutableListOf<Pair<Int, ByteArray>>(
            1 to rootListPayload(listOf("Program", "Live"), units = listOf(unit, unit)),
        )
        for (count in listOf(programCount, liveCount)) {
            responses += listOf(5 to ByteArray(0), 9 to count, 7 to ByteArray(4))
        }
        responses += walkResponses(programRecords, programCount)
        responses += walkResponses(liveRecords, liveCount)

        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val area = device.calibrateStorageUnits().single()

        assertEquals("all five items occupy storage, copies included", 5, area.recordCount)
        assertEquals(unit, area.unitBytes)
        assertEquals("counting the copies is what makes used add up", 0, area.residual)
    }

    @Test
    fun `deriveBankLayout reports why it failed instead of just answering null`() = runTest {
        // From the power-cut report: a null bank layout with nothing to say why, where every
        // other probe explained itself. Swallowing the cause here robbed both callers of it -
        // the device report wants it for its failure map, the connect path for its log - so this
        // throws, and null now means only "the instrument answered with no children".
        val device = NordFixtures.device(ReplayTransport(emptyList()), NordFixtures.GRAND_PROFILE)

        val thrown = try {
            device.deriveBankLayout()
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("expected the transport failure to reach the caller", thrown)

        // ...and a Program category that really does report no children is the null case.
        val empty = NordFixtures.device(
            ReplayTransport(
                listOf(1 to rootListPayload(listOf("Program")), 3 to ByteArray(9)),
            ),
            NordFixtures.GRAND_PROFILE,
        )
        assertNull(empty.deriveBankLayout())
    }

    @Test
    fun `a short item-count reply is refused by length, not by running off the end`() = runTest {
        // readUInt32BE(payload, 20) would otherwise throw ArrayIndexOutOfBounds here, which is a
        // worse error to receive in a report than one that names the problem.
        val device = NordFixtures.device(
            ReplayTransport(listOf(5 to ByteArray(0), 9 to ByteArray(12), 7 to ByteArray(4))),
            NordFixtures.GRAND_PROFILE,
        )

        val e = try {
            device.fetchCategorySpace(0)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull("expected a length complaint, not a crash", e)
        assertTrue(e!!.message!!, e.message!!.contains("too short"))
    }

    @Test
    fun `two views of one area are merged, not summed`() = runTest {
        val sampleSizes = listOf(200_000, 1_600_000, 5_000_000)
        val unit = 64 * 1024
        val used = NordDevice.storageUnitsAt(sampleSizes, unit).toInt()
        // The "(Native)" view lists the same three items at different addresses, as a real Nord
        // Grand does. Same storage triple (which is what groups them), own item count.
        val plain = sampleSizes.mapIndexed { i, size -> itemRecord(0, i, size, name = "sample$i") }
        val native = sampleSizes.mapIndexed { i, size -> itemRecord(0, 10 + i, size, name = "sample$i") }
        val count = countPayload(3, 100, used, 0, 4)
        val programCount = countPayload(2, 74520, 141480, 0, 0)

        val responses = mutableListOf<Pair<Int, ByteArray>>(
            1 to rootListPayload(listOf("Wave Pool (Native)", "Wave Pool", "Program")),
        )
        listOf(count, count, programCount).forEach {
            responses += listOf(5 to ByteArray(0), 9 to it, 7 to ByteArray(4))
        }
        responses += walkResponses(native, count)
        responses += walkResponses(plain, count)

        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val areas = device.calibrateStorageUnits()

        assertEquals(2, areas.size)
        val sampleArea = areas[0]
        assertEquals(listOf("Wave Pool (Native)", "Wave Pool"), sampleArea.names)
        // Three items across two listings of three, not six.
        assertEquals(3, sampleArea.recordCount)
        assertEquals(unit, sampleArea.unitBytes)
        assertTrue(sampleArea.solved)

        // A byte-counted area has no block size to find, and says so without walking anything.
        val programArea = areas[1]
        assertTrue(!programArea.solved)
        assertTrue(programArea.reason, programArea.reason.contains("counted in bytes"))
    }

    /**
     * The overhead an area leaves unexplained has to be available at the unit the *instrument*
     * reports, not only at the fitted one: the fit names the roundest candidate in an admissible
     * range, which is usually not the reported figure, so quoting only that overstates what is
     * unaccounted for.
     *
     * These numbers are a real Nord Grand's piano area in miniature, and they overstate it the
     * same way - reported 130,816 explains `used` exactly while the roundest candidate 131,072
     * leaves 4. Both are inside the range, so the two still agree; that is what makes the
     * distinction easy to miss.
     */
    @Test
    fun `the residual is also measured at the reported unit`() = runTest {
        val reported = 130_816
        val sizes = listOf(reported * 1960, reported * 30 + 5, reported * 7)
        val used = NordDevice.storageUnitsAt(sizes, reported).toInt()
        val names = listOf("Piano (Native)", "Piano")
        val count = countPayload(3, 100, used, 0, 2)
        val records = sizes.mapIndexed { i, size -> itemRecord(0, i, size, name = "piano$i") }

        val responses = mutableListOf<Pair<Int, ByteArray>>(
            1 to rootListPayload(names, units = listOf(reported, reported)),
        )
        names.forEach { _ -> responses += listOf(5 to ByteArray(0), 9 to count, 7 to ByteArray(4)) }
        responses += walkResponses(records, count)
        responses += walkResponses(records, count)

        val device = NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE)
        val area = device.calibrateStorageUnits().single()

        assertEquals(131_072, area.unitBytes)      // the fit rounds elsewhere...
        assertEquals(4, area.residual)             // ...and leaves 4 unexplained
        assertEquals(0, area.reportedResidual)     // where the reported unit leaves none
    }
}
