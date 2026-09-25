// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The fictitious Nord the protocol tests run against.
 *
 * **A test fixture, and now in the test source set.** These lived in [DeviceProfile]'s companion
 * in `src/main` long after the app's own demo mode moved up to `demo.DemoInstrument` - so
 * the production APK carried a profile only `src/test` referenced. Their collaborators
 * ([DemoUsbTransport], `DemoCatalog`) were already here.
 */
object DemoProfile {

    /** Profile id for a session backed by the [DemoUsbTransport] fixture rather than hardware. */
    const val ID = "demo"

    /** Firmware version [DemoUsbTransport] reports on its fake control read. Kept beside [profile]
     * so the two can't drift apart - if they ever disagreed, `NordDevice.connect()` would reject
     * the fixture's own fake firmware. */
    const val FIRMWARE_VERSION = 100

    /** Four banks of 25 slots, matching the `DemoCatalog` fixture's dummy program layout. Not
     * backed by any USB device - vendorId/productId are never dialed, only carried for display. */
    fun profile() = DeviceProfile(
        id = ID,
        name = "Demo Instrument",
        vendorId = -1,
        productId = -1,
        maxBankLetter = 'D',
        maxGroup = 5,
        slotsPerGroup = 5,
        maxDisplayTextLen = 16,
        supportedFirmwareVersions = setOf(FIRMWARE_VERSION),
    )
}
