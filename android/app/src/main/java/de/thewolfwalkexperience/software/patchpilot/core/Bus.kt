// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Which bus a session runs over. A type rather than a label string, because
 * `InstrumentViewModel.discoveryFor()` picks the discovery to open a candidate with by comparing
 * it. [label] is what the user sees.
 */
enum class Bus(val label: String) {
    /** The USB host bus - a claimed vendor or USB-MIDI interface this app drives itself. */
    USB("USB"),

    /** A port published by `android.media.midi`. */
    MIDI("MIDI"),

    /** No bus: demo mode, and the fixtures that stand in for an instrument in tests. */
    NONE("none"),
}
