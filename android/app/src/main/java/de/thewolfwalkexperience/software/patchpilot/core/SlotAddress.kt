// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Where a preset lives on an instrument: a bank and a slot within it, both 0-based. The only
 * thing the operations in [Instrument]'s facets take, and never a display string: a Nord's
 * `A:1:1` and a Pro-800's `A00` are two renderings of the same pair, and the rendering belongs
 * to an [AddressFormat].
 */
data class SlotAddress(val bank: Int, val slot: Int) {
    init {
        require(bank >= 0) { "bank must not be negative, was $bank" }
        require(slot >= 0) { "slot must not be negative, was $slot" }
    }
}

/**
 * Renders and parses the ids an instrument's own UI and manual use, so that nothing above this
 * has to know what they look like. The UI never takes an id apart itself; [PresetSlot.bankLabel]
 * travels on the row instead.
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
 * instrument's own front-panel group/slot buttons. The same rendering as
 * `NordDevice.formatPresetId`/`parsePresetId`, without needing a connected device.
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

/**
 * The Axe-Fx III rendering: one run of preset numbers, `000`-`511`, with no bank in the id,
 * because the instrument has none. The app still groups the run into [presetsPerBank]-sized
 * virtual banks, so the index rail has somewhere to jump to; their labels are number ranges
 * (`000–127`), never letters. The digit count follows from [presetCount].
 */
class LinearAddressFormat(
    private val presetCount: Int,
    private val presetsPerBank: Int,
) : AddressFormat {

    init {
        require(presetCount > 0) { "presetCount must be positive, was $presetCount" }
        require(presetsPerBank > 0) { "presetsPerBank must be positive, was $presetsPerBank" }
    }

    private val digits = (presetCount - 1).toString().length

    val bankCount: Int get() = (presetCount + presetsPerBank - 1) / presetsPerBank

    /** The instrument's own preset number for [address]. Throws for one outside the run. */
    fun numberOf(address: SlotAddress): Int {
        require(address.slot < presetsPerBank) {
            "slot ${address.slot} is outside a bank of $presetsPerBank"
        }
        val number = address.bank * presetsPerBank + address.slot
        require(number < presetCount) { "preset $number is outside 0..${presetCount - 1}" }
        return number
    }

    fun addressOf(number: Int): SlotAddress {
        require(number in 0 until presetCount) { "preset $number is outside 0..${presetCount - 1}" }
        return SlotAddress(bank = number / presetsPerBank, slot = number % presetsPerBank)
    }

    override fun format(address: SlotAddress): String = render(address.bank * presetsPerBank + address.slot)

    override fun parse(id: String): SlotAddress {
        val trimmed = id.trim()
        require(PATTERN.matches(trimmed)) { "Invalid preset id '$id'; expected a number like '042'" }
        val number = trimmed.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid preset id '$id'")
        require(number in 0 until presetCount) {
            "Invalid preset id '$id': outside 0..${presetCount - 1}"
        }
        return addressOf(number)
    }

    /** The range a virtual bank covers, e.g. `"000–127"`; the last bank stops at the last preset. */
    override fun bankLabel(bank: Int): String {
        val first = bank * presetsPerBank
        val last = minOf(first + presetsPerBank, presetCount) - 1
        return "${render(first)}–${render(last)}"
    }

    /** The bank's first number, which is what the index rail has room for. */
    fun railLabel(bank: Int): String = render(bank * presetsPerBank)

    /** Every virtual bank, flagged [BankSpec.virtual] so a header shows the range on its own. */
    fun layout(): SlotLayout = SlotLayout(
        banks = (0 until bankCount).map { bank ->
            BankSpec(
                label = bankLabel(bank),
                slotCount = minOf(presetsPerBank, presetCount - bank * presetsPerBank),
                shortLabel = railLabel(bank),
                virtual = true,
            )
        },
        format = this,
    )

    private fun render(number: Int): String = number.toString().padStart(digits, '0')

    private companion object {
        val PATTERN = Regex("""\d+""")
    }
}

/** One bank: what to call it, and how many slots it holds. */
data class BankSpec(
    val label: String,
    val slotCount: Int,
    /**
     * What the bank index rail draws where [label] is too wide for it: the rail has room for one
     * or two characters, and a Motif XS bank is called `USER DR`. Defaults to [label].
     */
    val shortLabel: String = label,
    /**
     * Whether the instrument refuses writes here - true for a factory bank.
     *
     * On the bank rather than on [PresetSlot]: read-only-ness is a property of the bank in every
     * family that has one, so which addresses a copy may target and which rows offer an edit both
     * derive from this one field. A read-only bank is also what [PresetScope.FACTORY] lists.
     */
    val readOnly: Boolean = false,
    /**
     * Whether the bank is the app's own grouping of a flat numbering rather than something the
     * instrument has. Its [label] is then a number range, and its header shows that range without
     * calling it a bank.
     */
    val virtual: Boolean = false,
)

/**
 * An instrument's whole addressable preset space, plus how to render an address in it.
 *
 * The bank count is the program area's, not the instrument's: a Nord Stage 2 EX addresses
 * programs in four banks (A-D) while giving its `Piano` category six children.
 * `NordDevice.categoryBankCount()` bounds a walk of another category.
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
