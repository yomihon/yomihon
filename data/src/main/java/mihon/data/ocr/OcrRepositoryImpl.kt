package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.system.logcat

/**
 * OCR repository implementation that manages engine selection based on user preference.
 */
class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val preferenceStore = AndroidPreferenceStore(context)
    private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.LEGACY)

    // Lazy initialization to avoid startup overhead
    private val environment by lazy { Environment.create() }
    private val textPostprocessor by lazy { TextPostprocessor() }

    private var legacyEngine: LegacyOcrEngine? = null
    private var fastEngine: FastOcrEngine? = null
    private var glensEngine: GlensOcrEngine? = null

    private val engineMutex = Mutex()

    private enum class EngineType {
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

    private suspend fun getEngine(type: EngineType): OcrEngine {
        return engineMutex.withLock {
            when (type) {
                EngineType.FAST -> {
                    fastEngine ?: FastOcrEngine(context, environment, textPostprocessor).also {
                        fastEngine = it
                    }
                }

                EngineType.LEGACY -> {
                    legacyEngine ?: LegacyOcrEngine(context, environment, textPostprocessor).also {
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
    }

    private fun fallbackFor(type: EngineType): EngineType {
        return when (type) {
            EngineType.GLENS -> EngineType.FAST
            EngineType.FAST -> EngineType.GLENS
            EngineType.LEGACY -> EngineType.GLENS
        }
    }

    private suspend fun recognizeWithEngine(type: EngineType, image: Bitmap): String {
        return getEngine(type).recognizeText(image)
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

    override suspend fun recognizeText(image: Bitmap): String {
        val primary = selectedEngineType()
        return recognizeWithFallback(primary, image)
    }

    override fun cleanup() {
        try {
            legacyEngine?.close()
            legacyEngine = null

            fastEngine?.close()
            fastEngine = null

            glensEngine?.close()
            glensEngine = null

            logcat(LogPriority.INFO) { "OcrRepositoryImpl cleaned up successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error cleaning up OcrRepositoryImpl" }
        }
    }
}
