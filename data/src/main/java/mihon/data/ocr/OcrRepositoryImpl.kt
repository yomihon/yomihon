package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import androidx.core.graphics.scale
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.repository.OcrRepository
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.util.system.logcat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime
import androidx.core.graphics.createBitmap

class OcrRepositoryImpl(
    private val context: Context,
) : OcrRepository {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val preferenceStore = AndroidPreferenceStore(context)
    private val ocrModelPref = preferenceStore.getEnum("pref_ocr_model", OcrModel.LEGACY)

    private val qualityInitDeferred: Deferred<Boolean> by lazy {
        scope.async {
            initQuality()
        }
    }
    private val qualityInitialized = AtomicBoolean(false)

    private val fastInitDeferred: Deferred<Boolean> by lazy {
        scope.async {
            initFast()
        }
    }
    private val fastInitialized = AtomicBoolean(false)

    private val encoderModelPath: String = "ocr/encoder.tflite"
    private val decoderModelPath: String = "ocr/decoder.tflite"
    private val embeddingsPath: String = "ocr/embeddings.bin"

    private val fastEncoderModelPath: String = "ocr_fast/encoder.tflite"
    private val fastDecoderModelPath: String = "ocr_fast/decoder.tflite"
    private val environment = Environment.create()
    // Quality model
    private lateinit var encoderModel: CompiledModel
    private lateinit var decoderModel: CompiledModel
    private val textPostprocessor: TextPostprocessor = TextPostprocessor()

    // Fast model
    private lateinit var fastEncoderModel: CompiledModel
    private lateinit var fastDecoderModel: CompiledModel

    private lateinit var encoderImageInput: TensorBuffer
    private lateinit var encoderHiddenStatesOutput: TensorBuffer
    private lateinit var decoderHiddenStatesInput: TensorBuffer
    private lateinit var decoderEmbeddingsInput: TensorBuffer
    private lateinit var decoderAttentionMaskInput: TensorBuffer
    private lateinit var decoderLogitsOutput: TensorBuffer

    private lateinit var fastEncoderImageInput: TensorBuffer
    private lateinit var fastEncoderHiddenStatesOutput: TensorBuffer

    // Fast decoder signature buffers (created once and reused)
    private lateinit var fastDecoderInitEncoderStatesInput: TensorBuffer
    private lateinit var fastDecoderInitInputIdsInput: TensorBuffer
    private lateinit var fastDecoderInitLogitsOutput: TensorBuffer
    private lateinit var fastDecoderInitSelfKSliceOutput: TensorBuffer
    private lateinit var fastDecoderInitSelfVSliceOutput: TensorBuffer
    private lateinit var fastDecoderInitCrossKOutput: TensorBuffer
    private lateinit var fastDecoderInitCrossVOutput: TensorBuffer

    private lateinit var fastDecoderStepEncoderStatesInput: TensorBuffer
    private lateinit var fastDecoderStepInputIdsInput: TensorBuffer
    private lateinit var fastDecoderStepPositionIdsInput: TensorBuffer
    private lateinit var fastDecoderStepSelfKCacheInput: TensorBuffer
    private lateinit var fastDecoderStepSelfVCacheInput: TensorBuffer
    private lateinit var fastDecoderStepCrossKInput: TensorBuffer
    private lateinit var fastDecoderStepCrossVInput: TensorBuffer
    private lateinit var fastDecoderStepLogitsOutput: TensorBuffer
    private lateinit var fastDecoderStepSelfKSliceOutput: TensorBuffer
    private lateinit var fastDecoderStepSelfVSliceOutput: TensorBuffer

    private val inputIdsArray: IntArray = IntArray(MAX_SEQUENCE_LENGTH)
    private val inferenceMutex = Mutex() // Guards shared inference buffers
    private val pixelsBuffer = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    private val normalizedBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val tokenBuffer = IntArray(MAX_SEQUENCE_LENGTH)
    private val textBuilder = StringBuilder(MAX_SEQUENCE_LENGTH * 2)

    private val fastNchwBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val fastTokenBuffer = IntArray(FAST_MAX_SEQUENCE_LENGTH)
    private val fastSelfKCache = FloatArray(FAST_NUM_LAYERS * FAST_NUM_HEADS * FAST_MAX_SEQUENCE_LENGTH * FAST_HEAD_DIM)
    private val fastSelfVCache = FloatArray(FAST_NUM_LAYERS * FAST_NUM_HEADS * FAST_MAX_SEQUENCE_LENGTH * FAST_HEAD_DIM)
    private val fastCrossKCache = FloatArray(FAST_NUM_LAYERS * FAST_NUM_HEADS * FAST_ENCODER_SEQ_LEN * FAST_HEAD_DIM)
    private val fastCrossVCache = FloatArray(FAST_NUM_LAYERS * FAST_NUM_HEADS * FAST_ENCODER_SEQ_LEN * FAST_HEAD_DIM)
    private val fastScalarLong = LongArray(1)
    private val fastScalarPosLong = LongArray(1)
    private val fastTextBuilder = StringBuilder(FAST_MAX_SEQUENCE_LENGTH * 2)

    private lateinit var fastScratchBitmap: Bitmap
    private lateinit var fastScratchCanvas: Canvas
    private lateinit var fastScratchPaint: Paint
    private val fastDstRect = Rect()

    private lateinit var embeddings: FloatArray
    private val embeddingsInputBuffer = FloatArray(MAX_SEQUENCE_LENGTH * HIDDEN_SIZE)
    private val attentionMaskBuffer = FloatArray(MAX_SEQUENCE_LENGTH)

    companion object {
        private const val IMAGE_SIZE = 224
        private const val NORMALIZATION_MEAN = 0.5f
        private const val NORMALIZATION_STD = 0.5f
        private const val NORMALIZATION_FACTOR = 1.0f / (255.0f * NORMALIZATION_STD)
        private const val NORMALIZED_MEAN = NORMALIZATION_MEAN / NORMALIZATION_STD
        private const val START_TOKEN_ID = 2
        private const val END_TOKEN_ID = 3
        private const val PAD_TOKEN_ID = 0
        private const val SPECIAL_TOKEN_THRESHOLD = 5
        private const val MAX_SEQUENCE_LENGTH = 300
        private const val VOCAB_SIZE = 6144
        private const val HIDDEN_SIZE = 768

        private const val FAST_MAX_SEQUENCE_LENGTH = 256
        private const val FAST_ENCODER_SEQ_LEN = 196
        private const val FAST_ENCODER_HIDDEN_SIZE = 256
        private const val FAST_VOCAB_SIZE = 9415
        private const val FAST_START_TOKEN_ID = 2
        private const val FAST_END_TOKEN_ID = 3
        private const val FAST_SPECIAL_TOKEN_THRESHOLD = 5

        private const val FAST_NUM_LAYERS = 4
        private const val FAST_NUM_HEADS = 4
        private const val FAST_HEAD_DIM = 64

        private const val FAST_DECODER_INIT_SIGNATURE = "init"
        private const val FAST_DECODER_STEP_SIGNATURE = "step"

        // LiteRT signature I/O names are the SignatureDef keys (not the tensor names).
        private const val FAST_DECODER_INIT_INPUT_ENCODER_STATES = "args_0"
        private const val FAST_DECODER_INIT_INPUT_IDS = "args_1"

        private const val FAST_DECODER_STEP_INPUT_ENCODER_STATES = "args_0"
        private const val FAST_DECODER_STEP_INPUT_IDS = "args_1"
        private const val FAST_DECODER_STEP_INPUT_POSITION_IDS = "args_2"
        private const val FAST_DECODER_STEP_INPUT_SELF_K_CACHE = "args_3"
        private const val FAST_DECODER_STEP_INPUT_SELF_V_CACHE = "args_4"
        private const val FAST_DECODER_STEP_INPUT_CROSS_K = "args_5"
        private const val FAST_DECODER_STEP_INPUT_CROSS_V = "args_6"

        private const val FAST_DECODER_INIT_OUTPUT_LOGITS = "output_0"
        private const val FAST_DECODER_INIT_OUTPUT_SELF_K_SLICE = "output_1"
        private const val FAST_DECODER_INIT_OUTPUT_SELF_V_SLICE = "output_2"
        private const val FAST_DECODER_INIT_OUTPUT_CROSS_K = "output_3"
        private const val FAST_DECODER_INIT_OUTPUT_CROSS_V = "output_4"

        private const val FAST_DECODER_STEP_OUTPUT_LOGITS = "output_0"
        private const val FAST_DECODER_STEP_OUTPUT_SELF_K_SLICE = "output_1"
        private const val FAST_DECODER_STEP_OUTPUT_SELF_V_SLICE = "output_2"

        private val FAST_DECODER_INIT_INPUT_NAMES = listOf(
            FAST_DECODER_INIT_INPUT_ENCODER_STATES,
            FAST_DECODER_INIT_INPUT_IDS,
        )

        private val FAST_DECODER_INIT_OUTPUT_NAMES = listOf(
            FAST_DECODER_INIT_OUTPUT_LOGITS,
            FAST_DECODER_INIT_OUTPUT_SELF_K_SLICE,
            FAST_DECODER_INIT_OUTPUT_SELF_V_SLICE,
            FAST_DECODER_INIT_OUTPUT_CROSS_K,
            FAST_DECODER_INIT_OUTPUT_CROSS_V,
        )

        private val FAST_DECODER_STEP_INPUT_NAMES = listOf(
            FAST_DECODER_STEP_INPUT_ENCODER_STATES,
            FAST_DECODER_STEP_INPUT_IDS,
            FAST_DECODER_STEP_INPUT_POSITION_IDS,
            FAST_DECODER_STEP_INPUT_SELF_K_CACHE,
            FAST_DECODER_STEP_INPUT_SELF_V_CACHE,
            FAST_DECODER_STEP_INPUT_CROSS_K,
            FAST_DECODER_STEP_INPUT_CROSS_V,
        )

        private val FAST_DECODER_STEP_OUTPUT_NAMES = listOf(
            FAST_DECODER_STEP_OUTPUT_LOGITS,
            FAST_DECODER_STEP_OUTPUT_SELF_K_SLICE,
            FAST_DECODER_STEP_OUTPUT_SELF_V_SLICE,
        )
    }

    private fun createNamedInputBuffer(model: CompiledModel, signature: String, name: String): TensorBuffer {
        return model.createInputBuffer(name, signature)
    }

    private fun createNamedOutputBuffer(model: CompiledModel, signature: String, name: String): TensorBuffer {
        return model.createOutputBuffer(name, signature)
    }

    private fun validateSignatureIo(
        model: CompiledModel,
        signature: String,
        expectedInputs: List<String>,
        expectedOutputs: List<String>,
    ) {
        var ok = true

        for (name in expectedInputs) {
            try {
                val requirements = model.getInputBufferRequirements(name, signature)
                logcat(LogPriority.DEBUG) {
                    "OCR(fast) Decoder signature '$signature' input '$name' requirements: $requirements"
                }
            } catch (e: Exception) {
                ok = false
                logcat(LogPriority.ERROR, e) {
                    "OCR(fast) Decoder signature '$signature' is missing required input '$name' (buffer requirements query failed)"
                }
            }
        }

        for (name in expectedOutputs) {
            try {
                val requirements = model.getOutputBufferRequirements(name, signature)
                logcat(LogPriority.DEBUG) {
                    "OCR(fast) Decoder signature '$signature' output '$name' requirements: $requirements"
                }
            } catch (e: Exception) {
                ok = false
                logcat(LogPriority.ERROR, e) {
                    "OCR(fast) Decoder signature '$signature' is missing required output '$name' (buffer requirements query failed)"
                }
            }
        }

        if (!ok) {
            throw IllegalStateException("OCR(fast) decoder signature '$signature' I/O validation failed")
        }

        logcat(LogPriority.INFO) {
            "OCR(fast) Decoder signature '$signature' I/O validated (inputs=${expectedInputs.size}, outputs=${expectedOutputs.size})"
        }
    }

    private fun useFastModel(): Boolean {
        return ocrModelPref.get() == OcrModel.FAST
    }

    private suspend fun ensureInitialized(isFast: Boolean) {
        val ok = if (isFast) fastInitDeferred.await() else qualityInitDeferred.await()
        if (!ok) throw OcrException.InitializationError()
    }

    private fun initQuality(): Boolean {
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
                logcat(LogPriority.INFO) { "OCR (quality) models initialized (GPU)" }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to initialize OCR (quality) models with GPU" }
                closeQualityInternal()
                initModels(Accelerator.CPU)
                logcat(LogPriority.INFO) { "OCR (quality) models initialized (CPU)" }
            }

            qualityInitialized.set(true)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize OCR (quality) models" }
            closeQualityInternal()
            false
        }
    }

    private fun initFast(): Boolean {
        return try {
            val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val encoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
            }
            val decoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
            }

            fastEncoderModel = CompiledModel.create(
                context.assets,
                fastEncoderModelPath,
                encoderOptions,
                environment,
            )

            fastDecoderModel = CompiledModel.create(
                context.assets,
                fastDecoderModelPath,
                decoderOptions,
                environment,
            )

            validateSignatureIo(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_INPUT_NAMES,
                FAST_DECODER_INIT_OUTPUT_NAMES,
            )
            validateSignatureIo(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_NAMES,
                FAST_DECODER_STEP_OUTPUT_NAMES,
            )

            validateSignatureIo(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_INPUT_NAMES,
                FAST_DECODER_INIT_OUTPUT_NAMES,
            )
            validateSignatureIo(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_NAMES,
                FAST_DECODER_STEP_OUTPUT_NAMES,
            )

            val encInputs = fastEncoderModel.createInputBuffers()
            val encOutputs = fastEncoderModel.createOutputBuffers()
            fastEncoderImageInput = encInputs[0]
            fastEncoderHiddenStatesOutput = encOutputs[0]

            // Decoder signature buffers (named to avoid buffer index mismatch)
            fastDecoderInitEncoderStatesInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_INPUT_ENCODER_STATES,
            )
            fastDecoderInitInputIdsInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_INPUT_IDS,
            )
            fastDecoderInitLogitsOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_OUTPUT_LOGITS,
            )
            fastDecoderInitSelfKSliceOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_OUTPUT_SELF_K_SLICE,
            )
            fastDecoderInitSelfVSliceOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_OUTPUT_SELF_V_SLICE,
            )
            fastDecoderInitCrossKOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_OUTPUT_CROSS_K,
            )
            fastDecoderInitCrossVOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_INIT_SIGNATURE,
                FAST_DECODER_INIT_OUTPUT_CROSS_V,
            )

            fastDecoderStepEncoderStatesInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_ENCODER_STATES,
            )
            fastDecoderStepInputIdsInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_IDS,
            )
            fastDecoderStepPositionIdsInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_POSITION_IDS,
            )
            fastDecoderStepSelfKCacheInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_SELF_K_CACHE,
            )
            fastDecoderStepSelfVCacheInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_SELF_V_CACHE,
            )
            fastDecoderStepCrossKInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_CROSS_K,
            )
            fastDecoderStepCrossVInput = createNamedInputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_INPUT_CROSS_V,
            )
            fastDecoderStepLogitsOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_OUTPUT_LOGITS,
            )
            fastDecoderStepSelfKSliceOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_OUTPUT_SELF_K_SLICE,
            )
            fastDecoderStepSelfVSliceOutput = createNamedOutputBuffer(
                fastDecoderModel,
                FAST_DECODER_STEP_SIGNATURE,
                FAST_DECODER_STEP_OUTPUT_SELF_V_SLICE,
            )

            // Preprocessing scratch
            fastScratchBitmap = createBitmap(IMAGE_SIZE, IMAGE_SIZE)
            fastScratchCanvas = Canvas(fastScratchBitmap)
            fastScratchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                isAntiAlias = true
            }

            fastInitialized.set(true)
            logcat(LogPriority.INFO) { "OCR (fast) models initialized (CPU threads=$cpuThreads)" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize OCR (fast) models" }
            closeFastInternal()
            false
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val useFast = useFastModel()
        ensureInitialized(useFast)

        return if (useFast) {
            recognizeTextFast(image)
        } else {
            recognizeTextQuality(image)
        }
    }

    private suspend fun recognizeTextQuality(image: Bitmap): String {
        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val preprocessTime = measureNanoTime {
                preprocessImage(image, encoderImageInput)
            }
            logcat(LogPriority.INFO) { "OCR(quality) Runtime: preprocessImage took ${preprocessTime / 1_000_000} ms" }

            val encoderHiddenStates: FloatArray
            val encoderTime = measureNanoTime {
                encoderHiddenStates = runEncoder()
            }
            logcat(LogPriority.INFO) { "OCR(quality) Runtime: runEncoder took ${encoderTime / 1_000_000} ms" }

            val tokenCount: Int
            val decoderTime = measureNanoTime {
                tokenCount = runDecoder(encoderHiddenStates)
            }
            logcat(LogPriority.INFO) { "OCR(quality) Runtime: runDecoder took ${decoderTime / 1_000_000} ms" }

            val decodedText: String
            val decodeTokensTime = measureNanoTime {
                decodedText = decodeTokens(tokenBuffer, tokenCount)
            }
            logcat(LogPriority.INFO) {
                "OCR(quality) Runtime: decodeTokens took ${decodeTokensTime / 1_000_000} ms for ${decodedText.length + 2} tokens"
            }

            decodedText
        }

        val postprocessedText: String
        val postprocessTime = measureNanoTime {
            postprocessedText = textPostprocessor.postprocess(rawText)
        }
        logcat(LogPriority.INFO) { "OCR(quality) Runtime: postprocess took ${postprocessTime / 1_000_000} ms" }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR(quality) Runtime: recognizeText total time: $totalTime ms" }

        return postprocessedText
    }

    private suspend fun recognizeTextFast(image: Bitmap): String {
        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val preprocessTime = measureNanoTime {
                preprocessImageFast(image, fastEncoderImageInput)
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: preprocessImage took ${preprocessTime / 1_000_000} ms" }

            val encoderHiddenStates: FloatArray
            val encoderTime = measureNanoTime {
                encoderHiddenStates = runFastEncoder()
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: runEncoder took ${encoderTime / 1_000_000} ms" }

            val tokenCount: Int
            val decoderTime = measureNanoTime {
                tokenCount = runFastDecoder(encoderHiddenStates)
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: runDecoder took ${decoderTime / 1_000_000} ms" }

            val decodedText: String
            val decodeTokensTime = measureNanoTime {
                decodedText = decodeFastTokens(fastTokenBuffer, tokenCount)
            }
            logcat(LogPriority.INFO) {
                "OCR(fast) Runtime: decodeTokens took ${decodeTokensTime / 1_000_000} ms for ${decodedText.length + 2} tokens"
            }

            decodedText
        }

        val postprocessedText: String
        val postprocessTime = measureNanoTime {
            postprocessedText = textPostprocessor.postprocess(rawText)
        }
        logcat(LogPriority.INFO) { "OCR(fast) Runtime: postprocess took ${postprocessTime / 1_000_000} ms" }

        val totalTime = (System.nanoTime() - startTime) / 1_000_000
        logcat(LogPriority.INFO) { "OCR(fast) Runtime: recognizeText total time: $totalTime ms" }

        return postprocessedText
    }

    private fun preprocessImage(bitmap: Bitmap, inputBuffer: TensorBuffer) {
        val needsResize = bitmap.width != IMAGE_SIZE || bitmap.height != IMAGE_SIZE
        val needsConversion = bitmap.config != Bitmap.Config.ARGB_8888

        // Resize and color to strict model input requirement
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

            // Direct pixel processing with pre-allocated arrays
            workingBitmap.getPixels(pixelsBuffer, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            // Normalize directly to output buffer
            normalizePixels(pixelsBuffer, normalizedBuffer)

            inputBuffer.writeFloat(normalizedBuffer)
        } finally {
            // Clean up only if we created a new bitmap
            if (workingBitmap !== bitmap) {
                workingBitmap?.recycle()
            }
        }
    }

    /**
     * Inline function for pixel normalization.
     * Converts RGB pixels to normalized float values.
     */
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

    /**
     * Run encoder and return a persistent FloatArray copy of hidden states.
     * Uses dedicated output buffers created at initialization.
     */
    private fun runEncoder(): FloatArray {
        val inputBuffers = listOf(encoderImageInput)
        val outputBuffers = listOf(encoderHiddenStatesOutput)
        encoderModel.run(inputBuffers, outputBuffers)

        // Read and create persistent copy for decoder
        return encoderHiddenStatesOutput.readFloat()
    }

    private fun runDecoder(encoderHiddenStates: FloatArray): Int {
        val tokenIds = tokenBuffer
        tokenIds[0] = START_TOKEN_ID
        var tokenCount = 1

        // Reset input IDs array to PAD tokens
        inputIdsArray.fill(PAD_TOKEN_ID)
        inputIdsArray[0] = START_TOKEN_ID

        // Clear buffers to ensure no artifacts from previous images
        embeddingsInputBuffer.fill(0f)
        attentionMaskBuffer.fill(0f)

        // Pre-calculate the Start Token embedding/mask
        updateEmbedding(START_TOKEN_ID, 0)
        attentionMaskBuffer[0] = 1.0f

        var totalInferenceTime = 0L

        @Suppress("UNUSED_PARAMETER")
        for (step in 0 until MAX_SEQUENCE_LENGTH - 1) {
            // Write encoder states and input IDs to reused buffers
            decoderHiddenStatesInput.writeFloat(encoderHiddenStates)
            decoderEmbeddingsInput.writeFloat(embeddingsInputBuffer)
            decoderAttentionMaskInput.writeFloat(attentionMaskBuffer)

            val decoderInputs = listOf(decoderHiddenStatesInput, decoderAttentionMaskInput, decoderEmbeddingsInput)
            val decoderOutputs = listOf(decoderLogitsOutput)

            val logits: FloatArray

            // Run inference
            totalInferenceTime += measureNanoTime {
                decoderModel.run(decoderInputs, decoderOutputs)


                logits = decoderLogitsOutput.readFloat()
            }
            val nextToken = findMaxLogitToken(logits, tokenCount)

            // Validate token
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

    /**
    * Only copies the specific slice needed for the current index.
    */
    private fun updateEmbedding(tokenId: Int, index: Int) {
        val embedOffset = tokenId * HIDDEN_SIZE
        val outputOffset = index * HIDDEN_SIZE
        System.arraycopy(embeddings, embedOffset, embeddingsInputBuffer, outputOffset, HIDDEN_SIZE)
    }

    /**
     * Find the token with maximum logit value for the current sequence position.
     */
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
        // Reuse builder to minimize per-call allocations
        val text = textBuilder
        text.setLength(0)

        for (index in 0 until tokenCount) {
            val tokenId = tokenIds[index]

            if (tokenId < SPECIAL_TOKEN_THRESHOLD) continue

            // Bounds check to avoid potential token errors
            if (tokenId < VOCAB_SIZE) {
                text.append(vocab[tokenId])
            }
        }

        return text.toString()
    }

    private fun preprocessImageFast(bitmap: Bitmap, inputBuffer: TensorBuffer) {
        // Draw scaled bitmap into a white 224x224 canvas (aspect-preserving + centered)
        fastScratchCanvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC)

        val sw = bitmap.width.toFloat()
        val sh = bitmap.height.toFloat()
        val scale = minOf(IMAGE_SIZE / sw, IMAGE_SIZE / sh)
        val dw = (sw * scale).toInt().coerceAtLeast(1)
        val dh = (sh * scale).toInt().coerceAtLeast(1)

        val left = (IMAGE_SIZE - dw) / 2
        val top = (IMAGE_SIZE - dh) / 2
        fastDstRect.set(left, top, left + dw, top + dh)

        // Canvas scaling avoids allocating an intermediate scaled Bitmap.
        fastScratchCanvas.drawBitmap(bitmap, null, fastDstRect, fastScratchPaint)

        // Read pixels into shared pre-allocated buffer
        fastScratchBitmap.getPixels(pixelsBuffer, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        // Convert to NCHW float values in [0..1]
        val hw = IMAGE_SIZE * IMAGE_SIZE
        var idx = 0
        for (i in 0 until hw) {
            val p = pixelsBuffer[idx++]
            fastNchwBuffer[i] = ((p shr 16) and 0xFF) * (1f / 255f)
            fastNchwBuffer[hw + i] = ((p shr 8) and 0xFF) * (1f / 255f)
            fastNchwBuffer[2 * hw + i] = (p and 0xFF) * (1f / 255f)
        }

        inputBuffer.writeFloat(fastNchwBuffer)
    }

    private fun runFastEncoder(): FloatArray {
        val inputBuffers = listOf(fastEncoderImageInput)
        val outputBuffers = listOf(fastEncoderHiddenStatesOutput)
        fastEncoderModel.run(inputBuffers, outputBuffers)
        return fastEncoderHiddenStatesOutput.readFloat()
    }

    private fun runFastDecoder(encoderHiddenStates: FloatArray): Int {
        val tokenIds = fastTokenBuffer
        tokenIds[0] = FAST_START_TOKEN_ID
        var tokenCount = 1

        // Reset caches
        fastSelfKCache.fill(0f)
        fastSelfVCache.fill(0f)

        // --- init signature ---
        fastDecoderInitEncoderStatesInput.writeFloat(encoderHiddenStates)
        fastScalarLong[0] = FAST_START_TOKEN_ID.toLong()
        fastDecoderInitInputIdsInput.writeLong(fastScalarLong)

        val initInputs = mapOf(
            FAST_DECODER_INIT_INPUT_ENCODER_STATES to fastDecoderInitEncoderStatesInput,
            FAST_DECODER_INIT_INPUT_IDS to fastDecoderInitInputIdsInput,
        )
        val initOutputs = mapOf(
            FAST_DECODER_INIT_OUTPUT_LOGITS to fastDecoderInitLogitsOutput,
            FAST_DECODER_INIT_OUTPUT_SELF_K_SLICE to fastDecoderInitSelfKSliceOutput,
            FAST_DECODER_INIT_OUTPUT_SELF_V_SLICE to fastDecoderInitSelfVSliceOutput,
            FAST_DECODER_INIT_OUTPUT_CROSS_K to fastDecoderInitCrossKOutput,
            FAST_DECODER_INIT_OUTPUT_CROSS_V to fastDecoderInitCrossVOutput,
        )
        fastDecoderModel.run(initInputs, initOutputs, FAST_DECODER_INIT_SIGNATURE)

        val logits0 = fastDecoderInitLogitsOutput.readFloat()
        val initSelfKSlice = fastDecoderInitSelfKSliceOutput.readFloat()
        val initSelfVSlice = fastDecoderInitSelfVSliceOutput.readFloat()
        val crossK = fastDecoderInitCrossKOutput.readFloat()
        val crossV = fastDecoderInitCrossVOutput.readFloat()

        insertFastKvSlice(fastSelfKCache, initSelfKSlice, seqIndex = 0)
        insertFastKvSlice(fastSelfVCache, initSelfVSlice, seqIndex = 0)

        // Cache cross-attention tensors for the step signature
        System.arraycopy(crossK, 0, fastCrossKCache, 0, minOf(crossK.size, fastCrossKCache.size))
        System.arraycopy(crossV, 0, fastCrossVCache, 0, minOf(crossV.size, fastCrossVCache.size))
        fastDecoderStepCrossKInput.writeFloat(fastCrossKCache)
        fastDecoderStepCrossVInput.writeFloat(fastCrossVCache)

        // First generated token from init logits
        var nextToken = findMaxFastToken(logits0)
        if (nextToken == FAST_END_TOKEN_ID) {
            return tokenCount
        }
        tokenIds[tokenCount++] = nextToken

        var cacheLen = 1 // start token already cached at seqIndex 0
        var currentToken = nextToken

        // --- step loop ---
        while (tokenCount < FAST_MAX_SEQUENCE_LENGTH && cacheLen < FAST_MAX_SEQUENCE_LENGTH) {
            fastDecoderStepEncoderStatesInput.writeFloat(encoderHiddenStates)
            fastScalarLong[0] = currentToken.toLong()
            fastDecoderStepInputIdsInput.writeLong(fastScalarLong)

            // Most exports treat positions as 1-based (0 reserved for PAD).
            // Start token is implicitly position 1, so the first generated token uses position 2.
            fastScalarPosLong[0] = (cacheLen + 1).toLong()
            fastDecoderStepPositionIdsInput.writeLong(fastScalarPosLong)

            fastDecoderStepSelfKCacheInput.writeFloat(fastSelfKCache)
            fastDecoderStepSelfVCacheInput.writeFloat(fastSelfVCache)

            val stepInputs = mapOf(
                FAST_DECODER_STEP_INPUT_ENCODER_STATES to fastDecoderStepEncoderStatesInput,
                FAST_DECODER_STEP_INPUT_IDS to fastDecoderStepInputIdsInput,
                FAST_DECODER_STEP_INPUT_POSITION_IDS to fastDecoderStepPositionIdsInput,
                FAST_DECODER_STEP_INPUT_SELF_K_CACHE to fastDecoderStepSelfKCacheInput,
                FAST_DECODER_STEP_INPUT_SELF_V_CACHE to fastDecoderStepSelfVCacheInput,
                FAST_DECODER_STEP_INPUT_CROSS_K to fastDecoderStepCrossKInput,
                FAST_DECODER_STEP_INPUT_CROSS_V to fastDecoderStepCrossVInput,
            )
            val stepOutputs = mapOf(
                FAST_DECODER_STEP_OUTPUT_LOGITS to fastDecoderStepLogitsOutput,
                FAST_DECODER_STEP_OUTPUT_SELF_K_SLICE to fastDecoderStepSelfKSliceOutput,
                FAST_DECODER_STEP_OUTPUT_SELF_V_SLICE to fastDecoderStepSelfVSliceOutput,
            )

            fastDecoderModel.run(stepInputs, stepOutputs, FAST_DECODER_STEP_SIGNATURE)

            // Insert KV slice for the token we just fed into the model
            val stepSelfKSlice = fastDecoderStepSelfKSliceOutput.readFloat()
            val stepSelfVSlice = fastDecoderStepSelfVSliceOutput.readFloat()
            insertFastKvSlice(fastSelfKCache, stepSelfKSlice, seqIndex = cacheLen)
            insertFastKvSlice(fastSelfVCache, stepSelfVSlice, seqIndex = cacheLen)
            cacheLen++

            val logits = fastDecoderStepLogitsOutput.readFloat()
            nextToken = findMaxFastToken(logits)
            if (nextToken == FAST_END_TOKEN_ID) {
                break
            }

            tokenIds[tokenCount++] = nextToken
            currentToken = nextToken
        }

        return tokenCount
    }

    private fun findMaxFastToken(logits: FloatArray): Int {
        var maxLogit = Float.NEGATIVE_INFINITY
        var maxToken = 0

        val limit = minOf(FAST_VOCAB_SIZE, logits.size)
        for (i in 0 until limit) {
            val logit = logits[i]
            if (logit > maxLogit) {
                maxLogit = logit
                maxToken = i
            }
        }

        return maxToken
    }

    /**
     * Inserts a KV slice shaped [L,1,H,1,D] into a full cache shaped [L,1,H,S,D] at [seqIndex].
     */
    private fun insertFastKvSlice(fullCache: FloatArray, slice: FloatArray, seqIndex: Int) {
        if (seqIndex !in 0 until FAST_MAX_SEQUENCE_LENGTH) return

        var sliceOffset = 0
        for (layer in 0 until FAST_NUM_LAYERS) {
            for (head in 0 until FAST_NUM_HEADS) {
                val dstBase = ((layer * FAST_NUM_HEADS + head) * FAST_MAX_SEQUENCE_LENGTH + seqIndex) * FAST_HEAD_DIM
                System.arraycopy(slice, sliceOffset, fullCache, dstBase, FAST_HEAD_DIM)
                sliceOffset += FAST_HEAD_DIM
            }
        }
    }

    private fun decodeFastTokens(tokenIds: IntArray, tokenCount: Int): String {
        val text = fastTextBuilder
        text.setLength(0)

        for (index in 0 until tokenCount) {
            val tokenId = tokenIds[index]
            if (tokenId < FAST_SPECIAL_TOKEN_THRESHOLD) continue
            if (tokenId in vocabFast.indices) {
                text.append(vocabFast[tokenId])
            }
        }

        return text.toString()
    }

    override fun close() {
        runBlocking {
            inferenceMutex.withLock {
                if (qualityInitialized.getAndSet(false)) closeQualityInternal()
                if (fastInitialized.getAndSet(false)) closeFastInternal()
            }
        }
        scope.cancel()
    }

    private fun closeQualityInternal() {
        try {
            if (::encoderImageInput.isInitialized) encoderImageInput.close()
            if (::encoderHiddenStatesOutput.isInitialized) encoderHiddenStatesOutput.close()
            if (::decoderHiddenStatesInput.isInitialized) decoderHiddenStatesInput.close()
            if (::decoderEmbeddingsInput.isInitialized) decoderEmbeddingsInput.close()
            if (::decoderAttentionMaskInput.isInitialized) decoderAttentionMaskInput.close()
            if (::decoderLogitsOutput.isInitialized) decoderLogitsOutput.close()

            if (::encoderModel.isInitialized) encoderModel.close()
            if (::decoderModel.isInitialized) decoderModel.close()

            logcat(LogPriority.INFO) { "OCR (quality) models closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing OCR (quality) models" }
        }
    }

    private fun closeFastInternal() {
        try {
            if (::fastEncoderImageInput.isInitialized) fastEncoderImageInput.close()
            if (::fastEncoderHiddenStatesOutput.isInitialized) fastEncoderHiddenStatesOutput.close()

            if (::fastDecoderInitEncoderStatesInput.isInitialized) fastDecoderInitEncoderStatesInput.close()
            if (::fastDecoderInitInputIdsInput.isInitialized) fastDecoderInitInputIdsInput.close()
            if (::fastDecoderInitLogitsOutput.isInitialized) fastDecoderInitLogitsOutput.close()
            if (::fastDecoderInitSelfKSliceOutput.isInitialized) fastDecoderInitSelfKSliceOutput.close()
            if (::fastDecoderInitSelfVSliceOutput.isInitialized) fastDecoderInitSelfVSliceOutput.close()
            if (::fastDecoderInitCrossKOutput.isInitialized) fastDecoderInitCrossKOutput.close()
            if (::fastDecoderInitCrossVOutput.isInitialized) fastDecoderInitCrossVOutput.close()

            if (::fastDecoderStepEncoderStatesInput.isInitialized) fastDecoderStepEncoderStatesInput.close()
            if (::fastDecoderStepInputIdsInput.isInitialized) fastDecoderStepInputIdsInput.close()
            if (::fastDecoderStepPositionIdsInput.isInitialized) fastDecoderStepPositionIdsInput.close()
            if (::fastDecoderStepSelfKCacheInput.isInitialized) fastDecoderStepSelfKCacheInput.close()
            if (::fastDecoderStepSelfVCacheInput.isInitialized) fastDecoderStepSelfVCacheInput.close()
            if (::fastDecoderStepCrossKInput.isInitialized) fastDecoderStepCrossKInput.close()
            if (::fastDecoderStepCrossVInput.isInitialized) fastDecoderStepCrossVInput.close()
            if (::fastDecoderStepLogitsOutput.isInitialized) fastDecoderStepLogitsOutput.close()
            if (::fastDecoderStepSelfKSliceOutput.isInitialized) fastDecoderStepSelfKSliceOutput.close()
            if (::fastDecoderStepSelfVSliceOutput.isInitialized) fastDecoderStepSelfVSliceOutput.close()

            if (::fastEncoderModel.isInitialized) fastEncoderModel.close()
            if (::fastDecoderModel.isInitialized) fastDecoderModel.close()

            if (::fastScratchBitmap.isInitialized && !fastScratchBitmap.isRecycled) {
                fastScratchBitmap.recycle()
            }

            logcat(LogPriority.INFO) { "OCR (fast) models closed successfully" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Error closing OCR (fast) models" }
        }
    }
}
