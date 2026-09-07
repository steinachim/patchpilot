package de.thewolfwalkexperience.software.patchpilot.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.thewolfwalkexperience.software.patchpilot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One entry in the list: what it covers, which license, and the asset [syncLicenses] put it in. */
private data class LicenseEntry(val groupRes: Int, val licenseNameRes: Int, val assetFile: String)

/**
 * The four things this app's own GPLv3 license doesn't cover: the libraries and fonts it bundles.
 * Names and asset files are hand-listed here rather than read off the Gradle dependency graph -
 * there are exactly four, they change about as often as a dependency version bump, and
 * `android/NOTICE.md` is the place that list is cross-checked against, not this screen.
 *
 * Full text comes from assets rather than a web link: this is a USB/MIDI hardware tool the
 * manifest declares no INTERNET permission for, so a license anyone can actually read here has to
 * be bundled, not fetched.
 */
private val LICENSE_ENTRIES = listOf(
    LicenseEntry(R.string.licenses_group_patchpilot, R.string.licenses_patchpilot_license, "LICENSE-GPL-3.0.txt"),
    LicenseEntry(R.string.licenses_group_androidx, R.string.licenses_apache_license, "Apache-2.0.txt"),
    LicenseEntry(R.string.licenses_group_cinzel, R.string.licenses_ofl_license, "OFL-Cinzel.txt"),
    LicenseEntry(R.string.licenses_group_share_tech_mono, R.string.licenses_ofl_license, "OFL-ShareTechMono.txt"),
)

@Composable
fun OpenSourceLicensesScreen(onOpenLicense: (assetFile: String) -> Unit, onBack: () -> Unit) {
    PatchPilotScaffold(title = stringResource(R.string.licenses_title), onBack = onBack) { innerPadding ->
        LazyColumn(Modifier.padding(innerPadding)) {
            items(LICENSE_ENTRIES) { entry ->
                ListItem(
                    headlineContent = { Text(stringResource(entry.groupRes)) },
                    supportingContent = { Text(stringResource(entry.licenseNameRes)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLicense(entry.assetFile) },
                )
            }
        }
    }
}

/** The full text of one license, read from `assets/licenses/<assetFile>` off the main thread. */
@Composable
fun LicenseTextScreen(assetFile: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(assetFile) { mutableStateOf<String?>(null) }
    LaunchedEffect(assetFile) {
        text = readLicenseAsset(context, assetFile)
    }
    // The license's own name, not its filename. Deriving the title from the asset put "OFL-Cinzel"
    // and "LICENSE-GPL-3.0" in the app bar - build artefacts, and not what the row the user tapped
    // called it. The license name is also what the screen is actually showing the text of, where
    // the group name ("AndroidX, Jetpack Compose, Kotlin, kotlinx") would only ellipsize.
    val title = LICENSE_ENTRIES.firstOrNull { it.assetFile == assetFile }
        ?.let { stringResource(it.licenseNameRes) }
        ?: assetFile.substringBeforeLast('.')
    PatchPilotScaffold(title = title, onBack = onBack) { innerPadding ->
        SelectionContainer {
            Text(
                text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

private suspend fun readLicenseAsset(context: Context, assetFile: String): String =
    withContext(Dispatchers.IO) {
        context.assets.open("licenses/$assetFile").bufferedReader().use { it.readText() }
    }
