// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class Pro800ProgramCodecTest {

    /**
     * The encoding, spelled out by hand: an overflow byte every 8 positions, whose bit *n* is the
     * stripped high bit of the byte at offset +(n+1).
     */
    @Test
    fun `decode reassembles high bits from the overflow byte`() {
        // Overflow 0b0000_0101 -> the 1st and 3rd payload bytes had their high bit set.
        val encoded = byteArrayOf(0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 0x02, 0x83.toByte(), 0x04, 0x05, 0x06, 0x07),
            Pro800ProgramCodec.decode(encoded),
        )
    }

    @Test
    fun `encode restores the overflow byte`() {
        val dense = byteArrayOf(0x81.toByte(), 0x02, 0x83.toByte(), 0x04, 0x05, 0x06, 0x07)
        assertArrayEquals(
            byteArrayOf(0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07),
            Pro800ProgramCodec.encode(dense),
        )
    }

    /** The property that matters: whatever the instrument sent, we can send back unchanged. */
    @Test
    fun `encode is the exact inverse of decode over random data`() {
        val random = Random(20260817)
        repeat(200) {
            val dense = ByteArray(random.nextInt(1, 400)) { random.nextInt(256).toByte() }
            assertArrayEquals(dense, Pro800ProgramCodec.decode(Pro800ProgramCodec.encode(dense)))
        }
    }

    @Test
    fun `a real-sized program record round-trips`() {
        // A 210-byte program message carries 198 encoded data bytes: 24 whole groups plus an
        // overflow byte and five values.
        val encodedSize = 198
        val random = Random(7)
        val dense = ByteArray(Pro800ProgramCodec.denseSizeOf(encodedSize)) { random.nextInt(256).toByte() }
        val encoded = Pro800ProgramCodec.encode(dense)
        assertEquals(encodedSize, encoded.size)
        assertArrayEquals(dense, Pro800ProgramCodec.decode(encoded))
    }

    /**
     * The mapping that makes the dense-coordinate design equivalent to the reference
     * implementation's hybrid indexing: raw p -> p - p/8 - 1.
     */
    @Test
    fun `denseIndexOf translates the documented raw offsets`() {
        assertEquals(0, Pro800ProgramCodec.denseIndexOf(1))
        assertEquals(6, Pro800ProgramCodec.denseIndexOf(7))
        assertEquals(7, Pro800ProgramCodec.denseIndexOf(9))
        assertEquals(4, Pro800ProgramCodec.denseIndexOf(5))
        // The name field's start, per Pro800ProgramConstants.h (the markdown says 173; see
        // Pro800ProgramFields.NAME_DENSE_OFFSET for why the header wins).
        assertEquals(150, Pro800ProgramCodec.denseIndexOf(172))
    }

    /**
     * A multi-byte value straddling an overflow byte is the case the reference implementation
     * needs special skip logic for. In dense coordinates it stops being a case at all - raw 15 and
     * raw 17 (with the overflow byte at 16 between them) are simply adjacent.
     */
    @Test
    fun `a value straddling an overflow byte is contiguous in dense coordinates`() {
        assertEquals(
            Pro800ProgramCodec.denseIndexOf(15) + 1,
            Pro800ProgramCodec.denseIndexOf(17),
        )
    }

    @Test
    fun `an overflow position is not a valid field offset`() {
        assertThrows(IllegalArgumentException::class.java) { Pro800ProgramCodec.denseIndexOf(0) }
        assertThrows(IllegalArgumentException::class.java) { Pro800ProgramCodec.denseIndexOf(16) }
    }

    @Test
    fun `values are little-endian and can be sign-extended`() {
        val dense = byteArrayOf(0x34, 0x12, 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0x1234, Pro800ProgramCodec.readValue(dense, 0, 2))
        assertEquals(0xFFFF, Pro800ProgramCodec.readValue(dense, 2, 2))
        assertEquals(-1, Pro800ProgramCodec.readValue(dense, 2, 2, signed = true))
    }

    @Test
    fun `reading past the end of a record is refused rather than returning garbage`() {
        val dense = ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) { Pro800ProgramCodec.readValue(dense, 3, 2) }
        assertThrows(IllegalArgumentException::class.java) { Pro800ProgramCodec.readValue(dense, -1, 1) }
    }

    @Test
    fun `writeValue round-trips through readValue`() {
        val dense = ByteArray(8)
        Pro800ProgramCodec.writeValue(dense, 2, 2, 0xBEEF)
        assertEquals(0xBEEF, Pro800ProgramCodec.readValue(dense, 2, 2))
    }

    /** A preset name is device-supplied text rendered straight into a list row, so control bytes
     * are stripped rather than passed through. */
    @Test
    fun `strings drop NULs and other control bytes wherever they fall`() {
        val dense = "Fat Saw\u0000Bass\u0000\u0000".toByteArray(Charsets.US_ASCII)
        assertEquals("Fat SawBass", Pro800ProgramCodec.readString(dense, 0, dense.size))

        val hostile = byteArrayOf(0x41, 0x1B, 0x5B, 0x33, 0x31, 0x6D, 0x42)
        assertEquals("A[31mB", Pro800ProgramCodec.readString(hostile, 0, hostile.size))
    }

    @Test
    fun `writeString pads with NULs and truncates rather than overrunning`() {
        val dense = ByteArray(10) { 0x7F }
        Pro800ProgramCodec.writeString(dense, 0, 6, "Hi")
        assertEquals("Hi", Pro800ProgramCodec.readString(dense, 0, 6))
        assertEquals(0x7F.toByte(), dense[6]) // untouched beyond the field

        Pro800ProgramCodec.writeString(dense, 0, 4, "Much too long")
        assertEquals("Much", Pro800ProgramCodec.readString(dense, 0, 4))
    }

    /**
     * Pins the preset name's position against a **literally constructed** record, rather than
     * against the constant the production code uses.
     *
     * `Pro800InstrumentTest` builds its fixtures with `Pro800ProgramFields.NAME_DENSE_OFFSET`, so
     * it agrees with whatever that constant says and would pass with a wrong value - where a real
     * Pro-800 returns "lassical Brass" for "Classical Brass".
     *
     * The layout here comes from `Pro800ProgramConstants.h`:
     * `{172, 1, "Preset Name (first char)"}` .. `{189, 1, "Preset Name (last char)"}`, with
     * overflow bytes at raw 176 and 184 falling inside that span, so 18 raw positions carry 16
     * characters.
     */
    @Test
    fun `the preset name sits at raw offsets 172 to 189`() {
        val name = "Classical Brass"
        val encoded = ByteArray(198)

        // Write the name across the raw positions the header names, skipping overflow bytes.
        var written = 0
        var raw = 172
        while (written < name.length) {
            if (raw % 8 != 0) {
                encoded[raw] = name[written].code.toByte()
                written++
            }
            raw++
        }

        val dense = Pro800ProgramCodec.decode(encoded)
        assertEquals(
            name,
            Pro800ProgramCodec.readString(
                dense,
                Pro800ProgramFields.NAME_DENSE_OFFSET,
                Pro800ProgramFields.NAME_LENGTH,
            ),
        )
    }

    /** The derivation of that offset, spelled out, so a change to it is a deliberate act. */
    @Test
    fun `the name field's dense offset and length are the ones the header implies`() {
        assertEquals(150, Pro800ProgramFields.NAME_DENSE_OFFSET) // raw 172 - 172/8 - 1
        assertEquals(16, Pro800ProgramFields.NAME_LENGTH) // raw 172..189, less overflow at 176, 184
    }

    // ---- patchValue ----

    /** Whatever was patched in is what decode reads back out, at every offset and width. */
    @Test
    fun `a patched value decodes to what was written`() {
        val encoded = Pro800ProgramCodec.encode(Random(7).nextBytes(40))
        for (denseOffset in 0 until 38) {
            val patched = Pro800ProgramCodec.patchValue(encoded, denseOffset, 2, 0xBEEF)
            assertEquals(
                "two bytes at dense $denseOffset",
                0xBEEF,
                Pro800ProgramCodec.readValue(Pro800ProgramCodec.decode(patched), denseOffset, 2),
            )
        }
    }

    /**
     * **Nothing outside the patched field moves - not one bit.**
     *
     * The reason this is a patcher rather than a decode/modify/encode round trip. A 46-byte
     * settings block ends mid-group: its last overflow byte governs only five value bytes, so two
     * of its bits belong to no value at all. `decode` never reads them and `encode` would rebuild
     * that byte from dense alone and zero them. This is a write to the block holding every global
     * setting, so bits nobody has accounted for are left exactly as they were read.
     */
    @Test
    fun `patching leaves every other byte and every unrelated overflow bit alone`() {
        // Every bit set, including the two the last overflow byte has no value for.
        val encoded = ByteArray(46) { 0x7F }
        val patched = Pro800ProgramCodec.patchValue(encoded, denseOffset = 5, byteCount = 2, value = 0)

        // Dense 5 and 6 are raw 6 and 7, sharing the overflow byte at raw 0 in bits 5 and 6.
        assertEquals(listOf(0, 6, 7), encoded.indices.filter { encoded[it] != patched[it] })
        assertEquals(0x7F and (1 shl 5).inv() and (1 shl 6).inv(), patched[0].toInt())
        // The trailing overflow byte governs five values, not seven; its top two bits survive.
        assertEquals(0x7F, patched[40].toInt())
    }

    /** A high bit belongs in the overflow byte, not truncated off the value. */
    @Test
    fun `a value above 0x7F stores its high bit in the overflow byte`() {
        val encoded = ByteArray(46)
        val patched = Pro800ProgramCodec.patchValue(encoded, denseOffset = 0, byteCount = 1, value = 0x80)

        assertEquals("the value byte keeps only its low seven bits", 0x00, patched[1].toInt())
        assertEquals("and the high bit lands in bit 0 of the overflow byte", 0x01, patched[0].toInt())
        assertEquals(0x80, Pro800ProgramCodec.decode(patched)[0].toInt() and 0xFF)
    }

    /** A field the record is too short to hold is a caller error, not a silently grown buffer. */
    @Test
    fun `patching past the end of a record is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pro800ProgramCodec.patchValue(ByteArray(46), denseOffset = 39, byteCount = 2, value = 1)
        }
    }
}
