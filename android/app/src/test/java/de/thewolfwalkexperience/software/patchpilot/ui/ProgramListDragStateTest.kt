// SPDX-FileCopyrightText: 2026 Achim Stein
// SPDX-License-Identifier: GPL-3.0-only

package de.thewolfwalkexperience.software.patchpilot.ui

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag-to-reorder gesture's state, against a list of thirty 100 px rows in a 600 px viewport.
 * The gesture itself cannot be driven over adb, so this is where it is checked.
 */
class ProgramListDragStateTest {

    /** Thirty rows of [ROW] px; [scrolled] moves them up, clamped to the content. */
    private class FakeList(private val rows: Int = 30) : DragListViewport {
        var scrolled = 0f
        val requests = mutableListOf<Float>()

        override val viewportEnd = VIEWPORT

        override val visibleRows: List<RowSpan>
            get() = (0 until rows)
                .map { RowSpan(it, it * ROW - scrolled.toInt(), ROW) }
                .filter { it.offset + it.size > 0 && it.offset < VIEWPORT }

        override suspend fun scrollBy(px: Float): Float {
            requests += px
            val target = (scrolled + px).coerceIn(0f, (rows * ROW - VIEWPORT).toFloat())
            val moved = target - scrolled
            scrolled = target
            return moved
        }
    }

    private fun drag(list: FakeList = FakeList()) =
        ProgramListDragState(list, autoScrollThresholdPx = 64f, autoScrollMaxSpeedPx = 24f, handleZoneWidthPx = 56f)

    private val any: (Int) -> Boolean = { true }

    @Test
    fun `a drag starts only on a row's handle`() {
        val drag = drag()
        drag.begin(Offset(100f, 150f), any)
        assertNull("a touch past the handle band is not a drag", drag.draggedIndex)

        drag.begin(Offset(20f, 150f), any)
        assertEquals(1, drag.draggedIndex)
        assertEquals(1, drag.dropTargetIndex)
        // The ghost sits where the row was, wherever in the row the finger landed.
        assertEquals(100, drag.ghostOffsetY)
    }

    @Test
    fun `a row the listing refuses is not picked up`() {
        val drag = drag()
        drag.begin(Offset(20f, 150f)) { it != 1 }
        assertNull(drag.draggedIndex)
    }

    @Test
    fun `the drop target follows the finger and skips rows that take no drop`() {
        val drag = drag()
        drag.begin(Offset(20f, 150f), any)
        drag.update(200f, any)
        assertEquals(3, drag.dropTargetIndex)

        drag.update(100f) { it != 4 }
        assertEquals("a refused row leaves the last good target", 3, drag.dropTargetIndex)
        assertEquals(400, drag.ghostOffsetY)
    }

    @Test
    fun `a finger beyond the laid-out rows targets the nearest one`() {
        val drag = drag()
        drag.begin(Offset(20f, 150f), any)
        drag.update(1_000f, any)
        assertEquals("the last row in the 600 px viewport", 5, drag.dropTargetIndex)
        drag.update(-2_000f, any)
        assertEquals(0, drag.dropTargetIndex)
    }

    @Test
    fun `end hands back source and target and clears the drag`() {
        val drag = drag()
        assertNull("no drag, nothing to hand back", drag.end())

        drag.begin(Offset(20f, 150f), any)
        drag.update(200f, any)
        assertEquals(1 to 3, drag.end())
        assertNull(drag.draggedIndex)
        assertNull(drag.dropTargetIndex)

        drag.begin(Offset(20f, 150f), any)
        drag.cancel()
        assertNull(drag.draggedIndex)
        assertNull(drag.end())
    }

    /** 32 px into the 64 px bottom band is half speed, 12 px a tick; the target follows the scroll. */
    @Test
    fun `auto-scroll speeds up with depth into the edge band and re-picks the target`() = runTest {
        val list = FakeList()
        val drag = drag(list)
        drag.begin(Offset(20f, 150f), any)
        drag.update(418f, any) // to y = 568, row 5
        assertEquals(5, drag.dropTargetIndex)

        val scrolling = launch { drag.autoScroll(any) }
        runCurrent()
        assertEquals(12f, list.requests.first())

        advanceTimeBy(3 * 16L)
        runCurrent()
        assertTrue(list.scrolled >= 36f)
        assertEquals("row 6 has moved under the finger", 6, drag.dropTargetIndex)
        scrolling.cancel()
    }

    @Test
    fun `auto-scroll stays still away from the edges and upward at the top`() = runTest {
        val list = FakeList()
        val drag = drag(list)
        drag.begin(Offset(20f, 300f), any)
        val middle = launch { drag.autoScroll(any) }
        advanceTimeBy(5 * 16L)
        runCurrent()
        middle.cancel()
        assertTrue("nothing scrolls in the middle", list.requests.isEmpty())

        drag.update(-284f, any) // to y = 16: 48 px into the top band
        val top = launch { drag.autoScroll(any) }
        runCurrent()
        top.cancel()
        assertEquals(-18f, list.requests.single())
    }

    private companion object {
        const val ROW = 100
        const val VIEWPORT = 600
    }
}
