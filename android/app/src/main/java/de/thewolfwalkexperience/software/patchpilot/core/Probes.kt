// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.core

import android.util.Log
import kotlinx.coroutines.CancellationException

private const val TAG = "Probes"

/**
 * Collects the results of a device report's queries, so that no single query is fatal.
 *
 * A report describes an instrument nobody has profiled yet, which is where a query may be
 * unsupported, time out, or answer something this app cannot parse. A partial report is worth
 * more than none, and a recorded failure is itself a finding about the instrument.
 */
class Probes {
    private val _failures = LinkedHashMap<String, String>()

    /** What went wrong, keyed by what was being attempted. Part of the report. */
    val failures: Map<String, String> get() = _failures

    /**
     * Runs one query, recording a failure under [what] and carrying on with [fallback].
     * Not `runCatching`, which would swallow [CancellationException].
     */
    suspend fun <T> probe(what: String, fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Device report: '$what' failed", e)
        _failures[what] = e.message ?: e.toString()
        fallback
    }

    /** Records a failure found outside a [probe] call, e.g. by a callback. */
    fun record(what: String, reason: String) {
        _failures[what] = reason
    }
}

/** Lowercase hex, the form every raw field in a device report uses. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
