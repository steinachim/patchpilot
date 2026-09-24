// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.FlatBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.indexWalk
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.sanitizeDeviceText
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import de.thewolfwalkexperience.software.patchpilot.core.Bus

private const val TAG = "Pro800Instrument"

/**
 * The Behringer Pro-800, over MIDI SysEx. Browsing, selection and the raw preset transfer live
 * here; every edit is composed from that transfer by [Pro800Editor], with read-back verification
 * after every write.
 */
class Pro800Instrument(
    private val exchange: SysExExchange,
    private val config: Pro800Config,
    /** The catalog entry's id and name; defaulted so the JVM tests can build this with no catalog. */
    private val descriptorId: String = "behringer_pro800",
    private val catalogName: String = "Behringer Pro-800",
) : Instrument, PresetBrowser, PresetSelector, PresetTransfer {

    /** Four banks of a hundred, displayed `A00`-`D99` - how the instrument's own panel splits its flat 0..399 numbering. */
    override val layout = SlotLayout.uniform(
        bankCount = config.bankCount,
        slotsPerBank = config.slotsPerBank,
        format = FlatBankAddressFormat(slotDigits = config.slotDigits),
    )

    private var firmware: String = UNKNOWN_FIRMWARE
    private var deviceName: String = catalogName

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = descriptorId,
            family = FAMILY,
            name = deviceName,
            firmwareVersion = firmware,
            bus = Bus.MIDI,
            stableKey = "pro800:$descriptorId",
        )

    override val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val transfer: PresetTransfer get() = this

    /** Set by [validateFirmware] where the firmware is untested; see [Instrument.advisory]. */
    override var advisory: String? = null
        private set

    /** Every edit is host-composed from reads and writes - see [Pro800Editor]. */
    override val editor: PresetEditor = Pro800Editor(this, layout)

    /** A Pro-800 preset has no category and no favorite mark. */
    override val tagger: PresetTagger? = null

    override val report: DeviceReporter = Pro800Reporter(exchange, layout) {
        Pro800ReportIdentity(deviceName, firmware, descriptorId)
    }

    /** Reads the instrument's name and firmware version; neither is a prerequisite for anything below. */
    override suspend fun connect() {
        // Device-supplied text that lands in the app bar.
        deviceName = sanitizeDeviceText(queryDeviceName()) ?: catalogName
        firmware = queryFirmware() ?: UNKNOWN_FIRMWARE
        validateFirmware()
    }

    /**
     * Raises an advisory on a firmware this app has not been tested against, as
     * `NordDevice.validateFirmwareVersion` does. An empty
     * [Pro800Config.supportedFirmwareVersions] skips the check.
     */
    private fun validateFirmware() {
        val supported = config.supportedFirmwareVersions
        if (supported.isEmpty() || firmware in supported) return
        // A version that could not be read is not evidence of one that is unsupported.
        if (firmware == UNKNOWN_FIRMWARE) {
            Log.w(
                TAG,
                "Couldn't read $deviceName's firmware version; continuing without the check.",
            )
            return
        }
        // Warn, do not refuse - see Instrument.advisory.
        val warning = "$deviceName reports firmware version $firmware, which this app has not " +
            "been tested against (tested: ${supported.sorted().joinToString(", ")}). The app " +
            "might still work, but it is not guaranteed that presets will be read or written " +
            "correctly. Please consider sending in a device report, so support for this firmware " +
            "can be added in future."
        advisory = warning
        Log.w(TAG, warning)
    }

    /**
     * One read of the settings block, as its still-encoded payload. Not routed through [read],
     * which bounds its address to the 400 preset slots and re-reads short preset records. A
     * failure is not swallowed: [select] is built on this.
     */
    private suspend fun readSettings(): ByteArray {
        val reply = exchange.exchange(
            Pro800SysEx.requestSettings(),
            what = "reading the instrument's settings",
        ) { message ->
            Pro800SysEx.typeOf(message) == Pro800SysEx.TYPE_DUMP &&
                Pro800SysEx.addressOf(message) == Pro800SysEx.SETTINGS_ADDRESS
        }
        return Pro800SysEx.dumpPayload(reply)
    }

    /** Releases the MIDI port - see [SysExExchange.close]. */
    override fun close() = exchange.close()

    private suspend fun queryDeviceName(): String? = runProbe {
        val reply = exchange.exchange(
            Pro800SysEx.request(Pro800SysEx.TYPE_DEVICE_NAME),
            what = "asking the instrument its name",
        ) { Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_DEVICE_NAME_REPLY }
        Pro800SysEx.deviceName(reply)
    }

    private suspend fun queryFirmware(): String? = runProbe {
        val reply = exchange.exchange(
            Pro800SysEx.requestFirmware(),
            what = "reading the firmware version",
        ) { Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_FIRMWARE_REPLY }
        Pro800SysEx.firmwareVersion(reply)
    }

    /** A failed identity probe must not stop a session: the presets are what the user came for. */
    private suspend fun <T> runProbe(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---- PresetBrowser ----

    /**
     * Walks all 400 addresses, dumping each one: the instrument has no directory, so the only way
     * to learn a preset's name is to read the whole preset. [scope] is ignored; every address is
     * writable, so only [PresetScope.USER] is declared.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = indexWalk(
        addresses = layout.allAddresses().asIterable(),
        total = layout.slotCount,
        batchSize = BATCH_SIZE,
        layout = layout,
        readSlot = ::readSlot,
    )

    override suspend fun refresh(address: SlotAddress): PresetSlot =
        readSlot(address, layout.format.format(address))

    private suspend fun readSlot(address: SlotAddress, displayId: String): PresetSlot {
        val program = Pro800Program.fromEncoded(read(address))
        return PresetSlot(
            address = address,
            displayId = displayId,
            bankLabel = layout.format.bankLabel(address.bank),
            // An unnamed preset is still a preset: null means the slot is empty.
            name = when {
                program.isEmpty -> null
                else -> program.name ?: UNNAMED
            },
            badges = emptyList(),
        )
    }

    // ---- PresetSelector ----

    /**
     * Moves the instrument's own selection pointer, then makes it act on it. Pure SysEx: a bank
     * select plus program change is not used, because nothing acknowledges either and the
     * receive channel is not always knowable (rear DIP switches, `MIDI RX Channel = OFF`, a
     * separate `MIDI PC Mode`). The pointer write works in every one of those configurations.
     *
     *  1. Read the settings block.
     *  2. Patch the pointer and write it back, both fields in one write
     *     ([Pro800Settings.withSelection]).
     *  3. Poll the read-back until it matches: of 55 measured writes, most were visible on the
     *     next read but several took over a second.
     *  4. Only then reload with [Pro800SysEx.reloadPreset]. The write moves the pointer, and the
     *     display and every readable field follow it, while the voice engine keeps playing the
     *     previous preset until this recall. Nothing readable reports the recall, so a regression
     *     in this step is invisible to every check the app can make; it was confirmed by ear,
     *     `MIDI RX Channel = OFF` included.
     *
     * A select issued during a scan interleaves with its dumps, which costs at most one extra
     * poll iteration.
     */
    override suspend fun select(address: SlotAddress) {
        val programNumber = programNumberOf(address)
        val displayId = layout.format.format(address)

        val settings = Pro800Settings.withSelection(readSettings(), programNumber, address.bank)
        // Fire-and-forget, as every write here: the read-back below is the proof.
        exchange.tell(Pro800SysEx.writeSettings(settings))
        delay(WRITE_SETTLE_MS)

        var waited = 0L
        while (true) {
            val readBack = Pro800Settings.fromEncoded(readSettings())
            if (readBack.currentPresetNumber == programNumber && readBack.currentBank == address.bank) {
                exchange.exchange(Pro800SysEx.reloadPreset(), what = "loading $displayId") {
                    Pro800SysEx.typeOf(it) == Pro800SysEx.TYPE_STATUS
                }
                Log.i(TAG, "$deviceName: selected $displayId (program $programNumber)")
                return
            }
            if (waited >= SELECT_CONFIRM_BUDGET_MS) break
            delay(SELECT_POLL_INTERVAL_MS)
            waited += SELECT_POLL_INTERVAL_MS
        }
        // No reload: recalling now would load whatever the pointer is at.
        throw InstrumentException.ProtocolDesync(
            "Asked the instrument to load $displayId, but it never reported it as selected. The " +
                "preset was not loaded.",
        )
    }

    // ---- PresetTransfer ----

    /** One preset's raw, still-encoded blob. The browser is built on this. */
    override suspend fun read(address: SlotAddress): ByteArray {
        val first = readOnce(address)

        // A suspiciously short record is read again and accepted only if both reads agree: a
        // truncated splice is indistinguishable from a legitimately short preset by inspection,
        // but truncation reproduces and a splice does not (see Pro800Program.isSuspiciouslyShort).
        if (Pro800Program.fromEncoded(first).isSuspiciouslyShort) {
            val second = readOnce(address)
            if (!first.contentEquals(second)) {
                throw InstrumentException.ProtocolDesync(
                    "Two reads of ${layout.format.format(address)} disagreed, so one of them was " +
                        "corrupt - this happens when another application is using the same MIDI " +
                        "port. Close it and try again.",
                )
            }
        }
        return first
    }

    /** One dump round trip, with the matcher that decides what counts as its answer. */
    private suspend fun readOnce(address: SlotAddress): ByteArray {
        val programNumber = programNumberOf(address)
        val reply = exchange.exchange(
            Pro800SysEx.requestDump(programNumber),
            what = "reading preset ${layout.format.format(address)}",
        ) { message ->
            // A dump is matched on its echoed address, since a late reply to the previous request
            // is still a dump. A bare F0 F7 (an empty slot) carries no address and is accepted as
            // the answer to whatever is in flight, which is safe because exchanges are serialized.
            // The address is not sufficient: on a shared bus a reply that lost its F7 merges with
            // the next, carrying our address and another record's tail, so a record outrunning
            // its own version byte is left unmatched and the exchange's retry asks again.
            Pro800SysEx.isEmptyReply(message) ||
                Pro800SysEx.isStatusFailure(message) ||
                (
                    Pro800SysEx.typeOf(message) == Pro800SysEx.TYPE_DUMP &&
                        Pro800SysEx.addressOf(message) == programNumber &&
                        !Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(message))
                            .outrunsDeclaredVersion
                    )
        }
        // An address the instrument will not read answers `01 00 01`; leaving that unmatched
        // would spend the timeout twice and then report silence. Addresses are bounded before
        // they get here, so this is a diagnosis rather than a live path.
        if (Pro800SysEx.isStatusFailure(reply)) {
            throw InstrumentException.DeviceRejected(
                "read ${layout.format.format(address)}",
                status = Pro800SysEx.statusCodeOf(reply) ?: -1,
            )
        }
        // An empty reply decodes to an empty record, which Pro800Program reports as an empty slot.
        return if (Pro800SysEx.isEmptyReply(reply)) ByteArray(0) else Pro800SysEx.dumpPayload(reply)
    }

    /**
     * Stores a preset's raw blob at [address], or clears the address when [blob] is empty.
     *
     * Fire-and-forget. A `0x78` write is answered with a status (`01 00 00`), but the status
     * carries no address, and waiting for it on an exchange that retries by re-sending would
     * mean a slow status sends the write twice. Proof that the write landed is the read-back
     * [Pro800Editor] does after every write, which also catches a write accepted and silently
     * not stored. The only method here that changes a preset; [select] writes the settings block
     * through [Pro800SysEx.writeSettings] instead.
     */
    override suspend fun write(address: SlotAddress, blob: ByteArray) {
        val programNumber = programNumberOf(address)
        exchange.tell(Pro800SysEx.writeDump(programNumber, blob))
    }

    override val fileExtension = "syx"

    /**
     * Flattens an address into the instrument's own program number, bounded to the 400 presets:
     * the settings block lives in the same address space at 510 and must not be reachable from a
     * browsable row.
     */
    private fun programNumberOf(address: SlotAddress): Int {
        val number = address.bank * config.slotsPerBank + address.slot
        require(number in 0 until Pro800SysEx.PROGRAM_COUNT) {
            "address ${layout.format.format(address)} is outside this instrument's ${Pro800SysEx.PROGRAM_COUNT} presets"
        }
        return number
    }

    companion object {
        const val FAMILY = "pro800"

        /** Shown for a preset that exists but carries no name. */
        const val UNNAMED = "(unnamed)"

        /** What [identity] reports when the firmware query went unanswered. */
        const val UNKNOWN_FIRMWARE = "unknown"

        /** Emitting every 25 dumps keeps the list filling without a recomposition per round trip. */
        private const val BATCH_SIZE = 25

        /** The pause the reference implementation leaves after a write; `Pro800Editor` uses the same. */
        private const val WRITE_SETTLE_MS = 20L

        private const val SELECT_POLL_INTERVAL_MS = 200L

        /**
         * How long a settings write has to become visible before [select] gives up. Across 55
         * measured writes most were readable immediately and a handful only after about 1.6 s.
         */
        private const val SELECT_CONFIRM_BUDGET_MS = 2500L
    }
}
