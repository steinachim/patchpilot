// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.motifxs

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MotifXsSelection"

/** Reads the instrument's current mode, or null if it will not say: one parameter request, ~10 ms. Best-effort - see [MotifXsSelector]. */
internal suspend fun MotifXsInstrument.readMode(): MotifXsMode? = try {
    val reply = exchange.exchange(
        request = MotifXsSysEx.requestMode(config.deviceNumber),
        what = "asking which mode the instrument is in",
        timeout = MotifXsInstrument.MODE_TIMEOUT,
    ) { MotifXsSysEx.modeOf(it) != null }
    MotifXsSysEx.modeOf(reply)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "Couldn't read the mode; continuing without the check", e)
    null
}

/**
 * The voice currently loaded in the edit buffer, read a byte at a time from `40 00 nn` - the
 * documented readback channel for what the panel is showing. Answers in Voice and Song mode
 * and is silent in Performance mode, so null means "could not tell", never "no voice".
 */
internal suspend fun MotifXsInstrument.readEditBufferName(): String? {
    val bytes = ByteArray(MotifXsVoice.NAME_LENGTH)
    for (offset in 0 until MotifXsVoice.NAME_LENGTH) {
        val reply = try {
            exchange.exchange(
                request = MotifXsSysEx.requestEditBufferNameByte(config.deviceNumber, offset),
                what = "reading the loaded voice's name",
                timeout = MotifXsInstrument.MODE_TIMEOUT,
            ) { m ->
                MotifXsSysEx.typeOf(m) == MotifXsSysEx.TYPE_PARAM_CHANGE &&
                    MotifXsSysEx.addressOf(m) ==
                    Triple(MotifXsSysEx.COMMON_HI, MotifXsSysEx.COMMON_MID, offset)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val at = MotifXsSysEx.REQUEST_ADDRESS_INDEX + 3
        if (reply.size <= at + 1) return null
        bytes[offset] = reply[at]
    }
    return String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000', ' ').ifEmpty { null }
}

/**
 * Selection is three parameter sets at `65 00 00/01/02`, in Voice mode only: in Performance and
 * Song mode the instrument ignores them silently, so a selection there is refused with switching
 * to Voice mode as the remedy.
 */
internal class MotifXsSelector(private val motif: MotifXsInstrument) : PresetSelector {

    override suspend fun select(address: SlotAddress) {
        requireVoiceMode()
        val bank = requireNotNull(motif.config.banks.getOrNull(address.bank)) { "No bank ${address.bank}" }
        // Assuming an LSB would select a different bank without saying so.
        val lsb = bank.selectLsb ?: throw InstrumentException.NotSupported(
            "select a voice in ${bank.shownLabel}, whose bank-select LSB is unknown",
        )
        val sets = MotifXsSysEx.selectVoice(
            motif.config.deviceNumber,
            lsb,
            address.slot,
            bank.selectMsb,
        )
        // All three under one lock, so a listing's dump request cannot land between the bank
        // select and the program. Only the third echo - the program, carrying the value sent -
        // means the selection landed. A selection is idempotent, so a missing echo sends all
        // three again, as often as the exchange retries anything else.
        val what = "selecting ${motif.layout.format.format(address)}"
        var retriesLeft = MotifXsInstrument.SELECT_RETRIES
        while (true) {
            try {
                motif.exchange.exchangeAfterAll(sets, what, timeout = MotifXsInstrument.VOICE_TIMEOUT) { reply ->
                    MotifXsSysEx.isSelectEcho(reply) && reply.size > 8 &&
                        (reply[7].toInt() and 0xFF) == MotifXsSysEx.SELECT_PROGRAM &&
                        (reply[8].toInt() and 0x7F) == address.slot
                }
                return
            } catch (e: InstrumentException.Timeout) {
                if (retriesLeft-- == 0) throw e
            }
        }
    }

    /**
     * Refuses a selection the instrument would silently ignore: selection works in Voice mode
     * only, and in Performance and Song mode the three parameter sets draw no echo and change
     * nothing, so the selection would otherwise time out and report silence.
     *
     * The mode change is offered as a remedy rather than sent, since taking a player out of the
     * Performance they are using is a visible change to their setup. An unreadable mode blocks
     * nothing: the selection is attempted anyway.
     */
    private suspend fun requireVoiceMode() {
        val mode = motif.readMode() ?: return
        if (!mode.blocksVoiceSelection) return
        throw InstrumentException.BlockedByDeviceState(
            message = "The instrument is in ${mode.label} mode, where it ignores a voice " +
                "selection silently - nothing would change.",
            remedyLabel = "Switch to Voice mode",
            remedyDetail = "This switches the instrument out of ${mode.label} mode, which " +
                "changes what it is playing. Anything unsaved in the current " +
                "${mode.label.lowercase()} stays on the instrument, but you will have to go " +
                "back to it yourself.",
            remedy = { switchToVoiceMode() },
        )
    }

    /**
     * Sends the documented mode change and confirms it took - only ever called from a remedy the
     * user has accepted. The confirmation matters because a parameter change draws no reply of
     * its own, so a switch that did not happen would look like one that did.
     */
    private suspend fun switchToVoiceMode() {
        motif.exchange.tell(MotifXsSysEx.setMode(motif.config.deviceNumber, MotifXsMode.VOICE))

        // Polled, not read back once: the instrument tears down a Performance and loads a voice,
        // so a read straight after the send returns the old value. Bounded by the clock rather
        // than by a poll count, since each read carries its own timeout and retries.
        var last: MotifXsMode? = null
        val reachedVoice = withTimeoutOrNull(MotifXsInstrument.MODE_SETTLE_BUDGET) {
            var now: MotifXsMode?
            do {
                delay(MotifXsInstrument.MODE_SETTLE_INTERVAL)
                now = motif.readMode()
                if (now != null) last = now
            } while (now?.blocksVoiceSelection != false)
            true
        }
        if (reachedVoice == true) return

        // Never read at all is "could not tell", as in [requireVoiceMode].
        val stuck = last ?: return
        throw InstrumentException.BlockedByDeviceState(
            "The instrument was still in ${stuck.label} mode after being asked to switch. " +
                "Change it from the panel instead.",
        )
    }
}
