// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Nord program's category tag: one assignment, no sub-categories, no favorites, no read-only
 * banks, a sparse id space ([NordCategories]), and "None" as a category rather than the absence
 * of one. Reading costs nothing (the tag is in the item record the browser fetches for every
 * row); writing is sub-opcode 51/52 - see [NordDevice.setPresetCategory].
 */
internal class NordTagger(
    private val device: NordDevice,
    private val categories: NordCategories,
    /** [NordInstrument]'s lock; defaulted for the tests, which drive this class on its own. */
    private val deviceLock: Mutex = Mutex(),
) : PresetTagger {

    override val taxonomy: CategoryTaxonomy = categories.taxonomy

    /** One. A Nord program is filed under a single category, with no second level. */
    override val assignmentCount: Int = 1

    /** A Nord has no notion of a favorite at all. */
    override val favorites: FavoriteModel? = null

    /** False: every Nord program carries a category id, and `None` (id 17) is one of the categories. */
    override val allowsUnassigned: Boolean = false

    /** Every Nord bank is writable - the layout declares no read-only ones. */
    override fun canSetCategories(address: SlotAddress): Boolean = true

    override fun canSetFavorite(address: SlotAddress): Boolean = false

    /**
     * The category stored at [address]: one program-list walk, as [NordInstrument.refresh] pays.
     * Null where the program carries an id this instrument does not name (`No Cat` on its display).
     */
    override suspend fun read(address: SlotAddress): PresetTags = deviceLock.withLock {
        mapNordFailure("read a preset's category") {
            val id = categoryIdAt(address)
            PresetTags(categories = listOf(id?.let { categories.refOf(it) }))
        }
    }

    /**
     * Writes the single assignment, then verifies against the item record the browser lists with.
     * The argument checks stay outside [mapNordFailure], which would read an
     * `IllegalArgumentException` as an unparseable reply.
     */
    override suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>) {
        val wanted = categories.firstOrNull()
            ?: throw IllegalArgumentException(
                "A Nord program always carries a category; there is no way to clear one."
            )
        val id = this.categories.idOf(wanted)
            ?: throw IllegalArgumentException("No category ${wanted.main} on this Nord.")

        deviceLock.withLock {
            mapNordFailure("set a preset's category") {
                device.setPresetCategory(address.bank, address.slot, id)

                val stored = categoryIdAt(address)
                if (stored != id) {
                    throw NordProtocolException(
                        "set the category of ${device.formatPresetId(address.bank, address.slot)} " +
                            "to $id but it reads back as ${stored ?: "nothing"}",
                    )
                }
            }
        }
    }

    override suspend fun setFavorite(address: SlotAddress, under: Set<Int>): Unit =
        throw UnsupportedOperationException("A Nord has no favorites.")

    /** The raw wire id stored at [address], or null where the slot is empty or carries none. */
    private suspend fun categoryIdAt(address: SlotAddress): Int? {
        val displayId = device.formatPresetId(address.bank, address.slot)
        val items = device.collectItemNames(
            device.fetchCategoryItems(device.getProgramCategoryIndex()),
        )
        return items.firstOrNull { it.presetId == displayId }?.categoryId
    }
}
