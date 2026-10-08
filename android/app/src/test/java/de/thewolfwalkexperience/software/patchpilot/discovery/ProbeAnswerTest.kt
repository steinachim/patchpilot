// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import de.thewolfwalkexperience.software.patchpilot.catalog.hexToBytes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as an instrument answering a scan's probe. The case that matters: a virtual or
 * loopback MIDI port sends the probe straight back, and an Axe-Fx III's reply prefix is the start
 * of its own probe, so a phone with four loopback ports would list four Axe-Fx IIIs.
 */
class ProbeAnswerTest {

    // As devices/fractal_axefx3.json declares them.
    private val axeProbe = "f0 00 01 74 10 08 1d f7".hexToBytes()
    private val axePrefix = "f0 00 01 74 10 08".hexToBytes()

    @Test
    fun `an echo of the probe is not an answer`() {
        assertFalse(answersProbe(axeProbe.copyOf(), axeProbe, axePrefix))
    }

    /** The firmware reply's shape, as an Axe-Fx III on 32.06 sends it. */
    @Test
    fun `a firmware reply is an answer`() {
        val reply = "f0 00 01 74 10 08 20 06 10 01 11 00 00 4f 63 74 00 12 f7".hexToBytes()
        assertTrue(answersProbe(reply, axeProbe, axePrefix))
    }

    @Test
    fun `a message with another prefix is not an answer`() {
        assertFalse(answersProbe("f0 7e 00 06 02 f7".hexToBytes(), axeProbe, axePrefix))
    }
}
