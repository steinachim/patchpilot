// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot

/*
 * The preset screen's three question dialogs.
 *
 * Lifted out of `ProgramsScreen` together: each is self-contained, none of them reads the screen's
 * state beyond what it is handed, and as inline `AlertDialog` blocks at the tail of an already long
 * composable they were the easiest part of it to lose track of.
 */

/**
 * The one operation on the preset screen that asks first.
 *
 * Move and swap deliberately do not (see `runRelocation`): they were verified on hardware, they
 * leave the data somewhere, and a dialog in front of every drag is friction the reliability does
 * not justify. Delete is the opposite case - there is nowhere for the preset to have gone
 * afterwards, and on two of the three families nothing in this app can put it back.
 *
 * @param emulated true where the family composes the erase from a write rather than issuing one
 *   command, which is what decides which of the two effect sentences is shown.
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
                            // Pro-800, Motif XS: an initialised preset is written over the slot.
                            R.string.programs_delete_effect_emulated
                        } else {
                            // Nord: sub-opcode 20/21, space is only reclaimable, not freed.
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
        // Cancelling leaves the instrument exactly as it is, which is why it is also what a tap
        // outside does: the remedy must be chosen, never arrived at by dismissing something.
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Renames one preset.
 *
 * @param maxNameLength null where the instrument declares no limit. Where it does, the field is
 *   capped as the user types rather than validated afterwards: the instrument accepts an over-long
 *   name and quietly stores its first characters, so without this the only sign is the write
 *   verification failing on a preset that was in fact saved.
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
