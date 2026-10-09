// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Demo mode refuses an edit on an out-of-date list as the Nord it mirrors does. */
class DemoInstrumentTest {

    /** Seeded every third slot, so A:1:1 and A:1:4 hold presets and A:1:2 does not. */
    private val first = SlotAddress(0, 0)
    private val empty = SlotAddress(0, 1)
    private val second = SlotAddress(0, 3)

    private suspend fun refusal(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test
    fun `a move or copy onto an occupied slot asks for a refresh`() = runTest {
        val demo = DemoInstrument()
        val moved = refusal { demo.move(first, second) }
        val copied = refusal { demo.copyProgram(first, second) }
        assertTrue("move: got $moved", moved is InstrumentException.SlotStateChanged)
        assertTrue("copy: got $copied", copied is InstrumentException.SlotStateChanged)
    }

    @Test
    fun `an edit of an empty slot asks for a refresh`() = runTest {
        val demo = DemoInstrument()
        for ((name, edit) in listOf<Pair<String, suspend () -> Unit>>(
            "rename" to { demo.rename(empty, "Name") },
            "delete" to { demo.delete(empty) },
            "move" to { demo.move(empty, SlotAddress(0, 2)) },
        )) {
            val failure = refusal(edit)
            assertTrue("$name: got $failure", failure is InstrumentException.SlotStateChanged)
        }
    }

    @Test
    fun `a swap with an empty slot is refused and changes nothing`() = runTest {
        val demo = DemoInstrument()
        val before = demo.refresh(first).name

        val failure = refusal { demo.swap(first, empty) }

        assertTrue("got $failure", failure is InstrumentException.SlotStateChanged)
        assertEquals(before, demo.refresh(first).name)
        assertNull(demo.refresh(empty).name)
    }

    // ---- Categories, shaped like a Nord's ----

    @Test
    fun `a category set reads back`() = runTest {
        val tagger = DemoInstrument().tagger
        val other = tagger.taxonomy.mains.indices
            .map { CategoryRef(it) }
            .first { it != tagger.read(first).categories.single() }

        tagger.setCategories(first, listOf(other))

        assertEquals(listOf(other), tagger.read(first).categories)
    }

    /** As on a Nord: `None` is a category, so there is no "none" to send, and no favorites at all. */
    @Test
    fun `a category cannot be cleared, an empty slot cannot be tagged, and nothing is favorited`() = runTest {
        val tagger = DemoInstrument().tagger
        assertTrue(refusal { tagger.setCategories(first, listOf(null)) } is IllegalArgumentException)
        val onEmpty = refusal { tagger.setCategories(empty, listOf(CategoryRef(0))) }
        assertTrue("got $onEmpty", onEmpty is InstrumentException.SlotStateChanged)
        assertNull(tagger.favorites)
        assertTrue(refusal { tagger.setFavorite(first, setOf(0)) } is InstrumentException.NotSupported)
    }
}
