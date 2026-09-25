// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.PresetTags

/*
 * The preset screen's two tagging dialogs. As in `ProgramDialogs`, each takes plain data and
 * callbacks and knows nothing about which instrument is plugged in: everything family-specific
 * arrives as a [CategoryTaxonomy] and a count.
 */

/**
 * Which of a preset's own categories the instrument should list it under. The checkboxes are the
 * preset's assignment slots rather than the whole taxonomy, since a Motif XS favorite flag
 * selects among the voice's two assignments rather than naming a category.
 *
 * A slot the voice has not assigned is still offered: marking one is legal and the instrument
 * does it itself, and the voice then appears in its Favorite bank under no category.
 *
 * @param tags what is stored now; [PresetTags.categories] positions the rows.
 */
@Composable
internal fun SetFavoriteDialog(
    target: PresetSlot,
    tags: PresetTags,
    taxonomy: CategoryTaxonomy,
    assignmentCount: Int,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val assigned = remember(tags) { tags.assigned }
    var checked by remember(tags) { mutableStateOf(tags.favoriteUnder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_favorite_title, target.displayId)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.programs_favorite_body))
                Spacer(Modifier.height(8.dp))
                // Every slot, not only the assigned ones, so a voice with one assignment can be
                // favorited under that category or under none. One row where the voice has
                // neither, rather than two both reading "No category": with nothing assigned
                // there is nothing to tell the slots apart, and slot 0 encodes to the value the
                // panel writes.
                val slotsShown = if (assigned.isEmpty()) 1 else assignmentCount
                (0 until slotsShown).forEach { slot ->
                    val category = tags.categories.getOrNull(slot)?.let(taxonomy::label)
                    val isOn = slot in checked
                    ListItem(
                        headlineContent = {
                            Text(category ?: stringResource(R.string.programs_no_category))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.programs_favorite_slot, slot + 1))
                        },
                        leadingContent = { Checkbox(checked = isOn, onCheckedChange = null) },
                        modifier = Modifier.toggleable(
                            value = isOn,
                            role = Role.Checkbox,
                            onValueChange = { on ->
                                checked = if (on) checked + slot else checked - slot
                            },
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.programs_favorite_clear_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            // Enabled even when nothing changed: the write is a no-op the driver skips, and a
            // "Save" that does nothing is less confusing than a button that looks broken.
            TextButton(onClick = { onConfirm(checked) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            // Always Cancel, even with no categories: a favorite can be set regardless, and
            // Set categories is a row-menu action rather than a way out of this dialog.
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * A preset's category assignments: a main category and, where the family has them, a sub - one
 * group per assignment slot, two dropdowns per group. Dropdowns rather than a list of every pair,
 * which is around eighty rows wide on a Motif XS; a flat taxonomy renders one group with its sub
 * dropdown suppressed.
 *
 * @param allowsUnassigned whether to offer a synthetic "None" meaning no category. False on a
 *   Nord, which has a real category called `None`, and two entries with one word would put a
 *   value the instrument cannot store in the dropdown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetCategoriesDialog(
    target: PresetSlot,
    tags: PresetTags,
    taxonomy: CategoryTaxonomy,
    assignmentCount: Int,
    allowsUnassigned: Boolean,
    onConfirm: (List<CategoryRef?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val picks = remember(tags) {
        List(assignmentCount) { tags.categories.getOrNull(it) }.toMutableStateList()
    }
    val noneLabel = stringResource(R.string.programs_categories_none)
    val noSubLabel = stringResource(R.string.programs_categories_no_sub)
    // How far the dropdown's indices run ahead of the taxonomy's: 1 while the synthetic "None"
    // occupies row 0, 0 without it. Written once, so the two callbacks below cannot disagree.
    val offset = if (allowsUnassigned) 1 else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(pluralStringResource(R.plurals.programs_categories_title, assignmentCount, target.displayId))
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                repeat(assignmentCount) { slot ->
                    val ref = picks[slot]
                    val main = ref?.let { taxonomy.main(it.main) }

                    Picker(
                        // Numbered only where there is more than one to tell apart.
                        label = pluralStringResource(
                            R.plurals.programs_categories_main, assignmentCount, slot + 1,
                        ),
                        selected = main?.name ?: noneLabel,
                        options = (if (allowsUnassigned) listOf(noneLabel) else emptyList()) +
                            taxonomy.mains.map { it.name },
                        onSelect = { index ->
                            picks[slot] = if (allowsUnassigned && index == 0) null
                            else CategoryRef(index - offset, null)
                        },
                    )

                    // Suppressed for a flat taxonomy and for a main with no subs, rather than
                    // shown holding a single dash - a control with nothing to choose is noise.
                    if (main != null && main.subs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Picker(
                            label = stringResource(R.string.programs_categories_sub),
                            selected = ref.sub?.let { main.subs.getOrNull(it) } ?: noSubLabel,
                            options = listOf(noSubLabel) + main.subs,
                            onSelect = { index ->
                                picks[slot] = ref.copy(sub = if (index == 0) null else index - 1)
                            },
                        )
                    }
                    if (slot < assignmentCount - 1) Spacer(Modifier.height(16.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picks.toList()) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A read-only text field that drops a menu - Material's own shape for a short, closed choice. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onSelect(index) },
                )
            }
        }
    }
}
