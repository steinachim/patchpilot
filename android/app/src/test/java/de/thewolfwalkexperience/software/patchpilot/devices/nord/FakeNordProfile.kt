// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The fictitious Nord the protocol tests run against.
 *
 * A test fixture, with its collaborators [FakeNordTransport] and [FakeNordCatalog]; nothing here
 * reaches the APK.
 */
object FakeNordProfile {

    /** Profile id for a session backed by the [FakeNordTransport] fixture rather than hardware. */
    const val ID = "fake_nord"

    /**
     * Firmware version [FakeNordTransport] reports on its fake control read. Kept beside [profile]
     * so the two can't drift apart - if they ever disagreed, `NordDevice.connect()` would reject
     * the fixture's own fake firmware.
     */
    const val FIRMWARE_VERSION = 100

    /**
     * Four banks of 25 slots, matching the `FakeNordCatalog` fixture's dummy program layout. Not
     * backed by any USB device - vendorId/productId are never dialed, only carried for display.
     */
    fun profile() = DeviceProfile(
        id = ID,
        name = "Fake Nord",
        vendorId = -1,
        productId = -1,
        maxBankLetter = 'D',
        maxGroup = 5,
        slotsPerGroup = 5,
        maxDisplayTextLen = 16,
        supportedFirmwareVersions = setOf(FIRMWARE_VERSION),
    )
}
