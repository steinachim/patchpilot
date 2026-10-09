// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingBrowserTest {

    private val key = CacheKey("inst-a", "desc", "1.00", "A:4|B:4", "usb:/dev/bus/usb/001/004")
    private val otherInstrument = key.copy(instrument = "inst-b")

    private fun slot(bank: Int, index: Int, name: String?) = PresetSlot(
        address = SlotAddress(bank, index),
        displayId = "$bank:$index",
        bankLabel = "Bank $bank",
        name = name,
    )

    /** Records how often it was asked to read, which is the only thing these tests are about. */
    private class CountingBrowser(
        private val slots: List<PresetSlot>,
        private val failures: List<SlotAddress> = emptyList(),
        private val complete: Boolean = true,
    ) : PresetBrowser {
        var indexRuns = 0
        var refreshes = mutableListOf<SlotAddress>()
        var refreshResult: (SlotAddress) -> PresetSlot = { addr ->
            PresetSlot(addr, "${addr.bank}:${addr.slot}", "Bank ${addr.bank}", "refreshed")
        }

        override val scopes = listOf(PresetScope.USER, PresetScope.FAVORITES)

        override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
            indexRuns++
            emit(IndexUpdate.Slots(slots))
            failures.forEach { emit(IndexUpdate.Failed(it, "unreadable")) }
            if (complete) emit(IndexUpdate.Complete)
        }

        override suspend fun refresh(address: SlotAddress): PresetSlot {
            refreshes += address
            return refreshResult(address)
        }
    }

    @Test
    fun `a cold start reads the instrument and a warm one does not`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(listOf(slot(0, 0, "One"), slot(0, 1, null)))
        val browser = CachingBrowser(delegate, cache, key)

        val first = browser.index().toList()
        assertEquals(1, delegate.indexRuns)
        assertTrue(first.last() is IndexUpdate.Complete)

        val second = browser.index().toList()
        assertEquals("the second listing must not touch the instrument", 1, delegate.indexRuns)
        assertEquals(
            first.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots },
            second.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots },
        )
        assertTrue(second.last() is IndexUpdate.Complete)
    }

    /**
     * A run with an unreadable address is cached with that address, and a replay reads only it
     * again: one slot that will not read would otherwise cost every reconnect the whole listing.
     */
    @Test
    fun `a run with a failed address is cached, and a replay re-reads only that address`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(
            slots = listOf(slot(0, 0, "One")),
            failures = listOf(SlotAddress(0, 1)),
        )
        val browser = CachingBrowser(delegate, cache, key)
        browser.index().toList()
        assertEquals(setOf(SlotAddress(0, 1)), cache.unreadable(key))

        val replay = browser.index().toList()

        assertEquals("the listing is not read again", 1, delegate.indexRuns)
        assertEquals(listOf(SlotAddress(0, 1)), delegate.refreshes)
        assertEquals(
            listOf("One", "refreshed"),
            replay.filterIsInstance<IndexUpdate.Slots>().flatMap { it.slots }.map { it.name },
        )
        assertEquals("read now, so no longer unknown", emptySet<SlotAddress>(), cache.unreadable(key))
    }

    /** Never left out: a missing row would read as an empty slot, free to copy into. */
    @Test
    fun `an address still unreadable on a replay is reported as such`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(slots = listOf(slot(0, 0, "One")), failures = listOf(SlotAddress(0, 1)))
            .apply { refreshResult = { error("timed out") } }
        val browser = CachingBrowser(delegate, cache, key)
        browser.index().toList()

        val replay = browser.index().toList()

        assertEquals(listOf(SlotAddress(0, 1)), replay.filterIsInstance<IndexUpdate.Failed>().map { it.address })
        assertTrue(replay.last() is IndexUpdate.Complete)
        assertEquals(setOf(SlotAddress(0, 1)), cache.unreadable(key))
    }

    /** An index that never reached Complete is a partial one, whatever it emitted. */
    @Test
    fun `an incomplete run is not cached`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(listOf(slot(0, 0, "One")), complete = false)
        CachingBrowser(delegate, cache, key).index().toList()
        assertFalse(cache.holds(key))
    }

    @Test
    fun `refresh writes through and keeps the row in place`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(
            listOf(slot(0, 0, "One"), slot(0, 1, "Two"), slot(0, 2, "Three")),
        )
        val browser = CachingBrowser(delegate, cache, key)
        browser.index().toList()

        browser.refresh(SlotAddress(0, 1))

        val cached = cache.get(key)!!
        assertEquals(listOf("One", "refreshed", "Three"), cached.map { it.name })
        assertEquals(
            "the order the instrument gave must survive a write-through",
            listOf(0, 1, 2),
            cached.map { it.address.slot },
        )
    }

    /**
     * The bug this would catch is a move that refreshes only its destination, leaving the source
     * showing the voice it no longer holds - indistinguishable, to the user, from a failed write.
     */
    @Test
    fun `a two-slot edit must write through both addresses`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(listOf(slot(0, 0, "Moved"), slot(0, 1, null)))
        val browser = CachingBrowser(delegate, cache, key)
        browser.index().toList()

        delegate.refreshResult = { addr ->
            if (addr.slot == 0) slot(0, 0, null) else slot(0, 1, "Moved")
        }
        browser.refresh(SlotAddress(0, 0))
        browser.refresh(SlotAddress(0, 1))

        assertEquals(listOf(null, "Moved"), cache.get(key)!!.map { it.name })
    }

    /**
     * A Nord lists only the slots it holds, so a copy or a move into an empty slot refreshes an
     * address the cached listing never had. It has to land in device order: appended, the browser
     * replays it after the last bank and draws its bank header twice.
     */
    @Test
    fun `an address the listing did not hold is inserted in device order`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(
            listOf(slot(0, 0, "A first"), slot(0, 3, "A last"), slot(1, 0, "B first")),
        )
        val browser = CachingBrowser(delegate, cache, key)
        browser.index().toList()

        delegate.refreshResult = { addr -> slot(addr.bank, addr.slot, "copied") }
        browser.refresh(SlotAddress(0, 2))
        browser.refresh(SlotAddress(1, 5))

        assertEquals(
            listOf(0 to 0, 0 to 2, 0 to 3, 1 to 0, 1 to 5),
            cache.get(key)!!.map { it.address.bank to it.address.slot },
        )
    }

    @Test
    fun `invalidating forces the next listing back to the instrument`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(listOf(slot(0, 0, "One")))
        val browser = CachingBrowser(delegate, cache, key)

        browser.index().toList()
        cache.invalidate(key)
        browser.index().toList()

        assertEquals(2, delegate.indexRuns)
    }

    /** An unplug drops what was cached for the device's port, whichever model it was read as. */
    @Test
    fun `invalidating a device drops its keys and fences a listing in flight`() {
        val cache = PresetIndexCache()
        val otherModel = key.copy(instrument = "inst-b", descriptorId = "desc-b")
        val otherPort = key.copy(physicalDevice = "usb:/dev/bus/usb/001/005")
        listOf(key, otherModel, otherPort).forEach { cache.put(it, listOf(slot(0, 0, "Pad"))) }
        val inFlight = cache.version(key)

        cache.invalidateDevice(key.physicalDevice)

        assertNull(cache.get(key))
        assertNull(cache.get(otherModel))
        assertEquals(1, cache.get(otherPort)?.size)
        assertFalse(
            "a listing started before the unplug must not be stored",
            cache.putIfUnchanged(key, emptyList(), inFlight),
        )
    }

    /**
     * Two instruments must not see each other's presets.
     *
     * `InstrumentIdentity.stableKey` is model-level on every family today, so two units of one
     * model would collide - this asserts the cache respects the key it is given, which is the half
     * of that problem the cache owns.
     */
    @Test
    fun `two keys do not share slots`() = runTest {
        val cache = PresetIndexCache()
        cache.put(key, listOf(slot(0, 0, "Mine")))

        assertNull(cache.get(otherInstrument))
        assertEquals(listOf("Mine"), cache.get(key)!!.map { it.name })
    }

    /** A firmware or bank-table change makes a cached index wrong, not merely old. */
    @Test
    fun `a layout or firmware change discards the cache`() = runTest {
        val cache = PresetIndexCache()
        cache.put(key, listOf(slot(0, 0, "One")))

        assertNull(cache.get(key.copy(layoutFingerprint = "A:8|B:8")))
        assertNull(cache.get(key.copy(firmwareVersion = "2.00")))
    }

    /** Updating an instrument with no cached index must not invent a one-preset index. */
    @Test
    fun `a write-through with no cached index stores nothing`() {
        val cache = PresetIndexCache()
        cache.update(key, slot(0, 0, "One"))
        assertFalse(cache.holds(key))
        assertNull(cache.get(key))
    }

    /**
     * An edit written through while the listing is still collecting keeps that run out of the
     * cache: the listing may already hold the row from before the edit, and replayed from here
     * after the next resume, the edit would look undone.
     */
    @Test
    fun `a listing an edit overlapped is not cached`() = runTest {
        val cache = PresetIndexCache()
        val midway = CompletableDeferred<Unit>()
        val delegate = object : PresetBrowser {
            override fun index(scope: PresetScope): Flow<IndexUpdate> = flow {
                emit(IndexUpdate.Slots(listOf(slot(0, 0, "Before"))))
                midway.await()
                emit(IndexUpdate.Complete)
            }

            override suspend fun refresh(address: SlotAddress) = slot(address.bank, address.slot, "After")
        }
        val browser = CachingBrowser(delegate, cache, key)

        val listing = async { browser.index().toList() }
        runCurrent()
        browser.refresh(SlotAddress(0, 0))
        midway.complete(Unit)
        listing.await()

        assertFalse(cache.holds(key))
    }
}
