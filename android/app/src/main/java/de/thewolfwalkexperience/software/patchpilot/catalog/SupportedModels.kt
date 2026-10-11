// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.catalog

/**
 * The supported-instrument list, one line per model: siblings of one family that differ only in
 * their [InstrumentDescriptor.variant] share a line, the first in full and the rest by variant -
 * "Yamaha Motif XS6 / 7 / 8". A descriptor without a variant, or whose name does not end with it,
 * is a line of its own under its full name, so a catalog mistake costs the grouping and nothing
 * else.
 */
fun supportedModelLines(descriptors: List<InstrumentDescriptor>): List<String> {
    val (grouped, single) = descriptors.partition { it.base() != null }
    val groupLines = grouped
        .groupBy { it.family to it.base() }
        .values
        .map { members ->
            val sorted = members.sortedBy { it.name }
            (listOf(sorted.first().name) + sorted.drop(1).map { it.variant!! }).joinToString(" / ")
        }
    return (groupLines + single.map { it.name }).sorted()
}

/** [InstrumentDescriptor.name] without its variant, or null if it has none or does not end with it. */
private fun InstrumentDescriptor.base(): String? {
    val variant = variant?.takeIf { it.isNotBlank() } ?: return null
    if (!name.endsWith(variant)) return null
    return name.removeSuffix(variant).trimEnd().takeIf { it.isNotEmpty() }
}
