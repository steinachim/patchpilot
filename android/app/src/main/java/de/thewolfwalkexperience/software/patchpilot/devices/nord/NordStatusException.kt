// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The instrument answered a write sub-opcode with a non-zero status, or echoed back a slot other
 * than the one asked for. Its own type so the status reaches `NordInstrument`, which turns it
 * into [de.thewolfwalkexperience.software.patchpilot.core.InstrumentException.DeviceRejected]
 * with the code verbatim.
 *
 * @param what the operation, in the wire's own words - `"delete-item"`, `"copy-program"`.
 * @param status the instrument's code. `4` means the destination already holds an item; the rest
 *   have no known meaning.
 */
class NordStatusException(
    val what: String,
    val status: Int,
    detail: String,
) : IllegalStateException(detail)
