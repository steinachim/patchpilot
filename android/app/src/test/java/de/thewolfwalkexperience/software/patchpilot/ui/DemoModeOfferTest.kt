// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import de.thewolfwalkexperience.software.patchpilot.usb.AttachedUsbDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the connect screen offers demo mode: off the two gates, on the screens they lead back to. */
class DemoModeOfferTest {

    private val device = object : AttachedUsbDevice {
        override val physicalKey = "usb:4092:99"
        override val handle = "usb:/dev/bus/usb/001/002"
        override val label = "Unknown (0x0FFC:0x0063)"
        override val vendorId = 0x0FFC
        override val productId = 0x63
    }

    @Test
    fun `the advisory and unknown-device gates do not offer demo mode`() {
        assertFalse(offersDemoMode(ConnectionState.AdvisoryWarning(DemoInstrument(), "Untested firmware.")))
        assertFalse(offersDemoMode(ConnectionState.UnknownDeviceWarning(device)))
    }

    @Test
    fun `the screens the gates lead back to offer demo mode`() {
        assertTrue(offersDemoMode(ConnectionState.DeviceSelection(emptyList())))
        assertTrue(offersDemoMode(ConnectionState.NothingFound(emptyList())))
    }
}
