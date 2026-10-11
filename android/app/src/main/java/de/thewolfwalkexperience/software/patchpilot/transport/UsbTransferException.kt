// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

/**
 * A USB transfer that failed: the device is gone, the connection is closed, or an endpoint has
 * stopped answering. Its own type so a protocol layer can tell a lost link, which a reconnect may
 * fix, from a bug or a reply it cannot make sense of. An [IllegalStateException] all the same, so
 * a caller that does not care treats it as any other failure.
 */
open class UsbTransferException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A read that waited out its whole timeout: the device was silent, which a retry may cure. */
class UsbReadTimeoutException(message: String) : UsbTransferException(message)
