// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/*
 * What a family can say about one preset beyond what every row shows, for the Info dialog.
 */

/**
 * The channel messages that select a preset: Bank Select MSB (CC 0), Bank Select LSB (CC 32) and
 * Program Change, as the values on the wire, each 0..127.
 *
 * [bankMsb] and [bankLsb] are null where the instrument ignores that controller. Describes the
 * messages, not how this app selects: a family may select over SysEx and still answer these.
 */
data class MidiSelection(val bankMsb: Int?, val bankLsb: Int?, val program: Int) {
    init {
        require(bankMsb == null || bankMsb in 0..127) { "Bank Select MSB $bankMsb is outside 0..127" }
        require(bankLsb == null || bankLsb in 0..127) { "Bank Select LSB $bankLsb is outside 0..127" }
        require(program in 0..127) { "Program Change $program is outside 0..127" }
    }
}

/** One labelled value a family adds to the Info dialog. */
data class InfoRow(val label: String, val value: String)
