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

    /**
     * The instrument answered, and said no. [status] is its own code, verbatim.
     *
     * @param explanation what that code actually means, where the family knows. A bare "(status 4)"
     *   is the instrument's vocabulary, not the user's - it says something went wrong without
     *   saying what, or what to do about it - so a family that can name the condition passes the
     *   sentence here and it is shown instead. [status] is still carried for the log and for
     *   anything that needs to branch on it.
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

    /**
     * The instrument is attached but not listening, and **only the user can fix it** - at the
     * instrument's own front panel.
     *
     * The sibling of [BlockedByDeviceState], for the case where there is no remedy to offer: the
     * app cannot send the fix, because the reason it is stuck is that the instrument is not
     * receiving what the app sends. [steps] is the button sequence, in order, so the screen can
     * show it as a list rather than as one long sentence.
     *
     * The case it was written for: a Motif XS routes MIDI to exactly one of its ports - the DIN
     * sockets, USB, or mLAN - and set to any of the others it still enumerates as a USB device and
     * still opens, while answering nothing at all. Every operation then times out, one at a time,
     * and none of them can say why. Retrying is worth offering because the user is expected to go
     * and change something between the failure and the retry, which is exactly when a plain "did
     * not answer" is least useful.
     *
     * [alsoCheck] carries the other explanations for the same silence - a cable, another
     * application holding the device - because the app is inferring the cause from an absence and
     * must not claim more certainty than that.
     */
    class NeedsManualSetting(
        message: String,
        val steps: List<String>,
        val alsoCheck: String? = null,
    ) : InstrumentException(message)
}
