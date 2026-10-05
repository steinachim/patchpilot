// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.thewolfwalkexperience.software.patchpilot.MainActivity
import de.thewolfwalkexperience.software.patchpilot.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app on Robolectric, in demo mode: leaving the preset screen while an operation holds the
 * instrument, as a device report does for minutes on a Nord.
 */
@RunWith(AndroidJUnit4::class)
class LeaveSessionTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel: InstrumentViewModel
        get() = ViewModelProvider(compose.activity)[InstrumentViewModel::class.java]

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun awaitText(@StringRes id: Int) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `back while an operation holds the instrument leaves the preset screen at once`() {
        awaitText(R.string.action_try_demo)
        // Below the fold on Robolectric's small default screen.
        compose.onNodeWithText(text(R.string.action_try_demo)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(text(R.string.cd_back)).fetchSemanticsNodes().isNotEmpty()
        }

        val release = CompletableDeferred<Unit>()
        viewModel.viewModelScope.launch { viewModel.connection.exclusive { release.await() } }
        compose.onNodeWithContentDescription(text(R.string.cd_back)).performClick()

        // The connect screen, searching, while the operation still holds the instrument.
        awaitText(R.string.connect_searching)
        compose.onNodeWithText(text(R.string.connect_searching)).assertIsDisplayed()

        // Robolectric has no instrument attached, so the scan the teardown leads to finds nothing.
        release.complete(Unit)
        awaitText(R.string.connect_none_found)
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertDoesNotExist()
    }
}
