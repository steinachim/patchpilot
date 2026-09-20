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
 * The Behringer Pro-800, over MIDI SysEx.
 *
 * Shares no wire-level anything with the Nord family - different bus, different framing, no
 * checksum, no directory service - which is why the abstraction they meet at is the *operation*
 * and why there is no common message type anywhere beneath this.
 *
 * Browsing, selection and the raw preset transfer live here; every edit is composed from that
 * transfer by [Pro800Editor], with read-back verification after every write.
 */
class Pro800Instrument(
    private val exchange: SysExExchange,
    private val config: Pro800Config,
    /**
     * The catalog entry's id and name.
     *
     * Constructor parameters rather than fields on [Pro800Config], because this class also has
     * to be buildable with no catalog entry at all - the JVM tests do exactly that, from a
     * hand-written [Pro800Config] - so they default to a generic placeholder rather than being
     * required.
     */
    private val descriptorId: String = "behringer_pro800",
    private val catalogName: String = "Behringer Pro-800",
) : Instrument, PresetBrowser, PresetSelector, PresetTransfer {

    /**
     * Four banks of a hundred, displayed `A00`-`D99`. The instrument's own front panel splits its
     * flat 0..399 numbering exactly this way, which is why the layout says so rather than the app
     * inventing a grouping.
     */
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

    /**
     * Every edit is a host-composed read-write-erase sequence that can lose a preset if
     * interrupted, so it comes with read-back verification, destructive-step-last ordering and
     * an undo buffer - see [Pro800Editor].
     */
    override val editor: PresetEditor = Pro800Editor(this, layout)

    /** A Pro-800 preset has no category and no favorite mark. */
    override val tagger: PresetTagger? = null

    override val report: DeviceReporter = Pro800Reporter(exchange, layout) {
        Pro800ReportIdentity(deviceName, firmware, descriptorId)
    }

    /**
     * Reads the two things the instrument will tell us about itself. Both are read-only queries,
     * and neither is a prerequisite for anything below - they are here so the connect screen has
     * something true to show.
     */
    override suspend fun connect() {
        // Device-supplied text that lands in the app bar, so cleaned the way every other
        // device string is (see sanitizeDeviceText).
        deviceName = sanitizeDeviceText(queryDeviceName()) ?: catalogName
        firmware = queryFirmware() ?: UNKNOWN_FIRMWARE
        validateFirmware()
    }

    /**
     * Raises an advisory on a firmware this app has not been tested against, matching what
     * `NordDevice.validateFirmwareVersion` does for the other family: untested firmware may not
     * behave the way the decoding here assumes.
     *
     * An empty [Pro800Config.supportedFirmwareVersions] means "skip the check".
     */
    private fun validateFirmware() {
        val supported = config.supportedFirmwareVersions
        if (supported.isEmpty() || firmware in supported) return
        // A version we could not *read* is not evidence of one we do not support: the identity
        // probe has already established this is a Pro-800, so a silent firmware query is a
        // transient rather than a reason to refuse the session.
        if (firmware == UNKNOWN_FIRMWARE) {
            Log.w(
                TAG,
                "Couldn't read $deviceName's firmware version; continuing without the check.",
            )
            return
        }
        // **Warn, do not refuse.** Untested firmware may not behave the way the decoding here
        // assumes - but refusing locks out the one person who could find out what it actually
        // does, and who can send back a device report saying so. The screens raise this once and
        // keep it visible (Instrument.advisory).
        val warning = "$deviceName reports firmware version $firmware, which this app has not " +
            "been tested against (tested: ${supported.sorted().joinToString(", ")}). The app " +
            "might still work, but it is not guaranteed that presets will be read or written " +
            "correctly. Please consider sending in a device report, so support for this firmware " +
            "can be added in future."
        advisory = warning
        Log.w(TAG, warning)
    }

    /**
     * One read of the settings block, as its still-encoded payload.
     *
     * Deliberately **not** routed through [read]: that path bounds its address to the 400 preset
     * slots, which is what keeps the settings block out of a browsable index, and it carries a
     * re-read rule for suspiciously short *preset* records that means nothing here.
     *
     * Unlike the identity probes above, a failure is not swallowed. [select] is built on this, and
     * a select that cannot read the block must fail loudly rather than proceed on a payload it
     * does not have.
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

    /** Releases the MIDI port. Not optional: an unclosed port stays claimed, and the next
     * connection cannot probe the device at all - see [SysExExchange.close]. */
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
     * Walks all 400 addresses, dumping each one.
     *
     * **This is the expensive shape [IndexUpdate] exists for.** The instrument has no
     * directory: the only way to learn a preset's name is to read the whole preset. So the index
     * is 400 sequential round trips, emitted in batches with progress so the list fills in front
     * of the user rather than behind a spinner.
     *
     * A single unreadable address yields [IndexUpdate.Failed] and the walk continues - losing 399
     * presets because one slot timed out would be absurd, and the same rule already governs the
     * Nord device report's probes.
     *
     * [scope] is ignored: all 400 of this instrument's addresses are writable, so it declares only
     * [PresetScope.USER] and no other value can arrive here.
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
            // An unnamed preset is still a preset: null would mean the slot is empty, and one
            // of this instrument's own presets carries no name at all.
            name = when {
                program.isEmpty -> null
                else -> program.name ?: UNNAMED
            },
            // No badge for the preset *format* version: it describes the record layout, not the
            // sound, and means nothing to whoever is reading the list.
            badges = emptyList(),
        )
    }

    // ---- PresetSelector ----

    /**
     * Moves the instrument's own selection pointer, then makes it act on it. **Pure SysEx: no MIDI
     * channel is involved anywhere.**
     *
     * Not a bank select plus a program change, because the channel-voice path cannot be made
     * reliable. Nothing acknowledges either message, so a channel mismatch is a button that
     * silently does nothing; and the channel is not always knowable - the instrument takes it
     * from its rear DIP switches in one mode and refuses MIDI entirely in another, and a separate
     * `MIDI PC Mode` setting can disable program-change reception on top of that. Writing the
     * pointer works in every one of those cases, and is confirmed rather than hoped for.
     *
     * Four steps, and the order of the last two is the whole design:
     *
     *  1. Read the settings block.
     *  2. Patch the pointer and write it back - both fields in one write, see
     *     [Pro800Settings.withSelection].
     *  3. **Poll the read-back until it matches.** A settings write is not guaranteed to be visible
     *     on the very next read: of 55 measured writes, most were immediate but several took over a
     *     second. Checking once and moving on would mean acting on a pointer that has not landed.
     *  4. **Only then** reload. The write moves the pointer; it does not recall the preset - the
     *     display and every "current preset" field follow it while the voice engine keeps playing
     *     what was loaded before. [Pro800SysEx.reloadPreset] is what makes the instrument act, and
     *     sending it before the pointer commits would recall the preset on its way out.
     *
     * Each message is serialized against the rest of the bus by the exchange's lock, but the
     * sequence as a whole is not, so a select issued during a 400-preset scan interleaves with
     * dumps. That is harmless: the write is atomic under the lock, and an intervening dump costs at
     * most one extra poll iteration.
     *
     * **Confirmed on hardware, by ear** - including with `MIDI RX Channel` set to OFF, where the
     * old path could do nothing at all. Worth knowing before simplifying any of this: step 4 is not
     * decoration. Drop it and the display, the settings block and the preset list all still report
     * the new preset while the instrument keeps playing the old one - so the regression passes every
     * check the app is capable of making, and only listening catches it.
     *
     * That is also the exact limit of what [confirmationFor] claims afterwards. The pointer is
     * written and read back until it matches, so "Selected" is verified rather than hoped; the
     * *audible* recall is not, because the instrument transmits nothing when it loads a preset and
     * no readable surface reports it. Every check available to a client reports the pointer.
     */
    override suspend fun select(address: SlotAddress) {
        val programNumber = programNumberOf(address)
        val displayId = layout.format.format(address)

        val settings = Pro800Settings.withSelection(readSettings(), programNumber, address.bank)
        // Fire-and-forget with a settle pause, the same posture as every other write here: the
        // instrument does answer with a status, but the read-back below is the stronger proof and
        // is the one being waited on anyway.
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
        // No reload. The pointer is where the instrument left it, and recalling now would load
        // whatever that is rather than what the user asked for.
        throw InstrumentException.ProtocolDesync(
            "Asked the instrument to load $displayId, but it never reported it as selected. The " +
                "preset was not loaded.",
        )
    }

    // ---- PresetTransfer ----

    /**
     * One preset's raw, still-encoded blob.
     *
     * The browser above is built on this, so a completed scan has already read every preset the
     * instrument holds - which is why backup is nearly free here and why this facet exists from
     * day one.
     */
    override suspend fun read(address: SlotAddress): ByteArray {
        val first = readOnce(address)

        // **A suspiciously short record is confirmed, not rejected** (see
        // Pro800Program.isSuspiciouslyShort). The long splices are already gone - the matcher below
        // refuses anything outrunning its own version byte - but a *truncated* splice is
        // indistinguishable from a legitimately short preset by inspection, and there is no length
        // to reject on: an unnamed version of almost any preset on a real instrument would end one
        // byte below the name field, and one would end 65 bytes below it.
        //
        // What separates them is repetition. Truncation is deterministic and a splice is a
        // collision, so reading again and requiring agreement costs one round trip and cannot
        // produce a false positive. On an instrument whose records all reach the name field this
        // never runs.
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
            // Two valid answers. A dump is matched on its echoed address, not just its type:
            // after a timeout a late reply to the *previous* request is still a dump, and
            // accepting it would return the wrong preset - data loss on the write path this
            // feeds. A bare F0 F7 is the instrument saying the address holds nothing
            // (Pro800SysEx.isEmptyReply); it carries no address to check, and is accepted as the
            // answer to whatever is in flight, which is safe because exchanges are serialized.
            //
            // **The address is necessary and not sufficient.** On a shared bus replies splice -
            // one loses its F7 under load and merges with the next - so a corrupt message can
            // carry our header, our type and our address and another record's tail, because its
            // head genuinely is our reply. The address bytes
            // arrive before the splice does, so only the record's own length betrays it. A reply
            // that fails this is left unmatched rather than raised: the exchange keeps listening,
            // and if nothing valid follows, SysExExchange's retry asks again - which is what a
            // corrupt read deserves, and costs nothing on a quiet bus where it never fires.
            Pro800SysEx.isEmptyReply(message) ||
                Pro800SysEx.isStatusFailure(message) ||
                (
                    Pro800SysEx.typeOf(message) == Pro800SysEx.TYPE_DUMP &&
                        Pro800SysEx.addressOf(message) == programNumber &&
                        !Pro800Program.fromEncoded(Pro800SysEx.dumpPayload(message))
                            .outrunsDeclaredVersion
                    )
        }
        // A refusal is an *answer*, and accepting it as one is the whole point: an address the
        // instrument will not read answers `01 00 01`, and leaving that unmatched spends the full
        // timeout twice before failing with "did not answer" - which
        // is both slow and wrong about what happened. Addresses are bounded before they get here,
        // so this is a diagnosis rather than a live path.
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
     * **Fire-and-forget, deliberately.** A `0x78` write *is* answered - a [Pro800SysEx.TYPE_STATUS]
     * of `01 00 00` for a store and an erase alike - but it is not waited for. Waiting would cost
     * a round trip on every write and would have to be built on an exchange that retries by
     * *re-sending*, so a slow status would mean the write went twice; and a status carries no
     * address, so it could not be correlated to a particular write anyway. Proof that the write
     * landed comes from reading the address back, which [Pro800Editor] does after every single
     * write - the stronger check, and the only one that catches a write accepted and silently not
     * stored. The unconsumed status is real traffic: it shows in a debug build's log, since
     * [SysExExchange] logs messages that arrive with no exchange in flight, and it cannot be
     * mistaken for a reply, since every matcher here checks a type and an address. Where a status
     * code *is* actionable is on the read path, which treats a refusal as an answer - see [read].
     *
     * This is the only method in this class that changes a *preset*. It is not reachable from the
     * UI except through [editor], which warns first. [select] also writes, but to the settings
     * block rather than through here - see [programNumberOf] for why the two paths are separate.
     */
    override suspend fun write(address: SlotAddress, blob: ByteArray) {
        val programNumber = programNumberOf(address)
        exchange.tell(Pro800SysEx.writeDump(programNumber, blob))
    }

    override val fileExtension = "syx"

    /**
     * Flattens an address into the instrument's own program number.
     *
     * Bounded against [Pro800SysEx.SETTINGS_ADDRESS] as well as the program count: the settings
     * block lives in the same address space at 510, and a browsable row that overwrites global
     * settings is not a preset. [select] does write that block, but reaches it
     * through [Pro800SysEx.writeSettings] rather than through an address that came from a row -
     * which is exactly the separation this bound exists to enforce.
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

        /** Emitting every 25 dumps keeps the list visibly filling without one recomposition per
         * round trip. */
        private const val BATCH_SIZE = 25

        /** The pause the reference implementation leaves after a write before reading back. Same
         * figure `Pro800Editor` uses, and for the same reason. */
        private const val WRITE_SETTLE_MS = 20L

        private const val SELECT_POLL_INTERVAL_MS = 200L

        /**
         * How long a settings write has to become visible before [select] gives up.
         *
         * Measured rather than guessed: across 55 settings writes on real hardware, most were
         * readable immediately, a handful only after about 1.6 seconds, and a few not within three.
         * Anything under about two and a half seconds is not a window at all.
         */
        private const val SELECT_CONFIRM_BUDGET_MS = 2500L
    }
}
