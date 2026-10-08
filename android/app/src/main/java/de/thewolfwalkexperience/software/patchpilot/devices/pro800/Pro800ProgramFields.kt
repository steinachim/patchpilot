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

    /**
     * Raw offsets 1..4: a storage code the instrument writes. Not read: an empty slot is told by
     * its empty reply ([Pro800Program.isEmpty]). Listed so a test checks the version byte does not
     * overlap it (`Pro800ProgramCodecTest`).
     */
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

        version >= 111 -> Pro800ProgramCodec.denseIndexOf(197) + 1

        // ...Pitchbend Range
        version >= 110 -> Pro800ProgramCodec.denseIndexOf(191) + 1

        // ...LFO Aftertouch Amount
        else -> NAME_DENSE_END // the name is the last field
    }

    private fun field(name: String, rawOffset: Int, byteCount: Int) =
        Pro800Field(name, Pro800ProgramCodec.denseIndexOf(rawOffset), byteCount)
}
