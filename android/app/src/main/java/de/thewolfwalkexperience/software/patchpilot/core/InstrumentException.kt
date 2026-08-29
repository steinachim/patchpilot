package de.thewolfwalkexperience.software.patchpilot.core

/**
 * What went wrong, in terms the UI can act on without matching on message strings - which is what
 * it does today, and which stops working the moment a second family words its failures
 * differently.
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

    /** The instrument answered, and said no. [status] is its own code, verbatim. */
    class DeviceRejected(what: String, val status: Int) :
        InstrumentException("The instrument refused to $what (status $status).")

    /** A reply arrived that this app cannot make sense of - a bug, or an unprofiled device. */
    class ProtocolDesync(detail: String, cause: Throwable? = null) :
        InstrumentException(detail, cause)

    /** The connection is gone; the session has to be rebuilt. */
    class TransportLost(detail: String, cause: Throwable? = null) :
        InstrumentException(detail, cause)

    /**
     * The **instrument's own state** blocks this operation, and the app may be able to change it.
     *
     * Distinct from [NotSupported], which means "never, on this device". This means "not right
     * now, and here is what would fix it" - so the screen can offer the fix instead of only
     * reporting the obstacle.
     *
     * The case it was written for: a Motif XS ignores a voice selection unless it is in Voice
     * mode, silently. Refusing outright was already better than the timeout it replaced, but the
     * remedy is one documented message, and making the user walk to the instrument for something
     * the app can do is a poor trade.
     *
     * **[remedy] changes the instrument, so it is never run without asking.** Switching a player
     * out of the Performance or Song they are using is a visible, stateful change to their setup;
     * [remedyDetail] is what the dialog shows so they can decide, and [remedyLabel] is the button.
     * A family that has no fix leaves [remedy] null and this behaves like any other failure.
     */
    class BlockedByDeviceState(
        message: String,
        val remedyLabel: String? = null,
        val remedyDetail: String? = null,
        val remedy: (suspend () -> Unit)? = null,
    ) : InstrumentException(message)
}
