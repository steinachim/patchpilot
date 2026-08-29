package de.thewolfwalkexperience.software.patchpilot.midi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framer is pure Kotlin with no Android in it, which is the point: these are byte-stream
 * reassembly bugs that are otherwise hard to isolate from timing-dependent hardware behavior.
 */
class SysExFramerTest {

    private val message = byteArrayOf(0xF0.toByte(), 0x00, 0x20, 0x32, 0x11, 0x22, 0xF7.toByte())

    @Test
    fun `a whole message in one chunk comes straight back out`() {
        val framed = SysExFramer().feed(message)
        assertEquals(1, framed.size)
        assertArrayEquals(message, framed.single())
    }

    /**
     * The load-bearing one: Android's MIDI callback hands over whatever has arrived, so a message
     * can split anywhere - including between F0 and the manufacturer id.
     */
    @Test
    fun `a message split at every possible boundary still reassembles`() {
        for (cut in 1 until message.size) {
            val framer = SysExFramer()
            val first = framer.feed(message.copyOfRange(0, cut))
            val second = framer.feed(message.copyOfRange(cut, message.size))
            assertTrue("cut at $cut delivered early", first.isEmpty())
            assertEquals("cut at $cut", 1, second.size)
            assertArrayEquals("cut at $cut", message, second.single())
        }
    }

    @Test
    fun `one chunk carrying several messages yields all of them`() {
        val framed = SysExFramer().feed(message + message + message)
        assertEquals(3, framed.size)
        framed.forEach { assertArrayEquals(message, it) }
    }

    /**
     * Realtime bytes are legal *inside* a SysEx message and are not part of it. Clock (0xF8) in
     * particular arrives constantly if anything on the bus is sending it, so treating one as a
     * terminator would corrupt every dump taken while a DAW was running.
     */
    @Test
    fun `interleaved realtime bytes are stripped, not treated as terminators`() {
        val framer = SysExFramer()
        val withClock = byteArrayOf(
            0xF0.toByte(), 0x00, 0xF8.toByte(), 0x20, 0x32, 0xFE.toByte(), 0x11, 0x22, 0xF7.toByte(),
        )
        val framed = framer.feed(withClock)
        assertEquals(1, framed.size)
        assertArrayEquals(message, framed.single())
    }

    @Test
    fun `a non-realtime status byte mid-message aborts it rather than delivering a fragment`() {
        val framer = SysExFramer()
        // 0x90 = note on: a device that never sent its F7, followed by real traffic.
        val truncated = byteArrayOf(0xF0.toByte(), 0x00, 0x20, 0x90.toByte(), 0x40, 0x7F)
        assertTrue(framer.feed(truncated).isEmpty())
        // The framer resyncs, so the next whole message still arrives.
        assertArrayEquals(message, framer.feed(message).single())
    }

    @Test
    fun `a second F0 abandons the unterminated first message`() {
        val framer = SysExFramer()
        assertTrue(framer.feed(byteArrayOf(0xF0.toByte(), 0x01, 0x02)).isEmpty())
        assertArrayEquals(message, framer.feed(message).single())
    }

    /**
     * Without a bound, a device that never terminates a message grows the buffer without limit -
     * one malfunctioning peripheral away from an OOM.
     */
    @Test
    fun `an over-long message is dropped and the framer resyncs`() {
        val framer = SysExFramer(maxMessageBytes = 16)
        val flood = byteArrayOf(0xF0.toByte()) + ByteArray(64) { 0x01 }
        assertTrue(framer.feed(flood).isEmpty())
        assertEquals(1, framer.droppedMessages)
        assertArrayEquals(message, framer.feed(message).single())
    }

    @Test
    fun `bytes outside a message are ignored`() {
        val framer = SysExFramer()
        // Channel-voice traffic arriving between messages.
        assertTrue(framer.feed(byteArrayOf(0x90.toByte(), 0x40, 0x7F, 0xB0.toByte(), 0x07, 0x64)).isEmpty())
        assertArrayEquals(message, framer.feed(message).single())
    }

    @Test
    fun `reset discards a partial message so a stale reply cannot complete the next one`() {
        val framer = SysExFramer()
        framer.feed(byteArrayOf(0xF0.toByte(), 0x00, 0x20))
        framer.reset()
        // Without the reset these trailing bytes would complete the abandoned message.
        assertTrue(framer.feed(byteArrayOf(0x32, 0x11, 0x22, 0xF7.toByte())).isEmpty())
    }
}
