package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.core.SetupQuestion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Asks the user for something the instrument will not tell us.
 *
 * **Deliberately not dismissable.** There is no cancel: the question exists because an operation
 * cannot work until it is answered, and the one case that exists - which MIDI channel a Pro-800's
 * DIP switches are set to - fails *silently* when guessed wrong. A dialog the user can wave away
 * would leave them with a Load button that does nothing and no explanation, which is exactly the
 * situation this replaced.
 *
 * The default is pre-selected but still has to be confirmed, for the same reason.
 */
@Composable
fun SetupQuestionDialog(question: SetupQuestion, onAnswer: (Int) -> Unit) {
    var selected by remember(question) { mutableStateOf(question.defaultOption) }
    var expanded by remember(question) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { /* answering is the only way out - see the class doc */ },
        title = { Text(question.title) },
        text = {
            Column {
                Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))

                // **A dropdown once the list gets long, radio buttons while it is short.**
                // The one question that exists today offers sixteen MIDI channels, and sixteen
                // radio buttons is a scrolling list where the choice the user already knows is
                // off-screen - they were asked "which channel", not "read this list". A dropdown
                // shows the current answer as a single line and opens only when they want to
                // change it. Below the threshold radio buttons are still better: every option
                // visible at once, one tap to answer, nothing to open.
                if (question.options.size > INLINE_OPTION_LIMIT) {
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                question.options.getOrElse(selected) { "" },
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.heightIn(max = 320.dp),
                        ) {
                            question.options.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selected = index
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Column {
                        question.options.forEachIndexed { index, label ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = index }
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = selected == index, onClick = { selected = index })
                                Text(label)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(selected) }) {
                Text(stringResource(R.string.action_use_this_channel))
            }
        },
    )
}

/** Above this many options the dialog switches from radio buttons to a dropdown. */
private const val INLINE_OPTION_LIMIT = 5
