// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * Renders a voice address the way the instrument's own display does: `USER 3 - D:05`, not a flat
 * slot number, since the Motif XS groups its banks into lettered sets of sixteen.
 */
class MotifXsAddressFormat(
    private val bankLabels: List<String>,
    private val displayLabels: List<String> = bankLabels,
) : AddressFormat {

    override fun format(address: SlotAddress): String {
        val group = 'A' + (address.slot / GROUP_SIZE)
        val within = (address.slot % GROUP_SIZE) + 1
        return "${displayLabel(address.bank)} - $group:${within.toString().padStart(2, '0')}"
    }

    /** The display label, which is what the rows carry and the bank headers group by. */
    override fun bankLabel(bank: Int): String = displayLabel(bank)

    private fun displayLabel(bank: Int): String =
        displayLabels.getOrElse(bank) { bankLabels.getOrElse(bank) { "?" } }

    private companion object {
        /** Sixteen slots per lettered group, what the instrument's own A..H buttons select. */
        const val GROUP_SIZE = 16
    }
}
