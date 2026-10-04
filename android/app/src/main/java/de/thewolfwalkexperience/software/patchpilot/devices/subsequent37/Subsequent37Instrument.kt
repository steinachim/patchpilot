// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.Bus
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReporter
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentIdentity
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetEditor
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSelector
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTagger
import de.thewolfwalkexperience.software.patchpilot.core.PresetTransfer
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.SlotLayout
import de.thewolfwalkexperience.software.patchpilot.core.UNKNOWN_FIRMWARE
import de.thewolfwalkexperience.software.patchpilot.core.UNNAMED_PRESET
import de.thewolfwalkexperience.software.patchpilot.core.queryOrNull
import de.thewolfwalkexperience.software.patchpilot.core.untestedFirmwareAdvisory
import de.thewolfwalkexperience.software.patchpilot.midi.SysExExchange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val TAG = "Subsequent37Instrument"

/**
 * The Moog Subsequent 37 over class-compliant USB-MIDI SysEx, firmware 1.2.0 as measured.
 * Browsing, selection and the raw program transfer live here; [Subsequent37Editor] composes the
 * edits and [Subsequent37Tagger] the category.
 *
 * **Nothing here is heard.** A program is read with `06 01`, which names its slot and selects
 * nothing, and written with `05 01`, which is not acknowledged and is proved by reading the slot
 * back. Only [select] sends a Program Change.
 *
 * **The listing is one 142-second message.** The instrument sends all 256 programs in one reply
 * and answers nothing else until it ends, so a listing holds [lock] throughout and every other
 * operation refuses at once ([requireIdle]) rather than queueing behind it for minutes. Programs
 * are decoded and shown while the reply still arrives, where the platform hands it over in
 * pieces.
 *
 * **One lock for every sequence**, as on the Axe-Fx III: each public method holds [lock] for its
 * whole sequence. Internal helpers that send anything take no lock and may only be called while
 * it is held.
 */
class Subsequent37Instrument(
    private val exchange: SysExExchange,
    private val config: Subsequent37Config,
    /** What a delete writes: the panel's own Initialize; see `Subsequent37Family`. */
    internal val blank: Subsequent37Program,
    /** The catalog entry's id and name; defaulted so the JVM tests can build this with no catalog. */
    private val descriptorId: String = "moog_subsequent37",
    private val catalogName: String = "Moog Subsequent 37",
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    verifyPause: Duration = Subsequent37Device.VERIFY_PAUSE,
) : Instrument, PresetBrowser, PresetSelector, PresetTransfer {

    internal val device = Subsequent37Device(exchange, verifyPause)
    internal val lock = Mutex()

    override val layout: SlotLayout = Subsequent37AddressFormat.layout

    private var firmware: String = UNKNOWN_FIRMWARE

    /** Read at [connect], and again when a selection goes unacknowledged. */
    internal var settings: Subsequent37Settings? = null
        private set

    /** Set while a listing runs; see [requireIdle]. */
    @Volatile
    private var listing = false

    /** How long the running listing's transfer still takes, where that is known. */
    @Volatile
    private var listingTimeLeft: Duration? = null

    override val identity: InstrumentIdentity
        get() = InstrumentIdentity(
            descriptorId = descriptorId,
            family = FAMILY,
            name = catalogName,
            firmwareVersion = firmware,
            bus = Bus.MIDI,
            stableKey = "$FAMILY:$descriptorId",
        )

    override val rebuildOnResume: Boolean get() = device.rebuildOnResume

    override val browser: PresetBrowser get() = this
    override val selector: PresetSelector get() = this
    override val transfer: PresetTransfer get() = this
    override val editor: PresetEditor = Subsequent37Editor(this)
    override val tagger: PresetTagger = Subsequent37Tagger(this)
    override val report: DeviceReporter = Subsequent37Reporter(this, timeSource)

    /** Set by [connect] for an untested firmware; see [Instrument.advisory]. */
    override var advisory: String? = null
        private set

    override val notices: List<InstrumentNotice> = listOf(
        InstrumentNotice(
            id = SLOW_LISTING_NOTICE,
            title = "Listing presets takes about 2½ minutes",
            message = "The Subsequent 37 sends all 256 presets in one transfer of about two and " +
                "a half minutes, the first time Patch Pilot lists them in a run. Until it is " +
                "done its MIDI button blinks red and it answers nothing else, so loading or " +
                "editing a preset has to wait; leave its panel alone meanwhile. If Patch Pilot " +
                "cannot find the Subsequent 37 right after an interrupted listing, wait until " +
                "the MIDI button stops blinking.",
        ),
        InstrumentNotice(
            id = INIT_PRESET_NOTICE,
            title = "Presets named Init Preset count as empty",
            message = "Patch Pilot lists a preset named “Init Preset” as an empty " +
                "slot, and copying or moving a preset may write over it. Deleting a preset " +
                "writes the Init Preset the Subsequent 37's own Initialize makes. Rename a " +
                "preset named Init Preset if you want to keep it.",
        ),
    )

    /**
     * The firmware version, from the standard Identity Reply, and the settings block, whose IN
     * channel a selection is sent on. Neither stops a session.
     */
    override suspend fun connect() {
        lock.withLock {
            firmware = queryOrNull(TAG) { Subsequent37SysEx.decodeFirmware(device.identityReply()) } ?: UNKNOWN_FIRMWARE
            settings = queryOrNull(TAG) { device.settings() }
            advisory = advisoryText()
            Log.i(TAG, "$catalogName, firmware $firmware, IN channel ${settings?.inChannelLabel ?: "unknown"}")
        }
    }

    /** A firmware that could not be read is not evidence of one that is unsupported. */
    private fun advisoryText(): String? {
        val supported = config.supportedFirmwareVersions
        if (supported.isEmpty() || firmware == UNKNOWN_FIRMWARE || firmware in supported) return null
        return untestedFirmwareAdvisory(catalogName, firmware, supported.sorted()).also { Log.w(TAG, it) }
    }

    /** Releases the MIDI port - see [SysExExchange.close]. */
    override fun close() = exchange.close()

    // ---- PresetBrowser ----

    /**
     * Every program from one bulk dump, shown as it arrives; programs the dump did not deliver
     * whole are then read one by one. [scope] is ignored: every slot is writable, so only
     * [PresetScope.USER] is declared.
     */
    override fun index(scope: PresetScope): Flow<IndexUpdate> = channelFlow {
        lock.withLock {
            listing = true
            try {
                val decoded = receiveDump()
                fillGaps(decoded)
                send(IndexUpdate.Complete)
            } finally {
                listing = false
                listingTimeLeft = null
            }
        }
    }

    override suspend fun refresh(address: SlotAddress): PresetSlot {
        val number = numberOf(address)
        return operation { slotOf(number, device.readProgram(number)) }
    }

    /**
     * Requests the bulk dump and follows it while it arrives, emitting each program as soon as
     * its block is whole. Returns how many programs, from 0, arrived whole.
     *
     * Following needs the platform to deliver the reply in pieces, which `MidiManager` is
     * expected to do. Where it delivers the reply only once whole, nothing is pending until the
     * end: progress is then estimated from the time, and the whole reply is decoded at once.
     */
    private suspend fun ProducerScope<IndexUpdate>.receiveDump(): Int = coroutineScope {
        val decoder = Subsequent37BulkDecoder()
        val started = timeSource.markNow()
        val receivedBefore = device.receivedBytes
        // A Result, so a dump that fails does not cancel this scope before the programs that
        // did arrive are kept.
        val dump = async {
            try {
                Result.success(device.bulkDump())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        // The framer's number for the reply being followed, once its header has arrived.
        var followed: Long? = null
        var lastGrowth = started
        var goneTicks = 0
        var interrupted: String? = null

        suspend fun emit(programs: List<Subsequent37Program>) {
            if (programs.isEmpty()) return
            val first = decoder.decoded - programs.size
            programs.forEachIndexed { i, program -> device.noteRead(first + i, program) }
            send(IndexUpdate.Slots(programs.mapIndexed { i, program -> slotOf(first + i, program) }))
        }

        while (withTimeoutOrNull(TICK) { dump.join() } == null) {
            val pending = device.pending(if (followed == null) 0 else decoder.received)
            when {
                followed == null -> {
                    if (Subsequent37SysEx.isBulkDumpReply(pending.bytes)) {
                        followed = pending.start
                        lastGrowth = timeSource.markNow()
                        emit(decoder.feed(pending.bytes))
                    }
                }
                // Another message began, or this one ended without the exchange completing (a
                // channel message inside it aborts it). An ending looks the same for an instant,
                // so it has two ticks to show up as the exchange's result.
                pending.start != followed -> interrupted = "another message replaced it"
                pending.size == 0 -> if (++goneTicks >= 2) interrupted = "it broke off"
                pending.bytes.isNotEmpty() -> {
                    lastGrowth = timeSource.markNow()
                    goneTicks = 0
                    emit(decoder.feed(pending.bytes))
                }
                lastGrowth.elapsedNow() >= STALL -> interrupted = "it stopped arriving"
            }
            if (interrupted != null) break
            send(dumpProgress(decoder, followed != null, started, device.receivedBytes - receivedBefore))
        }

        if (interrupted != null) {
            Log.w(TAG, "The preset list did not arrive whole: $interrupted, after ${decoder.decoded} programs")
            dump.cancel()
            awaitQuiet()
        } else {
            val message = dump.await().getOrElse { e ->
                if (decoder.decoded == 0) throw e
                Log.w(TAG, "The preset list ended early, after ${decoder.decoded} programs", e)
                null
            }
            if (message != null) {
                emit(decoder.feed(message.copyOfRange(minOf(decoder.received, message.size), message.size)))
                decoder.problemAtEnd(message.size)?.let { Log.w(TAG, "Preset list: $it") }
            }
        }
        decoder.decoded
    }

    /** Progress through the dump: exact while it is followed, estimated from the time where not. */
    private fun dumpProgress(
        decoder: Subsequent37BulkDecoder,
        following: Boolean,
        started: ComparableTimeMark,
        receivedSince: Long,
    ): IndexUpdate.Progress {
        val total = Subsequent37SysEx.PROGRAM_COUNT
        if (following) {
            val left = ((Subsequent37SysEx.FULL_BULK_DUMP_BYTES - decoder.received).coerceAtLeast(0) * 1000L / BYTES_PER_SECOND).milliseconds
            listingTimeLeft = left
            return IndexUpdate.Progress(decoder.decoded, total, "Reading presets, ${timeLeftText(left)}")
        }
        val elapsed = started.elapsedNow()
        // Bytes that arrive while nothing of the dump is pending are the tail of an earlier
        // transfer: the instrument answers this request only once that has ended.
        if (receivedSince > 0 && elapsed >= EARLIER_TRANSFER_AFTER) {
            listingTimeLeft = null
            return IndexUpdate.Progress(0, total, "The Subsequent 37 is still finishing an earlier transfer")
        }
        val left = (DUMP_DURATION - elapsed).coerceAtLeast(Duration.ZERO)
        listingTimeLeft = left
        val done = ((elapsed / DUMP_DURATION) * total).toInt().coerceIn(0, total - 1)
        return IndexUpdate.Progress(done, total, "Reading presets, ${timeLeftText(left)}")
    }

    /**
     * Waits until nothing has arrived for [QUIET], so a read sent after an interrupted dump is not
     * lost behind the rest of it: the instrument keeps sending until the dump is done.
     */
    private suspend fun ProducerScope<IndexUpdate>.awaitQuiet() {
        send(IndexUpdate.Progress(0, 1, "Waiting for the Subsequent 37 to finish sending"))
        val gaveUp = timeSource.markNow() + DUMP_DURATION + QUIET
        var last = device.receivedBytes
        var since = timeSource.markNow()
        while (since.elapsedNow() < QUIET && !gaveUp.hasPassedNow()) {
            delay(TICK)
            val now = device.receivedBytes
            if (now != last) {
                last = now
                since = timeSource.markNow()
            }
        }
    }

    /**
     * Reads, one by one, every program from [from] on - what a dump that broke off or arrived
     * damaged did not deliver. Each read is about two seconds. After [GIVE_UP_AFTER] unreadable
     * slots in a row the rest are reported failed unread: an instrument that answers nothing
     * would otherwise cost minutes of timeouts.
     */
    private suspend fun ProducerScope<IndexUpdate>.fillGaps(from: Int) {
        val total = Subsequent37SysEx.PROGRAM_COUNT
        if (from >= total) return
        Log.i(TAG, "Reading programs $from..${total - 1} one by one")
        var failedInARow = 0
        for (number in from until total) {
            val address = Subsequent37AddressFormat.addressOf(number)
            val displayId = displayIdOf(number)
            listingTimeLeft = null
            send(IndexUpdate.Progress(number, total, "Reading $displayId"))
            if (failedInARow >= GIVE_UP_AFTER) {
                send(IndexUpdate.Failed(address, "not read: the Subsequent 37 stopped answering"))
                continue
            }
            try {
                send(IndexUpdate.Slots(listOf(slotOf(number, device.readProgram(number)))))
                failedInARow = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedInARow++
                Log.w(TAG, "Couldn't read $displayId", e)
                send(IndexUpdate.Failed(address, e.message ?: "unreadable"))
            }
        }
    }

    // ---- PresetSelector ----

    /**
     * Bank select and Program Change on the IN channel the settings block names, acknowledged
     * with `36`. Unacknowledged, the settings are read again in case the channel changed on the
     * panel; still unacknowledged, the instrument is taken to ignore Program Changes.
     */
    override suspend fun select(address: SlotAddress) {
        val number = numberOf(address)
        val displayId = displayIdOf(number)
        operation {
            val channel = settings?.selectionChannel ?: 0
            if (trySelect(channel, number, displayId)) return@operation
            val fresh = queryOrNull(TAG) { device.settings() }?.also { settings = it }
            if (fresh != null && fresh.selectionChannel != channel && trySelect(fresh.selectionChannel, number, displayId)) {
                return@operation
            }
            // Assumption: silence here means RCV PGM CHG is off; the instrument says nothing.
            throw InstrumentException.NeedsManualSetting(
                message = "The Subsequent 37 did not acknowledge loading $displayId. It ignores " +
                    "Program Changes when RCV PGM CHG is off.",
                steps = listOf(
                    "Press the MIDI button to open the MIDI MENU.",
                    "Go to RCV PGM CHG (MIDI MENU 1.4) and set it to ON.",
                ),
                alsoCheck = "If it is already ON, check that no other app is using the " +
                    "Subsequent 37, then reconnect: Patch Pilot reads the IN CHANNEL " +
                    "(MIDI MENU 1.1) when it connects.",
            )
        }
    }

    private suspend fun trySelect(channel: Int, number: Int, displayId: String): Boolean = try {
        device.select(channel, number, displayId)
        true
    } catch (e: InstrumentException.Timeout) {
        Log.w(TAG, "No acknowledgement selecting $displayId on channel ${channel + 1}")
        false
    }

    // ---- PresetTransfer ----

    /** Stored program [address] as the `02 07` message the instrument sends: a `.syx` file. */
    override suspend fun read(address: SlotAddress): ByteArray {
        val number = numberOf(address)
        return operation { device.readStoredMessage(number) }
    }

    /**
     * Stores [blob] at [address], overwriting it, and reads it back. Accepts one program as a
     * single-program SysEx message (as [read] returns it, or as the vendor's files carry it) or
     * as a bare 488-byte payload, with a checksum that closes. Only the program is sent, inside
     * a write built here: never a file's own bytes.
     */
    override suspend fun write(address: SlotAddress, blob: ByteArray) {
        val payload = when (blob.size) {
            Subsequent37SysEx.PAYLOAD_BYTES -> blob.takeIf { Subsequent37SysEx.checksumCloses(it) }
            else -> Subsequent37SysEx.programOf(blob)
        } ?: throw IllegalArgumentException("This is not one Subsequent 37 preset with a checksum that closes.")
        val number = numberOf(address)
        operation { device.writeVerified(number, Subsequent37Program(payload), displayIdOf(number)) }
    }

    override val fileExtension = "syx"

    // ---- Shared by the editor, the tagger and the reporter ----

    internal fun numberOf(address: SlotAddress): Int = Subsequent37AddressFormat.numberOf(address)

    internal fun displayIdOf(number: Int): String =
        Subsequent37AddressFormat.format(Subsequent37AddressFormat.addressOf(number))

    /** A row: null name for an empty slot, the category as a badge. */
    internal fun slotOf(number: Int, program: Subsequent37Program): PresetSlot {
        val address = Subsequent37AddressFormat.addressOf(number)
        return PresetSlot(
            address = address,
            displayId = displayIdOf(number),
            bankLabel = layout.banks[address.bank].label,
            name = when {
                program.isEmpty -> null
                program.name.isEmpty() -> UNNAMED_PRESET
                else -> program.name
            },
            badges = listOfNotNull(Subsequent37Categories.nameOf(program.category)),
        )
    }

    /** Runs [block] under [lock], refusing at once while a listing runs. */
    internal suspend fun <T> operation(block: suspend () -> T): T {
        requireIdle()
        return lock.withLock { block() }
    }

    /**
     * Refuses while a listing runs: the instrument answers nothing else until its dump ends, and
     * waiting on [lock] for minutes would look like a hang.
     */
    internal fun requireIdle() {
        if (!listing) return
        val left = listingTimeLeft?.let { " (${timeLeftText(it)})" }.orEmpty()
        throw InstrumentException.BlockedByDeviceState(
            "The Subsequent 37 is still sending its preset list$left, and answers nothing else " +
                "until it is done. Try again then.",
        )
    }

    companion object {
        const val FAMILY = "subsequent37"

        const val SLOW_LISTING_NOTICE = "subsequent37.slow_listing"
        const val INIT_PRESET_NOTICE = "subsequent37.init_preset_is_empty"

        /** How the instrument transmits, measured: 203,800 bytes in about 142 s. */
        private const val BYTES_PER_SECOND = 1_435L
        private val DUMP_DURATION = 142.seconds

        private val TICK = 500.milliseconds

        /** No growth for this long, while the dump was arriving in pieces, means it stopped. */
        private val STALL = 15.seconds

        /** How long to wait before naming bytes that arrive unasked an earlier transfer. */
        private val EARLIER_TRANSFER_AFTER = 5.seconds

        /** Silence for this long means an interrupted dump has ended. */
        private val QUIET = 2.seconds

        private const val GIVE_UP_AFTER = 3

        /** "about 2 minutes left", for a progress line or a refusal. */
        internal fun timeLeftText(left: Duration): String {
            val seconds = left.inWholeSeconds
            return when {
                seconds >= 90 -> "about ${(seconds + 30) / 60} minutes left"
                seconds >= 45 -> "about a minute left"
                seconds > 0 -> "less than a minute left"
                else -> "almost done"
            }
        }
    }
}
