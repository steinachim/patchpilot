// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held report survives process death through [SavedStateHandle], failures included: a report
 * restored after a kill must still say it is incomplete, or the debug screen would offer it as
 * whole. The name a share was confirmed under survives with it.
 */
class DeviceReportRunnerTest {

    private fun runner(scope: CoroutineScope, saved: SavedStateHandle, result: DeviceReportResult) =
        runner(scope, saved) { result }

    private fun runner(scope: CoroutineScope, saved: SavedStateHandle, read: suspend () -> DeviceReportResult) =
        DeviceReportRunner(
            scope = scope,
            savedState = saved,
            build = { progress ->
                progress("reading")
                read()
            },
            identity = { "nord_grand" to "Reads everything." },
        )

    @Test
    fun `a report whose reads failed is held as incomplete and restored that way`() = runTest {
        val saved = SavedStateHandle()
        val failures = linkedMapOf(
            "storage[Program]" to "USB bulk write failed: sent -1 of 26 bytes",
            "storage[Live]" to "USB bulk write failed: sent -1 of 26 bytes",
        )
        runner(this, saved, DeviceReportResult("{}", failures)).start("starting")
        advanceUntilIdle()

        val restored = runner(this, saved, DeviceReportResult("unused", emptyMap())).state.value
        val done = (restored as RunState.Done).result
        assertFalse(done.isComplete)
        assertEquals("{}", done.json)
        assertEquals("nord_grand", done.stem)
        assertEquals(
            listOf(
                "storage[Program]: USB bulk write failed: sent -1 of 26 bytes",
                "storage[Live]: USB bulk write failed: sent -1 of 26 bytes",
            ),
            done.failures,
        )
    }

    @Test
    fun `a complete report restores as complete`() = runTest {
        val saved = SavedStateHandle()
        runner(this, saved, DeviceReportResult("{}", emptyMap())).start("starting")
        advanceUntilIdle()

        val done = (runner(this, saved, DeviceReportResult("unused", emptyMap())).state.value as RunState.Done).result
        assertTrue(done.isComplete)
        assertEquals(emptyList<String>(), done.failures)
    }

    /**
     * The name typed for a share outlives the screen that asked, process death included, so the
     * screen shown when the read ends still shares it - once.
     */
    @Test
    fun `a share confirmed before a restore is taken once, and the report stays until dismissed`() = runTest {
        val saved = SavedStateHandle()
        runner(this, saved, DeviceReportResult("{}", emptyMap())).start("starting", shareAs = "stage_grand")
        advanceUntilIdle()

        val restored = runner(this, saved, DeviceReportResult("unused", emptyMap()))
        val (stem, done) = restored.takeShare()!!
        assertEquals("stage_grand", stem)
        assertEquals("{}", done.json)
        assertNull("a recomposition must not share it again", restored.takeShare())
        assertTrue(restored.state.value is RunState.Done)

        restored.dismiss()
        assertEquals(RunState.Idle, runner(this, saved, DeviceReportResult("unused", emptyMap())).state.value)
    }

    /** The debug menu holds its report for Share and Save, so a read it starts is never shared on its own. */
    @Test
    fun `a read started without a name is held, not shared`() = runTest {
        val saved = SavedStateHandle()
        val reports = runner(this, saved, DeviceReportResult("{}", emptyMap()))
        reports.start("starting", shareAs = "stage_grand")
        advanceUntilIdle()
        reports.start("starting")
        advanceUntilIdle()

        assertNull(reports.shareAs.value)
        assertNull(reports.takeShare())
        assertNull(runner(this, saved, DeviceReportResult("unused", emptyMap())).shareAs.value)
    }

    @Test
    fun `a read that fails says why and holds nothing`() = runTest {
        val saved = SavedStateHandle()
        val reports = runner(this, saved) { throw InstrumentException.TransportLost("USB cable unplugged") }
        reports.start("starting", shareAs = "stage_grand")
        advanceUntilIdle()

        assertEquals(RunState.Idle, reports.state.value)
        assertEquals("USB cable unplugged", reports.error.value)
        assertNull(reports.takeShare())
        assertEquals(RunState.Idle, runner(this, saved, DeviceReportResult("unused", emptyMap())).state.value)
    }

    @Test
    fun `a share that fails after the read keeps the report and says why`() = runTest {
        val reports = runner(this, SavedStateHandle(), DeviceReportResult("{}", emptyMap()))
        reports.start("starting", shareAs = "stage_grand")
        advanceUntilIdle()
        reports.takeShare()
        reports.reportShareFailure("No space left on device")

        assertEquals("No space left on device", reports.error.value)
        assertTrue(reports.state.value is RunState.Done)
    }
}
