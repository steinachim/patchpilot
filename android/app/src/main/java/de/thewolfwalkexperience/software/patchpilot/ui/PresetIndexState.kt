// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot

/**
 * A preset index as it arrives, rather than only once it is whole: a Pro-800 scan is 400
 * sequential dumps, so "loading" and "has data" overlap for most of a minute, while a Nord fills
 * this in one batch and lands on [complete] immediately.
 */
data class PresetIndexState(
    val slots: List<PresetSlot> = emptyList(),
    val progress: IndexUpdate.Progress? = null,
    val complete: Boolean = false,
    /** Individual addresses that could not be read. Never fatal - the rest of the index stands. */
    val failures: List<IndexUpdate.Failed> = emptyList(),
    /** Set only when the scan itself failed, which is the one case with nothing to show. */
    val error: String? = null,
    /**
     * Which run of the index this describes, because [complete] alone cannot answer "has my
     * refresh finished?": on a fast listing (demo mode, a Nord, a cache hit) the reset and the
     * completion land in one recomposition, so a `LaunchedEffect` keyed on `complete` never sees
     * its key change.
     */
    val generation: Any? = null,
) {
    val loading: Boolean get() = !complete && error == null

    /** Folds one update in. Pure, so the collector can live in the ViewModel and a unit test can hold the rule that `Failed` is never fatal. */
    fun plus(update: IndexUpdate): PresetIndexState = when (update) {
        is IndexUpdate.Slots -> copy(slots = slots + update.slots)
        is IndexUpdate.Progress -> copy(progress = update)
        is IndexUpdate.Failed -> copy(failures = failures + update)
        IndexUpdate.Complete -> copy(complete = true, progress = null)
    }

    /**
     * This listing with [slot]'s address showing [slot], or unchanged if it does not hold it,
     * in place so a row re-read after a rename stays where it was.
     *
     * The same address can appear in more than one listing - a favorited user voice is in both -
     * and an edit made from either has to correct both, while the cache write-through only
     * reaches whichever is collected next. A no-op for an absent address rather than an
     * insertion: a listing is the set of slots a scan found.
     */
    fun replacing(slot: PresetSlot): PresetIndexState {
        val index = slots.indexOfFirst { it.address == slot.address }
        if (index < 0) return this
        return copy(slots = slots.toMutableList().also { it[index] = slot })
    }
}
