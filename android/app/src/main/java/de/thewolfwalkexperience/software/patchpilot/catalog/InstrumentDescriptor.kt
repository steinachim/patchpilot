// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One instrument model, as the catalog describes it: a family-agnostic core plus an opaque block
 * only that family understands, so a new family adds a factory and a JSON block and changes no
 * shared type.
 */
@Serializable
data class InstrumentDescriptor(
    val id: String,
    /** The factory key - "nord", "pro800". */
    val family: String,
    val name: String,
    val match: DeviceMatch,
    /** Everything only this family understands, decoded by its own factory. */
    val familyConfig: JsonObject = JsonObject(emptyMap()),
)

/** How to recognize an instrument: by USB ids, or by probing a MIDI port. */
@Serializable
sealed interface DeviceMatch {

    /**
     * A USB device with these ids, on the USB host bus.
     *
     * [endpointOut]/[endpointIn] default to the Nord vendor interface's pair; a Motif XS uses
     * 0x01 OUT. [midiCable] is set only for a device whose bulk endpoints carry USB-MIDI event
     * packets rather than a vendor protocol, and its presence is what wraps the transport in a
     * [de.thewolfwalkexperience.software.patchpilot.transport.UsbMidiBulkTransport].
     */
    @Serializable
    @SerialName("usb")
    data class Usb(
        val vendorId: Int,
        val productId: Int,
        val endpointOut: Int = 0x03,
        val endpointIn: Int = 0x82,
        val midiCable: Int? = null,
    ) : DeviceMatch

    /**
     * A MIDI port whose device answers [probeHex] with a message starting [replyPrefixHex].
     * Never matched on port name. [usbHint] narrows which ports get probed but never decides.
     *
     * The probe must be read-only and idempotent: for a Pro-800 that is SysEx `0x06`, "request
     * device name". Nothing from an undocumented range may be used here - one of that
     * instrument's undocumented types is a factory reset with no confirmation.
     */
    @Serializable
    @SerialName("midiIdentity")
    data class MidiIdentity(
        val probeHex: String,
        val replyPrefixHex: String,
        val usbHint: Usb? = null,
        /**
         * The USB ids Android should launch the app for when this device is plugged in. Read only
         * by the `generateUsbDeviceFilter` Gradle task; nothing at runtime matches on it. Kept
         * apart from [usbHint], which narrows probing: an instrument reached through some other
         * USB-MIDI interface must still be found.
         */
        val launchOnUsbAttach: UsbIds? = null,
        /** Which of the device's MIDI ports to probe and then talk on. Zero for every instrument matched this way. */
        val portIndex: Int = 0,
    ) : DeviceMatch {
        val probe: ByteArray get() = probeHex.hexToBytes()
        val replyPrefix: ByteArray get() = replyPrefixHex.hexToBytes()
    }
}

/** A USB vendor/product id pair on its own, where no endpoint is involved. */
@Serializable
data class UsbIds(val vendorId: Int, val productId: Int)

internal fun String.hexToBytes(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex string '$this' has an odd number of digits" }
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * One family's catalog file: its shared configuration, then its devices. [familyConfig] at this
 * level holds what belongs to the family rather than to one device - the Motif XS bank table and
 * category encoding.
 */
@Serializable
data class FamilyCatalog(
    val family: String,
    val schemaVersion: Int,
    val familyConfig: JsonObject = JsonObject(emptyMap()),
    val devices: List<InstrumentDescriptor>,
)
