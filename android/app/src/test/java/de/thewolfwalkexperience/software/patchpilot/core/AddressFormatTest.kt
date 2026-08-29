package de.thewolfwalkexperience.software.patchpilot.core

import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice
import de.thewolfwalkexperience.software.patchpilot.devices.nord.DemoUsbTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `a negative address is refused at construction`() {
        assertThrows(IllegalArgumentException::class.java) { SlotAddress(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SlotAddress(0, -1) }
    }
}
