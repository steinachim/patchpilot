// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as an instrument answering a scan's probe. The case that matters: a virtual or
 * loopback MIDI port sends the probe straight back, and an Axe-Fx III's reply prefix is the start
 * of its own probe, so a phone with four loopback ports listed four Axe-Fx IIIs.
 */
class ProbeAnswerTest {

    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    // As devices/fractal_axefx3.json declares them.
    private val axeProbe = hex("f0 00 01 74 10 08 1d f7")
    private val axePrefix = hex("f0 00 01 74 10 08")

    @Test
    fun `an echo of the probe is not an answer`() {
        assertFalse(answersProbe(axeProbe.copyOf(), axeProbe, axePrefix))
    }

    /** The firmware reply's shape, as an Axe-Fx III on 32.06 sends it. */
    @Test
    fun `a firmware reply is an answer`() {
        val reply = hex("f0 00 01 74 10 08 20 06 10 01 11 00 00 4f 63 74 00 12 f7")
        assertTrue(answersProbe(reply, axeProbe, axePrefix))
    }

    @Test
    fun `a message with another prefix is not an answer`() {
        assertFalse(answersProbe(hex("f0 7e 00 06 02 f7"), axeProbe, axePrefix))
    }
}
