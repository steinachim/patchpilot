// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.REPORT_JSON
import de.thewolfwalkexperience.software.patchpilot.core.ReportIdentity
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Everything the app can read off a Subsequent 37 in a few seconds without changing anything on
 * it, as JSON: the ping and Identity replies, the settings block word by word, and two timed
 * reads of the selected program. Never a preset's name or sound: the reads are reported by
 * timing, category and the bytes the app interprets.
 *
 * No bulk dump: it would take two and a half minutes.
 */
class Subsequent37Reporter(
    private val instrument: Subsequent37Instrument,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : DeviceReporter {

    private val device get() = instrument.device

    override fun suggestedFilename() = "moog_subsequent37"

    override val description =
        "Reads the Subsequent 37's firmware version and MIDI settings, times two reads of the " +
            "selected preset, and shares that as JSON. Nothing is written to the instrument or " +
            "selected on it, and no preset's name or sound is included."

    override suspend fun buildReport(progress: ((String) -> Unit)?): DeviceReportResult {
        instrument.requireIdle()
        val probes = Probes()

        progress?.invoke("Reading device identity...")
        val pingHex = probes.probe("ping", "") { locked { device.ping().toHex() } }
        val identity = probes.probe<ByteArray?>("identity", null) { locked { device.identityReply() } }

        progress?.invoke("Reading settings...")
        val settingsReply = probes.probe<ByteArray?>("settings", null) { locked { device.settingsReply() } }
        val settings = settingsReply?.let { Subsequent37Settings.parse(it) }

        progress?.invoke("Timing preset reads...")
        val reads = settings?.selectedProgram?.let { number ->
            (1..2).mapNotNull { attempt ->
                probes.probe<TimedRead?>("programRead[$attempt]", null) { locked { timedRead(number) } }
            }
        }.orEmpty()

        val report = Subsequent37Report(
            note = "Read-only report generated from the connected instrument. Nothing was written or selected.",
            identity = ReportIdentity.of(instrument.identity),
            pingReplyHex = pingHex,
            identityReplyHex = identity?.toHex().orEmpty(),
            identityFirmware = identity?.let { Subsequent37SysEx.decodeFirmware(it) },
            settingsReplyHex = settingsReply?.toHex().orEmpty(),
            settingsWords = settings?.words.orEmpty(),
            selectedProgram = settings?.selectedProgram,
            inChannel = settings?.inChannelLabel,
            outChannel = settings?.outChannel?.plus(1),
            programReads = reads.map { it.summary },
            programReadsIdentical = reads.size == 2 && reads[0].raw.contentEquals(reads[1].raw),
            failures = probes.failures,
        )
        return DeviceReportResult(REPORT_JSON.encodeToString(report), probes.failures)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = instrument.lock.withLock { block() }

    /** One read of stored program [number], timed. The program itself stays here. */
    private suspend fun timedRead(number: Int): TimedRead {
        val start = timeSource.markNow()
        val message = device.readStoredMessage(number)
        val total = start.elapsedNow().inWholeMilliseconds
        val program = Subsequent37Program(Subsequent37SysEx.programOf(message)!!)
        return TimedRead(
            raw = message,
            summary = Subsequent37ProgramReadReport(
                program = number,
                totalMs = total,
                messageBytes = message.size,
                category = program.category,
                byte51 = program.byte51,
            ),
        )
    }

    private class TimedRead(val raw: ByteArray, val summary: Subsequent37ProgramReadReport)
}

/** One timed `06 01` read, with the settings pair sent first. */
@Serializable
data class Subsequent37ProgramReadReport(
    val program: Int,
    val totalMs: Long,
    val messageBytes: Int,
    /** Byte 50, raw: 0..21, or 119 (`0x77`) for none. */
    val category: Int,
    /** Byte 51, raw: stored as given; a save on the panel writes 7. */
    val byte51: Int,
)

@Serializable
data class Subsequent37Report(
    val note: String,
    val identity: ReportIdentity,
    val pingReplyHex: String,
    val identityReplyHex: String,
    /** What the app reads from the Identity Reply; the panel shows two versions, M and P. */
    val identityFirmware: String?,
    val settingsReplyHex: String,
    /** All 268 words; only 2 (selected program), 30 (IN) and 31 (OUT) are named. */
    val settingsWords: List<Int>,
    val selectedProgram: Int?,
    /** As the panel shows it: 1..16 or ALL. */
    val inChannel: String?,
    val outChannel: Int?,
    val programReads: List<Subsequent37ProgramReadReport>,
    /** Whether both reads of the same program arrived byte for byte the same. */
    val programReadsIdentical: Boolean,
    val failures: Map<String, String>,
)
