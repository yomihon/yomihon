package mihon.data.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * OCR engine backed by Google Lens online OCR.
 * Extracts plain text from text-layout boxes in the protobuf response.
 */
internal class GlensOcrEngine : OcrEngine {
    private val textPostprocessor = TextPostprocessor()

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        val startTime = System.nanoTime()
        try {
            val preparedImage = prepareImage(image)
            val payload = buildRequestPayload(preparedImage)
            val responseBytes = executeRequest(payload)
            val extractedText = parseResponsePage(responseBytes).text

            val totalTime = (System.nanoTime() - startTime) / 1_000_000
            logcat(LogPriority.INFO) { "OCR(glens) Runtime: recognizeText total time: $totalTime ms" }
            extractedText
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR (glens) failed" }
            throw e
        }
    }

    internal suspend fun recognizePage(image: Bitmap): GlensPageResult = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        val startTime = System.nanoTime()
        try {
            val preparedImage = prepareImage(image)
            val payload = buildRequestPayload(preparedImage)
            val responseBytes = executeRequest(payload)
            val pageResult = parseResponsePage(responseBytes)

            val totalTime = (System.nanoTime() - startTime) / 1_000_000
            logcat(LogPriority.INFO) { "OCR(glens) Runtime: recognizePage total time: $totalTime ms" }
            pageResult
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OCR (glens) page recognition failed" }
            throw e
        }
    }

    override fun close() = Unit

    private fun prepareImage(image: Bitmap): PreparedImage {
        val maxDim = maxOf(image.width, image.height)
        val resized = if (maxDim > MAX_IMAGE_DIMENSION) {
            val scaleFactor = MAX_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
            val targetWidth = (image.width * scaleFactor).toInt().coerceAtLeast(1)
            val targetHeight = (image.height * scaleFactor).toInt().coerceAtLeast(1)
            image.scale(targetWidth, targetHeight, filter = true)
        } else {
            null
        }

        val working = resized ?: image
        return try {
            val encoded = ByteArrayOutputStream()
            val success = working.compress(Bitmap.CompressFormat.PNG, 100, encoded)
            if (!success) {
                throw IOException("Failed to encode image for GLens request")
            }

            PreparedImage(
                bytes = encoded.toByteArray(),
                width = working.width,
                height = working.height,
            )
        } finally {
            resized?.recycle()
        }
    }

    private fun buildRequestPayload(image: PreparedImage): ByteArray {
        val requestId = Random.nextLong()

        return ProtoWriter().apply {
            writeMessage(fieldNumber = SERVER_REQUEST_OBJECTS_REQUEST) { objectsRequest ->
                objectsRequest.writeMessage(fieldNumber = OBJECTS_REQUEST_CONTEXT) { requestContext ->
                    requestContext.writeMessage(fieldNumber = REQUEST_CONTEXT_REQUEST_ID) { requestIdMessage ->
                        requestIdMessage.writeUInt64(fieldNumber = REQUEST_ID_UUID, value = requestId)
                        requestIdMessage.writeInt32(fieldNumber = REQUEST_ID_SEQUENCE_ID, value = 0)
                        requestIdMessage.writeInt32(fieldNumber = REQUEST_ID_IMAGE_SEQUENCE_ID, value = 0)
                        requestIdMessage.writeBytes(fieldNumber = REQUEST_ID_ANALYTICS_ID, value = Random.nextBytes(16))
                    }
                    requestContext.writeMessage(fieldNumber = REQUEST_CONTEXT_CLIENT_CONTEXT) { clientContext ->
                        clientContext.writeInt32(fieldNumber = CLIENT_CONTEXT_PLATFORM, value = PLATFORM_WEB)
                        clientContext.writeInt32(fieldNumber = CLIENT_CONTEXT_SURFACE, value = SURFACE_CHROMIUM)
                        clientContext.writeMessage(fieldNumber = CLIENT_CONTEXT_LOCALE_CONTEXT) { localeContext ->
                            localeContext.writeString(fieldNumber = LOCALE_LANGUAGE, value = DEFAULT_CLIENT_LANGUAGE)
                            localeContext.writeString(fieldNumber = LOCALE_REGION, value = DEFAULT_CLIENT_REGION)
                        }
                        clientContext.writeMessage(fieldNumber = CLIENT_CONTEXT_CLIENT_FILTERS) { clientFilters ->
                            clientFilters.writeMessage(fieldNumber = CLIENT_FILTERS_FILTER) { filter ->
                                filter.writeInt32(fieldNumber = FILTER_FILTER_TYPE, value = AUTO_FILTER)
                            }
                        }
                    }
                }

                objectsRequest.writeMessage(fieldNumber = OBJECTS_REQUEST_IMAGE_DATA) { imageData ->
                    imageData.writeMessage(fieldNumber = IMAGE_DATA_PAYLOAD) { payload ->
                        payload.writeBytes(fieldNumber = IMAGE_PAYLOAD_BYTES, value = image.bytes)
                    }
                    imageData.writeMessage(fieldNumber = IMAGE_DATA_METADATA) { metadata ->
                        metadata.writeInt32(fieldNumber = IMAGE_METADATA_WIDTH, value = image.width)
                        metadata.writeInt32(fieldNumber = IMAGE_METADATA_HEIGHT, value = image.height)
                    }
                }
            }
        }.toByteArray()
    }

    private fun executeRequest(payload: ByteArray): ByteArray {
        val connection = (URL(LENS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", CONTENT_TYPE_PROTOBUF)
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Sec-Fetch-Mode", "no-cors")
            setRequestProperty("Sec-Fetch-Dest", "empty")
        }

        return try {
            connection.outputStream.use { output ->
                output.write(payload)
            }

            val statusCode = connection.responseCode
            val responseBytes = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { input -> input.readBytes() }
                ?: ByteArray(0)

            if (statusCode !in 200..299) {
                val bodyPreview = responseBytes.toString(Charsets.UTF_8).take(256)
                throw IOException("GLens request failed with HTTP $statusCode: $bodyPreview")
            }

            responseBytes
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponsePage(responseBytes: ByteArray): GlensPageResult {
        val reader = ProtoReader(responseBytes)
        val allLines = mutableListOf<ParsedLine>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == SERVER_RESPONSE_OBJECTS_RESPONSE && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                allLines += parseObjectsResponse(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        // Full-page furigana filter (across all text-layout blocks)
        val verticalLines = allLines.filter { it.isVertical && it.hasJpText }
        val horizontalLines = allLines.filter { !it.isVertical && it.hasJpText }
        val nonJpLines = allLines.filter { !it.hasJpText }

        val filteredVertical = filterRuby(
            verticalLines.sortedByDescending { it.centerX + it.width / 2f },
            isVertical = true,
        )
        val filteredHorizontal = filterRuby(
            horizontalLines.sortedBy { it.centerY },
            isVertical = false,
        )
        val filteredLines = filteredVertical + filteredHorizontal + nonJpLines

        val bubbles = mergeIntoBubbles(filteredLines)
        val regions = bubbles
            .mapIndexedNotNull { index, bubble -> bubble.toRegion(index) }
            .filter { it.text.isNotBlank() }

        return GlensPageResult(
            text = regions.joinToString(separator = " ") { it.text }.trim(),
            regions = regions,
        )
    }

    private fun parseObjectsResponse(objectsBytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(objectsBytes)
        val paragraphs = mutableListOf<ParsedLine>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == OBJECTS_RESPONSE_TEXT && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                paragraphs += parseText(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
    }

    private fun parseText(textBytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(textBytes)
        val paragraphs = mutableListOf<ParsedLine>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == TEXT_LAYOUT && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                paragraphs += parseTextLayout(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return paragraphs
    }

    private fun parseTextLayout(layoutBytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(layoutBytes)
        val allLines = mutableListOf<ParsedLine>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == TEXT_LAYOUT_PARAGRAPH && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                allLines += collectParagraphLines(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return allLines
    }

    /** Parses one paragraph proto, returning its [ParsedLine] objects without filtering. */
    private fun collectParagraphLines(paragraphBytes: ByteArray): List<ParsedLine> {
        val reader = ProtoReader(paragraphBytes)
        val lines = mutableListOf<ParsedLine>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == PARAGRAPH_LINE && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                val parsedLine = parseLine(reader.readBytes())
                if (parsedLine.text.isNotBlank()) {
                    lines += parsedLine
                }
            } else {
                reader.skipField(wireType)
            }
        }

        return lines
    }

    private fun parseLine(lineBytes: ByteArray): ParsedLine {
        val reader = ProtoReader(lineBytes)
        val words = mutableListOf<ParsedWord>()

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == LINE_WORD && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                words += parseWord(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        val plainText = words.joinToString("") { it.text }
        val text = words.joinToString(separator = "") { w -> w.text + w.separator }.trim()

        val isVertical = isVerticalLine(words)

        val positioned = words.filter { it.centerX > 0f || it.centerY > 0f }
        var centerX = 0f
        var centerY = 0f
        var width = 0f
        var height = 0f

        if (positioned.isNotEmpty()) {
            val minX = positioned.minOf { it.centerX - it.width / 2f }
            val maxX = positioned.maxOf { it.centerX + it.width / 2f }
            val minY = positioned.minOf { it.centerY - it.height / 2f }
            val maxY = positioned.maxOf { it.centerY + it.height / 2f }
            width = maxX - minX
            height = maxY - minY
            centerX = minX + width / 2f
            centerY = minY + height / 2f
        }

        val sizes = words.mapNotNull { w ->
            val s = if (isVertical) w.width else w.height
            s.takeIf { it > 0f }
        }.sorted()
        val characterSize = if (sizes.isNotEmpty()) sizes[sizes.size / 2] else 0f

        return ParsedLine(
            words = words,
            text = text,
            hasJpText = containsJapanese(plainText),
            hasKanji = containsKanji(plainText),
            isVertical = isVertical,
            centerX = centerX,
            centerY = centerY,
            width = width,
            height = height,
            characterSize = characterSize,
        )
    }

    private fun compute1DIntersection(minA: Float, maxA: Float, minB: Float, maxB: Float): Float {
        val overlapStart = maxOf(minA, minB)
        val overlapEnd = minOf(maxA, maxB)
        return maxOf(0f, overlapEnd - overlapStart)
    }

    private fun horizontalOverlapRatio(boxA: ParsedLine, boxB: ParsedLine): Float {
        val intersect = compute1DIntersection(
            boxA.centerX - boxA.width / 2f,
            boxA.centerX + boxA.width / 2f,
            boxB.centerX - boxB.width / 2f,
            boxB.centerX + boxB.width / 2f,
        )
        val smallestWidth = minOf(boxA.width, boxB.width)
        return if (smallestWidth > 0f) intersect / smallestWidth else 0f
    }

    private fun verticalOverlapRatio(boxA: ParsedLine, boxB: ParsedLine): Float {
        val intersect = compute1DIntersection(
            boxA.centerY - boxA.height / 2f,
            boxA.centerY + boxA.height / 2f,
            boxB.centerY - boxB.height / 2f,
            boxB.centerY + boxB.height / 2f,
        )
        val smallestHeight = minOf(boxA.height, boxB.height)
        return if (smallestHeight > 0f) intersect / smallestHeight else 0f
    }

    private fun filterRuby(lines: List<ParsedLine>, isVertical: Boolean): List<ParsedLine> {
        if (lines.none { it.hasJpText }) return lines

        val results = mutableListOf<ParsedLine>()
        var index = 0

        while (index < lines.size) {
            val current = lines[index]
            val next = lines.getOrNull(index + 1)

            if (next == null || !current.hasJpText || !next.hasJpText || current.hasKanji || !next.hasKanji) {
                results.add(current)
                index++
                continue
            }

            // At this point: current has no kanji and next has kanji.
            // current is the ruby (furigana) candidate; next is the kanji base.
            val ruby = current

            val isValidAlignment = if (isVertical) {
                val distH = ruby.centerX - next.centerX
                val minH = kotlin.math.abs(next.width - ruby.width) / 2f
                val maxH = next.width + (ruby.width / 2f)
                val overlapV = verticalOverlapRatio(ruby, next)
                distH > minH && distH < maxH && overlapV > 0.4f
            } else {
                val distV = next.centerY - ruby.centerY
                val minV = kotlin.math.abs(next.height - ruby.height) / 2f
                val maxV = next.height + (ruby.height / 2f)
                val overlapH = horizontalOverlapRatio(ruby, next)
                distV > minV && distV < maxV && overlapH > 0.4f
            }

            val baseSize = if (isVertical) next.characterSize else next.height
            val rubySize = if (isVertical) ruby.characterSize else ruby.height
            val isRubySized = baseSize > 0f && rubySize < baseSize * 0.85f

            if (isValidAlignment && isRubySized) {
                results.add(next)
                index += 2
            } else {
                results.add(current)
                index++
            }
        }
        return results
    }

    private fun mergeIntoBubbles(lines: List<ParsedLine>): List<ParsedLine> {
        if (lines.isEmpty()) return emptyList()

        val verticalLines = lines
            .filter { it.isVertical }
            .sortedByDescending { it.centerX }

        val horizontalLines = lines
            .filter { !it.isVertical }
            .sortedBy { it.centerY }

        return mergeBubbleGroup(verticalLines, isVertical = true) +
            mergeBubbleGroup(horizontalLines, isVertical = false)
    }

    /** Groups a pre-sorted list of same-orientation lines into bubble clusters. */
    private fun mergeBubbleGroup(lines: List<ParsedLine>, isVertical: Boolean): List<ParsedLine> {
        if (lines.isEmpty()) return emptyList()

        val used = BooleanArray(lines.size)
        val bubbles = mutableListOf<ParsedLine>()

        for (i in lines.indices) {
            if (used[i]) continue

            val cluster = mutableListOf(lines[i])
            used[i] = true

            var expanded = true
            while (expanded) {
                expanded = false
                for (j in lines.indices) {
                    if (used[j]) continue
                    if (cluster.any { isSameBubble(it, lines[j], isVertical) }) {
                        cluster += lines[j]
                        used[j] = true
                        expanded = true
                    }
                }
            }

            bubbles += if (cluster.size == 1) {
                cluster[0]
            } else {
                combineBubbleCluster(cluster, isVertical)
            }
        }

        return bubbles
    }

    private fun isSameBubble(a: ParsedLine, b: ParsedLine, isVertical: Boolean): Boolean {
        return if (isVertical) {
            val vOverlap = verticalOverlapRatio(a, b)
            val hGap = kotlin.math.abs(a.centerX - b.centerX) -
                (a.width + b.width) / 2f
            val maxWidth = maxOf(a.width, b.width)
            vOverlap >= 0.4f && hGap <= maxWidth * 2f
        } else {
            val hOverlap = horizontalOverlapRatio(a, b)
            val vGap = kotlin.math.abs(a.centerY - b.centerY) -
                (a.height + b.height) / 2f
            val maxHeight = maxOf(a.height, b.height)
            hOverlap >= 0.3f && vGap <= maxHeight * 2f
        }
    }

    private fun combineBubbleCluster(cluster: List<ParsedLine>, isVertical: Boolean): ParsedLine {
        val sorted = if (isVertical) {
            cluster.sortedWith(
                compareByDescending<ParsedLine> { it.centerX }
                    .thenBy { it.centerY },
            )
        } else {
            cluster.sortedBy { it.centerY }
        }

        val text = sorted.joinToString("\n") { it.text }

        val minX = sorted.minOf { it.centerX - it.width / 2f }
        val maxX = sorted.maxOf { it.centerX + it.width / 2f }
        val minY = sorted.minOf { it.centerY - it.height / 2f }
        val maxY = sorted.maxOf { it.centerY + it.height / 2f }
        val newWidth = maxX - minX
        val newHeight = maxY - minY
        val newCenterX = minX + newWidth / 2f
        val newCenterY = minY + newHeight / 2f

        val representativeWords = sorted.flatMap { it.words }
        val medianCharSize = sorted.map { it.characterSize }.sorted().let { it[it.size / 2] }

        return ParsedLine(
            words = representativeWords,
            text = text,
            hasJpText = sorted.any { it.hasJpText },
            hasKanji = sorted.any { it.hasKanji },
            isVertical = isVertical,
            centerX = newCenterX,
            centerY = newCenterY,
            width = newWidth,
            height = newHeight,
            characterSize = medianCharSize,
        )
    }

    private fun isVerticalLine(words: List<ParsedWord>): Boolean {
        val positioned = words.filter { it.centerX > 0f || it.centerY > 0f }
        if (positioned.size >= 2) {
            val xRange = positioned.maxOf { it.centerX } - positioned.minOf { it.centerX }
            val yRange = positioned.maxOf { it.centerY } - positioned.minOf { it.centerY }
            return yRange > xRange
        }
        // Fallback: compare median word dimensions.
        val heights = words.mapNotNull { it.height.takeIf { h -> h > 0f } }.sorted()
        val widths = words.mapNotNull { it.width.takeIf { w -> w > 0f } }.sorted()
        if (heights.isEmpty() || widths.isEmpty()) return false
        val medH = heights[heights.size / 2]
        val medW = widths[widths.size / 2]
        return medH > medW
    }

    // Simple heuristic, doesn't need to be perfect due to other filtering restrictions
    private fun containsJapanese(text: String): Boolean = text.any { c ->
        val cp = c.code
        cp in 0x3040..0x30FF || // Hiragana + Katakana
            cp in 0x4E00..0x9FFF || // CJK Unified Ideographs (common kanji)
            cp in 0x3400..0x4DBF || // CJK Extension A
            cp in 0xF900..0xFAFF // CJK Compatibility Ideographs
    }

    private fun containsKanji(text: String): Boolean = text.any { c ->
        val cp = c.code
        cp in 0x4E00..0x9FFF || // CJK Unified Ideographs (common kanji)
            cp in 0x3400..0x4DBF || // CJK Extension A
            cp in 0xF900..0xFAFF // CJK Compatibility Ideographs
    }

    private fun parseWord(wordBytes: ByteArray): ParsedWord {
        val reader = ProtoReader(wordBytes)
        var text = ""
        var separator = ""
        var centerX = 0f
        var centerY = 0f
        var width = 0f
        var height = 0f

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            when (field) {
                WORD_PLAIN_TEXT if wireType == WIRE_TYPE_LENGTH_DELIMITED -> {
                    text = reader.readString()
                }
                WORD_SEPARATOR if wireType == WIRE_TYPE_LENGTH_DELIMITED -> {
                    separator = reader.readString()
                }
                WORD_GEOMETRY if wireType == WIRE_TYPE_LENGTH_DELIMITED -> {
                    val box = parseGeometryBox(reader.readBytes())
                    centerX = box.centerX
                    centerY = box.centerY
                    width = box.width
                    height = box.height
                }
                else -> reader.skipField(wireType)
            }
        }

        return ParsedWord(
            text = text,
            separator = separator,
            centerX = centerX,
            centerY = centerY,
            width = width,
            height = height,
        )
    }

    private data class BoundingBoxData(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    private fun parseGeometryBox(geometryBytes: ByteArray): BoundingBoxData {
        val reader = ProtoReader(geometryBytes)
        var box = BoundingBoxData(0f, 0f, 0f, 0f)

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            if (field == GEOMETRY_BOUNDING_BOX && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                box = parseBoundingBox(reader.readBytes())
            } else {
                reader.skipField(wireType)
            }
        }

        return box
    }

    private fun parseBoundingBox(bboxBytes: ByteArray): BoundingBoxData {
        val reader = ProtoReader(bboxBytes)
        var centerX = 0f
        var centerY = 0f
        var width = 0f
        var height = 0f

        while (reader.hasRemaining()) {
            val tag = reader.readTag()
            if (tag == 0) break

            val field = tag ushr 3
            val wireType = tag and WIRE_TYPE_MASK
            when (field) {
                BOUNDING_BOX_CENTER_X if wireType == WIRE_TYPE_32BIT -> centerX = reader.readFloat()
                BOUNDING_BOX_CENTER_Y if wireType == WIRE_TYPE_32BIT -> centerY = reader.readFloat()
                BOUNDING_BOX_WIDTH if wireType == WIRE_TYPE_32BIT -> width = reader.readFloat()
                BOUNDING_BOX_HEIGHT if wireType == WIRE_TYPE_32BIT -> height = reader.readFloat()
                else -> reader.skipField(wireType)
            }
        }

        return BoundingBoxData(centerX = centerX, centerY = centerY, width = width, height = height)
    }

    /**
     * Intermediate representation of a parsed word used during furigana filtering.
     * Discarded after [parseLine] assembles the final string.
     * Coords are normalized to [0, 1] domain.
     */
    private data class ParsedWord(
        val text: String,
        val separator: String,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
    )

    private data class ParsedLine(
        val words: List<ParsedWord>,
        val text: String,
        val hasJpText: Boolean,
        val hasKanji: Boolean,
        val isVertical: Boolean,
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val characterSize: Float,
    )

    internal data class GlensPageResult(
        val text: String,
        val regions: List<OcrRegion>,
    )

    private fun ParsedLine.toRegion(order: Int): OcrRegion? {
        if (text.isBlank() || width <= 0f || height <= 0f) {
            return null
        }

        val left = (centerX - width / 2f).coerceIn(0f, 1f)
        val top = (centerY - height / 2f).coerceIn(0f, 1f)
        val right = (centerX + width / 2f).coerceIn(0f, 1f)
        val bottom = (centerY + height / 2f).coerceIn(0f, 1f)
        val box = OcrBoundingBox(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )

        if (!box.isValid()) {
            return null
        }

        return OcrRegion(
            order = order,
            text = textPostprocessor.postprocess(text),
            boundingBox = box,
            textOrientation = if (isVertical) OcrTextOrientation.Vertical else OcrTextOrientation.Horizontal,
        )
    }

    private data class PreparedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PreparedImage

            if (width != other.width) return false
            if (height != other.height) return false
            if (!bytes.contentEquals(other.bytes)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    companion object {
        private const val LENS_ENDPOINT = "https://lensfrontend-pa.googleapis.com/v1/crupload"
        private const val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
        private const val API_KEY = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
        private const val DEFAULT_CLIENT_LANGUAGE = "ja"
        private const val DEFAULT_CLIENT_REGION = "Asia/Tokyo"
        private const val MAX_IMAGE_DIMENSION = 1500

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000

        private const val WIRE_TYPE_MASK = 0x7
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2
        private const val WIRE_TYPE_32BIT = 5

        // Request fields
        private const val SERVER_REQUEST_OBJECTS_REQUEST = 1
        private const val OBJECTS_REQUEST_CONTEXT = 1
        private const val OBJECTS_REQUEST_IMAGE_DATA = 3
        private const val REQUEST_CONTEXT_REQUEST_ID = 3
        private const val REQUEST_CONTEXT_CLIENT_CONTEXT = 4
        private const val REQUEST_ID_UUID = 1
        private const val REQUEST_ID_SEQUENCE_ID = 2
        private const val REQUEST_ID_IMAGE_SEQUENCE_ID = 3
        private const val REQUEST_ID_ANALYTICS_ID = 4
        private const val CLIENT_CONTEXT_PLATFORM = 1
        private const val CLIENT_CONTEXT_SURFACE = 2
        private const val CLIENT_CONTEXT_LOCALE_CONTEXT = 4
        private const val CLIENT_CONTEXT_CLIENT_FILTERS = 7
        private const val CLIENT_FILTERS_FILTER = 1
        private const val FILTER_FILTER_TYPE = 1
        private const val AUTO_FILTER = 1
        private const val LOCALE_LANGUAGE = 1
        private const val LOCALE_REGION = 2
        private const val IMAGE_DATA_PAYLOAD = 1
        private const val IMAGE_DATA_METADATA = 3
        private const val IMAGE_PAYLOAD_BYTES = 1
        private const val IMAGE_METADATA_WIDTH = 1
        private const val IMAGE_METADATA_HEIGHT = 2
        private const val PLATFORM_WEB = 3
        private const val SURFACE_CHROMIUM = 4

        // Response fields — text layout hierarchy
        private const val SERVER_RESPONSE_OBJECTS_RESPONSE = 2
        private const val OBJECTS_RESPONSE_TEXT = 3
        private const val TEXT_LAYOUT = 1
        private const val TEXT_LAYOUT_PARAGRAPH = 1
        private const val PARAGRAPH_LINE = 2
        private const val LINE_WORD = 1
        private const val WORD_PLAIN_TEXT = 2
        private const val WORD_SEPARATOR = 3

        // Response fields — geometry
        private const val WORD_GEOMETRY = 4
        private const val GEOMETRY_BOUNDING_BOX = 1
        private const val BOUNDING_BOX_CENTER_X = 1
        private const val BOUNDING_BOX_CENTER_Y = 2
        private const val BOUNDING_BOX_WIDTH = 3
        private const val BOUNDING_BOX_HEIGHT = 4
    }
}

private class ProtoWriter {
    private val output = ByteArrayOutputStream()

    fun writeInt32(fieldNumber: Int, value: Int) {
        writeTag(fieldNumber, wireType = 0)
        writeVarint32(value)
    }

    fun writeUInt64(fieldNumber: Int, value: Long) {
        writeTag(fieldNumber, wireType = 0)
        writeVarint64(value)
    }

    fun writeString(fieldNumber: Int, value: String) {
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray) {
        writeTag(fieldNumber, wireType = 2)
        writeVarint32(value.size)
        output.write(value)
    }

    fun writeMessage(fieldNumber: Int, block: (ProtoWriter) -> Unit) {
        val nested = ProtoWriter().apply(block).toByteArray()
        writeBytes(fieldNumber, nested)
    }

    fun toByteArray(): ByteArray = output.toByteArray()

    private fun writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint32((fieldNumber shl 3) or wireType)
    }

    private fun writeVarint32(value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        output.write(v and 0x7F)
    }

    private fun writeVarint64(value: Long) {
        var v = value
        while ((v and -0x80L) != 0L) {
            output.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        output.write((v and 0x7F).toInt())
    }
}

private class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun hasRemaining(): Boolean = position < bytes.size

    fun readTag(): Int {
        if (!hasRemaining()) return 0
        return readVarint32()
    }

    fun readString(): String = readBytes().toString(Charsets.UTF_8)

    fun readBytes(): ByteArray {
        val length = readVarint32()
        if (length < 0 || position + length > bytes.size) {
            throw IOException("Invalid length-delimited field length: $length")
        }

        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    /**
     * Read a 32-bit little-endian IEEE 754 float (protobuf wire type 5).
     * The caller must consume the tag first and verify wireType == [WIRE_TYPE_32BIT].
     */
    fun readFloat(): Float {
        if (position + 4 > bytes.size) {
            throw IOException("Unexpected end of protobuf while reading float")
        }
        val bits = (bytes[position ].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8) or
            ((bytes[position + 2].toInt() and 0xFF) shl 16) or
            ((bytes[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return java.lang.Float.intBitsToFloat(bits)
    }

    fun skipField(wireType: Int) {
        when (wireType) {
            0 -> readVarint64()
            1 -> skipBytes(8)
            2 -> {
                val length = readVarint32()
                skipBytes(length)
            }
            5 -> skipBytes(4)
            else -> throw IOException("Unsupported protobuf wire type: $wireType")
        }
    }

    private fun skipBytes(count: Int) {
        if (count < 0 || position + count > bytes.size) {
            throw IOException("Invalid skip length: $count")
        }
        position += count
    }

    private fun readVarint32(): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32")
            val b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
        }

        // Read and discard extra bytes for malformed/int64 varints.
        repeat(5) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint32 overflow")
            if ((bytes[position++].toInt() and 0x80) == 0) return result
        }
        throw IOException("Malformed varint32")
    }

    private fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (!hasRemaining()) throw IOException("Unexpected end of protobuf while reading varint64")
            val b = bytes[position++].toLong() and 0xFFL
            result = result or ((b and 0x7FL) shl shift)
            if ((b and 0x80L) == 0L) return result
            shift += 7
        }
        throw IOException("Malformed varint64")
    }
}
