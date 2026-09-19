// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * Something plugged in that might be an instrument.
 *
 * [descriptor] is null for a device on a known bus that matches nothing in the catalog - which the
 * connect screen still offers, behind an "unrecognized, at your own risk" warning.
 *
 * [physicalKey] is what makes cross-bus deduplication possible, and it is needed: a Nord exposes a
 * class-compliant USB-MIDI interface (interface 2, endpoints 0x04/0x84) alongside the vendor
 * interface this app actually uses, so the same instrument turns up on both scans. Listing it
 * twice would put an entry in the picker that cannot manage presets at all. A candidate is
 * identified by the physical device, not by the port it was found through.
 */
data class Candidate(
    val descriptor: InstrumentDescriptor?,
    val displayName: String,
    val bus: Bus,
    val physicalKey: String,
    val handle: Any,
)

/** One bus's view of what is attached. */
interface DeviceDiscovery {
    val bus: Bus

    /** Candidates this bus can see, matched against [catalog] where possible. */
    suspend fun scan(catalog: List<InstrumentDescriptor>): List<Candidate>

    /** Opens one candidate, prompting for whatever permission the bus requires. */
    suspend fun open(candidate: Candidate): Transport
}

/**
 * Merges several buses' scans, keeping one entry per physical device.
 *
 * Order decides which bus wins for a device visible on both: the first discovery listed. The USB
 * host bus goes first, so a Nord is claimed through its vendor interface rather than through the
 * MIDI interface that cannot browse presets.
 */
fun mergeCandidates(perBus: List<List<Candidate>>): List<Candidate> {
    val seen = mutableSetOf<String>()
    return perBus.flatten().filter { seen.add(it.physicalKey) }
}
