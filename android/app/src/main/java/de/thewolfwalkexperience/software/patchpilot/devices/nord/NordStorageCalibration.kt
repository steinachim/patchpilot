// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.devices.nord

import android.util.Log
import de.thewolfwalkexperience.software.patchpilot.core.GroupedBankAddressFormat
import de.thewolfwalkexperience.software.patchpilot.core.SlotAddress
import de.thewolfwalkexperience.software.patchpilot.core.printableAscii
import de.thewolfwalkexperience.software.patchpilot.core.untestedFirmwareAdvisory
import de.thewolfwalkexperience.software.patchpilot.devices.nord.NordDevice.CategorySpace
import de.thewolfwalkexperience.software.patchpilot.transport.UsbBulkTransport
import de.thewolfwalkexperience.software.patchpilot.transport.UsbTransferException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val TAG = "NordStorageCalibration"

/** One storage area and everything measured about it - see [calibrateStorageUnits]. */
data class StorageArea(
    val names: List<String>,
    val indexes: List<Int>,
    /** The shared storage figures - identical across [names], which is what groups them. */
    val space: CategorySpace,
    /** Each category's own item count, aligned to [names]. These *do* differ within an area. */
    val itemCounts: List<Int>,
    val recordCount: Int,
    val totalBytes: Long,
    val unitBytes: Int?,
    val residual: Int?,
    val candidateRange: Pair<Int, Int>?,
    val reason: String,
    /**
     * The same overhead measured at the unit the instrument reports rather than at the fitted
     * one (the roundest value in the admissible range): a Grand's piano area leaves 0 at the
     * reported 130,816 and 33 at the fitted 131,072.
     */
    val reportedResidual: Int? = null,
) {
    val solved: Boolean get() = unitBytes != null && !space.countedInBytes
}

/**
 * Every storage area's allocation unit, fitted from the instrument's own item records as a
 * read-only cross-check on the unit the root category list states: every record carries its
 * blob's byte length, so the unit is the U for which `sum(ceil(size / U))` lands on the
 * area's `used`. A fit only narrows U to a range; the test is whether the reported figure
 * falls inside it.
 *
 * Categories reporting an identical (free, used, reclaimable, unitCode) share one area. A
 * category is dropped only if it lists exactly the same records as one already kept: `Samp
 * Lib` and `Samp Lib (Native)` are two views of the same items, while `Program`, `Live` and
 * `Settings` are different content in one area. Whole listings are compared, not items: on a
 * Nord Grand every `Live` slot holds a byte-identical copy of a program.
 *
 * Two round trips per item, so slow on a large instrument; [progress] is called as
 * `progress(name, index)` before each category walk.
 */
suspend fun NordDevice.calibrateStorageUnits(
    progress: ((String, Int) -> Unit)? = null,
    onFailure: ((String, Exception) -> Unit)? = null,
): List<StorageArea> {
    // The names to walk, and the allocation unit each category states in its trailer.
    val rootCategories = parseRootCategories(rootCategoryListPayload())
    val names = rootCategories.map { it.name }
    val reportedUnits = rootCategories.map { it.unitBytes }

    // Per category, so one that will not answer does not abandon the rest.
    val spaces = LinkedHashMap<Int, CategorySpace>()
    names.indices.forEach { index ->
        try {
            spaces[index] = fetchCategorySpace(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read storage figures for '${names[index]}'", e)
            onFailure?.invoke(names[index], e)
        }
    }

    // Group by the storage triple: identical figures mean one shared area.
    val grouped = LinkedHashMap<List<Int>, MutableList<Int>>()
    spaces.forEach { (index, s) ->
        grouped.getOrPut(listOf(s.free, s.used, s.reclaimable, s.unitCode)) { mutableListOf() }.add(index)
    }

    return grouped.values.map { indexes ->
        calibrateArea(
            indexes.map { names[it] },
            indexes,
            spaces.getValue(indexes.first()),
            indexes.map { spaces.getValue(it).itemCount },
            // Every category in an area states the same unit; the first tolerates a short trailer.
            indexes.firstNotNullOfOrNull { reportedUnits.getOrNull(it) },
            progress,
            onFailure,
        )
    }
}

private suspend fun NordDevice.calibrateArea(
    names: List<String>,
    indexes: List<Int>,
    space: CategorySpace,
    itemCounts: List<Int>,
    reportedUnitBytes: Int?,
    progress: ((String, Int) -> Unit)?,
    onFailure: ((String, Exception) -> Unit)?,
): StorageArea {
    if (space.countedInBytes) {
        return StorageArea(
            names = names,
            indexes = indexes,
            space = space,
            itemCounts = itemCounts,
            recordCount = space.itemCount,
            totalBytes = 0L,
            unitBytes = 1,
            residual = null,
            candidateRange = null,
            reason = "counted in bytes, not blocks (unit code 0) - nothing to calibrate",
        )
    }

    val listings = ArrayList<Map<ItemIdentity, Int>>() // per category: identity -> times listed
    val sizeOf = HashMap<ItemIdentity, Int>()
    for ((name, index) in names.zip(indexes)) {
        progress?.invoke(name, index)
        val payloads = try {
            fetchCategoryItems(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Both the area's note and the report's top-level failure map, so an interrupted
            // report does not read as complete.
            onFailure?.invoke(name, e)
            return StorageArea(
                names = names,
                indexes = indexes,
                space = space,
                itemCounts = itemCounts,
                recordCount = 0,
                totalBytes = 0L,
                unitBytes = null,
                residual = null,
                candidateRange = null,
                reason = "couldn't walk '$name', so the records are incomplete (${e.message})",
            )
        }
        val here = HashMap<ItemIdentity, Int>()
        for (payload in payloads) {
            val identity = itemIdentity(payload)
            here[identity] = (here[identity] ?: 0) + 1
            sizeOf[identity] = NordDevice.parseItemDataSize(payload)
        }
        if (listings.none { it == here }) listings.add(here)
    }

    val sizes = listings.flatMap { here ->
        here.flatMap { (identity, count) -> List(count) { sizeOf.getValue(identity) } }
    }
    val solved = NordStorageCalibration.solveStorageUnit(sizes, space.used)
    val reportedResidual = if (sizes.isNotEmpty() && reportedUnitBytes != null && reportedUnitBytes > 0) {
        (space.used - NordStorageCalibration.storageUnitsAt(sizes, reportedUnitBytes)).toInt()
    } else {
        null
    }
    return StorageArea(
        names = names,
        indexes = indexes,
        space = space,
        itemCounts = itemCounts,
        recordCount = sizes.size,
        totalBytes = sizes.sumOf { it.toLong() },
        unitBytes = solved.unitBytes,
        residual = solved.residual,
        candidateRange = solved.candidateRange,
        reason = solved.reason,
        reportedResidual = reportedResidual,
    )
}

/**
 * A key identifying an item record by what it is, for recognising the same item listed under
 * two categories: the content id where the instrument provides one, `(tag, name, size)` where
 * it does not (a Stage 2 EX). Not `(bank, item)`: a Nord Grand's two `Samp Lib` views list the
 * same 235 samples at different addresses.
 */
fun NordDevice.itemIdentity(payload: ByteArray): ItemIdentity =
    itemContentId(payload)?.let { ItemIdentity.Content(it) }
        ?: ItemIdentity.Described(
            tag = payload.copyOfRange(16, 20).toList(),
            name = parseItemName(payload),
            size = NordDevice.parseItemDataSize(payload),
        )

/** See [itemIdentity]. */
sealed interface ItemIdentity {
    /** The content id the instrument gives the item. */
    data class Content(val id: Int) : ItemIdentity

    /** What the record says the item is, where it carries no content id. */
    data class Described(val tag: List<Byte>, val name: String, val size: Int) : ItemIdentity
}

/** The allocation-unit solver behind [calibrateStorageUnits], with the tolerances it fits within. */
internal object NordStorageCalibration {

    /** Total allocation units `sizes` occupy at a candidate unit size. */
    fun storageUnitsAt(sizes: List<Int>, unitBytes: Int): Long =
        sizes.sumOf { ((it + unitBytes - 1) / unitBytes).toLong() }

    /**
     * How far `used` may exceed the computed sum and still count as a fit. The residual (the
     * instrument's per-item overhead) ran under 0.3% on every area measured; wrong candidates
     * miss by 25-98%.
     */
    private const val CALIBRATION_TOLERANCE = 0.01
    private const val CALIBRATION_MIN_TOLERANCE = 2

    /**
     * Below this many units per item on average, nearly every item fits in one unit and every
     * candidate at or above the largest item fits equally well: a Nord Grand's slot-counted
     * `Program` area (ratio 1.05), where the block-counted areas run from 9 to 159.
     */
    private const val CALIBRATION_MIN_UNITS_PER_ITEM = 1.5

    data class SolvedUnit(
        val unitBytes: Int?,
        val residual: Int?,
        val candidateRange: Pair<Int, Int>?,
        val reason: String,
    )

    /**
     * Solve for the allocation unit that explains `used` given every item's byte size.
     *
     * `sum(ceil(size / U))` is non-increasing in U, so the units that fit form one
     * contiguous range: its lower edge is the smallest U whose sum has fallen to `used` or
     * below, its upper edge the largest still within tolerance. Both are found by binary
     * search, so this is a few dozen sums however large the area is. The value reported out
     * of that range is the one divisible by the largest power of two: real units are round
     * numbers (64/128/192/256 KB across the instruments measured so far) while the range's
     * edges are wherever the arithmetic happened to cross.
     */
    fun solveStorageUnit(rawSizes: List<Int>, used: Int): SolvedUnit {
        val sizes = rawSizes.filter { it > 0 }
        val count = sizes.size
        if (count == 0) return SolvedUnit(null, null, null, "no item records to measure against")
        if (used <= 0) {
            return SolvedUnit(null, null, null, "the area reports used=$used, which nothing can be fitted to")
        }
        if (used < count) {
            return SolvedUnit(
                null,
                null,
                null,
                "$count records against a reported used of $used - fewer units than items, so " +
                    "these records are not (only) what this area is accounting for",
            )
        }
        if (used < count * CALIBRATION_MIN_UNITS_PER_ITEM) {
            // Locale.ROOT, not the device's locale: this string is machine-readable output
            // that goes into a shared JSON report, and a German phone renders "1.05" as "1,05".
            val perItem = String.format(Locale.ROOT, "%.2f", used.toDouble() / count)
            return SolvedUnit(
                null,
                null,
                null,
                "$used units over $count items is $perItem per item - at barely one unit each, " +
                    "every candidate at or above the largest item explains the figures equally " +
                    "well, so these records cannot separate a block size from a per-item slot " +
                    "count",
            )
        }

        val tolerance = maxOf(CALIBRATION_MIN_TOLERANCE, (used * CALIBRATION_TOLERANCE).toInt())
        val largest = sizes.max()

        // Smallest unit whose total has come down to `used` - the range's lower edge.
        var lo = 1
        var hi = largest
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (storageUnitsAt(sizes, mid) <= used) hi = mid else lo = mid + 1
        }
        val unitLo = lo
        if (storageUnitsAt(sizes, unitLo) < used - tolerance) {
            return SolvedUnit(
                null,
                null,
                null,
                "no unit size reproduces a used of $used from these $count records " +
                    "(the closest total is ${storageUnitsAt(sizes, unitLo)})",
            )
        }

        // ...and the largest still within tolerance, the upper edge.
        lo = unitLo
        hi = largest
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (used - storageUnitsAt(sizes, mid) <= tolerance) lo = mid else hi = mid - 1
        }
        val unitHi = lo

        val unit = roundestInRange(unitLo, unitHi)
        val residual = (used - storageUnitsAt(sizes, unit)).toInt()
        return SolvedUnit(
            unit,
            residual,
            unitLo to unitHi,
            "$count records, $used units used, $residual unaccounted for; any unit from " +
                "$unitLo to $unitHi bytes fits as well",
        )
    }

    /**
     * The value in [lo]..[hi] divisible by the largest power of two, smallest first on a tie
     * - see [solveStorageUnit] for why that is the one worth reporting. Falls back to [lo]
     * for a range holding nothing rounder.
     */
    fun roundestInRange(lo: Int, hi: Int): Int {
        for (shift in (31 - Integer.numberOfLeadingZeros(hi)) downTo 1) {
            val step = 1 shl shift
            val first = ((lo + step - 1) / step) * step
            if (first <= hi) return first
        }
        return lo
    }
}
