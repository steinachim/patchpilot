package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy

/**
 * How a Motif XS packs one category assignment into a number, both ways.
 *
 * The instrument stores an assignment as two bytes - a main category and a sub-category - at
 * `0x18`-`0x1B` of a voice's Common block. The cheap `0C` voice dump carries the same two values
 * packed as `main * 16 + sub` and printed as decimal ASCII (see [MotifXsVoice.categoriesOf]). One
 * object converts both, so the packed form and the byte pair cannot drift apart.
 */
object MotifXsCategories {

    /** How many sub-category values one main category's figure packs. */
    const val SUBS_PER_MAIN = 16

    /**
     * The main-category value meaning "no assignment".
     *
     * Also the last entry of the shipped `mainByValue` table, where it reads `NoAsg`.
     */
    const val NO_ASSIGNMENT_MAIN = 16

    /** The figure an unassigned slot reads as: `16 * 16 + 0`. */
    const val NO_ASSIGNMENT_FIGURE = NO_ASSIGNMENT_MAIN * SUBS_PER_MAIN

    /**
     * The assignment [figure] names, or null where it names none.
     *
     * [taxonomy] decides what the sub half means. **"No sub-category" is one past the main's last
     * sub, not a fixed value** - `subs.size`, which is 5 for fourteen mains and 4 for `Bass` and
     * `Dr/Pc`, the two with only four subs each. A hard-coded 5 decodes those two wrongly, and
     * every *factory* voice happens to avoid the case, so nothing in a catalog check would catch
     * it. Anything at or past that boundary is read as "main assigned, no sub".
     */
    fun refOf(figure: Int, taxonomy: CategoryTaxonomy?): CategoryRef? {
        val main = figure / SUBS_PER_MAIN
        if (main >= NO_ASSIGNMENT_MAIN) return null
        val sub = figure % SUBS_PER_MAIN
        val subs = taxonomy?.main(main)?.subs.orEmpty()
        return CategoryRef(main, sub.takeIf { it < subs.size })
    }

    /**
     * The two bytes [ref] is stored as: main, then sub. Null means no assignment.
     *
     * **An unassigned slot stores `(16, 0)`, not `(16, subs.size)`.** With no main assigned the
     * sub is meaningless and the instrument zeroes it, so writing the no-sub sentinel alongside
     * main 16 reads back different from what was sent and fails a read-back verification.
     */
    fun bytesOf(ref: CategoryRef?, taxonomy: CategoryTaxonomy?): Pair<Int, Int> {
        if (ref == null) return NO_ASSIGNMENT_MAIN to 0
        val subs = taxonomy?.main(ref.main)?.subs.orEmpty()
        return ref.main to (ref.sub ?: subs.size)
    }

    /**
     * The shipped factory table's assignments, resolved against [encoding].
     *
     * An assignment naming a category the encoding does not list is dropped rather than rendered
     * as an index, so a catalog whose two halves disagree loses a badge instead of showing a
     * number no one can read.
     */
    fun refsOf(
        listed: List<MotifXsFactoryCategory>,
        encoding: MotifXsCategoryEncoding?,
    ): List<CategoryRef> {
        if (encoding == null) return emptyList()
        return listed.mapNotNull { encoding.refOf(it.main, it.sub) }
    }
}
