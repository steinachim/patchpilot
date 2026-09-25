// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

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
 *
 * The settings block is stored the same way, because selection writes it: see [staleSettingsReads],
 * which models the delayed commit that is the reason `Pro800Instrument.select` polls at all.
 */
class FakePro800(
    initial: Map<Int, ByteArray> = emptyMap(),
    /** Accept writes and quietly drop them - the failure a status byte would never reveal. */
    var ignoreWrites: Boolean = false,
    /** Program numbers whose writes silently do nothing. */
    var failWritesTo: Set<Int> = emptySet(),
    /**
     * How many settings reads after a settings write still report the *old* block.
     *
     * Real hardware does this: most settings writes are visible on the next read, but some take
     * over a second to commit. Zero is the common case; anything above it exercises the poll loop.
     */
    var staleSettingsReads: Int = 0,
) {
    /** Program number -> the encoded payload stored there. Absent means an empty address. */
    private val stored = initial.toMutableMap()

    /** The committed settings block, and a write waiting out [staleSettingsReads] before it lands. */
    private var settings: ByteArray = initialSettings()
    private var uncommittedSettings: ByteArray? = null
    private var staleReadsLeft = 0

    val writeLog = mutableListOf<Pair<Int, Int>>() // (programNumber, payload size)

    /** Every request the instrument was sent, in order - what ordering assertions are made on. */
    val requestLog = mutableListOf<ByteArray>()

    fun contentsOf(programNumber: Int): ByteArray? = stored[programNumber]

    fun nameAt(programNumber: Int): String? =
        stored[programNumber]?.let { Pro800Program.fromEncoded(it).name }

    fun isEmptyAt(programNumber: Int): Boolean = stored[programNumber] == null

    /** The committed settings block as the instrument would report it, decoded. */
    fun currentSettings(): Pro800Settings = Pro800Settings.fromEncoded(settings)

    /** The committed settings block's raw encoded payload. */
    fun settingsPayload(): ByteArray = settings

    val transport = FakeMidiTransport { request ->
        requestLog += request
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
            // The preset reload. Answers a plain OK status, like every other command.
            Pro800SysEx.TYPE_RESET_MODE -> listOf(
                Pro800SysEx.HEADER +
                    byteArrayOf(Pro800SysEx.TYPE_STATUS.toByte(), 0x00, Pro800SysEx.STATUS_OK.toByte()) +
                    Pro800SysEx.SYSEX_END,
            )
            else -> emptyList()
        }
    }

    private fun applyWrite(request: ByteArray) {
        val programNumber = Pro800SysEx.addressOf(request)!!
        val payload = Pro800SysEx.dumpPayload(request)
        writeLog += programNumber to payload.size
        if (ignoreWrites || programNumber in failWritesTo) return
        if (programNumber == Pro800SysEx.SETTINGS_ADDRESS) {
            uncommittedSettings = payload
            staleReadsLeft = staleSettingsReads
            return
        }
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

    /** Serves the block, committing a pending write once its stale reads are used up. */
    private fun settingsDump(): ByteArray {
        if (staleReadsLeft > 0) {
            staleReadsLeft--
        } else {
            uncommittedSettings?.let { settings = it }
            uncommittedSettings = null
        }
        return Pro800SysEx.HEADER +
            byteArrayOf(
                Pro800SysEx.TYPE_DUMP.toByte(),
                (Pro800SysEx.SETTINGS_ADDRESS and 0x7F).toByte(),
                ((Pro800SysEx.SETTINGS_ADDRESS shr 7) and 0x7F).toByte(),
            ) +
            settings +
            Pro800SysEx.SYSEX_END
    }

    /**
     * A full-length settings block: MIDI RX ALL and the pointer at A00.
     *
     * Full length on purpose - 40 dense bytes encode to exactly the 46 raw bytes a real block is,
     * ending mid-group, which is the shape `Pro800ProgramCodec.patchValue` exists to handle.
     */
    private fun initialSettings(): ByteArray {
        val dense = ByteArray(SETTINGS_DENSE_SIZE)
        Pro800ProgramCodec.writeValue(dense, Pro800Settings.RX_CHANNEL_DENSE, 1, Pro800Settings.RX_ALL)
        return Pro800ProgramCodec.encode(dense)
    }

    companion object {
        /** What a real 46-byte settings block decodes to. */
        const val SETTINGS_DENSE_SIZE = 40

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
