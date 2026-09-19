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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a regression run has got to. */
internal sealed interface RegressionRunState {
    data object Idle : RegressionRunState

    data class Running(val step: String) : RegressionRunState

    /** [stem] is the instrument's filename stem, resolved before the run in case the instrument
     * is gone by the time the report is shared. */
    data class Done(val report: RegressionReport, val stem: String) : RegressionRunState
}

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
 * Drives one regression run at a time and holds its state for the debug screen.
 *
 * Owned by the ViewModel rather than by the screen, because a run writes to the instrument and
 * has to outlive the composition: a configuration change tears the debug screen down, and a run
 * cancelled between a swap and its swap-back leaves a real preset in the wrong slot. The screen
 * collects [state] and [question] and answers the latter; a finished report also survives
 * process death through [savedState], so the screen shows it again after a restore.
 *
 * @param run the ViewModel's own regression entry point, which holds the instrument mutex for the
 *   whole run, confirmation dialogs included.
 * @param filenameStem resolved at the start of a run while the instrument is connected.
 */
internal class RegressionRunner(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val run: suspend (
        onConfirmSelect: suspend (String) -> Boolean,
        onConfirmRealSlotMutation: suspend (OccupiedSlotReason) -> Boolean,
        progress: (String) -> Unit,
    ) -> RegressionReport,
    private val filenameStem: () -> String,
) {
    private val _state = MutableStateFlow(restore(savedState.get<Array<String>>(SAVED_KEY)))

    val state: StateFlow<RegressionRunState> = _state.asStateFlow()

    private val _question = MutableStateFlow<PendingConfirmation?>(null)

    /** The question a run is currently blocked on, or null. */
    val question: StateFlow<PendingConfirmation?> = _question.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last run failed, cleared when the next one starts. */
    val error: StateFlow<String?> = _error.asStateFlow()

    val isRunning: Boolean get() = _state.value is RegressionRunState.Running

    /** Starts a run unless one is in progress. [startingLabel] is the first step shown. */
    fun start(startingLabel: String) {
        if (isRunning) return
        scope.launch {
            _error.value = null
            set(RegressionRunState.Running(startingLabel))
            try {
                val stem = filenameStem()
                val report = run(
                    { shown -> ask(PendingQuestion.ConfirmSelect(shown)) },
                    { reason -> ask(PendingQuestion.ConfirmRealSlotMutation(reason)) },
                    { step -> set(RegressionRunState.Running(step)) },
                )
                set(RegressionRunState.Done(report, stem))
            } catch (e: CancellationException) {
                set(RegressionRunState.Idle)
                throw e
            } catch (e: Exception) {
                set(RegressionRunState.Idle)
                _error.value = e.message
            }
        }
    }

    /** Drops a finished report. A run in progress is not dismissable. */
    fun dismissReport() {
        if (_state.value is RegressionRunState.Done) set(RegressionRunState.Idle)
    }

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

    private fun set(state: RegressionRunState) {
        _state.value = state
        savedState[SAVED_KEY] = flatten(state).toTypedArray()
    }

    private companion object {
        const val SAVED_KEY = "regressionRun"

        /**
         * A finished report as a flat string list, which is what [SavedStateHandle] can hold
         * without a `@Parcelize` dependency. Idle and Running both flatten to nothing and
         * restore to Idle: a run in progress cannot survive process death.
         */
        fun flatten(state: RegressionRunState): List<String> {
            val done = state as? RegressionRunState.Done ?: return emptyList()
            val flat = mutableListOf(done.stem, done.report.summary)
            done.report.results.forEach { result ->
                flat.add(result.name)
                flat.add(result.status.name)
                flat.add(result.detail)
            }
            return flat
        }

        fun restore(saved: Array<String>?): RegressionRunState {
            if (saved == null || saved.size < 2) return RegressionRunState.Idle
            val results = saved.drop(2).chunked(3).map { triple ->
                RegressionResult(triple[0], Status.valueOf(triple[1]), triple[2])
            }
            return RegressionRunState.Done(RegressionReport(results, saved[1]), saved[0])
        }
    }
}
