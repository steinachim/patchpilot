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
 * A route of its own rather than a dialog, because the regression test needs room for a progress
 * line and a scrollable report.
 *
 * Both entries put the instrument, rather than the app, under test:
 *
 * - the device report, always available here and on the preset screen only for a device outside
 *   the catalog or on untested firmware. Read once, then offered for sharing or saving.
 * - the regression test, which exercises every operation the connected instrument declares - see
 *   [de.thewolfwalkexperience.software.patchpilot.core.RegressionTester] for what it will and
 *   will not do.
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
    // What the connected instrument offers, keyed on the session as ProgramsScreen keys its facet
    // reads: nothing about a plain getter tells Compose when the answer changes, so read as plain
    // properties these would not recover when a session came back. The hand-off when a session is
    // lost is PatchPilotNavHost's; this only keeps the buttons honest through a reconnect's dip.
    val session by viewModel.state.collectAsState()
    val hasReport = remember(session) { viewModel.hasReport }
    val canVerifyFactoryNames = remember(session) { viewModel.canVerifyFactoryNames }
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

    // "Save to device": the content is resolved when the picker returns rather than captured when
    // it was launched. The picker is another Activity, and a configuration change while it is up
    // recreates this composition, so anything in plain `remember` is gone by the time the Uri
    // arrives - while both reports survive in the ViewModel. One launcher per kind of report, so
    // the result says which one it is for.
    val saveFailed = stringResource(R.string.debug_save_failed)
    fun onSaveResult(uri: Uri?, content: () -> String?) {
        val text = content()
        if (uri == null || text == null) return
        scope.launch {
            try {
                // Off the main thread and not abandonable part way: the picker has already created
                // the document, and a cancelled write would leave it truncated.
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
    // what it found. Not saved like the two reports, since a stale verdict would be worse than
    // none; the bank is kept, or checking PRE1 and then PRE2 would leave a verdict with nothing
    // saying which bank earned it.
    var verifyBank by remember { mutableStateOf<String?>(null) }
    var verifyProgress by remember { mutableStateOf<String?>(null) }
    var verifyResult by remember { mutableStateOf<List<String>?>(null) }
    val factoryBanks = remember(session) { viewModel.factoryBanks() }

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

    // Back out of a report - either kind - to the debug menu, not out of the debug screen: the
    // reports render inside this screen rather than on routes of their own, so a finished report
    // is a state of it, and back clears that state. Only from the menu itself does back leave.
    val atMenu = run !is RegressionRunState.Done && reportState !is DeviceReportState.Done
    // Clears both; only one is ever set, since each report hides the button that starts the other.
    fun backToMenu() {
        runner.dismissReport()
        reportRunner.dismiss()
    }
    val handleBack: () -> Unit = { if (atMenu) onBack() else backToMenu() }
    // Also catches the system/gesture back, which otherwise disagrees with the arrow beside it.
    BackHandler(enabled = !atMenu) { backToMenu() }

    // A run in progress is not dismissable: it survives the screen, but its confirmation dialogs
    // are shown only here and it holds the instrument mutex while it waits on one, so leaving
    // would strand it on a question nobody can see and block every edit behind it. The wait is
    // bounded by the exchange timeouts. Never enabled together with the handler above, since
    // `Running` counts as `atMenu`.
    //
    // The device report read is not held like this: it writes nothing and carries on in the
    // ViewModel, so coming back finds the result waiting.
    val running = run is RegressionRunState.Running
    BackHandler(enabled = running) {
        // Empty: refusing the gesture is the behaviour, and the step line says what is running.
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
                // At most one of the first four holds at a time, since each replaces the menu and
                // with it the buttons that start the others. The device report is read on demand
                // and then held, the same shape as the regression run: one "Generate" whose result
                // offers Share and Save.
                report is DeviceReportState.Done -> DeviceReportReadyView(
                    sizeBytes = report.json.toByteArray().size,
                    failures = report.failures,
                    onShare = {
                        pendingShare = PendingShare(
                            title = reportShareTitle,
                            description = report.description,
                            suffix = jsonSuffix,
                            initialStem = report.stem,
                            // From the held report: the read is the slow part this view keeps.
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
                    // this menu: a report read replaces the menu and would hide them mid-read.
                    Button(
                        onClick = { reportRunner.start(readingLabel) },
                        enabled = hasReport && verifyProgress == null,
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
                    // Motif XS. Read-only, so it needs none of the regression test's confirmations.
                    if (canVerifyFactoryNames) {
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
                                // Every one, not a count: a mismatch means the shipped table is
                                // wrong, and what matters is which slot and what the instrument
                                // calls it.
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
            // Dismissing without answering is a "no": a tap outside is not consent.
            onDismissRequest = { confirmation.answer.complete(false) },
            title = { Text(stringResource(confirmation.question.title)) },
            text = {
                Text(
                    when (val question = confirmation.question) {
                        is PendingQuestion.ConfirmSelect ->
                            stringResource(R.string.debug_confirm_select_body, question.shownOnDevice)
                        // The swap alone has its own wording, since the three-test body names
                        // two tests that are not in question there.
                        is PendingQuestion.ConfirmRealSlotMutation -> when (question.reason) {
                            OccupiedSlotReason.NO_FREE_SLOT -> stringResource(
                                R.string.debug_confirm_occupied_body,
                                stringResource(R.string.debug_confirm_occupied_reason_no_slot),
                            )
                            OccupiedSlotReason.CANNOT_COPY -> stringResource(
                                R.string.debug_confirm_occupied_body,
                                stringResource(R.string.debug_confirm_occupied_reason_no_copy),
                            )
                            OccupiedSlotReason.NO_SECOND_FREE_SLOT ->
                                stringResource(R.string.debug_confirm_swap_body)
                        }
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
 * The JSON itself is not shown: for a Nord it is tens of kilobytes of hex, and the useful thing to
 * do with it is send it on. What is shown is enough to know the read happened and, where reads
 * failed, which - since a report whose cable was pulled halfway reaches this view like a whole
 * one. Share and Save stay, and the title and list say it is partial.
 */
@Composable
private fun DeviceReportReadyView(
    sizeBytes: Int,
    failures: List<String>,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onBackToMenu: () -> Unit,
) {
    Text(
        stringResource(
            if (failures.isEmpty()) R.string.debug_report_ready else R.string.debug_report_ready_incomplete,
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        // Rounded up, so a demo-sized report says "1 KB" rather than "0 KB".
        stringResource(R.string.debug_report_ready_body, (sizeBytes + 1023) / 1024),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (failures.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.debug_report_incomplete_body, failures.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failures.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
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
            // Monospaced, so a run of addresses lines up down the report.
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

