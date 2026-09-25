// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

/**
 * The instrument answered, but with something this app cannot make sense of: a reply out of
 * step with the request, a reply of the wrong shape, a walk that does not add up, or a
 * read-back that contradicts the write it verifies.
 *
 * Its own type so that `NordInstrument` can translate it to
 * [de.thewolfwalkexperience.software.patchpilot.core.InstrumentException.ProtocolDesync] rather
 * than to `TransportLost`, which is what a plain [IllegalStateException] from the transport
 * ("USB bulk write failed") maps to. The two differ in what a retry can do: a lost link may come
 * back, a protocol mismatch will not.
 *
 * Still an [IllegalStateException], so a caller that does not care about the distinction - the
 * device report's probes, the protocol tests - handles it as before.
 */
class NordProtocolException(detail: String, cause: Throwable? = null) :
    IllegalStateException(detail, cause)
