// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.ComposedEditor
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Edits on an Axe-Fx III, each the sequence Axe-Edit III itself sends:
 *
 *  - **Rename** is the instrument's own: load the preset, set the name, store.
 *  - **Copy, move, swap** read whole presets and write them back: there is no command for any of
 *    them. [ComposedEditor] orders the writes.
 *  - **Delete** writes the editor's recorded empty preset, which the instrument takes as an erase.
 *
 * Every edit runs inside [AxeFx3Instrument.operation], which holds the instrument's lock and
 * loads the previously loaded preset again afterwards, and proves what it wrote by reading it
 * back. A slot is told empty by its name, so a refusal reads no preset data.
 */
class AxeFx3Editor(private val instrument: AxeFx3Instrument) : ComposedEditor<AxeFx3Editor.Stored>() {

    /** A stored preset: its name, and its data once [load] has read it. */
    class Stored(val name: String, val image: AxeFx3DumpImage? = null)

    private val device get() = instrument.device

    override val maxNameLength: Int = MAX_NAME_LENGTH

    override fun isEmulated(op: EditOp): Boolean = op != EditOp.RENAME

    override val emptySlotName = AxeFx3SysEx.EMPTY_NAME

    override val instrumentName = "Axe-Fx III"

    override fun displayIdOf(address: SlotAddress) = instrument.displayIdOf(instrument.numberOf(address))

    /** Follows a relocated preset by number, which is what the instrument reports as loaded. */
    override suspend fun <T> operation(moved: Map<SlotAddress, SlotAddress>, block: suspend () -> T): T {
        val follow = moved.entries.associate { (from, to) -> instrument.numberOf(from) to instrument.numberOf(to) }
        return instrument.operation(follow = { follow[it] ?: it }, block = block)
    }

    override suspend fun peek(address: SlotAddress) = Stored(device.name(instrument.numberOf(address)))

    override suspend fun load(address: SlotAddress, preset: Stored) =
        Stored(preset.name, device.readDump(instrument.numberOf(address)))

    override fun isEmpty(preset: Stored) = preset.name == AxeFx3SysEx.EMPTY_NAME

    override fun isNamed(preset: Stored, name: String) = preset.name == name

    override fun shownName(preset: Stored) = instrument.displayName(preset.name) ?: UNNAMED_PRESET

    /** Writes the preset's data and proves it, data and name both. */
    override suspend fun writeVerified(address: SlotAddress, preset: Stored) {
        val number = instrument.numberOf(address)
        instrument.writeVerified(number, checkNotNull(preset.image) { "written before it was loaded" })
        val stored = device.name(number)
        if (stored != preset.name) {
            throw InstrumentException.ProtocolDesync(
                "Wrote ${instrument.displayIdOf(number)}, but it reports the name '$stored' " +
                    "rather than '${preset.name}'.",
            )
        }
    }

    override suspend fun erase(address: SlotAddress) = instrument.erase(instrument.numberOf(address))

    override suspend fun storeName(address: SlotAddress, preset: Stored, name: String) {
        val number = instrument.numberOf(address)
        withContext(NonCancellable) {
            device.select(number)
            device.setName(name)
            device.store(number)
        }
        val stored = device.name(number)
        if (stored != name) {
            throw InstrumentException.ProtocolDesync(
                "${instrument.displayIdOf(number)} was saved, but the Axe-Fx III stored its " +
                    "name as '$stored' rather than '$name'. It may not accept every character.",
            )
        }
    }

    companion object {
        /**
         * One short of the 32-byte name field: the instrument returns names as C strings, and
         * whether it keeps a 32nd character with no room for the terminator is untested.
         */
        const val MAX_NAME_LENGTH = 31
    }
}
