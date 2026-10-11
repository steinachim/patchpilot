// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one decision every family's firmware advisory rests on. */
class FirmwareAdvisoryTest {

    private val tested = listOf("1.0", "1.2")

    @Test
    fun `a tested firmware, an unread one and an unconfigured check raise nothing`() {
        assertNull(untestedFirmwareAdvisory("Synth", "1.2", tested))
        assertNull(untestedFirmwareAdvisory("Synth", UNKNOWN_FIRMWARE, tested))
        assertNull(untestedFirmwareAdvisory("Synth", "9.9", emptyList()))
    }

    @Test
    fun `an untested firmware names itself and the tested ones, in the order given`() {
        assertEquals(
            "Synth reports firmware version 2.0, which this app has not been tested against " +
                "(tested: 1.0, 1.2). $UNTESTED_ADVISORY_CLOSING",
            untestedFirmwareAdvisory("Synth", "2.0", tested),
        )
    }
}
