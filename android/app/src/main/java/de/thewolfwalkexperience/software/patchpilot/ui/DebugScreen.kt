package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.OccupiedSlotReason
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.RegressionResult
import de.thewolfwalkexperience.software.patchpilot.core.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
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
 * - **the device report**, which used to be a button on the preset screen for every device. That
 *   was a temporary testing affordance; it now lives here, where it is always available, and the
 *   preset screen's own button is back to appearing only for a device outside the catalog.
 * - **the regression test**, which exercises every operation the connected instrument declares
 *   and says what happened. See [de.thewolfwalkexperience.software.patchpilot.core.RegressionTester]
 *   for what it will and will not do to an instrument.
 */
@Composable
fun DebugScreen(viewModel: InstrumentViewModel, onBack: () -> Unit) {
    var run by rememberSaveable(stateSaver = RegressionRunStateSaver) {
        mutableStateOf<RegressionRunState>(RegressionRunState.Idle)
    }
    var pending by remember { mutableStateOf<PendingConfirmation?>(null) }
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    val sharer = rememberReportSharer(viewModel)
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val jsonSuffix = stringResource(R.string.programs_json_suffix)
    val txtSuffix = stringResource(R.string.programs_txt_suffix)
    val reportShareTitle = stringResource(R.string.programs_share_report)
    val regressionShareTitle = stringResource(R.string.debug_regression_share)

    /**
     * Puts a question to the user from inside the test engine and suspends until it is answered.
     *
     * The engine knows nothing about Compose - it is handed two suspending lambdas and awaits
     * them. This is the half that turns one into a dialog: park a [CompletableDeferred] in screen
     * state, let the dialog below complete it, and hand the answer back to whoever was waiting.
     */
    suspend fun ask(question: PendingQuestion): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pending = PendingConfirmation(question, answer)
        return try {
            answer.await()
        } finally {
            pending = null
        }
    }

    // The factory-name check: which bank the result belongs to, whether a read is running, and
    // what it found. Deliberately not `rememberSaveable` like the regression run - a two-minute
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
            verifyProgress = context.getString(R.string.debug_verify_starting)
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

    fun onRunRegressionTest() {
        scope.launch {
            error = null
            run = RegressionRunState.Running(context.getString(R.string.debug_regression_starting))
            try {
                val report = viewModel.runRegressionTest(
                    onConfirmSelect = { shown -> ask(PendingQuestion.ConfirmSelect(shown)) },
                    onConfirmRealSlotMutation = { reason -> ask(PendingQuestion.ConfirmRealSlotMutation(reason)) },
                    progress = { step -> run = RegressionRunState.Running(step) },
                )
                run = RegressionRunState.Done(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                run = RegressionRunState.Idle
                error = e.message
            }
        }
    }

    // Back out of the *report* to the debug menu, not out of the debug screen entirely.
    //
    // The report is rendered inside this screen rather than on its own route, so the scaffold's
    // arrow was leaving for the programs list and skipping the menu the user had just come from -
    // which reads as the app losing your place. A finished run is a state of this screen, so back
    // clears the state; only from the menu itself does back actually leave.
    //
    // A run still in progress is deliberately *not* dismissable this way: it is writing to the
    // instrument, and an arrow that abandoned it mid-sequence would be the worst affordance on
    // the screen.
    val atMenu = run !is RegressionRunState.Done
    val handleBack: () -> Unit = { if (atMenu) onBack() else run = RegressionRunState.Idle }
    // Also catches the system/gesture back, which otherwise disagrees with the arrow beside it.
    BackHandler(enabled = !atMenu) { run = RegressionRunState.Idle }

    PatchPilotScaffold(title = stringResource(R.string.debug_title), onBack = handleBack) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            (error ?: sharer.error)?.let {
                Text(stringResource(R.string.programs_error, it), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            when (val state = run) {
                is RegressionRunState.Idle -> {
                    Text(stringResource(R.string.debug_report_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            pendingShare = PendingShare(
                                title = reportShareTitle,
                                description = viewModel.reportDescription,
                                suffix = jsonSuffix,
                                initialStem = viewModel.suggestedReportFilename(),
                                onConfirm = { stem -> sharer.share(stem) },
                            )
                        },
                        enabled = !sharer.isRunning && viewModel.hasReport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(sharer.progress ?: stringResource(R.string.debug_report_action))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.debug_regression_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onRunRegressionTest() },
                        enabled = !sharer.isRunning,
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
                                enabled = verifyProgress == null && !sharer.isRunning,
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
                is RegressionRunState.Running -> {
                    Text(stringResource(R.string.debug_regression_running), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(state.step, style = MaterialTheme.typography.bodyMedium)
                }
                is RegressionRunState.Done -> RegressionReportView(
                    report = state.report,
                    onShare = {
                        pendingShare = PendingShare(
                            title = regressionShareTitle,
                            description = state.report.summary,
                            suffix = txtSuffix,
                            // Not `suggestedReportFilename()`: that requires the DeviceReporter
                            // facet, which a Motif XS does not have - and this share crashed the
                            // app there. The regression test is offered for every family.
                            initialStem = "${viewModel.filenameStem}_capabilities",
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
                    onRunAgain = { run = RegressionRunState.Idle },
                )
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

@Composable
private fun RegressionReportView(
    report: RegressionReport,
    onShare: () -> Unit,
    onRunAgain: () -> Unit,
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
    Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_share))
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onRunAgain, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.debug_regression_back))
    }
}

/**
 * Where the regression run has got to.
 *
 * A sub-state of this screen rather than a fourth navigation route: nothing outside can link to a
 * finished run, and a report that survived leaving the screen would be a claim about an instrument
 * the app may no longer be connected to.
 */
private sealed interface RegressionRunState {
    data object Idle : RegressionRunState

    data class Running(val step: String) : RegressionRunState

    data class Done(val report: RegressionReport) : RegressionRunState
}

private fun regressionRunStateToStrings(state: RegressionRunState): List<String> {
    val done = state as? RegressionRunState.Done ?: return emptyList()
    val flattened = mutableListOf(done.report.summary)
    done.report.results.forEach { result ->
        flattened.add(result.name)
        flattened.add(result.status.name)
        flattened.add(result.detail)
    }
    return flattened
}

private fun regressionRunStateFromStrings(saved: List<String>): RegressionRunState {
    if (saved.isEmpty()) return RegressionRunState.Idle
    val summary = saved[0]
    val results = saved.drop(1).chunked(3).map { triple ->
        RegressionResult(triple[0], Status.valueOf(triple[1]), triple[2])
    }
    return RegressionRunState.Done(RegressionReport(results, summary))
}

/**
 * Saves a finished [RegressionRunState.Done] across a configuration change; [RegressionRunState
 * .Idle] and a run still [RegressionRunState.Running] both save as nothing and restore to `Idle`.
 *
 * A run in progress could not meaningfully survive regardless of what this saved: its coroutine
 * lives in this screen's `rememberCoroutineScope()`, which a configuration change tears down along
 * with the rest of the composition, so a restored `Running` would show a progress bar nothing is
 * ever going to advance again. Idle is the honest state to land on instead - the buttons that
 * started it are right there.
 *
 * [RegressionReport] carries no `@Parcelize` (this project has no reason for that plugin
 * otherwise), so this flattens it to the flat string list `rememberSaveable` already knows how to
 * put in a Bundle rather than adding a dependency for one screen's state.
 *
 * Confirmed reproducible without this: rotating away from a finished run's report silently
 * reverted the screen to the plain menu, even though navigation itself stayed on `debug` the whole
 * time (2026-08-28).
 *
 * Typed `Saver<RegressionRunState, Any>`, not the `List<Saveable>` its name and samples suggest:
 * `listSaver`'s own declared return type is `Saver<Original, Any>` in this Compose version, since
 * it boxes the list itself rather than parameterising over its element type.
 */
private val RegressionRunStateSaver: Saver<RegressionRunState, Any> = listSaver(
    save = { state -> regressionRunStateToStrings(state) },
    restore = { saved -> regressionRunStateFromStrings(saved) },
)

/** A question the run is blocked on, and the answer it is waiting for. */
private class PendingConfirmation(
    val question: PendingQuestion,
    val answer: CompletableDeferred<Boolean>,
)

private sealed interface PendingQuestion {
    @get:StringRes val title: Int

    @get:StringRes val confirm: Int

    @get:StringRes val decline: Int

    /** Only the person holding the instrument can see what its display says. */
    data class ConfirmSelect(val shownOnDevice: String) : PendingQuestion {
        override val title = R.string.debug_confirm_select_title
        override val confirm = R.string.debug_confirm_select_yes
        override val decline = R.string.debug_confirm_select_no
    }

    /** The one prompt covering rename, move and swap against a slot holding real data. */
    data class ConfirmRealSlotMutation(val reason: OccupiedSlotReason) : PendingQuestion {
        override val title = R.string.debug_confirm_occupied_title
        override val confirm = R.string.debug_confirm_occupied_yes
        override val decline = R.string.action_cancel
    }
}
