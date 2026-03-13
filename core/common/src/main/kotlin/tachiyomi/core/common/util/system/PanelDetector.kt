package tachiyomi.core.common.util.system

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/**
 * Manga panel detection utility for panel-by-panel zoom navigation.
 */
data class Panel(val rect: Rect)

enum class ReadingDirection {
    RTL,
    LTR,
    VERTICAL,
}

object PanelDetector {

    private const val MAX_WORKING_DIMENSION = 800

    fun detectPanels(bitmap: Bitmap, direction: ReadingDirection): List<Panel> {
        if (bitmap.width <= 1 || bitmap.height <= 1) return emptyList()

        val (workingBitmap, scaleX, scaleY) = bitmap.downscaleForDetection()
        val width = workingBitmap.width
        val height = workingBitmap.height
        if (width <= 1 || height <= 1) {
            if (workingBitmap !== bitmap) workingBitmap.recycle()
            return emptyList()
        }

        val binary = toBinaryMask(workingBitmap)
        if (workingBitmap !== bitmap) {
            workingBitmap.recycle()
        }

        dilateBlack(binary, width, height, radius = 3)

        val components = connectedComponents(binary, width, height)
        val filtered = components.filter { component ->
            val area = component.area.toFloat() / (width * height).toFloat()
            area in 0.03f..0.90f
        }

        val merged = mergeOverlapping(filtered.map { it.rect })
        if (merged.size < 2) return emptyList()

        val ordered = sortByReadingOrder(merged, height, direction)

        return ordered.map {
            Panel(
                Rect(
                    (it.left * scaleX).toInt().coerceAtLeast(0),
                    (it.top * scaleY).toInt().coerceAtLeast(0),
                    (it.right * scaleX).toInt().coerceAtMost(bitmap.width),
                    (it.bottom * scaleY).toInt().coerceAtMost(bitmap.height),
                ),
            )
        }
    }

    private data class Component(val rect: Rect, val area: Int)

    private fun Bitmap.downscaleForDetection(): Triple<Bitmap, Float, Float> {
        val largest = max(width, height)
        if (largest <= MAX_WORKING_DIMENSION) {
            return Triple(this, 1f, 1f)
        }

        val ratio = MAX_WORKING_DIMENSION / largest.toFloat()
        val scaledW = (width * ratio).toInt().coerceAtLeast(1)
        val scaledH = (height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(this, scaledW, scaledH, true)
        return Triple(scaled, width / scaledW.toFloat(), height / scaledH.toFloat())
    }

    private fun toBinaryMask(bitmap: Bitmap): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val mask = BooleanArray(width * height)
        pixels.forEachIndexed { i, pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b)
            // true = white / panel content, false = black/gutter
            mask[i] = luminance >= 50f
        }
        return mask
    }

    private fun dilateBlack(mask: BooleanArray, width: Int, height: Int, radius: Int) {
        val original = mask.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!original[index]) continue

                var hasBlackNeighbor = false
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        if (!original[ny * width + nx]) {
                            hasBlackNeighbor = true
                            break
                        }
                    }
                    if (hasBlackNeighbor) break
                }

                if (hasBlackNeighbor) {
                    mask[index] = false
                }
            }
        }
    }

    private fun connectedComponents(mask: BooleanArray, width: Int, height: Int): List<Component> {
        val labels = IntArray(width * height)
        var nextLabel = 1
        val parent = IntArray(width * height) { it }

        fun find(x: Int): Int {
            var node = x
            while (parent[node] != node) {
                parent[node] = parent[parent[node]]
                node = parent[node]
            }
            return node
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!mask[index]) continue

                val neighbors = mutableListOf<Int>()
                if (x > 0) {
                    val left = labels[index - 1]
                    if (left > 0) neighbors += left
                }
                if (y > 0) {
                    val up = labels[index - width]
                    if (up > 0) neighbors += up
                }

                if (neighbors.isEmpty()) {
                    labels[index] = nextLabel
                    nextLabel++
                } else {
                    val minLabel = neighbors.minOrNull()!!
                    labels[index] = minLabel
                    neighbors.forEach { union(minLabel, it) }
                }
            }
        }

        data class Agg(var left: Int, var top: Int, var right: Int, var bottom: Int, var area: Int)
        val components = mutableMapOf<Int, Agg>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val label = labels[index]
                if (label == 0) continue

                val root = find(label)
                val agg = components.getOrPut(root) { Agg(x, y, x + 1, y + 1, 0) }
                agg.left = minOf(agg.left, x)
                agg.top = minOf(agg.top, y)
                agg.right = maxOf(agg.right, x + 1)
                agg.bottom = maxOf(agg.bottom, y + 1)
                agg.area++
            }
        }

        return components.values.map {
            Component(Rect(it.left, it.top, it.right, it.bottom), it.area)
        }
    }

    private fun mergeOverlapping(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val list = rects.toMutableList()

        var mergedAny: Boolean
        do {
            mergedAny = false
            loop@ for (i in 0 until list.size) {
                for (j in i + 1 until list.size) {
                    if (iou(list[i], list[j]) > 0.5f) {
                        val union = Rect(
                            minOf(list[i].left, list[j].left),
                            minOf(list[i].top, list[j].top),
                            maxOf(list[i].right, list[j].right),
                            maxOf(list[i].bottom, list[j].bottom),
                        )
                        list[i] = union
                        list.removeAt(j)
                        mergedAny = true
                        break@loop
                    }
                }
            }
        } while (mergedAny)

        return list
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interW = (interRight - interLeft).coerceAtLeast(0)
        val interH = (interBottom - interTop).coerceAtLeast(0)
        val interArea = interW * interH
        if (interArea == 0) return 0f

        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea == 0) 0f else interArea.toFloat() / unionArea.toFloat()
    }

    private fun sortByReadingOrder(rects: List<Rect>, imageHeight: Int, direction: ReadingDirection): List<Rect> {
        if (direction == ReadingDirection.VERTICAL) {
            return rects.sortedWith(compareBy<Rect> { it.centerY() }.thenBy { it.centerX() })
        }

        val rowThreshold = imageHeight * 0.15f
        val rows = mutableListOf<MutableList<Rect>>()

        val byY = rects.sortedBy { it.centerY() }
        byY.forEach { rect ->
            val row = rows.find { existing -> abs(existing.first().centerY() - rect.centerY()) <= rowThreshold }
            if (row == null) {
                rows += mutableListOf(rect)
            } else {
                row += rect
            }
        }

        return rows
            .sortedBy { row -> row.minOf { it.centerY() } }
            .flatMap { row ->
                when (direction) {
                    ReadingDirection.RTL -> row.sortedByDescending { it.centerX() }
                    ReadingDirection.LTR -> row.sortedBy { it.centerX() }
                    ReadingDirection.VERTICAL -> row.sortedBy { it.centerY() }
                }
            }
    }
}
