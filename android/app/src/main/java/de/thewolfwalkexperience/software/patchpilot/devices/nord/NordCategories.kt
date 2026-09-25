// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory

/**
 * One instrument's program categories: the ids it offers, their display names, and the mapping
 * between a taxonomy index and the id on the wire. The two differ: a Nord's ids are sparse (a
 * Nord Grand offers `0, 1, 2, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 17, 21, 22, 23, 24, 27, 28, 30`),
 * and [CategoryRef.main] is an index into a [CategoryTaxonomy].
 *
 * Built from the catalog's family-level `programCategories` master list and the device's own
 * [DeviceProfile.programCategoryIds] subset, plus any [DeviceProfile.programCategoryNameOverrides].
 */
internal class NordCategories private constructor(
    /** Wire ids, in taxonomy order - so `ids[ref.main]` is what sub-opcode 51 carries. */
    private val ids: List<Int>,
    val taxonomy: CategoryTaxonomy,
) {

    /** The id sub-opcode 51 should carry for [ref], or null where it names no category here. */
    fun idOf(ref: CategoryRef): Int? = ids.getOrNull(ref.main)

    /**
     * The taxonomy entry for a wire [id], or null where this instrument does not name it: every
     * id 0..31 is accepted and stored, and one outside the instrument's own set shows as `No Cat`.
     */
    fun refOf(id: Int): CategoryRef? =
        ids.indexOf(id).takeIf { it >= 0 }?.let { CategoryRef(it, null) }

    /** The display name for a wire [id], or null - the label a row's badge shows. */
    fun nameOf(id: Int): String? = refOf(id)?.let { taxonomy.label(it) }

    companion object {
        /** The category every Nord lists last: a real category with a real id, not the absence of one. */
        const val NONE = "None"

        /**
         * Resolves [profile]'s subset against the catalog's [master] list. Null - and so no
         * tagging facet - when the profile declares no ids, or when any declared id cannot be
         * named from [master] or the profile's overrides: a partial list would look like a
         * working feature. Ids 47-52 have no master name, so an override for them is expected.
         *
         * Ordered alphabetically with [NONE] last, the order the vendor's own editor lists
         * categories in; the catalog stores the ids in the instrument's own order.
         */
        fun resolve(profile: DeviceProfile, master: Map<String, String>): NordCategories? {
            val declared = profile.programCategoryIds?.takeIf { it.isNotEmpty() } ?: return null
            val overrides = profile.programCategoryNameOverrides.orEmpty()

            val named = declared.map { id ->
                val name = overrides[id.toString()] ?: master[id.toString()] ?: return null
                id to name
            }

            // The master list is not injective (ids 2 and 37 are both `Wind`), so a profile that
            // picked both halves of a pair would make the name->id direction ambiguous.
            val duplicated = named.groupBy { it.second }.filterValues { it.size > 1 }
            require(duplicated.isEmpty()) {
                "${profile.id} lists one category name under more than one id: " +
                    duplicated.map { (name, pairs) -> "$name = ${pairs.map { it.first }}" }
            }

            val ordered = named.sortedWith(
                compareBy({ it.second == NONE }, { it.second.lowercase() }),
            )
            return NordCategories(
                ids = ordered.map { it.first },
                taxonomy = CategoryTaxonomy(ordered.map { MainCategory(it.second, emptyList()) }),
            )
        }
    }
}
