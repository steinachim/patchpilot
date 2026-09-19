// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection, xorout 0), used to checksum
 * every message on the Nord wire protocol.
 */
fun crc16CcittFalse(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
    var crc = 0xFFFF
    for (i in offset until offset + length) {
        crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
    }
    return crc
}
