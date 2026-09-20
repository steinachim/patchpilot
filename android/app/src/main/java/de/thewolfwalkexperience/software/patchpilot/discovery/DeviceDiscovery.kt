// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.core.Bus

/**
 * Something plugged in that might be an instrument.
 *
 * [descriptor] is null for a device that matches nothing in the catalog; the connect screen
 * offers it behind an "unrecognized, at your own risk" warning.
 *
 * [physicalKey] identifies the physical device rather than the port it was found through, for
 * cross-bus deduplication: a Nord exposes a class-compliant USB-MIDI interface alongside the
 * vendor interface this app uses, so it turns up on both scans.
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
 * Merges several buses' scans, keeping one entry per physical device; the first bus listed wins.
 * The USB host bus goes first, so a Nord is claimed through its vendor interface rather than
 * through the MIDI interface that cannot browse presets.
 */
fun mergeCandidates(perBus: List<List<Candidate>>): List<Candidate> {
    val seen = mutableSetOf<String>()
    return perBus.flatten().filter { seen.add(it.physicalKey) }
}
