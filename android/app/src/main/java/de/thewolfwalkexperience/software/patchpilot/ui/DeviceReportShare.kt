// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.thewolfwalkexperience.software.patchpilot.R
import kotlinx.coroutines.launch

/**
 * The read-then-share flow for a device report, as the connect and preset screens offer it: ask
 * for a filename ([dialog]), read the report in [DeviceReportRunner], and hand it to the share
 * sheet the moment it is done.
 *
 * @property step what a read in progress is doing, or null while none is.
 * @property error why the last read failed, until [clearError].
 * @property label what the button says: the step while reading, the action otherwise.
 */
internal class DeviceReportShare(
    val step: String?,
    val error: String?,
    val label: String,
    val clearError: () -> Unit,
    private val makeDialog: () -> PendingShare?,
) {
    val running: Boolean get() = step != null

    /**
     * The filename prompt that starts a read ([ShareFilenameDialog]), or null where the session
     * has no report by the time of the tap: the button's gate can trail the session by a frame.
     */
    fun dialog(): PendingShare? = makeDialog()
}

/**
 * Remembers the [DeviceReportShare] for this screen. The read runs in the ViewModel, so a rotation
 * mid-read costs nothing, and the stem the user typed is saved, so the share still happens if the
 * screen was recreated.
 *
 * @param onStarted called as a read starts.
 * @param onShared called once the share sheet is up, with the file name it was given and how many
 *   of the report's reads failed - a report read part way is shared all the same.
 */
@Composable
internal fun rememberDeviceReportShare(
    viewModel: InstrumentViewModel,
    onStarted: () -> Unit = {},
    onShared: (sharedAs: String, failedReads: Int) -> Unit = { _, _ -> },
): DeviceReportShare {
    val runner = viewModel.deviceReportRunner
    val state by runner.state.collectAsStateWithLifecycle()
    val error by runner.error.collectAsStateWithLifecycle()
    var pendingStem by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.programs_share_report)
    val suffix = stringResource(R.string.programs_json_suffix)
    val readingLabel = stringResource(R.string.share_reading_device)
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnShared by rememberUpdatedState(onShared)

    LaunchedEffect(state, pendingStem) {
        val stem = pendingStem ?: return@LaunchedEffect
        val done = (state as? RunState.Done)?.result ?: return@LaunchedEffect
        pendingStem = null
        runner.dismiss()
        // Launched apart from this effect, which the two lines above restart: the share suspends
        // while it writes the file, and a restart would cancel it.
        scope.launch {
            val sharedAs = shareTextReport(context, "$stem$suffix", done.json, JSON_MIME_TYPE, title)
            currentOnShared(sharedAs, done.failures.size)
        }
    }

    val step = (state as? RunState.Running)?.step
    return DeviceReportShare(
        step = step,
        error = error,
        label = step ?: title,
        clearError = runner::clearError,
    ) {
        // Supplied by the instrument's own reporter, since the families read different things.
        val (initialStem, description) = viewModel.reportNaming() ?: return@DeviceReportShare null
        PendingShare(
            title = title,
            description = description,
            suffix = suffix,
            initialStem = initialStem,
            onConfirm = { stem ->
                pendingStem = stem
                currentOnStarted()
                runner.start(readingLabel)
            },
        )
    }
}
