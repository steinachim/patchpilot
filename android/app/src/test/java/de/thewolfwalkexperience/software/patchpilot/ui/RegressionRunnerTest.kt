// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.core.RegressionReport
import de.thewolfwalkexperience.software.patchpilot.core.RegressionResult
import de.thewolfwalkexperience.software.patchpilot.core.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A finished run survives process death through [SavedStateHandle] with every result and the
 * stem it was named under, and a question the run asks is published until it is answered or the
 * run is cancelled.
 */
class RegressionRunnerTest {

    private val report = RegressionReport(
        results = listOf(
            RegressionResult("Load a preset", Status.PASS, "Selected A:1:1. Confirmed on the instrument."),
            RegressionResult("Swap two presets", Status.SKIPPED, "not supported by this instrument"),
            RegressionResult("Delete a preset", Status.FAIL, "A:2:3 is still occupied"),
        ),
        summary = "Demo Instrument: 1 passed, 1 failed, 1 skipped",
    )

    private fun runner(scope: CoroutineScope, saved: SavedStateHandle) = RegressionRunner(
        scope = scope,
        savedState = saved,
        run = { onConfirmSelect, _, progress ->
            progress("selecting")
            onConfirmSelect("\"Concert Grand\" in A:1:1")
            report
        },
        filenameStem = { "demo_instrument" },
    )

    @Test
    fun `a finished run is restored whole after process death`() = runTest {
        val saved = SavedStateHandle()
        val first = runner(this, saved)
        first.start("starting")
        advanceUntilIdle()
        // The run is waiting on its question; answering it lets it finish.
        first.question.value!!.answer.complete(true)
        advanceUntilIdle()
        assertNull("an answered question is withdrawn", first.question.value)

        val restored = runner(this, saved).state.value
        assertTrue(restored is RunState.Done)
        assertEquals(FinishedRun(report, "demo_instrument"), (restored as RunState.Done).result)
    }

    @Test
    fun `a dismissed report is not restored`() = runTest {
        val saved = SavedStateHandle()
        val first = runner(this, saved)
        first.start("starting")
        advanceUntilIdle()
        first.question.value!!.answer.complete(true)
        advanceUntilIdle()
        first.dismissReport()

        assertEquals(RunState.Idle, runner(this, saved).state.value)
    }

    /** The ViewModel's scope ends with the app; a question left on screen would answer nothing. */
    @Test
    fun `a run cancelled while it asks withdraws the question and holds nothing`() = runTest {
        val saved = SavedStateHandle()
        val owner = this + Job()
        val runner = runner(owner, saved)
        runner.start("starting")
        advanceUntilIdle()
        assertNotNull(runner.question.value)

        owner.cancel()
        advanceUntilIdle()

        assertNull(runner.question.value)
        assertEquals(RunState.Idle, runner.state.value)
        assertNull("a cancellation is not a failure", runner.error.value)
        assertEquals(RunState.Idle, runner(this, saved).state.value)
    }

    /** As an unplug does: the run's question goes with it, and nothing is held. */
    @Test
    fun `a cancelled run withdraws its question`() = runTest {
        val runner = runner(this, SavedStateHandle())
        runner.start("starting")
        advanceUntilIdle()
        assertNotNull(runner.question.value)

        runner.cancel()
        advanceUntilIdle()

        assertNull(runner.question.value)
        assertEquals(RunState.Idle, runner.state.value)
    }

    /** Restore runs as the ViewModel is built, so a status it cannot read must not throw. */
    @Test
    fun `a saved report with a status this build does not know restores as nothing`() = runTest {
        // The runner's own key and layout: stem, summary, then name, status, detail per result.
        val saved = SavedStateHandle(
            mapOf("regressionRun" to arrayOf("demo_instrument", "summary", "Load a preset", "RENAMED", "detail")),
        )
        assertEquals(RunState.Idle, runner(this, saved).state.value)
    }
}
