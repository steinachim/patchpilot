package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * Where a value lives inside a decoded program record.
 *
 * [denseOffset] is in **dense** coordinates - the array [Pro800ProgramCodec.decode] produces, with
 * overflow bytes already removed. The documentation states raw offsets, so a field ported from it
 * goes through [Pro800ProgramCodec.denseIndexOf].
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
 * The subset of a program record this app reads.
 *
 * **On the source of truth.** `docs/Pro800SysExMessages.md` describes the structure correctly, but
 * several rows of its offset table do not add up arithmetically - a 2-byte field at 15 followed by
 * an overflow byte at 16, a jump from 135+4 to 140, an overflow byte given at both 136 and 142.
 * Some of those are typos in the table rather than facts about the device. So:
 * **`Pro800ProgramConstants.h` in the reference implementation is authoritative for offsets, and
 * the markdown for meaning.** Every offset below is a documented raw offset run through
 * [Pro800ProgramCodec.denseIndexOf], and each is listed with the raw value it came from so the two
 * can be checked against each other and against real dumps.
 *
 * Only what the browser needs is modelled. A full parameter map is a different feature (a knob
 * editor) with no Nord counterpart, and is deliberately out of scope - see the design's section 12.
 */
object Pro800ProgramFields {

    /** Raw offset 5: the record's own format version. 110 and 111 add fields; older ones lack them. */
    val VERSION = field("version", rawOffset = 5, byteCount = 1)

    /** Raw offsets 1..4: a storage code the instrument writes; used only to tell a written slot
     * from an uninitialized one. */
    val STORAGE_CODE = field("storageCode", rawOffset = 1, byteCount = 4)

    /**
     * The preset name: raw offsets **172**..189 inclusive, minus the two overflow bytes that fall
     * inside that span (176 and 184), giving 16 characters.
     *
     * **172, not 173.** `docs/Pro800SysExMessages.md` says 173; `Pro800ProgramConstants.h` says
     * `{172, 1, "Preset Name (first char)"}`, and the header is right. At 173 every preset name
     * comes back with its first character missing - "Classical Brass" as "lassical Brass".
     */
    val NAME_DENSE_OFFSET = Pro800ProgramCodec.denseIndexOf(172)
    const val NAME_LENGTH = 16

    /**
     * The newest preset version whose layout this app has been taught.
     *
     * Older records are read without complaint: the two fields read here (version, name) sit at
     * fixed offsets every version carries, and versions 110 and 111 only *append* fields. Format
     * 109 presets remain in normal use, so flagging those would be crying wolf. A version *newer*
     * than this may have moved something, and that is worth showing.
     */
    const val MAX_KNOWN_VERSION = 111

    /** Where the name field would end in a record long enough to carry all of it. Records are
     * routinely shorter than this - the instrument truncates trailing padding - so this is a
     * bound for reading, never a test for whether a preset exists. */
    val NAME_DENSE_END = NAME_DENSE_OFFSET + NAME_LENGTH

    /**
     * How long a record of a given preset format may be, in dense bytes.
     *
     * **The version field is a schema version, and the fields after the name are gated on it**:
     * format 110 appends LFO Aftertouch Amount (raw 190..191) and 111 appends Voice Spread, Key
     * Tracking Ref Note, Glide Mode and Pitchbend Range (raw 193..197). Writing those bytes into a
     * record that still calls itself 109 would produce a preset whose declared schema does not
     * match its contents - which the instrument would then read according to the version it was
     * told, not the one it was given.
     *
     * That makes [Pro800Program.withName]'s growth a correctness question rather than a padding
     * question, and the margin is exactly one byte: the name field ends at dense 165, and format
     * 110's first appended byte is dense 166. Growing to [NAME_DENSE_END] therefore stops
     * precisely at the boundary and adds nothing version-gated; a change to [NAME_LENGTH] or a
     * new appended field would cross it, which is what [Pro800Program.withName]'s check is for.
     *
     * The boundaries match the record lengths a full-length preset of each format actually
     * reaches: 166 dense bytes for format 109, 173 for format 111.
     */
    fun maxDenseSizeFor(version: Int?): Int = when {
        version == null -> NAME_DENSE_END
        version >= 111 -> Pro800ProgramCodec.denseIndexOf(197) + 1 // ...Pitchbend Range
        version >= 110 -> Pro800ProgramCodec.denseIndexOf(191) + 1 // ...LFO Aftertouch Amount
        else -> NAME_DENSE_END // the name is the last field
    }

    private fun field(name: String, rawOffset: Int, byteCount: Int, signed: Boolean = false, minPresetVersion: Int = 0) =
        Pro800Field(name, Pro800ProgramCodec.denseIndexOf(rawOffset), byteCount, signed, minPresetVersion)

    /**
     * The self-checks that make a ported offset table safe to trust.
     *
     * Run once from [Pro800Program]'s initializer rather than at every read: a table that overlaps
     * itself or runs off the end of the shortest record it claims to support is a porting mistake,
     * and finding it at startup beats finding it as a wrong value in a list row.
     */
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
 * One decoded program record.
 *
 * Construction is deliberately tolerant: a zero-length record is an *empty slot*, not an error,
 * and a short one is a perfectly normal truncated preset. Every one of the Pro-800's 400 addresses
 * answers a dump request one way or the other, so both are ordinary values rather than failures.
 */
class Pro800Program(val dense: ByteArray) {

    init {
        Pro800ProgramFields.validate()
    }

    /**
     * True where this address holds nothing.
     *
     * **A dump means a preset exists; length says nothing about it.** The instrument answers a
     * populated address with a `0x78` dump and an empty one with a bare `F0 F7`
     * ([Pro800SysEx.isEmptyReply]), which arrives here as a zero-length record. That is the whole
     * test.
     *
     * Neither length nor name can stand in for that test. **Records are variable length**: the
     * instrument truncates trailing padding, so the same firmware returns 173, 166, 158 and 157
     * dense bytes for four consecutive populated slots. And a preset may legitimately carry **no
     * name at all** (one measured did), so an empty name is not an empty slot either.
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

    /** False only for a version *newer* than this app knows - see [Pro800ProgramFields.MAX_KNOWN_VERSION]. */
    val isKnownVersion: Boolean get() = (version ?: 0) <= Pro800ProgramFields.MAX_KNOWN_VERSION

    /**
     * Is this record longer than its own version byte says it may be?
     *
     * A record that is, is **not a preset the instrument sent**. The version field is a schema
     * version and each one bounds the record's length ([Pro800ProgramFields.maxDenseSizeFor]), so a
     * record outrunning its own declaration is internally inconsistent and cannot be trusted.
     *
     * **This is a corruption check, and it is checked on the way in.** On a shared MIDI bus - MIDI
     * has no exclusive open, and a second application reading presets on the same port is enough -
     * replies get **spliced**: under load one reply loses its `F7` terminator and runs together
     * with the next, producing a message with our manufacturer header, our message type and *our
     * echoed address*, because its head genuinely is our reply, and another record's tail. Roughly
     * 1% of reads return a reply spliced from two adjacent records rather than one clean record.
     *
     * Address matching cannot catch it, because the address bytes arrive before the splice does.
     * Length can: every splice measured ran to 228, 229 or 231 bytes against a valid maximum of
     * 210.
     *
     * **Not a proof, and deliberately not described as one.** A splice that drops enough of the
     * middle lands back inside the range - one measured at 21 bytes did, and was caught only by
     * decoding to an implausible version. What this converts is the *silent* case: a corrupt record
     * that would otherwise be shown as a preset, and worse, be used as the source of a rename and
     * written back.
     */
    val outrunsDeclaredVersion: Boolean
        get() = !isEmpty && dense.size > Pro800ProgramFields.maxDenseSizeFor(version)

    /**
     * Is this record short enough that it might be a *truncated* splice rather than a preset?
     *
     * **A suspicion, not a verdict** - and the distinction is the whole design. Splices come in two
     * shapes. The long ones are caught outright by [outrunsDeclaredVersion], because outrunning
     * your own version byte is impossible for a genuine record. The short ones - as short as 4, 7,
     * 9 or 18 dense bytes - are not detectable that way: some carry a perfectly plausible version
     * of 109, because the splice fell after the version field.
     *
     * **There is no safe length to reject on**, which is what makes this a trigger rather than a
     * test. The instrument truncates after the last meaningful byte, so a record with an empty name
     * ends at its last non-zero *parameter*. Across a sample of 102 records: if their name fields
     * were empty, 90 would end at dense 149, eleven at 150, and one at **85**. A floor at the name
     * field would therefore reject an unnamed variant of almost every preset on the instrument, and
     * a floor low enough to be safe would be too low to catch much. Format 109 is not
     * factory-only either - presets from a SysEx file or an older firmware can carry it - so
     * "109 records are always named" is not something to lean on.
     *
     * What is true is that **corruption does not reproduce and truncation does**: a genuinely short
     * record reads the same twice, and a splice is a collision that will not recur byte for byte.
     * So [Pro800Instrument] re-reads a record this flags and accepts it only if the two agree.
     * Being wrong here costs one extra round trip, which is why the threshold can be generous - and
     * on a normal instrument it never fires at all, every record measured being 155 dense or more.
     */
    val isSuspiciouslyShort: Boolean
        get() = !isEmpty && dense.size < Pro800ProgramFields.NAME_DENSE_OFFSET

    /**
     * The preset's name, or null where it has none.
     *
     * Null here means "this preset is unnamed", **not** "there is no preset" - see [isEmpty] for
     * that. One of the instrument's own presets came back with an empty name field, so the two
     * have to be distinguishable.
     *
     * `readString` clamps to the record's actual length, which matters: a truncated record may
     * end partway through the name field.
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
     * A copy with the name replaced - the read-modify-write a rename is built from, since the
     * instrument has no rename command.
     *
     * **Preserves the record's version rather than upgrading it.** The reference implementation's
     * constructor resizes a short (pre-111) message and stamps it as 111, which silently converts
     * a preset the user never asked to convert. Round-tripping a rename must not do that (design
     * section 11.7).
     */
    fun withName(newName: String): Pro800Program {
        check(!isEmpty) { "cannot rename an uninitialized slot" }
        // **Grow the record if the old name left no room for the new one.** Records arrive
        // truncated after their last meaningful byte, so a preset called "Harp" comes back 155
        // dense bytes long - eleven short of the name field's end. Writing a longer name into that
        // is not a mistake to reject, it is the normal case: the field is 16 wide and the record
        // simply has not been carrying all of it.
        val target = maxOf(dense.size, Pro800ProgramFields.NAME_DENSE_END)

        // **Growth must never reach a field this record's format does not declare.** The version
        // byte is a schema version: formats 110 and 111 append fields after the name, and a record
        // carrying them while still calling itself 109 is one the instrument would read by the
        // wrong schema. The name field ends exactly one byte before format 110's first addition,
        // so growing to NAME_DENSE_END is safe for every version - but the margin is one byte, and
        // leaving that implicit is how it would stop being true.
        // **A rename can never be what breaks this.** target is max(dense.size, NAME_DENSE_END),
        // and NAME_DENSE_END is 166 while the permitted maximum is 166, 168 or 173 - never below
        // it. So if the record was within its own limit when it arrived, it still is after the
        // rename, and a failure here means it arrived already outrunning its version byte. That
        // is [outrunsDeclaredVersion]'s corruption case, which the read path now rejects before a
        // record can reach this far; the check stays as the backstop for anything that builds a
        // record another way.
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
        /** What a dump message's payload decodes to. */
        fun fromEncoded(encodedPayload: ByteArray) = Pro800Program(Pro800ProgramCodec.decode(encodedPayload))
    }
}
