package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import kotlinx.coroutines.cancel
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OCR repository implementation using native LiteRT inference.
 *
 * This class manages the lifecycle of the native OCR engine and provides
 * thread-safe text recognition from bitmap images.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {

    private val inferenceMutex = Mutex()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val initDeferred: Deferred<Boolean>
    private val initialized = AtomicBoolean(false)

    companion object {
        private const val IMAGE_SIZE = 224
        private const val NS_TO_MS = 1_000_000L

        init {
            // Load the GPU accelerator library first (if available)
            // This must be done before loading yomihon_ocr so its symbols can be used
            try {
                val startLibLoad = System.nanoTime()
                System.loadLibrary("LiteRtOpenClAccelerator")
                val ms = (System.nanoTime() - startLibLoad) / NS_TO_MS
                logcat(LogPriority.INFO) { "Loaded LiteRtOpenClAccelerator.so for GPU acceleration (took $ms ms)" }
            } catch (e: UnsatisfiedLinkError) {
                logcat(LogPriority.WARN, e) { "GPU accelerator library not available: ${e.message}" }
            }

            // Now load the main library
            try {
                val startLoadMain = System.nanoTime()
                System.loadLibrary("yomihon_ocr")
                val ms = (System.nanoTime() - startLoadMain) / NS_TO_MS
                logcat(LogPriority.INFO) { "Loaded yomihon_ocr main native library (took $ms ms)" }
            } catch (e: UnsatisfiedLinkError) {
                logcat(LogPriority.ERROR, e) { "Failed to load yomihon_ocr native library: ${e.message}" }
                throw e
            }
        }
    }

    init {
        val cacheDir = context.cacheDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        logcat(LogPriority.INFO) { "Native library directory: $nativeLibDir" }

        initDeferred = scope.async {
            val initStartNanos = System.nanoTime()
            val success = nativeOcrInit(context.assets, cacheDir, nativeLibDir)
            val initDurationMs = (System.nanoTime() - initStartNanos) / NS_TO_MS

            if (!success) {
                logcat(LogPriority.ERROR) { "Native OCR engine failed to initialize (took $initDurationMs ms)" }
            } else {
                initialized.set(true)
                logcat(LogPriority.INFO) { "Native OCR engine initialized successfully (took $initDurationMs ms)" }
            }
            success
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        // Wait for initialization to complete
        if (!initDeferred.await()) {
            throw OcrException.InitializationError()
        }

        val result = inferenceMutex.withLock {
            check(!image.isRecycled) { "Input bitmap is recycled" }

            val prepStart = System.nanoTime()
            val workingBitmap = prepareImage(image)
            val prepMs = (System.nanoTime() - prepStart) / NS_TO_MS
            if (prepMs > 0) {
                // Log only if there was measurable preparation time to reduce noise
                logcat(LogPriority.INFO) { "OCR: prepareImage took $prepMs ms" }
                logcat(LogPriority.INFO) { "app.yomihon.dev: OCR Prep: prepareImage took $prepMs ms" }
            }

            try {
                val recognizedText = nativeRecognizeText(workingBitmap)

                if (recognizedText.isEmpty()) {
                    logcat(LogPriority.WARN) { "OCR returned empty text" }
                }

                recognizedText
            } finally {
                // Clean up working bitmap if we created a new one
                if (workingBitmap !== image && !workingBitmap.isRecycled) {
                    workingBitmap.recycle()
                }
            }
        }

        return result
    }

    /**
     * Prepare the input image for OCR by converting to the correct size and format.
     * Returns the original bitmap if no conversion is needed.
     */
    private fun prepareImage(bitmap: Bitmap): Bitmap {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        return when {
            needsConversion && needsResize -> {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Failed to convert bitmap to ARGB_8888")
                val scaled = converted.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
                if (scaled !== converted) {
                    converted.recycle()
                }
                scaled
            }
            needsConversion -> {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IllegalStateException("Failed to convert bitmap to ARGB_8888")
            }
            needsResize -> bitmap.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
            else -> bitmap
        }
    }

    override fun close() {
        runBlocking {
            if (initDeferred.isActive) {
                try { initDeferred.join() } catch(e: Exception) {}
            }
            inferenceMutex.withLock {
                if (initialized.getAndSet(false)) {
                    nativeOcrClose()
                }
            }
        }
        scope.cancel()
    }

    // Native methods for C++ inference
    private external fun nativeOcrInit(
        assetManager: android.content.res.AssetManager,
        cacheDir: String,
        nativeLibDir: String
    ): Boolean

    private external fun nativeRecognizeText(bitmap: Bitmap): String

    private external fun nativeOcrClose()
}
