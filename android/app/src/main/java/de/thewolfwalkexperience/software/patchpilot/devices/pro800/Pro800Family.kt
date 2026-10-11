// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.catalog.CatalogLoader
import de.thewolfwalkexperience.software.patchpilot.catalog.FamilyCatalog
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentFamily
import de.thewolfwalkexperience.software.patchpilot.catalog.familyConfig
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.midi.familyExchange
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import kotlinx.serialization.Serializable

/** Named for the one instrument it describes; the loader dispatches on the `family` field inside it. */
private const val CATALOG_ASSET = "behringer_pro800.json"

/**
 * The configuration this family needs, the typed form of the catalog's top-level
 * [FamilyCatalog.familyConfig] block. There is one Pro-800 entry, so there is no per-device
 * override. The id and name belong to [InstrumentDescriptor].
 */
@Serializable
data class Pro800Config(
    val bankCount: Int = 4,
    val slotsPerBank: Int = 100,
    val slotDigits: Int = 2,
    /** Firmware versions this app has been tested against; empty skips the check. */
    val supportedFirmwareVersions: Set<String> = setOf("1.4.6"),
)

/** The Pro-800 family: `devices/behringer_pro800.json`, in the generic [FamilyCatalog] shape, plus a factory. */
object Pro800Family : InstrumentFamily {

    override val id = Pro800Instrument.FAMILY

    private val catalog = CatalogLoader(CATALOG_ASSET, FamilyCatalog.serializer())

    override fun descriptors(context: Context): List<InstrumentDescriptor> =
        catalog.load(context).devices

    override fun create(
        context: Context,
        descriptor: InstrumentDescriptor,
        transport: Transport,
    ): Instrument {
        // Resolved before the exchange - see familyExchange.
        val config = catalog.familyConfig(context, Pro800Config.serializer())
        return Pro800Instrument(
            familyExchange(transport, "A Pro-800", descriptor.name),
            config,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
        )
    }
}
