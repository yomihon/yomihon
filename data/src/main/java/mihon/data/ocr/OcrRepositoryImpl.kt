package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litert.Environment
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

    private val environment = Environment.create()
    private val textPostprocessor = TextPostprocessor()

    private var legacyEngine: LegacyOcrEngine? = null
    private var fastEngine: FastOcrEngine? = null

    private val engineMutex = Mutex()

    private fun useFastModel(): Boolean {
        return ocrModelPref.get() == OcrModel.FAST
    }

    private suspend fun getEngine(): OcrEngine {
        val useFast = useFastModel()
        return engineMutex.withLock {
            if (useFast) {
                fastEngine ?: FastOcrEngine(context, environment, textPostprocessor).also {
                    fastEngine = it
                }
            } else {
                legacyEngine ?: LegacyOcrEngine(context, environment, textPostprocessor).also {
                    legacyEngine = it
                }
            }
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val engine = getEngine()
        return engine.recognizeText(image)
    }

    override fun close() {
        try {
            legacyEngine?.close()
            legacyEngine = null

            fastEngine?.close()
            fastEngine = null

            logcat(LogPriority.INFO) { "OcrRepositoryImpl closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing OcrRepositoryImpl" }
        }
    }
}
