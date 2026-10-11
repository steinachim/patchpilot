// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.OccupiedSlotReason
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.RegressionResult
import de.thewolfwalkexperience.software.patchpilot.core.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A finished run. [stem] is resolved before the run, in case the instrument is gone by the time the report is shared. */
internal data class FinishedRun(val report: RegressionReport, val stem: String)

/** A question the run is blocked on. Only the person holding the instrument can answer it. */
internal sealed interface PendingQuestion {
    @get:StringRes val title: Int

    @get:StringRes val confirm: Int

    @get:StringRes val decline: Int

    /** Whether the instrument's display shows the preset the app just selected. */
    data class ConfirmSelect(val shownOnDevice: String) : PendingQuestion {
        override val title = R.string.debug_confirm_select_title
        override val confirm = R.string.debug_confirm_select_yes
        override val decline = R.string.debug_confirm_select_no
    }

    /** One prompt covering rename, move and swap against a slot holding real data. */
    data class ConfirmRealSlotMutation(val reason: OccupiedSlotReason) : PendingQuestion {
        override val title = R.string.debug_confirm_occupied_title
        override val confirm = R.string.debug_confirm_occupied_yes
        override val decline = R.string.action_cancel
    }
}

/** A question and the answer the run is waiting for. */
internal class PendingConfirmation(
    val question: PendingQuestion,
    val answer: CompletableDeferred<Boolean>,
)

/**
 * Drives one regression run at a time and holds its state for the debug screen. Owned by the
 * ViewModel, because a run writes to the instrument and has to outlive the composition: a
 * configuration change tears the debug screen down, and a run cancelled between a swap and its
 * swap-back leaves a preset in the wrong slot. The screen collects [state] and [question] and
 * answers the latter; a finished report survives process death - see [SavedResultRunner].
 *
 * @param run the ViewModel's own regression entry point, which holds the instrument mutex for the
 *   whole run, confirmation dialogs included.
 * @param filenameStem resolved at the start of a run while the instrument is connected.
 */
internal class RegressionRunner(
    scope: CoroutineScope,
    savedState: SavedStateHandle,
    private val run: suspend (
        onConfirmSelect: suspend (String) -> Boolean,
        onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
        progress: (String) -> Unit,
    ) -> RegressionReport,
    private val filenameStem: () -> String,
) {
    private val runner = SavedResultRunner(scope, savedState, SAVED_KEY, ::flatten, ::restore)

    val state: StateFlow<RunState<FinishedRun>> get() = runner.state

    /** Why the last run failed, cleared when the next one starts. */
    val error: StateFlow<String?> get() = runner.error

    private val _question = MutableStateFlow<PendingConfirmation?>(null)

    /** The question a run is currently blocked on, or null. */
    val question: StateFlow<PendingConfirmation?> = _question.asStateFlow()

    /** Starts a run unless one is in progress. [startingLabel] is the first step shown. */
    fun start(startingLabel: String) = runner.start(startingLabel) { progress ->
        val stem = filenameStem()
        val report = run(
            { shown -> ask(PendingQuestion.ConfirmSelect(shown)) },
            { reason -> ask(PendingQuestion.ConfirmRealSlotMutation(reason)) },
            progress,
        )
        FinishedRun(report, stem)
    }

    /** Drops a finished report. A run in progress is not dismissable. */
    fun dismissReport() = runner.dismiss()

    /**
     * Stops a run in progress, a question it waits on included, for an instrument that has gone:
     * a run can be stopped only where nothing it would still write can land.
     */
    fun cancel() = runner.cancel()

    /** Publishes [question] and suspends until the screen answers it. */
    private suspend fun ask(question: PendingQuestion): Boolean {
        val answer = CompletableDeferred<Boolean>()
        _question.value = PendingConfirmation(question, answer)
        return try {
            answer.await()
        } finally {
            _question.value = null
        }
    }

    private companion object {
        const val SAVED_KEY = "regressionRun"

        /** The stem and the summary, then three entries (name, status, detail) per result. */
        fun flatten(run: FinishedRun): List<String> = buildList {
            add(run.stem)
            add(run.report.summary)
            run.report.results.forEach { result ->
                add(result.name)
                add(result.status.name)
                add(result.detail)
            }
        }

        fun restore(saved: List<String>): FinishedRun? {
            if (saved.size < 2 || (saved.size - 2) % 3 != 0) return null
            val results = saved.drop(2).chunked(3).map { (name, status, detail) ->
                // A name this build does not know restores as nothing, never as a crash at launch.
                RegressionResult(name, Status.entries.firstOrNull { it.name == status } ?: return null, detail)
            }
            return FinishedRun(RegressionReport(results, saved[1]), saved[0])
        }
    }
}
