// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory

/**
 * One instrument's program categories: the ids it offers, their display names, and the mapping
 * between a taxonomy index and the id that goes on the wire.
 *
 * **The index is not the id.** A Nord's ids are sparse - a Nord Grand offers
 * `0, 1, 2, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 17, 21, 22, 23, 24, 27, 28, 30` - so the twenty-one
 * entries of its taxonomy are not values `0..20` on the wire. [CategoryRef.main] has always been
 * an index into a [CategoryTaxonomy] rather than a device value; on a Motif XS the two happen to
 * coincide, and here they do not. This class is the whole of the difference.
 *
 * Built from two halves that are stored separately for a reason: the catalog's family-level
 * `programCategories` master list (54 entries, shared across Clavia's whole line) and the device's
 * own [DeviceProfile.programCategoryIds] subset, plus any
 * [DeviceProfile.programCategoryNameOverrides]. See [DeviceCatalog.programCategories] on why the
 * master list cannot simply be inverted.
 */
internal class NordCategories private constructor(
    /** Wire ids, in taxonomy order - so `ids[ref.main]` is what sub-opcode 51 carries. */
    private val ids: List<Int>,
    val taxonomy: CategoryTaxonomy,
) {

    /** The id sub-opcode 51 should carry for [ref], or null where it names no category here. */
    fun idOf(ref: CategoryRef): Int? = ids.getOrNull(ref.main)

    /**
     * The taxonomy entry for a wire [id], or null where this instrument does not name it.
     *
     * Null is a real answer rather than a failure: every id from 0 to 31 is *accepted* by the
     * instrument, and one outside its own set is stored verbatim and shown as `No Cat` on its
     * display. A row for such a program gets no badge, which is the honest rendering of a
     * category this app cannot name.
     */
    fun refOf(id: Int): CategoryRef? =
        ids.indexOf(id).takeIf { it >= 0 }?.let { CategoryRef(it, null) }

    /** The display name for a wire [id], or null - the label a row's badge shows. */
    fun nameOf(id: Int): String? = refOf(id)?.let { taxonomy.label(it) }

    companion object {
        /**
         * The category every Nord lists last rather than alphabetically.
         *
         * It is a real category with a real id, **not** the absence of one - which is why
         * `NordTagger.allowsUnassigned` is false and the editor never synthesises a second entry
         * with this name.
         */
        const val NONE = "None"

        /**
         * Resolves [profile]'s subset against the catalog's [master] list, or null where there is
         * nothing to resolve.
         *
         * Null - and therefore no tagging facet at all - when the profile declares no ids (an
         * unrecognised device, or a model nobody has profiled), or when any declared id cannot be
         * named from [master] or the profile's own overrides. A facet offering an empty or partial
         * list of categories would be worse than none, which is the same rule the Motif XS applies
         * to a catalog with no encoding.
         *
         * Note ids 47-52 have no master name at all, so an override standing in for a missing
         * master entry is expected, not a workaround.
         *
         * **Ordered alphabetically with [NONE] last**, which is the order the vendor's own editor
         * lists categories in. The catalog stores the ids in the instrument's own order instead,
         * so this is a display-time sort: the app and the vendor's tool then show the same list
         * in the same order, and a user comparing the two is not left wondering whether they are
         * looking at the same thing.
         */
        fun resolve(profile: DeviceProfile, master: Map<String, String>): NordCategories? {
            val declared = profile.programCategoryIds?.takeIf { it.isNotEmpty() } ?: return null
            val overrides = profile.programCategoryNameOverrides.orEmpty()

            // **All or nothing.** A partly-resolved list is the worst outcome available: with
            // overrides but no master list it would name 2 of a Nord Grand's 21 categories and
            // silently make the other 19 unreachable from the editor, which looks like a working
            // feature. Either every declared id can be named or this instrument gets no facet.
            val named = declared.map { id ->
                val name = overrides[id.toString()] ?: master[id.toString()] ?: return null
                id to name
            }

            // The master list is not injective, so a name only identifies an id *within* one
            // instrument's subset. A profile that picked both halves of a duplicate pair would
            // make this class's own name->id direction ambiguous, and every caller downstream of
            // it wrong in a way nothing would report - so refuse rather than pick one.
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
