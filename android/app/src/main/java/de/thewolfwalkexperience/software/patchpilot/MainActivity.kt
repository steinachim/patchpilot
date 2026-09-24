// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private val viewModel: InstrumentViewModel by viewModels()

    // The first onResume() after onCreate() is the launch path ConnectScreen's own initial
    // connect() already handles, so the reconnect below skips it.
    private var hasResumedBefore = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before the content is set; PatchPilotScaffold is what applies the insets.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A cold start from the manifest's USB_DEVICE_ATTACHED filter needs nothing beyond this
        // log line: the ViewModel is new, and ConnectScreen scans from Disconnected on its own.
        intent.attachedUsbDevice()?.let { Log.i(TAG, "Started for the USB attach of ${it.deviceName}") }
        // A display setting rather than instrument state, and every screen needs it before an
        // instrument is connected (Settings is reachable from ConnectScreen).
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            // Created outside PatchPilotTheme's content lambda and threaded down: the `remember`
            // that hands out this controller lives in whatever composition position calls it (see
            // PatchPilotTheme), so anchoring it at the outermost position keeps the same back
            // stack across a theme switch.
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

    /**
     * The manifest's USB_DEVICE_ATTACHED filter, when this activity already exists (launchMode is
     * singleTop). The ViewModel decides by connection state whether a rescan is wanted - the
     * "nothing found" screen left standing after the instrument was plugged in is the case this
     * covers. Android has granted the device's permission by the time this runs.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val device = intent.attachedUsbDevice() ?: return
        Log.i(TAG, "USB attach delivered for ${device.deviceName}")
        viewModel.onUsbDeviceAttached(device)
    }

    override fun onPause() {
        super.onPause()
        // A resume discards a running scan anyway (see below), and on a Motif XS its own display
        // would otherwise sit on "dump in progress" for the whole scan with the phone locked.
        //
        // Not on a rotation: that pauses this instance only to recreate it, the ViewModel and its
        // scan survive, and the new instance's first resume does not rebuild.
        if (!isChangingConfigurations) viewModel.cancelScanOnBackground()
    }

    override fun onResume() {
        super.onResume()
        // Whether a rebuild is wanted is the session's own answer - see Instrument.rebuildOnResume.
        if (hasResumedBefore && viewModel.shouldRebuildOnResume) {
            viewModel.forceReconnect()
        }
        hasResumedBefore = true
    }
}

/** The device a USB_DEVICE_ATTACHED intent is about, or null for any other intent. */
private fun Intent.attachedUsbDevice(): UsbDevice? {
    if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
