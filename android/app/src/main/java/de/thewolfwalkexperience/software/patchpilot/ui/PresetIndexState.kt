package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot

/**
 * A preset index as it arrives, rather than only once it is whole.
 *
 * A loading/error/data trio cannot express this: a Pro-800 scan is 400 sequential dumps, so
 * "loading" and "has data" overlap for most of a minute, and the rows have to be renderable
 * throughout. A Nord fills this in one batch and lands on [complete] immediately, so the
 * same screen serves both.
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
     * Which run of the index this describes - the `key` that produced it.
     *
     * Carried because [complete] alone cannot answer "has *my* refresh finished?". A caller that
     * triggers a re-read and then waits for `complete` to become true is watching a value that
     * may never be observed as false: when a listing is fast (demo mode, a Nord, a cache hit) the
     * reset and the completion land inside a single recomposition, so a `LaunchedEffect` keyed on
     * `complete` never sees its key change and never re-runs. Comparing this against the key that
     * was just requested is unambiguous whether the run takes a millisecond or 93 seconds.
     */
    val generation: Any? = null,
) {
    val loading: Boolean get() = !complete && error == null

    /**
     * Folds one update in. Pure, so the collector can live anywhere.
     *
     * It used to be inline in a `produceState` inside the screen, which is what tied the whole
     * scan to the composition - and a rotation therefore restarted a 93-second listing. Pulling
     * the fold out is what let the collector move into the ViewModel, and it made the rule that
     * `Failed` is never fatal into something a unit test can hold.
     */
    fun plus(update: IndexUpdate): PresetIndexState = when (update) {
        is IndexUpdate.Slots -> copy(slots = slots + update.slots)
        is IndexUpdate.Progress -> copy(progress = update)
        is IndexUpdate.Failed -> copy(failures = failures + update)
        IndexUpdate.Complete -> copy(complete = true, progress = null)
    }
}
