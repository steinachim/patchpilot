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
 * **This exists because reaching for the reporter's name from outside the reporter crashed the
 * app.** The debug menu's regression test is offered for every family and shared its results
 * under `"${'$'}{suggestedReportFilename()}_capabilities"`, which requires the facet; on a Motif XS
 * that threw the moment Share was tapped. A null facet means "cannot do this at all", so a
 * feature outside the facet must not route through it - and this is the function that makes
 * doing the right thing easier than repeating the mistake.
 */
fun stemFor(instrument: Instrument): String =
    instrument.report?.suggestedFilename() ?: slugifyDeviceId(instrument.identity.name)
