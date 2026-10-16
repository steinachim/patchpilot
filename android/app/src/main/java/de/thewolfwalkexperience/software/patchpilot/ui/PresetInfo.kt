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

/** A titled run of rows; [note] is a line of small print under them. */
data class InfoSection(
    val title: String?,
    val rows: List<InfoRow>,
    val note: String? = null,
)

/** What the Info dialog shows for one preset. */
data class PresetInfo(val title: String, val sections: List<InfoSection>)

/** What a family's tagger contributes: the taxonomy and the shape of its assignments and favorite. */
internal data class TagInfo(
    val tags: PresetTags,
    val taxonomy: CategoryTaxonomy,
    val assignmentCount: Int,
    val favorites: FavoriteModel?,
)

/**
 * Assembles the Info dialog from what the app holds about [slot]: the generic rows every family
 * gets, then the family's own [extras]. Pure, so the rows can be tested without an instrument.
 *
 * @param bank the slot's bank, or null where the layout does not have it.
 * @param tagInfo null where the family has no categories or favorites; the slot's own
 *   [PresetSlot.badges] are then listed instead.
 * @param canSelect whether the family can load a preset at all; only then is the MIDI section
 *   shown, saying so where [midi] is null.
 * @param midi the Bank Select and Program Change values, or null where the family has none to state.
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
    val sections = mutableListOf<InfoSection>()

    val general = mutableListOf<InfoRow>()
    general += InfoRow(strings.get(R.string.info_row_name), slot.name ?: slot.displayId)
    general += InfoRow(strings.get(R.string.info_row_location), slot.displayId)
    general += InfoRow(strings.get(R.string.info_row_bank), slot.bankLabel)
    if (bank != null) {
        general += InfoRow(
            strings.get(R.string.info_row_slot),
            strings.get(R.string.info_slot_value, slot.address.slot + 1, bank.slotCount),
        )
        general += InfoRow(
            strings.get(R.string.info_row_memory),
            strings.get(if (bank.readOnly) R.string.info_memory_factory else R.string.info_memory_user),
        )
    }
    if (tagInfo == null && slot.badges.isNotEmpty()) {
        general += InfoRow(strings.get(R.string.info_row_tags), slot.badges.joinToString(", "))
    }
    sections += InfoSection(title = null, rows = general)

    if (tagInfo != null) {
        val tagRows = tagRows(tagInfo, strings)
        if (tagRows.isNotEmpty()) sections += InfoSection(title = null, rows = tagRows)
    }

    if (canSelect) {
        sections += if (midi == null) {
            InfoSection(
                title = strings.get(R.string.info_section_midi),
                rows = emptyList(),
                note = strings.get(R.string.info_midi_unknown),
            )
        } else {
            InfoSection(
                title = strings.get(R.string.info_section_midi),
                rows = listOf(
                    InfoRow(strings.get(R.string.info_row_midi_msb), midiValue(midi.bankMsb, strings)),
                    InfoRow(strings.get(R.string.info_row_midi_lsb), midiValue(midi.bankLsb, strings)),
                    InfoRow(strings.get(R.string.info_row_midi_program), midiValue(midi.program, strings)),
                ),
                note = strings.get(R.string.info_midi_note),
            )
        }
    }

    if (extras.isNotEmpty()) {
        sections += InfoSection(title = strings.get(R.string.info_section_device), rows = extras)
    }
    return PresetInfo(title = slot.name ?: slot.displayId, sections = sections)
}

private fun midiValue(value: Int?, strings: StringResolver): String =
    if (value == null) {
        strings.get(R.string.info_midi_not_used)
    } else {
        strings.get(R.string.info_midi_value, value, value.toString(16).uppercase().padStart(2, '0'))
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
