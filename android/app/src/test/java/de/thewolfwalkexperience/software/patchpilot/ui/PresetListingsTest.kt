// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.demo.DemoInstrument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stopping the listings at the end of a session: every listing's cleanup has run before
 * [PresetListings.cancelAndJoin] returns, as a Nord's category unlock has to before the
 * connection closes.
 */
class PresetListingsTest {

    /** Listings that never finish, and take [CLEANUP_MS] to clean up once cancelled. */
    private class SlowToStop {
        val cleanedUp = mutableListOf<PresetScope>()
        private val demo = DemoInstrument()

        val instrument = object : Instrument by demo {
            override val browser = object : PresetBrowser by demo {
                override fun index(scope: PresetScope) = flow<IndexUpdate> {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(CLEANUP_MS)
                            cleanedUp += scope
                        }
                    }
                }
            }
        }
    }

    /** [test] with a scope for the listings, cancelled when [test] ends; see `ConnectionCoordinatorTest.runSession`. */
    private fun runListings(test: suspend TestScope.(PresetListings, SlowToStop) -> Unit) = runTest {
        val session = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val slow = SlowToStop()
        val listings = PresetListings(session, { slow.instrument }, { null }, StringResolver { id, _ -> "string $id" })
        try {
            test(listings, slow)
        } finally {
            session.cancel()
        }
    }

    @Test
    fun `cancelAndJoin returns once the listing has cleaned up`() = runListings { listings, slow ->
        listings.start()
        runCurrent()
        listings.cancelAndJoin()
        assertEquals(listOf(PresetScope.USER), slow.cleanedUp)
    }

    /** As `cancelScanOnBackground` does on pause, ahead of the resume's teardown. */
    @Test
    fun `a listing cancelled earlier is still waited for`() = runListings { listings, slow ->
        listings.start()
        runCurrent()
        listings.cancel()
        listings.cancelAndJoin()
        assertEquals(listOf(PresetScope.USER), slow.cleanedUp)
    }

    @Test
    fun `a listing started during the wait is stopped too`() = runListings { listings, slow ->
        listings.start()
        runCurrent()
        launch {
            delay(CLEANUP_MS / 2)
            listings.setScope(PresetScope.FAVORITES)
        }
        listings.cancelAndJoin()
        assertEquals(listOf(PresetScope.USER, PresetScope.FAVORITES), slow.cleanedUp)
    }

    private companion object {
        const val CLEANUP_MS = 1_000L
    }
}
