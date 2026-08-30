package de.thewolfwalkexperience.software.patchpilot.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops the app sending a vendor protocol at hardware it has not identified.
 *
 * Worth real tests despite being twelve lines: before it existed, picking any attached USB device
 * - a keyboard, a hub, a charger - claimed its interface and spoke Clavia's bulk protocol at it.
 */
class UnknownDevicePolicyTest {

    /** The real ids, so a catalog edit that changed them would surface here. */
    private val clavia = 4092
    private val yamaha = 1177
    private val behringer = 5015

    private fun usb(id: String, family: String, vendorId: Int, productId: Int) =
        InstrumentDescriptor(
            id = id,
            family = family,
            name = id,
            match = DeviceMatch.Usb(vendorId = vendorId, productId = productId),
        )

    private val catalog = listOf(
        usb("nord_grand", "nord", clavia, 1),
        usb("nord_stage_2_ex", "nord", clavia, 2),
        usb("yamaha_motif_xs6", "motifxs", yamaha, 4162),
        usb("behringer_pro800", "pro800", behringer, 4703),
    )

    @Test
    fun `an unrecognised Clavia device may be guessed at`() {
        assertTrue(UnknownDevicePolicy.allows(catalog, clavia))
    }

    /**
     * The decision of 2026-08-21, asserted rather than only documented.
     *
     * Two Nord models have been verified to share one protocol; Yamaha and Behringer contribute
     * one instrument each, and one instrument is no evidence about a vendor.
     */
    @Test
    fun `a Yamaha or Behringer device may not be`() {
        assertFalse(UnknownDevicePolicy.allows(catalog, yamaha))
        assertFalse(UnknownDevicePolicy.allows(catalog, behringer))
    }

    @Test
    fun `a vendor in no catalog at all may not be`() {
        assertFalse("a USB keyboard must not be opened as a Nord",
            UnknownDevicePolicy.allows(catalog, 0x1234))
    }

    /**
     * Fails closed.
     *
     * `InstrumentRegistry.allDescriptors` swallows a family whose catalog will not load, so an
     * empty list is a reachable state rather than a hypothetical - and it must refuse everything
     * rather than fall back to a hardcoded id.
     */
    @Test
    fun `an empty catalog refuses every device`() {
        assertFalse(UnknownDevicePolicy.allows(emptyList(), clavia))
        assertTrue(UnknownDevicePolicy.vendorIdsAllowingAGuess(emptyList()).isEmpty())
    }

    /** Read from the catalog, so a new Nord model needs no code change here. */
    @Test
    fun `a Nord model added to the catalog is covered without a code change`() {
        val newVendor = 4093
        val extended = catalog + usb("nord_piano_6", "nord", newVendor, 9)
        assertTrue(UnknownDevicePolicy.allows(extended, newVendor))
        assertEquals(setOf(clavia, newVendor), UnknownDevicePolicy.vendorIdsAllowingAGuess(extended))
    }

    /** A MIDI-matched descriptor contributes no vendor id - there is no USB device to guess at. */
    @Test
    fun `only USB matches contribute a vendor id`() {
        assertEquals(setOf(clavia), UnknownDevicePolicy.vendorIdsAllowingAGuess(catalog))
    }
}
