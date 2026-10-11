// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.thrownBy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [requireStillHolds], which a delete runs first: a slot changed on the panel since the list was
 * read must not have its new preset erased under the old one's name.
 */
class SlotCheckTest {

    private suspend fun listed(demo: DemoInstrument): PresetSlot =
        demo.index().toList().filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.first { !it.isEmpty }

    @Test
    fun `a slot as the list shows it passes`() = runTest {
        val demo = DemoInstrument()
        demo.browser.requireStillHolds(listed(demo))
    }

    /** As on the bench: another program stored over the listed one on the panel. */
    @Test
    fun `a slot holding another preset now is refused, naming both`() = runTest {
        val demo = DemoInstrument()
        val row = listed(demo)
        demo.rename(row.address, "Stored Over")

        val e = thrownBy<InstrumentException.SlotStateChanged> { demo.browser.requireStillHolds(row) }

        assertTrue(e.message!!, e.message!!.contains("now holds \"Stored Over\", not \"${row.name}\""))
        assertTrue(e.message!!, e.message!!.contains("Refresh the list"))
    }

    @Test
    fun `a slot emptied since is refused`() = runTest {
        val demo = DemoInstrument()
        val row = listed(demo)
        demo.delete(row.address)

        val e = thrownBy<InstrumentException.SlotStateChanged> { demo.browser.requireStillHolds(row) }

        assertTrue(e.message!!, e.message!!.contains("is empty"))
    }
}
