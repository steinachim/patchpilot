package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.thewolfwalkexperience.software.patchpilot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The MIME type a device report is shared as. Its `.json` half is `R.string.programs_json_suffix`. */
const val JSON_MIME_TYPE = "application/json"

/** The MIME type the regression report is shared as, alongside `R.string.programs_txt_suffix`. */
const val TEXT_MIME_TYPE = "text/plain"

/**
 * Builds the connected instrument's device report and hands it to the share sheet.
 *
 * **One holder rather than the three copies this replaces.** `ProgramsScreen`, `ConnectScreen` and
 * `DebugScreen` each had the same launch, the same `"Reading device..."`, the same `"$stem.json"`,
 * the same MIME type and the same try/catch/finally - and they had already drifted apart in how
 * they reported the result. Three copies is where a shape stops being a coincidence.
 *
 * [progress] is non-null while a report is being read, which is what each screen puts on its
 * button; the probe walks every item on the instrument, so it is slow enough to need saying.
 */
class ReportSharer(
    private val viewModel: InstrumentViewModel,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val context: android.content.Context,
    private val chooserTitle: String,
    private val readingLabel: String,
) {
    var progress: String? by mutableStateOf(null)
        private set

    var error: String? by mutableStateOf(null)
        private set

    /** True while a report is in flight - what a caller gates its button on. */
    val isRunning: Boolean get() = progress != null

    /**
     * Reads the report and opens the chooser, reporting the sanitised filename through [onShared].
     *
     * [onShared] is how the one caller that says so out loud (the preset screen's snackbar) gets
     * the name that actually reached the filesystem, which is not necessarily the one typed.
     */
    fun share(filenameStem: String, onShared: (String) -> Unit = {}) {
        scope.launch {
            error = null
            progress = readingLabel
            try {
                val json = viewModel.buildDeviceReport { step -> progress = step }
                onShared(
                    shareTextReport(
                        context = context,
                        filename = "$filenameStem${JSON_SUFFIX}",
                        content = json,
                        mimeType = JSON_MIME_TYPE,
                        chooserTitle = chooserTitle,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message
            } finally {
                progress = null
            }
        }
    }

    private companion object {
        const val JSON_SUFFIX = ".json"
    }
}

/** Remembers a [ReportSharer] for the current screen, resolving its strings once. */
@Composable
fun rememberReportSharer(viewModel: InstrumentViewModel): ReportSharer {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.programs_share_report)
    val readingLabel = stringResource(R.string.share_reading_device)
    return remember(viewModel, chooserTitle, readingLabel) {
        ReportSharer(viewModel, scope, context, chooserTitle, readingLabel)
    }
}
