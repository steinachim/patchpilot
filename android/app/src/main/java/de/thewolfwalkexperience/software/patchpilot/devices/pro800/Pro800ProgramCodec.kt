// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * The Pro-800's 7-bit-safe encoding. SysEx cannot carry a byte above `0x7F`, so Behringer inserts
 * an overflow byte at every position that is a multiple of 8; bit *n* of that byte is the
 * stripped high bit of the byte at offset +(n+1). Overflow bytes can fall in the middle of a
 * multi-byte value.
 *
 * A record is decoded once into a dense array and field offsets are expressed in dense
 * coordinates: for a raw position `p` that is not a multiple of 8, the dense index is
 * `p - p/8 - 1`, and a value straddling an overflow byte is contiguous. [denseIndexOf] is that
 * mapping, so documented raw offsets can be translated rather than hand-converted.
 */
object Pro800ProgramCodec {

    private const val GROUP = 8

    /** Encoded (7-bit + overflow bytes) -> dense 8-bit bytes. */
    fun decode(encoded: ByteArray): ByteArray {
        val dense = ArrayList<Byte>(encoded.size)
        for (position in encoded.indices) {
            if (position % GROUP == 0) continue // the overflow byte itself carries no value
            val overflowByte = encoded[(position / GROUP) * GROUP].toInt() and 0xFF
            val overflowBit = (position % GROUP) - 1
            val high = if (overflowByte and (1 shl overflowBit) != 0) 0x80 else 0
            dense.add(((encoded[position].toInt() and 0x7F) or high).toByte())
        }
        return dense.toByteArray()
    }

    /**
     * Dense 8-bit bytes -> encoded. The inverse of [decode] for every buffer a real record
     * produces; an encoded buffer ending on a dangling overflow byte does not round-trip, and a
     * real dump never ends on one.
     */
    fun encode(dense: ByteArray): ByteArray {
        val encoded = ArrayList<Byte>(dense.size + dense.size / (GROUP - 1) + 1)
        var index = 0
        while (index < dense.size) {
            val chunk = dense.copyOfRange(index, minOf(index + GROUP - 1, dense.size))
            var overflow = 0
            chunk.forEachIndexed { i, byte ->
                if (byte.toInt() and 0x80 != 0) overflow = overflow or (1 shl i)
            }
            encoded.add(overflow.toByte())
            chunk.forEach { encoded.add((it.toInt() and 0x7F).toByte()) }
            index += GROUP - 1
        }
        return encoded.toByteArray()
    }

    /**
     * The dense index of a documented raw offset - the reference implementation states positions
     * in encoded coordinates. Throws for an overflow byte, where no field lives.
     */
    fun denseIndexOf(rawOffset: Int): Int {
        require(rawOffset % GROUP != 0) { "raw offset $rawOffset is an overflow byte, not a field" }
        return rawOffset - rawOffset / GROUP - 1
    }

    /** How many dense bytes an encoded buffer of [encodedSize] holds. */
    fun denseSizeOf(encodedSize: Int): Int = encodedSize - (encodedSize + GROUP - 1) / GROUP

    /**
     * A copy of [encoded] with [byteCount] little-endian bytes at [denseOffset] set to [value],
     * touching only those value bytes and the overflow bits that carry their high bits.
     *
     * Not a [decode]/[encode] round trip: the settings block is 46 raw bytes and ends mid-group,
     * so its last overflow byte has two bits no value owns, which a re-encode would zero. Whether
     * the hardware puts anything there is unknown, so the block is patched in place. Dense index
     * `d` sits in group `d / 7`, whose overflow byte is at raw `group * 8` and whose value byte
     * is at raw `group * 8 + d % 7 + 1`, with its high bit in bit `d % 7` of the overflow byte.
     */
    fun patchValue(encoded: ByteArray, denseOffset: Int, byteCount: Int, value: Int): ByteArray {
        require(byteCount in 1..4) { "only 1..4 byte values are defined, asked for $byteCount" }
        require(denseOffset >= 0) { "dense offset $denseOffset is negative" }
        val patched = encoded.copyOf()
        for (i in 0 until byteCount) {
            val dense = denseOffset + i
            val group = dense / (GROUP - 1)
            val overflowPosition = group * GROUP
            val bit = dense % (GROUP - 1)
            val valuePosition = overflowPosition + bit + 1
            require(valuePosition < patched.size) {
                "dense byte $dense is past the end of this ${encoded.size}-byte record"
            }
            val byte = (value shr (i * 8)) and 0xFF
            patched[valuePosition] = (byte and 0x7F).toByte()
            val overflow = patched[overflowPosition].toInt() and 0x7F
            patched[overflowPosition] = ((overflow and (1 shl bit).inv()) or ((byte shr 7) shl bit)).toByte()
        }
        return patched
    }

    /** [byteCount] little-endian bytes at [denseOffset], optionally sign-extended. */
    fun readValue(dense: ByteArray, denseOffset: Int, byteCount: Int, signed: Boolean = false): Int {
        require(byteCount in 1..4) { "only 1..4 byte values are defined, asked for $byteCount" }
        require(denseOffset >= 0 && denseOffset + byteCount <= dense.size) {
            "value at $denseOffset..${denseOffset + byteCount - 1} runs past a ${dense.size}-byte record"
        }
        var value = 0
        for (i in 0 until byteCount) {
            value = value or ((dense[denseOffset + i].toInt() and 0xFF) shl (i * 8))
        }
        if (signed) {
            val signBit = 1 shl (byteCount * 8 - 1)
            if (value and signBit != 0) value = value or (-1 shl (byteCount * 8))
        }
        return value
    }

    fun writeValue(dense: ByteArray, denseOffset: Int, byteCount: Int, value: Int) {
        require(denseOffset >= 0 && denseOffset + byteCount <= dense.size) {
            "value at $denseOffset..${denseOffset + byteCount - 1} runs past a ${dense.size}-byte record"
        }
        for (i in 0 until byteCount) {
            dense[denseOffset + i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
    }

    /**
     * An ASCII string field: a fixed span with no length prefix. NULs are removed wherever they
     * fall, matching the reference implementation, and so is every other control byte, since the
     * string is rendered straight into a list row.
     */
    fun readString(dense: ByteArray, denseOffset: Int, length: Int): String {
        if (denseOffset >= dense.size) return ""
        val end = minOf(denseOffset + length, dense.size)
        return String(dense, denseOffset, end - denseOffset, Charsets.US_ASCII)
            .filter { it.code in 0x20..0x7E }
            .trim()
    }

    /** Writes [value] into a fixed span, NUL-padding the remainder and truncating an over-long
     * name rather than running past the field. */
    fun writeString(dense: ByteArray, denseOffset: Int, length: Int, value: String) {
        require(denseOffset + length <= dense.size) {
            "string at $denseOffset..${denseOffset + length - 1} runs past a ${dense.size}-byte record"
        }
        val bytes = value.toByteArray(Charsets.US_ASCII)
        for (i in 0 until length) {
            dense[denseOffset + i] = if (i < bytes.size) bytes[i] else 0
        }
    }
}
