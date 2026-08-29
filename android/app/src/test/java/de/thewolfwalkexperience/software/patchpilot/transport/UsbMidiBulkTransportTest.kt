package de.thewolfwalkexperience.software.patchpilot.transport

import de.thewolfwalkexperience.software.patchpilot.devices.motifxs.MotifXsFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USB-MIDI event-packet codec, which is what stands between this app and a Motif XS.
 *
 * Every expected byte string here matches the packing a real instrument accepts and answers to -
 * the same standard the two `SysExFramer` implementations are held to. Packing and unpacking have
 * to agree exactly with what the instrument actually expects on the wire.
 */
class UsbMidiBulkTransportTest {

    private fun hex(s: String) = s.split(" ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    /**
     * The Universal Device Inquiry, packed on cable 3 - the exact bytes a Motif XS answers when
     * written to the bulk OUT endpoint.
     *
     * Note the two packet headers: `34` is cable 3 CIN 4 ("SysEx continues", three bytes) and `37`
     * is cable 3 CIN 7 ("ends with three"). A packer that used CIN 4 throughout would produce a
     * stream that never terminates, which is the failure this vector exists to catch.
     */
    @Test
    fun `an inquiry packs into the bytes the instrument answered`() {
        val request = hex("f0 7e 00 06 01 f7")
        assertArrayEquals(
            hex("34 f0 7e 00 37 06 01 f7"),
            UsbMidiBulkTransport.packSysEx(request, cable = 3),
        )
    }

    /** A message whose length is not a multiple of three ends on CIN 5 or 6 instead. */
    @Test
    fun `the final packet's code index states how many bytes it carries`() {
        // 15 bytes: four full packets of three, then three left over -> CIN 7.
        val reply = hex("f0 7e 7f 06 02 43 00 41 35 06 06 00 00 7f f7")
        assertArrayEquals(
            hex("34 f0 7e 7f 34 06 02 43 34 00 41 35 34 06 06 00 37 00 7f f7"),
            UsbMidiBulkTransport.packSysEx(reply, cable = 3),
        )
        // 7 bytes: two full packets, one left over -> CIN 5, and the packet is zero-padded.
        assertArrayEquals(
            hex("34 f0 43 20 34 7f 03 0c 35 f7 00 00"),
            UsbMidiBulkTransport.packSysEx(hex("f0 43 20 7f 03 0c f7"), cable = 3),
        )
        // 8 bytes: two full packets, two left over -> CIN 6.
        assertArrayEquals(
            hex("34 f0 43 20 34 7f 03 0c 36 0a f7 00"),
            UsbMidiBulkTransport.packSysEx(hex("f0 43 20 7f 03 0c 0a f7"), cable = 3),
        )
    }

    /** Round-tripping a real 12.6 kB drum dump, which is the size that stresses everything. */
    @Test
    fun `a drum voice survives packing and unpacking`() {
        val dump = MotifXsFixtures.drumVoice
        val packed = UsbMidiBulkTransport.packSysEx(dump, cable = 3)
        assertEquals(0, packed.size % 4)
        assertArrayEquals(dump, UsbMidiBulkTransport.unpackEvents(packed, cable = 3))
    }

    /**
     * The cable filter, which is not an optimisation.
     *
     * A Motif XS sends Active Sensing on cable 0 while the editor's SysEx runs on cable 3 - 590
     * of them during a single dump. Accepting both cables would splice `FE` into the middle of a
     * dump: the same class of error as merging the two USB endpoints.
     */
    @Test
    fun `another cable's traffic is dropped rather than spliced in`() {
        val mixed = hex(
            "34 f0 7e 7f 34 06 02 43 0f fe 00 00 34 00 41 35 34 06 06 00 37 00 7f f7",
        ) + ByteArray(40) // zero padding to the 64-byte transfer
        assertArrayEquals(
            hex("f0 7e 7f 06 02 43 00 41 35 06 06 00 00 7f f7"),
            UsbMidiBulkTransport.unpackEvents(mixed, cable = 3),
        )
        assertArrayEquals(hex("fe"), UsbMidiBulkTransport.unpackEvents(mixed, cable = 0))
        assertEquals(0, UsbMidiBulkTransport.unpackEvents(mixed, cable = 1).size)
    }

    /** Zero padding is not a packet, and a reserved CIN is not three bytes of MIDI. */
    @Test
    fun `padding and reserved code index numbers contribute nothing`() {
        assertEquals(0, UsbMidiBulkTransport.unpackEvents(ByteArray(64), cable = 0).size)
        assertEquals(0, UsbMidiBulkTransport.unpackEvents(hex("30 01 02 03"), cable = 3).size)
        assertEquals(0, UsbMidiBulkTransport.unpackEvents(hex("31 01 02 03"), cable = 3).size)
    }

    /** A trailing part-packet is ignored rather than read past the end of the buffer. */
    @Test
    fun `a truncated transfer does not run off the end`() {
        assertArrayEquals(
            hex("f0 7e 00"),
            UsbMidiBulkTransport.unpackEvents(hex("34 f0 7e 00 37 06"), cable = 3),
        )
    }

    /** A fragment has no correct packing, so it is refused rather than packed wrongly. */
    @Test
    fun `sending anything but a complete message is refused`() = withTransport(FakeBulk()) { transport, bulk ->
        assertThrows(IllegalArgumentException::class.java) {
            transport.send(hex("f0 7e 00 06 01"))
        }
        assertTrue(bulk.written.isEmpty())
    }

    /**
     * End to end over a fake endpoint pair: what goes out is packed, what comes back is framed.
     *
     * On a **real** dispatcher rather than `runTest`'s, deliberately. The reader is a polling loop
     * whose only suspension point is the idle `yield`, and `tryEmit` into a buffered flow never
     * suspends - so under a single-threaded test scheduler the loop can run without ever letting
     * the collector resume. That is a property of this design, not of the test, and the first
     * version of this file hung on it. A real dispatcher and a wall-clock timeout test what the
     * phone will actually do.
     */
    @Test
    fun `a reply on the right cable reaches the incoming flow`() {
        val reply = hex("f0 7e 7f 06 02 43 00 41 35 06 06 00 00 7f f7")
        // Served on every read, so the collector cannot miss it by subscribing a moment late.
        val bulk = FakeBulk(always = UsbMidiBulkTransport.packSysEx(reply, cable = 3) + ByteArray(20))
        withTransport(bulk) { transport, _ ->
            transport.send(hex("f0 7e 00 06 01 f7"))
            assertArrayEquals(hex("34 f0 7e 00 37 06 01 f7"), bulk.written.single())
            runBlocking {
                assertArrayEquals(reply, withTimeout(5_000) { transport.incoming.first() })
            }
        }
    }

    /** Builds a transport on a real dispatcher and tears it (and its reader) down afterwards. */
    private fun withTransport(bulk: FakeBulk, body: (UsbMidiBulkTransport, FakeBulk) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            body(UsbMidiBulkTransport(bulk, cable = 3, scope = scope), bulk)
        } finally {
            scope.cancel()
        }
    }

    private class FakeBulk(private val always: ByteArray? = null) : UsbBulkTransport {
        val written = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        override val rebuildOnResume = true
        override fun bulkWrite(data: ByteArray) { written += data }
        override fun bulkRead(bufferSize: Int): ByteArray = always ?: ByteArray(0)
        override fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int, length: Int,
        ): ByteArray = ByteArray(length)
        override fun close() {}
    }
}
