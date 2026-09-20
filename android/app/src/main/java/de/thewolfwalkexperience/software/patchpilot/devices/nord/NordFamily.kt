// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentFamily
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import de.thewolfwalkexperience.software.patchpilot.catalog.CatalogLoader

private const val DEVICE_CATALOG_ASSET = "nord_devices.json"

/**
 * The Nord family: `devices/nord_devices.json` plus a factory for [NordDevice]/[NordInstrument].
 * The catalog file keeps its own shape; each [DeviceProfile] becomes a descriptor whose
 * `familyConfig` is that profile serialized, which the factory reads straight back. List order
 * decides which device is preferred if more than one is connected.
 */
object NordFamily : InstrumentFamily {

    override val id = NordInstrument.FAMILY

    private val catalog = CatalogLoader(DEVICE_CATALOG_ASSET, DeviceCatalog.serializer())

    override fun descriptors(context: Context): List<InstrumentDescriptor> =
        catalog.load(context).devices.map { it.toDescriptor() }

    private fun DeviceProfile.toDescriptor() = InstrumentDescriptor(
        id = id,
        family = NordInstrument.FAMILY,
        name = name,
        match = DeviceMatch.Usb(vendorId, productId),
        familyConfig = catalog.format.encodeToJsonElement(this).jsonObject,
    )

    override fun create(
        context: Context,
        descriptor: InstrumentDescriptor,
        transport: Transport,
    ): Instrument {
        val bulk = transport as? UsbBulkTransport
            ?: error("A Nord speaks its vendor protocol over bulk USB, not ${transport::class.simpleName}.")
        // The master category list is family-level data; `load` is memoised.
        return NordInstrument(
            NordDevice(bulk, descriptor.profile()),
            catalog.load(context).programCategories,
        )
    }

    /** The profile [toDescriptor] stashed, read back with no field mapping. */
    private fun InstrumentDescriptor.profile(): DeviceProfile =
        catalog.format.decodeFromJsonElement(DeviceProfile.serializer(), familyConfig)
}
