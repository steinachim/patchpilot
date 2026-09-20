// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

/**
 * What went wrong, in terms the UI can act on without matching on message strings, which would
 * stop working the moment a second family worded its failures differently.
 *
 * The distinction that matters to a screen is "would retrying help?": [Timeout] and
 * [TransportLost] may, [NotSupported] and [DeviceRejected] will not.
 */
sealed class InstrumentException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** The instrument, or this app's support for it, has no such operation. */
    class NotSupported(what: String) : InstrumentException("This instrument cannot $what.")

    /** No reply arrived in time. */
    class Timeout(what: String, cause: Throwable? = null) :
        InstrumentException("The instrument did not answer while $what.", cause)

    /**
     * The instrument answered, and said no. [status] is its own code, verbatim.
     *
     * @param explanation what that code means, where the family knows: shown instead of the
     *   bare "(status 4)", which says nothing about what to do. [status] is still carried for the
     *   log.
     */
    class DeviceRejected(what: String, val status: Int, val explanation: String? = null) :
        InstrumentException(
            explanation ?: "The instrument refused to $what (status $status).",
        )

    /** A reply arrived that this app cannot make sense of - a bug, or an unprofiled device. */
    class ProtocolDesync(detail: String, cause: Throwable? = null) :
        InstrumentException(detail, cause)

    /** The connection is gone; the session has to be rebuilt. */
    class TransportLost(detail: String, cause: Throwable? = null) :
        InstrumentException(detail, cause)

    /**
     * The instrument's own state blocks this operation, and the app may be able to change it.
     *
     * Distinct from [NotSupported] ("never, on this device"): this is "not right now, and here is
     * what would fix it", so the screen can offer the fix. The case it covers: a Motif XS
     * silently ignores a voice selection unless it is in Voice mode.
     *
     * [remedy] changes the instrument, so it is never run without asking: switching a player out
     * of the Performance they are using is a visible change to their setup. [remedyDetail] is
     * what the dialog shows and [remedyLabel] its button. With [remedy] null this behaves like
     * any other failure.
     */
    class BlockedByDeviceState(
        message: String,
        val remedyLabel: String? = null,
        val remedyDetail: String? = null,
        val remedy: (suspend () -> Unit)? = null,
    ) : InstrumentException(message)

    /**
     * The instrument is attached but not listening, and only the user can fix it, at the
     * instrument's own front panel.
     *
     * The sibling of [BlockedByDeviceState] for the case where the app cannot send the fix,
     * because the instrument is not receiving what the app sends. [steps] is the button sequence
     * the screen shows as a list. The case it covers: a Motif XS routes MIDI to exactly one of
     * its ports (DIN, USB or mLAN) and, set to any but USB, still enumerates and opens while
     * answering nothing.
     *
     * [alsoCheck] carries the other explanations for the same silence - a cable, another
     * application holding the device - since the cause is inferred from an absence.
     */
    class NeedsManualSetting(
        message: String,
        val steps: List<String>,
        val alsoCheck: String? = null,
    ) : InstrumentException(message)
}
