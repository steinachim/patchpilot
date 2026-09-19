// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
 * Just the theme picker for now - the one setting the app has. A radio list rather than a
 * single on/off switch even though there are only two entries: [AppTheme] is what a third skin
 * would extend, and a switch has nowhere to grow to that a radio list already does.
 *
 * Applies the choice immediately - [ThemePreferences.setTheme] writes through DataStore, which
 * [MainActivity] is already collecting as the live `appTheme` passed to `PatchPilotTheme`, so the
 * whole app recomposes into the new skin without a restart.
 */
@Composable
fun SettingsScreen(themePreferences: ThemePreferences, onOpenLicenses: () -> Unit, onBack: () -> Unit) {
    val current by themePreferences.theme.collectAsState(initial = AppTheme.Default)
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
