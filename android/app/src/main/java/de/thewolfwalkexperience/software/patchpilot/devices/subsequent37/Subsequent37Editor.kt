// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.ComposedEditor
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET

/**
 * Edits on a Subsequent 37. The instrument has no rename, copy, move, swap or delete: each is
 * a read of the stored program (`06 01`) and a whole-program write (`05 01`) proved by reading
 * the slot back, so every one is emulated, in the order [ComposedEditor] gives them.
 *
 *  - **Rename** changes the 24-byte name field and nothing else.
 *  - **Delete**, and the second half of a move, write the panel's Initialize,
 *    [Subsequent37Instrument.blank]: a slot always holds a program.
 *
 * Nothing is selected and nothing is heard. Every edit runs inside
 * [Subsequent37Instrument.operation], which holds the instrument's lock.
 */
class Subsequent37Editor(private val instrument: Subsequent37Instrument) : ComposedEditor<Subsequent37Program>() {

    private val device get() = instrument.device

    override val maxNameLength: Int = Subsequent37Program.NAME_LENGTH

    override val emptySlotName = Subsequent37Program.EMPTY_NAME

    override val instrumentName = "Subsequent 37"

    override fun displayIdOf(address: SlotAddress) = instrument.displayIdOf(instrument.numberOf(address))

    override suspend fun <T> operation(moved: Map<SlotAddress, SlotAddress>, block: suspend () -> T): T =
        instrument.operation(block)

    override suspend fun peek(address: SlotAddress) = device.readProgram(instrument.numberOf(address))

    override fun isEmpty(preset: Subsequent37Program) = preset.isEmpty

    override fun isNamed(preset: Subsequent37Program, name: String) = preset.name == name

    override fun shownName(preset: Subsequent37Program) = preset.name.ifEmpty { UNNAMED_PRESET }

    override suspend fun writeVerified(address: SlotAddress, preset: Subsequent37Program) =
        device.writeVerified(instrument.numberOf(address), preset, displayIdOf(address))

    override suspend fun erase(address: SlotAddress) = writeVerified(address, instrument.blank)

    override suspend fun storeName(address: SlotAddress, preset: Subsequent37Program, name: String) =
        writeVerified(address, preset.withName(name))
}
