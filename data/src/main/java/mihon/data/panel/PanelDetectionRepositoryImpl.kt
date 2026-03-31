package mihon.data.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.panel.model.PanelDetectionResult
import mihon.domain.panel.repository.PanelDetectionRepository
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.ReadingDirection
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime

class PanelDetectionRepositoryImpl(
    private val context: Context,
) : PanelDetectionRepository {

    private val environment by lazy { Environment.create() }

    private var engine: YoloPanelDetectionEngine? = null
    private val engineMutex = Mutex()

    private suspend fun getEngine(): YoloPanelDetectionEngine {
        return engineMutex.withLock {
            engine ?: YoloPanelDetectionEngine(context, environment).also {
                engine = it
            }
        }
    }

    override suspend fun detectPanels(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult {
        return try {
            getEngine().detectPanels(
                cacheKey = cacheKey,
                image = image,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                direction = direction,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Panel detection failed for $cacheKey" }
            PanelDetectionResult.EMPTY
        }
    }

    override fun cleanup() {
        try {
            engine?.close()
            engine = null
            logcat(LogPriority.INFO) { "PanelDetectionRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up panel detector" }
        }
    }
}

private class YoloPanelDetectionEngine(
    private val context: Context,
    environment: Environment,
) : Closeable {

    private val compiledModel: CompiledModel
    private val inputBuffers: List<TensorBuffer>
    private val outputBuffers: List<TensorBuffer>
    private val inferenceMutex = Mutex()

    private val scratchBitmap = createBitmap(INPUT_SIZE, INPUT_SIZE)
    private val scratchCanvas = Canvas(scratchBitmap)
    private val scratchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        isAntiAlias = true
    }
    private val sourceRect = Rect()
    private val destinationRect = Rect()
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputFloatBuffer = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)

    private val cache = object : LinkedHashMap<String, PanelDetectionResult>(CACHE_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PanelDetectionResult>?): Boolean {
            return size > CACHE_CAPACITY
        }
    }
    private val cacheMutex = Mutex()

    init {
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val options = CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
        }

        compiledModel = CompiledModel.create(
            context.assets,
            MODEL_PATH,
            options,
            environment,
        )
        inputBuffers = compiledModel.createInputBuffers()
        outputBuffers = compiledModel.createOutputBuffers()
        check(inputBuffers.size == 1) { "Panel detector expected 1 input but found ${inputBuffers.size}" }
        check(outputBuffers.isNotEmpty()) { "Panel detector expected at least 1 output buffer" }

        logcat(LogPriority.INFO) {
            "Panel detector initialized (cpuThreads=$cpuThreads, outputs=${outputBuffers.size})"
        }
    }

    suspend fun detectPanels(
        cacheKey: String,
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
    ): PanelDetectionResult {
        cacheMutex.withLock {
            cache[cacheEntryKey(cacheKey, direction)]?.let {
                return it.copy(cacheHit = true)
            }
        }

        var preprocessNanos = 0L
        var inferenceNanos = 0L
        val detections = mutableListOf<ScoredPanel>()

        val totalStart = System.nanoTime()
        inferenceMutex.withLock {
            val preprocessing = preprocessImage(
                image = image,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            )
            preprocessNanos = preprocessing.durationNanos

            inputBuffers[0].writeFloat(inputFloatBuffer)

            inferenceNanos = measureNanoTime {
                compiledModel.run(inputBuffers, outputBuffers)
            }

            val rawOutputs = outputBuffers.map { it.readFloat() }

            detections += parseDetections(
                rawOutputs = rawOutputs,
                mapping = preprocessing.mapping,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
            )
        }
        val totalNanos = System.nanoTime() - totalStart

        val result = buildResult(
            cacheKey = cacheKey,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            direction = direction,
            preprocessNanos = preprocessNanos,
            inferenceNanos = inferenceNanos,
            totalNanos = totalNanos,
            detections = detections,
        )

        cacheMutex.withLock {
            cache[cacheEntryKey(cacheKey, direction)] = result
        }
        return result
    }

    private fun buildResult(
        cacheKey: String,
        originalWidth: Int,
        originalHeight: Int,
        direction: ReadingDirection,
        preprocessNanos: Long,
        inferenceNanos: Long,
        totalNanos: Long,
        detections: List<ScoredPanel>,
    ): PanelDetectionResult {
        val prunedPanels = removeHeavyOverlaps(
            detections
                .filter { it.confidence >= CONFIDENCE_THRESHOLD }
                .filter { it.rect.width() > 0 && it.rect.height() > 0 }
                .filter { isLargeEnough(it.rect, originalWidth, originalHeight) },
        )

        val orderedPanels = sortByReadingOrder(
            rects = prunedPanels.map { it.rect },
            imageHeight = originalHeight,
            direction = direction,
        ).map(::Panel)

        val result = PanelDetectionResult(
            panels = orderedPanels,
            preprocessMillis = preprocessNanos / 1_000_000,
            inferenceMillis = inferenceNanos / 1_000_000,
            totalMillis = totalNanos / 1_000_000,
        )

        logcat(LogPriority.INFO) {
            "Panel detector result key=$cacheKey panels=${result.panels.size} " +
                "preprocessMs=${result.preprocessMillis} inferenceMs=${result.inferenceMillis} totalMs=${result.totalMillis}"
        }

        return result
    }

    private fun preprocessImage(
        image: Bitmap,
        originalWidth: Int,
        originalHeight: Int,
    ): PreprocessResult {
        lateinit var mapping: LetterboxMapping
        val durationNanos = measureNanoTime {
            val pageBounds = findContentBounds(image)
            sourceRect.set(pageBounds)

            scratchCanvas.drawColor(Color.WHITE)

            val cropWidth = pageBounds.width().coerceAtLeast(1)
            val cropHeight = pageBounds.height().coerceAtLeast(1)
            val scale = minOf(INPUT_SIZE / cropWidth.toFloat(), INPUT_SIZE / cropHeight.toFloat())

            val contentWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
            val contentHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
            val padX = ((INPUT_SIZE - contentWidth) / 2f)
            val padY = ((INPUT_SIZE - contentHeight) / 2f)

            destinationRect.set(
                padX.roundToInt(),
                padY.roundToInt(),
                (padX + contentWidth).roundToInt(),
                (padY + contentHeight).roundToInt(),
            )
            scratchCanvas.drawBitmap(image, sourceRect, destinationRect, scratchPaint)

            scratchBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            var outputIndex = 0
            pixelBuffer.forEach { pixel ->
                inputFloatBuffer[outputIndex++] = ((pixel shr 16) and 0xFF) / 255f
                inputFloatBuffer[outputIndex++] = ((pixel shr 8) and 0xFF) / 255f
                inputFloatBuffer[outputIndex++] = (pixel and 0xFF) / 255f
            }

            mapping = LetterboxMapping(
                sampledWidth = image.width,
                sampledHeight = image.height,
                originalWidth = originalWidth,
                originalHeight = originalHeight,
                cropLeft = pageBounds.left,
                cropTop = pageBounds.top,
                scale = scale,
                padX = padX,
                padY = padY,
            )
        }

        return PreprocessResult(
            durationNanos = durationNanos,
            mapping = mapping,
        )
    }

    private fun parseDetections(
        rawOutputs: List<FloatArray>,
        mapping: LetterboxMapping,
        originalWidth: Int,
        originalHeight: Int,
    ): List<ScoredPanel> {
        if (rawOutputs.isEmpty()) return emptyList()

        val preferredValues = rawOutputs.firstOrNull { it.size == PREFERRED_OUTPUT_COUNT * DETECTION_STRIDE }
        if (preferredValues == null) return emptyList()

        return parseCombinedTensor(
            values = preferredValues,
            detectionCount = PREFERRED_OUTPUT_COUNT,
            transposed = false,
            coordinateEncoding = CoordinateEncoding.XYXY,
            normalized = true,
            mapping = mapping,
        )
            .filter { it.confidence >= CONFIDENCE_THRESHOLD }
            .filter { it.rect.width() > 0 && it.rect.height() > 0 }
            .filter { isLargeEnough(it.rect, originalWidth, originalHeight) }
            .let(::removeHeavyOverlaps)
    }

    private fun parseCombinedTensor(
        values: FloatArray,
        detectionCount: Int,
        transposed: Boolean,
        coordinateEncoding: CoordinateEncoding,
        normalized: Boolean,
        mapping: LetterboxMapping,
    ): List<ScoredPanel> {
        if (detectionCount <= 0) return emptyList()

        return (0 until detectionCount).mapNotNull { index ->
            val chunk = FloatArray(DETECTION_STRIDE) { slot ->
                if (transposed) {
                    values[(slot * detectionCount) + index]
                } else {
                    values[(index * DETECTION_STRIDE) + slot]
                }
            }

            parseDetectionChunk(
                x0 = chunk[0],
                y0 = chunk[1],
                x1 = chunk[2],
                y1 = chunk[3],
                confidenceRaw = chunk[4],
                classRaw = chunk[5],
                coordinateEncoding = coordinateEncoding,
                normalized = normalized,
                mapping = mapping,
            )
        }
    }

    private fun parseDetectionChunk(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        confidenceRaw: Float,
        classRaw: Float,
        coordinateEncoding: CoordinateEncoding,
        normalized: Boolean,
        mapping: LetterboxMapping,
    ): ScoredPanel? {
        if (!confidenceRaw.isFinite() || !classRaw.isFinite()) return null

        val classId = classRaw.roundToInt()
        if (classId != PANEL_CLASS_ID || confidenceRaw < CONFIDENCE_THRESHOLD) return null

        val coordinateScale = if (normalized) INPUT_SIZE.toFloat() else 1f
        val scaledX0 = x0 * coordinateScale
        val scaledY0 = y0 * coordinateScale
        val scaledX1 = x1 * coordinateScale
        val scaledY1 = y1 * coordinateScale

        val (left, top, right, bottom) = when (coordinateEncoding) {
            CoordinateEncoding.XYXY -> BoxCorners(
                left = scaledX0,
                top = scaledY0,
                right = scaledX1,
                bottom = scaledY1,
            )
            CoordinateEncoding.XYWH -> {
                val halfWidth = scaledX1 / 2f
                val halfHeight = scaledY1 / 2f
                BoxCorners(
                    left = scaledX0 - halfWidth,
                    top = scaledY0 - halfHeight,
                    right = scaledX0 + halfWidth,
                    bottom = scaledY0 + halfHeight,
                )
            }
        }

        return mapModelRectToOriginal(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = confidenceRaw,
            mapping = mapping,
        )
    }

    private fun mapModelRectToOriginal(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float,
        mapping: LetterboxMapping,
    ): ScoredPanel? {
        val sampledLeft = ((left - mapping.padX) / mapping.scale) + mapping.cropLeft
        val sampledTop = ((top - mapping.padY) / mapping.scale) + mapping.cropTop
        val sampledRight = ((right - mapping.padX) / mapping.scale) + mapping.cropLeft
        val sampledBottom = ((bottom - mapping.padY) / mapping.scale) + mapping.cropTop

        val mappedLeft = (sampledLeft * mapping.originalWidth / mapping.sampledWidth.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalWidth)
        val mappedTop = (sampledTop * mapping.originalHeight / mapping.sampledHeight.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalHeight)
        val mappedRight = (sampledRight * mapping.originalWidth / mapping.sampledWidth.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalWidth)
        val mappedBottom = (sampledBottom * mapping.originalHeight / mapping.sampledHeight.toFloat())
            .roundToInt()
            .coerceIn(0, mapping.originalHeight)

        val rect = Rect(
            minOf(mappedLeft, mappedRight),
            minOf(mappedTop, mappedBottom),
            maxOf(mappedLeft, mappedRight),
            maxOf(mappedTop, mappedBottom),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null

        return ScoredPanel(rect = rect, confidence = confidence)
    }

    private fun removeHeavyOverlaps(panels: List<ScoredPanel>): List<ScoredPanel> {
        if (panels.isEmpty()) return emptyList()

        val sorted = panels.sortedByDescending { it.confidence }
        val kept = mutableListOf<ScoredPanel>()

        sorted.forEach { candidate ->
            if (kept.none { intersectionOverUnion(it.rect, candidate.rect) > MAX_DUPLICATE_IOU }) {
                kept += candidate
            }
        }

        return kept
    }

    private fun sortByReadingOrder(
        rects: List<Rect>,
        imageHeight: Int,
        direction: ReadingDirection,
    ): List<Rect> {
        if (direction == ReadingDirection.VERTICAL) {
            return rects.sortedWith(compareBy<Rect> { it.centerY() }.thenBy { it.centerX() })
        }

        val rowThreshold = imageHeight * ROW_GROUPING_RATIO
        val rows = mutableListOf<MutableList<Rect>>()

        rects.sortedBy { it.centerY() }.forEach { rect ->
            val row = rows.find { existing ->
                abs(existing.first().centerY() - rect.centerY()) <= rowThreshold
            }
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

    private fun isLargeEnough(rect: Rect, width: Int, height: Int): Boolean {
        val area = rect.width().toLong() * rect.height().toLong()
        val imageArea = width.toLong() * height.toLong()
        return imageArea > 0 && area.toFloat() / imageArea.toFloat() >= MIN_PANEL_AREA_RATIO
    }

    private fun findContentBounds(bitmap: Bitmap): Rect {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var left = bitmap.width
        var top = bitmap.height
        var right = 0
        var bottom = 0

        pixels.forEachIndexed { index, pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (maxOf(r, g, b) <= CONTENT_THRESHOLD) return@forEachIndexed

            val x = index % bitmap.width
            val y = index / bitmap.width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }

        if (left >= right || top >= bottom) {
            return Rect(0, 0, bitmap.width, bitmap.height)
        }

        val rect = Rect(left, top, right, bottom)
        val minMeaningfulWidth = (bitmap.width * MIN_CONTENT_SPAN_RATIO).roundToInt()
        val minMeaningfulHeight = (bitmap.height * MIN_CONTENT_SPAN_RATIO).roundToInt()
        return if (rect.width() < minMeaningfulWidth || rect.height() < minMeaningfulHeight) {
            Rect(0, 0, bitmap.width, bitmap.height)
        } else {
            rect
        }
    }

    private fun intersectionOverUnion(first: Rect, second: Rect): Float {
        val intersectionLeft = maxOf(first.left, second.left)
        val intersectionTop = maxOf(first.top, second.top)
        val intersectionRight = minOf(first.right, second.right)
        val intersectionBottom = minOf(first.bottom, second.bottom)

        if (intersectionLeft >= intersectionRight || intersectionTop >= intersectionBottom) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val firstArea = first.width() * first.height()
        val secondArea = second.width() * second.height()
        val union = firstArea + secondArea - intersectionArea

        return if (union == 0) 0f else intersectionArea.toFloat() / union.toFloat()
    }

    private fun cacheEntryKey(
        cacheKey: String,
        direction: ReadingDirection,
    ): String {
        return "$cacheKey:${direction.name}"
    }

    override fun close() {
        scratchBitmap.recycle()
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        compiledModel.close()
        cache.clear()
    }

    private data class PreprocessResult(
        val durationNanos: Long,
        val mapping: LetterboxMapping,
    )

    private data class LetterboxMapping(
        val sampledWidth: Int,
        val sampledHeight: Int,
        val originalWidth: Int,
        val originalHeight: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    private data class ScoredPanel(
        val rect: Rect,
        val confidence: Float,
    )

    private data class BoxCorners(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private enum class CoordinateEncoding {
        XYXY,
        XYWH,
    }

    companion object {
        private const val MODEL_PATH = "panel_detector/model.tflite"
        private const val INPUT_SIZE = 640
        private const val DETECTION_STRIDE = 6
        private const val PREFERRED_OUTPUT_COUNT = 300
        private const val PANEL_CLASS_ID = 0
        private const val CONFIDENCE_THRESHOLD = 0.25f
        private const val MIN_PANEL_AREA_RATIO = 0.02f
        private const val MAX_DUPLICATE_IOU = 0.8f
        private const val ROW_GROUPING_RATIO = 0.15f
        private const val CONTENT_THRESHOLD = 16
        private const val MIN_CONTENT_SPAN_RATIO = 0.25f
        private const val CACHE_CAPACITY = 128
    }
}
