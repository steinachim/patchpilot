// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.FactoryNameCheck
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import de.thewolfwalkexperience.software.patchpilot.core.indexWalk
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MotifXsInstrument"

/**
 * The Yamaha Motif XS: browses every voice bank, selects, and rearranges the user ones. Renaming
 * a stored voice goes through Yamaha's documented bulk-dump path (see [MotifXsEditor.rename]).
 *
 * Writing is two steps: a bulk dump to a stored-voice address is acknowledged and held, and
 * `11 00 00` commits it - see [MotifXsEditor] and `MotifXsSysEx.storeMarker`.
 *
 * The instrument is invisible to `MidiManager`, so the app reaches it over raw USB (see
 * `UsbMidiBulkTransport`).
 */
class MotifXsInstrument(
    internal val exchange: SysExExchange,
    internal val config: MotifXsConfig,
    /** What [PresetEditor.delete] and [PresetEditor.move] write over a cleared slot, per kind of voice. */
    internal val blanks: MotifXsBlanks,
    /** The catalog entry's id and name; defaulted so the JVM tests can build this with no catalog. */
    private val descriptorId: String = "yamaha_motif_xs",
    private val catalogName: String = "Yamaha Motif XS",
    /** The read-only banks' voice names, shipped rather than read ([MotifXsFactoryVoices]); an empty table offers no factory listing. */
    private val factoryVoices: MotifXsFactoryVoices = MotifXsFactoryVoices(),
) : Instrument, PresetBrowser {

    /**
     * Every voice bank the catalog declares: all fifteen, 1,633 slots. Bank sizes differ (128 for
     * a normal bank, 64 for PRE DR, 32 for USER DR, 1 for GM DR), hence [BankSpec]s rather than
     * [SlotLayout.uniform].
     *
     * The layout is the whole instrument; a listing is not. Only the four writable banks are
     * walked by [indexUser]; the read-only ones are named from a shipped table by [indexFactory].
     * Anything needing the addresses a user could write to filters on [BankSpec.readOnly].
     */
    override val layout = SlotLayout(
        // The display label, not the internal one: this is what the browser's headers and rail
        // show, beside ids that read "USER 2 - A:01".
        banks = config.banks.map { spec ->
            val shown = spec.shownLabel
            BankSpec(
                label = shown,
                slotCount = spec.slotCount,
                shortLabel = spec.shortLabel.ifEmpty { shown },
                readOnly = spec.readOnly,
            )
        },
        format = MotifXsAddressFormat(
            config.banks.map { it.label },
            config.banks.map { it.shownLabel },
        ),
    )

    private var firmware: String = UNKNOWN_FIRMWARE

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = descriptorId,
            family = FAMILY,
            name = catalogName,
            firmwareVersion = firmware,
            // USB: this instrument gets no MIDI port at all, so UsbHostDiscovery is what finds it.
            bus = Bus.USB,
            stableKey = "motifxs:$descriptorId",
        )

    override val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    override val browser: PresetBrowser get() = this

    override val editor: PresetEditor = MotifXsEditor(this)

    // Neither is implemented for this instrument: no raw voice transfer, no device report.
    override val transfer: PresetTransfer? = null
    override val report: DeviceReporter? = null

    /** Categories and favorites; null where the catalog carries no category encoding, which is what names the byte values. */
    override val tagger: PresetTagger? =
        config.categoryEncoding?.let { MotifXsTagger(this, config, it, factoryVoices) }

    /** The shipped factory names against the instrument's; null where no bank is a factory bank. */
    override val factoryNames: FactoryNameCheck? =
        if (config.banks.any { it.readOnly }) FactoryNames() else null

    /**
     * Reads the identity reply, and refuses the session if the instrument answers nothing at all.
     * The reply itself is cosmetic and stays best-effort; total silence is not survivable - see
     * [requireItIsListening].
     */
    override suspend fun connect() {
        val version = readIdentityVersion()
        if (version == null) requireItIsListening()
        firmware = version ?: UNKNOWN_FIRMWARE
    }

    /** The identity reply's version, or null if the instrument did not answer. */
    private suspend fun readIdentityVersion(): String? = try {
        val reply = exchange.exchange(
            MotifXsSysEx.identityRequest(),
            what = "asking the instrument to identify itself",
        ) { it.size > 4 && it[1].toInt() == 0x7E && it[4].toInt() == 0x02 }
        // Version occupies the four bytes before F7 in the inquiry reply.
        reply.copyOfRange(reply.size - 5, reply.size - 1)
            .joinToString(".") { (it.toInt() and 0x7F).toString() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the identity reply", e)
        null
    }

    /**
     * Turns "answers nothing" into the explanation that fixes it: a Motif XS routes MIDI to
     * exactly one destination (DIN, USB or mLAN), and set to any but USB it still enumerates,
     * opens its endpoints and then ignores every byte, so every operation would otherwise time
     * out separately with no way to say why.
     *
     * Two different probes have to be silent, not one: the identity reply is allowed to be
     * missing on an instrument that otherwise works. Silence has other causes (a cable, another
     * application holding the device), so the message says what to check rather than asserting a
     * diagnosis.
     */
    private suspend fun requireItIsListening() {
        if (readMode() != null) return
        throw InstrumentException.NeedsManualSetting(
            message = "$catalogName is connected but not answering. The most likely reason is " +
                "that its MIDI In/Out setting is routing MIDI to the MIDI or mLAN ports rather " +
                "than to USB - it still appears as a USB device either way, but ignores " +
                "everything sent to it.",
            steps = listOf(
                "On the instrument, press UTILITY.",
                "Select [F5] Control, then [SF2] MIDI.",
                "Set MIDI In/Out to USB.",
                "Tap Retry below.",
            ),
            alsoCheck = "If it is already set to USB, check that the cable runs to the " +
                "instrument's TO HOST port and that no other application is using the instrument.",
        )
    }

    override fun close() = exchange.close()

    // ---- The documented write path ----

    /*
     * These sit on the instrument rather than inside the editor because a rename and a category
     * change are the same write with a different patch.
     */

    /**
     * Yamaha's documented write path, with [patch] applied to the voice's Common block: the one
     * place this app writes a stored voice by the documented route, shared by
     * [MotifXsEditor.rename] and [MotifXsTagger.setCategories], which differ only in which bytes
     * they change (the name at `0x00`-`0x13`, the categories at `0x18`-`0x1B`).
     *
     * Reads the stored voice at Bulk Header `0E mm nn`, patches the Common block, and sends every
     * block back between a header and a footer, which saves to Flash ROM. No store marker; that
     * belongs to the `0C` path.
     *
     * [busyWhat] is the present participle the exchange logs ("renaming USER 1 - A:16") and
     * [failedWhat] the noun phrase the failure message is built from.
     *
     * @param patch mutates a copy of the Common block's payload in place, so it cannot change the
     *   block's length.
     */
    internal suspend fun editCommonBlock(
        address: SlotAddress,
        busyWhat: String,
        failedWhat: String,
        patch: (ByteArray) -> Unit,
    ) {
        val bank = config.banks[address.bank]
        val where = layout.format.format(address)
        val blocks = readStoredVoice(address)

        // Rebuilt, never echoed - see MotifXsSysEx.rebuildForHost. The header and footer are
        // ours, addressed at the destination.
        val body = blocks
            .filter { !isHeaderOrFooter(it) }
            .map { block ->
                if (MotifXsSysEx.isCommonBlock(block)) {
                    patchedCommonBlock(block, patch)
                } else {
                    MotifXsSysEx.rebuildForHost(config.deviceNumber, block)
                }
            }
        // No expected block count: a Normal Voice is 24 blocks and a Drum Voice 81, the shape
        // comes from the instrument, and every byte but the patched ones goes straight back. What
        // is checked is integrity - see readStoredVoice.
        if (body.isEmpty()) throw InstrumentException.ProtocolDesync("The documented read of $where carried no blocks.")

        val sequence = buildList {
            add(MotifXsSysEx.bulkHeader(config.deviceNumber, bank.addressMid, address.slot))
            addAll(body)
            add(MotifXsSysEx.bulkFooter(config.deviceNumber, bank.addressMid, address.slot))
        }
        try {
            exchange.exchangeAfterAll(
                sequence = sequence,
                what = busyWhat,
                timeout = SEQUENCE_TIMEOUT,
                gap = BLOCK_GAP,
            ) { MotifXsSysEx.isAck(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException.Timeout) {
            throw InstrumentException.Timeout(
                busyWhat,
                e,
                explanation = "The instrument did not acknowledge the $failedWhat. The sequence " +
                    "was sent whole, so it has either been stored or refused outright - the " +
                    "instrument's own screen says which. Re-read the bank before trusting what the " +
                    "app shows.",
            )
        } catch (e: Exception) {
            // A send that failed part way, which exchangeAfterAll cannot prevent.
            throw InstrumentException.TransportLost(
                "The $failedWhat failed part way through (${e.message}). If the instrument's " +
                    "screen stays on \"receiving midi bulk data\", switch it off and on; re-read " +
                    "the bank afterwards before trusting what the app shows.",
                e,
            )
        }
    }

    /** The Common block with [patch] applied to a copy of its payload. */
    private fun patchedCommonBlock(block: ByteArray, patch: (ByteArray) -> Unit): ByteArray {
        // Re-addressed to whichever Common block this is: 40 00 00 for a Normal Voice, 46 00 00
        // for a Drum.
        val (hi, mid, lo) = MotifXsSysEx.addressOf(block)
            ?: throw InstrumentException.ProtocolDesync("The Common block carries no address.")
        val payload = MotifXsSysEx.dumpPayload(block).copyOf()
        patch(payload)
        return MotifXsSysEx.bulkDump(config.deviceNumber, hi, mid, lo, payload)
    }

    /**
     * One voice's `0C` dump payload: what the browser lists and what every edit reads before it
     * writes the bytes back.
     *
     * Matched on the echoed address, not merely the type: a dump takes 160 ms and a drum voice a
     * second, so a late reply to a previous request would otherwise put one voice's name on
     * another's row, or have an edit write the wrong voice and its read-back confirm it.
     * Well-formedness is part of the match too, so a truncated dump is retried rather than
     * accepted.
     */
    internal suspend fun readVoicePayload(address: SlotAddress): ByteArray {
        val bank = config.banks[address.bank]
        val where = layout.format.format(address)
        val reply = exchange.exchange(
            MotifXsSysEx.requestDump(config.deviceNumber, bank.addressHi, bank.addressMid, address.slot),
            what = "reading voice $where",
            timeout = VOICE_TIMEOUT,
            intact = MotifXsSysEx::isWellFormedBulkDump,
        ) { message ->
            MotifXsSysEx.typeOf(message) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(message) == Triple(bank.addressHi, bank.addressMid, address.slot)
        }
        return MotifXsSysEx.dumpPayload(reply)
    }

    /** Header and footer bracket the sequence and carry the slot; the blocks do not. */
    private fun isHeaderOrFooter(message: ByteArray): Boolean =
        MotifXsSysEx.addressOf(message)?.first
            .let { it == MotifXsSysEx.BULK_HEADER_HI || it == MotifXsSysEx.BULK_FOOTER_HI }

    /**
     * The documented read: one request, 26 messages back, ending at the footer. Every block is
     * checked before any of it is sent back, because this is the last point at which a rename can
     * be aborted safely - once the header goes out the instrument is receiving a sequence (see
     * [SysExExchange.exchangeAfterAll]).
     */
    private suspend fun readStoredVoice(address: SlotAddress): List<ByteArray> {
        val bank = config.banks[address.bank]
        val where = layout.format.format(address)
        val blocks = exchange.exchangeSequence(
            request = MotifXsSysEx.requestStoredVoice(
                config.deviceNumber,
                bank.addressMid,
                address.slot,
            ),
            what = "reading $where by its documented address",
            timeout = SEQUENCE_TIMEOUT,
            accept = { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP },
        ) { MotifXsSysEx.isBulkFooter(it) }

        if (blocks.isEmpty() || !blocks.all { MotifXsSysEx.isWellFormedBulkDump(it) }) {
            throw InstrumentException.ProtocolDesync(
                "The documented read of $where came back damaged; refusing to write it back.",
            )
        }
        // Exactly one, since the patch assumes one: "at least one" would let an unrecognised
        // sequence through and patch only its first block.
        val commonBlocks = blocks.count { MotifXsSysEx.isCommonBlock(it) }
        if (commonBlocks != 1) {
            throw InstrumentException.ProtocolDesync(
                "The documented read of $where carried $commonBlocks Common blocks; expected exactly " +
                    "one, so there is no single name to patch.",
            )
        }
        return blocks
    }

    internal fun requireWritable(address: SlotAddress) {
        val bank = requireNotNull(config.banks.getOrNull(address.bank)) { "No bank ${address.bank}" }
        require(!bank.readOnly) {
            "${bank.shownLabel} is a factory bank and cannot be written."
        }
    }

    // ---- Tagging support ----

    /** The address as the browser writes it, e.g. `USER 1 - A:16`. */
    internal fun describe(address: SlotAddress): String = layout.format.format(address)

    /**
     * Writes the four category bytes of a stored voice by the documented path. [bytes] is
     * `main1, sub1, main2, sub2`, the layout at `0x18`-`0x1B` of the Common block. A factory bank
     * is refused: these bytes are inside the voice.
     *
     * Writes nothing, and returns false, where [alreadyStored] says the voice as read already
     * carries them: the write goes to Flash, and the read is needed anyway.
     */
    internal suspend fun editStoredCategories(
        address: SlotAddress,
        bytes: List<Int>,
        alreadyStored: (voicePayload: ByteArray) -> Boolean = { false },
    ): Boolean {
        requireWritable(address)
        val where = describe(address)
        require(bytes.size == MotifXsSysEx.CATEGORY_LENGTH) {
            "a Motif XS voice has ${MotifXsSysEx.CATEGORY_LENGTH} category bytes, not ${bytes.size}"
        }
        require(bytes.all { it in 0..0x7F }) { "a category byte has to fit in seven bits" }
        // As for a rename: an empty slot answers the read too, and would gain a nameless voice.
        val current = readVoicePayload(address)
        if (MotifXsVoice.nameOf(current) == null) {
            throw InstrumentException.SlotStateChanged.empty(where)
        }
        if (alreadyStored(current)) return false
        editCommonBlock(
            address,
            "setting the categories of $where",
            "category change of $where",
        ) { payload ->
            if (payload.size < MotifXsSysEx.CATEGORY_OFFSET + MotifXsSysEx.CATEGORY_LENGTH) {
                throw InstrumentException.ProtocolDesync(
                    "The Common block of $where is ${payload.size} bytes, too short to hold its categories.",
                )
            }
            bytes.forEachIndexed { i, value ->
                payload[MotifXsSysEx.CATEGORY_OFFSET + i] = value.toByte()
            }
        }
        return true
    }

    /** One bank's favorite marks - see [readFavoriteMarks]. */
    internal suspend fun readFavoriteTable(spec: MotifXsBank): ByteArray =
        readFavoriteMarks(spec, spec.shownLabel)

    /**
     * Writes one bank's whole favorite table and polls until the instrument has applied it: the
     * write is acknowledged at once and applied a moment later, so a read-back straight after the
     * ack returns the old table. No store marker - see [MotifXsSysEx.writeFavorites].
     */
    internal suspend fun writeFavoriteTable(spec: MotifXsBank, table: ByteArray) {
        val label = spec.shownLabel
        exchange.exchange(
            request = MotifXsSysEx.writeFavorites(config.deviceNumber, spec.addressMid, table),
            what = "setting $label's favorite marks",
            timeout = WRITE_TIMEOUT,
        ) { MotifXsSysEx.isAck(it) }

        val settled = withTimeoutOrNull(FAVORITE_SETTLE) {
            while (!readFavoriteMarks(spec, label).contentEquals(table)) delay(FAVORITE_POLL)
            true
        }
        // The write was acknowledged, so this says it has not shown up yet rather than that it
        // failed.
        if (settled != true) {
            throw InstrumentException.Timeout(
                "applying $label's favorite marks",
                explanation = "$label's favorite marks were acknowledged but had not applied after $FAVORITE_SETTLE.",
            )
        }
    }

    /** Selection is three parameter sets at `65 00 00/01/02`; see [MotifXsSelector]. */
    override val selector: PresetSelector = MotifXsSelector(this)

    // ---- PresetBrowser ----

    /**
     * All three listings, where the instrument can fill them. [PresetScope.FACTORY] needs both
     * read-only banks and a name table, or the tab would open onto unnamed rows.
     * [PresetScope.FAVORITES] needs neither: it is read from the instrument, and nothing marked
     * is a true answer.
     */
    override val scopes: List<PresetScope> = buildList {
        add(PresetScope.USER)
        if (config.banks.any { it.readOnly } && !factoryVoices.isEmpty) add(PresetScope.FACTORY)
        add(PresetScope.FAVORITES)
    }

    /**
     * The addresses [indexUser] walks: the banks marked [MotifXsBank.indexByDefault], the four
     * writable ones. The factory banks are excluded rather than absent from the catalog, since
     * [indexFactory] names them from a shipped table - see [MotifXsBank.indexByDefault].
     */
    private fun defaultAddresses(): Sequence<SlotAddress> = sequence {
        config.banks.forEachIndexed { bank, spec ->
            if (spec.indexByDefault) {
                for (slot in 0 until spec.slotCount) yield(SlotAddress(bank, slot))
            }
        }
    }

    /**
     * Reads one factory bank and compares it with the shipped table, sending only dump requests:
     * about 20 s for a 128-voice bank and a minute for the drum bank at the user-voice rates
     * measured below (an extrapolation; not timed). The expected names come from the factory listing, which is
     * built from that table with no round trips.
     */
    private inner class FactoryNames : FactoryNameCheck {
        override val banks: List<Pair<Int, String>>
            get() = layout.banks.withIndex().filter { it.value.readOnly }.map { it.index to it.value.label }

        override suspend fun verify(bank: Int, progress: (String) -> Unit): List<String> {
            val spec = requireNotNull(layout.banks.getOrNull(bank)) { "No bank $bank" }
            require(spec.readOnly) { "${spec.label} is not a factory bank." }
            val expected = browser.index(PresetScope.FACTORY).toList()
                .filterIsInstance<IndexUpdate.Slots>()
                .flatMap { it.slots }
                .filter { it.address.bank == bank }
                .associateBy { it.address.slot }

            val mismatches = mutableListOf<String>()
            indexBank(bank).collect { update ->
                when (update) {
                    is IndexUpdate.Progress -> progress(update.label)

                    is IndexUpdate.Failed ->
                        mismatches += "${update.address.slot + 1}: unreadable (${update.reason})"

                    is IndexUpdate.Slots -> update.slots.forEach { read ->
                        val shippedName = expected[read.address.slot]?.name
                        if (read.name != shippedName) {
                            mismatches += "${read.displayId}: instrument says " +
                                "\"${read.name ?: "(empty)"}\", app ships \"${shippedName ?: "(none)"}\""
                        }
                    }

                    IndexUpdate.Complete -> Unit
                }
            }
            return mismatches
        }
    }

    /**
     * Reads one bank on demand, for a bank a full [index] would skip. Same streaming shape and
     * per-slot failure isolation; the debug menu uses it to check one factory bank against the
     * shipped table, which costs that bank's dumps rather than a walk of the instrument.
     */
    fun indexBank(bank: Int): Flow<IndexUpdate> {
        val spec = requireNotNull(config.banks.getOrNull(bank)) {
            "No bank $bank; this instrument has ${config.banks.size}"
        }
        return indexWalk(
            addresses = (0 until spec.slotCount).map { SlotAddress(bank, it) },
            total = spec.slotCount,
            // One row per emission: a check is watched slot by slot, not in batches.
            batchSize = 1,
            layout = layout,
            onFailure = { displayId, cause -> Log.w(TAG, "Couldn't read $displayId", cause) },
            readSlot = ::readSlot,
        )
    }

    /**
     * Walks every user voice, one dump request each - slow, which is what the streaming shape is
     * for: a normal voice is ~1.9 kB and takes 158 ms, a drum kit ~12.6 kB and 1,016 ms, and the
     * 416 user voices ~93 s in total, at the 12,468 B/s the instrument paces its USB output at.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> {
        val listing = when (scope) {
            PresetScope.USER -> indexUser()
            PresetScope.FACTORY -> indexFactory()
            PresetScope.FAVORITES -> indexFavorites()
        }
        // A listing is the app's "re-read the instrument" gesture, and the favorite marks are
        // what a player can change behind its back from the front panel. On collection rather
        // than here, since a caller may build a flow it never collects.
        return listing.onStart { (tagger as? MotifXsTagger)?.invalidate() }
    }

    private fun indexUser(): Flow<IndexUpdate> = indexWalk(
        addresses = defaultAddresses().asIterable(),
        total = defaultAddresses().count(),
        batchSize = BATCH_SIZE,
        layout = layout,
        // Logged as well as counted: a Failed slot reaches the user as a number, which says
        // nothing about a timeout against a malformed reply.
        onFailure = { displayId, cause -> Log.w(TAG, "Couldn't read $displayId", cause) },
        readSlot = ::readSlot,
    )

    /**
     * The eleven read-only banks, named from the shipped table, with no round trips - hence one
     * batch rather than the streaming shape. A slot the table has no entry for becomes an unnamed
     * row rather than being skipped, so a gap shows as one missing name instead of shifting every
     * row after it.
     */
    private fun indexFactory(): Flow<IndexUpdate> = flow {
        val slots = config.banks.withIndex()
            .filter { (_, spec) -> spec.readOnly }
            .flatMap { (bank, spec) ->
                (0 until spec.slotCount).map { slot -> factorySlot(spec, SlotAddress(bank, slot)) }
            }
        emit(IndexUpdate.Slots(slots))
        emit(IndexUpdate.Complete)
    }

    /**
     * The voices marked as favorites on the instrument itself, across every bank: one small dump
     * per bank at `71 mm 00` ([MotifXsSysEx.FAVORITES_ADDRESS_HI]) says which slots are marked,
     * and the names come from the shipped table for a read-only bank or from a ~160 ms dump per
     * marked slot for a user bank.
     *
     * Only catalogued banks are asked, since an unmapped address puts an *Illegal Bulk Data*
     * message on the instrument's screen. A bank that will not answer costs only itself.
     */
    private fun indexFavorites(): Flow<IndexUpdate> = flow {
        val total = config.banks.size
        config.banks.forEachIndexed { bank, spec ->
            val label = spec.shownLabel
            val marks = try {
                readFavoriteMarks(spec, label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read $label's favorite marks", e)
                // Addressed at the bank's first slot: the failure is the bank's, and
                // IndexUpdate.Failed carries an address.
                emit(IndexUpdate.Failed(SlotAddress(bank, 0), e.message ?: "unreadable"))
                emit(IndexUpdate.Progress(bank + 1, total, "Reading $label"))
                return@forEachIndexed
            }

            val batch = mutableListOf<PresetSlot>()
            // The smaller of the two bounds: a short reply must not be indexed past its end, and
            // a long one must not invent slots the bank does not have.
            for (slot in 0 until minOf(marks.size, spec.slotCount)) {
                // Any non-zero mark counts; which of the voice's assignments 1, 2 and 3 name is
                // the tag dialog's question, not this listing's.
                if (marks[slot].toInt() == 0) continue
                val address = SlotAddress(bank, slot)
                val displayId = layout.format.format(address)
                if (spec.readOnly) {
                    batch += factorySlot(spec, address)
                } else {
                    try {
                        batch += readSlot(address, displayId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't read favorited voice $displayId", e)
                        emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
                    }
                }
            }
            if (batch.isNotEmpty()) emit(IndexUpdate.Slots(batch))
            emit(IndexUpdate.Progress(bank + 1, total, "Reading $label"))
        }
        emit(IndexUpdate.Complete)
    }

    /** One bank's marks, one raw byte per slot. See [MotifXsSysEx.FAVORITES_ADDRESS_HI]. */
    private suspend fun readFavoriteMarks(spec: MotifXsBank, label: String): ByteArray {
        var damaged = false
        val reply = try {
            exchange.exchange(
                MotifXsSysEx.requestFavorites(config.deviceNumber, spec.addressMid),
                what = "reading $label's favorite marks",
                // The exchange's own default, not [VOICE_TIMEOUT]: this is the smallest message
                // on the wire, at most 128 bytes.
            ) { message ->
                // Matched on the echoed address and on well-formedness, for the reasons
                // readVoicePayload gives.
                MotifXsSysEx.typeOf(message) == MotifXsSysEx.TYPE_BULK_DUMP &&
                    MotifXsSysEx.addressOf(message) ==
                    Triple(MotifXsSysEx.FAVORITES_ADDRESS_HI, spec.addressMid, 0) &&
                    MotifXsSysEx.isWellFormedBulkDump(message).also { intact ->
                        if (!intact) damaged = true
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (damaged) {
                throw InstrumentException.ProtocolDesync(
                    "$label's favorite marks arrived damaged (bad length or checksum) on every " +
                        "attempt.",
                    e,
                )
            }
            throw e
        }
        // Not unpacked: unlike a voice dump this payload is one plain byte per slot, and the
        // unpacker would turn it into plausible-looking rubbish rather than an error.
        return MotifXsSysEx.dumpPayload(reply)
    }

    /**
     * Re-reads one row after an edit. A factory row is rebuilt from the table rather than dumped:
     * the one edit that can target a read-only address is favoriting, which changes only the mark.
     */
    override suspend fun refresh(address: SlotAddress): PresetSlot {
        val spec = config.banks[address.bank]
        if (spec.readOnly) return factorySlot(spec, address)
        return readSlot(address, layout.format.format(address))
    }

    private suspend fun readSlot(address: SlotAddress, displayId: String): PresetSlot {
        val bank = config.banks[address.bank]
        // Unpacked once for both the name and the categories.
        val dense = MotifXsVoice.denseOf(readVoicePayload(address))
        return PresetSlot(
            address = address,
            displayId = displayId,
            // The display label, matching what SlotLayout's BankSpec carries: the browser groups
            // by this string, so a disagreement gives every bank two headers.
            bankLabel = bank.shownLabel,
            name = dense?.let(MotifXsVoice::nameIn),
            // The categories sit in front of the name in the bytes this dump already carried -
            // see [MotifXsVoice.categoriesOf].
            badges = categoryBadges(dense?.let(MotifXsVoice::figuresOf)),
        )
    }

    /**
     * A row for a factory slot, built entirely from the shipped table with no round trips. The
     * one place a factory row is made, so [indexFactory], [indexFavorites] and [refresh] cannot
     * disagree.
     */
    private fun factorySlot(spec: MotifXsBank, address: SlotAddress): PresetSlot = PresetSlot(
        address = address,
        displayId = layout.format.format(address),
        bankLabel = spec.shownLabel,
        name = factoryVoices.name(spec.label, address.slot),
        badges = factoryCategories(spec, address.slot).mapNotNull { taxonomy?.label(it) },
    )

    /** The category names this instrument knows, or null where the catalog carries none. */
    internal val taxonomy: CategoryTaxonomy? get() = config.categoryEncoding?.taxonomy

    /** A factory voice's assignments, from the shipped table; never a device read. */
    internal fun factoryCategories(spec: MotifXsBank, slot0: Int): List<CategoryRef> =
        MotifXsCategories.refsOf(
            factoryVoices.categories(spec.label, slot0),
            config.categoryEncoding,
        )

    /** Labels for decoded `0C` figures, dropping anything the shipped taxonomy cannot name. */
    private fun categoryBadges(figures: List<Int?>?): List<String> {
        val names = taxonomy ?: return emptyList()
        return figures.orEmpty().mapNotNull { figure ->
            figure?.let { MotifXsCategories.refOf(it, names) }?.let { names.label(it) }
        }
    }

    companion object {
        const val FAMILY = "motifxs"

        /** Emitting every 8 voices fills the list without a recomposition per round trip - at ~160 ms each, a batch every ~1.3 s. */
        private const val BATCH_SIZE = 8

        /** Sized for the slowest voice: a drum voice is ~12.6 kB and takes ~1.02 s against a user voice's ~160 ms. */
        internal val VOICE_TIMEOUT = 5.seconds

        /** As many as the exchange the family builds (`MotifXsFamily`) allows any other request. */
        internal const val SELECT_RETRIES = 2

        /**
         * A write's acknowledgement takes ~158 ms for a 1,916-byte voice. Generous, since too
         * short would report a slow but accepted write as refused while it is still pending
         * inside the instrument.
         */
        internal val WRITE_TIMEOUT = 5.seconds

        /** The commit is a flash write - ~160 ms measured, against ~19 ms for a data dump. */
        internal val COMMIT_TIMEOUT = 5.seconds

        /** One parameter request; the mode reply comes back in ~10 ms. */
        internal val MODE_TIMEOUT = 2.seconds

        /**
         * How long a favorites write gets to apply after being acknowledged. No settle time was
         * measured - the delay showed up as an immediate read-back returning the old table - so
         * this is polled rather than slept through, and generous.
         */
        private val FAVORITE_SETTLE = 3.seconds

        /** Between polls of the favorites table; each is a single dump of at most 128 bytes. */
        private val FAVORITE_POLL = 150.milliseconds

        /**
         * How long to wait for a mode change to take effect: three seconds, asked every 250 ms.
         * Generous, since too short would tell the user their switch failed while they watch it
         * succeed. The loop exits as soon as the instrument reports Voice.
         */
        internal val MODE_SETTLE_INTERVAL = 250.milliseconds
        internal val MODE_SETTLE_BUDGET = 3.seconds

        /** A whole documented block sequence, not each message: the read takes ~1.4 s and the write ends in a flash commit. */
        private val SEQUENCE_TIMEOUT = 10.seconds

        /** Pacing between the blocks of a write sequence, matching the vendor editor's spacing. */
        private val BLOCK_GAP = 15.milliseconds

        /** What a failed write leaves behind, whichever way it failed - see `MotifXsEditor.committed`. */
        internal const val ARMED_WRITE_WARNING =
            "If this operation had already written another slot, that write is sitting " +
                "accepted-but-uncommitted inside the instrument and will be applied by the next " +
                "thing that commits. Switching the instrument off and on discards it."
    }
}
