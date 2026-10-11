// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.usb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a USB permission request settles ([awaitPermission]): on its answer, on a grant that comes
 * without one, or after [PERMISSION_TIMEOUT_MS] - never on a wait that only leaving the session
 * ends.
 */
class PermissionWaitTest {

    @Test
    fun `an answer settles the request with whatever was decided`() = runTest {
        val answered = CompletableDeferred<Unit>()
        val wait = async { awaitPermission(answered) { false } }
        advanceTimeBy(2_000)
        answered.complete(Unit)
        runCurrent()

        assertTrue(wait.isCompleted)
        assertFalse("a denial", wait.await())
    }

    /** As Android's attach dialog does: the grant is held, but this request's answer never comes. */
    @Test
    fun `a grant that comes without an answer settles the request`() = runTest {
        var granted = false
        val wait = async { awaitPermission(CompletableDeferred()) { granted } }
        advanceTimeBy(3_000)
        granted = true
        advanceTimeBy(PERMISSION_POLL_MS + 1)

        assertTrue(wait.isCompleted)
        assertTrue(wait.await())
    }

    @Test
    fun `a request nobody answers is settled as not granted after the timeout`() = runTest {
        val granted = awaitPermission(CompletableDeferred()) { false }

        assertFalse(granted)
        assertEquals(PERMISSION_TIMEOUT_MS, currentTime)
    }
}
