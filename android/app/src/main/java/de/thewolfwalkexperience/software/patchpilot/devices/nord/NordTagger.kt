package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * A Nord program's category tag.
 *
 * **The simple end of this facet**, and deliberately so: one assignment, no sub-categories, no
 * favorites, and no read-only banks - so most of what the Motif XS implementation has to reason
 * about is simply absent here. What it does carry that the Motif XS does not is a sparse id space
 * ([NordCategories]) and the fact that "None" is a category rather than the absence of one.
 *
 * Reading costs nothing: the tag is in the item record the browser already fetches for every row.
 * Writing is sub-opcode 51/52, which is `renamePreset` with a different payload - see
 * [NordDevice.setPresetCategory].
 */
internal class NordTagger(
    private val device: NordDevice,
    private val categories: NordCategories,
) : PresetTagger {

    override val taxonomy: CategoryTaxonomy = categories.taxonomy

    /** One. A Nord program is filed under a single category, with no second level. */
    override val assignmentCount: Int = 1

    /** A Nord has no notion of a favorite at all. */
    override val favorites: FavoriteModel? = null

    /**
     * False: every Nord program always carries a category id.
     *
     * `None` is one of the categories the instrument offers (id 17), not the absence of one, so
     * there is nothing for a "clear it" option to write - and offering one beside the real `None`
     * would put the same word in the dropdown twice.
     */
    override val allowsUnassigned: Boolean = false

    /** Every Nord bank is writable - the layout declares no read-only ones. */
    override fun canSetCategories(address: SlotAddress): Boolean = true

    override fun canSetFavorite(address: SlotAddress): Boolean = false

    /**
     * The category stored at [address].
     *
     * Costs one program-list walk, which is what [NordInstrument.refresh] already pays after any
     * edit - the protocol's per-item fetch needs a category selection this layer does not hold
     * open. Null where the program carries an id this instrument does not name, which its own
     * display shows as `No Cat`.
     */
    override suspend fun read(address: SlotAddress): PresetTags {
        val id = categoryIdAt(address)
        return PresetTags(categories = listOf(id?.let { categories.refOf(it) }))
    }

    /**
     * Writes the single assignment, then verifies by reading the record back.
     *
     * Refuses null rather than inventing an id for it: see [allowsUnassigned]. The read-back is
     * against the item record - the same bytes the browser lists with - so what is verified is
     * what the user will see.
     */
    override suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>) {
        val wanted = categories.firstOrNull()
            ?: throw IllegalArgumentException(
                "A Nord program always carries a category; there is no way to clear one."
            )
        val id = this.categories.idOf(wanted)
            ?: throw IllegalArgumentException("No category ${wanted.main} on this Nord.")

        device.setPresetCategory(address.bank, address.slot, id)

        val stored = categoryIdAt(address)
        check(stored == id) {
            "set the category of ${device.formatPresetId(address.bank, address.slot)} to $id " +
                "but it reads back as ${stored ?: "nothing"}"
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
