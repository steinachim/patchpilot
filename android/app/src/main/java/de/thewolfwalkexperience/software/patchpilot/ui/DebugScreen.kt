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
 *   preset screen's own button is back to appearing only for a device outside the catalog. Read
 *   once, then offered for sharing or saving - the regression test's shape, rather than a share
 *   button and a save button that each read the instrument on their own.
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
    // The device report once it has been read, with everything sharing it needs - null until
    // then. Saved for the same reason a finished regression run is: a report that took a couple
    // of minutes to read must not vanish on rotation. The report is bounded (the largest part of
    // a Nord's is a few hundred program names), so it is nowhere near what a Bundle can carry.
    // The read in progress is `sharer.progress`, which does not survive rotation any more than a
    // regression run does, and for the same reason - see [RegressionRunStateSaver].
    var deviceReport by rememberSaveable(stateSaver = HeldDeviceReportSaver) {
        mutableStateOf<HeldDeviceReport?>(null)
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val jsonSuffix = stringResource(R.string.programs_json_suffix)
    val txtSuffix = stringResource(R.string.programs_txt_suffix)
    val reportShareTitle = stringResource(R.string.programs_share_report)
    val regressionShareTitle = stringResource(R.string.debug_regression_share)

    // Holds whichever report is waiting on the "Save to device" picker below to return a Uri -
    // there is only ever one save in flight at a time, so one field covers both the device report
    // and the regression report rather than needing a launcher each with its own.
    var pendingSaveContent by remember { mutableStateOf<String?>(null) }
    fun onSaveResult(uri: Uri?) {
        val content = pendingSaveContent
        pendingSaveContent = null
        if (uri != null && content != null) saveTextReport(context, uri, content)
    }
    val saveJsonLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME_TYPE)) { onSaveResult(it) }
    val saveTextLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT_MIME_TYPE)) { onSaveResult(it) }

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
                // Resolved before the run and kept with the report: the instrument that names
                // the file is the one about to be tested, and it is not guaranteed to still be
                // connected when Share is tapped - see [HeldDeviceReport].
                val stem = viewModel.filenameStem
                val report = viewModel.runRegressionTest(
                    onConfirmSelect = { shown -> ask(PendingQuestion.ConfirmSelect(shown)) },
                    onConfirmRealSlotMutation = { reason -> ask(PendingQuestion.ConfirmRealSlotMutation(reason)) },
                    progress = { step -> run = RegressionRunState.Running(step) },
                )
                run = RegressionRunState.Done(report, stem)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                run = RegressionRunState.Idle
                error = e.message
            }
        }
    }

    // Back out of a *report* - either kind - to the debug menu, not out of the debug screen.
    //
    // The reports are rendered inside this screen rather than on routes of their own, so the
    // scaffold's arrow was leaving for the programs list and skipping the menu the user had just
    // come from - which reads as the app losing your place. A finished report is a state of this
    // screen, so back clears the state; only from the menu itself does back actually leave.
    val atMenu = run !is RegressionRunState.Done && deviceReport == null
    // Clears both; only one is ever set, since each report hides the button that starts the other.
    fun backToMenu() {
        run = RegressionRunState.Idle
        deviceReport = null
    }
    val handleBack: () -> Unit = { if (atMenu) onBack() else backToMenu() }
    // Also catches the system/gesture back, which otherwise disagrees with the arrow beside it.
    BackHandler(enabled = !atMenu) { backToMenu() }

    // A run in progress is not dismissable at all. It is writing to the instrument, and it runs
    // on this screen's scope, so leaving the screen would cancel it between steps - after the
    // test's copy is made, or between a swap and its swap-back - with nothing left to put the
    // instrument right again. So the arrow is greyed out and the gesture swallowed, the presets
    // screen's shape during an edit. The wait is bounded: a run is a handful of writes with
    // pauses between them, and an instrument that stops answering fails the run through the
    // exchange timeouts. Never enabled together with the handler above, since `Running` counts
    // as `atMenu` - so which of the two Compose consults first does not matter.
    //
    // The device report read is *not* held like this: it writes nothing, so leaving mid-read
    // costs only the read.
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
            (error ?: sharer.error)?.let {
                Text(stringResource(R.string.programs_error, it), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            val state = run
            val report = deviceReport
            val reading = sharer.progress
            when {
                // Precedence, not state: at most one of the first four holds at a time, since each
                // replaces the menu and with it the buttons that start the others. The device
                // report is read on demand and then *held* - the same shape as the regression run,
                // and for the same reason: a single "Generate" whose result offers Share and Save
                // says what each does, where "Save to device" as a second entry point did not
                // (does it generate? must one generate first?).
                report != null -> DeviceReportReadyView(
                    sizeBytes = report.json.toByteArray().size,
                    onShare = {
                        pendingShare = PendingShare(
                            title = reportShareTitle,
                            description = report.description,
                            suffix = jsonSuffix,
                            initialStem = report.stem,
                            // From memory, not `sharer.share` - that would read the instrument
                            // again, and the read is the slow part this view exists to keep.
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
                    onSave = {
                        pendingSaveContent = report.json
                        saveJsonLauncher.launch(report.stem + jsonSuffix)
                    },
                    onBackToMenu = ::backToMenu,
                )
                reading != null -> RunningView(stringResource(R.string.debug_report_running), reading)
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
                    onSave = {
                        // Already in memory and synchronous, unlike the device report - no
                        // ReportSharer round trip needed before the picker can launch.
                        pendingSaveContent = state.report.asText()
                        saveTextLauncher.launch("${state.stem}_capabilities$txtSuffix")
                    },
                    onBackToMenu = ::backToMenu,
                )
                else -> {
                    Text(stringResource(R.string.debug_report_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    // Gated on the factory-name check, whose progress and verdict live inline on
                    // this menu: a report read replaces the menu, and would hide them mid-read.
                    Button(
                        onClick = {
                            // The stem and description are resolved in the callback, which
                            // ReportSharer runs inside the same try as the read: a teardown
                            // in the meantime then fails the report the way a failed read does,
                            // instead of throwing out of this click.
                            sharer.build { json ->
                                deviceReport = HeldDeviceReport(
                                    json = json,
                                    stem = viewModel.suggestedReportFilename(),
                                    description = viewModel.reportDescription,
                                )
                            }
                        },
                        enabled = viewModel.hasReport && verifyProgress == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.debug_report_action))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.debug_regression_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onRunRegressionTest() },
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

    /** [stem] is the instrument's filename stem, resolved before the run - see [HeldDeviceReport]. */
    data class Done(val report: RegressionReport, val stem: String) : RegressionRunState
}

private fun regressionRunStateToStrings(state: RegressionRunState): List<String> {
    val done = state as? RegressionRunState.Done ?: return emptyList()
    val flattened = mutableListOf(done.stem, done.report.summary)
    done.report.results.forEach { result ->
        flattened.add(result.name)
        flattened.add(result.status.name)
        flattened.add(result.detail)
    }
    return flattened
}

private fun regressionRunStateFromStrings(saved: List<String>): RegressionRunState {
    if (saved.isEmpty()) return RegressionRunState.Idle
    val stem = saved[0]
    val summary = saved[1]
    val results = saved.drop(2).chunked(3).map { triple ->
        RegressionResult(triple[0], Status.valueOf(triple[1]), triple[2])
    }
    return RegressionRunState.Done(RegressionReport(results, summary), stem)
}

/**
 * A device report once read, together with the filename stem and the description the share
 * dialog shows for it - both the instrument's own wording, resolved while it was connected.
 *
 * **Everything a held report needs is held with it, because the instrument may be gone by the
 * time Share is tapped.** Sharing opens the system chooser, which is an Activity of its own: the
 * app pauses under it and resumes when it closes, and a resume rebuilds a USB session from
 * scratch (`MainActivity.onResume`, `Instrument.rebuildOnResume`). Between the teardown and the
 * reconnect there is no instrument, while this report - `rememberSaveable` - is still on screen
 * with its buttons live. Reading `filenameStem` there crashed the app on a Nord Grand the second
 * time Share was tapped (2026-09-17). The regression run keeps its stem the same way.
 */
private data class HeldDeviceReport(val json: String, val stem: String, val description: String)

private val HeldDeviceReportSaver: Saver<HeldDeviceReport?, Any> = listSaver(
    save = { held -> if (held == null) emptyList() else listOf(held.json, held.stem, held.description) },
    restore = { saved -> if (saved.isEmpty()) null else HeldDeviceReport(saved[0], saved[1], saved[2]) },
)

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
