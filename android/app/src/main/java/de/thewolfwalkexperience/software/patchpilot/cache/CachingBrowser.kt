package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [PresetBrowser] that answers from [PresetIndexCache] when it can.
 *
 * **A decorator, so that no instrument family knows this exists.** `NordInstrument`,
 * `Pro800Instrument`, `MotifXsInstrument` and anything added later are untouched, and a new family
 * gets caching by being wrapped rather than by implementing anything. That is the whole
 * extensibility argument: caching is a property of *browsing*, which every instrument does by
 * definition — `Instrument.browser` is non-null because "every instrument lists presets; that is
 * what makes it one".
 *
 * ### The rule
 *
 * A cached index is one that **finished cleanly**. On a hit, the cached slots are replayed and the
 * flow completes without a single round trip; on a miss, the delegate runs exactly as before and
 * the result is cached only if it reached [IndexUpdate.Complete] with no [IndexUpdate.Failed].
 *
 * Two things that rule buys, both of which a subtler design got wrong first:
 *
 * - **It never assumes which addresses the delegate walks.** A Motif XS indexes only the banks
 *   marked `indexByDefault`, not its whole layout, so a decorator that diffed the cache against
 *   `SlotLayout.allAddresses()` would decide 1,217 factory voices were "missing" and read them.
 * - **A run with a failed address is not cached at all.** Today a transient read failure is
 *   retried on the next reconnect; caching a partial run would freeze that failure in place for
 *   the rest of the session, and a missing address is indistinguishable from an empty slot
 *   (`PresetSlot.isEmpty` is `name == null`). Discarding the whole run is blunter than tracking
 *   which address failed, and it fails in the safe direction.
 */
class CachingBrowser(
    private val delegate: PresetBrowser,
    private val cache: PresetIndexCache,
    private val key: CacheKey,
) : PresetBrowser {

    override fun index(): Flow<IndexUpdate> = flow {
        cache.get(key)?.let { cached ->
            emit(IndexUpdate.Slots(cached))
            emit(IndexUpdate.Complete)
            return@flow
        }

        val collected = mutableListOf<PresetSlot>()
        var anyFailed = false
        var completed = false

        delegate.index().collect { update ->
            when (update) {
                is IndexUpdate.Slots -> collected += update.slots
                is IndexUpdate.Failed -> anyFailed = true
                is IndexUpdate.Complete -> completed = true
                is IndexUpdate.Progress -> Unit
            }
            emit(update)
        }

        // Cancellation throws out of collect above, so an interrupted scan never reaches here -
        // which is the intended behaviour and not an accident worth relying on silently.
        if (completed && !anyFailed) cache.put(key, collected)
    }

    /**
     * Re-reads one slot and writes it through to the cache.
     *
     * This is the whole of "a preset the app writes is dumped again to refresh its entry": the
     * facet already existed for exactly this purpose, so an edit needs no new concept, only a
     * caller that refreshes **every** address it touched. Move and swap touch two.
     */
    override suspend fun refresh(address: SlotAddress): PresetSlot =
        delegate.refresh(address).also { cache.update(key, it) }
}
