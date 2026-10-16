// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * The Bank Select / Program Change values Info states for a Nord, against the **shipped**
 * catalog. The expected figures are worked out from each manual's own ranges; none has been
 * measured on an instrument.
 */
class NordMidiSelectionTest {

    private val catalog: NordCatalog by lazy {
        val dir = System.getProperty("deviceCatalogDir")
            ?: error("deviceCatalogDir is not set; see the Test task config in app/build.gradle.kts")
        catalogJson.decodeFromString(
            NordCatalog.serializer(),
            File(dir).resolve("nord_devices.json").readText(),
        )
    }

    private fun instrument(id: String) =
        NordInstrument(NordFixtures.device(ReplayTransport(emptyList()), catalog.devices.first { it.id == id }))

    private fun NordInstrument.midi(bank: Int, slot: Int) = selector.midiSelection(SlotAddress(bank, slot))

    /** One bank per LSB: banks A-D on LSB 0-3, Program Change 0-99. */
    @Test
    fun `a Stage 2 EX bank is its LSB and the item is the Program Change`() {
        val nord = instrument("nord_stage_2ex")
        assertEquals(MidiSelection(0, 0, 0), nord.midi(0, 0))
        assertEquals(MidiSelection(0, 1, 0), nord.midi(1, 0))
        assertEquals(MidiSelection(0, 3, 99), nord.midi(3, 99))
    }

    @Test
    fun `a Grand has sixteen LSB values of twenty-five programs`() {
        val nord = instrument("nord_grand")
        assertEquals(MidiSelection(0, 0, 0), nord.midi(0, 0))
        assertEquals(MidiSelection(0, 1, 0), nord.midi(1, 0))
        assertEquals(MidiSelection(0, 15, 24), nord.midi(15, 24))
    }

    /** "Banks A-E ... Bank LSB 0, banks F-J with Bank LSB 1": the Program Change counts through the group. */
    @Test
    fun `a Wave 2 puts five banks on one LSB`() {
        val nord = instrument("nord_wave_2")
        assertEquals(MidiSelection(0, 0, 124), nord.midi(4, 24))
        assertEquals(MidiSelection(0, 1, 0), nord.midi(5, 0))
        assertEquals(MidiSelection(0, 2, 124), nord.midi(14, 24))
        assertEquals(MidiSelection(0, 3, 24), nord.midi(15, 24))
    }

    @Test
    fun `models that group several banks per LSB count the Program Change through the group`() {
        val grand2 = instrument("nord_grand_2")
        assertEquals(MidiSelection(0, 0, 107), grand2.midi(2, 35))
        assertEquals(MidiSelection(0, 1, 0), grand2.midi(3, 0))
        assertEquals(MidiSelection(0, 4, 107), grand2.midi(14, 35))
        assertEquals(MidiSelection(0, 5, 35), grand2.midi(15, 35))

        val stage4 = instrument("nord_stage_4")
        assertEquals(MidiSelection(0, 0, 127), stage4.midi(1, 63))
        assertEquals(MidiSelection(0, 1, 0), stage4.midi(2, 0))
        assertEquals(MidiSelection(0, 3, 127), stage4.midi(7, 63))

        assertEquals(MidiSelection(0, 3, 124), instrument("nord_electro_7").midi(19, 24))
        assertEquals(MidiSelection(0, 4, 107), instrument("nord_piano_6").midi(14, 35))
        assertEquals(MidiSelection(0, 7, 49), instrument("nord_lead_a1").midi(7, 49))
    }

    /** Every Program Change fits a data byte, for every address of every profiled model. */
    @Test
    fun `every address of every profiled model has a selection`() {
        for (profile in catalog.devices) {
            val nord = instrument(profile.id)
            val layout = nord.layout
            assertTrue("${profile.id} should declare midiProgramSelect", profile.midiProgramSelect != null)
            for (address in layout.allAddresses()) {
                assertTrue("${profile.id} $address", nord.selector.midiSelection(address) != null)
            }
        }
    }

    @Test
    fun `a model without a declared layout states no values`() {
        val grand = catalog.devices.first { it.id == "nord_grand" }.copy(midiProgramSelect = null)
        val nord = NordInstrument(NordFixtures.device(ReplayTransport(emptyList()), grand))
        assertNull(nord.midi(0, 0))
    }
}
