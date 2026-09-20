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
 * The [CoroutineExceptionHandler] is the last-resort net: a `SupervisorJob` only stops siblings
 * being cancelled, and an uncaught throw in a child would otherwise reach the platform's default
 * handler and kill the process. The loops handle their own failures (see [UsbMidiBulkTransport]'s
 * reader), so anything arriving here is a bug worth the error log.
 *
 * [Dispatchers.IO] rather than the inherited [Dispatchers.Default]: a blocking read loop on the
 * shared default pool would, on an erroring endpoint, spin on a worker that Compose also needs.
 *
 * @param name identifies the instrument in a log line; a session can hold more than one scope.
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
