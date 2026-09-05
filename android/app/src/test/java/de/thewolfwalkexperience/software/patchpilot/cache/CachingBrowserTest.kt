package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.IndexUpdate
import de.thewolfwalkexperience.software.patchpilot.core.PresetBrowser
import de.thewolfwalkexperience.software.patchpilot.core.PresetScope
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingBrowserTest {

    private val key = CacheKey("inst-a", "desc", "1.00", "A:4|B:4")
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
     * A run with an unreadable address is not cached.
     *
     * Today a transient failure is retried on the next reconnect. Caching a run that contained one
     * would freeze it for the session - and a missing address is indistinguishable from an empty
     * slot, since `PresetSlot.isEmpty` is `name == null`.
     */
    @Test
    fun `a run with a failed address is not cached`() = runTest {
        val cache = PresetIndexCache()
        val delegate = CountingBrowser(
            slots = listOf(slot(0, 0, "One")),
            failures = listOf(SlotAddress(0, 1)),
        )
        val browser = CachingBrowser(delegate, cache, key)

        browser.index().toList()
        assertFalse(cache.holds(key))

        browser.index().toList()
        assertEquals("it must be retried, not served from a partial cache", 2, delegate.indexRuns)
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
        assertEquals("the order the instrument gave must survive a write-through",
            listOf(0, 1, 2), cached.map { it.address.slot })
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
}
