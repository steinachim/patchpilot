package de.thewolfwalkexperience.software.patchpilot.devices.nord

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [DemoUsbTransport] through a real [NordDevice] - the same shape as
 * NordDeviceProtocolTest, but proving the fake instrument itself answers the wire protocol
 * correctly (round-trips through [NordMessage]'s CRC framing, not just returns plausible-looking
 * data), and that mutations (rename/move/swap) persist across calls within a session.
 */
class DemoUsbTransportTest {

    private fun demoDevice(catalog: DemoCatalog = DemoCatalog()): NordDevice =
        NordFixtures.device(DemoUsbTransport(catalog), DemoProfile.profile())

    @Test
    fun `connect succeeds against the fake firmware and protocol version table`() = runTest {
        val device = demoDevice()
        device.connect()
        assertEquals(DemoProfile.FIRMWARE_VERSION, device.firmwareVersion)
    }

    @Test
    fun `listRootCategories returns the catalog's categories in order`() = runTest {
        val catalog = DemoCatalog()
        val device = demoDevice(catalog)
        device.connect()
        assertEquals(catalog.rootCategoryNames, device.listRootCategories())
    }

    @Test
    fun `listPrograms round-trips the Program category's occupied slots`() = runTest {
        val catalog = DemoCatalog()
        val device = demoDevice(catalog)
        device.connect()

        val programIndex = device.getCategoryIndexByName("Program")
        val expected = catalog.itemsInCategory(programIndex)
            .map { (slot, name) -> device.formatPresetId(slot.bank, slot.item) to name }
            .toSet()

        val actual = device.collectItemNames(device.fetchCategoryItems(programIndex))
            .map { it.presetId to it.name }
            .toSet()

        assertEquals(expected, actual)
    }

    @Test
    fun `listCategoryItems works for a non-Program category too`() = runTest {
        val catalog = DemoCatalog()
        val device = demoDevice(catalog)
        device.connect()

        val pianoIndex = device.getCategoryIndexByName("Piano")
        val names = device.collectItemNames(device.fetchCategoryItems(pianoIndex)).map { it.name }.toSet()
        assertEquals(catalog.itemsInCategory(pianoIndex).values.toSet(), names)
    }

    @Test
    fun `selectPreset succeeds for an occupied slot`() = runTest {
        val device = demoDevice()
        device.connect()
        device.selectPreset(0, 0) // "Init Program" in DemoCatalog
    }

    @Test
    fun `renamePreset persists and is visible on the next listPrograms`() = runTest {
        val device = demoDevice()
        device.connect()
        device.renamePreset(0, 0, "Renamed Patch")

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex))
        assertTrue(names.any { it.presetId == device.formatPresetId(0, 0) && it.name == "Renamed Patch" })
    }

    @Test
    fun `moveProgram relocates a program to an empty slot`() = runTest {
        val device = demoDevice()
        device.connect()
        // (0, 0) is occupied ("Init Program"); (3, 24) is left empty by DemoCatalog.
        device.moveProgram(0, 0, 3, 24)

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex))
        assertTrue(names.none { it.presetId == device.formatPresetId(0, 0) })
        assertTrue(names.any { it.presetId == device.formatPresetId(3, 24) && it.name == "Init Program" })
    }

    @Test
    fun `swapPrograms exchanges two occupied slots`() = runTest {
        val device = demoDevice()
        device.connect()
        // (0, 0) = "Init Program", (0, 1) = "Warm Pad" in DemoCatalog.
        device.swapPrograms(0, 0, 0, 1)

        val programIndex = device.getProgramCategoryIndex()
        val names = device.collectItemNames(device.fetchCategoryItems(programIndex)).associate { it.presetId to it.name }
        assertEquals("Warm Pad", names[device.formatPresetId(0, 0)])
        assertEquals("Init Program", names[device.formatPresetId(0, 1)])
    }

}
