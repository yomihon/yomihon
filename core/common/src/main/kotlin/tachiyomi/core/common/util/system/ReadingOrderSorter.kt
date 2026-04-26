package tachiyomi.core.common.util.system

import android.graphics.Rect

/**
 * Sorts panel bounding boxes into manga reading order using recursive bisection.
 *
 * The algorithm recursively splits panels with horizontal and vertical cuts:
 * - Horizontal cuts divide into top/bottom groups (top first)
 * - Vertical cuts divide into left/right groups (order depends on reading direction)
 * - When no clean cut exists, falls back to positional sorting
 *
 * This handles complex layouts like tall panels next to stacked small panels,
 * which simple center-based row grouping gets wrong.
 */
object ReadingOrderSorter {

    /**
     * Returns indices into [rects] sorted by reading order for the given [direction].
     */
    fun sort(rects: List<Rect>, direction: ReadingDirection): List<Int> {
        if (rects.size <= 1) return rects.indices.toList()
        return sortRecursive(rects.indices.toList(), rects, direction)
    }

    private fun sortRecursive(
        indices: List<Int>,
        rects: List<Rect>,
        direction: ReadingDirection,
    ): List<Int> {
        if (indices.size <= 1) return indices

        // Try horizontal cut first (top-to-bottom reading is primary)
        val hCut = findBestCut(indices, rects, horizontal = true)
        if (hCut != null) {
            val top = indices.filter { rects[it].bottom <= hCut }
            val bottom = indices.filter { rects[it].top >= hCut }
            return sortRecursive(top, rects, direction) + sortRecursive(bottom, rects, direction)
        }

        // Try vertical cut
        val vCut = findBestCut(indices, rects, horizontal = false)
        if (vCut != null) {
            val left = indices.filter { rects[it].right <= vCut }
            val right = indices.filter { rects[it].left >= vCut }
            return when (direction) {
                ReadingDirection.RTL -> sortRecursive(right, rects, direction) + sortRecursive(left, rects, direction)
                ReadingDirection.LTR -> sortRecursive(left, rects, direction) + sortRecursive(right, rects, direction)
                ReadingDirection.VERTICAL -> sortRecursive(left, rects, direction) +
                    sortRecursive(right, rects, direction)
            }
        }

        // No clean cut — fallback to positional sort
        return when (direction) {
            ReadingDirection.RTL -> indices.sortedWith(
                compareBy<Int> { rects[it].top }.thenByDescending { rects[it].right },
            )
            ReadingDirection.LTR -> indices.sortedWith(
                compareBy<Int> { rects[it].top }.thenBy { rects[it].left },
            )
            ReadingDirection.VERTICAL -> indices.sortedWith(
                compareBy<Int> { rects[it].top }.thenBy { rects[it].left },
            )
        }
    }

    /**
     * Finds the best cut line that separates [indices] into two non-empty groups.
     * For horizontal cuts, finds a Y value no panel straddles.
     * For vertical cuts, finds an X value no panel straddles.
     * Returns the midpoint of the widest gap, or null if no valid cut exists.
     */
    private fun findBestCut(
        indices: List<Int>,
        rects: List<Rect>,
        horizontal: Boolean,
    ): Int? {
        data class Edge(val pos: Int, val isStart: Boolean)

        val edges = mutableListOf<Edge>()
        indices.forEach { i ->
            val r = rects[i]
            if (horizontal) {
                edges.add(Edge(r.top, isStart = true))
                edges.add(Edge(r.bottom, isStart = false))
            } else {
                edges.add(Edge(r.left, isStart = true))
                edges.add(Edge(r.right, isStart = false))
            }
        }
        // Process ends before starts at the same position so that
        // touching panels (end == start) still produce a valid gap.
        edges.sortWith(compareBy<Edge> { it.pos }.thenBy { if (it.isStart) 1 else 0 })

        var active = 0
        var bestCut: Int? = null
        var bestGap = -1
        var gapStart = -1

        for (edge in edges) {
            if (edge.isStart) {
                if (active == 0 && gapStart >= 0) {
                    val gap = edge.pos - gapStart
                    if (gap > bestGap) {
                        bestGap = gap
                        bestCut = (gapStart + edge.pos) / 2
                    }
                }
                active++
            } else {
                active--
                if (active == 0) {
                    gapStart = edge.pos
                }
            }
        }

        // Verify the cut produces two non-empty groups
        if (bestCut != null) {
            val hasAbove = indices.any {
                if (horizontal) rects[it].bottom <= bestCut else rects[it].right <= bestCut
            }
            val hasBelow = indices.any {
                if (horizontal) rects[it].top >= bestCut else rects[it].left >= bestCut
            }
            if (!hasAbove || !hasBelow) return null
        }

        return bestCut
    }
}
