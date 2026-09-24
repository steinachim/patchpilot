// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.thewolfwalkexperience.software.patchpilot.R
import java.io.File
import java.io.IOException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Hands [content] to whatever app the user picks - the one place this app talks to another.
 * `ACTION_SEND`'s `EXTRA_STREAM` needs a `content://` Uri and the `FileProvider` behind it serves
 * only real files, so the text is written to `cacheDir` first.
 *
 * [filename] carries its own extension and is sanitised here rather than by each caller, since it
 * reaches the filesystem and one caller takes it from a text field. The sanitised name is
 * returned, so a caller can say what was written.
 */
fun shareTextReport(
    context: Context,
    filename: String,
    content: String,
    mimeType: String,
    chooserTitle: String,
): String {
    val safeName = filename.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.').ifBlank { "report" }

    // A dedicated directory rather than cacheDir's root, cleared before each share: a regression
    // report names the user's presets and a device report carries firmware and protocol hex, so
    // keeping only the file being shared is the smaller footprint. It also narrows what
    // file_paths.xml has to expose.
    val dir = File(context.cacheDir, SHARE_DIR).apply {
        deleteRecursively()
        mkdirs()
    }
    val file = File(dir, safeName).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    return safeName
}

/**
 * Writes [content] to a location the user picked through the system "Save to…" picker - the local
 * counterpart to [shareTextReport], for a report generated with no signal to hand it on. No
 * `FileProvider`: the picker hands back a `content://` Uri this app can write to directly.
 *
 * Blocking, and throws on failure: a provider that cannot be written to is the caller's to
 * report rather than something to swallow into an empty file.
 */
fun saveTextReport(context: Context, uri: Uri, content: String) {
    val stream = context.contentResolver.openOutputStream(uri)
        ?: throw IOException("The chosen location could not be opened for writing.")
    stream.use { it.write(content.toByteArray()) }
}

/** Subdirectory of `cacheDir` holding the one report currently being shared - see
 * [shareTextReport] and the `<cache-path>` entry in `res/xml/file_paths.xml`. */
const val SHARE_DIR = "shared"

/** The MIME type a device report is shared as. Its `.json` half is `R.string.programs_json_suffix`. */
const val JSON_MIME_TYPE = "application/json"

/** The MIME type the regression report is shared as, alongside `R.string.programs_txt_suffix`. */
const val TEXT_MIME_TYPE = "text/plain"

/**
 * What a caller wants to share, before the user has had a chance to rename the file - see
 * [ShareFilenameDialog].
 */
data class PendingShare(
    val title: String,
    val description: String,
    val suffix: String,
    val initialStem: String,
    val onConfirm: (String) -> Unit,
)

/**
 * Lets the user rename the file before it goes anywhere - the same prompt for every kind of report
 * this app shares. The stem lives in this composable's own state: each appearance is a fresh
 * composition, so there is nothing to preserve between prompts.
 */
@Composable
fun ShareFilenameDialog(pending: PendingShare, onDismiss: () -> Unit) {
    var stem by remember { mutableStateOf(pending.initialStem) }
    val keyboardController = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose { keyboardController?.hide() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pending.title) },
        text = {
            Column {
                Text(pending.description)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = stem,
                    onValueChange = { stem = it },
                    label = { Text(stringResource(R.string.programs_file_name)) },
                    suffix = { Text(pending.suffix) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            // Dismisses itself, or the dialog would stand over the progress text it reveals.
            TextButton(
                enabled = stem.isNotBlank(),
                onClick = { pending.onConfirm(stem); onDismiss() },
            ) {
                Text(stringResource(R.string.action_share))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
