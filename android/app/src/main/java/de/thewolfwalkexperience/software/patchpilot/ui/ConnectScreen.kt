package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.usb.displayLabel
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ConnectScreen(viewModel: InstrumentViewModel, onConnected: () -> Unit, onOpenSettings: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val theme = LocalThemeStyle.current

    // **The report is reachable from the untested-firmware gate**, not only after continuing.
    // Someone who does not want to touch an instrument the app cannot vouch for should still be
    // able to send back the one thing that would let it be supported - and telling them to
    // continue first, into the very session they were hesitating about, is a poor way to ask.
    // The instrument is already connected here (the advisory is raised after the handshake), so
    // the reporter facet is available exactly as it is on the program screen.
    var pendingShare by remember { mutableStateOf<PendingShare?>(null) }
    val sharer = rememberReportSharer(viewModel)
    val reportShareTitle = stringResource(R.string.programs_share_report)
    val jsonSuffix = stringResource(R.string.programs_json_suffix)

    LaunchedEffect(Unit) {
        if (state is ConnectionState.Disconnected) viewModel.connect()
    }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connected) onConnected()
    }

    Box(
        // No Scaffold here: this screen is a centred hero with its own title, and a top app bar
        // would just repeat it. The frame goes on this outer, full-bleed box - edge to edge, the
        // same true screen bounds `PatchPilotScaffold` frames - rather than on the inset box
        // below; putting it on the inset box crowded the rivets against both the settings icon
        // and the system status bar, and left Connect's border sitting inside the frame Programs
        // draws flush with the edge.
        modifier = Modifier
            .fillMaxSize()
            .let { theme.screenFrame(it) },
    ) {
    Box(
        // Clears the system bars itself under edge-to-edge, same as before - just no longer the
        // box the frame itself is measured against.
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
    IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
    }
    Column(
        // Scrollable, not just centred: NothingFound's supported-instrument list alone is ten
        // names (and grows every time a device is added, see [ConnectionState.NothingFound]'s doc
        // comment), which overflows a landscape phone's height on its own before the retry/demo
        // buttons below it are even counted - and Arrangement.Center on a fixed-height Column
        // clips instead of scrolling, leaving those buttons genuinely unreachable rather than just
        // scrolled past. Short content still centres between the scroll bounds exactly as before.
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
                // One message for both buses. The USB branch used to claim "Waiting for USB
                // permission...", which is true only the first time an instrument is plugged in:
                // `UsbConnectionManager.requestPermission` returns immediately once permission
                // has been granted, and this state also covers opening the endpoints and the
                // family's handshake - so for every connection after the first it named a step
                // that never happened. Nothing is lost by dropping it: when a prompt really is
                // raised, the system puts its own dialog on top of this.
                Text(stringResource(R.string.connect_opening, s.displayName, s.bus.label))
            }
            is ConnectionState.Error -> {
                Text(stringResource(R.string.programs_error, s.message), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            // Deliberately not styled as an error. Nothing is broken and nothing failed that the
            // user can read as their fault - the instrument is listening on a different port, and
            // what they need is the sequence that changes it, legible enough to follow while
            // standing at the instrument.
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
                Button(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            is ConnectionState.Connected -> {
                Text(stringResource(R.string.connect_connected, s.instrument.identity.name))
                Text(stringResource(R.string.connect_firmware, s.instrument.identity.firmwareVersion))
            }
            is ConnectionState.DeviceSelection -> {
                val recognised = s.entries.count { it is PickerEntry.Known }
                // The heading depends on why we are here, because the two cases are opposites.
                // With an instrument attached this is a choice among things that work (one entry
                // if the user asked to see the picker after backing out with a single device
                // still plugged in, more than one if several are); with none it is a last resort.
                // Saying "No supported instrument was found" in the first case would be plainly
                // untrue.
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
                    // Every attached device is listed, including the ones that cannot be opened.
                    // Hiding those would be indistinguishable from the device not being plugged
                    // in, and would send somebody hunting a cable fault that does not exist - so
                    // they are shown, they stay selectable, and picking one explains itself
                    // below.
                    Text(stringResource(R.string.connect_pick_unknown))
                }
                Spacer(Modifier.height(8.dp))
                // Recognised entries come first from the view model. Each says which it is, so a
                // list mixing "your Nord Grand" with "some USB hub" cannot be misread.
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
                Button(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry_search)) }
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
                Button(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry)) }
            }
            is ConnectionState.AdvisoryWarning -> {
                // Same shape as the unknown-device gate below, deliberately: the user is being
                // asked the same kind of question - "this will probably work, do you want to" -
                // and a second visual language for it would only make it easier to click past.
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
                    Button(onClick = { viewModel.confirmAdvisory() }) {
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
                                onConfirm = { stem -> sharer.share(stem) },
                            )
                        },
                        enabled = !sharer.isRunning,
                    ) {
                        Text(sharer.progress ?: stringResource(R.string.programs_share_report))
                    }
                }
                sharer.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
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
                Button(onClick = { viewModel.connect() }) { Text(stringResource(R.string.action_retry_search)) }
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
                    Button(onClick = { viewModel.confirmUnknownDevice(s.device) }) { Text(stringResource(R.string.action_continue_anyway)) }
                }
            }
        }
        pendingShare?.let { pending ->
            ShareFilenameDialog(pending) { pendingShare = null }
        }

        // Not offered while a real connection attempt is in flight or has already landed -
        // Connected navigates away on its own, and Searching/Opening are moments
        // when a real instrument might still show up.
        if (state !is ConnectionState.Searching &&
            state !is ConnectionState.Opening &&
            state !is ConnectionState.Connected
        ) {
            Spacer(Modifier.height(24.dp))
            // One demo device, not one per profile. DemoInstrument's second (Pro-800-shaped)
            // profile still exists, but as a *test* fixture for the screens' facet gating;
            // offering it here read as a second instrument the app claims to support.
            TextButton(onClick = { viewModel.connectDemo() }) { Text(stringResource(R.string.action_try_demo)) }
        }
    }
    }
    }
}
