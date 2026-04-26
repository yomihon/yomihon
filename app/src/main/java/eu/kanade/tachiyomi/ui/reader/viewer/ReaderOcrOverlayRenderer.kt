package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import mihon.domain.ocr.model.OcrTextOrientation
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

internal class ReaderOcrOverlayRenderer(
    private val textPaint: TextPaint,
    private val density: Float,
    private val scaledDensity: Float,
    highlightColor: Int,
) {
    private val touchAllowancePx = density * 12f
    private val minTextSizePx = 6f * scaledDensity
    private val textSizeStepPx = (scaledDensity * 0.5f).coerceAtLeast(0.5f)

    // Use the theme's onPrimaryContainer color with a fixed alpha for the highlight.
    private val highlightPaintColor = Color.argb(
        220,
        Color.red(highlightColor),
        Color.green(highlightColor),
        Color.blue(highlightColor),
    )
    private val horizontalHighlightPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = highlightPaintColor
        }
    private val verticalHighlightPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = highlightPaintColor
        }
    private val verticalHighlightRadiusPx = density * 4f

    /**
     * Text color to use on top of the highlight - black or white chosen by contrast against
     * the highlight background color.
     */
    private val highlightTextColor: Int = contrastTextColor(highlightColor)

    fun buildLayout(
        bubbleRect: RectF,
        displayText: String,
        textOrientation: OcrTextOrientation,
        highlightRange: Pair<Int, Int>?,
    ): ReaderOcrOverlayLayout? {
        if (displayText.isBlank() || bubbleRect.width() <= 0f || bubbleRect.height() <= 0f) {
            return null
        }

        val contentRect = RectF(bubbleRect)
        return when (textOrientation) {
            OcrTextOrientation.Horizontal -> buildHorizontalLayout(bubbleRect, contentRect, displayText, highlightRange)
            OcrTextOrientation.Vertical -> buildVerticalLayout(bubbleRect, contentRect, displayText, highlightRange)
        }
    }

    fun drawOverlay(canvas: Canvas, overlayLayout: ReaderOcrOverlayLayout) {
        when (overlayLayout) {
            is ReaderOcrOverlayLayout.Horizontal -> drawHorizontalText(canvas, overlayLayout)
            is ReaderOcrOverlayLayout.Vertical -> drawVerticalText(canvas, overlayLayout)
        }
    }

    fun isPointNearText(
        overlayLayout: ReaderOcrOverlayLayout,
        localX: Float,
        localY: Float,
    ): Boolean {
        return when (overlayLayout) {
            is ReaderOcrOverlayLayout.Horizontal -> {
                applyTextPaint(overlayLayout)
                overlayLayout.lines.any { line ->
                    val lineRight = line.left + measureText(line.text)
                    localY >= line.top - touchAllowancePx &&
                        localY <= line.bottom + touchAllowancePx &&
                        localX >= line.left - touchAllowancePx &&
                        localX <= lineRight + touchAllowancePx
                }
            }
            is ReaderOcrOverlayLayout.Vertical -> overlayLayout.glyphs.any { glyph ->
                localX >= glyph.rect.left - touchAllowancePx &&
                    localX <= glyph.rect.right + touchAllowancePx &&
                    localY >= glyph.rect.top - touchAllowancePx &&
                    localY <= glyph.rect.bottom + touchAllowancePx
            }
        }
    }

    fun resolveQueryOffset(
        overlayLayout: ReaderOcrOverlayLayout,
        localX: Float,
        localY: Float,
    ): Int {
        val displayText = overlayLayout.displayText
        if (displayText.isEmpty()) return 0

        val displayOffset = when (overlayLayout) {
            is ReaderOcrOverlayLayout.Horizontal -> {
                applyTextPaint(overlayLayout)
                val line = overlayLayout.lines.minByOrNull { placedLine ->
                    val centerY = (placedLine.top + placedLine.bottom) / 2f
                    abs(centerY - localY)
                } ?: return 0
                val relativeX = (localX - line.left).coerceAtLeast(0f)
                var charOffsetInLine = line.text.length
                for (index in line.text.indices) {
                    val charCenter = measureText(line.text, 0, index) + (measureText(line.text[index].toString()) / 2f)
                    if (relativeX <= charCenter) {
                        charOffsetInLine = index
                        break
                    }
                }
                min(line.startDisplayOffset + charOffsetInLine, displayText.lastIndex)
            }
            is ReaderOcrOverlayLayout.Vertical -> {
                overlayLayout.glyphs.minByOrNull { glyph ->
                    val centerX = (glyph.rect.left + glyph.rect.right) / 2f
                    val centerY = (glyph.rect.top + glyph.rect.bottom) / 2f
                    val deltaX = centerX - localX
                    val deltaY = centerY - localY
                    (deltaX * deltaX) + (deltaY * deltaY)
                }?.displayOffset ?: 0
            }
        }

        return displayOffsetToQueryOffset(displayText, displayOffset)
    }

    private fun drawHorizontalText(canvas: Canvas, overlayLayout: ReaderOcrOverlayLayout.Horizontal) {
        applyTextPaint(overlayLayout)
        overlayLayout.lines.forEach { line ->
            // Draw one merged highlight rect spanning the full highlighted range on this line.
            if (line.highlightSegments.isNotEmpty()) {
                val left = line.highlightSegments.minOf { it.left }
                val right = line.highlightSegments.maxOf { it.right }
                val top = line.highlightSegments.first().top
                val bottom = line.highlightSegments.first().bottom
                canvas.drawRect(left, top, right, bottom, horizontalHighlightPaint)
            }
            // Always draw white text; highlighted segments get a contrasting color.
            // We draw non-highlighted text first in white, then re-draw highlighted chars
            // on top in the contrasting color so the highlight background shows correctly.
            textPaint.color = Color.WHITE
            canvas.drawText(line.text, line.left, line.baselineY, textPaint)
            if (line.highlightSegments.isNotEmpty()) {
                textPaint.color = highlightTextColor
                line.highlightText.forEach { (text, x) ->
                    canvas.drawText(text, x, line.baselineY, textPaint)
                }
            }
        }
    }

    private fun drawVerticalText(canvas: Canvas, overlayLayout: ReaderOcrOverlayLayout.Vertical) {
        textPaint.textSize = overlayLayout.textSizePx
        textPaint.letterSpacing = 0f

        // Draw one merged rounded-rect highlight per column span.
        overlayLayout.glyphs
            .filter { it.isHighlighted }
            .groupBy { it.rect.left }
            .forEach { (_, columnGlyphs) ->
                val left = columnGlyphs.first().rect.left
                val right = columnGlyphs.first().rect.right
                val top = columnGlyphs.minOf { it.rect.top }
                val bottom = columnGlyphs.maxOf { it.rect.bottom }
                canvas.drawRoundRect(
                    RectF(left, top, right, bottom),
                    verticalHighlightRadiusPx,
                    verticalHighlightRadiusPx,
                    verticalHighlightPaint,
                )
            }

        overlayLayout.glyphs.forEach { glyph ->
            textPaint.color = if (glyph.isHighlighted) highlightTextColor else Color.WHITE
            val drawChar = verticalEllipsis(glyph.char)
            val textWidth = measureText(drawChar)
            val textX = glyph.rect.left + ((glyph.rect.width() - textWidth) / 2f)
            canvas.drawText(drawChar, textX, glyph.baselineY, textPaint)
        }
    }

    private fun buildHorizontalLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        displayText: String,
        highlightRange: Pair<Int, Int>?,
    ): ReaderOcrOverlayLayout? {
        val lines = displayText.split('\n')
        val maxTextSizePx = maxOf(12f * scaledDensity, minOf(contentRect.height(), contentRect.width()) * 0.9f)

        var textSizePx = maxTextSizePx
        while (textSizePx >= minTextSizePx) {
            val layout = createHorizontalLayout(bubbleRect, contentRect, lines, displayText, highlightRange, textSizePx)
            if (layout != null) {
                return layout
            }
            textSizePx -= textSizeStepPx
        }

        return null
    }

    private fun createHorizontalLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        lines: List<String>,
        displayText: String,
        highlightRange: Pair<Int, Int>?,
        textSizePx: Float,
    ): ReaderOcrOverlayLayout.Horizontal? {
        textPaint.textSize = textSizePx
        textPaint.letterSpacing = 0f
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top
        if (lineHeight <= 0f) return null

        // Fit check with no letter spacing so the longest line fits naturally
        val longestLine = lines.maxByOrNull { measureText(it) } ?: return null
        val longestLineNaturalWidth = measureText(longestLine)
        val totalTextHeight = lineHeight * lines.size
        if (longestLineNaturalWidth > contentRect.width() || totalTextHeight > contentRect.height()) {
            return null
        }

        // Letter spacing (in em units) to make the longest line span the full width
        val letterSpacingEm = if (longestLine.isNotEmpty() && longestLineNaturalWidth < contentRect.width()) {
            (contentRect.width() - longestLineNaturalWidth) / (longestLine.length * textSizePx)
        } else {
            0f
        }
        textPaint.letterSpacing = letterSpacingEm

        val centerLines = !hasCjk(displayText)
        val linePlacements = mutableListOf<HorizontalLinePlacement>()
        var displayOffset = 0
        lines.forEachIndexed { index, line ->
            // Lines start from the top of the content rect
            val lineTop = contentRect.top + (lineHeight * index)
            val lineBottom = lineTop + lineHeight
            val lineWidth = measureText(line) // measured with letter spacing applied
            // CJK: left-align; non-CJK: center each line within the content rect
            val lineLeft = if (centerLines) {
                contentRect.left + (contentRect.width() - lineWidth) / 2f
            } else {
                contentRect.left
            }
            val highlightSegments = mutableListOf<RectF>()
            // Collect contiguous runs of highlighted characters so we can re-draw them
            // in a contrasting text color on top of the highlight background.
            val highlightText = mutableListOf<Pair<String, Float>>()
            var highlightRunStart = -1
            var highlightRunStartIndex = -1

            fun flushHighlightRun(endCharIndex: Int) {
                if (highlightRunStart >= 0) {
                    val runText = line.substring(highlightRunStartIndex, endCharIndex)
                    val runX = lineLeft + measureText(line, 0, highlightRunStartIndex)
                    highlightText += Pair(runText, runX)
                    highlightRunStart = -1
                    highlightRunStartIndex = -1
                }
            }

            line.forEachIndexed { charIndex, _ ->
                val charDisplayOffset = displayOffset + charIndex
                if (highlightRange != null && charDisplayOffset in highlightRange.first until highlightRange.second) {
                    val left = lineLeft + measureText(line, 0, charIndex)
                    val right = lineLeft + measureText(line, 0, charIndex + 1)
                    highlightSegments += RectF(left, lineTop, right, lineBottom)
                    if (highlightRunStart < 0) {
                        highlightRunStart = charDisplayOffset
                        highlightRunStartIndex = charIndex
                    }
                } else {
                    flushHighlightRun(charIndex)
                }
            }
            flushHighlightRun(line.length)

            linePlacements += HorizontalLinePlacement(
                text = line,
                left = lineLeft,
                top = lineTop,
                bottom = lineBottom,
                baselineY = lineTop - fontMetrics.top,
                startDisplayOffset = displayOffset,
                highlightSegments = highlightSegments,
                highlightText = highlightText,
            )
            displayOffset += line.length
            if (index < lines.lastIndex) {
                displayOffset++
            }
        }

        return ReaderOcrOverlayLayout.Horizontal(
            bubbleRect = RectF(bubbleRect),
            textRect = RectF(contentRect),
            lines = linePlacements,
            displayText = displayText,
            textSizePx = textSizePx,
            letterSpacingEm = letterSpacingEm,
        )
    }

    private fun buildVerticalLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        displayText: String,
        highlightRange: Pair<Int, Int>?,
    ): ReaderOcrOverlayLayout? {
        val lines = displayText.split('\n')
        val maxCharsInColumn = lines.maxOfOrNull(String::length)?.coerceAtLeast(1) ?: return null
        val columnCount = lines.size.coerceAtLeast(1)
        val maxTextSizePx = maxOf(
            12f * scaledDensity,
            minOf(contentRect.height() / maxCharsInColumn, contentRect.width() / columnCount) * 1.2f,
        )

        var textSizePx = maxTextSizePx
        while (textSizePx >= minTextSizePx) {
            val layout = createVerticalLayout(bubbleRect, contentRect, displayText, highlightRange, textSizePx)
            if (layout != null) {
                return layout
            }
            textSizePx -= textSizeStepPx
        }

        return null
    }

    private fun createVerticalLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        displayText: String,
        highlightRange: Pair<Int, Int>?,
        textSizePx: Float,
    ): ReaderOcrOverlayLayout.Vertical? {
        textPaint.textSize = textSizePx
        textPaint.letterSpacing = 0f
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top
        if (lineHeight <= 0f) return null

        val lines = displayText.split('\n')
        val columnCount = lines.size.coerceAtLeast(1)
        val columnWidth = contentRect.width() / columnCount
        if (columnWidth <= 0f) return null

        val lineData = mutableListOf<VerticalLineData>()
        var displayOffset = 0
        for ((lineIndex, line) in lines.withIndex()) {
            val glyphs = mutableListOf<VerticalGlyph>()
            line.forEachIndexed { charIndex, char ->
                val glyphWidth = maxOf(measureText(verticalEllipsis(char.toString())), textSizePx * 0.85f)
                val isHighlighted = highlightRange?.let { displayOffset in it.first until it.second } == true
                glyphs += VerticalGlyph(
                    char = char.toString(),
                    displayOffset = displayOffset,
                    glyphWidth = glyphWidth,
                    isHighlighted = isHighlighted,
                    indexInColumn = charIndex,
                )
                displayOffset++
            }
            lineData += VerticalLineData(
                glyphs = glyphs,
                lineWidth =
                glyphs.maxOfOrNull { it.glyphWidth }?.coerceAtLeast(textSizePx * 0.85f) ?: textSizePx * 0.85f,
            )
            if (lineIndex < lines.lastIndex) {
                displayOffset++
            }
        }

        val maxCharsInColumn = lineData.maxOfOrNull { it.glyphs.size } ?: 1
        // Each CJK glyph nominally occupies ~1em square; lineHeight (~1.5em) is too tall.
        // Use textSizePx as the cell height for tight, natural vertical packing.
        val glyphCellHeight = textSizePx
        if (lineData.any { it.lineWidth > columnWidth } || glyphCellHeight * maxCharsInColumn > contentRect.height()) {
            return null
        }

        val glyphTopStep = glyphCellHeight
        // Recompute baseline so text is vertically centered within the smaller cell.
        // Standard centering: baseline = cellTop + (cellHeight - lineHeight)/2 - fontMetrics.top
        val baselineOffsetInCell = (glyphCellHeight - lineHeight) / 2f - fontMetrics.top

        val placedGlyphs = mutableListOf<VerticalGlyphPlacement>()
        lineData.forEachIndexed { columnIndex, line ->
            val columnLeft = contentRect.right - ((columnIndex + 1) * columnWidth)
            val columnRight = columnLeft + columnWidth
            line.glyphs.forEach { glyph ->
                val glyphTop = contentRect.top + (glyph.indexInColumn * glyphTopStep)
                placedGlyphs += VerticalGlyphPlacement(
                    char = glyph.char,
                    displayOffset = glyph.displayOffset,
                    rect = RectF(columnLeft, glyphTop, columnRight, glyphTop + glyphCellHeight),
                    baselineY = glyphTop + baselineOffsetInCell,
                    isHighlighted = glyph.isHighlighted,
                )
            }
        }

        return ReaderOcrOverlayLayout.Vertical(
            bubbleRect = RectF(bubbleRect),
            textRect = RectF(contentRect),
            glyphs = placedGlyphs,
            displayText = displayText,
            textSizePx = textSizePx,
        )
    }

    private fun applyTextPaint(layout: ReaderOcrOverlayLayout.Horizontal) {
        textPaint.textSize = layout.textSizePx
        textPaint.letterSpacing = layout.letterSpacingEm
    }

    private fun measureText(text: String): Float = textPaint.measureText(text)

    private fun measureText(text: String, start: Int, end: Int): Float {
        return textPaint.measureText(text, start, end)
    }

    private fun verticalEllipsis(text: String): String {
        return when (text) {
            "…" -> "︙"
            else -> text
        }
    }

    private fun hasCjk(text: String): Boolean = text.any { c ->
        val cp = c.code
        // CJK Unified Ideographs, CJK Extension A/B, CJK Compatibility Ideographs,
        // Hiragana, Katakana, Hangul Syllables, Hangul Jamo, Bopomofo
        cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF ||
            cp in 0x3040..0x309F || cp in 0x30A0..0x30FF ||
            cp in 0xAC00..0xD7AF || cp in 0x1100..0x11FF ||
            cp in 0x3100..0x312F
    }

    companion object {
        /**
         * Returns [Color.BLACK] or [Color.WHITE] - whichever has higher contrast
         * against [background] - using the WCAG relative-luminance formula.
         */
        fun contrastTextColor(background: Int): Int {
            fun linearize(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            val r = linearize(Color.red(background))
            val g = linearize(Color.green(background))
            val b = linearize(Color.blue(background))
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            // Contrast ratio against white = (1 + 0.05) / (luminance + 0.05)
            // Contrast ratio against black = (luminance + 0.05) / (0 + 0.05)
            // Choose whichever is larger.
            return if ((1.05 / (luminance + 0.05)) >= ((luminance + 0.05) / 0.05)) {
                Color.WHITE
            } else {
                Color.BLACK
            }
        }
    }
}

internal sealed interface ReaderOcrOverlayLayout {
    val bubbleRect: RectF
    val textRect: RectF
    val displayText: String

    data class Horizontal(
        override val bubbleRect: RectF,
        override val textRect: RectF,
        val lines: List<HorizontalLinePlacement>,
        override val displayText: String,
        val textSizePx: Float,
        val letterSpacingEm: Float,
    ) : ReaderOcrOverlayLayout

    data class Vertical(
        override val bubbleRect: RectF,
        override val textRect: RectF,
        val glyphs: List<VerticalGlyphPlacement>,
        override val displayText: String,
        val textSizePx: Float,
    ) : ReaderOcrOverlayLayout
}

internal data class VerticalGlyphPlacement(
    val char: String,
    val displayOffset: Int,
    val rect: RectF,
    val baselineY: Float,
    val isHighlighted: Boolean,
)

internal data class HorizontalLinePlacement(
    val text: String,
    val left: Float,
    val top: Float,
    val bottom: Float,
    val baselineY: Float,
    val startDisplayOffset: Int,
    val highlightSegments: List<RectF>,
    /** Contiguous runs of highlighted characters as (text, x) for contrast-color redraw. */
    val highlightText: List<Pair<String, Float>> = emptyList(),
)

private data class VerticalGlyph(
    val char: String,
    val displayOffset: Int,
    val glyphWidth: Float,
    val isHighlighted: Boolean,
    val indexInColumn: Int,
)

private data class VerticalLineData(
    val glyphs: List<VerticalGlyph>,
    val lineWidth: Float,
)
