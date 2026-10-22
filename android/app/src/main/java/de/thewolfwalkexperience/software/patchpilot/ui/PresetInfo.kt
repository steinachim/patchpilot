// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import de.thewolfwalkexperience.software.patchpilot.core.MidiSelection
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags

/** One number of a [MidiBlock]: [value] is null where the controller is not used. */
data class MidiCell(val label: String, val caption: String, val value: Int?)

/** The Bank Select and Program Change values, drawn side by side, with a line of small print. */
data class MidiBlock(val cells: List<MidiCell>, val note: String?)

/**
 * What the Info dialog shows for one preset: [subtitle] under the title, then [rows] as
 * label/value pairs, then the [midi] block where there is anything to say about it.
 */
data class PresetInfo(
    val title: String,
    val subtitle: String,
    val rows: List<InfoRow>,
    val midi: MidiBlock?,
)

/** What a family's tagger contributes: the taxonomy and the shape of its assignments and favorite. */
internal data class TagInfo(
    val tags: PresetTags,
    val taxonomy: CategoryTaxonomy,
    val assignmentCount: Int,
    val favorites: FavoriteModel?,
)

/**
 * Assembles the Info dialog from what the app holds about [slot]. Pure, so the rows can be tested
 * without an instrument.
 *
 * The name is the title and the id the subtitle, so neither is repeated as a row; the bank and slot
 * are part of the id, so they have none either. A preset in the user's own memory says nothing
 * about it: only a factory one is named as such, since that explains the missing edit actions.
 *
 * @param bank the slot's bank, for its read-only flag; null where the layout does not have it.
 * @param tagInfo null where the family has no categories or favorites; the slot's own
 *   [PresetSlot.badges] are then listed instead.
 * @param canSelect whether the family can load a preset at all; only then is the MIDI block
 *   shown, saying so where [midi] is null.
 * @param midi the Bank Select and Program Change values, or null where the family has none to state.
 * @param extras the family's own rows, last.
 */
internal fun buildPresetInfo(
    slot: PresetSlot,
    bank: BankSpec?,
    tagInfo: TagInfo?,
    canSelect: Boolean,
    midi: MidiSelection?,
    extras: List<InfoRow>,
    strings: StringResolver,
): PresetInfo {
    val rows = mutableListOf<InfoRow>()
    if (tagInfo == null) {
        if (slot.badges.isNotEmpty()) {
            rows += InfoRow(strings.get(R.string.info_row_tags), slot.badges.joinToString(", "))
        }
    } else {
        rows += tagRows(tagInfo, strings)
    }
    rows += extras

    val subtitle = if (bank?.readOnly == true) {
        strings.get(R.string.info_subtitle_factory, slot.displayId)
    } else {
        slot.displayId
    }
    return PresetInfo(
        title = slot.name ?: slot.displayId,
        subtitle = subtitle,
        rows = rows,
        midi = if (canSelect) midiBlock(midi, strings) else null,
    )
}

private fun midiBlock(midi: MidiSelection?, strings: StringResolver): MidiBlock =
    if (midi == null) {
        MidiBlock(cells = emptyList(), note = strings.get(R.string.info_midi_unknown))
    } else {
        MidiBlock(
            cells = listOf(
                MidiCell(strings.get(R.string.info_midi_msb), strings.get(R.string.info_midi_cc, 0), midi.bankMsb),
                MidiCell(strings.get(R.string.info_midi_lsb), strings.get(R.string.info_midi_cc, 32), midi.bankLsb),
                MidiCell(strings.get(R.string.info_midi_program), strings.get(R.string.info_midi_pc), midi.program),
            ),
            note = strings.get(R.string.info_midi_note),
        )
    }

private fun tagRows(info: TagInfo, strings: StringResolver): List<InfoRow> {
    val rows = mutableListOf<InfoRow>()
    val none = strings.get(R.string.programs_no_category)
    // Positional, as PresetTags guarantees: one entry per assignment, null where unused.
    for (index in 0 until info.assignmentCount) {
        val label = info.tags.categories.getOrNull(index)?.let { info.taxonomy.label(it) } ?: none
        val name = if (info.assignmentCount == 1) {
            strings.get(R.string.info_row_category)
        } else {
            strings.get(R.string.info_row_category_n, index + 1)
        }
        rows += InfoRow(name, label)
    }
    when (info.favorites) {
        null -> Unit

        FavoriteModel.FLAG ->
            rows += InfoRow(
                strings.get(R.string.info_row_favorite),
                strings.get(if (info.tags.isFavorite) R.string.info_yes else R.string.info_no),
            )

        FavoriteModel.PER_ASSIGNMENT -> {
            val under = info.tags.favoriteUnder.sorted().joinToString(", ") { index ->
                info.tags.categories.getOrNull(index)?.let { info.taxonomy.label(it) } ?: none
            }
            rows += InfoRow(
                strings.get(R.string.info_row_favorite),
                if (info.tags.isFavorite) {
                    strings.get(R.string.info_favorite_under, under)
                } else {
                    strings.get(R.string.info_no)
                },
            )
        }
    }
    return rows
}
