// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Subsequent37InstrumentTest {

    private fun commands(fake: FakeSubsequent37): List<String> = fake.requests.map { request ->
        when {
            (request[0].toInt() and 0xFF) != 0xF0 -> "select"
            request[1].toInt() == 0x7E -> "identity"
            else -> request.copyOfRange(3, 5).joinToString(" ") { "%02x".format(it) }
        }
    }

    private fun slotsOf(updates: List<IndexUpdate>): List<PresetSlot> =
        updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }

    /** What a whole listing of the fixture library must look like. */
    private fun assertWholeLibrary(slots: List<PresetSlot>) {
        assertEquals((0 until 256).map { Subsequent37AddressFormat.addressOf(it) }, slots.map { it.address })
        assertEquals(Subsequent37Fixtures.EMPTY_COUNT, slots.count { it.isEmpty })
        assertEquals("B01/P01", slots[0].displayId)
        assertEquals("1", slots[0].bankLabel)
        assertEquals("Preset 000", slots[0].name)
        assertEquals(listOf("Evolving"), slots[0].badges)
        assertEquals(listOf("Sync"), slots[1].badges)
        assertEquals("B16/P16", slots[255].displayId)
    }

    @Test
    fun `connect reads the firmware and the settings, and selects nothing`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        assertEquals("Moog Subsequent 37", instrument.identity.name)
        assertEquals("1.2.0", instrument.identity.firmwareVersion)
        assertEquals("subsequent37:moog_subsequent37", instrument.identity.stableKey)
        assertEquals(0, instrument.settings!!.inChannel)
        assertNull(instrument.advisory)
        assertEquals(listOf("identity", "06 04", "06 00"), commands(fake))
        assertEquals(16, instrument.layout.bankCount)
        assertEquals(256, instrument.layout.slotCount)
        assertEquals("16", instrument.layout.banks[15].shortLabel)
        assertEquals(2, instrument.notices.size)
    }

    @Test
    fun `an untested firmware raises the advisory, and an unreadable one does not`() = runTest {
        val untested = subsequent37(FakeSubsequent37(backgroundScope), Subsequent37Config(setOf("1.1.0"))).apply {
            connect()
        }
        assertTrue(untested.advisory!!.contains("firmware version 1.2.0"))

        val silent = subsequent37(FakeSubsequent37(backgroundScope).apply { identityReply = null }).apply { connect() }
        assertEquals(UNKNOWN_FIRMWARE, silent.identity.firmwareVersion)
        assertNull(silent.advisory)
    }

    /** An instrument that answers neither question is not offered as a session, since nothing else would answer either. */
    @Test
    fun `an instrument that answers neither the identity nor the settings request is refused`() = runTest {
        val silent = subsequent37(
            FakeSubsequent37(backgroundScope).apply {
                identityReply = null
                settingsReply = null
            },
        )
        try {
            silent.connect()
            fail("a silent instrument was connected")
        } catch (expected: InstrumentException.Timeout) {
            assertTrue(expected.message!!.contains("answered neither"))
        }
    }

    /** The point of following the dump: rows appear while it is still arriving, not after 142 seconds. */
    @Test
    fun `the listing shows programs while the dump still arrives`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val timed = mutableListOf<Pair<Long, IndexUpdate>>()
        instrument.index().collect { timed += testScheduler.currentTime to it }
        val updates = timed.map { it.second }

        assertWholeLibrary(slotsOf(updates))
        assertEquals(IndexUpdate.Complete, updates.last())
        val firstRow = timed.first { it.second is IndexUpdate.Slots }.first
        assertTrue("first row at $firstRow ms", firstRow < 10_000)
        assertTrue(fake.dumpEndedAt!! > 140_000)

        val progress = updates.filterIsInstance<IndexUpdate.Progress>()
        assertTrue(progress.zipWithNext().all { (a, b) -> b.done >= a.done })
        assertTrue(progress.any { it.label.startsWith("Reading presets, about 2 minutes left") })
        // No program had to be read on its own.
        assertFalse(commands(fake).contains("06 01"))
    }

    /** Where the platform hands the reply over only once whole: the same rows, progress from the clock. */
    @Test
    fun `a dump delivered whole lists the same`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { dumpWhole = true }
        val instrument = subsequent37(fake).apply { connect() }
        val updates = instrument.index().toList()
        assertWholeLibrary(slotsOf(updates))
        val progress = updates.filterIsInstance<IndexUpdate.Progress>()
        assertTrue(progress.size > 100)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.done >= a.done })
        assertFalse(commands(fake).contains("06 01"))
    }

    @Test
    fun `a damaged dump keeps what came before the damage and reads the rest one by one`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            damageDump =
                { dump ->
                    dump.copyOf().also { it[23 + 5 * 796 + 100] = (it[23 + 5 * 796 + 100].toInt() xor 1).toByte() }
                }
        }
        val instrument = subsequent37(fake).apply { connect() }
        val updates = instrument.index().toList()
        assertWholeLibrary(slotsOf(updates))
        val reads = fake.requests.filter { Subsequent37SysEx.isCommand(it, 0x06, 0x01) }
        assertEquals((5 until 256).toList(), reads.map { Subsequent37SysEx.addressOf(it) })
        assertTrue(updates.none { it is IndexUpdate.Failed })
    }

    /** A dump that stops arriving part way is given up on once nothing has come for a while, and finished by single reads. */
    @Test
    fun `a dump that stops arriving is completed by single reads`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { stallAfterBytes = 23 + 10 * 796 + 300 }
        val instrument = subsequent37(fake).apply { connect() }
        assertWholeLibrary(slotsOf(instrument.index().toList()))
        val reads = fake.requests.filter { Subsequent37SysEx.isCommand(it, 0x06, 0x01) }
        assertEquals((10 until 256).toList(), reads.map { Subsequent37SysEx.addressOf(it) })
    }

    /**
     * Single reads that go unanswered are given up on after three slots in a row: the rest are
     * reported failed without being asked for, rather than costing a timeout each.
     */
    @Test
    fun `after three unanswered slots the rest are reported failed unread`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            damageDump =
                { dump ->
                    dump.copyOf().also { it[23 + 5 * 796 + 100] = (it[23 + 5 * 796 + 100].toInt() xor 1).toByte() }
                }
            unansweredReads = 1_000
        }
        val instrument = subsequent37(fake).apply { connect() }
        val updates = instrument.index().toList()
        val asked = fake.requests.filter { Subsequent37SysEx.isCommand(it, 0x06, 0x01) }
            .map { Subsequent37SysEx.addressOf(it) }.distinct()
        assertEquals(listOf(5, 6, 7), asked)
        val failed = updates.filterIsInstance<IndexUpdate.Failed>()
        assertEquals(251, failed.size)
        assertTrue(failed.last().reason.contains("not read"))
    }

    @Test
    fun `a dump with bytes missing is completed by single reads`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            damageDump = { it.copyOfRange(0, 5_399) + it.copyOfRange(5_399 + 173, it.size) }
        }
        val instrument = subsequent37(fake).apply { connect() }
        assertWholeLibrary(slotsOf(instrument.index().toList()))
        assertEquals(250, fake.requests.count { Subsequent37SysEx.isCommand(it, 0x06, 0x01) })
    }

    /**
     * A channel message inside the dump aborts it at the framer, while the instrument goes on
     * sending the rest. Reads sent before it stops would be lost behind it.
     */
    @Test
    fun `an interrupted dump is completed only once the instrument has stopped sending`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            injectIntoDumpAt = 50_000 to "b0 4a 40".hexToBytes()
        }
        val instrument = subsequent37(fake).apply { connect() }
        val updates = instrument.index().toList()
        assertWholeLibrary(slotsOf(updates))

        val firstRead = fake.requests.indexOfFirst { Subsequent37SysEx.isCommand(it, 0x06, 0x01) }
        assertTrue(fake.requestTimes[firstRead] >= fake.dumpEndedAt!!)
        // The programs whose blocks had arrived before the abort came from the dump; the rest were read.
        val reads = fake.requests.filter {
            Subsequent37SysEx.isCommand(it, 0x06, 0x01)
        }.map { Subsequent37SysEx.addressOf(it)!! }
        assertTrue("reading from ${reads.first()}", reads.first() in 55..62)
        assertEquals((reads.first() until 256).toList(), reads)
    }

    @Test
    fun `a dump that never comes fails the listing rather than reading 256 slots`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { dumpSilent = true }
        val instrument = subsequent37(fake).apply { connect() }
        try {
            instrument.index().toList()
            fail("a silent instrument listed")
        } catch (expected: InstrumentException.Timeout) {
        }
        assertFalse(commands(fake).contains("06 01"))
        // The listing is over, so other operations are no longer refused.
        instrument.refresh(SlotAddress(0, 1))
    }

    /** Waiting behind a 142-second dump would look like a hang; a refusal says why. */
    @Test
    fun `everything else is refused while the listing runs, and works afterwards`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val listing = launch { instrument.index().collect {} }
        advanceTimeBy(30.seconds)
        runCurrent()

        val refusals = listOf<suspend () -> Unit>(
            { instrument.select(SlotAddress(0, 1)) },
            { instrument.refresh(SlotAddress(0, 1)) },
            { instrument.editor.rename(SlotAddress(0, 1), "New") },
            // Program 255 has not arrived yet, so its category would have to be read.
            { instrument.tagger.read(SlotAddress(15, 15)) },
            { instrument.read(SlotAddress(0, 1)) },
            { instrument.report.buildReport() },
        )
        for (attempt in refusals) {
            try {
                attempt()
                fail("an operation ran during the listing")
            } catch (expected: InstrumentException.BlockedByDeviceState) {
                assertTrue(expected.message!!.contains("still sending its preset list (about"))
            }
        }
        // One the dump has delivered is answered from it, with nothing sent.
        val sent = fake.requests.size
        instrument.tagger.read(SlotAddress(0, 1))
        assertEquals(sent, fake.requests.size)

        listing.join()
        instrument.select(SlotAddress(0, 1))
        assertEquals(1, fake.selected)
    }

    @Test
    fun `a cancelled listing leaves the instrument usable`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val listing = launch { instrument.index().collect {} }
        advanceTimeBy(20.seconds)
        listing.cancel()
        listing.join()
        instrument.select(SlotAddress(2, 3))
        assertEquals(35, fake.selected)
    }

    // ---- Selection ----

    @Test
    fun `a selection goes out on the IN channel the settings name`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            settingsReply = Subsequent37Fixtures.SETTINGS_IN03_OUT14
            programChangeChannel = 2
        }
        val instrument = subsequent37(fake).apply { connect() }
        instrument.select(SlotAddress(15, 12))
        assertArrayEquals("b2 00 00 b2 20 01 c2 7c".hexToBytes(), fake.requests.last())
        assertEquals(252, fake.selected)
    }

    @Test
    fun `an IN channel of ALL selects on channel 1`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply {
            settingsReply = Subsequent37ProgramTest.settingsMessageWithInChannel(Subsequent37Settings.IN_CHANNEL_ALL)
            programChangeChannel = null
        }
        val instrument = subsequent37(fake).apply { connect() }
        instrument.select(SlotAddress(0, 6))
        assertArrayEquals("b0 00 00 b0 20 00 c0 06".hexToBytes(), fake.requests.last())
    }

    /** The IN channel changed on the panel since connecting: the settings are read again. */
    @Test
    fun `an unacknowledged selection is retried on a changed IN channel`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        fake.settingsReply = Subsequent37Fixtures.SETTINGS_IN03_OUT14
        fake.programChangeChannel = 2
        instrument.select(SlotAddress(0, 4))
        assertEquals(4, fake.selected)
        assertEquals(2, instrument.settings!!.inChannel)
    }

    @Test
    fun `an instrument that ignores Program Changes is reported with the panel steps`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { receivesProgramChanges = false }
        val instrument = subsequent37(fake).apply { connect() }
        try {
            instrument.select(SlotAddress(0, 4))
            fail("an unacknowledged selection succeeded")
        } catch (expected: InstrumentException.NeedsManualSetting) {
            assertTrue(expected.steps.any { it.contains("RCV PGM CHG (MIDI MENU 1.4)") })
        }
    }

    // ---- Refresh and transfer ----

    @Test
    fun `a refresh reads the stored program and selects nothing`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val slot = instrument.refresh(SlotAddress(0, 1))
        assertEquals("Preset 001", slot.name)
        assertNull(fake.selected)
        assertEquals(listOf("06 04", "06 00", "06 01"), commands(fake).drop(3))
    }

    @Test
    fun `a read that goes unanswered is asked for once more`() = runTest {
        val fake = FakeSubsequent37(backgroundScope).apply { unansweredReads = 1 }
        val instrument = subsequent37(fake).apply { connect() }
        assertEquals("Preset 001", instrument.refresh(SlotAddress(0, 1)).name)
        assertEquals(2, fake.requests.count { Subsequent37SysEx.isCommand(it, 0x06, 0x01) })
    }

    /** The fake builds its replies from the captured headers; this pins that it matches a real one. */
    @Test
    fun `a transfer read is the stored program's message as the instrument sends it`() = runTest {
        val instrument = subsequent37(FakeSubsequent37(backgroundScope)).apply { connect() }
        assertArrayEquals(Subsequent37Fixtures.READ_P001[1], instrument.read(SlotAddress(0, 1)))
        assertArrayEquals(Subsequent37Fixtures.READ_P255[1], instrument.read(SlotAddress(15, 15)))
    }

    @Test
    fun `a transfer write takes a program message or a bare payload, and nothing broken`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        val target = Subsequent37Fixtures.emptyPrograms(2)

        instrument.write(Subsequent37AddressFormat.addressOf(target[0]), Subsequent37Fixtures.WRITE_RENAME)
        assertEquals("Preset 000  Test", fake.programAt(target[0]).name)
        // Only the program travels: the file's own header, addressed to program 0, is never sent.
        assertEquals(target[0], Subsequent37SysEx.addressOf(fake.writes.last()))

        instrument.write(Subsequent37AddressFormat.addressOf(target[1]), Subsequent37Fixtures.unpackBlock(1))
        assertEquals("Preset 001", fake.programAt(target[1]).name)

        val broken = Subsequent37Fixtures.unpackBlock(1).also { it[200] = (it[200] + 1).toByte() }
        try {
            instrument.write(SlotAddress(0, 0), broken)
            fail("a payload with a broken checksum was written")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals(2, fake.writes.size)
    }
}
