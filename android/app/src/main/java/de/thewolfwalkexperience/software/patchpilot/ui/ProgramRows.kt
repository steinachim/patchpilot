// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * One row of the preset list: either a bank caption or a preset/empty-slot entry. Interleaving
 * both in one list lets the `LazyColumn` render captions inline while drag-and-drop hit-testing
 * works off one indexable sequence. [programIndex] is this entry's position among [ProgramEntry]
 * rows only, which keeps the alternating shading stable wherever headers fall.
 */
internal sealed class ProgramRow {
    data class BankHeader(val bank: String) : ProgramRow()
    data class ProgramEntry(val item: PresetSlot, val programIndex: Int) : ProgramRow()
}

/**
 * Everything `ProgramsScreen` derives from a listing before it can draw anything.
 *
 * @property rows what the list renders, headers interleaved.
 * @property bankLabels the banks the index rail offers, in order.
 * @property occupied addresses that actually hold a preset.
 * @property freeSlots every address a copy could be written to.
 * @property bankHeaderIndex bank label -> the row index of its header, for the rail's jump.
 */
internal data class ProgramListing(
    val rows: List<ProgramRow> = emptyList(),
    val bankLabels: List<String> = emptyList(),
    val occupied: Set<SlotAddress> = emptySet(),
    val freeSlots: List<PresetSlot> = emptyList(),
    val bankHeaderIndex: Map<String, Int> = emptyMap(),
)

/**
 * Where a copy could be written: every address the layout allows that does not already hold one.
 * Its own function, because it is not a property of what is on screen - copying a factory voice
 * needs the free user slots while the factory listing, in which nothing is free, is displayed.
 * [allSlots] is therefore the caller's choice of address space.
 */
internal fun freeSlots(reported: List<PresetSlot>, allSlots: List<PresetSlot>): List<PresetSlot> {
    val occupied = reported.filterNot { it.isEmpty }.map { it.address }.toSet()
    return allSlots.filterNot { it.address in occupied }
}

/**
 * Turns a listing plus the screen's filters into the rows to draw. Pure and out of the composable,
 * so `ProgramRowsTest` can hold each of the rules below without running Compose.
 *
 * @param reported what the instrument answered with. A Nord lists only what it holds; a Pro-800
 *   answers for every address, empty ones included.
 * @param allSlots every address the instrument's layout allows, in device order.
 */
internal fun buildProgramListing(
    reported: List<PresetSlot>,
    allSlots: List<PresetSlot>,
    showEmptySlots: Boolean,
    picking: Boolean,
    searchText: String,
): ProgramListing {
    // Addresses that actually hold a preset, not every address the index mentioned: a Pro-800
    // answers for all 400, and an empty slot must not get a drag handle and a Rename button.
    val occupied = reported.filterNot { it.isEmpty }.map { it.address }.toSet()

    // From the full address space rather than the toggle, since picking a destination needs all
    // of it whether or not "show empty slots" is on.
    val freeSlots = freeSlots(reported, allSlots)

    // Bank labels come off the rows rather than being parsed out of a display id, since "A:1:1"
    // yields its bank to substringBefore(':') and "A00" does not.
    //
    // In device order, which is the order they arrive in: `distinct` preserves it. Sorted, a
    // space sorts before a digit, so the rail would offer PRE DR before PRE1 against a list
    // running PRE1..PRE8, GM, PRE DR - taps would still land, but dragging the rail scrubs
    // through the labels in order and would jump back and forth.
    //
    // Picking a copy destination needs every bank on the rail, since an empty bank may be where
    // the target is. Otherwise only banks with something in them: a Pro-800 reports its empty
    // addresses too, and a Nord's listing gains an empty entry once a delete has re-read a slot,
    // either of which would offer a bank the list has no header for.
    val bankLabels =
        (if (showEmptySlots || picking) allSlots else reported.filterNot { it.isEmpty })
            .map { it.bankLabel }.distinct()

    // "Show empty slots" works in both directions, since the families report occupancy
    // differently: a Nord lists only what it holds, so showing empties means adding placeholder
    // rows, while a Pro-800 answers for every address, so it means removing the empty ones.
    //
    // Picking a copy destination overrides both the toggle and the filter: the target may be
    // hidden by either, and a filter has nothing to match against a slot with no name.
    val expanded = if (picking || (showEmptySlots && searchText.isBlank())) {
        val byAddress = reported.associateBy { it.address }
        allSlots.map { placeholder -> byAddress[placeholder.address] ?: placeholder }
    } else {
        // A non-blank filter excludes empty slots, which have no name to match, so this also runs
        // when the box is checked and a filter is set.
        reported.filterNot { it.isEmpty }
    }
    val visible = if (picking || searchText.isBlank()) {
        expanded
    } else {
        expanded.filter { it.name?.contains(searchText, ignoreCase = true) == true }
    }

    // `visible` is already grouped by bank, so one linear pass inserts a header wherever the
    // bank changes.
    val rows = buildList {
        var lastBank: String? = null
        visible.forEachIndexed { programIndex, program ->
            if (program.bankLabel != lastBank) {
                add(ProgramRow.BankHeader(program.bankLabel))
                lastBank = program.bankLabel
            }
            add(ProgramRow.ProgramEntry(program, programIndex))
        }
    }

    return ProgramListing(
        rows = rows,
        bankLabels = bankLabels,
        occupied = occupied,
        freeSlots = freeSlots,
        // Row index of each bank's header, so the index rail can scroll straight to it.
        bankHeaderIndex = rows.withIndex()
            .mapNotNull { (i, row) -> (row as? ProgramRow.BankHeader)?.bank?.let { it to i } }
            .toMap(),
    )
}
