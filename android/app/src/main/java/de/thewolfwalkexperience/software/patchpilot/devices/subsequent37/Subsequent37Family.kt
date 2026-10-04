// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.catalog.CatalogLoader
import de.thewolfwalkexperience.software.patchpilot.catalog.FamilyCatalog
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentFamily
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.transportScope
import kotlinx.serialization.Serializable

/** Named for the one instrument it describes; the loader dispatches on the `family` field inside it. */
private const val CATALOG_ASSET = "moog_subsequent37.json"

/**
 * What the instrument's own front-panel Initialize produces, stored and read back: the program a
 * delete writes. Shipped rather than built, since 461 of its bytes are not decoded.
 */
private const val BLANK_ASSET = "subsequent37_panel_initialized.bin"

/** The typed form of the catalog's `familyConfig`. The layout is fixed by the protocol, not configured. */
@Serializable
data class Subsequent37Config(
    /** Firmware versions this app has been tested against; empty skips the check. */
    val supportedFirmwareVersions: Set<String> = setOf("1.2.0"),
)

/** The Subsequent 37 family: `devices/moog_subsequent37.json`, in the generic [FamilyCatalog] shape, plus a factory. */
object Subsequent37Family : InstrumentFamily {

    override val id = Subsequent37Instrument.FAMILY

    private val catalog = CatalogLoader(CATALOG_ASSET, FamilyCatalog.serializer())

    /** A whole bulk dump is 203,800 bytes, far over the framer's default. */
    private const val MAX_MESSAGE_BYTES = 256 * 1024

    override fun descriptors(context: Context): List<InstrumentDescriptor> =
        catalog.load(context).devices

    override fun create(
        context: Context,
        descriptor: InstrumentDescriptor,
        transport: Transport,
    ): Instrument {
        val midi = transport as? MidiTransport
            ?: error("A Subsequent 37 speaks MIDI SysEx, not ${transport::class.simpleName}.")
        // Resolved before the exchange starts its collector, so a catalog or asset fault cannot
        // leave a collector running on a transport nobody owns.
        val config = catalog.format.decodeFromJsonElement(
            Subsequent37Config.serializer(),
            catalog.load(context).familyConfig,
        )
        val blank = Subsequent37Program(context.assets.open(BLANK_ASSET).use { it.readBytes() })
        check(blank.checksumCloses && blank.isEmpty) { "$BLANK_ASSET is not an Init Preset with a closing checksum." }
        val scope = transportScope(descriptor.name)
        return Subsequent37Instrument(
            SysExExchange(midi, scope, framer = SysExFramer(MAX_MESSAGE_BYTES)),
            config,
            blank,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
        )
    }
}
