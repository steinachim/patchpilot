// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument

/**
 * Which unrecognised USB devices may be opened on a guess against a family's protocol (a Nord
 * model newer than the catalog, say), and which must be refused.
 *
 * The rule: guess a family from a vendor id only where two verified members already share one
 * protocol. Clavia's vendor id covers several verified models; Yamaha's and Behringer's cover one
 * verified instrument each (the XS7 and XS8 are catalogued on inferred product ids), which is no
 * evidence about the vendor's other synths. [FAMILIES_ALLOWING_A_GUESS] is that rule applied by
 * hand; revisit it if a second Yamaha or Behringer instrument is verified on its sibling's
 * protocol.
 *
 * Without this gate, picking a keyboard, a hub or a charger would claim its interface and send
 * Clavia's vendor protocol - which carries destructive sub-opcodes - at it.
 */
object UnknownDevicePolicy {

    /** Families whose protocol may be tried on an unrecognised device of the same vendor. */
    private val FAMILIES_ALLOWING_A_GUESS = setOf(NordInstrument.FAMILY)

    /** Vendor ids for which [allows] returns true, read from the catalog so adding a Nord model needs no change here. */
    fun vendorIdsAllowingAGuess(descriptors: List<InstrumentDescriptor>): Set<Int> =
        descriptors
            .filter { it.family in FAMILIES_ALLOWING_A_GUESS }
            .mapNotNull { (it.match as? DeviceMatch.Usb)?.vendorId }
            .toSet()

    /** May an unrecognised device with [vendorId] be opened on a guess? Fails closed on an empty catalog. */
    fun allows(descriptors: List<InstrumentDescriptor>, vendorId: Int): Boolean =
        vendorId in vendorIdsAllowingAGuess(descriptors)
}
