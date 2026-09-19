// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.OccupiedSlotReason
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * The hidden debug menu, reached by tapping the instrument name five times on the preset screen.
 *
 * A route of its own rather than a dialog or a bottom sheet, because the regression test needs
 * real room: a progress line while it runs, and a scrollable report afterwards.
 *
 * Two entries, both of which exist for the same reason - putting the instrument, rather than the
 * app, under test:
 *
 * - **the device report**, always available here; the preset screen offers it only for a device
 *   outside the catalog or on untested firmware. Read once, then offered for sharing or saving,
 *   rather than a share button and a save button that each read the instrument on their own.
 * - **the regression test**, which exercises every operation the connected instrument declares
 *   and says what happened. See [de.thewolfwalkexperience.software.patchpilot.core.RegressionTester]
 *   for what it will and will not do to an instrument.
 */
@Composable
fun DebugScreen(viewModel: InstrumentViewModel, onBack: () -> Unit) {
    // The run itself lives in the ViewModel, so a configuration change mid-run neither cancels
    // it nor loses the dialog it is waiting on - see RegressionRunner.
    val runner = viewModel.regressionRunner
    val run by runner.state.collectAsState()
    val pending by runner.question.collectAsState()
    val runError by runner.error.collectAsState()
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    // The device report, read and held in the ViewModel like the regression run, so neither the
    // read nor the result is lost to a rotation - see DeviceReportRunner.
    val reportRunner = viewModel.deviceReportRunner
    val reportState by reportRunner.state.collectAsState()
    val reportError by reportRunner.error.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val jsonSuffix = stringResource(R.string.programs_json_suffix)
    val txtSuffix = stringResource(R.string.programs_txt_suffix)
    val reportShareTitle = stringResource(R.string.programs_share_report)
    val regressionShareTitle = stringResource(R.string.debug_regression_share)
    val regressionStartingLabel = stringResource(R.string.debug_regression_starting)
    val readingLabel = stringResource(R.string.share_reading_device)
    val verifyStartingLabel = stringResource(R.string.debug_verify_starting)

    // "Save to device": the content is resolved when the picker returns, from the held report
    // it was launched for, not captured when it was launched. The picker is another Activity,
    // and a configuration change while it is up recreates this composition; anything captured
    // in plain `remember` is gone by the time the Uri arrives, while both reports survive in
    // the ViewModel. Each kind of report has its own launcher, so the result already says which
    // one it is for.
    val saveFailed = stringResource(R.string.debug_save_failed)
    fun onSaveResult(uri: Uri?, content: () -> String?) {
        val text = content()
        if (uri == null || text == null) return
        scope.launch {
            try {
                // Off the main thread, and not abandonable part way: the picker has already
                // created the document, and a cancelled write would leave it truncated.
                withContext(Dispatchers.IO + NonCancellable) { saveTextReport(context, uri, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: saveFailed
            }
        }
    }
    val saveJsonLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME_TYPE)) { uri ->
            onSaveResult(uri) { (reportRunner.state.value as? DeviceReportState.Done)?.json }
        }
    val saveTextLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT_MIME_TYPE)) { uri ->
            onSaveResult(uri) { (runner.state.value as? RegressionRunState.Done)?.report?.asText() }
        }

    // The factory-name check: which bank the result belongs to, whether a read is running, and
    // what it found. Deliberately not saved like the two reports - a two-minute
    // read is not worth resuming across process death, and a stale verdict would be worse than
    // none. The bank is kept because checking PRE1 and then PRE2 would otherwise leave a verdict
    // on screen with nothing saying which bank earned it.
    var verifyBank by remember { mutableStateOf<String?>(null) }
    var verifyProgress by remember { mutableStateOf<String?>(null) }
    var verifyResult by remember { mutableStateOf<List<String>?>(null) }
    val factoryBanks = remember(viewModel.canVerifyFactoryNames) { viewModel.factoryBanks() }

    fun onVerifyFactoryNames(bank: Int) {
        scope.launch {
            error = null
            verifyResult = null
            verifyProgress = verifyStartingLabel
            try {
                verifyResult = viewModel.verifyFactoryNames(bank) { step -> verifyProgress = step }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                verifyProgress = null
            }
        }
    }

    // Back out of a *report* - either kind - to the debug menu, not out of the debug screen.
    //
    // The reports are rendered inside this screen rather than on routes of their own, so the
    // scaffold's arrow was leaving for the programs list and skipping the menu the user had just
    // come from - which reads as the app losing your place. A finished report is a state of this
    // screen, so back clears the state; only from the menu itself does back actually leave.
    val atMenu = run !is RegressionRunState.Done && reportState !is DeviceReportState.Done
    // Clears both; only one is ever set, since each report hides the button that starts the other.
    fun backToMenu() {
        runner.dismissReport()
        reportRunner.dismiss()
    }
    val handleBack: () -> Unit = { if (atMenu) onBack() else backToMenu() }
    // Also catches the system/gesture back, which otherwise disagrees with the arrow beside it.
    BackHandler(enabled = !atMenu) { backToMenu() }

    // A run in progress is not dismissable. It survives the screen (it runs in the ViewModel),
    // but its confirmation dialogs are shown only here, and it holds the instrument mutex while
    // it waits on one - so leaving would strand the run on a question nobody can see and block
    // every edit behind it. The arrow is greyed out and the gesture swallowed, the presets
    // screen's shape during an edit. The wait is bounded: a run is a handful of writes with
    // pauses between them, and an instrument that stops answering fails the run through the
    // exchange timeouts. Never enabled together with the handler above, since `Running` counts
    // as `atMenu`, so which of the two Compose consults first does not matter.
    //
    // The device report read is not held like this: it writes nothing, and it carries on in the
    // ViewModel if the screen is left, so coming back finds the result waiting.
    val running = run is RegressionRunState.Running
    BackHandler(enabled = running) {
        // Deliberately empty: refusing the gesture *is* the behaviour, and the step line already
        // says what is running.
    }

    PatchPilotScaffold(
        title = stringResource(R.string.debug_title),
        onBack = handleBack,
        backEnabled = !running,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            (error ?: runError ?: reportError)?.let {
                Text(stringResource(R.string.programs_error, it), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            val state = run
            val report = reportState
            when {
                // Precedence, not state: at most one of the first four holds at a time, since each
                // replaces the menu and with it the buttons that start the others. The device
                // report is read on demand and then *held* - the same shape as the regression run,
                // and for the same reason: a single "Generate" whose result offers Share and Save
                // says what each does, where "Save to device" as a second entry point did not
                // (does it generate? must one generate first?).
                report is DeviceReportState.Done -> DeviceReportReadyView(
                    sizeBytes = report.json.toByteArray().size,
                    onShare = {
                        pendingShare = PendingShare(
                            title = reportShareTitle,
                            description = report.description,
                            suffix = jsonSuffix,
                            initialStem = report.stem,
                            // From the held report, not a fresh read: the read is the slow part
                            // this view exists to keep.
                            onConfirm = { stem ->
                                shareTextReport(
                                    context = context,
                                    filename = "$stem$jsonSuffix",
                                    content = report.json,
                                    mimeType = JSON_MIME_TYPE,
                                    chooserTitle = reportShareTitle,
                                )
                            },
                        )
                    },
                    onSave = { saveJsonLauncher.launch(report.stem + jsonSuffix) },
                    onBackToMenu = ::backToMenu,
                )
                report is DeviceReportState.Running ->
                    RunningView(stringResource(R.string.debug_report_running), report.step)
                state is RegressionRunState.Running ->
                    RunningView(stringResource(R.string.debug_regression_running), state.step)
                state is RegressionRunState.Done -> RegressionReportView(
                    report = state.report,
                    onShare = {
                        pendingShare = PendingShare(
                            title = regressionShareTitle,
                            description = state.report.summary,
                            suffix = txtSuffix,
                            initialStem = "${state.stem}_capabilities",
                            onConfirm = { stem ->
                                shareTextReport(
                                    context = context,
                                    filename = "$stem.txt",
                                    content = state.report.asText(),
                                    mimeType = TEXT_MIME_TYPE,
                                    chooserTitle = regressionShareTitle,
                                )
                            },
                        )
                    },
                    onSave = { saveTextLauncher.launch("${state.stem}_capabilities$txtSuffix") },
                    onBackToMenu = ::backToMenu,
                )
                else -> {
                    Text(stringResource(R.string.debug_report_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    // Gated on the factory-name check, whose progress and verdict live inline on
                    // this menu: a report read replaces the menu, and would hide them mid-read.
                    Button(
                        onClick = { reportRunner.start(readingLabel) },
                        enabled = viewModel.hasReport && verifyProgress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.debug_report_action))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.debug_regression_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { runner.start(regressionStartingLabel) },
                        enabled = verifyProgress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.debug_regression_action))
                    }
                    // Only where there is a shipped name table to check, which today means a
                    // Motif XS. Read-only throughout: it issues dump requests and writes nothing,
                    // which is why it needs none of the regression test's confirmations.
                    if (viewModel.canVerifyFactoryNames) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            stringResource(R.string.debug_verify_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        factoryBanks.forEach { (bank, label) ->
                            OutlinedButton(
                                onClick = { verifyBank = label; onVerifyFactoryNames(bank) },
                                enabled = verifyProgress == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.debug_verify_action, label))
                            }
                        }
                        verifyProgress?.let { step ->
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(4.dp))
                            Text(step, style = MaterialTheme.typography.bodySmall)
                        }
                        verifyResult?.let { mismatches ->
                            Spacer(Modifier.height(8.dp))
                            if (mismatches.isEmpty()) {
                                Text(
                                    stringResource(R.string.debug_verify_ok, verifyBank.orEmpty()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                Text(
                                    stringResource(
                                        R.string.debug_verify_mismatches,
                                        verifyBank.orEmpty(), mismatches.size,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // Every one of them, not a count. A mismatch means the shipped
                                // table is wrong, and the only useful next step is knowing which
                                // slot and what the instrument actually calls it.
                                mismatches.forEach {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pending?.let { confirmation ->
        AlertDialog(
            // Dismissing without answering is a "no": the run is waiting on this, and treating a
            // tap outside as consent is exactly what a warning dialog must not do.
            onDismissRequest = { confirmation.answer.complete(false) },
            title = { Text(stringResource(confirmation.question.title)) },
            text = {
                Text(
                    when (val question = confirmation.question) {
                        is PendingQuestion.ConfirmSelect ->
                            stringResource(R.string.debug_confirm_select_body, question.shownOnDevice)
                        is PendingQuestion.ConfirmRealSlotMutation -> stringResource(
                            R.string.debug_confirm_occupied_body,
                            stringResource(
                                when (question.reason) {
                                    OccupiedSlotReason.NO_FREE_SLOT -> R.string.debug_confirm_occupied_reason_no_slot
                                    OccupiedSlotReason.CANNOT_COPY -> R.string.debug_confirm_occupied_reason_no_copy
                                },
                            ),
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmation.answer.complete(true) }) {
                    Text(stringResource(confirmation.question.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmation.answer.complete(false) }) {
                    Text(stringResource(confirmation.question.decline))
                }
            },
        )
    }

    pendingShare?.let { share ->
        ShareFilenameDialog(share, onDismiss = { pendingShare = null })
    }
}

/** A title, an indeterminate bar and the current step - what either report looks like mid-read. */
@Composable
private fun RunningView(title: String, step: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(step, style = MaterialTheme.typography.bodyMedium)
}

/**
 * The device report, read and waiting to go somewhere - the counterpart of [RegressionReportView].
 *
 * The JSON itself is not shown: for a Nord it is tens of kilobytes, most of it hex payloads and
 * program names, and the useful thing to do with it is to send it on rather than read it on a
 * phone. What is shown is enough to know the read happened and roughly what it produced.
 */
@Composable
private fun DeviceReportReadyView(
    sizeBytes: Int,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onBackToMenu: () -> Unit,
) {
    Text(stringResource(R.string.debug_report_ready), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        // Rounded up, so a demo-sized report says "1 KB" rather than "0 KB".
        stringResource(R.string.debug_report_ready_body, (sizeBytes + 1023) / 1024),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    ReportActions(onShare, onSave, onBackToMenu)
}

@Composable
private fun RegressionReportView(
    report: RegressionReport,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onBackToMenu: () -> Unit,
) {
    Text(report.summary, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    report.results.forEach { result ->
        Text(
            "${result.status.name} - ${result.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = when (result.status) {
                Status.FAIL -> MaterialTheme.colorScheme.error
                Status.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                Status.PASS -> MaterialTheme.colorScheme.onSurface
            },
        )
        Text(
            result.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Monospaced so a run of addresses ("A:1:1", "A:1:4") lines up down the report.
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(12.dp))
    ReportActions(onShare, onSave, onBackToMenu)
}

/** What a finished report of either kind offers: send it on, keep it here, or drop it. */
@Composable
private fun ReportActions(onShare: () -> Unit, onSave: () -> Unit, onBackToMenu: () -> Unit) {
    Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_share))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_save_to_device))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onBackToMenu, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.debug_regression_back))
    }
}

