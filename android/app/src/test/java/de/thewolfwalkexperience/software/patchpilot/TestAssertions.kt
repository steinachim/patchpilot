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

/**
 * What caused this, nearest first. A test asks the chain rather than [Throwable.cause] alone:
 * with assertions on, as under Gradle, kotlinx.coroutines recovers stack traces by copying an
 * exception that crosses a `withContext` and setting the original as the copy's cause, so the
 * cause a family set can sit one link further down.
 */
fun Throwable.causes(): Sequence<Throwable> = generateSequence(cause) { it.cause }

/** Whether [why] is among the causes, other than another link of the same kind as this one. */
fun Throwable.causedBy(why: (Throwable) -> Boolean): Boolean =
    causes().any { it::class != this::class && why(it) }
