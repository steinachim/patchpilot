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
 * The other 461 bytes are the sound and are not decoded: an edit changes the name or the
 * category and writes every other byte back as it was read.
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
        get() = String(CharArray(NAME_LENGTH) { i ->
            val code = bytes[i].toInt() and 0xFF
            if (code in PRINTABLE) code.toChar() else '?'
        }).trimEnd()

    /**
     * Empty, as this app counts it: named `Init Preset`, which is what both a never-written slot
     * and the panel's Initialize hold. A preset the user saved under that name counts too.
     */
    val isEmpty: Boolean get() = name == EMPTY_NAME

    /** Byte 50: a [Subsequent37Categories] value, or [Subsequent37Categories.UNSET]. */
    val category: Int get() = bytes[CATEGORY_OFFSET].toInt() and 0xFF

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
        return Subsequent37Program(Subsequent37SysEx.withChecksum(bytes.copyOf().also { it[CATEGORY_OFFSET] = value.toByte() }))
    }

    /** Whether both hold the same 488 bytes. */
    fun sameBytesAs(other: Subsequent37Program): Boolean = bytes.contentEquals(other.bytes)

    companion object {
        const val NAME_LENGTH = 24
        const val EMPTY_NAME = "Init Preset"
        private const val CATEGORY_OFFSET = 50
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

/**
 * The 22 categories byte 50 can hold, and [UNSET] for none.
 *
 * The value is the category's position in the front panel's list counted from the end: the
 * panel lists `User` first and `Misc` last, and byte 50 holds `Misc` as 0 and `User` as 21.
 * **Assumption:** `Vox` (`0x11`) and `Misc` (`0x00`) were read off the panel; the other 20
 * values follow from the panel's order and agree with every preset name in one 256-program
 * library, but were not each read off the instrument.
 *
 * [UNSET] (`0x77`) is what Initialize leaves. The panel shows it as `Misc`, like value 0.
 */
object Subsequent37Categories {

    /** In the front panel's order, which is the order the app lists them in. */
    val PANEL_ORDER = listOf(
        "User", "Percussion", "Sequence", "Arp", "Vox", "Distorted", "Evolving", "Pads",
        "Strings", "Sync", "Organs", "Keys", "Sound FX", "Woodwind", "Brass", "Lead", "Bass",
        "Noise", "Drone", "Duo", "Classic", "Misc",
    )

    const val UNSET = 0x77

    /** The byte-50 value of the category at [panelIndex] in [PANEL_ORDER]. */
    fun valueOf(panelIndex: Int): Int {
        require(panelIndex in PANEL_ORDER.indices) { "No category at $panelIndex." }
        return PANEL_ORDER.size - 1 - panelIndex
    }

    /** The [PANEL_ORDER] index for byte-50 [value], or null for [UNSET] or a value with no category. */
    fun panelIndexOf(value: Int): Int? =
        if (value in PANEL_ORDER.indices) PANEL_ORDER.size - 1 - value else null

    fun nameOf(value: Int): String? = panelIndexOf(value)?.let { PANEL_ORDER[it] }

    fun isStorable(value: Int): Boolean = value in PANEL_ORDER.indices || value == UNSET
}

/**
 * Programs out of a bulk dump (`01 07`) as its bytes arrive, in any chunking: block `i` is
 * program `i`, at offset `23 + 796 × i`, and its 732 packed bytes can be checked on their own
 * (record markers, checksum) without waiting for the rest.
 *
 * A dump that lost bytes in transit still ends in `F7`, with every block after the loss shifted.
 * So decoding stops at the first block that fails, and nothing after it is ever returned, even
 * a block that happens to pass.
 */
class Subsequent37BulkDecoder {

    private var buffer = ByteArray(0)
    private var size = 0

    /** How many bytes of the message have been fed. */
    val received: Int get() = size

    /** The next block to decode; also how many have been returned. */
    var decoded: Int = 0
        private set

    /** The first block that failed, or null while none has. */
    var brokenAt: Int? = null
        private set

    /** Whether the bytes fed so far start like a bulk dump; false until five have arrived. */
    val headerSeen: Boolean
        get() = size >= 5 && Subsequent37SysEx.isCommand(buffer.copyOf(5), *Subsequent37SysEx.REPLY_BULK_DUMP)

    /** Appends the next bytes of the message, `F0` first, and returns the programs they completed. */
    fun feed(chunk: ByteArray): List<Subsequent37Program> {
        if (size + chunk.size > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size, INITIAL_CAPACITY))
        }
        chunk.copyInto(buffer, size)
        size += chunk.size
        return decodeAvailable()
    }

    /**
     * What is wrong with the message once it has ended at [total] bytes, `F7` included, or null
     * where it is a whole dump of [Subsequent37SysEx.PROGRAM_COUNT] programs. Call after feeding it all.
     */
    fun problemAtEnd(total: Int = size): String? = when {
        !headerSeen -> "the reply is not a bulk dump"
        brokenAt != null -> "program $brokenAt arrived damaged"
        (total - Subsequent37SysEx.BODY_OFFSET - 1) % Subsequent37SysEx.BULK_STRIDE != 0 ->
            "the reply is $total bytes, which is not a whole number of programs"
        decoded < Subsequent37SysEx.PROGRAM_COUNT -> "the reply holds $decoded programs, not ${Subsequent37SysEx.PROGRAM_COUNT}"
        else -> null
    }

    private fun decodeAvailable(): List<Subsequent37Program> {
        if (brokenAt != null || size < 5) return emptyList()
        if (!headerSeen) {
            brokenAt = decoded
            return emptyList()
        }
        val out = mutableListOf<Subsequent37Program>()
        while (decoded < Subsequent37SysEx.PROGRAM_COUNT) {
            val at = Subsequent37SysEx.BODY_OFFSET + decoded * Subsequent37SysEx.BULK_STRIDE
            if (at + Subsequent37SysEx.PACKED_PROGRAM_BYTES > size) break
            val payload = Subsequent37SysEx.unpack(buffer, at, Subsequent37SysEx.PAYLOAD_BYTES / 2)
            if (payload == null || !Subsequent37SysEx.checksumCloses(payload)) {
                brokenAt = decoded
                break
            }
            out += Subsequent37Program(payload)
            decoded++
        }
        return out
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
    }
}
