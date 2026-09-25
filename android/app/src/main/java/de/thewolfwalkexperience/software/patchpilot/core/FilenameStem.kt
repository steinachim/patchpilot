// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/** A filesystem- and share-safe stem for a device name: `"Nord Grand"` -> `"nord_grand"`. */
fun slugifyDeviceId(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "device" }
    return if (slug.first().isDigit()) "device_$slug" else slug
}

/**
 * A filename stem for anything shared about [instrument], whether or not it has a
 * [DeviceReporter]: the reporter's own name where present, otherwise one derived from the
 * identity. The regression test is offered for every family, including one whose `report` facet
 * is null.
 */
fun stemFor(instrument: Instrument): String =
    instrument.report?.suggestedFilename() ?: slugifyDeviceId(instrument.identity.name)
