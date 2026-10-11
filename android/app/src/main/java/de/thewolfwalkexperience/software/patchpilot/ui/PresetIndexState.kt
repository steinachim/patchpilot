// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

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
) {
    val loading: Boolean get() = !complete && error == null

    /** Folds one update in. Pure, so the collector can live in the ViewModel and a unit test can hold the rule that `Failed` is never fatal. */
    fun plus(update: IndexUpdate): PresetIndexState = when (update) {
        is IndexUpdate.Slots -> copy(slots = slots + update.slots)
        is IndexUpdate.Progress -> copy(progress = update)
        is IndexUpdate.Failed -> copy(failures = failures + update)
        IndexUpdate.Complete -> copy(complete = true, progress = null)
    }

    /** Addresses that could not be read: neither known to hold a preset nor known to be free. */
    val unreadable: Set<SlotAddress> get() = failures.mapTo(mutableSetOf()) { it.address }

    /**
     * This listing with [slot] at its address, which no longer counts as unreadable. In place
     * where the listing holds the address, so a row re-read after a rename stays where it was.
     *
     * The same address can appear in more than one listing - a favorited user voice is in both -
     * and an edit made from either has to correct both, while the cache write-through only
     * reaches whichever is collected next.
     *
     * An absent address holding a preset is inserted in device order only where [insert] says
     * this listing spans the address space, and only once it is [complete]. A Nord lists only
     * the slots it holds, so a copy into an empty one adds a row; a favorites listing is a sparse
     * set an edit elsewhere must not grow; and a listing still arriving will list the address
     * itself.
     */
    fun withSlot(slot: PresetSlot, insert: Boolean): PresetIndexState {
        val remaining = failures.filterNot { it.address == slot.address }
        val index = slots.indexOfFirst { it.address == slot.address }
        val updated = when {
            index >= 0 -> slots.toMutableList().also { it[index] = slot }

            !insert || !complete || slot.isEmpty -> slots

            else -> {
                val at = slots.indexOfFirst { SlotAddress.DEVICE_ORDER.compare(slot.address, it.address) < 0 }
                slots.toMutableList().also { it.add(if (at < 0) slots.size else at, slot) }
            }
        }
        return copy(slots = updated, failures = remaining)
    }
}
