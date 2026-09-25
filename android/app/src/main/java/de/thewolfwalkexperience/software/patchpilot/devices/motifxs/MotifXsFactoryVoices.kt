// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import kotlinx.serialization.Serializable

/**
 * The names and category assignments of the instrument's 1,217 read-only voices, by bank and slot.
 *
 * Transcribed from Yamaha's own Data List rather than read off the wire: the eleven factory banks
 * never change, and reading them over MIDI would take several minutes (extrapolated from the
 * measured ~160 ms per voice and ~1 s per drum kit; the walk has never been timed end to end).
 * `devices/blanks/README.md`'s rule about fabricated wire bytes does not apply - these are
 * display strings the app never transmits, so a wrong entry is a wrong label, and the browser
 * says where the names came from.
 *
 * The categories are here so that the factory listing costs no round trips at all (see
 * `MotifXsInstrument.indexFactory`), even though reading them is cheap
 * ([MotifXsVoice.categoriesOf]). They are stored by name; turning a name into an index needs
 * [MotifXsCategoryEncoding], which describes the instrument and lives in the device catalog.
 * PREDR's and GMDR's came from an instrument, since Yamaha's drum voice list has no category
 * columns.
 *
 * Bank labels match `MotifXsBank.label`, which `MotifXsFactoryVoicesTest` pins along with every
 * bank carrying categories for every voice.
 */
@Serializable
data class MotifXsFactoryVoices(
    val banks: List<MotifXsFactoryBank> = emptyList(),
) {
    /**
     * Slot names per bank label, built once: an array indexed by slot rather than a map keyed by a
     * pair, since a factory listing asks for all 1,217 in a row. A short bank reads null past its
     * end, so a table that disagrees with the catalog yields an unnamed row rather than an
     * exception.
     */
    private val byLabel: Map<String, Array<MotifXsFactoryVoice?>> by lazy {
        banks.associate { bank ->
            val slots = arrayOfNulls<MotifXsFactoryVoice>(bank.slotCount)
            for (voice in bank.voices) {
                // 1-based in the Data List and in this file, 0-based in the app; the guard keeps
                // a mistyped slot number out of an ArrayIndexOutOfBoundsException at connect time.
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

    /** The assignments at [slot0] of [bankLabel] (0-based), by name; empty where this table has none. */
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
 * One voice: its 1-based slot within the bank, its name, and its category assignments.
 * [categories] is null where the table carries none, distinct from an empty list, which would
 * claim the voice has no assignment.
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
