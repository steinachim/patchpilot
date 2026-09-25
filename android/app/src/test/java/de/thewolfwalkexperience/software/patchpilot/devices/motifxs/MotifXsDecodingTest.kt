// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder against realistic sample byte sequences, not against values the encoder itself
 * produced.
 *
 * Every assertion here is anchored to [MotifXsFixtures], not to a message this test built from the
 * same constants the code uses. That distinction is the whole point: a fixture assembled by the
 * encoder only proves the encoder and decoder agree with each other, which says nothing about
 * whether either is actually correct.
 */
class MotifXsDecodingTest {

    @Test
    fun `a sample dump satisfies the length formula and the checksum`() {
        for (dump in listOf(
            MotifXsFixtures.namedVoice,
            MotifXsFixtures.longNameVoice,
            MotifXsFixtures.emptyVoice,
        )) {
            val count = MotifXsSysEx.declaredCount(dump)!!
            assertEquals("message is count + 12 bytes", count + MotifXsSysEx.BULK_OVERHEAD, dump.size)
            assertTrue(MotifXsSysEx.isWellFormedBulkDump(dump))
        }
    }

    /**
     * A drum voice is ~12.6 kB where a user voice is ~1.9 kB, and it decodes identically. The size
     * is the point: `SysExFramer`'s default 4096-byte cap would drop all 32 of them, so this test
     * exists next to [`a drum voice survives a framer sized for this instrument`] rather than
     * only asserting the name.
     */
    @Test
    fun `a drum voice is the same format at seven times the size`() {
        val drum = MotifXsFixtures.drumVoice
        assertEquals(12600, drum.size)
        assertTrue(MotifXsSysEx.isWellFormedBulkDump(drum))
        assertEquals(Triple(0x0C, 0x28, 0x00), MotifXsSysEx.addressOf(drum))
        // An initialized kit: its figure pair decodes, and its cleared name reads as none.
        assertEquals(listOf(192, 192), MotifXsVoice.categoriesOf(MotifXsSysEx.dumpPayload(drum)))
        assertNull(MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(drum)))
    }

    /**
     * The framer must be configured for this instrument, not left on its default.
     *
     * This is the regression test for a bug that would have shipped: the default cap is 4096
     * bytes, sized for a Pro-800's 210-byte program, and every drum voice exceeds it. The failure
     * mode is silent - the message is dropped, the read times out, and the DRUM bank simply lists
     * as 32 unreadable slots.
     */
    @Test
    fun `a drum voice survives a framer sized for this instrument`() {
        val drum = MotifXsFixtures.drumVoice

        val default = SysExFramer()
        assertTrue("the default cap must be shown to drop it", default.feed(drum).isEmpty())
        assertEquals(1, default.droppedMessages)

        val sized = SysExFramer(maxMessageBytes = 32768)
        val framed = sized.feed(drum)
        assertEquals(1, framed.size)
        assertArrayEquals(drum, framed.single())
        assertEquals(0, sized.droppedMessages)
    }

    @Test
    fun `a dump reports the address it is for`() {
        assertEquals(Triple(0x0C, 0x0A, 0x0F), MotifXsSysEx.addressOf(MotifXsFixtures.namedVoice))
        assertEquals(Triple(0x0C, 0x0B, 0x2C), MotifXsSysEx.addressOf(MotifXsFixtures.longNameVoice))
        assertEquals(MotifXsSysEx.TYPE_BULK_DUMP, MotifXsSysEx.typeOf(MotifXsFixtures.namedVoice))
    }

    @Test
    fun `names come back off the wire`() {
        assertEquals(
            "TWE2 Wolf Walk",
            MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(MotifXsFixtures.namedVoice)),
        )
    }

    /**
     * The name ends where its repeat says, not where printing stops.
     *
     * A printable read overshoots by one whenever the trailer's first byte happens to be
     * printable, which it is for 54 of the instrument's 334 named slots. The name is repeated
     * [MotifXsVoice.NAME_TRAILER_BYTES] after its real end, so the repeat's position settles the
     * length. All three names below are what the instrument's own display shows.
     */
    @Test
    fun `a name ends where its repeat says it does`() {
        assertEquals("TWE2 Stranger", MotifXsVoice.nameOf(
            MotifXsSysEx.dumpPayload(MotifXsFixtures.overshootVoice)))
        assertEquals("TWE2 Addicted", MotifXsVoice.nameOf(
            MotifXsSysEx.dumpPayload(MotifXsFixtures.secondOvershootVoice)))
        assertEquals("TWE2 Dreadnought 2.0", MotifXsVoice.nameOf(
            MotifXsSysEx.dumpPayload(MotifXsFixtures.longNameVoice)))
    }

    // `TWE2 Growing Pains` is why no "strip a trailing letter" rule is used: its `s` is genuine,
    // and the bytes after it are byte-for-byte the ordinary case - the repeat sits at +6, not +5.
    // Every content-based heuristic anyone might reach for corrupts this one.

    /**
     * A stray printable byte after a *blank* name is not a name.
     *
     * `0C`'s layout past the name field is undecoded, so a slot whose name has been cleared
     * exposes whatever byte happens to sit there - and a greedy printable read calls it a
     * one-character voice. A cleared drum kit reading back as `b` would make the browser render a
     * row named "b" instead of an empty slot; an empty row is the only signal it has, since
     * nothing on the wire flags emptiness.
     *
     * The rule that tells them apart: a real name is repeated 6 bytes past its end. Requiring
     * that costs nothing on real data - the strict and greedy readings agree everywhere except on
     * a genuinely cleared slot.
     */
    @Test
    fun `a stray printable byte after a blank name reads as empty`() {
        // The real case: the initialized drum kit opens "192:192:" then a lone `b`.
        assertNull(MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(MotifXsFixtures.drumVoice)))

        // The same shape, hand-built: "192:192:" then a lone printable byte, then nothing that
        // repeats it.
        val withStray = packed("192:192:".toByteArray() + byteArrayOf(0x62) + ByteArray(40))
        assertNull(MotifXsVoice.nameOf(withStray))

        // The same shape with a real name - repeat present 6 bytes past the end - still decodes.
        val name = "Kit".toByteArray()
        val real = packed(
            "192:192:".toByteArray() + name + ByteArray(MotifXsVoice.NAME_TRAILER_BYTES) +
                name + ByteArray(24)
        )
        assertEquals("Kit", MotifXsVoice.nameOf(real))
    }

    /** MSB-packs a dense stream and prefixes the two bytes a payload carries before it. */
    private fun packed(dense: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0); out.add(0)
        var i = 0
        while (i < dense.size) {
            var msb = 0
            val chunk = ArrayList<Byte>()
            for (j in 0 until 7) {
                if (i + j >= dense.size) break
                val b = dense[i + j].toInt() and 0xFF
                if (b and 0x80 != 0) msb = msb or (1 shl j)
                chunk.add((b and 0x7F).toByte())
            }
            out.add(msb.toByte())
            out.addAll(chunk)
            i += 7
        }
        return out.toByteArray()
    }

    @Test
    fun `a genuine trailing letter is not stripped`() {
        assertEquals("TWE2 Growing Pains", MotifXsVoice.nameOf(
            MotifXsSysEx.dumpPayload(MotifXsFixtures.genuineTrailingLetterVoice)))
    }

    /** 20 is an observation the trailer rule yields, not a bound fed into it. */
    @Test
    fun `the longest name is twenty characters`() {
        val longest = MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(MotifXsFixtures.longNameVoice))
        assertEquals("TWE2 Dreadnought 2.0", longest)
        assertEquals(20, longest!!.length)
    }

    /**
     * The figure pair before the name is variable width, so the name's offset is too. Reading it
     * at a fixed offset of 8 mis-decodes any voice whose figure pair is narrower than three
     * digits each, dropping the leading character of the name.
     */
    @Test
    fun `a narrower figure pair moves where the name starts`() {
        val short = MotifXsSysEx.dumpPayload(MotifXsFixtures.shortPrefixVoice)
        assertEquals(7, MotifXsVoice.nameStart(MotifXsVoice.unpack(short.copyOfRange(
            MotifXsVoice.PACKED_OFFSET, short.size))))
        assertEquals("TWE2 Ashes", MotifXsVoice.nameOf(short))
        assertNotEquals("WE2 Ashes", MotifXsVoice.nameOf(short))

        val zero = MotifXsSysEx.dumpPayload(MotifXsFixtures.zeroPrefixVoice)
        assertEquals(4, MotifXsVoice.nameStart(MotifXsVoice.unpack(zero.copyOfRange(
            MotifXsVoice.PACKED_OFFSET, zero.size))))
        assertEquals("TWE2 Capital Of Low", MotifXsVoice.nameOf(zero))
    }

    /**
     * A 20-character name behind a two-digit figure, and one behind a three-digit figure whose
     * next field opens with a printable `S`: both decode to exactly their 20 characters.
     */
    @Test
    fun `a full-width name does not run into the next field`() {
        val payload = MotifXsSysEx.dumpPayload(MotifXsFixtures.fullWidthNameVoice)
        assertEquals("TWE2 Blue Matter 2.0", MotifXsVoice.nameOf(payload))

        val long = MotifXsSysEx.dumpPayload(MotifXsFixtures.longNameVoice)
        assertEquals("TWE2 Dreadnought 2.0", MotifXsVoice.nameOf(long))
        assertNotEquals("TWE2 Dreadnought 2.0S", MotifXsVoice.nameOf(long))
    }

    /**
     * The empty slot is a **full-length, checksum-valid dump**. Nothing about its size or framing
     * says "empty"; only the absence of a printable name does. Asserting both halves here guards
     * against a decoder that assumes size or framing can substitute for that check.
     */
    @Test
    fun `an empty slot is a valid dump with no name rather than a short or absent one`() {
        assertTrue(MotifXsSysEx.isWellFormedBulkDump(MotifXsFixtures.emptyVoice))
        assertTrue(MotifXsFixtures.emptyVoice.size > 1900)
        assertNull(MotifXsVoice.nameOf(MotifXsSysEx.dumpPayload(MotifXsFixtures.emptyVoice)))
    }

    /**
     * The packed region starting two bytes into the payload is not derivable from the format's
     * other fields, so it gets a test that fails if someone "simplifies" it back to zero.
     */
    @Test
    fun `unpacking from the payload start instead of offset 2 yields garbage`() {
        val payload = MotifXsSysEx.dumpPayload(MotifXsFixtures.namedVoice)

        // Misaligned unpacking still leaves readable fragments - the bytes do contain "Wolf"
        // somewhere - so asserting that no letters survive would pass or fail on the window
        // chosen rather than on the offset. The property that matters is that reading a name at
        // the wrong offset does not produce the right name.
        val misread = readNameAt(MotifXsVoice.unpack(payload))
        val correct = MotifXsVoice.nameOf(payload)
        assertEquals("TWE2 Wolf Walk", correct)
        assertNotEquals(correct, misread)
    }

    /** [MotifXsVoice.nameOf]'s scan, applied to an already-unpacked stream, so a test can ask
     * what a *differently* unpacked stream would have named the voice. */
    private fun readNameAt(dense: ByteArray): String? {
        val start = MotifXsVoice.nameStart(dense) ?: return null
        val text = StringBuilder()
        var i = start
        while (i < dense.size && i < start + MotifXsVoice.NAME_SCAN_LIMIT) {
            val c = dense[i].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) break
            text.append(c.toChar())
            i++
        }
        return text.toString().trim().ifEmpty { null }
    }

    /** MSB packing, checked on a group whose high bits are known: 7 bytes per 8, high bit set by
     * the leading byte's bit j. */
    @Test
    fun `unpack lifts each group's high bits out of its leading byte`() {
        val packed = byteArrayOf(0b0000_0101, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val dense = MotifXsVoice.unpack(packed)
        assertEquals(7, dense.size)
        assertEquals(0x81.toByte(), dense[0]) // bit 0 set
        assertEquals(0x02.toByte(), dense[1])
        assertEquals(0x83.toByte(), dense[2]) // bit 2 set
        assertEquals(0x04.toByte(), dense[3])
    }

    /** A truncated group must not run off the end. This shape does not occur in a well-formed
     * dump, but a dropped USB frame produces exactly this and would otherwise index past the
     * array. */
    @Test
    fun `unpack tolerates a group cut short`() {
        val dense = MotifXsVoice.unpack(byteArrayOf(0b0000_0010, 0x41, 0x42))
        assertEquals(2, dense.size)
        assertEquals(0x41.toByte(), dense[0])
        assertEquals(0xC2.toByte(), dense[1])
    }

    @Test
    fun `a damaged dump is rejected rather than decoded`() {
        val corrupted = MotifXsFixtures.namedVoice.copyOf()
        corrupted[500] = (corrupted[500] + 1).toByte() // one flipped data byte
        assertFalse(MotifXsSysEx.isWellFormedBulkDump(corrupted))

        val truncated = MotifXsFixtures.namedVoice.copyOf(900)
        assertFalse(MotifXsSysEx.isWellFormedBulkDump(truncated))
    }

    @Test
    fun `a dump request is the nine bytes the editor sent`() {
        val request = MotifXsSysEx.requestDump(device = 0, addressHi = 0x0C, addressMid = 0x0A, addressLo = 0x0F)
        assertEquals(
            "f043207f030c0a0ff7",
            request.joinToString("") { "%02x".format(it) },
        )
    }

    /**
     * A request and a dump keep their address at different offsets, because only the dump carries
     * a byte count first. Reading a request at the dump's offset yields null rather than an
     * address, which would silently break any correlation built on comparing the two.
     */
    @Test
    fun `a request and its dump report the same address despite different layouts`() {
        val request = MotifXsSysEx.requestDump(device = 0, addressHi = 0x0C, addressMid = 0x0A, addressLo = 0x0F)
        assertEquals(Triple(0x0C, 0x0A, 0x0F), MotifXsSysEx.addressOf(request))
        assertEquals(MotifXsSysEx.addressOf(MotifXsFixtures.namedVoice), MotifXsSysEx.addressOf(request))
        assertEquals(MotifXsSysEx.TYPE_DUMP_REQUEST, MotifXsSysEx.typeOf(request))
    }

    /**
     * Addressed to device `00`, not the `7F` broadcast. `00` is what the editor sends and what
     * this instrument answers; a broadcast inquiry is untested, and an unanswered identity
     * request does not mis-identify the instrument - it means the app never finds it.
     */
    @Test
    fun `the identity request is addressed the way the instrument was seen to answer`() {
        assertEquals(
            "f07e000601f7",
            MotifXsSysEx.identityRequest().joinToString("") { "%02x".format(it) },
        )
    }
}
