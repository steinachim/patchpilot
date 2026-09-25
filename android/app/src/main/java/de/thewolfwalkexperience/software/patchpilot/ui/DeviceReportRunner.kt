// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
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
     * A report read and held, with everything sharing or saving it needs. [stem] and [description]
     * are resolved during the read, while the instrument was connected: sharing opens the system
     * chooser, and the resume from it rebuilds the session, so nothing here may be resolved at
     * share time.
     *
     * [failures] is what the read could not complete, one "what: why" line each, carried beside
     * the JSON so a screen can say the report is incomplete without parsing it.
     */
    data class Done(
        val json: String,
        val stem: String,
        val description: String,
        val failures: List<String> = emptyList(),
    ) : DeviceReportState {
        val isComplete: Boolean get() = failures.isEmpty()
    }
}

/**
 * Reads one device report at a time and holds the result for whichever screen asked. Owned by the
 * ViewModel, since a Nord report walks every item and takes minutes, and a read tied to a
 * composition would be cancelled by a rotation. The read only queries, so cancelling changes
 * nothing on the instrument; it is the minutes that are worth keeping. A finished report survives
 * process death through [savedState], and is bounded well within what a Bundle carries.
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
    private val build: suspend (progress: (String) -> Unit) -> DeviceReportResult,
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
                // Inside the try, so a teardown in the meantime fails the report as a failed read
                // does rather than throwing out of the caller's click.
                val (stem, description) = identity()
                val built = build { step -> set(DeviceReportState.Running(step)) }
                set(
                    DeviceReportState.Done(
                        built.json, stem, description,
                        built.failures.map { (what, why) -> "$what: $why" },
                    ),
                )
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

        /** How many entries precede the failure lines in the saved array. */
        private const val FIXED_FIELDS = 3

        /** Only a held report is saved. The three fixed fields come first, then one entry per failure line. */
        fun flatten(state: DeviceReportState): List<String> {
            val done = state as? DeviceReportState.Done ?: return emptyList()
            return listOf(done.json, done.stem, done.description) + done.failures
        }

        fun restore(saved: Array<String>?): DeviceReportState =
            if (saved == null || saved.size < FIXED_FIELDS) DeviceReportState.Idle
            else DeviceReportState.Done(saved[0], saved[1], saved[2], saved.drop(FIXED_FIELDS))
    }
}
