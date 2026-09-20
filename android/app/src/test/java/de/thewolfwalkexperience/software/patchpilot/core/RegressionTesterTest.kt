// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoUsbTransport
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordInstrument
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoProfile

/**
 * What the debug menu's regression test does to an instrument, checked against the wire-level Nord
 * fake and the in-memory demo instrument rather than hardware.
 *
 * **The property under test is "the instrument is exactly as it was afterwards."** Every case below
 * compares the whole program listing before and after the run, because that - not the individual
 * PASS lines - is what makes a test suite safe to point at somebody's own instrument.
 */
class RegressionTesterTest {

    /** A Nord whose wire is answered by the stateful fake: real protocol code, no hardware. */
    private suspend fun nord(): NordInstrument =
        NordInstrument(NordDevice(DemoUsbTransport(), DemoProfile.profile())).also { it.connect() }

    /** A family with no copy of its own - see [NoCopyFixtureInstrument]. */
    private suspend fun noCopyFamily(): NoCopyFixtureInstrument =
        NoCopyFixtureInstrument().also { it.connect() }

    /** The whole listing, which is what "unchanged" is judged on. */
    private suspend fun listing(instrument: Instrument): Map<SlotAddress, String> =
        instrument.browser.index().toList()
            .filterIsInstance<IndexUpdate.Slots>()
            .flatMap { it.slots }
            .filterNot { it.isEmpty }
            .associate { it.address to it.name!! }

    private fun RegressionReport.of(name: String): RegressionResult =
        results.single { it.name == name }

    private fun RegressionReport.failures(): List<RegressionResult> =
        results.filter { it.status == Status.FAIL }

    /**
     * An instrument whose selector is blocked by device state until its own remedy is applied.
     *
     * Models a Motif XS in Performance mode, which ignores a voice selection silently and offers
     * a documented mode change as the fix.
     */
    private class BlockedUntilRemedied(
        private val delegate: Instrument,
    ) : Instrument by delegate {
        var remedyApplied = false
            private set

        override val selector = object : PresetSelector {
            override suspend fun select(address: SlotAddress) {
                if (!remedyApplied) {
                    throw InstrumentException.BlockedByDeviceState(
                        message = "The instrument is in Performance mode.",
                        remedyLabel = "Switch to Voice mode",
                        remedyDetail = "Changes what it is playing.",
                        remedy = { remedyApplied = true },
                    )
                }
                delegate.selector!!.select(address)
            }

            override fun confirmationFor(displayId: String) =
                delegate.selector!!.confirmationFor(displayId)
        }
    }

    /**
     * The regression test takes an offered remedy instead of failing the whole suite.
     *
     * Everywhere else a remedy is put to the user first, because it changes what a player hears.
     * This is a debug run they launched deliberately, and one that already has permission to write
     * presets - a suite reported as FAIL because the instrument was in the wrong mode tells nobody
     * anything.
     */
    @Test
    fun `a blocked step applies the offered remedy and carries on`() = runTest {
        val instrument = BlockedUntilRemedied(nord())
        val before = listing(instrument)

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        assertTrue("the remedy should have been applied", instrument.remedyApplied)
        assertEquals(Status.PASS, report.of("Load a preset").status)
        // Named in the step, so the report never implies the instrument was left as found...
        assertTrue(
            "the step should say what it changed, was: ${report.of("Load a preset").detail}",
            report.of("Load a preset").detail.contains("Switch to Voice mode"),
        )
        // ...and disclosed in the headline, where someone walking back to the instrument will see it.
        assertTrue(
            "the summary should disclose it, was: ${report.summary}",
            report.summary.contains("Switch to Voice mode"),
        )
    }

    /** With no remedy on offer, a blocked step is still a plain failure. */
    @Test
    fun `a blocked step with no remedy fails`() = runTest {
        val instrument = nord()
        val occupied = listing(instrument).keys
        val blocked = object : Instrument by instrument {
            override val selector = object : PresetSelector {
                override suspend fun select(address: SlotAddress) =
                    throw InstrumentException.BlockedByDeviceState("Nothing can be done about this.")
                override fun confirmationFor(displayId: String) = "unused"
            }
        }
        val report = RegressionTester(
            instrument = blocked,
            allSlots = { blocked.layout.allAddresses().toList() },
            // Real occupied slots, or the select test skips before it ever reaches the selector -
            // which is what this test would then be silently asserting nothing about.
            occupiedSlots = { occupied },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        assertEquals(Status.FAIL, report.of("Load a preset").status)
        assertFalse(report.summary.contains("Changed on the instrument"))
    }

    @Test
    fun `a Nord with a free slot runs every edit against a sandbox copy and leaves nothing behind`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        var realSlotAsked = 0

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { realSlotAsked++; true },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        listOf(
            "Load a preset", "Build a device report", "Copy a preset",
            "Rename a preset", "Move a preset", "Swap two presets", "Delete a preset",
        ).forEach { assertEquals(it, Status.PASS, report.of(it).status) }

        // The whole point of the sandbox: no question is ever put to the user, because no slot
        // holding real data is written.
        assertEquals(0, realSlotAsked)
        assertEquals(before, listing(instrument))
    }

    /** The instrument names the copy, so the report has to carry the name it chose. */
    @Test
    fun `the copy test reports the name the instrument gave the copy`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        val source = instrument.layout.allAddresses().first { it in before.keys }

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        val copy = report.of("Copy a preset")
        assertEquals(Status.PASS, copy.status)
        // Derived from the source's name but not equal to it - the instrument disambiguates.
        val sourceName = before.getValue(source)
        assertTrue(copy.detail, copy.detail.contains("named it \"$sourceName "))
    }

    /**
     * A family without copy has no sandbox to work in, so its edits are only run after the user
     * has agreed to them touching real data - and each one reverts itself.
     */
    @Test
    fun `a family without copy tests against real data once confirmed, and reverts`() = runTest {
        val instrument = noCopyFamily()
        val before = listing(instrument)
        var realSlotAsked = 0

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { realSlotAsked++; true },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        // One question covering all three, not one per operation.
        assertEquals(1, realSlotAsked)
        listOf("Rename a preset", "Move a preset", "Swap two presets").forEach {
            assertEquals(it, Status.PASS, report.of(it).status)
            assertTrue(report.of(it).detail, report.of(it).detail.contains("real data"))
        }
        assertEquals(Status.SKIPPED, report.of("Copy a preset").status)
        // Never attempted without a sandbox, whatever the user answered.
        assertEquals(Status.SKIPPED, report.of("Delete a preset").status)
        assertEquals(before, listing(instrument))
    }

    /**
     * The swap is the one sandbox test that needs a second occupied slot, and the obvious partner
     * - the preset the copy was made from - is real data: a process killed between the swap and
     * the swap-back left that preset sitting in the scratch slot on a real instrument. So the
     * partner is a second copy, and no address the instrument held before the run is ever passed
     * to `swap` at all.
     */
    @Test
    fun `the swap test swaps two copies, never a stored preset`() = runTest {
        val real = nord()
        val before = listing(real)
        val swapped = mutableListOf<Pair<SlotAddress, SlotAddress>>()
        val instrument = object : Instrument by real {
            override val editor = object : PresetEditor by real.editor!! {
                override suspend fun swap(a: SlotAddress, b: SlotAddress) {
                    swapped += a to b
                    real.editor!!.swap(a, b)
                }
            }
        }

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { real.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { fail("no question is asked while there is room for a second copy"); false },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        assertEquals(Status.PASS, report.of("Swap two presets").status)
        // Once there and once back.
        assertEquals(2, swapped.size)
        swapped.flatMap { it.toList() }.forEach { address ->
            assertFalse("$address held a real preset and was swapped", address in before.keys)
        }
        // Both copies are gone afterwards, which is the property everything above serves.
        val delete = report.of("Delete a preset")
        assertEquals(Status.PASS, delete.status)
        assertTrue(delete.detail, delete.detail.startsWith("Deleted the copies from"))
        assertEquals(before, listing(real))
    }

    /**
     * With exactly one free slot the copy has nowhere to be swapped with but the preset it came
     * from, which is the no-sandbox path's situation narrowed to one test - and it is asked the
     * same way. Move is skipped for the same lack of room.
     */
    @Test
    fun `with one free slot the swap asks first, and runs against real data once confirmed`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        val free = instrument.layout.allAddresses().first { it !in before.keys }
        val asked = mutableListOf<OccupiedSlotReason>()

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { (before.keys + free).toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { asked += it; true },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        assertEquals(listOf(OccupiedSlotReason.NO_SECOND_FREE_SLOT), asked)
        assertEquals(Status.PASS, report.of("Copy a preset").status)
        assertEquals(Status.SKIPPED, report.of("Move a preset").status)
        val swap = report.of("Swap two presets")
        assertEquals(Status.PASS, swap.status)
        assertTrue(swap.detail, swap.detail.contains("real data"))
        assertEquals(Status.PASS, report.of("Delete a preset").status)
        assertEquals(before, listing(instrument))
    }

    @Test
    fun `with one free slot a declined swap is skipped and the copy is still deleted`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        val free = instrument.layout.allAddresses().first { it !in before.keys }

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { (before.keys + free).toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { false },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        val swap = report.of("Swap two presets")
        assertEquals(Status.SKIPPED, swap.status)
        assertEquals("declined by the user", swap.detail)
        assertEquals(Status.PASS, report.of("Delete a preset").status)
        assertEquals(before, listing(instrument))
    }

    /**
     * The other reason there is no sandbox: nothing is free. Move has nowhere to go either, which
     * is a skip rather than a failure - but rename and swap can still run and revert.
     */
    @Test
    fun `with no free slot at all, rename and swap run against real data and move is skipped`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        var realSlotAsked = 0

        val report = RegressionTester(
            instrument = instrument,
            // Every address the instrument has is occupied - the full bank case.
            allSlots = { before.keys.toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { realSlotAsked++; true },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        assertEquals(1, realSlotAsked)
        assertEquals(Status.PASS, report.of("Rename a preset").status)
        assertEquals(Status.PASS, report.of("Swap two presets").status)
        assertEquals(Status.SKIPPED, report.of("Move a preset").status)
        assertEquals(Status.SKIPPED, report.of("Copy a preset").status)
        assertEquals(Status.SKIPPED, report.of("Delete a preset").status)
        assertEquals(before, listing(instrument))
    }

    @Test
    fun `declining the occupied-slot warning skips every edit and writes nothing`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        var realSlotAsked = 0

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { before.keys.toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { realSlotAsked++; false },
            refreshEdited = {},
        ).run {}

        assertEquals(emptyList<RegressionResult>(), report.failures())
        assertEquals(1, realSlotAsked)
        listOf("Rename a preset", "Move a preset", "Swap two presets").forEach {
            assertEquals(it, Status.SKIPPED, report.of(it).status)
            assertTrue(report.of(it).detail, report.of(it).detail.contains("declined"))
        }
        assertEquals(before, listing(instrument))
    }

    /**
     * The user is the only witness to what the instrument's display actually shows, so answering
     * "no" has to fail the test rather than pass it quietly.
     */
    @Test
    fun `a select the user does not see on the instrument is a failure`() = runTest {
        val instrument = nord()
        val before = listing(instrument)

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { false },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        assertEquals(Status.FAIL, report.of("Load a preset").status)
        // A failed test does not end the run - the edits after it still say something.
        assertEquals(Status.PASS, report.of("Copy a preset").status)
    }

    /**
     * The decoy select is only worth anything if it is confirmed too - an unconfirmed decoy select
     * that silently failed would leave the target's own "yes" meaning nothing, since the display
     * could still be showing whatever it showed before this test ran.
     */
    @Test
    fun `the select test checks a decoy preset before the target, to rule out a stale selection`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        val shown = mutableListOf<String>()

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { text -> shown += text; true },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        assertEquals(Status.PASS, report.of("Load a preset").status)
        // Two distinct presets confirmed, not one - the decoy first, then the real target.
        assertEquals(2, shown.size)
        assertNotEquals(shown[0], shown[1])
    }

    /** A decoy that fails its own confirmation ends the test there - the target is never sent. */
    @Test
    fun `a decoy the user does not see fails the test before the target is even selected`() = runTest {
        val instrument = nord()
        val before = listing(instrument)
        var confirmations = 0

        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { before.keys },
            onConfirmSelect = { confirmations++; false },
            onConfirmRealSlotMutation = { true },
            refreshEdited = {},
        ).run {}

        assertEquals(Status.FAIL, report.of("Load a preset").status)
        // Only the decoy was asked about - the run stopped there rather than going on to the target.
        assertEquals(1, confirmations)
    }

    @Test
    fun `the inventory records which facets the instrument offers`() = runTest {
        val instrument = noCopyFamily()
        val occupied = listing(instrument).keys
        val report = RegressionTester(
            instrument = instrument,
            allSlots = { instrument.layout.allAddresses().toList() },
            occupiedSlots = { occupied },
            onConfirmSelect = { true },
            onConfirmRealSlotMutation = { false },
            refreshEdited = {},
        ).run {}

        assertEquals(Status.PASS, report.of("Offers: Loads presets").status)
        assertEquals(Status.PASS, report.of("Offers: Edits presets").status)
        // This profile mirrors a Pro-800, which has no device report of its own.
        assertEquals(Status.SKIPPED, report.of("Offers: Describes itself").status)
        assertNotEquals("", report.summary)
        assertTrue(report.asText(), report.asText().contains(report.summary))
    }
}
