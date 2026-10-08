// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.cache.CacheKey
import de.thewolfwalkexperience.software.patchpilot.cache.CachingBrowser
import de.thewolfwalkexperience.software.patchpilot.cache.PresetIndexCache
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val TAG = "PresetListings"

/** Sanity cap for [PresetListings.allSlots]'s per-bank loop bound - see its own comment. */
private const val MAX_ITEMS_PER_BANK = 10_000

/**
 * The preset listings of the connected instrument, one per [PresetScope], collected here rather
 * than in the composition, so a rotation does not restart a 93-second scan. Each scope keeps its
 * own state: "Copy to..." has to know which user slots are free while the factory listing is on
 * screen, and an edit made from the favorites listing changes a row the user listing also holds.
 *
 * @param instrument the session's instrument, or null between sessions.
 * @param deviceHandle the handle of the device the session runs over - half of the cache key.
 */
internal class PresetListings(
    private val scope: CoroutineScope,
    private val instrument: () -> Instrument?,
    private val deviceHandle: () -> String?,
    private val strings: StringResolver,
) {
    private val listingsByScope = MutableStateFlow<Map<PresetScope, PresetIndexState>>(emptyMap())

    private val _browseScope = MutableStateFlow(PresetScope.USER)

    /** Which listing the browser shows. */
    val browseScope: StateFlow<PresetScope> = _browseScope.asStateFlow()

    /**
     * The listing for whichever scope is selected. `Eagerly` rather than `WhileSubscribed`: the
     * scan driving it outlives the composition, so a state that reset itself when the screen went
     * away would undo that.
     */
    val index: StateFlow<PresetIndexState> = combine(_browseScope, listingsByScope) { scope, byScope ->
        byScope[scope] ?: PresetIndexState()
    }.stateIn(scope, SharingStarted.Eagerly, PresetIndexState())

    /**
     * The user listing specifically, whatever scope is selected. A flow rather than a getter,
     * since "can this factory voice be copied, and to where?" is asked while another scope is
     * displayed and a plain read would be answered once.
     */
    val userIndex: StateFlow<PresetIndexState> = listingsByScope
        .map { it[PresetScope.USER] ?: PresetIndexState() }
        .stateIn(scope, SharingStarted.Eagerly, PresetIndexState())

    /** The listing of [scope] as it stands, or null where none has started. */
    fun listing(scope: PresetScope): PresetIndexState? = listingsByScope.value[scope]

    // One job per scope, so switching never throws a 93-second user scan away. Two listings at
    // once is safe: `SysExExchange` serialises one request/reply at a time, so a favorites read
    // started mid-scan interleaves with it and each reply is matched on its own echoed address.
    private val indexJobs = mutableMapOf<PresetScope, Job>()
    private var indexGeneration = 0

    /**
     * Parent of every listing job. A cancelled job stays among its children until its cleanup has
     * run, which [indexJobs] forgets at once - see [cancelAndJoin].
     */
    private val listingJobs = SupervisorJob(scope.coroutineContext[Job])

    /** Which instrument [listingsByScope] describes, so a *different* one still triggers a fresh scan. */
    private var indexedInstrument: String? = null

    /**
     * The last instrument actually browsed, surviving a teardown, which [indexedInstrument] does
     * not: [cancel] clears that one on every disconnect, and a resume force-reconnects, so keyed
     * on it the browser would drop out of the factory or favorites listing every time the user
     * switched apps.
     */
    private var lastBrowsedInstrument: String? = null

    /**
     * Preset listings, kept for as long as this object lives - longer than any one instrument,
     * because a resume replaces the instrument on every return to the foreground, and a Motif XS
     * would otherwise re-read 416 user voices (about 93 s) every time. Not persisted - see
     * `docs/ARCHITECTURE.md`, "Caching".
     */
    private val indexCache = PresetIndexCache()

    private fun updateIndex(scope: PresetScope, transform: (PresetIndexState) -> PresetIndexState) {
        listingsByScope.update { byScope ->
            byScope + (scope to transform(byScope[scope] ?: PresetIndexState()))
        }
    }

    /** True where [scope]'s listing has finished or failed, so re-running it would buy nothing. */
    private fun isSettled(scope: PresetScope): Boolean =
        listingsByScope.value[scope]?.let { it.complete || it.error != null } == true

    /** Switches which listing the browser shows, starting it if it has not been read yet; a finished scope costs nothing. */
    fun setScope(scope: PresetScope) {
        if (_browseScope.value == scope) return
        _browseScope.value = scope
        val key = instrument()?.identity?.stableKey ?: return
        if (isSettled(scope) || indexJobs[scope]?.isActive == true) return
        launchIndex(key, scope)
    }

    /**
     * Starts the listing unless one is running or finished for this instrument. Idempotent, since
     * both callers are safe: ProgramsScreen on first composition, and the coordinator whenever a
     * session begins, which is what covers a reconnect.
     */
    fun start() {
        val key = instrument()?.identity?.stableKey ?: return
        // A different instrument invalidates all three listings and returns the browser to the
        // user presets. Keyed on the instrument rather than the session, which a resume rebuilds.
        if (key != lastBrowsedInstrument) {
            listingsByScope.value = emptyMap()
            _browseScope.value = PresetScope.USER
        }
        lastBrowsedInstrument = key
        val scope = _browseScope.value
        if (key == indexedInstrument && (indexJobs[scope]?.isActive == true || isSettled(scope))) return
        launchIndex(key, scope)
    }

    /**
     * Set where an edit could not re-read an address it touched ([refreshEdited]), so the
     * listings may show a row nothing has confirmed. Cleared by [reloadAfterEdit].
     */
    private var editLeftListingStale = false

    /**
     * Re-collects the listing on screen after an edit, only where the edit left it unconfirmed:
     * a re-read failed, or the listing was dropped ([dropFavorites]). Otherwise [refreshEdited]
     * has already corrected every listing holding the addresses the edit touched, and collecting
     * again would re-read a listing the cache does not hold from the instrument - one with an
     * unreadable slot, say, which costs 93 s on a Motif XS.
     */
    fun reloadAfterEdit() {
        val key = instrument()?.identity?.stableKey ?: return
        val scope = _browseScope.value
        if (!editLeftListingStale && listingsByScope.value[scope] != null) return
        editLeftListingStale = false
        launchIndex(key, scope)
    }

    /**
     * Drops the cache and re-reads from the instrument - the pull-to-refresh gesture. Returns the
     * generation it started, so a caller can tell its own refresh finishing from another's; see
     * [PresetIndexState.generation].
     */
    fun refresh(): Int? {
        invalidate()
        val key = instrument()?.identity?.stableKey ?: return null
        return launchIndex(key, _browseScope.value)
    }

    private fun launchIndex(key: String, scope: PresetScope): Int {
        indexJobs.remove(scope)?.cancel()
        indexedInstrument = key
        // One counter across all scopes: a per-scope counter would let two scopes issue the same
        // number, and a refresh of one would look like the screen's own refresh landing.
        val generation = ++indexGeneration
        updateIndex(scope) { PresetIndexState(generation = generation) }
        indexJobs[scope] = this.scope.launch(listingJobs) {
            try {
                browser().index(scope).collect { update -> updateIndex(scope) { it.plus(update) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Listing $scope failed", e)
                updateIndex(scope) {
                    it.copy(error = e.message ?: strings.get(R.string.listing_unknown_error), progress = null)
                }
            }
        }
        return generation
    }

    /**
     * Drops the listing and whatever is producing it, so nothing survives into the next session.
     *
     * No fresh [PresetIndexState]: emitting one would recompose ProgramsScreen at the moment there
     * is no instrument to derive its rail and placeholder rows from. [launchIndex] resets the
     * state when the next scan starts.
     */
    fun cancel() {
        indexJobs.values.forEach { it.cancel() }
        indexJobs.clear()
        indexedInstrument = null
    }

    /**
     * [cancel], then waits until every listing has finished its cleanup, including one cancelled
     * earlier (a scope switch, a pause): a Nord's listing sends its category unlock from a
     * `NonCancellable` section, which fails if the connection is closed under it and leaves the
     * category lock set. A listing started during the wait is stopped too.
     */
    suspend fun cancelAndJoin() {
        do {
            cancel()
            listingJobs.children.toList().joinAll()
        } while (listingJobs.children.any())
    }

    /**
     * Throws away the cached listing so the next collection re-reads the instrument - what
     * pull-to-refresh calls. Only the user listing is cached (see [CachingBrowser]), so this is a
     * no-op for the other two: refreshing the favorites listing must not cost 93 seconds the next
     * time the user listing is shown.
     */
    fun invalidate() {
        if (_browseScope.value != PresetScope.USER) return
        invalidateUserCache()
    }

    /** Drops every cached listing of the device at [handle], connected or not - see [PresetIndexCache.invalidateDevice]. */
    fun forgetDevice(handle: String) = indexCache.invalidateDevice(handle)

    /**
     * Drops the cached user listing whichever scope is on screen. Separate from [invalidate]: a
     * failed re-read after an edit makes the cached user listing untrustworthy whichever listing
     * the edit was made from, and so does the device being unplugged.
     */
    fun invalidateUserCache() {
        val instrument = instrument() ?: return
        cacheKey(instrument)?.let { indexCache.invalidate(it) }
    }

    /**
     * Re-reads the slots an edit touched, so the cached listing matches the instrument - every
     * address it touched, since a move and a swap each change two. A failed re-read drops the
     * whole cached index rather than leaving a row nothing has confirmed.
     *
     * The re-read slot is written into every listing already holding that address, not only into
     * the cache: a favorited user voice sits in two listings at once, and a finished listing is
     * never re-collected. The user listing also gains an address it lacked, as a copy into an
     * empty Nord slot needs - see [PresetIndexState.withSlot].
     */
    suspend fun refreshEdited(vararg addresses: SlotAddress) {
        val browser = browser()
        for (address in addresses) {
            try {
                val slot = browser.refresh(address)
                listingsByScope.update { byScope ->
                    byScope.mapValues { (scope, state) -> state.withSlot(slot, insert = scope == PresetScope.USER) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't re-read $address after an edit; dropping the cached index", e)
                invalidateUserCache()
                editLeftListingStale = true
                return
            }
        }
    }

    /**
     * Forgets the favorites listing and stops a scan still filling it: a scan left running would
     * write rows read before the edit into the fresh state, which [setScope] would then show as
     * settled.
     */
    fun dropFavorites() {
        indexJobs.remove(PresetScope.FAVORITES)?.cancel()
        listingsByScope.update { it - PresetScope.FAVORITES }
    }

    /** Which user addresses hold a preset, and which could not be read and so may or may not. */
    data class Occupancy(val occupied: Set<SlotAddress>, val unreadable: Set<SlotAddress>)

    /**
     * The user listing's [Occupancy]. A user listing still running is waited for and read:
     * collecting the browser beside it would start a second full read behind the first, about
     * 142 s on a Subsequent 37. Otherwise the browser is collected, from the cache where a
     * finished listing left one.
     */
    suspend fun occupancy(): Occupancy {
        indexJobs[PresetScope.USER]?.join()
        val listing = listingsByScope.value[PresetScope.USER]?.takeIf { it.complete }
            ?: browser().index().fold(PresetIndexState()) { state, update -> state.plus(update) }
        return Occupancy(
            occupied = listing.slots.filterNot { it.isEmpty }.mapTo(mutableSetOf()) { it.address },
            unreadable = listing.unreadable,
        )
    }

    /**
     * Every address within the connected instrument's bank/slot range, whether or not a preset is
     * actually stored there - used by ProgramsScreen to show the gaps a device that only reports
     * occupied slots leaves behind. Empty rather than throwing while nothing is connected: it is
     * read during composition, across the reconnect a resume causes.
     */
    fun allSlots(scope: PresetScope = PresetScope.USER): List<PresetSlot> {
        val layout = instrument()?.layout ?: return emptyList()
        // A favorites listing has no address space of its own: it is a sparse set drawn from every
        // bank, so there is no address at which one is "missing". Returning the whole instrument
        // here would make "show empty slots" offer 1,633 placeholder rows for a list of eleven.
        if (scope == PresetScope.FAVORITES) return emptyList()
        val wantReadOnly = scope == PresetScope.FACTORY
        // Clamped on top of the protocol layer's own bound: a bank's capacity traces back to a
        // value the instrument reported, and this is the loop it feeds.
        return layout.banks.flatMapIndexed { bank, spec ->
            if (spec.readOnly != wantReadOnly) return@flatMapIndexed emptyList()
            (0 until spec.slotCount.coerceIn(0, MAX_ITEMS_PER_BANK)).map { slot ->
                val address = SlotAddress(bank, slot)
                PresetSlot(
                    address = address,
                    displayId = layout.format.format(address),
                    bankLabel = spec.label,
                    name = null,
                )
            }
        }
    }

    /**
     * The connected instrument's browser, wrapped so a listing can come from memory. Built per
     * call: [CachingBrowser] carries no state, and [indexCache] outlives the instrument a
     * reconnect throws away. Demo mode is not wrapped - its library is already in memory.
     */
    private fun browser(): PresetBrowser {
        val instrument = instrument() ?: error("Not connected to an instrument")
        val key = cacheKey(instrument) ?: return instrument.browser
        return CachingBrowser(instrument.browser, indexCache, key)
    }

    /**
     * The cache key for [instrument]'s listing, or null where nothing is cached: demo mode, whose
     * library is in memory already, and a session with no device handle to key on.
     */
    private fun cacheKey(instrument: Instrument): CacheKey? {
        if (instrument is DemoInstrument) return null
        return CacheKey.of(instrument, deviceHandle() ?: return null)
    }
}
