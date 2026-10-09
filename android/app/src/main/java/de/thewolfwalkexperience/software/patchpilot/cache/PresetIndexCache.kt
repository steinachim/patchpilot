// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.cache

import androidx.annotation.VisibleForTesting
import de.thewolfwalkexperience.software.patchpilot.core.Instrument
import de.thewolfwalkexperience.software.patchpilot.core.PresetSlot
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress

/**
 * Which instrument's index is cached, and under what assumptions. Each field can change without
 * the instrument changing, and each would make a cached index wrong rather than stale:
 *
 * - [firmwareVersion]: a firmware change can move or reinterpret what is stored.
 * - [layoutFingerprint]: the bank table is catalog data, and an index cached against an old
 *   table maps its entries onto the wrong addresses.
 * - [physicalDevice]: the USB device path or MIDI device id the session runs over. No family
 *   reads a serial number, so [instrument] names a model, and two units of the same model would
 *   otherwise share one cached listing. The path is stable across the resume rebuild this cache
 *   exists to survive, but it is not a reliable replug detector (Android hands the same port the
 *   same path again), so the listing is dropped on the USB detach broadcast; this field
 *   keeps two units apart when a detach is missed or the bus is MIDI.
 *
 * No decoder-version field: this cache dies with the process, so the code that decoded its
 * contents cannot change while it is held. A cache written to disk would need one - see
 * `docs/ARCHITECTURE.md`, "Caching".
 */
data class CacheKey(
    val instrument: String,
    val descriptorId: String,
    val firmwareVersion: String,
    val layoutFingerprint: String,
    val physicalDevice: String,
) {
    companion object {
        /** @param physicalDevice the session's device handle - see the class doc. */
        fun of(instrument: Instrument, physicalDevice: String) = CacheKey(
            instrument = instrument.identity.stableKey,
            descriptorId = instrument.identity.descriptorId,
            firmwareVersion = instrument.identity.firmwareVersion,
            layoutFingerprint = instrument.layout.banks
                .joinToString("|") { "${it.label}:${it.slotCount}" },
            physicalDevice = physicalDevice,
        )
    }
}

/**
 * Completed preset indexes, held in memory for as long as the process lives.
 *
 * For surviving a reconnect, not a restart: a resume on a USB session tears the session down and
 * rebuilds it, and without this a Motif XS would re-index its 416 user voices (about 93 s) every
 * time the user switched apps. Owned by `PresetListings`, so it outlives the instrument object the
 * reconnect throws away. Not persisted to disk - see `docs/ARCHITECTURE.md`, "Caching".
 */
class PresetIndexCache {

    /** Address-keyed and insertion-ordered, so a replay keeps device order and a write-through replaces a row in place. */
    private val entries = mutableMapOf<CacheKey, LinkedHashMap<SlotAddress, PresetSlot>>()

    /**
     * The addresses each index could not read, kept apart from its rows: a missing row would
     * otherwise read as an empty slot (`PresetSlot.isEmpty` is `name == null`).
     */
    private val unread = mutableMapOf<CacheKey, MutableSet<SlotAddress>>()

    private val lock = Any()

    /** Bumped by every write-through and invalidation, so a listing that ran across one can tell - see [putIfUnchanged]. */
    private val versions = mutableMapOf<CacheKey, Int>()

    /** Bumped by [invalidateDevice], and counted into every key's [version] for that device. */
    private val deviceVersions = mutableMapOf<String, Int>()

    /**
     * The cached index, or null if there is none. Null and empty mean different things: an
     * instrument with nothing stored has a legitimately empty *completed* index.
     */
    fun get(key: CacheKey): List<PresetSlot>? = synchronized(lock) {
        entries[key]?.values?.toList()
    }

    /** The addresses [key]'s cached index could not read, which a replay reads again - see [CachingBrowser]. */
    fun unreadable(key: CacheKey): Set<SlotAddress> = synchronized(lock) { unread[key].orEmpty().toSet() }

    /**
     * Stores a completed index - one that reached `IndexUpdate.Complete` - with the addresses it
     * could not read; see [CachingBrowser].
     */
    fun put(key: CacheKey, slots: List<PresetSlot>, unreadable: Set<SlotAddress> = emptySet()) = synchronized(lock) {
        entries[key] = LinkedHashMap<SlotAddress, PresetSlot>(slots.size).apply {
            slots.forEach { put(it.address, it) }
        }
        if (unreadable.isEmpty()) unread.remove(key) else unread[key] = unreadable.toMutableSet()
    }

    /** Where [key]'s writes stand; a listing reads it as it starts - see [putIfUnchanged]. */
    fun version(key: CacheKey): Int = synchronized(lock) { versionOf(key) }

    private fun versionOf(key: CacheKey): Int = (versions[key] ?: 0) + (deviceVersions[key.physicalDevice] ?: 0)

    /**
     * [put], unless [key] was written through or invalidated since [version]. An edit made while
     * the listing was collecting may have changed a row the listing had already read, which would
     * then replay stale from here. Skipping costs the next listing a re-read; the listing on screen
     * was already corrected by the edit's own re-read. Returns whether it stored.
     */
    fun putIfUnchanged(
        key: CacheKey,
        slots: List<PresetSlot>,
        version: Int,
        unreadable: Set<SlotAddress> = emptySet(),
    ): Boolean = synchronized(lock) {
        if (versionOf(key) != version) return false
        put(key, slots, unreadable)
        true
    }

    /**
     * Replaces one slot, if this instrument has a cached index at all; never creates an entry,
     * since a cache holding one slot would claim to be a completed index.
     *
     * An address the index does not hold yet is inserted in `(bank, slot)` order, not appended: a
     * Nord lists only the slots it holds, so a copy into an empty slot writes through an address
     * the listing never had, and appended it would replay under a second bank header.
     */
    fun update(key: CacheKey, slot: PresetSlot): Unit = synchronized(lock) {
        bump(key)
        val index = entries[key] ?: return
        // Read now, so no longer unknown.
        unread[key]?.remove(slot.address)
        if (slot.address in index) {
            index[slot.address] = slot
            return
        }
        val reordered = LinkedHashMap<SlotAddress, PresetSlot>(index.size + 1)
        var inserted = false
        for ((address, cached) in index) {
            if (!inserted && SlotAddress.DEVICE_ORDER.compare(slot.address, address) < 0) {
                reordered[slot.address] = slot
                inserted = true
            }
            reordered[address] = cached
        }
        if (!inserted) reordered[slot.address] = slot
        entries[key] = reordered
    }

    /** Forgets one instrument's index, so the next listing re-reads it from the hardware. */
    fun invalidate(key: CacheKey) = synchronized(lock) {
        bump(key)
        entries.remove(key)
        unread.remove(key)
        Unit
    }

    /**
     * Forgets every index cached for [physicalDevice], whichever instrument it was read as, and
     * fences off any listing of it still being collected.
     */
    fun invalidateDevice(physicalDevice: String) = synchronized(lock) {
        deviceVersions[physicalDevice] = (deviceVersions[physicalDevice] ?: 0) + 1
        entries.keys.removeAll { it.physicalDevice == physicalDevice }
        unread.keys.removeAll { it.physicalDevice == physicalDevice }
        Unit
    }

    private fun bump(key: CacheKey) {
        versions[key] = (versions[key] ?: 0) + 1
    }

    /** Whether [key] has a cached index. */
    @VisibleForTesting
    fun holds(key: CacheKey): Boolean = synchronized(lock) { entries.containsKey(key) }
}
