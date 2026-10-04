// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Lowercase hex, two digits per byte with [separator] between them: the form every raw field in
 * a device report uses (no separator) and every protocol log line (a space).
 */
fun ByteArray.toHex(separator: String = ""): String = joinToString(separator) { byte ->
    val value = byte.toInt() and 0xFF
    "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0x0F]}"
}
