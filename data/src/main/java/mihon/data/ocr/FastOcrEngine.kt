package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import tachiyomi.core.common.util.system.logcat
import kotlin.system.measureNanoTime

/**
 * OCR engine for the fast model.
 * Optimized for ARM CPU with KV-cache based decoding.
 */
internal class FastOcrEngine(
    private val context: Context,
    private val environment: Environment,
    private val textPostprocessor: TextPostprocessor,
) : OcrEngine {
    private val encoderModelPath = "ocr_fast/encoder.tflite"
    private val decoderModelPath = "ocr_fast/decoder.tflite"

    private lateinit var encoderModel: CompiledModel
    private lateinit var decoderModel: CompiledModel

    // Encoder buffers
    private lateinit var encoderImageInput: TensorBuffer
    private lateinit var encoderHiddenStatesOutput: TensorBuffer

    // Decoder init signature buffers
    private lateinit var initEncoderStatesInput: TensorBuffer
    private lateinit var initInputIdsInput: TensorBuffer
    private lateinit var initLogitsOutput: TensorBuffer
    private lateinit var initSelfKSliceOutput: TensorBuffer
    private lateinit var initSelfVSliceOutput: TensorBuffer
    private lateinit var initCrossKOutput: TensorBuffer
    private lateinit var initCrossVOutput: TensorBuffer

    // Decoder step signature buffers
    private lateinit var stepEncoderStatesInput: TensorBuffer
    private lateinit var stepInputIdsInput: TensorBuffer
    private lateinit var stepPositionIdsInput: TensorBuffer
    private lateinit var stepSelfKCacheInput: TensorBuffer
    private lateinit var stepSelfVCacheInput: TensorBuffer
    private lateinit var stepCrossKInput: TensorBuffer
    private lateinit var stepCrossVInput: TensorBuffer
    private lateinit var stepLogitsOutput: TensorBuffer
    private lateinit var stepSelfKSliceOutput: TensorBuffer
    private lateinit var stepSelfVSliceOutput: TensorBuffer

    // Preprocessing scratch
    private lateinit var scratchBitmap: Bitmap
    private lateinit var scratchCanvas: Canvas
    private lateinit var scratchPaint: Paint
    private val dstRect = Rect()

    // Pre-allocated buffers
    private val pixelsBuffer = IntArray(IMAGE_SIZE * IMAGE_SIZE)
    private val nchwBuffer = FloatArray(IMAGE_SIZE * IMAGE_SIZE * 3)
    private val tokenBuffer = IntArray(MAX_SEQUENCE_LENGTH)

    private val selfKCache = FloatArray(SELF_CACHE_FLOATS)
    private val selfVCache = FloatArray(SELF_CACHE_FLOATS)

    private val crossKCache = FloatArray(NUM_LAYERS * NUM_HEADS * ENCODER_SEQ_LEN * HEAD_DIM)
    private val crossVCache = FloatArray(NUM_LAYERS * NUM_HEADS * ENCODER_SEQ_LEN * HEAD_DIM)
    private val scalarLong = LongArray(1)
    private val scalarPosLong = LongArray(1)
    private val textBuilder = StringBuilder(MAX_SEQUENCE_LENGTH * 2)

    // Reused maps to avoid per-inference/per-step allocations
    private lateinit var initInputs: Map<String, TensorBuffer>
    private lateinit var initOutputs: Map<String, TensorBuffer>
    private lateinit var stepInputs: Map<String, TensorBuffer>
    private lateinit var stepOutputs: Map<String, TensorBuffer>

    private val inferenceMutex = Mutex()

    @Volatile
    private var initialized = false

    companion object {
        private const val IMAGE_SIZE = 224
        private const val MAX_SEQUENCE_LENGTH = 256
        private const val ENCODER_SEQ_LEN = 196
        private const val VOCAB_SIZE = 9415
        private const val START_TOKEN_ID = 2
        private const val END_TOKEN_ID = 3
        private const val SPECIAL_TOKEN_THRESHOLD = 5

        private const val NUM_LAYERS = 4
        private const val NUM_HEADS = 4
        private const val HEAD_DIM = 64

        private const val INIT_SIGNATURE = "init"
        private const val STEP_SIGNATURE = "step"

        // Init signature I/O names
        private const val INIT_INPUT_ENCODER_STATES = "args_0"
        private const val INIT_INPUT_IDS = "args_1"
        private const val INIT_OUTPUT_LOGITS = "output_0"
        private const val INIT_OUTPUT_SELF_K_SLICE = "output_1"
        private const val INIT_OUTPUT_SELF_V_SLICE = "output_2"
        private const val INIT_OUTPUT_CROSS_K = "output_3"
        private const val INIT_OUTPUT_CROSS_V = "output_4"

        // Step signature I/O names
        private const val STEP_INPUT_ENCODER_STATES = "args_0"
        private const val STEP_INPUT_IDS = "args_1"
        private const val STEP_INPUT_POSITION_IDS = "args_2"
        private const val STEP_INPUT_SELF_K_CACHE = "args_3"
        private const val STEP_INPUT_SELF_V_CACHE = "args_4"
        private const val STEP_INPUT_CROSS_K = "args_5"
        private const val STEP_INPUT_CROSS_V = "args_6"
        private const val STEP_OUTPUT_LOGITS = "output_0"
        private const val STEP_OUTPUT_SELF_K_SLICE = "output_1"
        private const val STEP_OUTPUT_SELF_V_SLICE = "output_2"

        private const val SELF_CACHE_FLOATS = NUM_LAYERS * NUM_HEADS * MAX_SEQUENCE_LENGTH * HEAD_DIM

        // 0..255 -> 0.0..1.0 conversion table (precomputed to avoid per-pixel division).
        private val BYTE_TO_UNIT_FLOAT = FloatArray(256) { it * (1f / 255f) }
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
        return try {
            val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val encoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
            }
            val decoderOptions = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(cpuThreads, null, null)
            }

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

            val encInputs = encoderModel.createInputBuffers()
            val encOutputs = encoderModel.createOutputBuffers()
            encoderImageInput = encInputs[0]
            encoderHiddenStatesOutput = encOutputs[0]

            // Init signature buffers
            initEncoderStatesInput = createNamedInputBuffer(decoderModel, INIT_SIGNATURE, INIT_INPUT_ENCODER_STATES)
            initInputIdsInput = createNamedInputBuffer(decoderModel, INIT_SIGNATURE, INIT_INPUT_IDS)
            initLogitsOutput = createNamedOutputBuffer(decoderModel, INIT_SIGNATURE, INIT_OUTPUT_LOGITS)
            initSelfKSliceOutput = createNamedOutputBuffer(decoderModel, INIT_SIGNATURE, INIT_OUTPUT_SELF_K_SLICE)
            initSelfVSliceOutput = createNamedOutputBuffer(decoderModel, INIT_SIGNATURE, INIT_OUTPUT_SELF_V_SLICE)
            initCrossKOutput = createNamedOutputBuffer(decoderModel, INIT_SIGNATURE, INIT_OUTPUT_CROSS_K)
            initCrossVOutput = createNamedOutputBuffer(decoderModel, INIT_SIGNATURE, INIT_OUTPUT_CROSS_V)

            // Step signature buffers
            stepEncoderStatesInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_ENCODER_STATES)
            stepInputIdsInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_IDS)
            stepPositionIdsInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_POSITION_IDS)
            stepSelfKCacheInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_SELF_K_CACHE)
            stepSelfVCacheInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_SELF_V_CACHE)
            stepCrossKInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_CROSS_K)
            stepCrossVInput = createNamedInputBuffer(decoderModel, STEP_SIGNATURE, STEP_INPUT_CROSS_V)
            stepLogitsOutput = createNamedOutputBuffer(decoderModel, STEP_SIGNATURE, STEP_OUTPUT_LOGITS)
            stepSelfKSliceOutput = createNamedOutputBuffer(decoderModel, STEP_SIGNATURE, STEP_OUTPUT_SELF_K_SLICE)
            stepSelfVSliceOutput = createNamedOutputBuffer(decoderModel, STEP_SIGNATURE, STEP_OUTPUT_SELF_V_SLICE)

            // Cache I/O maps once (TensorBuffers are reused; contents are overwritten each call)
            initInputs = mapOf(
                INIT_INPUT_ENCODER_STATES to initEncoderStatesInput,
                INIT_INPUT_IDS to initInputIdsInput,
            )
            initOutputs = mapOf(
                INIT_OUTPUT_LOGITS to initLogitsOutput,
                INIT_OUTPUT_SELF_K_SLICE to initSelfKSliceOutput,
                INIT_OUTPUT_SELF_V_SLICE to initSelfVSliceOutput,
                INIT_OUTPUT_CROSS_K to initCrossKOutput,
                INIT_OUTPUT_CROSS_V to initCrossVOutput,
            )

            stepInputs = mapOf(
                STEP_INPUT_ENCODER_STATES to stepEncoderStatesInput,
                STEP_INPUT_IDS to stepInputIdsInput,
                STEP_INPUT_POSITION_IDS to stepPositionIdsInput,
                STEP_INPUT_SELF_K_CACHE to stepSelfKCacheInput,
                STEP_INPUT_SELF_V_CACHE to stepSelfVCacheInput,
                STEP_INPUT_CROSS_K to stepCrossKInput,
                STEP_INPUT_CROSS_V to stepCrossVInput,
            )
            stepOutputs = mapOf(
                STEP_OUTPUT_LOGITS to stepLogitsOutput,
                STEP_OUTPUT_SELF_K_SLICE to stepSelfKSliceOutput,
                STEP_OUTPUT_SELF_V_SLICE to stepSelfVSliceOutput,
            )

            // Preprocessing scratch
            scratchBitmap = createBitmap(IMAGE_SIZE, IMAGE_SIZE)
            scratchCanvas = Canvas(scratchBitmap)
            scratchPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
                isAntiAlias = true
            }

            initialized = true
            logcat(LogPriority.INFO) { "OCR (fast) models initialized (CPU threads=$cpuThreads)" }
            true
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to initialize OCR (fast) models" }
            closeInternal()
            false
        }
    }

    private fun createNamedInputBuffer(model: CompiledModel, signature: String, name: String): TensorBuffer {
        return model.createInputBuffer(name, signature)
    }

    private fun createNamedOutputBuffer(model: CompiledModel, signature: String, name: String): TensorBuffer {
        return model.createOutputBuffer(name, signature)
    }

    override suspend fun recognizeText(image: Bitmap): String {
        ensureInitialized()

        val startTime = System.nanoTime()
        val rawText = inferenceMutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }

            val preprocessTime = measureNanoTime {
                preprocessImage(image)
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: preprocessImage took ${preprocessTime / 1_000_000} ms" }

            val encoderHiddenStates: FloatArray
            val encoderTime = measureNanoTime {
                encoderHiddenStates = runEncoder()
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: runEncoder took ${encoderTime / 1_000_000} ms" }

            val tokenCount: Int
            val decoderTime = measureNanoTime {
                tokenCount = runDecoder(encoderHiddenStates)
            }
            logcat(LogPriority.INFO) { "OCR(fast) Runtime: runDecoder took ${decoderTime / 1_000_000} ms" }

            val decodedText: String
            val decodeTokensTime = measureNanoTime {
                decodedText = decodeTokens(tokenBuffer, tokenCount)
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

    private fun preprocessImage(bitmap: Bitmap) {
        // Draw scaled bitmap into a white 224x224 canvas (aspect-preserving + centered)
        scratchCanvas.drawColor(Color.WHITE, PorterDuff.Mode.SRC)

        val sw = bitmap.width.toFloat()
        val sh = bitmap.height.toFloat()
        val scale = minOf(IMAGE_SIZE / sw, IMAGE_SIZE / sh)
        val dw = (sw * scale).toInt().coerceAtLeast(1)
        val dh = (sh * scale).toInt().coerceAtLeast(1)

        val left = (IMAGE_SIZE - dw) / 2
        val top = (IMAGE_SIZE - dh) / 2
        dstRect.set(left, top, left + dw, top + dh)

        scratchCanvas.drawBitmap(bitmap, null, dstRect, scratchPaint)

        scratchBitmap.getPixels(pixelsBuffer, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        // Convert to NCHW float values in [0..1]
        val lut = BYTE_TO_UNIT_FLOAT
        val hw = IMAGE_SIZE * IMAGE_SIZE
        for (i in 0 until hw) {
            val p = pixelsBuffer[i]
            nchwBuffer[i] = lut[(p shr 16) and 0xFF]
            nchwBuffer[hw + i] = lut[(p shr 8) and 0xFF]
            nchwBuffer[2 * hw + i] = lut[p and 0xFF]
        }

        encoderImageInput.writeFloat(nchwBuffer)
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

        selfKCache.fill(0f)
        selfVCache.fill(0f)

        // Decoder init signature (first inference)
        initEncoderStatesInput.writeFloat(encoderHiddenStates)
        scalarLong[0] = START_TOKEN_ID.toLong()
        initInputIdsInput.writeLong(scalarLong)

        decoderModel.run(initInputs, initOutputs, INIT_SIGNATURE)

        val logits0 = initLogitsOutput.readFloat()
        val initSelfKSlice = initSelfKSliceOutput.readFloat()
        val initSelfVSlice = initSelfVSliceOutput.readFloat()
        val crossK = initCrossKOutput.readFloat()
        val crossV = initCrossVOutput.readFloat()

        insertKvSlice(selfKCache, initSelfKSlice, seqIndex = 0)
        insertKvSlice(selfVCache, initSelfVSlice, seqIndex = 0)

        // Cache cross-attention tensors for the step signature
        if (crossK.size == crossKCache.size) {
            stepCrossKInput.writeFloat(crossK)
        } else {
            System.arraycopy(crossK, 0, crossKCache, 0, minOf(crossK.size, crossKCache.size))
            stepCrossKInput.writeFloat(crossKCache)
        }
        if (crossV.size == crossVCache.size) {
            stepCrossVInput.writeFloat(crossV)
        } else {
            System.arraycopy(crossV, 0, crossVCache, 0, minOf(crossV.size, crossVCache.size))
            stepCrossVInput.writeFloat(crossVCache)
        }

        // Encoder hidden states are constant for all decoder steps
        stepEncoderStatesInput.writeFloat(encoderHiddenStates)

        // First generated token from init logits
        var nextToken = findMaxToken(logits0)
        if (nextToken == END_TOKEN_ID) {
            return tokenCount
        }
        tokenIds[tokenCount++] = nextToken

        var cacheLen = 1 // start token already cached at seqIndex 0
        var currentToken = nextToken

        // Decoder step signature (subsequent inferences w/ KV cache for speed)
        while (tokenCount < MAX_SEQUENCE_LENGTH && cacheLen < MAX_SEQUENCE_LENGTH) {
            scalarLong[0] = currentToken.toLong()
            stepInputIdsInput.writeLong(scalarLong)

            scalarPosLong[0] = (cacheLen + 1).toLong()
            stepPositionIdsInput.writeLong(scalarPosLong)

            stepSelfKCacheInput.writeFloat(selfKCache)
            stepSelfVCacheInput.writeFloat(selfVCache)

            decoderModel.run(stepInputs, stepOutputs, STEP_SIGNATURE)

            val stepSelfKSlice = stepSelfKSliceOutput.readFloat()
            val stepSelfVSlice = stepSelfVSliceOutput.readFloat()

            insertKvSlice(selfKCache, stepSelfKSlice, seqIndex = cacheLen)
            insertKvSlice(selfVCache, stepSelfVSlice, seqIndex = cacheLen)
            cacheLen++

            val logits = stepLogitsOutput.readFloat()
            nextToken = findMaxToken(logits)
            if (nextToken == END_TOKEN_ID) {
                break
            }

            tokenIds[tokenCount++] = nextToken
            currentToken = nextToken
        }

        return tokenCount
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun findMaxToken(logits: FloatArray): Int {
        var maxLogit = Float.NEGATIVE_INFINITY
        var maxToken = 0

        val limit = minOf(VOCAB_SIZE, logits.size)
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
    private fun insertKvSlice(fullCache: FloatArray, slice: FloatArray, seqIndex: Int) {
        if (seqIndex !in 0 until MAX_SEQUENCE_LENGTH) return

        var sliceOffset = 0
        for (layer in 0 until NUM_LAYERS) {
            for (head in 0 until NUM_HEADS) {
                val dstBase = ((layer * NUM_HEADS + head) * MAX_SEQUENCE_LENGTH + seqIndex) * HEAD_DIM
                System.arraycopy(slice, sliceOffset, fullCache, dstBase, HEAD_DIM)
                sliceOffset += HEAD_DIM
            }
        }
    }

    private fun decodeTokens(tokenIds: IntArray, tokenCount: Int): String {
        val text = textBuilder
        text.setLength(0)

        for (index in 0 until tokenCount) {
            val tokenId = tokenIds[index]
            if (tokenId < SPECIAL_TOKEN_THRESHOLD) continue
            if (tokenId in vocabFast.indices) {
                text.append(vocabFast[tokenId])
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

            if (::initEncoderStatesInput.isInitialized) initEncoderStatesInput.close()
            if (::initInputIdsInput.isInitialized) initInputIdsInput.close()
            if (::initLogitsOutput.isInitialized) initLogitsOutput.close()
            if (::initSelfKSliceOutput.isInitialized) initSelfKSliceOutput.close()
            if (::initSelfVSliceOutput.isInitialized) initSelfVSliceOutput.close()
            if (::initCrossKOutput.isInitialized) initCrossKOutput.close()
            if (::initCrossVOutput.isInitialized) initCrossVOutput.close()

            if (::stepEncoderStatesInput.isInitialized) stepEncoderStatesInput.close()
            if (::stepInputIdsInput.isInitialized) stepInputIdsInput.close()
            if (::stepPositionIdsInput.isInitialized) stepPositionIdsInput.close()
            if (::stepSelfKCacheInput.isInitialized) stepSelfKCacheInput.close()
            if (::stepSelfVCacheInput.isInitialized) stepSelfVCacheInput.close()
            if (::stepCrossKInput.isInitialized) stepCrossKInput.close()
            if (::stepCrossVInput.isInitialized) stepCrossVInput.close()
            if (::stepLogitsOutput.isInitialized) stepLogitsOutput.close()
            if (::stepSelfKSliceOutput.isInitialized) stepSelfKSliceOutput.close()
            if (::stepSelfVSliceOutput.isInitialized) stepSelfVSliceOutput.close()

            if (::encoderModel.isInitialized) encoderModel.close()
            if (::decoderModel.isInitialized) decoderModel.close()

            if (::scratchBitmap.isInitialized && !scratchBitmap.isRecycled) {
                scratchBitmap.recycle()
            }

            logcat(LogPriority.INFO) { "OCR (fast) models closed successfully" }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error closing OCR (fast) models" }
        }
    }
}
