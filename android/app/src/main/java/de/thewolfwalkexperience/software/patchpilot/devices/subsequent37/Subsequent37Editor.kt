// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "Subsequent37Editor"

/**
 * Edits on a Subsequent 37. The instrument has no rename, copy, move, swap or delete: each is
 * a read of the stored program (`06 01`) and a whole-program write (`05 01`) proved by reading
 * the slot back, so every one is emulated.
 *
 *  - **Rename** changes the 24-byte name field and nothing else.
 *  - **Copy** writes the source's bytes to an empty slot. **Move** is a copy, then the panel's
 *    Initialize written over the source, only once the copy is proved.
 *  - **Swap** reads both before writing either, and writes the first slot back if the second
 *    write fails.
 *  - **Delete** writes the panel's Initialize, [Subsequent37Instrument.blank]: a slot always
 *    holds a program.
 *
 * Nothing is selected and nothing is heard. Every edit runs inside
 * [Subsequent37Instrument.operation], which holds the instrument's lock, and reads its slots
 * fresh rather than trusting the listing, which may predate a save on the panel. Refusals come
 * before anything is written.
 */
class Subsequent37Editor(private val instrument: Subsequent37Instrument) : PresetEditor {

    private val device get() = instrument.device

    override val supported: Set<EditOp> = EditOp.entries.toSet()

    override val maxNameLength: Int = Subsequent37Program.NAME_LENGTH

    override fun isEmulated(op: EditOp): Boolean = true

    /** The shared rule, plus the name this app reads as an empty slot. */
    override fun nameProblem(name: String): String? =
        super.nameProblem(name) ?: if (name.trimEnd() == Subsequent37Program.EMPTY_NAME) {
            "'${Subsequent37Program.EMPTY_NAME}' is how the Subsequent 37 marks an empty slot, so " +
                "a preset of that name would be taken for one."
        } else {
            null
        }

    override suspend fun rename(address: SlotAddress, newName: String) {
        val name = newName.trimEnd()
        nameProblem(name)?.let { throw IllegalArgumentException(it) }
        val number = instrument.numberOf(address)
        instrument.operation {
            val current = requireOccupied(number)
            if (current.name == name) return@operation
            device.writeVerified(number, current.withName(name), instrument.displayIdOf(number))
        }
    }

    /** Duplicates a preset into an empty slot and returns its name there. */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        if (src == dst) throw InstrumentException.NotSupported("copy a preset onto itself")
        val from = instrument.numberOf(src)
        val to = instrument.numberOf(dst)
        return instrument.operation {
            val source = requireOccupied(from)
            requireEmpty(to)
            device.writeVerified(to, source, instrument.displayIdOf(to))
            source.name.ifEmpty { UNNAMED_PRESET }
        }
    }

    /** Copy, then the blank over the source: if that fails, the preset is in both slots. */
    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        if (from == to) return
        val source = instrument.numberOf(from)
        val destination = instrument.numberOf(to)
        instrument.operation {
            val program = requireOccupied(source)
            requireEmpty(destination)
            withContext(NonCancellable) {
                device.writeVerified(destination, program, instrument.displayIdOf(destination))
                try {
                    device.writeVerified(source, instrument.blank, instrument.displayIdOf(source))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw InstrumentException.ProtocolDesync(
                        "Copied ${instrument.displayIdOf(source)} to " +
                            "${instrument.displayIdOf(destination)}, but could not empty " +
                            "${instrument.displayIdOf(source)} afterwards, so the preset is now " +
                            "in both slots.",
                        e,
                    )
                }
            }
        }
    }

    /**
     * Reads both programs before writing either, since the first write destroys one of them.
     * An empty side moves as its own bytes. The two writes cannot be one: if the second fails,
     * the first slot is written back.
     */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        if (a == b) return
        val first = instrument.numberOf(a)
        val second = instrument.numberOf(b)
        instrument.operation {
            val programA = device.readProgram(first)
            val programB = device.readProgram(second)
            if (programA.isEmpty && programB.isEmpty) return@operation
            withContext(NonCancellable) {
                device.writeVerified(second, programA, instrument.displayIdOf(second))
                try {
                    device.writeVerified(first, programB, instrument.displayIdOf(first))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    rollback(second, programB, e)
                    throw e
                }
            }
        }
    }

    override suspend fun delete(address: SlotAddress) {
        val number = instrument.numberOf(address)
        instrument.operation {
            // Already empty; deleting it again is not an error.
            if (device.readProgram(number).isEmpty) return@operation
            device.writeVerified(number, instrument.blank, instrument.displayIdOf(number))
        }
    }

    /**
     * Best-effort restore of a slot this class has just overwritten. A failure is logged rather
     * than thrown, so it cannot mask the error that caused the rollback.
     */
    private suspend fun rollback(number: Int, original: Subsequent37Program, becauseOf: Exception) {
        val displayId = instrument.displayIdOf(number)
        try {
            device.writeVerified(number, original, displayId)
            Log.w(TAG, "Rolled $displayId back after: ${becauseOf.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not roll $displayId back after a failed swap", e)
        }
    }

    /** The preset, read fresh rather than trusted from the list, which may predate a change on the panel. */
    private suspend fun requireOccupied(number: Int): Subsequent37Program {
        val program = device.readProgram(number)
        if (program.isEmpty) throw InstrumentException.SlotStateChanged.empty(instrument.displayIdOf(number))
        return program
    }

    private suspend fun requireEmpty(number: Int) {
        if (!device.readProgram(number).isEmpty) {
            throw InstrumentException.SlotStateChanged.occupied(instrument.displayIdOf(number))
        }
    }
}
