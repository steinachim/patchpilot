package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.midi.FakeMidiTransport

/**
 * A Pro-800 that can be written to: a map of address to stored blob, answered and mutated through
 * the real SysEx layer.
 *
 * `FakeMidiTransport` alone is a script - fine for reads, useless for a write path, where the
 * whole question is whether what was written comes back. This keeps state, so
 * [Pro800Editor]'s read-back verification is genuinely exercised rather than trivially satisfied.
 *
 * Two failure modes can be switched on, because they are the ones the verification exists for:
 * [ignoreWrites] models an instrument that accepts a write and does not store it, and
 * [failWritesTo] models one address refusing while others succeed - which is what turns a swap
 * into a potential data loss.
 */
class FakePro800(
    initial: Map<Int, ByteArray> = emptyMap(),
    /** Accept writes and quietly drop them - the failure a status byte would never reveal. */
    var ignoreWrites: Boolean = false,
    /** Program numbers whose writes silently do nothing. */
    var failWritesTo: Set<Int> = emptySet(),
) {
    /** Program number -> the encoded payload stored there. Absent means an empty address. */
    private val stored = initial.toMutableMap()

    val writeLog = mutableListOf<Pair<Int, Int>>() // (programNumber, payload size)

    fun contentsOf(programNumber: Int): ByteArray? = stored[programNumber]

    fun nameAt(programNumber: Int): String? =
        stored[programNumber]?.let { Pro800Program.fromEncoded(it).name }

    fun isEmptyAt(programNumber: Int): Boolean = stored[programNumber] == null

    val transport = FakeMidiTransport { request ->
        when (Pro800SysEx.typeOf(request)) {
            Pro800SysEx.TYPE_DEVICE_NAME -> listOf(
                Pro800SysEx.HEADER + byteArrayOf(Pro800SysEx.TYPE_DEVICE_NAME_REPLY.toByte()) +
                    "PRO-800".toByteArray(Charsets.US_ASCII) + Pro800SysEx.SYSEX_END,
            )
            Pro800SysEx.TYPE_FIRMWARE -> listOf(
                Pro800SysEx.HEADER +
                    byteArrayOf(Pro800SysEx.TYPE_FIRMWARE_REPLY.toByte(), 0x00, 1, 4, 6) +
                    Pro800SysEx.SYSEX_END,
            )
            Pro800SysEx.TYPE_REQUEST_DUMP -> listOf(dumpFor(Pro800SysEx.addressOf(request)!!))
            // A write. The instrument answers nothing, which is exactly the point.
            Pro800SysEx.TYPE_DUMP -> {
                applyWrite(request)
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun applyWrite(request: ByteArray) {
        val programNumber = Pro800SysEx.addressOf(request)!!
        val payload = Pro800SysEx.dumpPayload(request)
        writeLog += programNumber to payload.size
        if (ignoreWrites || programNumber in failWritesTo) return
        if (payload.isEmpty()) stored.remove(programNumber) else stored[programNumber] = payload
    }

    private fun dumpFor(programNumber: Int): ByteArray {
        if (programNumber == Pro800SysEx.SETTINGS_ADDRESS) return settingsDump()
        val payload = stored[programNumber] ?: return byteArrayOf(0xF0.toByte(), 0xF7.toByte())
        return Pro800SysEx.HEADER +
            byteArrayOf(
                Pro800SysEx.TYPE_DUMP.toByte(),
                (programNumber and 0x7F).toByte(),
                ((programNumber shr 7) and 0x7F).toByte(),
            ) +
            payload +
            Pro800SysEx.SYSEX_END
    }

    /** MIDI RX ALL, so nothing in these tests depends on a channel. */
    private fun settingsDump(): ByteArray {
        val dense = ByteArray(Pro800Settings.RX_CHANNEL_DENSE + 1)
        Pro800ProgramCodec.writeValue(dense, Pro800Settings.RX_CHANNEL_DENSE, 1, Pro800Settings.RX_ALL)
        return Pro800SysEx.HEADER +
            byteArrayOf(
                Pro800SysEx.TYPE_DUMP.toByte(),
                (Pro800SysEx.SETTINGS_ADDRESS and 0x7F).toByte(),
                ((Pro800SysEx.SETTINGS_ADDRESS shr 7) and 0x7F).toByte(),
            ) +
            Pro800ProgramCodec.encode(dense) +
            Pro800SysEx.SYSEX_END
    }

    companion object {
        /** A stored preset with [name], built the way the instrument's own records are. */
        fun preset(name: String?, version: Int = 111): ByteArray {
            val dense = ByteArray(Pro800ProgramFields.NAME_DENSE_END)
            Pro800ProgramCodec.writeValue(
                dense, Pro800ProgramFields.VERSION.denseOffset, Pro800ProgramFields.VERSION.byteCount, version,
            )
            if (name != null) {
                Pro800ProgramCodec.writeString(
                    dense, Pro800ProgramFields.NAME_DENSE_OFFSET, Pro800ProgramFields.NAME_LENGTH, name,
                )
            }
            return Pro800ProgramCodec.encode(dense)
        }
    }
}
