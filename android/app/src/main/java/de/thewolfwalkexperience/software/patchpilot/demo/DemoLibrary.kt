// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout

/**
 * The in-memory content behind [DemoInstrument]: a sparse map of occupied slots, mutable where a
 * real instrument would let preset-list editing change it. [nameList] and [slotsPerBank] are what
 * a caller shapes, since the same engine backs `core.NoCopyFixtureInstrument` in the tests.
 */
class DemoLibrary(
    private val nameList: List<String>,
    private val slotsPerBank: Int,
    /**
     * What each seeded preset's category tag starts as, by preset name. Empty for a caller with no
     * categories at all, which is what leaves every row unbadged.
     */
    private val categoryByName: Map<String, String> = emptyMap(),
) {

    /**
     * One preset: its name and its category tag. The tag travels with the name, as a real
     * instrument stores it, so move, swap, copy and delete relocate the whole record and a preset
     * cannot arrive somewhere wearing another's category.
     */
    data class Program(val name: String, val category: String? = null)

    private val programs: MutableMap<SlotAddress, Program> = seedPrograms().toMutableMap()

    /** Every occupied slot, in device order, as browser rows. */
    fun slots(layout: SlotLayout): List<PresetSlot> =
        layout.allAddresses()
            .filter { it in programs }
            .map { toSlot(it, layout) }
            .toList()

    fun slot(address: SlotAddress, layout: SlotLayout): PresetSlot = toSlot(address, layout)

    private fun toSlot(address: SlotAddress, layout: SlotLayout) = PresetSlot(
        address = address,
        displayId = layout.format.format(address),
        bankLabel = layout.format.bankLabel(address.bank),
        name = programs[address]?.name,
        badges = listOfNotNull(programs[address]?.category),
    )

    fun requireOccupied(address: SlotAddress) {
        check(address in programs) { "No preset is stored at that slot." }
    }

    fun rename(address: SlotAddress, newName: String) {
        requireOccupied(address)
        // Copied, not replaced, so a rename keeps the category tag.
        programs[address] = programs.getValue(address).copy(name = newName)
    }

    /** The category tag at [address], or null where the preset carries none. */
    fun categoryAt(address: SlotAddress): String? = programs[address]?.category

    fun setCategoryAt(address: SlotAddress, category: String) {
        requireOccupied(address)
        programs[address] = programs.getValue(address).copy(category = category)
    }

    fun move(from: SlotAddress, to: SlotAddress) {
        requireOccupied(from)
        check(to !in programs) { "That slot already holds a preset; a move needs an empty one." }
        programs[to] = programs.remove(from)!!
    }

    /**
     * Duplicates [from] into the empty slot [to], leaving [from] alone, and names the copy itself
     * as a real Nord does, so
     * [de.thewolfwalkexperience.software.patchpilot.core.PresetEditor.copyProgram]'s contract
     * holds here too.
     */
    fun copy(from: SlotAddress, to: SlotAddress): String {
        requireOccupied(from)
        check(to !in programs) { "That slot already holds a preset; a copy needs an empty one." }
        val source = programs.getValue(from)
        val copyName = generateSequence(2) { it + 1 }
            .map { "${source.name} $it" }
            .first { candidate -> programs.values.none { it.name == candidate } }
        // The copy keeps the source's category, the way a real instrument's copy does.
        programs[to] = source.copy(name = copyName)
        return copyName
    }

    fun swap(a: SlotAddress, b: SlotAddress) {
        val left = programs[a]
        val right = programs[b]
        if (right != null) programs[a] = right else programs.remove(a)
        if (left != null) programs[b] = left else programs.remove(b)
    }

    fun delete(address: SlotAddress) {
        requireOccupied(address)
        programs.remove(address)
    }

    /** Leaves gaps, so "show empty slots" and the move-versus-swap distinction have something to act on. */
    private fun seedPrograms(): Map<SlotAddress, Program> = buildMap {
        nameList.forEachIndexed { index, name ->
            val flat = index * 3
            put(
                SlotAddress(flat / slotsPerBank, flat % slotsPerBank),
                Program(name, categoryByName[name]),
            )
        }
    }
}
