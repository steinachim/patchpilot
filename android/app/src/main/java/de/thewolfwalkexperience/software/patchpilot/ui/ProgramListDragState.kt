// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Drag-to-reorder state for the preset list: which row is held, where the finger is, and the
 * edge auto-scroll that lets a target off-screen be reached.
 *
 * **One type rather than loose state in `ProgramsScreen`**: four `var`s, three density
 * conversions, a hit-test and an auto-scroll loop are one mechanism and only ever change together.
 *
 * Deliberately knows nothing about `ProgramRow`: which rows may be picked up and which may be
 * dropped on are questions about the *listing*, and change on every recomposition, so they arrive
 * as predicates rather than being baked in here.
 */
@Stable
internal class ProgramListDragState(
    private val listState: LazyListState,
    private val autoScrollThresholdPx: Float,
    private val autoScrollMaxSpeedPx: Float,
    /**
     * Left-edge band that counts as "touched the drag handle" - generous enough to cover the
     * handle glyph plus the row's padding around it, while staying clear of the headline text
     * that starts further right.
     */
    val handleZoneWidthPx: Float,
) {
    /** Row index (into the rendered rows) being dragged, or null when no drag is in flight. */
    var draggedIndex by mutableStateOf<Int?>(null)
        private set

    /** Row index the finger is currently over. */
    var dropTargetIndex by mutableStateOf<Int?>(null)
        private set

    /**
     * The finger's own y position in the list's coordinate space.
     *
     * Tracked independently of any row, because the dragged row's layout slot scrolls off the top
     * of the viewport after about one screen height of auto-scroll - `LazyColumn` culls on layout
     * position, not on the translation used to follow the finger - so judging the edge bands by it
     * would stop the scrolling well before the target is reached.
     */
    private var pointerY by mutableStateOf(0f)
    private var grabOffsetWithinRowPx by mutableStateOf(0f)

    /** Where to draw the floating "ghost" row that stands in for the one being dragged. */
    val ghostOffsetY: Int get() = (pointerY - grabOffsetWithinRowPx).roundToInt()

    /**
     * Resolves a y position to the row under it.
     *
     * Falls back to the nearest visible row's edge rather than null when y is beyond the composed
     * range (the finger dragged above or below the list), so a drop target stays available for the
     * whole duration of a drag.
     */
    private fun hitRowInfo(y: Float): LazyListItemInfo? {
        val items = listState.layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return null
        return items.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if (y < items.first().offset) items.first() else items.last()
    }

    /** Starts a drag if [offset] landed on the handle of a row [canDrag] accepts. */
    fun begin(offset: Offset, canDrag: (rowIndex: Int) -> Boolean) {
        if (offset.x > handleZoneWidthPx) return
        val hit = hitRowInfo(offset.y) ?: return
        if (!canDrag(hit.index)) return
        draggedIndex = hit.index
        dropTargetIndex = hit.index
        pointerY = offset.y
        grabOffsetWithinRowPx = offset.y - hit.offset
    }

    /** Moves the finger by [deltaY], re-resolving the drop target against [canDrop]. */
    fun update(deltaY: Float, canDrop: (rowIndex: Int) -> Boolean) {
        if (draggedIndex == null) return
        pointerY += deltaY
        hitRowInfo(pointerY)?.let { if (canDrop(it.index)) dropTargetIndex = it.index }
    }

    /** Ends the drag, returning the (source, target) row indices if both are still resolvable. */
    fun end(): Pair<Int, Int>? {
        val source = draggedIndex
        val target = dropTargetIndex
        cancel()
        return if (source != null && target != null) source to target else null
    }

    fun cancel() {
        draggedIndex = null
        dropTargetIndex = null
    }

    /**
     * Scrolls the list while the finger sits in an edge band, so targets outside the visible range
     * can be reached. Runs until cancelled - the caller keys it on [draggedIndex], so ending or
     * cancelling a drag stops it.
     *
     * Once a scroll actually moves content the drop target is re-resolved, since the row under a
     * stationary [pointerY] has changed.
     */
    suspend fun autoScroll(canDrop: (rowIndex: Int) -> Boolean) {
        while (true) {
            val viewportBottom = listState.layoutInfo.viewportEndOffset.toFloat()
            val overflowBottom = pointerY - (viewportBottom - autoScrollThresholdPx)
            val overflowTop = autoScrollThresholdPx - pointerY
            val scrollAmount = when {
                overflowBottom > 0f ->
                    (overflowBottom / autoScrollThresholdPx).coerceIn(0f, 1f) * autoScrollMaxSpeedPx
                overflowTop > 0f ->
                    -(overflowTop / autoScrollThresholdPx).coerceIn(0f, 1f) * autoScrollMaxSpeedPx
                else -> 0f
            }
            if (scrollAmount != 0f && listState.scrollBy(scrollAmount) != 0f) {
                hitRowInfo(pointerY)?.let { if (canDrop(it.index)) dropTargetIndex = it.index }
            }
            delay(AUTO_SCROLL_TICK_MS)
        }
    }
}

/**
 * Remembers a [ProgramListDragState] for [listState], converting its bands from dp so they scale
 * with screen density.
 */
@Composable
internal fun rememberProgramListDragState(listState: LazyListState): ProgramListDragState {
    val density = LocalDensity.current
    // Edge band that triggers auto-scroll, and the fastest the list scrolls once the drag is deep
    // inside that band - both tuned in dp.
    val threshold = with(density) { 64.dp.toPx() }
    val maxSpeed = with(density) { 24.dp.toPx() }
    val handleZone = with(density) { 56.dp.toPx() }
    return remember(listState, threshold, maxSpeed, handleZone) {
        ProgramListDragState(listState, threshold, maxSpeed, handleZone)
    }
}

/** One frame at 60 Hz - how often the auto-scroll loop re-evaluates while dragging. */
private const val AUTO_SCROLL_TICK_MS = 16L
