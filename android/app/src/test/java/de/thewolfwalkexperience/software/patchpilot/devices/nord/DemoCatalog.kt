// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The instrument [DemoUsbTransport] pretends to be: a fixed set of root categories, each holding
 * a handful of dummy patches, mutable exactly where a real instrument would let program-list
 * editing change them (rename/move/swap on "Program"). This is small, static, UI-only content -
 * there is no real instrument's catalog behind it, unlike devices/nord_devices.json.
 *
 * Bank/item bounds match [DemoProfile.profile]: banks 0-3 ('A'-'D'), 25 items each.
 */
class DemoCatalog {
    data class Slot(val bank: Int, val item: Int)

    /** Category name -> (bank, item) -> patch name, insertion order preserved for both, since
     * that order is what [DemoUsbTransport] hands out as root-list/cursor-walk indexes. */
    private val categories: LinkedHashMap<String, LinkedHashMap<Slot, String>> = linkedMapOf(
        "Program" to programBank(),
        "Piano" to singleBank(
            listOf("Grand Piano A", "Bright Grand", "Mellow Upright", "Honky Tonk", "Studio Grand"),
        ),
        "Live" to singleBank(listOf("Live Set 1", "Live Set 2", "Live Set 3")),
        "Samp Lib" to singleBank(listOf("Strings Lib", "Brass Lib", "Choir Lib")),
        "Settings" to singleBank(listOf("Global Settings")),
    )

    val rootCategoryNames: List<String> get() = categories.keys.toList()

    fun itemsInCategory(categoryIndex: Int): Map<Slot, String> = categories.values.elementAt(categoryIndex)

    /** Mutates "Program" only - the one category a real instrument lets rename/move/swap touch. */
    fun rename(bank: Int, item: Int, newName: String) {
        programBank[Slot(bank, item)] = newName
    }

    fun move(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int) {
        val name = programBank.remove(Slot(srcBank, srcItem)) ?: return
        programBank[Slot(dstBank, dstItem)] = name
    }

    /**
     * Duplicates a program into an empty slot, leaving the source alone, and **names the copy
     * itself** - the way a real instrument does, since that auto-naming is the whole
     * reason `NordDevice.copyProgram` reads the destination back rather than trusting the request.
     * Returns null where the destination is already occupied, which the transport turns into the
     * instrument's status 4.
     */
    fun copy(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int): String? {
        val dstKey = Slot(dstBank, dstItem)
        if (dstKey in programBank) return null
        val sourceName = programBank[Slot(srcBank, srcItem)] ?: return null
        val copyName = generateSequence(2) { it + 1 }
            .map { "$sourceName $it" }
            .first { candidate -> programBank.values.none { it == candidate } }
        programBank[dstKey] = copyName
        return copyName
    }

    fun swap(srcBank: Int, srcItem: Int, dstBank: Int, dstItem: Int) {
        val srcKey = Slot(srcBank, srcItem)
        val dstKey = Slot(dstBank, dstItem)
        val srcName = programBank[srcKey]
        val dstName = programBank[dstKey]
        if (dstName != null) programBank[srcKey] = dstName else programBank.remove(srcKey)
        if (srcName != null) programBank[dstKey] = srcName else programBank.remove(dstKey)
    }

    fun delete(bank: Int, item: Int) {
        programBank.remove(Slot(bank, item))
    }

    private val programBank: LinkedHashMap<Slot, String> get() = categories.getValue("Program")

    private fun singleBank(names: List<String>): LinkedHashMap<Slot, String> =
        LinkedHashMap<Slot, String>().apply { names.forEachIndexed { i, name -> put(Slot(0, i), name) } }

    private fun programBank(): LinkedHashMap<Slot, String> {
        // (bank, item) left with gaps on purpose, so ProgramsScreen's empty-slot handling
        // (drag-to-move onto a gap, InstrumentViewModel.allSlots) has something to show.
        val entries = listOf(
            Slot(0, 0) to "Init Program",
            Slot(0, 1) to "Warm Pad",
            Slot(0, 2) to "Bright EPiano",
            Slot(0, 5) to "Clav Funk",
            Slot(0, 6) to "Organ B3",
            Slot(0, 10) to "Str Ensemble",
            Slot(0, 11) to "Slow Strings",
            Slot(0, 12) to "Analog Bass",
            Slot(1, 0) to "FM Bells",
            Slot(1, 1) to "Vintage Keys",
            Slot(1, 2) to "Choir Aah",
            Slot(1, 5) to "Wide Pad",
            Slot(1, 6) to "Percussive Keys",
            Slot(2, 0) to "Deep Sub",
            Slot(2, 1) to "Glass Bells",
            Slot(2, 2) to "Rhodes Mk1",
            Slot(2, 3) to "Wurli Tine",
            Slot(3, 0) to "Init Program 2",
            Slot(3, 1) to "Init Program 3",
        )
        return LinkedHashMap<Slot, String>().apply { entries.forEach { (slot, name) -> put(slot, name) } }
    }
}
