// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [PresetBrowser] that answers from [PresetIndexCache] when it can. A decorator, so no
 * instrument family knows caching exists.
 *
 * A cached index is one that finished: on a miss the delegate runs unchanged and the result is
 * cached if it reached [IndexUpdate.Complete] with no edit written through while it ran (a run an
 * edit overlapped may hold the row from before the edit), together with the addresses it could
 * not read. On a hit the cached slots are replayed without a round trip, and only those addresses
 * are read again: one slot that will not read would otherwise make every reconnect read the whole
 * instrument again, 93 s on a Motif XS. An address still unreadable is reported as such, never
 * left out, since a missing row reads as an empty slot (`PresetSlot.isEmpty` is `name == null`).
 */
class CachingBrowser(
    private val delegate: PresetBrowser,
    private val cache: PresetIndexCache,
    private val key: CacheKey,
) : PresetBrowser {

    /** The delegate's: inheriting the default would hide every scope but the user one. */
    override val scopes: List<PresetScope> get() = delegate.scopes

    /** The delegate's: inheriting the default would drop the family's rows from the Info dialog. */
    override fun details(address: SlotAddress): List<InfoRow> = delegate.details(address)

    override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
        // Only the user listing is cached ([CacheKey] carries no scope). A factory listing costs
        // no round trips, and a favorites listing is exactly what changes behind the app's back,
        // from the instrument's own panel.
        if (scope != PresetScope.USER) {
            delegate.index(scope).collect { emit(it) }
            return@flow
        }

        cache.get(key)?.let { cached ->
            emit(IndexUpdate.Slots(cached))
            for (address in cache.unreadable(key)) {
                try {
                    // Through [refresh], which writes a slot that reads now through to the cache.
                    emit(IndexUpdate.Slots(listOf(refresh(address))))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(IndexUpdate.Failed(address, e.message ?: "unreadable"))
                }
            }
            emit(IndexUpdate.Complete)
            return@flow
        }

        val collected = mutableListOf<PresetSlot>()
        val failed = mutableSetOf<SlotAddress>()
        var completed = false
        // Read before the first request, so an edit written through mid-listing is seen.
        val version = cache.version(key)

        delegate.index(scope).collect { update ->
            when (update) {
                is IndexUpdate.Slots -> collected += update.slots
                is IndexUpdate.Failed -> failed += update.address
                is IndexUpdate.Complete -> completed = true
                is IndexUpdate.Progress -> Unit
            }
            emit(update)
        }

        // Cancellation throws out of collect above, so an interrupted scan never reaches here.
        if (completed) cache.putIfUnchanged(key, collected, version, unreadable = failed)
    }

    /** Re-reads one slot and writes it through to the cache. A caller refreshes every address an edit touched; move and swap touch two. */
    override suspend fun refresh(address: SlotAddress): PresetSlot =
        delegate.refresh(address).also { cache.update(key, it) }
}
