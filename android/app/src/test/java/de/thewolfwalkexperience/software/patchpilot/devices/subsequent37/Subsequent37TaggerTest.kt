// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.subsequent37

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Subsequent37TaggerTest {

    private fun address(number: Int) = Subsequent37AddressFormat.addressOf(number)

    private val vox = Subsequent37Categories.PANEL_ORDER.indexOf("Vox")

    @Test
    fun `the taxonomy is the panel's flat list with one assignment and no favorites`() = runTest {
        val tagger = subsequent37(FakeSubsequent37(backgroundScope)).tagger
        assertEquals(Subsequent37Categories.PANEL_ORDER, tagger.taxonomy.mains.map { it.name })
        assertTrue(tagger.taxonomy.isFlat)
        assertEquals(1, tagger.assignmentCount)
        assertNull(tagger.favorites)
        // Unassigned shows as Misc on the panel, so the dialog does not offer it.
        assertFalse(tagger.allowsUnassigned)
    }

    @Test
    fun `a category reads off byte 50, and none for an uncategorised preset`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val tagger = subsequent37(fake).apply { connect() }.tagger
        val sync = Subsequent37Categories.PANEL_ORDER.indexOf("Sync")
        assertEquals(listOf(CategoryRef(sync)), tagger.read(address(1)).categories)
        val empty = Subsequent37Fixtures.emptyPrograms(1).single()
        assertEquals(listOf<CategoryRef?>(null), tagger.read(address(empty)).categories)
    }

    /** The dialog opens on what the listing read rather than waiting a second or two for a read. */
    @Test
    fun `after a listing, a category is answered without asking the instrument`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val instrument = subsequent37(fake).apply { connect() }
        instrument.index().toList()
        val requests = fake.requests.size

        val sync = Subsequent37Categories.PANEL_ORDER.indexOf("Sync")
        assertEquals(listOf(CategoryRef(sync)), instrument.tagger.read(address(1)).categories)
        assertEquals(requests, fake.requests.size)
    }

    @Test
    fun `setting a category writes byte 50 alone, and none writes 0x77`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val tagger = subsequent37(fake).apply { connect() }.tagger
        val before = Subsequent37Fixtures.unpackBlock(1)

        tagger.setCategories(address(1), listOf(CategoryRef(vox)))
        val after = fake.programAt(1).payload
        assertEquals(0x11, after[50].toInt())
        assertTrue((0 until 486).filter { before[it] != after[it] } == listOf(50))

        tagger.setCategories(address(1), listOf(null))
        assertEquals(Subsequent37Categories.UNSET, fake.programAt(1).category)

        // Already so: nothing is written.
        val writes = fake.writes.size
        tagger.setCategories(address(1), listOf(null))
        assertEquals(writes, fake.writes.size)
    }

    @Test
    fun `an empty slot's category is not set`() = runTest {
        val fake = FakeSubsequent37(backgroundScope)
        val tagger = subsequent37(fake).apply { connect() }.tagger
        try {
            tagger.setCategories(address(Subsequent37Fixtures.emptyPrograms(1).single()), listOf(CategoryRef(vox)))
            fail("an empty slot was given a category")
        } catch (expected: InstrumentException.NotSupported) {
        }
        assertTrue(fake.writes.isEmpty())
    }
}
