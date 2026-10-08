// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

/**
 * One stored program: the 488-byte payload a single-program message or a bulk-dump block carries.
 *
 * | Offset | Size | Field |
 * |---|---|---|
 * | 0 | 24 | name, printable ASCII, space-padded |
 * | 50 | 1 | category ([Subsequent37Categories]) |
 * | 51 | 1 | stored as given; the panel's own save writes `7` |
 * | 486 | 2 | checksum ([Subsequent37SysEx.checksumCloses]) |
 *
 * The other 460 bytes are the sound and are not decoded, nor is byte 51: an edit changes the
 * name or the category and writes every other byte back as it was read.
 */
class Subsequent37Program(payload: ByteArray) {

    init {
        require(payload.size == Subsequent37SysEx.PAYLOAD_BYTES) {
            "A program is ${Subsequent37SysEx.PAYLOAD_BYTES} bytes, not ${payload.size}."
        }
    }

    private val bytes = payload.copyOf()

    /** A copy, so nothing outside can change this program. */
    val payload: ByteArray get() = bytes.copyOf()

    /**
     * The name as stored, trailing spaces removed. A run of spaces inside it is kept: the panel
     * shows the field as two lines of 12, and a name that fills the first line with spaces
     * continues on the second.
     */
    val name: String
        get() = String(
            CharArray(NAME_LENGTH) { i ->
                val code = bytes[i].toInt() and 0xFF
                if (code in PRINTABLE) code.toChar() else '?'
            },
        ).trimEnd()

    /**
     * Empty, as this app counts it: named `Init Preset`, which is what both a never-written slot
     * and the panel's Initialize hold. A preset the user saved under that name counts too.
     */
    val isEmpty: Boolean get() = name == EMPTY_NAME

    /** Byte 50: a [Subsequent37Categories] value, or [Subsequent37Categories.UNSET]. */
    val category: Int get() = bytes[CATEGORY_OFFSET].toInt() and 0xFF

    /** Byte 51, as stored and not decoded; the panel's own save writes `7`. */
    val byte51: Int get() = bytes[BYTE_51_OFFSET].toInt() and 0xFF

    val checksumCloses: Boolean get() = Subsequent37SysEx.checksumCloses(bytes)

    /** This program renamed [newName], checksum closed; every other byte unchanged. */
    fun withName(newName: String): Subsequent37Program {
        requireStorableName(newName)
        val field = newName.padEnd(NAME_LENGTH).toByteArray(Charsets.US_ASCII)
        return Subsequent37Program(Subsequent37SysEx.withChecksum(field + bytes.copyOfRange(NAME_LENGTH, bytes.size)))
    }

    /** This program filed under [value], checksum closed; byte 51 and all else unchanged. */
    fun withCategory(value: Int): Subsequent37Program {
        require(Subsequent37Categories.isStorable(value)) { "Category value $value is not one the Subsequent 37 has." }
        val filed = bytes.copyOf().also { it[CATEGORY_OFFSET] = value.toByte() }
        return Subsequent37Program(Subsequent37SysEx.withChecksum(filed))
    }

    /** Whether both hold the same 488 bytes. */
    fun sameBytesAs(other: Subsequent37Program): Boolean = bytes.contentEquals(other.bytes)

    companion object {
        const val NAME_LENGTH = 24
        const val EMPTY_NAME = "Init Preset"
        private const val CATEGORY_OFFSET = 50
        private const val BYTE_51_OFFSET = 51
        private val PRINTABLE = 0x20..0x7E

        /**
         * Refuses what the name field cannot hold, rather than cutting it: longer than 24
         * characters, or anything outside printable ASCII.
         */
        fun requireStorableName(name: String) {
            require(name.length <= NAME_LENGTH) {
                "A Subsequent 37 preset name can be at most $NAME_LENGTH characters; '$name' is ${name.length}."
            }
            require(name.all { it.code in PRINTABLE }) {
                "A Subsequent 37 preset name must be plain ASCII letters, digits, spaces and punctuation."
            }
        }
    }
}
