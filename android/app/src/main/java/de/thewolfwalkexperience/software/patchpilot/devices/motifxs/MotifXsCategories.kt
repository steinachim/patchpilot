// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy

/**
 * How a Motif XS packs one category assignment into a number, both ways: the instrument stores an
 * assignment as a main and a sub byte at `0x18`-`0x1B` of a voice's Common block, and the `0C`
 * voice dump carries the same pair packed as `main * 16 + sub` in decimal ASCII (see
 * [MotifXsVoice.categoriesOf]).
 */
object MotifXsCategories {

    /** How many sub-category values one main category's figure packs. */
    const val SUBS_PER_MAIN = 16

    /** The main-category value meaning "no assignment"; the last entry of the shipped `mainByValue` table, `NoAsg`. */
    const val NO_ASSIGNMENT_MAIN = 16

    /** The figure an unassigned slot reads as: `16 * 16 + 0`. */
    const val NO_ASSIGNMENT_FIGURE = NO_ASSIGNMENT_MAIN * SUBS_PER_MAIN

    /**
     * The assignment [figure] names, or null where it names none. "No sub-category" is one past
     * the main's last sub (`subs.size`: 5 for fourteen mains, 4 for `Bass` and `Dr/Pc`), not a
     * fixed value; anything at or past that boundary reads as "main assigned, no sub".
     */
    fun refOf(figure: Int, taxonomy: CategoryTaxonomy?): CategoryRef? {
        val main = figure / SUBS_PER_MAIN
        if (main >= NO_ASSIGNMENT_MAIN) return null
        val sub = figure % SUBS_PER_MAIN
        val subs = taxonomy?.main(main)?.subs.orEmpty()
        return CategoryRef(main, sub.takeIf { it < subs.size })
    }

    /**
     * The two bytes [ref] is stored as: main, then sub. Null means no assignment, which stores
     * `(16, 0)`: with no main assigned the instrument zeroes the sub, so the no-sub sentinel
     * would read back different from what was sent.
     */
    fun bytesOf(ref: CategoryRef?, taxonomy: CategoryTaxonomy?): Pair<Int, Int> {
        if (ref == null) return NO_ASSIGNMENT_MAIN to 0
        val subs = taxonomy?.main(ref.main)?.subs.orEmpty()
        return ref.main to (ref.sub ?: subs.size)
    }

    /**
     * The shipped factory table's assignments, resolved against [encoding]. An assignment naming
     * a category the encoding does not list is dropped, so a catalog whose halves disagree loses
     * a badge rather than showing an index.
     */
    fun refsOf(
        listed: List<MotifXsFactoryCategory>,
        encoding: MotifXsCategoryEncoding?,
    ): List<CategoryRef> {
        if (encoding == null) return emptyList()
        return listed.mapNotNull { encoding.refOf(it.main, it.sub) }
    }
}
