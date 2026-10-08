// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot

/**
 * Runs [block] and returns what it threw, failing unless that is a [T]. Inline, so [block] may
 * suspend inside `runTest`, where a `runBlocking` would park the thread the test scheduler needs.
 * Anything else thrown is passed on rather than counted as the failure: a ReplayTransport script
 * out of step throws `AssertionError`, which has to surface as itself.
 */
inline fun <reified T : Throwable> thrownBy(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Expected ${T::class.java.simpleName}, but nothing was thrown")
}
