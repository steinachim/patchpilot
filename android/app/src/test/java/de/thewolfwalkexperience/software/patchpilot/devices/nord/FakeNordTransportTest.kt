// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [FakeNordTransport] through a real [NordDevice] - the same shape as
 * NordDeviceProtocolTest, but proving the fake instrument itself answers the wire protocol
 * correctly (round-trips through [NordMessage]'s CRC framing, not just returns plausible-looking
 * data), and that mutations (rename/move/swap) persist across calls within a session.
 */
class FakeNordTransportTest {

    private fun fakeNord(catalog: FakeNordCatalog = FakeNordCatalog()): NordDevice =
        NordFixtures.device(FakeNordTransport(catalog), FakeNordProfile.profile())

    @Test
    fun `connect succeeds against the fake firmware and protocol version table`() = runTest {
        val device = fakeNord()
        device.connect()
        assertEquals(FakeNordProfile.FIRMWARE_VERSION, device.firmwareVersion)
    }

    @Test
    fun `listRootCategories returns the catalog's categories in order`() = runTest {
        val catalog = FakeNordCatalog()
        val device = fakeNord(catalog)
        device.connect()
        assertEquals(catalog.rootCategoryNames, device.listRootCategories())
    }

    @Test
    fun `listPrograms round-trips the Program category's occupied slots`() = runTest {
        val catalog = FakeNordCatalog()
        val device = fakeNord(catalog)
        device.connect()

        val programIndex = device.getCategoryIndexByName("Program")
        val expected = catalog.itemsInCategory(programIndex)
            .map { (slot, name) -> device.formatPresetId(slot.bank, slot.item) to name }
            .toSet()

        val actual = device.collectItemNames(device.fetchCategoryItems(programIndex))
            .map { device.formatPresetId(it.bank, it.item) to it.name }
            .toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `listCategoryItems works for a non-Program category too`() = runTest {
        val catalog = FakeNordCatalog()
        val device = fakeNord(catalog)
        device.connect()

        val pianoIndex = device.getCategoryIndexByName("Piano")
        val names = device.collectItemNames(device.fetchCategoryItems(pianoIndex)).map { it.name }.toSet()
        assertEquals(catalog.itemsInCategory(pianoIndex).values.toSet(), names)
    }

    @Test
    fun `selectPreset loads the slot it names`() = runTest {
        val transport = FakeNordTransport()
        val device = NordFixtures.device(transport, FakeNordProfile.profile())
        device.connect()
        device.selectPreset(0, 3)
        assertEquals(0 to 3, transport.selectedPreset)
    }

    @Test
    fun `renamePreset persists and is visible on the next listPrograms`() = runTest {
        val device = fakeNord()
        device.connect()
        device.renamePreset(0, 0, "Renamed Patch")

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex))
        assertTrue(names.any { it.bank == 0 && it.item == 0 && it.name == "Renamed Patch" })
    }

    @Test
    fun `moveProgram relocates a program to an empty slot`() = runTest {
        val device = fakeNord()
        device.connect()
        // (0, 0) is occupied ("Init Program"); (3, 24) is left empty by FakeNordCatalog.
        device.moveProgram(0, 0, 3, 24)

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex))
        assertTrue(names.none { it.bank == 0 && it.item == 0 })
        assertTrue(names.any { it.bank == 3 && it.item == 24 && it.name == "Init Program" })
    }

    @Test
    fun `swapPrograms exchanges two occupied slots`() = runTest {
        val device = fakeNord()
        device.connect()
        // (0, 0) = "Init Program", (0, 1) = "Warm Pad" in FakeNordCatalog.
        device.swapPrograms(0, 0, 0, 1)

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex))
            .associate { (it.bank to it.item) to it.name }
        assertEquals("Warm Pad", names[0 to 0])
        assertEquals("Init Program", names[0 to 1])
    }
}
