package de.thewolfwalkexperience.software.patchpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import de.thewolfwalkexperience.software.patchpilot.ui.PatchPilotApp
import de.thewolfwalkexperience.software.patchpilot.ui.InstrumentViewModel
import de.thewolfwalkexperience.software.patchpilot.ui.theme.AppTheme
import de.thewolfwalkexperience.software.patchpilot.ui.theme.LocalThemeStyle
import de.thewolfwalkexperience.software.patchpilot.ui.theme.PatchPilotTheme
import de.thewolfwalkexperience.software.patchpilot.ui.theme.ThemePreferences

class MainActivity : ComponentActivity() {
    private val viewModel: InstrumentViewModel by viewModels()

    // Skips the reconnect-on-resume below for the very first onResume() after onCreate(), which
    // is just the normal launch path already handled by ConnectScreen's own initial connect().
    private var hasResumedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opt in before the content is set. Android 15 makes this mandatory for anything
        // targeting SDK 35, so doing it now is the difference between choosing the layout and
        // having it imposed - and every screen already goes through PatchPilotScaffold, which is
        // what actually applies the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Held here rather than on the ViewModel: it is a display setting, not instrument state,
        // and every screen needs it before any instrument is even connected (SettingsScreen is
        // reachable from ConnectScreen too).
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            // Created here, outside PatchPilotTheme's content lambda, and threaded down as a
            // parameter rather than left for PatchPilotApp to rememberNavController() itself:
            // NavHostController owns the back stack outside Compose's slot table, but the
            // `remember` call that hands out *this session's* instance still lives in whatever
            // composition position calls it (see PatchPilotTheme's doc comment). Anchoring it at
            // the outermost, unconditional position keeps the same controller across a theme
            // switch instead of losing the back stack to a fresh one.
            val navController = rememberNavController()
            val appTheme by themePreferences.theme.collectAsState(initial = AppTheme.Default)
            PatchPilotTheme(appTheme = appTheme) {
                val theme = LocalThemeStyle.current
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .let { theme.screenTexture(it) },
                ) {
                    PatchPilotApp(viewModel, themePreferences, navController)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Whether a rebuild is wanted is the session's own answer, not this screen's: it comes
        // from the connected instrument's transport (see Instrument.rebuildOnResume). USB host
        // needs it, because unrelated bus activity while backgrounded can leave its endpoints
        // permanently erroring; a MIDI port has add/remove callbacks and does not, and a demo
        // session has no hardware to repair at all.
        if (hasResumedBefore && viewModel.shouldRebuildOnResume) {
            viewModel.forceReconnect()
        }
        hasResumedBefore = true
    }
}
