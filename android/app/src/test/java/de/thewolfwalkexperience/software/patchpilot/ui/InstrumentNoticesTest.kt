// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.InstrumentNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstrumentNoticesTest {

    private val first = InstrumentNotice("a", "First", "One")
    private val second = InstrumentNotice("b", "Second", "Two")

    @Test
    fun `the first notice neither hidden nor shown this run comes next`() {
        assertEquals(first, pendingNotice(listOf(first, second), hidden = emptySet(), shownThisRun = emptySet()))
        assertEquals(second, pendingNotice(listOf(first, second), hidden = setOf("a"), shownThisRun = emptySet()))
        assertEquals(second, pendingNotice(listOf(first, second), hidden = emptySet(), shownThisRun = setOf("a")))
    }

    @Test
    fun `nothing is pending once every notice was hidden or shown`() {
        assertNull(pendingNotice(listOf(first, second), hidden = setOf("a"), shownThisRun = setOf("b")))
        assertNull(pendingNotice(emptyList(), hidden = emptySet(), shownThisRun = emptySet()))
    }
}
