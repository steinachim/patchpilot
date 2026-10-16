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

    private fun PresetInfo.rowMap(): Map<String, String> = rows.associate { it.label to it.value }

    @Test
    fun `the name is the title and the id the subtitle, with no row repeating either`() {
        val info = build()

        assertEquals("Warm Brass", info.title)
        assertEquals("B05", info.subtitle)
        assertTrue(info.rows.none { it.value == "Warm Brass" || it.value == "B05" })
    }

    @Test
    fun `only a factory preset is called factory`() {
        assertEquals("B05", build().subtitle)
        assertEquals(text(R.string.info_subtitle_factory, "B05"), build(bank = bank.copy(readOnly = true)).subtitle)
    }

    @Test
    fun `an unnamed slot is titled by its id`() {
        assertEquals("B05", build(slot = slot.copy(name = null)).title)
    }

    @Test
    fun `badges are listed where the family has no tagger`() {
        val rows = build(slot = slot.copy(badges = listOf("v2", "Lead"))).rowMap()
        assertEquals("v2, Lead", rows[text(R.string.info_row_tags)])
    }

    @Test
    fun `a single-assignment family shows one category and a flag favorite`() {
        val tags = PresetTags(categories = listOf(CategoryRef(1)), favoriteUnder = setOf(0))
        val rows = build(tagInfo = TagInfo(tags, taxonomy, 1, FavoriteModel.FLAG)).rowMap()

        assertEquals("Bass", rows[text(R.string.info_row_category)])
        assertEquals(text(R.string.info_yes), rows[text(R.string.info_row_favorite)])
        assertNull("the badges are replaced by the tags", rows[text(R.string.info_row_tags)])
    }

    @Test
    fun `a two-assignment family numbers its categories and names the favorite's assignment`() {
        val tags = PresetTags(categories = listOf(CategoryRef(0, 1), null), favoriteUnder = setOf(0))
        val rows = build(tagInfo = TagInfo(tags, taxonomy, 2, FavoriteModel.PER_ASSIGNMENT)).rowMap()

        assertEquals("Pads / Cold", rows[text(R.string.info_row_category_n, 1)])
        assertEquals(text(R.string.programs_no_category), rows[text(R.string.info_row_category_n, 2)])
        assertEquals(text(R.string.info_favorite_under, "Pads / Cold"), rows[text(R.string.info_row_favorite)])
    }

    @Test
    fun `a favorite is no where none is set, and absent where the family has none`() {
        val tags = PresetTags(categories = listOf(CategoryRef(0)))
        val withFavorites = build(tagInfo = TagInfo(tags, taxonomy, 1, FavoriteModel.FLAG)).rowMap()
        val without = build(tagInfo = TagInfo(tags, taxonomy, 1, null)).rowMap()

        assertEquals(text(R.string.info_no), withFavorites[text(R.string.info_row_favorite)])
        assertNull(without[text(R.string.info_row_favorite)])
    }

    @Test
    fun `the MIDI block has the three values, with null where a controller is not used`() {
        val midi = build(canSelect = true, midi = MidiSelection(bankMsb = 63, bankLsb = null, program = 29)).midi!!

        assertEquals(
            listOf(
                MidiCell(text(R.string.info_midi_msb), text(R.string.info_midi_cc, 0), 63),
                MidiCell(text(R.string.info_midi_lsb), text(R.string.info_midi_cc, 32), null),
                MidiCell(text(R.string.info_midi_program), text(R.string.info_midi_pc), 29),
            ),
            midi.cells,
        )
        assertEquals(text(R.string.info_midi_note), midi.note)
    }

    @Test
    fun `a family that can select but states no values says so`() {
        val midi = build(canSelect = true, midi = null).midi!!

        assertTrue(midi.cells.isEmpty())
        assertEquals(text(R.string.info_midi_unknown), midi.note)
    }

    @Test
    fun `an instrument that cannot select has no MIDI block`() {
        assertNull(build(canSelect = false, midi = null).midi)
    }

    @Test
    fun `the family's own rows come last, in their order`() {
        val extras = listOf(InfoRow("Voice size", "1.9 kB"), InfoRow("Type", "Normal"))
        val info = build(extras = extras)

        assertEquals(extras, info.rows.takeLast(2))
    }
}
