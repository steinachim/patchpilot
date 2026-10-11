// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the platform's reader thread hands over arrives complete and in order, in few pieces. */
class MidiReceiveQueueTest {

    /**
     * Runs [test] with a queue whose consumer `advanceUntilIdle` drives, which it does not for
     * `backgroundScope`, and which ends with the test. [received] collects what it emits.
     */
    private fun runQueue(test: suspend TestScope.(MidiReceiveQueue, List<ByteArray>) -> Unit) = runTest {
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val received = mutableListOf<ByteArray>()
        try {
            test(MidiReceiveQueue(scope) { received += it }, received)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `thousands of small callbacks arrive complete and in order, batched`() = runQueue { queue, received ->
        // A preset dump: about 2,000 callbacks of 25 bytes, here numbered so order shows.
        val sent = ByteArray(49_000) { (it % 251).toByte() }
        for (offset in sent.indices step 25) queue.push(sent, offset, 25)
        advanceUntilIdle()

        assertArrayEquals(sent, received.reduce { a, b -> a + b })
        assertTrue("got ${received.size} batches", received.size < 10)
    }

    @Test
    fun `a callback is copied, since the platform reuses its buffer`() = runQueue { queue, received ->
        val buffer = byteArrayOf(1, 2, 3)

        queue.push(buffer, 0, 3)
        buffer.fill(9)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(1, 2, 3), received.single())
    }

    @Test
    fun `an out-of-range callback is clamped, not thrown`() = runQueue { queue, received ->
        queue.push(byteArrayOf(1, 2), 5, 3)
        queue.push(byteArrayOf(1, 2), 0, 0)
        queue.push(byteArrayOf(1, 2), 1, 99)
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertArrayEquals(byteArrayOf(2), received.single())
    }
}
