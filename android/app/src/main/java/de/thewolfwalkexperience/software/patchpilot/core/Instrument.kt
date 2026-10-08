// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What the UI talks to, whatever is plugged in.
 *
 * Facets are nullable, and null means "this instrument cannot do this at all": the UI checks
 * for a facet's presence, and there is no separate list of capability flags that could drift
 * from what is implemented.
 *
 * The abstraction sits at the operation level, not the wire level. A Nord's CRC-framed bulk
 * protocol and a Pro-800's SysEx stream share nothing below "banks of named presets", so there
 * is no shared message type or framing under here.
 */
interface Instrument {
    val identity: InstrumentIdentity

    /** The program space the browser screen shows - see [SlotLayout]'s note on categories. */
    val layout: SlotLayout

    /** Every instrument lists presets; that is what makes it one. */
    val browser: PresetBrowser

    val selector: PresetSelector?
    val editor: PresetEditor?
    val transfer: PresetTransfer?
    val report: DeviceReporter?

    /**
     * Categories and favorites - see [PresetTagger]. Null where the family does neither.
     *
     * Abstract rather than defaulted to null, so every family has to decide.
     */
    val tagger: PresetTagger?

    /** Checks the factory names this app ships against the instrument - see [FactoryNameCheck]. */
    val factoryNames: FactoryNameCheck? get() = null

    /**
     * Something the user must be told before using this instrument, or null where all is well.
     *
     * Set during [connect] by a family that can talk to the device but cannot vouch for it -
     * a firmware version this app has not been tested against. A warning rather than a refusal,
     * because refusing would lock out the one person able to report what that firmware does.
     *
     * Not an error channel: a facet that cannot work is null, and a failure that makes the
     * session useless throws. The screens gate on this once, then keep it visible.
     */
    val advisory: String? get() = null

    /**
     * Things worth knowing about how this app works with this instrument, shown when a session
     * connects, each until the user hides it for good. Unlike [advisory], nothing is wrong: an
     * Axe-Fx III edit loads the preset it writes, which the user should hear about once rather
     * than in every dialog that can cause it.
     */
    val notices: List<InstrumentNotice> get() = emptyList()

    /**
     * Whether returning to the foreground should rebuild this session - see
     * `Transport.rebuildOnResume`, which each family delegates to.
     *
     * Declared here because `MainActivity.onResume` takes the decision and cannot reach the
     * transport. Defaults to true: rebuilding a working session costs a reconnect, while failing
     * to rebuild a dead one leaves the app unusable until the user backs out by hand.
     */
    val rebuildOnResume: Boolean get() = true

    /** Handshake, version check, whatever this family needs before its facets can be used. */
    suspend fun connect()

    /** Raw transport teardown. Operations are responsible for undoing their own device state. */
    fun close()
}

/**
 * Who we are connected to, for display and for keying anything cached per instrument.
 *
 * [stableKey] must identify the *physical* instrument as well as this app can manage - it is what
 * a preset index cache would be keyed on, so two different instruments of the same model must not
 * collide, and the same one must not look new after a reconnect.
 */
data class InstrumentIdentity(
    val descriptorId: String,
    val family: String,
    val name: String,
    val firmwareVersion: String,
    /** Which bus this session is actually running over. */
    val bus: Bus,
    val stableKey: String,
) {
    /** Opened on a guess at its family's protocol, with no catalog entry - see `UnknownDevicePolicy`. */
    val isGuessed: Boolean get() = descriptorId == UNKNOWN_DESCRIPTOR_ID

    companion object {
        /** The [descriptorId] of an instrument the catalog does not list. */
        const val UNKNOWN_DESCRIPTOR_ID = "unknown"
    }
}

/**
 * Compares the factory preset names this app ships with what the instrument itself reports - the
 * only way to catch a wrongly transcribed name, which renders exactly like a right one. Read-only.
 * Offered where the app ships factory names (a Motif XS); null everywhere else.
 */
interface FactoryNameCheck {
    /** The banks that can be checked, as (bank index, display label). */
    val banks: List<Pair<Int, String>>

    /** Reads [bank] off the instrument and returns one line per disagreement; empty where all agree. */
    suspend fun verify(bank: Int, progress: (String) -> Unit): List<String>
}

/**
 * One [Instrument.notices] entry. [id] is what a hidden notice is remembered by, so it must stay
 * the same across releases for as long as the text means the same thing.
 */
data class InstrumentNotice(
    val id: String,
    val title: String,
    val message: String,
)

// ---- Facets ----

/**
 * Which part of an instrument's stored content a listing covers.
 *
 * [USER] is what every instrument has and what the browser opens on. The other two are separate
 * listings, not filters over the first: a factory listing may be free where a user one costs
 * minutes, and a favorites listing is a sparse set spanning both.
 *
 * A closed enum: the screens that render these name them, so an open set would only move the
 * exhaustiveness check from the compiler to a runtime `when`.
 */
enum class PresetScope { USER, FACTORY, FAVORITES }

/**
 * Lists what is stored on the instrument.
 *
 * A [Flow] of incremental updates rather than a suspending call returning a list: a Nord emits
 * one batch and completes, but a Pro-800 can only learn a preset's name by dumping the whole
 * preset, so its listing is 400 sequential round trips that have to show rows as they arrive.
 */
interface PresetBrowser {
    /**
     * The listings this instrument can produce, in the order a selector should offer them -
     * [PresetScope.USER] first. Defaulted, so a family with only user presets gets no selector.
     */
    val scopes: List<PresetScope> get() = listOf(PresetScope.USER)

    /**
     * Lists [scope], which is always one of [scopes].
     *
     * One abstract method taking a scope: a no-arg overload with a default body would let a
     * decorator override only that one and serve the user listing for every scope. The
     * parameter's default only saves callers that mean the user listing from saying so.
     */
    fun index(scope: PresetScope = PresetScope.USER): Flow<IndexUpdate>

    /** One slot, re-read after an edit, so the UI need not rebuild the whole index. */
    suspend fun refresh(address: SlotAddress): PresetSlot
}

sealed interface IndexUpdate {
    /** How far along a slow scan is. [label] is for display, e.g. "Reading A17". */
    data class Progress(val done: Int, val total: Int, val label: String) : IndexUpdate

    /** A batch of slots, in device order. Emitted as they are learned. */
    data class Slots(val slots: List<PresetSlot>) : IndexUpdate

    /**
     * One address could not be read. Not fatal: a single unreadable slot out of 400 must not lose
     * the other 399 - the same rule [Probes] applies to a device report.
     */
    data class Failed(val address: SlotAddress, val reason: String) : IndexUpdate

    data object Complete : IndexUpdate
}

/** How many timeouts in a row end an [indexWalk]: one or two are a slot, three are a silent instrument. */
internal const val SILENT_SLOTS_BEFORE_GIVING_UP = 3

/**
 * The sequential read-every-slot walk, for the families that have no directory to read instead
 * (Pro-800, Motif XS, Axe-Fx III). Rows are emitted as they arrive, one unreadable slot costs only
 * itself, and the final partial batch is always flushed.
 *
 * @param batchSize per family: 25 slots on a Pro-800, 8 on a Motif XS, whose reads are an order
 *   of magnitude larger, and 32 on an Axe-Fx III, whose name queries take about a millisecond.
 * @param onFailure lets a family log the cause; the emitted [IndexUpdate.Failed] carries only a
 *   reason string.
 * @param addresses walked in order; [total] is stated separately so a [Sequence] need not be
 *   counted twice.
 * @param readSlot may throw. A failure becomes an [IndexUpdate.Failed] and the walk goes on, with
 *   two exceptions that end it, keeping the rows already read: [InstrumentException.TransportLost],
 *   and [SILENT_SLOTS_BEFORE_GIVING_UP] timeouts in a row, since an instrument that has gone
 *   silent would otherwise cost the full timeout on every remaining slot. Cancellation is always
 *   propagated.
 */
fun indexWalk(
    addresses: Iterable<SlotAddress>,
    total: Int,
    batchSize: Int,
    layout: SlotLayout,
    onFailure: (displayId: String, cause: Throwable) -> Unit = { _, _ -> },
    readSlot: suspend (SlotAddress, String) -> PresetSlot,
): Flow<IndexUpdate> = flow {
    val batch = mutableListOf<PresetSlot>()
    var done = 0
    var silentInARow = 0

    for (address in addresses) {
        val displayId = layout.format.format(address)
        try {
            batch += readSlot(address, displayId)
            silentInARow = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: InstrumentException.TransportLost) {
            if (batch.isNotEmpty()) emit(IndexUpdate.Slots(batch.toList()))
            throw e
        } catch (e: Exception) {
            onFailure(displayId, e)
            emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
            silentInARow = if (e is InstrumentException.Timeout) silentInARow + 1 else 0
            if (silentInARow >= SILENT_SLOTS_BEFORE_GIVING_UP) {
                if (batch.isNotEmpty()) emit(IndexUpdate.Slots(batch.toList()))
                throw InstrumentException.Timeout(
                    "listing presets",
                    e,
                    explanation = "The instrument stopped answering: $silentInARow presets in a row went " +
                        "unanswered. Check the cable and the instrument's MIDI settings, then refresh the list.",
                )
            }
        }
        done++
        // `done == total` flushes a short final batch.
        if (batch.size >= batchSize || done == total) {
            emit(IndexUpdate.Progress(done, total, "Reading $displayId"))
            emit(IndexUpdate.Slots(batch.toList()))
            batch.clear()
        }
    }
    emit(IndexUpdate.Complete)
}

/**
 * One row in the browser.
 *
 * [displayId] and [bankLabel] are both carried rather than derived, so the UI never parses an id.
 */
data class PresetSlot(
    val address: SlotAddress,
    val displayId: String,
    val bankLabel: String,
    /** null for an empty or uninitialized slot. */
    val name: String?,
    /** Short family-supplied strings the UI renders uniformly, e.g. a preset version. */
    val badges: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = name == null
}

/** Loads a preset on the instrument. */
interface PresetSelector {
    suspend fun select(address: SlotAddress)

    /**
     * What to tell the user after [select] returns.
     *
     * The default states the selection as a fact, for a family that verifies it before returning
     * (a Nord checks the echoed address, a Pro-800 reads its pointer back). Overridable because
     * what a family can honestly claim is a property of its wire protocol: an Axe-Fx III whose
     * loaded-preset query goes unanswered says it asked.
     */
    fun confirmationFor(displayId: String): String = "Selected $displayId."
}

enum class EditOp { RENAME, MOVE, SWAP, DELETE, COPY }

/**
 * Rearranges and renames what is stored.
 *
 * [supported] and [isEmulated] exist because the same operations are device primitives on one
 * family and host-composed sequences on another: a Nord rename either happens or does not, while
 * a Pro-800 move is write-destination then erase-source, which can fail halfway. The UI says so
 * before it runs one.
 */
interface PresetEditor {
    val supported: Set<EditOp>

    /**
     * The longest name this instrument will store, or null where no limit is known. The rename
     * dialog caps its input at this, since a Pro-800 or a Nord silently truncates a longer name.
     */
    val maxNameLength: Int? get() = null

    /**
     * Why [name] cannot be stored as a preset name, or null where it can. The rename dialog asks
     * as the user types, so a name the instrument would refuse or change is caught before anything
     * is sent; [rename] checks again for itself. The default is [printableAsciiNameProblem]; a
     * family with a further rule, such as a name it reserves for an empty slot, adds it.
     */
    fun nameProblem(name: String): String? = printableAsciiNameProblem(name, maxNameLength)

    /** True where [op] is composed host-side from reads and writes rather than being one call. */
    fun isEmulated(op: EditOp): Boolean

    /** Stores [newName] as given: the caller trims it, as the rename dialog does. */
    suspend fun rename(address: SlotAddress, newName: String)
    suspend fun move(from: SlotAddress, to: SlotAddress)
    suspend fun swap(a: SlotAddress, b: SlotAddress)
    suspend fun delete(address: SlotAddress)

    /**
     * Duplicates [src] into the empty slot [dst], leaving [src] where it is, and returns the name
     * the instrument gave the copy - a Nord appends a disambiguating number ("Synth Strings" ->
     * "Synth Strings 2"), so only a read-back knows what was stored.
     *
     * The only edit with a default implementation: a family that offers it declares
     * [EditOp.COPY] and overrides this; one that does not inherits the refusal.
     */
    suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String =
        throw InstrumentException.NotSupported("copy a preset")
}

/**
 * The name rule every family shares: not blank, only printable ASCII (`0x20`-`0x7E`, the range
 * every family's name field stores and reads back unchanged), and no longer than [maxLength] where
 * one is known.
 */
fun printableAsciiNameProblem(name: String, maxLength: Int?): String? {
    if (name.isBlank()) return "A preset name cannot be blank."
    name.firstOrNull { it.code !in 0x20..0x7E }?.let { char ->
        return if (char.isISOControl()) {
            "A preset name cannot hold control characters."
        } else {
            "\u201c$char\u201d cannot be stored: a preset name can hold only plain ASCII letters, " +
                "digits, spaces and punctuation."
        }
    }
    if (maxLength != null && name.length > maxLength) {
        return "A preset name can be at most $maxLength characters."
    }
    return null
}

/**
 * Reads one preset's own data blob, as the instrument stores it. The regression test checks it,
 * and the Pro-800's browser is built on it.
 *
 * The Pro-800, the Axe-Fx III and the Subsequent 37 implement it: a whole-preset read is how each
 * of them is read at all. The Nord and the Motif XS return null, having no item-data read path.
 * There is no write here: the app has no backup or restore, and each family's editor writes
 * through its own device operations.
 */
interface PresetTransfer {
    suspend fun read(address: SlotAddress): ByteArray
}

/**
 * A built device report: the JSON to share, and what could not be read while building it.
 *
 * [failures] repeats the report's own failure map ([Probes.failures]) so a screen can say the
 * report is incomplete without parsing family-specific JSON.
 */
data class DeviceReportResult(val json: String, val failures: Map<String, String>) {
    val isComplete: Boolean get() = failures.isEmpty()
}

/** Everything the app can read off this instrument without changing anything on it, as JSON. */
interface DeviceReporter {
    suspend fun buildReport(progress: ((String) -> Unit)? = null): DeviceReportResult

    /** A filename stem for the shared file, e.g. "nord_grand". */
    fun suggestedFilename(): String

    /**
     * What the confirmation dialog tells the user this will do - supplied by the reporter, since
     * the families read different things.
     */
    val description: String
}
