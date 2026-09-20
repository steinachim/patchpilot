// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import android.content.Context
import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.transport.Transport

private const val TAG = "InstrumentRegistry"


/**
 * Everything the app needs to know about one instrument family: which devices it covers, and how
 * to build an [Instrument] once one of them is open. The whole extension point: a new family adds
 * an implementation of this and a catalog file, and an entry in [InstrumentRegistry], which is a
 * compile-time map rather than a plugin system (see docs/ARCHITECTURE.md, "Catalog and adding a
 * device family").
 */
interface InstrumentFamily {
    val id: String

    /** Every model this family covers, from its own catalog file. */
    fun descriptors(context: Context): List<InstrumentDescriptor>

    /**
     * Builds an instrument on an already-open transport. A family handed the wrong transport type
     * fails loudly here; that is a wiring bug in discovery.
     */
    fun create(context: Context, descriptor: InstrumentDescriptor, transport: Transport): Instrument
}

/**
 * Family id -> family, resolved at compile time. Order matters where more than one instrument is
 * connected: the first family with a match wins, as the Nord catalog's list order decides
 * between two connected Nords.
 */
object InstrumentRegistry {
    private val families: List<InstrumentFamily> by lazy {
        listOf(
            de.thewolfwalkexperience.software.patchpilot.devices.nord.NordFamily,
            de.thewolfwalkexperience.software.patchpilot.devices.pro800.Pro800Family,
            de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsFamily,
        )
    }

    fun family(id: String): InstrumentFamily =
        families.firstOrNull { it.id == id } ?: error("No support is compiled in for the '$id' family.")

    /** Every model every family covers, in family order. */
    fun allDescriptors(context: Context): List<InstrumentDescriptor> =
        families.flatMap { family ->
            // One family's catalog failing to load must not take the others down with it. Logged,
            // since the only other symptom is that family's instruments missing from the picker.
            try {
                family.descriptors(context)
            } catch (e: Exception) {
                Log.e(TAG, "The '${family.id}' catalog could not be loaded; its instruments will not be found", e)
                emptyList()
            }
        }

    fun create(context: Context, descriptor: InstrumentDescriptor, transport: Transport): Instrument =
        family(descriptor.family).create(context, descriptor, transport)
}
