// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoUsbTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoProfile

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
    fun `grouped format round-trips every address in a bank`() {
        for (slot in 0 until 25) {
            val address = SlotAddress(3, slot)
            assertEquals(address, grand.parse(grand.format(address)))
        }
    }

    @Test
    fun `grouped format accepts leading zeros and lowercase, like the CLI does`() {
        assertEquals(SlotAddress(0, 0), grand.parse("a:01:01"))
        assertEquals(SlotAddress(1, 12), grand.parse(" B:3:3 "))
    }

    @Test
    fun `grouped format rejects an out-of-range group or slot`() {
        assertThrows(IllegalArgumentException::class.java) { grand.parse("A:6:1") }
        assertThrows(IllegalArgumentException::class.java) { grand.parse("A:1:6") }
        assertThrows(IllegalArgumentException::class.java) { grand.parse("A00") }
    }

    /**
     * The formatter is a lift of two `NordDevice` methods, so it has to agree with them exactly -
     * otherwise an id shown by the browser would not be the id the protocol layer resolves. This
     * is the test that would have caught a transcription slip in the group arithmetic.
     */
    @Test
    fun `grouped format agrees with NordDevice for every address it can hold`() {
        val device = NordDevice(DemoUsbTransport(), DemoProfile.profile())
        val format = GroupedBankAddressFormat(
            groupsPerBank = DemoProfile.profile().maxGroup,
            slotsPerGroup = DemoProfile.profile().slotsPerGroup,
        )
        for (bank in 0 until 4) {
            for (item in 0 until 25) {
                val fromDevice = device.formatPresetId(bank, item)
                assertEquals(fromDevice, format.format(SlotAddress(bank, item)))
                val parsed = device.parsePresetId(fromDevice)
                assertEquals(SlotAddress(parsed.bank, parsed.item), format.parse(fromDevice))
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
    fun `flat format round-trips the whole Pro-800 address space`() {
        for (bank in 0 until 4) {
            for (slot in 0 until 100) {
                val address = SlotAddress(bank, slot)
                assertEquals(address, pro800.parse(pro800.format(address)))
            }
        }
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
    fun `linear format round-trips every preset and maps to the wire number`() {
        for (number in 0 until 512) {
            val address = markI.addressOf(number)
            assertEquals(number, markI.numberOf(address))
            assertEquals(address, markI.parse(markI.format(address)))
        }
    }

    @Test
    fun `linear format accepts a number with or without leading zeros`() {
        assertEquals(SlotAddress(0, 7), markI.parse("7"))
        assertEquals(SlotAddress(0, 7), markI.parse("007"))
        assertEquals(SlotAddress(1, 0), markI.parse(" 128 "))
    }

    @Test
    fun `linear format refuses a number it does not have, a letter, or nothing`() {
        for (id in listOf("512", "-1", "A00", "", "1.5")) {
            assertThrows(IllegalArgumentException::class.java) { markI.parse(id) }
        }
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
}
