// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot

/*
 * The preset screen's dialogs. Each is self-contained and reads no screen state beyond what it is
 * handed, which is what lets them live apart from `ProgramsScreen`.
 */

/**
 * The one operation on the preset screen that asks first: move and swap leave the data somewhere
 * (see `runRelocation`), while a delete has nowhere for the preset to have gone, and nothing in
 * this app can put it back.
 *
 * @param emulated true where the family composes the erase from a write rather than issuing one
 *   command, which decides which effect sentence is shown.
 */
@Composable
internal fun DeleteConfirmationDialog(
    target: PresetSlot,
    emulated: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_delete_title, target.name ?: target.displayId)) },
        text = {
            Column {
                Text(
                    target.name?.let {
                        stringResource(R.string.programs_delete_body_named, target.displayId, it)
                    } ?: stringResource(R.string.programs_delete_body, target.displayId),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        if (emulated) {
                            // Every family but the Nord: an empty or initialised preset is
                            // written over the slot.
                            R.string.programs_delete_effect_emulated
                        } else {
                            // Nord, and demo mode, which mirrors one: sub-opcode 20/21, space
                            // is only reclaimable, not freed.
                            R.string.programs_delete_effect_native
                        },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        // Cancel is listed second but is the safe choice; it is also what a tap outside does.
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * An operation the instrument's own state blocked, and the fix the family offered for it.
 *
 * Answerable rather than merely reportable - but the remedy changes what the instrument is
 * playing, so it is never applied without asking.
 */
@Composable
internal fun BlockedOperationDialog(
    pending: BlockedOperation,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_blocked_title)) },
        text = {
            Column {
                Text(pending.message)
                pending.detail?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it)
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply) { Text(pending.actionLabel) } },
        // Cancelling leaves the instrument as it is, which is also what a tap outside does: the
        // remedy has to be chosen rather than arrived at by dismissing something.
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Renames one preset.
 *
 * @param maxNameLength null where the instrument declares no limit. Where it does, the field is
 *   capped as the user types rather than validated afterwards, since the instrument accepts an
 *   over-long name and quietly stores its first characters.
 * @param nameProblem why a name cannot be stored, or null - asked of the trimmed text, which is
 *   what a rename sends. Shown in place of the counter, and Rename stays off while there is one.
 */
@Composable
internal fun RenameDialog(
    target: PresetSlot,
    text: String,
    onTextChange: (String) -> Unit,
    maxNameLength: Int?,
    nameProblem: (String) -> String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Not asked of a blank field: an empty box is not yet a mistake, and Rename is off anyway.
    val problem = if (text.isBlank()) null else nameProblem(text.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_rename_title, target.displayId)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    onTextChange(if (maxNameLength != null) typed.take(maxNameLength) else typed)
                },
                label = { Text(stringResource(R.string.programs_new_name)) },
                isError = problem != null,
                supportingText = when {
                    problem != null -> {
                        { Text(problem) }
                    }

                    maxNameLength != null -> {
                        { Text(stringResource(R.string.programs_name_counter, text.length, maxNameLength)) }
                    }

                    else -> null
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && problem == null, onClick = onConfirm) {
                Text(stringResource(R.string.action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * One [de.thewolfwalkexperience.software.patchpilot.core.Instrument.notices] entry, shown when a
 * session connects. Only acknowledged, never answered: the checkbox decides whether it comes back
 * on the next app start, and Settings can bring a hidden one back.
 */
@Composable
internal fun InstrumentNoticeDialog(
    notice: InstrumentNotice,
    onDismiss: (hideForever: Boolean) -> Unit,
) {
    var hideForever by rememberSaveable(notice.id) { mutableStateOf(false) }
    AlertDialog(
        // A tap outside closes it for this run only, like OK with the box unticked.
        onDismissRequest = { onDismiss(false) },
        title = { Text(notice.title) },
        text = {
            Column {
                Text(notice.message)
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = hideForever,
                            onValueChange = { hideForever = it },
                            role = Role.Checkbox,
                        ),
                ) {
                    Checkbox(checked = hideForever, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.notice_hide_forever))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(hideForever) }) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/**
 * What the app knows about one preset - see [buildPresetInfo]. Read-only, so it has a single
 * button; the content scrolls where a family with many rows or a large font does not fit.
 *
 * The name is the title and the id the subtitle; facts are label/value pairs on one line, and the
 * MIDI values sit side by side in one block, since they are read together and sent together.
 */
@Composable
internal fun PresetInfoDialog(info: PresetInfo, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(info.title)
                Text(
                    info.subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                info.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            "${row.label}:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(0.38f),
                        )
                        Text(
                            row.value,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(0.62f),
                        )
                    }
                }
                info.midi?.let { midi ->
                    if (info.rows.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    }
                    MidiBlockContent(midi)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun MidiBlockContent(midi: MidiBlock) {
    if (midi.cells.isEmpty()) {
        midi.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Text(stringResource(R.string.info_midi_heading), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        midi.cells.forEach { cell ->
            Column(Modifier.weight(1f)) {
                Text(
                    cell.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    cell.caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(cell.value?.toString() ?: "\u2013", style = MaterialTheme.typography.headlineSmall)
                Text(
                    cell.value?.let { "0x" + it.toString(16).uppercase().padStart(2, '0') }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    midi.note?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
