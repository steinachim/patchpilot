// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.BuildConfig
import de.thewolfwalkexperience.software.patchpilot.core.ComposedEditor
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import kotlinx.coroutines.delay

private const val TAG = "Pro800Editor"

/** What [Pro800Editor] reads and writes through: one preset's raw, still-encoded blob, by address. */
internal interface Pro800Store {
    suspend fun read(address: SlotAddress): ByteArray

    /** Stores [blob] at [address], or clears the address when [blob] is empty. Not verified here. */
    suspend fun write(address: SlotAddress, blob: ByteArray)
}

/**
 * Every edit on an instrument that has none of them as a command, composed by [ComposedEditor]
 * from reads and writes. On top of its transaction model, every write is verified by reading it
 * back: the write's own status carries no address and is not waited for (see
 * [Pro800Instrument.write]).
 */
class Pro800Editor internal constructor(
    private val store: Pro800Store,
    private val layout: SlotLayout,
) : ComposedEditor<Pro800Program>() {

    /** The name field is sixteen characters wide; the instrument keeps the first sixteen of anything longer. */
    override val maxNameLength = Pro800ProgramFields.NAME_LENGTH

    // ---- ComposedEditor ----

    override fun displayIdOf(address: SlotAddress): String = layout.format.format(address)

    override suspend fun peek(address: SlotAddress) = Pro800Program.fromEncoded(store.read(address))

    override fun isEmpty(preset: Pro800Program) = preset.isEmpty

    override fun isNamed(preset: Pro800Program, name: String) = preset.name == name

    override fun shownName(preset: Pro800Program) = preset.name ?: UNNAMED_PRESET

    override suspend fun writeVerified(address: SlotAddress, preset: Pro800Program) {
        if (preset.isEmpty) erase(address) else writeRecord(address, preset, preset.name)
    }

    override suspend fun erase(address: SlotAddress) = writeEmptyVerified(address)

    override suspend fun storeName(address: SlotAddress, preset: Pro800Program, name: String) =
        writeRecord(address, preset.withName(name), expectName = name)

    // ---- Write plumbing ----

    /**
     * Writes a record and proves it landed: the slot is occupied, and the bytes agree with what
     * was sent over the common prefix. The instrument truncates trailing padding, so a record
     * written at full length legitimately comes back shorter.
     */
    private suspend fun writeRecord(address: SlotAddress, program: Pro800Program, expectName: String?) {
        val displayId = displayIdOf(address)
        store.write(address, Pro800ProgramCodec.encode(program.dense))
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
        store.write(address, ByteArray(0))
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
}
