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
 * **This text is device-supplied and lands next to a trust decision**: both the USB picker and the
 * "unrecognized, continue at your own risk" gate show it, and that gate's whole point is an honest
 * choice. A name free to contain bidi overrides and zero-width characters could visually disguise
 * one device as another.
 *
 * Shared by the USB and MIDI discovery paths rather than duplicated: two copies of a
 * security-adjacent rule are two rules that can drift.
 *
 * Returns null rather than a placeholder so each caller can name its own fallback.
 */
fun sanitizeDeviceText(raw: String?): String? {
    val cleaned = raw?.replace(UNSAFE_DEVICE_TEXT, "")?.trim() ?: return null
    val truncated =
        if (cleaned.length > MAX_DEVICE_TEXT_LEN) cleaned.take(MAX_DEVICE_TEXT_LEN) + "…" else cleaned
    return truncated.ifBlank { null }
}
