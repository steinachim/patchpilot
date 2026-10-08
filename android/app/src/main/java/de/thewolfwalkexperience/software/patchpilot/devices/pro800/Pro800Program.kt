// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * One decoded program record. A zero-length record is an empty slot, and a short one is a normal
 * truncated preset; neither is an error.
 */
class Pro800Program(val dense: ByteArray) {

    /**
     * True where this address holds nothing: the instrument answers an empty address with a bare
     * `F0 F7` ([Pro800SysEx.isEmptyReply]), which arrives as a zero-length record. Neither length
     * nor name can stand in: records are variable length (173, 166, 158 and 157 dense bytes for
     * four consecutive populated slots), and a preset may carry no name at all.
     */
    val isEmpty: Boolean get() = dense.isEmpty()

    val version: Int?
        get() = if (dense.size > Pro800ProgramFields.VERSION.denseOffset) {
            Pro800ProgramCodec.readValue(
                dense,
                Pro800ProgramFields.VERSION.denseOffset,
                Pro800ProgramFields.VERSION.byteCount,
            )
        } else {
            null
        }

    /**
     * Is this record longer than its own version byte says it may be
     * ([Pro800ProgramFields.maxDenseSizeFor])? Such a record is not one the instrument sent. On a
     * shared MIDI port about 1% of replies arrive spliced: one loses its `F7` under load and runs
     * into the next, producing a message with our header, type and echoed address and another
     * record's tail. Every splice measured ran to 228..231 bytes against a valid maximum of 210.
     * Not a proof: a splice that drops enough of the middle lands back inside the range.
     */
    val outrunsDeclaredVersion: Boolean
        get() = !isEmpty && dense.size > Pro800ProgramFields.maxDenseSizeFor(version)

    /**
     * Is this record short enough that it might be a truncated splice rather than a preset? A
     * suspicion, not a verdict: there is no safe length to reject on, since the instrument
     * truncates after the last meaningful byte and an unnamed preset ends at its last non-zero
     * parameter (across 102 measured records, as low as dense 85). What separates the two is
     * repetition - truncation reproduces and a splice does not - so [Pro800Instrument] re-reads a
     * flagged record and accepts it only if the two reads agree. Every record measured on a normal
     * instrument is 155 dense bytes or more, so this rarely fires.
     */
    val isSuspiciouslyShort: Boolean
        get() = !isEmpty && dense.size < Pro800ProgramFields.NAME_DENSE_OFFSET

    /**
     * The preset's name, or null where it has none - distinct from [isEmpty], since one of the
     * instrument's own presets carries an empty name field. A truncated record may end partway
     * through the field; `readString` clamps to the record's length.
     */
    val name: String?
        get() = if (isEmpty) {
            null
        } else {
            Pro800ProgramCodec
                .readString(dense, Pro800ProgramFields.NAME_DENSE_OFFSET, Pro800ProgramFields.NAME_LENGTH)
                .ifBlank { null }
        }

    /**
     * A copy with the name replaced - the read-modify-write a rename is built from. Preserves the
     * record's version rather than upgrading it (the reference implementation stamps a short
     * record as 111, which converts a preset the user did not ask to convert).
     */
    fun withName(newName: String): Pro800Program {
        check(!isEmpty) { "cannot rename an uninitialized slot" }
        // Records arrive truncated after their last meaningful byte, so a short old name leaves
        // no room for a longer one; grow the record to the end of the name field.
        val target = maxOf(dense.size, Pro800ProgramFields.NAME_DENSE_END)

        // Growth must never reach a field this record's format does not declare - see
        // maxDenseSizeFor. NAME_DENSE_END is never above the permitted maximum, so a failure
        // here means the record arrived already outrunning its version byte, which the read
        // path rejects; this is the backstop.
        val permitted = Pro800ProgramFields.maxDenseSizeFor(version)
        check(target <= permitted) {
            "this record declares preset format $version, which may hold at most $permitted " +
                "bytes, but carries $target - it is malformed rather than too long to rename, " +
                "and should have been rejected when it was read"
        }

        val copy = dense.copyOf(target)
        Pro800ProgramCodec.writeString(
            copy,
            Pro800ProgramFields.NAME_DENSE_OFFSET,
            Pro800ProgramFields.NAME_LENGTH,
            newName,
        )
        return Pro800Program(copy)
    }

    companion object {
        /** What a dump message's payload decodes to. */
        fun fromEncoded(encodedPayload: ByteArray) = Pro800Program(Pro800ProgramCodec.decode(encodedPayload))
    }
}
