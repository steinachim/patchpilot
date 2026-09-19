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
 * The factory voice-name table - see [MotifXsFactoryVoices] for why it ships rather than being read.
 *
 * A separate asset from the catalog, because it is a different kind of thing: the catalog is
 * configuration a person edits, this is 94 kB of transcribed reference data. Bundling them would
 * mean every device entry carried the whole table through `InstrumentDescriptor.familyConfig`.
 */
private const val FACTORY_VOICES_ASSET = "motifxs_factory_voices.json"

/** One addressable bank of voices: what to call it, how many slots, and where it lives. */
@Serializable
data class MotifXsBank(
    val label: String,
    val slotCount: Int,
    val addressHi: Int,
    val addressMid: Int,
    /**
     * What the instrument's own display calls this bank, e.g. `USER 3` or `USER DR`.
     *
     * Separate from [label] because they are different things: [label] is this project's own
     * internal shorthand, while this is what a player reads off the screen. Defaults to [label]
     * so a bank added without one still renders something.
     */
    val displayLabel: String = "",
    /**
     * The two-or-so characters the bank index rail draws, e.g. `U1` for `USER 1`.
     *
     * Not derived from [displayLabel]: `USER DR` shortens to `U DR`, not `UD`, and no rule that
     * produces one produces the other. Defaults to [displayLabel] when unset.
     */
    val shortLabel: String = "",
    /**
     * Whether the instrument refuses writes here. True for the factory banks.
     *
     * The browser does not offer an edit here, and the editor refuses one.
     */
    val readOnly: Boolean = false,
    /**
     * Whether this bank holds **drum kits** rather than normal voices.
     *
     * Defaulted from the address rather than configured, because the instrument's own bank map
     * puts the drum banks at `0x20` and above and the normal ones below it: the drum banks
     * offset by `0x20`, and the three drum banks are `20`, `21` and `28`. Overridable all the
     * same, since a derived device could in principle report something the map does not cover.
     *
     * **Load-bearing, not cosmetic.** A drum kit and a normal voice are different objects - ~12.6
     * kB against ~1.9 kB, and completely different documented block sequences - and the
     * instrument **accepts a payload of the wrong kind without complaint** (a normal voice
     * offered to `0C 28 00` is acknowledged, and a drum kit to `0C 0A 7F` likewise). So nothing
     * below this app will stop a copy or a move across that boundary; this flag is what does.
     */
    val isDrum: Boolean = addressMid >= MotifXsSysEx.FIRST_DRUM_BANK,
    /**
     * Whether a full index walks this bank.
     *
     * False for the factory banks. Indexing every one costs about **7.5 minutes** - 1,633 voices
     * at ~220 ms each, and ~1.05 s for every drum kit - against 93 s for the user banks alone.
     * They are read-only and never change, so paying that on every connect buys nothing; their
     * names ship with the app instead (see [MotifXsFactoryVoices]).
     */
    val indexByDefault: Boolean = true,
    /**
     * This bank's **bank-select LSB**, which is *not* [addressMid].
     *
     * They agree for PRE1 and USER DR and disagree for every user normal bank - USER 1 selects
     * with `0x08` and dumps at `0x0A`. Reusing one for the other selects a different bank
     * silently, so they are separate fields rather than one with an adjustment.
     *
     * Null where it is not known. Only banks with a confirmed bank-select LSB carry one here; the
     * rest are absent rather than guessed, because a plausible wrong value here selects somebody
     * else's voice and reports success.
     */
    val selectLsb: Int? = null,
    /**
     * This bank's **bank-select MSB**, `0x3F` for every normal voice bank.
     *
     * A field rather than a constant because it is not one: GM selects at `0x00` and GM DR at
     * `0x7F`. It matters more than its two exceptions suggest - PRE1, GM and GM DR share
     * bank-select LSB `0x00`, so this byte is the only thing separating them, and a wrong pair is
     * *ignored* rather than refused.
     */
    val selectMsb: Int = MotifXsSysEx.BANK_MSB,
)

/**
 * The per-model configuration for this family.
 *
 * The bank table is data rather than code because it is the part most likely to be wrong: a
 * different Motif XS - or a Motif XF - may lay its memory out differently. Correcting it should
 * be a catalog edit, not a code change.
 *
 * **Carries no id or name.** Those belong to [InstrumentDescriptor], which [MotifXsFamily.create]
 * already has on hand and passes straight to [MotifXsInstrument] - putting them here too would
 * only be a second place for the same two strings to drift apart.
 *
 * [banks] defaults to empty here rather than being required, because a device entry in the
 * catalog is free to omit it and inherit [resolvedConfig]'s shared one instead - see there for
 * why the XS6/7/8 do.
 */
@Serializable
data class MotifXsConfig(
    /** The device number in the SysEx status byte. This app always uses 0; whether the
     * instrument answers others is untested. */
    val deviceNumber: Int = 0,
    val banks: List<MotifXsBank> = emptyList(),
    /**
     * How this instrument encodes a voice's category assignments.
     *
     * **A property of the instrument, so it lives here** rather than beside the factory voice
     * names: a user voice is filed under the same categories a factory one is, and the encoding
     * describes the format rather than the shipped list. Null in a catalog that does not carry it,
     * which is what leaves the app without a tagging facet at all.
     */
    val categoryEncoding: MotifXsCategoryEncoding? = null,
)

/**
 * How the four category bytes at `0x18`-`0x1B` of a voice's Common block encode two assignments.
 *
 * Ships as catalog data rather than as Kotlin constants because it is per-family *data*, and
 * because the sub-category order is a hardware measurement that Yamaha's own published voice list
 * contradicts - `Brass` prints as `Orche, Solo, BrsEn` and indexes as `Solo, BrsEn, Orche`.
 *
 * The JSON also carries `mainSource`, `subSource` and `subNoAssignmentRule`: prose explaining
 * where each half came from. They are not decoded here, and they are why the file is worth reading
 * before anyone changes this table.
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
 * [catalogFamilyConfig] when [deviceFamilyConfig] does not carry its own.
 *
 * **Why a fallback rather than always reading the shared one.** The XS6, XS7 and XS8 are the same
 * instrument electrically - same memory map, same endpoints, same cable - confirmed against the
 * vendor's own driver package (see `CatalogParsesTest`), so their bank table would otherwise be
 * copied three times in the catalog for no reason but which product id it sits next to. A device
 * that turns out to lay its memory out differently - a Motif XF, say - still overrides it by
 * simply giving its own catalog entry a `banks` array; nothing here special-cases that, the
 * per-device one just wins because it is checked first.
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

/**
 * The payload written over a slot to erase it, one per kind of voice.
 *
 * Not part of [MotifXsConfig] because it is not configuration: it is real instrument data that
 * ships with the app, and a catalog entry has no business carrying kilobytes of it.
 */
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
     * This instrument's messages do not fit the default framer.
     *
     * A normal voice dump is ~1.9 kB, but a **drum voice is ~12.6 kB** - three times
     * `SysExFramer.DEFAULT_MAX_MESSAGE_BYTES`, which would drop all 32 of them as over-long. The
     * default is sized for a Pro-800's 210-byte program and was never a protocol limit; this is
     * the same kind of bound, set from what this instrument actually sends (largest known: 12,622
     * bytes) with room for a bigger kit on a related model.
     */
    private const val MAX_MESSAGE_BYTES = 32768

    /**
     * Payloads written over a slot to erase it, one per kind of voice.
     *
     * **Real instrument data, not constructed.** Each is a voice initialised through the normal
     * instrument workflow and whose 20 name bytes were then cleared through the documented write
     * path, read back through `0C`.
     *
     * Shipping them replaces a search for an empty slot *in the same bank*, which was the only
     * way the app had to obtain one - and which fails outright on a bank with no empty slots, such
     * as a fully populated USER DR, where delete and move would otherwise not work at all.
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
            // Three attempts, not the default two. A dropped message on this bus is ordinary
            // rather than exceptional - see UsbMidiBulkTransport's buffer note for what causes it
            // - and one voice lost out of 416 is a visible hole in the browser. The extra attempt
            // costs nothing on the overwhelming majority of slots that succeed first time, and a
            // scan that has already committed to 93 seconds is not the place to be stingy about a
            // second retry.
            //
            // **A backstop, not the fix.** The cause is buffer overflow under a GC pause, and it
            // is addressed there; this is what remains for genuinely lost transfers.
            SysExExchange(midi, scope, framer = SysExFramer(MAX_MESSAGE_BYTES), retries = 2),
            config,
            blanks,
            descriptorId = descriptor.id,
            catalogName = descriptor.name,
            factoryVoices = factoryVoices,
        )
    }

    /**
     * The factory name table, or an empty one if it cannot be read.
     *
     * **Never throws, unlike the blanks above it.** `InstrumentRegistry.create` is not wrapped in
     * `runCatching` the way `descriptors` is, so anything thrown here does not degrade a feature -
     * it fails the whole connect, and a Motif XS becomes unusable. The blanks are allowed that
     * because delete and move genuinely cannot work without them; a missing name table only means
     * [MotifXsInstrument] does not offer the factory listing, which it decides by asking whether
     * this is empty.
     */
    private fun loadFactoryVoices(context: Context): MotifXsFactoryVoices =
        runCatching { factoryVoices.load(context) }.getOrElse { MotifXsFactoryVoices() }

    /**
     * The instrument speaks MIDI SysEx; the bus it speaks it over is not `android.media.midi`.
     *
     * A Motif XS declares a single **vendor-specific** interface and no MIDIStreaming one, so
     * `MidiManager` never enumerates it and there is no MIDI port to open. The way through is to
     * pack a Universal Device Inquiry into USB-MIDI event packets by hand and read the reply back
     * off the bulk endpoint directly.
     *
     * A [MidiTransport] is still accepted, because nothing here is Motif-specific except which
     * bus that particular instrument is on, and a Motif XF or a class-compliant sibling would
     * arrive the other way.
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
