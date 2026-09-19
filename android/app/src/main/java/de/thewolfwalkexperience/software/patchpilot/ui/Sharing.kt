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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Hands [content] to whatever app the user picks - the one place this app talks to another.
 *
 * The mechanism is not obvious: `ACTION_SEND`'s `EXTRA_STREAM` needs a `content://` Uri, and the
 * `FileProvider` behind it only serves real files, so the text has to be written to `cacheDir`
 * first even though it is already in memory.
 *
 * [filename] carries its own extension, since the two callers share different kinds of file, and
 * is sanitised here rather than by each caller - it reaches the filesystem, and one caller takes
 * it from a text field the user typed into. The sanitised name is returned, so a caller can say
 * what was actually written rather than what was asked for.
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

    // A dedicated directory rather than cacheDir's root, cleared before each share.
    //
    // Two reasons. Files written to the cache root would sit there until Android chose to evict
    // the cache, and a report is not nothing: a regression report names the user's presets, and a
    // device report carries the firmware version, the USB ids and raw protocol hex. Keeping
    // exactly the file being shared right now is the smaller footprint. Second, it narrows what the FileProvider path in
    // file_paths.xml exposes: the grant is per-Uri either way, but there is no reason for the
    // declared path to span the whole cache when one subdirectory will do.
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
 * Writes [content] straight to a location the user picked through the system "Save to…" picker
 * (`ActivityResultContracts.CreateDocument`, launched by the caller) - the local counterpart to
 * [shareTextReport], for whoever generated a report with no signal to hand it on to anyone, e.g.
 * offline in the field. No `FileProvider` involved: the picker hands back a `content://` Uri this
 * app can write to directly, in whichever app (Files, a cloud drive already synced offline, an SD
 * card) the user chose.
 */
fun saveTextReport(context: Context, uri: Uri, content: String) {
    context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
}

/** Subdirectory of `cacheDir` holding the one report currently being shared - see
 * [shareTextReport] and the `<cache-path>` entry in `res/xml/file_paths.xml`. */
const val SHARE_DIR = "shared"

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
 * Lets the user rename the file before it goes anywhere - the same prompt for every kind of
 * report this app shares, since the reason to want a different name (a bench log, a bug report
 * attachment) does not depend on what is in the file.
 *
 * The stem lives in this composable's own state rather than the caller's: each appearance of the
 * dialog is a fresh composition (the caller gates it behind a nullable trigger), so there is
 * nothing to preserve between one prompt and the next.
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
            // Dismisses itself, rather than leaving each caller to remember: a caller that did
            // not would leave this dialog standing over the progress text it was meant to reveal.
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
