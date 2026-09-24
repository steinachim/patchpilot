// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.BuildConfig
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "Pro800Editor"

/**
 * Rename, move, swap and delete on an instrument that has none of those commands: every one is
 * composed from reads and writes, and every one has a moment where a preset exists in exactly one
 * place. Four rules follow:
 *
 *  1. Verify by reading back after every write; the write's own status carries no address and is
 *     not waited for (see [Pro800Instrument.write]).
 *  2. Order the destructive step last: a move writes the destination and only then erases the
 *     source, so an interruption leaves a duplicate rather than a hole.
 *  3. Roll back what cannot be finished: a swap whose second write fails puts the first back.
 *  4. Keep what was overwritten in [undoBuffer] for the session.
 */
class Pro800Editor(
    private val transfer: PresetTransfer,
    private val layout: SlotLayout,
) : PresetEditor {

    override val supported = setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    /** All of them. The instrument has no single command for any of these. */
    override fun isEmulated(op: EditOp) = true

    /** The name field is sixteen characters wide; the instrument keeps the first sixteen of anything longer. */
    override val maxNameLength = Pro800ProgramFields.NAME_LENGTH

    /**
     * The bytes that were at an address before this session overwrote them, newest first. If a
     * rollback itself fails, the only copy of the overwritten preset is here.
     */
    private val _undoBuffer = ArrayDeque<UndoEntry>()
    val undoBuffer: List<UndoEntry> get() = _undoBuffer.toList()

    data class UndoEntry(val address: SlotAddress, val displayId: String, val blob: ByteArray) {
        // Content equality for the array member.
        override fun equals(other: Any?) =
            other is UndoEntry && address == other.address && blob.contentEquals(other.blob)

        override fun hashCode() = 31 * address.hashCode() + blob.contentHashCode()
    }

    // ---- Operations ----

    override suspend fun rename(address: SlotAddress, newName: String) {
        require(newName.isNotBlank()) { "A preset name must not be blank." }
        require(newName.all { it.code in 0x20..0x7E }) { "A preset name must be plain ASCII." }
        require(newName.length <= Pro800ProgramFields.NAME_LENGTH) {
            "A preset name can be at most ${Pro800ProgramFields.NAME_LENGTH} characters; " +
                "'$newName' is ${newName.length}."
        }

        val before = readProgram(address)
        if (before.isEmpty) throw InstrumentException.NotSupported("rename an empty slot")
        writeVerified(address, before.withName(newName), expectName = newName)
    }

    override suspend fun delete(address: SlotAddress) {
        val before = readProgram(address)
        if (before.isEmpty) return // already nothing there; deleting it again is not an error
        remember(address, before)
        writeEmptyVerified(address)
    }

    /** Copy to the destination, then erase the source: if the erase fails, the preset exists at both addresses. */
    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        if (from == to) return
        val source = readProgram(from)
        if (source.isEmpty) throw InstrumentException.NotSupported("move an empty slot")
        val destination = readProgram(to)
        if (!destination.isEmpty) {
            throw InstrumentException.NotSupported("move onto an occupied slot - use swap instead")
        }

        remember(from, source)
        writeVerified(to, source, expectName = source.name)
        writeEmptyVerified(from)
    }

    /**
     * Duplicates a preset into an empty slot, leaving the source where it is. Writes the
     * destination and nothing else, so it needs no rollback and no undo entry. The returned name
     * is the source's, verbatim: the instrument stores exactly the bytes it is sent, and two
     * presets may share a name. An unnamed preset reports [Pro800Instrument.UNNAMED], as the
     * browser does for the same record.
     */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        if (src == dst) throw InstrumentException.NotSupported("copy a preset onto itself")

        val source = readProgram(src)
        if (source.isEmpty) throw InstrumentException.NotSupported("copy an empty slot")

        // Nothing on the instrument refuses an occupied destination, so this has to.
        val destination = readProgram(dst)
        if (!destination.isEmpty) {
            throw InstrumentException.NotSupported("copy onto an occupied slot")
        }

        writeVerified(dst, source, expectName = source.name)
        return source.name ?: Pro800Instrument.UNNAMED
    }

    /** Two writes, with the first undone if the second fails: once `a` holds `b`'s contents, `a`'s are only in memory. */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        if (a == b) return
        val first = readProgram(a)
        val second = readProgram(b)
        if (first.isEmpty && second.isEmpty) return

        remember(a, first)
        remember(b, second)

        writeOrEmpty(a, second)
        try {
            writeOrEmpty(b, first)
        } catch (e: Exception) {
            rollback(a, first, becauseOf = e)
            throw e
        }
    }

    // ---- Write plumbing ----

    private suspend fun writeOrEmpty(address: SlotAddress, program: Pro800Program) {
        if (program.isEmpty) writeEmptyVerified(address) else writeVerified(address, program, program.name)
    }

    /**
     * Writes a record and proves it landed: the slot is occupied, and the bytes agree with what
     * was sent over the common prefix. The instrument truncates trailing padding, so a record
     * written at full length legitimately comes back shorter.
     */
    private suspend fun writeVerified(address: SlotAddress, program: Pro800Program, expectName: String?) {
        val displayId = layout.format.format(address)
        transfer.write(address, Pro800ProgramCodec.encode(program.dense))
        settle()

        val readBack = readProgram(address)
        if (readBack.isEmpty) {
            throw InstrumentException.ProtocolDesync(
                "Wrote $displayId but the instrument reports it as empty. The preset may not have " +
                    "been stored.",
            )
        }
        // The name field as written and as stored: only the bytes tell the instrument declining
        // a character from this app mis-encoding one. Debug-only, since it is preset content.
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "$displayId name field: wrote ${nameFieldHex(program)} ('$expectName') " +
                    "read ${nameFieldHex(readBack)} ('${readBack.name}')",
            )
        }

        val common = minOf(readBack.dense.size, program.dense.size)
        if (!readBack.dense.copyOf(common).contentEquals(program.dense.copyOf(common))) {
            throw InstrumentException.ProtocolDesync(
                "Wrote $displayId but reading it back gave different data.",
            )
        }
        if (expectName != null && readBack.name != expectName) {
            // The record verified above, so only the name came back different.
            throw InstrumentException.ProtocolDesync(
                "$displayId was saved, but the instrument stored its name as " +
                    "'${readBack.name}' rather than '$expectName'. It may not accept every " +
                    "character.",
            )
        }
    }

    /** Sets an address back to uninitialized - a `0x78` with no data - and proves it took. */
    private suspend fun writeEmptyVerified(address: SlotAddress) {
        val displayId = layout.format.format(address)
        transfer.write(address, ByteArray(0))
        settle()

        if (!readProgram(address).isEmpty) {
            throw InstrumentException.ProtocolDesync(
                "Asked the instrument to clear $displayId but it still reports a preset there.",
            )
        }
    }

    /**
     * Best-effort restore of an address this class has just overwritten. Under [NonCancellable],
     * since a cancelled caller would otherwise skip it; a failure is logged rather than thrown,
     * so it cannot mask the error that caused the rollback.
     */
    private suspend fun rollback(address: SlotAddress, original: Pro800Program, becauseOf: Exception) {
        withContext(NonCancellable) {
            try {
                writeOrEmpty(address, original)
                Log.w(TAG, "Rolled ${layout.format.format(address)} back after: ${becauseOf.message}")
            } catch (e: Exception) {
                // The one path that can lose data; the original bytes are still in the undo buffer.
                Log.e(
                    TAG,
                    "Could not roll ${layout.format.format(address)} back after a failed edit. " +
                        "Its previous contents are still in this session's undo buffer.",
                    e,
                )
            }
        }
    }

    /** The name field's raw bytes, for the log line above. */
    private fun nameFieldHex(program: Pro800Program): String {
        val start = Pro800ProgramFields.NAME_DENSE_OFFSET
        if (program.dense.size <= start) return "(record ends before the name field)"
        val end = minOf(Pro800ProgramFields.NAME_DENSE_END, program.dense.size)
        return program.dense.copyOfRange(start, end).joinToString(" ") { "%02x".format(it) }
    }

    private suspend fun readProgram(address: SlotAddress) =
        Pro800Program.fromEncoded(transfer.read(address))

    private fun remember(address: SlotAddress, program: Pro800Program) {
        if (program.isEmpty) return
        _undoBuffer.addFirst(
            UndoEntry(address, layout.format.format(address), Pro800ProgramCodec.encode(program.dense)),
        )
        while (_undoBuffer.size > UNDO_DEPTH) _undoBuffer.removeLast()
    }

    /** The pause the reference implementation leaves between writes, taken here before reading back. */
    private suspend fun settle() = delay(WRITE_SETTLE_MS)

    private companion object {
        const val WRITE_SETTLE_MS = 20L

        /** A few kilobytes at ~200 bytes per preset. */
        const val UNDO_DEPTH = 32
    }
}
