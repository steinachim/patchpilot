// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import kotlinx.serialization.json.Json

/*
 * Wording and settings every device family uses the same way, kept in one place so the families
 * cannot drift apart.
 */

/** What a row shows for a preset that is stored but carries no name. */
const val UNNAMED_PRESET = "(unnamed)"

/** What [InstrumentIdentity.firmwareVersion] holds where the instrument did not say. */
const val UNKNOWN_FIRMWARE = "unknown"

/**
 * A device report's Json. encodeDefaults, so a report spells out every field, defaults included:
 * a Nord report's catalog entry has to show every field its recipient fills in.
 */
val REPORT_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * The sentence an [Instrument.advisory] opens with for firmware this app has not been tested
 * against. [tested] is shown in the order given.
 */
fun untestedFirmwareWarning(name: String, firmware: String, tested: List<String>): String =
    "$name reports firmware version $firmware, which this app has not been tested against " +
        "(tested: ${tested.joinToString(", ")})."

/** How every untested-firmware or untested-model advisory ends: a warning, not a refusal. */
const val UNTESTED_ADVISORY_CLOSING =
    "The app might still work, but it is not guaranteed that presets will be read or written " +
        "correctly. Please consider sending in a device report, so support can be confirmed in " +
        "future."

/** The whole [Instrument.advisory] for an untested firmware - see [untestedFirmwareWarning]. */
fun untestedFirmwareAdvisory(name: String, firmware: String, tested: List<String>): String =
    "${untestedFirmwareWarning(name, firmware, tested)} $UNTESTED_ADVISORY_CLOSING"
