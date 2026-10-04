// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val fractal = 9318
    private val moog = 9826

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
        usb("fractal_axefx3", "axefx3", fractal, 32784),
        usb("moog_subsequent37", "subsequent37", moog, 4092),
    )

    @Test
    fun `an unrecognised Clavia device may be guessed at`() {
        assertTrue(UnknownDevicePolicy.allows(catalog, clavia))
    }

    /**
     * The policy's rule, asserted rather than only documented.
     *
     * Two Nord models have been verified to share one protocol; Yamaha, Behringer, Fractal and
     * Moog contribute one instrument each, and one instrument is no evidence about a vendor.
     */
    @Test
    fun `a Yamaha, Behringer, Fractal or Moog device may not be`() {
        assertFalse(UnknownDevicePolicy.allows(catalog, yamaha))
        assertFalse(UnknownDevicePolicy.allows(catalog, behringer))
        assertFalse(UnknownDevicePolicy.allows(catalog, fractal))
        assertFalse(UnknownDevicePolicy.allows(catalog, moog))
    }

    @Test
    fun `a vendor in no catalog at all may not be`() {
        assertFalse(
            "a USB keyboard must not be opened as a Nord",
            UnknownDevicePolicy.allows(catalog, 0x1234),
        )
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
        assertNull(UnknownDevicePolicy.familyFor(emptyList(), clavia))
    }

    /** Read from the catalog, so a new Nord model needs no code change here. */
    @Test
    fun `a Nord model added to the catalog is covered without a code change`() {
        val newVendor = 4093
        val extended = catalog + usb("nord_piano_6", "nord", newVendor, 9)
        assertTrue(UnknownDevicePolicy.allows(extended, newVendor))
        assertEquals("nord", UnknownDevicePolicy.familyFor(extended, newVendor))
    }

    /** The guess names the family whose protocol is tried, which is what builds the instrument. */
    @Test
    fun `the guess names the Nord family`() {
        assertEquals("nord", UnknownDevicePolicy.familyFor(catalog, clavia))
    }
}
