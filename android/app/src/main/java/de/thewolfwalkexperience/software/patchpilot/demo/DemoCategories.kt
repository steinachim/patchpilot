// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.demo

import de.thewolfwalkexperience.software.patchpilot.core.CategoryRef
import de.thewolfwalkexperience.software.patchpilot.core.CategoryTaxonomy
import de.thewolfwalkexperience.software.patchpilot.core.MainCategory

/**
 * The demo instrument's made-up category list, and which category each of its presets starts in.
 *
 * **Shaped like a Nord's, because that is what [DemoInstrument] mirrors**: one category per preset,
 * no sub-categories, and `None` is a real category rather than the absence of one - so demo mode
 * exercises the same `allowsUnassigned = false` editor path a Nord does, rather than a third shape
 * no instrument has.
 *
 * Invented, like every other name in demo mode. Nothing here describes real hardware, and the
 * ordering matches what a Nord shows - alphabetical, with `None` last - so the demo and the real
 * thing do not look gratuitously different side by side.
 */
internal object DemoCategories {

    /** The category a preset with no assignment shows. A real entry, not a null. */
    const val NONE = "None"

    /** Alphabetical, `None` last - the order a Nord's own editor lists them in. */
    val NAMES: List<String> = listOf(
        "Bass", "Brass", "Clavinet", "EPiano1", "EPiano2", "Grand", "Guitar", "Harpsichord",
        "Lead", "Mallet", "Organ", "Pad", "Strings", "Upright", "Vocal", NONE,
    )

    val taxonomy = CategoryTaxonomy(NAMES.map { MainCategory(it, emptyList()) })

    /**
     * What each seeded preset is filed under, by preset name.
     *
     * A couple sit in [NONE] on purpose, so the listing shows that state rather than implying
     * every preset always has a category.
     */
    val BY_PRESET: Map<String, String> = mapOf(
        "Concert Grand" to "Grand",
        "Studio Upright" to "Upright",
        "Rhodes Mk I" to "EPiano1",
        "Wurli 200A" to "EPiano2",
        "Clavinet D6" to "Clavinet",
        "Harpsichord" to "Harpsichord",
        "Church Organ" to "Organ",
        "Jazz Organ" to "Organ",
        "String Pad" to "Pad",
        "Warm Brass" to "Brass",
        "Nylon Guitar" to "Guitar",
        "Fretless Bass" to "Bass",
        "Choir Aah" to "Vocal",
        "Vibraphone" to "Mallet",
        "Marimba" to "Mallet",
        "Bright Piano" to "Grand",
        "Dark Piano" to "Grand",
        "Tine EP" to "EPiano1",
        "Reed EP" to "EPiano2",
        "Toy Piano" to NONE,
        "Celesta" to "Mallet",
        "Glockenspiel" to "Mallet",
        "Mellotron Flute" to "Pad",
        "Pipe Organ" to "Organ",
        "Accordion" to NONE,
        "Analog Strings" to "Strings",
        "Poly Brass" to "Brass",
        "Solo Lead" to "Lead",
        "Sub Bass" to "Bass",
        "Bell Pad" to "Pad",
    )

    /** The taxonomy index for a category name, or null where this demo does not list it. */
    fun refOf(name: String?): CategoryRef? =
        name?.let { NAMES.indexOf(it).takeIf { i -> i >= 0 } }?.let { CategoryRef(it, null) }

    /** The category name at a taxonomy index, or null where the index names none. */
    fun nameOf(ref: CategoryRef): String? = NAMES.getOrNull(ref.main)
}
