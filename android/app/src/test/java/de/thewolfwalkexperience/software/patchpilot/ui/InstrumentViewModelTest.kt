// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The ViewModel on Robolectric, with nothing attached: it connects without any screen asking, and
 * rebuilds the session on resume only after the app was in the background.
 */
@RunWith(AndroidJUnit4::class)
class InstrumentViewModelTest {

    private fun viewModel() = InstrumentViewModel(ApplicationProvider.getApplicationContext(), SavedStateHandle())

    /** After process death the back stack can restore onto the preset screen, which never asks to connect. */
    @Test
    fun `a new ViewModel starts connecting without any screen asking`() {
        val viewModel = viewModel()
        assertNotEquals(ConnectionState.Disconnected, viewModel.state.value)
        viewModel.awaitSettled()
        assertTrue(viewModel.state.value is ConnectionState.NothingFound)
    }

    @Test
    fun `the session is rebuilt on resume only after a stay in the background`() {
        val viewModel = viewModel().apply { awaitSettled() }

        // A launch or a rotation: resumed without having been in the background.
        viewModel.onAppForegrounded()
        assertTrue(viewModel.state.value is ConnectionState.NothingFound)

        viewModel.onAppBackgrounded()
        viewModel.onAppForegrounded()
        assertEquals(ConnectionState.Searching, viewModel.state.value)
    }

    /** The share button's gate can trail the session by a frame, so a tap may find no session at all. */
    @Test
    fun `a report is named only while a session can give one`() {
        val viewModel = viewModel().apply { awaitSettled() }
        assertEquals(null, viewModel.reportNaming())
    }

    /**
     * Runs the main looper until the attempt in flight has landed. Polls rather than idling once:
     * the attempt reads the auto-connect preference on a DataStore thread.
     */
    private fun InstrumentViewModel.awaitSettled() {
        val deadline = System.nanoTime() + 10_000_000_000
        while (state.value == ConnectionState.Searching || state.value == ConnectionState.Disconnected) {
            check(System.nanoTime() < deadline) { "Still ${state.value} after 10 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
