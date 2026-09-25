// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A [PresetBrowser] that answers from [PresetIndexCache] when it can. A decorator, so no
 * instrument family knows caching exists.
 *
 * A cached index is one that finished cleanly: on a hit the cached slots are replayed without a
 * round trip; on a miss the delegate runs unchanged and the result is cached only if it reached
 * [IndexUpdate.Complete] with no [IndexUpdate.Failed]. A partial run is not cached, because a
 * missing address is indistinguishable from an empty slot (`PresetSlot.isEmpty` is
 * `name == null`), and a transient failure is retried on the next reconnect instead.
 */
class CachingBrowser(
    private val delegate: PresetBrowser,
    private val cache: PresetIndexCache,
    private val key: CacheKey,
) : PresetBrowser {

    /** The delegate's: inheriting the default would hide every scope but the user one. */
    override val scopes: List<PresetScope> get() = delegate.scopes

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
            emit(IndexUpdate.Complete)
            return@flow
        }

        val collected = mutableListOf<PresetSlot>()
        var anyFailed = false
        var completed = false

        delegate.index(scope).collect { update ->
            when (update) {
                is IndexUpdate.Slots -> collected += update.slots
                is IndexUpdate.Failed -> anyFailed = true
                is IndexUpdate.Complete -> completed = true
                is IndexUpdate.Progress -> Unit
            }
            emit(update)
        }

        // Cancellation throws out of collect above, so an interrupted scan never reaches here.
        if (completed && !anyFailed) cache.put(key, collected)
    }

    /** Re-reads one slot and writes it through to the cache. A caller refreshes every address an edit touched; move and swap touch two. */
    override suspend fun refresh(address: SlotAddress): PresetSlot =
        delegate.refresh(address).also { cache.update(key, it) }
}
