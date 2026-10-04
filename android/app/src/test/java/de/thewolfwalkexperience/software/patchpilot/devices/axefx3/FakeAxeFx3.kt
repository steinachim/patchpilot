// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import java.nio.ByteBuffer
import kotlinx.coroutines.test.TestScope

/**
 * A scripted Axe-Fx III with the behaviour the app depends on: stored presets, a selection and
 * an edit buffer, a rename and a dump write that change only the edit buffer, and a store that
 * writes it to a slot.
 *
 * **Names without the format.** Where a preset's name lives inside its dump is not known, so the
 * fake keeps a registry from each image's raw bytes to its name. It accepts a dump write only of
 * an image it knows and acknowledges anything else with status `0x13`, storing nothing. That is
 * the real instrument's contract too: it only takes dumps exactly as it sent them, so an app that
 * re-encoded one would fail here as it would on the unit.
 *
 * Every image is derived from the captured never-used dump by changing wire bytes test-side
 * (see [presetImage]), never by the code under test.
 */
class FakeAxeFx3(
    val presetCount: Int = 512,
    bufferCapacity: Int = 4096,
    /** Splits every reply into pieces of this many bytes, as a platform delivering small packets. */
    private val pieceSize: Int? = null,
) {
    class Slot(val name: String?, val image: ByteArray)

    val transport = FakeMidiTransport(bufferCapacity) { request -> respond(request) }

    /** Every request the app sent, in order. */
    val requests: List<ByteArray> get() = transport.sent

    val slots: Array<Slot> = Array(presetCount) { Slot(null, AxeFx3Fixtures.NEVER_USED_IMAGE) }

    var selected = 0
        private set

    private var editBuffer: Slot = slots[0]
    private var receiving: MutableList<ByteArray>? = null
    private val known = HashMap<ByteBuffer, String?>()

    // ---- Knobs ----

    var firmwareReply: ByteArray? = AxeFx3Fixtures.FIRMWARE_REPLY
    var capabilitiesReply: ByteArray? = AxeFx3Fixtures.CAPABILITIES_REPLY_MARK_I

    /** False makes the "which preset is loaded" query echo `7F 7F` rather than a number. */
    var loadedQueryEchoesNumber = true
    var loadedQuerySilent = false

    var beginStatus = 0

    /** The status the end of dump write n (1-based) is acknowledged with; non-zero stores nothing. */
    var endStatusOf: (Int) -> Int = { 0 }
    private var writes = 0

    /** Sees every request before it is answered, e.g. to cancel the sender part way. */
    var onRequest: ((ByteArray) -> Unit)? = null

    /** The status the acknowledgement of chunk k (1-based) carries. */
    var chunkStatus: (Int) -> Int = { 0 }

    /** A chunk (1-based) whose acknowledgement never comes. */
    var silentChunk: Int? = null

    /** Acknowledges stores without performing them. */
    var ignoreStores = false

    /** Performs stores without acknowledging them. */
    var silentStores = false

    enum class ReadFault { BAD_CHECKSUM, MISSING_CHUNK, NO_END }

    var readFault: ReadFault? = null

    /** How many reads [readFault] damages before reads come through whole again. */
    var readFaultCount = 1

    /** An end marker left over from an earlier read, sent ahead of the next read's acknowledgement. */
    var strayBeforeReadAck = false

    init {
        known[key(AxeFx3Fixtures.NEVER_USED_IMAGE)] = null
        known[key(AxeFx3Fixtures.EMPTY_PRESET_IMAGE)] = null
    }

    /** Stores a preset named [name] at [number], built from [seed]; returns its image. */
    fun put(number: Int, name: String, seed: Int = number): ByteArray {
        val image = presetImage(seed)
        known[key(image)] = name
        slots[number] = Slot(name, image)
        return image
    }

    fun nameAt(number: Int): String = slots[number].name ?: AxeFx3SysEx.EMPTY_NAME

    // ---- Wire ----

    private fun respond(request: ByteArray): List<ByteArray> {
        onRequest?.invoke(request)
        val replies = replyTo(request)
        val size = pieceSize ?: return replies
        return replies.flatMap { message -> message.toList().chunked(size).map { it.toByteArray() } }
    }

    private fun replyTo(request: ByteArray): List<ByteArray> {
        if (request.contentEquals(AxeFx3Fixtures.WHO_IS_THERE)) return listOf(AxeFx3Fixtures.WHO_IS_THERE_REPLY)
        val payload = request.copyOfRange(6, request.size - 2).map { it.toInt() and 0xFF }
        return when (request[5].toInt() and 0xFF) {
            AxeFx3SysEx.CMD_FIRMWARE -> listOfNotNull(firmwareReply)

            AxeFx3SysEx.CMD_CAPABILITIES -> listOfNotNull(capabilitiesReply)

            AxeFx3SysEx.CMD_QUERY_NAME -> nameReply(payload[0], payload[1])

            AxeFx3SysEx.CMD_EDITOR -> editor(request, payload)

            AxeFx3SysEx.CMD_DUMP_READ -> dump((payload[0] shl 7) or payload[1])

            AxeFx3SysEx.CMD_DUMP_BEGIN -> {
                receiving = mutableListOf()
                listOf(stepAck(AxeFx3SysEx.CMD_DUMP_BEGIN, beginStatus))
            }

            AxeFx3SysEx.CMD_DUMP_CHUNK -> {
                val chunks = receiving ?: return emptyList()
                chunks += request
                if (silentChunk == chunks.size) {
                    emptyList()
                } else {
                    listOf(stepAck(AxeFx3SysEx.CMD_DUMP_CHUNK, chunkStatus(chunks.size)))
                }
            }

            AxeFx3SysEx.CMD_DUMP_END -> listOf(stepAck(AxeFx3SysEx.CMD_DUMP_END, endOfWrite(request)))

            else -> emptyList()
        }
    }

    private fun nameReply(lo: Int, hi: Int): List<ByteArray> {
        if (lo == 0x7F && hi == 0x7F) {
            if (loadedQuerySilent) return emptyList()
            val echo = if (loadedQueryEchoesNumber) {
                intArrayOf(selected and 0x7F, selected shr 7)
            } else {
                intArrayOf(0x7F, 0x7F)
            }
            return listOf(message(AxeFx3SysEx.CMD_QUERY_NAME, *echo, *nameField(editBuffer.name)))
        }
        val number = lo or (hi shl 7)
        if (number >= presetCount) return emptyList()
        return listOf(message(AxeFx3SysEx.CMD_QUERY_NAME, lo, hi, *nameField(slots[number].name)))
    }

    /** A C string in a fixed buffer, with a stale tail after the NUL as the real one has. */
    private fun nameField(name: String?): IntArray {
        val text = (name ?: AxeFx3SysEx.EMPTY_NAME).toByteArray(Charsets.US_ASCII)
        val field = IntArray(AxeFx3SysEx.NAME_FIELD_LENGTH)
        text.forEachIndexed { i, b -> field[i] = b.toInt() }
        if (text.size + 1 < field.size) {
            "p Wolfwalk".forEachIndexed { i, c ->
                if (text.size + 1 + i < field.size) field[text.size + 1 + i] = c.code
            }
        }
        return field
    }

    private fun editor(request: ByteArray, payload: List<Int>): List<ByteArray> {
        val sub = payload[0]
        val number = payload.getOrElse(6) { 0 } or (payload.getOrElse(7) { 0 } shl 7)
        return when (sub) {
            AxeFx3SysEx.SUB_SELECT -> {
                if (number >= presetCount) return emptyList()
                selected = number
                editBuffer = slots[number]
                listOf(AxeFx3Fixtures.SELECT_ACK)
            }

            AxeFx3SysEx.SUB_STORE -> {
                if (!ignoreStores) slots[number] = stored(editBuffer)
                if (silentStores) emptyList() else listOf(AxeFx3Fixtures.STORE_ACK)
            }

            AxeFx3SysEx.SUB_SET_NAME -> {
                val name = renamedTo(request)
                val image = mutate(editBuffer.image, chunk = 15, at = 1000 + name.length)
                known[key(image)] = name
                editBuffer = Slot(name, image)
                listOf(message(AxeFx3SysEx.CMD_EDITOR, AxeFx3SysEx.SUB_SET_NAME, 0, 0))
            }

            else -> emptyList()
        }
    }

    private fun dump(number: Int): List<ByteArray> {
        if (number >= presetCount) return emptyList()
        val messages = split(slots[number].image).toMutableList()
        val fault = readFault?.takeIf { readFaultCount > 0 }
        if (fault != null) readFaultCount--
        when (fault) {
            ReadFault.BAD_CHECKSUM -> messages[3] =
                messages[3].copyOf().also { it[it.size - 2] = (it[it.size - 2].toInt() xor 0x01).toByte() }

            ReadFault.MISSING_CHUNK -> messages.removeAt(5)

            ReadFault.NO_END -> messages.removeAt(messages.lastIndex)

            null -> Unit
        }
        val ack = message(AxeFx3SysEx.CMD_DUMP_BEGIN, number shr 7, number and 0x7F, 0, 0, 1)
        val stray = if (strayBeforeReadAck) listOf(message(AxeFx3SysEx.CMD_DUMP_END, 1, 2, 3)) else emptyList()
        return stray + listOf(ack) + messages
    }

    private fun endOfWrite(end: ByteArray): Int {
        val chunks = receiving ?: return 0x13
        receiving = null
        val raw = chunks.fold(ByteArray(0)) { acc, m -> acc + m } + end
        val status = endStatusOf(++writes)
        if (status != 0) return status
        if (chunks.size != AxeFx3SysEx.DUMP_CHUNK_COUNT || !known.containsKey(key(raw))) return 0x13
        editBuffer = Slot(known[key(raw)], raw)
        return 0
    }

    /**
     * What a store leaves in the slot: the instrument rewrites a few header bytes of every preset
     * it stores; here one bit of decoded byte 5. The delete image is taken as an erase, so its
     * fill data is not what the slot ends up holding.
     */
    private fun stored(buffer: Slot): Slot {
        if (buffer.image.contentEquals(AxeFx3Fixtures.EMPTY_PRESET_IMAGE)) {
            return Slot(null, AxeFx3Fixtures.NEVER_USED_IMAGE)
        }
        // Wire byte 8 of chunk 0's packed region carries dense bits 56-62: decoded byte 5.
        val image = mutate(buffer.image, chunk = 0, at = 5 + 8)
        known[key(image)] = buffer.name
        return Slot(buffer.name, image)
    }

    // ---- Test-side encoding: changes wire bytes, never re-encodes ----

    companion object {
        /**
         * A distinct, well-formed preset image: the captured never-used dump with one wire byte
         * changed and that message's checksum re-closed, plus non-zero padding bits in chunk 0's
         * last wire byte, which any decode-and-re-encode would lose.
         */
        fun presetImage(seed: Int): ByteArray {
            var image = mutate(
                AxeFx3Fixtures.NEVER_USED_IMAGE,
                chunk = 1 + seed % 14,
                at = 100 + seed / 14 % 2000,
                xor = 1 + seed % 0x3F,
            )
            image = setPaddingBits(image)
            return image
        }

        private fun setPaddingBits(image: ByteArray): ByteArray {
            val messages = split(image).toMutableList()
            val chunk = messages[0].copyOf()
            // The last data byte's low five bits are padding: 3,075 x 7 bits hold 2,690 x 8.
            chunk[chunk.size - 3] = (chunk[chunk.size - 3].toInt() or 0x05).toByte()
            closeChecksum(chunk)
            messages[0] = chunk
            return messages.fold(ByteArray(0)) { acc, m -> acc + m }
        }

        /** XORs wire byte [at] of message [chunk] with [xor] and re-closes its checksum. */
        fun mutate(image: ByteArray, chunk: Int, at: Int, xor: Int = 0x01): ByteArray {
            val messages = split(image).toMutableList()
            val message = messages[chunk].copyOf()
            message[at] = (message[at].toInt() xor xor and 0x7F).toByte()
            closeChecksum(message)
            messages[chunk] = message
            return messages.fold(ByteArray(0)) { acc, m -> acc + m }
        }

        private fun closeChecksum(message: ByteArray) {
            var x = 0
            for (i in 0 until message.size - 2) x = x xor (message[i].toInt() and 0xFF)
            message[message.size - 2] = (x and 0x7F).toByte()
        }

        fun split(image: ByteArray): List<ByteArray> {
            val out = mutableListOf<ByteArray>()
            var start = 0
            image.forEachIndexed { i, b ->
                if (b == 0xF7.toByte()) {
                    out += image.copyOfRange(start, i + 1)
                    start = i + 1
                }
            }
            return out
        }

        fun message(cmd: Int, vararg payload: Int): ByteArray {
            val body = byteArrayOf(0xF0.toByte(), 0x00, 0x01, 0x74, 0x10, cmd.toByte()) +
                ByteArray(payload.size) { payload[it].toByte() }
            val message = body + byteArrayOf(0, 0xF7.toByte())
            closeChecksum(message)
            return message
        }

        fun stepAck(step: Int, status: Int) = message(AxeFx3SysEx.CMD_STEP_ACK, step, status)

        private fun key(image: ByteArray) = ByteBuffer.wrap(image.copyOf())

        /** The name a rename request carries: the last 32 of its unpacked bytes, space-padded. */
        private fun renamedTo(request: ByteArray): String {
            val dense = unpack(request.copyOfRange(5, request.size - 2))
            return String(dense.copyOfRange(dense.size - 32, dense.size), Charsets.US_ASCII).trimEnd()
        }

        /** Fractal's bitstream, written out independently of the code under test. */
        private fun unpack(wire: ByteArray): ByteArray {
            val bits = wire.flatMap { b -> (6 downTo 0).map { (b.toInt() shr it) and 1 } }
            return ByteArray(bits.size / 8) { i ->
                (0 until 8).fold(0) { acc, j -> (acc shl 1) or bits[i * 8 + j] }.toByte()
            }
        }
    }
}

/** An instrument on [fake], as the family builds it, with the test's clock. */
internal fun TestScope.axeFx3(fake: FakeAxeFx3, config: AxeFx3Config = AxeFx3Config()) = AxeFx3Instrument(
    SysExExchange(fake.transport, backgroundScope),
    config,
    AxeFx3DumpImage.parse(AxeFx3Fixtures.EMPTY_PRESET_IMAGE),
    timeSource = testScheduler.timeSource,
)

/** Each request as its command, plus the sub-function for the editor channel, for checking order. */
internal fun FakeAxeFx3.commands(): List<String> = requests.map { request ->
    val cmd = request[5].toInt() and 0xFF
    when {
        request.contentEquals(AxeFx3Fixtures.WHO_IS_THERE) -> "who"
        cmd == AxeFx3SysEx.CMD_EDITOR -> "%02x/%02x".format(cmd, request[6].toInt() and 0xFF)
        else -> "%02x".format(cmd)
    }
}
