// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TAG = "AxeFx3Device"

/**
 * One Axe-Fx III request at a time, over [exchange]: the wire operations and nothing composed
 * from them. Takes no lock of its own; [AxeFx3Instrument] holds one around every sequence, since
 * a select and the store that follows it must not have anything else between them.
 *
 * Which steps may be re-sent is decided per step. A query or a select is idempotent and keeps
 * the exchange's retry. A dump write step never is: a re-sent chunk would land in the transfer
 * twice.
 */
class AxeFx3Device(private val exchange: SysExExchange) {

    /** The preset this session last selected, so a restore that would change nothing is skipped. */
    var lastSelected: Int? = null
        private set

    /** How many selects have been sent, so an edit can tell whether it loaded anything at all. */
    var selections: Int = 0
        private set

    val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    suspend fun firmwareReply(): ByteArray =
        exchange.exchange(AxeFx3SysEx.firmwareRequest(), "reading the firmware version") {
            AxeFx3SysEx.commandOf(it) == AxeFx3SysEx.CMD_FIRMWARE
        }

    suspend fun capabilitiesReply(): ByteArray =
        exchange.exchange(AxeFx3SysEx.capabilitiesRequest(), "asking which model this is") {
            AxeFx3SysEx.isCapabilitiesReply(it)
        }

    /** The broadcast "who is there" Axe-Edit III opens with, for the device report only. */
    suspend fun whoIsThereReply(): ByteArray =
        exchange.exchange(WHO_IS_THERE, "asking who is there") {
            AxeFx3SysEx.commandOf(it) == AxeFx3SysEx.CMD_STEP_ACK
        }

    /** Preset [number]'s raw 32-byte name field, matched on the number its reply echoes. */
    suspend fun nameField(number: Int): ByteArray {
        val reply = exchange.exchange(AxeFx3SysEx.queryName(number), "reading the name of preset $number", NAME_TIMEOUT) {
            AxeFx3SysEx.nameReplyNumber(it) == number
        }
        return AxeFx3SysEx.nameField(reply)
            ?: throw InstrumentException.ProtocolDesync("The name of preset $number arrived too short.")
    }

    /** Preset [number]'s name as the instrument reports it, [AxeFx3SysEx.EMPTY_NAME] included. */
    suspend fun name(number: Int): String = AxeFx3SysEx.nameFromField(nameField(number))

    /**
     * The number of the preset the instrument has loaded, from the reply to a name query for
     * "the current preset". Assumed from Fractal's documentation, not yet seen on a unit: the
     * reply carries it. Null where the reply echoes `7F 7F` instead or a number this unit does
     * not have, which [AxeFx3Instrument.connect] uses to tell whether the assumption holds.
     */
    suspend fun loadedPreset(presetCount: Int): Int? {
        val reply = exchange.exchange(AxeFx3SysEx.queryLoaded(), "asking which preset is loaded", NAME_TIMEOUT) {
            AxeFx3SysEx.commandOf(it) == AxeFx3SysEx.CMD_QUERY_NAME
        }
        return AxeFx3SysEx.nameReplyNumber(reply)?.takeIf { it < presetCount }
    }

    suspend fun select(number: Int) {
        exchange.exchange(AxeFx3SysEx.select(number), "loading preset $number") {
            AxeFx3SysEx.isEditorAck(it, AxeFx3SysEx.SUB_SELECT)
        }
        lastSelected = number
        selections++
    }

    /** Renames the loaded preset's edit buffer; nothing is stored until [store]. */
    suspend fun setName(name: String) {
        exchange.exchange(AxeFx3SysEx.setName(name), "renaming the loaded preset") {
            AxeFx3SysEx.isEditorAck(it, AxeFx3SysEx.SUB_SET_NAME)
        }
    }

    /**
     * Stores the edit buffer into slot [number]: a flash write, acknowledged after about 600 ms.
     * Not re-sent on a timeout, and a missing acknowledgement is not an error by itself: every
     * caller reads the slot back, which is the proof either way. Returns whether it was
     * acknowledged.
     */
    suspend fun store(number: Int): Boolean = try {
        exchange.exchange(AxeFx3SysEx.store(number), "storing preset $number", STORE_TIMEOUT, retries = 0) {
            AxeFx3SysEx.isEditorAck(it, AxeFx3SysEx.SUB_STORE)
        }
        true
    } catch (e: InstrumentException.Timeout) {
        Log.w(TAG, "No acknowledgement for storing preset $number; the read-back decides")
        false
    }

    /**
     * Preset [number]'s dump. The request names the preset; nothing is selected. Read twice at
     * most: a damaged or incomplete dump is read again after a pause, which lets whatever was
     * still arriving from the first attempt drain away.
     *
     * @param onMessage sees every message of the dump as it is accepted, for the device report's
     *   timings.
     */
    suspend fun readDump(number: Int, onMessage: ((ByteArray) -> Unit)? = null): AxeFx3DumpImage {
        try {
            return readDumpOnce(number, onMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException) {
            if (e !is InstrumentException.Timeout && e !is InstrumentException.ProtocolDesync) throw e
            Log.w(TAG, "Reading preset $number failed (${e.message}); reading it again")
        }
        delay(READ_RETRY_PAUSE)
        return readDumpOnce(number, onMessage)
    }

    private suspend fun readDumpOnce(number: Int, onMessage: ((ByteArray) -> Unit)?): AxeFx3DumpImage {
        // Nothing counts until this read's own acknowledgement: a chunk or end marker still
        // arriving from an earlier, abandoned read must not be taken for part of this one.
        var started = false
        val messages = exchange.exchangeSequence(
            AxeFx3SysEx.dumpReadRequest(number),
            what = "reading preset $number",
            timeout = READ_TIMEOUT,
            accept = { message ->
                val command = AxeFx3SysEx.commandOf(message)
                val accepted = if (!started) {
                    AxeFx3SysEx.isDumpReadAck(message, number).also { started = it }
                } else {
                    command == AxeFx3SysEx.CMD_DUMP_CHUNK || command == AxeFx3SysEx.CMD_DUMP_END
                }
                if (accepted) onMessage?.invoke(message)
                accepted
            },
            done = { started && AxeFx3SysEx.commandOf(it) == AxeFx3SysEx.CMD_DUMP_END },
        )
        return AxeFx3DumpImage.fromReadReplies(messages.drop(1))
    }

    /**
     * Loads [image] into the edit buffer of the loaded preset: the begin message, the 16 chunks
     * exactly as read, then the end marker, each acknowledged before the next. Every
     * acknowledgement's status is checked, since the instrument acknowledges a dump it rejects.
     * Nothing is stored; [store] does that.
     */
    suspend fun writeEditBuffer(image: AxeFx3DumpImage) {
        step(AxeFx3SysEx.dumpWriteBegin(), AxeFx3SysEx.CMD_DUMP_BEGIN, "start the preset transfer", STEP_TIMEOUT)
        image.chunks.forEachIndexed { index, chunk ->
            step(chunk, AxeFx3SysEx.CMD_DUMP_CHUNK, "take part ${index + 1} of the preset", STEP_TIMEOUT)
        }
        step(image.end, AxeFx3SysEx.CMD_DUMP_END, "accept the preset", END_TIMEOUT)
    }

    private suspend fun step(message: ByteArray, step: Int, what: String, timeout: Duration) {
        val ack = exchange.exchange(message, "sending a preset", timeout, retries = 0) {
            AxeFx3SysEx.isStepAck(it, step)
        }
        val status = AxeFx3SysEx.stepAckStatus(ack)
        if (status != 0) {
            throw InstrumentException.DeviceRejected(
                what,
                status,
                explanation = if (step == AxeFx3SysEx.CMD_DUMP_END) {
                    "The Axe-Fx III rejected the preset data (status $status) and stored nothing."
                } else {
                    null
                },
            )
        }
    }

    companion object {
        /** Model `7F` addresses any Fractal device; the Axe-Fx III answers as model `10`. */
        val WHO_IS_THERE = byteArrayOf(0xF0.toByte(), 0x00, 0x01, 0x74, 0x7F, 0x00, 0x7A, 0xF7.toByte())

        /** A name reply arrives within about a millisecond. */
        private val NAME_TIMEOUT = 500.milliseconds

        /** The flash write behind a store takes about 600 ms. */
        private val STORE_TIMEOUT = 5.seconds

        /** A dump is acknowledged within 5 ms and complete after about 470 ms. */
        private val READ_TIMEOUT = 5.seconds
        private val READ_RETRY_PAUSE = 500.milliseconds

        /** Each chunk is acknowledged after about 20 ms. */
        private val STEP_TIMEOUT = 2.seconds

        /** The end marker is acknowledged after about 85 ms, once the instrument has checked it. */
        private val END_TIMEOUT = 3.seconds
    }
}
