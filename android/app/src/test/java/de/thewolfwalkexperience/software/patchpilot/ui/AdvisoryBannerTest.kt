// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.thewolfwalkexperience.software.patchpilot.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The advisory above the preset list: one line, with the full text on request. */
@RunWith(AndroidJUnit4::class)
class AdvisoryBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(
        id,
    )

    private val advisory = "Synth reports firmware version 9.9, which this app has not been tested against."

    @Test
    fun `the banner shows the short title until details are asked for`() {
        compose.setContent { AdvisoryBanner(advisory) }

        compose.onNodeWithText(text(R.string.connect_advisory_title)).assertExists()
        compose.onNodeWithText(advisory).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.action_details)).performClick()
        compose.onNodeWithText(advisory).assertExists()

        compose.onNodeWithText(text(R.string.action_hide_details)).performClick()
        compose.onNodeWithText(advisory).assertDoesNotExist()
    }
}
