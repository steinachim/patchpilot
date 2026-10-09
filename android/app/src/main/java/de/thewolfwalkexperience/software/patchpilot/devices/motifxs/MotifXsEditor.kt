// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.AddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.BankSpec
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MotifXsEditor"

/**
 * Copy, move, swap, delete and rename - all five emulated from reads and writes.
 *
 * Copy, move, swap and delete share one primitive: a whole voice written back as a bulk dump
 * to `0C <bank> <slot>`. Rename uses the documented path instead (see [rename]), since
 * parameter changes reach only the edit buffer and no host message carries that to a slot.
 *
 * Every operation is write, write, commit, verify. All the writes precede the single commit: a
 * refused write is answered with silence and is not buffered, so a rejected second write stops
 * the operation before anything is committed. That holds only until the next commit, though: a
 * write accepted before the refusal stays held, and the next commit from any edit applies it
 * (see [write]). The read-back comes after the commit, since an acknowledgement is a claim about
 * the message and not about the flash.
 */
internal class MotifXsEditor(private val motif: MotifXsInstrument) : PresetEditor {
    override val supported =
        setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    override val maxNameLength = MotifXsVoice.NAME_LENGTH

    /** All five are composed host-side, so a failure can leave the instrument part-way. */
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
        motif.requireWritable(address)
        // Printable ASCII is the field's own range - the Data List gives `00, 20 - 7E` - so a
        // name the instrument cannot store is refused here rather than written as mojibake.
        nameProblem(newName)?.let { throw IllegalArgumentException(it) }

        val where = motif.layout.format.format(address)

        // An empty slot still answers the documented read with a full sequence, so without
        // this a rename would succeed and leave a named voice with nothing in it.
        val previousName = MotifXsVoice.nameOf(motif.readVoicePayload(address))
            ?: throw InstrumentException.SlotStateChanged.empty(where)

        motif.editCommonBlock(address, "renaming $where", "rename of $where") { payload ->
            if (payload.size < MotifXsVoice.NAME_LENGTH) {
                throw InstrumentException.ProtocolDesync(
                    "The Common block of $where is ${payload.size} bytes, too short to hold a name.",
                )
            }
            // NUL-padded, as the instrument itself does and revision C0 of the Data List
            // permits; revision B0 says space-padded and does not match the instrument.
            for (i in 0 until MotifXsVoice.NAME_LENGTH) {
                payload[i] = if (i < newName.length) newName[i].code.toByte() else 0
            }
        }

        // Read back through `0C`, the path the browser lists with.
        val stored = MotifXsVoice.nameOf(motif.readVoicePayload(address))
        if (stored != newName) {
            throw InstrumentException.ProtocolDesync(
                "Renamed $where but it reads back as ${stored ?: "empty"}.",
            )
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
            if (motif.readMode() != MotifXsMode.VOICE) return
            if (motif.readEditBufferName() != previousName) return
            motif.selector.select(address)
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
        motif.requireWritable(address)
        val blank = blankPayloadFor(address.bank)
        committed {
            write(address, blank)
        }
        verify(address, blank)
    }

    /** Swap: read both, write each to the other's address. Needs no blank payload. */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        motif.requireWritable(a)
        motif.requireWritable(b)
        requireSameKind(a, b)
        if (a == b) return
        val payloadA = motif.readVoicePayload(a)
        val payloadB = motif.readVoicePayload(b)
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
        motif.requireWritable(dst)
        requireSameKind(src, dst)
        if (src == dst) throw InstrumentException.NotSupported("copy a voice onto itself")

        val payload = motif.readVoicePayload(src)
        val name = MotifXsVoice.nameOf(payload)
            ?: throw InstrumentException.SlotStateChanged.empty(motif.layout.format.format(src))

        // The instrument accepts a well-formed dump to an occupied writable slot, so this
        // has to refuse one.
        if (MotifXsVoice.nameOf(motif.readVoicePayload(dst)) != null) {
            throw InstrumentException.SlotStateChanged.occupied(motif.layout.format.format(dst))
        }

        committed {
            write(dst, payload)
        }
        verify(dst, payload)
        return name
    }

    /**
     * Move: write the voice to an empty destination, then clear the source with the shipped
     * blank ([blankPayloadFor]). Both writes precede the single commit, so a refused clear
     * leaves nothing committed; destination first, so a partial commit would leave the voice
     * in two places rather than none.
     */
    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        motif.requireWritable(from)
        motif.requireWritable(to)
        requireSameKind(from, to)
        if (from == to) return
        val payload = motif.readVoicePayload(from)
        if (MotifXsVoice.nameOf(payload) == null) {
            throw InstrumentException.SlotStateChanged.empty(motif.layout.format.format(from))
        }
        // As in [copyProgram]: the instrument would overwrite an occupied slot without
        // complaint, and a listing out of date offers one as empty.
        if (MotifXsVoice.nameOf(motif.readVoicePayload(to)) != null) {
            throw InstrumentException.SlotStateChanged.occupied(motif.layout.format.format(to))
        }
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
        val source = motif.config.banks[from.bank]
        val target = motif.config.banks[to.bank]
        if (source.isDrum == target.isDrum) return
        val kind = { b: MotifXsBank -> if (b.isDrum) "a drum kit" else "a normal voice" }
        throw InstrumentException.NotSupported(
            "put ${kind(source)} into a slot that holds ${kind(target)} - " +
                "${source.shownLabel} and " +
                "${target.shownLabel} store different kinds of " +
                "voice, and the instrument does not refuse the mismatch itself",
        )
    }

    /** An initialised-voice payload to write over a cleared slot, shipped with the app rather than fabricated. */
    private fun blankPayloadFor(bank: Int): ByteArray = motif.blanks.forBank(motif.config.banks[bank])

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
        val bank = motif.config.banks[address.bank]
        val where = motif.layout.format.format(address)
        try {
            motif.exchange.exchange(
                request = MotifXsSysEx.bulkDump(
                    motif.config.deviceNumber,
                    bank.addressHi,
                    bank.addressMid,
                    address.slot,
                    payload,
                ),
                what = "writing $where",
                timeout = MotifXsInstrument.WRITE_TIMEOUT,
            ) { MotifXsSysEx.isAck(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException.Timeout) {
            // Silence is how the instrument refuses a write.
            throw InstrumentException.Timeout(
                "writing $where",
                e,
                explanation = "The instrument refused the write to $where, so nothing has " +
                    "been stored. ${MotifXsInstrument.ARMED_WRITE_WARNING}",
            )
        } catch (e: Exception) {
            throw InstrumentException.TransportLost(
                "The write to $where failed (${e.message}), so nothing has been stored. " +
                    MotifXsInstrument.ARMED_WRITE_WARNING,
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
            motif.exchange.exchange(
                request = MotifXsSysEx.storeMarker(motif.config.deviceNumber),
                what = "committing the write",
                timeout = MotifXsInstrument.COMMIT_TIMEOUT,
            ) { MotifXsSysEx.isAck(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException.Timeout) {
            throw InstrumentException.Timeout(
                "committing the write",
                e,
                explanation = "The instrument did not confirm the commit, so the writes may or " +
                    "may not have been stored. Re-read the bank before trusting what it shows.",
            )
        } catch (e: Exception) {
            throw InstrumentException.TransportLost(
                "The commit failed (${e.message}), so the writes may or may not have been " +
                    "stored. Re-read the bank before trusting what it shows.",
                e,
            )
        }
    }

    /** Reads a slot back and insists it holds what was written. */
    private suspend fun verify(address: SlotAddress, payload: ByteArray) {
        val readBack = motif.readVoicePayload(address)
        if (!readBack.contentEquals(payload)) {
            throw InstrumentException.ProtocolDesync(
                "Wrote ${motif.layout.format.format(address)} but read back something different " +
                    "(${payload.size} bytes out, ${readBack.size} back).",
            )
        }
    }
}
