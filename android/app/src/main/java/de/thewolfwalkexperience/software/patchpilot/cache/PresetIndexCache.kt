// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.cache

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
 *   same path again), so the ViewModel drops the listing on the USB detach broadcast; this field
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
 * time the user switched apps. Owned by the ViewModel, so it outlives the instrument object the
 * reconnect throws away. Not persisted to disk - see `docs/ARCHITECTURE.md`, "Caching".
 */
class PresetIndexCache {

    /** Address-keyed and insertion-ordered, so a replay keeps device order and a write-through replaces a row in place. */
    private val entries = mutableMapOf<CacheKey, LinkedHashMap<SlotAddress, PresetSlot>>()

    private val lock = Any()

    /** The cached index, or null if there is none. Null and empty mean different things: an
     * instrument with nothing stored has a legitimately empty *completed* index. */
    fun get(key: CacheKey): List<PresetSlot>? = synchronized(lock) {
        entries[key]?.values?.toList()
    }

    /** Stores a completed index - one that reached `IndexUpdate.Complete` without a failed address; see [CachingBrowser]. */
    fun put(key: CacheKey, slots: List<PresetSlot>) = synchronized(lock) {
        entries[key] = LinkedHashMap<SlotAddress, PresetSlot>(slots.size).apply {
            slots.forEach { put(it.address, it) }
        }
    }

    /**
     * Replaces one slot, if this instrument has a cached index at all; never creates an entry,
     * since a cache holding one slot would claim to be a completed index.
     *
     * An address the index does not hold yet is inserted in `(bank, slot)` order, not appended: a
     * Nord lists only the slots it holds, so a copy into an empty slot writes through an address
     * the listing never had, and appended it would replay under a second bank header.
     */
    fun update(key: CacheKey, slot: PresetSlot) = synchronized(lock) {
        val index = entries[key] ?: return
        if (slot.address in index) {
            index[slot.address] = slot
            return
        }
        val reordered = LinkedHashMap<SlotAddress, PresetSlot>(index.size + 1)
        var inserted = false
        for ((address, cached) in index) {
            if (!inserted && ADDRESS_ORDER.compare(slot.address, address) < 0) {
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
        entries.remove(key)
        Unit
    }

    /** Whether [key] has a cached index. Used by the tests. */
    fun holds(key: CacheKey): Boolean = synchronized(lock) { entries.containsKey(key) }

    private companion object {
        /** Device order on every family: bank first, then slot within it. */
        val ADDRESS_ORDER: Comparator<SlotAddress> = compareBy({ it.bank }, { it.slot })
    }
}
