// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.ui.theme.BarAction
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle

@Composable
fun ConnectScreen(viewModel: InstrumentViewModel, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = LocalThemeStyle.current

    // The report is reachable from the untested-firmware gate, not only after continuing: someone
    // who does not want to touch an instrument the app cannot vouch for can still send back what
    // would let it be supported. The instrument is already connected here, since the advisory is
    // raised after the handshake.
    // Whether the filename prompt is open, rather than the prompt itself: its callback belongs to
    // the composition that built it, so a rotation rebuilds it from [reportShare] instead.
    var shareOpen by rememberSaveable { mutableStateOf(false) }
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val interruptedEdit by viewModel.interruptedEdit.collectAsStateWithLifecycle()
    // Said rather than swallowed: a read the cable interrupted lands in the same Done as a whole
    // one, and the report is shared the moment it is read. Kept until the next read.
    var incompleteReads by rememberSaveable { mutableIntStateOf(0) }
    val reportShare = rememberDeviceReportShare(
        viewModel,
        onStarted = { incompleteReads = 0 },
        onShared = { _, failedReads -> incompleteReads = failedReads },
    )

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
                    // Shown only before the ViewModel's first attempt starts; the button keeps it
                    // from being a dead end all the same.
                    is ConnectionState.Disconnected -> {
                        Text(stringResource(R.string.connect_idle))
                        Spacer(Modifier.height(16.dp))
                        theme.FilledButton(onClick = {
                            viewModel.connection.connect()
                        }) { Text(stringResource(R.string.action_retry_search)) }
                    }

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
                        Text(
                            stringResource(R.string.programs_error, s.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        theme.FilledButton(onClick = {
                            viewModel.connection.retry()
                        }) { Text(stringResource(R.string.action_retry)) }
                    }

                    // Retry re-asks: the permission is still not held, so the next open puts the system
                    // dialog up again. Nothing else does - see ConnectionState.PermissionDenied.
                    // Retry asks about the same instrument again, so the picker is the way to another
                    // one attached beside it.
                    is ConnectionState.PermissionDenied -> {
                        Text(
                            stringResource(R.string.connect_usb_permission_denied, s.displayName),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.connect_permission_denied_hint))
                        Spacer(Modifier.height(16.dp))
                        theme.FilledButton(onClick = {
                            viewModel.connection.retry()
                        }) { Text(stringResource(R.string.action_retry)) }
                        TextButton(onClick = { viewModel.connection.chooseDevice() }) {
                            Text(stringResource(R.string.action_show_all_devices))
                        }
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
                        theme.FilledButton(onClick = {
                            viewModel.connection.retry()
                        }) { Text(stringResource(R.string.action_retry)) }
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
                            // A catalog instrument that is attached but silent is not "none found".
                            val silent = s.entries.any { it is PickerEntry.Unknown && it.looksLike != null }
                            Text(
                                stringResource(
                                    if (silent) R.string.connect_silent_found else R.string.connect_none_found,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            // Every attached device, including the ones that cannot be opened: hiding
                            // those would be indistinguishable from the device not being plugged in.
                            Text(stringResource(R.string.connect_pick_unknown))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Recognised entries come first from the view model, and each says which it is.
                        s.entries.forEach { entry ->
                            val sublabel = when (entry) {
                                is PickerEntry.Known -> entry.candidate.via.let { via ->
                                    if (via == null) {
                                        stringResource(R.string.connect_entry_known, entry.candidate.bus.label)
                                    } else {
                                        stringResource(
                                            R.string.connect_entry_known_via,
                                            entry.candidate.bus.label,
                                            via,
                                        )
                                    }
                                }

                                is PickerEntry.Unknown ->
                                    entry.looksLike
                                        ?.let { stringResource(R.string.connect_entry_silent, it) }
                                        ?: stringResource(R.string.connect_entry_unknown)
                            }
                            theme.DeviceRow(
                                label = entry.label,
                                sublabel = sublabel,
                                onClick = { viewModel.connection.selectEntry(entry) },
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
                        theme.FilledButton(onClick = {
                            viewModel.connection.connect()
                        }) { Text(stringResource(R.string.action_retry_search)) }
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
                        theme.FilledButton(onClick = {
                            viewModel.connection.connect()
                        }) { Text(stringResource(R.string.action_retry)) }
                    }

                    is ConnectionState.AdvisoryWarning -> AdvisoryGate(
                        instrumentName = s.instrument.identity.name,
                        message = s.message,
                        reportShare = reportShare.takeIf { capabilities.hasReport },
                        incompleteReads = incompleteReads,
                        onDisconnect = { viewModel.connection.disconnect() },
                        onContinue = { viewModel.connection.confirmAdvisory() },
                        onShareReport = { shareOpen = reportShare.dialog() != null },
                    )

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
                        interruptedEdit?.let { detail ->
                            Spacer(Modifier.height(8.dp))
                            Text(detail, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(16.dp))
                        theme.FilledButton(onClick = {
                            viewModel.connection.connect()
                        }) { Text(stringResource(R.string.action_retry_search)) }
                    }

                    is ConnectionState.UnknownDeviceWarning -> {
                        Text(
                            stringResource(R.string.connect_unknown_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(s.device.label)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.connect_unknown_warning),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row {
                            OutlinedButton(onClick = {
                                viewModel.connection.cancelUnknownDeviceSelection()
                            }) { Text(stringResource(R.string.action_cancel)) }
                            Spacer(Modifier.width(8.dp))
                            theme.FilledButton(onClick = {
                                viewModel.connection.confirmUnknownDevice(s.device)
                            }) { Text(stringResource(R.string.action_continue_anyway)) }
                        }
                    }
                }
                if (shareOpen) {
                    // Gone with the session it would name the report from: closed, not reopened later.
                    val pending = reportShare.dialog()
                    if (pending ==
                        null
                    ) {
                        SideEffect { shareOpen = false }
                    } else {
                        ShareFilenameDialog(pending) { shareOpen = false }
                    }
                }

                if (offersDemoMode(state)) {
                    Spacer(Modifier.height(24.dp))
                    // One demo device, not one per family: a second entry would read as a second
                    // instrument the app claims to support.
                    TextButton(onClick = { viewModel.connection.connectDemo() }) {
                        Text(stringResource(R.string.action_try_demo))
                    }
                }
            }
        }
    }
}

/**
 * Whether the connect screen offers demo mode in [state]. Not while a real connection attempt is
 * in flight or has landed: Connected navigates away, and Searching/Opening are moments when an
 * instrument might show up. Nor on the screens that ask about one instrument - the advisory, the
 * unknown device, the permission, the setting to change and the error: all lead back to the
 * device list, which offers it, and so does "No supported instrument was found".
 */
internal fun offersDemoMode(state: ConnectionState): Boolean = when (state) {
    ConnectionState.Searching,
    is ConnectionState.Opening,
    is ConnectionState.Connected,
    is ConnectionState.AdvisoryWarning,
    is ConnectionState.UnknownDeviceWarning,
    is ConnectionState.PermissionDenied,
    is ConnectionState.NeedsManualSetting,
    is ConnectionState.Error,
    -> false

    ConnectionState.Disconnected,
    is ConnectionState.NothingFound,
    is ConnectionState.DeviceSelection,
    is ConnectionState.DeviceLost,
    -> true
}

/**
 * The untested-firmware or untested-model question: disconnect, or continue anyway. The same
 * shape as the unknown-device gate, since it is the same kind of question and a second visual
 * language for it would make it easier to click past.
 *
 * "Continue anyway" waits for a report being read: it leaves this screen, which is what shares
 * the report, so the read would finish with nobody to hand it to. Disconnect stays live, since it
 * cancels the read.
 *
 * @param reportShare the report flow, or null where the instrument has no report.
 */
@Composable
internal fun AdvisoryGate(
    instrumentName: String,
    message: String,
    reportShare: DeviceReportShare?,
    incompleteReads: Int,
    onDisconnect: () -> Unit,
    onContinue: () -> Unit,
    onShareReport: () -> Unit,
) {
    val theme = LocalThemeStyle.current
    val reading = reportShare?.running == true
    Text(
        stringResource(R.string.connect_advisory_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(instrumentName)
    Spacer(Modifier.height(16.dp))
    Text(message, color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Row {
        OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(R.string.action_disconnect))
        }
        Spacer(Modifier.width(8.dp))
        theme.FilledButton(onClick = onContinue, enabled = !reading) {
            Text(stringResource(R.string.action_continue_anyway))
        }
    }
    if (reportShare != null) {
        TextButton(onClick = onShareReport, enabled = !reading) {
            Text(reportShare.label)
        }
        reportShare.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
    if (incompleteReads > 0) {
        Text(
            stringResource(R.string.report_shared_incomplete, incompleteReads),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
