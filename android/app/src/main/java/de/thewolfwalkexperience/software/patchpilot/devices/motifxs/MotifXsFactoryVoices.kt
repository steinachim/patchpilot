// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import kotlinx.serialization.Serializable

/**
 * The names and category assignments of the instrument's 1,217 read-only voices, by bank and slot.
 *
 * **Transcribed from Yamaha's own Data List, not read off the wire.** That is the whole point of
 * shipping it: the eleven factory banks never change, and reading them over MIDI costs about seven
 * and a half minutes - 1,217 dumps at ~160 ms, and a full second for each of the 65 drum kits -
 * which is a price worth paying exactly never for data that is identical on every Motif XS ever
 * built. The user banks are the opposite case and are still read from the instrument every time.
 *
 * This is a departure from the rule stated in `devices/blanks/README.md`, that this project ships
 * only real instrument data, and it is worth being explicit about why it is not a violation. That
 * rule is about *bytes on a wire* - payloads the app sends to an instrument, which it must never
 * fabricate. These are display strings the app never transmits. The failure mode of a wrong entry
 * here is a row with the wrong label on it, not a malformed write; and the browser says where the
 * names came from, so nobody is invited to believe the instrument was asked.
 *
 * **The categories are here for the same reason, and one more.** Reading them is cheap now (see
 * [MotifXsVoice.categoriesOf]), but "cheap" still means a dump per voice, and the factory listing
 * is built without asking the instrument anything at all - see `MotifXsInstrument.indexFactory`.
 * Serving factory categories from this table is what keeps that true.
 *
 * They are stored **by name**, and turning a name into an index needs
 * [MotifXsCategoryEncoding], which lives in the device catalog rather than here - it describes the
 * instrument, not this list.
 *
 * **PREDR's and GMDR's categories came from the instrument, not from a voice list.** Yamaha's
 * drum voice list has no category columns at all, so those 65 drum kits' assignments were read
 * off a Motif XS and added here. Every bank in this table carries categories for every voice,
 * which `MotifXsFactoryVoicesTest` pins.
 *
 * Bank labels match `MotifXsBank.label` in the catalog, and `MotifXsFactoryVoicesTest` pins the two
 * together so a bank renamed in one and not the other fails the build rather than quietly listing
 * a bank of nulls.
 */
@Serializable
data class MotifXsFactoryVoices(
    val banks: List<MotifXsFactoryBank> = emptyList(),
) {
    /**
     * Slot names per bank label, built once: `by[label][slot0]`.
     *
     * An `Array<String?>` indexed by slot rather than a map keyed by a pair, because a factory
     * listing asks for all 1,217 in a row and this is the difference between 1,217 hash lookups on
     * an allocated key and 1,217 array reads. Sparse or short banks read null past their end, so a
     * table that disagrees with the catalog about a slot count yields an unnamed row rather than an
     * exception.
     */
    private val byLabel: Map<String, Array<MotifXsFactoryVoice?>> by lazy {
        banks.associate { bank ->
            val slots = arrayOfNulls<MotifXsFactoryVoice>(bank.slotCount)
            for (voice in bank.voices) {
                // 1-based in the Data List and in this file, 0-based everywhere in the app. The
                // out-of-range guard is not decoration: it is the only thing standing between a
                // mistyped slot number and an ArrayIndexOutOfBoundsException at connect time.
                val index = voice.slot - 1
                if (index in slots.indices) slots[index] = voice
            }
            bank.label to slots
        }
    }

    /** True where no names were loaded at all, which is what drops the factory listing entirely. */
    val isEmpty: Boolean get() = banks.isEmpty()

    /** The name at [slot0] of [bankLabel] (0-based), or null if this table does not carry one. */
    fun name(bankLabel: String, slot0: Int): String? = voice(bankLabel, slot0)?.name

    /**
     * The assignments at [slot0] of [bankLabel] (0-based), **by name**.
     *
     * Empty for a voice this table has no categories for. Resolving a name to an index is
     * [MotifXsCategoryEncoding]'s job, since that is the instrument's format rather than this
     * list's.
     */
    fun categories(bankLabel: String, slot0: Int): List<MotifXsFactoryCategory> =
        voice(bankLabel, slot0)?.categories.orEmpty()

    private fun voice(bankLabel: String, slot0: Int): MotifXsFactoryVoice? =
        byLabel[bankLabel]?.getOrNull(slot0)
}

/** One factory bank's voices. [slotCount] is carried so the table can be checked against the catalog. */
@Serializable
data class MotifXsFactoryBank(
    val label: String,
    val slotCount: Int,
    val voices: List<MotifXsFactoryVoice> = emptyList(),
)

/**
 * One voice: its **1-based** slot within the bank, its name, and its category assignments.
 *
 * [categories] is null where the table carries none, distinct from an empty list, which would
 * claim the voice genuinely has no assignment.
 */
@Serializable
data class MotifXsFactoryVoice(
    val slot: Int,
    val name: String,
    val categories: List<MotifXsFactoryCategory>? = null,
)

/** One assignment, by name. [sub] is null for "this main, no sub-category". */
@Serializable
data class MotifXsFactoryCategory(val main: String, val sub: String? = null)
