// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What the UI talks to, whatever is plugged in.
 *
 * **Facets are nullable, and null means "this instrument cannot do this at all".** The UI
 * writes `instrument.transfer?.let { ... }` and there is no second list of capability flags that
 * can drift out of sync with what is actually implemented - which is the whole reason this is not
 * a fat interface whose unsupported methods throw, and not a `Set<Capability>` beside one.
 *
 * The abstraction deliberately sits at the *operation* level, not the wire level. A Nord's
 * CRC-framed synchronous bulk protocol and a Pro-800's SysEx stream share nothing worth naming
 * below "banks of named presets", so there is no shared message type, no shared framing, and no
 * plugin ABI anywhere under here.
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
     * Abstract like the four above it rather than defaulted to null, for the reason this file
     * gives everywhere else: a facet nobody is made to look at is a facet a new family forgets it
     * could have offered.
     */
    val tagger: PresetTagger?

    /**
     * Something the user must be told before using this instrument, or null where all is well.
     *
     * **Usable but not vouched for.** Set during [connect] by a family that got far enough to talk
     * to the device and then found something it cannot stand behind - today, a firmware version
     * nobody has tested this app against. A warning rather than a refusal, because refusing would
     * lock out the one person able to report what an untested firmware actually does.
     *
     * Not an error channel: a facet that cannot work must still be null, and a failure that makes
     * the session useless must still throw. This is for "it will probably work, and you should
     * know why it might not" - the screens gate on it once, then keep it visible.
     */
    val advisory: String? get() = null

    /**
     * Whether returning to the foreground should proactively rebuild this session.
     *
     * Delegates to the transport underneath, which is the layer that knows: USB host has no
     * add/remove callback this app can rely on mid-session, and unrelated bus activity while
     * backgrounded can leave its endpoints erroring on every subsequent transfer. MIDI has
     * explicit callbacks and no such failure mode.
     *
     * **Declared here because `Transport` is not reachable from where the decision is taken.**
     * `MainActivity.onResume` is the caller, and the transport lives two layers down inside each
     * family's instrument.
     *
     * Defaults to true: rebuilding an already-working session costs a reconnect, while failing to
     * rebuild a dead one leaves the app unusable until the user backs out by hand.
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
)

// ---- Facets ----

/**
 * Which part of an instrument's stored content a listing covers.
 *
 * [USER] is what every instrument has and what the browser opens on. The other two are separate
 * *listings*, not filters over the first: a factory listing may be free where a user one costs
 * minutes, and a favorites listing is a sparse set spanning both, in an order the instrument
 * chooses rather than device order.
 *
 * **A closed enum, so a family cannot add a fourth scope without editing this file.** That is the
 * same ceiling `InstrumentRegistry` already documents as a decision rather than an oversight: the
 * three screens that render these have to name them anyway, so an open set would only move the
 * exhaustiveness check from the compiler to a runtime `when` branch nobody would remember to add.
 */
enum class PresetScope { USER, FACTORY, FAVORITES }

/**
 * Lists what is stored on the instrument.
 *
 * **A [Flow] of incremental updates rather than a suspending call returning a list.** On a
 * Nord this emits one batch and completes, costing nothing. On a Pro-800 the only way to learn a
 * preset's name is to dump the whole preset, so listing is 400 sequential round trips - and a
 * `suspend fun list(): List<PresetSlot>` would mean a spinner for the better part of a minute
 * with nothing to show for it.
 */
interface PresetBrowser {
    /**
     * The listings this instrument can produce, in the order a selector should offer them.
     *
     * A [List] rather than a [Set] because the UI renders it positionally - a segmented control
     * needs to know which item is first and which is last to shape it - and because "USER first"
     * is a contract this type can state and a set cannot.
     *
     * Defaulted, so a family with only its user presets says nothing and gets no selector.
     */
    val scopes: List<PresetScope> get() = listOf(PresetScope.USER)

    /**
     * Lists [scope], which is always one of [scopes].
     *
     * **One abstract method taking a scope, not a second overload with a default body.** An
     * `index(scope)` whose default body forwarded to a no-arg `index()` would let a decorator
     * override only the latter and then silently serve the user listing for every other scope.
     * Abstract means the compiler makes all five implementors look at it.
     *
     * The *parameter* still defaults, which is a different thing: it saves the many callers that
     * only ever mean the user listing from saying so, without giving any implementor somewhere to
     * hide. Production callers that track a selected scope pass it explicitly.
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
     * One address could not be read. Deliberately not fatal: a single unreadable slot out of 400
     * must not lose the other 399, the same rule `InstrumentViewModel.buildDeviceReport()` already
     * applies to its probes.
     */
    data class Failed(val address: SlotAddress, val reason: String) : IndexUpdate

    data object Complete : IndexUpdate
}

/**
 * The sequential read-every-slot walk, for the families that have no directory to read instead.
 *
 * A Motif XS and a Pro-800 arrive at the same shape from opposite directions - one has 1,217
 * factory voices at 12,468 B/s, the other has 400 addresses and no way to learn a name except by
 * reading the whole preset - and both need the same three things: rows in front of the user as
 * they arrive, one unreadable slot costing only itself, and progress that means something. That
 * is this loop, and having it once keeps the two families agreeing on the details that matter
 * (whether a failure aborts the walk; whether the final partial batch is emitted at all).
 *
 * What genuinely differs stays a parameter: [batchSize] is tuned per family (a Pro-800's 25 slots
 * against a Motif XS's 8, whose reads are an order of magnitude larger), and [onFailure] lets a
 * family log the cause where the emitted [IndexUpdate.Failed] only carries a count to the user.
 *
 * @param addresses walked in order; [total] is stated separately so a [Sequence] need not be
 *   counted twice.
 * @param readSlot may throw - anything but [kotlinx.coroutines.CancellationException] becomes an
 *   [IndexUpdate.Failed] and the walk goes on. Cancellation is always propagated.
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

    for (address in addresses) {
        val displayId = layout.format.format(address)
        try {
            batch += readSlot(address, displayId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure(displayId, e)
            emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
        }
        done++
        // `done == total` is what flushes the tail: without it a walk whose last batch is short
        // ends having emitted rows the UI never received.
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
 * [displayId] and [bankLabel] are both carried rather than derived, because deriving them means
 * parsing [displayId], and the UI has no business knowing an id's shape.
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
     * Every family currently verifies its own selection before returning - a Nord echoes the
     * address back, a Pro-800 reads its pointer back until it matches - so every family can state
     * it as a fact, and the default says so. It stays overridable rather than becoming a constant
     * in the screen because what a family can *honestly* claim is the family's to know: one that
     * could only fire a message off and hope would have to word this differently, and that is a
     * property of the wire protocol, not of the UI.
     */
    fun confirmationFor(displayId: String): String = "Selected $displayId."
}

enum class EditOp { RENAME, MOVE, SWAP, DELETE, COPY }

/**
 * Rearranges and renames what is stored.
 *
 * [supported] and [isEmulated] exist because the same four operations are device primitives on one
 * family and host-composed multi-step sequences on another, and the difference is not hideable:
 * a Nord rename either happens or does not, while a Pro-800 move is read-destination-write then
 * erase-source, which can fail halfway and lose a preset. The UI has to be able to say so before
 * it runs.
 */
interface PresetEditor {
    val supported: Set<EditOp>

    /**
     * The longest name this instrument will store, or null where no limit is known.
     *
     * Declared so the rename dialog can cap the input rather than letting the user type a name
     * the instrument will silently truncate: a Pro-800 asked to store a longer name keeps the
     * first sixteen characters, and the only sign is the read-back verification failing.
     */
    val maxNameLength: Int? get() = null

    /** True where [op] is composed host-side from reads and writes rather than being one call. */
    fun isEmulated(op: EditOp): Boolean

    suspend fun rename(address: SlotAddress, newName: String)
    suspend fun move(from: SlotAddress, to: SlotAddress)
    suspend fun swap(a: SlotAddress, b: SlotAddress)
    suspend fun delete(address: SlotAddress)

    /**
     * Duplicates [src] into the empty slot [dst], leaving [src] where it is, and returns the name
     * the *instrument* gave the copy.
     *
     * The only edit with a default implementation: a family that offers it declares [EditOp.COPY]
     * and overrides this; one that does not declares neither and inherits the refusal, which is
     * what `EditOp.COPY !in supported` reads as everywhere.
     *
     * The name comes back rather than being chosen by the caller because the instrument picks it:
     * a Nord appends a disambiguating number to the source's ("Synth Strings" -> "Synth Strings 2")
     * and nothing in the request carries a name at all, so the only way to know what was stored is
     * to read the destination back.
     */
    suspend fun copyProgram(src: SlotAddress, dst: SlotAddress): String =
        throw UnsupportedOperationException("This instrument cannot copy a preset.")
}

/**
 * Reads and writes a preset's own data blob - backup and restore.
 *
 * Only the Pro-800 implements it: there this *is* the listing operation, so its browser is built
 * on [read] and a "save everything" action after a completed scan costs no extra round trips.
 * The other families return null for the facet until an item-data read/write path is implemented.
 */
interface PresetTransfer {
    suspend fun read(address: SlotAddress): ByteArray
    suspend fun write(address: SlotAddress, blob: ByteArray)
    val fileExtension: String
}

/** Everything the app can read off this instrument without changing anything on it, as JSON. */
interface DeviceReporter {
    suspend fun buildReport(progress: ((String) -> Unit)? = null): String

    /** A filename stem for the shared file, e.g. "nord_grand". */
    fun suggestedFilename(): String

    /**
     * What the confirmation dialog tells the user this will do.
     *
     * Supplied by the reporter because the two families read completely different things: one
     * walks categories and measures storage areas, the other dumps 400 preset addresses, and a
     * single wording in the screen would describe one of them wrongly.
     */
    val description: String
}
