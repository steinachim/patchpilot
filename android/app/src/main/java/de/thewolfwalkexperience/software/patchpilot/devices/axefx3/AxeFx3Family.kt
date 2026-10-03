// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

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
private const val CATALOG_ASSET = "fractal_axefx3.json"

/**
 * What Axe-Edit III writes to delete a preset, recorded from the editor: 16 chunks of fill data
 * and an end marker of `00 00 00`. The instrument takes it as an erase rather than storing it.
 * Shipped, because a dump cannot be built (see [AxeFx3SysEx]).
 */
private const val EMPTY_PRESET_ASSET = "axefx3_empty_preset.bin"

/**
 * The typed form of the catalog's `familyConfig`. The preset count is not here: the unit reports
 * it at connect (512 on a Mark I, 1024 on a Mark II).
 */
@Serializable
data class AxeFx3Config(
    /** The size of the virtual banks the index rail jumps between; the instrument has no banks. */
    val presetsPerBank: Int = 128,
    /** Firmware versions this app has been tested against; empty skips the check. */
    val supportedFirmwareVersions: Set<String> = setOf("32.06"),
    /** Preset counts, i.e. models, this app has been tested against; another raises the advisory. */
    val testedPresetCounts: Set<Int> = setOf(AxeFx3SysEx.PRESET_COUNT_MARK_I),
    /** Whether an edit loads the previously loaded preset again once it is done. */
    val restoreLoadedPresetAfterEdit: Boolean = true,
)

/** The Axe-Fx III family: `devices/fractal_axefx3.json`, in the generic [FamilyCatalog] shape, plus a factory. */
object AxeFx3Family : InstrumentFamily {

    override val id = AxeFx3Instrument.FAMILY

    private val catalog = CatalogLoader(CATALOG_ASSET, FamilyCatalog.serializer())

    /**
     * A whole dump chunk is 3,082 bytes, under the framer's default; stated explicitly so a change
     * to that default cannot start dropping chunks.
     */
    private const val MAX_MESSAGE_BYTES = 4096

    override fun descriptors(context: Context): List<InstrumentDescriptor> =
        catalog.load(context).devices

    override fun create(
        context: Context,
        descriptor: InstrumentDescriptor,
        transport: Transport,
    ): Instrument {
        val midi = transport as? MidiTransport
            ?: error("An Axe-Fx III speaks MIDI SysEx, not ${transport::class.simpleName}.")
        // Resolved before the exchange starts its collector, so a catalog or asset fault cannot
        // leave a collector running on a transport nobody owns.
        val config = catalog.format.decodeFromJsonElement(AxeFx3Config.serializer(), catalog.load(context).familyConfig)
        val emptyPreset = AxeFx3DumpImage.parse(context.assets.open(EMPTY_PRESET_ASSET).use { it.readBytes() })
        val scope = transportScope(descriptor.name)
        return AxeFx3Instrument(
            SysExExchange(midi, scope, framer = SysExFramer(MAX_MESSAGE_BYTES)),
            config,
            emptyPreset,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
        )
    }
}
