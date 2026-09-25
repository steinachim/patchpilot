// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.usb.displayLabel
import de.thewolfwalkexperience.software.patchpilot.ui.theme.BarAction
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ConnectScreen(viewModel: InstrumentViewModel, onConnected: () -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val theme = LocalThemeStyle.current

    // The report is reachable from the untested-firmware gate, not only after continuing: someone
    // who does not want to touch an instrument the app cannot vouch for can still send back what
    // would let it be supported. The instrument is already connected here, since the advisory is
    // raised after the handshake.
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    // Read in the ViewModel and shared as soon as it is done, as on ProgramsScreen: the read is
    // minutes on a Nord and must not restart on rotation.
    val reportRunner = viewModel.deviceReportRunner
    val reportState by reportRunner.state.collectAsState()
    val reportError by reportRunner.error.collectAsState()
    var pendingReportStem by rememberSaveable { mutableStateOf<String?>(null) }
    // Said rather than swallowed: a read the cable interrupted lands in the same Done as a whole
    // one, and the report is shared the moment it is read. Kept until the next read.
    var incompleteReads by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    val reportShareTitle = stringResource(R.string.programs_share_report)
    val jsonSuffix = stringResource(R.string.programs_json_suffix)
    val readingLabel = stringResource(R.string.share_reading_device)
    LaunchedEffect(reportState, pendingReportStem) {
        val stem = pendingReportStem ?: return@LaunchedEffect
        val done = reportState as? DeviceReportState.Done ?: return@LaunchedEffect
        pendingReportStem = null
        incompleteReads = done.failures.size
        reportRunner.dismiss()
        shareTextReport(context, "$stem$jsonSuffix", done.json, JSON_MIME_TYPE, reportShareTitle)
    }

    LaunchedEffect(Unit) {
        if (state is ConnectionState.Disconnected) viewModel.connect()
    }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected) onConnected()
    }

    Box(
        // No Scaffold: this screen is a centred hero with its own title, which a top app bar would
        // repeat. The frame goes on this outer, full-bleed box - the same screen bounds
        // `PatchPilotScaffold` frames - rather than on the inset box below, which would sit
        // Connect's border inside the frame Programs draws flush with the edge.
        modifier = Modifier
            .fillMaxSize()
            .let { theme.screenFrame(it) },
    ) {
    Box(
        // Clears the system bars itself under edge-to-edge; the frame above is measured against
        // the full screen, not against this. The capped gesture inset too (see AppScaffold): a
        // side with no bar (most phones, both sides; large screens, the settings-gear side) gets
        // no inset at all from `safeDrawing` alone, which on a device with edge-swipe-back
        // navigation puts a tap right inside the swipe strip - discoverable by pressing there,
        // not by looking at it.
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.union(cappedSystemGestures())),
    ) {
    // Placed where `PatchPilotScaffold` puts its own actions, so the gear does not jump when the
    // preset screen replaces this one: centred in a bar-height strip, an icon button's own 4.dp
    // from the end. Material's `TopAppBar` metrics are not public, hence the two constants.
    //
    // `zIndex` because the scrollable Column below is a later sibling that also fills the whole
    // screen: its own content is centred and never draws up here, but its layout bounds still
    // cover this corner, and a later sibling wins hit-testing over an earlier one wherever they
    // overlap. Without this, part of the gear's own reported touch target silently belonged to
    // the Column's scroll gesture detector instead of the button.
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .height(TOP_BAR_HEIGHT)
            .padding(end = TOP_BAR_ACTION_INSET)
            .zIndex(1f),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onOpenSettings) {
            theme.ActionIcon(BarAction.Settings, stringResource(R.string.cd_settings))
        }
    }
    Column(
        // Scrollable, not only centred: NothingFound's supported-instrument list overflows a
        // landscape phone before the retry and demo buttons are counted, and Arrangement.Center
        // on a fixed-height Column clips rather than scrolling, leaving them unreachable. Short
        // content still centres between the scroll bounds.
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Image(
            painter = painterResource(R.drawable.patch_pilot_logo),
            contentDescription = null,
            modifier = Modifier.size(128.dp),
        )
        Spacer(Modifier.height(16.dp))
        when (val s = state) {
            is ConnectionState.Disconnected -> Text(stringResource(R.string.connect_idle))
            is ConnectionState.Searching -> {
                theme.ProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.connect_searching))
            }
            is ConnectionState.Opening -> {
                theme.ProgressIndicator()
                Spacer(Modifier.height(8.dp))
                // One message for both buses, without naming the permission prompt: this state
                // also covers opening the endpoints and the family's handshake, and the prompt
                // only appears the first time an instrument is plugged in - with the system's own
                // dialog on top of this.
                Text(stringResource(R.string.connect_opening, s.displayName, s.bus.label))
            }
            is ConnectionState.Error -> {
                Text(stringResource(R.string.programs_error, s.message), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            // Retry re-asks: the permission is still not held, so the next open puts the system
            // dialog up again. Nothing else does - see ConnectionState.PermissionDenied.
            is ConnectionState.PermissionDenied -> {
                Text(
                    stringResource(R.string.connect_usb_permission_denied, s.displayName),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.connect_permission_denied_hint))
                Spacer(Modifier.height(16.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            // Not styled as an error: nothing is broken, the instrument is listening on a
            // different port, and what the user needs is the sequence that changes it, legible
            // while standing at the instrument.
            is ConnectionState.NeedsManualSetting -> {
                Text(s.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                s.steps.forEachIndexed { index, step ->
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                s.alsoCheck?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            is ConnectionState.Connected -> {
                Text(stringResource(R.string.connect_connected, s.instrument.identity.name))
                Text(stringResource(R.string.connect_firmware, s.instrument.identity.firmwareVersion))
            }
            is ConnectionState.DeviceSelection -> {
                val recognised = s.entries.count { it is PickerEntry.Known }
                // The heading depends on why we are here: with an instrument attached this is a
                // choice among things that work, with none it is a last resort.
                if (recognised > 0) {
                    Text(
                        stringResource(
                            if (recognised > 1) R.string.connect_multiple_found else R.string.connect_one_found,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.connect_pick_one))
                } else {
                    Text(stringResource(R.string.connect_none_found))
                    Spacer(Modifier.height(8.dp))
                    // Every attached device, including the ones that cannot be opened: hiding
                    // those would be indistinguishable from the device not being plugged in.
                    Text(stringResource(R.string.connect_pick_unknown))
                }
                Spacer(Modifier.height(8.dp))
                // Recognised entries come first from the view model, and each says which it is.
                s.entries.forEach { entry ->
                    val sublabel = when (entry) {
                        is PickerEntry.Known -> stringResource(
                            R.string.connect_entry_known, entry.candidate.bus.label,
                        )
                        is PickerEntry.Unknown -> stringResource(R.string.connect_entry_unknown)
                    }
                    theme.DeviceRow(
                        label = entry.label,
                        sublabel = sublabel,
                        onClick = { viewModel.selectEntry(entry) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                s.message?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry_search)) }
            }
            is ConnectionState.NothingFound -> {
                Text(stringResource(R.string.connect_none_found))
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.connect_supported_heading),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    s.supportedNames.forEach { name ->
                        Text(name)
                    }
                }
                Spacer(Modifier.height(16.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            is ConnectionState.AdvisoryWarning -> {
                // The same shape as the unknown-device gate below: the same kind of question, and
                // a second visual language for it would make it easier to click past.
                Text(
                    stringResource(R.string.connect_advisory_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(s.instrument.identity.name)
                Spacer(Modifier.height(16.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Row {
                    OutlinedButton(onClick = { viewModel.declineAdvisory() }) {
                        Text(stringResource(R.string.action_disconnect))
                    }
                    Spacer(Modifier.width(8.dp))
                    theme.FilledButton(onClick = { viewModel.confirmAdvisory() }) {
                        Text(stringResource(R.string.action_continue_anyway))
                    }
                }
                if (viewModel.hasReport) {
                    TextButton(
                        onClick = {
                            pendingShare = PendingShare(
                                title = reportShareTitle,
                                description = viewModel.reportDescription,
                                suffix = jsonSuffix,
                                initialStem = viewModel.suggestedReportFilename(),
                                onConfirm = { stem ->
                                    pendingReportStem = stem
                                    incompleteReads = 0
                                    reportRunner.start(readingLabel)
                                },
                            )
                        },
                        enabled = reportState !is DeviceReportState.Running,
                    ) {
                        Text((reportState as? DeviceReportState.Running)?.step ?: reportShareTitle)
                    }
                }
                reportError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                if (incompleteReads > 0) {
                    Text(
                        stringResource(R.string.report_shared_incomplete, incompleteReads),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            is ConnectionState.DeviceLost -> {
                Text(
                    stringResource(R.string.connect_device_lost_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.connect_device_lost_message, s.instrumentName),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                theme.FilledButton(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry_search)) }
            }
            is ConnectionState.UnknownDeviceWarning -> {
                Text(stringResource(R.string.connect_unknown_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.device.displayLabel())
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.connect_unknown_warning),
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
                Row {
                    OutlinedButton(onClick = { viewModel.cancelUnknownDeviceSelection() }) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    theme.FilledButton(onClick = { viewModel.confirmUnknownDevice(s.device) }) { Text(stringResource(R.string.action_continue_anyway)) }
                }
            }
        }
        pendingShare?.let { pending ->
            ShareFilenameDialog(pending) { pendingShare = null }
        }

        // Not offered while a real connection attempt is in flight or has landed: Connected
        // navigates away, and Searching/Opening are moments when an instrument might show up.
        if (state !is ConnectionState.Searching &&
            state !is ConnectionState.Opening &&
            state !is ConnectionState.Connected
        ) {
            Spacer(Modifier.height(24.dp))
            // One demo device, not one per family: a second entry would read as a second
            // instrument the app claims to support.
            TextButton(onClick = { viewModel.connectDemo() }) { Text(stringResource(R.string.action_try_demo)) }
        }
    }
    }
    }
}
