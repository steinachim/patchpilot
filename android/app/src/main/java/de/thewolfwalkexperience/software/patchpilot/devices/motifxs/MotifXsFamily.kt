// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.content.Context
import de.thewolfwalkexperience.software.patchpilot.catalog.FamilyCatalog
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentDescriptor
import de.thewolfwalkexperience.software.patchpilot.catalog.InstrumentFamily
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import de.thewolfwalkexperience.software.patchpilot.catalog.DeviceMatch
import de.thewolfwalkexperience.software.patchpilot.transport.MidiTransport
import de.thewolfwalkexperience.software.patchpilot.transport.Transport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbMidiBulkTransport
import de.thewolfwalkexperience.software.patchpilot.transport.transportScope
import kotlinx.coroutines.CoroutineScope
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import de.thewolfwalkexperience.software.patchpilot.catalog.CatalogLoader

private const val CATALOG_ASSET = "yamaha_motif_xs.json"

/**
 * The factory voice-name table - see [MotifXsFactoryVoices]. A separate asset from the catalog:
 * 94 kB of transcribed reference data rather than configuration, and bundling them would carry
 * the whole table through every `InstrumentDescriptor.familyConfig`.
 */
private const val FACTORY_VOICES_ASSET = "motifxs_factory_voices.json"

/** One addressable bank of voices: what to call it, how many slots, and where it lives. */
@Serializable
data class MotifXsBank(
    val label: String,
    val slotCount: Int,
    val addressHi: Int,
    val addressMid: Int,
    /** What the instrument's own display calls this bank, e.g. `USER 3`; [label] is this project's internal shorthand. */
    val displayLabel: String = "",
    /** The two-or-so characters the bank index rail draws, e.g. `U1`. Not derived: `USER DR` shortens to `U DR`. */
    val shortLabel: String = "",
    /**
     * Whether the instrument refuses writes here. True for the factory banks.
     *
     * The browser does not offer an edit here, and the editor refuses one.
     */
    val readOnly: Boolean = false,
    /**
     * Whether this bank holds drum kits rather than normal voices, defaulted from the address:
     * the drum banks are `0x20` and above (`20`, `21`, `28`). Load-bearing, since a drum kit and
     * a normal voice are different objects (~12.6 kB against ~1.9 kB, different block sequences)
     * and the instrument accepts a payload of the wrong kind without complaint - this flag is the
     * only thing that refuses a copy or move across the boundary.
     */
    val isDrum: Boolean = addressMid >= MotifXsSysEx.FIRST_DRUM_BANK,
    /**
     * Whether a full index walks this bank. False for the factory banks: walking them would take
     * several minutes (extrapolated from the measured ~160 ms per normal voice and ~1 s per drum
     * kit, against the measured 93 s for the 416 user voices; the eleven banks have never been
     * walked end to end), and they are read-only and never change - their names ship with the app
     * instead (see [MotifXsFactoryVoices]).
     */
    val indexByDefault: Boolean = true,
    /**
     * This bank's bank-select LSB, which is not [addressMid]: they agree for PRE1 and USER DR and
     * differ for every user normal bank (USER 1 selects with `0x08` and dumps at `0x0A`). Null
     * where it is not confirmed, since a wrong value here selects another bank's voice and
     * reports success.
     */
    val selectLsb: Int? = null,
    /**
     * This bank's bank-select MSB, `0x3F` for every normal voice bank but `0x00` for GM and
     * `0x7F` for GM DR. PRE1, GM and GM DR share LSB `0x00`, so this byte is the only thing
     * separating them, and an unrecognised pair is ignored rather than refused.
     */
    val selectMsb: Int = MotifXsSysEx.BANK_MSB,
)

/**
 * The per-model configuration for this family. The bank table is catalog data, so a model that
 * lays its memory out differently is a catalog edit; [banks] defaults to empty, since a device
 * entry may inherit the family's shared table (see [resolvedConfig]). The id and name belong to
 * [InstrumentDescriptor].
 */
@Serializable
data class MotifXsConfig(
    /** The device number in the SysEx status byte. This app always uses 0; whether the
     * instrument answers others is untested. */
    val deviceNumber: Int = 0,
    val banks: List<MotifXsBank> = emptyList(),
    /**
     * How this instrument encodes a voice's category assignments - a property of the instrument
     * rather than of the shipped voice list. Null in a catalog that does not carry it, which
     * leaves the app without a tagging facet.
     */
    val categoryEncoding: MotifXsCategoryEncoding? = null,
)

/**
 * How the four category bytes at `0x18`-`0x1B` of a voice's Common block encode two assignments.
 * Catalog data, because the sub-category order is a hardware measurement that Yamaha's published
 * voice list contradicts (`Brass` prints `Orche, Solo, BrsEn` and indexes `Solo, BrsEn, Orche`).
 * The JSON's `mainSource`, `subSource` and `subNoAssignmentRule` say where each half came from
 * and are worth reading before changing this table; they are not decoded here.
 */
@Serializable
data class MotifXsCategoryEncoding(
    /** Main category by byte value, index 0 up. The last entry is `NoAsg`, meaning unassigned. */
    val mainByValue: List<String> = emptyList(),
    /** Sub-categories by byte value, keyed by main-category name. */
    val subByValuePerMain: Map<String, List<String>> = emptyMap(),
) {
    /** The taxonomy as everything above wants it: by index, in the main table's own order. */
    val taxonomy: CategoryTaxonomy by lazy {
        CategoryTaxonomy(
            mainByValue.map { name -> MainCategory(name, subByValuePerMain[name].orEmpty()) },
        )
    }

    /** The index of one assignment's main and sub by name, or null where the table lacks it. */
    fun refOf(main: String, sub: String?): CategoryRef? {
        val mainIndex = mainByValue.indexOf(main).takeIf { it >= 0 } ?: return null
        val subs = subByValuePerMain[main].orEmpty()
        return CategoryRef(mainIndex, sub?.let { subs.indexOf(it).takeIf { i -> i >= 0 } })
    }

    companion object {
        /** The main-category name this table uses for "no assignment". */
        const val NO_ASSIGNMENT = "NoAsg"
    }
}

/**
 * A device's [MotifXsConfig], with [MotifXsConfig.banks] filled in from the catalog's shared
 * [catalogFamilyConfig] where [deviceFamilyConfig] carries none: the XS6, XS7 and XS8 share one
 * memory map, and a device that does not simply gives its own entry a `banks` array.
 */
internal fun resolvedConfig(
    catalogFamilyConfig: JsonObject,
    deviceFamilyConfig: JsonObject,
    format: Json,
): MotifXsConfig {
    val own = format.decodeFromJsonElement(MotifXsConfig.serializer(), deviceFamilyConfig)
    val shared = format.decodeFromJsonElement(MotifXsConfig.serializer(), catalogFamilyConfig)
    // Per field, not all-or-nothing: a device that overrides its bank table still inherits the
    // family's category encoding, which is the same silicon whatever the memory map looks like.
    return own.copy(
        banks = own.banks.ifEmpty { shared.banks },
        categoryEncoding = own.categoryEncoding ?: shared.categoryEncoding,
    )
}

/** The payload written over a slot to erase it, one per kind of voice - real instrument data, not configuration. */
data class MotifXsBlanks(val normal: ByteArray, val drum: ByteArray) {
    fun forBank(bank: MotifXsBank): ByteArray = if (bank.isDrum) drum else normal

    // A data class over ByteArray needs these written out; the default ones compare identity.
    override fun equals(other: Any?): Boolean =
        this === other || (other is MotifXsBlanks &&
            normal.contentEquals(other.normal) && drum.contentEquals(other.drum))

    override fun hashCode(): Int = 31 * normal.contentHashCode() + drum.contentHashCode()
}

/**
 * The Motif XS family: `devices/yamaha_motif_xs.json` plus a factory.
 */
object MotifXsFamily : InstrumentFamily {

    override val id = MotifXsInstrument.FAMILY

    private val catalog = CatalogLoader(CATALOG_ASSET, FamilyCatalog.serializer())

    private val factoryVoices = CatalogLoader(FACTORY_VOICES_ASSET, MotifXsFactoryVoices.serializer())

    /**
     * A drum voice is ~12.6 kB, three times `SysExFramer.DEFAULT_MAX_MESSAGE_BYTES`, which would
     * drop every one as over-long. Set from what this instrument sends (largest known 12,622
     * bytes) with room for a bigger kit on a related model.
     */
    private const val MAX_MESSAGE_BYTES = 32768

    /**
     * Payloads written over a slot to erase it, one per kind of voice: each an initialised voice
     * whose 20 name bytes were cleared through the documented write path, read back through `0C`.
     * Shipped rather than copied from an empty slot at run time, which would fail on a fully
     * populated bank.
     */
    private const val BLANK_NORMAL = "motifxs_blank_normal.bin"
    private const val BLANK_DRUM = "motifxs_blank_drum.bin"

    override fun descriptors(context: Context): List<InstrumentDescriptor> =
        catalog.load(context).devices

    override fun create(
        context: Context,
        descriptor: InstrumentDescriptor,
        transport: Transport,
    ): Instrument {
        // Everything that can fail is resolved before the transport is wrapped: wrapping starts
        // the reader coroutine, and a throw after that point would leave it running on a transport
        // nobody owns.
        val config = resolvedConfig(catalog.load(context).familyConfig, descriptor.familyConfig, catalog.format)
        require(config.banks.isNotEmpty()) {
            "${descriptor.name} has no banks configured; the catalog entry is incomplete."
        }
        val blanks = MotifXsBlanks(
            normal = context.assets.open(BLANK_NORMAL).use { it.readBytes() },
            drum = context.assets.open(BLANK_DRUM).use { it.readBytes() },
        )
        val factoryVoices = loadFactoryVoices(context)
        val scope = transportScope(descriptor.name)
        val midi = midiOver(transport, descriptor, scope)
        return MotifXsInstrument(
            // Three attempts, not the default two: a dropped message on this bus is ordinary
            // (see UsbMidiBulkTransport's buffer note), and one voice lost out of 416 is a
            // visible hole in the browser. A backstop for genuinely lost transfers; the buffer
            // is what addresses the cause.
            SysExExchange(midi, scope, framer = SysExFramer(MAX_MESSAGE_BYTES), retries = 2),
            config,
            blanks,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
            factoryVoices = factoryVoices,
        )
    }

    /**
     * The factory name table, or an empty one if it cannot be read. Never throws, unlike the
     * blanks: `InstrumentRegistry.create` is not guarded, so a throw here would fail the whole
     * connect, while a missing table only drops the factory listing. Delete and move genuinely
     * cannot work without the blanks.
     */
    private fun loadFactoryVoices(context: Context): MotifXsFactoryVoices =
        runCatching { factoryVoices.load(context) }.getOrElse { MotifXsFactoryVoices() }

    /**
     * The instrument speaks MIDI SysEx over raw bulk endpoints: it declares a single
     * vendor-specific interface, so `MidiManager` never enumerates it and the packets are packed
     * by hand ([UsbMidiBulkTransport]). A [MidiTransport] is still accepted, for a class-compliant
     * sibling.
     */
    private fun midiOver(
        transport: Transport,
        descriptor: InstrumentDescriptor,
        scope: CoroutineScope,
    ): MidiTransport = when (transport) {
        is MidiTransport -> transport
        is UsbBulkTransport -> {
            val cable = (descriptor.match as? DeviceMatch.Usb)?.midiCable
                ?: error("${descriptor.name} is on USB but its catalog entry sets no midiCable, " +
                    "so there is no way to know which cable its SysEx is on.")
            UsbMidiBulkTransport(transport, cable, scope)
        }
        else -> error("A Motif XS speaks MIDI SysEx, not ${transport::class.simpleName}.")
    }
}
