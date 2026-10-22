// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.thewolfwalkexperience.software.patchpilot.MainActivity
import de.thewolfwalkexperience.software.patchpilot.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app on Robolectric, in demo mode: the preset screen while an operation holds the
 * instrument, as a device report does for minutes on a Nord - leaving it, and recreating it.
 */
@RunWith(AndroidJUnit4::class)
class PresetScreenLifecycleTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val viewModel: InstrumentViewModel
        get() = ViewModelProvider(compose.activity)[InstrumentViewModel::class.java]

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun awaitText(@StringRes id: Int) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty() }

    /** Connects demo mode and waits for its preset screen. */
    private fun openDemoPresets() {
        awaitText(R.string.action_try_demo)
        // Below the fold on Robolectric's small default screen.
        compose.onNodeWithText(text(R.string.action_try_demo)).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(text(R.string.cd_back)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Holds the operation mutex, as a report or a slow edit does, until the result is completed. */
    private fun holdInstrument(): CompletableDeferred<Unit> {
        val release = CompletableDeferred<Unit>()
        viewModel.viewModelScope.launch { viewModel.connection.exclusive { release.await() } }
        return release
    }

    @Test
    fun `back while an operation holds the instrument leaves the preset screen at once`() {
        openDemoPresets()
        val release = holdInstrument()
        compose.onNodeWithContentDescription(text(R.string.cd_back)).performClick()

        // The connect screen, searching, while the operation still holds the instrument.
        awaitText(R.string.connect_searching)
        compose.onNodeWithText(text(R.string.connect_searching)).assertIsDisplayed()

        // Robolectric has no instrument attached, so the scan the teardown leads to finds nothing.
        release.complete(Unit)
        awaitText(R.string.connect_none_found)
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertDoesNotExist()
    }

    /**
     * A rotation mid-edit keeps the edit's progress line and its gate on back, and the result
     * still shows once the edit lands - all of which the controller owns, so it has to outlive
     * the activity.
     */
    @Test
    fun `recreating the activity mid-edit keeps the edit's progress, gate and result`() {
        openDemoPresets()
        compose.waitUntil(10_000) { viewModel.listings.userIndex.value.complete }
        val slot = viewModel.listings.userIndex.value.slots.first { !it.isEmpty }
        val busy = compose.activity.getString(R.string.programs_busy_deleting, slot.displayId)

        val release = holdInstrument()
        compose.runOnUiThread { viewModel.programs.onDeleteConfirmed(slot) }
        compose.waitForIdle()
        compose.onNodeWithText(busy).assertExists()
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertIsNotEnabled()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(busy).assertExists()
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertIsNotEnabled()

        release.complete(Unit)
        val result = compose.activity.getString(R.string.result_deleted, slot.displayId)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(result).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertIsEnabled()
    }

    /** System back does what the arrow does, rather than finishing or backgrounding the activity. */
    @Test
    fun `system back leaves the preset screen for the picker, as the arrow does`() {
        openDemoPresets()

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

        awaitText(R.string.connect_none_found)
        assertFalse(compose.activity.isFinishing)
    }

    /** In pick mode, back ends the pick and stays on the presets. */
    @Test
    fun `system back while picking a destination ends the pick`() {
        openDemoPresets()
        compose.waitUntil(10_000) { viewModel.listings.userIndex.value.complete }
        val slot = viewModel.listings.userIndex.value.slots.first { !it.isEmpty }
        compose.runOnUiThread { viewModel.programs.beginPicking(slot) }

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertFalse(viewModel.programs.picking)
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertExists()
    }

    /** The arrow does what system back and the pick's Cancel do: it ends the pick and stays. */
    @Test
    fun `the arrow while picking a destination ends the pick`() {
        openDemoPresets()
        compose.waitUntil(10_000) { viewModel.listings.userIndex.value.complete }
        val slot = viewModel.listings.userIndex.value.slots.first { !it.isEmpty }
        compose.runOnUiThread { viewModel.programs.beginPicking(slot) }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(text(R.string.cd_back)).performClick()
        compose.waitForIdle()

        assertFalse(viewModel.programs.picking)
        compose.onNodeWithContentDescription(text(R.string.cd_back)).assertExists()
    }

    /** What the user filtered by survives a rotation, as the list it narrows does. */
    @Test
    fun `recreating the activity keeps the filter text`() {
        openDemoPresets()
        compose.onNodeWithText(text(R.string.programs_filter_hint)).performTextInput("Grand")
        compose.waitForIdle()

        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(text(R.string.cd_back)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Grand", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.action_clear_filter)).assertDoesNotExist()
    }
}
