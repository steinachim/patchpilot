// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AxeFx3InstrumentTest {

    /** Refused as the catalog is decoded, before a factory has started an exchange it would leak. */
    @Test
    fun `a catalog with no presets per bank is refused as it is decoded`() {
        thrownBy<IllegalArgumentException> {
            Json.decodeFromString(AxeFx3Config.serializer(), """{"presetsPerBank": 0}""")
        }
    }

    private val markIIReply = FakeAxeFx3.message(
        AxeFx3SysEx.CMD_CAPABILITIES, 0x50, 0x02, 0x00, 0x10, 0x00, 0x10, 0x10, 0x00, 0x00, 0x08,
    )

    @Test
    fun `connect reads the firmware and the model, and asks nothing else`() = runTest {
        val fake = FakeAxeFx3()
        val instrument = axeFx3(fake).apply { connect() }
        assertEquals("Fractal Audio Axe-Fx III Mark I", instrument.identity.name)
        assertEquals("32.06", instrument.identity.firmwareVersion)
        assertEquals(512, instrument.presetCount)
        assertEquals(listOf("000–127", "128–255", "256–383", "384–511"), instrument.layout.banks.map { it.label })
        assertEquals(listOf("000", "128", "256", "384"), instrument.layout.banks.map { it.shortLabel })
        assertTrue(instrument.layout.banks.all { it.virtual })
        assertNull(instrument.advisory)
        assertEquals(listOf("08", "47", "0d"), fake.commands())
    }

    @Test
    fun `a Mark II gets 1024 presets in eight virtual banks, behind an advisory`() = runTest {
        val fake = FakeAxeFx3(presetCount = 1024).apply { capabilitiesReply = markIIReply }
        val instrument = axeFx3(fake).apply { connect() }
        assertEquals("Fractal Audio Axe-Fx III Mark II", instrument.identity.name)
        assertEquals(1024, instrument.layout.slotCount)
        assertEquals(8, instrument.layout.bankCount)
        assertEquals("1023", instrument.layout.format.format(SlotAddress(7, 127)))
        assertTrue(instrument.advisory!!.contains("1024 presets"))
    }

    /** A unit that does not say it is a Mark II is a Mark I, never offered slots it may not have. */
    @Test
    fun `no model record, or an acknowledgement in its place, means a Mark I`() = runTest {
        for (reply in listOf(null, FakeAxeFx3.stepAck(AxeFx3SysEx.CMD_CAPABILITIES, 0))) {
            val fake = FakeAxeFx3().apply { capabilitiesReply = reply }
            val instrument = axeFx3(fake).apply { connect() }
            assertEquals(512, instrument.presetCount)
            assertEquals("Fractal Audio Axe-Fx III Mark I", instrument.identity.name)
        }
    }

    @Test
    fun `an untested firmware raises the advisory, and an unreadable one does not`() = runTest {
        val untested = axeFx3(FakeAxeFx3(), AxeFx3Config(supportedFirmwareVersions = setOf("31.00"))).apply {
            connect()
        }
        assertTrue(untested.advisory!!.contains("firmware version 32.06"))

        val silent = axeFx3(FakeAxeFx3().apply { firmwareReply = null }).apply { connect() }
        assertEquals(UNKNOWN_FIRMWARE, silent.identity.firmwareVersion)
        assertNull(silent.advisory)
    }

    /** A port that answers neither question is not offered as a session, since nothing else would answer either. */
    @Test
    fun `an instrument that answers neither the firmware nor the capabilities query is refused`() = runTest {
        val silent = axeFx3(
            FakeAxeFx3().apply {
                firmwareReply = null
                capabilitiesReply = null
            },
        )
        try {
            silent.connect()
            fail("a silent instrument was connected")
        } catch (expected: InstrumentException.Timeout) {
            assertTrue(expected.message!!.contains("answered neither"))
        }
    }

    @Test
    fun `the listing walks every preset in order, empty slots as null`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(0, "59 Bassguy")
        fake.put(130, "")
        val instrument = axeFx3(fake).apply { connect() }

        val updates = instrument.index().toList()
        val slots = updates.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }
        assertEquals(512, slots.size)
        assertTrue(updates.none { it is IndexUpdate.Failed })
        assertEquals(IndexUpdate.Complete, updates.last())
        assertEquals((0 until 512).map { it.toString().padStart(3, '0') }, slots.map { it.displayId })

        assertEquals(PresetSlot(SlotAddress(0, 0), "000", "000–127", "59 Bassguy"), slots[0])
        assertEquals(PresetSlot(SlotAddress(1, 2), "130", "128–255", UNNAMED_PRESET), slots[130])
        assertNull(slots[131].name)
        assertEquals(instrument.layout.banks.map { it.label }, slots.map { it.bankLabel }.distinct())
    }

    @Test
    fun `select loads the preset through the editor channel`() = runTest {
        val fake = FakeAxeFx3()
        val instrument = axeFx3(fake).apply { connect() }
        instrument.select(SlotAddress(2, 100))
        assertEquals(356, fake.selected)
        assertTrue(fake.requests.any { it.contentEquals(AxeFx3Fixtures.SELECT_356) })
    }

    @Test
    fun `a selection the loaded-preset query confirms says selected`() = runTest {
        val instrument = axeFx3(FakeAxeFx3()).apply { connect() }
        instrument.select(SlotAddress(2, 100))
        assertEquals("Selected 356.", instrument.confirmationFor("356"))
    }

    @Test
    fun `a selection nothing confirms says it asked`() = runTest {
        val instrument = axeFx3(FakeAxeFx3().apply { loadedQueryEchoesNumber = false }).apply { connect() }
        instrument.select(SlotAddress(2, 100))
        assertEquals("Asked the instrument to load 356.", instrument.confirmationFor("356"))
    }

    @Test
    fun `a late name reply does not fail a selection`() = runTest {
        val fake = FakeAxeFx3()
        val instrument = axeFx3(fake).apply { connect() }
        fake.strayLoadedAnswer = 5
        instrument.select(SlotAddress(2, 100))
        assertEquals("Selected 356.", instrument.confirmationFor("356"))
    }

    @Test
    fun `a number the unit does not have is refused before anything is sent`() = runTest {
        val fake = FakeAxeFx3()
        val instrument = axeFx3(fake).apply { connect() }
        val before = fake.requests.size
        try {
            instrument.select(SlotAddress(4, 0))
            fail("expected a refusal")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(before, fake.requests.size)
    }

    /** The reason for the instrument's own lock: a listing runs outside the ViewModel's mutex. */
    @Test
    fun `no name query lands between an edit's select and its store`() = runTest {
        val fake = FakeAxeFx3()
        fake.put(1, "Lead")
        val instrument = axeFx3(fake).apply { connect() }
        launch { instrument.index().toList() }
        launch { instrument.editor.copyProgram(SlotAddress(0, 1), SlotAddress(0, 2)) }
        launch { instrument.editor.rename(SlotAddress(0, 1), "Lead 2") }
        advanceUntilIdle()

        // From each store back to the select that began its write; a restore's select has no store.
        val commands = fake.commands()
        assertTrue(commands.count { it == "01/26" } == 2 && commands.count { it == "0d" } > 512)
        commands.forEachIndexed { i, command ->
            if (command == "01/26") {
                val select = commands.subList(0, i).lastIndexOf("01/27")
                assertFalse("a name query between a select and its store", "0d" in commands.subList(select, i))
            }
        }
        assertEquals("Lead", fake.nameAt(2))
        assertEquals("Lead 2", fake.nameAt(1))
    }

    @Test
    fun `transfer reads the raw preset`() = runTest {
        val fake = FakeAxeFx3()
        val image = fake.put(1, "Lead")
        val instrument = axeFx3(fake).apply { connect() }

        val read = instrument.transfer.read(SlotAddress(0, 1))
        assertArrayEquals(image, read)
        assertEquals(49_323, read.size)
    }

    @Test
    fun `facets and notices`() = runTest {
        val instrument = axeFx3(FakeAxeFx3()).apply { connect() }
        assertNull(instrument.tagger)
        assertNotNull(instrument.editor)
        assertNotNull(instrument.report)
        assertFalse(instrument.rebuildOnResume)
        val notice = instrument.notices.single()
        assertEquals(AxeFx3Instrument.EDITS_LOAD_PRESETS_NOTICE, notice.id)
        assertTrue(notice.message.contains("loads the preset that was loaded before"))

        val noEcho = axeFx3(FakeAxeFx3().apply { loadedQueryEchoesNumber = false }).apply { connect() }
        assertTrue(noEcho.notices.single().message.contains("leaves it loaded"))
    }

    @Test
    fun `close releases the port`() = runTest {
        val fake = FakeAxeFx3()
        axeFx3(fake).close()
        assertTrue(fake.transport.closed)
    }

    /** Assumption: CC 0 carries the bank and the Program Change the slot, matching the preset number. */
    @Test
    fun `the MIDI selection is the virtual bank in CC0 and the slot in the Program Change`() = runTest {
        val instrument = axeFx3(FakeAxeFx3()).apply { connect() }

        assertEquals(MidiSelection(0, null, 0), instrument.selector.midiSelection(SlotAddress(0, 0)))
        assertEquals(MidiSelection(2, null, 100), instrument.selector.midiSelection(SlotAddress(2, 100)))
        assertEquals(MidiSelection(3, null, 127), instrument.selector.midiSelection(SlotAddress(3, 127)))
        assertTrue(runCatching { instrument.selector.midiSelection(SlotAddress(4, 0)) }.isFailure)
    }
}
