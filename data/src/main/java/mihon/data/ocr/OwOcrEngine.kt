package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.domain.ocr.exception.OcrException
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrRegion
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.service.OcrPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
internal data class OwOcrBoundingBox(
    val center_x: Float,
    val center_y: Float,
    val width: Float,
    val height: Float,
    val rotation_z: Float? = null,
)

@Serializable
internal data class OwOcrWord(
    val text: String,
    val bounding_box: OwOcrBoundingBox,
    val separator: String? = null,
)

@Serializable
internal data class OwOcrLine(
    val bounding_box: OwOcrBoundingBox,
    val words: List<OwOcrWord> = emptyList(),
    val text: String? = null,
    val writing_direction: String? = null,
)

@Serializable
internal data class OwOcrParagraph(
    val bounding_box: OwOcrBoundingBox,
    val lines: List<OwOcrLine> = emptyList(),
    val writing_direction: String? = null,
)

@Serializable
internal data class OwOcrResult(
    val paragraphs: List<OwOcrParagraph> = emptyList(),
)

/**
 * OCR engine that communicates with a self-hosted OwOCR WebSocket endpoint.
 * Sends cropped bitmap images and receives recognized text.
 */
internal class OwOcrEngine(context: Context) : OcrEngine {
    private val ocrPreferences = Injekt.get<OcrPreferences>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private suspend fun queryServer(image: Bitmap): String = withContext(Dispatchers.IO) {
        val address = ocrPreferences.owocrAddress().get().trim()
        if (address.isBlank()) {
            throw IOException("OwOCR address is blank. Please configure it in settings.")
        }

        val stream = ByteArrayOutputStream()
        val success = image.compress(Bitmap.CompressFormat.PNG, 100, stream)
        if (!success) {
            throw IOException("Failed to compress bitmap to PNG")
        }
        val imageBytes = stream.toByteArray()

        val request = Request.Builder()
            .url(address)
            .build()

        val deferredResult = CompletableDeferred<String>()
        var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            private var accepted = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(imageBytes.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The first message is typically 'True' or 'False' indicating acceptance
                if (!accepted) {
                    if (text == "True") {
                        accepted = true
                        // Wait for the actual OCR result
                    } else {
                        deferredResult.completeExceptionally(IOException("Server rejected the image with: $text"))
                        webSocket.close(1000, "Rejected")
                    }
                } else {
                    deferredResult.complete(text)
                    webSocket.close(1000, "Done")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                deferredResult.completeExceptionally(t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferredResult.isCompleted) {
                    deferredResult.completeExceptionally(IOException("Connection closed: $reason ($code)"))
                }
            }
        }

        webSocket = client.newWebSocket(request, listener)

        try {
            deferredResult.await()
        } catch (t: Throwable) {
            webSocket.cancel()
            throw t
        }
    }

    override suspend fun recognizeText(image: Bitmap): String {
        val rawResponse = queryServer(image)
        val trimmed = rawResponse.trim()
        val isJson = trimmed.startsWith("{") && trimmed.endsWith("}")

        if (isJson) {
            try {
                val ocrResult = jsonParser.decodeFromString<OwOcrResult>(trimmed)
                val textPostprocessor = TextPostprocessor()
                val combinedText = ocrResult.paragraphs.joinToString("\n\n") { paragraph ->
                    paragraph.lines.joinToString("\n") { line ->
                        if (!line.text.isNullOrBlank()) {
                            line.text
                        } else {
                            line.words.joinToString("") { word ->
                                word.text + (word.separator ?: " ")
                            }.trim()
                        }
                    }
                }
                return textPostprocessor.postprocess(combinedText)
            } catch (e: Exception) {
                // Fallback to raw response on parse failure
            }
        }

        val textPostprocessor = TextPostprocessor()
        return textPostprocessor.postprocess(rawResponse)
    }

    suspend fun recognizePage(image: Bitmap): List<OcrRegion> {
        val rawResponse = queryServer(image)
        val trimmed = rawResponse.trim()
        val isJson = trimmed.startsWith("{") && trimmed.endsWith("}")

        if (!isJson) {
            throw OcrException.DetectionUnavailable()
        }

        val ocrResult = try {
            jsonParser.decodeFromString<OwOcrResult>(trimmed)
        } catch (e: Exception) {
            throw OcrException.DetectionUnavailable(e)
        }

        val textPostprocessor = TextPostprocessor()
        val regions = ocrResult.paragraphs.mapIndexedNotNull { index, paragraph ->
            val textBuilder = StringBuilder()
            paragraph.lines.forEachIndexed { lineIdx, line ->
                if (lineIdx > 0) textBuilder.append("\n")
                if (!line.text.isNullOrBlank()) {
                    textBuilder.append(line.text)
                } else {
                    line.words.forEach { word ->
                        textBuilder.append(word.text)
                        textBuilder.append(word.separator ?: " ")
                    }
                }
            }
            val paragraphText = textBuilder.toString().trim()
            if (paragraphText.isBlank()) return@mapIndexedNotNull null

            val bbox = paragraph.bounding_box
            val left = (bbox.center_x - bbox.width / 2f).coerceIn(0f, 1f)
            val top = (bbox.center_y - bbox.height / 2f).coerceIn(0f, 1f)
            val right = (bbox.center_x + bbox.width / 2f).coerceIn(0f, 1f)
            val bottom = (bbox.center_y + bbox.height / 2f).coerceIn(0f, 1f)

            val boundingBox = OcrBoundingBox(left = left, top = top, right = right, bottom = bottom)
            if (!boundingBox.isValid()) return@mapIndexedNotNull null

            val isVertical = paragraph.writing_direction == "TOP_TO_BOTTOM" ||
                paragraph.lines.any { it.writing_direction == "TOP_TO_BOTTOM" }

            val orientation = if (isVertical) OcrTextOrientation.Vertical else OcrTextOrientation.Horizontal

            OcrRegion(
                order = index,
                text = textPostprocessor.postprocess(paragraphText),
                boundingBox = boundingBox,
                textOrientation = orientation,
            )
        }

        if (regions.isEmpty()) {
            throw OcrException.DetectionUnavailable()
        }

        return regions
    }

    override fun close() {
        // OkHttpClient resources are automatically pooled and closed, no-op
    }
}
