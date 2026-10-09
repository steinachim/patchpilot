// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A report read and held, with everything sharing or saving it needs. [stem] and [description]
 * are resolved during the read, while the instrument was connected: sharing opens the system
 * chooser, and the resume from it rebuilds the session, so nothing here may be resolved at share
 * time.
 *
 * [failures] is what the read could not complete, one "what: why" line each, carried beside the
 * JSON so a screen can say the report is incomplete without parsing it.
 */
internal data class HeldReport(
    val json: String,
    val stem: String,
    val description: String,
    val failures: List<String> = emptyList(),
) {
    val isComplete: Boolean get() = failures.isEmpty()
}

/**
 * Reads one device report at a time and holds the result for whichever screen asked. Owned by the
 * ViewModel, since a Nord report walks every item and takes minutes, and a read tied to a
 * composition would be cancelled by a rotation. The read only queries, so cancelling changes
 * nothing on the instrument; it is the minutes that are worth keeping. A finished report survives
 * process death - see [SavedResultRunner]. **Assumption, not measured:** the largest report, a
 * Nord's at tens of kilobytes, stays well within the ~1 MB Binder limit the whole saved state
 * shares.
 *
 * Three screens use it: the debug menu holds the result and offers Share and Save, while the
 * preset and connect screens share it straight away and then [dismiss] it.
 *
 * @param build the ViewModel's own report entry point, which holds the instrument mutex.
 * @param identity the filename stem and the description, resolved at the start of a run.
 */
internal class DeviceReportRunner(
    scope: CoroutineScope,
    private val savedState: SavedStateHandle,
    private val build: suspend (progress: (String) -> Unit) -> DeviceReportResult,
    private val identity: () -> Pair<String, String>,
) {
    private val runner = SavedResultRunner(scope, savedState, SAVED_KEY, ::flatten, ::restore)

    val state: StateFlow<RunState<HeldReport>> get() = runner.state

    /** Why the last read or share failed, until the next read starts or [clearError] is called. */
    val error: StateFlow<String?> get() = runner.error

    private val _shareAs = MutableStateFlow(savedState.get<String>(SHARE_AS_KEY))

    /**
     * The file name stem the running or finished read is to be shared under, or null for a read
     * that is only held (the debug menu's). Here rather than in the screen that asked, so a
     * rotation between confirming the name and the read finishing still shares it, and saved, so
     * process death does too.
     */
    val shareAs: StateFlow<String?> = _shareAs.asStateFlow()

    /**
     * Starts a read unless one is in progress, replacing any held report. [shareAs] is the stem
     * to share the result under once it is read - see [takeShare].
     */
    fun start(readingLabel: String, shareAs: String? = null) {
        if (runner.isRunning) return
        setShareAs(shareAs)
        runner.start(readingLabel) { progress ->
            val (stem, description) = identity()
            val built = build(progress)
            HeldReport(built.json, stem, description, built.failures.map { (what, why) -> "$what: $why" })
        }
    }

    /**
     * The finished report and the stem it is to be shared under, or null where none is waiting.
     * Taken once: the pending share is cleared, so a recomposition cannot share it twice. The
     * report stays held until the caller [dismiss]es it, once the share sheet is up.
     */
    fun takeShare(): Pair<String, HeldReport>? {
        val stem = _shareAs.value ?: return null
        val done = (runner.state.value as? RunState.Done)?.result ?: return null
        setShareAs(null)
        return stem to done
    }

    /** Drops a held report and any share waiting for it. A read in progress is not dismissable. */
    fun dismiss() {
        if (runner.isRunning) return
        setShareAs(null)
        runner.dismiss()
    }

    /** Stops a read in progress, for a session the user is leaving; a held report stays. */
    fun cancel() {
        setShareAs(null)
        runner.cancel()
    }

    fun clearError() = runner.clearError()

    /** A share that failed after the read, reported where a failed read is. */
    fun reportShareFailure(message: String) = runner.reportError(message)

    private fun setShareAs(stem: String?) {
        _shareAs.value = stem
        savedState[SHARE_AS_KEY] = stem
    }

    private companion object {
        const val SAVED_KEY = "deviceReport"
        const val SHARE_AS_KEY = "deviceReportShareAs"

        /** How many entries precede the failure lines in the saved list. */
        const val FIXED_FIELDS = 3

        /** The three fixed fields first, then one entry per failure line. */
        fun flatten(report: HeldReport): List<String> =
            listOf(report.json, report.stem, report.description) + report.failures

        fun restore(saved: List<String>): HeldReport? =
            if (saved.size < FIXED_FIELDS) {
                null
            } else {
                HeldReport(saved[0], saved[1], saved[2], saved.drop(FIXED_FIELDS))
            }
    }
}
