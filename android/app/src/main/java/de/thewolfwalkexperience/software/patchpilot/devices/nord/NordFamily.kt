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
 *
 * **The catalog file is read in its own native shape, unchanged**, rather than rewritten into the
 * generic [InstrumentDescriptor] shape. So this adapts instead: each [DeviceProfile] becomes a
 * descriptor whose `familyConfig` is that same profile serialized, which the factory below reads
 * straight back. One file per family, merged at load time by
 * [de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentRegistry].
 *
 * List order also decides which device is preferred if more than one supported instrument is
 * connected at once.
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
        return NordInstrument(NordDevice(bulk, descriptor.profile()))
    }

    /** The profile [toDescriptor] stashed, read back with no field mapping - the two are the same
     * type, which is the point of carrying it opaquely. */
    private fun InstrumentDescriptor.profile(): DeviceProfile =
        catalog.format.decodeFromJsonElement(DeviceProfile.serializer(), familyConfig)
}
