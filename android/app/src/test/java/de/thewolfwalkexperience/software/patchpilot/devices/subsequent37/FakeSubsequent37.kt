// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import de.thewolfwalkexperience.software.patchpilot.midi.SysExFramer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * A scripted Subsequent 37: the library of [Subsequent37Fixtures.BULK_DUMP], answering the
 * requests the app sends the way the instrument does.
 *
 * **Programs are kept packed**, as the wire carries them: a write stores the 732 bytes of its
 * `05 01`, a read and the bulk dump send them back inside headers taken from the captured
 * replies. So the fake never encodes or decodes a program with the code under test.
 *
 * The bulk dump is sent from [scope] over virtual time, at the instrument's 1,435 bytes per
 * second, in pieces as `MidiManager` is expected to deliver it, or in one piece at the end
 * ([dumpWhole]).
 */
class FakeSubsequent37(private val scope: CoroutineScope) {

    val transport = FakeMidiTransport(bufferCapacity = 8192) { request -> respond(request) }

    /** Every request the app sent, in order. */
    val requests: List<ByteArray> get() = transport.sent

    /** When each request was sent, in the test's virtual milliseconds. */
    val requestTimes = mutableListOf<Long>()

    val packed: Array<ByteArray> = Array(256) { Subsequent37Fixtures.packedBlock(it) }

    // ---- Knobs ----

    var identityReply: ByteArray? = Subsequent37Fixtures.IDENTITY_REPLY
    var settingsReply: ByteArray? = Subsequent37Fixtures.SETTINGS_IN01_OUT16

    /** The channel (0..15) Program Changes are taken on, or null for any; off ignores them all. */
    var programChangeChannel: Int? = 0
    var receivesProgramChanges = true

    /** Sends the bulk dump in one piece once it is whole. */
    var dumpWhole = false

    /** Never answers the bulk dump request. */
    var dumpSilent = false

    /** Changes the dump's bytes on their way out (a loss, a flipped bit). */
    var damageDump: (ByteArray) -> ByteArray = { it }

    /** Bytes inserted into the dump stream once this many of the dump's bytes have gone out. */
    var injectIntoDumpAt: Pair<Int, ByteArray>? = null

    /** How many bytes go out per piece, one piece a second. */
    var dumpPieceBytes = 1_435

    /** Writes to these programs are not stored. */
    var ignoreWritesTo: Set<Int> = emptySet()

    /** How many reads after each write still show the program as it was before. */
    var staleReadsAfterWrite = 0

    /** The next this-many program reads go unanswered. */
    var unansweredReads = 0

    /** Virtual time the last dump piece went out, if one did. */
    var dumpEndedAt: Long? = null
        private set

    var selected: Int? = null
        private set

    val writes = mutableListOf<ByteArray>()

    private val before = HashMap<Int, ByteArray>()
    private var staleLeft = HashMap<Int, Int>()

    /** Virtual time; set by [subsequent37] so the fake can stamp requests. */
    internal var now: () -> Long = { 0L }

    fun programAt(number: Int): Subsequent37Program =
        Subsequent37Program(Subsequent37SysEx.unpack(packed[number], 0, 244)!!)

    /** The bulk dump this fake would send now, before [damageDump]. */
    fun bulkDump(): ByteArray {
        val original = Subsequent37Fixtures.BULK_DUMP
        val out = original.copyOf()
        for (n in 0 until 256) packed[n].copyInto(out, 23 + n * 796)
        return out
    }

    // ---- Wire ----

    private fun respond(request: ByteArray): List<ByteArray> {
        requestTimes += now()
        if (request.contentEquals(Subsequent37SysEx.IDENTITY_REQUEST)) return listOfNotNull(identityReply)
        val status = request[0].toInt() and 0xFF
        if (status in 0xB0..0xBF) return select(request)
        if (status != 0xF0 || request[1].toInt() != 0x04 || request[2].toInt() != 0x0C) return emptyList()
        val command = (request[3].toInt() and 0xFF) to (request[4].toInt() and 0xFF)
        return when {
            command.first == 0x32 -> listOf(byteArrayOf(0xF0.toByte(), 0x04, 0x0C, 0x32, 0x7F, 0x7F, 0xF7.toByte()))

            command == (0x06 to 0x04) -> listOfNotNull(settingsReply)

            command == (0x06 to 0x00) -> emptyList()

            command == (0x06 to 0x01) -> read(((request[5].toInt() and 0x7F) shl 7) or (request[6].toInt() and 0x7F))

            command == (0x06 to 0x03) -> {
                startDump()
                emptyList()
            }

            command == (0x05 to 0x01) -> {
                write(request)
                emptyList()
            }

            else -> emptyList()
        }
    }

    /** `B<ch> 00 00 B<ch> 20 <cc32> C<ch> <pc>`, acknowledged `36 <cc32> <pc>` where it is taken. */
    private fun select(request: ByteArray): List<ByteArray> {
        val channel = request[0].toInt() and 0x0F
        val cc32 = request[5].toInt()
        val pc = request[7].toInt()
        if (!receivesProgramChanges) return emptyList()
        if (programChangeChannel != null && programChangeChannel != channel) return emptyList()
        selected = cc32 * 128 + pc
        return listOf(byteArrayOf(0xF0.toByte(), 0x04, 0x0C, 0x36, cc32.toByte(), pc.toByte(), 0xF7.toByte()))
    }

    /** The selected program's buffer, then the stored program asked for, as the instrument answers. */
    private fun read(number: Int): List<ByteArray> {
        if (unansweredReads > 0) {
            unansweredReads--
            return emptyList()
        }
        val stale = staleLeft[number] ?: 0
        val stored = if (stale > 0) {
            staleLeft[number] = stale - 1
            before.getValue(number)
        } else {
            packed[number]
        }
        val bufferHeader = Subsequent37Fixtures.READ_P001[0].copyOfRange(0, 23)
        val storedHeader = Subsequent37Fixtures.READ_P001[1].copyOfRange(0, 23).also {
            it[5] = (number shr 7).toByte()
            it[6] = (number and 0x7F).toByte()
        }
        val end = byteArrayOf(0xF7.toByte())
        return listOf(
            bufferHeader + packed[selected ?: 0] + end,
            storedHeader + stored + end,
        )
    }

    private fun write(message: ByteArray) {
        writes += message
        val number = ((message[5].toInt() and 0x7F) shl 7) or (message[6].toInt() and 0x7F)
        if (number in ignoreWritesTo) return
        before[number] = packed[number]
        staleLeft[number] = staleReadsAfterWrite
        packed[number] = message.copyOfRange(23, 23 + 732)
    }

    private fun startDump() {
        if (dumpSilent) return
        val dump = damageDump(bulkDump())
        scope.launch {
            if (dumpWhole) {
                delay(seconds(dump.size))
                transport.inject(dump)
                dumpEndedAt = now()
                return@launch
            }
            var sent = 0
            val inject = injectIntoDumpAt
            while (sent < dump.size) {
                delay(1.seconds)
                val end = minOf(sent + dumpPieceBytes, dump.size)
                if (inject != null && inject.first in sent until end) {
                    transport.inject(dump.copyOfRange(sent, inject.first))
                    transport.inject(inject.second)
                    transport.inject(dump.copyOfRange(inject.first, end))
                } else {
                    transport.inject(dump.copyOfRange(sent, end))
                }
                sent = end
            }
            dumpEndedAt = now()
        }
    }

    private fun seconds(bytes: Int): Duration = (bytes / 1_435.0).seconds
}

/** An instrument on [fake], connected to nothing yet; virtual time throughout. */
internal fun TestScope.subsequent37(
    fake: FakeSubsequent37,
    config: Subsequent37Config = Subsequent37Config(),
): Subsequent37Instrument {
    fake.now = { testScheduler.currentTime }
    return Subsequent37Instrument(
        SysExExchange(fake.transport, backgroundScope, framer = SysExFramer(256 * 1024)),
        config,
        Subsequent37Program(Subsequent37Fixtures.BLANK),
        timeSource = testScheduler.timeSource,
    )
}
