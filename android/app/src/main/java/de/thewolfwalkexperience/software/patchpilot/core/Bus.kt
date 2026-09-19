// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Which bus a session runs over.
 *
 * **A type rather than a label string**, because it is not only a label:
 * `InstrumentViewModel.discoveryFor()` picks the discovery to open a candidate with by comparing
 * it, and `ConnectScreen` picks its wording the same way. A string could carry "MIDI" on an
 * instrument found on the USB bus and nothing would notice.
 *
 * [label] is what the user sees.
 */
enum class Bus(val label: String) {
    /** The USB host bus - a claimed vendor or USB-MIDI interface this app drives itself. */
    USB("USB"),

    /** A port published by `android.media.midi`. */
    MIDI("MIDI"),

    /**
     * No bus: demo mode, and the fixtures that stand in for an instrument in tests.
     *
     * Its own value rather than a null [Bus], so that "which bus is this on?" always has an
     * answer and no caller has to handle an absent one.
     */
    NONE("none"),
}
