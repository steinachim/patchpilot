// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.ui.theme.AppTheme
import de.thewolfwalkexperience.software.patchpilot.ui.theme.ThemePreferences
import kotlinx.coroutines.launch

/**
 * The theme picker, the auto-connect toggle, bringing back hidden instrument notices, and the
 * legal links. The theme picker is a radio list rather than a switch, since [AppTheme] is what a
 * third skin would extend. Every setting applies immediately: each writes through DataStore,
 * which its readers collect live.
 */
@Composable
fun SettingsScreen(
    themePreferences: ThemePreferences,
    connectionPreferences: ConnectionPreferences,
    noticePreferences: NoticePreferences,
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val current by themePreferences.theme.collectAsState(initial = AppTheme.Default)
    val autoConnect by connectionPreferences.autoConnectToFirstFound.collectAsState(initial = true)
    val hiddenNotices by noticePreferences.hidden.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    PatchPilotScaffold(title = stringResource(R.string.settings_title), onBack = onBack) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Text(
                stringResource(R.string.settings_theme_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            ThemeOption(
                title = stringResource(R.string.settings_theme_default),
                description = stringResource(R.string.settings_theme_default_description),
                selected = current == AppTheme.Default,
                onSelect = { scope.launch { themePreferences.setTheme(AppTheme.Default) } },
            )
            ThemeOption(
                title = stringResource(R.string.settings_theme_steampunk),
                description = stringResource(R.string.settings_theme_steampunk_description),
                selected = current == AppTheme.Steampunk,
                onSelect = { scope.launch { themePreferences.setTheme(AppTheme.Steampunk) } },
            )
            Text(
                stringResource(R.string.settings_connection_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_auto_connect)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_connect_description)) },
                // onCheckedChange = null leaves the click to the row, so TalkBack reads one switch.
                trailingContent = { Switch(checked = autoConnect, onCheckedChange = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = autoConnect,
                        onValueChange = { enabled ->
                            scope.launch { connectionPreferences.setAutoConnectToFirstFound(enabled) }
                        },
                        role = Role.Switch,
                    ),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_show_hidden_notices)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (hiddenNotices.isEmpty()) {
                                R.string.settings_show_hidden_notices_none
                            } else {
                                R.string.settings_show_hidden_notices_description
                            },
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hiddenNotices.isNotEmpty()) {
                        scope.launch { noticePreferences.showAll() }
                    },
            )
            Text(
                stringResource(R.string.settings_legal_heading),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_open_source_licenses)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLicenses),
            )
        }
    }
}

@Composable
private fun ThemeOption(title: String, description: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
    )
}
