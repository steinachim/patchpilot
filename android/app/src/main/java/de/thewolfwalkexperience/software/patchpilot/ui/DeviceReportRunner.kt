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

/** Where a device report has got to. */
internal sealed interface DeviceReportState {
    data object Idle : DeviceReportState

    data class Running(val step: String) : DeviceReportState

    /**
     * A report read and held, with everything sharing or saving it needs.
     *
     * [stem] and [description] are the instrument's own wording, resolved during the read while
     * it was connected: sharing opens the system chooser, which is an Activity of its own, and a
     * resume from it rebuilds a USB session from scratch. Between the teardown and the reconnect
     * there is no instrument, so nothing about a held report may be resolved at share time.
     */
    data class Done(val json: String, val stem: String, val description: String) : DeviceReportState
}

/**
 * Reads one device report at a time and holds the result for whichever screen asked.
 *
 * Owned by the ViewModel rather than by a screen for the same reason [RegressionRunner] is: a
 * Nord report walks every item on the instrument and takes minutes, and a read tied to a
 * composition is cancelled by a rotation and starts over. The read only queries, so cancelling
 * it changes nothing on the instrument; it is the minutes that are worth keeping. A finished
 * report also survives process death through [savedState]. The report is bounded (the largest
 * part of a Nord's is the raw hex of its category replies; it carries no preset names or data),
 * so it is nowhere near what a Bundle can carry.
 *
 * Three screens use it: the debug menu holds the result and offers Share and Save, while the
 * preset and connect screens share it straight away and then [dismiss] it.
 *
 * @param build the ViewModel's own report entry point, which holds the instrument mutex.
 * @param identity the filename stem and the description, resolved at the start of a run.
 */
internal class DeviceReportRunner(
    private val scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val build: suspend (progress: (String) -> Unit) -> String,
    private val identity: () -> Pair<String, String>,
) {
    private val _state = MutableStateFlow(restore(savedState.get<Array<String>>(SAVED_KEY)))

    val state: StateFlow<DeviceReportState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last read failed, until the next one starts or [clearError] is called. */
    val error: StateFlow<String?> = _error.asStateFlow()

    val isRunning: Boolean get() = _state.value is DeviceReportState.Running

    /** Starts a read unless one is in progress, replacing any held report. */
    fun start(readingLabel: String) {
        if (isRunning) return
        scope.launch {
            _error.value = null
            set(DeviceReportState.Running(readingLabel))
            try {
                // Resolved inside the try, so a teardown in the meantime fails the report the
                // way a failed read does instead of throwing out of the caller's click.
                val (stem, description) = identity()
                val json = build { step -> set(DeviceReportState.Running(step)) }
                set(DeviceReportState.Done(json, stem, description))
            } catch (e: CancellationException) {
                set(DeviceReportState.Idle)
                throw e
            } catch (e: Exception) {
                set(DeviceReportState.Idle)
                _error.value = e.message
            }
        }
    }

    /** Drops a held report. A read in progress is not dismissable. */
    fun dismiss() {
        if (_state.value is DeviceReportState.Done) set(DeviceReportState.Idle)
    }

    fun clearError() {
        _error.value = null
    }

    private fun set(state: DeviceReportState) {
        _state.value = state
        savedState[SAVED_KEY] = flatten(state).toTypedArray()
    }

    private companion object {
        const val SAVED_KEY = "deviceReport"

        /** Only a held report is saved; Idle and Running both restore to Idle. */
        fun flatten(state: DeviceReportState): List<String> {
            val done = state as? DeviceReportState.Done ?: return emptyList()
            return listOf(done.json, done.stem, done.description)
        }

        fun restore(saved: Array<String>?): DeviceReportState =
            if (saved == null || saved.size != 3) DeviceReportState.Idle
            else DeviceReportState.Done(saved[0], saved[1], saved[2])
    }
}
