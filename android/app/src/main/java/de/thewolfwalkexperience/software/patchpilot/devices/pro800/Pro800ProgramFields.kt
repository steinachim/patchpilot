// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Where a value lives inside a decoded program record. [denseOffset] is in dense coordinates;
 * a documented raw offset goes through [Pro800ProgramCodec.denseIndexOf].
 */
data class Pro800Field(
    val name: String,
    val denseOffset: Int,
    val byteCount: Int,
    val signed: Boolean = false,
    /** Fields the older preset versions do not carry at all (110 and 111 added their own). */
    val minPresetVersion: Int = 0,
)

/**
 * The subset of a program record this app reads. Every offset here is a raw offset from the
 * reference implementation's `Pro800ProgramConstants.h`, run through
 * [Pro800ProgramCodec.denseIndexOf] and listed with the value it came from. A full parameter map
 * is out of scope.
 */
object Pro800ProgramFields {

    /** Raw offset 5: the record's own format version. 110 and 111 add fields; older ones lack them. */
    val VERSION = field("version", rawOffset = 5, byteCount = 1)

    /** Raw offsets 1..4: a storage code the instrument writes; used only to tell a written slot
     * from an uninitialized one. */
    val STORAGE_CODE = field("storageCode", rawOffset = 1, byteCount = 4)

    /**
     * The preset name: raw offsets 172..189 inclusive, minus the two overflow bytes inside that
     * span (176 and 184), giving 16 characters.
     */
    val NAME_DENSE_OFFSET = Pro800ProgramCodec.denseIndexOf(172)
    const val NAME_LENGTH = 16

    /** Where the name field ends in a record long enough to carry all of it. Records are routinely shorter, so this is a bound for reading, not a test for whether a preset exists. */
    val NAME_DENSE_END = NAME_DENSE_OFFSET + NAME_LENGTH

    /**
     * How long a record of a given preset format may be, in dense bytes. The version field is a
     * schema version: format 110 appends LFO Aftertouch Amount (raw 190..191) and 111 appends
     * Voice Spread, Key Tracking Ref Note, Glide Mode and Pitchbend Range (raw 193..197). The
     * name field ends at dense 165 and format 110's first appended byte is dense 166, so
     * [Pro800Program.withName] can grow a record to [NAME_DENSE_END] without crossing into a
     * field its version does not declare. A full-length record is 166 dense bytes at format 109
     * and 173 at 111.
     */
    fun maxDenseSizeFor(version: Int?): Int = when {
        version == null -> NAME_DENSE_END
        version >= 111 -> Pro800ProgramCodec.denseIndexOf(197) + 1 // ...Pitchbend Range
        version >= 110 -> Pro800ProgramCodec.denseIndexOf(191) + 1 // ...LFO Aftertouch Amount
        else -> NAME_DENSE_END // the name is the last field
    }

    private fun field(name: String, rawOffset: Int, byteCount: Int, signed: Boolean = false, minPresetVersion: Int = 0) =
        Pro800Field(name, Pro800ProgramCodec.denseIndexOf(rawOffset), byteCount, signed, minPresetVersion)

    /** Self-checks on the ported offset table, run once when [Pro800Program] is first used. */
    fun validate() {
        val fields = listOf(STORAGE_CODE, VERSION)
        fields.forEach { field ->
            require(field.denseOffset >= 0) { "${field.name} has a negative dense offset" }
        }
        fields.sortedBy { it.denseOffset }.zipWithNext { a, b ->
            require(a.denseOffset + a.byteCount <= b.denseOffset) {
                "${a.name} and ${b.name} overlap in the program record"
            }
        }
        require(NAME_DENSE_OFFSET > VERSION.denseOffset) { "the name field precedes the version field" }
    }
}

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
                dense, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount,
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
            copy, Pro800ProgramFields.NAME_DENSE_OFFSET, Pro800ProgramFields.NAME_LENGTH, newName,
        )
        return Pro800Program(copy)
    }

    companion object {
        init {
            Pro800ProgramFields.validate()
        }

        /** What a dump message's payload decodes to. */
        fun fromEncoded(encodedPayload: ByteArray) = Pro800Program(Pro800ProgramCodec.decode(encodedPayload))
    }
}
