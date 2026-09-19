package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Where a preset lives on an instrument, in the instrument's own terms: a bank and a slot within
 * it, both 0-based. This is the only thing the operations in [Instrument]'s facets take, and it is
 * deliberately *not* a display string.
 *
 * The split between this and [AddressFormat] already existed inside the Nord code before it was
 * named: `NordDevice`'s canonical pair is (bank, item), and "group" appears nowhere except in
 * `formatPresetId`/`parsePresetId`, where `item = (group - 1) * slotsPerGroup + (slot - 1)`. So a
 * Nord's `A:1:1` and a Pro-800's `A00` are two renderings of the same idea, and the difference
 * belongs in a formatter rather than in every caller.
 */
data class SlotAddress(val bank: Int, val slot: Int) {
    init {
        require(bank >= 0) { "bank must not be negative, was $bank" }
        require(slot >= 0) { "slot must not be negative, was $slot" }
    }
}

/**
 * Renders and parses the ids an instrument's own UI and manual use, so that nothing above this
 * has to know what they look like.
 *
 * The UI must never take an id apart itself: recovering a bank letter with
 * `presetId.substringBefore(':')` is correct for `A:1:1` and silently wrong for `A00` - hence
 * [PresetSlot.bankLabel], which travels on the row rather than being re-derived.
 */
interface AddressFormat {
    /** e.g. `"A:1:1"` on a Nord Grand, `"A00"` on a Pro-800. */
    fun format(address: SlotAddress): String

    /** Inverse of [format]. Throws [IllegalArgumentException] on anything it does not recognize. */
    fun parse(id: String): SlotAddress

    /** The bank's own label, e.g. `"A"` - what bank headers and the fast-scroll index show. */
    fun bankLabel(bank: Int): String
}

/**
 * The Nord rendering: `bank:group:slot`, all 1-based in display form except the bank, which is a
 * letter. A bank's slots are presented as [groupsPerBank] groups of [slotsPerGroup], matching the
 * instrument's own front-panel group/slot buttons.
 *
 * A straight lift of `NordDevice.formatPresetId`/`parsePresetId`, which keep working unchanged -
 * this exists so the *domain* can format an address without going through a connected device.
 */
class GroupedBankAddressFormat(
    private val groupsPerBank: Int,
    private val slotsPerGroup: Int,
) : AddressFormat {

    override fun format(address: SlotAddress): String {
        val group = address.slot / slotsPerGroup
        val slot = address.slot % slotsPerGroup
        return "${bankLabel(address.bank)}:${group + 1}:${slot + 1}"
    }

    override fun parse(id: String): SlotAddress {
        val match = PATTERN.matchEntire(id.trim())
            ?: throw IllegalArgumentException("Invalid preset id '$id'; expected a form like 'A:1:1'")
        val (bankLetter, groupText, slotText) = match.destructured
        val group = groupText.toInt()
        val slot = slotText.toInt()
        require(group in 1..groupsPerBank) {
            "Invalid preset id '$id': group $group is outside 1..$groupsPerBank"
        }
        require(slot in 1..slotsPerGroup) {
            "Invalid preset id '$id': slot $slot is outside 1..$slotsPerGroup"
        }
        return SlotAddress(
            bank = bankLetter.uppercase()[0] - 'A',
            slot = (group - 1) * slotsPerGroup + (slot - 1),
        )
    }

    override fun bankLabel(bank: Int): String = ('A' + bank).toString()

    private companion object {
        val PATTERN = Regex("""([A-Za-z]):0*(\d+):0*(\d+)""")
    }
}

/**
 * The Pro-800 rendering: a bank letter followed by a zero-padded slot number within the bank,
 * e.g. `A00`-`D99`. No group level exists, because the instrument has none - its presets are one
 * flat run of numbers that its own display splits into hundreds.
 */
class FlatBankAddressFormat(private val slotDigits: Int) : AddressFormat {

    override fun format(address: SlotAddress): String =
        bankLabel(address.bank) + address.slot.toString().padStart(slotDigits, '0')

    override fun parse(id: String): SlotAddress {
        val trimmed = id.trim()
        val match = PATTERN.matchEntire(trimmed)
            ?: throw IllegalArgumentException("Invalid preset id '$id'; expected a form like 'A00'")
        val (bankLetter, slotText) = match.destructured
        return SlotAddress(bank = bankLetter.uppercase()[0] - 'A', slot = slotText.toInt())
    }

    override fun bankLabel(bank: Int): String = ('A' + bank).toString()

    private companion object {
        val PATTERN = Regex("""([A-Za-z])(\d+)""")
    }
}

/** One bank: what to call it, and how many slots it holds. */
data class BankSpec(
    val label: String,
    val slotCount: Int,
    /**
     * What the bank index rail draws, where [label] is too wide for it.
     *
     * The rail is 20.dp - room for one or two characters - because for a Nord a bank *is* a
     * single letter. A Motif XS bank is `USER DR`, which wraps to three stacked lines and is
     * unreadable. Defaults to [label], so an instrument whose labels already fit says nothing.
     */
    val shortLabel: String = label,
    /**
     * Whether the instrument refuses writes here - true for a factory bank.
     *
     * **On the bank rather than on [PresetSlot], deliberately.** A row-level flag would have to be
     * set at every construction site in every family, and the one that forgot would default to
     * "writable" - failing in the direction that offers a Delete the instrument will reject.
     * Read-only-ness is a property of the *bank* in every family that has one, so stating it once
     * on the layout means nothing downstream can forget it: which addresses a copy may target and
     * which rows offer an edit both derive from here.
     *
     * It is also what separates [PresetScope.USER] from [PresetScope.FACTORY] - a read-only bank
     * is a factory bank - so there is no second field to keep inverted against this one.
     */
    val readOnly: Boolean = false,
)

/**
 * An instrument's whole addressable preset space, plus how to render an address in it.
 *
 * Note that bank count is a property of the *program* area specifically, not of the instrument as
 * a whole: a Nord Stage 2 EX addresses programs in four banks (A-D) while giving its `Piano`
 * category six children. `NordDevice.categoryBankCount()` is what bounds a walk of some other
 * category; this type describes the program space the browser screen shows.
 */
data class SlotLayout(
    val banks: List<BankSpec>,
    val format: AddressFormat,
) {
    val bankCount: Int get() = banks.size

    /** Every address this instrument can hold a preset at, in device order. */
    fun allAddresses(): Sequence<SlotAddress> = sequence {
        banks.forEachIndexed { bank, spec ->
            for (slot in 0 until spec.slotCount) yield(SlotAddress(bank, slot))
        }
    }

    val slotCount: Int get() = banks.sumOf { it.slotCount }

    companion object {
        /** [bankCount] identical banks of [slotsPerBank], the shape every instrument so far has. */
        fun uniform(bankCount: Int, slotsPerBank: Int, format: AddressFormat): SlotLayout =
            SlotLayout(
                banks = (0 until bankCount).map { BankSpec(format.bankLabel(it), slotsPerBank) },
                format = format,
            )
    }
}
