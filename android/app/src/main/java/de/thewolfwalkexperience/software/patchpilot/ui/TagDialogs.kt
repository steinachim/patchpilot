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

/**
 * The preset screen's two tagging dialogs.
 *
 * Same rule as `ProgramDialogs`: each takes plain data and callbacks, reads no screen state and no
 * view model, and knows nothing about which instrument is plugged in - everything family-specific
 * arrives as a [CategoryTaxonomy] and a count.
 */

/**
 * Which of a preset's own categories the instrument should list it under.
 *
 * **The checkboxes are the preset's assignments, not the whole taxonomy**, because that is all the
 * hardware can express: a Motif XS favorite flag selects among the voice's two category
 * assignments, so a voice filed under nothing has nowhere to be a favorite. That is not a
 * simplification of a richer model - writing a mark that points at an unassigned slot produces a
 * favorite the instrument lists nowhere at all.
 *
 * @param tags what is stored now; [PresetTags.categories] positions the rows.
 * @param onSetCategories non-null where this preset's categories can be changed, which offers the
 *   way out of the "no categories" dead end. Null for a factory preset.
 */
@Composable
internal fun SetFavoriteDialog(
    target: PresetSlot,
    tags: PresetTags,
    taxonomy: CategoryTaxonomy,
    assignmentCount: Int,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    onSetCategories: (() -> Unit)? = null,
) {
    val assigned = remember(tags) { tags.assigned }
    var checked by remember(tags) { mutableStateOf(tags.favoriteUnder) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.programs_favorite_title, target.displayId)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (assigned.isEmpty()) {
                    // **Not a dead end.** A voice filed under no category can still be a
                    // favorite: the instrument's own front panel writes the same mark this
                    // dialog would, and lists the voice in its Favorite bank - measured on
                    // hardware, and the reason the driver no longer refuses it. So this offers
                    // the plain on/off the situation actually has, rather than explaining why
                    // it cannot be done.
                    //
                    // `setOf(0)` is what "on" means here: it encodes to the same value the panel
                    // writes. Which of the two assignment slots it names is immaterial to a voice
                    // that has neither.
                    Text(stringResource(R.string.programs_favorite_uncategorised))
                    Spacer(Modifier.height(8.dp))
                    val isOn = checked.isNotEmpty()
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.programs_favorite_toggle)) },
                        leadingContent = { Checkbox(checked = isOn, onCheckedChange = null) },
                        modifier = Modifier.toggleable(
                            value = isOn,
                            role = Role.Checkbox,
                            onValueChange = { on -> checked = if (on) setOf(0) else emptySet() },
                        ),
                    )
                } else {
                    Text(stringResource(R.string.programs_favorite_body))
                    Spacer(Modifier.height(8.dp))
                    assigned.forEach { slot ->
                        val label = tags.categories[slot]?.let(taxonomy::label).orEmpty()
                        val isOn = slot in checked
                        ListItem(
                            headlineContent = { Text(label) },
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
            }
        },
        confirmButton = {
            // Enabled even when nothing changed: "Save" that does nothing is less confusing
            // than a button that looks broken, and the write is a no-op the driver skips.
            TextButton(onClick = { onConfirm(checked) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            // Still offered where the voice has no categories and they can be set - now as the
            // other thing you might want, rather than as the only button in the dialog.
            if (assigned.isEmpty() && onSetCategories != null) {
                TextButton(onClick = onSetCategories) {
                    Text(pluralStringResource(R.plurals.action_set_categories, assignmentCount))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * A preset's category assignments: a main category and, where the family has them, a sub.
 *
 * **One group per assignment slot, two dropdowns per group.** Dropdowns rather than a scrolling
 * list of every pair because the taxonomy is two-level and around eighty pairs wide on a Motif XS,
 * and a dialog is the wrong place to scroll through eighty rows. A family with a flat taxonomy -
 * a Nord - renders one group with its sub dropdown suppressed, without this composable branching
 * on which instrument it is.
 *
 * @param allowsUnassigned whether to offer a synthetic "None" entry meaning *no* category. False
 *   on a Nord, which has a real category called `None` of its own: offering both would put two
 *   entries with the same word in one dropdown, one of which the instrument cannot store.
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
    // How far the dropdown's indices run ahead of the taxonomy's, which is 1 while the synthetic
    // "None" occupies row 0 and 0 without it - written once so the two dropdown callbacks below
    // cannot disagree about it.
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
