package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Nord adapter turns protocol failures into [InstrumentException]s.
 *
 * **This family was the last one throwing raw `IllegalStateException`s.** Pro-800 and Motif XS
 * adopted the hierarchy when they were written; Nord predates it, so the type whose own doc says
 * the UI must not match on message strings did not cover the family most users have. These tests
 * pin the translation, which is behaviour no other test exercises: the protocol tests drive
 * `NordDevice` directly and never go through the adapter.
 */
class NordFailureMappingTest {

    // Same two builders NordDeviceProtocolTest uses; private there, so restated rather than
    // widened - these tests are about the adapter, not about that class's fixtures.
    private fun uint32BE(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun rootList(trailerLen: Int = 29): ByteArray {
        val names = listOf("Piano", "Program", "Settings")
        val out = mutableListOf<Byte>()
        repeat(4) { out += 0.toByte() }
        out += names.size.toByte()
        for (name in names) {
            val nameBytes = name.toByteArray(Charsets.US_ASCII)
            out += uint32BE(nameBytes.size).toList()
            out += nameBytes.toList()
            out += List(trailerLen) { 0.toByte() }
        }
        return out.toByteArray()
    }

    private fun instrument(responses: List<Pair<Int, ByteArray>>) =
        NordInstrument(NordFixtures.device(ReplayTransport(responses), NordFixtures.GRAND_PROFILE))

    /** A non-zero status is the instrument answering and saying no - code carried verbatim. */
    @Test
    fun `a refused delete becomes DeviceRejected carrying the instrument's own status`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootList(),
                5 to ByteArray(0), // SELECT_CATEGORY
                21 to (uint32BE(9) + uint32BE(8) + uint32BE(12)), // status 9
                7 to ByteArray(0), // unlock still runs
            ),
        )
        val failure = assertThrows(InstrumentException.DeviceRejected::class.java) {
            kotlinx.coroutines.runBlocking { instrument.delete(SlotAddress(8, 12)) }
        }
        assertEquals(9, failure.status)
        assertTrue(failure.message!!.contains("delete a preset"))
    }

    /**
     * An echo for a slot other than the one asked for is a refusal too, not a desync: the
     * instrument answered, it just did not do what was asked.
     */
    @Test
    fun `an echo for the wrong slot becomes DeviceRejected`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                21 to (uint32BE(0) + uint32BE(8) + uint32BE(13)), // status 0, wrong item
                7 to ByteArray(0),
            ),
        )
        assertThrows(InstrumentException.DeviceRejected::class.java) {
            kotlinx.coroutines.runBlocking { instrument.delete(SlotAddress(8, 12)) }
        }
    }

    /** Status 4 is the one code the protocol names - "file exists" (§3.3.12). */
    @Test
    fun `copying onto an occupied slot reports status 4`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                23 to uint32BE(NordDevice.STATUS_FILE_EXISTS),
                7 to ByteArray(0),
            ),
        )
        val failure = assertThrows(InstrumentException.DeviceRejected::class.java) {
            kotlinx.coroutines.runBlocking {
                instrument.copyProgram(SlotAddress(0, 0), SlotAddress(0, 1))
            }
        }
        assertEquals(NordDevice.STATUS_FILE_EXISTS, failure.status)
        // The status survives for the log; what the *user* is shown has to be the condition, not
        // the code. "(status 4)" says something went wrong without saying what or what to do.
        assertEquals(
            "Couldn't copy a preset: the instrument says that slot already holds one, though the " +
                "listing shows it as empty. Refresh the listing.",
            failure.message,
        )
    }

    /**
     * The counterpart: a code with no documented meaning must not be given an invented one. The
     * generic wording is honest - the app has the instrument's answer and cannot interpret it.
     */
    @Test
    fun `a status the protocol does not name keeps the generic wording`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                21 to (uint32BE(9) + uint32BE(8) + uint32BE(12)),
                7 to ByteArray(0),
            ),
        )
        val failure = assertThrows(InstrumentException.DeviceRejected::class.java) {
            kotlinx.coroutines.runBlocking { instrument.delete(SlotAddress(8, 12)) }
        }
        assertNull(failure.explanation)
        assertTrue(failure.message!!.contains("status 9"))
    }

    /**
     * A reply this app cannot parse is a desync, not a refusal - the distinction the screens act
     * on, since one may be worth retrying and the other is a bug or an unprofiled device.
     */
    @Test
    fun `an unparseable reply becomes ProtocolDesync`() = runTest {
        val instrument = instrument(
            listOf(
                1 to rootList(),
                5 to ByteArray(0),
                21 to uint32BE(0), // too short for the (status, bank, item) triple
                7 to ByteArray(0),
            ),
        )
        assertThrows(InstrumentException.ProtocolDesync::class.java) {
            kotlinx.coroutines.runBlocking { instrument.delete(SlotAddress(8, 12)) }
        }
    }
}
