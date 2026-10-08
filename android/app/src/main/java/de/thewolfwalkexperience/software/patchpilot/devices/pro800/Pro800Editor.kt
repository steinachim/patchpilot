// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.util.Log
import androidx.annotation.VisibleForTesting
import de.thewolfwalkexperience.software.patchpilot.BuildConfig
import de.thewolfwalkexperience.software.patchpilot.core.ComposedEditor
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import kotlinx.coroutines.delay

private const val TAG = "Pro800Editor"

/**
 * Every edit on an instrument that has none of them as a command, composed by [ComposedEditor]
 * from reads and writes. Two rules on top of its transaction model:
 *
 *  1. Verify by reading back after every write; the write's own status carries no address and is
 *     not waited for (see [Pro800Instrument.write]).
 *  2. Keep what was overwritten in [undoBuffer] for the session.
 */
class Pro800Editor(
    private val transfer: PresetTransfer,
    private val layout: SlotLayout,
) : ComposedEditor<Pro800Program>() {

    /** The name field is sixteen characters wide; the instrument keeps the first sixteen of anything longer. */
    override val maxNameLength = Pro800ProgramFields.NAME_LENGTH

    /**
     * The bytes that were at an address before this session overwrote them, newest first. If a
     * rollback itself fails, the only copy of the overwritten preset is here.
     */
    private val _undoBuffer = ArrayDeque<UndoEntry>()

    @VisibleForTesting
    val undoBuffer: List<UndoEntry> get() = _undoBuffer.toList()

    data class UndoEntry(val address: SlotAddress, val displayId: String, val blob: ByteArray) {
        // Content equality for the array member.
        override fun equals(other: Any?) =
            other is UndoEntry && address == other.address && blob.contentEquals(other.blob)

        override fun hashCode() = 31 * address.hashCode() + blob.contentHashCode()
    }

    // ---- ComposedEditor ----

    override fun displayIdOf(address: SlotAddress): String = layout.format.format(address)

    override suspend fun peek(address: SlotAddress) = Pro800Program.fromEncoded(transfer.read(address))

    override fun isEmpty(preset: Pro800Program) = preset.isEmpty

    override fun isNamed(preset: Pro800Program, name: String) = preset.name == name

    override fun shownName(preset: Pro800Program) = preset.name ?: UNNAMED_PRESET

    override suspend fun writeVerified(address: SlotAddress, preset: Pro800Program) {
        if (preset.isEmpty) erase(address) else writeRecord(address, preset, preset.name)
    }

    override suspend fun erase(address: SlotAddress) = writeEmptyVerified(address)

    override suspend fun storeName(address: SlotAddress, preset: Pro800Program, name: String) =
        writeRecord(address, preset.withName(name), expectName = name)

    override fun overwriting(address: SlotAddress, preset: Pro800Program) {
        _undoBuffer.addFirst(
            UndoEntry(address, displayIdOf(address), Pro800ProgramCodec.encode(preset.dense)),
        )
        while (_undoBuffer.size > UNDO_DEPTH) _undoBuffer.removeLast()
    }

    // ---- Write plumbing ----

    /**
     * Writes a record and proves it landed: the slot is occupied, and the bytes agree with what
     * was sent over the common prefix. The instrument truncates trailing padding, so a record
     * written at full length legitimately comes back shorter.
     */
    private suspend fun writeRecord(address: SlotAddress, program: Pro800Program, expectName: String?) {
        val displayId = displayIdOf(address)
        transfer.write(address, Pro800ProgramCodec.encode(program.dense))
        settle()

        val readBack = peek(address)
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
        val displayId = displayIdOf(address)
        transfer.write(address, ByteArray(0))
        settle()

        if (!peek(address).isEmpty) {
            throw InstrumentException.ProtocolDesync(
                "Asked the instrument to clear $displayId but it still reports a preset there.",
            )
        }
    }

    /** The name field's raw bytes, for the log line above. */
    private fun nameFieldHex(program: Pro800Program): String {
        val start = Pro800ProgramFields.NAME_DENSE_OFFSET
        if (program.dense.size <= start) return "(record ends before the name field)"
        val end = minOf(Pro800ProgramFields.NAME_DENSE_END, program.dense.size)
        return program.dense.copyOfRange(start, end).toHex(" ")
    }

    /** The pause the reference implementation leaves between writes, taken here before reading back. */
    private suspend fun settle() = delay(Pro800SysEx.WRITE_SETTLE_MS)

    private companion object {
        /** A few kilobytes at ~200 bytes per preset. */
        const val UNDO_DEPTH = 32
    }
}
