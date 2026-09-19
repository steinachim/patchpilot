// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.transport

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val TAG = "TransportScope"

/**
 * The scope a transport's reader loop and its exchange collector live in.
 *
 * A bare `CoroutineScope(SupervisorJob())` would be wrong in two ways that only show up when
 * something goes wrong on the bus.
 *
 * **A `SupervisorJob` does not swallow exceptions; it only stops siblings being cancelled.** An
 * uncaught throw in a child still reaches the context's [CoroutineExceptionHandler], and with none
 * installed it reaches the platform's default one, which kills the process - so an ordinary
 * endpoint error would be a crash. The handler below is the last-resort net for that; the loops
 * themselves are expected to handle their own failures (see [UsbMidiBulkTransport]'s reader), and
 * anything arriving here is a bug worth the error log.
 *
 * **A scope with no dispatcher inherits [Dispatchers.Default]**, a pool sized to the core count and
 * shared with the rest of the app's background work. A blocking read loop does not belong there:
 * when an endpoint errors, reads return immediately instead of waiting out their timeout, and the
 * loop spins on a worker that Compose also needs. [Dispatchers.IO] is elastic and is where a
 * blocking read belongs.
 *
 * @param name identifies the instrument in a stack trace or a log line, since a session can hold
 *   more than one of these at a time.
 */
fun transportScope(name: String): CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineName(name) +
        CoroutineExceptionHandler { context, error ->
            // Reaching here means a transport coroutine failed in a way it did not handle itself.
            // Logged rather than rethrown: the session is already unusable, and taking the process
            // down with it is what this handler exists to prevent.
            Log.e(TAG, "Unhandled failure in $name's transport (${context[CoroutineName]?.name})", error)
        },
)
