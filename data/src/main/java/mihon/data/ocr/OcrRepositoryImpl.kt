package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.system.logcat
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * OCR repository implementation that manages engine selection, page scanning, and OCR cache.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val preferenceStore = AndroidPreferenceStore(context)
    private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.LEGACY)

    private val environmentResult by lazy {
        runCatching { Environment.create() }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) {
                    "LiteRT environment unavailable; local OCR engines will fall back"
                }
            }
    }

    private val textPostprocessor by lazy { TextPostprocessor() }
    private val cacheStore by lazy { OcrCacheStore(context) }

    private var legacyEngine: LegacyOcrEngine? = null
    private var fastEngine: FastOcrEngine? = null
    private var glensEngine: GlensOcrEngine? = null
    private var detEngine: DetOcrEngine? = null

    private val engineLocks = OcrEngineLocks()
    private val cleanupMutex = Mutex()
    private val sessionMutex = Mutex()
    private val operationMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskQueue = PrioritizedTaskQueue(scope) {
        scope.launch {
            performDeferredCleanupIfIdle()
        }
    }

    private var cleanupRequested = false

    private var activeScanSessions = 0
    private var activeOperations = 0

    internal enum class EngineType {
        LEGACY,
        FAST,
        GLENS,
    }

    private fun selectedEngineType(): EngineType {
        return when (ocrModelPref.get()) {
            OcrModel.LEGACY -> EngineType.LEGACY
            OcrModel.FAST -> EngineType.FAST
            OcrModel.GLENS -> EngineType.GLENS
        }
    }

    private fun isConnectivityFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is SocketTimeoutException ||
                current.message?.contains("Unable to resolve host", ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun fallbackFor(type: EngineType): EngineType {
        return when (type) {
            EngineType.GLENS -> EngineType.FAST
            EngineType.FAST -> EngineType.GLENS
            EngineType.LEGACY -> EngineType.GLENS
        }
    }

    private fun requireEnvironment(): Environment {
        return environmentResult.getOrElse { cause ->
            throw OcrException.InitializationError(cause)
        }
    }

    private fun localOcrAvailable(): Boolean {
        return environmentResult.isSuccess
    }

    private fun engineFor(type: EngineType): OcrEngine {
        return when (type) {
            EngineType.FAST -> {
                fastEngine ?: FastOcrEngine(context, requireEnvironment(), textPostprocessor).also {
                    fastEngine = it
                }
            }
            EngineType.LEGACY -> {
                legacyEngine ?: LegacyOcrEngine(context, requireEnvironment(), textPostprocessor).also {
                    legacyEngine = it
                }
            }
            EngineType.GLENS -> {
                glensEngine ?: GlensOcrEngine().also {
                    glensEngine = it
                }
            }
        }
    }

    private fun detectionEngine(): DetOcrEngine {
        return detEngine ?: (
            if (localOcrAvailable()) {
                UnavailableDetOcrEngine() // TODO: replace with real DetOcrEngine with a local model
            } else {
                UnavailableDetOcrEngine()
            }
            ).also {
            detEngine = it
        }
    }

    private suspend fun recognizeWithEngine(type: EngineType, image: Bitmap): String {
        return engineLocks.withTextEngineLock(type) {
            engineFor(type).recognizeText(image)
        }
    }

    private suspend fun recognizeWithFallback(primary: EngineType, image: Bitmap): String {
        return try {
            recognizeWithEngine(primary, image)
        } catch (primaryError: Throwable) {
            if (primaryError is CancellationException) throw primaryError

            val fallback = fallbackFor(primary)
            if (fallback == primary) {
                throw primaryError
            }

            logcat(LogPriority.WARN, primaryError) {
                "OCR (${primary.name.lowercase()}) failed, falling back to ${fallback.name.lowercase()}"
            }

            try {
                recognizeWithEngine(fallback, image)
            } catch (fallbackError: Throwable) {
                if (fallbackError is CancellationException) throw fallbackError
                primaryError.addSuppressed(fallbackError)
                throw primaryError
            }
        }
    }

    override suspend fun recognizeText(image: OcrImage): String {
        return withActiveOperation {
            submitTask(PrioritizedTaskQueue.Priority.HIGH) {
                image.useBitmap { bitmap ->
                    recognizeWithFallback(selectedEngineType(), bitmap)
                }
            }
        }
    }

    override suspend fun scanPage(
        chapterId: Long,
        pageIndex: Int,
        image: OcrImage,
    ): OcrPageResult {
        return withActiveOperation {
            val result = image.useBitmap { bitmap ->
                when (val selectedModel = ocrModelPref.get()) {
                    OcrModel.GLENS -> scanWithGlens(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        image = bitmap,
                        modelKey = selectedModel,
                    )
                    OcrModel.LEGACY -> scanLocalOrFallback(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        image = bitmap,
                        modelKey = selectedModel,
                        type = EngineType.LEGACY,
                    )
                    OcrModel.FAST -> scanLocalOrFallback(
                        chapterId = chapterId,
                        pageIndex = pageIndex,
                        image = bitmap,
                        modelKey = selectedModel,
                        type = EngineType.FAST,
                    )
                }
            }

            cacheStore.upsert(result)
            result
        }
    }

    override suspend fun getCachedPage(
        chapterId: Long,
        pageIndex: Int,
    ): OcrPageResult? {
        return cacheStore.getPage(
            chapterId = chapterId,
            pageIndex = pageIndex,
        )
    }

    override suspend fun getCachedChapterIds(chapterIds: Collection<Long>): Set<Long> {
        return cacheStore.getCachedChapterIds(
            chapterIds = chapterIds,
        )
    }

    override suspend fun clearCachedChapter(chapterId: Long) {
        cacheStore.clearChapter(chapterId)
    }

    override suspend fun clearCache() {
        cacheStore.clear()
    }

    override suspend fun getCacheSizeBytes(): Long {
        return cacheStore.sizeBytes()
    }

    override suspend fun <T> withScanSession(block: suspend () -> T): T {
        sessionMutex.withLock {
            activeScanSessions++
        }

        return try {
            block()
        } finally {
            sessionMutex.withLock {
                activeScanSessions--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun scanLocalOrFallback(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
        type: EngineType,
    ): OcrPageResult {
        return try {
            scanLocally(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
                type = type,
            )
        } catch (e: OcrException.DetectionUnavailable) {
            logcat(LogPriority.WARN, e) {
                "OCR scanning redirected to glens because local detection is unavailable"
            }
            scanWithGlens(
                chapterId = chapterId,
                pageIndex = pageIndex,
                image = image,
                modelKey = modelKey,
            )
        }
    }

    private suspend fun scanWithGlens(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
    ): OcrPageResult {
        val result = try {
            submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                engineLocks.withTextEngineLock(EngineType.GLENS) {
                    val engine = glensEngine ?: GlensOcrEngine().also {
                        glensEngine = it
                    }
                    engine.recognizePage(image)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isConnectivityFailure(error)) {
                throw OcrException.ConnectionError(error)
            }
            throw error
        }
        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = result.regions,
        )
    }

    private suspend fun scanLocally(
        chapterId: Long,
        pageIndex: Int,
        image: Bitmap,
        modelKey: OcrModel,
        type: EngineType,
    ): OcrPageResult {
        val boxes = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
            engineLocks.withDetectionLock {
                val engine = detectionEngine()
                engine.detectTextRegions(image)
            }
        }
            .filter(OcrBoundingBox::isValid)

        val regions = boxes.mapIndexedNotNull { index, box ->
            val crop = cropBitmap(image, box) ?: return@mapIndexedNotNull null
            try {
                val text = submitTask(PrioritizedTaskQueue.Priority.NORMAL) {
                    recognizeWithEngine(type, crop)
                }.trim()
                if (text.isBlank()) {
                    null
                } else {
                    OcrRegion(
                        order = index,
                        text = text,
                        boundingBox = box,
                        textOrientation = OcrTextOrientation.Horizontal,
                    )
                }
            } finally {
                if (!crop.isRecycled) {
                    crop.recycle()
                }
            }
        }

        return OcrPageResult(
            chapterId = chapterId,
            pageIndex = pageIndex,
            ocrModel = modelKey,
            imageWidth = image.width,
            imageHeight = image.height,
            regions = regions,
        )
    }

    private fun cropBitmap(
        image: Bitmap,
        box: OcrBoundingBox,
    ): Bitmap? {
        val left = (box.left * image.width).toInt().coerceIn(0, image.width - 1)
        val top = (box.top * image.height).toInt().coerceIn(0, image.height - 1)
        val right = (box.right * image.width).toInt().coerceIn(left + 1, image.width)
        val bottom = (box.bottom * image.height).toInt().coerceIn(top + 1, image.height)

        val rect = Rect(left, top, right, bottom)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return null
        }

        return Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
    }

    override fun cleanup() {
        scope.launch {
            cleanupMutex.withLock {
                cleanupRequested = true
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun <T> submitTask(
        priority: PrioritizedTaskQueue.Priority,
        block: suspend () -> T,
    ): T {
        return taskQueue.submit(priority, block)
    }

    private suspend fun <T> withActiveOperation(block: suspend () -> T): T {
        operationMutex.withLock {
            activeOperations++
        }

        return try {
            block()
        } finally {
            operationMutex.withLock {
                activeOperations--
            }
            performDeferredCleanupIfIdle()
        }
    }

    private suspend fun performDeferredCleanupIfIdle() {
        val shouldCleanup = cleanupMutex.withLock {
            if (!cleanupRequested || !taskQueue.isIdle() || hasActiveOperations() || hasActiveScanSessions()) {
                return@withLock false
            }

            cleanupRequested = false
            true
        }

        if (!shouldCleanup) {
            return
        }

        try {
            closeEngines()
            cacheStore.close()
            logcat(LogPriority.INFO) { "OcrRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up OcrRepositoryImpl" }
        }
    }

    private suspend fun <T> OcrImage.useBitmap(
        block: suspend (Bitmap) -> T,
    ): T {
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return try {
            block(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun hasActiveScanSessions(): Boolean {
        return sessionMutex.withLock { activeScanSessions > 0 }
    }

    private suspend fun hasActiveOperations(): Boolean {
        return operationMutex.withLock { activeOperations > 0 }
    }

    private suspend fun closeEngines() {
        engineLocks.withAllLocks {
            legacyEngine?.close()
            legacyEngine = null

            fastEngine?.close()
            fastEngine = null

            glensEngine?.close()
            glensEngine = null

            detEngine?.close()
            detEngine = null
        }
    }
}
