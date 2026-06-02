package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import mihon.domain.ocr.service.OcrPreferences
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
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

    override fun close() {
        // OkHttpClient resources are automatically pooled and closed, no-op
    }
}
