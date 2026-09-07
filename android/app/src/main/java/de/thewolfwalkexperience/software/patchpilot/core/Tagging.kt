package de.thewolfwalkexperience.software.patchpilot.core

/**
 * The categories a preset can be filed under, and whether it is one of the user's favorites.
 *
 * **A facet in its own right rather than more surface on [PresetEditor].** Everything on that
 * interface rearranges *where a preset lives*; nothing here moves a preset at all. The split also
 * lets the two capabilities be declared independently of each other, which they have to be: a
 * Motif XS lets a host favorite a factory voice but not re-categorise one, and those are different
 * answers about the same slot.
 *
 * **Shaped for more than one family from the start.** A Motif XS voice carries two assignments,
 * each a main category and a sub-category; a Nord program carries one, with no sub-categories at
 * all. Both are [CategoryTaxonomy] with [PresetTagger.assignmentCount] set differently and, for the
 * Nord, [MainCategory.subs] empty everywhere - so a single dialog renders both without knowing
 * which instrument is plugged in.
 */

/**
 * One main category and the sub-categories under it.
 *
 * [subs] is empty for a family with no second level. It is deliberately *not* nullable: "this main
 * has no subs" and "this family has no subs" are the same thing to every caller, and an empty list
 * makes `subs.indices` and `subs.isEmpty()` the only two questions anyone needs to ask.
 */
data class MainCategory(val name: String, val subs: List<String>)

/** Every category a family's presets can be filed under, in the order the instrument lists them. */
data class CategoryTaxonomy(val mains: List<MainCategory>) {
    /** True where no main has a second level - a flat taxonomy, which is the Nord case. */
    val isFlat: Boolean get() = mains.all { it.subs.isEmpty() }

    fun main(index: Int): MainCategory? = mains.getOrNull(index)

    /**
     * A display label for one assignment, e.g. `"Pads / Warm"`, or just `"S.EFX"` where the main
     * is assigned and the sub is not. Null where [ref] names a main this taxonomy does not have,
     * so an instrument that reports something unexpected shows no badge rather than crashing.
     */
    fun label(ref: CategoryRef): String? {
        val main = main(ref.main) ?: return null
        val sub = ref.sub?.let { main.subs.getOrNull(it) }
        return if (sub == null) main.name else "${main.name} / $sub"
    }
}

/**
 * One category assignment: an index into [CategoryTaxonomy.mains], and optionally one into that
 * main's [MainCategory.subs].
 *
 * [sub] is null for "this main, no sub-category" - a real state on a Motif XS, distinct from having
 * no assignment at all, which is represented by a null [CategoryRef] rather than by a value here.
 */
data class CategoryRef(val main: Int, val sub: Int? = null)

/**
 * What a preset is filed under, and whether it is favorited.
 *
 * [categories] always has [PresetTagger.assignmentCount] entries, with null for an unused slot, so
 * a caller can index it positionally without checking its length. [favoriteUnder] holds indices
 * *into that list*: on a Motif XS the favorite flag names which of the voice's own two assignments
 * the instrument's browser files it under, so `setOf(0)` means "listed under its first category
 * only". An empty set means it is not a favorite.
 */
data class PresetTags(
    val categories: List<CategoryRef?>,
    val favoriteUnder: Set<Int> = emptySet(),
) {
    val isFavorite: Boolean get() = favoriteUnder.isNotEmpty()

    /** Indices of the assignment slots that actually carry a category. */
    val assigned: List<Int> get() = categories.indices.filter { categories[it] != null }
}

/**
 * What "favorite" means to a family.
 *
 * The distinction is not cosmetic - it decides what the dialog can offer. A [FLAG] family can only
 * be asked yes or no; a [PER_ASSIGNMENT] family is asked *where*, and the answer is constrained to
 * the preset's own assignments, because that is all the instrument can store.
 */
enum class FavoriteModel {
    /** On or off. A single-assignment family, where "favorited" and "filed under it" coincide. */
    FLAG,

    /** Filed under a chosen subset of the preset's own assignments - a Motif XS. */
    PER_ASSIGNMENT,
}

/**
 * Reads and writes a preset's categories and its favorite mark.
 *
 * Null on [Instrument] where the family does neither.
 */
interface PresetTagger {
    val taxonomy: CategoryTaxonomy

    /** How many category assignments one preset holds. Motif XS 2, Nord 1. */
    val assignmentCount: Int

    /** Null where the family has no notion of a favorite at all. */
    val favorites: FavoriteModel?

    /**
     * Whether an assignment can be left empty.
     *
     * True on a Motif XS, which has a real `NoAsg` state its own panel offers. **False on a
     * Nord**, where every program always carries a category id and `None` is one of the
     * categories rather than the absence of one - so an editor must not offer a "clear it"
     * option the instrument has nowhere to store.
     *
     * **Constrains writing, not reading.** [PresetTags.categories] can still hold a null here: a
     * Nord program tagged with an id outside the ids its own model names displays as `No Cat` on
     * the instrument, and null is the honest way to show something this app cannot name.
     */
    val allowsUnassigned: Boolean get() = true

    /**
     * Whether [address]'s categories can be changed.
     *
     * **Per-slot, and separate from [canSetFavorite], because on a Motif XS they disagree.** A
     * voice's categories live inside the voice, so setting one in a factory bank means rewriting a
     * factory voice and is refused; its favorite mark lives in a separate per-bank table the
     * instrument lets a host rewrite for any bank. Asking the driver keeps that rule in the one
     * place that knows it, rather than having the UI re-derive it from the bank layout.
     */
    fun canSetCategories(address: SlotAddress): Boolean

    /** Whether [address]'s favorite mark can be changed. See [canSetCategories] on why it differs. */
    fun canSetFavorite(address: SlotAddress): Boolean

    /** What is stored at [address] today. */
    suspend fun read(address: SlotAddress): PresetTags

    /**
     * Replaces every assignment at once, nulls included.
     *
     * Whole-list rather than "set assignment n", because the underlying write is a whole-record
     * one on every family that has it, and an API that implied otherwise would invite a
     * read-modify-write per assignment where one suffices.
     */
    suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>)

    /**
     * Files [address] under the assignments named by [under], or removes the favorite if it is
     * empty.
     *
     * Every index in [under] must name a slot that currently *has* a category. An instrument has
     * nowhere to show a favorite filed under an unassigned slot, so this refuses rather than
     * writing a mark the user would never see.
     */
    suspend fun setFavorite(address: SlotAddress, under: Set<Int>)
}
