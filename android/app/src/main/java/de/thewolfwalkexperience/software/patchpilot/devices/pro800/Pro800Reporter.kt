package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.Probes
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.toHex
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The report writer's Json, built once.
 *
 * encodeDefaults: kotlinx.serialization's default config omits a property entirely when its value
 * equals the declared default, which would drop nullable fields from the report. The schema allows
 * them to be absent, but a report is more useful spelling out every field the recipient has to
 * fill in than silently leaving holes.
 */
private val REPORT_JSON = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Everything the app can read off a Pro-800 without changing anything on it, as JSON.
 *
 * **Read-only, and deliberately narrow about it.** Every request below is a documented query:
 * the identity and firmware probes, the two undocumented-but-answering types `0x02` and `0x04`
 * (recorded in `docs/Pro800SysExMessages.md` as pure responders, worth recording since nobody
 * has decoded them), the settings block, and program dumps. Nothing in the ranges whose effects
 * are unknown is sent, and `0x7D`/`0x32` are never sent by anything.
 *
 * **What it is really for.** A report from a Nord describes an instrument nobody has profiled. A
 * report from a Pro-800 does that too, but its more immediate job is supplying fixtures that
 * exercise the record shapes the decoder actually has to handle correctly. A fixture whose bytes
 * are built by hand can easily encode the same assumption as the code it is meant to test, letting
 * a decoding bug slip past a whole test suite; independently-sourced byte sequences cannot share
 * that blind spot. [sampleDumps] deliberately selects for the record shapes worth testing against:
 * a full-length one, a truncated one, an unnamed one, and an empty address.
 */
class Pro800Reporter(
    private val exchange: SysExExchange,
    private val layout: SlotLayout,
    private val identity: () -> Pro800ReportIdentity,
) : DeviceReporter {

    override fun suggestedFilename() = "behringer_pro800"

    override val description =
        "Reads the instrument's identity, firmware version, global settings and every one of its " +
            "preset slots, and shares it as JSON. Nothing is written to the instrument. The " +
            "bytes read here are what this app's Pro-800 support is tested against."

    override suspend fun buildReport(progress: ((String) -> Unit)?): String {
        val probes = Probes()

        progress?.invoke("Reading device identity...")
        val nameHex = probes.probe("deviceName", "") { rawReply(Pro800SysEx.TYPE_DEVICE_NAME).toHex() }
        val firmwareHex = probes.probe("firmware", "") {
            exchangeRaw(Pro800SysEx.requestFirmware(), "firmware") {
                Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_FIRMWARE_REPLY
            }.toHex()
        }
        // Two types the documentation lists as answering with a fixed payload nobody has decoded.
        // Recording them costs one round trip each and may be what lets someone decode them.
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
                Pro800Settings.fromEncoded(Pro800SysEx.dumpPayload(hexToBytes(it)))
            }
        }

        progress?.invoke("Scanning presets...")
        val slots = mutableListOf<Pro800SlotReport>()
        val rawByAddress = LinkedHashMap<String, String>()
        val total = layout.slotCount
        var done = 0

        for (address in layout.allAddresses()) {
            val displayId = layout.format.format(address)
            val programNumber = address.bank * (total / layout.bankCount) + address.slot
            val message = probes.probe("dump[$displayId]", ByteArray(0)) {
                exchangeRaw(Pro800SysEx.requestDump(programNumber), "dump $displayId") { reply ->
                    Pro800SysEx.isEmptyReply(reply) ||
                        (
                            Pro800SysEx.typeOf(reply) == Pro800SysEx.TYPE_DUMP &&
                                Pro800SysEx.addressOf(reply) == programNumber
                            )
                }
            }
            done++
            if (done % 50 == 0) progress?.invoke("Scanning presets ($done/$total)...")
            if (message.isEmpty()) continue

            val empty = Pro800SysEx.isEmptyReply(message)
            val payload = if (empty) ByteArray(0) else Pro800SysEx.dumpPayload(message)
            val program = Pro800Program.fromEncoded(payload)
            slots += Pro800SlotReport(
                id = displayId,
                programNumber = programNumber,
                empty = empty,
                messageBytes = message.size,
                denseBytes = program.dense.size,
                presetVersion = program.version,
                name = program.name,
            )
            rawByAddress[displayId] = message.toHex()
        }

        // The record shapes that broke the decoder, kept verbatim so a test can replay them.
        val samples = sampleDumps(slots, rawByAddress)

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
            unnamedCount = slots.count { !it.empty && it.name == null },
            presetVersionHistogram = slots.filterNot { it.empty }
                .groupingBy { it.presetVersion?.toString() ?: "unknown" }
                .eachCount(),
            messageLengths = slots.filterNot { it.empty }.map { it.messageBytes }.distinct().sorted(),
            sampleDumpsHex = samples,
            failures = probes.failures,
        )
        return REPORT_JSON.encodeToString(report)
    }

    /**
     * A handful of raw messages chosen to cover the shapes that have actually caused bugs, rather
     * than the first few addresses.
     */
    private fun sampleDumps(
        slots: List<Pro800SlotReport>,
        rawByAddress: Map<String, String>,
    ): Map<String, String> {
        val occupied = slots.filterNot { it.empty }
        val picks = linkedMapOf<String, Pro800SlotReport?>(
            // A full-length record, and a truncated one: length varies, and treating short as
            // empty is what hid 98 real presets.
            "longest" to occupied.maxByOrNull { it.messageBytes },
            "shortest" to occupied.minByOrNull { it.messageBytes },
            // A preset with no name, which must not be mistaken for an empty slot.
            "unnamed" to occupied.firstOrNull { it.name == null },
            // The newest and oldest preset format seen on this instrument.
            "newestPresetVersion" to occupied.maxByOrNull { it.presetVersion ?: -1 },
            "oldestPresetVersion" to occupied.minByOrNull { it.presetVersion ?: Int.MAX_VALUE },
            // A named one, for the decode-the-name check.
            "named" to occupied.firstOrNull { it.name != null },
            // And an address holding nothing at all.
            "empty" to slots.firstOrNull { it.empty },
        )
        return picks.mapNotNull { (label, slot) ->
            slot?.let { rawByAddress[it.id]?.let { hex -> "$label@${it.id}" to hex } }
        }.toMap()
    }

    private suspend fun rawReply(type: Int, expectType: Int = type + 1): ByteArray =
        exchangeRaw(Pro800SysEx.request(type), "type $type") { Pro800SysEx.typeOf(it) == expectType }

    private suspend fun exchangeRaw(
        request: ByteArray,
        what: String,
        matches: (ByteArray) -> Boolean,
    ): ByteArray = exchange.exchange(request, what, matches = matches)

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Who answered, as the report records it. */
@Serializable
data class Pro800ReportIdentity(
    val name: String,
    val firmwareVersion: String,
    val descriptorId: String,
)

/** One address, as the instrument answered for it. */
@Serializable
data class Pro800SlotReport(
    val id: String,
    val programNumber: Int,
    val empty: Boolean,
    /** Whole SysEx message length, `F0` and `F7` included. 2 for an empty address. */
    val messageBytes: Int,
    /** Decoded 8-bit length - what the field offsets are expressed against. */
    val denseBytes: Int,
    val presetVersion: Int?,
    val name: String?,
)

@Serializable
data class Pro800Report(
    val note: String,
    val identity: Pro800ReportIdentity,
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
    /** Every distinct message length seen. A single value here would have meant fixed-size
     * records; several is what proves they are not. */
    val messageLengths: List<Int>,
    val sampleDumpsHex: Map<String, String>,
    val failures: Map<String, String>,
)
