package de.thewolfwalkexperience.software.patchpilot.catalog

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.transport.Transport

/**
 * Everything the app needs to know about one instrument family: which devices it covers, and how
 * to build an [Instrument] once one of them is open.
 *
 * **This is the whole extension point.** A third family adds an implementation of this and a JSON
 * block; it changes no shared type. Deliberately not a service loader, a reflection-based plugin
 * system or a device-definition DSL - [InstrumentRegistry] is a compile-time map, and that ceiling
 * is a decision rather than an oversight (see docs/ARCHITECTURE.md, "Catalog and adding a new
 * device family"). The extension
 * point is already small enough that making it smaller has no payoff.
 */
interface InstrumentFamily {
    val id: String

    /** Every model this family covers, from its own catalog file. */
    fun descriptors(context: Context): List<InstrumentDescriptor>

    /**
     * Builds an instrument on an already-open transport.
     *
     * [transport] is the family-agnostic marker type, so a family that needs a
     * [de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport] and is handed a
     * [de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport] should fail loudly
     * here. That mismatch is a wiring bug in discovery, not a device problem.
     */
    fun create(context: Context, descriptor: InstrumentDescriptor, transport: Transport): Instrument
}

/**
 * Family id -> family, resolved at compile time.
 *
 * Order matters where more than one instrument is connected at once: the first family with a
 * match wins, as the Nord catalog's own list order decides between two connected Nords.
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
            // One family's catalog failing to load must not take the others down with it: a
            // malformed or missing asset for a family the user does not own would otherwise leave
            // the app unable to find the instrument they do.
            runCatching { family.descriptors(context) }.getOrElse { emptyList() }
        }

    fun create(context: Context, descriptor: InstrumentDescriptor, transport: Transport): Instrument =
        family(descriptor.family).create(context, descriptor, transport)
}
