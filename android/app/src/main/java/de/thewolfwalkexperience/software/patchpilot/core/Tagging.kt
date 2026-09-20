// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/*
 * The categories a preset can be filed under, and whether it is one of the user's favorites.
 *
 * A facet of its own rather than part of [PresetEditor], which rearranges where a preset lives;
 * nothing here moves one. A Motif XS voice carries two assignments, each a main category and a
 * sub-category; a Nord program carries one, with no sub-categories. Both are a [CategoryTaxonomy]
 * with [PresetTagger.assignmentCount] set differently, so one dialog renders both.
 */

/** One main category and the sub-categories under it; [subs] is empty for a family with no second level. */
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
 * What "favorite" means to a family, and so what the dialog can offer: a [FLAG] family is asked
 * yes or no, a [PER_ASSIGNMENT] family is asked under which of the preset's own assignments.
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
     * Whether an assignment can be left empty. True on a Motif XS, which has a `NoAsg` state;
     * false on a Nord, where every program carries a category id and `None` is a category rather
     * than the absence of one.
     *
     * Constrains writing, not reading: [PresetTags.categories] can still hold a null for an id
     * this app cannot name (a Nord shows such a program as `No Cat`).
     */
    val allowsUnassigned: Boolean get() = true

    /**
     * Whether [address]'s categories can be changed. Separate from [canSetFavorite] because on a
     * Motif XS they disagree: a voice's categories live inside the voice, so a factory bank
     * refuses them, while its favorite mark lives in a per-bank table any bank accepts.
     */
    fun canSetCategories(address: SlotAddress): Boolean

    /** Whether [address]'s favorite mark can be changed. */
    fun canSetFavorite(address: SlotAddress): Boolean

    /** What is stored at [address] today. */
    suspend fun read(address: SlotAddress): PresetTags

    /**
     * Replaces every assignment at once, nulls included - the underlying write is a whole-record
     * one on every family that has it.
     */
    suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>)

    /**
     * Files [address] under the assignments named by [under], or removes the favorite if it is
     * empty. An index in [under] may name a slot with no category: on a Motif XS that is a legal
     * state its own panel produces, and the voice then appears in its Favorite bank under no
     * category.
     */
    suspend fun setFavorite(address: SlotAddress, under: Set<Int>)
}
