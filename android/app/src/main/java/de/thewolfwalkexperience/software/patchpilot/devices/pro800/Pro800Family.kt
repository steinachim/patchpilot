package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.catalog.FamilyCatalog
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentFamily
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.transportScope
import kotlinx.serialization.Serializable
import de.thewolfwalkexperience.software.patchpilot.catalog.CatalogLoader

/** Named for the instrument it describes, not for a family of them - this file covers exactly
 * one model. The name carries no meaning to the loader; the `family` field inside it does. */
private const val CATALOG_ASSET = "behringer_pro800.json"

/**
 * The per-model configuration this family needs - the typed form of a [FamilyCatalog.familyConfig]
 * block.
 *
 * Everything here is Pro-800-specific and none of it exists on a Nord, which is exactly why it
 * lives in an opaque block rather than in the shared descriptor: a flat cross-family schema would
 * carry these as five more mostly-null columns.
 *
 * **Read from the catalog's top-level familyConfig, not the device's.** There is exactly one
 * Pro-800 catalog entry (see [Pro800Family]'s own doc), so there is no second device for a
 * per-device block to ever need to differ from - unlike the Motif XS catalog's shared bank table,
 * this has no per-device override to fall back past, which is why [Pro800Family.create] decodes it
 * straight off [FamilyCatalog.familyConfig] with no merge step.
 *
 * **Carries no id or name.** Those belong to [InstrumentDescriptor], which [Pro800Family.create]
 * already has on hand and passes straight to [Pro800Instrument] - putting them here too would
 * only be a second place for the same two strings to drift apart.
 */
@Serializable
data class Pro800Config(
    val bankCount: Int = 4,
    val slotsPerBank: Int = 100,
    val slotDigits: Int = 2,
    /**
     * Which MIDI channel to send bank select and program change on (0-based).
     *
     * A real configuration input with no Nord counterpart: the instrument's own `MIDI RX Channel`
     * setting can be ALL, dip-switch-driven, a fixed channel, or OFF, and sending on the wrong one
     * fails **silently** - there is no acknowledgement to notice its absence in. Once the settings
     * block at address 510 is readable, this can be derived from its byte 10 instead of
     * configured, including detecting OFF and saying so rather than presenting a dead button
     * (design section 7.6).
     */
    val midiChannel: Int = 0,
    /**
     * Firmware versions this app has been tested against. Empty means "skip the check".
     *
     * Mirrors `VersionMessage::SUPPORTED_FIRMWARE_VERSIONS` in the reference implementation, and
     * the same posture `NordDevice` takes: decline rather than risk mis-reading presets on
     * firmware whose layout nobody has verified.
     */
    val supportedFirmwareVersions: Set<String> = setOf("1.4.6"),
)

/**
 * The Pro-800 family: `devices/behringer_pro800.json` plus a factory.
 *
 * Its catalog is written in the generic [FamilyCatalog] shape directly, unlike the Nord one, which
 * keeps its own native shape because Python reads that file too. Both arrive at the registry
 * as descriptors either way - which is the point of letting each family load its own file.
 *
 * "Family" here is the code-level dispatch key, not a claim about how many instruments the file
 * holds: this one holds a single Pro-800, and is named accordingly.
 */
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
        val midi = transport as? MidiTransport
            ?: error("A Pro-800 speaks MIDI SysEx, not ${transport::class.simpleName}.")
        val config = catalog.format.decodeFromJsonElement(Pro800Config.serializer(), catalog.load(context).familyConfig)
        // The exchange owns the collector draining this transport, so its scope has to outlive
        // any single operation. It ends when the transport is closed and the instrument with it.
        val scope = transportScope(descriptor.name)
        return Pro800Instrument(
            SysExExchange(midi, scope),
            config,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
        )
    }
}
