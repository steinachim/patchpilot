// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a long job has got to: nothing held, running at [Running.step], or finished with [Done.result]. */
internal sealed interface RunState<out T> {
    data object Idle : RunState<Nothing>

    data class Running(val step: String) : RunState<Nothing>

    data class Done<out T>(val result: T) : RunState<T>
}

/**
 * Runs one long job at a time on [scope], which outlives the screen that asked, and holds its
 * finished result in [savedState] under [savedKey], so it survives process death. A job still
 * running cannot: it is lost with the process.
 *
 * @param save flattens a result to strings, which [SavedStateHandle] holds without a
 *   `@Parcelize` dependency; [restore] reverses it, answering null for anything it cannot read.
 */
internal class SavedResultRunner<T : Any>(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val savedKey: String,
    private val save: (T) -> List<String>,
    restore: (List<String>) -> T?,
) {
    private val _state = MutableStateFlow<RunState<T>>(
        savedState.get<Array<String>>(savedKey)
            ?.let { restore(it.toList()) }
            ?.let { RunState.Done(it) }
            ?: RunState.Idle,
    )

    val state: StateFlow<RunState<T>> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last job failed, until the next one starts or [clearError] is called. */
    val error: StateFlow<String?> = _error.asStateFlow()

    val isRunning: Boolean get() = _state.value is RunState.Running

    /**
     * Starts [work] unless a job is running, replacing any held result. [startingLabel] is the
     * first step shown; [work] reports later ones through the callback it is handed. Anything
     * [work] throws ends the job and lands in [error] rather than in the caller's click - a
     * teardown in the meantime included. Cancellation ends it quietly.
     */
    fun start(startingLabel: String, work: suspend (progress: (String) -> Unit) -> T) {
        if (isRunning) return
        scope.launch {
            _error.value = null
            set(RunState.Running(startingLabel))
            try {
                set(RunState.Done(work { step -> set(RunState.Running(step)) }))
            } catch (e: CancellationException) {
                set(RunState.Idle)
                throw e
            } catch (e: Exception) {
                set(RunState.Idle)
                _error.value = e.message
            }
        }
    }

    /** Drops a held result. A job in progress is not dismissable. */
    fun dismiss() {
        if (_state.value is RunState.Done) set(RunState.Idle)
    }

    fun clearError() {
        _error.value = null
    }

    /** Only a held result is saved; anything else saves as empty, which restores as [RunState.Idle]. */
    private fun set(state: RunState<T>) {
        _state.value = state
        val flat = (state as? RunState.Done)?.result?.let(save).orEmpty()
        savedState[savedKey] = flat.toTypedArray()
    }
}
