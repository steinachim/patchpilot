package de.thewolfwalkexperience.software.patchpilot.devices.pro800

/**
 * The Pro-800's 7-bit-safe encoding, and the one simplification worth having over the reference
 * implementation.
 *
 * SysEx cannot carry a byte above `0x7F`, so Behringer inserts an **overflow byte** at every
 * position that is a multiple of 8; bit *n* of that byte is the stripped high bit of the byte at
 * offset +(n+1). Overflow bytes can fall in the middle of a multi-byte value.
 *
 * **Decode once into a dense array; express field offsets in dense coordinates.** The reference
 * implementation (`Pro800DataMessage.cpp`) instead reads fields straight out of the encoded
 * stream, skipping overflow positions inline and re-deriving the overflow bit on every byte
 * access. That works, but it makes every field offset a hybrid coordinate and the skip logic easy
 * to get subtly wrong where a value straddles an overflow byte.
 *
 * **This is provably equivalent, not a reinterpretation.** For a raw position `p` that is not a
 * multiple of 8, the dense index is `p - p/8 - 1`. The C++ `getValue()` walks raw positions and
 * skips any that land on a multiple of 8 - which maps onto *contiguous* dense indices. So reading
 * n contiguous little-endian bytes from the dense array yields exactly the same value, and
 * straddling an overflow byte stops being a special case at all. `denseIndexOf` below is that
 * mapping, kept so the documented raw offsets can be translated and checked rather than
 * hand-converted.
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
     * Dense 8-bit bytes -> encoded. The exact inverse of [decode] for any buffer [decode] can
     * produce from a real record.
     *
     * The one shape that does not round-trip is an encoded buffer ending on a *dangling overflow
     * byte* - one with no data bytes after it - because that byte carries no information and
     * [decode] rightly drops it. A real dump cannot look like that: a 210-byte program message
     * carries 198 encoded data bytes, which is 24 whole groups plus an overflow byte and five
     * values.
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
     * The dense index of a documented raw offset.
     *
     * `docs/Pro800SysExMessages.md` states field positions in *encoded* coordinates, so this is
     * how a field table ported from that document is translated. Throws for a raw offset that is
     * an overflow byte, since no field lives there.
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
     * **Deliberately not a [decode]/[encode] round trip.** That would be the obvious way to change
     * one field, and it is wrong for a record whose length ends mid-group. The settings block is 46
     * raw bytes: its last overflow byte governs only five value bytes, not seven, so two of its bits
     * have no owner. [decode] never reads them and [encode] would re-derive the byte from dense
     * alone and zero them. Whether real hardware ever puts anything there is unconfirmed, and this
     * is the block holding every global setting - so patch in place and leave every byte, and every
     * bit of a shared overflow byte, exactly as it was read.
     *
     * The mapping is the exact inverse of [decode]'s: dense index `d` sits in group `d / 7`, whose
     * overflow byte is at raw `group * 8` and whose value byte is at raw `group * 8 + d % 7 + 1`,
     * carrying its high bit in bit `d % 7` of that overflow byte.
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
     * An ASCII string field - a preset name is stored as a fixed span with no length prefix, so
     * anything past the name is padding.
     *
     * NULs are removed wherever they fall rather than only at the end, matching the reference
     * implementation, and every other control byte goes with them: this string is rendered
     * straight into a list row, and a preset name is device-supplied text like any other field.
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
