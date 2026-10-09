// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.REPORT_JSON
import de.thewolfwalkexperience.software.patchpilot.core.ReportIdentity
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Everything the app can read off a Pro-800 without changing anything on it, as JSON: the
 * identity and firmware probes, the two undocumented types `0x02` and `0x04` (fixed replies
 * nobody has decoded), the settings block, and program dumps. The dumps are read to count them;
 * the report carries only aggregates, never a preset's bytes or its name.
 */
class Pro800Reporter(
    private val exchange: SysExExchange,
    private val layout: SlotLayout,
    /**
     * One address's whole reply, read as the browser reads it (`Pro800Instrument`), so a reply
     * spliced on a shared port is retried or refused rather than counted.
     */
    private val readReply: suspend (SlotAddress) -> ByteArray,
    private val identity: () -> ReportIdentity,
) : DeviceReporter {

    override fun suggestedFilename() = "behringer_pro800"

    override val description =
        "Reads the instrument's identity, firmware version and global settings, counts its " +
            "presets by format version, and shares that as JSON. Nothing is written to the " +
            "instrument, and no preset's data or name is included."

    override suspend fun buildReport(progress: ((String) -> Unit)?): DeviceReportResult {
        val probes = Probes()

        progress?.invoke("Reading device identity...")
        val nameHex = probes.probe("deviceName", "") { rawReply(Pro800SysEx.TYPE_DEVICE_NAME).toHex() }
        val firmwareHex = probes.probe("firmware", "") {
            exchangeRaw(Pro800SysEx.requestFirmware(), "firmware") {
                Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_FIRMWARE_REPLY
            }.toHex()
        }
        // Fixed replies nobody has decoded, recorded for whoever does.
        val unknown02 = probes.probe("type02", "") { rawReply(0x02, expectType = 0x03).toHex() }
        val unknown04 = probes.probe("type04", "") { rawReply(0x04, expectType = 0x05).toHex() }

        progress?.invoke("Reading settings...")
        val settingsHex = probes.probe("settings", "") {
            exchangeRaw(Pro800SysEx.requestSettings(), "settings") { message ->
                Pro800SysEx.typeOf(message) == Pro800SysEx.TYPE_DUMP &&
                    Pro800SysEx.addressOf(message) == Pro800SysEx.SETTINGS_ADDRESS
            }.toHex()
        }
        val settings = settingsHex.takeIf { it.isNotEmpty() }?.let {
            probes.probe<Pro800Settings?>("settingsParse", null) {
                Pro800Settings.fromEncoded(Pro800SysEx.dumpPayload(it.hexToBytes()))
            }
        }

        progress?.invoke("Scanning presets...")
        val slots = mutableListOf<Pro800SlotReport>()
        val total = layout.slotCount
        var done = 0

        val addresses = layout.allAddresses().toList()
        probes.startLoop()
        for ((i, address) in addresses.withIndex()) {
            if (probes.gaveUp("dumpsNotRead", addresses.size - i)) break
            val displayId = layout.format.format(address)
            val message = probes.probe("dump[$displayId]", ByteArray(0)) { readReply(address) }
            done++
            if (done % 50 == 0) progress?.invoke("Scanning presets ($done/$total)...")
            if (message.isEmpty()) continue

            val empty = Pro800SysEx.isEmptyReply(message)
            val payload = if (empty) ByteArray(0) else Pro800SysEx.dumpPayload(message)
            val program = Pro800Program.fromEncoded(payload)
            slots += Pro800SlotReport(
                empty = empty,
                messageBytes = message.size,
                presetVersion = program.version,
                unnamed = !empty && program.name == null,
            )
        }

        val report = Pro800Report(
            note = "Read-only report generated from the connected instrument. No data was written.",
            identity = identity(),
            deviceNameReplyHex = nameHex,
            firmwareReplyHex = firmwareHex,
            type02ReplyHex = unknown02,
            type04ReplyHex = unknown04,
            settingsReplyHex = settingsHex,
            midiRxChannelSetting = settings?.midiRxChannelSetting,
            midiRxDescription = settings?.midiRxDescription,
            slotCount = slots.size,
            occupiedCount = slots.count { !it.empty },
            unnamedCount = slots.count { it.unnamed },
            presetVersionHistogram = slots.filterNot { it.empty }
                .groupingBy { it.presetVersion?.toString() ?: "unknown" }
                .eachCount(),
            messageLengths = slots.filterNot { it.empty }.map { it.messageBytes }.distinct().sorted(),
            failures = probes.failures,
        )
        return DeviceReportResult(REPORT_JSON.encodeToString(report), probes.failures)
    }

    private suspend fun rawReply(type: Int, expectType: Int = type + 1): ByteArray =
        exchangeRaw(Pro800SysEx.request(type), "type $type") { Pro800SysEx.typeOf(it) == expectType }

    private suspend fun exchangeRaw(
        request: ByteArray,
        what: String,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = exchange.exchange(request, what, matches = matches)
}

/** One address, reduced to what the aggregates need; nothing here identifies the preset. */
private data class Pro800SlotReport(
    val empty: Boolean,
    /** Whole SysEx message length, `F0` and `F7` included. 2 for an empty address. */
    val messageBytes: Int,
    val presetVersion: Int?,
    val unnamed: Boolean,
)

@Serializable
data class Pro800Report(
    val note: String,
    val identity: ReportIdentity,
    val deviceNameReplyHex: String,
    val firmwareReplyHex: String,
    val type02ReplyHex: String,
    val type04ReplyHex: String,
    val settingsReplyHex: String,
    val midiRxChannelSetting: Int?,
    val midiRxDescription: String?,
    val slotCount: Int,
    val occupiedCount: Int,
    val unnamedCount: Int,
    /** How many presets carry each format version - the spread the decoder has to cope with. */
    val presetVersionHistogram: Map<String, Int>,
    /** Every distinct message length seen; several is what shows records are variable length. */
    val messageLengths: List<Int>,
    val failures: Map<String, String>,
)
