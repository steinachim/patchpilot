// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.thewolfwalkexperience.software.patchpilot.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The advisory question while a device report is being read, which only this screen shares. */
@RunWith(AndroidJUnit4::class)
class AdvisoryGateTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(
        id,
    )

    private fun share(step: String?) =
        DeviceReportShare(step = step, error = null, label = step ?: "Share report", clearError = {}) { null }

    private fun show(reportShare: DeviceReportShare?) = compose.setContent {
        Column {
            AdvisoryGate(
                instrumentName = "Synth",
                message = "Untested firmware.",
                reportShare = reportShare,
                incompleteReads = 0,
                onDisconnect = {},
                onContinue = {},
                onShareReport = {},
            )
        }
    }

    @Test
    fun `continue waits for a report being read, while disconnect stays available`() {
        show(share(step = "Reading the device…"))
        compose.onNodeWithText(text(R.string.action_continue_anyway)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.action_disconnect)).assertIsEnabled()
    }

    @Test
    fun `with no report being read, continue is available`() {
        show(share(step = null))
        compose.onNodeWithText(text(R.string.action_continue_anyway)).assertIsEnabled()
    }
}
