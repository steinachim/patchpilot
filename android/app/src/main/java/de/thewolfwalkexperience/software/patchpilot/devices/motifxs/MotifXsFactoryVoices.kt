package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import kotlinx.serialization.Serializable

/**
 * The names of the instrument's 1,217 read-only voices, by bank and slot.
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
    private val byLabel: Map<String, Array<String?>> by lazy {
        banks.associate { bank ->
            val slots = arrayOfNulls<String>(bank.slotCount)
            for (voice in bank.voices) {
                // 1-based in the Data List and in this file, 0-based everywhere in the app. The
                // out-of-range guard is not decoration: it is the only thing standing between a
                // mistyped slot number and an ArrayIndexOutOfBoundsException at connect time.
                val index = voice.slot - 1
                if (index in slots.indices) slots[index] = voice.name
            }
            bank.label to slots
        }
    }

    /** True where no names were loaded at all, which is what drops the factory listing entirely. */
    val isEmpty: Boolean get() = banks.isEmpty()

    /** The name at [slot0] of [bankLabel] (0-based), or null if this table does not carry one. */
    fun name(bankLabel: String, slot0: Int): String? = byLabel[bankLabel]?.getOrNull(slot0)
}

/** One factory bank's names. [slotCount] is carried so the table can be checked against the catalog. */
@Serializable
data class MotifXsFactoryBank(
    val label: String,
    val slotCount: Int,
    val voices: List<MotifXsFactoryVoice> = emptyList(),
)

/** One voice: its **1-based** slot within the bank, and its name. */
@Serializable
data class MotifXsFactoryVoice(val slot: Int, val name: String)
