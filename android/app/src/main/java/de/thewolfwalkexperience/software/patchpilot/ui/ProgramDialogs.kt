// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
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
 */
@Composable
internal fun RenameDialog(
    target: PresetSlot,
    text: String,
    onTextChange: (String) -> Unit,
    maxNameLength: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_rename_title, target.displayId)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { typed -> onTextChange(if (maxNameLength != null) typed.take(maxNameLength) else typed) },
                label = { Text(stringResource(R.string.programs_new_name)) },
                supportingText = maxNameLength?.let {
                    { Text(stringResource(R.string.programs_name_counter, text.length, it)) }
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = onConfirm) {
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
