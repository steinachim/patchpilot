package de.thewolfwalkexperience.software.patchpilot.cache

import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * Which instrument's index is cached, and under what assumptions.
 *
 * More than [instrument] alone because two of the other three can change without the instrument
 * changing, and each would make a cached index wrong rather than merely stale:
 *
 * - [firmwareVersion] — a firmware change can move or reinterpret what is stored.
 * - [layoutFingerprint] — the bank table is catalog data (the JSON files under `devices/`) precisely so it
 *   corrected without a code change, and an index cached against the old table maps its entries
 *   onto the wrong addresses.
 *
 * There is deliberately **no decoder-version field**, which a persistent cache would need. This
 * cache lives and dies with the process, so the code that decoded its contents cannot change
 * while it is held. If this is ever written to disk that stops being true, and the field has to
 * come back — see `docs/ARCHITECTURE.md`, under "Caching".
 */
data class CacheKey(
    val instrument: String,
    val descriptorId: String,
    val firmwareVersion: String,
    val layoutFingerprint: String,
) {
    companion object {
        fun of(instrument: Instrument) = CacheKey(
            instrument = instrument.identity.stableKey,
            descriptorId = instrument.identity.descriptorId,
            firmwareVersion = instrument.identity.firmwareVersion,
            layoutFingerprint = instrument.layout.banks
                .joinToString("|") { "${it.label}:${it.slotCount}" },
        )
    }
}

/**
 * Completed preset indexes, held in memory for as long as the process lives.
 *
 * **The point is surviving a reconnect, not surviving a restart.** `MainActivity.onResume` calls
 * `forceReconnect()` on every return to the foreground, which tears the session down and rebuilds
 * it — so before this existed, a Motif XS user paid a **93-second** re-index every time they
 * switched to another app and came back, not merely once per launch. Owning the cache at the
 * ViewModel means it outlives the instrument object that reconnect throws away.
 *
 * Persistence to disk was **considered and declined** (2026-08-20): a full sync on the first
 * connection of a session is an acceptable cost, and persisting buys only the cold-launch case
 * while costing a physical-instrument identity this app cannot yet establish, a decoder-version
 * trap, and a staleness the UI would have to disclose. `docs/ARCHITECTURE.md`'s "Caching" section
 * records the reasoning so that revisiting it starts from the argument rather than from scratch.
 *
 * A single concrete class rather than an interface with one implementation: extracting a seam for
 * a second implementation that may never exist is speculative, and the API below is small enough
 * that a file-backed variant could be dropped behind it in an afternoon.
 */
class PresetIndexCache {

    /**
     * Address-keyed and insertion-ordered, so a replay comes back in the order the instrument
     * gave it and a write-through replaces a row **in place** rather than moving it to the end.
     */
    private val entries = mutableMapOf<CacheKey, LinkedHashMap<SlotAddress, PresetSlot>>()

    private val lock = Any()

    /** The cached index, or null if there is none. Null and empty mean different things: an
     * instrument with nothing stored has a legitimately empty *completed* index. */
    fun get(key: CacheKey): List<PresetSlot>? = synchronized(lock) {
        entries[key]?.values?.toList()
    }

    /**
     * Stores a **completed** index.
     *
     * Only ever called with the result of a run that reached [de.thewolfwalkexperience.software
     * .patchpilot.core.IndexUpdate.Complete] without a single failed address — see
     * [CachingBrowser]. A partial index must not be cached, because the addresses it is missing
     * are indistinguishable from addresses the instrument reports as empty.
     */
    fun put(key: CacheKey, slots: List<PresetSlot>) = synchronized(lock) {
        entries[key] = LinkedHashMap<SlotAddress, PresetSlot>(slots.size).apply {
            slots.forEach { put(it.address, it) }
        }
    }

    /**
     * Replaces one slot, if this instrument has a cached index at all.
     *
     * Deliberately does **not** create an entry when there is none: a cache holding one slot would
     * claim to be a completed index of one preset.
     */
    fun update(key: CacheKey, slot: PresetSlot) = synchronized(lock) {
        entries[key]?.let { it[slot.address] = slot }
        Unit
    }

    /** Forgets one instrument's index, so the next listing re-reads it from the hardware. */
    fun invalidate(key: CacheKey) = synchronized(lock) {
        entries.remove(key)
        Unit
    }

    /** Whether [key] has a cached index. Used by the tests; nothing in the UI distinguishes a
     * cached row from a freshly read one today. */
    fun holds(key: CacheKey): Boolean = synchronized(lock) { entries.containsKey(key) }
}
