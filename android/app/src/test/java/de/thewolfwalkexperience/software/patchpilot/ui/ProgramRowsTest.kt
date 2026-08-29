package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules `ProgramsScreen` derives its rows from.
 *
 * **These had no coverage at all while they lived inside the composable**, which is most of why
 * [buildProgramListing] was lifted out: the devices differ in what they report, and each rule
 * below was previously only checkable by running the app against hardware.
 */
class ProgramRowsTest {

    private fun slot(bank: Int, index: Int, name: String?) = PresetSlot(
        address = SlotAddress(bank, index),
        displayId = "${'A' + bank}:${index + 1}",
        bankLabel = ('A' + bank).toString(),
        name = name,
    )

    /** Every address the layout allows, as the view model would supply it. */
    private fun allSlots(banks: Int = 2, perBank: Int = 3) =
        (0 until banks).flatMap { b -> (0 until perBank).map { slot(b, it, null) } }

    /** A Nord: lists only what it holds. */
    private val nordReported = listOf(slot(0, 0, "Grand"), slot(0, 2, "Rhodes"), slot(1, 1, "Pad"))

    /** A Pro-800: answers for every address, empty ones included. */
    private val pro800Reported = listOf(
        slot(0, 0, "Grand"), slot(0, 1, null), slot(0, 2, "Rhodes"),
        slot(1, 0, null), slot(1, 1, "Pad"), slot(1, 2, null),
    )

    private fun entries(listing: ProgramListing) =
        listing.rows.filterIsInstance<ProgramRow.ProgramEntry>().map { it.item }

    @Test
    fun `occupied counts only slots that hold a preset, whichever way the family reports`() {
        val fromNord = buildProgramListing(nordReported, allSlots(), false, false, "")
        val fromPro800 = buildProgramListing(pro800Reported, allSlots(), false, false, "")
        assertEquals(fromNord.occupied, fromPro800.occupied)
        assertEquals(3, fromNord.occupied.size)
    }

    /**
     * A Pro-800 answers for all 400 addresses, which made every empty slot look occupied and gave
     * it a drag handle and a Rename button it had no business offering.
     */
    @Test
    fun `an unchecked box hides empty slots even when the instrument reported them`() {
        val listing = buildProgramListing(pro800Reported, allSlots(), false, false, "")
        assertEquals(3, entries(listing).size)
        assertTrue(entries(listing).none { it.isEmpty })
    }

    /** A Nord lists only what it holds, so showing empties means *adding* placeholder rows. */
    @Test
    fun `checking the box fills in the gaps a sparse listing leaves`() {
        val listing = buildProgramListing(nordReported, allSlots(), true, false, "")
        assertEquals(6, entries(listing).size)
        assertEquals(listOf("Grand", null, "Rhodes", null, "Pad", null), entries(listing).map { it.name })
    }

    @Test
    fun `a filter matches names case-insensitively and always excludes empty slots`() {
        val listing = buildProgramListing(pro800Reported, allSlots(), true, false, "rhod")
        assertEquals(listOf("Rhodes"), entries(listing).map { it.name })
    }

    /**
     * Picking a copy destination overrides both the toggle and the filter: the target the user
     * needs might be hidden by either, and a filter has nothing to match against an unnamed slot.
     */
    @Test
    fun `picking a copy destination shows every address regardless of filter or toggle`() {
        val listing = buildProgramListing(nordReported, allSlots(), false, true, "rhod")
        assertEquals(6, entries(listing).size)
        assertEquals(listOf("A", "B"), listing.bankLabels)
    }

    @Test
    fun `free slots are every address the layout allows that holds nothing`() {
        val listing = buildProgramListing(nordReported, allSlots(), false, false, "")
        assertEquals(3, listing.freeSlots.size)
        assertTrue(listing.freeSlots.none { it.address in listing.occupied })
    }

    @Test
    fun `a bank header is inserted wherever the bank changes, once each`() {
        val listing = buildProgramListing(nordReported, allSlots(), false, false, "")
        val headers = listing.rows.filterIsInstance<ProgramRow.BankHeader>().map { it.bank }
        assertEquals(listOf("A", "B"), headers)
        assertEquals(mapOf("A" to 0, "B" to 3), listing.bankHeaderIndex)
    }

    /** Shading alternates on preset rows only, so headers must not advance the counter. */
    @Test
    fun `programIndex counts entries only, skipping header rows`() {
        val listing = buildProgramListing(nordReported, allSlots(), false, false, "")
        val indices = listing.rows.filterIsInstance<ProgramRow.ProgramEntry>().map { it.programIndex }
        assertEquals(listOf(0, 1, 2), indices)
    }

    @Test
    fun `a filter matching nothing yields no rows at all, headers included`() {
        val listing = buildProgramListing(nordReported, allSlots(), false, false, "zzz")
        assertTrue(listing.rows.isEmpty())
    }

    /** An instrument that reports nothing still has a layout, and copy still needs its addresses. */
    @Test
    fun `an empty listing still offers every address as a copy destination`() {
        val listing = buildProgramListing(emptyList(), allSlots(), false, false, "")
        assertTrue(listing.rows.isEmpty())
        assertEquals(6, listing.freeSlots.size)
    }
}
