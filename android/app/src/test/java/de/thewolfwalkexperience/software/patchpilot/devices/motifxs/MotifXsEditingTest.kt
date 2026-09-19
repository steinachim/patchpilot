// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection and the bulk-dump writer, pinned to the vendor editor's own wire format.
 *
 * Every expected string here is representative instrument data, not a value the code under test
 * could have produced independently, so a passing assertion means the byte layout actually
 * matches what the instrument speaks.
 */
class MotifXsEditingTest {

    private fun hex(s: String) = s.split(" ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    /** Selecting USER 1 - E:05. */
    @Test
    fun `a selection is the three sets the editor sent`() {
        val sets = MotifXsSysEx.selectVoice(device = 0, bankSelectLsb = 0x08, program = 0x44)
        assertEquals(3, sets.size)
        assertArrayEquals(hex("f0 43 40 7f 03 65 00 00 3f f7"), sets[0])
        assertArrayEquals(hex("f0 43 40 7f 03 65 00 01 08 f7"), sets[1])
        assertArrayEquals(hex("f0 43 40 7f 03 65 00 02 44 f7"), sets[2])
    }

    /**
     * The program byte is the flat zero-based slot, so the panel's group and position fall out
     * of it. All four rows match what the instrument's own display shows for these slots.
     */
    @Test
    fun `the program byte carries the panel's group and position`() {
        val cases = listOf(
            Triple(3, 'A', 4),      // PRE1 A:04, Glasgow
            Triple(36, 'C', 5),     // USER 2 C:05, TWE2 EAIT
            Triple(22, 'B', 7),     // USER DR B:07, Jazz Kit
            Triple(125, 'H', 14),   // USER 3 H:14
        )
        for ((program, group, position) in cases) {
            assertEquals(group, 'A' + (program / 16))
            assertEquals(position, program % 16 + 1)
            assertEquals(
                program.toByte(),
                MotifXsSysEx.selectVoice(0, 0x08, program)[2][8],
            )
        }
    }

    /** A program outside 0..127 has no representation in one 7-bit byte. */
    @Test
    fun `an out-of-range program is refused rather than truncated`() {
        for (bad in listOf(-1, 128, 999)) {
            var threw = false
            try {
                MotifXsSysEx.selectVoice(0, 0x08, bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("program $bad should be refused", threw)
        }
    }

    /** The device's echo of a selection, which is the only confirmation one gets. */
    @Test
    fun `the selection echo is recognised and a dump is not`() {
        assertTrue(MotifXsSysEx.isSelectEcho(hex("f0 43 10 7f 0b 65 00 02 44 f7")))
        // The poll's reply shares the type but not the address.
        assertFalse(MotifXsSysEx.isSelectEcho(hex("f0 43 10 7f 0b 70 00 06 f7")))
        assertFalse(MotifXsSysEx.isSelectEcho(MotifXsFixtures.namedVoice))
    }

    /**
     * The acknowledgement says the device answered, **not** that the write worked.
     *
     * Every acknowledgement carries the same `0x02` payload, which carries no information of its
     * own about success - that is why the editor verifies by reading back as well.
     */
    @Test
    fun `the bulk acknowledgement is recognised`() {
        assertTrue(MotifXsSysEx.isAck(hex("f0 43 60 02 f7")))
        assertFalse(MotifXsSysEx.isAck(hex("f0 43 10 7f 0b 65 00 02 44 f7")))
        assertFalse(MotifXsSysEx.isAck(MotifXsFixtures.drumVoice))
    }

    /**
     * The writer must reproduce a dump the instrument sent, byte for byte apart from the one
     * byte that names the speaker.
     *
     * This is the strongest available check on a message class nothing has safely sent yet: take
     * a real voice off the instrument, strip it to its payload, rebuild the dump around it, and
     * require the result to match - byte count, address, checksum and all.
     *
     * The exception is offset 4, the model-low byte, which is `0x0B` on everything the instrument
     * says and `0x03` on everything the host says. A host rebuilding a device's dump *must*
     * differ there, and the checksum differs with it. Every write the host sends carries `0x03`,
     * which is what this builder emits.
     */
    @Test
    fun `a rebuilt bulk dump matches the instrument's, with the host's model byte`() {
        for (fixture in listOf(
            MotifXsFixtures.namedVoice,      // 1.9 kB user voice
            MotifXsFixtures.emptyVoice,      // the 1,903-byte initialised voice a move writes
            MotifXsFixtures.drumVoice,       // 12.6 kB, the size that stresses the count field
        )) {
            val address = MotifXsSysEx.addressOf(fixture)!!
            val rebuilt = MotifXsSysEx.bulkDump(
                device = 0,
                addressHi = address.first,
                addressMid = address.second,
                addressLo = address.third,
                payload = MotifXsSysEx.dumpPayload(fixture),
            )
            val expected = fixture.copyOf()
            expected[4] = MotifXsSysEx.MODEL_HOST.toByte()
            expected[expected.size - 2] = MotifXsSysEx.checksumOf(expected).toByte()

            assertArrayEquals(expected, rebuilt)
            assertTrue(MotifXsSysEx.isWellFormedBulkDump(rebuilt))
            // The declared count is the payload length, not the payload plus its address.
            assertEquals(MotifXsSysEx.dumpPayload(fixture).size, MotifXsSysEx.declaredCount(rebuilt))
            assertEquals(fixture.size, rebuilt.size)
        }
    }

    /** The store marker a write is followed by: a bulk dump with no payload at all. */
    @Test
    fun `a zero-payload dump is well formed`() {
        val marker = MotifXsSysEx.bulkDump(0, 0x11, 0x00, 0x00, ByteArray(0))
        assertArrayEquals(hex("f0 43 00 7f 03 00 00 11 00 00 6f f7"), marker)
        assertEquals(MotifXsSysEx.BULK_OVERHEAD, marker.size)
        assertTrue(MotifXsSysEx.isWellFormedBulkDump(marker))
    }
}
