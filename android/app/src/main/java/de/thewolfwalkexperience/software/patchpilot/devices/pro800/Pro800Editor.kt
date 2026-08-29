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
 * Rename, move, swap and delete on an instrument that has **none of those commands**.
 *
 * Every one is composed here from reads and writes (design §7.5), which is what
 * [isEmulated] tells the UI so it can warn before the first destructive step. The difference from
 * a native operation is not cosmetic: a Nord rename either happens or does not, while every
 * operation below has a moment where a preset exists in exactly one place and the next message
 * decides whether it survives.
 *
 * Four rules follow from that, and they are the whole design of this class:
 *
 *  1. **Verify by reading back** after every write, before anything else happens. The instrument
 *     does not acknowledge a write (the reference implementation sends them fire-and-forget with
 *     a 20 ms pause and never looks), so a read is the only evidence there is.
 *  2. **Order the destructive step last.** A move writes the destination and only then erases the
 *     source, so an interruption leaves a duplicate rather than a hole.
 *  3. **Roll back what cannot be finished.** A swap that writes the first half and fails on the
 *     second would destroy the preset it had just overwritten; [swap] puts it back.
 *  4. **Keep what was overwritten.** [undoBuffer] holds the pre-edit bytes of recent edits for the
 *     session, so a failure that defeats even the rollback still has the data somewhere.
 */
class Pro800Editor(
    private val transfer: PresetTransfer,
    private val layout: SlotLayout,
) : PresetEditor {

    override val supported = setOf(EditOp.RENAME, EditOp.MOVE, EditOp.SWAP, EditOp.DELETE, EditOp.COPY)

    /** All of them. The instrument has no single command for any of these. */
    override fun isEmulated(op: EditOp) = true

    /** The name field is sixteen characters wide, and the instrument keeps the first sixteen of
     * anything longer without complaint. */
    override val maxNameLength = Pro800ProgramFields.NAME_LENGTH

    /**
     * The bytes that were at an address before this session overwrote them, newest first.
     *
     * A last line of defence rather than a feature: if a rollback itself fails, the only copy of
     * the overwritten preset is here, and losing it to a bounded buffer would be worse than
     * holding a few hundred bytes per edit.
     */
    private val _undoBuffer = ArrayDeque<UndoEntry>()
    val undoBuffer: List<UndoEntry> get() = _undoBuffer.toList()

    data class UndoEntry(val address: SlotAddress, val displayId: String, val blob: ByteArray) {
        // ByteArray identity would make two entries with equal contents compare unequal; these are
        // only ever compared in tests, but a data class with an array member is a known trap.
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
        // Preserves the record's own format version rather than upgrading it - see
        // Pro800Program.withName, and design §11.7.
        writeVerified(address, before.withName(newName), expectName = newName)
    }

    override suspend fun delete(address: SlotAddress) {
        val before = readProgram(address)
        if (before.isEmpty) return // already nothing there; deleting it again is not an error
        remember(address, before)
        writeEmptyVerified(address)
    }

    /**
     * Copy to the destination, then erase the source - **in that order**.
     *
     * If the erase fails, the preset exists at both addresses. That is a mess the user can see and
     * fix; the other order risks a hole they cannot.
     */
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
     * Duplicate a preset into an empty slot, leaving the source where it is.
     *
     * **The safe one.** Every other operation here has a moment where a preset exists in one place
     * only; a copy never does. It writes the destination and touches nothing else, so an
     * interruption leaves the source intact and the destination either written or still empty.
     * That is also why there is no rollback and no undo entry: nothing is overwritten, because an
     * occupied destination is refused rather than merged.
     *
     * **The returned name is the source's, verbatim.** [PresetEditor.copyProgram] returns a name
     * because a Nord chooses one - it appends a disambiguator, so "Synth Strings" becomes
     * "Synth Strings 2" and only reading the destination back reveals it. This instrument has no
     * copy command at all and stores exactly the bytes it is sent, so the copy carries the source's
     * name unchanged and two presets may share a name. That is fine here: the address is the
     * identity, not the name. [writeVerified] has already read the destination back and compared
     * the stored name against this one, so it is a verified fact rather than an assumption.
     *
     * A preset with no name reports [Pro800Instrument.UNNAMED], which is what the browser shows for
     * the same record - the caller compares the two (`RegressionTester`), so they have to agree.
     */
    override suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String {
        if (src == dst) throw InstrumentException.NotSupported("copy a preset onto itself")

        val source = readProgram(src)
        if (source.isEmpty) throw InstrumentException.NotSupported("copy an empty slot")

        // Refused, not overwritten. The contract says "into the empty slot dst", and on a family
        // where this is native the instrument enforces it - a Nord answers status 4, "file exists".
        // Composed host-side, nothing enforces it unless this does, and the failure would be
        // silently destroying whatever was there.
        val destination = readProgram(dst)
        if (!destination.isEmpty) {
            throw InstrumentException.NotSupported("copy onto an occupied slot")
        }

        writeVerified(dst, source, expectName = source.name)
        return source.name ?: Pro800Instrument.UNNAMED
    }

    /**
     * Two writes, with the first undone if the second fails.
     *
     * Without the rollback this is the one operation that can destroy a preset outright: once
     * `a` has been overwritten with `b`'s contents, the only copy of `a`'s is in memory.
     */
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
            // `a` now holds b's contents and `b` still holds them too - so a's original is only in
            // memory. Put it back before surfacing the failure.
            rollback(a, first, becauseOf = e)
            throw e
        }
    }

    // ---- Write plumbing ----

    private suspend fun writeOrEmpty(address: SlotAddress, program: Pro800Program) {
        if (program.isEmpty) writeEmptyVerified(address) else writeVerified(address, program, program.name)
    }

    /**
     * Writes a record and proves it landed.
     *
     * The read-back is compared on the two things that can be checked: the slot is occupied, and
     * the bytes agree with what was sent **as far as both go**. The prefix rule is not laziness -
     * the instrument truncates trailing padding, so a record written at full length legitimately
     * comes back shorter (this is the same variable-length behaviour that made 190- and 210-byte
     * records both valid). A mismatch inside the common prefix is real corruption.
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
        // The name field as written and as stored, side by side. A name that comes back
        // different is either the instrument declining a character or this app mis-encoding one,
        // and only the bytes distinguish those - the same reason SysExExchange logs a reply it
        // rejects rather than just timing out. Debug-only: this is preset content, not
        // diagnostics anyone needs from a release build's logcat.
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
            // The record itself verified above, so the preset is intact and stored - only the name
            // came back different. On this instrument that means it did not accept some character
            // of the name rather than that anything is damaged, and the message should say so.
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
     * Best-effort restore of an address this class has just overwritten.
     *
     * Runs under [NonCancellable] for the same reason `NordDevice.unlockCategorySelection` does:
     * this is cleanup after a failure, and a caller whose coroutine is already being cancelled
     * would otherwise skip it - which is precisely when a preset would be lost. A failure here is
     * logged rather than thrown, so it cannot mask the error that caused the rollback.
     */
    private suspend fun rollback(address: SlotAddress, original: Pro800Program, becauseOf: Exception) {
        withContext(NonCancellable) {
            try {
                writeOrEmpty(address, original)
                Log.w(TAG, "Rolled ${layout.format.format(address)} back after: ${becauseOf.message}")
            } catch (e: Exception) {
                // The original bytes are still in the undo buffer, which is the whole reason it
                // exists. Say so loudly - this is the one path that can lose data.
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

    /**
     * The pause the reference implementation leaves between writes, before reading back.
     *
     * It sleeps 20 ms between consecutive program writes and never checks anything; taking the
     * same pause before asking the instrument what it stored costs nothing and avoids depending
     * on a write being visible the instant it is sent.
     */
    private suspend fun settle() = delay(WRITE_SETTLE_MS)

    private companion object {
        const val WRITE_SETTLE_MS = 20L

        /** Deep enough to cover any single operation several times over. A preset is ~200 bytes,
         * so this is a few kilobytes held for the session. */
        const val UNDO_DEPTH = 32
    }
}
