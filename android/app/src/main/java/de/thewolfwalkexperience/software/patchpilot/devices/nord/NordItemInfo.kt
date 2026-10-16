// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import de.thewolfwalkexperience.software.patchpilot.core.InfoRow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an item record says about a program beyond its name and category, as Info rows: the
 * content version, when it was saved, the size of its data and its content id.
 */
internal object NordItemInfo {

    /** The scale of a content version whose tag the catalog does not list: `312` is `3.12`. */
    private const val DEFAULT_VERSION_SCALE = 100

    private val DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * @param record a status-0 sub-opcode 31 record.
     * @param scales [NordCatalog.contentVersionScales].
     * @param contentId the record's CRC-32, or null where this protocol version has none.
     */
    fun rows(record: ByteArray, scales: Map<String, Int>, contentId: Int?): List<InfoRow> {
        val rows = mutableListOf<InfoRow>()
        val tag = NordDevice.parseItemTag(record)
        NordDevice.parseItemContentVersion(record)?.let { version ->
            rows += InfoRow("Version", formatVersion(version, scales[tag] ?: DEFAULT_VERSION_SCALE))
        }
        NordDevice.parseItemTimestamp(record)?.let { seconds ->
            // Assumption: Unix seconds in UTC; the instrument has no time zone to say otherwise.
            rows +=
                InfoRow("Saved", if (seconds == 0L) "Factory content" else DATE.format(Instant.ofEpochSecond(seconds)))
        }
        rows += InfoRow("Data size", "${NordDevice.parseItemDataSize(record)} bytes")
        contentId?.let { rows += InfoRow("Content ID", "%08X".format(Locale.ROOT, it)) }
        return rows
    }

    /**
     * [version] over [scale], with the fraction padded to the scale's width: `101` over 100 is
     * `1.01`, `7` over 1 is `7.00` (Nord Sound Manager prints `7.00` for a Stage 2 EX program).
     */
    fun formatVersion(version: Long, scale: Int): String {
        if (scale <= 1) return "$version.00"
        val digits = scale.toString().length - 1
        return "${version / scale}.${(version % scale).toString().padStart(digits, '0')}"
    }
}
