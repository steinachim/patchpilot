// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout

/**
 * The Subsequent 37 rendering: `B01/P01`–`B16/P16`, its panel's BANK and PRESET buttons, both
 * 1-based. The instrument's own program number is `bank × 16 + slot`, both 0-based.
 */
object Subsequent37AddressFormat : AddressFormat {

    const val BANKS = 16
    const val SLOTS_PER_BANK = 16

    override fun format(address: SlotAddress): String =
        "B${two(address.bank + 1)}/P${two(address.slot + 1)}"

    /** The number alone, as the panel's BANK buttons show it; the list's header adds the word. */
    override fun bankLabel(bank: Int): String = "${bank + 1}"

    /** The instrument's program number for [address], 0..255. */
    fun numberOf(address: SlotAddress): Int {
        require(address.bank in 0 until BANKS && address.slot in 0 until SLOTS_PER_BANK) {
            "$address is outside 16 banks of 16"
        }
        return address.bank * SLOTS_PER_BANK + address.slot
    }

    fun addressOf(number: Int): SlotAddress {
        require(number in 0 until BANKS * SLOTS_PER_BANK) { "Program $number is outside 0..255" }
        return SlotAddress(number / SLOTS_PER_BANK, number % SLOTS_PER_BANK)
    }

    /** Sixteen banks of sixteen; the index rail shows the bank number alone. */
    val layout: SlotLayout = SlotLayout(
        banks = (0 until BANKS).map {
            BankSpec(label = bankLabel(it), slotCount = SLOTS_PER_BANK, shortLabel = "${it + 1}")
        },
        format = this,
    )

    /** Not `"%02d".format`, whose digits follow the device locale. */
    private fun two(value: Int): String = value.toString().padStart(2, '0')
}
