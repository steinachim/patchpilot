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
class DemoLibrary(private val nameList: List<String>, private val slotsPerBank: Int) {

    private val programs: MutableMap<SlotAddress, String> = seedPrograms().toMutableMap()

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
        name = programs[address],
    )

    fun requireOccupied(address: SlotAddress) {
        check(address in programs) { "No preset is stored at that slot." }
    }

    fun rename(address: SlotAddress, newName: String) {
        requireOccupied(address)
        programs[address] = newName
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
        val sourceName = programs.getValue(from)
        val copyName = generateSequence(2) { it + 1 }
            .map { "$sourceName $it" }
            .first { candidate -> programs.values.none { it == candidate } }
        programs[to] = copyName
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
    private fun seedPrograms(): Map<SlotAddress, String> = buildMap {
        nameList.forEachIndexed { index, name ->
            // Spread across banks with gaps rather than filling bank A first.
            val flat = index * 3
            put(SlotAddress(flat / slotsPerBank, flat % slotsPerBank), name)
        }
    }
}
