// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.indexWalk
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
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
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import de.thewolfwalkexperience.software.patchpilot.core.Bus

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
    private val exchange: SysExExchange,
    private val config: MotifXsConfig,
    /** What [PresetEditor.delete] and [PresetEditor.move] write over a cleared slot, per kind of voice. */
    private val blanks: MotifXsBlanks,
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
            val shown = spec.displayLabel.ifEmpty { spec.label }
            BankSpec(
                label = shown,
                slotCount = spec.slotCount,
                shortLabel = spec.shortLabel.ifEmpty { shown },
                readOnly = spec.readOnly,
            )
        },
        format = MotifXsAddressFormat(
            config.banks.map { it.label },
            config.banks.map { it.displayLabel.ifEmpty { it.label } },
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

    override val editor: PresetEditor = MotifXsEditor()

    // Not implemented for this instrument; see the class doc.
    override val transfer: PresetTransfer? = null
    override val report: DeviceReporter? = null

    /** Categories and favorites; null where the catalog carries no category encoding, which is what names the byte values. */
    override val tagger: PresetTagger? =
        config.categoryEncoding?.let { MotifXsTagger(this, config, it, factoryVoices) }

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

    // ---- PresetEditor ----

    /**
     * Copy, move, swap, delete and rename - all five emulated from reads and writes.
     *
     * Copy, move, swap and delete share one primitive: a whole voice written back as a bulk dump
     * to `0C <bank> <slot>`. Rename uses the documented path instead (see [rename]), since
     * parameter changes reach only the edit buffer and no host message carries that to a slot.
     *
     * Every operation is write, write, commit, verify. All the writes precede the single commit,
     * which makes them all-or-nothing: a refused write is answered with silence and is not
     * buffered, so a rejected second write leaves nothing committed. The read-back comes after
     * the commit, since an acknowledgement is a claim about the message and not about the flash.
     */
    private inner class MotifXsEditor : PresetEditor {
        override val supported =
            setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

        override val maxNameLength = MotifXsVoice.NAME_LENGTH

        /** Both are composed host-side, so a failure can leave the instrument part-way. */
        override fun isEmulated(op: EditOp) = true

        /**
         * Renames a stored voice by Yamaha's documented write path:
         *
         *   1. read the stored voice at Bulk Header `0E mm nn` (26 messages, 1,762 data bytes);
         *   2. patch the first 20 bytes of the Common block, which is the name, fixed width;
         *   3. send the blocks back between a header and a footer, which saves to Flash ROM.
         *
         * The one edit here not built on `0C`, and the one needing no store marker. The write
         * survives a power cycle, and every block but the Common one goes back byte-identical.
         */
        override suspend fun rename(address: SlotAddress, newName: String) {
            requireWritable(address)
            val trimmed = newName.trim()
            require(trimmed.isNotEmpty()) { "A voice name cannot be empty." }
            require(trimmed.length <= MotifXsVoice.NAME_LENGTH) {
                "A Motif XS voice name is at most ${MotifXsVoice.NAME_LENGTH} characters."
            }
            // ASCII is the field's own range - the Data List gives `00, 20 - 7E` - so a name the
            // instrument cannot store is refused here rather than written as mojibake.
            require(trimmed.all { it.code in 0x20..0x7E }) {
                "A Motif XS voice name can only hold plain ASCII characters."
            }

            val where = layout.format.format(address)

            // An empty slot still answers the documented read with a full sequence, so without
            // this a rename would succeed and leave a named voice with nothing in it.
            val previousName = MotifXsVoice.nameOf(readVoicePayload(address))
                ?: throw InstrumentException.NotSupported("rename an empty slot")

            editCommonBlock(address, "renaming $where", "rename of $where") { payload ->
                check(payload.size >= MotifXsVoice.NAME_LENGTH) {
                    "the Common block is ${payload.size} bytes, too short to hold a name"
                }
                // NUL-padded, as the instrument itself does and revision C0 of the Data List
                // permits; revision B0 says space-padded and does not match the instrument.
                for (i in 0 until MotifXsVoice.NAME_LENGTH) {
                    payload[i] = if (i < trimmed.length) trimmed[i].code.toByte() else 0
                }
            }

            // Read back through `0C`, the path the browser lists with.
            val stored = MotifXsVoice.nameOf(readVoicePayload(address))
            check(stored == trimmed) {
                "renamed $where but it reads back as ${stored ?: "empty"}"
            }

            reloadIfShowing(address, previousName)
        }

        /**
         * Re-selects the voice if the instrument's own panel is still showing the old name: the
         * documented write stores to Flash and does not touch the edit buffer. Reloads only when
         * the edit buffer's name matches the one just replaced, so a player is not yanked onto a
         * voice they had not chosen; where two slots share a name it may reload the renamed one
         * instead. Cosmetic, so a failure here is logged and never fails the rename.
         */
        private suspend fun reloadIfShowing(address: SlotAddress, previousName: String) {
            try {
                if (readMode() != MotifXsMode.VOICE) return
                if (readEditBufferName() != previousName) return
                selector.select(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Renamed, but couldn't reload the voice to refresh the panel", e)
            }
        }

        /**
         * Erases a slot by writing an initialised voice over it - the same operation [move]
         * performs on its source, not the vendor editor's "Initialize Current Voice", which
         * rewrites the edit buffer and touches no stored address.
         */
        override suspend fun delete(address: SlotAddress) {
            requireWritable(address)
            val blank = blankPayloadFor(address.bank)
            committed {
                write(address, blank)
            }
            verify(address, blank)
        }

        /** Swap: read both, write each to the other's address. Needs no blank payload. */
        override suspend fun swap(a: SlotAddress, b: SlotAddress) {
            requireWritable(a)
            requireWritable(b)
            requireSameKind(a, b)
            if (a == b) return
            val payloadA = readVoicePayload(a)
            val payloadB = readVoicePayload(b)
            committed {
                write(a, payloadB)
                write(b, payloadA)
            }
            verify(a, payloadB)
            verify(b, payloadA)
        }

        /**
         * Copy: write the source's bytes to an empty destination, commit, verify - [move] without
         * the second write. Works within a bank and across banks alike, since the payload carries
         * no address of its own. The returned name is the source's verbatim; the instrument stores
         * exactly the bytes it is sent, and [verify] has already compared them.
         */
        override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
            requireWritable(dst)
            requireSameKind(src, dst)
            if (src == dst) throw InstrumentException.NotSupported("copy a voice onto itself")

            val payload = readVoicePayload(src)
            val name = MotifXsVoice.nameOf(payload)
                ?: throw InstrumentException.NotSupported("copy an empty slot")

            // The instrument accepts a well-formed dump to an occupied writable slot, so this
            // has to refuse one.
            if (MotifXsVoice.nameOf(readVoicePayload(dst)) != null) {
                throw InstrumentException.NotSupported("copy onto an occupied slot")
            }

            committed {
                write(dst, payload)
            }
            verify(dst, payload)
            return name
        }

        /**
         * Move: write the voice to the destination, then clear the source with the shipped blank
         * ([blankPayloadFor]). Both writes precede the single commit, so a refused clear leaves
         * nothing committed; destination first, so a partial commit would leave the voice in two
         * places rather than none.
         */
        override suspend fun move(from: SlotAddress, to: SlotAddress) {
            requireWritable(from)
            requireWritable(to)
            requireSameKind(from, to)
            if (from == to) return
            val payload = readVoicePayload(from)
            // Sourced before the first write, so a bank with no blank to copy fails before
            // anything has been changed rather than after the destination is already written.
            val blank = blankPayloadFor(from.bank)
            committed {
                write(to, payload)
                write(from, blank)
            }
            verify(to, payload)
            verify(from, blank)
        }

        /**
         * Refuses an edit that would move a voice between a drum bank and a normal one - the one
         * cross-slot rule the instrument does not enforce. Measured: a normal voice offered to
         * `0C 28 00` was acknowledged, and a drum kit to `0C 0A 7F` likewise. Neither was
         * committed, so what a commit would make of the slot is untested.
         */
        private fun requireSameKind(from: SlotAddress, to: SlotAddress) {
            val source = config.banks[from.bank]
            val target = config.banks[to.bank]
            if (source.isDrum == target.isDrum) return
            val kind = { b: MotifXsBank -> if (b.isDrum) "a drum kit" else "a normal voice" }
            throw InstrumentException.NotSupported(
                "put ${kind(source)} into a slot that holds ${kind(target)} - " +
                    "${source.displayLabel.ifEmpty { source.label }} and " +
                    "${target.displayLabel.ifEmpty { target.label }} store different kinds of " +
                    "voice, and the instrument does not refuse the mismatch itself"
            )
        }

        /** An initialised-voice payload to write over a cleared slot, shipped with the app rather than fabricated. */
        private fun blankPayloadFor(bank: Int): ByteArray = blanks.forBank(config.banks[bank])

        /**
         * Offers one slot's worth of new contents and waits to hear it accepted. Nothing is stored
         * yet when this returns (see [commit]); what it establishes is that the instrument took
         * the message, since a refusal is silence.
         *
         * A failure is not always "nothing happened": an earlier accepted write in the same
         * operation is still held, and the next commit from anywhere applies it. The message says
         * so, and that a power cycle discards it.
         */
        private suspend fun write(address: SlotAddress, payload: ByteArray) {
            val bank = config.banks[address.bank]
            val where = layout.format.format(address)
            try {
                exchange.exchange(
                    request = MotifXsSysEx.bulkDump(
                        config.deviceNumber, bank.addressHi, bank.addressMid, address.slot, payload,
                    ),
                    what = "writing $where",
                    timeout = WRITE_TIMEOUT,
                ) { MotifXsSysEx.isAck(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The instrument refused the write to $where. Nothing has been stored - but " +
                        "if this operation had already written another slot, that write is " +
                        "sitting accepted-but-uncommitted inside the instrument and will be " +
                        "applied by the next thing that commits. Switching the instrument off " +
                        "and on discards it.",
                    e,
                )
            }
        }

        /**
         * Runs [writes] and the commit that applies them as one uninterruptible unit. An accepted
         * write is armed, not pending: [commit] applies everything offered since the last commit,
         * so a cancellation landing between a write and the commit defers the edit to some later,
         * unrelated commit - and for [move] and [swap] that later commit would apply half an
         * operation. `viewModelScope` removes the ordinary triggers
         * (`InstrumentViewModel.launchEdit`) but not `onCleared()`.
         *
         * The verify stays outside: it is a read, so cancelling it changes nothing on the
         * instrument and lets a torn-down session stop promptly.
         */
        private suspend fun committed(writes: suspend () -> Unit) = withContext(NonCancellable) {
            writes()
            commit()
        }

        /**
         * Commits every write offered since the last commit: `11 00 00`, a zero-payload bulk
         * dump, acknowledged in ~160 ms against ~19 ms for a data dump. The scope is everything
         * pending, not the writes just made, which is why [write] failing aborts the whole
         * operation.
         */
        private suspend fun commit() {
            try {
                exchange.exchange(
                    request = MotifXsSysEx.storeMarker(config.deviceNumber),
                    what = "committing the write",
                    timeout = COMMIT_TIMEOUT,
                ) { MotifXsSysEx.isAck(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The instrument did not confirm the commit, so the writes may or may not have " +
                        "been stored. Re-read the bank before trusting what it shows.",
                    e,
                )
            }
        }

        /** Reads a slot back and insists it holds what was written. */
        private suspend fun verify(address: SlotAddress, payload: ByteArray) {
            val readBack = readVoicePayload(address)
            check(readBack.contentEquals(payload)) {
                "wrote ${layout.format.format(address)} but read back something different " +
                    "(${payload.size} bytes out, ${readBack.size} back)"
            }
        }
    }

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
    private suspend fun editCommonBlock(
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
                if (MotifXsSysEx.isCommonBlock(block)) patchedCommonBlock(block, patch)
                else MotifXsSysEx.rebuildForHost(config.deviceNumber, block)
            }
        // No expected block count: a Normal Voice is 24 blocks and a Drum Voice 81, the shape
        // comes from the instrument, and every byte but the patched ones goes straight back. What
        // is checked is integrity - see readStoredVoice.
        check(body.isNotEmpty()) { "the documented read of $where carried no blocks" }

        val sequence = buildList {
            add(MotifXsSysEx.bulkHeader(config.deviceNumber, bank.addressMid, address.slot))
            addAll(body)
            add(MotifXsSysEx.bulkFooter(config.deviceNumber, bank.addressMid, address.slot))
        }
        try {
            exchange.exchangeAfterAll(
                messages = sequence,
                what = busyWhat,
                timeout = SEQUENCE_TIMEOUT,
                gap = BLOCK_GAP,
            ) { MotifXsSysEx.isAck(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(
                "The instrument did not acknowledge the $failedWhat. The sequence was sent " +
                    "whole, so it has either been stored or refused outright - the instrument's " +
                    "own screen says which. Re-read the bank before trusting what the app shows.",
                e,
            )
        }
    }

    /** The Common block with [patch] applied to a copy of its payload. */
    private fun patchedCommonBlock(block: ByteArray, patch: (ByteArray) -> Unit): ByteArray {
        // Re-addressed to whichever Common block this is: 40 00 00 for a Normal Voice, 46 00 00
        // for a Drum.
        val (hi, mid, lo) = MotifXsSysEx.addressOf(block)
            ?: error("the Common block carries no address")
        val payload = MotifXsSysEx.dumpPayload(block).copyOf()
        patch(payload)
        return MotifXsSysEx.bulkDump(config.deviceNumber, hi, mid, lo, payload)
    }

    internal suspend fun readVoicePayload(address: SlotAddress): ByteArray {
        val bank = config.banks[address.bank]
        val reply = exchange.exchange(
            request = MotifXsSysEx.requestDump(
                config.deviceNumber, bank.addressHi, bank.addressMid, address.slot,
            ),
            what = "reading ${layout.format.format(address)}",
            timeout = VOICE_TIMEOUT,
            // Well-formedness is part of the match, so a truncated dump is retried rather than
            // accepted (see readSlot). These bytes are written straight back to the instrument.
        ) { m ->
            MotifXsSysEx.typeOf(m) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.isWellFormedBulkDump(m)
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
                config.deviceNumber, bank.addressMid, address.slot,
            ),
            what = "reading $where by its documented address",
            timeout = SEQUENCE_TIMEOUT,
            accept = { MotifXsSysEx.typeOf(it) == MotifXsSysEx.TYPE_BULK_DUMP },
        ) { MotifXsSysEx.isBulkFooter(it) }

        check(blocks.isNotEmpty() && blocks.all { MotifXsSysEx.isWellFormedBulkDump(it) }) {
            "the documented read of $where came back damaged; refusing to write it back"
        }
        // Exactly one, since the patch assumes one: "at least one" would let an unrecognised
        // sequence through and patch only its first block.
        check(blocks.count { MotifXsSysEx.isCommonBlock(it) } == 1) {
            "the documented read of $where carried " +
                "${blocks.count { MotifXsSysEx.isCommonBlock(it) }} Common blocks; expected " +
                "exactly one, so there is no single name to patch"
        }
        return blocks
    }

    private fun requireWritable(address: SlotAddress) {
        val bank = config.banks.getOrNull(address.bank) ?: error("No bank ${address.bank}")
        require(!bank.readOnly) {
            "${bank.displayLabel.ifEmpty { bank.label }} is a factory bank and cannot be written."
        }
    }

    // ---- Tagging support ----

    /** The address as the browser writes it, e.g. `USER 1 - A:16`. */
    internal fun describe(address: SlotAddress): String = layout.format.format(address)

    /**
     * Writes the four category bytes of a stored voice by the documented path. [bytes] is
     * `main1, sub1, main2, sub2`, the layout at `0x18`-`0x1B` of the Common block. A factory bank
     * is refused: these bytes are inside the voice.
     */
    internal suspend fun editStoredCategories(address: SlotAddress, bytes: List<Int>) {
        requireWritable(address)
        val where = describe(address)
        require(bytes.size == MotifXsSysEx.CATEGORY_LENGTH) {
            "a Motif XS voice has ${MotifXsSysEx.CATEGORY_LENGTH} category bytes, not ${bytes.size}"
        }
        require(bytes.all { it in 0..0x7F }) { "a category byte has to fit in seven bits" }
        editCommonBlock(
            address,
            "setting the categories of $where",
            "category change of $where",
        ) { payload ->
            check(payload.size >= MotifXsSysEx.CATEGORY_OFFSET + MotifXsSysEx.CATEGORY_LENGTH) {
                "the Common block is ${payload.size} bytes, too short to hold its categories"
            }
            bytes.forEachIndexed { i, value ->
                payload[MotifXsSysEx.CATEGORY_OFFSET + i] = value.toByte()
            }
        }
    }

    /** One bank's favorite marks - see [readFavoriteMarks]. */
    internal suspend fun readFavoriteTable(spec: MotifXsBank): ByteArray =
        readFavoriteMarks(spec, spec.displayLabel.ifEmpty { spec.label })

    /**
     * Writes one bank's whole favorite table and polls until the instrument has applied it: the
     * write is acknowledged at once and applied a moment later, so a read-back straight after the
     * ack returns the old table. No store marker - see [MotifXsSysEx.writeFavorites].
     */
    internal suspend fun writeFavoriteTable(spec: MotifXsBank, table: ByteArray) {
        val label = spec.displayLabel.ifEmpty { spec.label }
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
        check(settled == true) {
            "$label's favorite marks were acknowledged but had not applied after $FAVORITE_SETTLE"
        }
    }

    // ---- Mode ----

    /** Reads the instrument's current mode, or null if it will not say: one parameter request, ~10 ms. Best-effort - see [requireVoiceMode]. */
    private suspend fun readMode(): MotifXsMode? = try {
        val reply = exchange.exchange(
            request = MotifXsSysEx.requestMode(config.deviceNumber),
            what = "asking which mode the instrument is in",
            timeout = MODE_TIMEOUT,
        ) { MotifXsSysEx.modeOf(it) != null }
        MotifXsSysEx.modeOf(reply)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the mode; continuing without the check", e)
        null
    }

    /**
     * Refuses a selection the instrument would silently ignore: selection works in Voice mode
     * only, and in Performance and Song mode the three parameter sets draw no echo and change
     * nothing, so the selection would otherwise time out and report silence.
     *
     * The mode change is offered as a remedy rather than sent, since taking a player out of the
     * Performance they are using is a visible change to their setup. An unreadable mode blocks
     * nothing: the selection is attempted anyway.
     */
    private suspend fun requireVoiceMode() {
        val mode = readMode() ?: return
        if (!mode.blocksVoiceSelection) return
        throw InstrumentException.BlockedByDeviceState(
            message = "The instrument is in ${mode.label} mode, where it ignores a voice " +
                "selection silently - nothing would change.",
            remedyLabel = "Switch to Voice mode",
            remedyDetail = "This switches the instrument out of ${mode.label} mode, which " +
                "changes what it is playing. Anything unsaved in the current " +
                "${mode.label.lowercase()} stays on the instrument, but you will have to go " +
                "back to it yourself.",
            remedy = { switchToVoiceMode() },
        )
    }

    /**
     * Sends the documented mode change and confirms it took - only ever called from a remedy the
     * user has accepted. The confirmation matters because a parameter change draws no reply of
     * its own, so a switch that did not happen would look like one that did.
     */
    private suspend fun switchToVoiceMode() {
        exchange.tell(MotifXsSysEx.setMode(config.deviceNumber, MotifXsMode.VOICE))

        // Polled, not read back once: the instrument tears down a Performance and loads a voice,
        // so a read straight after the send returns the old value. Bounded by the clock rather
        // than by a poll count, since each read carries its own timeout and retries.
        var last: MotifXsMode? = null
        val reachedVoice = withTimeoutOrNull(MODE_SETTLE_BUDGET) {
            var now: MotifXsMode?
            do {
                delay(MODE_SETTLE_INTERVAL)
                now = readMode()
                if (now != null) last = now
            } while (now?.blocksVoiceSelection != false)
            true
        }
        if (reachedVoice == true) return

        // Never read at all is "could not tell", as in [requireVoiceMode].
        val stuck = last ?: return
        throw InstrumentException.BlockedByDeviceState(
            "The instrument was still in ${stuck.label} mode after being asked to switch. " +
                "Change it from the panel instead."
        )
    }

    /**
     * The voice currently loaded in the edit buffer, read a byte at a time from `40 00 nn` - the
     * documented readback channel for what the panel is showing. Answers in Voice and Song mode
     * and is silent in Performance mode, so null means "could not tell", never "no voice".
     */
    private suspend fun readEditBufferName(): String? {
        val bytes = ByteArray(MotifXsVoice.NAME_LENGTH)
        for (offset in 0 until MotifXsVoice.NAME_LENGTH) {
            val reply = try {
                exchange.exchange(
                    request = MotifXsSysEx.requestEditBufferNameByte(config.deviceNumber, offset),
                    what = "reading the loaded voice's name",
                    timeout = MODE_TIMEOUT,
                ) { m ->
                    MotifXsSysEx.typeOf(m) == MotifXsSysEx.TYPE_PARAM_CHANGE &&
                        MotifXsSysEx.addressOf(m) ==
                        Triple(MotifXsSysEx.COMMON_HI, MotifXsSysEx.COMMON_MID, offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            val at = MotifXsSysEx.REQUEST_ADDRESS_INDEX + 3
            if (reply.size <= at + 1) return null
            bytes[offset] = reply[at]
        }
        return String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000', ' ').ifEmpty { null }
    }

    // ---- PresetSelector ----

    /** Selection is three parameter sets at `65 00 00/01/02`. */
    override val selector: PresetSelector = object : PresetSelector {
        override suspend fun select(address: SlotAddress) {
            requireVoiceMode()
            val bank = config.banks.getOrNull(address.bank)
                ?: error("No bank ${address.bank}")
            val lsb = bank.selectLsb ?: error(
                "${bank.displayLabel.ifEmpty { bank.label }} has no known bank-select LSB, so it " +
                    "cannot be selected. Only banks with a known bank-select LSB can be " +
                    "selected; assuming one would select a different bank without saying so."
            )
            val sets = MotifXsSysEx.selectVoice(
                config.deviceNumber, lsb, address.slot, bank.selectMsb,
            )
            // The first two are told, not asked: only the third echo - the program - means the
            // selection landed.
            exchange.tell(sets[0])
            exchange.tell(sets[1])
            exchange.exchange(
                request = sets[2],
                what = "selecting ${layout.format.format(address)}",
                timeout = VOICE_TIMEOUT,
            ) { reply ->
                MotifXsSysEx.isSelectEcho(reply) &&
                    reply.size > 8 && (reply[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_PROGRAM
            }
        }
    }

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
     * Reads one bank on demand, for a bank a full [index] would skip. Same streaming shape and
     * per-slot failure isolation; the debug menu uses it to check one factory bank against the
     * shipped table, which costs that bank's dumps rather than a walk of the instrument.
     */
    fun indexBank(bank: Int): Flow<IndexUpdate> {
        val spec = config.banks.getOrNull(bank)
            ?: error("No bank $bank; this instrument has ${config.banks.size}")
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
        // A listing is the app's "re-read the instrument" gesture, and the favorite marks are
        // what a player can change behind its back from the front panel.
        (tagger as? MotifXsTagger)?.invalidate()
        return when (scope) {
            PresetScope.USER -> indexUser()
            PresetScope.FACTORY -> indexFactory()
            PresetScope.FAVORITES -> indexFavorites()
        }
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
            val label = spec.displayLabel.ifEmpty { spec.label }
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
                // Matched on the echoed address and on well-formedness, for the reasons readSlot
                // gives.
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
                throw IllegalStateException(
                    "$label's favorite marks arrived damaged (bad length or checksum) on every " +
                        "attempt", e
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
        // Set by the matcher when a reply arrived but was malformed, so exhausting the retries
        // reports "arrived damaged" rather than the silence a bare timeout would claim.
        var damaged = false
        val reply = try {
            exchange.exchange(
            MotifXsSysEx.requestDump(config.deviceNumber, bank.addressHi, bank.addressMid, address.slot),
            what = "reading voice $displayId",
            timeout = VOICE_TIMEOUT,
        ) { message ->
            // Matched on the echoed address, not merely the type: a dump takes 160 ms and a drum
            // voice a second, so a late reply to a previous request would put one voice's name on
            // another's row. Well-formedness is part of the match too, so a truncated dump is
            // retried rather than accepted as the reply.
            MotifXsSysEx.typeOf(message) == MotifXsSysEx.TYPE_BULK_DUMP &&
                MotifXsSysEx.addressOf(message) ==
                Triple(bank.addressHi, bank.addressMid, address.slot) &&
                MotifXsSysEx.isWellFormedBulkDump(message).also { intact ->
                    if (!intact) damaged = true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (damaged) {
                throw IllegalStateException(
                    "voice $displayId arrived damaged (bad length or checksum) on every attempt", e
                )
            }
            throw e
        }

        val payload = MotifXsSysEx.dumpPayload(reply)
        return PresetSlot(
            address = address,
            displayId = displayId,
            // The display label, matching what SlotLayout's BankSpec carries: the browser groups
            // by this string, so a disagreement gives every bank two headers.
            bankLabel = bank.displayLabel.ifEmpty { bank.label },
            name = MotifXsVoice.nameOf(payload),
            // The categories sit in front of the name in the bytes this dump already carried -
            // see [MotifXsVoice.categoriesOf].
            badges = categoryBadges(MotifXsVoice.categoriesOf(payload)),
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
        bankLabel = spec.displayLabel.ifEmpty { spec.label },
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

        /** What [identity] reports when the identity inquiry went unanswered but the instrument answered otherwise. */
        const val UNKNOWN_FIRMWARE = "unknown"

        /** Emitting every 8 voices fills the list without a recomposition per round trip - at ~160 ms each, a batch every ~1.3 s. */
        private const val BATCH_SIZE = 8

        /** Sized for the slowest voice: a drum voice is ~12.6 kB and takes ~1.02 s against a user voice's ~160 ms. */
        private val VOICE_TIMEOUT = 5.seconds

        /**
         * A write's acknowledgement takes ~158 ms for a 1,916-byte voice. Generous, since too
         * short would report a slow but accepted write as refused while it is still pending
         * inside the instrument.
         */
        private val WRITE_TIMEOUT = 5.seconds

        /** The commit is a flash write - ~160 ms measured, against ~19 ms for a data dump. */
        private val COMMIT_TIMEOUT = 5.seconds

        /** One parameter request; the mode reply comes back in ~10 ms. */
        private val MODE_TIMEOUT = 2.seconds

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
        private val MODE_SETTLE_INTERVAL = 250.milliseconds
        private val MODE_SETTLE_BUDGET = 3.seconds


        /** A whole documented block sequence, not each message: the read takes ~1.4 s and the write ends in a flash commit. */
        private val SEQUENCE_TIMEOUT = 10.seconds

        /** Pacing between the blocks of a write sequence, matching the vendor editor's spacing. */
        private val BLOCK_GAP = 15.milliseconds
    }
}

/**
 * Renders a voice address the way the instrument's own display does: `USER 3 - D:05`, not a flat
 * slot number, since the Motif XS groups its banks into lettered sets of sixteen. [parse] also
 * accepts the flat `USR3:053` form as a shorthand.
 */
class MotifXsAddressFormat(
    private val bankLabels: List<String>,
    private val displayLabels: List<String> = bankLabels,
) : AddressFormat {

    override fun format(address: SlotAddress): String {
        val group = 'A' + (address.slot / GROUP_SIZE)
        val within = (address.slot % GROUP_SIZE) + 1
        return "${displayLabel(address.bank)} - $group:${within.toString().padStart(2, '0')}"
    }

    override fun parse(id: String): SlotAddress {
        val text = id.trim()
        GROUPED.matchEntire(text)?.let { match ->
            val (label, group, within) = match.destructured
            val bank = displayLabels.indexOfFirst { it.equals(label.trim(), ignoreCase = true) }
                .takeIf { it >= 0 }
                ?: bankLabels.indexOfFirst { it.equals(label.trim(), ignoreCase = true) }
            require(bank >= 0) { "Invalid voice id '$id': no bank called '${label.trim()}'" }
            val number = within.toInt()
            require(number in 1..GROUP_SIZE) {
                "Invalid voice id '$id': group positions run 1..$GROUP_SIZE"
            }
            return SlotAddress(bank, (group[0].uppercaseChar() - 'A') * GROUP_SIZE + number - 1)
        }
        val (label, slot) = text.split(':', limit = 2).let {
            require(it.size == 2) {
                "Invalid voice id '$id'; expected 'USER 3 - D:05' or 'USR3:053'"
            }
            it[0] to it[1]
        }
        val bank = bankLabels.indexOfFirst { it.equals(label, ignoreCase = true) }
        require(bank >= 0) { "Invalid voice id '$id': no bank called '$label'" }
        val number = slot.toIntOrNull() ?: throw IllegalArgumentException("Invalid voice id '$id'")
        require(number >= 1) { "Invalid voice id '$id': slot numbers start at 1" }
        return SlotAddress(bank, number - 1)
    }

    /** The display label, which is what the rows carry and the bank headers group by. */
    override fun bankLabel(bank: Int): String = displayLabel(bank)

    private fun displayLabel(bank: Int): String =
        displayLabels.getOrElse(bank) { bankLabels.getOrElse(bank) { "?" } }

    private companion object {
        /** Sixteen slots per lettered group, what the instrument's own A..H buttons select. */
        const val GROUP_SIZE = 16
        val GROUPED = Regex("""(.+?)\s*-\s*([A-Ha-h]):(\d{1,2})""")
    }
}
