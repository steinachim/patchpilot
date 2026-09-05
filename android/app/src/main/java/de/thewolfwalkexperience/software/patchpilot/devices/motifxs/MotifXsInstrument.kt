package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import de.thewolfwalkexperience.software.patchpilot.core.Bus

private const val TAG = "MotifXsInstrument"

/**
 * The Yamaha Motif XS: browses every voice bank, selects, and rearranges the user ones.
 *
 * Selecting, moving, copying, swapping and deleting voices are all supported; renaming a stored
 * voice goes through Yamaha's documented bulk-dump path (see [MotifXsEditor.rename]). Facets that
 * are not implemented return null, which is what a null facet is for.
 *
 * **Writing is two steps, and the second is easy to miss.** A bulk dump to a stored-voice address
 * is acknowledged and held; `11 00 00` is what commits it. Everything about the write path here -
 * why the acknowledgement is checked, why all the writes precede a single commit, why a refusal
 * is silence - is in [MotifXsEditor] and in `MotifXsSysEx.storeMarker`.
 *
 * The instrument is invisible to `MidiManager`: its only interface is vendor-specific, so the app
 * reaches it over raw USB (see `UsbMidiBulkTransport`).
 */
class MotifXsInstrument(
    private val exchange: SysExExchange,
    private val config: MotifXsConfig,
    /** What [PresetEditor.delete] and [PresetEditor.move] write over a cleared slot, per kind of voice. */
    private val blanks: MotifXsBlanks,
    /**
     * The catalog entry's id and name, e.g. `"yamaha_motif_xs6"` / `"Yamaha Motif XS6"`.
     *
     * Constructor parameters rather than fields on [MotifXsConfig], because this class also has
     * to be buildable with no catalog entry at all - the JVM tests do exactly that, from a
     * hand-written [MotifXsConfig] - so they default to a generic placeholder rather than being
     * required.
     */
    private val descriptorId: String = "yamaha_motif_xs",
    private val catalogName: String = "Yamaha Motif XS",
) : Instrument, PresetBrowser {

    /**
     * Every voice bank the catalog declares: 416 slots across the four user banks (128, 128,
     * 128 and 32 for USER DR).
     *
     * Bank sizes differ, which is why this is built from [BankSpec]s rather than
     * [SlotLayout.uniform] - a shape that also has room for the instrument's eleven factory
     * banks (PRE DR, GM DR and the rest) if they are ever added to the catalog; see
     * [defaultAddresses] and [indexBank] for why they are not there today.
     *
     * Every catalogued bank is walked by a full [index]; see [MotifXsBank.indexByDefault].
     */
    override val layout = SlotLayout(
        // The *display* label, not the internal one: this is what the browser's headers and
        // rail show, and a row reading "Bank USR2" above ids reading "USER 2 - A:01" is the
        // instrument described two ways at once. `label` stays the catalog/internal shorthand.
        banks = config.banks.map { spec ->
            val shown = spec.displayLabel.ifEmpty { spec.label }
            BankSpec(
                label = shown,
                slotCount = spec.slotCount,
                shortLabel = spec.shortLabel.ifEmpty { shown },
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
            // USB, not MIDI. This instrument exposes no MIDIStreaming interface and gets no
            // MIDI port at all, so its catalog entry is a `usb` match and UsbHostDiscovery is what
            // finds it - see InstrumentDescriptor.MidiIdentity.portIndex's note. It reported
            // "MIDI" here for as long as the match had been USB, and nothing could notice while
            // this was a string.
            bus = Bus.USB,
            stableKey = "motifxs:$descriptorId",
        )

    override val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    override val browser: PresetBrowser get() = this

    override val editor: PresetEditor = MotifXsEditor()

    // Not implemented for this instrument; see the class doc.
    override val transfer: PresetTransfer? = null
    override val report: DeviceReporter? = null

    /**
     * Reads the identity reply, and refuses the session if the instrument answers nothing at all.
     *
     * **The identity reply itself stays best-effort** - it is a cosmetic field, and an instrument
     * that will not supply it still browses fine. What is not survivable is an instrument that
     * answers *nothing*, and this is the only place that can tell the difference cheaply.
     *
     * See [requireItIsListening] for what total silence means here and why it is worth a second
     * probe before blaming it on anything.
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
     * Turns "answers nothing" into the one explanation that actually fixes it.
     *
     * **A Motif XS routes MIDI to exactly one destination** - its DIN sockets, USB, or mLAN - and
     * set to any but USB it still enumerates, still gets permission, still opens its bulk
     * endpoints, and then ignores every byte the app sends. Nothing about the connection looks
     * wrong; the instrument is simply listening somewhere else. Before this check the session was
     * built anyway and the user got a working-looking app where each operation timed out
     * separately, none of them able to say why.
     *
     * **Two different probes have to be silent, not one.** The identity inquiry is a Universal
     * SysEx message and the mode request is Yamaha's own; one going unanswered is thin evidence,
     * and the identity reply in particular is allowed to be missing on an instrument that
     * otherwise works. Both going unanswered means nothing is getting through in either direction,
     * which is the only claim this makes.
     *
     * It is still an *inference from an absence* - a cable, or another application holding the
     * device, produces the same silence - so the message says what to check rather than asserting
     * what is wrong.
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
     * Copy, move, swap, delete and rename - all five **emulated** from reads and writes.
     *
     * Copy, move and swap share one primitive: a whole voice written back as a bulk dump to
     * `0C <bank> <slot>`. There is no move opcode and no delete opcode.
     *
     * Delete needs no edit-buffer write at all, being the same stored-slot write that clears a
     * move's source.
     *
     * Rename does not use that primitive at all - see [rename] for the documented write path it
     * uses instead. Sending parameter changes to rename the *edit buffer* never reaches the
     * stored slot; only the panel's own Store carries an edit buffer across, and that is not a
     * message a host can send.
     *
     * **Every operation is write, write, commit, verify** - and the commit is not optional. A
     * bulk dump to a stored-voice address is acknowledged but not applied until a separate commit
     * message writes it to flash; `MotifXsSysEx.storeMarker` is what sends that commit.
     *
     * Doing all the writes before the single commit is what makes these operations
     * **all-or-nothing**. A refused write is answered with silence and is not buffered, so if the
     * second write of a move or a swap is rejected, nothing has been committed and the instrument
     * is exactly as it was. The alternative - write, verify, write, verify - could stop half-way
     * with the source already cleared.
     *
     * The read-back happens after the commit rather than before it. The acknowledgement means
     * "accepted", but "accepted" is a claim about the message and not about the flash, and the
     * read-back is what tells the user the voice is really there.
     */
    private inner class MotifXsEditor : PresetEditor {
        override val supported =
            setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

        override val maxNameLength = MotifXsVoice.NAME_LENGTH

        /** Both are composed host-side, so a failure can leave the instrument part-way. */
        override fun isEmulated(op: EditOp) = true

        /**
         * Renames a stored voice by Yamaha's **documented** write path, not the vendor editor's
         * undocumented extension.
         *
         * Sending parameter changes to the current voice's Common block renames only the edit
         * buffer: the stored slot stays byte-identical, and a store marker does not change it.
         * Only the panel's own Store carries an edit buffer to a slot, and that is not a message
         * a host can send.
         *
         * Yamaha's documented route reaches the stored slot directly:
         *
         *   1. read the **stored** voice at Bulk Header `0E mm nn` - 26 messages, 1,762 data bytes;
         *   2. patch the first 20 bytes of the Common block, which is the name, fixed width;
         *   3. send the 24 blocks back between a header and a **footer**, which saves to Flash ROM.
         *
         * The write survives a power cycle, and every block but the Common one comes back
         * byte-identical to its source.
         *
         * **This is the one edit here that is not built on `0C`.** Every other operation in this
         * class writes a whole `0C mm nn` payload and commits it with `11 00 00`; this one uses the
         * documented blocks and the documented footer, and needs no store marker at all. Two
         * independent write paths, two independent commits - see [commit]'s note.
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

            val bank = config.banks[address.bank]
            val where = layout.format.format(address)

            // Refused, for the same reason `copyProgram` refuses an empty source. An empty slot
            // still answers the documented read with a full 26-message sequence, so this would
            // *succeed* - and leave a named voice with nothing in it, which is not what anyone
            // means by "rename". The browser hides empty slots by default, so it would also be
            // an edit whose result the user could not see.
            val previousName = MotifXsVoice.nameOf(readPayload(address))
                ?: throw InstrumentException.NotSupported("rename an empty slot")

            val blocks = readStoredVoice(address)

            // Rebuilt, never echoed: the instrument sends model 0x0B and the host must send 0x03,
            // and the checksum covers that byte. The header and footer are ours, addressed at the
            // destination; only the blocks between them come off the instrument.
            val body = blocks
                .filter { !isHeaderOrFooter(it) }
                .map { block ->
                    if (MotifXsSysEx.isCommonBlock(block)) renamedCommonBlock(block, trimmed)
                    else MotifXsSysEx.rebuildForHost(config.deviceNumber, block)
                }
            // **No expected block count.** A Normal Voice is 24 blocks and a Drum Voice is 81,
            // and hard-coding either is what used to refuse the other. The shape comes from the
            // instrument and every byte but the name goes straight back, so the checks that
            // matter are about integrity rather than about recognising a layout: a header and a
            // footer arrived, every block is well-formed, and exactly one Common block is
            // present to patch. Those hold whatever the instrument sent.
            check(body.isNotEmpty()) { "the documented read of $where carried no blocks" }

            val sequence = buildList {
                add(MotifXsSysEx.bulkHeader(config.deviceNumber, bank.addressMid, address.slot))
                addAll(body)
                add(MotifXsSysEx.bulkFooter(config.deviceNumber, bank.addressMid, address.slot))
            }
            try {
                exchange.exchangeAfterAll(
                    messages = sequence,
                    what = "renaming $where",
                    timeout = SEQUENCE_TIMEOUT,
                    gap = BLOCK_GAP,
                ) { MotifXsSysEx.isAck(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "The instrument did not acknowledge the rename of $where. The sequence was " +
                        "sent whole, so it has either been stored or refused outright - the " +
                        "instrument's own screen says which. Re-read the bank before trusting " +
                        "what the app shows.",
                    e,
                )
            }

            // Read back through `0C`, the path the browser itself lists with, so what is verified
            // is what the user will see rather than what was just written.
            val stored = MotifXsVoice.nameOf(readPayload(address))
            check(stored == trimmed) {
                "renamed $where but it reads back as ${stored ?: "empty"}"
            }

            reloadIfShowing(address, previousName)
        }

        /**
         * Re-selects the voice if the **instrument's own panel** is still showing the old name.
         *
         * The documented write stores to Flash and does not touch the edit buffer, so a player
         * who had the renamed voice loaded goes on seeing its old name until something reloads
         * it. That is faithful to the protocol and confusing to look at.
         *
         * **Detection, not assumption.** Re-selecting unconditionally would yank a player onto a
         * voice they had not chosen, so this reloads only when the edit buffer's name still
         * matches the one just replaced. Where two slots share a name it can reload the renamed
         * one instead of the identically-named one that was loaded - visible, harmless and not
         * destructive, which is the right side to err on.
         *
         * **Cosmetic, so it never fails the rename.** The write is committed and verified by the
         * time this runs; anything that goes wrong here is logged and swallowed.
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

        /** Header and footer bracket the sequence and carry the slot; the blocks do not. */
        private fun isHeaderOrFooter(message: ByteArray): Boolean =
            MotifXsSysEx.addressOf(message)?.first
                .let { it == MotifXsSysEx.BULK_HEADER_HI || it == MotifXsSysEx.BULK_FOOTER_HI }

        /**
         * The Common block with its first 20 bytes replaced by [name], NUL padded.
         *
         * Padding is NUL rather than space, which is what the instrument itself does and what
         * revision C0 of the Data List permits - revision B0 says otherwise and is wrong.
         */
        private fun renamedCommonBlock(block: ByteArray, name: String): ByteArray {
            // Re-addressed to whichever Common block this is - 40 00 00 for a Normal Voice,
            // 46 00 00 for a Drum. Rebuilding it at a fixed address would send a drum kit's
            // Common to a normal voice's address.
            val (hi, mid, lo) = MotifXsSysEx.addressOf(block)
                ?: error("the Common block carries no address")
            val payload = MotifXsSysEx.dumpPayload(block).copyOf()
            check(payload.size >= MotifXsVoice.NAME_LENGTH) {
                "the Common block is ${payload.size} bytes, too short to hold a name"
            }
            for (i in 0 until MotifXsVoice.NAME_LENGTH) {
                payload[i] = if (i < name.length) name[i].code.toByte() else 0
            }
            return MotifXsSysEx.bulkDump(config.deviceNumber, hi, mid, lo, payload)
        }

        /**
         * The documented read: one request, 26 messages back, ending at the footer.
         *
         * Every block is checked for structural soundness before any of it is sent back, because
         * this is the only place a rename can still be aborted safely - once the header goes out
         * the instrument is committed to receiving a sequence (see [SysExExchange.exchangeAfterAll]).
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
            // Exactly one, because that is what the name patch assumes. "At least one" would
            // let an unrecognised sequence through and rename only the first block of it.
            check(blocks.count { MotifXsSysEx.isCommonBlock(it) } == 1) {
                "the documented read of $where carried " +
                    "${blocks.count { MotifXsSysEx.isCommonBlock(it) }} Common blocks; expected " +
                    "exactly one, so there is no single name to patch"
            }
            return blocks
        }

        /**
         * Erases a slot by writing an initialised voice over it.
         *
         * **Exactly the operation [move] performs on its source.** Both carry the same risk and
         * the same verification.
         *
         * Note this is **not** the vendor editor's "Initialize Current Voice", which rewrites the
         * edit buffer block by block and touches no stored address. This writes the stored slot
         * directly, the way a move clears the slot it came from.
         */
        override suspend fun delete(address: SlotAddress) {
            requireWritable(address)
            val blank = blankPayloadFor(address.bank)
            write(address, blank)
            commit()
            verify(address, blank)
        }

        /**
         * Swap: read both, write each to the other's address.
         *
         * Needs no fabricated payload - every byte written was read off this instrument moments
         * earlier - which is why it is the safer of the two and why it is implemented first.
         */
        override suspend fun swap(a: SlotAddress, b: SlotAddress) {
            requireWritable(a)
            requireWritable(b)
            requireSameKind(a, b)
            if (a == b) return
            val payloadA = readPayload(a)
            val payloadB = readPayload(b)
            write(a, payloadB)
            write(b, payloadA)
            commit()
            verify(a, payloadB)
            verify(b, payloadA)
        }

        /**
         * Copy: write the source's bytes to an empty destination, commit, verify.
         *
         * **Strictly less than [move]** - the same write, the same commit, the same read-back,
         * without the second write that clears the source, since copy and move share the same
         * underlying primitive.
         *
         * This works both within a bank (`USR1:001` -> `USR1:127`) and across banks
         * (`USR3:105` -> `USR1:126`); the destination matches the source byte for byte in each.
         * Cross-bank needs no rewriting because the payload carries no address of its own - the
         * bank and slot live in the message header.
         *
         * **The returned name is the source's, verbatim.** The contract returns a name because a
         * Nord invents one, appending a disambiguator. This instrument has no copy command at all
         * and stores exactly the bytes it is sent, so both slots end up with the same name and
         * that is fine here: the address is the identity. [verify] has already compared the
         * destination's bytes against the source's, so the name is a verified fact.
         */
        override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
            requireWritable(dst)
            requireSameKind(src, dst)
            if (src == dst) throw InstrumentException.NotSupported("copy a voice onto itself")

            val payload = readPayload(src)
            val name = MotifXsVoice.nameOf(payload)
                ?: throw InstrumentException.NotSupported("copy an empty slot")

            // Refused, not overwritten. The contract says "into the empty slot dst"; composed
            // host-side, nothing enforces that unless this does, and the instrument certainly
            // will not - a well-formed dump to an occupied writable slot is simply accepted.
            if (MotifXsVoice.nameOf(readPayload(dst)) != null) {
                throw InstrumentException.NotSupported("copy onto an occupied slot")
            }

            write(dst, payload)
            commit()
            verify(dst, payload)
            return name
        }

        /**
         * Move: write the voice to the destination, then clear the source.
         *
         * The clear needs an **initialised voice payload**, and this app does not have one - the
         * vendor's editor sent a 1,903-byte one it built itself. Rather than fabricate bytes that
         * have never been on this wire, the payload is taken from a slot the instrument itself
         * reports empty. If the bank holds none, the move is refused rather than half-done.
         *
         * Both writes are offered before the single commit, which makes the move atomic: if the
         * source-clearing write is refused, nothing has been committed and the voice is still
         * exactly where it was. Destination first is kept anyway, so that a commit which somehow
         * lands partially leaves the voice in two places rather than none.
         */
        override suspend fun move(from: SlotAddress, to: SlotAddress) {
            requireWritable(from)
            requireWritable(to)
            requireSameKind(from, to)
            if (from == to) return
            val payload = readPayload(from)
            // Sourced before the first write, so a bank with no blank to copy fails before
            // anything has been changed rather than after the destination is already written.
            val blank = blankPayloadFor(from.bank)
            write(to, payload)
            write(from, blank)
            commit()
            verify(to, payload)
            verify(from, blank)
        }

        /**
         * Refuses an edit that would move a voice between a drum bank and a normal one.
         *
         * **Nothing below this app will stop it.** A drum kit and a normal voice are different
         * objects - ~12.6 kB against ~1.9 kB, and entirely different documented block sequences -
         * and the instrument *accepts a payload of the wrong kind without complaint*. Measured:
         * a normal voice offered to `0C 28 00` was acknowledged, and a drum kit to `0C 0A 7F`
         * likewise. Neither was committed, so what a commit would then make of the slot is
         * unknown and deliberately untested; the point is that the acknowledgement carries no
         * warning, so the app cannot learn about it by trying.
         *
         * This is the only cross-slot rule the *instrument* does not enforce for us, which is
         * exactly why it has to live here.
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

        private fun requireWritable(address: SlotAddress) {
            val bank = config.banks.getOrNull(address.bank) ?: error("No bank ${address.bank}")
            require(!bank.readOnly) {
                "${bank.displayLabel.ifEmpty { bank.label }} is a factory bank and cannot be written."
            }
        }

        private suspend fun readPayload(address: SlotAddress): ByteArray {
            val bank = config.banks[address.bank]
            val reply = exchange.exchange(
                request = MotifXsSysEx.requestDump(
                    config.deviceNumber, bank.addressHi, bank.addressMid, address.slot,
                ),
                what = "reading ${layout.format.format(address)}",
                timeout = VOICE_TIMEOUT,
                // Well-formedness is part of the match here too, so a truncated dump is retried
                // rather than accepted and then rejected - see readSlot. It matters more on this
                // path than on the browser's: these bytes get written straight back to the
                // instrument, so "damaged" would mean writing damage.
            ) { m ->
                MotifXsSysEx.typeOf(m) == MotifXsSysEx.TYPE_BULK_DUMP &&
                    MotifXsSysEx.isWellFormedBulkDump(m)
            }
            return MotifXsSysEx.dumpPayload(reply)
        }

        /**
         * An initialised-voice payload to write over a cleared slot, per kind of voice.
         *
         * The app has no way to build one itself - an initialised payload is a voice constructed
         * internally by the instrument's own firmware - so rather than fabricate bytes that have
         * never been on this wire, this is one shipped with the app: real instrument data, not
         * synthesized.
         */
        private fun blankPayloadFor(bank: Int): ByteArray = blanks.forBank(config.banks[bank])

        /**
         * Offers one slot's worth of new contents, and waits to hear it accepted.
         *
         * **Nothing is stored yet when this returns** - see [commit]. What it does establish is
         * that the instrument took the message, because a refusal is silence: a well-formed dump
         * to a read-only bank, a dump with a broken checksum, and a dump whose declared count
         * disagrees with its length are all answered with no acknowledgement at all, and none of
         * them is buffered.
         *
         * This used to be `exchange.tell` - fire and forget - which threw away the one signal the
         * write path has.
         *
         * **A failure here is not always "nothing happened".** A refused write leaves nothing
         * pending, but an *earlier* accepted write in the same operation is still held by the
         * instrument, and the next commit from anywhere applies it. The message says so, and says
         * what clears it: a power cycle discards everything uncommitted.
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
         * Commits every write offered since the last commit.
         *
         * `11 00 00`, a zero-payload bulk dump. Its acknowledgement takes ~160 ms against ~19 ms
         * for a data dump, which is the flash write happening.
         *
         * **Scope is "everything pending", not "the writes I just made".** That is worth stating
         * because an accepted-but-uncommitted write can survive being left abandoned - even
         * across the instrument being reconnected to a different host - and then get applied
         * later by an unrelated commit. It is also why [write] failing must abort the whole
         * operation rather than press on: an uncommitted write is not discarded, it is armed.
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
            val readBack = readPayload(address)
            check(readBack.contentEquals(payload)) {
                "wrote ${layout.format.format(address)} but read back something different " +
                    "(${payload.size} bytes out, ${readBack.size} back)"
            }
        }
    }

    // ---- Mode ----

    /**
     * Reads the instrument's current mode, or null if it will not say.
     *
     * One parameter request, ~10 ms. Best-effort by design: see [requireVoiceMode] for why a
     * silent instrument must not block a selection.
     */
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
     * Refuses a selection the instrument would silently ignore.
     *
     * Selection works in Voice mode only: in Performance and
     * Song mode the three parameter sets draw **no echo at all** and change nothing - not the
     * Performance, not any part's voice assignment. Before this check the app waited out its
     * timeout and reported "the instrument did not answer", which is true and useless; the player
     * needs to be told their instrument is in the wrong mode.
     *
     * **Refusing rather than switching is deliberate**, a decision deferred until the behaviour
     * was measured. The mode change is a documented message and would be easy
     * to send - but taking a player out of the Performance or Song they are using is a visible,
     * stateful change to their setup, and doing it silently to make a browser row work is a worse
     * failure than the one it fixes. The message says what to press.
     *
     * **An unreadable mode does not block anything.** If the instrument will not answer the mode
     * request, the selection is attempted anyway: this check exists to turn a silent failure into
     * an explanation, not to add a new way for a working instrument to be refused.
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
     * Sends the documented mode change and confirms it took.
     *
     * **Only ever called from a remedy the user has accepted.** The confirmation matters because
     * the mode change is a parameter *change*, which draws no reply of its own - so without
     * reading the mode back, a switch that did not happen would look exactly like one that did,
     * and the selection that follows would fail again for a reason the app had just claimed to
     * have fixed.
     */
    private suspend fun switchToVoiceMode() {
        exchange.tell(MotifXsSysEx.setMode(config.deviceNumber, MotifXsMode.VOICE))

        // **Poll until it settles; do not read it back once.** Changing mode is not instant - the
        // instrument tears down a Performance and loads a voice - and the first version read the
        // mode immediately after sending, got the *old* value every time, and reported a switch
        // that had plainly worked as having failed.
        //
        // Polled rather than given a fixed delay because a constant here is wrong in one
        // direction or the other: too short and this comes back, too long and every switch pays
        // for the worst case. The read costs ~10 ms, so asking repeatedly is cheaper than waiting
        // blindly.
        // Bounded by the clock, not by a poll count. Each read carries its own timeout *and*
        // retries, so an instrument that goes quiet while it switches - which is exactly when it
        // might - could otherwise stretch a handful of polls into most of a minute with the user
        // staring at a dialog.
        var last: MotifXsMode? = null
        val reachedVoice = withTimeoutOrNull(MODE_SETTLE_BUDGET) {
            while (true) {
                delay(MODE_SETTLE_INTERVAL)
                val now = readMode()
                if (now != null) {
                    last = now
                    if (!now.blocksVoiceSelection) return@withTimeoutOrNull true
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (reachedVoice == true) return

        // Never read at all: treated as "could not tell", the same as [requireVoiceMode] does, so
        // an instrument that will not answer is not accused of refusing.
        val stuck = last ?: return
        throw InstrumentException.BlockedByDeviceState(
            "The instrument was still in ${stuck.label} mode after being asked to switch. " +
                "Change it from the panel instead."
        )
    }

    /**
     * The voice currently loaded in the edit buffer, read a byte at a time from `40 00 nn`.
     *
     * This is the documented readback channel for what the *panel* is showing. Answers in Voice
     * and Song mode; **silent in Performance mode**, where the Normal Voice edit buffer is not
     * addressable at all - so a null here means "could not tell", never "no voice".
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

    /**
     * Selection is three parameter sets at `65 00 00/01/02`.
     */
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
            // The first two are told, not asked: each draws its own echo, but only the third
            // one - the program - means the selection actually landed. Waiting on the last is
            // what distinguishes "the instrument acted on it" from "the bytes left the building".
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

        /**
         * The instrument echoes each set back, so this can claim more than a Pro-800's "sent" -
         * but less than a Nord's verified read-back, because the echo is of the request rather
         * than of the resulting state.
         */
        override fun confirmationFor(displayId: String) = "Selected $displayId"
    }

    // ---- PresetBrowser ----

    /**
     * The addresses a full [index] walks: every bank the catalog declares.
     *
     * The instrument's eleven factory banks (1,217 read-only voices) are not in the catalog at
     * all yet, so there is nothing here to exclude - see [CatalogParsesTest] for why. Were they
     * added, they would need [MotifXsBank.indexByDefault] set to false: re-reading read-only
     * voices on every connect would cost about six minutes for nothing.
     */
    private fun defaultAddresses(): Sequence<SlotAddress> = sequence {
        config.banks.forEachIndexed { bank, spec ->
            if (spec.indexByDefault) {
                for (slot in 0 until spec.slotCount) yield(SlotAddress(bank, slot))
            }
        }
    }

    /**
     * Reads one bank on demand, for a bank with [MotifXsBank.indexByDefault] set to false that a
     * full [index] would skip.
     *
     * Same streaming shape and the same per-slot failure isolation as [index]; the only
     * difference is the range. No catalogued bank sets `indexByDefault` to false today, so this
     * is unreachable from the current catalog - it exists for when the factory banks are added
     * (see [layout]'s doc comment): a caller that opened a 64-slot PRE DR then would pay 64
     * dumps (~67 s), not a walk of the whole instrument.
     */
    fun indexBank(bank: Int): Flow<IndexUpdate> = flow {
        val spec = config.banks.getOrNull(bank)
            ?: error("No bank $bank; this instrument has ${config.banks.size}")
        val batch = mutableListOf<PresetSlot>()
        var done = 0
        for (slot in 0 until spec.slotCount) {
            val address = SlotAddress(bank, slot)
            val displayId = layout.format.format(address)
            try {
                batch += readSlot(address, displayId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Logged, not just counted. A Failed slot reaches the user as a number - "2 slots
                // could not be read" - which says nothing about *why*, and the difference between
                // a timeout and a malformed reply is the difference between a slow bus and a bug.
                Log.w(TAG, "Couldn't read $displayId", e)
                emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
            }
            done++
            if (batch.isNotEmpty()) {
                emit(IndexUpdate.Slots(batch.toList()))
                batch.clear()
            }
            emit(IndexUpdate.Progress(done, spec.slotCount, "Reading $displayId"))
        }
        emit(IndexUpdate.Complete)
    }

    /**
     * Walks every user voice, one dump request each.
     *
     * **This is slow and the streaming shape is not decorative here.** A normal voice is ~1.9 kB
     * and takes **158 ms**, a drum kit ~12.6 kB and **1,016 ms**, and the 416 user voices take
     * **~93 s** in total. The instrument paces its USB output at almost exactly four DIN MIDI
     * ports' aggregate rate (12,468 B/s), which no client-side change moves. Rows therefore have
     * to appear as they arrive, and an unreadable voice has to cost only itself rather than the
     * minute already spent.
     */
    override fun index(): Flow<IndexUpdate> = flow {
        val batch = mutableListOf<PresetSlot>()
        val total = defaultAddresses().count()
        var done = 0

        for (address in defaultAddresses()) {
            val displayId = layout.format.format(address)
            try {
                batch += readSlot(address, displayId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Logged, not just counted. A Failed slot reaches the user as a number - "2 slots
                // could not be read" - which says nothing about *why*, and the difference between
                // a timeout and a malformed reply is the difference between a slow bus and a bug.
                Log.w(TAG, "Couldn't read $displayId", e)
                emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
            }
            done++
            if (batch.size >= BATCH_SIZE || done == total) {
                emit(IndexUpdate.Progress(done, total, "Reading $displayId"))
                emit(IndexUpdate.Slots(batch.toList()))
                batch.clear()
            }
        }
        emit(IndexUpdate.Complete)
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot =
        readSlot(address, layout.format.format(address))

    private suspend fun readSlot(address: SlotAddress, displayId: String): PresetSlot {
        val bank = config.banks[address.bank]
        // Set by the matcher when a reply arrived but was malformed. Kept so that exhausting the
        // retries reports what actually happened - "arrived damaged" rather than the "did not
        // answer" a bare timeout would claim, which would send anyone debugging it after the bus
        // instead of after the data.
        var damaged = false
        val reply = try {
            exchange.exchange(
            MotifXsSysEx.requestDump(config.deviceNumber, bank.addressHi, bank.addressMid, address.slot),
            what = "reading voice $displayId",
            timeout = VOICE_TIMEOUT,
        ) { message ->
            // Matched on the echoed address, not merely the type: these dumps take 160 ms each
            // and a drum voice a full second, so a late reply to a previous request is a live
            // possibility rather than a theoretical one, and accepting it would put one voice's
            // name on another's row.
            //
            // **Well-formedness is part of the match, and that is the point.** It used to be
            // checked *after* the exchange returned, which meant a truncated dump was accepted as
            // "the reply", the retry machinery never saw it, and the slot failed permanently on
            // a fault that is transient by nature. On a live 416-voice scan that showed up as a
            // handful of slots reading "arrived damaged" with zero retries logged against them.
            // Rejecting it here instead lets `exchange` do what it is for.
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
            // Every attempt drew a malformed reply rather than none, so say so: a bare timeout
            // here would read as "the instrument did not answer", which is the wrong thing to
            // chase.
            if (damaged) {
                throw IllegalStateException(
                    "voice $displayId arrived damaged (bad length or checksum) on every attempt", e
                )
            }
            throw e
        }

        return PresetSlot(
            address = address,
            displayId = displayId,
            // The display label, matching what SlotLayout's BankSpec carries. These two are the
            // browser's only sources of rows and it groups by this string, so if they disagree
            // every bank gets two headers - one for the indexed rows, one for the placeholders.
            bankLabel = bank.displayLabel.ifEmpty { bank.label },
            name = MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(reply)),
        )
    }

    companion object {
        const val FAMILY = "motifxs"

        /** What [identity] reports when the identity inquiry went unanswered but the instrument
         * proved it is listening some other way. */
        const val UNKNOWN_FIRMWARE = "unknown"

        /** Emitting every 8 voices keeps the list visibly filling without a recomposition per
         * round trip - at ~160 ms each that is a batch roughly every 1.3 seconds. */
        private const val BATCH_SIZE = 8

        /**
         * Sized for the slowest voice, not the typical one.
         *
         * A user voice arrives in ~160 ms, but a **drum voice takes ~1.02 s** - it is ~12.6 kB
         * against ~1.9 kB. Three seconds would be only 3x headroom on exactly the messages most
         * likely to be slow, and a timeout here costs a whole voice.
         */
        private val VOICE_TIMEOUT = 5.seconds

        /**
         * A write's acknowledgement takes ~158 ms for a 1,916-byte voice.
         *
         * Generous because the cost of being wrong is asymmetric: too short and a slow but
         * accepted write is reported as refused, after which the app's own model of the
         * instrument is wrong while the write is still pending inside it.
         */
        private val WRITE_TIMEOUT = 5.seconds

        /** The commit is a flash write - ~160 ms measured, against ~19 ms for a data dump. */
        private val COMMIT_TIMEOUT = 5.seconds

        /** One parameter request; the mode reply comes back in ~10 ms. */
        private val MODE_TIMEOUT = 2.seconds

        /**
         * How long to wait for a mode change to take effect, as interval x count.
         *
         * Three seconds of wall clock, asked every 250 ms. Generous because the cost of being
         * wrong is asymmetric and this is the failure that was actually shipped: too short and
         * the app tells the user their switch failed while they watch it succeed on the panel,
         * whereas too long only delays a message nobody wants to see anyway. The loop exits as
         * soon as the instrument reports Voice, so a fast switch pays a single interval.
         */
        private val MODE_SETTLE_INTERVAL = 250.milliseconds
        private val MODE_SETTLE_BUDGET = 3.seconds


        /**
         * A whole documented block sequence - 26 messages in, or 26 out.
         *
         * Bounds the **sequence**, not each message. The read takes ~1.4 s and the write ends in
         * a flash commit, so this is roughly 5x headroom on the slower of the two.
         */
        private val SEQUENCE_TIMEOUT = 10.seconds

        /**
         * Pacing between the blocks of a write sequence, matching the spacing the vendor editor
         * uses between blocks.
         */
        private val BLOCK_GAP = 15.milliseconds
    }
}

/**
 * Renders a voice address the way the instrument's own display does: `USER 3 - D:05`.
 *
 * **Not a flat slot number.** The Motif XS groups its banks into lettered sets of sixteen, so
 * what this project calls `USR3:053` the instrument's own display shows as `USER 3 - D:05`, and
 * `DRUM:031` as `USER DR - B:15`. A browser whose row labels cannot be found on the instrument is
 * a browser you have to translate by hand, which is most of the value gone.
 *
 * [parse] still accepts the flat `USR3:053` form as well, as a plain shorthand for the same
 * address.
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
            return SlotAddress(bank, (group[0] - 'A') * GROUP_SIZE + number - 1)
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

    override fun bankLabel(bank: Int): String = bankLabels.getOrElse(bank) { "?" }

    private fun displayLabel(bank: Int): String =
        displayLabels.getOrElse(bank) { bankLabels.getOrElse(bank) { "?" } }

    private companion object {
        /** Sixteen slots per lettered group, which is what the instrument's own A..H buttons select. */
        const val GROUP_SIZE = 16
        val GROUPED = Regex("""(.+?)\s*-\s*([A-Ha-h]):(\d{1,2})""")
    }
}
