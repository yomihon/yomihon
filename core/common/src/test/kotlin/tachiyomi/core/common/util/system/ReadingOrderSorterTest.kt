package tachiyomi.core.common.util.system

import android.graphics.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReadingOrderSorterTest {

    private fun r(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    private fun sortIndicesRtl(rects: List<Rect>) =
        ReadingOrderSorter.sort(rects, ReadingDirection.RTL)

    private fun sortIndicesLtr(rects: List<Rect>) =
        ReadingOrderSorter.sort(rects, ReadingDirection.LTR)

    // ── Basic grid layouts ─────────────────────────────────────────────

    @Test
    fun `two panels side by side RTL`() {
        val rects = listOf(
            r(0, 0, 400, 500), // 0: left
            r(410, 0, 800, 500), // 1: right
        )
        assertEquals(listOf(1, 0), sortIndicesRtl(rects))
    }

    @Test
    fun `two panels side by side LTR`() {
        val rects = listOf(
            r(0, 0, 400, 500), // 0: left
            r(410, 0, 800, 500), // 1: right
        )
        assertEquals(listOf(0, 1), sortIndicesLtr(rects))
    }

    @Test
    fun `two panels stacked vertically`() {
        val rects = listOf(
            r(0, 0, 800, 400), // 0: top
            r(0, 410, 800, 800), // 1: bottom
        )
        assertEquals(listOf(0, 1), sortIndicesRtl(rects))
    }

    @Test
    fun `2x2 grid RTL`() {
        val rects = listOf(
            r(0, 0, 400, 400), // 0: topLeft
            r(410, 0, 800, 400), // 1: topRight
            r(0, 410, 400, 800), // 2: bottomLeft
            r(410, 410, 800, 800), // 3: bottomRight
        )
        // RTL: topRight, topLeft, bottomRight, bottomLeft
        assertEquals(listOf(1, 0, 3, 2), sortIndicesRtl(rects))
    }

    @Test
    fun `2x2 grid LTR`() {
        val rects = listOf(
            r(0, 0, 400, 400), // 0: topLeft
            r(410, 0, 800, 400), // 1: topRight
            r(0, 410, 400, 800), // 2: bottomLeft
            r(410, 410, 800, 800), // 3: bottomRight
        )
        assertEquals(listOf(0, 1, 2, 3), sortIndicesLtr(rects))
    }

    // ── Tall panel next to stacked panels ──────────────────────────────

    @Test
    fun `tall right panel with stacked left panels RTL`() {
        val rects = listOf(
            r(50, 110, 468, 340), // 0: topLeft
            r(50, 341, 468, 553), // 1: midLeft
            r(51, 563, 468, 759), // 2: botLeft
            r(480, 111, 755, 756), // 3: tallRight
        )
        // RTL: tallRight first, then left stack top-to-bottom
        assertEquals(listOf(3, 0, 1, 2), sortIndicesRtl(rects))
    }

    @Test
    fun `tall left panel with stacked right panels RTL`() {
        val rects = listOf(
            r(0, 100, 300, 800), // 0: tallLeft
            r(310, 100, 700, 350), // 1: topRight
            r(310, 360, 700, 580), // 2: midRight
            r(310, 590, 700, 800), // 3: botRight
        )
        // RTL: right stack first (top to bottom), then tallLeft
        assertEquals(listOf(1, 2, 3, 0), sortIndicesRtl(rects))
    }

    // ── Real manga page layouts from logs ──────────────────────────────

    @Test
    fun `page with wide top and rows below RTL - each row right before left`() {
        val rects = listOf(
            r(235, 0, 715, 352), // 0: wideTopRight
            r(1, 0, 222, 350), // 1: wideTopLeft
            r(343, 387, 715, 814), // 2: midRight
            r(13, 389, 332, 814), // 3: midLeft
            r(267, 851, 714, 1111), // 4: botRight
            r(14, 851, 254, 1110), // 5: botLeft
        )
        val result = sortIndicesRtl(rects)
        // Top row: 0 before 1
        assert(result.indexOf(0) < result.indexOf(1)) { "Top: right(0) before left(1). Got $result" }
        // Middle row: 2 before 3
        assert(result.indexOf(2) < result.indexOf(3)) { "Mid: right(2) before left(3). Got $result" }
        // Bottom row: 4 before 5
        assert(result.indexOf(4) < result.indexOf(5)) { "Bot: right(4) before left(5). Got $result" }
        // Top row before middle row
        assert(result.indexOf(1) < result.indexOf(2)) { "Top row before mid row. Got $result" }
        // Middle row before bottom row
        assert(result.indexOf(3) < result.indexOf(4)) { "Mid row before bot row. Got $result" }
    }

    @Test
    fun `page with 3 top panels and 2 middle panels RTL - middle right before left`() {
        val rects = listOf(
            r(545, 109, 763, 378), // 0: topRight
            r(316, 109, 583, 304), // 1: topMid
            r(46, 127, 248, 426), // 2: topLeft
            r(49, 516, 398, 830), // 3: midLeft
            r(409, 517, 763, 830), // 4: midRight
            r(369, 865, 751, 1199), // 5: botRight
            r(49, 866, 358, 1200), // 6: botLeft
        )
        val result = sortIndicesRtl(rects)
        // Middle row: right(4) before left(3)
        assert(result.indexOf(4) < result.indexOf(3)) {
            "Middle: right(4) before left(3). Got $result"
        }
        // Bottom row: right(5) before left(6)
        assert(result.indexOf(5) < result.indexOf(6)) {
            "Bottom: right(5) before left(6). Got $result"
        }
        // Top section before middle section
        assert(result.indexOf(2) < result.indexOf(3) && result.indexOf(2) < result.indexOf(4)) {
            "Top panels before middle panels. Got $result"
        }
    }

    // ── Edge cases ─────────────────────────────────────────────────────

    @Test
    fun `single panel`() {
        val rects = listOf(r(100, 100, 500, 500))
        assertEquals(listOf(0), sortIndicesRtl(rects))
    }

    @Test
    fun `empty list`() {
        assertEquals(emptyList<Int>(), sortIndicesRtl(emptyList()))
    }

    @Test
    fun `touching panels at boundary`() {
        val rects = listOf(
            r(0, 0, 800, 400), // 0: top
            r(0, 400, 800, 800), // 1: bottom
        )
        assertEquals(listOf(0, 1), sortIndicesRtl(rects))
    }

    @Test
    fun `overlapping panels fall back to positional sort RTL`() {
        val rects = listOf(
            r(200, 0, 800, 600), // 0: topRight
            r(0, 300, 600, 900), // 1: bottomLeft
        )
        // Overlap in both X and Y — fallback: sort by top, then right desc
        val result = sortIndicesRtl(rects)
        assertEquals(0, result[0]) // top=0 comes first
        assertEquals(1, result[1])
    }

    @Test
    fun `full width panels sort top to bottom`() {
        val rects = listOf(
            r(0, 0, 764, 400), // 0
            r(0, 410, 764, 700), // 1
            r(0, 710, 764, 1200), // 2
        )
        assertEquals(listOf(0, 1, 2), sortIndicesRtl(rects))
    }

    @Test
    fun `three rows of two panels RTL`() {
        val rects = listOf(
            r(0, 0, 380, 350), // 0: topLeft
            r(390, 0, 764, 350), // 1: topRight
            r(0, 360, 380, 700), // 2: midLeft
            r(390, 360, 764, 700), // 3: midRight
            r(0, 710, 380, 1200), // 4: botLeft
            r(390, 710, 764, 1200), // 5: botRight
        )
        assertEquals(listOf(1, 0, 3, 2, 5, 4), sortIndicesRtl(rects))
    }
}
