// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.axefx3

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource

/** The report writer's Json. encodeDefaults, so the report spells out every field. */
private val REPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Everything the app can read off an Axe-Fx III without changing anything on it, as JSON: the
 * identity replies (firmware, the model record, the "who is there" answer), which preset number
 * is loaded, how many slots hold a preset, and how one preset's dump arrives over this phone's
 * MIDI path. Never a preset's name or data: the loaded preset is reported by number only, and the
 * dumps by size and timing.
 */
class AxeFx3Reporter(
    private val instrument: AxeFx3Instrument,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : DeviceReporter {

    private val device get() = instrument.device

    override fun suggestedFilename() = "fractal_axefx3"

    override val description =
        "Reads the Axe-Fx III's firmware version and model, counts its presets, and times two " +
            "reads of one preset, and shares that as JSON. Nothing is written to the instrument " +
            "or loaded on it, and no preset's data or name is included."

    override suspend fun buildReport(progress: ((String) -> Unit)?): DeviceReportResult {
        val probes = Probes()

        progress?.invoke("Reading device identity...")
        val firmwareHex = probes.probe("firmware", "") { locked { device.firmwareReply().toHex() } }
        val capabilitiesHex = probes.probe("capabilities", "") { locked { device.capabilitiesReply().toHex() } }
        val whoIsThereHex = probes.probe("whoIsThere", "") { locked { device.whoIsThereReply().toHex() } }
        val loaded = probes.probe<Int?>("loadedPreset", null) {
            locked { device.loadedPreset(instrument.presetCount) }
        }

        progress?.invoke("Counting presets...")
        val count = instrument.presetCount
        var occupied = 0
        var unnamed = 0
        var staleTails = 0
        var firstOccupied: Int? = null
        for (number in 0 until count) {
            val field = probes.probe<ByteArray?>("name[$number]", null) { locked { device.nameField(number) } }
                ?: continue
            val name = AxeFx3SysEx.nameFromField(field)
            if (AxeFx3SysEx.hasStaleTail(field)) staleTails++
            if (name != AxeFx3SysEx.EMPTY_NAME) {
                occupied++
                if (name.isBlank()) unnamed++
                if (firstOccupied == null) firstOccupied = number
            }
            if ((number + 1) % 64 == 0) progress?.invoke("Counting presets (${number + 1}/$count)...")
        }

        progress?.invoke("Timing preset reads...")
        val reads = firstOccupied?.let { number ->
            (1..2).mapNotNull { attempt ->
                probes.probe<TimedRead?>("dumpRead[$attempt]", null) { locked { timedRead(number) } }
            }
        }.orEmpty()

        val report = AxeFx3Report(
            note = "Read-only report generated from the connected instrument. Nothing was written or loaded.",
            identity = AxeFx3ReportIdentity(
                name = instrument.identity.name,
                firmwareVersion = instrument.identity.firmwareVersion,
                descriptorId = instrument.identity.descriptorId,
            ),
            firmwareReplyHex = firmwareHex,
            capabilitiesReplyHex = capabilitiesHex,
            capabilities = instrument.capabilities,
            presetCount = count,
            whoIsThereReplyHex = whoIsThereHex,
            loadedPresetNumber = loaded,
            loadedPresetQueryWorks = loaded != null,
            occupiedCount = occupied,
            emptyCount = count - occupied - probes.failures.keys.count { it.startsWith("name[") },
            unnamedCount = unnamed,
            staleNameTailCount = staleTails,
            dumpReads = reads.map { it.summary },
            dumpReadsIdentical = reads.size == 2 && reads[0].raw.contentEquals(reads[1].raw),
            failures = probes.failures,
        )
        return DeviceReportResult(REPORT_JSON.encodeToString(report), probes.failures)
    }

    private suspend fun <T> locked(block: suspend () -> T): T = instrument.lock.withLock { block() }

    /** One dump read of [number], timed message by message. The data itself stays here. */
    private suspend fun timedRead(number: Int): TimedRead {
        val marks = mutableListOf<ComparableTimeMark>()
        val start = timeSource.markNow()
        val image = device.readDump(number) { marks += timeSource.markNow() }
        val total = start.elapsedNow().inWholeMilliseconds
        // marks[0] is the acknowledgement; the rest are the 16 chunks and the end marker.
        val ackMs = marks.firstOrNull()?.let { (it - start).inWholeMilliseconds }
        val intervals = marks.drop(1).zipWithNext { a, b -> (b - a).inWholeMilliseconds }.sorted()
        return TimedRead(
            raw = image.raw,
            summary = AxeFx3DumpReadReport(
                totalMs = total,
                ackMs = ackMs,
                chunkIntervalMinMs = intervals.firstOrNull(),
                chunkIntervalMedianMs = intervals.getOrNull(intervals.size / 2),
                chunkIntervalMaxMs = intervals.lastOrNull(),
                messageCount = image.chunks.size + 1,
                messageLengths = (image.chunks + image.end).map { it.size }.distinct().sorted(),
                totalBytes = image.raw.size,
            ),
        )
    }

    private class TimedRead(val raw: ByteArray, val summary: AxeFx3DumpReadReport)
}

/** Who answered, as the report records it. */
@Serializable
data class AxeFx3ReportIdentity(
    val name: String,
    val firmwareVersion: String,
    val descriptorId: String,
)

/** One timed dump read: sizes and timings, nothing of the preset itself. */
@Serializable
data class AxeFx3DumpReadReport(
    val totalMs: Long,
    val ackMs: Long?,
    val chunkIntervalMinMs: Long?,
    val chunkIntervalMedianMs: Long?,
    val chunkIntervalMaxMs: Long?,
    val messageCount: Int,
    /** Every distinct message length; one chunk size and one end size is what a whole dump has. */
    val messageLengths: List<Int>,
    val totalBytes: Int,
)

@Serializable
data class AxeFx3Report(
    val note: String,
    val identity: AxeFx3ReportIdentity,
    val firmwareReplyHex: String,
    /** The model record; its fifth value is the preset count, the other four are not decoded. */
    val capabilitiesReplyHex: String,
    val capabilities: List<Int>,
    val presetCount: Int,
    val whoIsThereReplyHex: String,
    /** Only the number: the reply also carries the preset's name, which is left out. */
    val loadedPresetNumber: Int?,
    val loadedPresetQueryWorks: Boolean,
    val occupiedCount: Int,
    val emptyCount: Int,
    val unnamedCount: Int,
    /** Name fields with leftover bytes after the terminator, which deleting a preset leaves. */
    val staleNameTailCount: Int,
    val dumpReads: List<AxeFx3DumpReadReport>,
    /** Whether the two reads of the same preset arrived byte for byte the same. */
    val dumpReadsIdentical: Boolean,
    val failures: Map<String, String>,
)
