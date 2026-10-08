// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.devices.nord.FakeNordProfile
import de.thewolfwalkexperience.software.patchpilot.devices.nord.FakeNordTransport
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFormatTest {

    private val grand = GroupedBankAddressFormat(groupsPerBank = 5, slotsPerGroup = 5)
    private val pro800 = FlatBankAddressFormat(slotDigits = 2)

    @Test
    fun `grouped format renders bank group and slot`() {
        assertEquals("A:1:1", grand.format(SlotAddress(0, 0)))
        assertEquals("A:1:5", grand.format(SlotAddress(0, 4)))
        assertEquals("A:2:1", grand.format(SlotAddress(0, 5)))
        assertEquals("P:5:5", grand.format(SlotAddress(15, 24)))
    }

    @Test
    fun `grouped format gives every address in a bank its own id`() {
        assertDistinct(grand, (0 until 25).map { SlotAddress(3, it) })
    }

    /**
     * `NordDevice` names its items through this format, built from its profile; the ids it logs
     * and reports have to be the ones the browser shows.
     */
    @Test
    fun `grouped format agrees with NordDevice for every address it can hold`() {
        val device = NordDevice(FakeNordTransport(), FakeNordProfile.profile())
        val format = GroupedBankAddressFormat(
            groupsPerBank = FakeNordProfile.profile().maxGroup,
            slotsPerGroup = FakeNordProfile.profile().slotsPerGroup,
        )
        for (bank in 0 until 4) {
            for (item in 0 until 25) {
                val fromDevice = device.formatPresetId(bank, item)
                assertEquals(fromDevice, format.format(SlotAddress(bank, item)))
            }
        }
    }

    @Test
    fun `flat format renders a zero-padded slot within its bank`() {
        assertEquals("A00", pro800.format(SlotAddress(0, 0)))
        assertEquals("A09", pro800.format(SlotAddress(0, 9)))
        assertEquals("B42", pro800.format(SlotAddress(1, 42)))
        assertEquals("D99", pro800.format(SlotAddress(3, 99)))
    }

    @Test
    fun `flat format gives every Pro-800 address its own id`() {
        assertDistinct(pro800, (0 until 4).flatMap { bank -> (0 until 100).map { SlotAddress(bank, it) } })
    }

    /**
     * The reason `PresetSlot` carries its own bank label rather than the UI recovering one from
     * the display id: the trick that works for a Nord id is silently wrong for a flat one.
     */
    @Test
    fun `a flat id has no colon for substringBefore to find`() {
        val flat = pro800.format(SlotAddress(2, 7))
        assertEquals(flat, flat.substringBefore(':'))
        assertEquals("C", pro800.bankLabel(2))
    }

    @Test
    fun `uniform layout enumerates every address in device order`() {
        val layout = SlotLayout.uniform(bankCount = 2, slotsPerBank = 3, format = pro800)
        assertEquals(6, layout.slotCount)
        assertEquals(
            listOf("A00", "A01", "A02", "B00", "B01", "B02"),
            layout.allAddresses().map { layout.format.format(it) }.toList(),
        )
    }

    private val markI = LinearAddressFormat(presetCount = 512, presetsPerBank = 128)

    @Test
    fun `linear format renders one run of numbers with no bank in the id`() {
        assertEquals("000", markI.format(SlotAddress(0, 0)))
        assertEquals("007", markI.format(SlotAddress(0, 7)))
        assertEquals("127", markI.format(SlotAddress(0, 127)))
        assertEquals("128", markI.format(SlotAddress(1, 0)))
        assertEquals("511", markI.format(SlotAddress(3, 127)))
    }

    @Test
    fun `linear format maps every preset to its wire number and its own id`() {
        for (number in 0 until 512) {
            val address = markI.addressOf(number)
            assertEquals(number, markI.numberOf(address))
            assertEquals(number.toString().padStart(3, '0'), markI.format(address))
        }
    }

    @Test
    fun `linear format refuses an address or a number it does not have`() {
        assertThrows(IllegalArgumentException::class.java) { markI.addressOf(512) }
        assertThrows(IllegalArgumentException::class.java) { markI.numberOf(SlotAddress(4, 0)) }
        assertThrows(IllegalArgumentException::class.java) { markI.numberOf(SlotAddress(0, 128)) }
    }

    @Test
    fun `linear format labels its virtual banks with number ranges`() {
        assertEquals(listOf("000–127", "128–255", "256–383", "384–511"), (0 until 4).map(markI::bankLabel))
        assertEquals(listOf("000", "128", "256", "384"), (0 until 4).map(markI::railLabel))
    }

    @Test
    fun `the digit count follows the preset count`() {
        val markII = LinearAddressFormat(presetCount = 1024, presetsPerBank = 128)
        assertEquals("0000", markII.format(SlotAddress(0, 0)))
        assertEquals("1023", markII.format(SlotAddress(7, 127)))
        assertEquals("0896–1023", markII.bankLabel(7))
        assertEquals(8, markII.layout().bankCount)
    }

    @Test
    fun `a short last bank ends at the last preset`() {
        val format = LinearAddressFormat(presetCount = 300, presetsPerBank = 128)
        assertEquals("256–299", format.bankLabel(2))
        assertEquals(listOf(128, 128, 44), format.layout().banks.map { it.slotCount })
    }

    @Test
    fun `a linear layout is virtual banks in device order`() {
        val layout = markI.layout()
        assertEquals(512, layout.slotCount)
        assertTrue(layout.banks.all { it.virtual })
        assertEquals(layout.banks.indices.map(markI::bankLabel), layout.banks.map { it.label })
        assertEquals(
            (0 until 512).map { it.toString().padStart(3, '0') },
            layout.allAddresses().map { layout.format.format(it) }.toList(),
        )
    }

    @Test
    fun `a negative address is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) { SlotAddress(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotAddress(0, -1) }
    }

    /** The property a row's id has to have: no two addresses share one. */
    private fun assertDistinct(format: AddressFormat, addresses: List<SlotAddress>) {
        val ids = addresses.map { format.format(it) }
        assertEquals("ids repeat: $ids", ids.size, ids.toSet().size)
    }
}
