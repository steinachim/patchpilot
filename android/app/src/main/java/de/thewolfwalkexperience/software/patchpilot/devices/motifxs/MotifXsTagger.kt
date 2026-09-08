package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.FavoriteModel
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * A Motif XS voice's categories and its favorite mark.
 *
 * **Two stores, not one, and they behave differently at every turn.** The categories are four
 * bytes inside the voice; the favorite mark is one byte in a separate per-bank table at `71 mm 00`
 * that the instrument keeps alongside the voices. That is why [canSetCategories] and
 * [canSetFavorite] disagree about a factory bank - a favorite is the user's own data about factory
 * content, and the instrument lets a host write it; a category is the factory voice itself.
 *
 * The flag is not a boolean. It names which of the voice's *own* assignments the instrument's
 * browser files the favorite under, so this facet can only ever offer the assignments a voice
 * already has - which is also why a voice with no categories cannot be favorited at all.
 */
internal class MotifXsTagger(
    private val instrument: MotifXsInstrument,
    private val config: MotifXsConfig,
    private val encoding: MotifXsCategoryEncoding,
    private val factoryVoices: MotifXsFactoryVoices,
) : PresetTagger {

    /**
     * Shipped, not read - and off the **device catalog**, not the voice-name table.
     *
     * The encoding describes the instrument's format, which is why a user voice can be filed under
     * the same categories a factory one is. A catalog without it gets no facet at all rather than
     * one that can name nothing - see `MotifXsInstrument.tagger`.
     */
    override val taxonomy: CategoryTaxonomy = encoding.taxonomy

    /** Yamaha's `Category 1` and `Category 2`. Fixed by the format, not by the catalog. */
    override val assignmentCount: Int = ASSIGNMENTS

    override val favorites: FavoriteModel = FavoriteModel.PER_ASSIGNMENT

    /** Inside the voice, so a factory bank is out - the same rule a rename obeys. */
    override fun canSetCategories(address: SlotAddress): Boolean =
        config.banks.getOrNull(address.bank)?.readOnly == false

    /**
     * Outside the voice, so any catalogued bank will take one.
     *
     * Confirmed on hardware against PRE1, which is read-only and accepted the write regardless.
     */
    override fun canSetFavorite(address: SlotAddress): Boolean =
        address.bank in config.banks.indices

    /**
     * What is stored at [address].
     *
     * **A factory voice is never dumped.** Its categories come from the shipped table, which is
     * the same rule `MotifXsInstrument.indexFactory` keeps and for the same reason: the factory
     * listing costs no round trips and must go on costing none. A user voice's come out of the
     * `0C` payload the browser already reads, so they cost nothing either.
     *
     * The mark is a different store and always costs one small dump per bank, cached below.
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

    /**
     * Writes all four category bytes through the documented path.
     *
     * The read-back is the browser's own `0C` view rather than the blocks just written, so what
     * is verified is what the user will see.
     */
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
     * Files [address] under [under], or clears the mark when it is empty.
     *
     * Read the bank's whole table, change one byte, send the whole table back. See
     * [MotifXsSysEx.writeFavorites] for why it is whole-table, needs no store marker, and cannot
     * be verified by an immediate read.
     */
    override suspend fun setFavorite(address: SlotAddress, under: Set<Int>) {
        val spec = config.banks.getOrNull(address.bank)
            ?: throw IllegalArgumentException("No bank ${address.bank}")
        require(under.all { it in 0 until ASSIGNMENTS }) {
            "A Motif XS voice has $ASSIGNMENTS category slots; $under names another."
        }

        // **A mark against an unassigned category is legal, and the instrument makes them itself.**
        // Measured on hardware (2026-09-08): marking a category-less USER voice from the front
        // panel - Category Search -> FAVORITE - writes `2`, the same value `setOf(0)` encodes to
        // here, and the voice then appears in the instrument's own FAVORITE bank. So this is not
        // a write that "lists the voice nowhere"; it is the ordinary way to favorite a voice that
        // is filed under nothing, and 43 of the 128 voices in the USR1 bank measured are in
        // exactly that state. Refusing it made a third of a user bank unfavoritable from here
        // while the panel beside it allowed the same thing.
        //
        // No read of the voice's own categories is needed to decide that, which is why one no
        // longer happens on this path.

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

    /**
     * `0` none, `1` both, `2` Category 1 only, `3` Category 2 only.
     *
     * Not a bitmask, however much `1`/`2`/`3` invites reading it as one: `1` is *both*, and
     * writing a fourth value produces no listing rather than a third one.
     */
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
     * One bank's marks, remembered for the life of the session or until something writes.
     *
     * Cached because a favorite dialog opening on a row would otherwise cost a dump every time.
     * The table changes when this app writes one - and also when the *player* marks something on
     * the front panel, mid-session, which nothing here can be told about. So the cache is dropped
     * on every write and at the start of every listing, which is the app's own "re-read the
     * instrument" gesture: see `MotifXsInstrument.index`.
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
