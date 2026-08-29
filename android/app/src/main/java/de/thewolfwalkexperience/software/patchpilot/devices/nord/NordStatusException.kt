package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The instrument answered a write sub-opcode with a non-zero status, or echoed back a slot other
 * than the one asked for.
 *
 * **Its own type so the status survives the trip to the adapter.** `NordInstrument` turns this into
 * [de.thewolfwalkexperience.software.patchpilot.core.InstrumentException.DeviceRejected], which
 * carries the instrument's own code verbatim - and the only alternative was recovering that number
 * from the message text with a regex, which is precisely the string-matching `InstrumentException`
 * was written to remove.
 *
 * Still an [IllegalStateException], and still declared here rather than in `core/`: it is specific
 * to this wire protocol's status codes, not a general instrument-level failure.
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
