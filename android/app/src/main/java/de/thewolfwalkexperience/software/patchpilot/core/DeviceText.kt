// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * Bidi override/embedding characters, zero-width characters, and other control characters a
 * hostile peripheral could put in the name it reports.
 */
private val UNSAFE_DEVICE_TEXT =
    Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")

/** Long enough for any real product name, short enough not to push a warning off the screen. */
private const val MAX_DEVICE_TEXT_LEN = 64

/**
 * Cleans a name a device reported about itself, or null if nothing legible is left.
 *
 * The text is device-supplied and lands next to a trust decision (the USB picker and the
 * "unrecognized, continue at your own risk" gate), and a name carrying bidi overrides or
 * zero-width characters could visually disguise one device as another. Shared by every path that
 * shows device text. Returns null rather than a placeholder so each caller names its own
 * fallback.
 */
fun sanitizeDeviceText(raw: String?): String? {
    val cleaned = raw?.replace(UNSAFE_DEVICE_TEXT, "")?.trim() ?: return null
    val truncated =
        if (cleaned.length > MAX_DEVICE_TEXT_LEN) cleaned.take(MAX_DEVICE_TEXT_LEN) + "…" else cleaned
    return truncated.ifBlank { null }
}
