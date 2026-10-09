// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a report loop gives up on a silent instrument ([Probes.gaveUp]). */
class ProbesTest {

    private suspend fun Probes.timesOut(what: String) =
        probe(what, Unit) { throw InstrumentException.Timeout("reading $what") }

    private suspend fun Probes.answers(what: String) = probe(what, Unit) { }

    @Test
    fun `three timeouts in a row give up, and record what is left`() = runTest {
        val probes = Probes()
        probes.startLoop()
        repeat(2) { probes.timesOut("slot $it") }
        assertFalse(probes.gaveUp("rest", left = 8))
        probes.timesOut("slot 2")

        assertTrue(probes.gaveUp("rest", left = 7))
        assertTrue(probes.failures.getValue("rest"), probes.failures.getValue("rest").startsWith("7 not read"))
    }

    @Test
    fun `an answer, or a failure other than a timeout, starts the count again`() = runTest {
        val probes = Probes()
        probes.startLoop()
        repeat(2) { probes.timesOut("slot $it") }
        probes.answers("slot 2")
        repeat(2) { probes.timesOut("slot ${it + 3}") }
        probes.probe("slot 5", Unit) { throw InstrumentException.ReplyDamaged("reading slot 5") }
        probes.timesOut("slot 6")

        assertFalse(probes.gaveUp("rest", left = 1))
    }

    /** Queries made before a loop, which an instrument may simply not support, do not count against it. */
    @Test
    fun `timeouts before the loop do not count`() = runTest {
        val probes = Probes()
        repeat(3) { probes.timesOut("query $it") }
        probes.startLoop()

        assertFalse(probes.gaveUp("rest", left = 10))
        assertEquals(3, probes.failures.size)
    }
}
