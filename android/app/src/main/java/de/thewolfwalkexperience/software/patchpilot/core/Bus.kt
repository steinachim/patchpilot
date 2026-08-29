package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Which bus a session runs over.
 *
 * **A type rather than the `String` this used to be**, because the string was never only a label:
 * `InstrumentViewModel.discoveryFor()` picks the discovery to open a candidate with by comparing
 * it, and `ConnectScreen` picks its wording the same way. Two of those comparisons decide
 * behaviour, and none of them could be checked.
 *
 * That cost something real. A Motif XS is matched on [Bus.USB] - its catalog entry is a `usb`
 * match, and `InstrumentDescriptor` records why: it exposes no MIDIStreaming interface and gets no
 * MIDI port at all. Its identity nevertheless reported `"MIDI"`, hardcoded, because the match moved
 * to USB and the string did not follow. Nothing could notice: `Candidate.busLabel` said `"USB"` and
 * `InstrumentIdentity.busLabel` said `"MIDI"` for one session, and both were just strings.
 *
 * [label] is what the user sees, so the displayed wording is unchanged.
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
