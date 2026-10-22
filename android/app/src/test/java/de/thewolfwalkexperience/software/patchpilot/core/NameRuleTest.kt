// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name rule the rename dialog asks before anything is sent ([PresetEditor.nameProblem]): a
 * name it lets through is one every family stores and reads back unchanged.
 */
class NameRuleTest {

    @Test
    fun `a plain name within the limit passes`() {
        assertNull(printableAsciiNameProblem("Warm Pad 2", 16))
        assertNull(printableAsciiNameProblem("~!@#$%^&*()_+{}|:<>?", null))
    }

    @Test
    fun `a blank name is refused`() {
        assertNotNull(printableAsciiNameProblem("", 16))
        assertNotNull(printableAsciiNameProblem("   ", 16))
    }

    @Test
    fun `anything outside printable ASCII is refused`() {
        assertNotNull("no family's name field holds it", printableAsciiNameProblem("Café", 16))
        assertNotNull(printableAsciiNameProblem("Tab\there", 16))
        assertNotNull(printableAsciiNameProblem("Pad \uD83C\uDFB9", 16))
    }

    @Test
    fun `a name over the limit is refused, and there is no limit where none is known`() {
        assertNotNull(printableAsciiNameProblem("x".repeat(17), 16))
        assertNull(printableAsciiNameProblem("x".repeat(16), 16))
        assertNull(printableAsciiNameProblem("x".repeat(100), null))
    }

    /** The default every family without a rule of its own inherits, demo mode included. */
    @Test
    fun `an editor applies the shared rule with its own limit`() {
        val editor = DemoInstrument().editor
        assertNull(editor.nameProblem("Concert Grand 2"))
        assertNotNull(editor.nameProblem("x".repeat(editor.maxNameLength!! + 1)))
        assertNotNull(editor.nameProblem("Flügel"))
    }
}
