package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout

/**
 * The in-memory content behind [DemoInstrument]: a sparse map of occupied slots, mutable exactly
 * where a real instrument would let preset-list editing change it.
 *
 * Small, static and UI-only. There is no real instrument's catalog behind this, unlike
 * `devices/nord_devices.json` - the point is to give every screen something plausible to render,
 * not to model any particular hardware. [nameList] and [slotsPerBank] are what a caller shapes
 * differently - [DemoInstrument] is the only production caller, but the same engine backs
 * `core.NoCopyFixtureInstrument`, a test-only fixture shaped like a family with no copy of its
 * own, which is why this class knows nothing about who is asking.
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
     * One preset: its name and its category tag.
     *
     * **The tag travels with the name rather than in a parallel map keyed by address**, which is
     * how a real instrument stores it too - inside the preset. It is also the only version of this
     * that cannot desynchronise: move, swap, copy and delete all relocate the whole record, so a
     * preset cannot arrive somewhere wearing the category of whatever used to be there.
     */
    data class Program(val name: String, val category: String? = null)

    private val programs: MutableMap<SlotAddress, Program> = seedPrograms().toMutableMap()

    /** Unused by anything today - kept because nothing established it should go with this change. */
    private val categories: Map<String, List<String>> = linkedMapOf(
        "Program" to emptyList(),
        "Piano" to listOf("Grand Piano A", "Bright Grand", "Mellow Upright", "Honky Tonk", "Studio Grand"),
        "Live" to listOf("Live Set 1", "Live Set 2", "Live Set 3"),
        "Samp Lib" to listOf("Strings Lib", "Brass Lib", "Choir Lib"),
        "Settings" to listOf("Global Settings"),
    )

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
        // Copied, not replaced: a rename must not silently clear the category tag.
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
     * Duplicates [from] into the empty slot [to], leaving [from] alone, and names the copy itself -
     * the way a real Nord does ([de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice.copyProgram]) -
     * so [de.thewolfwalkexperience.software.patchpilot.core.PresetEditor.copyProgram]'s contract of
     * returning the instrument-assigned name holds in demo mode too.
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

    fun categoryNames(): List<String> = categories.keys.toList()

    fun itemsIn(categoryName: String, layout: SlotLayout): List<PresetSlot> {
        if (categoryName.equals("Program", ignoreCase = true)) return slots(layout)
        val names = categories.entries
            .firstOrNull { it.key.equals(categoryName, ignoreCase = true) }
            ?.value
            ?: error("No '$categoryName' category on this instrument.")
        // A category's items live in their own address space on a real instrument; one bank of
        // consecutive slots is close enough for a demo and keeps the rows renderable.
        return names.mapIndexed { index, name ->
            val address = SlotAddress(0, index)
            PresetSlot(
                address = address,
                displayId = layout.format.format(address),
                bankLabel = layout.format.bankLabel(0),
                name = name,
            )
        }
    }

    /**
     * Leaves deliberate gaps, so "show empty slots" and the move-versus-swap distinction both have
     * something to act on.
     */
    private fun seedPrograms(): Map<SlotAddress, Program> = buildMap {
        nameList.forEachIndexed { index, name ->
            // Spread across banks with gaps rather than filling bank A first.
            val flat = index * 3
            put(
                SlotAddress(flat / slotsPerBank, flat % slotsPerBank),
                Program(name, categoryByName[name]),
            )
        }
    }
}
