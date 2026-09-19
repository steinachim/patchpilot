// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * A filesystem- and share-safe stem for a device name: `"Nord Grand"` -> `"nord_grand"`.
 *
 * Lives in `core` rather than beside its first caller because two unrelated things now need it -
 * a Nord's own report filename, and [stemFor], which has to work for a family that has no
 * reporter at all.
 */
fun slugifyDeviceId(name: String): String {
    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "device" }
    return if (slug.first().isDigit()) "device_$slug" else slug
}

/**
 * A filename stem for anything shared about [instrument], **whether or not it can describe
 * itself**.
 *
 * A [DeviceReporter] carries its own preferred name and is used where present. Where it is null -
 * a Motif XS today - the instrument's own identity supplies one instead.
 *
 * The debug menu's regression test is offered for every family, including one whose `report`
 * facet is null, and a null facet means "cannot do this at all" - so a feature outside the facet
 * must not route through it for a filename either. This is the function that derives one
 * without the facet.
 */
fun stemFor(instrument: Instrument): String =
    instrument.report?.suggestedFilename() ?: slugifyDeviceId(instrument.identity.name)
