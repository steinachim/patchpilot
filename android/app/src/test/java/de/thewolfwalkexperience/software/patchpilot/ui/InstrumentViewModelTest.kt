// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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
        val viewModel = viewModel().apply {
            awaitSettled()
            awaitAttemptEnded()
        }

        // A launch or a rotation: resumed without having been in the background.
        viewModel.onAppForegrounded()
        assertTrue(viewModel.state.value is ConnectionState.NothingFound)
        assertFalse(viewModel.attemptRunning())

        // The rebuild's Searching lasts microseconds and its attempt then ends, so state alone
        // cannot show it. Holding the operation mutex stalls the attempt at its teardown, where
        // it stays visible as a running job until released.
        val mutex = viewModel.coordinatorField("instrumentMutex") as Mutex
        runBlocking { mutex.lock() }
        try {
            viewModel.onAppBackgrounded()
            viewModel.onAppForegrounded()
            assertTrue(viewModel.attemptRunning())
        } finally {
            mutex.unlock()
        }
        viewModel.awaitAttemptEnded()
    }

    /** The preset screen's state lives in the ViewModel, so leaving the session has to clear it. */
    @Test
    fun `leaving a session forgets the preset screen's open dialog`() {
        val viewModel = viewModel().apply { awaitSettled() }
        viewModel.connection.connectDemo()
        viewModel.awaitSettled()
        val slot = viewModel.listings.allSlots().first()
        viewModel.programs.renameTarget = slot

        viewModel.connection.disconnect()
        assertEquals(null, viewModel.programs.renameTarget)
    }

    /**
     * A rebuild of the same instrument keeps the screen's dialogs, but not a remedy offer: its fix
     * is bound to the instrument that raised it, which the rebuild closed.
     */
    @Test
    fun `a rebuilt session keeps the open dialog but drops a remedy offer`() {
        val viewModel = viewModel().apply { awaitSettled() }
        // The session's start resets the screen's state, which clears this marker: the state can
        // be published off the main thread, so seeing it here does not mean the ViewModel's
        // collector has run yet, and a reset landing after the dialog opens would close it.
        viewModel.programs.blocked = remedyOffer()
        viewModel.connection.connectDemo()
        viewModel.awaitRemedyDropped("The first session did not reset the screen")
        val first = viewModel.connection.instrument
        val slot = viewModel.listings.allSlots().first()
        viewModel.programs.renameTarget = slot
        viewModel.programs.blocked = remedyOffer()

        viewModel.connection.connectDemo()
        viewModel.awaitRemedyDropped("The remedy offer survived the rebuild")

        assertNotSame(first, viewModel.connection.instrument)
        assertEquals(slot, viewModel.programs.renameTarget)
    }

    /** As on the bench: a preset stored over the confirmed one on the panel is not the one erased. */
    @Test
    fun `a delete refuses a slot that no longer holds the confirmed preset`() {
        val viewModel = viewModel().apply { awaitSettled() }
        viewModel.connection.connectDemo()
        val deadline = System.nanoTime() + 10_000_000_000
        while (viewModel.connection.instrument !is DemoInstrument) {
            check(System.nanoTime() < deadline) { "No demo session after 10 s (${viewModel.state.value})" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val demo = viewModel.connection.instrument as DemoInstrument
        val row = runBlocking { demo.index().toList() }
            .filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.first { !it.isEmpty }
        runBlocking { demo.rename(row.address, "Stored Over") }

        val failure = runCatching { runBlocking { viewModel.deleteProgram(row) } }.exceptionOrNull()

        assertTrue("got $failure", failure is InstrumentException.SlotStateChanged)
        assertEquals("Stored Over", runBlocking { demo.refresh(row.address).name })
    }

    /** A replug that beat the edit's failure leaves the preset screen's failure line to say it. */
    @Test
    fun `an interrupted edit reported while a session is up is not kept for the disconnect screen`() {
        val viewModel = viewModel().apply { awaitSettled() }
        viewModel.connection.connectDemo()
        viewModel.awaitSettled()

        viewModel.programs.onEditInterrupted?.invoke("Swapping A1 and A2 stopped part way.")

        assertEquals(null, viewModel.interruptedEdit.value)
    }

    /** A replug that beat the edit's failure: the text meets the new session's start, which clears the screen's state, so it is carried over. */
    @Test
    fun `an interrupted edit reported between a replug and the new session reaches the new session's failure line`() {
        val viewModel = viewModel().apply { awaitSettled() }
        check(viewModel.state.value is ConnectionState.NothingFound)

        viewModel.programs.onEditInterrupted?.invoke("Swapping A1 and A2 stopped part way.")
        assertEquals(null, viewModel.interruptedEdit.value)

        viewModel.connection.connectDemo()
        val deadline = System.nanoTime() + 10_000_000_000
        while (viewModel.programs.operationError == null) {
            check(System.nanoTime() < deadline) { "Nothing carried over (${viewModel.state.value})" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("Swapping A1 and A2 stopped part way.", viewModel.programs.operationError)
    }

    private fun remedyOffer() = BlockedOperation("Blocked.", null, "Fix", apply = {}, retry = {})

    /** Runs the main looper until the ViewModel's session collector has cleared the remedy offer. */
    private fun InstrumentViewModel.awaitRemedyDropped(failure: String) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (programs.blocked != null) {
            check(System.nanoTime() < deadline) { "$failure (${state.value})" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /**
     * Every hook the coordinator calls reaches the parts the ViewModel builds after it. A bound
     * reference (`listings::forgetDevice`) is resolved while the coordinator is built, before
     * `listings` exists: the first unplug then threw, and detach handling ended for the session.
     */
    @Test
    fun `the coordinator's hooks reach what the ViewModel builds after it`() {
        val viewModel = viewModel().apply { awaitSettled() }
        fun hook(name: String): Any =
            ConnectionCoordinator::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.get(viewModel.connection)!!

        @Suppress("UNCHECKED_CAST")
        (hook("onUsbDetached") as (String) -> Unit)("usb:/dev/bus/usb/001/002")
        @Suppress("UNCHECKED_CAST")
        (hook("onSessionLost") as () -> Unit)()
        @Suppress("UNCHECKED_CAST")
        (hook("onLeavingSession") as () -> Unit)()
    }

    /** The share button's gate can trail the session by a frame, so a tap may find no session at all. */
    @Test
    fun `with no session, a report is not named`() {
        val viewModel = viewModel().apply { awaitSettled() }
        assertEquals(null, viewModel.reportNaming())
    }

    private fun InstrumentViewModel.coordinatorField(name: String): Any? =
        ConnectionCoordinator::class.java.getDeclaredField(name).apply { isAccessible = true }.get(connection)

    private fun InstrumentViewModel.attemptRunning(): Boolean =
        (coordinatorField("connectJob") as Job?)?.isActive == true

    /**
     * Waits for the connection attempt's job to finish. The attempt publishes its state before it
     * returns, and a resume in that window is turned away by the coordinator's single-flight
     * guard, so [awaitSettled] alone leaves a rebuild on resume to chance.
     */
    private fun InstrumentViewModel.awaitAttemptEnded() {
        val deadline = System.nanoTime() + 10_000_000_000
        while (attemptRunning()) {
            check(System.nanoTime() < deadline) { "The attempt is still running after 10 s (${state.value})" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
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
