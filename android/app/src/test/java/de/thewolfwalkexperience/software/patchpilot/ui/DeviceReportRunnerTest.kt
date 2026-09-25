// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.lifecycle.SavedStateHandle
import de.thewolfwalkexperience.software.patchpilot.core.DeviceReportResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held report survives process death through [SavedStateHandle], failures included: a report
 * restored after a kill must still say it is incomplete, or the debug screen would offer it as
 * whole.
 */
class DeviceReportRunnerTest {

    private fun runner(scope: kotlinx.coroutines.CoroutineScope, saved: SavedStateHandle, result: DeviceReportResult) =
        DeviceReportRunner(
            scope = scope,
            savedState = saved,
            build = { progress -> progress("reading"); result },
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
        val done = restored as DeviceReportState.Done
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

        val done = runner(this, saved, DeviceReportResult("unused", emptyMap())).state.value as DeviceReportState.Done
        assertTrue(done.isComplete)
        assertEquals(emptyList<String>(), done.failures)
    }
}
