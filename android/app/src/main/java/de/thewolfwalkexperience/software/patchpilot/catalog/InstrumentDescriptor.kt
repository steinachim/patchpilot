// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One instrument model, as the catalog describes it: a family-agnostic core plus an opaque block
 * only that family understands.
 *
 * **Why the split.** A single flat schema across families degenerates into a record that is mostly
 * null. Keeping the core small means a new family adds a factory and a JSON block and changes no
 * shared type, and a family's own fields can change without touching anything above
 * [familyConfig].
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

/**
 * How to recognize an instrument. A sealed type rather than a VID/PID pair, because the second
 * family is not found the same way as the first.
 */
@Serializable
sealed interface DeviceMatch {

    /**
     * A USB device with these ids, on the USB host bus.
     *
     * [endpointOut]/[endpointIn] default to the Nord vendor interface's pair. They are here rather
     * than hard-coded in the transport because they vary by device - a Motif XS uses 0x01 OUT -
     * and because a wrong one should be a catalog edit. [midiCable] is set only for a device whose
     * bulk endpoints carry USB-MIDI event packets rather than a vendor protocol; it selects which
     * cable to speak on, and its presence is what says "wrap this in a
     * [de.thewolfwalkexperience.software.patchpilot.transport.UsbMidiBulkTransport]".
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
     *
     * **Never matched on port name.** Those vary by OS, by hub, and by whether another app renamed
     * the port; a device that identifies itself in its own words is authoritative where a name is
     * a guess. [usbHint] narrows *which* ports get probed (Android exposes the backing USB device
     * on `MidiDeviceInfo`) but is never the deciding test on its own.
     *
     * **The probe must be read-only and idempotent.** For a Pro-800 that means SysEx `0x06`,
     * "request device name". Nothing in its undocumented ranges may ever be used here: those have
     * unknown side effects, and one of its neighbours is a factory reset with no confirmation.
     */
    @Serializable
    @SerialName("midiIdentity")
    data class MidiIdentity(
        val probeHex: String,
        val replyPrefixHex: String,
        val usbHint: Usb? = null,
        /**
         * Which of the device's MIDI ports to probe and then talk on.
         *
         * Zero for anything with one cable, which is every instrument matched this way. Kept
         * because a class-compliant multi-port device is addressed by port, not just opened by it.
         */
        val portIndex: Int = 0,
    ) : DeviceMatch {
        val probe: ByteArray get() = probeHex.hexToBytes()
        val replyPrefix: ByteArray get() = replyPrefixHex.hexToBytes()
    }
}

internal fun String.hexToBytes(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }
    require(cleaned.length % 2 == 0) { "hex string '$this' has an odd number of digits" }
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * One family's catalog file: its shared configuration, then its devices.
 *
 * **[familyConfig] at this level is not a duplicate of the per-device one.** A Nord catalog
 * carries a 54-entry `programCategories` master list that belongs to no single device - and the
 * master list is deliberately not injective (ids 2 and 37 are both `Wind`), which is exactly why
 * it cannot be folded into per-device maps. The Motif XS catalog keeps its bank table and
 * category encoding here for the same reason: they are properties of the model family rather
 * than of an individual unit.
 */
@Serializable
data class FamilyCatalog(
    val family: String,
    val schemaVersion: Int,
    val familyConfig: JsonObject = JsonObject(emptyMap()),
    val devices: List<InstrumentDescriptor>,
)
