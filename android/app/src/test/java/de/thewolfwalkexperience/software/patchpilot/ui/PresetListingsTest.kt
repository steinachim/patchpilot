// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.InstrumentException
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stopping the listings at the end of a session: every listing's cleanup has run before
 * [PresetListings.cancelAndJoin] returns, as a Nord's category unlock has to before the
 * connection closes. And what an edit or a refresh costs the listings afterwards.
 */
class PresetListingsTest {

    /** Listings that never finish, and take [CLEANUP_MS] to clean up once cancelled. */
    private class SlowToStop {
        val started = mutableListOf<PresetScope>()
        val cleanedUp = mutableListOf<PresetScope>()
        private val demo = DemoInstrument()

        val instrument = object : Instrument by demo {
            override val browser = object : PresetBrowser by demo {
                override fun index(scope: PresetScope) = flow<IndexUpdate> {
                    started += scope
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

    /** As `onAppBackgrounded` does on pause, ahead of the resume's teardown. */
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

    /**
     * Edits wait for the user listing whichever tab is shown, so a pause that stopped it behind
     * another tab must not leave it unfinished until the user happens to open the User tab.
     */
    @Test
    fun `a user listing stopped behind another tab starts again with the session`() = runListings { listings, slow ->
        listings.start()
        runCurrent()
        listings.setScope(PresetScope.FAVORITES)
        runCurrent()
        listings.cancel()
        runCurrent()
        slow.started.clear()

        listings.start()
        runCurrent()

        assertEquals(setOf(PresetScope.FAVORITES, PresetScope.USER), slow.started.toSet())
    }

    /**
     * Demo mode's library, with one slot that cannot be read where [unreadable] says so; counts
     * every collection.
     */
    private class PartlyUnreadable(private val unreadable: Boolean = true) {
        val collected = mutableListOf<PresetScope>()
        val reRead = mutableListOf<SlotAddress>()
        var refreshFails = false
        private val demo = DemoInstrument()

        val instrument = object : Instrument by demo {
            override val browser = object : PresetBrowser by demo {
                override fun index(scope: PresetScope) = flow {
                    collected += scope
                    demo.index(scope).collect { update ->
                        if (unreadable &&
                            update == IndexUpdate.Complete
                        ) {
                            emit(IndexUpdate.Failed(UNREADABLE, "timed out"))
                        }
                        emit(update)
                    }
                }

                override suspend fun refresh(address: SlotAddress): PresetSlot {
                    reRead += address
                    if (refreshFails) error("timed out")
                    return demo.refresh(address)
                }
            }
        }
    }

    /** [test] against [PartlyUnreadable], with a device handle so the listing goes through the cache. */
    private fun runEdits(
        unreadable: Boolean = true,
        test: suspend TestScope.(PresetListings, PartlyUnreadable) -> Unit,
    ) = runTest {
        val session = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        val fake = PartlyUnreadable(unreadable)
        val listings =
            PresetListings(session, { fake.instrument }, { "usb:test" }, StringResolver { id, _ -> "string $id" })
        try {
            listings.start()
            advanceUntilIdle()
            test(listings, fake)
        } finally {
            session.cancel()
        }
    }

    /**
     * An edit patches the rows it touched and collects nothing again, even on a listing with an
     * unreadable slot: collected again, it would be re-read from the instrument after every rename.
     */
    @Test
    fun `an edit does not collect the listing again`() = runEdits { listings, fake ->
        val edited = listings.userIndex.value.slots.first().address
        listings.refreshEdited(edited)
        listings.reloadAfterEdit()
        advanceUntilIdle()
        assertEquals(listOf(PresetScope.USER), fake.collected)
    }

    @Test
    fun `a failed re-read after an edit collects the listing again`() = runEdits { listings, fake ->
        fake.refreshFails = true
        listings.refreshEdited(listings.userIndex.value.slots.first().address)
        listings.reloadAfterEdit()
        advanceUntilIdle()
        assertEquals(listOf(PresetScope.USER, PresetScope.USER), fake.collected)
    }

    /**
     * A listing read whole is cached, so a resume serves it without asking the instrument; a
     * re-read after an edit that fails drops it, so the next one asks again.
     */
    @Test
    fun `a failed re-read after an edit drops the cached listing`() = runEdits(unreadable = false) { listings, fake ->
        listings.cancel()
        listings.start()
        advanceUntilIdle()
        assertEquals("served from the cache", listOf(PresetScope.USER), fake.collected)

        fake.refreshFails = true
        listings.refreshEdited(listings.userIndex.value.slots.first().address)
        listings.cancel()
        listings.start()
        advanceUntilIdle()

        assertEquals(listOf(PresetScope.USER, PresetScope.USER), fake.collected)
    }

    /** A favorite set or cleared drops that listing, which the screen showing it needs back. */
    @Test
    fun `a favorites listing an edit dropped is collected again`() = runEdits { listings, fake ->
        listings.setScope(PresetScope.FAVORITES)
        advanceUntilIdle()
        listings.dropFavorites()
        listings.reloadAfterEdit()
        advanceUntilIdle()
        assertEquals(listOf(PresetScope.USER, PresetScope.FAVORITES, PresetScope.FAVORITES), fake.collected)
    }

    /** A failed edit may still have changed the instrument, so what it touched is read again. */
    @Test
    fun `an edit the instrument failed re-reads what it touched`() = runEdits { listings, fake ->
        val touched = listings.userIndex.value.slots.first().address
        val failure = InstrumentException.ProtocolDesync("now in both slots")
        val thrown = runCatching { listings.editAndRefresh(touched) { throw failure } }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals(listOf(touched), fake.reRead)
    }

    /** A refusal sent nothing, so there is nothing to re-read. */
    @Test
    fun `an edit refused before anything was sent re-reads nothing`() = runEdits { listings, fake ->
        val touched = listings.userIndex.value.slots.first().address
        for (refusal in listOf(
            InstrumentException.NotSupported("swap presets"),
            InstrumentException.BlockedByDeviceState("The instrument is busy."),
        )) {
            val thrown = runCatching { listings.editAndRefresh(touched) { throw refusal } }.exceptionOrNull()
            assertSame(refusal, thrown)
        }
        assertEquals(emptyList<SlotAddress>(), fake.reRead)
    }

    @Test
    fun `a successful edit re-reads every slot it touched`() = runEdits { listings, fake ->
        val (a, b) = listings.userIndex.value.slots.take(2).map { it.address }
        assertEquals("Swapped", listings.editAndRefresh(a, b) { "Swapped" })
        assertEquals(listOf(a, b), fake.reRead)
    }

    @Test
    fun `refreshing lasts as long as the refresh's listing`() = runEdits { listings, fake ->
        assertTrue(listings.refresh())
        assertTrue(listings.refreshing.value)
        assertFalse("a second pull waits for the first", listings.refresh())
        advanceUntilIdle()
        assertFalse(listings.refreshing.value)
        assertEquals(listOf(PresetScope.USER, PresetScope.USER), fake.collected)
    }

    /** As `onAppBackgrounded` does on pause: a spinner left running would block every later refresh. */
    @Test
    fun `a refresh cancelled by a pause stops refreshing`() = runListings { listings, _ ->
        listings.start()
        runCurrent()
        assertTrue(listings.refresh())
        listings.cancel()
        advanceUntilIdle()
        assertFalse(listings.refreshing.value)
        assertTrue("the next pull starts again", listings.refresh())
    }

    /** The reconnect dip: no instrument, so nothing to re-read and no spinner. */
    @Test
    fun `a refresh with no instrument starts nothing`() = runTest {
        val listings = PresetListings(backgroundScope, { null }, { null }, StringResolver { id, _ -> "string $id" })
        assertFalse(listings.refresh())
        assertFalse(listings.refreshing.value)
    }

    /**
     * An unplug drops the device's cached listings, and the user listing kept in memory behind
     * another tab with them: the panel may change before the replug, and copy destinations and
     * the edit gate must not go on reading rows nothing vouches for.
     */
    @Test
    fun `unplugging the session's device drops a user listing kept behind another tab`() = runEdits { listings, fake ->
        listings.setScope(PresetScope.FAVORITES)
        advanceUntilIdle()
        assertTrue(listings.userIndex.value.complete)

        listings.forgetDevice("usb:other")
        runCurrent()
        assertTrue("another device's unplug leaves it", listings.userIndex.value.complete)

        listings.forgetDevice("usb:test")
        runCurrent()
        assertFalse(listings.userIndex.value.complete)

        fake.collected.clear()
        listings.start()
        advanceUntilIdle()
        assertEquals(listOf(PresetScope.USER), fake.collected)
        assertTrue(listings.userIndex.value.complete)
    }

    /** The regression run plans its scratch slots around this: an unreadable slot is neither free nor taken. */
    @Test
    fun `occupancy keeps unreadable slots apart from occupied ones`() = runEdits { listings, _ ->
        val (occupied, unreadable) = listings.occupancy()
        assertEquals(setOf(UNREADABLE), unreadable)
        assertTrue(occupied.isNotEmpty())
        assertTrue(UNREADABLE !in occupied)
    }

    private companion object {
        const val CLEANUP_MS = 1_000L

        /** Outside the demo library, so it is unreadable and nothing else. */
        val UNREADABLE = SlotAddress(9, 0)
    }
}
