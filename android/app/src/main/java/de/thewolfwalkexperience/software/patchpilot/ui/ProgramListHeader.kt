// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope

/**
 * Everything above the list, in order: the scope selector, the advisory and picking banners, the
 * filter and the empty-slot toggle, and the lines saying what is unreadable or running. Emitted
 * straight into the caller's column.
 */
@Composable
internal fun ProgramListHeader(
    scopes: List<PresetScope>,
    browseScope: PresetScope,
    onSelectScope: (PresetScope) -> Unit,
    advisory: String?,
    ops: ProgramsController,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    showEmptySlots: Boolean,
    onShowEmptySlotsChange: (Boolean) -> Unit,
    index: PresetIndexState,
) {
    // Only where there is a choice to make; an instrument with one listing shows no selector.
    //
    // Disabled rather than hidden while an edit runs or a copy destination is being picked:
    // both are states the user is *in the middle of*, and a control that vanishes and comes
    // back shifts everything below it - in a list they may be reading at the time.
    if (scopes.size > 1) {
        ScopeSelector(
            scopes = scopes,
            selected = browseScope,
            // Off during an edit, not during a listing: a factory listing costs no round
            // trips, a favorites read interleaves with a running scan (`SysExExchange` locks
            // one request/reply at a time), and each scope keeps its own job. An edit is
            // different - `runEdit` reloads whichever scope is current when it finishes - and
            // picking a copy destination owns the list until it resolves.
            enabled = ops.busy == null && !ops.picking,
            onSelect = onSelectScope,
        )
    }
    // Stays for the whole session: the reason has to still be on screen when a rename goes
    // wrong an hour after the connect screen asked.
    if (advisory != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                advisory,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    // Picking a destination happens in the list itself rather than behind a dialog: occupied
    // rows grey out, empty rows show regardless of the filter, and tapping one resolves the
    // pick (see [ProgramsController.onPickConfirmed]). This banner is the persistent sign
    // that the mode is active, and its Cancel the only way out besides picking a target.
    ops.pickSource?.let { source ->
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        when (ops.pickIntent) {
                            PickIntent.COPY -> R.string.programs_copy_title
                            PickIntent.MOVE -> R.string.programs_move_title
                        },
                        source.name ?: source.displayId,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ops.endPicking() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
    // Hidden rather than disabled while picking: neither affects the list in that mode, and
    // the banner above already says what mode this is.
    if (!ops.picking) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            label = { Text(stringResource(R.string.programs_filter_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // One control, not two nodes, so TalkBack reads it as a single checkbox. Only in the
        // user listing: every factory slot holds a voice, and the favorites listing has no
        // address space to have gaps in.
        if (browseScope == PresetScope.USER) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = showEmptySlots,
                        onValueChange = onShowEmptySlotsChange,
                        role = Role.Checkbox,
                    )
                    .minimumInteractiveComponentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onCheckedChange = null leaves the click to the Row above, and also drops the
                // padding a standalone Checkbox brings, hence the explicit Spacer.
                Checkbox(checked = showEmptySlots, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.programs_show_empty))
            }
        }
    }
    index.failures.takeIf { it.isNotEmpty() }?.let { failures ->
        // Unreadable slots are a finding rather than a failure of the whole scan.
        Text(
            pluralStringResource(
                R.plurals.programs_unreadable_slots,
                failures.size,
                failures.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    ops.busy?.takeIf { it.showProgress }?.let { (what, _) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(what, style = MaterialTheme.typography.labelMedium)
            // Indeterminate: the instrument reports no progress through an edit.
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    index.progress?.let { progress ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(progress.label, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Which of the instrument's listings to show, plus a line saying what the current one is.
 *
 * Segmented buttons rather than tabs: these three differ in what can be done to a row, not only
 * in which rows are shown, and each segment carries `Role.RadioButton` and a selected state, so a
 * screen reader announces "Factory, selected, 2 of 3" without any semantics here.
 *
 * The caption keeps two of the listings from misrepresenting themselves: the factory names are
 * transcribed from the maker's documentation rather than read off the instrument, and the favorite
 * marks are the instrument's own, changed from a row menu rather than from the listing.
 */
@Composable
private fun ScopeSelector(
    scopes: List<PresetScope>,
    selected: PresetScope,
    enabled: Boolean,
    onSelect: (PresetScope) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            scopes.forEachIndexed { index, scope ->
                SegmentedButton(
                    selected = scope == selected,
                    onClick = { onSelect(scope) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = scopes.size),
                ) {
                    Text(stringResource(scope.labelRes()))
                }
            }
        }
        selected.noteRes()?.let { note ->
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The segment's label, kept out of [PresetScope], which is in `core` and knows nothing about resources. */
@StringRes
private fun PresetScope.labelRes(): Int = when (this) {
    PresetScope.USER -> R.string.programs_scope_user
    PresetScope.FACTORY -> R.string.programs_scope_factory
    PresetScope.FAVORITES -> R.string.programs_scope_favorites
}

/** What this listing has to admit about itself, or null where there is nothing to say. */
@StringRes
private fun PresetScope.noteRes(): Int? = when (this) {
    PresetScope.USER -> null
    PresetScope.FACTORY -> R.string.programs_scope_note_factory
    PresetScope.FAVORITES -> R.string.programs_scope_note_favorites
}
