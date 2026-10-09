// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val TAG = "ComposedEditor"

/**
 * An editor for an instrument with no command for copy, move, swap or delete, each composed from
 * whole-preset reads and verified writes. One transaction model for every such family:
 *
 *  - Slots are read fresh rather than trusted from the list, which may predate a change on the
 *    panel. A slot not as the list showed it is [InstrumentException.SlotStateChanged], raised
 *    before anything is written.
 *  - A move writes the destination before it empties the source, so a failure leaves the preset
 *    in both slots rather than in neither, and says so.
 *  - A swap reads both presets before writing either, and writes the first slot back if either
 *    write fails - the first included, since a write can land and still fail its read-back.
 *  - Moves and swaps run under [NonCancellable]: a cancelled one finishes rather than stopping
 *    half done.
 *
 * [P] is one stored preset as the family reads it. [peek] reads as much as emptiness and the name
 * need, and [load] completes it before it is written, so a family whose names are cheaper to read
 * than its data refuses an edit without reading any data.
 */
abstract class ComposedEditor<P : Any> : PresetEditor {

    override val supported: Set<EditOp> = EditOp.entries.toSet()

    /** Every edit; a family with its own rename command says so. */
    override fun isEmulated(op: EditOp): Boolean = true

    /** The name the instrument reports for an empty slot, refused as a preset name; null where there is none. */
    protected open val emptySlotName: String? = null

    /** The instrument as [nameProblem]'s refusal of [emptySlotName] names it. */
    protected open val instrumentName: String = "instrument"

    override fun nameProblem(name: String): String? =
        super.nameProblem(name) ?: emptySlotName?.takeIf { name.trim() == it }?.let {
            "'$it' is how the $instrumentName marks an empty slot, so a preset of that name " +
                "would be taken for one."
        }

    // ---- What a family supplies ----

    /** [address] as the list shows it, for messages. */
    protected abstract fun displayIdOf(address: SlotAddress): String

    /**
     * Runs one edit under the instrument's lock. [moved] maps each slot whose preset the edit
     * relocates to where it ends up, for a family that loads the previously loaded preset again
     * afterwards.
     */
    protected open suspend fun <T> operation(moved: Map<SlotAddress, SlotAddress>, block: suspend () -> T): T =
        block()

    /** [address] read far enough to tell whether it is empty and what it is called. */
    protected abstract suspend fun peek(address: SlotAddress): P

    /** [preset], as [peek] read it from [address], complete enough to be written. */
    protected open suspend fun load(address: SlotAddress, preset: P): P = preset

    protected abstract fun isEmpty(preset: P): Boolean

    /** Whether [preset] is already called [name], so a rename has nothing to write. */
    protected abstract fun isNamed(preset: P, name: String): Boolean

    /** The name as the list shows it, [UNNAMED_PRESET] for a preset without one. */
    protected abstract fun shownName(preset: P): String

    /** Writes [preset], an empty one included, to [address], and proves by reading back that it landed. */
    protected abstract suspend fun writeVerified(address: SlotAddress, preset: P)

    /** Empties [address] and proves it. */
    protected abstract suspend fun erase(address: SlotAddress)

    /** Stores [preset], as [peek] read it from [address], under [name], and proves it. */
    protected abstract suspend fun storeName(address: SlotAddress, preset: P, name: String)

    /** Called with each occupied preset, as [peek] read it, before an edit overwrites or empties its slot. */
    protected open fun overwriting(address: SlotAddress, preset: P) = Unit

    // ---- The edits ----

    override suspend fun rename(address: SlotAddress, newName: String) {
        nameProblem(newName)?.let { throw IllegalArgumentException(it) }
        operation(emptyMap()) {
            val current = requireOccupied(address)
            if (isNamed(current, newName)) return@operation
            storeName(address, current, newName)
        }
    }

    /** Writes the source's preset to an empty slot and returns its name there. */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        if (src == dst) throw InstrumentException.NotSupported("copy a preset onto itself")
        return operation(emptyMap()) {
            val source = requireOccupied(src)
            requireEmpty(dst)
            writeVerified(dst, load(src, source))
            shownName(source)
        }
    }

    override suspend fun move(from: SlotAddress, to: SlotAddress) {
        if (from == to) return
        operation(mapOf(from to to)) {
            val source = requireOccupied(from)
            requireEmpty(to)
            val preset = load(from, source)
            overwriting(from, source)
            withContext(NonCancellable) {
                writeVerified(to, preset)
                try {
                    erase(from)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw InstrumentException.ProtocolDesync(
                        "Copied ${displayIdOf(from)} to ${displayIdOf(to)}, but could not empty " +
                            "${displayIdOf(from)} afterwards, so the preset is now in both slots.",
                        e,
                    )
                }
            }
        }
    }

    /** An empty side moves as its own data, which the instrument stores as empty. */
    override suspend fun swap(a: SlotAddress, b: SlotAddress) {
        if (a == b) return
        operation(mapOf(a to b, b to a)) {
            val first = peek(a)
            val second = peek(b)
            if (isEmpty(first) && isEmpty(second)) return@operation
            val presetA = load(a, first)
            val presetB = load(b, second)
            if (!isEmpty(first)) overwriting(a, first)
            if (!isEmpty(second)) overwriting(b, second)
            withContext(NonCancellable) {
                try {
                    writeVerified(b, presetA)
                    writeVerified(a, presetB)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Also after the first write: its read-back can fail with the write already
                    // stored, and b's preset would then exist only here.
                    rollback(b, presetB, e)
                    throw e
                }
            }
        }
    }

    override suspend fun delete(address: SlotAddress) {
        operation(emptyMap()) {
            val current = peek(address)
            // Already empty; deleting it again is not an error.
            if (isEmpty(current)) return@operation
            overwriting(address, current)
            erase(address)
        }
    }

    // ---- Shared steps ----

    private suspend fun requireOccupied(address: SlotAddress): P {
        val preset = peek(address)
        if (isEmpty(preset)) throw InstrumentException.SlotStateChanged.empty(displayIdOf(address))
        return preset
    }

    private suspend fun requireEmpty(address: SlotAddress) {
        if (!isEmpty(peek(address))) throw InstrumentException.SlotStateChanged.occupied(displayIdOf(address))
    }

    /**
     * Best-effort restore of a slot just overwritten. A failure is logged rather than thrown, so
     * it cannot mask the error that caused the rollback.
     */
    private suspend fun rollback(address: SlotAddress, original: P, becauseOf: Exception) {
        try {
            writeVerified(address, original)
            Log.w(TAG, "Rolled ${displayIdOf(address)} back after: ${becauseOf.message}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Could not roll ${displayIdOf(address)} back after a failed swap; its previous " +
                    "contents are no longer on the instrument.",
                e,
            )
        }
    }
}
