package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * One row of the preset list: either a bank caption or an actual preset/empty-slot entry.
 *
 * Interleaving both in a single list lets the `LazyColumn` render captions inline between banks
 * while still driving drag-and-drop hit-testing off one flat, indexable sequence. [programIndex]
 * is this entry's position among [ProgramEntry] rows only, used to keep alternating row shading
 * stable regardless of where header rows fall.
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
 * Turns a listing plus the screen's filters into the rows to draw.
 *
 * **Pure, and out of the composable on purpose.** This was six `remember` blocks inside a very
 * long composable, which made the rules below - each accounting for a different way an instrument
 * can report its slots - impossible to test without running Compose. Nothing here touches the UI
 * toolkit, so `ProgramRowsTest` can hold every one of them.
 *
 * @param reported what the instrument actually answered with. A Nord lists only what it holds; a
 *   Pro-800 answers for every address, empty ones included.
 * @param allSlots every address the instrument's layout allows, in device order.
 */
/**
 * Where a copy could be written: every address the layout allows that does not already hold one.
 *
 * **Its own function, because it is not a property of what is on screen.** Copying a factory voice
 * needs the free *user* slots while the factory listing is displayed - a listing in which nothing
 * is free, since all 1,217 rows hold a voice. Computed inside `buildProgramListing` alone, it
 * answered "empty" there and hid the Copy item on exactly the rows the feature exists for.
 *
 * [allSlots] is therefore the caller's choice of address space, not the whole instrument.
 */
internal fun freeSlots(reported: List<PresetSlot>, allSlots: List<PresetSlot>): List<PresetSlot> {
    val occupied = reported.filterNot { it.isEmpty }.map { it.address }.toSet()
    return allSlots.filterNot { it.address in occupied }
}

internal fun buildProgramListing(
    reported: List<PresetSlot>,
    allSlots: List<PresetSlot>,
    showEmptySlots: Boolean,
    pickingCopy: Boolean,
    searchText: String,
): ProgramListing {
    // Addresses that actually hold a preset - **not** simply every address the index mentioned.
    // A Nord lists only what it holds, so the two were the same thing; a Pro-800 answers for all
    // 400, which made every empty slot look occupied and gave it a drag handle and a Rename
    // button it had no business offering.
    val occupied = reported.filterNot { it.isEmpty }.map { it.address }.toSet()

    // The same set "Show empty slots" already renders, just not filtered to the current bank or
    // name. Computed from the full address space rather than the toggle, since picking a
    // destination needs all of it whether or not that checkbox happens to be on.
    val freeSlots = freeSlots(reported, allSlots)

    // Bank labels come off the rows themselves rather than being parsed back out of a display id:
    // "A:1:1" yields its bank to substringBefore(':') and "A00" does not, and no screen should
    // know which shape it is looking at.
    //
    // **In device order, which is the order they arrive in - not sorted.** `distinct` already
    // preserves it, and sorting destroyed it. That was invisible for as long as every instrument's
    // labels happened to sort into device order ("USER 1".."USER DR"), and stops being invisible
    // the moment a bank is called "PRE1" and another "PRE DR": a space sorts before a digit, so
    // the rail would offer GM, GM DR, PRE DR, PRE1..PRE8 against a list running PRE1..PRE8, GM,
    // PRE DR, GM DR. Taps would still land, since the jump is a lookup by label, but dragging the
    // rail scrubs through the labels in order and would jump backwards and forwards.
    //
    // Picking a copy destination needs every bank on the rail, whether or not "show empty slots"
    // happens to be checked - an empty bank may be exactly where the target is.
    val bankLabels =
        (if (showEmptySlots || pickingCopy) allSlots else reported)
            .map { it.bankLabel }.distinct()

    // "Show empty slots" has to work in both directions, because the two families report
    // occupancy differently: a Nord lists only what it holds, so showing empties means *adding*
    // placeholder rows; a Pro-800 answers for every address, so it means *removing* the ones it
    // reported as empty. Doing only the first left a Pro-800 listing 300 empty rows with the box
    // unchecked.
    //
    // Picking a copy destination overrides both the toggle and the filter: the target the user
    // needs might be hidden by either, and a filter has nothing to match against a slot with no
    // name anyway.
    val expanded = if (pickingCopy || (showEmptySlots && searchText.isBlank())) {
        val byAddress = reported.associateBy { it.address }
        allSlots.map { placeholder -> byAddress[placeholder.address] ?: placeholder }
    } else {
        // A non-blank filter always excludes empty slots too - there is no name to match against -
        // which is why this also runs when the box is checked but a filter is set.
        reported.filterNot { it.isEmpty }
    }
    val visible = if (pickingCopy || searchText.isBlank()) {
        expanded
    } else {
        expanded.filter { it.name?.contains(searchText, ignoreCase = true) == true }
    }

    // `visible` is already grouped by bank (device order, see above), so a single linear pass can
    // insert a header row wherever the bank changes instead of needing a group-by.
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
