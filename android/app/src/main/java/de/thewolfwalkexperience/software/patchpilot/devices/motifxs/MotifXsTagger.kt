// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * A Motif XS voice's categories and its favorite mark - two stores that behave differently. The
 * categories are four bytes inside the voice; the favorite mark is one byte in a separate
 * per-bank table at `71 mm 00`. Hence [canSetCategories] and [canSetFavorite] disagreeing about a
 * factory bank: the instrument lets a host write a favorite for one, but a category is the
 * factory voice itself.
 *
 * The mark names which of the voice's own assignments the instrument's browser files it under, so
 * this facet offers assignment slots rather than categories. A slot the voice has not assigned is
 * a legal target, and the voice then appears under no category.
 *
 * Verified against an XS6: a favorite written from the app leaves the rest of its bank's marks
 * intact, and a category write of `Bass`/`Dr/Pc` with no sub stores `(4, 4)`/`(12, 4)`, one past
 * each main's last sub.
 */
internal class MotifXsTagger(
    private val instrument: MotifXsInstrument,
    private val config: MotifXsConfig,
    private val encoding: MotifXsCategoryEncoding,
    private val factoryVoices: MotifXsFactoryVoices,
) : PresetTagger {

    /** Shipped in the device catalog rather than the voice-name table: the encoding describes the instrument's format. */
    override val taxonomy: CategoryTaxonomy = encoding.taxonomy

    /** Yamaha's `Category 1` and `Category 2`. Fixed by the format, not by the catalog. */
    override val assignmentCount: Int = ASSIGNMENTS

    override val favorites: FavoriteModel = FavoriteModel.PER_ASSIGNMENT

    /** Inside the voice, so a factory bank is out - the same rule a rename obeys. */
    override fun canSetCategories(address: SlotAddress): Boolean =
        config.banks.getOrNull(address.bank)?.readOnly == false

    /** Outside the voice, so any catalogued bank takes one - confirmed against PRE1, which is read-only. */
    override fun canSetFavorite(address: SlotAddress): Boolean =
        address.bank in config.banks.indices

    /**
     * What is stored at [address]. A factory voice is never dumped: its categories come from the
     * shipped table, as in `MotifXsInstrument.indexFactory`, and a user voice's out of the `0C`
     * payload the browser already reads. The mark is a different store and costs one small dump
     * per bank, cached below.
     */
    override suspend fun read(address: SlotAddress): PresetTags {
        val spec = config.banks.getOrNull(address.bank)
            ?: throw IllegalArgumentException("No bank ${address.bank}")
        val categories = if (spec.readOnly) {
            instrument.factoryCategories(spec, address.slot)
        } else {
            val payload = instrument.readVoicePayload(address)
            MotifXsVoice.categoriesOf(payload).orEmpty()
                .map { figure -> figure?.let { MotifXsCategories.refOf(it, taxonomy) } }
        }
        val marks = marksOf(spec)
        return PresetTags(
            categories = List(ASSIGNMENTS) { categories.getOrNull(it) },
            favoriteUnder = decodeMark(marks.getOrNull(address.slot)?.toInt() ?: 0),
        )
    }

    /** Writes all four category bytes through the documented path, verified against the browser's own `0C` view. */
    override suspend fun setCategories(address: SlotAddress, categories: List<CategoryRef?>) {
        val wanted = List(ASSIGNMENTS) { categories.getOrNull(it) }
        wanted.filterNotNull().forEach { ref ->
            val main = taxonomy.main(ref.main)
                ?: throw IllegalArgumentException("No category ${ref.main} on a Motif XS.")
            require(ref.sub == null || ref.sub in main.subs.indices) {
                "${main.name} has no sub-category ${ref.sub}."
            }
        }
        val bytes = wanted.flatMap { ref ->
            val (main, sub) = MotifXsCategories.bytesOf(ref, taxonomy)
            listOf(main, sub)
        }

        instrument.editStoredCategories(address, bytes)

        // Read back through `0C`, the same path the browser lists with.
        val stored = MotifXsVoice.categoriesOf(instrument.readVoicePayload(address)).orEmpty()
            .map { figure -> figure?.let { MotifXsCategories.refOf(it, taxonomy) } }
        val storedPadded = List(ASSIGNMENTS) { stored.getOrNull(it) }
        check(storedPadded == wanted) {
            "set the categories of ${instrument.describe(address)} but they read back as " +
                storedPadded.joinToString { it?.let(taxonomy::label) ?: "none" }
        }
        invalidate(address.bank)
    }

    /**
     * Files [address] under [under], or clears the mark when it is empty: read the bank's whole
     * table, change one byte, send it back - see [MotifXsSysEx.writeFavorites].
     */
    override suspend fun setFavorite(address: SlotAddress, under: Set<Int>) {
        val spec = config.banks.getOrNull(address.bank)
            ?: throw IllegalArgumentException("No bank ${address.bank}")
        require(under.all { it in 0 until ASSIGNMENTS }) {
            "A Motif XS voice has $ASSIGNMENTS category slots; $under names another."
        }

        // A mark against an unassigned category is legal and the instrument makes them itself:
        // marking a category-less USER voice from its own Category Search -> FAVORITE writes `2`,
        // the same value `setOf(0)` encodes to, and the voice then appears under no category. So
        // no read of the voice's own categories is needed here.
        val value = encodeMark(under)
        val table = instrument.readFavoriteTable(spec).copyOf()
        require(address.slot in table.indices) {
            "${spec.label}'s favorites table is ${table.size} bytes; it has no slot " +
                "${address.slot}"
        }
        if (table[address.slot].toInt() == value) return
        table[address.slot] = value.toByte()

        instrument.writeFavoriteTable(spec, table)
        invalidate(address.bank)
    }

    // ---- The mark byte ----

    /** `0` none, `1` both, `2` Category 1 only, `3` Category 2 only. Not a bitmask: `1` is both. */
    private fun decodeMark(value: Int): Set<Int> = when (value) {
        MARK_BOTH -> setOf(0, 1)
        MARK_FIRST -> setOf(0)
        MARK_SECOND -> setOf(1)
        // 0, and anything the instrument has been left holding that means nothing to it.
        else -> emptySet()
    }

    private fun encodeMark(under: Set<Int>): Int = when (under) {
        emptySet<Int>() -> MARK_NONE
        setOf(0) -> MARK_FIRST
        setOf(1) -> MARK_SECOND
        else -> MARK_BOTH
    }

    // ---- The per-bank mark table ----

    /**
     * One bank's marks, remembered so a favorite dialog opening on a row does not cost a dump
     * every time. The table also changes when the player marks something on the front panel, so
     * the cache is dropped on every write and at the start of every listing - see
     * `MotifXsInstrument.index`.
     */
    private val marks = HashMap<String, ByteArray>()

    private suspend fun marksOf(spec: MotifXsBank): ByteArray =
        marks[spec.label] ?: instrument.readFavoriteTable(spec).also { marks[spec.label] = it }

    /** Drops one bank's cached marks, or all of them. */
    fun invalidate(bank: Int? = null) {
        if (bank == null) marks.clear() else config.banks.getOrNull(bank)?.let { marks.remove(it.label) }
    }

    private companion object {
        /** Yamaha's `Category 1` and `Category 2`. */
        const val ASSIGNMENTS = 2

        const val MARK_NONE = 0
        const val MARK_BOTH = 1
        const val MARK_FIRST = 2
        const val MARK_SECOND = 3
    }
}
