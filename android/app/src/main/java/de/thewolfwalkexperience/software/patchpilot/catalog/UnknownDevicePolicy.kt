// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument

/**
 * Which unrecognised USB devices may be opened on a guess, and which must be refused.
 *
 * The app can offer to try an *unrecognised* device against a family's protocol - a Nord model
 * newer than this catalog, say. That is only defensible where the guess has evidence behind it,
 * and this object is where "has evidence" is defined.
 *
 * ### Why Nord and nothing else
 *
 * **The asymmetry is structural.** Clavia's vendor id covers several models that this project has
 * verified share one protocol, so "another Clavia device probably speaks this too" is an inference
 * with evidence. Yamaha's and Behringer's ids cover one *verified* instrument each (the XS7 and
 * XS8 are catalogued on inferred product ids, not confirmed on a unit), and a Motif XS is no
 * evidence about Yamaha synths in general: its address map has a hole at `0x08` the instrument
 * rejects, and USER DR sits detached at `0x28` for no reason anyone has established.
 *
 * The rule: **guess a family from a vendor id only where two verified members have already
 * agreed.** [FAMILIES_ALLOWING_A_GUESS] is that rule applied by hand; revisit it if a second
 * Yamaha or Behringer instrument is verified and turns out to speak its sibling's protocol.
 *
 * Without this gate, the picker would offer every attached USB device, and picking a keyboard, a
 * hub or a charger would claim its interface and send Clavia's vendor bulk protocol at it -
 * unsolicited and far from read-only, since `NordDevice` carries destructive sub-opcodes.
 */
object UnknownDevicePolicy {

    /** Families whose protocol may be tried on an unrecognised device of the same vendor. */
    private val FAMILIES_ALLOWING_A_GUESS = setOf(NordInstrument.FAMILY)

    /**
     * Vendor ids for which [allows] returns true, read from [descriptors] rather than hardcoded.
     *
     * From the catalog so that adding a Nord model needs no change here, and so this cannot
     * drift from the ids the registry actually matches on.
     */
    fun vendorIdsAllowingAGuess(descriptors: List<InstrumentDescriptor>): Set<Int> =
        descriptors
            .filter { it.family in FAMILIES_ALLOWING_A_GUESS }
            .mapNotNull { (it.match as? DeviceMatch.Usb)?.vendorId }
            .toSet()

    /**
     * May an unrecognised device with [vendorId] be opened on a guess?
     *
     * **Fails closed.** An empty or unreadable catalog yields an empty set and refuses everything,
     * which is the safe direction for a decision whose entire job is to avoid talking to
     * strangers.
     */
    fun allows(descriptors: List<InstrumentDescriptor>, vendorId: Int): Boolean =
        vendorId in vendorIdsAllowingAGuess(descriptors)
}
