// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory
import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows of the Info dialog. Resource ids are opaque here: a resolver that renders an id and
 * its arguments is enough to tell one string from another.
 */
class PresetInfoTest {

    private val strings =
        StringResolver { id, args -> "$id${if (args.isEmpty()) "" else args.joinToString(",", "(", ")")}" }

    private fun text(id: Int, vararg args: Any) = strings.get(id, *args)

    private val slot = PresetSlot(
        address = SlotAddress(1, 4),
        displayId = "B05",
        bankLabel = "B",
        name = "Warm Brass",
    )
    private val bank = BankSpec(label = "B", slotCount = 16)
    private val taxonomy = CategoryTaxonomy(
        listOf(MainCategory("Pads", listOf("Warm", "Cold")), MainCategory("Bass", emptyList())),
    )

    private fun build(
        slot: PresetSlot = this.slot,
        bank: BankSpec? = this.bank,
        tagInfo: TagInfo? = null,
        canSelect: Boolean = false,
        midi: MidiSelection? = null,
        extras: List<InfoRow> = emptyList(),
    ) = buildPresetInfo(slot, bank, tagInfo, canSelect, midi, extras, strings)

    private fun PresetInfo.rows(): Map<String, String> =
        sections.flatMap { it.rows }.associate { it.label to it.value }

    @Test
    fun `the generic rows state name, location, bank, position and memory`() {
        val info = build()

        assertEquals("Warm Brass", info.title)
        val rows = info.rows()
        assertEquals("Warm Brass", rows[text(R.string.info_row_name)])
        assertEquals("B05", rows[text(R.string.info_row_location)])
        assertEquals("B", rows[text(R.string.info_row_bank)])
        assertEquals(text(R.string.info_slot_value, 5, 16), rows[text(R.string.info_row_slot)])
        assertEquals(text(R.string.info_memory_user), rows[text(R.string.info_row_memory)])
    }

    @Test
    fun `a read-only bank is called factory memory`() {
        val rows = build(bank = bank.copy(readOnly = true)).rows()
        assertEquals(text(R.string.info_memory_factory), rows[text(R.string.info_row_memory)])
    }

    @Test
    fun `a slot with no bank in the layout still gets its identity rows`() {
        val rows = build(bank = null).rows()
        assertEquals("B05", rows[text(R.string.info_row_location)])
        assertNull(rows[text(R.string.info_row_slot)])
        assertNull(rows[text(R.string.info_row_memory)])
    }

    @Test
    fun `badges are listed where the family has no tagger`() {
        val rows = build(slot = slot.copy(badges = listOf("v2", "Lead"))).rows()
        assertEquals("v2, Lead", rows[text(R.string.info_row_tags)])
    }

    @Test
    fun `a single-assignment family shows one category and a flag favorite`() {
        val tags = PresetTags(categories = listOf(CategoryRef(1)), favoriteUnder = setOf(0))
        val rows = build(tagInfo = TagInfo(tags, taxonomy, 1, FavoriteModel.FLAG)).rows()

        assertEquals("Bass", rows[text(R.string.info_row_category)])
        assertEquals(text(R.string.info_yes), rows[text(R.string.info_row_favorite)])
        assertNull("the badges are replaced by the tags", rows[text(R.string.info_row_tags)])
    }

    @Test
    fun `a two-assignment family numbers its categories and names the favorite's assignment`() {
        val tags = PresetTags(categories = listOf(CategoryRef(0, 1), null), favoriteUnder = setOf(0))
        val rows = build(tagInfo = TagInfo(tags, taxonomy, 2, FavoriteModel.PER_ASSIGNMENT)).rows()

        assertEquals("Pads / Cold", rows[text(R.string.info_row_category_n, 1)])
        assertEquals(text(R.string.programs_no_category), rows[text(R.string.info_row_category_n, 2)])
        assertEquals(text(R.string.info_favorite_under, "Pads / Cold"), rows[text(R.string.info_row_favorite)])
    }

    @Test
    fun `a favorite is no where none is set, and absent where the family has none`() {
        val tags = PresetTags(categories = listOf(CategoryRef(0)))
        val withFavorites = build(tagInfo = TagInfo(tags, taxonomy, 1, FavoriteModel.FLAG)).rows()
        val without = build(tagInfo = TagInfo(tags, taxonomy, 1, null)).rows()

        assertEquals(text(R.string.info_no), withFavorites[text(R.string.info_row_favorite)])
        assertNull(without[text(R.string.info_row_favorite)])
    }

    @Test
    fun `the MIDI section lists the wire values and says where a controller is not used`() {
        val info = build(canSelect = true, midi = MidiSelection(bankMsb = 63, bankLsb = null, program = 29))
        val midi = info.sections.single { it.title == text(R.string.info_section_midi) }

        assertEquals(
            listOf(
                InfoRow(text(R.string.info_row_midi_msb), text(R.string.info_midi_value, 63, "3F")),
                InfoRow(text(R.string.info_row_midi_lsb), text(R.string.info_midi_not_used)),
                InfoRow(text(R.string.info_row_midi_program), text(R.string.info_midi_value, 29, "1D")),
            ),
            midi.rows,
        )
        assertEquals(text(R.string.info_midi_note), midi.note)
    }

    @Test
    fun `a family that can select but states no values says so`() {
        val midi = build(canSelect = true, midi = null).sections
            .single { it.title == text(R.string.info_section_midi) }

        assertTrue(midi.rows.isEmpty())
        assertEquals(text(R.string.info_midi_unknown), midi.note)
    }

    @Test
    fun `an instrument that cannot select has no MIDI section`() {
        val info = build(canSelect = false, midi = null)
        assertTrue(info.sections.none { it.title == text(R.string.info_section_midi) })
    }

    @Test
    fun `the family's own rows come last, in their order`() {
        val extras = listOf(InfoRow("Voice size", "1.9 kB"), InfoRow("Type", "Normal"))
        val info = build(canSelect = true, midi = MidiSelection(0, 0, 1), extras = extras)

        val last = info.sections.last()
        assertEquals(text(R.string.info_section_device), last.title)
        assertEquals(extras, last.rows)
    }

    @Test
    fun `no extras means no details section`() {
        assertTrue(build().sections.none { it.title == text(R.string.info_section_device) })
    }
}
