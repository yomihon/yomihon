package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime

/**
 * OCR engine for the legacy model.
 * Supports GPU acceleration with CPU fallback.
 */
internal class LegacyOcrEngine(
    private val context: Context,
    private val environment: Environment,
    private val textPostprocessor: TextPostprocessor,
) : OcrEngine {
    private val encoderModelPath = "ocr/encoder.tflite"
    private val decoderModelPath = "ocr/decoder.tflite"
    private val embeddingsPath = "ocr/embeddings.bin"

    private lateinit var encoderModel: CompiledModel
    private lateinit var decoderModel: CompiledModel

    private lateinit var encoderImageInput: TensorBuffer
    private lateinit var encoderHiddenStatesOutput: TensorBuffer
    private lateinit var decoderHiddenStatesInput: TensorBuffer
    private lateinit var decoderEmbeddingsInput: TensorBuffer
    private lateinit var decoderAttentionMaskInput: TensorBuffer
    private lateinit var decoderLogitsOutput: TensorBuffer

    private lateinit var embeddings: FloatArray

    private val inputIdsArray = IntArray(MAX_SEQUENCE_LENGTH)
    private val pixelsBuffer = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    private val normalizedBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val tokenBuffer = IntArray(MAX_SEQUENCE_LENGTH)
    private val textBuilder = StringBuilder(MAX_SEQUENCE_LENGTH * 2)
    private val embeddingsInputBuffer = FloatArray(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
    private val attentionMaskBuffer = FloatArray(MAX_SEQUENCE_LENGTH)

    private val inferenceMutex = Mutex()
    @Volatile
    private var initialized = false

    companion object {
        private const val IMAGE_SIZE = 224
        private const val NORMALIZATION_FACTOR = 1.0f / (255.0f * 0.5f)
        private const val NORMALIZED_MEAN = 0.5f / 0.5f
        private const val START_TOKEN_ID = 2
        private const val END_TOKEN_ID = 3
        private const val PAD_TOKEN_ID = 0
        private const val SPECIAL_TOKEN_THRESHOLD = 5
        private const val MAX_SEQUENCE_LENGTH = 300
        private const val VOCAB_SIZE = 6144
        private const val HIDDEN_SIZE = 768
    }

    suspend fun ensureInitialized() {
        if (initialized) return
        inferenceMutex.withLock {
            if (!initialized) {
                val ok = init()
                if (!ok) throw OcrException.InitializationError()
            }
        }
    }

    private fun init(): Boolean {
        fun initModels(accelerator: Accelerator) {
            val encoderOptions = CompiledModel.Options(accelerator)
            val decoderOptions = CompiledModel.Options(accelerator)

            encoderModel = CompiledModel.create(
                context.assets,
                encoderModelPath,
                encoderOptions,
                environment,
            )

            decoderModel = CompiledModel.create(
                context.assets,
                decoderModelPath,
                decoderOptions,
                environment,
            )

            val encoderInputBuffers = encoderModel.createInputBuffers()
            val encoderOutputBuffers = encoderModel.createOutputBuffers()
            val decoderInputBuffers = decoderModel.createInputBuffers()
            val decoderOutputBuffers = decoderModel.createOutputBuffers()

            encoderImageInput = encoderInputBuffers[0]
            encoderHiddenStatesOutput = encoderOutputBuffers[0]

            decoderHiddenStatesInput = decoderInputBuffers[0]
            decoderAttentionMaskInput = decoderInputBuffers[1]
            decoderEmbeddingsInput = decoderInputBuffers[2]

            decoderLogitsOutput = decoderOutputBuffers[0]
        }

        return try {
            embeddings = context.assets.open(embeddingsPath).use { stream ->
                val bytes = stream.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(buffer.remaining()).apply { buffer.get(this) }
            }

            try {
                initModels(Accelerator.GPU)
                logcat(LogPriority.INFO) { "OCR (legacy) models initialized (GPU)" }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to initialize OCR (legacy) models with GPU" }
                closeInternal()
                initModels(Accelerator.CPU)
                logcat(LogPriority.INFO) { "OCR (legacy) models initialized (CPU)" }
            }

            initialized = true
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize OCR (legacy) models" }
            closeInternal()
            false
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        ensureInitialized()

        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val preprocessTime = measureNanoTime {
                preprocessImage(image)
            }
            logcat(LogPriority.INFO) { "OCR(legacy) Runtime: preprocessImage took ${preprocessTime / 1_000_000} ms" }

            val encoderHiddenStates: FloatArray
            val encoderTime = measureNanoTime {
                encoderHiddenStates = runEncoder()
            }
            logcat(LogPriority.INFO) { "OCR(legacy) Runtime: runEncoder took ${encoderTime / 1_000_000} ms" }

            val tokenCount: Int
            val decoderTime = measureNanoTime {
                tokenCount = runDecoder(encoderHiddenStates)
            }
            logcat(LogPriority.INFO) { "OCR(legacy) Runtime: runDecoder took ${decoderTime / 1_000_000} ms" }

            val decodedText: String
            val decodeTokensTime = measureNanoTime {
                decodedText = decodeTokens(tokenBuffer, tokenCount)
            }
            logcat(LogPriority.INFO) {
                "OCR(legacy) Runtime: decodeTokens took ${decodeTokensTime / 1_000_000} ms for ${decodedText.length + 2} tokens"
            }

            decodedText
        }

        val postprocessedText: String
        val postprocessTime = measureNanoTime {
            postprocessedText = textPostprocessor.postprocess(rawText)
        }
        logcat(LogPriority.INFO) { "OCR(legacy) Runtime: postprocess took ${postprocessTime / 1_000_000} ms" }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR(legacy) Runtime: recognizeText total time: $totalTime ms" }

        return postprocessedText
    }

    private fun preprocessImage(bitmap: Bitmap) {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        val workingBitmap = when {
            needsConversion && needsResize -> {
                val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val scaled = converted.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
                if (scaled !== converted) converted?.recycle()
                scaled
            }
            needsConversion -> bitmap.copy(Bitmap.Config.ARGB_8888, false)
            needsResize -> bitmap.scale(IMAGE_SIZE, IMAGE_SIZE, filter = true)
            else -> bitmap
        }

        try {
            if (workingBitmap == null) return

            workingBitmap.getPixels(pixelsBuffer, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)
            normalizePixels(pixelsBuffer, normalizedBuffer)
            encoderImageInput.writeFloat(normalizedBuffer)
        } finally {
            if (workingBitmap !== bitmap) {
                workingBitmap?.recycle()
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun normalizePixels(pixels: IntArray, output: FloatArray) {
        var outIndex = 0
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)

            output[outIndex++] = r * NORMALIZATION_FACTOR - NORMALIZED_MEAN
            output[outIndex++] = g * NORMALIZATION_FACTOR - NORMALIZED_MEAN
            output[outIndex++] = b * NORMALIZATION_FACTOR - NORMALIZED_MEAN
        }
    }

    private fun runEncoder(): FloatArray {
        val inputBuffers = listOf(encoderImageInput)
        val outputBuffers = listOf(encoderHiddenStatesOutput)
        encoderModel.run(inputBuffers, outputBuffers)
        return encoderHiddenStatesOutput.readFloat()
    }

    private fun runDecoder(encoderHiddenStates: FloatArray): Int {
        val tokenIds = tokenBuffer
        tokenIds[0] = START_TOKEN_ID
        var tokenCount = 1

        inputIdsArray.fill(PAD_TOKEN_ID)
        inputIdsArray[0] = START_TOKEN_ID

        embeddingsInputBuffer.fill(0f)
        attentionMaskBuffer.fill(0f)

        updateEmbedding(START_TOKEN_ID, 0)
        attentionMaskBuffer[0] = 1.0f

        var totalInferenceTime = 0L

        @Suppress("UNUSED_PARAMETER")
        for (step in 0 until MAX_SEQUENCE_LENGTH - 1) {
            decoderHiddenStatesInput.writeFloat(encoderHiddenStates)
            decoderEmbeddingsInput.writeFloat(embeddingsInputBuffer)
            decoderAttentionMaskInput.writeFloat(attentionMaskBuffer)

            val decoderInputs = listOf(decoderHiddenStatesInput, decoderAttentionMaskInput, decoderEmbeddingsInput)
            val decoderOutputs = listOf(decoderLogitsOutput)

            val logits: FloatArray

            totalInferenceTime += measureNanoTime {
                decoderModel.run(decoderInputs, decoderOutputs)
                logits = decoderLogitsOutput.readFloat()
            }
            val nextToken = findMaxLogitToken(logits, tokenCount)

            if (nextToken < 0 || nextToken == END_TOKEN_ID) {
                break
            }

            val nextIndex = tokenCount
            tokenIds[nextIndex] = nextToken
            inputIdsArray[nextIndex] = nextToken

            updateEmbedding(nextToken, nextIndex)
            attentionMaskBuffer[nextIndex] = 1.0f

            tokenCount++
            if (tokenCount >= MAX_SEQUENCE_LENGTH) {
                break
            }
        }

        logcat(LogPriority.INFO) { "OCR Runtime: decoderModel.run sub-time took ${totalInferenceTime / 1_000_000} ms" }

        return tokenCount
    }

    private fun updateEmbedding(tokenId: Int, index: Int) {
        val embedOffset = tokenId * HIDDEN_SIZE
        val outputOffset = index * HIDDEN_SIZE
        System.arraycopy(embeddings, embedOffset, embeddingsInputBuffer, outputOffset, HIDDEN_SIZE)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun findMaxLogitToken(logits: FloatArray, seqLen: Int): Int {
        val lastTokenPos = seqLen - 1
        val logitsOffset = lastTokenPos * VOCAB_SIZE

        var maxLogit = Float.NEGATIVE_INFINITY
        var maxToken = PAD_TOKEN_ID

        for (vocabIdx in 0 until VOCAB_SIZE) {
            val logit = logits[logitsOffset + vocabIdx]
            if (logit > maxLogit) {
                maxLogit = logit
                maxToken = vocabIdx
            }
        }

        return maxToken
    }

    private fun decodeTokens(tokenIds: IntArray, tokenCount: Int): String {
        val text = textBuilder
        text.setLength(0)

        for (index in 0 until tokenCount) {
            val tokenId = tokenIds[index]

            if (tokenId < SPECIAL_TOKEN_THRESHOLD) continue

            if (tokenId < VOCAB_SIZE) {
                text.append(vocab[tokenId])
            }
        }

        return text.toString()
    }

    override fun close() {
        initialized = false
        closeInternal()
    }

    private fun closeInternal() {
        try {
            if (::encoderImageInput.isInitialized) encoderImageInput.close()
            if (::encoderHiddenStatesOutput.isInitialized) encoderHiddenStatesOutput.close()
            if (::decoderHiddenStatesInput.isInitialized) decoderHiddenStatesInput.close()
            if (::decoderEmbeddingsInput.isInitialized) decoderEmbeddingsInput.close()
            if (::decoderAttentionMaskInput.isInitialized) decoderAttentionMaskInput.close()
            if (::decoderLogitsOutput.isInitialized) decoderLogitsOutput.close()

            if (::encoderModel.isInitialized) encoderModel.close()
            if (::decoderModel.isInitialized) decoderModel.close()

            logcat(LogPriority.INFO) { "OCR (legacy) models closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing OCR (legacy) models" }
        }
    }
}
