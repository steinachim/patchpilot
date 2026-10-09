// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * A Subsequent 37 preset's category: byte 50 of the program ([Subsequent37Categories]), one
 * assignment, no sub-categories, no favorites. Unassigned is a stored state of its own (`0x77`,
 * what Initialize leaves), which the panel shows as `Misc` like the real category 0: it is read as
 * no category, and not offered when setting one, since it would read back on the panel as `Misc`.
 *
 * Written like every edit: the stored program read with `06 01`, byte 50 changed, the whole
 * program written back and read again.
 */
class Subsequent37Tagger(private val instrument: Subsequent37Instrument) : PresetTagger {

    private val device get() = instrument.device

    /** In the panel's order. A [CategoryRef.main] is an index into it. */
    override val taxonomy: CategoryTaxonomy =
        CategoryTaxonomy(Subsequent37Categories.PANEL_ORDER.map { MainCategory(it, emptyList()) })

    override val assignmentCount: Int = 1

    override val favorites: FavoriteModel? = null

    /** False: see the class doc. A `null` still stores `0x77`; the dialog never sends one (`categoriesSavable`). */
    override val allowsUnassigned: Boolean = false

    override fun canSetCategories(address: SlotAddress): Boolean = true

    override fun canSetFavorite(address: SlotAddress): Boolean = false

    /**
     * Null for an unassigned preset, and for a byte-50 value with no category. From the program
     * last read at [address] where there is one (see [Subsequent37Device.lastRead]), so the dialog
     * opens at once with what the list shows; read now otherwise.
     */
    override suspend fun read(address: SlotAddress): PresetTags {
        val number = instrument.numberOf(address)
        val program = device.lastRead(number) ?: instrument.operation { device.readProgram(number) }
        return PresetTags(listOf(Subsequent37Categories.panelIndexOf(program.category)?.let { CategoryRef(it) }))
    }

    /** Changes byte 50 alone; null stores `0x77`, unassigned. */
    override suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>) {
        val wanted = categories.firstOrNull()
        require(wanted?.sub == null) { "A Subsequent 37 category has no sub-categories." }
        val value = wanted?.let { Subsequent37Categories.valueOf(it.main) } ?: Subsequent37Categories.UNSET
        val number = instrument.numberOf(address)
        instrument.operation {
            val current = device.readProgram(number)
            if (current.isEmpty) throw InstrumentException.SlotStateChanged.empty(instrument.displayIdOf(number))
            if (current.category == value) return@operation
            device.writeVerified(number, current.withCategory(value), instrument.displayIdOf(number))
        }
    }

    override suspend fun setFavorite(address: SlotAddress, under: Set<Int>): Unit =
        throw InstrumentException.NotSupported("mark a favorite")
}
