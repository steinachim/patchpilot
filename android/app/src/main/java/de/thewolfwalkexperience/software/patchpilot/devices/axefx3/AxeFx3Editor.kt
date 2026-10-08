// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.EditOp
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "AxeFx3Editor"

/**
 * Edits on an Axe-Fx III, each the sequence Axe-Edit III itself sends:
 *
 *  - **Rename** is the instrument's own: load the preset, set the name, store.
 *  - **Copy, move, swap** read whole presets and write them back: there is no command for any of
 *    them. A move is a copy and then a delete.
 *  - **Delete** writes the editor's recorded empty preset, which the instrument takes as an erase.
 *
 * Every edit runs inside [AxeFx3Instrument.operation], which holds the instrument's lock and
 * loads the previously loaded preset again afterwards, and proves what it wrote by reading it
 * back. Refusals come before anything is selected.
 */
class AxeFx3Editor(private val instrument: AxeFx3Instrument) : PresetEditor {

    private val device get() = instrument.device

    override val supported: Set<EditOp> = EditOp.entries.toSet()

    override val maxNameLength: Int = MAX_NAME_LENGTH

    override fun isEmulated(op: EditOp): Boolean = op != EditOp.RENAME

    /** The shared rule, plus the name the instrument reports for an empty slot. */
    override fun nameProblem(name: String): String? =
        super.nameProblem(name) ?: if (name.trimEnd() == AxeFx3SysEx.EMPTY_NAME) {
            "'${AxeFx3SysEx.EMPTY_NAME}' is how the Axe-Fx III marks an empty slot, so a preset " +
                "of that name would be taken for one."
        } else {
            null
        }

    override suspend fun rename(address: SlotAddress, newName: String) {
        val name = newName.trimEnd()
        nameProblem(name)?.let { throw IllegalArgumentException(it) }
        val number = instrument.numberOf(address)
        instrument.operation {
            val current = requireOccupied(number)
            if (current == name) return@operation

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
    }

    /**
     * Duplicates a preset into an empty slot and returns its name as the instrument reports it
     * there, which is what a refresh of that row will show.
     */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        if (src == dst) throw InstrumentException.NotSupported("copy a preset onto itself")
        val from = instrument.numberOf(src)
        val to = instrument.numberOf(dst)
        return instrument.operation {
            val name = requireOccupied(from)
            requireEmpty(to)
            instrument.writeVerified(to, device.readDump(from))
            val stored = device.name(to)
            if (stored != name) {
                throw InstrumentException.ProtocolDesync(
                    "Copied ${instrument.displayIdOf(from)} to ${instrument.displayIdOf(to)}, " +
                        "but the copy reports the name '$stored' rather than '$name'.",
                )
            }
            instrument.displayName(stored) ?: UNNAMED_PRESET
        }
    }

    /** Copy, then delete the source: if the delete fails, the preset exists in both slots. */
    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        if (from == to) return
        val source = instrument.numberOf(from)
        val destination = instrument.numberOf(to)
        instrument.operation(follow = { if (it == source) destination else it }) {
            requireOccupied(source)
            requireEmpty(destination)
            val image = device.readDump(source)
            withContext(NonCancellable) {
                instrument.writeVerified(destination, image)
                try {
                    instrument.erase(source)
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
     * Reads both presets before writing either, since the first write destroys one of them. An
     * empty side is moved as the empty slot's own data, which the instrument stores as empty.
     * If the second write fails, the first is rolled back.
     */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        if (a == b) return
        val first = instrument.numberOf(a)
        val second = instrument.numberOf(b)
        instrument.operation(follow = {
            if (it == first) {
                second
            } else if (it == second) {
                first
            } else {
                it
            }
        }) {
            val nameA = device.name(first)
            val nameB = device.name(second)
            if (nameA == AxeFx3SysEx.EMPTY_NAME && nameB == AxeFx3SysEx.EMPTY_NAME) return@operation
            val imageA = device.readDump(first)
            val imageB = device.readDump(second)
            withContext(NonCancellable) {
                put(second, imageA, nameA)
                try {
                    put(first, imageB, nameB)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    rollback(second, imageB, nameB, e)
                    throw e
                }
            }
        }
    }

    override suspend fun delete(address: SlotAddress) {
        val number = instrument.numberOf(address)
        instrument.operation {
            // Already nothing there; deleting it again is not an error.
            if (device.name(number) == AxeFx3SysEx.EMPTY_NAME) return@operation
            instrument.erase(number)
        }
    }

    /** Writes [image] and proves it, data and name both. */
    private suspend fun put(number: Int, image: AxeFx3DumpImage, expectName: String) {
        instrument.writeVerified(number, image)
        val stored = device.name(number)
        if (stored != expectName) {
            throw InstrumentException.ProtocolDesync(
                "Wrote ${instrument.displayIdOf(number)}, but it reports the name '$stored' " +
                    "rather than '$expectName'.",
            )
        }
    }

    /**
     * Best-effort restore of a slot this class has just overwritten. A failure is logged rather
     * than thrown, so it cannot mask the error that caused the rollback.
     */
    private suspend fun rollback(number: Int, original: AxeFx3DumpImage, name: String, becauseOf: Exception) {
        try {
            put(number, original, name)
            Log.w(TAG, "Rolled ${instrument.displayIdOf(number)} back after: ${becauseOf.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not roll ${instrument.displayIdOf(number)} back after a failed swap", e)
        }
    }

    /** The preset's name, read fresh rather than trusted from the list, which may predate a change on the panel. */
    private suspend fun requireOccupied(number: Int): String {
        val name = device.name(number)
        if (name == AxeFx3SysEx.EMPTY_NAME) {
            throw InstrumentException.SlotStateChanged.empty(instrument.displayIdOf(number))
        }
        return name
    }

    private suspend fun requireEmpty(number: Int) {
        if (device.name(number) != AxeFx3SysEx.EMPTY_NAME) {
            throw InstrumentException.SlotStateChanged.occupied(instrument.displayIdOf(number))
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
