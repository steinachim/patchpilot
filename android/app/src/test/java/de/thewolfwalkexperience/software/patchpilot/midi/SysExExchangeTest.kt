package de.thewolfwalkexperience.software.patchpilot.midi

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.devices.pro800.Pro800SysEx
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class SysExExchangeTest {

    private fun dump(programNumber: Int, payload: ByteArray = byteArrayOf(0x00, 0x01)): ByteArray =
        Pro800SysEx.HEADER +
            byteArrayOf(Pro800SysEx.TYPE_DUMP.toByte()) +
            byteArrayOf((programNumber and 0x7F).toByte(), ((programNumber shr 7) and 0x7F).toByte()) +
            payload +
            Pro800SysEx.SYSEX_END

    @Test
    fun `an exchange returns the first matching reply`() = runTest {
        val transport = FakeMidiTransport { listOf(dump(7)) }
        val exchange = SysExExchange(transport, backgroundScope)

        val reply = exchange.exchange(Pro800SysEx.requestDump(7), "reading 7") {
            Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DUMP
        }
        assertEquals(7, Pro800SysEx.addressOf(reply))
        assertArrayEquals(Pro800SysEx.requestDump(7), transport.sent.single())
    }

    /** A reply delivered in pieces is exactly what a real port does; the framer underneath has to
     * make that invisible here. */
    @Test
    fun `a reply split across chunks still satisfies an exchange`() = runTest {
        val whole = dump(3)
        val transport = FakeMidiTransport { listOf(whole.copyOfRange(0, 4), whole.copyOfRange(4, whole.size)) }
        val exchange = SysExExchange(transport, backgroundScope)

        val reply = exchange.exchange(Pro800SysEx.requestDump(3), "reading 3") {
            Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DUMP
        }
        assertEquals(3, Pro800SysEx.addressOf(reply))
    }

    /**
     * The reason the matcher is a caller-supplied predicate rather than "same message type":
     * a stale reply to an earlier request is still a dump, and returning it would hand back the
     * wrong preset - data loss once this feeds a write path.
     */
    @Test
    fun `a reply for the wrong address does not satisfy the matcher`() = runTest {
        val transport = FakeMidiTransport { request ->
            // Answers with a stale dump for slot 1 first, then the one actually asked for.
            val asked = Pro800SysEx.addressOf(request.copyOf())
                ?: (request[9].toInt() or (request[10].toInt() shl 7))
            listOf(dump(1), dump(asked))
        }
        val exchange = SysExExchange(transport, backgroundScope)

        val reply = exchange.exchange(Pro800SysEx.requestDump(42), "reading 42") {
            Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DUMP && Pro800SysEx.addressOf(it) == 42
        }
        assertEquals(42, Pro800SysEx.addressOf(reply))
    }

    @Test
    fun `foreign SysEx from another device on the bus is ignored`() = runTest {
        val foreign = byteArrayOf(0xF0.toByte(), 0x43, 0x10, 0x4C, 0x00, 0xF7.toByte()) // Yamaha
        val transport = FakeMidiTransport { listOf(foreign, dump(9)) }
        val exchange = SysExExchange(transport, backgroundScope)

        val reply = exchange.exchange(Pro800SysEx.requestDump(9), "reading 9") {
            Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DUMP
        }
        assertEquals(9, Pro800SysEx.addressOf(reply))
    }

    @Test
    fun `a silent instrument times out with a message naming what was being done`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val exchange = SysExExchange(transport, backgroundScope, defaultTimeout = 20.milliseconds, retries = 0)

        // Caught rather than assertThrows: a runBlocking inside runTest would deadlock, since
        // the virtual-time scheduler needs the very thread runBlocking parks.
        val thrown = try {
            exchange.exchange(Pro800SysEx.requestDump(0), "reading preset A00") { true }
            null
        } catch (e: InstrumentException.Timeout) {
            e
        }
        assertTrue(thrown!!.message!!.contains("reading preset A00"))
    }

    /** On a stream transport a single dropped message is normal; failing a 400-slot scan on the
     * first hiccup would make the feature unusable. */
    @Test
    fun `a dropped reply is retried`() = runTest {
        var attempt = 0
        val transport = FakeMidiTransport {
            attempt++
            if (attempt == 1) emptyList() else listOf(dump(5))
        }
        val exchange = SysExExchange(transport, backgroundScope, defaultTimeout = 50.milliseconds, retries = 1)

        val reply = exchange.exchange(Pro800SysEx.requestDump(5), "reading 5") {
            Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DUMP
        }
        assertEquals(5, Pro800SysEx.addressOf(reply))
        assertEquals(2, transport.sent.size)
    }

    /**
     * A write is fire-and-forget - the proof it landed is reading the address back, not a status
     * byte - so it needs a path that does not wait for a reply, but *does* take the same lock.
     */
    @Test
    fun `tell sends without waiting for anything`() = runTest {
        val transport = FakeMidiTransport { emptyList() }
        val exchange = SysExExchange(transport, backgroundScope)

        val first = Pro800SysEx.writeDump(2, byteArrayOf(0x00, 0x01))
        val second = Pro800SysEx.writeDump(37, byteArrayOf(0x00, 0x02))
        exchange.tell(first)
        exchange.tell(second)

        assertEquals(2, transport.sent.size)
        assertArrayEquals(first, transport.sent[0])
        assertArrayEquals(second, transport.sent[1])
    }
}
