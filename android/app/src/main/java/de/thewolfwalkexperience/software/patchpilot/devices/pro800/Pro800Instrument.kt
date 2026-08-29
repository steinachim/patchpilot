package de.thewolfwalkexperience.software.patchpilot.devices.pro800

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.FlatBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.SetupQuestion
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentSetup
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import de.thewolfwalkexperience.software.patchpilot.core.Bus

private const val TAG = "Pro800Instrument"

/**
 * The Behringer Pro-800, over MIDI SysEx.
 *
 * Shares no wire-level anything with the Nord family - different bus, different framing, no
 * checksum, no directory service - which is why the abstraction they meet at is the *operation*
 * and why there is no common message type anywhere beneath this.
 *
 * **What ships here is read-only, plus selection.** Browsing 400 presets and loading any of them
 * needs neither the write path nor a confirmed answer to what marks an empty slot, so it is
 * useful on its own and risks nothing on a user's instrument. The editor facet is deliberately
 * absent until the write path lands with its read-back verification and undo buffer (design
 * section 7.5, stage 5).
 */
class Pro800Instrument(
    private val exchange: SysExExchange,
    private val config: Pro800Config,
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
    private var deviceName: String = config.name

    /**
     * The channel selection is sent on, resolved at connect time from the instrument's own
     * settings.
     *
     * Null means no program change can land: either the instrument's MIDI receive is OFF, or it is
     * in DIP-switch mode and the user has not yet said which channel that is ([setup]). [select]
     * refuses in both cases rather than sending into the void, because nothing acknowledges a
     * program change and a wrong-channel send is indistinguishable from a working one.
     */
    private var sendChannel: Int? = config.midiChannel
    private var midiRxDescription: String = "not read"

    /** Set when the instrument said its channel comes from the DIP switches, which it will not
     * read out. Only the user can resolve it - see [setup]. */
    private var awaitingChannelChoice = false

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = config.descriptorId,
            family = FAMILY,
            name = deviceName,
            firmwareVersion = firmware,
            bus = Bus.MIDI,
            stableKey = "pro800:${config.descriptorId}",
        )

    override val rebuildOnResume: Boolean get() = exchange.rebuildOnResume

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val transfer: PresetTransfer get() = this

    /**
     * Every edit here is a host-composed read-write-erase sequence that can lose a preset if
     * interrupted, so it ships *with* its read-back verification, its destructive-step-last
     * ordering and its undo buffer, or not at all. See [Pro800Editor].
     */
    override var advisory: String? = null
        private set

    override val editor: PresetEditor = Pro800Editor(this, layout)

    /**
     * Non-null because this instrument *can* need something the app cannot work out: its MIDI
     * channel, when it is taking that from its DIP switches. [InstrumentSetup.question] is null
     * whenever nothing is actually pending.
     */
    override val setup: InstrumentSetup = object : InstrumentSetup {
        override val question: SetupQuestion?
            get() = if (!awaitingChannelChoice) {
                null
            } else {
                SetupQuestion(
                    title = "Which MIDI channel?",
                    explanation = "$deviceName takes its MIDI receive channel from the DIP " +
                        "switches on its back panel, and does not report which one they are set " +
                        "to. Loading a preset sends on the channel you pick here. If it is wrong, " +
                        "the instrument ignores it silently - nothing comes back either way.",
                    options = (1..16).map { "Channel $it" },
                    defaultOption = config.midiChannel.coerceIn(0, 15),
                )
            }

        override suspend fun answer(optionIndex: Int) {
            require(optionIndex in 0..15) { "MIDI channel index must be 0..15, was $optionIndex" }
            sendChannel = optionIndex
            awaitingChannelChoice = false
            midiRxDescription = "channel ${optionIndex + 1} (set by you)"
            Log.i(TAG, "$deviceName: sending selection on MIDI channel ${optionIndex + 1} by user choice")
        }
    }

    /** Read-only, and the source of the sample fixtures used in tests - see [Pro800Reporter]. */
    override val report: DeviceReporter = Pro800Reporter(exchange, layout) {
        Pro800ReportIdentity(deviceName, firmware, config.descriptorId)
    }

    /**
     * Reads the two things the instrument will tell us about itself. Both are read-only queries,
     * and neither is a prerequisite for anything below - they are here so the connect screen has
     * something true to show.
     */
    override suspend fun connect() {
        deviceName = queryDeviceName() ?: config.name
        firmware = queryFirmware() ?: UNKNOWN_FIRMWARE
        validateFirmware()
        resolveSendChannel()
    }

    /**
     * Refuses to continue on a firmware this app has not been tested against, matching what
     * `NordDevice.validateFirmwareVersion` does for the other family: untested firmware may not
     * behave the way the decoding here assumes, and finding that out by mis-reading someone's
     * presets is worse than declining.
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
        advisory = "$deviceName reports firmware version $firmware, which this app has not been " +
            "tested against (tested: ${supported.sorted().joinToString(", ")}). The app might " +
            "still work, but it is not guaranteed that presets will be read or written " +
            "correctly. Please consider sending in a device report, so support for this firmware " +
            "can be added in future."
        Log.w(TAG, advisory!!)
    }

    /**
     * Reads the instrument's `MIDI RX Channel` setting and sends on it.
     *
     * **This is what makes selection work at all.** A program change sent on the wrong channel is
     * ignored, and nothing acknowledges it, so the failure is completely silent: an instrument set
     * to channel 3 with an app configured for channel 1 looks like a button that does nothing.
     *
     * Three answers are possible, and only one of them is a channel:
     *
     *  - **A fixed channel, or ALL** - use it, and nothing further is needed.
     *  - **OFF** - no channel will work at all; [select] says so rather than pretending.
     *  - **The DIP switches** - the instrument knows and will not say. That is what [setup] is
     *    for: ask the user. Falling back to a configured default here is precisely the silent
     *    failure this whole path exists to avoid.
     *
     * A settings read that fails outright keeps the configured fallback: not knowing the mode is
     * different from knowing it is unknowable, and a wrong-channel select is no worse there than
     * refusing to send at all.
     */
    private suspend fun resolveSendChannel() {
        val settings = runProbe {
            val reply = exchange.exchange(
                Pro800SysEx.requestSettings(),
                what = "reading the instrument's settings",
            ) { message ->
                Pro800SysEx.typeOf(message) == Pro800SysEx.TYPE_DUMP &&
                    Pro800SysEx.addressOf(message) == Pro800SysEx.SETTINGS_ADDRESS
            }
            Pro800Settings.fromEncoded(Pro800SysEx.dumpPayload(reply))
        } ?: return

        midiRxDescription = settings.midiRxDescription
        val reported = settings.sendChannel
        awaitingChannelChoice = !settings.midiReceiveDisabled && reported == null
        sendChannel = when {
            settings.midiReceiveDisabled -> null
            reported != null -> reported
            else -> null // DIP switches: unanswerable from here, so [setup] asks
        }
        Log.i(
            TAG,
            "$deviceName receives on $midiRxDescription; sending selection on " +
                (sendChannel?.let { "MIDI channel ${it + 1}" } ?: "no channel - selection disabled"),
        )
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
     */
    override fun index(): Flow<IndexUpdate> = flow {
        val batch = mutableListOf<PresetSlot>()
        val total = layout.slotCount
        var done = 0

        for (address in layout.allAddresses()) {
            val displayId = layout.format.format(address)
            try {
                batch += readSlot(address, displayId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
            }
            done++
            if (batch.size >= BATCH_SIZE || done == total) {
                emit(IndexUpdate.Progress(done, total, "Reading $displayId"))
                emit(IndexUpdate.Slots(batch.toList()))
                batch.clear()
            }
        }
        emit(IndexUpdate.Complete)
    }

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
     * Bank select (CC 0) then program change - two plain channel-voice messages, exactly the
     * (bank, slot) pair the address already carries.
     *
     * Goes out through `tell` because **nothing answers either message**: no status, no echo,
     * nothing to correlate. Taking the same lock is what keeps a select issued during a
     * 400-preset scan from landing in the middle of a dump.
     */
    override suspend fun select(address: SlotAddress) {
        val channel = sendChannel ?: throw InstrumentException.NotSupported(
            if (awaitingChannelChoice) {
                "load presets until you say which MIDI channel its DIP switches are set to"
            } else {
                "load presets while its MIDI receive channel is $midiRxDescription"
            },
        )
        exchange.tell(Pro800SysEx.bankSelect(channel, address.bank))
        exchange.tell(Pro800SysEx.programChange(channel, address.slot))
    }

    /**
     * "Sent", not "Selected".
     *
     * The instrument does not acknowledge a program change, so the app genuinely does not know
     * whether it landed - and if the instrument's MIDI RX channel does not match
     * [Pro800Config.midiChannel], it did not. Claiming otherwise would be the app inventing a
     * confirmation nothing gave it. Contrast `NordInstrument`, where the reply echoes the address
     * back and the call verifies it.
     */
    override fun confirmationFor(displayId: String) = "Sent $displayId."

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
     * **Fire-and-forget, deliberately.** The instrument *does* answer a write, with a
     * [Pro800SysEx.TYPE_STATUS] carrying the OK code, for both a store and an erase. It is still
     * not waited for: the reference implementation sends these with a 20 ms
     * pause and never inspects a reply, and a status byte is the weaker check anyway. Proof that
     * the write landed comes from reading the address back, which [Pro800Editor] does after every
     * single write, and that is what catches a write accepted and silently not stored. The
     * unconsumed status message is therefore real traffic rather than a hypothetical, and it
     * cannot be mistaken for anything, since every matcher here checks a type and an address.
     *
     * This is the one method in this class that changes the instrument. It is not reachable from
     * the UI except through [editor], which warns first.
     */
    override suspend fun write(address: SlotAddress, blob: ByteArray) {
        val programNumber = programNumberOf(address)
        exchange.tell(Pro800SysEx.writeDump(programNumber, blob))
    }

    /**
     * **A write's status is deliberately not waited for, and needs no machinery to be seen.**
     *
     * A `0x78` write *is* answered - `01 00 00` for a store and an erase alike - but nothing here
     * waits for it. Waiting would cost a round trip on every write and would have to be built on an
     * exchange that retries by *re-sending*, so a slow status would mean the write went twice.
     * Read-back stays the proof that a write landed: it is the stronger check, being the only one
     * that catches a write accepted and silently not stored.
     *
     * The status is still visible: it arrives when **no exchange is in flight**, so
     * [SysExExchange]'s "ignoring" log - which only runs inside an exchange - never sees it, and
     * with `replay = 0` a message with no subscriber would otherwise be emitted to nobody and gone.
     * [SysExExchange] logs messages that arrive with no subscriber, which is precisely this case,
     * so the code lands in the log next to the read-back that explains what the user saw - without
     * this class collecting anything.
     *
     * A status carries no address, so it could not be correlated to a particular write in any case.
     * Where a code *is* actionable is on the read path, which treats a refusal as an answer - see
     * [read].
     */
    override val fileExtension = "syx"

    /**
     * Flattens an address into the instrument's own program number.
     *
     * Bounded against [Pro800SysEx.SETTINGS_ADDRESS] as well as the program count: the settings
     * block lives in the same address space at 510, and a browsable row that overwrites global
     * settings is not a preset (design section 7.4).
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
    }
}
