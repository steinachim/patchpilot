// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

/**
 * The 22 categories byte 50 can hold, and [UNSET] for none.
 *
 * The value is the category's position in the front panel's list counted from the end: the
 * panel lists `User` first and `Misc` last, and byte 50 holds `Misc` as 0 and `User` as 21.
 * **Assumption:** `Vox` (`0x11`) and `Misc` (`0x00`) were read off the panel; the other 20
 * values follow from the panel's order and agree with every preset name in one 256-program
 * library, but were not each read off the instrument.
 *
 * [UNSET] (`0x77`) is what Initialize leaves. The panel shows it as `Misc`, like value 0.
 */
object Subsequent37Categories {

    /** In the front panel's order, which is the order the app lists them in. */
    val PANEL_ORDER = listOf(
        "User", "Percussion", "Sequence", "Arp", "Vox", "Distorted", "Evolving", "Pads",
        "Strings", "Sync", "Organs", "Keys", "Sound FX", "Woodwind", "Brass", "Lead", "Bass",
        "Noise", "Drone", "Duo", "Classic", "Misc",
    )

    const val UNSET = 0x77

    /** The byte-50 value of the category at [panelIndex] in [PANEL_ORDER]. */
    fun valueOf(panelIndex: Int): Int {
        require(panelIndex in PANEL_ORDER.indices) { "No category at $panelIndex." }
        return PANEL_ORDER.size - 1 - panelIndex
    }

    /** The [PANEL_ORDER] index for byte-50 [value], or null for [UNSET] or a value with no category. */
    fun panelIndexOf(value: Int): Int? =
        if (value in PANEL_ORDER.indices) PANEL_ORDER.size - 1 - value else null

    fun nameOf(value: Int): String? = panelIndexOf(value)?.let { PANEL_ORDER[it] }

    fun isStorable(value: Int): Boolean = value in PANEL_ORDER.indices || value == UNSET
}
